package com.leadsystems.minecraft.ascension.storage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * The single storage thread. Every database operation runs here, in submission order, so the main
 * server thread never blocks on I/O and one player's writes can never be reordered.
 * <p>
 * Results come back as {@link CompletableFuture}s; callers must hop back to the main thread
 * (see {@code MainThread}) before touching any Bukkit state.
 */
public final class StorageExecutor implements AutoCloseable {

    private final SqlDatabase db;
    private final StorageHealth health;
    private final Logger log;
    private final ExecutorService executor;

    public StorageExecutor(SqlDatabase db, StorageHealth health, Logger log) {
        this.db = db;
        this.health = health;
        this.log = log;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Ascension-Storage");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Queues a task. Failures mark storage unhealthy and complete the future exceptionally;
     * successes mark it healthy again.
     *
     * @param label operation name for logs
     */
    public <T> CompletableFuture<T> submit(String label, SqlTask<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    T result = task.run(db.connection());
                    health.markOk();
                    future.complete(result);
                } catch (Throwable t) {
                    health.markFailed(label, t);
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /** Cheap health probe used while in degraded mode. */
    public CompletableFuture<Boolean> probe() {
        return submit("probe", c -> {
            try (var st = c.createStatement()) {
                st.execute("SELECT 1");
            }
            return true;
        });
    }

    /** True once {@link #close()} has been called. */
    public boolean isShutdown() {
        return executor.isShutdown();
    }

    /** Stops accepting work and waits up to 15 s for queued writes to finish. */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                log.severe("Storage thread did not finish within 15 s; some writes may be lost.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
