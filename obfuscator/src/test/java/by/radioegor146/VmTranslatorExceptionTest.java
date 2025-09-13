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
 * Tests translation and execution of the ATHROW instruction.
 */
public class VmTranslatorExceptionTest {

    static class Sample {
        static void rethrow(RuntimeException e) {
            throw e;
        }
    }

    private void run(Instruction[] code, Object[] locals) throws Throwable {
        Object[] stack = new Object[256];
        int sp = 0;
        int pc = 0;
        while (pc < code.length) {
            Instruction ins = code[pc++];
            switch (ins.opcode) {
                case VmOpcodes.OP_ALOAD:
                    stack[sp++] = locals[(int) ins.operand];
                    break;
                case VmOpcodes.OP_ATHROW:
                    Throwable t = (Throwable) stack[--sp];
                    throw t;
                case VmOpcodes.OP_HALT:
                    return;
                default:
                    throw new IllegalStateException("Unknown opcode: " + ins.opcode);
            }
        }
    }

    @Test
    public void testThrowingException() throws Exception {
        ClassReader cr = new ClassReader(Sample.class.getName());
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        MethodNode mn = cn.methods.stream()
                .filter(m -> m.name.equals("rethrow"))
                .findFirst()
                .orElse(null);
        assertNotNull(mn);

        VmTranslator translator = new VmTranslator();
        Instruction[] code = translator.translate(mn);
        assertNotNull(code);
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_ATHROW));

        RuntimeException ex = new RuntimeException("boom");
        Object[] locals = new Object[]{ex};

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> run(code, locals));
        assertSame(ex, thrown);
    }
}
