package by.radioegor146.javaobf;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.*;

/**
 * Robust Java bytecode-level control-flow flattener.
 *
 * Key guarantees:
 *  - Skips <init> and <clinit> to avoid super() and clinit ordering violations.
 *  - Preserves and remaps try/catch blocks (and local variables) via label map.
 *  - Avoids state-key collisions across LOW/MEDIUM/HIGH encoding.
 *  - Delegates frame/max computation to ASM (use ClassWriter with COMPUTE_FRAMES|COMPUTE_MAXS).
 *
 * Strength:
 *  - LOW: direct integer state values.
 *  - MEDIUM: affine-mixed integer encoding.
 *  - HIGH: extra dummy states + VM-like arithmetic mixing/diffusion.
 */
public final class JavaControlFlowFlattener {

    private JavaControlFlowFlattener() {}

    public static boolean canProcess(MethodNode mn) {
        if (mn == null) return false;
        if ((mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return false;
        if (mn.instructions == null || mn.instructions.size() == 0) return false;

        // Never flatten constructors or class initializers (keep JVM rules on super() and clinit order)
        if ("<init>".equals(mn.name) || "<clinit>".equals(mn.name)) return false;

        // Avoid JSR/RET (legacy)
        for (AbstractInsnNode p = mn.instructions.getFirst(); p != null; p = p.getNext()) {
            int op = p.getOpcode();
            if (op == Opcodes.JSR || op == Opcodes.RET) return false;
        }
        return true;
    }

    public static void flatten(MethodNode mn, String methodId, JavaObfuscationConfig.Strength strength) {
        Objects.requireNonNull(mn, "mn");
        Objects.requireNonNull(strength, "strength");
        if (!canProcess(mn)) return;

        // Prefer safe multi-block flattening when possible (stack-aware); otherwise fall back
        // to wrapper-style flattening which is always stable.
        if (strength == JavaObfuscationConfig.Strength.HIGH && canSafelyMultiBlock(mn)) {
            try {
                flattenMultiBlock(mn, methodId);
                return;
            } catch (Throwable t) {
                // Safety net: any unexpected issue -> fall back
            }
        }

        // === 1) Clone original body with label mapping ===
        final InsnList original = mn.instructions;
        final InsnList bodyCopy = new InsnList();
        final Map<LabelNode, LabelNode> labelMap = new IdentityHashMap<>();

        // Map all original labels
        for (AbstractInsnNode insn = original.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode) {
                labelMap.put((LabelNode) insn, new LabelNode());
            }
        }
        // Clone body
        for (AbstractInsnNode insn = original.getFirst(); insn != null; insn = insn.getNext()) {
            bodyCopy.add(insn.clone(labelMap));
        }

        // Remap try/catch blocks
        final List<TryCatchBlockNode> oldTcbs = mn.tryCatchBlocks == null ? Collections.emptyList() : mn.tryCatchBlocks;
        final List<TryCatchBlockNode> newTcbs = new ArrayList<>(oldTcbs.size());
        for (TryCatchBlockNode tcb : oldTcbs) {
            LabelNode start = labelMap.get(tcb.start);
            LabelNode end   = labelMap.get(tcb.end);
            LabelNode handler = labelMap.get(tcb.handler);
            // Some tools create handler labels lazily; if missing, keep original
            if (start == null) start = tcb.start;
            if (end == null) end = tcb.end;
            if (handler == null) handler = tcb.handler;
            newTcbs.add(new TryCatchBlockNode(start, end, handler, tcb.type));
        }

        // Remap local variables (debug info)
        final List<LocalVariableNode> oldLvs = mn.localVariables == null ? Collections.emptyList() : mn.localVariables;
        final List<LocalVariableNode> newLvs = new ArrayList<>(oldLvs.size());
        for (LocalVariableNode lv : oldLvs) {
            LabelNode start = labelMap.get(lv.start);
            LabelNode end   = labelMap.get(lv.end);
            if (start == null) start = lv.start;
            if (end == null) end = lv.end;
            newLvs.add(new LocalVariableNode(lv.name, lv.desc, lv.signature, start, end, lv.index));
        }

        // === 2) Reset method body (but replace with state machine wrapper) ===
        mn.instructions = new InsnList();
        mn.tryCatchBlocks = newTcbs;      // preserve ranges into the cloned body
        mn.localVariables = newLvs;       // preserve debug info

        // === 3) Allocate locals for state ===
        final int stateLocal = mn.maxLocals;       // 1 slot int
        mn.maxLocals = Math.max(mn.maxLocals, stateLocal + 1);

        // === 4) Build deterministic, collision-free encoded states ===
        final int dummyCount = (strength == JavaObfuscationConfig.Strength.HIGH ? 3
                : strength == JavaObfuscationConfig.Strength.MEDIUM ? 1 : 0);

        // Derive seed from method identity
        int seed = (methodId == null ? (mn.name + mn.desc) : methodId).hashCode();
        Random rnd = new Random((((long) seed) * 1103515245L + 12345L) ^ 0x9E3779B9L);

        // Choose parameters; ensure uniqueness of encodings
        int mask, mul, add;
        int enc0, enc1;
        int[] encDummy = new int[dummyCount];
        outer:
        while (true) {
            mask = rnd.nextInt() | 1;
            mul  = rnd.nextInt() | 1;
            add  = rnd.nextInt();

            enc0 = encodeState(0, mask, mul, add, strength);
            enc1 = encodeState(1, mask, mul, add, strength);

            if (enc0 == enc1) continue;

            boolean ok = true;
            for (int i = 0; i < dummyCount; i++) {
                encDummy[i] = encodeState(100 + i, mask, mul, add, strength);
            }
            // all unique?
            Set<Integer> uniq = new HashSet<>();
            if (!uniq.add(enc0) || !uniq.add(enc1)) continue;
            for (int v : encDummy) if (!uniq.add(v)) { ok = false; break; }
            if (ok) break outer;
        }

        // === 5) Labels ===
        final LabelNode L_LOOP     = new LabelNode();
        final LabelNode L_CASE0    = new LabelNode();
        final LabelNode L_CASE1    = new LabelNode();
        final LabelNode L_DEFAULT  = new LabelNode();
        final LabelNode[] L_DUMMIES = new LabelNode[dummyCount];
        for (int i = 0; i < dummyCount; i++) L_DUMMIES[i] = new LabelNode();

        // === 6) Prologue: init state ===
        pushConst(mn.instructions, enc0);
        mn.instructions.add(new VarInsnNode(Opcodes.ISTORE, stateLocal));

        // === 7) Dispatch loop ===
        mn.instructions.add(L_LOOP);
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, stateLocal));

