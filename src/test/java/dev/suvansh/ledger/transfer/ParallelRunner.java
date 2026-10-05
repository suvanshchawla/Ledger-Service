package dev.suvansh.ledger.transfer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs tasks on many threads released at the same instant, and collects every result or failure. */
final class ParallelRunner {

    private static final int MAX_THREADS = 64;
    private static final long TIMEOUT_SECONDS = 30;

    private ParallelRunner() {}

    record Outcome<T>(T value, Throwable error) {
        boolean failed() {
            return error != null;
        }
    }

    /**
     * Returns outcomes in the same order as {@code tasks}. The first {@code min(tasks, 64)} tasks
     * all start together (a latch holds them until every worker is ready); any beyond that queue
     * behind them. Failures are captured, not thrown, so a test can assert on all of them.
     * A task still running after 30 seconds fails the test: that is how a deadlock shows up.
     */
    static <T> List<Outcome<T>> runTogether(List<Callable<T>> tasks) throws InterruptedException {
        int threads = Math.min(tasks.size(), MAX_THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            if (!ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("Workers did not all start within " + TIMEOUT_SECONDS + "s");
            }
            go.countDown();

            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    outcomes.add(new Outcome<>(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), null));
                } catch (ExecutionException e) {
                    outcomes.add(new Outcome<>(null, e.getCause()));
                } catch (TimeoutException e) {
                    throw new AssertionError("A task was still running after " + TIMEOUT_SECONDS
                            + "s: possible deadlock", e);
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
