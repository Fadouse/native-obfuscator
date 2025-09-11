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
        assertEquals("11377904LL", stringPool.get("test"));
        assertEquals("11377904LL", stringPool.get("test"));
        assertEquals("11377909LL", stringPool.get("\u0080\u0050"));
        assertEquals("11377913LL", stringPool.get("\u0800"));
        assertEquals("11377917LL", stringPool.get("\u0080"));
    }
}