        // Build sorted lookup switch
        final int total = 2 + dummyCount;
        int[] keys = new int[total];
        LabelNode[] labels = new LabelNode[total];
        int k = 0;
        keys[k] = enc0; labels[k++] = L_CASE0;
        keys[k] = enc1; labels[k++] = L_CASE1;
        for (int i = 0; i < dummyCount; i++) {
            keys[k] = encDummy[i]; labels[k++] = L_DUMMIES[i];
        }
        // sort keys + labels together
        for (int i = 0; i < keys.length - 1; i++) {
            for (int j = i + 1; j < keys.length; j++) {
                if (Integer.compare(keys[i], keys[j]) > 0) {
                    int tk = keys[i]; keys[i] = keys[j]; keys[j] = tk;
                    LabelNode tl = labels[i]; labels[i] = labels[j]; labels[j] = tl;
                }
            }
        }
        mn.instructions.add(new LookupSwitchInsnNode(L_DEFAULT, keys, labels));

        // case 0 -> go to state 1
        mn.instructions.add(L_CASE0);
        emitTransition(mn.instructions, stateLocal, 1, mask, mul, add, strength);
        mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, L_LOOP));

        // case 1 -> original body
        mn.instructions.add(L_CASE1);
        // Insert cloned body; its try/catch ranges were remapped and already attached to mn.tryCatchBlocks.
        mn.instructions.add(bodyCopy);

        // dummy cases (noise then steer to state 1)
        for (int i = 0; i < dummyCount; i++) {
            mn.instructions.add(L_DUMMIES[i]);
            pushConst(mn.instructions, rnd.nextInt());
            mn.instructions.add(new InsnNode(Opcodes.POP));
            emitTransition(mn.instructions, stateLocal, 1, mask, mul, add, strength);
            mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, L_LOOP));
        }

        // default -> recover to state 1
        mn.instructions.add(L_DEFAULT);
        emitTransition(mn.instructions, stateLocal, 1, mask, mul, add, strength);
        mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, L_LOOP));

        // Let ClassWriter compute maxs/frames.
        mn.maxStack = Math.max(mn.maxStack, 4);
    }

    private static int encodeState(int raw, int mask, int mul, int add, JavaObfuscationConfig.Strength s) {
        switch (s) {
            case LOW:
                return raw;
            case MEDIUM:
                return (raw ^ mask) * mul + add;
            case HIGH: {
                int v = raw ^ Integer.rotateLeft(mask, 7);
                v = Integer.rotateLeft(v * (mul | 1), 3) + add;
                v ^= (v >>> 13);
                return v;
            }
            default:
                return raw;
        }
    }

    private static void emitTransition(InsnList il, int stateLocal, int nextRaw, int mask, int mul, int add,
                                       JavaObfuscationConfig.Strength s) {
        switch (s) {
            case LOW: {
                pushConst(il, nextRaw);
                il.add(new VarInsnNode(Opcodes.ISTORE, stateLocal));
                return;
            }
            case MEDIUM: {
                // ((nextRaw ^ mask) * mul + add)
                pushConst(il, nextRaw);
                pushConst(il, mask);
                il.add(new InsnNode(Opcodes.IXOR));
                pushConst(il, mul);
                il.add(new InsnNode(Opcodes.IMUL));
                pushConst(il, add);
                il.add(new InsnNode(Opcodes.IADD));
                il.add(new VarInsnNode(Opcodes.ISTORE, stateLocal));
                return;
            }
            case HIGH: {
                // VM-like transform with rotl and diffusion x ^= (x >>> 13)
                pushConst(il, nextRaw);
                pushConst(il, Integer.rotateLeft(mask, 7));
                il.add(new InsnNode(Opcodes.IXOR));
                pushConst(il, (mul | 1));
                il.add(new InsnNode(Opcodes.IMUL));

                // rotl(x, 3): (x << 3) | (x >>> 29)
                il.add(new InsnNode(Opcodes.DUP));
                pushConst(il, 3);
                il.add(new InsnNode(Opcodes.ISHL));
                il.add(new InsnNode(Opcodes.SWAP));
                pushConst(il, 29);
                il.add(new InsnNode(Opcodes.IUSHR));
                il.add(new InsnNode(Opcodes.IOR));

                pushConst(il, add);
                il.add(new InsnNode(Opcodes.IADD));

                il.add(new InsnNode(Opcodes.DUP));
                pushConst(il, 13);
                il.add(new InsnNode(Opcodes.IUSHR));
                il.add(new InsnNode(Opcodes.IXOR));
                il.add(new VarInsnNode(Opcodes.ISTORE, stateLocal));
                return;
            }
        }
    }

    // ===== Advanced multi-block flattening (HIGH strength) =====

    private static final class Block {
        final int id;
        final int startIndex;
        final int endIndex;
        final AbstractInsnNode startInsn;
        final AbstractInsnNode endInsn;
        Block(int id, int startIndex, int endIndex, AbstractInsnNode startInsn, AbstractInsnNode endInsn) {
            this.id = id;
            this.startIndex = startIndex;
            this.endIndex = endIndex;
            this.startInsn = startInsn;
            this.endInsn = endInsn;
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
    }

    private static void flattenMultiBlock(MethodNode mn, String methodId) {
        // Clone body and remap labels/try-catch/local variables
        final InsnList original = mn.instructions;
        final InsnList body = new InsnList();
        final Map<LabelNode, LabelNode> labelMap = new IdentityHashMap<>();
        for (AbstractInsnNode p = original.getFirst(); p != null; p = p.getNext()) {
            if (p instanceof LabelNode) labelMap.put((LabelNode) p, new LabelNode());
        }
        final List<AbstractInsnNode> cloned = new ArrayList<>();
        for (AbstractInsnNode p = original.getFirst(); p != null; p = p.getNext()) {
            AbstractInsnNode c = p.clone(labelMap);
            body.add(c);
            cloned.add(c);
        }

        final List<TryCatchBlockNode> oldTcbs = mn.tryCatchBlocks == null ? Collections.emptyList() : mn.tryCatchBlocks;
        // We'll rebuild try/catch nodes after emitting new instructions.
        final List<LocalVariableNode> oldLvs = mn.localVariables == null ? Collections.emptyList() : mn.localVariables;
        final List<LocalVariableNode> newLvs = new ArrayList<>(oldLvs.size());
        for (LocalVariableNode lv : oldLvs) {
            newLvs.add(new LocalVariableNode(lv.name, lv.desc, lv.signature,
                    labelMap.getOrDefault(lv.start, lv.start),
                    labelMap.getOrDefault(lv.end, lv.end), lv.index));
        }

        // Build indices and leaders
        final Map<LabelNode, Integer> labelToIndex = new IdentityHashMap<>();
        for (int i = 0; i < cloned.size(); i++) {
            if (cloned.get(i) instanceof LabelNode) labelToIndex.put((LabelNode) cloned.get(i), i);
        }
        final int n = cloned.size();
        final boolean[] isLeader = new boolean[n];
        if (n > 0) isLeader[0] = true;
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
                for (Object l : sw.labels) {
                    Integer li = labelToIndex.get((LabelNode) l);
                    if (li != null) isLeader[li] = true;
                }
            } else if (insn instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode sw = (TableSwitchInsnNode) insn;
                Integer di = labelToIndex.get(sw.dflt);
                if (di != null) isLeader[di] = true;
                for (Object l : sw.labels) {
                    Integer li = labelToIndex.get((LabelNode) l);
                    if (li != null) isLeader[li] = true;
                }
            } else if (isTerminator(op)) {
                if (i + 1 < n) isLeader[i + 1] = true;
            }
        }
        // try-catch specific leaders (from cloned labels)
        for (TryCatchBlockNode tcb : oldTcbs) {
            LabelNode startCl = labelMap.getOrDefault(tcb.start, tcb.start);
            LabelNode handlerCl = labelMap.getOrDefault(tcb.handler, tcb.handler);
            Integer s = labelToIndex.get(startCl);
            Integer h = labelToIndex.get(handlerCl);
            if (s != null) isLeader[s] = true;
            if (h != null) isLeader[h] = true;
        }
        final List<Integer> leaders = new ArrayList<>();
        for (int i = 0; i < n; i++) if (isLeader[i]) leaders.add(i);
        final List<Block> blocks = new ArrayList<>();
        for (int bi = 0; bi < leaders.size(); bi++) {
            int s = leaders.get(bi);
            int e = (bi + 1 < leaders.size() ? leaders.get(bi + 1) : n) - 1;
            if (e < s) e = s;
            blocks.add(new Block(bi, s, e, cloned.get(s), cloned.get(e)));
        }

        // Map label->blockId
        final Map<LabelNode, Integer> blockOfLabel = new IdentityHashMap<>();
        for (Block b : blocks) {
            LabelNode startLabel = (b.startInsn instanceof LabelNode) ? (LabelNode) b.startInsn : new LabelNode();
            if (!(b.startInsn instanceof LabelNode)) {
                body.insertBefore(b.startInsn, startLabel);
            }
            blockOfLabel.put(startLabel, b.id);
        }
        // Map every known label to its containing block id
        for (Map.Entry<LabelNode, Integer> en : labelToIndex.entrySet()) {
            int idx = en.getValue();
            int pos = Collections.binarySearch(leaders, idx);
            if (pos < 0) pos = -pos - 2; // greatest leader <= idx
            if (pos < 0) pos = 0;
            if (pos >= blocks.size()) pos = blocks.size() - 1;
            blockOfLabel.put(en.getKey(), pos);
        }

        // Per-block encoders and keys
        final int stateLocal = mn.maxLocals;
        mn.maxLocals = Math.max(mn.maxLocals, stateLocal + 1);
        int seed = (methodId == null ? (mn.name + mn.desc) : methodId).hashCode();
        Random rnd = new Random((((long) seed) * 6364136223846793005L + 0x9E3779B97F4A7C15L) ^ 0x13A5B6C7);
        final FormulaParams[] params = new FormulaParams[blocks.size()];
        final int[] keys = new int[blocks.size()];
        final LabelNode[] entryLabels = new LabelNode[blocks.size()];
        final LabelNode[] blockAfter = new LabelNode[blocks.size()];
        for (Block b : blocks) {
            FormulaParams fp; int key;
            retry:
            while (true) {
                int variant = rnd.nextInt(4);
                int mask = rnd.nextInt() | 1;
                int mul = rnd.nextInt() | 1;
                int add = rnd.nextInt();
                int r1 = 1 + rnd.nextInt(31);
                int r2 = 1 + rnd.nextInt(31);
                fp = new FormulaParams(variant, mask, mul, add, r1, r2);
                key = encodeWithFormula(b.id, fp);
                boolean collision = false;
                for (int i = 0; i < b.id; i++) if (keys[i] == key) { collision = true; break; }
                if (collision) continue retry;
                break;
            }
            params[b.id] = fp;
            keys[b.id] = key;
            entryLabels[b.id] = new LabelNode();
            blockAfter[b.id] = new LabelNode();
        }

        // Emit dispatcher
        mn.instructions = new InsnList();
        mn.tryCatchBlocks = new ArrayList<>();
        mn.localVariables = newLvs;

        LabelNode L_METHOD_START = new LabelNode();
        LabelNode L_DISPATCH = new LabelNode();
        LabelNode L_RECOVER = new LabelNode();

        // Prologue label
        mn.instructions.add(L_METHOD_START);
        pushConst(mn.instructions, keys[0]);
        mn.instructions.add(new VarInsnNode(Opcodes.ISTORE, stateLocal));
        mn.instructions.add(L_DISPATCH);
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, stateLocal));
        int[] sortedKeys = Arrays.stream(keys).sorted().toArray();
        LabelNode[] sortedEntry = new LabelNode[sortedKeys.length];
        for (int i = 0; i < sortedKeys.length; i++) {
            int k = sortedKeys[i];
            for (int j = 0; j < keys.length; j++) if (keys[j] == k) { sortedEntry[i] = entryLabels[j]; break; }
        }
        mn.instructions.add(new LookupSwitchInsnNode(L_RECOVER, sortedKeys, sortedEntry));
        mn.instructions.add(L_RECOVER);
        emitEncodedStoreForBlock(mn.instructions, stateLocal, 0, params[0]);
        mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, L_DISPATCH));

        // Map of emitted position labels corresponding to each cloned label
        final Map<LabelNode, LabelNode> emittedLabel = new IdentityHashMap<>();

        // Emit blocks
        for (Block b : blocks) {
            mn.instructions.add(entryLabels[b.id]);
            for (int i = b.startIndex; i <= b.endIndex; i++) {
                AbstractInsnNode insn = cloned.get(i);
                // Re-emit original labels at corresponding positions to keep try/catch ranges intact
                if (insn instanceof LabelNode) {
                    LabelNode lab = (LabelNode) insn;
                    LabelNode out = emittedLabel.get(lab);
                    if (out == null) { out = new LabelNode(); emittedLabel.put(lab, out); }
                    mn.instructions.add(out);
                    continue;
                }
                if (insn instanceof FrameNode) {
                    // Drop frames; ClassWriter will recompute
                    continue;
                }
                if (insn instanceof LineNumberNode) {
                    LineNumberNode ln = (LineNumberNode) insn;
                    LabelNode mapped = emittedLabel.get(ln.start);
                    if (mapped == null) { mapped = new LabelNode(); emittedLabel.put(ln.start, mapped); mn.instructions.add(mapped); }
                    mn.instructions.add(new LineNumberNode(ln.line, mapped));
                    continue;
                }
                if (insn == b.endInsn) {
                    int op = insn.getOpcode();
                    if (insn instanceof JumpInsnNode) {
                        JumpInsnNode j = (JumpInsnNode) insn;
                        Integer target = blockOfLabel.get(j.label);
                        if (target == null) target = 0;
                        if (hasFallThrough(op)) {
                            LabelNode L_TRUE = new LabelNode();
                            mn.instructions.add(new JumpInsnNode(op, L_TRUE));
                            // boundary after original IF
                            mn.instructions.add(blockAfter[b.id]);
                            int fall = (b.id + 1 < blocks.size()) ? b.id + 1 : b.id;
                            emitEncodedStoreForBlock(mn.instructions, stateLocal, fall, params[fall]);
                            mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, L_DISPATCH));
                            mn.instructions.add(L_TRUE);
                            emitEncodedStoreForBlock(mn.instructions, stateLocal, target, params[target]);
                            mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, L_DISPATCH));
                        } else {
                            // boundary after original GOTO
                            mn.instructions.add(blockAfter[b.id]);
                            emitEncodedStoreForBlock(mn.instructions, stateLocal, target, params[target]);
                            mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, L_DISPATCH));
                        }
                    } else if (insn instanceof LookupSwitchInsnNode) {
                        LookupSwitchInsnNode sw = (LookupSwitchInsnNode) insn;
                        LabelNode[] caseLabels = new LabelNode[sw.labels.size()];
                        for (int ci = 0; ci < caseLabels.length; ci++) caseLabels[ci] = new LabelNode();
                        LabelNode def = new LabelNode();
                        int[] caseKeys = new int[sw.keys.size()];
                        for (int ci = 0; ci < sw.keys.size(); ci++) caseKeys[ci] = (Integer) sw.keys.get(ci);
                        mn.instructions.add(new LookupSwitchInsnNode(def, caseKeys, caseLabels));
                        // boundary after original SWITCH
                        mn.instructions.add(blockAfter[b.id]);
                        for (int ci = 0; ci < caseLabels.length; ci++) {
                            mn.instructions.add(caseLabels[ci]);
                            Integer tb = blockOfLabel.get((LabelNode) sw.labels.get(ci));
                            if (tb == null) tb = 0;
                            emitEncodedStoreForBlock(mn.instructions, stateLocal, tb, params[tb]);
                            mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, L_DISPATCH));
                        }
                        mn.instructions.add(def);
                        Integer db = blockOfLabel.get(sw.dflt);
                        if (db == null) db = 0;
                        emitEncodedStoreForBlock(mn.instructions, stateLocal, db, params[db]);
                        mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, L_DISPATCH));
                    } else if (insn instanceof TableSwitchInsnNode) {
                        TableSwitchInsnNode sw = (TableSwitchInsnNode) insn;
                        LabelNode[] caseLabels = new LabelNode[sw.labels.size()];
                        for (int ci = 0; ci < caseLabels.length; ci++) caseLabels[ci] = new LabelNode();
                        LabelNode def = new LabelNode();
                        mn.instructions.add(new TableSwitchInsnNode(sw.min, sw.max, def, caseLabels));
                        // boundary after original SWITCH
                        mn.instructions.add(blockAfter[b.id]);
                        for (int ci = 0; ci < caseLabels.length; ci++) {
                            mn.instructions.add(caseLabels[ci]);
                            Integer tb = blockOfLabel.get((LabelNode) sw.labels.get(ci));
                            if (tb == null) tb = 0;
                            emitEncodedStoreForBlock(mn.instructions, stateLocal, tb, params[tb]);
                            mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, L_DISPATCH));
                        }
                        mn.instructions.add(def);
                        Integer db = blockOfLabel.get(sw.dflt);
                        if (db == null) db = 0;
                        emitEncodedStoreForBlock(mn.instructions, stateLocal, db, params[db]);
                        mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, L_DISPATCH));
                    } else if (isTerminator(op)) {
                        mn.instructions.add(insn.clone(emittedLabel));
                        // end of original block
                        mn.instructions.add(blockAfter[b.id]);
                    } else {
                        // end of original block
                        mn.instructions.add(blockAfter[b.id]);
                        int next = (b.id + 1 < blocks.size()) ? b.id + 1 : b.id;
                        emitEncodedStoreForBlock(mn.instructions, stateLocal, next, params[next]);
                        mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, L_DISPATCH));
                    }
                } else {
                    // For regular insns, clone with emitted label map (covers label refs in e.g. var scopes)
                    mn.instructions.add(insn.clone(emittedLabel));
                }
            }
        }

        // Rebuild try/catch blocks by mapping original labels to emitted labels (preserve exact handlers)
        final List<TryCatchBlockNode> rebuilt = new ArrayList<>(oldTcbs.size());
        for (TryCatchBlockNode tcb : oldTcbs) {
            LabelNode startCl = labelMap.getOrDefault(tcb.start, tcb.start);
            LabelNode endCl = labelMap.getOrDefault(tcb.end, tcb.end);
            LabelNode handlerCl = labelMap.getOrDefault(tcb.handler, tcb.handler);
            LabelNode startOut = emittedLabel.get(startCl);
            LabelNode endOut = emittedLabel.get(endCl);
            LabelNode handlerOut = emittedLabel.get(handlerCl);
            if (startOut == null) {
                Integer bi = blockOfLabel.get(startCl);
                if (bi != null) startOut = entryLabels[bi];
            }
            if (endOut == null) {
                Integer bi = blockOfLabel.get(endCl);
                if (bi != null) endOut = blockAfter[bi];
            }
            if (handlerOut == null) {
                // Try best effort: use exact entry point of handler's block
                Integer bi = blockOfLabel.get(handlerCl);
                if (bi != null) handlerOut = entryLabels[bi];
            }
            if (startOut != null && endOut != null && handlerOut != null && startOut != endOut) {
                rebuilt.add(new TryCatchBlockNode(startOut, endOut, handlerOut, tcb.type));
            }
        }
        mn.tryCatchBlocks = rebuilt;

        // Rebuild LocalVariableTable using emitted labels as well
        if (!oldLvs.isEmpty()) {
            final List<LocalVariableNode> rebuiltLvt = new ArrayList<>(oldLvs.size());
            for (LocalVariableNode lv : oldLvs) {
                LabelNode startCl = labelMap.getOrDefault(lv.start, lv.start);
                LabelNode endCl = labelMap.getOrDefault(lv.end, lv.end);
                LabelNode startOut = emittedLabel.get(startCl);
                LabelNode endOut = emittedLabel.get(endCl);
                if (startOut == null) {
                    Integer bi = blockOfLabel.get(startCl);
                    if (bi != null) startOut = entryLabels[bi];
                }
                if (endOut == null) {
                    Integer bi = blockOfLabel.get(endCl);
                    if (bi != null) endOut = blockAfter[bi];
                }
                if (startOut != null && endOut != null && startOut != endOut) {
                    rebuiltLvt.add(new LocalVariableNode(lv.name, lv.desc, lv.signature, startOut, endOut, lv.index));
                }
            }
            mn.localVariables = rebuiltLvt;
        }

        mn.maxStack = Math.max(mn.maxStack, 6);
    }

    private static boolean hasFallThrough(int opcode) {
        switch (opcode) {
            case Opcodes.IFEQ:
            case Opcodes.IFNE:
            case Opcodes.IFLT:
            case Opcodes.IFGE:
            case Opcodes.IFGT:
            case Opcodes.IFLE:
            case Opcodes.IF_ICMPEQ:
            case Opcodes.IF_ICMPNE:
            case Opcodes.IF_ICMPLT:
            case Opcodes.IF_ICMPGE:
            case Opcodes.IF_ICMPGT:
            case Opcodes.IF_ICMPLE:
            case Opcodes.IF_ACMPEQ:
            case Opcodes.IF_ACMPNE:
            case Opcodes.IFNULL:
            case Opcodes.IFNONNULL:
                return true;
            default:
                return false;
        }
    }

    private static boolean isTerminator(int opcode) {
        if (opcode == -1) return false;
        switch (opcode) {
            case Opcodes.GOTO:
            case Opcodes.RETURN:
            case Opcodes.IRETURN:
            case Opcodes.LRETURN:
            case Opcodes.FRETURN:
            case Opcodes.DRETURN:
            case Opcodes.ARETURN:
            case Opcodes.ATHROW:
                return true;
            default:
                return false;
        }
    }

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
                il.add(new InsnNode(Opcodes.IXOR));
                pushConst(il, (p.mul | 1));
                il.add(new InsnNode(Opcodes.IMUL));
                il.add(new InsnNode(Opcodes.DUP));
                pushConst(il, p.r1);
                il.add(new InsnNode(Opcodes.ISHL));
                il.add(new InsnNode(Opcodes.SWAP));
                pushConst(il, 32 - (p.r1 & 31));
                il.add(new InsnNode(Opcodes.IUSHR));
                il.add(new InsnNode(Opcodes.IOR));
                pushConst(il, p.add);
                il.add(new InsnNode(Opcodes.IADD));
                il.add(new InsnNode(Opcodes.DUP));
                pushConst(il, 13);
                il.add(new InsnNode(Opcodes.IUSHR));
                il.add(new InsnNode(Opcodes.IXOR));
                il.add(new VarInsnNode(Opcodes.ISTORE, stateLocal));
                return;
            }
            case 1: {
                // ((x + m) * a) ^ add; rotl(r2) ^ (k>>>11)
                pushConst(il, raw);
                pushConst(il, p.mask);
                il.add(new InsnNode(Opcodes.IADD));
                pushConst(il, (p.mul | 1));
                il.add(new InsnNode(Opcodes.IMUL));
                pushConst(il, p.add);
                il.add(new InsnNode(Opcodes.IXOR));
                il.add(new InsnNode(Opcodes.DUP));
                pushConst(il, p.r2);
                il.add(new InsnNode(Opcodes.ISHL));
                il.add(new InsnNode(Opcodes.SWAP));
                pushConst(il, 32 - (p.r2 & 31));
                il.add(new InsnNode(Opcodes.IUSHR));
                il.add(new InsnNode(Opcodes.IOR));
                il.add(new InsnNode(Opcodes.DUP));
                pushConst(il, 11);
                il.add(new InsnNode(Opcodes.IUSHR));
                il.add(new InsnNode(Opcodes.IXOR));
                il.add(new VarInsnNode(Opcodes.ISTORE, stateLocal));
                return;
            }
            case 2: {
                // x ^ rotl(mask*(x|1), r1); (k + add) * a; k ^= (k>>>7)
                pushConst(il, raw);
                il.add(new InsnNode(Opcodes.DUP));
                pushConst(il, 1);
                il.add(new InsnNode(Opcodes.IOR));
                pushConst(il, p.mask);
                il.add(new InsnNode(Opcodes.IMUL));
                il.add(new InsnNode(Opcodes.DUP));
                pushConst(il, p.r1);
                il.add(new InsnNode(Opcodes.ISHL));
                il.add(new InsnNode(Opcodes.SWAP));
                pushConst(il, 32 - (p.r1 & 31));
                il.add(new InsnNode(Opcodes.IUSHR));
                il.add(new InsnNode(Opcodes.IOR));
                il.add(new InsnNode(Opcodes.IXOR));
                pushConst(il, p.add);
                il.add(new InsnNode(Opcodes.IADD));
                pushConst(il, (p.mul | 1));
                il.add(new InsnNode(Opcodes.IMUL));
                il.add(new InsnNode(Opcodes.DUP));
                pushConst(il, 7);
                il.add(new InsnNode(Opcodes.IUSHR));
                il.add(new InsnNode(Opcodes.IXOR));
                il.add(new VarInsnNode(Opcodes.ISTORE, stateLocal));
                return;
            }
            default: {
                // rotl(x + add, r2) * a; ^mask; ^(k>>>15)
                pushConst(il, raw);
                pushConst(il, p.add);
                il.add(new InsnNode(Opcodes.IADD));
                il.add(new InsnNode(Opcodes.DUP));
                pushConst(il, p.r2);
                il.add(new InsnNode(Opcodes.ISHL));
                il.add(new InsnNode(Opcodes.SWAP));
                pushConst(il, 32 - (p.r2 & 31));
                il.add(new InsnNode(Opcodes.IUSHR));
                il.add(new InsnNode(Opcodes.IOR));
                pushConst(il, (p.mul | 1));
                il.add(new InsnNode(Opcodes.IMUL));
                pushConst(il, p.mask);
                il.add(new InsnNode(Opcodes.IXOR));
                il.add(new InsnNode(Opcodes.DUP));
                pushConst(il, 15);
                il.add(new InsnNode(Opcodes.IUSHR));
                il.add(new InsnNode(Opcodes.IXOR));
                il.add(new VarInsnNode(Opcodes.ISTORE, stateLocal));
            }
        }
    }

    private static void pushConst(InsnList il, int v) {
        if (v >= -1 && v <= 5) {
            switch (v) {
                case -1: il.add(new InsnNode(Opcodes.ICONST_M1)); return;
                case 0:  il.add(new InsnNode(Opcodes.ICONST_0));  return;
                case 1:  il.add(new InsnNode(Opcodes.ICONST_1));  return;
                case 2:  il.add(new InsnNode(Opcodes.ICONST_2));  return;
                case 3:  il.add(new InsnNode(Opcodes.ICONST_3));  return;
                case 4:  il.add(new InsnNode(Opcodes.ICONST_4));  return;
                case 5:  il.add(new InsnNode(Opcodes.ICONST_5));  return;
            }
        }
        if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
            il.add(new IntInsnNode(Opcodes.BIPUSH, v));
        } else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
            il.add(new IntInsnNode(Opcodes.SIPUSH, v));
        } else {
            il.add(new LdcInsnNode(v));
        }
    }

    // ===== Safety analysis for multi-block flattening =====

    private static boolean canSafelyMultiBlock(MethodNode mn) {
        // Try/catch present -> not safe for multi-block in this pass
        if (mn.tryCatchBlocks != null && !mn.tryCatchBlocks.isEmpty()) return false;

        // Build index mapping for labels and instructions
        final List<AbstractInsnNode> list = new ArrayList<>();
        for (AbstractInsnNode p = mn.instructions.getFirst(); p != null; p = p.getNext()) list.add(p);
        final int n = list.size();
        if (n == 0) return false;

        final IdentityHashMap<LabelNode, Integer> labelIndex = new IdentityHashMap<>();
        for (int i = 0; i < n; i++) if (list.get(i) instanceof LabelNode) labelIndex.put((LabelNode) list.get(i), i);

        // Compute leaders with same rules as flattenMultiBlock
        final boolean[] isLeader = new boolean[n];
        isLeader[0] = true;
        for (int i = 0; i < n; i++) {
            AbstractInsnNode insn = list.get(i);
            int op = insn.getOpcode();
            if (insn instanceof JumpInsnNode) {
                JumpInsnNode j = (JumpInsnNode) insn;
                Integer ti = labelIndex.get(j.label);
                if (ti != null) isLeader[ti] = true;
                if (hasFallThrough(op) && i + 1 < n) isLeader[i + 1] = true;
            } else if (insn instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode sw = (LookupSwitchInsnNode) insn;
                Integer di = labelIndex.get(sw.dflt);
                if (di != null) isLeader[di] = true;
                for (Object l : sw.labels) {
                    Integer li = labelIndex.get((LabelNode) l);
                    if (li != null) isLeader[li] = true;
                }
            } else if (insn instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode sw = (TableSwitchInsnNode) insn;
                Integer di = labelIndex.get(sw.dflt);
                if (di != null) isLeader[di] = true;
                for (Object l : sw.labels) {
                    Integer li = labelIndex.get((LabelNode) l);
                    if (li != null) isLeader[li] = true;
                }
            } else if (isTerminator(op)) {
                if (i + 1 < n) isLeader[i + 1] = true; // unreachable in original, but mark for segmentation
            }
        }

        // Analyze frames to get stack depth at leaders
        final Frame<BasicValue>[] frames;
        try {
            Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicInterpreter());
            // owner is not used by BasicInterpreter beyond NEW/INIT linking; a placeholder is fine
            frames = analyzer.analyze("java/lang/Object", mn);
        } catch (AnalyzerException ex) {
            return false;
        }

        for (int i = 0; i < n; i++) {
            if (!isLeader[i]) continue;
            Frame<BasicValue> f = frames[i];
            if (f == null) {
                // Unreachable leader in original code: to stay safe, don't create a dispatcher entry
                // for it. Since current multi-block implementation would still segment here, fail-safe.
                return false;
            }
            if (f.getStackSize() != 0) {
                // Dispatcher would enter with empty stack; non-zero expected -> unsafe
                return false;
            }
        }
        return true;
    }
}
