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

public class VmTranslatorPrimitiveArrayTest {

    static class SampleArrays {
        static int processInt(int[] arr) {
            int tmp = arr[0];
            arr[1] = tmp;
            return arr[1];
        }
        static byte processByte(byte[] arr) {
            byte tmp = arr[0];
            arr[1] = tmp;
            return arr[1];
        }
        static char processChar(char[] arr) {
            char tmp = arr[0];
            arr[1] = tmp;
            return arr[1];
        }
        static short processShort(short[] arr) {
            short tmp = arr[0];
            arr[1] = tmp;
            return arr[1];
        }
        static long processLong(long[] arr) {
            long tmp = arr[0];
            arr[1] = tmp;
            return arr[1];
        }
        static float processFloat(float[] arr) {
            float tmp = arr[0];
            arr[1] = tmp;
            return arr[1];
        }
        static double processDouble(double[] arr) {
            double tmp = arr[0];
            arr[1] = tmp;
            return arr[1];
        }
    }

    private long run(Instruction[] code, long[] locals, Object[] oLocals) {
        Object[] stack = new Object[256];
        int sp = 0;
        for (int pc = 0; pc < code.length; pc++) {
            Instruction ins = code[pc];
            switch (ins.opcode) {
                case VmOpcodes.OP_PUSH:
                    stack[sp++] = ins.operand;
                    break;
                case VmOpcodes.OP_LOAD:
                    stack[sp++] = locals[(int) ins.operand];
                    break;
                case VmOpcodes.OP_STORE:
                    locals[(int) ins.operand] = (long) stack[--sp];
                    break;
                case VmOpcodes.OP_ALOAD:
                    stack[sp++] = oLocals[(int) ins.operand];
                    break;
                case VmOpcodes.OP_ASTORE:
                    oLocals[(int) ins.operand] = stack[--sp];
                    break;
                case VmOpcodes.OP_IALOAD: {
                    int idx = (int) (long) stack[--sp];
                    int[] arr = (int[]) stack[--sp];
                    stack[sp++] = (long) arr[idx];
                    break;
                }
                case VmOpcodes.OP_LALOAD: {
                    int idx = (int) (long) stack[--sp];
                    long[] arr = (long[]) stack[--sp];
                    stack[sp++] = arr[idx];
                    break;
                }
                case VmOpcodes.OP_FALOAD: {
                    int idx = (int) (long) stack[--sp];
                    float[] arr = (float[]) stack[--sp];
                    // Convert float to int bits for consistency with micro_vm
                    stack[sp++] = (long) Float.floatToIntBits(arr[idx]);
                    break;
                }
                case VmOpcodes.OP_DALOAD: {
                    int idx = (int) (long) stack[--sp];
                    double[] arr = (double[]) stack[--sp];
                    // Convert double to long bits for consistency with micro_vm
                    stack[sp++] = Double.doubleToLongBits(arr[idx]);
                    break;
                }
                case VmOpcodes.OP_BALOAD: {
                    int idx = (int) (long) stack[--sp];
                    byte[] arr = (byte[]) stack[--sp];
                    stack[sp++] = (long) arr[idx];
                    break;
                }
                case VmOpcodes.OP_CALOAD: {
                    int idx = (int) (long) stack[--sp];
                    char[] arr = (char[]) stack[--sp];
                    stack[sp++] = (long) arr[idx];
                    break;
                }
                case VmOpcodes.OP_SALOAD: {
                    int idx = (int) (long) stack[--sp];
                    short[] arr = (short[]) stack[--sp];
                    stack[sp++] = (long) arr[idx];
                    break;
                }
                case VmOpcodes.OP_IASTORE: {
                    long val = (long) stack[--sp];
                    int idx = (int) (long) stack[--sp];
                    int[] arr = (int[]) stack[--sp];
                    arr[idx] = (int) val;
                    break;
                }
                case VmOpcodes.OP_LASTORE: {
                    long val = (long) stack[--sp];
                    int idx = (int) (long) stack[--sp];
                    long[] arr = (long[]) stack[--sp];
                    arr[idx] = val;
                    break;
                }
                case VmOpcodes.OP_FASTORE: {
                    long val = (long) stack[--sp];
                    int idx = (int) (long) stack[--sp];
                    float[] arr = (float[]) stack[--sp];
                    // Convert int bits back to float for consistency with micro_vm
                    arr[idx] = Float.intBitsToFloat((int) val);
                    break;
                }
                case VmOpcodes.OP_DASTORE: {
                    long val = (long) stack[--sp];
                    int idx = (int) (long) stack[--sp];
                    double[] arr = (double[]) stack[--sp];
                    // Convert long bits back to double for consistency with micro_vm
                    arr[idx] = Double.longBitsToDouble(val);
                    break;
                }
                case VmOpcodes.OP_BASTORE: {
                    long val = (long) stack[--sp];
                    int idx = (int) (long) stack[--sp];
                    byte[] arr = (byte[]) stack[--sp];
                    arr[idx] = (byte) val;
                    break;
                }
                case VmOpcodes.OP_CASTORE: {
                    long val = (long) stack[--sp];
                    int idx = (int) (long) stack[--sp];
                    char[] arr = (char[]) stack[--sp];
                    arr[idx] = (char) val;
                    break;
                }
                case VmOpcodes.OP_SASTORE: {
                    long val = (long) stack[--sp];
                    int idx = (int) (long) stack[--sp];
                    short[] arr = (short[]) stack[--sp];
                    arr[idx] = (short) val;
                    break;
                }
                // Add missing opcodes that may be generated during translation
                case VmOpcodes.OP_LLOAD:
                    stack[sp++] = locals[(int) ins.operand];
                    break;
                case VmOpcodes.OP_LSTORE:
                    locals[(int) ins.operand] = (long) stack[--sp];
                    break;
                case VmOpcodes.OP_FLOAD:
                    stack[sp++] = locals[(int) ins.operand];
                    break;
                case VmOpcodes.OP_FSTORE:
                    locals[(int) ins.operand] = (long) stack[--sp];
                    break;
                case VmOpcodes.OP_DLOAD:
                    stack[sp++] = locals[(int) ins.operand];
                    break;
                case VmOpcodes.OP_DSTORE:
                    locals[(int) ins.operand] = (long) stack[--sp];
                    break;
                case VmOpcodes.OP_HALT:
                    return sp > 0 ? (long) stack[sp - 1] : 0;
                default:
                    throw new IllegalStateException("Unknown opcode: " + ins.opcode);
            }
        }
        return sp > 0 ? (long) stack[sp - 1] : 0;
    }

