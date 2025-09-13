package by.radioegor146;

import by.radioegor146.instructions.VmTranslator;
import by.radioegor146.instructions.VmTranslator.Instruction;
import by.radioegor146.instructions.VmTranslator.VmOpcodes;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

public class VmTranslatorStackOpTest {

    private long[] run(Instruction[] code) {
        long[] stack = new long[256];
        int sp = 0;
        for (Instruction ins : code) {
            switch (ins.opcode) {
                case VmOpcodes.OP_PUSH:
                    stack[sp++] = ins.operand;
                    break;
                case VmOpcodes.OP_POP:
                    if (sp >= 1) sp--;
                    break;
                case VmOpcodes.OP_POP2:
                    if (sp >= 2) sp -= 2;
                    break;
                case VmOpcodes.OP_DUP:
                    if (sp >= 1 && sp < 256) {
                        long v = stack[sp - 1];
                        stack[sp++] = v;
                    }
                    break;
                case VmOpcodes.OP_DUP_X1:
                    if (sp >= 2 && sp < 256) {
                        long v1 = stack[sp - 1];
                        long v2 = stack[sp - 2];
                        stack[sp] = v1;
                        stack[sp - 1] = v2;
                        stack[sp - 2] = v1;
                        sp++;
                    }
                    break;
                case VmOpcodes.OP_DUP_X2:
                    if (sp >= 3 && sp < 256) {
                        long v1 = stack[sp - 1];
                        long v2 = stack[sp - 2];
                        long v3 = stack[sp - 3];
                        stack[sp] = v1;
                        stack[sp - 1] = v2;
                        stack[sp - 2] = v3;
                        stack[sp - 3] = v1;
                        sp++;
                    }
                    break;
                case VmOpcodes.OP_DUP2:
                    if (sp >= 2 && sp < 255) {
                        stack[sp] = stack[sp - 2];
                        stack[sp + 1] = stack[sp - 1];
                        sp += 2;
                    }
                    break;
                case VmOpcodes.OP_DUP2_X1:
                    if (sp >= 3 && sp < 255) {
                        long v1 = stack[sp - 1];
                        long v2 = stack[sp - 2];
                        long v3 = stack[sp - 3];
                        stack[sp + 1] = v1;
                        stack[sp] = v2;
                        stack[sp - 1] = v3;
                        stack[sp - 2] = v1;
                        stack[sp - 3] = v2;
                        sp += 2;
                    }
                    break;
                case VmOpcodes.OP_DUP2_X2:
                    if (sp >= 4 && sp < 255) {
                        long v1 = stack[sp - 1];
                        long v2 = stack[sp - 2];
                        long v3 = stack[sp - 3];
                        long v4 = stack[sp - 4];
                        stack[sp + 1] = v1;
                        stack[sp] = v2;
                        stack[sp - 1] = v3;
                        stack[sp - 2] = v4;
                        stack[sp - 3] = v1;
                        stack[sp - 4] = v2;
                        sp += 2;
                    }
                    break;
                case VmOpcodes.OP_SWAP:
                    if (sp >= 2) {
                        long tmp = stack[sp - 1];
                        stack[sp - 1] = stack[sp - 2];
                        stack[sp - 2] = tmp;
                    }
                    break;
                default:
                    break;
            }
        }
        long[] out = new long[sp];
        System.arraycopy(stack, 0, out, 0, sp);
        return out;
    }

    @Test
    public void testPop() {
        Instruction[] code = new Instruction[]{
                new Instruction(VmOpcodes.OP_PUSH, 1),
                new Instruction(VmOpcodes.OP_PUSH, 2),
                new Instruction(VmOpcodes.OP_POP, 0)
        };
        assertArrayEquals(new long[]{1}, run(code));
    }

    @Test
    public void testPop2() {
        Instruction[] code = new Instruction[]{
                new Instruction(VmOpcodes.OP_PUSH, 1),
                new Instruction(VmOpcodes.OP_PUSH, 2),
                new Instruction(VmOpcodes.OP_PUSH, 3),
                new Instruction(VmOpcodes.OP_POP2, 0)
        };
        assertArrayEquals(new long[]{1}, run(code));
    }

    @Test
    public void testDup() {
        Instruction[] code = new Instruction[]{
                new Instruction(VmOpcodes.OP_PUSH, 5),
                new Instruction(VmOpcodes.OP_DUP, 0)
        };
        assertArrayEquals(new long[]{5, 5}, run(code));
    }

    @Test
    public void testDupX1() {
        Instruction[] code = new Instruction[]{
                new Instruction(VmOpcodes.OP_PUSH, 1),
                new Instruction(VmOpcodes.OP_PUSH, 2),
                new Instruction(VmOpcodes.OP_DUP_X1, 0)
        };
        assertArrayEquals(new long[]{2, 1, 2}, run(code));
    }

    @Test
    public void testDupX2() {
        Instruction[] code = new Instruction[]{
                new Instruction(VmOpcodes.OP_PUSH, 1),
                new Instruction(VmOpcodes.OP_PUSH, 2),
                new Instruction(VmOpcodes.OP_PUSH, 3),
                new Instruction(VmOpcodes.OP_DUP_X2, 0)
        };
        assertArrayEquals(new long[]{3, 1, 2, 3}, run(code));
    }

