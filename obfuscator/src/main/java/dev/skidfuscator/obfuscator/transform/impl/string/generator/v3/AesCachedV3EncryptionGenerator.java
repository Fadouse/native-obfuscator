package dev.skidfuscator.obfuscator.transform.impl.string.generator.v3;

import dev.skidfuscator.obfuscator.skidasm.SkidClassNode;
import dev.skidfuscator.obfuscator.skidasm.SkidMethodNode;
import dev.skidfuscator.obfuscator.skidasm.cfg.SkidBlock;
import org.mapleir.ir.code.Expr;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AES/GCM-based string encryption with runtime caching to minimise repeated decryptions.
 */
public class AesCachedV3EncryptionGenerator extends AbstractEncryptionGeneratorV3 {
    private static final int KEY_SIZE = 16;
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private final byte[] rawKey = new byte[KEY_SIZE];
    private final byte[] keyMask = new byte[KEY_SIZE];
    private final Map<String, Payload> payloadCache = new HashMap<>();
    private int nextSlot = 1;

    public AesCachedV3EncryptionGenerator() {
        super("AES Cached Generator");
        secureRandom.nextBytes(rawKey);
        secureRandom.nextBytes(keyMask);
    }

    @Override
    public void visitPre(SkidClassNode node) {
        super.visitPre(node);

        node.getClassInit().getEntryBlock().add(0, storeInjectField(
                node,
                "cache",
                "Ljava/util/concurrent/ConcurrentHashMap;",
                callInjectMethod(
                        node,
                        "createCache",
                        "()Ljava/util/concurrent/ConcurrentHashMap;"
                )
        ));

        node.getClassInit().getEntryBlock().add(0, storeInjectField(
                node,
                "aesKey",
                "[B",
                generateByteArrayGenerator(node, maskKeyMaterial())
        ));

        node.getClassInit().getEntryBlock().add(0, storeInjectField(
                node,
                "aesMask",
                "[B",
                generateByteArrayGenerator(node, keyMask.clone())
        ));
    }

    @Override
    public Expr encrypt(String input, SkidMethodNode node, SkidBlock block) {
        final int predicate = node.getBlockPredicate(block);
        final String dedupKey = predicate + ":" + input;

        Payload payload = payloadCache.get(dedupKey);
        if (payload == null) {
            payload = createPayload(input, predicate);
            payloadCache.put(dedupKey, payload);
        }

        return callInjectMethod(
                node.getParent(),
                "decryptor",
                "([BI)Ljava/lang/String;",
                generateByteArrayGenerator(node.getParent(), payload.data),
                getPredicateExpr(node, block)
        );
    }

    @Override
    public String decrypt(DecryptorDictionary dictionary, int key) {
        throw new UnsupportedOperationException("Runtime-only decryptor");
    }

    private Payload createPayload(String input, int predicate) {
        try {
            final byte[] plain = input.getBytes(StandardCharsets.UTF_16BE);
            final byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            final SecretKeySpec keySpec = new SecretKeySpec(deriveSessionKey(rawKey, predicate), "AES");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH, iv));
            final byte[] ciphertext = cipher.doFinal(plain);

