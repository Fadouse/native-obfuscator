import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

public class MatrixBench {
    private static final AtomicLong BH = new AtomicLong();
    private static final Random RANDOM = new Random(123456789L);

    public static void main(String[] args) throws Exception {
        microbenchMatrixMultiply();
        System.out.println("=== All tests completed ===");
    }

    static void microbenchMatrixMultiply() throws Exception {
        System.out.println("\n--- Microbench: Matrix Multiply (double) ---");
        final int N = 32; // keep modest for quick run
        double[][] A = new double[N][N];
        double[][] B = new double[N][N];
        double[][] C = new double[N][N];
        fillRandom(A);
        fillRandom(B);

        long tSeq = timeMillis(() -> mmulSeq(A, B, C), 1, 1);
        System.out.println("Seq:       " + tSeq + " ms");
    }

    static void mmulSeq(double[][] A, double[][] B, double[][] C) {
        int n = A.length;
        for (int i = 0; i < n; i++) {
            double[] Ci = C[i];
            for (int j = 0; j < n; j++) {
                double sum = 0;
                for (int k = 0; k < n; k++) {
                    sum += A[i][k] * B[k][j];
                }
                Ci[j] = sum;
            }
        }
        BH.addAndGet((long) C[0][0]);
    }

    static void mmulParallelStream(double[][] A, double[][] B, double[][] C) {
        int n = A.length;
        IntStream.range(0, n).parallel().forEach(i -> {
            double[] Ci = C[i];
            for (int j = 0; j < n; j++) {
                double sum = 0;
                for (int k = 0; k < n; k++) {
                    sum += A[i][k] * B[k][j];
                }
                Ci[j] = sum;
            }
        });
        BH.addAndGet((long) C[0][0]);
    }

    static void mmulVirtualThreads(double[][] A, double[][] B, double[][] C) throws Exception {
        int n = A.length;
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
            Future<?>[] futures = new Future<?>[n];
            for (int i = 0; i < n; i++) {
                final int row = i;
                futures[i] = executor.submit(() -> {
                    double[] Ci = C[row];
                    for (int j = 0; j < n; j++) {
                        double sum = 0;
                        for (int k = 0; k < n; k++) {
                            sum += A[row][k] * B[k][j];
                        }
                        Ci[j] = sum;
                    }
                });
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }
        BH.addAndGet((long) C[0][0]);
    }

    static void fillRandom(double[][] matrix) {
        for (double[] row : matrix) {
            for (int j = 0; j < row.length; j++) {
                row[j] = RANDOM.nextDouble();
            }
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    static long timeMillis(ThrowingRunnable runnable, int warmup, int iterations) throws Exception {
        for (int i = 0; i < warmup; i++) {
            runnable.run();
        }
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            runnable.run();
        }
        long duration = System.nanoTime() - start;
        return TimeUnit.NANOSECONDS.toMillis(duration);
    }
}
