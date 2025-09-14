package by.radioegor146;

import by.radioegor146.instructions.VmTranslator;
import by.radioegor146.instructions.VmTranslator.Instruction;
import by.radioegor146.instructions.VmTranslator.VmOpcodes;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ensures that methods with void return types are correctly translated
 * to a terminating {@link VmOpcodes#OP_HALT} instruction.
 */
public class VmTranslatorVoidReturnTest {

    /** Sample class containing a void method. */
    static class Sample {
        static void store(int a, int b) {
            int c = a + b;
        }
    }

    /**
     * Simple interpreter for a subset of VM instructions used by the test.
     */
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
                case VmOpcodes.OP_HALT:
                    return sp > 0 ? stack[sp - 1] : 0;
                default:
                    throw new IllegalStateException("Unknown opcode: " + ins.opcode);
            }
        }
        return sp > 0 ? stack[sp - 1] : 0;
    }

    @Test
    public void testVoidMethodTranslation() throws Exception {
        ClassReader cr = new ClassReader(Sample.class.getName());
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        MethodNode mn = cn.methods.stream()
                .filter(m -> m.name.equals("store"))
                .findFirst()
                .orElse(null);
        assertNotNull(mn);

        VmTranslator translator = new VmTranslator();
        Instruction[] code = translator.translate(mn);
        assertNotNull(code);

        // Ensure that the method terminates with OP_HALT
        assertEquals(VmOpcodes.OP_HALT, code[code.length - 1].opcode);

        long[] locals = new long[3];
        locals[0] = 2;
        locals[1] = 3;
        long result = run(code, locals);

        // Void method should not return a value but should store the sum
        assertEquals(0, result);
        assertEquals(locals[0] + locals[1], locals[2]);
    }
}