    @Test
    public void testDup2() {
        Instruction[] code = new Instruction[]{
                new Instruction(VmOpcodes.OP_PUSH, 1),
                new Instruction(VmOpcodes.OP_PUSH, 2),
                new Instruction(VmOpcodes.OP_DUP2, 0)
        };
        assertArrayEquals(new long[]{1, 2, 1, 2}, run(code));
    }

    @Test
    public void testDup2X1() {
        Instruction[] code = new Instruction[]{
                new Instruction(VmOpcodes.OP_PUSH, 1),
                new Instruction(VmOpcodes.OP_PUSH, 2),
                new Instruction(VmOpcodes.OP_PUSH, 3),
                new Instruction(VmOpcodes.OP_DUP2_X1, 0)
        };
        assertArrayEquals(new long[]{2, 3, 1, 2, 3}, run(code));
    }

    @Test
    public void testDup2X2() {
        Instruction[] code = new Instruction[]{
                new Instruction(VmOpcodes.OP_PUSH, 1),
                new Instruction(VmOpcodes.OP_PUSH, 2),
                new Instruction(VmOpcodes.OP_PUSH, 3),
                new Instruction(VmOpcodes.OP_PUSH, 4),
                new Instruction(VmOpcodes.OP_DUP2_X2, 0)
        };
        assertArrayEquals(new long[]{3, 4, 1, 2, 3, 4}, run(code));
    }

    @Test
    public void testSwap() {
        Instruction[] code = new Instruction[]{
                new Instruction(VmOpcodes.OP_PUSH, 1),
                new Instruction(VmOpcodes.OP_PUSH, 2),
                new Instruction(VmOpcodes.OP_SWAP, 0)
        };
        assertArrayEquals(new long[]{2, 1}, run(code));
    }

    private Instruction[] translate(MethodNode mn) {
        return new VmTranslator().translate(mn);
    }

    private void assertPop2Category2(int constOpcode) {
        MethodNode mn = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC, "m", "()V", null, null);
        mn.maxStack = 4;
        mn.maxLocals = 0;
        mn.instructions.add(new InsnNode(constOpcode));
        mn.instructions.add(new InsnNode(Opcodes.POP2));
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        Instruction[] code = translate(mn);
        assertNotNull(code);
        assertEquals(VmOpcodes.OP_POP, code[1].opcode);
    }

    @Test
    public void translatePop2Category2() {
        assertPop2Category2(Opcodes.LCONST_0);
        assertPop2Category2(Opcodes.DCONST_0);
    }

    private void assertDup2Category2(int constOpcode) {
        MethodNode mn = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC, "m", "()V", null, null);
        mn.maxStack = 5;
        mn.maxLocals = 0;
        mn.instructions.add(new InsnNode(constOpcode));
        mn.instructions.add(new InsnNode(Opcodes.DUP2));
        mn.instructions.add(new InsnNode(Opcodes.POP2));
        mn.instructions.add(new InsnNode(Opcodes.POP2));
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        Instruction[] code = translate(mn);
        assertNotNull(code);
        assertEquals(VmOpcodes.OP_DUP, code[1].opcode);
    }

    @Test
    public void translateDup2Category2() {
        assertDup2Category2(Opcodes.LCONST_0);
        assertDup2Category2(Opcodes.DCONST_0);
    }

    private void assertDup2X1Category2(int constOpcode) {
        MethodNode mn = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC, "m", "()V", null, null);
        mn.maxStack = 6;
        mn.maxLocals = 0;
        mn.instructions.add(new InsnNode(Opcodes.ICONST_0));
        mn.instructions.add(new InsnNode(constOpcode));
        mn.instructions.add(new InsnNode(Opcodes.DUP2_X1));
        mn.instructions.add(new InsnNode(Opcodes.POP2));
        mn.instructions.add(new InsnNode(Opcodes.POP));
        mn.instructions.add(new InsnNode(Opcodes.POP2));
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        Instruction[] code = translate(mn);
        assertNotNull(code);
        assertEquals(VmOpcodes.OP_DUP_X1, code[2].opcode);
        assertEquals(VmOpcodes.OP_POP, code[3].opcode);
    }

    @Test
    public void translateDup2X1Category2() {
        assertDup2X1Category2(Opcodes.LCONST_0);
        assertDup2X1Category2(Opcodes.DCONST_0);
    }

    private void assertDup2X2Category2(int constOpcode) {
        MethodNode mn = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC, "m", "()V", null, null);
        mn.maxStack = 6;
        mn.maxLocals = 0;
        mn.instructions.add(new InsnNode(constOpcode));
        mn.instructions.add(new InsnNode(constOpcode));
        mn.instructions.add(new InsnNode(Opcodes.DUP2_X2));
        mn.instructions.add(new InsnNode(Opcodes.POP2));
        mn.instructions.add(new InsnNode(Opcodes.POP2));
        mn.instructions.add(new InsnNode(Opcodes.POP2));
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        Instruction[] code = translate(mn);
        assertNotNull(code);
        assertEquals(VmOpcodes.OP_DUP_X2, code[2].opcode);
        assertEquals(VmOpcodes.OP_POP, code[3].opcode);
        assertEquals(VmOpcodes.OP_POP, code[4].opcode);
        assertEquals(VmOpcodes.OP_POP, code[5].opcode);
    }

    @Test
    public void translateDup2X2Category2() {
        assertDup2X2Category2(Opcodes.LCONST_0);
        assertDup2X2Category2(Opcodes.DCONST_0);
    }
}
