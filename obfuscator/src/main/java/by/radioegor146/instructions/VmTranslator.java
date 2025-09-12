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

    /** Representation of a VM instruction. */
    public static class Instruction {
        public final int opcode;
        public final int operand;

        public Instruction(int opcode, int operand) {
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
                case Opcodes.IADD:
                    result.add(new Instruction(VmOpcodes.OP_ADD, 0));
                    break;
                case Opcodes.ISUB:
                    result.add(new Instruction(VmOpcodes.OP_SUB, 0));
                    break;
                case Opcodes.IMUL:
                    result.add(new Instruction(VmOpcodes.OP_MUL, 0));
                    break;
                case Opcodes.IDIV:
                    result.add(new Instruction(VmOpcodes.OP_DIV, 0));
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
                case Opcodes.ISTORE:
                    result.add(new Instruction(VmOpcodes.OP_STORE, ((VarInsnNode) insn).var));
                    break;
                case 59: // ISTORE_0
                case 60: // ISTORE_1
                case 61: // ISTORE_2
                case 62: // ISTORE_3
                    result.add(new Instruction(VmOpcodes.OP_STORE, opcode - 59));
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
            sb.append(String.format("{ %d, %d, 0ULL }", ins.opcode, (long) ins.operand));
            if (i + 1 < code.length) sb.append(", ");
        }
        sb.append('}');
        return sb.toString();
    }
}

