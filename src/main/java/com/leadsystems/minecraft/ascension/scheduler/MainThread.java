package com.leadsystems.minecraft.ascension.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * Hops work back onto the server thread (adapted from the LeadSystems {@code SyncDispatcher}).
 * Async code (storage results, Bedrock form callbacks) never touches players, inventories or the
 * world directly; it always goes through here first. Work submitted after the plugin is disabled
 * is dropped, because the Bukkit scheduler rejects it.
 */
public final class MainThread {

    private final Plugin plugin;

    public MainThread(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Runs now if already on the main thread, otherwise next tick. */
    public void run(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /** Runs after {@code ticks} ticks (main thread). */
    public void later(long ticks, Runnable task) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(1L, ticks));
        }
    }

    /**
     * Delivers a future's outcome on the main thread. A handler that throws is logged with its
     * stack trace instead of disappearing inside a scheduler.
     */
    public <T> void onComplete(CompletableFuture<T> future, BiConsumer<T, Throwable> handler) {
        future.whenComplete((value, error) -> run(() -> {
            try {
                handler.accept(value, error);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Error while applying a storage result", t);
            }
        }));
    }
}
