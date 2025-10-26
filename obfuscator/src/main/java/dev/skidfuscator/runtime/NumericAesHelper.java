package dev.skidfuscator.runtime;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

public final class NumericAesHelper {
    private static final int TAG_LENGTH = 128;
    private static final Cipher CIPHER;
    private static final ConcurrentHashMap<Integer, Integer> CACHE = new ConcurrentHashMap<>();

    static {
        try {
            CIPHER = Cipher.getInstance("AES/GCM/NoPadding");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to initialize AES runtime", e);
        }
    }

    private NumericAesHelper() {
    }

    public static synchronized int decrypt(final byte[] payload,
                                           final byte[] maskedKey,
                                           final byte[] mask,
                                           final int predicate) {
        try {
            final int slot = bytesToInt(payload, 0);
            final Integer cached = CACHE.get(slot);
            if (cached != null) {
                return cached;
            }

            final byte[] baseKey = unmask(maskedKey, mask);
            final byte[] derivedKey = derive(baseKey, predicate);
            final byte[] iv = Arrays.copyOfRange(payload, 4, 16);
            final byte[] cipher = Arrays.copyOfRange(payload, 16, payload.length);

            CIPHER.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(derivedKey, "AES"),
                    new GCMParameterSpec(TAG_LENGTH, iv)
            );

            final byte[] plain = CIPHER.doFinal(cipher);
            final int value = bytesToInt(plain, 0);
            CACHE.put(slot, value);
            return value;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to decode numeric constant", ex);
        }
    }

    private static byte[] unmask(final byte[] maskedKey, final byte[] mask) {
        final byte[] key = new byte[maskedKey.length];
        for (int i = 0; i < maskedKey.length; i++) {
            key[i] = (byte) (maskedKey[i] ^ mask[i % mask.length]);
        }
        return key;
    }

    private static byte[] derive(final byte[] baseKey, final int predicate) {
        final byte[] derived = new byte[baseKey.length];
        int state = Integer.rotateLeft(predicate ^ 0x9E3779B9, 5) + 0x7f4a7c15;
        for (int i = 0; i < baseKey.length; i++) {
            final int dynamic = (predicate >>> ((i & 3) * 8)) & 0xFF;
            state = Integer.rotateLeft(state + dynamic + (i * 0x45d9f3b), 3);
            derived[i] = (byte) ((baseKey[i] & 0xFF) ^ dynamic ^ (state & 0xFF));
        }
        return derived;
    }

    private static int bytesToInt(final byte[] data, final int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }
}
