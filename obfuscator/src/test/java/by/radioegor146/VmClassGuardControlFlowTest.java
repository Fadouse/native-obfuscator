package by.radioegor146;

import by.radioegor146.helpers.ProcessHelper;
import by.radioegor146.helpers.ProcessHelper.ProcessResult;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for per-use class guard emission. Ensures cached class handles
 * are initialized even when the first lexical access is skipped at runtime.
 */
public class VmClassGuardControlFlowTest {

    @Test
    public void testClassGuardInitializedWhenFirstBranchIsSkipped() throws Exception {
        Path temp = Files.createTempDirectory("vm-class-guard");
        Path src = temp.resolve("src");
        Path classes = temp.resolve("classes");
        Path out = temp.resolve("out");
        Files.createDirectories(src);
        Files.createDirectories(classes);
        Files.createDirectories(out);

        String helper = "public class GuardTarget {\n" +
                "    public static int VALUE = 42;\n" +
                "    public static int compute() {\n" +
                "        return VALUE + 1;\n" +
                "    }\n" +
                "    public static int fetch() {\n" +
                "        return VALUE;\n" +
                "    }\n" +
                "}\n";
        String runner = "public class BranchRunner {\n" +
                "    public static int selectMethod(boolean flag) {\n" +
                "        if (flag) {\n" +
                "            return GuardTarget.compute();\n" +
                "        } else {\n" +
                "            return GuardTarget.fetch();\n" +
                "        }\n" +
                "    }\n" +
                "    public static int selectField(boolean flag) {\n" +
                "        if (flag) {\n" +
                "            return GuardTarget.compute();\n" +
                "        } else {\n" +
                "            return GuardTarget.VALUE;\n" +
                "        }\n" +
                "    }\n" +
                "    public static void main(String[] args) {\n" +
                "        int m = selectMethod(false);\n" +
                "        int f = selectField(false);\n" +
                "        System.out.print(m + \" \" + f);\n" +
                "    }\n" +
                "}\n";

        Files.writeString(src.resolve("GuardTarget.java"), helper);
        Files.writeString(src.resolve("BranchRunner.java"), runner);

        ProcessHelper.run(temp, 10_000,
                Arrays.asList("javac", "-d", classes.toString(),
                        src.resolve("GuardTarget.java").toString(),
                        src.resolve("BranchRunner.java").toString()))
                .check("javac");

        Path inputJar = temp.resolve("input.jar");
        ProcessHelper.run(temp, 10_000,
                Arrays.asList("jar", "cf", inputJar.toString(), "-C", classes.toString(), "."))
                .check("jar");

        new NativeObfuscator().process(inputJar, out, Collections.emptyList(),
                Collections.emptyList(), null, "native_library", null,
                Platform.HOTSPOT, false, false, true, true, true, true, true);

        Path cppDir = out.resolve("cpp");
        ProcessHelper.run(cppDir, 120_000, Arrays.asList("cmake", "."))
                .check("CMake configure");
        ProcessHelper.run(cppDir, 160_000,
                Arrays.asList("cmake", "--build", ".", "--config", "Release"))
                .check("CMake build");

        Files.find(cppDir.resolve("build").resolve("lib"), 1, (p, a) -> Files.isRegularFile(p)).forEach(p -> {
            try {
                Files.copy(p, out.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Path resultJar = out.resolve("input.jar");
        ProcessResult run = ProcessHelper.run(out, 20_000,
                Arrays.asList("java", "-Djava.library.path=.", "-cp", resultJar.toString(), "BranchRunner"));
        run.check("run");
        assertEquals("42 42", run.stdout.trim());
    }
}

