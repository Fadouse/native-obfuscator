package by.radioegor146;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight non-cryptographic random generator. Provides inexpensive, thread-safe
 * pseudo-random numbers so we can avoid the overhead of ThreadLocalRandom in hot paths.
 */
public final class FastRandom {

    private static final long GAMMA = 0x9E3779B97F4A7C15L;
    private static final AtomicLong STATE = new AtomicLong(System.nanoTime() ^ GAMMA);

    private FastRandom() {
    }

    private static long nextRaw() {
        long x = STATE.getAndAdd(GAMMA);
        x ^= x >>> 30;
        x *= 0xBF58476D1CE4E5B9L;
        x ^= x >>> 27;
        x *= 0x94D049BB133111EBL;
        x ^= x >>> 31;
        return x;
    }

    public static long nextLong() {
        return nextRaw();
    }

    public static int nextInt() {
        return (int) nextRaw();
    }

    public static int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        long r = Long.remainderUnsigned(nextRaw(), bound);
        return (int) r;
    }

    public static boolean nextBoolean() {
        return (nextRaw() & 1L) != 0;
    }
}
