package by.radioegor146.instructions;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Translates a limited subset of JVM bytecode instructions into the
 * custom micro VM instruction set.  If an unsupported instruction is
 * encountered the translator returns {@code null} to signal that the
 * caller should fall back to the normal native generation path.
 */
public class VmTranslator {

    private boolean useJit;

    public VmTranslator() {
        this(false);
    }

    public VmTranslator(boolean useJit) {
        this.useJit = useJit;
    }

    public boolean isUseJit() {
        return useJit;
    }

    public void setUseJit(boolean useJit) {
        this.useJit = useJit;
    }

    /** Representation of a VM instruction. */
    public static class Instruction {
        public final int opcode;
        public final long operand;

        public Instruction(int opcode, long operand) {
            this.opcode = opcode;
            this.operand = operand;
        }
    }

    /** Constants mirroring native_jvm::vm::OpCode. */
    public static class VmOpcodes {
        public static final int OP_PUSH = 0;
        public static final int OP_ADD = 1;
        public static final int OP_SUB = 2;
        public static final int OP_MUL = 3;
        public static final int OP_DIV = 4;
        public static final int OP_PRINT = 5;
        public static final int OP_HALT = 6;
        public static final int OP_NOP = 7;
        public static final int OP_JUNK1 = 8;
        public static final int OP_JUNK2 = 9;
        public static final int OP_SWAP = 10;
        public static final int OP_DUP = 11;
        public static final int OP_LOAD = 12;
        public static final int OP_IF_ICMPEQ = 13;
        public static final int OP_IF_ICMPNE = 14;
        public static final int OP_GOTO = 15;
        public static final int OP_STORE = 16;
        public static final int OP_AND = 17;
        public static final int OP_OR = 18;
        public static final int OP_XOR = 19;
        public static final int OP_SHL = 20;
        public static final int OP_SHR = 21;
        public static final int OP_USHR = 22;
        public static final int OP_IF_ICMPLT = 23;
        public static final int OP_IF_ICMPLE = 24;
        public static final int OP_IF_ICMPGT = 25;
        public static final int OP_IF_ICMPGE = 26;
        public static final int OP_I2L = 27;
        public static final int OP_I2B = 28;
        public static final int OP_I2C = 29;
        public static final int OP_I2S = 30;
        public static final int OP_NEG = 31;
        public static final int OP_ALOAD = 32;
        public static final int OP_ASTORE = 33;
        public static final int OP_AALOAD = 34;
        public static final int OP_AASTORE = 35;
        public static final int OP_INVOKESTATIC = 36;
        public static final int OP_LLOAD = 37;
        public static final int OP_FLOAD = 38;
        public static final int OP_DLOAD = 39;
        public static final int OP_LSTORE = 40;
        public static final int OP_FSTORE = 41;
        public static final int OP_DSTORE = 42;
        public static final int OP_LADD = 43;
        public static final int OP_LSUB = 44;
        public static final int OP_LMUL = 45;
        public static final int OP_LDIV = 46;
        public static final int OP_FADD = 47;
        public static final int OP_FSUB = 48;
        public static final int OP_FMUL = 49;
        public static final int OP_FDIV = 50;
        public static final int OP_DADD = 51;
        public static final int OP_DSUB = 52;
        public static final int OP_DMUL = 53;
        public static final int OP_DDIV = 54;
        public static final int OP_LDC = 55;
        public static final int OP_LDC_W = 56;
        public static final int OP_LDC2_W = 57;
        public static final int OP_FCONST_0 = 58;
        public static final int OP_FCONST_1 = 59;
        public static final int OP_FCONST_2 = 60;
        public static final int OP_DCONST_0 = 61;
        public static final int OP_DCONST_1 = 62;
        public static final int OP_LCONST_0 = 63;
        public static final int OP_LCONST_1 = 64;
    }

