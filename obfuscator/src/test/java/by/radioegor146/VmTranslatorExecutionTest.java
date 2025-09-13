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
 * Tests translation and execution of newly supported VM instructions
 * like ISUB/IMUL/IDIV/ISTORE.
 */
public class VmTranslatorExecutionTest {

    /** Sample method using the new instructions. */
    static class Sample {
        static int calc(int a, int b) {
            int c = a - b;
            int d = c * a;
            int e = d / b;
            return e;
        }
    }

    /** Simple interpreter matching the micro VM semantics used for testing. */
    private long run(Instruction[] code, long[] locals) {
        long[] stack = new long[256];
        int sp = 0;
        int pc = 0;
        while (pc < code.length) {
            Instruction ins = code[pc++];
            switch (ins.opcode) {
                case VmOpcodes.OP_PUSH:
                    stack[sp++] = ins.operand;
                    break;
                case VmOpcodes.OP_LOAD:
                    stack[sp++] = locals[(int) ins.operand];
                    break;
                case VmOpcodes.OP_STORE:
                    locals[(int) ins.operand] = stack[--sp];
                    break;
                case VmOpcodes.OP_ADD:
                    stack[sp - 2] += stack[sp - 1];
                    sp--;
                    break;
                case VmOpcodes.OP_SUB:
                    stack[sp - 2] -= stack[sp - 1];
                    sp--;
                    break;
                case VmOpcodes.OP_MUL:
                    stack[sp - 2] *= stack[sp - 1];
                    sp--;
                    break;
                case VmOpcodes.OP_DIV:
                    long b = stack[--sp];
                    long a = stack[sp - 1];
                    if (b == 0) throw new ArithmeticException("/ by zero");
                    stack[sp - 1] = a / b;
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
    public void testNewInstructionsTranslateAndExecute() throws Exception {
        // Obtain bytecode for Sample.calc
        ClassReader cr = new ClassReader(Sample.class.getName());
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        MethodNode mn = cn.methods.stream()
                .filter(m -> m.name.equals("calc"))
                .findFirst()
                .orElse(null);
        assertNotNull(mn);

        VmTranslator translator = new VmTranslator();
        Instruction[] code = translator.translate(mn);
        assertNotNull(code);

        // Ensure new opcodes are present in the translated program
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_SUB));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_MUL));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_DIV));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_STORE));

        long a = 10;
        long b = 3;
        long[] locals = new long[5];
        locals[0] = a;
        locals[1] = b;

        long result = run(code, locals);

        assertEquals(Sample.calc((int) a, (int) b), result);
        assertEquals(a - b, locals[2]);
        assertEquals((a - b) * a, locals[3]);
        assertEquals(((a - b) * a) / b, locals[4]);
    }
}

