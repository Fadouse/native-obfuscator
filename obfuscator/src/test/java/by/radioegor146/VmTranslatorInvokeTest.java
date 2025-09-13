package by.radioegor146;

import by.radioegor146.instructions.VmTranslator;
import by.radioegor146.instructions.VmTranslator.Instruction;
import by.radioegor146.instructions.VmTranslator.VmOpcodes;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class VmTranslatorInvokeTest {

    static class InstanceSample {
        int base;
        InstanceSample(int b) { this.base = b; }
        int inc(int x) { return base + x; }
        static int run(InstanceSample obj, int x) { return obj.inc(x); }
    }

    interface Adder { int add(int a, int b); }
    static class AdderImpl implements Adder {
        @Override
        public int add(int a, int b) { return a + b; }
    }
    static class InterfaceSample {
        static int run(Adder a, int x, int y) { return a.add(x, y); }
    }

    public static class DynSample {
        public static CallSite bootstrap(MethodHandles.Lookup lookup, String name, MethodType type) throws Exception {
            return new ConstantCallSite(lookup.findStatic(DynSample.class, name, type));
        }
        public static int add(int a, int b) { return a + b; }
    }

    private Object convert(Object v, Class<?> type) {
        if (type == int.class || type == short.class || type == byte.class ||
                type == char.class || type == boolean.class) {
            return ((Long) v).intValue();
        } else if (type == long.class) {
            return (Long) v;
        } else {
            return v;
        }
    }

    private Object box(Object v, Class<?> type) {
        if (type == int.class || type == short.class || type == byte.class ||
                type == char.class || type == boolean.class) {
            return (long) ((Number) v).intValue();
        } else if (type == long.class) {
            return v;
        } else {
            return v;
        }
    }

    private Object run(Instruction[] code, Object[] locals, List<Object> invokes) throws Throwable {
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
                case VmOpcodes.OP_ALOAD:
                    stack[sp++] = locals[(int) ins.operand];
                    break;
                case VmOpcodes.OP_INVOKEVIRTUAL:
                case VmOpcodes.OP_INVOKESPECIAL:
                case VmOpcodes.OP_INVOKEINTERFACE:
                case VmOpcodes.OP_INVOKESTATIC: {
                    Method m = (Method) invokes.get((int) ins.operand);
                    Class<?>[] pts = m.getParameterTypes();
                    Object[] args = new Object[pts.length];
                    for (int i = pts.length - 1; i >= 0; i--) {
                        args[i] = convert(stack[--sp], pts[i]);
                    }
                    Object target = null;
                    if (ins.opcode != VmOpcodes.OP_INVOKESTATIC) {
                        target = stack[--sp];
                    }
                    Object r = m.invoke(target, args);
                    if (m.getReturnType() != void.class) {
                        stack[sp++] = box(r, m.getReturnType());
                    }
                    break;
                }
                case VmOpcodes.OP_INVOKEDYNAMIC: {
                    CallSite cs = (CallSite) invokes.get((int) ins.operand);
                    MethodType mt = cs.type();
                    int ac = mt.parameterCount();
                    Object[] args = new Object[ac];
                    for (int i = ac - 1; i >= 0; i--) {
                        args[i] = convert(stack[--sp], mt.parameterType(i));
                    }
                    Object r = cs.getTarget().invokeWithArguments(args);
                    if (mt.returnType() != void.class) {
                        stack[sp++] = box(r, mt.returnType());
                    }
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

    private List<Object> collectInvokes(MethodNode mn) throws Throwable {
        List<Object> list = new ArrayList<>();
        for (AbstractInsnNode ins = mn.instructions.getFirst(); ins != null; ins = ins.getNext()) {
            int op = ins.getOpcode();
            if (op == Opcodes.INVOKEVIRTUAL || op == Opcodes.INVOKESPECIAL ||
                    op == Opcodes.INVOKEINTERFACE || op == Opcodes.INVOKESTATIC) {
                MethodInsnNode mi = (MethodInsnNode) ins;
                Class<?> owner = Class.forName(mi.owner.replace('/', '.'));
                Method target = null;
                for (Method m : owner.getDeclaredMethods()) {
                    if (m.getName().equals(mi.name) && Type.getMethodDescriptor(m).equals(mi.desc)) {
                        target = m;
                        break;
                    }
                }
                Objects.requireNonNull(target).setAccessible(true);
                list.add(target);
            } else if (op == Opcodes.INVOKEDYNAMIC) {
                InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) ins;
                Handle bsm = indy.bsm;
                Class<?> bsmOwner = Class.forName(bsm.getOwner().replace('/', '.'));
                MethodType bsmType = MethodType.fromMethodDescriptorString(bsm.getDesc(), bsmOwner.getClassLoader());
                MethodHandle bsmHandle = MethodHandles.lookup().findStatic(bsmOwner, bsm.getName(), bsmType);
                Object[] args = new Object[3 + indy.bsmArgs.length];
                args[0] = MethodHandles.lookup();
                args[1] = indy.name;
                args[2] = MethodType.fromMethodDescriptorString(indy.desc, bsmOwner.getClassLoader());
                System.arraycopy(indy.bsmArgs, 0, args, 3, indy.bsmArgs.length);
                CallSite cs = (CallSite) bsmHandle.invokeWithArguments(args);
                list.add(cs);
            }
        }
        return list;
    }

    @Test
    public void testInvokeVirtual() throws Throwable {
        ClassReader cr = new ClassReader(InstanceSample.class.getName());
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("run")).findFirst().orElse(null);
        VmTranslator tr = new VmTranslator();
        Instruction[] code = tr.translate(mn);
        assertNotNull(code);
        List<Object> invokes = collectInvokes(mn);
        InstanceSample obj = new InstanceSample(5);
        Object res = run(code, new Object[]{obj, 7L}, invokes);
        assertEquals(12L, res);
    }

    @Test
    public void testInvokeInterface() throws Throwable {
        ClassReader cr = new ClassReader(InterfaceSample.class.getName());
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("run")).findFirst().orElse(null);
        VmTranslator tr = new VmTranslator();
        Instruction[] code = tr.translate(mn);
        assertNotNull(code);
        List<Object> invokes = collectInvokes(mn);
        AdderImpl impl = new AdderImpl();
        Object res = run(code, new Object[]{impl, 3L, 4L}, invokes);
        assertEquals(7L, res);
    }

    @Test
    public void testInvokeDynamic() throws Throwable {
        MethodNode mn = new MethodNode(Opcodes.ACC_STATIC, "dyn", "(II)I", null, null);
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        String owner = Type.getInternalName(DynSample.class);
        Handle bsm = new Handle(Opcodes.H_INVOKESTATIC, owner,
                "bootstrap",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                false);
        mn.instructions.add(new InvokeDynamicInsnNode("add", "(II)I", bsm));
        mn.instructions.add(new InsnNode(Opcodes.IRETURN));
        mn.maxStack = 2;
        mn.maxLocals = 2;

        VmTranslator tr = new VmTranslator();
        Instruction[] code = tr.translate(mn);
        assertNotNull(code);
        List<Object> invokes = collectInvokes(mn);
        Object res = run(code, new Object[]{1L, 2L}, invokes);
        assertEquals(3L, res);
    }
}
