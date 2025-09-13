package by.radioegor146;

import by.radioegor146.instructions.VmTranslator;
import by.radioegor146.instructions.VmTranslator.Instruction;
import by.radioegor146.instructions.VmTranslator.VmOpcodes;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class VmTranslatorBitwiseTest {

    static class SampleInc {
        static int calc(int a) {
            a++;
            a--;
            return a;
        }
    }

    static class SampleLongBitwise {
        static long calc(long a, long b) {
            long c = a & b;
            long d = a | b;
            long e = a ^ b;
            return c + d + e;
        }
    }

    static class SampleShift {
        static long calc(int i, long l) {
            int a = i << 1;
            int b = i >> 2;
            int c = i >>> 3;
            long d = l << 1;
            long e = l >> 2;
            long f = l >>> 3;
            return a + b + c + d + e + f;
        }
    }

    private long run(Instruction[] code, long[] locals) {
        long[] stack = new long[256];
        int sp = 0;
        for (int pc = 0; pc < code.length; pc++) {
            Instruction ins = code[pc];
            switch (ins.opcode) {
                case VmOpcodes.OP_PUSH:
                    stack[sp++] = ins.operand;
                    break;
                case VmOpcodes.OP_LOAD:
                case VmOpcodes.OP_LLOAD:
                case VmOpcodes.OP_FLOAD:
                case VmOpcodes.OP_DLOAD:
                    stack[sp++] = locals[(int) ins.operand];
                    break;
                case VmOpcodes.OP_STORE:
                case VmOpcodes.OP_LSTORE:
                case VmOpcodes.OP_FSTORE:
                case VmOpcodes.OP_DSTORE:
                    locals[(int) ins.operand] = stack[--sp];
                    break;
                case VmOpcodes.OP_IINC: {
                    int idx = (int) (ins.operand & 0xFFFFFFFFL);
                    int inc = (int) (ins.operand >> 32);
                    int val = (int) locals[idx];
                    val += inc;
                    locals[idx] = val;
                    break;
                }
                case VmOpcodes.OP_ADD:
                case VmOpcodes.OP_LADD:
                    stack[sp - 2] += stack[sp - 1];
                    sp--;
                    break;
                case VmOpcodes.OP_SUB:
                case VmOpcodes.OP_LSUB:
                    stack[sp - 2] -= stack[sp - 1];
                    sp--;
                    break;
                case VmOpcodes.OP_AND:
                case VmOpcodes.OP_LAND:
                    stack[sp - 2] &= stack[sp - 1];
                    sp--;
                    break;
                case VmOpcodes.OP_OR:
                case VmOpcodes.OP_LOR:
                    stack[sp - 2] |= stack[sp - 1];
                    sp--;
                    break;
                case VmOpcodes.OP_XOR:
                case VmOpcodes.OP_LXOR:
                    stack[sp - 2] ^= stack[sp - 1];
                    sp--;
                    break;
                case VmOpcodes.OP_SHL:
                case VmOpcodes.OP_LSHL:
                    stack[sp - 2] <<= stack[sp - 1];
                    sp--;
                    break;
                case VmOpcodes.OP_SHR:
                case VmOpcodes.OP_LSHR:
                    stack[sp - 2] >>= stack[sp - 1];
                    sp--;
                    break;
                case VmOpcodes.OP_USHR:
                case VmOpcodes.OP_LUSHR:
                    stack[sp - 2] = (long) (((long) stack[sp - 2]) >>> stack[sp - 1]);
                    sp--;
                    break;
                case VmOpcodes.OP_I2L:
                    stack[sp - 1] = (long) (int) stack[sp - 1];
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
    public void testIInc() throws Exception {
        VmTranslator translator = new VmTranslator();
        ClassReader cr = new ClassReader(SampleInc.class.getName());
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("calc")).findFirst().orElseThrow();
        Instruction[] code = translator.translate(mn);
        assertNotNull(code);
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_IINC));
        long[] locals = new long[1];
        locals[0] = 5;
        long res = run(code, locals);
        assertEquals(SampleInc.calc(5), (int) res);
    }

    @Test
    public void testLongBitwise() throws Exception {
        VmTranslator translator = new VmTranslator();
        ClassReader cr = new ClassReader(SampleLongBitwise.class.getName());
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("calc")).findFirst().orElseThrow();
        Instruction[] code = translator.translate(mn);
        assertNotNull(code);
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_LAND));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_LOR));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_LXOR));
        long[] locals = new long[10];
        locals[0] = 0x0F0FL;
        locals[2] = 0x00FFL;
        long res = run(code, locals);
        assertEquals(SampleLongBitwise.calc(0x0F0FL, 0x00FFL), res);
    }

    @Test
    public void testShifts() throws Exception {
        VmTranslator translator = new VmTranslator();
        ClassReader cr = new ClassReader(SampleShift.class.getName());
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("calc")).findFirst().orElseThrow();
        Instruction[] code = translator.translate(mn);
        assertNotNull(code);
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_SHL));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_SHR));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_USHR));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_LSHL));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_LSHR));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_LUSHR));
        long[] locals = new long[12];
        locals[0] = 6; // i
        locals[1] = 13L; // l
        long res = run(code, locals);
        assertEquals(SampleShift.calc(6, 13L), res);
    }
}
