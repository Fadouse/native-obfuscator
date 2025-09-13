package test;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;

public class ClassCacheTest {
    static {
        try {
            compileAndLoad();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void compileAndLoad() throws Exception {
        Path tempDir = Files.createTempDirectory("native");
        Path cpp = tempDir.resolve("test_native.cpp");
        String code = String.join("\n",
            "#include \"micro_vm.hpp\"",
            "using namespace native_jvm::vm;",
            "",
            "extern \"C\" JNIEXPORT jlong JNICALL Java_test_ClassCacheNative_runTest(JNIEnv* env, jclass) {",
            "    clear_class_cache(env);",
            "    init_key(42);",
            "    FieldRef sRef{\"test/TestFields\", \"sVal\", \"I\"};",
            "    FieldRef iRef{\"test/TestFields\", \"val\", \"I\"};",
            "    jclass cls = env->FindClass(\"test/TestFields\");",
            "    jmethodID ctor = env->GetMethodID(cls, \"<init>\", \"()V\");",
            "    jobject obj = env->NewObject(cls, ctor);",
            "    Instruction prog[7] = {",
            "        {OP_GETSTATIC, reinterpret_cast<int64_t>(&sRef), 0},",
            "        {OP_GETSTATIC, reinterpret_cast<int64_t>(&sRef), 0},",
            "        {OP_PUSH, reinterpret_cast<int64_t>(obj), 0},",
            "        {OP_GETFIELD, reinterpret_cast<int64_t>(&iRef), 0},",
            "        {OP_PUSH, reinterpret_cast<int64_t>(obj), 0},",
            "        {OP_GETFIELD, reinterpret_cast<int64_t>(&iRef), 0},",
            "        {OP_HALT, 0, 0}",
            "    };",
            "    encode_program(prog, 7, 42);",
            "    execute(env, prog, 7, nullptr, 0, 42);",
            "    env->DeleteLocalRef(obj);",
            "    env->DeleteLocalRef(cls);",
            "    return (jlong)get_class_cache_calls();",
            "}"
        );
        Files.writeString(cpp, code);

        String javaHome = System.getProperty("java.home");
        Path lib = tempDir.resolve("libclasscache.so");
        List<String> cmd = Arrays.asList(
            "g++", "-std=c++17", "-fPIC", "-shared",
            "-I", javaHome + "/include",
            "-I", javaHome + "/include/linux",
            "-Isrc/main/resources/sources",
            cpp.toString(),
            "src/main/resources/sources/micro_vm.cpp",
            "src/main/resources/sources/vm_jit.cpp",
            "-o", lib.toString()
        );
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(Paths.get(".").toFile());
        Process p = pb.start();
        if (p.waitFor() != 0) {
            throw new RuntimeException("g++ failed");
        }
        System.load(lib.toAbsolutePath().toString());
    }

    @Test
    public void repeatedFieldAccessesUseCache() {
        long calls = ClassCacheNative.runTest();
        assertEquals(1L, calls);
    }
}