    /**
     * Attempts to translate the provided method.  On success an array of
     * VM instructions is returned.  On failure {@code null} is returned
     * so that the caller can provide a fallback implementation.
     */
    public Instruction[] translate(MethodNode method) {
        Map<LabelNode, Integer> labelIds = new HashMap<>();
        int index = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode) {
                labelIds.put((LabelNode) insn, index);
            } else if (!(insn instanceof LineNumberNode) && !(insn instanceof FrameNode)) {
                index++;
            }
        }

        List<Instruction> result = new ArrayList<>();
        int invokeIndex = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            switch (opcode) {
                case Opcodes.ILOAD:
                    result.add(new Instruction(VmOpcodes.OP_LOAD, ((VarInsnNode) insn).var));
                    break;
                case 26: // ILOAD_0
                case 27: // ILOAD_1
                case 28: // ILOAD_2
                case 29: // ILOAD_3
                    result.add(new Instruction(VmOpcodes.OP_LOAD, opcode - 26));
                    break;
                case Opcodes.LLOAD:
                    result.add(new Instruction(VmOpcodes.OP_LLOAD, ((VarInsnNode) insn).var));
                    break;
                case 30: // LLOAD_0
                case 31: // LLOAD_1
                case 32: // LLOAD_2
                case 33: // LLOAD_3
                    result.add(new Instruction(VmOpcodes.OP_LLOAD, opcode - 30));
                    break;
                case Opcodes.FLOAD:
                    result.add(new Instruction(VmOpcodes.OP_FLOAD, ((VarInsnNode) insn).var));
                    break;
                case 34: // FLOAD_0
                case 35: // FLOAD_1
                case 36: // FLOAD_2
                case 37: // FLOAD_3
                    result.add(new Instruction(VmOpcodes.OP_FLOAD, opcode - 34));
                    break;
                case Opcodes.DLOAD:
                    result.add(new Instruction(VmOpcodes.OP_DLOAD, ((VarInsnNode) insn).var));
                    break;
                case 38: // DLOAD_0
                case 39: // DLOAD_1
                case 40: // DLOAD_2
                case 41: // DLOAD_3
                    result.add(new Instruction(VmOpcodes.OP_DLOAD, opcode - 38));
                    break;
                case Opcodes.IADD:
                    result.add(new Instruction(VmOpcodes.OP_ADD, 0));
                    break;
                case Opcodes.LADD:
                    result.add(new Instruction(VmOpcodes.OP_LADD, 0));
                    break;
                case Opcodes.FADD:
                    result.add(new Instruction(VmOpcodes.OP_FADD, 0));
                    break;
                case Opcodes.DADD:
                    result.add(new Instruction(VmOpcodes.OP_DADD, 0));
                    break;
                case Opcodes.ISUB:
                    result.add(new Instruction(VmOpcodes.OP_SUB, 0));
                    break;
                case Opcodes.LSUB:
                    result.add(new Instruction(VmOpcodes.OP_LSUB, 0));
                    break;
                case Opcodes.FSUB:
                    result.add(new Instruction(VmOpcodes.OP_FSUB, 0));
                    break;
                case Opcodes.DSUB:
                    result.add(new Instruction(VmOpcodes.OP_DSUB, 0));
                    break;
                case Opcodes.IMUL:
                    result.add(new Instruction(VmOpcodes.OP_MUL, 0));
                    break;
                case Opcodes.LMUL:
                    result.add(new Instruction(VmOpcodes.OP_LMUL, 0));
                    break;
                case Opcodes.FMUL:
                    result.add(new Instruction(VmOpcodes.OP_FMUL, 0));
                    break;
                case Opcodes.DMUL:
                    result.add(new Instruction(VmOpcodes.OP_DMUL, 0));
                    break;
                case Opcodes.IDIV:
                    result.add(new Instruction(VmOpcodes.OP_DIV, 0));
                    break;
                case Opcodes.LDIV:
                    result.add(new Instruction(VmOpcodes.OP_LDIV, 0));
                    break;
                case Opcodes.FDIV:
                    result.add(new Instruction(VmOpcodes.OP_FDIV, 0));
                    break;
                case Opcodes.DDIV:
                    result.add(new Instruction(VmOpcodes.OP_DDIV, 0));
                    break;
                case Opcodes.IAND:
                    result.add(new Instruction(VmOpcodes.OP_AND, 0));
                    break;
                case Opcodes.IOR:
                    result.add(new Instruction(VmOpcodes.OP_OR, 0));
                    break;
                case Opcodes.IXOR:
                    result.add(new Instruction(VmOpcodes.OP_XOR, 0));
                    break;
                case Opcodes.ISHL:
                    result.add(new Instruction(VmOpcodes.OP_SHL, 0));
                    break;
                case Opcodes.ISHR:
                    result.add(new Instruction(VmOpcodes.OP_SHR, 0));
                    break;
                case Opcodes.IUSHR:
                    result.add(new Instruction(VmOpcodes.OP_USHR, 0));
                    break;
                case Opcodes.ALOAD:
                    result.add(new Instruction(VmOpcodes.OP_ALOAD, ((VarInsnNode) insn).var));
                    break;
                case 42: // ALOAD_0
                case 43: // ALOAD_1
                case 44: // ALOAD_2
                case 45: // ALOAD_3
                    result.add(new Instruction(VmOpcodes.OP_ALOAD, opcode - 42));
                    break;
                case Opcodes.ASTORE:
                    result.add(new Instruction(VmOpcodes.OP_ASTORE, ((VarInsnNode) insn).var));
                    break;
                case 75: // ASTORE_0
                case 76: // ASTORE_1
                case 77: // ASTORE_2
                case 78: // ASTORE_3
                    result.add(new Instruction(VmOpcodes.OP_ASTORE, opcode - 75));
                    break;
                case Opcodes.AALOAD:
                    result.add(new Instruction(VmOpcodes.OP_AALOAD, 0));
                    break;
                case Opcodes.AASTORE:
                    result.add(new Instruction(VmOpcodes.OP_AASTORE, 0));
                    break;
                case Opcodes.BIPUSH:
                case Opcodes.SIPUSH:
                    result.add(new Instruction(VmOpcodes.OP_PUSH, ((IntInsnNode) insn).operand));
                    break;
                case Opcodes.ICONST_M1:
                case Opcodes.ICONST_0:
                case Opcodes.ICONST_1:
                case Opcodes.ICONST_2:
                case Opcodes.ICONST_3:
                case Opcodes.ICONST_4:
                case Opcodes.ICONST_5:
                    int val = opcode - Opcodes.ICONST_0;
                    if (opcode == Opcodes.ICONST_M1) val = -1;
                    result.add(new Instruction(VmOpcodes.OP_PUSH, val));
                    break;
                case Opcodes.LCONST_0:
                    result.add(new Instruction(VmOpcodes.OP_LCONST_0, 0));
                    break;
                case Opcodes.LCONST_1:
                    result.add(new Instruction(VmOpcodes.OP_LCONST_1, 0));
                    break;
                case Opcodes.FCONST_0:
                    result.add(new Instruction(VmOpcodes.OP_FCONST_0, 0));
                    break;
                case Opcodes.FCONST_1:
                    result.add(new Instruction(VmOpcodes.OP_FCONST_1, 0));
                    break;
                case Opcodes.FCONST_2:
                    result.add(new Instruction(VmOpcodes.OP_FCONST_2, 0));
                    break;
                case Opcodes.DCONST_0:
                    result.add(new Instruction(VmOpcodes.OP_DCONST_0, 0));
                    break;
                case Opcodes.DCONST_1:
                    result.add(new Instruction(VmOpcodes.OP_DCONST_1, 0));
                    break;
                case Opcodes.LDC:
                    Object cst = ((LdcInsnNode) insn).cst;
                    if (cst instanceof Integer) {
                        result.add(new Instruction(VmOpcodes.OP_LDC, (Integer) cst));
                    } else if (cst instanceof Float) {
                        result.add(new Instruction(VmOpcodes.OP_LDC, Float.floatToIntBits((Float) cst)));
                    } else if (cst instanceof Long) {
                        result.add(new Instruction(VmOpcodes.OP_LDC2_W, (Long) cst));
                    } else if (cst instanceof Double) {
                        result.add(new Instruction(VmOpcodes.OP_LDC2_W, Double.doubleToLongBits((Double) cst)));
                    } else {
                        return null; // unsupported constant
                    }
                    break;
                case Opcodes.ISTORE:
                    result.add(new Instruction(VmOpcodes.OP_STORE, ((VarInsnNode) insn).var));
                    break;
                case 59: // ISTORE_0
                case 60: // ISTORE_1
                case 61: // ISTORE_2
                case 62: // ISTORE_3
                    result.add(new Instruction(VmOpcodes.OP_STORE, opcode - 59));
                    break;
                case Opcodes.LSTORE:
                    result.add(new Instruction(VmOpcodes.OP_LSTORE, ((VarInsnNode) insn).var));
                    break;
                case 63: // LSTORE_0
                case 64: // LSTORE_1
                case 65: // LSTORE_2
                case 66: // LSTORE_3
                    result.add(new Instruction(VmOpcodes.OP_LSTORE, opcode - 63));
                    break;
                case Opcodes.FSTORE:
                    result.add(new Instruction(VmOpcodes.OP_FSTORE, ((VarInsnNode) insn).var));
                    break;
                case 67: // FSTORE_0
                case 68: // FSTORE_1
                case 69: // FSTORE_2
                case 70: // FSTORE_3
                    result.add(new Instruction(VmOpcodes.OP_FSTORE, opcode - 67));
                    break;
                case Opcodes.DSTORE:
                    result.add(new Instruction(VmOpcodes.OP_DSTORE, ((VarInsnNode) insn).var));
                    break;
                case 71: // DSTORE_0
                case 72: // DSTORE_1
                case 73: // DSTORE_2
                case 74: // DSTORE_3
                    result.add(new Instruction(VmOpcodes.OP_DSTORE, opcode - 71));
                    break;
                case Opcodes.GOTO:
                    result.add(new Instruction(VmOpcodes.OP_GOTO, labelIds.get(((JumpInsnNode) insn).label)));
                    break;
                case Opcodes.IF_ICMPEQ:
                    result.add(new Instruction(VmOpcodes.OP_IF_ICMPEQ, labelIds.get(((JumpInsnNode) insn).label)));
                    break;
                case Opcodes.IF_ICMPNE:
                    result.add(new Instruction(VmOpcodes.OP_IF_ICMPNE, labelIds.get(((JumpInsnNode) insn).label)));
                    break;
                case Opcodes.IF_ICMPLT:
                    result.add(new Instruction(VmOpcodes.OP_IF_ICMPLT, labelIds.get(((JumpInsnNode) insn).label)));
                    break;
                case Opcodes.IF_ICMPLE:
                    result.add(new Instruction(VmOpcodes.OP_IF_ICMPLE, labelIds.get(((JumpInsnNode) insn).label)));
                    break;
                case Opcodes.IF_ICMPGT:
                    result.add(new Instruction(VmOpcodes.OP_IF_ICMPGT, labelIds.get(((JumpInsnNode) insn).label)));
                    break;
                case Opcodes.IF_ICMPGE:
                    result.add(new Instruction(VmOpcodes.OP_IF_ICMPGE, labelIds.get(((JumpInsnNode) insn).label)));
                    break;
                case Opcodes.IRETURN:
                case Opcodes.LRETURN:
                case Opcodes.FRETURN:
                case Opcodes.DRETURN:
                    result.add(new Instruction(VmOpcodes.OP_HALT, 0));
                    break;
                case Opcodes.ARETURN:
                    result.add(new Instruction(VmOpcodes.OP_HALT, 0));
                    break;
                case Opcodes.I2B:
                    result.add(new Instruction(VmOpcodes.OP_I2B, 0));
                    break;
                case Opcodes.I2C:
                    result.add(new Instruction(VmOpcodes.OP_I2C, 0));
                    break;
                case Opcodes.I2S:
                    result.add(new Instruction(VmOpcodes.OP_I2S, 0));
                    break;
                case Opcodes.I2L:
                    result.add(new Instruction(VmOpcodes.OP_I2L, 0));
                    break;
                case Opcodes.INEG:
                    result.add(new Instruction(VmOpcodes.OP_NEG, 0));
                    break;
                case Opcodes.ACONST_NULL:
                    result.add(new Instruction(VmOpcodes.OP_PUSH, 0));
                    break;
                case Opcodes.INVOKESTATIC:
                    result.add(new Instruction(VmOpcodes.OP_INVOKESTATIC, invokeIndex++));
                    break;
                case -1: // labels/frames/lines
                    break;
                default:
                    return null; // unsupported instruction
            }
        }
        if (result.isEmpty()) {
            return null;
        }
        return result.toArray(new Instruction[0]);
    }

    /** Serializes VM instructions into a C++ initializer string. */
    public static String serialize(Instruction[] code) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        for (int i = 0; i < code.length; i++) {
            Instruction ins = code[i];
            sb.append(String.format("{ %d, %d, 0ULL }", ins.opcode, ins.operand));
            if (i + 1 < code.length) sb.append(", ");
        }
        sb.append('}');
        return sb.toString();
    }
}