            final int slot = nextSlot++;
            final byte[] payload = new byte[4 + IV_LENGTH + ciphertext.length];
            StreamCipherUtil.writeInt(payload, 0, slot);
            System.arraycopy(iv, 0, payload, 4, IV_LENGTH);
            System.arraycopy(ciphertext, 0, payload, 4 + IV_LENGTH, ciphertext.length);
            return new Payload(slot, payload);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to encrypt string literal", ex);
        }
    }

    private byte[] maskKeyMaterial() {
        final byte[] masked = new byte[KEY_SIZE];
        for (int i = 0; i < KEY_SIZE; i++) {
            masked[i] = (byte) (rawKey[i] ^ keyMask[i]);
        }
        return masked;
    }

    private static byte[] deriveSessionKey(byte[] baseKey, int predicate) {
        final byte[] derived = new byte[baseKey.length];
        int state = Integer.rotateLeft(predicate ^ 0x9E3779B9, 5) + 0x7f4a7c15;
        for (int i = 0; i < baseKey.length; i++) {
            final int dynamic = (predicate >>> ((i & 3) * 8)) & 0xFF;
            state = Integer.rotateLeft(state + dynamic + (i * 0x45d9f3b), 3);
            derived[i] = (byte) ((baseKey[i] & 0xFF) ^ dynamic ^ (state & 0xFF));
        }
        return derived;
    }

    private static final class Payload {
        private final int slot;
        private final byte[] data;

        private Payload(int slot, byte[] data) {
            this.slot = slot;
            this.data = data;
        }
    }

    @InjectField(value = "aesKey", tags = {InjectFieldTag.RANDOM_NAME, InjectFieldTag.FINAL})
    private static byte[] RUNTIME_KEY;

    @InjectField(value = "aesMask", tags = {InjectFieldTag.RANDOM_NAME, InjectFieldTag.FINAL})
    private static byte[] RUNTIME_MASK;

    @InjectField(value = "cache", tags = {InjectFieldTag.RANDOM_NAME})
    private static ConcurrentHashMap<Integer, String> CACHE;

    @InjectMethod(value = "createCache")
    private static ConcurrentHashMap<Integer, String> createCache() {
        return new ConcurrentHashMap<>();
    }

    @InjectMethod(value = "decryptor", tags = InjectMethodTag.RANDOM_NAME)
    private static String decryptRuntime(final byte[] payload, final int predicate) {
        final int slot = readIntRuntime(payload, 0);

        final String memoized = CACHE.get(slot);
        if (memoized != null) {
            return memoized;
        }

        final byte[] iv = Arrays.copyOfRange(payload, 4, 4 + IV_LENGTH);
        final byte[] ciphertext = Arrays.copyOfRange(payload, 4 + IV_LENGTH, payload.length);

        try {
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            final SecretKeySpec keySpec = new SecretKeySpec(
                    mixKeyRuntime(unmaskKeyRuntime(RUNTIME_KEY, RUNTIME_MASK), predicate),
                    "AES"
            );
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH, iv));
            final byte[] plain = cipher.doFinal(ciphertext);
            final String value = new String(plain, StandardCharsets.UTF_16BE);
            CACHE.put(slot, value);
            return value;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to decrypt string literal", ex);
        }
    }

    @InjectMethod(value = "readIntRuntime")
    private static int readIntRuntime(final byte[] input, final int offset) {
        return ((input[offset] & 0xFF) << 24)
                | ((input[offset + 1] & 0xFF) << 16)
                | ((input[offset + 2] & 0xFF) << 8)
                | (input[offset + 3] & 0xFF);
    }

    @InjectMethod(value = "unmaskKeyRuntime")
    private static byte[] unmaskKeyRuntime(final byte[] maskedKey, final byte[] mask) {
        final byte[] key = new byte[maskedKey.length];
        for (int i = 0; i < maskedKey.length; i++) {
            key[i] = (byte) (maskedKey[i] ^ mask[i % mask.length]);
        }
        return key;
    }

    @InjectMethod(value = "mixKeyRuntime")
    private static byte[] mixKeyRuntime(final byte[] baseKey, final int predicate) {
        final byte[] derived = new byte[baseKey.length];
        int state = Integer.rotateLeft(predicate ^ 0x9E3779B9, 5) + 0x7f4a7c15;
        for (int i = 0; i < baseKey.length; i++) {
            final int dynamic = (predicate >>> ((i & 3) * 8)) & 0xFF;
            state = Integer.rotateLeft(state + dynamic + (i * 0x45d9f3b), 3);
            derived[i] = (byte) ((baseKey[i] & 0xFF) ^ dynamic ^ (state & 0xFF));
        }
        return derived;
    }
}
