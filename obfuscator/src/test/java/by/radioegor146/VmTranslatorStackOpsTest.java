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

/**
 * Tests for stack manipulation opcodes in {@link VmTranslator}.
 */
public class VmTranslatorStackOpsTest {

    private long run(Instruction[] code) {
        long[] stack = new long[256];
        int sp = 0;
        for (Instruction ins : code) {
            switch (ins.opcode) {
                case VmOpcodes.OP_PUSH:
                    stack[sp++] = ins.operand;
                    break;
                case VmOpcodes.OP_ADD:
                    if (sp >= 2) {
                        stack[sp - 2] += stack[sp - 1];
                        sp--;
                    }
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
                    if (sp >= 2 && sp <= 254) {
                        long v1 = stack[sp - 1];
                        long v2 = stack[sp - 2];
                        stack[sp] = v2;
                        stack[sp + 1] = v1;
                        sp += 2;
                    }
                    break;
                case VmOpcodes.OP_DUP2_X1:
                    if (sp >= 3 && sp <= 254) {
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
                    if (sp >= 4 && sp <= 254) {
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
                        long t = stack[sp - 1];
                        stack[sp - 1] = stack[sp - 2];
                        stack[sp - 2] = t;
                    }
                    break;
                case VmOpcodes.OP_HALT:
                    return sp > 0 ? stack[sp - 1] : 0;
            }
        }
        return sp > 0 ? stack[sp - 1] : 0;
    }

    private Instruction[] translate(MethodNode mn) {
        VmTranslator translator = new VmTranslator();
        return translator.translate(mn);
    }

    @Test
    public void testPop() {
        MethodNode mn = new MethodNode(Opcodes.ACC_STATIC, "m", "()I", null, null);
        InsnList il = mn.instructions;
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new InsnNode(Opcodes.POP));
        il.add(new InsnNode(Opcodes.ICONST_2));
        il.add(new InsnNode(Opcodes.IRETURN));
        Instruction[] code = translate(mn);
        assertNotNull(code);
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_POP));
        assertEquals(2, run(code));
    }

    @Test
    public void testPop2() {
        MethodNode mn = new MethodNode(Opcodes.ACC_STATIC, "m", "()I", null, null);
        InsnList il = mn.instructions;
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new InsnNode(Opcodes.ICONST_2));
        il.add(new InsnNode(Opcodes.POP2));
        il.add(new InsnNode(Opcodes.ICONST_3));
        il.add(new InsnNode(Opcodes.IRETURN));
        Instruction[] code = translate(mn);
        assertNotNull(code);
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_POP2));
        assertEquals(3, run(code));
    }

    @Test
    public void testDup() {
        MethodNode mn = new MethodNode(Opcodes.ACC_STATIC, "m", "()I", null, null);
        InsnList il = mn.instructions;
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IRETURN));
        Instruction[] code = translate(mn);
        assertNotNull(code);
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_DUP));
        assertEquals(2, run(code));
    }

    @Test
    public void testDupX1() {
        MethodNode mn = new MethodNode(Opcodes.ACC_STATIC, "m", "()I", null, null);
        InsnList il = mn.instructions;
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new InsnNode(Opcodes.ICONST_2));
        il.add(new InsnNode(Opcodes.DUP_X1));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IRETURN));
        Instruction[] code = translate(mn);
        assertNotNull(code);
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_DUP_X1));
        assertEquals(5, run(code));
    }

    @Test
    public void testDupX2() {
        MethodNode mn = new MethodNode(Opcodes.ACC_STATIC, "m", "()I", null, null);
        InsnList il = mn.instructions;
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new InsnNode(Opcodes.ICONST_2));
        il.add(new InsnNode(Opcodes.ICONST_3));
        il.add(new InsnNode(Opcodes.DUP_X2));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IRETURN));
        Instruction[] code = translate(mn);
        assertNotNull(code);
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_DUP_X2));
        assertEquals(9, run(code));
    }

    @Test
    public void testDup2() {
        MethodNode mn = new MethodNode(Opcodes.ACC_STATIC, "m", "()I", null, null);
        InsnList il = mn.instructions;
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new InsnNode(Opcodes.ICONST_2));
        il.add(new InsnNode(Opcodes.DUP2));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IRETURN));
        Instruction[] code = translate(mn);
        assertNotNull(code);
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_DUP2));
        assertEquals(5, run(code));
    }

    @Test
    public void testDup2X1() {
        MethodNode mn = new MethodNode(Opcodes.ACC_STATIC, "m", "()I", null, null);
        InsnList il = mn.instructions;
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new InsnNode(Opcodes.ICONST_2));
        il.add(new InsnNode(Opcodes.ICONST_3));
        il.add(new InsnNode(Opcodes.DUP2_X1));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IRETURN));
        Instruction[] code = translate(mn);
        assertNotNull(code);
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_DUP2_X1));
        assertEquals(11, run(code));
    }

    @Test
    public void testDup2X2() {
        MethodNode mn = new MethodNode(Opcodes.ACC_STATIC, "m", "()I", null, null);
        InsnList il = mn.instructions;
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new InsnNode(Opcodes.ICONST_2));
        il.add(new InsnNode(Opcodes.ICONST_3));
        il.add(new InsnNode(Opcodes.ICONST_4));
        il.add(new InsnNode(Opcodes.DUP2_X2));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IRETURN));
        Instruction[] code = translate(mn);
        assertNotNull(code);
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_DUP2_X2));
        assertEquals(17, run(code));
    }

    @Test
    public void testSwap() {
        MethodNode mn = new MethodNode(Opcodes.ACC_STATIC, "m", "()I", null, null);
        InsnList il = mn.instructions;
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new InsnNode(Opcodes.ICONST_2));
        il.add(new InsnNode(Opcodes.SWAP));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IRETURN));
        Instruction[] code = translate(mn);
        assertNotNull(code);
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_SWAP));
        assertEquals(3, run(code));
    }

    @Test
    public void testUnderflow() {
        MethodNode mn = new MethodNode(Opcodes.ACC_STATIC, "m", "()I", null, null);
        InsnList il = mn.instructions;
        il.add(new InsnNode(Opcodes.POP));
        il.add(new InsnNode(Opcodes.POP2));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new InsnNode(Opcodes.DUP_X1));
        il.add(new InsnNode(Opcodes.DUP_X2));
        il.add(new InsnNode(Opcodes.DUP2));
        il.add(new InsnNode(Opcodes.DUP2_X1));
        il.add(new InsnNode(Opcodes.DUP2_X2));
        il.add(new InsnNode(Opcodes.SWAP));
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new InsnNode(Opcodes.IRETURN));
        Instruction[] code = translate(mn);
        assertNotNull(code);
        // ensure all opcodes are present
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_POP));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_POP2));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_DUP));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_DUP_X1));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_DUP_X2));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_DUP2));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_DUP2_X1));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_DUP2_X2));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_SWAP));
        assertEquals(1, run(code));
    }
}

