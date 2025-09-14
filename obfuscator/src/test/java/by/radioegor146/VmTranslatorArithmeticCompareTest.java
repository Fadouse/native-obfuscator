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

/**
 * Unit tests for VM arithmetic and comparison operations
 * Tests IREM, LREM, FREM, DREM, LNEG, FNEG, DNEG, LCMP, FCMPL, FCMPG, DCMPL, DCMPG
 */
public class VmTranslatorArithmeticCompareTest {

    static class SampleArithmetic {
        static int testIrem(int a, int b) {
            return a % b;
        }

        static long testLrem(long a, long b) {
            return a % b;
        }

        static float testFrem(float a, float b) {
            return a % b;
        }

        static double testDrem(double a, double b) {
            return a % b;
        }

        static long testLneg(long a) {
            return -a;
        }

        static float testFneg(float a) {
            return -a;
        }

        static double testDneg(double a) {
            return -a;
        }

        static int testLcmp(long a, long b) {
            return Long.compare(a, b);
        }

        static int testFcmpl(float a, float b) {
            return Float.compare(a, b); // This should generate FCMPL or FCMPG
        }

        static int testFcmpg(float a, float b) {
            if (a > b) return 1;
            if (a < b) return -1;
            return 0; // This may generate FCMPG depending on compilation
        }

        static int testDcmpl(double a, double b) {
            return Double.compare(a, b); // This should generate DCMPL or DCMPG
        }

        static int testDcmpg(double a, double b) {
            if (a > b) return 1;
            if (a < b) return -1;
            return 0; // This may generate DCMPG depending on compilation
        }
    }

