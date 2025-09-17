package by.radioegor146;

import by.radioegor146.helpers.ProcessHelper;
import by.radioegor146.helpers.ProcessHelper.ProcessResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ensures VM-translated methods handle getstatic/putstatic and getfield/putfield correctly.
 */
public class VmGetPutFieldPipelineTest {

    @Test
    public void testFieldAccessesThroughPipeline() throws Exception {
        Path temp = Files.createTempDirectory("vm-get-put-field");
        Path src = temp.resolve("src");
        Path classes = temp.resolve("classes");
        Path out = temp.resolve("out");
        Files.createDirectories(src);
        Files.createDirectories(classes);
        Files.createDirectories(out);

        String fieldOps = "public class FieldOps {\n" +
                "    static int s;\n" +
                "    int i;\n" +
                "    public static int run(FieldOps obj) {\n" +
                "        s = 3;\n" +
                "        int a = s;\n" +
                "        obj.i = 4;\n" +
                "        int b = obj.i;\n" +
                "        return a + b;\n" +
                "    }\n" +
                "}\n";
        String runner = "public class Runner {\n" +
                "    public static void main(String[] args) {\n" +
                "        FieldOps obj = new FieldOps();\n" +
                "        int r = FieldOps.run(obj);\n" +
                "        System.out.print(r + \" \" + FieldOps.s + \" \" + obj.i);\n" +
                "    }\n" +
                "}\n";
        Files.write(src.resolve("FieldOps.java"), fieldOps.getBytes());
        Files.write(src.resolve("Runner.java"), runner.getBytes());

        ProcessHelper.run(temp, 10_000,
                Arrays.asList("javac", "-d", classes.toString(),
                        src.resolve("FieldOps.java").toString(),
                        src.resolve("Runner.java").toString()))
                .check("javac");

        Path inputJar = temp.resolve("input.jar");
        ProcessHelper.run(temp, 10_000,
                Arrays.asList("jar", "cf", inputJar.toString(), "-C", classes.toString(), "."))
                .check("jar");

        new NativeObfuscator().process(inputJar, out, Collections.emptyList(),
                Collections.emptyList(), null, "native_library", null,
                Platform.HOTSPOT, false, false, true, true, true);

        Path cppDir = out.resolve("cpp");
        ProcessHelper.run(cppDir, 120_000, Arrays.asList("cmake", "."))
                .check("CMake configure");
        ProcessHelper.run(cppDir, 160_000,
                Arrays.asList("cmake", "--build", ".", "--config", "Release"))
                .check("CMake build");

        Files.find(cppDir.resolve("build").resolve("lib"), 1,
                (p, a) -> Files.isRegularFile(p)).forEach(p -> {
            try {
                Files.copy(p, out.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        String cpp = Files.readString(cppDir.resolve("output").resolve("FieldOps_0.cpp"));
        assertTrue(cpp.contains("native_jvm::vm::"), "VM instructions missing in generated C++");

        Path resultJar = out.resolve("input.jar");
        ProcessResult run = ProcessHelper.run(out, 20_000,
                Arrays.asList("java", "-Djava.library.path=.", "-cp", resultJar.toString(), "Runner"));
        run.check("run");
        assertEquals("7 3 4", run.stdout.trim());
    }
}
