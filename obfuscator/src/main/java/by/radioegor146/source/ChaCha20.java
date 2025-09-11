package by.radioegor146.source;

class ChaCha20 {
    private static int rotateLeft(int v, int c) {
        return (v << c) | (v >>> (32 - c));
    }

    private static void quarterRound(int[] x, int a, int b, int c, int d) {
        x[a] += x[b]; x[d] ^= x[a]; x[d] = rotateLeft(x[d], 16);
        x[c] += x[d]; x[b] ^= x[c]; x[b] = rotateLeft(x[b], 12);
        x[a] += x[b]; x[d] ^= x[a]; x[d] = rotateLeft(x[d], 8);
        x[c] += x[d]; x[b] ^= x[c]; x[b] = rotateLeft(x[b], 7);
    }

    public static byte[] crypt(byte[] key, byte[] nonce, int counter, byte[] data) {
        int len = data.length;
        byte[] out = new byte[len];

        int[] keyWords = new int[8];
        for (int i = 0; i < 8; i++) {
            keyWords[i] = littleEndianToInt(key, i * 4);
        }
        int[] nonceWords = new int[3];
        for (int i = 0; i < 3; i++) {
            nonceWords[i] = littleEndianToInt(nonce, i * 4);
        }

        int offset = 0;
        while (offset < len) {
            int[] state = new int[16];
            state[0] = 0x61707865;
            state[1] = 0x3320646e;
            state[2] = 0x79622d32;
            state[3] = 0x6b206574;
            System.arraycopy(keyWords, 0, state, 4, 8);
            state[12] = counter;
            state[13] = nonceWords[0];
            state[14] = nonceWords[1];
            state[15] = nonceWords[2];

            int[] working = state.clone();
            for (int i = 0; i < 10; i++) {
                quarterRound(working, 0, 4, 8, 12);
                quarterRound(working, 1, 5, 9, 13);
                quarterRound(working, 2, 6, 10, 14);
                quarterRound(working, 3, 7, 11, 15);
                quarterRound(working, 0, 5, 10, 15);
                quarterRound(working, 1, 6, 11, 12);
                quarterRound(working, 2, 7, 8, 13);
                quarterRound(working, 3, 4, 9, 14);
            }
            for (int i = 0; i < 16; i++) {
                working[i] += state[i];
            }
            byte[] keystream = new byte[64];
            for (int i = 0; i < 16; i++) {
                intToLittleEndian(working[i], keystream, i * 4);
            }
            int blockSize = Math.min(64, len - offset);
            for (int i = 0; i < blockSize; i++) {
                out[offset + i] = (byte) (data[offset + i] ^ keystream[i]);
            }
            counter++;
            offset += blockSize;
        }
        return out;
    }

    private static int littleEndianToInt(byte[] bs, int off) {
        return (bs[off] & 0xFF) | ((bs[off + 1] & 0xFF) << 8) | ((bs[off + 2] & 0xFF) << 16) | ((bs[off + 3] & 0xFF) << 24);
    }

    private static void intToLittleEndian(int val, byte[] bs, int off) {
        bs[off] = (byte) val;
        bs[off + 1] = (byte) (val >>> 8);
        bs[off + 2] = (byte) (val >>> 16);
        bs[off + 3] = (byte) (val >>> 24);
    }
}
