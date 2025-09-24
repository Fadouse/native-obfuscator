package by.radioegor146.javaobf;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.util.*;

/**
 * Java bytecode control-flow flattener (V2)
 * - Real multi-block flattening with micro-splits (stack snapshot to locals)
 * - Per-block distinct state encoders (4 variants), per-edge light noise
 * - Robust try/catch remap; preserves local variable table
 * - Skips <init>/<clinit>, forbids JSR/RET
 * - Designed for ClassWriter(COMPUTE_FRAMES|COMPUTE_MAXS)
 * Notes:
 *  - We keep a safe fallback wrapper-style flattening when multi-block fails.
 *  - Micro-split: we snapshot the operand stack (at chosen cut points) into
 *    reserved locals (spill area), then restore at the entry of the next block.
 */
public final class JavaControlFlowFlattener implements Opcodes {

    private JavaControlFlowFlattener() {}

    // ==== Public API =========================================================

    public static boolean canProcess(MethodNode mn) {
        if (mn == null) return false;
        if ((mn.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) return false;
        if (mn.instructions == null || mn.instructions.size() == 0) return false;

        // CRITICAL: Never process constructors and static initializers
        // These methods have special JVM semantics that must be preserved
        if ("<init>".equals(mn.name) || "<clinit>".equals(mn.name)) {
            return false;
        }

        // Additional safety: check for other special method patterns
        if (mn.name != null && mn.name.startsWith("<") && mn.name.endsWith(">")) {
            // Skip any method with angle brackets (special JVM methods)
            return false;
        }

        // Forbid legacy JSR/RET
        for (AbstractInsnNode p = mn.instructions.getFirst(); p != null; p = p.getNext()) {
            final int op = p.getOpcode();
            if (op == JSR || op == RET) return false;
        }
        return true;
    }

    public static void flatten(MethodNode mn, String methodId, JavaObfuscationConfig.Strength strength) {
        Objects.requireNonNull(mn, "mn");
        Objects.requireNonNull(strength, "strength");
        if (!canProcess(mn)) return;

        // Store original instructions in case we need to restore
        InsnList originalInsns = new InsnList();
        IdentityHashMap<LabelNode, LabelNode> originalLabelMap = new IdentityHashMap<>();
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode) {
                originalLabelMap.put((LabelNode) insn, new LabelNode());
            }
        }
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            originalInsns.add(insn.clone(originalLabelMap));
        }
        int originalMaxStack = mn.maxStack;
        int originalMaxLocals = mn.maxLocals;

        try {
            new FlattenerImpl(mn, methodId, strength).run();

            // Validate the result
            if (mn.instructions.size() == 0) {
                throw new RuntimeException("Flattening produced empty method");
            }
        } catch (Throwable t) {
            // Try fallback
            try {
                // Restore original state
                mn.instructions = originalInsns;
                mn.maxStack = originalMaxStack;
                mn.maxLocals = originalMaxLocals;

                System.out.println("ControlFlowFlattener: multi-block flattening failed, applying fallback: " + t);
                WrapperFallback.run(mn, methodId, strength);
            } catch (Throwable t2) {
                // Even fallback failed, restore original
                mn.instructions = originalInsns;
                mn.maxStack = originalMaxStack;
                mn.maxLocals = originalMaxLocals;
                System.err.println("ControlFlowFlattener: all flattening attempts failed for " + methodId);
            }
        }
    }

    // ==== Internal: Advanced Flattener ======================================

    private static final class FlattenerImpl {

        // Tunables
        private final int SPILL_LIMIT;      // max stack items we snapshot at a micro-split
        private final int NOISE_LEVEL;      // how many small no-op noises on edges

        private final MethodNode mn;
        private final Random rnd;

        // Original → cloned body and label map
        private final InsnList original;
        private final InsnList body = new InsnList();
        private final IdentityHashMap<LabelNode, LabelNode> labelMap = new IdentityHashMap<>();
        private final ArrayList<AbstractInsnNode> cloned = new ArrayList<>();

        // Analysis
        private FrameLike[] frames; // light wrapper around BasicValue frames (before each insn)

        // Leaders / blocks
        private final ArrayList<Integer> leaders = new ArrayList<>();
        private final ArrayList<Block> blocks = new ArrayList<>();
        private final IdentityHashMap<LabelNode, Integer> labelToIndex = new IdentityHashMap<>();
        private final IdentityHashMap<LabelNode, Integer> blockOfLabel = new IdentityHashMap<>();
        private int[] indexToBlock; // fast index→blockId map

        // Per-block encoding
        private FormulaParams[] params;
        private int[] keys;                 // encoded key for each block.id
        private LabelNode[] entryLabels;    // dispatcher entry for each block
        private LabelNode[] afterLabels;    // label right after each block’s original tail
        private LabelNode[] handlerEntryStubs; // bridge labels for exception handlers
        private final List<TryCatchBlockNode> origTcbs;
        private final List<LocalVariableNode> origLvt;

        private boolean[] isHandlerEntry;

        private final String owner;

        // Spill locals (single contiguous area shared across all entries)
        private int spillBase = -1;

        // Emitted label mapping: cloned label -> emitted label
        private final IdentityHashMap<LabelNode, LabelNode> emittedLabel = new IdentityHashMap<>();

        private int stateLocal;

        FlattenerImpl(MethodNode mn, String methodId, JavaObfuscationConfig.Strength strength) {
            this.mn = mn;
            String methodId1 = (methodId != null ? methodId : (mn.name + mn.desc));
            long seed = 0x9E3779B97F4A7C15L
                    ^ (long) methodId1.hashCode()
                    ^ (((long) mn.access) << 17)
                    ^ (System.identityHashCode(mn) * 0x632BE59BD9B4E019L);
            this.rnd = new Random(seed);
            this.original = mn.instructions;
            this.owner = (methodId != null && methodId.contains("#"))
                    ? methodId.substring(0, methodId.indexOf('#'))
                    : guessOwnerFromLvtOrNull();
            // tune by strength
            this.origTcbs = (mn.tryCatchBlocks == null)
                    ? Collections.emptyList()
                    : new ArrayList<>(mn.tryCatchBlocks);  // 浅拷贝节点本身即可，后面会用 labelMap/emittedLabel 重映射

            this.origLvt  = (mn.localVariables == null)
                    ? Collections.emptyList()
                    : new ArrayList<>(mn.localVariables);

            switch (strength) {
                case HIGH:
                    SPILL_LIMIT = 4;
                    NOISE_LEVEL = 3;
                    break;
                case MEDIUM:
                    SPILL_LIMIT = 2;
                    NOISE_LEVEL = 2;
                    break;
                default:
                    SPILL_LIMIT = 0;
                    NOISE_LEVEL = 1;
            }
        }

        private String guessOwnerFromLvtOrNull() {
            if ((mn.access & ACC_STATIC) == 0 && mn.localVariables != null) {
                for (LocalVariableNode lv : mn.localVariables) {
                    if (lv.index == 0 && "this".equals(lv.name) &&
                            lv.desc != null && lv.desc.startsWith("L") && lv.desc.endsWith(";")) {
                        return Type.getType(lv.desc).getInternalName();
                    }
                }
            }
            return null; // 让 makeInitialFrameNode 用 TOP 兜底，而不是假定是 java/lang/Object
        }

        void run() throws AnalyzerException {
            cloneBody();
            analyzeFrames();
            computeLeadersWithMicroSplits();
            buildBlocksAndMaps();

            for (Block b : blocks) {
                FrameLike f = frameForBlock(b.id);
                if (f == null) continue;

                if (f.stackSize > 0 && !isHandlerBlock(b.id)) {
                    // 允许微切分：入口非空但可被 spill/restore
                    boolean spillable = (SPILL_LIMIT > 0) && f.allSpillable() && f.stackSize <= SPILL_LIMIT;
                    if (!spillable) {
                        throw new AnalyzerException(null,
                                "entry stack not spillable at block " + b.id + " (size=" + f.stackSize + ")");
                    }
                }
            }

            allocateStateAndParams();
            allocateSpillArea();
            seedEmittedLabels();
            emitFlattened();
            insertGlobalLocalPreinits();
            rebuildTryCatch();
            rebuildLocalVariables();

            // Margins for dispatcher/spill ops; frames will be recomputed by ClassWriter
            mn.maxStack = Math.max(mn.maxStack, 8);
        }

        // --- Clone body and labels ---
        private void cloneBody() {
            for (AbstractInsnNode p = original.getFirst(); p != null; p = p.getNext()) {
                if (p instanceof LabelNode) {
                    labelMap.put((LabelNode) p, new LabelNode());
                }
            }
            for (AbstractInsnNode p = original.getFirst(); p != null; p = p.getNext()) {
                AbstractInsnNode c = p.clone(labelMap);
                body.add(c);
                cloned.add(c);
                if (c instanceof LabelNode) {
                    labelToIndex.put((LabelNode) c, cloned.size() - 1);
                }
            }
        }

        // --- Frame analysis (before-insn) and lightweight wrapper ---
        private void analyzeFrames() throws AnalyzerException {
            Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicVerifier());
            // owner not used by BasicInterpreter
            @SuppressWarnings("unchecked")
            Frame<BasicValue>[] fs = analyzer.analyze(owner, methodNodeCopyOf(body));

            frames = new FrameLike[cloned.size()];

            for (int i = 0; i < cloned.size(); i++) {
                // Null frames indicate unreachable code - this is normal, not an error
                if (fs[i] == null) {
                    frames[i] = null;  // Keep it null - we'll handle this later
                } else {
                    frames[i] = FrameLike.from(fs[i]);
                }
            }
        }

        // Inside FlattenerImpl
        private int computeArgSlots() {
            int slots = ((mn.access & ACC_STATIC) == 0) ? 1 : 0;
            for (Type t : Type.getArgumentTypes(mn.desc)) {
                slots += (t == Type.LONG_TYPE || t == Type.DOUBLE_TYPE) ? 2 : 1;
            }
            return slots;
        }

        private MethodNode methodNodeCopyOf(InsnList il) {
            MethodNode tmp = new MethodNode(mn.access, mn.name, mn.desc, mn.signature, toArray(mn.exceptions));
            // try/catch are okay for analysis; we pass cloned body
            if (mn.tryCatchBlocks != null) {
                for (TryCatchBlockNode t : mn.tryCatchBlocks) {
                    tmp.tryCatchBlocks.add(new TryCatchBlockNode(
                            labelMap.getOrDefault(t.start, t.start),
                            labelMap.getOrDefault(t.end, t.end),
                            labelMap.getOrDefault(t.handler, t.handler),
                            t.type));
                }
            }
            for (int i = 0; i < il.size(); i++) tmp.instructions.add(il.get(i));

            // >>> 关键修复：为 Analyzer 提供“足够大”的 maxLocals / maxStack
            int argSlots = ((mn.access & ACC_STATIC) == 0 ? 1 : 0);
            for (Type t : Type.getArgumentTypes(mn.desc)) {
                int s = (t == Type.LONG_TYPE || t == Type.DOUBLE_TYPE) ? 2 : 1;
                argSlots += s;
            }
            int scanMax = scanMaxLocalIndex(il); // 见下方新增函数
            // locals 至少覆盖：原方法 maxLocals / 参数槽位 / 实际访问到的最高槽位
            tmp.maxLocals = Math.max(Math.max(mn.maxLocals, argSlots), scanMax + 1);
            // stack 给一个充裕上限，避免 “Insufficient maximum stack size”
            tmp.maxStack  = Math.max(mn.maxStack, 64);

            return tmp;
        }

        private static int scanMaxLocalIndex(InsnList il) {
            int max = -1;
            for (int i = 0; i < il.size(); i++) {
                AbstractInsnNode in = il.get(i);
                if (in instanceof VarInsnNode) {
                    VarInsnNode v = (VarInsnNode) in;
                    int op = v.getOpcode();
                    int sz = (op == LLOAD || op == DLOAD || op == LSTORE || op == DSTORE) ? 2 : 1;
                    max = Math.max(max, v.var + sz - 1);
                } else if (in instanceof IincInsnNode) {
                    IincInsnNode inc = (IincInsnNode) in;
                    max = Math.max(max, inc.var);
                }
            }
            return max;
        }

        private void seedEmittedLabels() {
            for (AbstractInsnNode in : cloned) {
                if (in instanceof LabelNode) {
                    emittedLabel.computeIfAbsent((LabelNode) in, k -> new LabelNode());
                }
            }
        }

        private static String[] toArray(List<String> xs) {
            if (xs == null || xs.isEmpty()) return null;
            return xs.toArray(new String[0]);
        }

        // --- Leaders + Micro-splits ---
        private void computeLeadersWithMicroSplits() {
            final int n = cloned.size();
            if (n == 0) return;

            boolean[] isLeader = new boolean[n];
            isLeader[0] = true;

            // Natural control-flow leaders
            // Natural control-flow leaders
            for (int i = 0; i < n; i++) {
                AbstractInsnNode insn = cloned.get(i);

                if (insn instanceof JumpInsnNode) {
                    JumpInsnNode j = (JumpInsnNode) insn;
                    Integer ti = labelToIndex.get(j.label);
                    if (ti != null) isLeader[ti] = true;
                    // **无论是否有fall-through，跳转后都截断**
                    if (i + 1 < n) isLeader[i + 1] = true;
                } else if (insn instanceof LookupSwitchInsnNode) {
                    LookupSwitchInsnNode sw = (LookupSwitchInsnNode) insn;
                    Integer di = labelToIndex.get(sw.dflt);
                    if (di != null) isLeader[di] = true;
                    for (LabelNode l : sw.labels) {
                        Integer li = labelToIndex.get(l);
                        if (li != null) isLeader[li] = true;
                    }
                    // **switch后必截断**
                    if (i + 1 < n) isLeader[i + 1] = true;
                } else if (insn instanceof TableSwitchInsnNode) {
                    TableSwitchInsnNode sw = (TableSwitchInsnNode) insn;
                    Integer di = labelToIndex.get(sw.dflt);
                    if (di != null) isLeader[di] = true;
                    for (LabelNode l : sw.labels) {
                        Integer li = labelToIndex.get(l);
                        if (li != null) isLeader[li] = true;
                    }
                    // **switch后必截断**
                    if (i + 1 < n) isLeader[i + 1] = true;
                } else if (isTerminator(insn.getOpcode())) {
                    if (i + 1 < n) isLeader[i + 1] = true;
                }
            }


            // Micro-splits: only process reachable code (non-null frames)
            for (int i = 1; i < n; i++) {
                if (isLeader[i]) continue;

                // Skip unreachable code
                if (frames[i] == null) continue;

                AbstractInsnNode insnI = cloned.get(i);
                int opI = insnI.getOpcode();

                // API & field access boundary splits
                boolean isBoundary =
                        (opI >= INVOKEVIRTUAL && opI <= INVOKEDYNAMIC) ||
                                opI == GETFIELD || opI == PUTFIELD || opI == GETSTATIC || opI == PUTSTATIC;

                if (isBoundary) {
                    FrameLike fI = frames[i];
                    if (fI.stackSize == 0 || (SPILL_LIMIT > 0 && fI.stackSize <= SPILL_LIMIT && fI.allSpillable())) {
                        isLeader[i] = true;
                        continue;
                    }
                }

                // Regular safe splits
                FrameLike f = frames[i];
                int sz = f.stackSize;
                if (sz == 0) {
                    isLeader[i] = true;
                    continue;
                }
                if (SPILL_LIMIT > 0 && sz <= SPILL_LIMIT && f.allSpillable()) {
                    isLeader[i] = true;
                    continue;
                }

                // Random micro-splits
                if (SPILL_LIMIT > 0 && sz > 0 && sz <= SPILL_LIMIT && f.allSpillable()) {
                    if ((rnd.nextInt(100)) < 35) {
                        isLeader[i] = true;
                    }
                }
            }

            for (int i = 0; i < n; i++) if (isLeader[i]) leaders.add(i);
        }

        // --- Build blocks, map labels to blocks, etc. ---
        private void buildBlocksAndMaps() {
            final int n0 = cloned.size();

            // Filter out leaders that point to unreachable code
            ArrayList<Integer> reachableLeaders = new ArrayList<>();
            for (Integer leader : leaders) {
                // Keep the first leader (entry point) even if frame is null
                if (leader == 0 || frames[leader] != null) {
                    reachableLeaders.add(leader);
                }
            }

            // If all leaders except entry are unreachable, keep at least the entry
            if (reachableLeaders.isEmpty() && !leaders.isEmpty()) {
                reachableLeaders.add(leaders.get(0));
            }

            // Replace leaders with reachable ones
            leaders.clear();
            leaders.addAll(reachableLeaders);

            // Build blocks from reachable leaders
            for (int bi = 0; bi < leaders.size(); bi++) {
                int s = leaders.get(bi);
                int e = (bi + 1 < leaders.size() ? leaders.get(bi + 1) : n0) - 1;
                if (e < s) e = s;
                blocks.add(new Block(bi, s, e));
            }

            boolean mutated = false;

            // Ensure every starting position has a label
            for (Block b : blocks) {
                AbstractInsnNode at = cloned.get(b.startIndex);
                LabelNode got;
                if (at instanceof LabelNode) {
                    got = (LabelNode) at;
                } else {
                    got = new LabelNode();
                    body.insertBefore(at, got);
                    cloned.add(b.startIndex, got);
                    mutated = true;

                    // Rebuild labelToIndex
                    labelToIndex.clear();
                    for (int i = 0; i < cloned.size(); i++) {
                        if (cloned.get(i) instanceof LabelNode) {
                            labelToIndex.put((LabelNode) cloned.get(i), i);
                        }
                    }
                    // Adjust indices
                    for (int i = b.id + 1; i < blocks.size(); i++) {
                        blocks.get(i).startIndex++;
                        blocks.get(i).endIndex++;
                    }
                    b.endIndex++;
                }
                b.entryLabel = got;
                blockOfLabel.put(got, b.id);
            }

            // Re-analyze if mutated
            if (mutated) {
                try {
                    analyzeFrames();
                } catch (AnalyzerException ex) {
                    // If re-analysis fails, continue with current frames
                    // The fallback will handle it
                }
                int last = cloned.size() - 1;
                for (Block b : blocks) {
                    if (b.endIndex > last) b.endIndex = last;
                }
            }

            // Build index→blockId mapping
            indexToBlock = new int[cloned.size()];
            Arrays.fill(indexToBlock, -1);
            for (Block b : blocks) {
                int s = Math.max(0, Math.min(b.startIndex, cloned.size() - 1));
                int e = Math.max(0, Math.min(b.endIndex, cloned.size() - 1));
                for (int i = s; i <= e; i++) {
                    indexToBlock[i] = b.id;
                }
            }

            // Mark handler entries
            List<TryCatchBlockNode> oldTcbs = (origTcbs == null) ? Collections.emptyList() : origTcbs;
            isHandlerEntry = new boolean[blocks.size()];
            for (TryCatchBlockNode tcb : oldTcbs) {
                LabelNode handlerCl = labelMap.getOrDefault(tcb.handler, tcb.handler);
                Integer hIdx = labelToIndex.get(handlerCl);
                if (hIdx != null) {
                    int bid = indexToBlock[hIdx];
                    if (bid >= 0 && bid < isHandlerEntry.length) {
                        isHandlerEntry[bid] = true;
                    }
                }
            }

        }


        // --- Allocate state variable and per-block encoder params ---
        private void allocateStateAndParams() {
            // State local at end
            stateLocal = mn.maxLocals;
            mn.maxLocals = stateLocal + 1;

            int nBlocks = blocks.size();
            params = new FormulaParams[nBlocks];
            keys = new int[nBlocks];
            entryLabels = new LabelNode[nBlocks];
            afterLabels = new LabelNode[nBlocks];
            handlerEntryStubs = new LabelNode[nBlocks];

            HashSet<Integer> used = new HashSet<>(nBlocks * 2);
            for (Block b : blocks) {
                FormulaParams p;
                int key;
                // Ensure key uniqueness across blocks (avoid switch collision)
                do {
                    p = FormulaParams.random(rnd);
                    key = encodeWithFormula(b.id, p);
                } while (!used.add(key));
                params[b.id] = p;
                keys[b.id] = key;
                entryLabels[b.id] = new LabelNode();
                afterLabels[b.id] = new LabelNode();
                if (isHandlerBlock(b.id)) {
                    handlerEntryStubs[b.id] = new LabelNode();
                }
            }
        }

        // --- Compute maximum spill locals needed across all entries ---
        private void allocateSpillArea() {
            int maxSlots = 0;
            for (Block b : blocks) {
                FrameLike f = frameForBlock(b.id);
                // Skip blocks with null frames (unreachable code)
                if (f == null) continue;
                maxSlots = Math.max(maxSlots, f.localSlotsForSpill());
            }
            int spillSlots = maxSlots;
            spillBase = mn.maxLocals;
            mn.maxLocals += spillSlots;
        }
        // Update makeInitialFrameNode to declare ALL locals that will be used
        private FrameNode makeInitialFrameNode() {
            final boolean isStatic = (mn.access & ACC_STATIC) != 0;
            final ArrayList<Object> locals = new ArrayList<>();

            // 1. Add 'this' for instance methods (using TOP as placeholder - ASM will infer)
            if (!isStatic) {
                if (owner != null) {
                    System.out.println("ControlFlowFlattener: assuming owner " + owner + " for method " + mn.name + mn.desc);
                    locals.add(owner);
                } else {
                    System.out.println("ControlFlowFlattener: unknown owner for method " + mn.name + mn.desc + ", using TOP");
                    locals.add(Opcodes.TOP); // 实在拿不到，就让 ASM 推断
                }
            }


            // 2. Add method parameters
            for (Type t : Type.getArgumentTypes(mn.desc)) {
                switch (t.getSort()) {
                    case Type.BOOLEAN:
                    case Type.BYTE:
                    case Type.CHAR:
                    case Type.SHORT:
                    case Type.INT:
                        locals.add(Opcodes.INTEGER);
                        break;
                    case Type.FLOAT:
                        locals.add(Opcodes.FLOAT);
                        break;
                    case Type.LONG:
                        locals.add(Opcodes.LONG);
                        locals.add(Opcodes.TOP); // Second slot for long
                        break;
                    case Type.DOUBLE:
                        locals.add(Opcodes.DOUBLE);
                        locals.add(Opcodes.TOP); // Second slot for double
                        break;
                    case Type.ARRAY:
                    case Type.OBJECT:
                        locals.add(Opcodes.TOP); // Let ASM infer
                        break;
                    default:
                        locals.add(Opcodes.TOP);
                }
            }

            // 3. CRITICAL FIX: Declare ALL other locals up to maxLocals
            // This includes existing locals, stateLocal, and spill area
            // Fill remaining slots with TOP (uninitialized)
            while (locals.size() < mn.maxLocals) {
                locals.add(Opcodes.TOP);
            }

            return new FrameNode(
                    Opcodes.F_FULL,
                    locals.size(), locals.toArray(),
                    0, new Object[0]
            );
        }

        // Collect locals that this block LOADs before any STORE in this block.
        private Map<Integer, Integer> collectBlockLocalNeeds(Block b) {
            final int args = computeArgSlots();
            final boolean[] seenStore = new boolean[mn.maxLocals];
            final HashMap<Integer, Integer> needs = new HashMap<>();

            for (int i = b.startIndex; i <= b.endIndex; i++) {
                AbstractInsnNode in = cloned.get(i);
                if (in instanceof VarInsnNode) {
                    VarInsnNode v = (VarInsnNode) in;
                    int op = v.getOpcode(), idx = v.var;

                    // NEW: never touch our synthetic locals
                    if (idx >= stateLocal) continue;

                    switch (op) {
                        case ISTORE: case FSTORE: case LSTORE: case DSTORE: case ASTORE:
                            if (idx >= 0 && idx < mn.maxLocals) seenStore[idx] = true;
                            break;
                        case ILOAD: case FLOAD: case LLOAD: case DLOAD: case ALOAD:
                            if (idx >= args && !seenStore[idx] && !needs.containsKey(idx)) {
                                needs.put(idx, op);
                            }
                            break;
                    }
                } else if (in instanceof IincInsnNode) {
                    int idx = ((IincInsnNode) in).var;
                    if (idx >= 0 && idx < stateLocal) seenStore[idx] = true; // treat iinc as write
                }
            }
            return needs;
        }


        private void emitPreinitsForBlock(Map<Integer, Integer> needs, InsnList out) {
            for (Map.Entry<Integer, Integer> e : needs.entrySet()) {
                int idx = e.getKey(), kind = e.getValue();
                switch (kind) {
                    case ILOAD:   pushConst(out, 0);          out.add(new VarInsnNode(ISTORE, idx)); break;
                    case FLOAD:   out.add(new InsnNode(FCONST_0)); out.add(new VarInsnNode(FSTORE, idx)); break;
                    case LLOAD:   out.add(new InsnNode(LCONST_0)); out.add(new VarInsnNode(LSTORE, idx)); break;
                    case DLOAD:   out.add(new InsnNode(DCONST_0)); out.add(new VarInsnNode(DSTORE, idx)); break;
                    case ALOAD:   out.add(new InsnNode(ACONST_NULL)); out.add(new VarInsnNode(ASTORE, idx)); break;
                }
            }
        }
        //emitFlattened with proper frame placement and single after-label per block
        private void emitFlattened() {
            mn.instructions   = new InsnList();
            mn.tryCatchBlocks = new ArrayList<>();
            mn.localVariables = new ArrayList<>();

            // Dispatcher labels
            LabelNode l_START    = new LabelNode();
            LabelNode l_DISPATCH = new LabelNode();
            LabelNode l_DEFAULT  = new LabelNode();

            // Prologue: initial frame & state init
            mn.instructions.add(l_START);
            mn.instructions.add(makeInitialFrameNode());
            pushConst(mn.instructions, keys[0]);
            mn.instructions.add(new VarInsnNode(ISTORE, stateLocal));

            // Main dispatcher
            mn.instructions.add(l_DISPATCH);
            mn.instructions.add(new VarInsnNode(ILOAD, stateLocal));
            int[] sorted = Arrays.stream(keys).sorted().toArray();
            LabelNode[] sortedEntries = new LabelNode[sorted.length];
            for (int i = 0; i < sorted.length; i++) {
                int k = sorted[i];
                for (int j = 0; j < keys.length; j++) {
                    if (keys[j] == k) { sortedEntries[i] = entryLabels[j]; break; }
                }
            }
            mn.instructions.add(new LookupSwitchInsnNode(l_DEFAULT, sorted, sortedEntries));

            // Default: recover to block 0
            mn.instructions.add(l_DEFAULT);
            emitSetStateDisguised(mn.instructions, stateLocal, keys[0]);
            injectNoise(mn.instructions);
            mn.instructions.add(new JumpInsnNode(GOTO, l_DISPATCH));

            // Emit each block
            for (Block b : blocks) {
                // Make all jumps to the original block-entry label land at our entry
                emittedLabel.put(b.entryLabel, entryLabels[b.id]);

                boolean afterPlaced = false;

                // If this block is an exception handler, emit the handler stub trampoline
                LabelNode handlerStub = (handlerEntryStubs != null && b.id < handlerEntryStubs.length)
                        ? handlerEntryStubs[b.id] : null;
                if (handlerStub != null) {
                    mn.instructions.add(handlerStub);

                    // Clear the handler stack: always preserve the incoming Throwable for later restore
                    FrameLike fAtHandler = frameForBlock(b.id);
                    if (fAtHandler == null || fAtHandler.stackSize == 0) {
                        mn.instructions.add(new InsnNode(POP));
                    } else {
                        spillStackForTarget(b.id, mn.instructions);
                    }

                    // Jump into dispatcher with our state set to this block
                    emitSetStateDisguised(mn.instructions, stateLocal, keys[b.id]);
                    injectNoise(mn.instructions);
                    mn.instructions.add(new JumpInsnNode(GOTO, l_DISPATCH));
                }

                // Block entry
                mn.instructions.add(entryLabels[b.id]);

                // Pre-initialize locals that are read before any write within this block
                emitPreinitsForBlock(collectBlockLocalNeeds(b), mn.instructions);

                // Restore spilled operand stack for this block
                restoreStackForBlock(b.id, mn.instructions);

                // Emit block body
                for (int i = b.startIndex; i <= b.endIndex; i++) {
                    AbstractInsnNode insn = cloned.get(i);

                    // Labels: map & re-emit (skip the block's own entry label which we already added)
                    if (insn instanceof LabelNode) {
                        LabelNode lab = (LabelNode) insn;
                        LabelNode out = emittedLabel.computeIfAbsent(lab, k -> new LabelNode());
                        if (out != entryLabels[b.id]) mn.instructions.add(out);
                        continue;
                    }

                    // Line numbers: remap start label
                    if (insn instanceof LineNumberNode) {
                        LineNumberNode ln = (LineNumberNode) insn;
                        LabelNode mapped = emittedLabel.get(ln.start);
                        if (mapped == null) {
                            mapped = new LabelNode();
                            emittedLabel.put(ln.start, mapped);
                            mn.instructions.add(mapped);
                        }
                        mn.instructions.add(new LineNumberNode(ln.line, mapped));
                        continue;
                    }

                    // Original stack-map frames are ignored (we're using COMPUTE_FRAMES)
                    if (insn instanceof FrameNode) continue;

                    final boolean isTail = (i == b.endIndex);
                    final int op = insn.getOpcode();

                    // --- Non-tail control-flow instructions ---
                    if (!isTail && insn instanceof JumpInsnNode) {
                        JumpInsnNode j = (JumpInsnNode) insn;
                        Integer tBlock = blockOfLabel.get(j.label);
                        if (tBlock == null) {
                            Integer tIdx = labelToIndex.get(j.label);
                            tBlock = (tIdx != null) ? indexToBlock[tIdx] : 0;
                        }
                        if (hasFallThrough(op)) {
                            // Conditional: false path = fall-through
                            LabelNode L_TRUE = new LabelNode();
                            mn.instructions.add(new JumpInsnNode(op, L_TRUE));

                            int fallIndex = Math.min(i + 1, cloned.size() - 1);
                            int fallBlock = indexToBlock[fallIndex];
                            if (fallBlock < 0) fallBlock = Math.min(b.id + 1, blocks.size() - 1);

                            // Fall-through path
                            spillStackForTarget(fallBlock, mn.instructions);
                            if (!afterPlaced) { mn.instructions.add(afterLabels[b.id]); afterPlaced = true; }
                            emitSetStateDisguised(mn.instructions, stateLocal, keys[fallBlock]);
                            injectNoise(mn.instructions);
                            mn.instructions.add(new JumpInsnNode(GOTO, l_DISPATCH));

                            // True branch
                            mn.instructions.add(L_TRUE);
                            spillStackForTarget(tBlock, mn.instructions);
                            if (!afterPlaced) { mn.instructions.add(afterLabels[b.id]); afterPlaced = true; }
                            emitSetStateDisguised(mn.instructions, stateLocal, keys[tBlock]);
                            injectNoise(mn.instructions);
                            mn.instructions.add(new JumpInsnNode(GOTO, l_DISPATCH));
                        } else {
                            // Unconditional jump
                            spillStackForTarget(tBlock, mn.instructions);
                            if (!afterPlaced) { mn.instructions.add(afterLabels[b.id]); afterPlaced = true; }
                            emitSetStateDisguised(mn.instructions, stateLocal, keys[tBlock]);
                            injectNoise(mn.instructions);
                            mn.instructions.add(new JumpInsnNode(GOTO, l_DISPATCH));
                        }
                        continue;
                    }

                    if (!isTail && insn instanceof LookupSwitchInsnNode) {
                        LookupSwitchInsnNode sw = (LookupSwitchInsnNode) insn;
                        int[] caseKeys = sw.keys.stream().mapToInt(Integer::intValue).toArray();
                        LabelNode[] caseStubs = new LabelNode[caseKeys.length];
                        for (int ci = 0; ci < caseStubs.length; ci++) caseStubs[ci] = new LabelNode();
                        LabelNode defStub = new LabelNode();

                        mn.instructions.add(new LookupSwitchInsnNode(defStub, caseKeys, caseStubs));

                        // each case
                        for (int ci = 0; ci < caseStubs.length; ci++) {
                            mn.instructions.add(caseStubs[ci]);
                            Integer tb = blockOfLabel.get(sw.labels.get(ci));
                            if (tb == null) {
                                Integer tIdx = labelToIndex.get(sw.labels.get(ci));
                                tb = (tIdx != null) ? indexToBlock[tIdx] : 0;
                            }
                            spillStackForTarget(tb, mn.instructions);
                            if (!afterPlaced) { mn.instructions.add(afterLabels[b.id]); afterPlaced = true; }
                            emitSetStateDisguised(mn.instructions, stateLocal, keys[tb]);
                            injectNoise(mn.instructions);
                            mn.instructions.add(new JumpInsnNode(GOTO, l_DISPATCH));
                        }

                        // default
                        mn.instructions.add(defStub);
                        Integer db = blockOfLabel.get(sw.dflt);
                        if (db == null) {
                            Integer dIdx = labelToIndex.get(sw.dflt);
                            db = (dIdx != null) ? indexToBlock[dIdx] : 0;
                        }
                        spillStackForTarget(db, mn.instructions);
                        if (!afterPlaced) { mn.instructions.add(afterLabels[b.id]); afterPlaced = true; }
                        emitSetStateDisguised(mn.instructions, stateLocal, keys[db]);
                        injectNoise(mn.instructions);
                        mn.instructions.add(new JumpInsnNode(GOTO, l_DISPATCH));
                        continue;
                    }

                    if (!isTail && insn instanceof TableSwitchInsnNode) {
                        TableSwitchInsnNode sw = (TableSwitchInsnNode) insn;
                        LabelNode[] caseStubs = new LabelNode[sw.labels.size()];
                        for (int ci = 0; ci < caseStubs.length; ci++) caseStubs[ci] = new LabelNode();
                        LabelNode defStub = new LabelNode();

                        mn.instructions.add(new TableSwitchInsnNode(sw.min, sw.max, defStub, caseStubs));

                        // cases
                        for (int ci = 0; ci < caseStubs.length; ci++) {
                            mn.instructions.add(caseStubs[ci]);
                            Integer tb = blockOfLabel.get(sw.labels.get(ci));
                            if (tb == null) {
                                Integer tIdx = labelToIndex.get(sw.labels.get(ci));
                                tb = (tIdx != null) ? indexToBlock[tIdx] : 0;
                            }
                            spillStackForTarget(tb, mn.instructions);
                            if (!afterPlaced) { mn.instructions.add(afterLabels[b.id]); afterPlaced = true; }
                            emitSetStateDisguised(mn.instructions, stateLocal, keys[tb]);
                            injectNoise(mn.instructions);
                            mn.instructions.add(new JumpInsnNode(GOTO, l_DISPATCH));
                        }

                        // default
                        mn.instructions.add(defStub);
                        Integer db = blockOfLabel.get(sw.dflt);
                        if (db == null) {
                            Integer dIdx = labelToIndex.get(sw.dflt);
                            db = (dIdx != null) ? indexToBlock[dIdx] : 0;
                        }
                        spillStackForTarget(db, mn.instructions);
                        if (!afterPlaced) { mn.instructions.add(afterLabels[b.id]); afterPlaced = true; }
                        emitSetStateDisguised(mn.instructions, stateLocal, keys[db]);
                        injectNoise(mn.instructions);
                        mn.instructions.add(new JumpInsnNode(GOTO, l_DISPATCH));
                        continue;
                    }

                    // --- Tail control-flow or fall-through ---
                    if (insn instanceof JumpInsnNode) {
                        JumpInsnNode j = (JumpInsnNode) insn;
                        Integer tBlock = blockOfLabel.get(j.label);
                        if (tBlock == null) tBlock = 0;

                        if (hasFallThrough(op)) {
                            LabelNode L_TRUE = new LabelNode();
                            mn.instructions.add(new JumpInsnNode(op, L_TRUE));

                            int fallIndex = Math.min(i + 1, cloned.size() - 1);
                            int fallBlock = indexToBlock[fallIndex];
                            if (fallBlock < 0) fallBlock = Math.min(b.id + 1, blocks.size() - 1);

                            // False path (fall-through)
                            spillStackForTarget(fallBlock, mn.instructions);
                            if (!afterPlaced) { mn.instructions.add(afterLabels[b.id]); afterPlaced = true; }
                            emitSetStateDisguised(mn.instructions, stateLocal, keys[fallBlock]);
                            injectNoise(mn.instructions);
                            mn.instructions.add(new JumpInsnNode(GOTO, l_DISPATCH));

                            // True path
                            mn.instructions.add(L_TRUE);
                            spillStackForTarget(tBlock, mn.instructions);
                            if (!afterPlaced) { mn.instructions.add(afterLabels[b.id]); afterPlaced = true; }
                            emitSetStateDisguised(mn.instructions, stateLocal, keys[tBlock]);
                            injectNoise(mn.instructions);
                            mn.instructions.add(new JumpInsnNode(GOTO, l_DISPATCH));
                        } else {
                            // Unconditional
                            spillStackForTarget(tBlock, mn.instructions);
                            if (!afterPlaced) { mn.instructions.add(afterLabels[b.id]); afterPlaced = true; }
                            emitSetStateDisguised(mn.instructions, stateLocal, keys[tBlock]);
                            injectNoise(mn.instructions);
                            mn.instructions.add(new JumpInsnNode(GOTO, l_DISPATCH));
                        }
                        continue;
                    }

                    if (insn instanceof LookupSwitchInsnNode) {
                        LookupSwitchInsnNode sw = (LookupSwitchInsnNode) insn;
                        int[] caseKeys = sw.keys.stream().mapToInt(Integer::intValue).toArray();
                        LabelNode[] caseStubs = new LabelNode[caseKeys.length];
                        for (int ci = 0; ci < caseStubs.length; ci++) caseStubs[ci] = new LabelNode();
                        LabelNode defStub = new LabelNode();

                        mn.instructions.add(new LookupSwitchInsnNode(defStub, caseKeys, caseStubs));

                        for (int ci = 0; ci < caseStubs.length; ci++) {
                            mn.instructions.add(caseStubs[ci]);
                            Integer tb = blockOfLabel.get(sw.labels.get(ci));
                            if (tb == null) tb = 0;
                            spillStackForTarget(tb, mn.instructions);
                            if (!afterPlaced) { mn.instructions.add(afterLabels[b.id]); afterPlaced = true; }
                            emitSetStateDisguised(mn.instructions, stateLocal, keys[tb]);
                            injectNoise(mn.instructions);
                            mn.instructions.add(new JumpInsnNode(GOTO, l_DISPATCH));
                        }

                        mn.instructions.add(defStub);
                        Integer db = blockOfLabel.get(sw.dflt);
                        if (db == null) db = 0;
                        spillStackForTarget(db, mn.instructions);
                        if (!afterPlaced) { mn.instructions.add(afterLabels[b.id]); afterPlaced = true; }
                        emitSetStateDisguised(mn.instructions, stateLocal, keys[db]);
                        injectNoise(mn.instructions);
                        mn.instructions.add(new JumpInsnNode(GOTO, l_DISPATCH));
                        continue;
                    }

                    if (insn instanceof TableSwitchInsnNode) {
                        TableSwitchInsnNode sw = (TableSwitchInsnNode) insn;
                        LabelNode[] caseStubs = new LabelNode[sw.labels.size()];
                        for (int ci = 0; ci < caseStubs.length; ci++) caseStubs[ci] = new LabelNode();
                        LabelNode defStub = new LabelNode();

                        mn.instructions.add(new TableSwitchInsnNode(sw.min, sw.max, defStub, caseStubs));

                        for (int ci = 0; ci < caseStubs.length; ci++) {
                            mn.instructions.add(caseStubs[ci]);
                            Integer tb = blockOfLabel.get(sw.labels.get(ci));
                            if (tb == null) tb = 0;
                            spillStackForTarget(tb, mn.instructions);
                            if (!afterPlaced) { mn.instructions.add(afterLabels[b.id]); afterPlaced = true; }
                            emitSetStateDisguised(mn.instructions, stateLocal, keys[tb]);
                            injectNoise(mn.instructions);
                            mn.instructions.add(new JumpInsnNode(GOTO, l_DISPATCH));
                        }

                        mn.instructions.add(defStub);
                        Integer db = blockOfLabel.get(sw.dflt);
                        if (db == null) db = 0;
                        spillStackForTarget(db, mn.instructions);
                        if (!afterPlaced) { mn.instructions.add(afterLabels[b.id]); afterPlaced = true; }
                        emitSetStateDisguised(mn.instructions, stateLocal, keys[db]);
                        injectNoise(mn.instructions);
                        mn.instructions.add(new JumpInsnNode(GOTO, l_DISPATCH));
                        continue;
                    }

                    // Terminators: emit and do NOT touch afterLabels here
                    if (isTerminator(op)) {
                        mn.instructions.add(insn.clone(emittedLabel));
                        continue;
                    }

                    // Tail non-terminator "fall-through" to next block
                    if (isTail) {
                        mn.instructions.add(insn.clone(emittedLabel));
                        int next = Math.min(b.id + 1, blocks.size() - 1);
                        spillStackForTarget(next, mn.instructions);
                        if (!afterPlaced) { mn.instructions.add(afterLabels[b.id]); afterPlaced = true; }
                        emitSetStateDisguised(mn.instructions, stateLocal, keys[next]);
                        injectNoise(mn.instructions);
                        mn.instructions.add(new JumpInsnNode(GOTO, l_DISPATCH));
                        continue;
                    }

                    // Ordinary non-tail instruction
                    mn.instructions.add(insn.clone(emittedLabel));
                }

                // Ensure the anchor exists exactly once per block
                if (!afterPlaced) {
                    mn.instructions.add(afterLabels[b.id]);
                }
            }
        }

        private void insertGlobalLocalPreinits() {
            final int locals = mn.maxLocals;
            final int argSlots = computeArgSlots();
            if (locals <= argSlots) return;

            int[] kind = new int[locals];
            Arrays.fill(kind, -1);

            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof VarInsnNode) {
                    VarInsnNode v = (VarInsnNode) insn;
                    if (v.var < 0 || v.var >= kind.length) continue;
                    int inferred = inferLocalKind(v.getOpcode());
                    if (inferred != -1 && kind[v.var] == -1) {
                        kind[v.var] = inferred;
                    }
                } else if (insn instanceof IincInsnNode) {
                    IincInsnNode inc = (IincInsnNode) insn;
                    if (inc.var >= 0 && inc.var < kind.length && kind[inc.var] == -1) {
                        kind[inc.var] = ILOAD;
                    }
                }
            }

            InsnList init = new InsnList();
            int extraStack = 0;
            for (int idx = argSlots; idx < locals; idx++) {
                int k = kind[idx];
                if (k == -1) continue;
                switch (k) {
                    case ILOAD:
                        pushConst(init, 0);
                        init.add(new VarInsnNode(ISTORE, idx));
                        extraStack = Math.max(extraStack, 1);
                        break;
                    case FLOAD:
                        init.add(new InsnNode(FCONST_0));
                        init.add(new VarInsnNode(FSTORE, idx));
                        extraStack = Math.max(extraStack, 1);
                        break;
                    case LLOAD:
                        init.add(new InsnNode(LCONST_0));
                        init.add(new VarInsnNode(LSTORE, idx));
                        extraStack = Math.max(extraStack, 2);
                        break;
                    case DLOAD:
                        init.add(new InsnNode(DCONST_0));
                        init.add(new VarInsnNode(DSTORE, idx));
                        extraStack = Math.max(extraStack, 2);
                        break;
                    case ALOAD:
                        init.add(new InsnNode(ACONST_NULL));
                        init.add(new VarInsnNode(ASTORE, idx));
                        extraStack = Math.max(extraStack, 1);
                        break;
                    default:
                        break;
                }
            }

            if (init.size() == 0) return;

            AbstractInsnNode anchor = findStateStoreInstruction();
            if (anchor == null) {
                mn.instructions.insert(init);
            } else {
                mn.instructions.insert(anchor, init);
            }
            mn.maxStack = Math.max(mn.maxStack, Math.max(8, extraStack + 1));
        }

        private AbstractInsnNode findStateStoreInstruction() {
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof VarInsnNode) {
                    VarInsnNode v = (VarInsnNode) insn;
                    if (v.getOpcode() == ISTORE && v.var == stateLocal) {
                        return insn;
                    }
                }
            }
            return mn.instructions.getFirst();
        }

        private static int inferLocalKind(int opcode) {
            switch (opcode) {
                case ILOAD:
                case ISTORE:
                    return ILOAD;
                case FLOAD:
                case FSTORE:
                    return FLOAD;
                case LLOAD:
                case LSTORE:
                    return LLOAD;
                case DLOAD:
                case DSTORE:
                    return DLOAD;
                case ALOAD:
                case ASTORE:
                    return ALOAD;
                default:
                    return -1;
            }
        }


        // --- Noise injection (harmless stack-neutral ops) ---
        private void injectNoise(InsnList out) {
            for (int i = 0; i < NOISE_LEVEL; i++) {
                switch (rnd.nextInt(3)) {
                    case 0:
                        out.add(new InsnNode(NOP));
                        break;
                    case 1:
                        pushConst(out, 0);
                        out.add(new InsnNode(POP));
                        break;
                    default:
                        pushConst(out, rnd.nextInt());
                        out.add(new InsnNode(POP));
                        break;
                }
            }
        }

        private void emitSetStateDisguised(InsnList il, int stateLocal, int targetKey) {
            int choice = rnd.nextInt(4);
            switch (choice) {
                case 0: { // XOR 三明治： (K^S) ^ S == K
                    int s = 0;
                    while (s == 0) s = rnd.nextInt();
                    pushConst(il, targetKey ^ s);
                    pushConst(il, s);
                    il.add(new InsnNode(IXOR));
                    il.add(new VarInsnNode(ISTORE, stateLocal));
                    return;
                }
                case 1: { // 加减抵消： (K+S) - S == K
                    int s = 0;
                    while (s == 0) s = rnd.nextInt();
                    pushConst(il, targetKey + s);
                    pushConst(il, s);
                    il.add(new InsnNode(ISUB));
                    il.add(new VarInsnNode(ISTORE, stateLocal));
                    return;
                }
                case 2: { // 旋转往返：rotl(rotr(K,r), r) == K
                    int r = 1 + rnd.nextInt(31);
                    pushConst(il, targetKey);            // K
                    // rotr(K, r)
                    il.add(new InsnNode(DUP));
                    pushConst(il, 32 - (r & 31));
                    il.add(new InsnNode(ISHL));
                    il.add(new InsnNode(SWAP));
                    pushConst(il, (r & 31));
                    il.add(new InsnNode(IUSHR));
                    il.add(new InsnNode(IOR));           // rotr(K, r)
                    // rotl( , r)
                    il.add(new InsnNode(DUP));
                    pushConst(il, (r & 31));
                    il.add(new InsnNode(ISHL));
                    il.add(new InsnNode(SWAP));
                    pushConst(il, 32 - (r & 31));
                    il.add(new InsnNode(IUSHR));
                    il.add(new InsnNode(IOR));           // K
                    il.add(new VarInsnNode(ISTORE, stateLocal));
                    return;
                }
                default: { // 混合：((K^S1)+S2)^S1 - S2 == K
                    int s1 = 0; while (s1 == 0) s1 = rnd.nextInt();
                    int s2 = rnd.nextInt();
                    pushConst(il, targetKey ^ s1);
                    pushConst(il, s2);
                    il.add(new InsnNode(IADD));
                    pushConst(il, s1);
                    il.add(new InsnNode(IXOR));
                    pushConst(il, s2);
                    il.add(new InsnNode(ISUB));
                    il.add(new VarInsnNode(ISTORE, stateLocal));
                }
            }
        }

        // --- Spill/Restore helpers -------------------------------------------

        private void spillStackForTarget(int targetBlockId, InsnList out) {
            if (targetBlockId < 0 || targetBlockId >= blocks.size()) return;

            FrameLike f = frameForBlock(targetBlockId);
            // If target block is unreachable (null frame), no spilling needed
            if (f == null || f.stackSize == 0) return;

            // Assign contiguous slots for the frame's stack
            int slot = spillBase;
            int[] elemBase = new int[f.stackSize];
            for (int k = 0; k < f.stackSize; k++) {
                Type t = f.stack[k];
                int sz = sizeOf(t);
                elemBase[k] = slot;
                slot += sz;
            }
            // Pop & store from TOP to BOTTOM
            for (int k = f.stackSize - 1; k >= 0; k--) {
                Type t = f.stack[k];
                int store = storeOpcodeFor(t);
                out.add(new VarInsnNode(store, elemBase[k]));
            }
        }

        private void restoreStackForBlock(int blockId, InsnList out) {
            if (blockId < 0 || blockId >= blocks.size()) return;

            FrameLike f = frameForBlock(blockId);
            // If block is unreachable (null frame), no restoration needed
            if (f == null || f.stackSize == 0) return;

            // Load from bottom to top
            int slot = spillBase;
            for (int k = 0; k < f.stackSize; k++) {
                Type t = f.stack[k];
                int load = loadOpcodeFor(t);
                out.add(new VarInsnNode(load, slot));
                slot += sizeOf(t);
            }
        }

        // --- Try/catch & LVT rebuild ----------------------------------------

        private int blockForLabel(LabelNode lab) {
            Integer idx = labelToIndex.get(lab);
            if (idx == null || idx < 0 || idx >= indexToBlock.length) return -1;
            return indexToBlock[idx];
        }

        private void rebuildTryCatch() {
            final List<TryCatchBlockNode> old = origTcbs;
            final List<TryCatchBlockNode> rebuilt = new ArrayList<>();
            if (old == null || old.isEmpty()) { mn.tryCatchBlocks = rebuilt; return; }

            for (TryCatchBlockNode tcb : old) {
                LabelNode startCl   = labelMap.getOrDefault(tcb.start,   tcb.start);
                LabelNode endCl     = labelMap.getOrDefault(tcb.end,     tcb.end);
                LabelNode handlerCl = labelMap.getOrDefault(tcb.handler, tcb.handler);

                // 先尝试直接映射（如果我们在输出时确实发射了对应 label）
                LabelNode startOut   = emittedLabel.get(startCl);
                LabelNode endOut     = emittedLabel.get(endCl);
                LabelNode handlerOut = emittedLabel.get(handlerCl);

                // 定位块，用于兜底以及保证 start<end
                int sb = blockForLabel(startCl);
                int eb = blockForLabel(endCl);
                int hb = blockForLabel(handlerCl);

                // fallback：start/end/handler 映射不到，则用块的 entry/after/stub
                if (startOut == null && sb >= 0) startOut = entryLabels[sb];
                if (endOut   == null && eb >= 0) endOut   = afterLabels[eb];

                if (handlerOut == null && hb >= 0) {
                    LabelNode stub = (hb < handlerEntryStubs.length) ? handlerEntryStubs[hb] : null;
                    handlerOut = (stub != null) ? stub : entryLabels[hb];
                }

                // 如果块次序反了（或 end 不明），做个保守矫正，确保 start<end
                if (sb >= 0 && eb >= 0 && eb < sb) {
                    int t = sb; sb = eb; eb = t;
                    // 用修正后的 entry/after 作为最终范围
                    startOut = entryLabels[sb];
                    endOut   = afterLabels[eb];
                } else if (sb >= 0 && eb < 0) {
                    // 没法定位 end，用与 start 同块的 after 兜底
                    endOut = afterLabels[sb];
                } else if (sb < 0 && eb >= 0) {
                    // 没法定位 start，用 end 块的 entry 兜底
                    startOut = entryLabels[eb];
                }

                // 仍然缺任何一个就放弃这个 TCB
                if (startOut == null || endOut == null || handlerOut == null) continue;

                // 避免 start==end（零长度区间会被后续 sanitize 丢掉）
                if (startOut == endOut && sb >= 0) {
                    endOut = afterLabels[sb];
                    if (startOut == endOut) continue; // 理论上不会相等；双保险
                }

                String handlerType = (tcb.type != null) ? tcb.type : "java/lang/Throwable";
                rebuilt.add(new TryCatchBlockNode(startOut, endOut, handlerOut, handlerType));
            }
            mn.tryCatchBlocks = rebuilt;
        }


        private void rebuildLocalVariables() {
            final List<LocalVariableNode> old = origLvt;
            if (old == null || old.isEmpty()) return;

            final List<LocalVariableNode> out = new ArrayList<>(old.size());
            for (LocalVariableNode lv : old) {
                LabelNode startCl = labelMap.getOrDefault(lv.start, lv.start);
                LabelNode endCl   = labelMap.getOrDefault(lv.end,   lv.end);

                LabelNode startOut = emittedLabel.get(startCl);
                LabelNode endOut   = emittedLabel.get(endCl);

                if (startOut == null) {
                    Integer idx = labelToIndex.get(startCl);
                    if (idx != null) {
                        int b = indexToBlock[idx];
                        if (b >= 0) startOut = entryLabels[b];
                    }
                }
                if (endOut == null) {
                    Integer idx = labelToIndex.get(endCl);
                    if (idx != null) {
                        int b = indexToBlock[idx];
                        if (b >= 0) endOut = afterLabels[b];
                    }
                }

                if (startOut != null && endOut != null && startOut != endOut) {
                    out.add(new LocalVariableNode(lv.name, lv.desc, lv.signature, startOut, endOut, lv.index));
                }
            }
            mn.localVariables = out;
        }



        // --- Helper structures ----------------------------------------------

        private boolean isHandlerBlock(int blockId) {
            return blockId >= 0 && blockId < isHandlerEntry.length && isHandlerEntry[blockId];
        }

        private FrameLike frameForBlock(int blockId) {
            if (blockId < 0 || blockId >= blocks.size()) return null;
            FrameLike f = frames[blocks.get(blockId).startIndex];
            if (isHandlerBlock(blockId) && (f == null || f.stackSize == 0)) {
                return FrameLike.throwableFallback();
            }
            return f;
        }

        private static final class Block {
            final int id;
            int startIndex;
            int endIndex;
            LabelNode entryLabel;
            Block(int id, int startIndex, int endIndex) {
                this.id = id;
                this.startIndex = startIndex;
                this.endIndex = endIndex;
            }
        }

        private static final class FrameLike {
            final int stackSize;
            final Type[] stack;

            FrameLike(int stackSize, Type[] stack) {
                this.stackSize = stackSize;
                this.stack = stack;
            }

            static FrameLike throwableFallback() {
                return new FrameLike(1, new Type[]{Type.getObjectType("java/lang/Throwable")});
            }

            static FrameLike from(Frame<BasicValue> f) {
                if (f == null) return null;
                int sz = f.getStackSize();
                if (sz == 0) return new FrameLike(0, new Type[0]);
                Type[] arr = new Type[sz];
                for (int i = 0; i < sz; i++) {
                    BasicValue v = f.getStack(i);
                    Type t = (v == null ? null : v.getType());
                    arr[i] = normalizeType(t);
                }
                return new FrameLike(sz, arr);
            }

            boolean allSpillable() {
                for (int i = 0; i < stackSize; i++) {
                    Type t = stack[i];
                    if (t == null) return false; // uninitialized/unknown
                    int s = t.getSort();
                    if (s == Type.VOID) return false;
                    // RETURN_ADDRESS not produced by BasicInterpreter; extra guard
                }
                return true;
            }

            int localSlotsForSpill() {
                int slots = 0;
                for (int i = 0; i < stackSize; i++) slots += sizeOf(stack[i]);
                return slots;
            }

            private static Type normalizeType(Type t) {
                if (t == null) return null;
                switch (t.getSort()) {
                    case Type.BOOLEAN:
                    case Type.BYTE:
                    case Type.CHAR:
                    case Type.SHORT:
                        return Type.INT_TYPE;
                    default:
                        return t;
                }
            }
        }

        private static final class FormulaParams {
            final int variant; // 0..3
            final int mask;
            final int mul;
            final int add;
            final int r1;
            final int r2;

            FormulaParams(int variant, int mask, int mul, int add, int r1, int r2) {
                this.variant = variant;
                this.mask = mask;
                this.mul = mul;
                this.add = add;
                this.r1 = r1;
                this.r2 = r2;
            }

            static FormulaParams random(Random rnd) {
                int variant = rnd.nextInt(4);
                int mask = rnd.nextInt() | 1;
                int mul = rnd.nextInt() | 1;
                int add = rnd.nextInt();
                int r1 = 1 + rnd.nextInt(31);
                int r2 = 1 + rnd.nextInt(31);
                return new FormulaParams(variant, mask, mul, add, r1, r2);
            }
        }

        // --- Encoders --------------------------------------------------------

        private static int encodeWithFormula(int raw, FormulaParams p) {
            switch (p.variant) {
                case 0: {
                    int k = Integer.rotateLeft((raw ^ p.mask) * (p.mul | 1), p.r1) + p.add;
                    k ^= (k >>> 13);
                    return k;
                }
                case 1: {
                    int k = ((raw + p.mask) * (p.mul | 1)) ^ p.add;
                    k = Integer.rotateLeft(k, p.r2) ^ (k >>> 11);
                    return k;
                }
                case 2: {
                    int k = raw ^ Integer.rotateLeft(p.mask * (raw | 1), p.r1);
                    k = (k + p.add) * (p.mul | 1);
                    k ^= (k >>> 7);
                    return k;
                }
                default: {
                    int k = Integer.rotateLeft(raw + p.add, p.r2) * (p.mul | 1);
                    k ^= p.mask;
                    k ^= (k >>> 15);
                    return k;
                }
            }
        }
    }

    // ==== Shared helpers =====================================================

    private static void pushConst(InsnList il, int v) {
        if (v >= -1 && v <= 5) {
            switch (v) {
                case -1: il.add(new InsnNode(ICONST_M1)); return;
                case 0:  il.add(new InsnNode(ICONST_0));  return;
                case 1:  il.add(new InsnNode(ICONST_1));  return;
                case 2:  il.add(new InsnNode(ICONST_2));  return;
                case 3:  il.add(new InsnNode(ICONST_3));  return;
                case 4:  il.add(new InsnNode(ICONST_4));  return;
                case 5:  il.add(new InsnNode(ICONST_5));  return;
            }
        }
        if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
            il.add(new IntInsnNode(BIPUSH, v));
        } else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
            il.add(new IntInsnNode(SIPUSH, v));
        } else {
            il.add(new LdcInsnNode(v));
        }
    }

    private static int sizeOf(Type t) {
        if (t == null) return 1; // be permissive
        int s = t.getSort();
        return (s == Type.LONG || s == Type.DOUBLE) ? 2 : 1;
    }

    private static int loadOpcodeFor(Type t) {
        if (t == null) return ALOAD;
        switch (t.getSort()) {
            case Type.LONG:   return LLOAD;
            case Type.FLOAT:  return FLOAD;
            case Type.DOUBLE: return DLOAD;
            case Type.OBJECT:
            case Type.ARRAY:  return ALOAD;
            default:          return ILOAD; // boolean/byte/char/short/int
        }
    }

    private static int storeOpcodeFor(Type t) {
        if (t == null) return ASTORE;
        switch (t.getSort()) {
            case Type.LONG:   return LSTORE;
            case Type.FLOAT:  return FSTORE;
            case Type.DOUBLE: return DSTORE;
            case Type.OBJECT:
            case Type.ARRAY:  return ASTORE;
            default:          return ISTORE; // boolean/byte/char/short/int
        }
    }

    private static boolean hasFallThrough(int opcode) {
        switch (opcode) {
            case IFEQ:
            case IFNE:
            case IFLT:
            case IFGE:
            case IFGT:
            case IFLE:
            case IF_ICMPEQ:
            case IF_ICMPNE:
            case IF_ICMPLT:
            case IF_ICMPGE:
            case IF_ICMPGT:
            case IF_ICMPLE:
            case IF_ACMPEQ:
            case IF_ACMPNE:
            case IFNULL:
            case IFNONNULL:
                return true;
            default:
                return false;
        }
    }

    private static boolean isTerminator(int opcode) {
        if (opcode == -1) return false;
        switch (opcode) {
            case GOTO:
            case RETURN:
            case IRETURN:
            case LRETURN:
            case FRETURN:
            case DRETURN:
            case ARETURN:
            case ATHROW:
                return true;
            default:
                return false;
        }
    }

    // ==== Simple wrapper fallback ===========================================

    // ==== Simple wrapper fallback ===========================================

    private static final class WrapperFallback implements Opcodes {

        static void run(MethodNode mn, String methodId, JavaObfuscationConfig.Strength strength) {
            // 1) Clone original with a fresh label map (keep original FrameNodes!)
            final InsnList original = mn.instructions;
            final InsnList bodyCopy = new InsnList();
            final IdentityHashMap<LabelNode, LabelNode> labelMap = new IdentityHashMap<>();

            for (AbstractInsnNode insn = original.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LabelNode) {
                    labelMap.put((LabelNode) insn, new LabelNode());
                }
            }
            for (AbstractInsnNode insn = original.getFirst(); insn != null; insn = insn.getNext()) {
                bodyCopy.add(insn.clone(labelMap));
            }

            for (AbstractInsnNode q = bodyCopy.getFirst(); q != null; ) {
                AbstractInsnNode next = q.getNext();
                if (q instanceof FrameNode) bodyCopy.remove(q);
                q = next;
            }

            // 2) Preserve try/catch (remap with labelMap), then keep only those whose geometry is sane
            final List<TryCatchBlockNode> oldTcbs = mn.tryCatchBlocks == null ? Collections.emptyList() : mn.tryCatchBlocks;
            final List<TryCatchBlockNode> newTcbs = new ArrayList<>(oldTcbs.size());
            for (TryCatchBlockNode tcb : oldTcbs) {
                LabelNode start   = labelMap.getOrDefault(tcb.start,   tcb.start);
                LabelNode end     = labelMap.getOrDefault(tcb.end,     tcb.end);
                LabelNode handler = labelMap.getOrDefault(tcb.handler, tcb.handler);
                newTcbs.add(new TryCatchBlockNode(start, end, handler, tcb.type));
            }

            // 3) Preserve LVT (debug) with remapped labels
            final List<LocalVariableNode> oldLvs = mn.localVariables == null ? Collections.emptyList() : mn.localVariables;
            final List<LocalVariableNode> newLvs = new ArrayList<>(oldLvs.size());
            for (LocalVariableNode lv : oldLvs) {
                LabelNode start = labelMap.getOrDefault(lv.start, lv.start);
                LabelNode end   = labelMap.getOrDefault(lv.end,   lv.end);
                newLvs.add(new LocalVariableNode(lv.name, lv.desc, lv.signature, start, end, lv.index));
            }

            // 4) Reset method body & sanitize TCBs/LVT using the cloned geometry
            mn.instructions = new InsnList();
            List<TryCatchBlockNode> filtered = new ArrayList<>(newTcbs.size());
            IdentityHashMap<LabelNode, Integer> pos = indexLabels(bodyCopy);

            for (TryCatchBlockNode t : newTcbs) {
                if (t.start == null || t.end == null || t.handler == null) continue;
                if (t.start == t.end) continue;
                Integer s = pos.get(t.start), e = pos.get(t.end), h = pos.get(t.handler);
                if (s == null || e == null || h == null) continue;
                if (s >= e) continue; // zero-length or inverted
                filtered.add(t);
            }
            mn.tryCatchBlocks = filtered;
            mn.localVariables = dedupLocalVariables(bodyCopy, newLvs);

            // 5) Pick a truly free local for 'state'
            int highestUsed = scanMaxLocalIndex(bodyCopy);
            int stateLocal  = Math.max(mn.maxLocals, highestUsed + 1);
            mn.maxLocals = Math.max(mn.maxLocals, stateLocal + 1);

            // 6) Build encodings (unique switch keys)
            final int dummyCount = (strength == JavaObfuscationConfig.Strength.HIGH ? 3
                    : strength == JavaObfuscationConfig.Strength.MEDIUM ? 1 : 0);

            int seed = (methodId == null ? (mn.name + mn.desc) : methodId).hashCode();
            Random rnd = new Random((((long) seed) * 1103515245L + 12345L) ^ 0x9E3779B9L);

            int mask, mul, add, enc0, enc1;
            int[] encDummy = new int[dummyCount];
            outer:
            while (true) {
                mask = rnd.nextInt() | 1;
                mul  = rnd.nextInt() | 1;
                add  = rnd.nextInt();

                enc0 = encodeState(0, mask, mul, add, strength);
                enc1 = encodeState(1, mask, mul, add, strength);
                if (enc0 == enc1) continue;

                HashSet<Integer> uniq = new HashSet<>();
                uniq.add(enc0);
                if (!uniq.add(enc1)) continue;
                for (int i = 0; i < dummyCount; i++) {
                    encDummy[i] = encodeState(100 + i, mask, mul, add, strength);
                    if (!uniq.add(encDummy[i])) continue outer;
                }
                break;
            }

            // 7) Labels
            final LabelNode L_LOOP       = new LabelNode();
            final LabelNode L_DEFAULT    = new LabelNode();
            final LabelNode L_CASE0      = new LabelNode();
            final LabelNode L_CASE1      = new LabelNode();
            final LabelNode[] L_DUM      = new LabelNode[dummyCount];
            for (int i = 0; i < dummyCount; i++) L_DUM[i] = new LabelNode();

            // Optional fenced body region (avoids accidental fall-through)
            final LabelNode L_BODY_START = new LabelNode();
            final LabelNode L_BODY_END   = new LabelNode();

            // 8) Prologue: initialize state to enc0
            pushConst(mn.instructions, enc0);
            mn.instructions.add(new VarInsnNode(ISTORE, stateLocal));

            // 9) Dispatch loop with LookupSwitch
            mn.instructions.add(L_LOOP);
            mn.instructions.add(new VarInsnNode(ILOAD, stateLocal));

            int total = 2 + dummyCount;
            int[] keys = new int[total];
            LabelNode[] labels = new LabelNode[total];
            int k = 0;
            keys[k] = enc0; labels[k++] = L_CASE0;
            keys[k] = enc1; labels[k++] = L_CASE1;
            for (int i = 0; i < dummyCount; i++) { keys[k] = encDummy[i]; labels[k++] = L_DUM[i]; }
            // sort by key (LookupSwitch requires ascending)
            for (int i = 0; i < keys.length - 1; i++) {
                for (int j = i + 1; j < keys.length; j++) {
                    if (keys[i] > keys[j]) {
                        int tk = keys[i]; keys[i] = keys[j]; keys[j] = tk;
                        LabelNode tl = labels[i]; labels[i] = labels[j]; labels[j] = tl;
                    }
                }
            }
            mn.instructions.add(new LookupSwitchInsnNode(L_DEFAULT, keys, labels));

            // case 0 -> transition to state 1
            mn.instructions.add(L_CASE0);
            emitSetStateConstDisguised(mn.instructions, stateLocal, enc1, rnd);
            mn.instructions.add(new JumpInsnNode(GOTO, L_LOOP));

            // case 1 -> jump into original body start
            mn.instructions.add(L_CASE1);
            mn.instructions.add(new JumpInsnNode(GOTO, L_BODY_START));

            // dummy cases
            for (int i = 0; i < dummyCount; i++) {
                mn.instructions.add(L_DUM[i]);
                pushConst(mn.instructions, rnd.nextInt());
                mn.instructions.add(new InsnNode(POP));
                emitSetStateConstDisguised(mn.instructions, stateLocal, enc1, rnd);
                mn.instructions.add(new JumpInsnNode(GOTO, L_LOOP));
            }

            // default -> recover
            mn.instructions.add(L_DEFAULT);
            emitSetStateConstDisguised(mn.instructions, stateLocal, enc1, rnd);
            mn.instructions.add(new JumpInsnNode(GOTO, L_LOOP));

            // ---- Defensive handler stubs (avoid handler→normal label stack mismatch) ----
            {
                int excLocal = Math.max(mn.maxLocals, stateLocal + 1);
                mn.maxLocals = Math.max(mn.maxLocals, excLocal + 1);

                final IdentityHashMap<LabelNode, Integer> bodyPos = indexLabels(bodyCopy);

                for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
                    if (tcb == null || tcb.handler == null) continue;
                    final LabelNode h = tcb.handler;

                    AbstractInsnNode first = null;
                    Integer hi = bodyPos.get(h);
                    if (hi != null) {
                        for (int idx = hi + 1; idx < bodyCopy.size(); idx++) {
                            AbstractInsnNode cand = bodyCopy.get(idx);
                            if (cand instanceof LabelNode || cand instanceof LineNumberNode || cand instanceof FrameNode) continue;
                            first = cand; break;
                        }
                    }

                    boolean looksLikeRealHandler =
                            (first instanceof VarInsnNode && ((VarInsnNode) first).getOpcode() == ASTORE) ||
                                    (first != null && first.getOpcode() == ATHROW) ||
                                    (first != null && first.getOpcode() == POP);

                    if (!looksLikeRealHandler) {
                        LabelNode stub = new LabelNode();

                        InsnList stubCode = new InsnList();
                        stubCode.add(stub);
                        stubCode.add(new VarInsnNode(ASTORE, excLocal));   // consume exception -> empty stack
                        stubCode.add(new VarInsnNode(ALOAD,  excLocal));   // <<< RESTORE Throwable for handler label
                        stubCode.add(new JumpInsnNode(GOTO, h));           // rejoin original handler code

                        mn.instructions.add(stubCode);

                        // Redirect the handler to land on our stub
                        tcb.handler = stub;
                    }
                }
            }

            // 10) Body region (as-is), but FIRST:
            //     (a) emit a full entry frame, (b) preinit ref locals read-before-write (safe), (c) optional primitive monomorphic preinits
            mn.instructions.add(L_BODY_START);
            emitBodyEntryFullFrame(mn, L_BODY_START, guessOwnerFromLvtOrNull(mn));
            mn.maxStack = Math.max(mn.maxStack, 4);

            preinitRefLocalsReadBeforeWrite(bodyCopy, mn, computeArgSlots(mn), stateLocal);
            preinitMonomorphicLocalsAtEntry(bodyCopy, mn, computeArgSlots(mn), stateLocal);

            // Splice original body (with its original frames preserved)
            mn.instructions.add(bodyCopy);

            mn.instructions.add(L_BODY_END);
            mn.instructions.add(new JumpInsnNode(GOTO, L_LOOP));
        }

        /**
         * Pre-initialize only locals whose kind is monomorphic across the whole method body,
         * *and only for primitive kinds*. References are skipped to avoid conflicts with
         * uninitialized object types before invokespecial <init>.
         */
        private static void preinitMonomorphicLocalsAtEntry(
                InsnList body, MethodNode mn, int args, int stateLocal) {

            final int nLocals = Math.max(mn.maxLocals, stateLocal + 1);

            // bit flags per slot: 1=int, 2=float, 4=long, 8=double, 16=ref
            final int[] kindMask = new int[nLocals];

            for (AbstractInsnNode p = body.getFirst(); p != null; p = p.getNext()) {
                if (p instanceof VarInsnNode) {
                    VarInsnNode v = (VarInsnNode) p;
                    int idx = v.var;
                    if (idx < 0 || idx >= stateLocal) continue; // skip synthetic and out-of-range
                    switch (v.getOpcode()) {
                        case ILOAD: case ISTORE: kindMask[idx] |= 1; break;
                        case FLOAD: case FSTORE: kindMask[idx] |= 2; break;
                        case LLOAD: case LSTORE: kindMask[idx] |= 4; break;
                        case DLOAD: case DSTORE: kindMask[idx] |= 8; break;
                        case ALOAD: case ASTORE: kindMask[idx] |= 16; break;
                    }
                } else if (p instanceof IincInsnNode) {
                    IincInsnNode inc = (IincInsnNode) p;
                    int idx = inc.var;
                    if (idx >= 0 && idx < stateLocal) kindMask[idx] |= 1; // IINC implies int
                }
            }

            // Emit one-time defaults only for slots used with exactly one primitive kind.
            InsnList init = new InsnList();
            for (int idx = args; idx < stateLocal; idx++) {
                int m = kindMask[idx];
                if (m == 0) continue;             // never touched
                if ((m & (m - 1)) != 0) continue; // polytypic slot — skip

                if      ((m & 1) != 0) {          // int
                    pushConst(init, 0);
                    init.add(new VarInsnNode(ISTORE, idx));
                } else if ((m & 2) != 0) {        // float
                    init.add(new InsnNode(FCONST_0));
                    init.add(new VarInsnNode(FSTORE, idx));
                } else if ((m & 4) != 0) {        // long
                    init.add(new InsnNode(LCONST_0));
                    init.add(new VarInsnNode(LSTORE, idx));
                } else if ((m & 8) != 0) {        // double
                    init.add(new InsnNode(DCONST_0));
                    init.add(new VarInsnNode(DSTORE, idx));
                }
                // m==16 (ref) intentionally ignored
            }

            if (init.size() > 0) {
                body.insertBefore(body.getFirst(), init);
                mn.maxStack = Math.max(mn.maxStack, 2);
            }
        }


        /** Emit an F_FULL at the body entry so ASM has a solid starting frame for the original code. */
        private static void emitBodyEntryFullFrame(MethodNode mn, LabelNode at, String ownerInternal) {
            final boolean isStatic = (mn.access & ACC_STATIC) != 0;
            final ArrayList<Object> locals = new ArrayList<>();

            if (!isStatic) {
                locals.add(ownerInternal != null ? ownerInternal : Opcodes.TOP);
            }
            for (Type t : Type.getArgumentTypes(mn.desc)) {
                switch (t.getSort()) {
                    case Type.BOOLEAN:
                    case Type.BYTE:
                    case Type.CHAR:
                    case Type.SHORT:
                    case Type.INT:
                        locals.add(Opcodes.INTEGER); break;
                    case Type.FLOAT:
                        locals.add(Opcodes.FLOAT); break;
                    case Type.LONG:
                        locals.add(Opcodes.LONG); locals.add(Opcodes.TOP); break;
                    case Type.DOUBLE:
                        locals.add(Opcodes.DOUBLE); locals.add(Opcodes.TOP); break;
                    case Type.ARRAY:
                        locals.add(t.getDescriptor()); break; // arrays use descriptor
                    case Type.OBJECT:
                        locals.add(t.getInternalName()); break; // objects use internal name
                    default:
                        locals.add(Opcodes.TOP);
                }
            }
            while (locals.size() < mn.maxLocals) locals.add(Opcodes.TOP);
            mn.instructions.add(new FrameNode(Opcodes.F_FULL, locals.size(), locals.toArray(), 0, new Object[0]));
        }

        /** Heuristic: null-initialize only reference locals that are read (ALOAD) before any ASTORE. Safe for println/System.out patterns. */
        private static void preinitRefLocalsReadBeforeWrite(InsnList body, MethodNode mn, int args, int stateLocal) {
            final int nLocals = Math.max(mn.maxLocals, stateLocal + 1);
            final int[] firstAccess = new int[nLocals]; // 0=unknown, 1=ALOAD-first, 2=STORE-first
            Arrays.fill(firstAccess, 0);

            for (AbstractInsnNode p = body.getFirst(); p != null; p = p.getNext()) {
                if (p instanceof VarInsnNode) {
                    VarInsnNode v = (VarInsnNode) p;
                    int idx = v.var;
                    if (idx < args || idx >= stateLocal) continue; // skip params & synthetic
                    if (firstAccess[idx] != 0) continue;

                    if (v.getOpcode() == ALOAD) {
                        firstAccess[idx] = 1;
                    } else if (v.getOpcode() == ASTORE) {
                        firstAccess[idx] = 2;
                    }
                }
            }

            InsnList init = new InsnList();
            boolean need = false;
            for (int idx = args; idx < stateLocal; idx++) {
                if (firstAccess[idx] == 1) {
                    init.add(new InsnNode(ACONST_NULL));
                    init.add(new VarInsnNode(ASTORE, idx));
                    need = true;
                }
            }
            if (need) {
                body.insertBefore(body.getFirst(), init);
                mn.maxStack = Math.max(mn.maxStack, 1);
            }
        }

        /** Best-effort owner inference from LVT. */
        private static String guessOwnerFromLvtOrNull(MethodNode mn) {
            if ((mn.access & ACC_STATIC) == 0 && mn.localVariables != null) {
                for (LocalVariableNode lv : mn.localVariables) {
                    if (lv.index == 0 && "this".equals(lv.name) &&
                            lv.desc != null && lv.desc.startsWith("L") && lv.desc.endsWith(";")) {
                        return Type.getType(lv.desc).getInternalName();
                    }
                }
            }
            return null;
        }

        // ---- helpers reused from your class ----

        private static IdentityHashMap<LabelNode, Integer> indexLabels(InsnList insns) {
            IdentityHashMap<LabelNode, Integer> pos = new IdentityHashMap<>();
            int i = 0;
            for (AbstractInsnNode p = insns.getFirst(); p != null; p = p.getNext(), i++) {
                if (p instanceof LabelNode) pos.put((LabelNode) p, i);
            }
            return pos;
        }

        private static int scanMaxLocalIndex(InsnList il) {
            int max = -1;
            for (AbstractInsnNode in = il.getFirst(); in != null; in = in.getNext()) {
                if (in instanceof VarInsnNode) {
                    VarInsnNode v = (VarInsnNode) in;
                    int op = v.getOpcode();
                    int sz = (op == LLOAD || op == DLOAD || op == LSTORE || op == DSTORE) ? 2 : 1;
                    max = Math.max(max, v.var + sz - 1);
                } else if (in instanceof IincInsnNode) {
                    IincInsnNode inc = (IincInsnNode) in;
                    max = Math.max(max, inc.var);
                }
            }
            return max;
        }

        private static List<LocalVariableNode> dedupLocalVariables(InsnList insns, List<LocalVariableNode> in) {
            if (in == null || in.isEmpty()) return Collections.emptyList();
            IdentityHashMap<LabelNode, Integer> pos = indexLabels(insns);
            HashSet<String> seen = new HashSet<>();
            ArrayList<LocalVariableNode> out = new ArrayList<>(in.size());
            for (LocalVariableNode lv : in) {
                if (lv == null || lv.start == null || lv.end == null) continue;
                Integer s = pos.get(lv.start), e = pos.get(lv.end);
                if (s == null || e == null) continue;
                if (s >= e) continue;
                String key = lv.index + ":" + s + ":" + e;
                if (!seen.add(key)) continue;
                out.add(lv);
            }
            out.sort((a, b) -> {
                Integer sa = pos.get(a.start), ea = pos.get(a.end);
                Integer sb = pos.get(b.start), eb = pos.get(b.end);
                int x = Integer.compare(sa, sb);
                return (x != 0) ? x : Integer.compare(ea, eb);
            });
            return out;
        }

        private static int computeArgSlots(MethodNode mn) {
            int slots = ((mn.access & ACC_STATIC) == 0) ? 1 : 0;
            for (Type t : Type.getArgumentTypes(mn.desc)) {
                slots += (t == Type.LONG_TYPE || t == Type.DOUBLE_TYPE) ? 2 : 1;
            }
            return slots;
        }

        private static int encodeState(int raw, int mask, int mul, int add, JavaObfuscationConfig.Strength s) {
            switch (s) {
                case LOW:    return raw;
                case MEDIUM: return (raw ^ mask) * mul + add;
                case HIGH: {
                    int v = raw ^ Integer.rotateLeft(mask, 7);
                    v = Integer.rotateLeft(v * (mul | 1), 3) + add;
                    v ^= (v >>> 13);
                    return v;
                }
                default: return raw;
            }
        }

        private static void emitSetStateConstDisguised(InsnList il, int stateLocal, int key, Random rnd) {
            int choice = rnd.nextInt(3);
            switch (choice) {
                case 0: {
                    int s = 0; while (s == 0) s = rnd.nextInt();
                    pushConst(il, key ^ s);
                    pushConst(il, s);
                    il.add(new InsnNode(IXOR));
                    il.add(new VarInsnNode(ISTORE, stateLocal));
                    return;
                }
                case 1: {
                    int s = 0; while (s == 0) s = rnd.nextInt();
                    pushConst(il, key + s);
                    pushConst(il, s);
                    il.add(new InsnNode(ISUB));
                    il.add(new VarInsnNode(ISTORE, stateLocal));
                    return;
                }
                default: {
                    int r = 1 + rnd.nextInt(31);
                    pushConst(il, key);
                    il.add(new InsnNode(DUP));
                    pushConst(il, r);
                    il.add(new InsnNode(ISHL));
                    il.add(new InsnNode(SWAP));
                    pushConst(il, 32 - (r & 31));
                    il.add(new InsnNode(IUSHR));
                    il.add(new InsnNode(IOR));
                    il.add(new InsnNode(DUP));
                    pushConst(il, 32 - (r & 31));
                    il.add(new InsnNode(ISHL));
                    il.add(new InsnNode(SWAP));
                    pushConst(il, (r & 31));
                    il.add(new InsnNode(IUSHR));
                    il.add(new InsnNode(IOR));
                    il.add(new VarInsnNode(ISTORE, stateLocal));
                }
            }
        }
    }
}
