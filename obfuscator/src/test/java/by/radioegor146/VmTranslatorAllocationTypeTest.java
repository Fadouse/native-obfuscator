package by.radioegor146;

import by.radioegor146.instructions.VmTranslator;
import by.radioegor146.instructions.VmTranslator.Instruction;
import by.radioegor146.instructions.VmTranslator.VmOpcodes;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Array;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class VmTranslatorAllocationTypeTest {

    private Object run(Instruction[] code, Object[] locals, List<Class<?>> classes, List<VmTranslator.MultiArrayRefInfo> multiArrayRefs) throws Exception {
        Object[] stack = new Object[256];
        int sp = 0;
        int pc = 0;
        while (pc < code.length) {
            Instruction ins = code[pc++];
            switch (ins.opcode) {
                case VmOpcodes.OP_PUSH:
                    stack[sp++] = ins.operand;
                    break;
                case VmOpcodes.OP_LOAD:
                case VmOpcodes.OP_LLOAD:
                case VmOpcodes.OP_FLOAD:
                case VmOpcodes.OP_DLOAD:
                case VmOpcodes.OP_ALOAD:
                    stack[sp++] = locals[(int) ins.operand];
                    break;
                case VmOpcodes.OP_NEW: {
                    Class<?> clazz = classes.get((int) ins.operand);
                    stack[sp++] = clazz.getDeclaredConstructor().newInstance();
                    break;
                }
                case VmOpcodes.OP_ANEWARRAY: {
                    int len = (int) (long) stack[--sp];
                    Class<?> clazz = classes.get((int) ins.operand);
                    Object arr = Array.newInstance(clazz, len);
                    stack[sp++] = arr;
                    break;
                }
                case VmOpcodes.OP_NEWARRAY: {
                    int len = (int) (long) stack[--sp];
                    Class<?> comp;
                    switch ((int) ins.operand) {
                        case Opcodes.T_BOOLEAN: comp = boolean.class; break;
                        case Opcodes.T_CHAR: comp = char.class; break;
                        case Opcodes.T_FLOAT: comp = float.class; break;
                        case Opcodes.T_DOUBLE: comp = double.class; break;
                        case Opcodes.T_BYTE: comp = byte.class; break;
                        case Opcodes.T_SHORT: comp = short.class; break;
                        case Opcodes.T_INT: comp = int.class; break;
                        case Opcodes.T_LONG: comp = long.class; break;
                        default: throw new IllegalStateException();
                    }
                    Object arr = Array.newInstance(comp, len);
                    stack[sp++] = arr;
                    break;
                }
                case VmOpcodes.OP_MULTIANEWARRAY: {
                    VmTranslator.MultiArrayRefInfo refInfo = multiArrayRefs.get((int) ins.operand);
                    int dims = refInfo.dims;
                    int[] sizes = new int[dims];
                    for (int i = dims - 1; i >= 0; --i) {
                        sizes[i] = (int) (long) stack[--sp];
                    }
                    Class<?> base = getArrayElementType(refInfo.desc);
                    Object arr = Array.newInstance(base, sizes);
                    stack[sp++] = arr;
                    break;
                }
                case VmOpcodes.OP_CHECKCAST: {
                    Class<?> clazz = classes.get((int) ins.operand);
                    Object obj = stack[sp - 1];
                    if (obj != null && !clazz.isInstance(obj)) {
                        throw new ClassCastException();
                    }
                    break;
                }
                case VmOpcodes.OP_INSTANCEOF: {
                    Class<?> clazz = classes.get((int) ins.operand);
                    Object obj = stack[--sp];
                    stack[sp++] = clazz.isInstance(obj) ? 1L : 0L;
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

    private List<Class<?>> collectClasses(MethodNode mn) throws Exception {
        Map<String, Integer> map = new HashMap<>();
        List<Class<?>> classes = new ArrayList<>();
        for (AbstractInsnNode ins = mn.instructions.getFirst(); ins != null; ins = ins.getNext()) {
            int op = ins.getOpcode();
            if (op == Opcodes.NEW || op == Opcodes.ANEWARRAY || op == Opcodes.CHECKCAST || op == Opcodes.INSTANCEOF) {
                String desc = ((TypeInsnNode) ins).desc;
                if (!map.containsKey(desc)) {
                    map.put(desc, classes.size());
                    classes.add(classForDescriptor(desc));
                }
            } else if (op == Opcodes.MULTIANEWARRAY) {
                String desc = ((MultiANewArrayInsnNode) ins).desc;
                if (!map.containsKey(desc)) {
                    map.put(desc, classes.size());
                    Type t = Type.getType(desc).getElementType();
                    classes.add(classForType(t));
                }
            }
        }
        return classes;
    }

    private Class<?> classForDescriptor(String desc) throws ClassNotFoundException {
        if (desc.startsWith("[")) {
            return Class.forName(Type.getType(desc).getClassName());
        }
        return Class.forName(desc.replace('/', '.'));
    }

    private Class<?> classForType(Type t) throws ClassNotFoundException {
        switch (t.getSort()) {
            case Type.BOOLEAN: return boolean.class;
            case Type.CHAR: return char.class;
            case Type.BYTE: return byte.class;
            case Type.SHORT: return short.class;
            case Type.INT: return int.class;
            case Type.FLOAT: return float.class;
            case Type.LONG: return long.class;
            case Type.DOUBLE: return double.class;
            case Type.OBJECT: return Class.forName(t.getClassName());
            default: throw new ClassNotFoundException();
        }
    }

    private Class<?> getArrayElementType(String desc) throws ClassNotFoundException {
        Type type = Type.getType(desc);
        while (type.getSort() == Type.ARRAY) {
            type = type.getElementType();
        }
        return classForType(type);
    }

    @Test
    public void testAllocations() throws Exception {
        VmTranslator translator = new VmTranslator();

        // NEW
        MethodNode mnNew = new MethodNode(Opcodes.ACC_STATIC, "m", "()Ljava/lang/Object;", null, null);
        mnNew.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/Object"));
        mnNew.instructions.add(new InsnNode(Opcodes.ARETURN));
        mnNew.maxStack = 1; mnNew.maxLocals = 0;
        Instruction[] codeNew = translator.translate(mnNew);
        assertNotNull(codeNew);
        assertTrue(Arrays.stream(codeNew).anyMatch(i -> i.opcode == VmOpcodes.OP_NEW));
        Object resNew = run(codeNew, new Object[0], collectClasses(mnNew), new ArrayList<>());
        assertNotNull(resNew);

        // ANEWARRAY
        MethodNode mnA = new MethodNode(Opcodes.ACC_STATIC, "m", "(I)[Ljava/lang/String;", null, null);
        mnA.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        mnA.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
        mnA.instructions.add(new InsnNode(Opcodes.ARETURN));
        mnA.maxStack = 1; mnA.maxLocals = 1;
        Instruction[] codeA = translator.translate(mnA);
        assertNotNull(codeA);
        assertTrue(Arrays.stream(codeA).anyMatch(i -> i.opcode == VmOpcodes.OP_ANEWARRAY));
        Object arrA = run(codeA, new Object[]{2L}, collectClasses(mnA), new ArrayList<>());
        assertEquals(2, Array.getLength(arrA));

        // NEWARRAY
        MethodNode mnN = new MethodNode(Opcodes.ACC_STATIC, "m", "(I)[I", null, null);
        mnN.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        mnN.instructions.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        mnN.instructions.add(new InsnNode(Opcodes.ARETURN));
        mnN.maxStack = 1; mnN.maxLocals = 1;
        Instruction[] codeN = translator.translate(mnN);
        assertNotNull(codeN);
        assertTrue(Arrays.stream(codeN).anyMatch(i -> i.opcode == VmOpcodes.OP_NEWARRAY));
        Object arrN = run(codeN, new Object[]{3L}, collectClasses(mnN), new ArrayList<>());
        assertEquals(3, Array.getLength(arrN));

        // MULTIANEWARRAY
        MethodNode mnM = new MethodNode(Opcodes.ACC_STATIC, "m", "(II)[[I", null, null);
        mnM.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        mnM.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        mnM.instructions.add(new MultiANewArrayInsnNode("[[I", 2));
        mnM.instructions.add(new InsnNode(Opcodes.ARETURN));
        mnM.maxStack = 2; mnM.maxLocals = 2;
        Instruction[] codeM = translator.translate(mnM);
        assertNotNull(codeM);
        assertTrue(Arrays.stream(codeM).anyMatch(i -> i.opcode == VmOpcodes.OP_MULTIANEWARRAY));
        Object arrM = run(codeM, new Object[]{2L,3L}, collectClasses(mnM), translator.getMultiArrayRefs());
        assertEquals(2, Array.getLength(arrM));
    }

    @Test
    public void testTypeChecks() throws Exception {
        VmTranslator translator = new VmTranslator();

        // CHECKCAST
        MethodNode mnC = new MethodNode(Opcodes.ACC_STATIC, "m", "(Ljava/lang/Object;)Ljava/lang/String;", null, null);
        mnC.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        mnC.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
        mnC.instructions.add(new InsnNode(Opcodes.ARETURN));
        mnC.maxStack = 1; mnC.maxLocals = 1;
        Instruction[] codeC = translator.translate(mnC);
        assertNotNull(codeC);
        assertTrue(Arrays.stream(codeC).anyMatch(i -> i.opcode == VmOpcodes.OP_CHECKCAST));
        List<Class<?>> classesC = collectClasses(mnC);
        Object castRes = run(codeC, new Object[]{"x"}, classesC, new ArrayList<>());
        assertEquals("x", castRes);
        assertThrows(ClassCastException.class, () -> run(codeC, new Object[]{new Object()}, classesC, new ArrayList<>()));

        // INSTANCEOF
        MethodNode mnI = new MethodNode(Opcodes.ACC_STATIC, "m", "(Ljava/lang/Object;)I", null, null);
        mnI.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        mnI.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, "java/lang/String"));
        mnI.instructions.add(new InsnNode(Opcodes.IRETURN));
        mnI.maxStack = 1; mnI.maxLocals = 1;
        Instruction[] codeI = translator.translate(mnI);
        assertNotNull(codeI);
        assertTrue(Arrays.stream(codeI).anyMatch(i -> i.opcode == VmOpcodes.OP_INSTANCEOF));
        List<Class<?>> classesI = collectClasses(mnI);
        long inst1 = (long) run(codeI, new Object[]{"x"}, classesI, new ArrayList<>());
        long inst2 = (long) run(codeI, new Object[]{new Object()}, classesI, new ArrayList<>());
        assertEquals(1L, inst1);
        assertEquals(0L, inst2);
    }
}
