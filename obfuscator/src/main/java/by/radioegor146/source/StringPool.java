package by.radioegor146.source;

import by.radioegor146.Util;
import by.radioegor146.instructions.LdcHandler;

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
        String keyExpr;
        String nonceExpr;

        Entry(long offset, int length, byte[] key, byte[] nonce, String keyExpr, String nonceExpr) {
            this.offset = offset;
            this.length = length;
            this.key = key;
            this.nonce = nonce;
            this.keyExpr = keyExpr;
            this.nonceExpr = nonceExpr;
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
            String keyExpr = encodeArray(key);
            String nonceExpr = encodeArray(nonce);
            entry = new Entry(length, bytes.length + 1, key, nonce, keyExpr, nonceExpr);
            pool.put(value, entry);
            length += entry.length;
        }
        return String.format("([](){ static unsigned char k[32] = %s; static unsigned char n[12] = %s; " +
                        "string_pool::decrypt_string(k, n, %dLL, %d); return (char *)(string_pool + %dLL); }())",
                entry.keyExpr, entry.nonceExpr, entry.offset, entry.length, entry.offset);
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
                });

        byte[] encrypted = new byte[encryptedBytes.size()];
        for (int i = 0; i < encryptedBytes.size(); i++) {
            encrypted[i] = encryptedBytes.get(i);
        }

        String poolArray = String.format("{ %s }", IntStream.range(0, encrypted.length)
                .map(i -> encrypted[i] & 0xFF)
                .mapToObj(String::valueOf)
                .collect(Collectors.joining(", ")));

        String template = Util.readResource("sources/string_pool.cpp");
        return Util.dynamicFormat(template, Util.createMap(
                "size", Math.max(1, encrypted.length) + "LL",
                "value", poolArray
        ));
    }

    private String encodeArray(byte[] arr) {
        return String.format("{ %s }", IntStream.range(0, arr.length)
                .mapToObj(i -> encodeByte(arr[i]))
                .collect(Collectors.joining(", ")));
    }

    private String encodeByte(byte b) {
        int value = b & 0xFF;
        int key = random.nextInt();
        int seed = random.nextInt();
        int mid = random.nextInt();
        int cid = random.nextInt();
        int mixed = mix32(key, mid, cid, seed);
        int enc = value ^ mixed;
        return String.format("(unsigned char)native_jvm::utils::decode_int(%s, %s, %s, %s, %s)",
                LdcHandler.getIntString(enc), LdcHandler.getIntString(key),
                LdcHandler.getIntString(mid), LdcHandler.getIntString(cid),
                LdcHandler.getIntString(seed));
    }

    private static int chachaRound(int a, int b, int c, int d) {
        a += b; d ^= a; d = Integer.rotateLeft(d, 16);
        c += d; b ^= c; b = Integer.rotateLeft(b, 12);
        a += b; d ^= a; d = Integer.rotateLeft(d, 8);
        c += d; b ^= c; b = Integer.rotateLeft(b, 7);
        return a;
    }

    private static int mix32(int key, int mid, int cid, int seed) {
        return chachaRound(key, mid, cid, seed);
    }
}
