package by.radioegor146;

import by.radioegor146.instructions.VmTranslator;
import by.radioegor146.instructions.VmTranslator.Instruction;
import by.radioegor146.instructions.VmTranslator.VmOpcodes;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class VmTranslatorStackOpTest {

    private long run(Instruction[] code, long[] locals) {
        long[] stack = new long[256];
        int sp = 0;
        for (Instruction ins : code) {
            switch (ins.opcode) {
                case VmOpcodes.OP_PUSH:
                    stack[sp++] = ins.operand;
                    break;
                case VmOpcodes.OP_DUP:
                    stack[sp] = stack[sp - 1];
                    sp++;
                    break;
                case VmOpcodes.OP_SWAP: {
                    long t = stack[sp - 1];
                    stack[sp - 1] = stack[sp - 2];
                    stack[sp - 2] = t;
                    break;
                }
                case VmOpcodes.OP_POP:
                    {
                        long unused = stack[--sp];
                    }
                    break;
                case VmOpcodes.OP_POP2:
                    sp -= 2;
                    {
                        long unused = stack[sp];
                    }
                    break;
                case VmOpcodes.OP_DUP_X1: {
                    long v1 = stack[sp - 1];
                    long v2 = stack[sp - 2];
                    stack[sp - 2] = v1;
                    stack[sp - 1] = v2;
                    stack[sp++] = v1;
                    break;
                }
                case VmOpcodes.OP_DUP_X2: {
                    long v1 = stack[sp - 1];
                    long v2 = stack[sp - 2];
                    long v3 = stack[sp - 3];
                    stack[sp - 3] = v1;
                    stack[sp - 2] = v3;
                    stack[sp - 1] = v2;
                    stack[sp++] = v1;
                    break;
                }
                case VmOpcodes.OP_DUP2:
                    stack[sp] = stack[sp - 2];
                    stack[sp + 1] = stack[sp - 1];
                    sp += 2;
                    break;
                case VmOpcodes.OP_DUP2_X1: {
                    long v1 = stack[sp - 1];
                    long v2 = stack[sp - 2];
                    long v3 = stack[sp - 3];
                    stack[sp - 3] = v2;
                    stack[sp - 2] = v1;
                    stack[sp - 1] = v3;
                    stack[sp] = v2;
                    stack[sp + 1] = v1;
                    sp += 2;
                    break;
                }
                case VmOpcodes.OP_DUP2_X2: {
                    long v1 = stack[sp - 1];
                    long v2 = stack[sp - 2];
                    long v3 = stack[sp - 3];
                    long v4 = stack[sp - 4];
                    stack[sp - 4] = v2;
                    stack[sp - 3] = v1;
                    stack[sp - 2] = v4;
                    stack[sp - 1] = v3;
                    stack[sp] = v2;
                    stack[sp + 1] = v1;
                    sp += 2;
                    break;
                }
                case VmOpcodes.OP_ADD:
                    stack[sp - 2] += stack[sp - 1];
                    sp--;
                    break;
                case VmOpcodes.OP_HALT:
                    return sp > 0 ? stack[sp - 1] : 0;
                default:
                    throw new IllegalStateException("Unknown opcode: " + ins.opcode);
            }
        }
        return sp > 0 ? stack[sp - 1] : 0;
    }

    @Test
    public void testTranslateStackOps() {
        MethodNode mn = new MethodNode(Opcodes.ACC_STATIC, "m", "()V", null, null);
        InsnList ins = mn.instructions;
        ins.add(new InsnNode(Opcodes.ICONST_1));
        ins.add(new InsnNode(Opcodes.ICONST_2));
        ins.add(new InsnNode(Opcodes.DUP_X1));
        ins.add(new InsnNode(Opcodes.POP2));
        ins.add(new InsnNode(Opcodes.ICONST_3));
        ins.add(new InsnNode(Opcodes.ICONST_4));
        ins.add(new InsnNode(Opcodes.DUP_X2));
        ins.add(new InsnNode(Opcodes.POP2));
        ins.add(new InsnNode(Opcodes.ICONST_5));
        ins.add(new InsnNode(Opcodes.ICONST_1));
        ins.add(new InsnNode(Opcodes.DUP2));
        ins.add(new InsnNode(Opcodes.POP2));
        ins.add(new InsnNode(Opcodes.ICONST_2));
        ins.add(new InsnNode(Opcodes.ICONST_3));
        ins.add(new InsnNode(Opcodes.ICONST_4));
        ins.add(new InsnNode(Opcodes.DUP2_X1));
        ins.add(new InsnNode(Opcodes.POP2));
        ins.add(new InsnNode(Opcodes.ICONST_1));
        ins.add(new InsnNode(Opcodes.ICONST_2));
        ins.add(new InsnNode(Opcodes.ICONST_3));
        ins.add(new InsnNode(Opcodes.ICONST_4));
        ins.add(new InsnNode(Opcodes.DUP2_X2));
        ins.add(new InsnNode(Opcodes.POP2));
        ins.add(new InsnNode(Opcodes.ICONST_0));
        ins.add(new InsnNode(Opcodes.IRETURN));
        VmTranslator translator = new VmTranslator();
        Instruction[] code = translator.translate(mn);
        assertNotNull(code);
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_DUP_X1));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_DUP_X2));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_DUP2));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_DUP2_X1));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_DUP2_X2));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_POP2));
    }

    @Test
    public void testExecutionAndUnderflow() {
        Instruction[] code = new Instruction[]{
                new Instruction(VmOpcodes.OP_PUSH, 1),
                new Instruction(VmOpcodes.OP_PUSH, 2),
                new Instruction(VmOpcodes.OP_DUP_X1, 0),
                new Instruction(VmOpcodes.OP_POP2, 0),
                new Instruction(VmOpcodes.OP_PUSH, 3),
                new Instruction(VmOpcodes.OP_HALT, 0)
        };
        long r = run(code, new long[0]);
        assertEquals(3, r);

        Instruction[] bad = new Instruction[]{
                new Instruction(VmOpcodes.OP_POP, 0),
                new Instruction(VmOpcodes.OP_HALT, 0)
        };
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> run(bad, new long[0]));
    }
}
