package by.radioegor146;

import by.radioegor146.instructions.VmTranslator;
import by.radioegor146.instructions.VmTranslator.Instruction;
import by.radioegor146.instructions.VmTranslator.VmOpcodes;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class VmTranslatorFieldTest {

    static class Sample {
        static int s;
        int i;
    }

    private Object run(Instruction[] code, Object[] locals, List<Field> fields) throws Exception {
        Object[] stack = new Object[256];
        int sp = 0;
        int pc = 0;
        while (pc < code.length) {
            Instruction ins = code[pc++];
            switch (ins.opcode) {
                case VmOpcodes.OP_LOAD:
                case VmOpcodes.OP_ALOAD:
                    stack[sp++] = locals[(int) ins.operand];
                    break;
                case VmOpcodes.OP_GETSTATIC: {
                    Field f = fields.get((int) ins.operand);
                    Object val = f.get(null);
                    stack[sp++] = (long) (int) val;
                    break;
                }
                case VmOpcodes.OP_PUTSTATIC: {
                    Field f = fields.get((int) ins.operand);
                    long v = (long) stack[--sp];
                    f.setInt(null, (int) v);
                    break;
                }
                case VmOpcodes.OP_GETFIELD: {
                    Field f = fields.get((int) ins.operand);
                    Object obj = stack[--sp];
                    Object val = f.get(obj);
                    stack[sp++] = (long) (int) val;
                    break;
                }
                case VmOpcodes.OP_PUTFIELD: {
                    Field f = fields.get((int) ins.operand);
                    long v = (long) stack[--sp];
                    Object obj = stack[--sp];
                    f.setInt(obj, (int) v);
                    break;
                }
                case VmOpcodes.OP_HALT:
                    return stack[sp - 1];
                default:
                    throw new IllegalStateException("Unknown opcode: " + ins.opcode);
            }
        }
        return stack[sp - 1];
    }

    private List<Field> collectFields(MethodNode mn) throws Exception {
        Map<String, Integer> map = new HashMap<>();
        List<Field> res = new ArrayList<>();
        for (AbstractInsnNode ins = mn.instructions.getFirst(); ins != null; ins = ins.getNext()) {
            int op = ins.getOpcode();
            if (op == Opcodes.GETSTATIC || op == Opcodes.PUTSTATIC || op == Opcodes.GETFIELD || op == Opcodes.PUTFIELD) {
                FieldInsnNode fi = (FieldInsnNode) ins;
                String key = fi.owner + '.' + fi.name + ':' + fi.desc;
                if (!map.containsKey(key)) {
                    map.put(key, res.size());
                    Class<?> owner = Class.forName(fi.owner.replace('/', '.'));
                    Field f = owner.getDeclaredField(fi.name);
                    f.setAccessible(true);
                    res.add(f);
                }
            }
        }
        return res;
    }

    @Test
    public void testStaticField() throws Exception {
        VmTranslator translator = new VmTranslator();
        String owner = Type.getInternalName(Sample.class);
        MethodNode mn = new MethodNode(Opcodes.ACC_STATIC, "m", "(I)I", null, null);
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        mn.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, "s", "I"));
        mn.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, "s", "I"));
        mn.instructions.add(new InsnNode(Opcodes.IRETURN));
        mn.maxStack = 2; mn.maxLocals = 1;

        Instruction[] code = translator.translate(mn);
        assertNotNull(code);
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_GETSTATIC));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_PUTSTATIC));

        Sample.s = 0;
        Object result = run(code, new Object[]{5L}, collectFields(mn));
        assertEquals(5L, result);
        assertEquals(5, Sample.s);
    }

    @Test
    public void testInstanceField() throws Exception {
        VmTranslator translator = new VmTranslator();
        String owner = Type.getInternalName(Sample.class);
        MethodNode mn = new MethodNode(0, "m", "(I)I", null, null);
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        mn.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "i", "I"));
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        mn.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "i", "I"));
        mn.instructions.add(new InsnNode(Opcodes.IRETURN));
        mn.maxStack = 2; mn.maxLocals = 2;

        Instruction[] code = translator.translate(mn);
        assertNotNull(code);
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_GETFIELD));
        assertTrue(Arrays.stream(code).anyMatch(i -> i.opcode == VmOpcodes.OP_PUTFIELD));

        Sample obj = new Sample();
        Object result = run(code, new Object[]{obj, 7L}, collectFields(mn));
        assertEquals(7L, result);
        assertEquals(7, obj.i);
    }
}

