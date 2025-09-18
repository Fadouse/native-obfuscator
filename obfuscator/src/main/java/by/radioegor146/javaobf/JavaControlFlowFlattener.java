package by.radioegor146.javaobf;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

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
}
