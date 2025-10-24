package dev.skidfuscator.obfuscator.transform.impl.string.generator.v3;

import dev.skidfuscator.obfuscator.skidasm.SkidClassNode;
import dev.skidfuscator.obfuscator.skidasm.SkidMethodNode;
import dev.skidfuscator.obfuscator.skidasm.cfg.SkidBlock;
import dev.skidfuscator.obfuscator.util.RandomUtil;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.expr.invoke.InvocationExpr;
import org.mapleir.ir.code.expr.invoke.StaticInvocationExpr;
import org.mapleir.ir.code.expr.invoke.VirtualInvocationExpr;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class ByteBufferClinitV3EncryptionGenerator extends AbstractEncryptionGeneratorV3 {
    public ByteBufferClinitV3EncryptionGenerator() {
        super("Bytes Generator");
    }

    private final StringBuilder buffer = new StringBuilder();
    private int bufferIndex = 0;

    @Override
    public void visitPost(SkidClassNode node) {
        super.visitPost(node);
        node.getClassInit().getEntryBlock().add(0, storeInjectField(
                node,
                "keys",
                "Ljava/lang/String;",
                new VirtualInvocationExpr(
                        InvocationExpr.CallType.VIRTUAL,
                        new Expr[] {
                                new VirtualInvocationExpr(
                                        InvocationExpr.CallType.VIRTUAL,
                                        new Expr[]{
                                                new StaticInvocationExpr(
                                                        new Expr[] {
                                                                this.generateByteArrayGenerator(
                                                                        node,
                                                                        buffer.toString().getBytes(StandardCharsets.UTF_16BE)
                                                                )
                                                        },
                                                        "java/nio/ByteBuffer",
                                                        "wrap",
                                                        "([B)Ljava/nio/ByteBuffer;"
                                                )
                                        },
                                        "java/nio/ByteBuffer",
                                        "asCharBuffer",
                                        "()Ljava/nio/CharBuffer;"
                                )
                        },
                        "java/nio/CharBuffer",
                        "toString",
                        "()Ljava/lang/String;"
                )

        ));

        /*System.out.println(String.format(
                "Generated buffer with %d chars and %d index in %s",
                buffer.length(),
                bufferIndex,
                node.getName()
        ));*/
    }

    @Override
    public Expr encrypt(String input, SkidMethodNode node, SkidBlock block) {
        final byte[] plain = input.getBytes(StandardCharsets.UTF_16BE);
        final int predicate = node.getBlockPredicate(block);
        final byte[] keyBytes = Integer.toString(predicate).getBytes(StandardCharsets.UTF_8);
        final int salt = RandomUtil.nextInt();

        final byte[] cipher = StreamCipherUtil.scramble(plain, predicate, null, keyBytes, salt);
        final byte[] encryptedByteBuffer = new byte[12];

        // Encode location of the buffer
        final int offset = buffer.length();
        encryptedByteBuffer[4] = (byte) (offset >> 24);
        encryptedByteBuffer[5] = (byte) (offset >> 16);
        encryptedByteBuffer[6] = (byte) (offset >> 8);
        encryptedByteBuffer[7] = (byte) offset;

        StreamCipherUtil.writeInt(encryptedByteBuffer, 8, salt);

        final String values = new String(cipher, StandardCharsets.UTF_16BE);

        final int length = values.length();
        // Encode length of the buffer
        encryptedByteBuffer[0] = (byte) (length >> 24);
        encryptedByteBuffer[1] = (byte) (length >> 16);
        encryptedByteBuffer[2] = (byte) (length >> 8);
        encryptedByteBuffer[3] = (byte) length;

        buffer.append(values);
        bufferIndex++;

        // Base64 encode it for testing
        return callInjectMethod(
                node.getParent(),
                "decryptor",
                "([BI)Ljava/lang/String;",
                generateByteArrayGenerator(node.getParent(), encryptedByteBuffer),
                node.getFlowPredicate().getGetter().get(block)
        );
    }

    @Override
    public String decrypt(DecryptorDictionary dictionary, int key) {
        throw new IllegalStateException("Not implemented");
    }

    @InjectField(
            value = "keys",
            tags = {InjectFieldTag.RANDOM_NAME}
    )
    private static String localBuffer;

    @InjectMethod(
            value = "decryptor",
            tags = InjectMethodTag.RANDOM_NAME
    )
    private static String decryptMeBitch(final byte[] index, final int key) {
        final byte[] keyBytes = Integer.toString(key).getBytes(StandardCharsets.UTF_8);

        final int size = ((index[0] & 0xFF) << 24) | ((index[1] & 0xFF) << 16) | ((index[2] & 0xFF) << 8) | (index[3] & 0xFF);
        final int offset = ((index[4] & 0xFF) << 24) | ((index[5] & 0xFF) << 16) | ((index[6] & 0xFF) << 8) | (index[7] & 0xFF);
        final int salt = readIntRuntime(index, 8);

        final byte[] input = localBuffer
                .substring(offset, offset + size)
                .getBytes(StandardCharsets.UTF_16BE);

        final byte[] plain = scrambleRuntime(input, null, keyBytes, key, salt);
        return new String(plain, StandardCharsets.UTF_16BE);
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
