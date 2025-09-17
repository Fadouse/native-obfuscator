package by.radioegor146;

import by.radioegor146.helpers.ProcessHelper;
import by.radioegor146.helpers.ProcessHelper.ProcessResult;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Regression test ensuring VM field references are correctly patched in native code. */
public class VmFieldPipelineTest {

    @Test
    public void testFieldAccessThroughPipeline() throws Exception {
        Path temp = Files.createTempDirectory("vm-field-test");
        Path src = temp.resolve("src");
        Path classes = temp.resolve("classes");
        Path out = temp.resolve("out");
        Files.createDirectories(src);
        Files.createDirectories(classes);
        Files.createDirectories(out);

        String sample = "public class FieldSample {\n" +
                "    public static int s;\n" +
                "    public static int test() { s = 7; return s; }\n" +
                "}\n";
        String mainSrc = "public class Main {\n" +
                "    public static void main(String[] args) {\n" +
                "        System.out.print(FieldSample.test());\n" +
                "        System.out.print(\" \");\n" +
                "        System.out.print(FieldSample.s);\n" +
                "    }\n" +
                "}\n";
        Files.write(src.resolve("FieldSample.java"), sample.getBytes());
        Files.write(src.resolve("Main.java"), mainSrc.getBytes());

        ProcessHelper.run(temp, 10_000,
                Arrays.asList("javac", "-d", classes.toString(),
                        src.resolve("FieldSample.java").toString(),
                        src.resolve("Main.java").toString()))
                .check("javac");

        Path inputJar = temp.resolve("input.jar");
        ProcessHelper.run(temp, 10_000,
                Arrays.asList("jar", "cf", inputJar.toString(), "-C", classes.toString(), "."))
                .check("jar");

        new NativeObfuscator().process(inputJar, out, Collections.emptyList(),
                Collections.singletonList("Main"), null, "native_library", null,
                Platform.HOTSPOT, true, false, true, true, true);

        Path cppDir = out.resolve("cpp");
        ProcessHelper.run(cppDir, 120_000, Arrays.asList("cmake", "."))
                .check("CMake configure");
        ProcessHelper.run(cppDir, 160_000,
                Arrays.asList("cmake", "--build", ".", "--config", "Release"))
                .check("CMake build");

        Files.find(cppDir.resolve("build").resolve("lib"), 1,
                (p, a) -> Files.isRegularFile(p))
                .forEach(p -> {
                    try {
                        Files.copy(p, out.resolve(p.getFileName()));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

        Path resultJar = out.resolve("input.jar");
        ProcessResult run = ProcessHelper.run(out, 20_000,
                Arrays.asList("java", "-Djava.library.path=.", "-cp", resultJar.toString(), "Main"));
        run.check("VM field run");
        assertEquals("7 7", run.stdout.trim());
    }
}
