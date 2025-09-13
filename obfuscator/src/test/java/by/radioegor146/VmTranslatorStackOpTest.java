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

    private long run(Instruction[] code) {
        long[] stack = new long[256];
        int sp = 0;
        for (Instruction ins : code) {
            switch (ins.opcode) {
                case VmOpcodes.OP_PUSH:
                    stack[sp++] = ins.operand;
                    break;
                case VmOpcodes.OP_ADD:
                    stack[sp - 2] += stack[sp - 1];
                    sp--;
                    break;
                case VmOpcodes.OP_SUB:
                    stack[sp - 2] -= stack[sp - 1];
                    sp--;
                    break;
                case VmOpcodes.OP_POP:
                    if (sp > 0) sp--;
                    break;
                case VmOpcodes.OP_POP2:
                    if (sp > 1) sp -= 2;
                    break;
                case VmOpcodes.OP_DUP:
                    stack[sp] = stack[sp - 1];
                    sp++;
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
                case VmOpcodes.OP_DUP2: {
                    long v1 = stack[sp - 1];
                    long v2 = stack[sp - 2];
                    stack[sp] = v2;
                    stack[sp + 1] = v1;
                    sp += 2;
                    break;
                }
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
                case VmOpcodes.OP_SWAP: {
                    long tmp = stack[sp - 1];
                    stack[sp - 1] = stack[sp - 2];
                    stack[sp - 2] = tmp;
                    break;
                }
                case VmOpcodes.OP_HALT:
                    return sp > 0 ? stack[sp - 1] : 0;
                default:
                    throw new IllegalStateException("Unknown opcode " + ins.opcode);
            }
        }
        return sp > 0 ? stack[sp - 1] : 0;
    }

    @Test
    public void testPopAndPop2() {
        VmTranslator tr = new VmTranslator();
        MethodNode mn = new MethodNode(Opcodes.ACC_STATIC, "m", "()I", null, null);
        InsnList il = mn.instructions;
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new InsnNode(Opcodes.ICONST_2));
        il.add(new InsnNode(Opcodes.POP));
        il.add(new InsnNode(Opcodes.ICONST_3));
        il.add(new InsnNode(Opcodes.ICONST_4));
        il.add(new InsnNode(Opcodes.POP2));
        il.add(new InsnNode(Opcodes.IRETURN));
        mn.maxStack = 4;
        mn.maxLocals = 0;
        Instruction[] code = tr.translate(mn);
        assertNotNull(code);
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_POP));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_POP2));
        assertEquals(1, run(code));
    }

    @Test
    public void testDupVariants() {
        VmTranslator tr = new VmTranslator();

        // DUP
        MethodNode dup = new MethodNode(Opcodes.ACC_STATIC, "m", "()I", null, null);
        InsnList il = dup.instructions;
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IRETURN));
        dup.maxStack = 2;
        dup.maxLocals = 0;
        Instruction[] codeDup = tr.translate(dup);
        assertNotNull(codeDup);
        assertTrue(Arrays.stream(codeDup).anyMatch(i -> i.opcode == VmOpcodes.OP_DUP));
        assertEquals(2, run(codeDup));

        // DUP_X1
        MethodNode dupx1 = new MethodNode(Opcodes.ACC_STATIC, "m", "()I", null, null);
        il = dupx1.instructions;
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new InsnNode(Opcodes.ICONST_2));
        il.add(new InsnNode(Opcodes.DUP_X1));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IRETURN));
        dupx1.maxStack = 3;
        dupx1.maxLocals = 0;
        Instruction[] codeDupX1 = tr.translate(dupx1);
        assertNotNull(codeDupX1);
        assertTrue(Arrays.stream(codeDupX1).anyMatch(i -> i.opcode == VmOpcodes.OP_DUP_X1));
        assertEquals(5, run(codeDupX1));

        // DUP_X2
        MethodNode dupx2 = new MethodNode(Opcodes.ACC_STATIC, "m", "()I", null, null);
        il = dupx2.instructions;
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new InsnNode(Opcodes.ICONST_2));
        il.add(new InsnNode(Opcodes.ICONST_3));
        il.add(new InsnNode(Opcodes.DUP_X2));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IRETURN));
        dupx2.maxStack = 4;
        dupx2.maxLocals = 0;
        Instruction[] codeDupX2 = tr.translate(dupx2);
        assertNotNull(codeDupX2);
        assertTrue(Arrays.stream(codeDupX2).anyMatch(i -> i.opcode == VmOpcodes.OP_DUP_X2));
        assertEquals(9, run(codeDupX2));

        // DUP2
        MethodNode dup2 = new MethodNode(Opcodes.ACC_STATIC, "m", "()I", null, null);
        il = dup2.instructions;
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new InsnNode(Opcodes.ICONST_2));
        il.add(new InsnNode(Opcodes.DUP2));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IRETURN));
        dup2.maxStack = 4;
        dup2.maxLocals = 0;
        Instruction[] codeDup2 = tr.translate(dup2);
        assertNotNull(codeDup2);
        assertTrue(Arrays.stream(codeDup2).anyMatch(i -> i.opcode == VmOpcodes.OP_DUP2));
        assertEquals(6, run(codeDup2));

        // DUP2_X1
        MethodNode dup2x1 = new MethodNode(Opcodes.ACC_STATIC, "m", "()I", null, null);
        il = dup2x1.instructions;
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new InsnNode(Opcodes.ICONST_2));
        il.add(new InsnNode(Opcodes.ICONST_3));
        il.add(new InsnNode(Opcodes.DUP2_X1));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new InsnNode(Opcodes.IRETURN));
        dup2x1.maxStack = 5;
        dup2x1.maxLocals = 0;
        Instruction[] codeDup2X1 = tr.translate(dup2x1);
        assertNotNull(codeDup2X1);
        assertTrue(Arrays.stream(codeDup2X1).anyMatch(i -> i.opcode == VmOpcodes.OP_DUP2_X1));
        assertEquals(11, run(codeDup2X1));

        // DUP2_X2
        MethodNode dup2x2 = new MethodNode(Opcodes.ACC_STATIC, "m", "()I", null, null);
        il = dup2x2.instructions;
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
        dup2x2.maxStack = 6;
        dup2x2.maxLocals = 0;
        Instruction[] codeDup2X2 = tr.translate(dup2x2);
        assertNotNull(codeDup2X2);
        assertTrue(Arrays.stream(codeDup2X2).anyMatch(i -> i.opcode == VmOpcodes.OP_DUP2_X2));
        assertEquals(17, run(codeDup2X2));
    }

    @Test
    public void testSwap() {
        VmTranslator tr = new VmTranslator();
        MethodNode mn = new MethodNode(Opcodes.ACC_STATIC, "m", "()I", null, null);
        InsnList il = mn.instructions;
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new InsnNode(Opcodes.ICONST_2));
        il.add(new InsnNode(Opcodes.SWAP));
        il.add(new InsnNode(Opcodes.ISUB));
        il.add(new InsnNode(Opcodes.IRETURN));
        mn.maxStack = 2;
        mn.maxLocals = 0;
        Instruction[] code = tr.translate(mn);
        assertNotNull(code);
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_SWAP));
        assertEquals(1, run(code));
    }
}

