package by.radioegor146;

import dev.skidfuscator.obfuscator.FlowExceptionMode;
import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.SkidfuscatorSession;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class JvmRenamerIntegrationTest {
    private static final String JAVA_SOURCE = "package test;" +
            "public class Sample {" +
            "    private String message = \"Hello\";" +
            "    public Sample() {}" +
            "    public String greet(String name) {" +
            "        java.util.function.Supplier<String> supplier = () -> message + \" \" + name;" +
            "        return supplier.get();" +
            "    }" +
            "    public static void main(String[] args) {" +
            "        System.out.println(new Sample().greet(args.length == 0 ? \"world\" : args[0]));" +
            "    }" +
            "}";

    private static final String CONFIG = String.join("\n",
            "stringEncryption { enabled = false }",
            "numberEncryption { enabled = false }",
            "flowCondition { enabled = false }",
            "flowException { enabled = false }",
            "flowRange { enabled = false }",
            "flowFactoryMaker { enabled = false }",
            "flowSwitch { enabled = false }",
            "outliner { enabled = false }",
            "ahegao { enabled = false }",
            "native { enabled = false }",
            "reference { enabled = false }",
            "driver { enabled = false }",
            "classRenamer {",
            "  enabled = true",
            "  prefix = \"zz/\"",
            "  depth = 3",
            "  directoryDepth = 2",
            "  segmentLength = 2",
            "}",
            "methodRenamer {",
            "  enabled = true",
            "  depth = 3",
            "}",
            "fieldRenamer {",
            "  enabled = true",
            "  depth = 3",
            "}");

    @Test
    void testJvmRenamerObfuscation() throws Exception {
        Path tempDir = Files.createTempDirectory("jvm-renamer-test");
        try {
            Path sourceDir = tempDir.resolve("src/test");
            Files.createDirectories(sourceDir);
            Path sourceFile = sourceDir.resolve("Sample.java");
            Files.writeString(sourceFile, JAVA_SOURCE, StandardCharsets.UTF_8);

            Path classesDir = tempDir.resolve("classes");
            Files.createDirectories(classesDir);

            compileSource(sourceFile, classesDir);

            Path inputJar = tempDir.resolve("input.jar");
            createJar(classesDir, inputJar);

            Path configFile = tempDir.resolve("config.hocon");
            Files.writeString(configFile, CONFIG, StandardCharsets.UTF_8);

            Path outputJar = tempDir.resolve("output.jar");
            SkidfuscatorSession session = SkidfuscatorSession.builder()
                    .input(inputJar.toFile())
                    .output(outputJar.toFile())
                    .libs(new java.io.File[0])
                    .config(configFile.toFile())
                    .analytics(false)
                    .phantom(false)
                    .fuckit(true)
                    .renamer(true)
                    .debug(false)
                    .skidStringObfuscation(false)
                    .skidNumberObfuscation(false)
                    .skidFlowObfuscation(false)
                    .skidSdkInjection(false)
                    .skidVmHashing(false)
                    .skidInvokeDynamicObfuscation(false)
                    .flowExceptionMode(FlowExceptionMode.STANDARD)
                    .build();

            new Skidfuscator(session).run();

            assertTrue(Files.exists(outputJar), "Obfuscated jar was not created");

            verifyJarContents(outputJar);
            verifyRuntimeMappings(outputJar);
        } finally {
            deleteRecursive(tempDir);
        }
    }

    private static void compileSource(Path sourceFile, Path classesDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Java compiler is required for the test");
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends javax.tools.JavaFileObject> units =
                    fileManager.getJavaFileObjectsFromFiles(List.of(sourceFile.toFile()));
            List<String> options = List.of("-d", classesDir.toString());
            Boolean result = compiler.getTask(null, fileManager, null, options, null, units).call();
            assertTrue(Boolean.TRUE.equals(result), "Compilation failed");
        }
    }

    private static void createJar(Path classesDir, Path jarPath) throws IOException {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            List<Path> files = Files.walk(classesDir)
                    .filter(Files::isRegularFile)
                    .collect(Collectors.toList());
            for (Path file : files) {
                String entryName = classesDir.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                JarEntry entry = new JarEntry(entryName);
                jar.putNextEntry(entry);
                jar.write(Files.readAllBytes(file));
                jar.closeEntry();
            }
        }
    }

    private static void verifyJarContents(Path outputJar) throws IOException {
        try (JarFile jar = new JarFile(outputJar.toFile())) {
            assertNull(jar.getEntry("test/Sample.class"), "Original class name should be absent");
            assertNotNull(jar.getEntry("dev/skidfuscator/runtime/ReflectionMappings.class"), "Metadata class missing");
            assertNotNull(jar.getEntry("dev/skidfuscator/runtime/ReflectionSupport.class"), "Reflection helper missing");

            List<String> classEntries = new ArrayList<>();
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class") && !entry.getName().startsWith("META-INF/")) {
                    classEntries.add(entry.getName());
                }
            }

            boolean foundObfuscated = classEntries.stream()
                    .anyMatch(name -> !name.startsWith("dev/skidfuscator/runtime/") && !name.equals("module-info.class"));
            assertTrue(foundObfuscated, "Obfuscated class entry was not found");
        }
    }

    private static void verifyRuntimeMappings(Path outputJar) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{outputJar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> support = Class.forName("dev.skidfuscator.runtime.ReflectionSupport", true, loader);
            Method obfuscateClassName = support.getMethod("obfuscateClassName", String.class);
            Method obfuscateMethodName = support.getMethod("obfuscateMethodName", String.class, String.class, String.class);
            Method obfuscateFieldName = support.getMethod("obfuscateFieldName", String.class, String.class, String.class);
            Method forName = support.getMethod("forName", String.class);

            String obfuscatedClass = (String) obfuscateClassName.invoke(null, "test.Sample");
            assertNotEquals("test.Sample", obfuscatedClass, "Class name was not remapped");

            Class<?> sampleClass = Class.forName(obfuscatedClass, true, loader);
            Object instance = sampleClass.getDeclaredConstructor().newInstance();

            String obfuscatedMethod = (String) obfuscateMethodName.invoke(null,
                    "test.Sample",
                    "greet",
                    "(Ljava/lang/String;)Ljava/lang/String;");
            Method greet = sampleClass.getDeclaredMethod(obfuscatedMethod, String.class);
            greet.setAccessible(true);
            assertEquals("Hello Agent", greet.invoke(instance, "Agent"));

            String obfuscatedField = (String) obfuscateFieldName.invoke(null,
                    "test.Sample",
                    "message",
                    "Ljava/lang/String;");
            Field field = sampleClass.getDeclaredField(obfuscatedField);
            field.setAccessible(true);
            field.set(instance, "Hi");
            assertEquals("Hi Agent", greet.invoke(instance, "Agent"));

            Class<?> reflected = (Class<?>) forName.invoke(null, "test.Sample");
            assertEquals(sampleClass, reflected);
        }
    }

    private static void deleteRecursive(Path root) {
        if (root == null) {
            return;
        }
        try {
            Files.walk(root)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }
}
