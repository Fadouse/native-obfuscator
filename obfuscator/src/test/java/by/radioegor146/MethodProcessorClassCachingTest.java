package by.radioegor146;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Objects;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

public class MethodProcessorClassCachingTest {

    static class TypeLoopSample {
        static Object loop(Object value, int iterations) {
            Object result = null;
            for (int i = 0; i < iterations; i++) {
                if (value instanceof String) {
                    result = (String) value;
                }
            }
            return result;
        }

        static Class<?> classLiteralLoop(int iterations) {
            Class<?> clazz = Object.class;
            for (int i = 0; i < iterations; i++) {
                clazz = String.class;
            }
            return clazz;
        }

        static void recursive(int depth) {
            if (depth > 0) {
                recursive(depth - 1);
            }
        }
    }

    private NativeObfuscator obfuscator;
    private MethodProcessor processor;
    private ClassNode classNode;

    @BeforeEach
    void setUp() throws Exception {
        obfuscator = new NativeObfuscator();
        processor = new MethodProcessor(obfuscator);

        ClassReader cr = new ClassReader(TypeLoopSample.class.getName());
        classNode = new ClassNode();
        cr.accept(classNode, ClassReader.EXPAND_FRAMES);
    }

    private MethodContext createContext(String name, String desc) {
        MethodNode target = null;
        int index = -1;
        for (int i = 0; i < classNode.methods.size(); i++) {
            MethodNode candidate = classNode.methods.get(i);
            if (Objects.equals(candidate.name, name) && Objects.equals(candidate.desc, desc)) {
                target = candidate;
                index = i;
                break;
            }
        }
        assertNotNull(target, "Expected method not found: " + name + desc);

        return new MethodContext(obfuscator, target, index, classNode, 0, ProtectionConfig.createDefault());
    }

    @Test
    void typeInstructionsReuseVerifiedClassLocals() {
        MethodContext context = createContext("loop", "(Ljava/lang/Object;I)Ljava/lang/Object;");
        processor.processMethod(context);

        String output = context.output.toString();

        assertTrue(output.contains("jclass cclass_local0 = nullptr;"), output);
        assertTrue(output.contains("bool cclass_local0_cached = false;"), output);
        assertTrue(output.contains("if (!cclass_local0_cached)"), output);
        assertTrue(Pattern.compile("IsInstanceOf\\(cstack\\d+\\.l, cclass_local0\\)").matcher(output).find(), output);
        assertFalse(Pattern.compile("IsInstanceOf\\(cstack\\d+\\.l, \\(?cclasses").matcher(output).find(), output);
    }

    @Test
    void ldcClassUsesVerifiedClassLocal() {
        MethodContext context = createContext("classLiteralLoop", "(I)Ljava/lang/Class;");
        processor.processMethod(context);

        String output = context.output.toString();

        assertTrue(output.contains("bool cclass_local0_cached = false;"), output);
        assertTrue(Pattern.compile("cstack\\d+\\.l = cclass_local0").matcher(output).find(), output);
        assertTrue(Pattern.compile("refs.insert\\(cstack\\d+\\.l\\);").matcher(output).find(), output);
        assertFalse(Pattern.compile("cstack\\d+\\.l = \\(?cclasses").matcher(output).find(), output);
    }

    @Test
    void staticRecursionBypassesJniBridge() {
        MethodContext context = createContext("recursive", "(I)V");
        processor.processMethod(context);

        String output = context.output.toString();

        assertTrue(output.contains("__ngen_native_recursive"), output);
        assertFalse(output.contains("CallStaticVoidMethod"), output);
    }
}
