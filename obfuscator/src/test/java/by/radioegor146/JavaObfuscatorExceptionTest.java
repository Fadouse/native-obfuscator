package by.radioegor146;

import by.radioegor146.helpers.ProcessHelper;
import by.radioegor146.javaobf.JavaObfuscationConfig;
import by.radioegor146.javaobf.JavaObfuscator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies Java-layer control-flow flattening preserves exception handling semantics.
 */
public class JavaObfuscatorExceptionTest {

    @Test
    public void testTryCatchUnsupportedOperation() throws Exception {
        runWith(JavaObfuscationConfig.Strength.LOW);
        runWith(JavaObfuscationConfig.Strength.MEDIUM);
        runWith(JavaObfuscationConfig.Strength.HIGH);
    }

    private void runWith(JavaObfuscationConfig.Strength strength) throws IOException {
        Path temp = Files.createTempDirectory("java-obf-exc-");
        try {
            Path srcDir = temp.resolve("src");
            Path classesDir = temp.resolve("classes");
            Files.createDirectories(srcDir);
            Files.createDirectories(classesDir);

            String clazz = "package t;\n" +
                    "import java.util.*;\n" +
                    "public class E {\n" +
                    "  public static void main(String[] a){\n" +
                    "    List<Integer> l = Collections.emptyList();\n" +
                    "    try { l.add(1); } catch (UnsupportedOperationException ex) { System.out.println(\"catch\"); }\n" +
                    "  }\n" +
                    "}";
            Path javaFile = srcDir.resolve("t/E.java");
            Files.createDirectories(javaFile.getParent());
            Files.write(javaFile, clazz.getBytes(StandardCharsets.UTF_8));

            ProcessHelper.run(temp, 20_000, Arrays.asList("javac", "-d", classesDir.toString(), javaFile.toString())).check("javac");
            Path jar = temp.resolve("app.jar");
            ProcessHelper.run(temp, 20_000, Arrays.asList("jar", "cvfe", jar.toString(), "t.E", "-C", classesDir.toString(), ".")).check("jar");

            // Run original
            ProcessHelper.ProcessResult ideal = ProcessHelper.run(temp, 10_000, Arrays.asList("java", "-jar", jar.toString()));
            ideal.check("ideal run");

            // Run Java obfuscator
            JavaObfuscator jobf = new JavaObfuscator();
            JavaObfuscationConfig cfg = new JavaObfuscationConfig(true, strength);
            Path outDir = temp.resolve("out");
            Path outJar = jobf.process(jar, outDir, Collections.emptyList(), cfg, false);

            // Run obfuscated
            ProcessHelper.ProcessResult obf = ProcessHelper.run(temp, 10_000, Arrays.asList("java", "-jar", outJar.toString()));
            obf.check("obfuscated run");

            assertEquals(ideal.stdout, obf.stdout, "Program output should match with strength=" + strength);
        } finally {
            try { Files.walk(temp).sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} }); } catch (IOException ignored) {}
        }
    }
}

