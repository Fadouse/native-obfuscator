package by.radioegor146.source;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class StringPoolTest {

    @Test
    public void testBuild() {
        StringPool stringPool = new StringPool();

        stringPool.get("test");

        String build1 = stringPool.build();
        org.junit.jupiter.api.Assertions.assertTrue(build1.contains("static unsigned char key[32]"));
        org.junit.jupiter.api.Assertions.assertTrue(build1.contains("static char pool[5LL] = { 254, 185, 226, 137, 159 };"));

        stringPool.get("other");

        String build2 = stringPool.build();
        org.junit.jupiter.api.Assertions.assertTrue(build2.contains("static char pool[11LL] = { 254, 185, 226, 137, 159, 155, 132, 157, 126, 125, 173 };"));
    }

    @Test
    public void testGet() {
        StringPool stringPool = new StringPool();
        assertEquals("(string_pool::decrypt_string(0LL, 5), (char *)(string_pool + 0LL))", stringPool.get("test"));
        assertEquals("(string_pool::decrypt_string(0LL, 5), (char *)(string_pool + 0LL))", stringPool.get("test"));
        assertEquals("(string_pool::decrypt_string(5LL, 4), (char *)(string_pool + 5LL))", stringPool.get("\u0080\u0050"));
        assertEquals("(string_pool::decrypt_string(9LL, 4), (char *)(string_pool + 9LL))", stringPool.get("\u0800"));
        assertEquals("(string_pool::decrypt_string(13LL, 3), (char *)(string_pool + 13LL))", stringPool.get("\u0080"));
    }
}
