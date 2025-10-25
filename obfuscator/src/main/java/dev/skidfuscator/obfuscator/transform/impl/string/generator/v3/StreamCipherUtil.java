package dev.skidfuscator.obfuscator.transform.impl.string.generator.v3;

final class StreamCipherUtil {
    private StreamCipherUtil() {
    }

    static byte[] scramble(byte[] input, int predicate, byte[] staticKeys, byte[] dynamicKeys, int salt) {
        final byte[] output = new byte[input.length];
        final int staticLen = staticKeys != null ? staticKeys.length : 0;
        final int dynamicLen = dynamicKeys != null ? dynamicKeys.length : 0;

        int state = initializeState(predicate, salt, staticLen, dynamicLen, input.length);
        for (int index = 0; index < input.length; index++) {
            final int mix = computeMix(staticKeys, staticLen, dynamicKeys, dynamicLen, salt, predicate, state, index);
            state = advanceState(state, mix, predicate, salt, index);
            final int keystream = produceKeystream(state);
            output[index] = (byte) (input[index] ^ keystream);
        }
        return output;
    }

    static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    static int readInt(byte[] source, int offset) {
        return ((source[offset] & 0xFF) << 24)
                | ((source[offset + 1] & 0xFF) << 16)
                | ((source[offset + 2] & 0xFF) << 8)
                | (source[offset + 3] & 0xFF);
    }

    private static int initializeState(int predicate, int salt, int staticLen, int dynamicLen, int size) {
        int state = predicate ^ salt ^ (size * 0x9E3779B9);
        state = Integer.rotateLeft(state, 13);
        state += staticLen * 0x85ebca6b + dynamicLen * 0xc2b2ae35 + 0x165667B1;
        state ^= state >>> 15;
        state = Integer.rotateLeft(state, 7);
        state += 0x27d4eb2d;
        state ^= predicate * 0x5bd1e995;
        return state;
    }

    private static int computeMix(byte[] staticKeys,
                                  int staticLen,
                                  byte[] dynamicKeys,
                                  int dynamicLen,
                                  int salt,
                                  int predicate,
                                  int state,
                                  int index) {
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

    private static int advanceState(int state, int mix, int predicate, int salt, int index) {
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

    private static int produceKeystream(int state) {
        int keystream = state ^ Integer.rotateLeft(state, 13);
        keystream += 0x7ed55d16;
        keystream ^= keystream >>> 15;
        keystream += 0xcebd5d7a;
        keystream ^= keystream >>> 12;
        return keystream & 0xFF;
    }
}
