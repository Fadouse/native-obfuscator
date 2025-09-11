package by.radioegor146.source;

import by.radioegor146.Util;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class StringPool {

    private long length;
    private final Map<String, Long> pool;

    private static final byte[] KEY = new byte[]{
            0, 1, 2, 3, 4, 5, 6, 7,
            8, 9, 10, 11, 12, 13, 14, 15,
            16, 17, 18, 19, 20, 21, 22, 23,
            24, 25, 26, 27, 28, 29, 30, 31
    };
    private static final byte[] NONCE = new byte[]{
            0, 0, 0, 9,
            0, 0, 0, 74,
            0, 0, 0, 0
    };

    public StringPool() {
        this.length = 0;
        this.pool = new HashMap<>();
    }

    public String get(String value) {
        if (!pool.containsKey(value)) {
            pool.put(value, length);
            length += getModifiedUtf8Bytes(value).length + 1;
        }
        return String.format("((char *)(string_pool + %dLL))", pool.get(value));
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
        List<Byte> plainBytes = new ArrayList<>();
        pool.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .forEach(string -> {
                    for (byte b : getModifiedUtf8Bytes(string)) {
                        plainBytes.add(b);
                    }
                    plainBytes.add((byte) 0);
                });

        byte[] plain = new byte[plainBytes.size()];
        for (int i = 0; i < plainBytes.size(); i++) {
            plain[i] = plainBytes.get(i);
        }
        byte[] encrypted = ChaCha20.crypt(KEY, NONCE, 0, plain);

        String result = String.format("{ %s }", IntStream.range(0, encrypted.length)
                .map(i -> encrypted[i] & 0xFF)
                .mapToObj(String::valueOf)
                .collect(Collectors.joining(", ")));

        String template = Util.readResource("sources/string_pool.cpp");
        return Util.dynamicFormat(template, Util.createMap(
                "size", Math.max(1, encrypted.length) + "LL",
                "value", result,
                "key", formatArray(KEY),
                "nonce", formatArray(NONCE)
        ));
    }

    private static String formatArray(byte[] arr) {
        return String.format("{ %s }", IntStream.range(0, arr.length)
                .map(i -> arr[i] & 0xFF)
                .mapToObj(String::valueOf)
                .collect(Collectors.joining(", ")));
    }
}
