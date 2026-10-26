package com.leadsystems.minecraft.ascension.storage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Tracks whether storage is usable. While unhealthy the plugin runs in degraded mode: no claims,
 * rewards, quest accepts, submissions, equips or nonce rotations - only in-memory progress that is
 * flushed once storage recovers. Thread-safe.
 */
public final class StorageHealth {

    private final Logger log;
    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private final List<Consumer<Boolean>> listeners = new CopyOnWriteArrayList<>();
    private volatile String lastError = "";

    public StorageHealth(Logger log) {
        this.log = log;
    }

    public boolean isHealthy() {
        return healthy.get();
    }

    public String lastError() {
        return lastError;
    }

    /** Registers a callback fired (on the calling thread) whenever health flips. */
    public void onChange(Consumer<Boolean> listener) {
        listeners.add(listener);
    }

    /** Records a successful operation; logs recovery if storage was down. */
    public void markOk() {
        if (healthy.compareAndSet(false, true)) {
            log.info("Storage recovered - leaving degraded mode.");
            listeners.forEach(l -> l.accept(true));
        }
    }

    /** Records a failed operation; the first failure switches to degraded mode. */
    public void markFailed(String operation, Throwable error) {
        lastError = operation + ": " + error;
        if (healthy.compareAndSet(true, false)) {
            log.severe("Storage failure during " + operation + " (" + error
                    + ") - entering degraded mode; rewards, claims and submissions are paused.");
            listeners.forEach(l -> l.accept(false));
        } else {
            log.warning("Storage still failing: " + operation + " (" + error + ")");
        }
    }
}
