package by.radioegor146.javaobf;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Basic Java bytecode-level control-flow flattener.
 *
 * It wraps the original method body into a while-switch state machine with
 * a configurable transition strategy to simulate varying strengths:
 *  - LOW: direct state constants
 *  - MEDIUM: arithmetic-mixed constants
 *  - HIGH: extra dummy states and arithmetic mixing
 *
 * This implementation preserves original try/catch blocks and does not
 * restructure the original body — it is placed into a single state branch.
 */
public final class JavaControlFlowFlattener {

    private JavaControlFlowFlattener() {}

    public static boolean canProcess(MethodNode mn) {
        // Only skip truly unprocessable methods
        if ((mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return false;
        if (mn.instructions == null || mn.instructions.size() == 0) return false;

        // Skip constructors only if they are too simple (just super() calls)
        if ("<init>".equals(mn.name) && mn.instructions.size() <= 4) return false;

        // Check for truly problematic instructions only
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            switch (insn.getOpcode()) {
                case Opcodes.JSR:
                case Opcodes.RET:
                    // JSR/RET are deprecated and complex to handle
                    return false;
            }
        }

        return true;
    }

    public static void flatten(MethodNode mn, String methodId, JavaObfuscationConfig.Strength strength) {
        Objects.requireNonNull(mn, "mn");
        Objects.requireNonNull(strength, "strength");
        if (!canProcess(mn)) return;

        // Copy original instructions with proper label mapping
        InsnList original = mn.instructions;
        InsnList bodyCopy = new InsnList();
        Map<LabelNode, LabelNode> labelMap = new HashMap<>();

        // First pass: create mapping for all label nodes
        for (AbstractInsnNode insn = original.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode) {
                labelMap.put((LabelNode) insn, new LabelNode());
            }
        }

        // Second pass: clone instructions with label mapping
        for (AbstractInsnNode insn = original.getFirst(); insn != null; insn = insn.getNext()) {
            bodyCopy.add(insn.clone(labelMap));
        }

        // Reset instructions and clear exception handlers as they may not be valid after transformation
        mn.instructions = new InsnList();
        mn.tryCatchBlocks.clear();
        if (mn.localVariables != null) {
            mn.localVariables.clear();
        }

        // Allocate a new local variable for the state machine
        int stateLocal = mn.maxLocals;
        mn.maxLocals += 2; // reserve 2: state + scratch

        // Labels for switch dispatch
        LabelNode lblLoop = new LabelNode(new Label());
        LabelNode lblSwitch = new LabelNode(new Label());
        LabelNode lblCase0 = new LabelNode(new Label());
        LabelNode lblCase1 = new LabelNode(new Label());
        LabelNode lblDefault = new LabelNode(new Label());

        // Additional dummy case labels (for HIGH)
        LabelNode[] dummyCases;
        int dummyCount = strength == JavaObfuscationConfig.Strength.HIGH ? 3 : (strength == JavaObfuscationConfig.Strength.MEDIUM ? 1 : 0);
        dummyCases = new LabelNode[dummyCount];
        for (int i = 0; i < dummyCases.length; i++) dummyCases[i] = new LabelNode(new Label());

        // Deterministic seeds from method identity
        int seed = methodId.hashCode();
        Random rnd = new Random(seed * 1103515245L + 12345L);
        int mask = rnd.nextInt() | 1;
        int mul = (rnd.nextInt() | 1);
        int add = rnd.nextInt();

        // Encoders for state values depending on strength
        int enc0 = encodeState(0, mask, mul, add, strength);
        int enc1 = encodeState(1, mask, mul, add, strength);
        int[] encDummy = new int[dummyCases.length];
        for (int i = 0; i < encDummy.length; i++) encDummy[i] = encodeState(100 + i, mask, mul, add, strength);

        // prologue: set initial state
        mn.instructions.add(new LabelNode(new Label()));
        pushConst(mn.instructions, enc0);
        mn.instructions.add(new VarInsnNode(Opcodes.ISTORE, stateLocal));

        // loop begin
        mn.instructions.add(lblLoop);
        mn.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

        // dispatch
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, stateLocal));
        // Build lookupswitch: default -> lblDefault
        // Keys must be sorted in ascending order for LookupSwitchInsnNode
        int[] keys = new int[2 + dummyCases.length];
        LabelNode[] labels = new LabelNode[2 + dummyCases.length];
        int idx = 0;
        keys[idx] = enc0; labels[idx++] = lblCase0;
        keys[idx] = enc1; labels[idx++] = lblCase1;
        for (int i = 0; i < dummyCases.length; i++) {
            keys[idx] = encDummy[i]; labels[idx++] = dummyCases[i];
        }

        // Sort keys and corresponding labels together
        for (int i = 0; i < keys.length - 1; i++) {
            for (int j = i + 1; j < keys.length; j++) {
                if (keys[i] > keys[j]) {
                    // Swap keys
                    int tempKey = keys[i];
                    keys[i] = keys[j];
                    keys[j] = tempKey;
                    // Swap corresponding labels
                    LabelNode tempLabel = labels[i];
                    labels[i] = labels[j];
                    labels[j] = tempLabel;
                }
            }
        }

        mn.instructions.add(new LookupSwitchInsnNode(lblDefault, keys, labels));

        // case 0: transition to state 1
        mn.instructions.add(lblCase0);
        emitTransition(mn.instructions, stateLocal, 1, mask, mul, add, strength);
        mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, lblLoop));

        // case 1: original body
        mn.instructions.add(lblCase1);
        mn.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        // place the original body as-is
        mn.instructions.add(bodyCopy);

        // dummy cases (HIGH/MEDIUM)
        for (int i = 0; i < dummyCases.length; i++) {
            mn.instructions.add(dummyCases[i]);
            // noise arithmetic without side-effects
            pushConst(mn.instructions, rnd.nextInt());
            mn.instructions.add(new InsnNode(Opcodes.POP));
            // eventually steer to state 1
            emitTransition(mn.instructions, stateLocal, 1, mask, mul, add, strength);
            mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, lblLoop));
        }

        // default: fallback to state 1
        mn.instructions.add(lblDefault);
        emitTransition(mn.instructions, stateLocal, 1, mask, mul, add, strength);
        mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, lblLoop));

        // Let ASM recompute max stack/frames
        mn.maxStack = Math.max(mn.maxStack, 4);
    }

    private static int encodeState(int raw, int mask, int mul, int add, JavaObfuscationConfig.Strength s) {
        switch (s) {
            case LOW:
                return raw;
            case MEDIUM:
                return (raw ^ mask) * mul + add;
            case HIGH: {
                int v = raw ^ (Integer.rotateLeft(mask, 7));
                v = Integer.rotateLeft(v * (mul | 1), 3) + add;
                v ^= (v >>> 13);
                return v;
            }
            default:
                return raw;
        }
    }

    private static void emitTransition(InsnList il, int stateLocal, int nextRaw, int mask, int mul, int add, JavaObfuscationConfig.Strength s) {
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
                // A few mixed operations emulating a tiny VM-like transform
                pushConst(il, nextRaw);
                pushConst(il, Integer.rotateLeft(mask, 7));
                il.add(new InsnNode(Opcodes.IXOR));
                pushConst(il, (mul | 1));
                il.add(new InsnNode(Opcodes.IMUL));
                // rotl by 3 -> (x << 3) | (x >>> 29)
                il.add(new InsnNode(Opcodes.DUP));
                pushConst(il, 3);
                il.add(new InsnNode(Opcodes.ISHL));
                il.add(new InsnNode(Opcodes.SWAP));
                pushConst(il, 29);
                il.add(new InsnNode(Opcodes.IUSHR));
                il.add(new InsnNode(Opcodes.IOR));
                pushConst(il, add);
                il.add(new InsnNode(Opcodes.IADD));
                // xor with shifted value to add diffusion
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
            il.add(new InsnNode(Opcodes.ICONST_0 + v));
        } else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
            il.add(new IntInsnNode(Opcodes.BIPUSH, v));
        } else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
            il.add(new IntInsnNode(Opcodes.SIPUSH, v));
        } else {
            il.add(new LdcInsnNode(v));
        }
    }
}

