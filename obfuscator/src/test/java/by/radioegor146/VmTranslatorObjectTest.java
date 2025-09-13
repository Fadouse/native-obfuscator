package by.radioegor146;

import by.radioegor146.instructions.VmTranslator;
import by.radioegor146.instructions.VmTranslator.Instruction;
import by.radioegor146.instructions.VmTranslator.VmOpcodes;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests translation and execution of object/array/method call instructions.
 */
public class VmTranslatorObjectTest {

    static class SampleObjects {
        static Object helper(Object o) { return o; }
        static Object process(Object[] arr) {
            Object tmp = arr[0];
            arr[1] = tmp;
            return helper(tmp);
        }
    }

    private Object run(Instruction[] code, Object[] locals, List<Method> methods) throws Exception {
        Object[] stack = new Object[256];
        int sp = 0;
        int pc = 0;
        while (pc < code.length) {
            Instruction ins = code[pc++];
            switch (ins.opcode) {
                case VmOpcodes.OP_PUSH:
                case VmOpcodes.OP_LDC:
                case VmOpcodes.OP_LDC_W:
                case VmOpcodes.OP_LDC2_W:
                    stack[sp++] = ins.operand;
                    break;
                case VmOpcodes.OP_ALOAD:
                    stack[sp++] = locals[(int) ins.operand];
                    break;
                case VmOpcodes.OP_ASTORE:
                    locals[(int) ins.operand] = stack[--sp];
                    break;
                case VmOpcodes.OP_AALOAD:
                    int idx = (int) (long) stack[--sp];
                    Object[] arr = (Object[]) stack[--sp];
                    stack[sp++] = arr[idx];
                    break;
                case VmOpcodes.OP_AASTORE:
                    Object val = stack[--sp];
                    int idx2 = (int) (long) stack[--sp];
                    Object[] arr2 = (Object[]) stack[--sp];
                    arr2[idx2] = val;
                    break;
                case VmOpcodes.OP_INVOKESTATIC:
                    Method m = methods.get((int) ins.operand);
                    Object arg = stack[--sp];
                    Object res = m.invoke(null, arg);
                    stack[sp++] = res;
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
    public void testObjectArrayAndMethod() throws Exception {
        ClassReader cr = new ClassReader(SampleObjects.class.getName());
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        MethodNode mn = cn.methods.stream()
                .filter(m -> m.name.equals("process"))
                .findFirst()
                .orElseThrow();

        VmTranslator translator = new VmTranslator();
        Instruction[] code = translator.translate(mn);
        assertNotNull(code);
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_AALOAD));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_AASTORE));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_INVOKESTATIC));

        // Build method table in encounter order
        List<Method> methods = new ArrayList<>();
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.INVOKESTATIC) {
                MethodInsnNode mi = (MethodInsnNode) insn;
                Class<?> owner = Class.forName(mi.owner.replace('/', '.'));
                Type[] argTypes = Type.getArgumentTypes(mi.desc);
                Class<?>[] params = new Class<?>[argTypes.length];
                for (int i = 0; i < argTypes.length; i++) {
                    params[i] = Class.forName(argTypes[i].getClassName());
                }
                Method m = owner.getDeclaredMethod(mi.name, params);
                m.setAccessible(true);
                methods.add(m);
            }
        }

        Object[] arr = new Object[]{"x", null};
        Object[] locals = new Object[2];
        locals[0] = arr;

        Object result = run(code, locals, methods);

        Object[] arrCopy = new Object[]{"x", null};
        Object expected = SampleObjects.process(arrCopy);

        assertEquals(expected, result);
        assertEquals(arr[0], arr[1]);
        assertEquals(arrCopy[0], arrCopy[1]);
    }
}
