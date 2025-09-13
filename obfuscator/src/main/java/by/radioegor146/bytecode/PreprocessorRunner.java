package by.radioegor146.bytecode;

import by.radioegor146.Platform;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

public class PreprocessorRunner {

    private final static List<Preprocessor> PREPROCESSORS = new ArrayList<>();
    private static Remapper remapper = new Remapper() {};

    static {
        PREPROCESSORS.add(new IndyPreprocessor());
        PREPROCESSORS.add(new LdcPreprocessor());
    }

    public static void setRemapper(Remapper remapper) {
        PreprocessorRunner.remapper = remapper;
    }

    public static Remapper getRemapper() {
        return remapper;
    }

    public static void preprocess(ClassNode classNode, MethodNode methodNode, Platform platform) {
        for (Preprocessor preprocessor : PREPROCESSORS) {
            preprocessor.process(classNode, methodNode, platform);
        }
    }
}
