package pack.tests.basics.runable;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Task {
    public void run() throws Exception {
        if (!(Pool.tpe instanceof ThreadPoolExecutor)) {
            throw new IllegalStateException("Pool.tpe must be a ThreadPoolExecutor.");
        }
        ThreadPoolExecutor tpe = (ThreadPoolExecutor) Pool.tpe;
        Exec.i = 1;

        Exec e1 = new Exec(2);
        Exec e2 = new Exec(3);
        Exec e3 = new Exec(100);

        // 控制时序用
        CountDownLatch blockAfterE2 = new CountDownLatch(1);  // e2 加完3后卡住线程
        CountDownLatch releaseLambda = new CountDownLatch(1); // 在 catch 后才允许 lambda 读取 Exec.i

        try {
            tpe.submit(() -> {
                e2.doAdd(); // +3：1 -> 4
                try {
                    blockAfterE2.await(); // 占住唯一工作线程
                } catch (InterruptedException ignored) {}
            });

            // 将 lambda 放到队列里（队列容量=1，刚好占满）
            tpe.submit(() -> {
                try {
                    // 等待 catch 释放，使得此处读取到的 ix 包含了 +10
                    releaseLambda.await();
                } catch (InterruptedException ignored) {}
                int ix = Exec.i; // 期望此时为 14（见下“序列说明”）
                e1.doAdd();      // +2：14 -> 16
                Exec.i += ix;    // +14：16 -> 30
            });

            // 第三次提交：此时 1 个工作线程被 e2 占住，队列里已有 1 个任务（lambda）
            // 故必然拒绝，触发 catch 分支
            try {
                tpe.submit(e3::doAdd); // 触发 RejectedExecutionException
            } catch (RejectedExecutionException ex) {
                Exec.i += 10;          // +10：4 -> 14
                releaseLambda.countDown(); // 放行 lambda，让它读取到 ix=14
            }

        } finally {
            // 释放 e2 的阻塞，让线程继续把队列中的 lambda 执行掉
            blockAfterE2.countDown();
        }

        // 给线程池一点时间把 lambda 跑完（通常不需要很久）
        Thread.sleep(500L);

        if (Exec.i == 30) {
            // 1 -> 4(+3,e2开始立即加) -> 14(+10,catch) -> 16(+2,e1 in lambda) -> 30(+14,ix)
            System.out.println("PASS");
        } else {
            System.out.println("FAIL: " + Exec.i);
        }
    }
}
