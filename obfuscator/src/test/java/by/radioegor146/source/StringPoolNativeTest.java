package by.radioegor146.source;

import by.radioegor146.helpers.ProcessHelper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public class StringPoolNativeTest {

    private static void copyResource(String resource, Path target) throws IOException {
        try (java.io.InputStream in = StringPoolNativeTest.class.getClassLoader().getResourceAsStream(resource)) {
            assert in != null;
            Files.copy(in, target);
        }
    }

    @Test
    public void testDecryptIdempotent() throws Exception {
        StringPool pool = new StringPool();
        long encoded = Long.parseLong(pool.get("test" ).replace("LL", ""));
        long offset = encoded ^ 0xAD9CF0L;
        String cpp = pool.build();

        Path tmp = Files.createTempDirectory("string-pool-native");
        Files.writeString(tmp.resolve("string_pool.cpp"), cpp, StandardCharsets.UTF_8);
        copyResource("sources/string_pool.hpp", tmp.resolve("string_pool.hpp"));
        copyResource("sources/micro_vm.cpp", tmp.resolve("micro_vm.cpp"));
        copyResource("sources/micro_vm.hpp", tmp.resolve("micro_vm.hpp"));

        String main = "#include \"string_pool.hpp\"\n" +
                "#include <cstring>\n" +
                "int main(){using namespace native_jvm::string_pool;" +
                "char* a=decrypt_string(" + offset + ");" +
                "char* b=decrypt_string(" + offset + ");" +
                "return std::strcmp(a, \"test\")||std::strcmp(b, \"test\");}";
        Files.writeString(tmp.resolve("main.cpp"), main, StandardCharsets.UTF_8);

        ProcessHelper.run(tmp, 10_000, Arrays.asList(
                "g++", "-std=c++17", "-fpermissive", "-Wno-narrowing", "main.cpp", "string_pool.cpp", "micro_vm.cpp", "-o", "test"))
                .check("C++ build");

        ProcessHelper.run(tmp, 5_000, Arrays.asList("./test"))
                .check("native run");
    }
}

