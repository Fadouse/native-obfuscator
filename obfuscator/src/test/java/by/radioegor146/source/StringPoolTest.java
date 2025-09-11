package by.radioegor146.source;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class StringPoolTest {

    @Test
    public void testBuild() {
        StringPool stringPool = new StringPool();

        stringPool.get("test");

        assertEquals(
                "#include \"string_pool.hpp\"\n" +
                        "#include <cstddef>\n" +
                        "\n" +
                        "namespace native_jvm::string_pool {\n" +
                        "    static char pool[5LL] = { 11, 150, 70, 1, 71 };\n" +
                        "\n" +
                        "    void decrypt_pool() {\n" +
                        "        for (size_t i = 0; i < 5LL; ++i) {\n" +
                        "            unsigned char key = (i * 0x5A + 0xAC) & 0xFF;\n" +
                        "            unsigned char val = static_cast<unsigned char>(pool[i]) - 0x33;\n" +
                        "            pool[i] = static_cast<char>(val ^ key);\n" +
                        "        }\n" +
                        "    }\n" +
                        "\n" +
                        "    char *get_pool() {\n" +
                        "        return pool;\n" +
                        "    }\n" +
                        "}\n", stringPool.build());

        stringPool.get("other");

        assertEquals(
                "#include \"string_pool.hpp\"\n" +
                        "#include <cstddef>\n" +
                        "\n" +
                        "namespace native_jvm::string_pool {\n" +
                        "    static char pool[11LL] = { 11, 150, 70, 1, 71, 52, 239, 125, 76, 215, 99 };\n" +
                        "\n" +
                        "    void decrypt_pool() {\n" +
                        "        for (size_t i = 0; i < 11LL; ++i) {\n" +
                        "            unsigned char key = (i * 0x5A + 0xAC) & 0xFF;\n" +
                        "            unsigned char val = static_cast<unsigned char>(pool[i]) - 0x33;\n" +
                        "            pool[i] = static_cast<char>(val ^ key);\n" +
                        "        }\n" +
                        "    }\n" +
                        "\n" +
                        "    char *get_pool() {\n" +
                        "        return pool;\n" +
                        "    }\n" +
                        "}\n", stringPool.build());
    }

    @Test
    public void testGet() {
        StringPool stringPool = new StringPool();
        assertEquals("((char *)(string_pool + 0LL))", stringPool.get("test"));
        assertEquals("((char *)(string_pool + 0LL))", stringPool.get("test"));
        assertEquals("((char *)(string_pool + 5LL))", stringPool.get("\u0080\u0050"));
        assertEquals("((char *)(string_pool + 9LL))", stringPool.get("\u0800"));
        assertEquals("((char *)(string_pool + 13LL))", stringPool.get("\u0080"));
    }
}