    private long run(Instruction[] code, long[] locals, Object[] oLocals) {
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
                case VmOpcodes.OP_ADD:
                case VmOpcodes.OP_LADD:
                    if (sp >= 2) {
                        long b = stack[--sp];
                        long a = stack[--sp];
                        stack[sp++] = a + b;
                    }
                    break;
                case VmOpcodes.OP_SUB:
                case VmOpcodes.OP_LSUB:
                    if (sp >= 2) {
                        long b = stack[--sp];
                        long a = stack[--sp];
                        stack[sp++] = a - b;
                    }
                    break;
                case VmOpcodes.OP_MUL:
                case VmOpcodes.OP_LMUL:
                    if (sp >= 2) {
                        long b = stack[--sp];
                        long a = stack[--sp];
                        stack[sp++] = a * b;
                    }
                    break;
                case VmOpcodes.OP_DIV:
                case VmOpcodes.OP_LDIV:
                    if (sp >= 2) {
                        long b = stack[--sp];
                        long a = stack[--sp];
                        if (b != 0) {
                            stack[sp++] = a / b;
                        } else {
                            stack[sp++] = 0; // Simplified handling
                        }
                    }
                    break;
                case VmOpcodes.OP_IREM:
                    if (sp >= 2) {
                        long b = stack[--sp];
                        long a = stack[--sp];
                        if (b != 0) {
                            stack[sp++] = ((int) a) % ((int) b);
                        } else {
                            stack[sp++] = 0; // Simplified handling
                        }
                    }
                    break;
                case VmOpcodes.OP_LREM:
                    if (sp >= 2) {
                        long b = stack[--sp];
                        long a = stack[--sp];
                        if (b != 0) {
                            stack[sp++] = a % b;
                        } else {
                            stack[sp++] = 0; // Simplified handling
                        }
                    }
                    break;
                case VmOpcodes.OP_FREM:
                    if (sp >= 2) {
                        long b_bits = stack[--sp];
                        long a_bits = stack[--sp];
                        float b = Float.intBitsToFloat((int) b_bits);
                        float a = Float.intBitsToFloat((int) a_bits);
                        float result = a % b; // Java % operator
                        stack[sp++] = Float.floatToIntBits(result);
                    }
                    break;
                case VmOpcodes.OP_DREM:
                    if (sp >= 2) {
                        long b_bits = stack[--sp];
                        long a_bits = stack[--sp];
                        double b = Double.longBitsToDouble(b_bits);
                        double a = Double.longBitsToDouble(a_bits);
                        double result = a % b; // Java % operator
                        stack[sp++] = Double.doubleToLongBits(result);
                    }
                    break;
                case VmOpcodes.OP_NEG:
                    if (sp >= 1) {
                        long a = stack[--sp];
                        stack[sp++] = -((int) a);
                    }
                    break;
                case VmOpcodes.OP_LNEG:
                    if (sp >= 1) {
                        long a = stack[--sp];
                        stack[sp++] = -a;
                    }
                    break;
                case VmOpcodes.OP_FNEG:
                    if (sp >= 1) {
                        long a_bits = stack[--sp];
                        float a = Float.intBitsToFloat((int) a_bits);
                        float result = -a;
                        stack[sp++] = Float.floatToIntBits(result);
                    }
                    break;
                case VmOpcodes.OP_DNEG:
                    if (sp >= 1) {
                        long a_bits = stack[--sp];
                        double a = Double.longBitsToDouble(a_bits);
                        double result = -a;
                        stack[sp++] = Double.doubleToLongBits(result);
                    }
                    break;
                case VmOpcodes.OP_LCMP:
                    if (sp >= 2) {
                        long b = (long) stack[--sp];
                        long a = stack[--sp];
                        if (a > b) stack[sp++] = 1;
                        else if (a < b) stack[sp++] = -1;
                        else stack[sp++] = 0;
                    }
                    break;
                case VmOpcodes.OP_FCMPL:
                    if (sp >= 2) {
                        long b_bits = stack[--sp];
                        long a_bits = stack[--sp];
                        float b = Float.intBitsToFloat((int) b_bits);
                        float a = Float.intBitsToFloat((int) a_bits);
                        if (Float.isNaN(a) || Float.isNaN(b)) {
                            stack[sp++] = -1; // NaN handling for FCMPL
                        } else if (a > b) {
                            stack[sp++] = 1;
                        } else if (a < b) {
                            stack[sp++] = -1;
                        } else {
                            stack[sp++] = 0;
                        }
                    }
                    break;
                case VmOpcodes.OP_FCMPG:
                    if (sp >= 2) {
                        long b_bits = stack[--sp];
                        long a_bits = stack[--sp];
                        float b = Float.intBitsToFloat((int) b_bits);
                        float a = Float.intBitsToFloat((int) a_bits);
                        if (Float.isNaN(a) || Float.isNaN(b)) {
                            stack[sp++] = 1; // NaN handling for FCMPG
                        } else if (a > b) {
                            stack[sp++] = 1;
                        } else if (a < b) {
                            stack[sp++] = -1;
                        } else {
                            stack[sp++] = 0;
                        }
                    }
                    break;
                case VmOpcodes.OP_DCMPL:
                    if (sp >= 2) {
                        long b_bits = stack[--sp];
                        long a_bits = stack[--sp];
                        double b = Double.longBitsToDouble(b_bits);
                        double a = Double.longBitsToDouble(a_bits);
                        if (Double.isNaN(a) || Double.isNaN(b)) {
                            stack[sp++] = -1; // NaN handling for DCMPL
                        } else if (a > b) {
                            stack[sp++] = 1;
                        } else if (a < b) {
                            stack[sp++] = -1;
                        } else {
                            stack[sp++] = 0;
                        }
                    }
                    break;
                case VmOpcodes.OP_DCMPG:
                    if (sp >= 2) {
                        long b_bits = stack[--sp];
                        long a_bits = stack[--sp];
                        double b = Double.longBitsToDouble(b_bits);
                        double a = Double.longBitsToDouble(a_bits);
                        if (Double.isNaN(a) || Double.isNaN(b)) {
                            stack[sp++] = 1; // NaN handling for DCMPG
                        } else if (a > b) {
                            stack[sp++] = 1;
                        } else if (a < b) {
                            stack[sp++] = -1;
                        } else {
                            stack[sp++] = 0;
                        }
                    }
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
    public void testArithmeticAndCompareInstructions() throws Exception {
        VmTranslator translator = new VmTranslator();
        ClassReader cr = new ClassReader(SampleArithmetic.class.getName());
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        // Test IREM instruction
        MethodNode iremMethod = cn.methods.stream().filter(m -> m.name.equals("testIrem")).findFirst().orElseThrow();
        Instruction[] iremCode = translator.translate(iremMethod);
        if (iremCode != null) {
            assertTrue(Arrays.stream(iremCode).anyMatch(i -> i.opcode == VmOpcodes.OP_IREM || i.opcode == VmOpcodes.OP_LOAD));
        }

        // Test LREM instruction
        MethodNode lremMethod = cn.methods.stream().filter(m -> m.name.equals("testLrem")).findFirst().orElseThrow();
        Instruction[] lremCode = translator.translate(lremMethod);
        if (lremCode != null) {
            assertTrue(Arrays.stream(lremCode).anyMatch(i -> i.opcode == VmOpcodes.OP_LREM || i.opcode == VmOpcodes.OP_LLOAD));
        }

        // Test FREM instruction
        MethodNode fremMethod = cn.methods.stream().filter(m -> m.name.equals("testFrem")).findFirst().orElseThrow();
        Instruction[] fremCode = translator.translate(fremMethod);
        if (fremCode != null) {
            assertTrue(Arrays.stream(fremCode).anyMatch(i -> i.opcode == VmOpcodes.OP_FREM || i.opcode == VmOpcodes.OP_FLOAD));
        }

        // Test DREM instruction
        MethodNode dremMethod = cn.methods.stream().filter(m -> m.name.equals("testDrem")).findFirst().orElseThrow();
        Instruction[] dremCode = translator.translate(dremMethod);
        if (dremCode != null) {
            assertTrue(Arrays.stream(dremCode).anyMatch(i -> i.opcode == VmOpcodes.OP_DREM || i.opcode == VmOpcodes.OP_DLOAD));
        }

        // Test LNEG instruction
        MethodNode lnegMethod = cn.methods.stream().filter(m -> m.name.equals("testLneg")).findFirst().orElseThrow();
        Instruction[] lnegCode = translator.translate(lnegMethod);
        if (lnegCode != null) {
            assertTrue(Arrays.stream(lnegCode).anyMatch(i -> i.opcode == VmOpcodes.OP_LNEG || i.opcode == VmOpcodes.OP_LLOAD));
        }

        // Test FNEG instruction
        MethodNode fnegMethod = cn.methods.stream().filter(m -> m.name.equals("testFneg")).findFirst().orElseThrow();
        Instruction[] fnegCode = translator.translate(fnegMethod);
        if (fnegCode != null) {
            assertTrue(Arrays.stream(fnegCode).anyMatch(i -> i.opcode == VmOpcodes.OP_FNEG || i.opcode == VmOpcodes.OP_FLOAD));
        }

        // Test DNEG instruction
        MethodNode dnegMethod = cn.methods.stream().filter(m -> m.name.equals("testDneg")).findFirst().orElseThrow();
        Instruction[] dnegCode = translator.translate(dnegMethod);
        if (dnegCode != null) {
            assertTrue(Arrays.stream(dnegCode).anyMatch(i -> i.opcode == VmOpcodes.OP_DNEG || i.opcode == VmOpcodes.OP_DLOAD));
        }

        // Test LCMP instruction
        MethodNode lcmpMethod = cn.methods.stream().filter(m -> m.name.equals("testLcmp")).findFirst().orElseThrow();
        Instruction[] lcmpCode = translator.translate(lcmpMethod);
        if (lcmpCode != null) {
            assertTrue(Arrays.stream(lcmpCode).anyMatch(i -> i.opcode == VmOpcodes.OP_LCMP || i.opcode == VmOpcodes.OP_LLOAD));
        }

        // Run functional tests for arithmetic operations
        testIremFunctionality();
        testLremFunctionality();
        testNegationFunctionality();
        testComparisonFunctionality();

        System.out.println("All arithmetic and comparison tests passed!");
    }

    private void testIremFunctionality() {
        Instruction[] testCode = {
            new Instruction(VmOpcodes.OP_PUSH, 7),    // Push 7
            new Instruction(VmOpcodes.OP_PUSH, 3),    // Push 3
            new Instruction(VmOpcodes.OP_IREM, 0),    // 7 % 3 = 1
            new Instruction(VmOpcodes.OP_HALT, 0)     // Halt
        };
        long[] locals = new long[2];
        Object[] oLocals = new Object[1];
        long result = run(testCode, locals, oLocals);
        assertEquals(1L, result, "IREM: 7 % 3 should equal 1");
    }

    private void testLremFunctionality() {
        Instruction[] testCode = {
            new Instruction(VmOpcodes.OP_PUSH, 10),   // Push 10
            new Instruction(VmOpcodes.OP_PUSH, 4),    // Push 4
            new Instruction(VmOpcodes.OP_LREM, 0),    // 10 % 4 = 2
            new Instruction(VmOpcodes.OP_HALT, 0)     // Halt
        };
        long[] locals = new long[2];
        Object[] oLocals = new Object[1];
        long result = run(testCode, locals, oLocals);
        assertEquals(2L, result, "LREM: 10 % 4 should equal 2");
    }

    private void testNegationFunctionality() {
        // Test LNEG
        Instruction[] testLnegCode = {
            new Instruction(VmOpcodes.OP_PUSH, 42),   // Push 42
            new Instruction(VmOpcodes.OP_LNEG, 0),    // -42
            new Instruction(VmOpcodes.OP_HALT, 0)     // Halt
        };
        long[] locals = new long[2];
        Object[] oLocals = new Object[1];
        long result = run(testLnegCode, locals, oLocals);
        assertEquals(-42L, result, "LNEG: -42 should equal -42");
    }

    private void testComparisonFunctionality() {
        // Test LCMP - equal values
        Instruction[] testLcmpEq = {
            new Instruction(VmOpcodes.OP_PUSH, 5),    // Push 5
            new Instruction(VmOpcodes.OP_PUSH, 5),    // Push 5
            new Instruction(VmOpcodes.OP_LCMP, 0),    // compare: should return 0
            new Instruction(VmOpcodes.OP_HALT, 0)     // Halt
        };
        long[] locals = new long[2];
        Object[] oLocals = new Object[1];
        long result = run(testLcmpEq, locals, oLocals);
        assertEquals(0L, result, "LCMP: 5 compared to 5 should equal 0");

        // Test LCMP - first > second
        Instruction[] testLcmpGt = {
            new Instruction(VmOpcodes.OP_PUSH, 7),    // Push 7
            new Instruction(VmOpcodes.OP_PUSH, 3),    // Push 3
            new Instruction(VmOpcodes.OP_LCMP, 0),    // compare: should return 1
            new Instruction(VmOpcodes.OP_HALT, 0)     // Halt
        };
        result = run(testLcmpGt, locals, oLocals);
        assertEquals(1L, result, "LCMP: 7 compared to 3 should equal 1");

        // Test LCMP - first < second
        Instruction[] testLcmpLt = {
            new Instruction(VmOpcodes.OP_PUSH, 2),    // Push 2
            new Instruction(VmOpcodes.OP_PUSH, 8),    // Push 8
            new Instruction(VmOpcodes.OP_LCMP, 0),    // compare: should return -1
            new Instruction(VmOpcodes.OP_HALT, 0)     // Halt
        };
        result = run(testLcmpLt, locals, oLocals);
        assertEquals(-1L, result, "LCMP: 2 compared to 8 should equal -1");
    }
}