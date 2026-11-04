import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controlled model of Db.kt:75-82,92-93,132-135. This is not an Android test.
 * The barriers force one valid interleaving of the unchanged queue operations.
 */
public class DbQueueRaceModel {
    static final int MAX_QUEUED = 20_000;
    static final ConcurrentLinkedQueue<Integer> queue = new ConcurrentLinkedQueue<>();
    static final AtomicInteger queued = new AtomicInteger();

    static void log(int value) {
        if (queued.incrementAndGet() > MAX_QUEUED) {
            queued.decrementAndGet();
            return;
        }
        queue.add(value);
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        Thread producer = new Thread(() -> {
            if (queued.incrementAndGet() > MAX_QUEUED) {
                queued.decrementAndGet();
                return;
            }
            reserved.countDown();
            try { resume.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            queue.add(42);
        });
        producer.start();
        reserved.await();
        // Db.clear(), concurrently with the producer paused after reserving a slot.
        queue.clear();
        queued.set(0);
        resume.countDown();
        producer.join();
        System.out.printf("After clear+pending log: actual=%d counter=%d%n", queue.size(), queued.get());
        if (queue.poll() != null) queued.decrementAndGet();
        System.out.printf("After flush: actual=%d counter=%d%n", queue.size(), queued.get());
        for (int i = 0; i < MAX_QUEUED + 1; i++) log(i);
        System.out.printf("At claimed cap: actual=%d counter=%d configured=%d%n", queue.size(), queued.get(), MAX_QUEUED);
        if (queue.size() != MAX_QUEUED + 1 || queued.get() != MAX_QUEUED) throw new AssertionError();
        System.out.println("CONFIRMED in a controlled concurrency model; no Android DB or firewall traffic exercised.");
    }
}
