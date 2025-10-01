package by.radioegor146.javaobf.frame;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

public class ComputingFrameClassWriter extends ClassWriter {
    private final ClassProvider provider;

    public ComputingFrameClassWriter(int flags, ClassProvider provider) {
        super(flags);
        this.provider = provider;
    }

    public ComputingFrameClassWriter(ClassReader cr, int flags, ClassProvider provider) {
        super(cr, flags);
        this.provider = provider;
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        // 使用 provider 离线解析，避免 Class.forName 导致 ClassLoader 污染或找不到类
        return provider.commonSuper(type1, type2);
    }
}
