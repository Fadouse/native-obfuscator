package dev.skidfuscator.obfuscator.transform.impl.string.generator.v3;

import dev.skidfuscator.obfuscator.skidasm.SkidClassNode;
import dev.skidfuscator.obfuscator.skidasm.SkidMethodNode;
import dev.skidfuscator.obfuscator.skidasm.cfg.SkidBlock;
import dev.skidfuscator.obfuscator.util.RandomUtil;
import org.mapleir.ir.code.Expr;

import java.nio.charset.StandardCharsets;

public class BytesClinitV3EncryptionGenerator extends AbstractEncryptionGeneratorV3 {
    private final byte[] keys;

    public BytesClinitV3EncryptionGenerator(byte[] keys) {
        super("Bytes Generator");
        this.keys = keys;
    }

    @Override
    public void visitPre(SkidClassNode node) {
        super.visitPre(node);

        node.getClassInit().getEntryBlock().add(0, storeInjectField(
                node,
                "keys",
                "[B",
                generateByteArrayGenerator(node, keys)
        ));
    }

    @Override
    public Expr encrypt(String input, SkidMethodNode node, SkidBlock block) {
        final byte[] plain = input.getBytes(StandardCharsets.UTF_16);
        final int predicate = node.getBlockPredicate(block);
        final byte[] keyBytes = Integer.toString(predicate).getBytes(StandardCharsets.UTF_8);
        final int salt = RandomUtil.nextInt();

        final byte[] cipher = StreamCipherUtil.scramble(plain, predicate, keys, keyBytes, salt);
        final byte[] payload = new byte[4 + cipher.length];
        StreamCipherUtil.writeInt(payload, 0, salt);
        System.arraycopy(cipher, 0, payload, 4, cipher.length);

        return callInjectMethod(
                node.getParent(),
                "decryptor",
                "([BI)Ljava/lang/String;",
                generateByteArrayGenerator(node.getParent(), payload),
                getPredicateExpr(node, block)
        );
    }

    @Override
    public String decrypt(DecryptorDictionary dictionary, int key) {
        final byte[] payload = dictionary.get("encrypted");
        final int salt = StreamCipherUtil.readInt(payload, 0);
        final byte[] cipher = new byte[payload.length - 4];
        System.arraycopy(payload, 4, cipher, 0, cipher.length);

        final byte[] keyBytes = Integer.toString(key).getBytes(StandardCharsets.UTF_8);
        final byte[] plain = StreamCipherUtil.scramble(cipher, key, keys, keyBytes, salt);

        return new String(plain, StandardCharsets.UTF_16);
    }

    @InjectField(
            value = "keys",
            tags = {InjectFieldTag.RANDOM_NAME}
    )
    private static byte[] localKeys;

    @InjectMethod(
            value = "decryptor",
            tags = InjectMethodTag.RANDOM_NAME
    )
    private static String decryptMeBitch(final byte[] input, final int key) {
        final int salt = readIntRuntime(input, 0);
        final byte[] cipher = new byte[input.length - 4];
        System.arraycopy(input, 4, cipher, 0, cipher.length);

        final byte[] keyBytes = Integer.toString(key).getBytes(StandardCharsets.UTF_8);
        final byte[] plain = scrambleRuntime(cipher, localKeys, keyBytes, key, salt);

        return new String(plain, StandardCharsets.UTF_16);
    }

    @InjectMethod(value = "scrambleRuntime")
    private static byte[] scrambleRuntime(final byte[] input,
                                          final byte[] staticKeys,
                                          final byte[] dynamicKeys,
                                          final int predicate,
                                          final int salt) {
        final int staticLen = staticKeys != null ? staticKeys.length : 0;
        final int dynamicLen = dynamicKeys != null ? dynamicKeys.length : 0;
        final byte[] output = new byte[input.length];

        int state = initializeStateRuntime(predicate, salt, staticLen, dynamicLen, input.length);
        for (int index = 0; index < input.length; index++) {
            final int mix = computeMixRuntime(staticKeys, staticLen, dynamicKeys, dynamicLen, salt, predicate, state, index);
            state = advanceStateRuntime(state, mix, predicate, salt, index);
            final int keystream = produceKeystreamRuntime(state);
            output[index] = (byte) (input[index] ^ keystream);
        }
        return output;
    }

    @InjectMethod(value = "initializeStateRuntime")
    private static int initializeStateRuntime(final int predicate,
                                              final int salt,
                                              final int staticLen,
                                              final int dynamicLen,
                                              final int size) {
        int state = predicate ^ salt ^ (size * 0x9E3779B9);
        state = Integer.rotateLeft(state, 13);
        state += staticLen * 0x85ebca6b + dynamicLen * 0xc2b2ae35 + 0x165667B1;
        state ^= state >>> 15;
        state = Integer.rotateLeft(state, 7);
        state += 0x27d4eb2d;
        state ^= predicate * 0x5bd1e995;
        return state;
    }

    @InjectMethod(value = "computeMixRuntime")
    private static int computeMixRuntime(final byte[] staticKeys,
                                         final int staticLen,
                                         final byte[] dynamicKeys,
                                         final int dynamicLen,
                                         final int salt,
                                         final int predicate,
                                         final int state,
                                         final int index) {
        final int staticComponent;
        if (staticLen > 0) {
            staticComponent = staticKeys[(index + (salt & 0x7fffffff)) % staticLen] & 0xFF;
        } else {
            staticComponent = (Integer.rotateLeft(salt, (index * 3 + 7) & 31) >>> ((index & 3) * 8)) & 0xFF;
        }

        final int dynamicComponent;
        if (dynamicLen > 0) {
            dynamicComponent = dynamicKeys[index % dynamicLen] & 0xFF;
        } else {
            dynamicComponent = (Integer.rotateLeft(predicate, (index * 5 + 11) & 31)
                    ^ (predicate >>> ((index & 3) + 1))) & 0xFF;
        }

        int blended = staticComponent ^ Integer.rotateLeft(dynamicComponent, (index % 5) + 1);
        blended ^= (predicate >>> ((index % 4) * 8));
        blended += (salt >>> ((index & 3) * 8)) & 0xFF;
        blended ^= state >>> 3;
        return blended;
    }

    @InjectMethod(value = "advanceStateRuntime")
    private static int advanceStateRuntime(int state,
                                           final int mix,
                                           final int predicate,
                                           final int salt,
                                           final int index) {
        state ^= Integer.rotateLeft(mix + predicate, (index % 13) + 3);
        state += 0x9E3779B9;
        state ^= state >>> 7;
        state = Integer.rotateLeft(state, 11);
        state ^= mix * 0x7f4a7c15;
        state += Integer.rotateLeft(salt, (index % 17) + 5);
        state ^= state >>> 11;
        state = Integer.rotateLeft(state, 17);
        state += 0x52dce729;
        return state;
    }

    @InjectMethod(value = "produceKeystreamRuntime")
    private static int produceKeystreamRuntime(int state) {
        int keystream = state ^ Integer.rotateLeft(state, 13);
        keystream += 0x7ed55d16;
        keystream ^= keystream >>> 15;
        keystream += 0xcebd5d7a;
        keystream ^= keystream >>> 12;
        return keystream & 0xFF;
    }

    @InjectMethod(value = "readIntRuntime")
    private static int readIntRuntime(final byte[] input, final int offset) {
        return ((input[offset] & 0xFF) << 24)
                | ((input[offset + 1] & 0xFF) << 16)
                | ((input[offset + 2] & 0xFF) << 8)
                | (input[offset + 3] & 0xFF);
    }
}
