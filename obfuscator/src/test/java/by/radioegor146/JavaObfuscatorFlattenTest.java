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

public class JavaObfuscatorFlattenTest {

    @Test
    public void testJavaFlattenLowStrength() throws Exception {
        runRoundTrip(JavaObfuscationConfig.Strength.LOW);
    }

    @Test
    public void testJavaFlattenMediumStrength() throws Exception {
        runRoundTrip(JavaObfuscationConfig.Strength.MEDIUM);
    }

    @Test
    public void testJavaFlattenHighStrength() throws Exception {
        runRoundTrip(JavaObfuscationConfig.Strength.HIGH);
    }

    private void runRoundTrip(JavaObfuscationConfig.Strength strength) throws IOException {
        Path temp = Files.createTempDirectory("java-obf-test-");
        try {
            Path srcDir = temp.resolve("src");
            Path classesDir = temp.resolve("classes");
            Files.createDirectories(srcDir);
            Files.createDirectories(classesDir);

            String clazz = "package t;\n" +
                    "public class M {\n" +
                    "  public static void main(String[] a){\n" +
                    "    int s = 0;\n" +
                    "    for(int i=0;i<3;i++){ s += i; }\n" +
                    "    System.out.println(\"S=\"+s);\n" +
                    "  }\n" +
                    "}";
            Path javaFile = srcDir.resolve("t/M.java");
            Files.createDirectories(javaFile.getParent());
            Files.write(javaFile, clazz.getBytes(StandardCharsets.UTF_8));

            ProcessHelper.run(temp, 20_000, Arrays.asList("javac", "-d", classesDir.toString(), javaFile.toString())).check("javac");
            Path jar = temp.resolve("app.jar");
            ProcessHelper.run(temp, 20_000, Arrays.asList("jar", "cvfe", jar.toString(), "t.M", "-C", classesDir.toString(), ".")).check("jar");

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

            assertEquals(ideal.stdout, obf.stdout, "Program output should match");
        } finally {
            // best-effort cleanup
            try { Files.walk(temp).sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} }); } catch (IOException ignored) {}
        }
    }
}

