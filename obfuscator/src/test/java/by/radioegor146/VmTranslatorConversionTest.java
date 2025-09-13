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

public class VmTranslatorConversionTest {

    static class Sample {
        static float i2f(int a) { return (float) a; }
        static double i2d(int a) { return (double) a; }
        static int l2i(long a) { return (int) a; }
        static float l2f(long a) { return (float) a; }
        static double l2d(long a) { return (double) a; }
        static int f2i(float a) { return (int) a; }
        static long f2l(float a) { return (long) a; }
        static double f2d(float a) { return (double) a; }
        static int d2i(double a) { return (int) a; }
        static long d2l(double a) { return (long) a; }
        static float d2f(double a) { return (float) a; }
    }

    private long run(Instruction[] code, long[] locals) {
        long[] stack = new long[256];
        int sp = 0;
        for (Instruction ins : code) {
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
                case VmOpcodes.OP_I2F:
                    stack[sp - 1] = Float.floatToIntBits((float) (int) stack[sp - 1]);
                    break;
                case VmOpcodes.OP_I2D:
                    stack[sp - 1] = Double.doubleToLongBits((double) (int) stack[sp - 1]);
                    break;
                case VmOpcodes.OP_L2I:
                    stack[sp - 1] = (int) stack[sp - 1];
                    break;
                case VmOpcodes.OP_L2F:
                    stack[sp - 1] = Float.floatToIntBits((float) stack[sp - 1]);
                    break;
                case VmOpcodes.OP_L2D:
                    stack[sp - 1] = Double.doubleToLongBits((double) stack[sp - 1]);
                    break;
                case VmOpcodes.OP_F2I: {
                    float f = Float.intBitsToFloat((int) stack[sp - 1]);
                    stack[sp - 1] = (int) f;
                    break;
                }
                case VmOpcodes.OP_F2L: {
                    float f = Float.intBitsToFloat((int) stack[sp - 1]);
                    stack[sp - 1] = (long) f;
                    break;
                }
                case VmOpcodes.OP_F2D: {
                    float f = Float.intBitsToFloat((int) stack[sp - 1]);
                    stack[sp - 1] = Double.doubleToLongBits((double) f);
                    break;
                }
                case VmOpcodes.OP_D2I: {
                    double d = Double.longBitsToDouble(stack[sp - 1]);
                    stack[sp - 1] = (int) d;
                    break;
                }
                case VmOpcodes.OP_D2L: {
                    double d = Double.longBitsToDouble(stack[sp - 1]);
                    stack[sp - 1] = (long) d;
                    break;
                }
                case VmOpcodes.OP_D2F: {
                    double d = Double.longBitsToDouble(stack[sp - 1]);
                    stack[sp - 1] = Float.floatToIntBits((float) d);
                    break;
                }
                case VmOpcodes.OP_HALT:
                    return sp > 0 ? stack[sp - 1] : 0;
                default:
                    throw new IllegalStateException("Unknown opcode: " + ins.opcode);
            }
        }
        return sp > 0 ? stack[sp - 1] : 0;
    }

    @Test
    public void testConversions() throws Exception {
        VmTranslator translator = new VmTranslator();
        ClassReader cr = new ClassReader(Sample.class.getName());
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        // I2F
        {
            MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("i2f")).findFirst().orElseThrow();
            Instruction[] code = translator.translate(mn);
            assertNotNull(code);
            assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_I2F));
            long[] locals = new long[1];
            locals[0] = 42;
            long res = run(code, locals);
            assertEquals(Float.floatToIntBits(Sample.i2f(42)), (int) res);
        }
        // I2D
        {
            MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("i2d")).findFirst().orElseThrow();
            Instruction[] code = translator.translate(mn);
            assertNotNull(code);
            assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_I2D));
            long[] locals = new long[1];
            locals[0] = 5;
            long res = run(code, locals);
            assertEquals(Double.doubleToLongBits(Sample.i2d(5)), res);
        }
        // L2I
        {
            MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("l2i")).findFirst().orElseThrow();
            Instruction[] code = translator.translate(mn);
            assertNotNull(code);
            assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_L2I));
            long[] locals = new long[2];
            locals[0] = 1234567890123L;
            long res = run(code, locals);
            assertEquals(Sample.l2i(1234567890123L), res);
        }
        // L2F
        {
            MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("l2f")).findFirst().orElseThrow();
            Instruction[] code = translator.translate(mn);
            assertNotNull(code);
            assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_L2F));
            long[] locals = new long[2];
            locals[0] = 1000L;
            long res = run(code, locals);
            assertEquals(Float.floatToIntBits(Sample.l2f(1000L)), (int) res);
        }
        // L2D
        {
            MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("l2d")).findFirst().orElseThrow();
            Instruction[] code = translator.translate(mn);
            assertNotNull(code);
            assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_L2D));
            long[] locals = new long[2];
            locals[0] = 1000L;
            long res = run(code, locals);
            assertEquals(Double.doubleToLongBits(Sample.l2d(1000L)), res);
        }
        // F2I
        {
            MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("f2i")).findFirst().orElseThrow();
            Instruction[] code = translator.translate(mn);
            assertNotNull(code);
            assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_F2I));
            long[] locals = new long[1];
            locals[0] = Float.floatToIntBits(3.5f);
            long res = run(code, locals);
            assertEquals(Sample.f2i(3.5f), res);
        }
        // F2L
        {
            MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("f2l")).findFirst().orElseThrow();
            Instruction[] code = translator.translate(mn);
            assertNotNull(code);
            assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_F2L));
            long[] locals = new long[1];
            locals[0] = Float.floatToIntBits(2.75f);
            long res = run(code, locals);
            assertEquals(Sample.f2l(2.75f), res);
        }
        // F2D
        {
            MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("f2d")).findFirst().orElseThrow();
            Instruction[] code = translator.translate(mn);
            assertNotNull(code);
            assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_F2D));
            long[] locals = new long[1];
            locals[0] = Float.floatToIntBits(1.25f);
            long res = run(code, locals);
            assertEquals(Double.doubleToLongBits(Sample.f2d(1.25f)), res);
        }
        // D2I
        {
            MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("d2i")).findFirst().orElseThrow();
            Instruction[] code = translator.translate(mn);
            assertNotNull(code);
            assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_D2I));
            long[] locals = new long[2];
            locals[0] = Double.doubleToLongBits(8.5d);
            long res = run(code, locals);
            assertEquals(Sample.d2i(8.5d), res);
        }
        // D2L
        {
            MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("d2l")).findFirst().orElseThrow();
            Instruction[] code = translator.translate(mn);
            assertNotNull(code);
            assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_D2L));
            long[] locals = new long[2];
            locals[0] = Double.doubleToLongBits(8.5d);
            long res = run(code, locals);
            assertEquals(Sample.d2l(8.5d), res);
        }
        // D2F
        {
            MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("d2f")).findFirst().orElseThrow();
            Instruction[] code = translator.translate(mn);
            assertNotNull(code);
            assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_D2F));
            long[] locals = new long[2];
            locals[0] = Double.doubleToLongBits(6.25d);
            long res = run(code, locals);
            assertEquals(Float.floatToIntBits(Sample.d2f(6.25d)), (int) res);
        }
    }
}