    @Test
    public void testPrimitiveArrays() throws Exception {
        VmTranslator translator = new VmTranslator();
        ClassReader cr = new ClassReader(SampleArrays.class.getName());
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        // int[]
        MethodNode mnI = cn.methods.stream().filter(m -> m.name.equals("processInt")).findFirst().orElseThrow();
        Instruction[] codeI = translator.translate(mnI);
        assertNotNull(codeI);
        assertTrue(Arrays.stream(codeI).anyMatch(i -> i.opcode == VmOpcodes.OP_IALOAD));
        assertTrue(Arrays.stream(codeI).anyMatch(i -> i.opcode == VmOpcodes.OP_IASTORE));
        long[] localsI = new long[2];
        Object[] oLocalsI = new Object[1];
        int[] arrI = new int[]{5,0};
        oLocalsI[0] = arrI;
        long resI = run(codeI, localsI, oLocalsI);
        assertEquals(SampleArrays.processInt(new int[]{5,0}), (int) resI);
        assertEquals(arrI[0], arrI[1]);

        // byte[]
        MethodNode mnB = cn.methods.stream().filter(m -> m.name.equals("processByte")).findFirst().orElseThrow();
        Instruction[] codeB = translator.translate(mnB);
        assertNotNull(codeB);
        assertTrue(Arrays.stream(codeB).anyMatch(i -> i.opcode == VmOpcodes.OP_BALOAD));
        assertTrue(Arrays.stream(codeB).anyMatch(i -> i.opcode == VmOpcodes.OP_BASTORE));
        long[] localsB = new long[2];
        Object[] oLocalsB = new Object[1];
        byte[] arrB = new byte[]{3,0};
        oLocalsB[0] = arrB;
        long resB = run(codeB, localsB, oLocalsB);
        assertEquals(SampleArrays.processByte(new byte[]{3,0}), (byte) resB);
        assertEquals(arrB[0], arrB[1]);

        // char[]
        MethodNode mnC = cn.methods.stream().filter(m -> m.name.equals("processChar")).findFirst().orElseThrow();
        Instruction[] codeC = translator.translate(mnC);
        assertNotNull(codeC);
        assertTrue(Arrays.stream(codeC).anyMatch(i -> i.opcode == VmOpcodes.OP_CALOAD));
        assertTrue(Arrays.stream(codeC).anyMatch(i -> i.opcode == VmOpcodes.OP_CASTORE));
        long[] localsC = new long[2];
        Object[] oLocalsC = new Object[1];
        char[] arrC = new char[]{'a','\0'};
        oLocalsC[0] = arrC;
        long resC = run(codeC, localsC, oLocalsC);
        assertEquals(SampleArrays.processChar(new char[]{'a','\0'}), (char) resC);
        assertEquals(arrC[0], arrC[1]);

        // short[]
        MethodNode mnS = cn.methods.stream().filter(m -> m.name.equals("processShort")).findFirst().orElseThrow();
        Instruction[] codeS = translator.translate(mnS);
        assertNotNull(codeS);
        assertTrue(Arrays.stream(codeS).anyMatch(i -> i.opcode == VmOpcodes.OP_SALOAD));
        assertTrue(Arrays.stream(codeS).anyMatch(i -> i.opcode == VmOpcodes.OP_SASTORE));
        long[] localsS = new long[2];
        Object[] oLocalsS = new Object[1];
        short[] arrS = new short[]{7,0};
        oLocalsS[0] = arrS;
        long resS = run(codeS, localsS, oLocalsS);
        assertEquals(SampleArrays.processShort(new short[]{7,0}), (short) resS);
        assertEquals(arrS[0], arrS[1]);

        // long[] - New array type test
        MethodNode mnL = cn.methods.stream().filter(m -> m.name.equals("processLong")).findFirst().orElseThrow();
        Instruction[] codeL = translator.translate(mnL);
        assertNotNull(codeL);
        assertTrue(Arrays.stream(codeL).anyMatch(i -> i.opcode == VmOpcodes.OP_LALOAD));
        assertTrue(Arrays.stream(codeL).anyMatch(i -> i.opcode == VmOpcodes.OP_LASTORE));
        long[] localsL = new long[2];
        Object[] oLocalsL = new Object[1];
        long[] arrL = new long[]{123456789L, 0L};
        oLocalsL[0] = arrL;
        long resL = run(codeL, localsL, oLocalsL);
        assertEquals(SampleArrays.processLong(new long[]{123456789L, 0L}), resL);
        assertEquals(arrL[0], arrL[1]);

        // float[] - New array type test
        MethodNode mnF = cn.methods.stream().filter(m -> m.name.equals("processFloat")).findFirst().orElseThrow();
        Instruction[] codeF = translator.translate(mnF);
        assertNotNull(codeF);
        assertTrue(Arrays.stream(codeF).anyMatch(i -> i.opcode == VmOpcodes.OP_FALOAD));
        assertTrue(Arrays.stream(codeF).anyMatch(i -> i.opcode == VmOpcodes.OP_FASTORE));
        long[] localsF = new long[2];
        Object[] oLocalsF = new Object[1];
        float[] arrF = new float[]{3.14f, 0.0f};
        oLocalsF[0] = arrF;
        long resF = run(codeF, localsF, oLocalsF);
        // Compare float values through bit representation for consistency
        assertEquals(SampleArrays.processFloat(new float[]{3.14f, 0.0f}), Float.intBitsToFloat((int) resF));
        assertEquals(arrF[0], arrF[1]);

        // double[] - New array type test
        MethodNode mnD = cn.methods.stream().filter(m -> m.name.equals("processDouble")).findFirst().orElseThrow();
        Instruction[] codeD = translator.translate(mnD);
        assertNotNull(codeD);
        assertTrue(Arrays.stream(codeD).anyMatch(i -> i.opcode == VmOpcodes.OP_DALOAD));
        assertTrue(Arrays.stream(codeD).anyMatch(i -> i.opcode == VmOpcodes.OP_DASTORE));
        long[] localsD = new long[2];
        Object[] oLocalsD = new Object[1];
        double[] arrD = new double[]{2.71828, 0.0};
        oLocalsD[0] = arrD;
        long resD = run(codeD, localsD, oLocalsD);
        // Compare double values through bit representation for consistency
        assertEquals(SampleArrays.processDouble(new double[]{2.71828, 0.0}), Double.longBitsToDouble(resD));
        assertEquals(arrD[0], arrD[1]);
    }
}
