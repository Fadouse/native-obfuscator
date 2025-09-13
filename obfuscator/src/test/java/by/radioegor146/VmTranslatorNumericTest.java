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

public class VmTranslatorNumericTest {

    static class SampleLong {
        static long calc(long a, long b) {
            long c = a + b;
            long d = c * a;
            long e = d / b;
            return e;
        }
    }

    static class SampleFloat {
        static float calc(float a, float b) {
            float c = a + b;
            float d = c * a;
            float e = d / b;
            return e;
        }
    }

    static class SampleDouble {
        static double calc(double a, double b) {
            double c = a + b;
            double d = c * a;
            double e = d / b;
            return e;
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
                    stack[sp++] = locals[ins.operand];
                    break;
                case VmOpcodes.OP_STORE:
                case VmOpcodes.OP_LSTORE:
                case VmOpcodes.OP_FSTORE:
                case VmOpcodes.OP_DSTORE:
                    locals[ins.operand] = stack[--sp];
                    break;
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
                case VmOpcodes.OP_MUL:
                case VmOpcodes.OP_LMUL:
                    stack[sp - 2] *= stack[sp - 1];
                    sp--;
                    break;
                case VmOpcodes.OP_DIV:
                case VmOpcodes.OP_LDIV:
                    long b = stack[--sp];
                    long a = stack[sp - 1];
                    if (b == 0) throw new ArithmeticException("/ by zero");
                    stack[sp - 1] = a / b;
                    break;
                case VmOpcodes.OP_FADD: {
                    float fb = Float.intBitsToFloat((int) stack[--sp]);
                    float fa = Float.intBitsToFloat((int) stack[sp - 1]);
                    stack[sp - 1] = Float.floatToIntBits(fa + fb);
                    break;
                }
                case VmOpcodes.OP_FSUB: {
                    float fb = Float.intBitsToFloat((int) stack[--sp]);
                    float fa = Float.intBitsToFloat((int) stack[sp - 1]);
                    stack[sp - 1] = Float.floatToIntBits(fa - fb);
                    break;
                }
                case VmOpcodes.OP_FMUL: {
                    float fb = Float.intBitsToFloat((int) stack[--sp]);
                    float fa = Float.intBitsToFloat((int) stack[sp - 1]);
                    stack[sp - 1] = Float.floatToIntBits(fa * fb);
                    break;
                }
                case VmOpcodes.OP_FDIV: {
                    float fb = Float.intBitsToFloat((int) stack[--sp]);
                    float fa = Float.intBitsToFloat((int) stack[sp - 1]);
                    stack[sp - 1] = Float.floatToIntBits(fa / fb);
                    break;
                }
                case VmOpcodes.OP_DADD: {
                    double db = Double.longBitsToDouble(stack[--sp]);
                    double da = Double.longBitsToDouble(stack[sp - 1]);
                    stack[sp - 1] = Double.doubleToLongBits(da + db);
                    break;
                }
                case VmOpcodes.OP_DSUB: {
                    double db = Double.longBitsToDouble(stack[--sp]);
                    double da = Double.longBitsToDouble(stack[sp - 1]);
                    stack[sp - 1] = Double.doubleToLongBits(da - db);
                    break;
                }
                case VmOpcodes.OP_DMUL: {
                    double db = Double.longBitsToDouble(stack[--sp]);
                    double da = Double.longBitsToDouble(stack[sp - 1]);
                    stack[sp - 1] = Double.doubleToLongBits(da * db);
                    break;
                }
                case VmOpcodes.OP_DDIV: {
                    double db = Double.longBitsToDouble(stack[--sp]);
                    double da = Double.longBitsToDouble(stack[sp - 1]);
                    stack[sp - 1] = Double.doubleToLongBits(da / db);
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
    public void testLongFloatDouble() throws Exception {
        VmTranslator translator = new VmTranslator();

        // Long operations
        ClassReader crL = new ClassReader(SampleLong.class.getName());
        ClassNode cnL = new ClassNode();
        crL.accept(cnL, 0);
        MethodNode mnL = cnL.methods.stream().filter(m -> m.name.equals("calc")).findFirst().orElseThrow();
        Instruction[] codeL = translator.translate(mnL);
        assertNotNull(codeL);
        assertTrue(Arrays.stream(codeL).anyMatch(i -> i.opcode == VmOpcodes.OP_LADD));
        assertTrue(Arrays.stream(codeL).anyMatch(i -> i.opcode == VmOpcodes.OP_LSTORE));
        long aL = 7L, bL = 3L;
        long[] localsL = new long[10];
        localsL[0] = aL;
        localsL[2] = bL;
        long resL = run(codeL, localsL);
        assertEquals(SampleLong.calc(aL, bL), resL);
        assertEquals(aL + bL, localsL[4]);
        assertEquals((aL + bL) * aL, localsL[6]);
        assertEquals(((aL + bL) * aL) / bL, localsL[8]);

        // Float operations
        ClassReader crF = new ClassReader(SampleFloat.class.getName());
        ClassNode cnF = new ClassNode();
        crF.accept(cnF, 0);
        MethodNode mnF = cnF.methods.stream().filter(m -> m.name.equals("calc")).findFirst().orElseThrow();
        Instruction[] codeF = translator.translate(mnF);
        assertNotNull(codeF);
        assertTrue(Arrays.stream(codeF).anyMatch(i -> i.opcode == VmOpcodes.OP_FADD));
        assertTrue(Arrays.stream(codeF).anyMatch(i -> i.opcode == VmOpcodes.OP_FSTORE));
        float aF = 5.5f, bF = 2.0f;
        long[] localsF = new long[5];
        localsF[0] = Float.floatToIntBits(aF);
        localsF[1] = Float.floatToIntBits(bF);
        long resFbits = run(codeF, localsF);
        float resF = Float.intBitsToFloat((int) resFbits);
        assertEquals(SampleFloat.calc(aF, bF), resF, 0.0001f);
        assertEquals(Float.floatToIntBits(aF + bF), (int) localsF[2]);
        assertEquals(Float.floatToIntBits((aF + bF) * aF), (int) localsF[3]);
        assertEquals(Float.floatToIntBits(((aF + bF) * aF) / bF), (int) localsF[4]);

        // Double operations
        ClassReader crD = new ClassReader(SampleDouble.class.getName());
        ClassNode cnD = new ClassNode();
        crD.accept(cnD, 0);
        MethodNode mnD = cnD.methods.stream().filter(m -> m.name.equals("calc")).findFirst().orElseThrow();
        Instruction[] codeD = translator.translate(mnD);
        assertNotNull(codeD);
        assertTrue(Arrays.stream(codeD).anyMatch(i -> i.opcode == VmOpcodes.OP_DADD));
        assertTrue(Arrays.stream(codeD).anyMatch(i -> i.opcode == VmOpcodes.OP_DSTORE));
        double aD = 7.25d, bD = 2.5d;
        long[] localsD = new long[10];
        localsD[0] = Double.doubleToLongBits(aD);
        localsD[2] = Double.doubleToLongBits(bD);
        long resDbits = run(codeD, localsD);
        double resD = Double.longBitsToDouble(resDbits);
        assertEquals(SampleDouble.calc(aD, bD), resD, 1e-9);
        assertEquals(Double.doubleToLongBits(aD + bD), localsD[4]);
        assertEquals(Double.doubleToLongBits((aD + bD) * aD), localsD[6]);
        assertEquals(Double.doubleToLongBits(((aD + bD) * aD) / bD), localsD[8]);
    }
}
