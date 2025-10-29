package by.radioegor146;

import dev.skidfuscator.obfuscator.FlowExceptionMode;
import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.SkidfuscatorSession;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
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
            "    public static void main(String[] args) throws Exception {" +
            "        Class<?> clazz = Class.forName(\"test.Sample\");" +
            "        java.lang.reflect.Method method = clazz.getDeclaredMethod(\"greet\", String.class);" +
            "        method.setAccessible(true);" +
            "        java.lang.reflect.Field field = clazz.getDeclaredField(\"message\");" +
            "        field.setAccessible(true);" +
            "        Object instance = clazz.getDeclaredConstructor().newInstance();" +
            "        field.set(instance, \"Hola\");" +
            "        String target = args.length == 0 ? \"world\" : args[0];" +
            "        System.out.println(method.invoke(instance, target));" +
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
            verifyRuntimeBehavior(outputJar);
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
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "test.Sample");

        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
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
            assertNull(jar.getEntry("dev/skidfuscator/runtime/ReflectionMappings.class"), "Reflection metadata should not be emitted");
            assertNull(jar.getEntry("dev/skidfuscator/runtime/ReflectionSupport.class"), "Reflection helper should not be emitted");

            Manifest manifest = jar.getManifest();
            assertNotNull(manifest, "Manifest missing from jar");
            String mainClass = manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
            assertNotNull(mainClass, "Main-Class attribute missing");
            assertNotEquals("test.Sample", mainClass, "Manifest main class was not updated");

            String mainEntry = mainClass.replace('.', '/') + ".class";
            assertNotNull(jar.getEntry(mainEntry), "Obfuscated main class missing from jar");

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

    private static void verifyRuntimeBehavior(Path outputJar) throws Exception {
        try (JarFile jar = new JarFile(outputJar.toFile())) {
            Manifest manifest = jar.getManifest();
            assertNotNull(manifest, "Manifest missing");
            String mainClass = manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
            assertNotNull(mainClass, "Main-Class attribute missing");

            try (URLClassLoader loader = new URLClassLoader(new URL[]{outputJar.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader())) {
                Class<?> entry = Class.forName(mainClass, true, loader);
                Method main = entry.getMethod("main", String[].class);

                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                PrintStream previous = System.out;
                PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8.name());
                try {
                    System.setOut(capture);
                    main.invoke(null, new Object[]{new String[]{"Agent"}});
                } finally {
                    capture.close();
                    System.setOut(previous);
                }

                String output = buffer.toString(StandardCharsets.UTF_8.name());
                assertTrue(output.contains("Hola Agent"), "Reflection-based main execution failed: " + output);
            }
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
