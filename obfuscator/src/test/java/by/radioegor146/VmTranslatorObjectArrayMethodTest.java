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
 * Tests translation and execution of array access and method invocation
 * instructions for the VM.
 */
public class VmTranslatorObjectArrayMethodTest {

    static class Helper {
        static int identity(int x) { return x; }
    }

    static class Sample {
        static int process(int[] arr) {
            int v = arr[0];
            v = Helper.identity(v);
            arr[0] = v;
            return v;
        }
    }

    private Object run(Instruction[] code, Object[] locals) {
        Object[] stack = new Object[256];
        int sp = 0;
        int pc = 0;
        while (pc < code.length) {
            Instruction ins = code[pc++];
            switch (ins.opcode) {
                case VmOpcodes.OP_PUSH:
                    stack[sp++] = (long) ins.operand;
                    break;
                case VmOpcodes.OP_LOAD:
                    stack[sp++] = locals[ins.operand];
                    break;
                case VmOpcodes.OP_STORE:
                    locals[ins.operand] = stack[--sp];
                    break;
                case VmOpcodes.OP_IALOAD:
                    int idx = (int) (long) stack[--sp];
                    int[] arr = (int[]) stack[--sp];
                    stack[sp++] = (long) arr[idx];
                    break;
                case VmOpcodes.OP_IASTORE:
                    int val = (int) (long) stack[--sp];
                    int index = (int) (long) stack[--sp];
                    int[] array = (int[]) stack[--sp];
                    array[index] = val;
                    break;
                case VmOpcodes.OP_CALL:
                    long arg = (long) stack[--sp];
                    if (ins.operand == 0) {
                        stack[sp++] = (long) Helper.identity((int) arg);
                    } else {
                        throw new IllegalStateException("Unknown method index: " + ins.operand);
                    }
                    break;
                case VmOpcodes.OP_HALT:
                    return sp > 0 ? stack[sp - 1] : null;
                default:
                    throw new IllegalStateException("Unknown opcode: " + ins.opcode);
            }
        }
        return sp > 0 ? stack[sp - 1] : null;
    }

    @Test
    public void testArrayAndCall() throws Exception {
        ClassReader cr = new ClassReader(Sample.class.getName());
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        MethodNode mn = cn.methods.stream()
                .filter(m -> m.name.equals("process"))
                .findFirst()
                .orElse(null);
        assertNotNull(mn);

        VmTranslator translator = new VmTranslator();
        Instruction[] code = translator.translate(mn);
        assertNotNull(code);

        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_IALOAD));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_IASTORE));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_CALL));

        int[] arr = {5};
        int[] arrCopy = {5};
        Object[] locals = new Object[2];
        locals[0] = arr;
        long result = (Long) run(code, locals);

        assertEquals(Sample.process(arrCopy), result);
        assertArrayEquals(arrCopy, arr);
    }
}
