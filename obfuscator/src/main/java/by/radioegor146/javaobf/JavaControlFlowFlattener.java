package by.radioegor146.javaobf;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

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
        if ("<init>".equals(mn.name) || "<clinit>".equals(mn.name)) return false;

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

        // Try the advanced multi-block approach; fallback to wrapper if anything fails.
        try {
            new FlattenerImpl(mn, methodId, strength).run();
        } catch (Throwable t) {
            // Fallback: still produce a valid (less strong) obfuscation rather than bailing out.
            try {
                WrapperFallback.run(mn, methodId, strength);
            } catch (Throwable ignore) {
                // If even fallback fails, keep original body unchanged.
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
            // tune by strength
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

        void run() throws AnalyzerException {
            cloneBody();
            analyzeFrames();
            computeLeadersWithMicroSplits();
            buildBlocksAndMaps();
            allocateStateAndParams();
            allocateSpillArea();

            emitFlattened();
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
            Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicInterpreter());
            // owner not used by BasicInterpreter
            @SuppressWarnings("unchecked")
            Frame<BasicValue>[] fs = analyzer.analyze("java/lang/Object", methodNodeCopyOf(body));
            frames = new FrameLike[cloned.size()];
            for (int i = 0; i < cloned.size(); i++) {
                frames[i] = FrameLike.from(fs[i]);
            }
        }

        // Copy method node solely for analyzer input
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
            return tmp;
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
            for (int i = 0; i < n; i++) {
                AbstractInsnNode insn = cloned.get(i);
                int op = insn.getOpcode();
                if (insn instanceof JumpInsnNode) {
                    JumpInsnNode j = (JumpInsnNode) insn;
                    Integer ti = labelToIndex.get(j.label);
                    if (ti != null) isLeader[ti] = true;
                    if (hasFallThrough(op) && i + 1 < n) isLeader[i + 1] = true;
                } else if (insn instanceof LookupSwitchInsnNode) {
                    LookupSwitchInsnNode sw = (LookupSwitchInsnNode) insn;
                    Integer di = labelToIndex.get(sw.dflt);
                    if (di != null) isLeader[di] = true;
                    for (LabelNode l : sw.labels) {
                        Integer li = labelToIndex.get(l);
                        if (li != null) isLeader[li] = true;
                    }
                } else if (insn instanceof TableSwitchInsnNode) {
                    TableSwitchInsnNode sw = (TableSwitchInsnNode) insn;
                    Integer di = labelToIndex.get(sw.dflt);
                    if (di != null) isLeader[di] = true;
                    for (LabelNode l : sw.labels) {
                        Integer li = labelToIndex.get(l);
                        if (li != null) isLeader[li] = true;
                    }
                } else if (isTerminator(op)) {
                    if (i + 1 < n) isLeader[i + 1] = true;
                }
            }

            // Micro-splits: split BEFORE instruction i where (stack size small & spillable)
            for (int i = 1; i < n; i++) {
                if (isLeader[i]) continue; // already a leader
                FrameLike f = frames[i];
                if (f == null) continue;   // unreachable → skip
                int sz = f.stackSize;
                if (sz == 0) {
                    isLeader[i] = true;
                } else if (SPILL_LIMIT > 0 && sz <= SPILL_LIMIT && f.allSpillable()) {
                    isLeader[i] = true;
                }
            }

            for (int i = 0; i < n; i++) if (isLeader[i]) leaders.add(i);
        }

        // --- Build blocks, map labels to blocks, etc. ---
        private void buildBlocksAndMaps() {
            final int n = cloned.size();
            for (int bi = 0; bi < leaders.size(); bi++) {
                int s = leaders.get(bi);
                int e = (bi + 1 < leaders.size() ? leaders.get(bi + 1) : n) - 1;
                if (e < s) e = s;
                blocks.add(new Block(bi, s, e));
            }

            // Ensure every starting position has a label so we can map label->block
            for (Block b : blocks) {
                AbstractInsnNode at = cloned.get(b.startIndex);
                LabelNode got;
                if (at instanceof LabelNode) {
                    got = (LabelNode) at;
                } else {
                    got = new LabelNode();
                    body.insertBefore(at, got);
                    cloned.add(b.startIndex, got);
                    // shift labelToIndex; rebuild quickly
                    labelToIndex.clear();
                    for (int i = 0; i < cloned.size(); i++) {
                        if (cloned.get(i) instanceof LabelNode) labelToIndex.put((LabelNode) cloned.get(i), i);
                    }
                    // Adjust indices of subsequent blocks
                    for (int i = b.id + 1; i < blocks.size(); i++) {
                        blocks.get(i).startIndex++;
                        blocks.get(i).endIndex++;
                    }
                    b.endIndex++;
                }
                b.entryLabel = got;
                blockOfLabel.put(got, b.id);
            }

            // make index→blockId map
            indexToBlock = new int[cloned.size()];
            Arrays.fill(indexToBlock, -1);
            for (Block b : blocks) {
                for (int i = b.startIndex; i <= b.endIndex; i++) {
                    indexToBlock[i] = b.id;
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
            }
        }

        // --- Compute maximum spill locals needed across all entries ---
        private void allocateSpillArea() {
            int maxSlots = 0;
            for (Block b : blocks) {
                FrameLike f = frames[b.startIndex];
                if (f == null) continue;
                maxSlots = Math.max(maxSlots, f.localSlotsForSpill());
            }
            // total local slots reserved
            int spillSlots = maxSlots;
            spillBase = mn.maxLocals;
            mn.maxLocals += spillSlots;
        }

        // --- Emit flattened method body ---
        private void emitFlattened() {
            mn.instructions = new InsnList();
            mn.tryCatchBlocks = new ArrayList<>();
            // locals will be rebuilt later from original LVT
            mn.localVariables = (mn.localVariables == null ? new ArrayList<>() : mn.localVariables);

            // Prologue: set initial state to key of block 0
            LabelNode L_START = new LabelNode();
            // Dispatcher labels
            LabelNode l_DISPATCH = new LabelNode();
            LabelNode l_DEFAULT = new LabelNode();
            mn.instructions.add(L_START);
            pushConst(mn.instructions, keys[0]);
            mn.instructions.add(new VarInsnNode(ISTORE, stateLocal));

            // Dispatcher
            mn.instructions.add(l_DISPATCH);
            mn.instructions.add(new VarInsnNode(ILOAD, stateLocal));
            int[] sorted = Arrays.stream(keys).sorted().toArray();
            LabelNode[] sortedEntries = new LabelNode[sorted.length];
            for (int i = 0; i < sorted.length; i++) {
                int k = sorted[i];
                for (int j = 0; j < keys.length; j++) if (keys[j] == k) { sortedEntries[i] = entryLabels[j]; break; }
            }
            mn.instructions.add(new LookupSwitchInsnNode(l_DEFAULT, sorted, sortedEntries));

            // Default recovery: steer to block 0 (safe)
            mn.instructions.add(l_DEFAULT);
            emitEncodedStoreForBlock(mn.instructions, stateLocal, 0, params[0]);
            injectNoise(mn.instructions);
            mn.instructions.add(new JumpInsnNode(GOTO, l_DISPATCH));

            // Emit each block
            for (Block b : blocks) {
                mn.instructions.add(entryLabels[b.id]);
                // Restore target-entry stack from spill
                restoreStackForBlock(b.id, mn.instructions);

                for (int i = b.startIndex; i <= b.endIndex; i++) {
                    AbstractInsnNode insn = cloned.get(i);

                    // Re-emit labels and line numbers mapping
                    if (insn instanceof LabelNode) {
                        LabelNode lab = (LabelNode) insn;
                        LabelNode out = emittedLabel.computeIfAbsent(lab, k -> new LabelNode());
                        mn.instructions.add(out);
                        continue;
                    }
                    if (insn instanceof LineNumberNode) {
                        LineNumberNode ln = (LineNumberNode) insn;
                        LabelNode mapped = emittedLabel.get(ln.start);
                        if (mapped == null) { mapped = new LabelNode(); emittedLabel.put(ln.start, mapped); mn.instructions.add(mapped); }
                        mn.instructions.add(new LineNumberNode(ln.line, mapped));
                        continue;
                    }
                    if (insn instanceof FrameNode) {
                        // drop frames; will be recomputed
                        continue;
                    }

                    final boolean isTail = (i == b.endIndex);
                    final int op = insn.getOpcode();

                    if (!isTail) {
                        // middle instruction → clone with label map
                        mn.instructions.add(insn.clone(emittedLabel));
                        continue;
                    }

                    // Tail handling: redirect edges to dispatcher
                    if (insn instanceof JumpInsnNode) {
                        JumpInsnNode j = (JumpInsnNode) insn;
                        Integer tBlock = blockOfLabel.get(j.label);
                        if (tBlock == null) tBlock = 0;

                        if (hasFallThrough(op)) {
                            // conditional branch
                            LabelNode L_TRUE = new LabelNode();
                            // emit original IF to L_TRUE
                            mn.instructions.add(new JumpInsnNode(op, L_TRUE));
                            // fall-through → block of next instruction (i+1)
                            int fallIndex = Math.min(i + 1, cloned.size() - 1);
                            int fallBlock = indexToBlock[fallIndex];
                            if (fallBlock < 0) fallBlock = Math.min(b.id + 1, blocks.size() - 1);
                            // spill and steer
                            mn.instructions.add(afterLabels[b.id]); // boundary for ranges
                            spillStackForTarget(fallBlock, mn.instructions);
                            emitEncodedStoreForBlock(mn.instructions, stateLocal, fallBlock, params[fallBlock]);
                            injectNoise(mn.instructions);
                            mn.instructions.add(new JumpInsnNode(GOTO, l_DISPATCH));
                            // TRUE branch
                            mn.instructions.add(L_TRUE);
                            spillStackForTarget(tBlock, mn.instructions);
                            emitEncodedStoreForBlock(mn.instructions, stateLocal, tBlock, params[tBlock]);
                            injectNoise(mn.instructions);
                            mn.instructions.add(new JumpInsnNode(GOTO, l_DISPATCH));
                        } else {
                            // unconditional GOTO
                            mn.instructions.add(afterLabels[b.id]);
                            spillStackForTarget(tBlock, mn.instructions);
                            emitEncodedStoreForBlock(mn.instructions, stateLocal, tBlock, params[tBlock]);
                            injectNoise(mn.instructions);
                            mn.instructions.add(new JumpInsnNode(GOTO, l_DISPATCH));
                        }
                        continue;
                    }

                    if (insn instanceof LookupSwitchInsnNode) {
                        LookupSwitchInsnNode sw = (LookupSwitchInsnNode) insn;
                        // Re-emit switch to stub labels, each stub spills & dispatches
                        int[] caseKeys = new int[sw.keys.size()];
                        for (int ci = 0; ci < caseKeys.length; ci++) caseKeys[ci] = sw.keys.get(ci);
                        LabelNode[] caseStubs = new LabelNode[caseKeys.length];
                        for (int ci = 0; ci < caseStubs.length; ci++) caseStubs[ci] = new LabelNode();
                        LabelNode defStub = new LabelNode();
                        mn.instructions.add(new LookupSwitchInsnNode(defStub, caseKeys, caseStubs));
                        mn.instructions.add(afterLabels[b.id]); // boundary

                        // cases
                        for (int ci = 0; ci < caseStubs.length; ci++) {
                            mn.instructions.add(caseStubs[ci]);
                            Integer tb = blockOfLabel.get(sw.labels.get(ci));
                            if (tb == null) tb = 0;
                            spillStackForTarget(tb, mn.instructions);
                            emitEncodedStoreForBlock(mn.instructions, stateLocal, tb, params[tb]);
                            injectNoise(mn.instructions);
                            mn.instructions.add(new JumpInsnNode(GOTO, l_DISPATCH));
                        }
                        // default
                        mn.instructions.add(defStub);
                        Integer db = blockOfLabel.get(sw.dflt);
                        if (db == null) db = 0;
                        spillStackForTarget(db, mn.instructions);
                        emitEncodedStoreForBlock(mn.instructions, stateLocal, db, params[db]);
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
                        mn.instructions.add(afterLabels[b.id]); // boundary
                        for (int ci = 0; ci < caseStubs.length; ci++) {
                            mn.instructions.add(caseStubs[ci]);
                            Integer tb = blockOfLabel.get(sw.labels.get(ci));
                            if (tb == null) tb = 0;
                            spillStackForTarget(tb, mn.instructions);
                            emitEncodedStoreForBlock(mn.instructions, stateLocal, tb, params[tb]);
                            injectNoise(mn.instructions);
                            mn.instructions.add(new JumpInsnNode(GOTO, l_DISPATCH));
                        }
                        mn.instructions.add(defStub);
                        Integer db = blockOfLabel.get(sw.dflt);
                        if (db == null) db = 0;
                        spillStackForTarget(db, mn.instructions);
                        emitEncodedStoreForBlock(mn.instructions, stateLocal, db, params[db]);
                        injectNoise(mn.instructions);
                        mn.instructions.add(new JumpInsnNode(GOTO, l_DISPATCH));
                        continue;
                    }

                    // If tail is a hard terminator (RETURN/ATHROW), emit it and mark boundary
                    if (isTerminator(op)) {
                        mn.instructions.add(insn.clone(emittedLabel));
                        mn.instructions.add(afterLabels[b.id]);
                    } else {
                        // Fallthrough to next block
                        mn.instructions.add(insn.clone(emittedLabel));
                        int next = Math.min(b.id + 1, blocks.size() - 1);
                        mn.instructions.add(afterLabels[b.id]);
                        spillStackForTarget(next, mn.instructions);
                        emitEncodedStoreForBlock(mn.instructions, stateLocal, next, params[next]);
                        injectNoise(mn.instructions);
                        mn.instructions.add(new JumpInsnNode(GOTO, l_DISPATCH));
                    }
                }
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

        // --- Spill/Restore helpers -------------------------------------------

        private void spillStackForTarget(int targetBlockId, InsnList out) {
            FrameLike f = frames[blocks.get(targetBlockId).startIndex];
            if (f == null || f.stackSize == 0) return;

            // Assign contiguous slots [spillBase .. spillBase+spillSlots) for the frame’s stack
            // Layout: bottom element at lowest slot; top element at highest slot(s).
            int slot = spillBase;
            int[] elemBase = new int[f.stackSize]; // base slot per stack element (bottom..top)
            for (int k = 0; k < f.stackSize; k++) {
                Type t = f.stack[k];
                int sz = sizeOf(t);
                elemBase[k] = slot;
                slot += sz;
            }
            // Now pop & store from TOP to BOTTOM using assigned slots
            for (int k = f.stackSize - 1; k >= 0; k--) {
                Type t = f.stack[k];
                int store = storeOpcodeFor(t);
                out.add(new VarInsnNode(store, elemBase[k])); // pops value
            }
        }

        private void restoreStackForBlock(int blockId, InsnList out) {
            FrameLike f = frames[blocks.get(blockId).startIndex];
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

        private void rebuildTryCatch() {
            final List<TryCatchBlockNode> old = (original == null || mn.tryCatchBlocks == null)
                    ? Collections.emptyList() : mn.tryCatchBlocks;
            final List<TryCatchBlockNode> rebuilt = new ArrayList<>();
            if (old.isEmpty()) {
                mn.tryCatchBlocks = rebuilt;
                return;
            }
            // Map using: original -> cloned(labelMap) -> emitted(emittedLabel) ; fallback to entry/after
            for (TryCatchBlockNode tcb : old) {
                LabelNode startCl = labelMap.getOrDefault(tcb.start, tcb.start);
                LabelNode endCl = labelMap.getOrDefault(tcb.end, tcb.end);
                LabelNode handlerCl = labelMap.getOrDefault(tcb.handler, tcb.handler);

                LabelNode startOut = emittedLabel.get(startCl);
                LabelNode endOut   = emittedLabel.get(endCl);
                LabelNode handlerOut = emittedLabel.get(handlerCl);

                if (startOut == null) {
                    Integer bi = blockOfLabel.get(startCl);
                    if (bi != null) startOut = entryLabels[bi];
                }
                if (endOut == null) {
                    Integer bi = blockOfLabel.get(endCl);
                    if (bi != null) endOut = afterLabels[bi];
                }
                if (handlerOut == null) {
                    Integer bi = blockOfLabel.get(handlerCl);
                    if (bi != null) handlerOut = entryLabels[bi];
                }
                if (startOut != null && endOut != null && handlerOut != null && startOut != endOut) {
                    rebuilt.add(new TryCatchBlockNode(startOut, endOut, handlerOut, tcb.type));
                }
            }
            mn.tryCatchBlocks = rebuilt;
        }

        private void rebuildLocalVariables() {
            final List<LocalVariableNode> old = mn.localVariables;
            if (old == null || old.isEmpty()) return;
            final List<LocalVariableNode> out = new ArrayList<>(old.size());
            for (LocalVariableNode lv : old) {
                LabelNode startCl = labelMap.getOrDefault(lv.start, lv.start);
                LabelNode endCl = labelMap.getOrDefault(lv.end, lv.end);
                LabelNode startOut = emittedLabel.get(startCl);
                LabelNode endOut   = emittedLabel.get(endCl);
                if (startOut == null) {
                    Integer bi = blockOfLabel.get(startCl);
                    if (bi != null) startOut = entryLabels[bi];
                }
                if (endOut == null) {
                    Integer bi = blockOfLabel.get(endCl);
                    if (bi != null) endOut = afterLabels[bi];
                }
                if (startOut != null && endOut != null && startOut != endOut) {
                    out.add(new LocalVariableNode(lv.name, lv.desc, lv.signature, startOut, endOut, lv.index));
                }
            }
            mn.localVariables = out;
        }

        // --- Helper structures ----------------------------------------------

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

        private static void emitEncodedStoreForBlock(InsnList il, int stateLocal, int raw, FormulaParams p) {
            switch (p.variant) {
                case 0: {
                    // rotl((x ^ m) * a, r1) + add; x ^= x>>>13
                    pushConst(il, raw);
                    pushConst(il, p.mask);
                    il.add(new InsnNode(IXOR));
                    pushConst(il, (p.mul | 1));
                    il.add(new InsnNode(IMUL));
                    il.add(new InsnNode(DUP));
                    pushConst(il, p.r1);
                    il.add(new InsnNode(ISHL));
                    il.add(new InsnNode(SWAP));
                    pushConst(il, 32 - (p.r1 & 31));
                    il.add(new InsnNode(IUSHR));
                    il.add(new InsnNode(IOR));
                    pushConst(il, p.add);
                    il.add(new InsnNode(IADD));
                    il.add(new InsnNode(DUP));
                    pushConst(il, 13);
                    il.add(new InsnNode(IUSHR));
                    il.add(new InsnNode(IXOR));
                    il.add(new VarInsnNode(ISTORE, stateLocal));
                    return;
                }
                case 1: {
                    // ((x + m) * a) ^ add; rotl(r2) ^ (k>>>11)
                    pushConst(il, raw);
                    pushConst(il, p.mask);
                    il.add(new InsnNode(IADD));
                    pushConst(il, (p.mul | 1));
                    il.add(new InsnNode(IMUL));
                    pushConst(il, p.add);
                    il.add(new InsnNode(IXOR));
                    il.add(new InsnNode(DUP));
                    pushConst(il, p.r2);
                    il.add(new InsnNode(ISHL));
                    il.add(new InsnNode(SWAP));
                    pushConst(il, 32 - (p.r2 & 31));
                    il.add(new InsnNode(IUSHR));
                    il.add(new InsnNode(IOR));
                    il.add(new InsnNode(DUP));
                    pushConst(il, 11);
                    il.add(new InsnNode(IUSHR));
                    il.add(new InsnNode(IXOR));
                    il.add(new VarInsnNode(ISTORE, stateLocal));
                    return;
                }
                case 2: {
                    // x ^ rotl(mask*(x|1), r1); (k + add) * a; k ^= (k>>>7)
                    pushConst(il, raw);
                    il.add(new InsnNode(DUP));
                    pushConst(il, 1);
                    il.add(new InsnNode(IOR));
                    pushConst(il, p.mask);
                    il.add(new InsnNode(IMUL));
                    il.add(new InsnNode(DUP));
                    pushConst(il, p.r1);
                    il.add(new InsnNode(ISHL));
                    il.add(new InsnNode(SWAP));
                    pushConst(il, 32 - (p.r1 & 31));
                    il.add(new InsnNode(IUSHR));
                    il.add(new InsnNode(IOR));
                    il.add(new InsnNode(IXOR));
                    pushConst
                            (il, p.add);
                    il.add(new InsnNode(IADD));
                    pushConst(il, (p.mul | 1));
                    il.add(new InsnNode(IMUL));
                    il.add(new InsnNode(DUP));
                    pushConst(il, 7);
                    il.add(new InsnNode(IUSHR));
                    il.add(new InsnNode(IXOR));
                    il.add(new VarInsnNode(ISTORE, stateLocal));
                    return;
                }
                default: {
                    // rotl(x + add, r2) * a; ^mask; ^(k>>>15)
                    pushConst(il, raw);
                    pushConst(il, p.add);
                    il.add(new InsnNode(IADD));
                    il.add(new InsnNode(DUP));
                    pushConst(il, p.r2);
                    il.add(new InsnNode(ISHL));
                    il.add(new InsnNode(SWAP));
                    pushConst(il, 32 - (p.r2 & 31));
                    il.add(new InsnNode(IUSHR));
                    il.add(new InsnNode(IOR));
                    pushConst(il, (p.mul | 1));
                    il.add(new InsnNode(IMUL));
                    pushConst(il, p.mask);
                    il.add(new InsnNode(IXOR));
                    il.add(new InsnNode(DUP));
                    pushConst(il, 15);
                    il.add(new InsnNode(IUSHR));
                    il.add(new InsnNode(IXOR));
                    il.add(new VarInsnNode(ISTORE, stateLocal));
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

    private static final class WrapperFallback implements Opcodes {

        static void run(MethodNode mn, String methodId, JavaObfuscationConfig.Strength strength) {
            // 1) Clone original with label map
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

            // Preserve try/catch (remap with labelMap)
            final List<TryCatchBlockNode> oldTcbs = mn.tryCatchBlocks == null ? Collections.emptyList() : mn.tryCatchBlocks;
            final List<TryCatchBlockNode> newTcbs = new ArrayList<>(oldTcbs.size());
            for (TryCatchBlockNode tcb : oldTcbs) {
                LabelNode start = labelMap.getOrDefault(tcb.start, tcb.start);
                LabelNode end   = labelMap.getOrDefault(tcb.end, tcb.end);
                LabelNode handler = labelMap.getOrDefault(tcb.handler, tcb.handler);
                newTcbs.add(new TryCatchBlockNode(start, end, handler, tcb.type));
            }

            // Preserve local variables (debug)
            final List<LocalVariableNode> oldLvs = mn.localVariables == null ? Collections.emptyList() : mn.localVariables;
            final List<LocalVariableNode> newLvs = new ArrayList<>(oldLvs.size());
            for (LocalVariableNode lv : oldLvs) {
                LabelNode start = labelMap.getOrDefault(lv.start, lv.start);
                LabelNode end   = labelMap.getOrDefault(lv.end, lv.end);
                newLvs.add(new LocalVariableNode(lv.name, lv.desc, lv.signature, start, end, lv.index));
            }

            // 2) Reset body
            mn.instructions = new InsnList();
            mn.tryCatchBlocks = newTcbs;
            mn.localVariables = newLvs;

            // 3) Allocate state local
            final int stateLocal = mn.maxLocals;
            mn.maxLocals = Math.max(mn.maxLocals, stateLocal + 1);

            // 4) Build encodings
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

            // 5) Labels
            final LabelNode L_LOOP = new LabelNode();
            final LabelNode L_DEFAULT = new LabelNode();
            final LabelNode L_CASE0 = new LabelNode();
            final LabelNode L_CASE1 = new LabelNode();
            final LabelNode[] L_DUM = new LabelNode[dummyCount];
            for (int i = 0; i < dummyCount; i++) L_DUM[i] = new LabelNode();

            // 6) Prologue
            pushConst(mn.instructions, enc0);
            mn.instructions.add(new VarInsnNode(ISTORE, stateLocal));

            // 7) Dispatch
            mn.instructions.add(L_LOOP);
            mn.instructions.add(new VarInsnNode(ILOAD, stateLocal));

            int total = 2 + dummyCount;
            int[] keys = new int[total];
            LabelNode[] labels = new LabelNode[total];
            int k = 0;
            keys[k] = enc0; labels[k++] = L_CASE0;
            keys[k] = enc1; labels[k++] = L_CASE1;
            for (int i = 0; i < dummyCount; i++) {
                keys[k] = encDummy[i]; labels[k++] = L_DUM[i];
            }
            // sort
            for (int i = 0; i < keys.length - 1; i++) {
                for (int j = i + 1; j < keys.length; j++) {
                    if (keys[i] > keys[j]) {
                        int tk2 = keys[i]; keys[i] = keys[j]; keys[j] = tk2;
                        LabelNode tl2 = labels[i]; labels[i] = labels[j]; labels[j] = tl2;
                    }
                }
            }
            mn.instructions.add(new LookupSwitchInsnNode(L_DEFAULT, keys, labels));

            // case 0 -> transition to state 1
            mn.instructions.add(L_CASE0);
            emitTransition(mn.instructions, stateLocal, 1, mask, mul, add, strength);
            mn.instructions.add(new JumpInsnNode(GOTO, L_LOOP));

            // case 1 -> original body
            mn.instructions.add(L_CASE1);
            mn.instructions.add(bodyCopy);

            // dummy cases
            for (int i = 0; i < dummyCount; i++) {
                mn.instructions.add(L_DUM[i]);
                pushConst(mn.instructions, rnd.nextInt());
                mn.instructions.add(new InsnNode(POP));
                emitTransition(mn.instructions, stateLocal, 1, mask, mul, add, strength);
                mn.instructions.add(new JumpInsnNode(GOTO, L_LOOP));
            }

            // default -> recover
            mn.instructions.add(L_DEFAULT);
            emitTransition(mn.instructions, stateLocal, 1, mask, mul, add, strength);
            mn.instructions.add(new JumpInsnNode(GOTO, L_LOOP));

            mn.maxStack = Math.max(mn.maxStack, 4);
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

        private static void emitTransition(InsnList il, int stateLocal, int nextRaw, int mask, int mul, int add,
                                           JavaObfuscationConfig.Strength s) {
            switch (s) {
                case LOW: {
                    pushConst(il, nextRaw);
                    il.add(new VarInsnNode(ISTORE, stateLocal));
                    return;
                }
                case MEDIUM: {
                    pushConst(il, nextRaw);
                    pushConst(il, mask);
                    il.add(new InsnNode(IXOR));
                    pushConst(il, mul);
                    il.add(new InsnNode(IMUL));
                    pushConst(il, add);
                    il.add(new InsnNode(IADD));
                    il.add(new VarInsnNode(ISTORE, stateLocal));
                    return;
                }
                case HIGH: {
                    pushConst(il, nextRaw);
                    pushConst(il, Integer.rotateLeft(mask, 7));
                    il.add(new InsnNode(IXOR));
                    pushConst(il, (mul | 1));
                    il.add(new InsnNode(IMUL));
                    // rotl(x,3)
                    il.add(new InsnNode(DUP));
                    pushConst(il, 3);
                    il.add(new InsnNode(ISHL));
                    il.add(new InsnNode(SWAP));
                    pushConst(il, 29);
                    il.add(new InsnNode(IUSHR));
                    il.add(new InsnNode(IOR));
                    pushConst(il, add);
                    il.add(new InsnNode(IADD));
                    il.add(new InsnNode(DUP));
                    pushConst(il, 13);
                    il.add(new InsnNode(IUSHR));
                    il.add(new InsnNode(IXOR));
                    il.add(new VarInsnNode(ISTORE, stateLocal));
                }
            }
        }
    }
}
