package by.radioegor146.javaobf.frame;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 为 ClassWriter/Verifier 提供类信息的统一入口 */
public interface ClassProvider {
    /** 返回内部名为 internalName 的 .class 字节（若不存在返回 null） */
    byte[] getClassBytes(String internalName);

    /** 一个常用实现：先从自定义路径/内存，再回退到当前 ClassLoader 的资源路径 */
    static ClassProvider ofClasspathFallback() {
        return new ClassProvider() {
            private final Map<String, byte[]> cache = new ConcurrentHashMap<>();
            @Override public byte[] getClassBytes(String internalName) {
                return cache.computeIfAbsent(internalName, k -> {
                    String res = k + ".class";
                    try (InputStream in = ClassLoader.getSystemResourceAsStream(res)) {
                        if (in == null) return null;
                        return in.readAllBytes();
                    } catch (IOException e) {
                        return null;
                    }
                });
            }
        };
    }

    default ClassNode readClassNode(String internalName) {
        byte[] bytes = getClassBytes(internalName);
        if (bytes == null) return null;
        ClassReader cr = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        // 展开帧，便于稳定分析
        cr.accept(cn, ClassReader.EXPAND_FRAMES);
        return cn;
    }

    default String getSuperName(String internalName) {
        ClassNode cn = readClassNode(internalName);
        return cn != null ? cn.superName : "java/lang/Object";
    }

    default String[] getInterfaces(String internalName) {
        ClassNode cn = readClassNode(internalName);
        return cn != null ? cn.interfaces.toArray(String[]::new) : new String[0];
    }

    default boolean isAssignableFrom(String a, String b) {
        // a 是否为 b 的超类型/同类型
        if (a.equals(b)) return true;
        String cur = b;
        while (cur != null) {
            if (a.equals(cur)) return true;
            for (String itf : getInterfaces(cur)) {
                if (isAssignableFrom(a, itf)) return true;
            }
            cur = getSuperName(cur);
        }
        return false;
    }

    default String commonSuper(String t1, String t2) {
        if (t1.equals(t2)) return t1;
        if (isAssignableFrom(t1, t2)) return t1;
        if (isAssignableFrom(t2, t1)) return t2;
        // 向上找最近公共父类
        String s = t1;
        while (s != null && !isAssignableFrom(s, t2)) {
            s = getSuperName(s);
        }
        return s != null ? s : "java/lang/Object";
    }
}
