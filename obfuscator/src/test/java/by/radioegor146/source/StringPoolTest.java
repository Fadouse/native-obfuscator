package by.radioegor146.source;

import by.radioegor146.source.ChaCha20;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class StringPoolTest {

    @Test
    public void testBuild() {
        StringPool stringPool = new StringPool();

        stringPool.get("test");

        String build1 = stringPool.build();
        assertTrue(build1.contains("static char pool[5LL]"));
        assertTrue(build1.contains("static unsigned char decrypted[5LL]"));
        assertFalse(build1.contains("entries"));

        stringPool.get("other");

        String build2 = stringPool.build();
        assertTrue(build2.contains("static char pool[11LL]"));
        assertTrue(build2.contains("static unsigned char decrypted[11LL]"));
    }

    @Test
    public void testGet() {
        StringPool stringPool = new StringPool();
        String res1 = stringPool.get("test");
        assertTrue(res1.startsWith("(string_pool::decrypt_string("));
        assertTrue(res1.endsWith(", 0LL, 5), (char *)(string_pool + 0LL))"));
        assertEquals(res1, stringPool.get("test"));

        String res3 = stringPool.get("\u0080\u0050");
        assertTrue(res3.startsWith("(string_pool::decrypt_string("));
        assertTrue(res3.endsWith(", 5LL, 4), (char *)(string_pool + 5LL))"));

        String res4 = stringPool.get("\u0800");
        assertTrue(res4.startsWith("(string_pool::decrypt_string("));
        assertTrue(res4.endsWith(", 9LL, 4), (char *)(string_pool + 9LL))"));

        String res5 = stringPool.get("\u0080");
        assertTrue(res5.startsWith("(string_pool::decrypt_string("));
        assertTrue(res5.endsWith(", 13LL, 3), (char *)(string_pool + 13LL))"));
    }

    @Test
    public void testRandomEncryptionAndCrypt() throws Exception {
        StringPool pool1 = new StringPool();
        pool1.get("test");
        StringPool pool2 = new StringPool();
        pool2.get("test");

        byte[] key1 = getEntryField(pool1, "test", "key");
        byte[] nonce1 = getEntryField(pool1, "test", "nonce");
        byte[] key2 = getEntryField(pool2, "test", "key");
        byte[] nonce2 = getEntryField(pool2, "test", "nonce");

        byte[] plain = getPlainBytes("test");

        byte[] enc1 = ChaCha20.crypt(key1, nonce1, 0, plain);
        byte[] enc2 = ChaCha20.crypt(key2, nonce2, 0, plain);
        assertFalse(Arrays.equals(enc1, enc2));
        byte[] dec1 = ChaCha20.crypt(key1, nonce1, 0, enc1);
        assertArrayEquals(plain, dec1);
        assertArrayEquals(enc1, ChaCha20.crypt(key1, nonce1, 0, dec1));
    }

    @SuppressWarnings("unchecked")
    private static byte[] getEntryField(StringPool pool, String value, String field) throws Exception {
        Field poolField = StringPool.class.getDeclaredField("pool");
        poolField.setAccessible(true);
        Map<String, Object> map = (Map<String, Object>) poolField.get(pool);
        Object entry = map.get(value);
        Field f = entry.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return (byte[]) f.get(entry);
    }

    private static byte[] getPlainBytes(String value) throws Exception {
        Method m = StringPool.class.getDeclaredMethod("getModifiedUtf8Bytes", String.class);
        m.setAccessible(true);
        byte[] bytes = (byte[]) m.invoke(null, value);
        return Arrays.copyOf(bytes, bytes.length + 1);
    }
}
