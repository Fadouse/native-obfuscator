package by.radioegor146.javaobf.frame;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.*;

public final class PreAnalyzer {
    private PreAnalyzer() {}

    @SuppressWarnings("unchecked")
    public static Frame<BasicValue>[] analyzeWithFallback(
            String ownerInternalName,
            String ownerSuperName,
            String[] ownerInterfaces,
            MethodNode method,
            ClassProvider provider,
            boolean isStatic
    ) throws AnalyzerException {

        // 1) 尝试 SimpleVerifier（需要类层次信息）
        try {
            SimpleVerifier verifier = new SimpleVerifier(
                    Type.getObjectType(ownerInternalName),
                    ownerSuperName == null ? null : Type.getObjectType(ownerSuperName),
                    java.util.Arrays.asList(toTypes(ownerInterfaces)),  // 修复：转换为List
                    !isStatic // isMainMethodInstance? false for static
            ) {
                @Override
                protected Class<?> getClass(Type t) { return Object.class; } // 禁止真实加载类
                @Override
                protected boolean isInterface(Type t) {
                    if (t == null) return false;
                    String in = t.getInternalName();
                    var cn = provider.readClassNode(in);
                    return cn != null && (cn.access & Opcodes.ACC_INTERFACE) != 0;
                }
                @Override
                protected Type getSuperClass(Type t) {
                    if (t == null) return null;
                    String sup = provider.getSuperName(t.getInternalName());
                    return sup == null ? null : Type.getObjectType(sup);
                }
                @Override
                protected boolean isAssignableFrom(Type t, Type u) {
                    if (t == null || u == null) return false;
                    return provider.isAssignableFrom(t.getInternalName(), u.getInternalName());
                }
            };
            Analyzer<BasicValue> a = new Analyzer<>(verifier);
            return (Frame<BasicValue>[]) a.analyze(ownerInternalName, method);
        } catch (Throwable ignore) {
            // 2) 退回 BasicInterpreter（更宽松）
            Analyzer<BasicValue> a = new Analyzer<>(new BasicInterpreter());
            return (Frame<BasicValue>[]) a.analyze(ownerInternalName, method);
        }
    }

    private static Type getTypeOrNull(String in) {
        return in == null ? null : Type.getObjectType(in);
    }

    private static Type[] toTypes(String[] ins) {
        if (ins == null || ins.length == 0) return new Type[0];
        Type[] ts = new Type[ins.length];
        for (int i = 0; i < ins.length; i++) ts[i] = getTypeOrNull(ins[i]);
        return ts;
    }
}
