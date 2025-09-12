package by.radioegor146.source;

import by.radioegor146.Util;

import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class StringPool {

    private static class Entry {
        long offset;
        int length;
        byte[] key;
        byte[] nonce;

        Entry(long offset, int length, byte[] key, byte[] nonce) {
            this.offset = offset;
            this.length = length;
            this.key = key;
            this.nonce = nonce;
        }
    }

    private long length;
    private final Map<String, Entry> pool;

    private final SecureRandom random;

    public StringPool() {
        this.length = 0;
        this.pool = new HashMap<>();
        this.random = new SecureRandom();
    }

    public String get(String value) {
        Entry entry = pool.get(value);
        if (entry == null) {
            byte[] bytes = getModifiedUtf8Bytes(value);
            byte[] key = new byte[32];
            byte[] nonce = new byte[12];
            random.nextBytes(key);
            random.nextBytes(nonce);
            entry = new Entry(length, bytes.length + 1, key, nonce);
            pool.put(value, entry);
            length += entry.length;
        }
        return String.format("(string_pool::decrypt_string(%dLL, %d), (char *)(string_pool + %dLL))",
                entry.offset, entry.length, entry.offset);
    }

    public long getOffset(String value) {
        return pool.get(value).offset;
    }

    public int getLength(String value) {
        return pool.get(value).length;
    }

    private static byte[] getModifiedUtf8Bytes(String str) {
        int strlen = str.length();
        int utflen = 0;
        int c, count = 0;

        for (int i = 0; i < strlen; i++) {
            c = str.charAt(i);
            if ((c >= 0x0001) && (c <= 0x007F)) {
                utflen++;
            } else if (c > 0x07FF) {
                utflen += 3;
            } else {
                utflen += 2;
            }
        }

        if (utflen > 65535)
            throw new RuntimeException("encoded string too long: " + utflen + " bytes");

        byte[] bytearr = new byte[utflen];

        int i;
        for (i = 0; i < strlen; i++) {
           c = str.charAt(i);
           if (!((c >= 0x0001) && (c <= 0x007F))) 
               break;
           bytearr[count++] = (byte) c;
        }

        for (; i < strlen; i++){
            c = str.charAt(i);
            if ((c >= 0x0001) && (c <= 0x007F)) {
                bytearr[count++] = (byte) c;

            } else if (c > 0x07FF) {
                bytearr[count++] = (byte) (0xE0 | ((c >> 12) & 0x0F));
                bytearr[count++] = (byte) (0x80 | ((c >>  6) & 0x3F));
                bytearr[count++] = (byte) (0x80 | ((c) & 0x3F));
            } else {
                bytearr[count++] = (byte) (0xC0 | ((c >>  6) & 0x1F));
                bytearr[count++] = (byte) (0x80 | ((c) & 0x3F));
            }
        }
        
        return bytearr;
    }

    public String build() {
        List<Byte> encryptedBytes = new ArrayList<>();
        List<String> entries = new ArrayList<>();
        pool.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().offset))
                .forEach(e -> {
                    String string = e.getKey();
                    Entry entry = e.getValue();
                    byte[] bytes = getModifiedUtf8Bytes(string);
                    byte[] plain = Arrays.copyOf(bytes, bytes.length + 1);
                    byte[] encrypted = ChaCha20.crypt(entry.key, entry.nonce, 0, plain);
                    for (byte b : encrypted) {
                        encryptedBytes.add(b);
                    }
                    entries.add(String.format("{ %dLL, %s, %s }", entry.offset,
                            formatArray(entry.key), formatArray(entry.nonce)));
                });

        byte[] encrypted = new byte[encryptedBytes.size()];
        for (int i = 0; i < encryptedBytes.size(); i++) {
            encrypted[i] = encryptedBytes.get(i);
        }

        String poolArray = String.format("{ %s }", IntStream.range(0, encrypted.length)
                .map(i -> encrypted[i] & 0xFF)
                .mapToObj(String::valueOf)
                .collect(Collectors.joining(", ")));

        String entriesArray = entries.stream().collect(Collectors.joining(", "));

        String template = Util.readResource("sources/string_pool.cpp");
        return Util.dynamicFormat(template, Util.createMap(
                "size", Math.max(1, encrypted.length) + "LL",
                "value", poolArray,
                "entries", entriesArray
        ));
    }

    private static String formatArray(byte[] arr) {
        return String.format("{ %s }", IntStream.range(0, arr.length)
                .map(i -> arr[i] & 0xFF)
                .mapToObj(String::valueOf)
                .collect(Collectors.joining(", ")));
    }
}
