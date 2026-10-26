package com.leadsystems.minecraft.ascension.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The plugin's single repeating main-thread task (once per second). Every periodic job - pulse
 * abilities, the inventory safety sweep, batched saves, health probes - is registered here instead
 * of scheduling its own task, so there is never a per-player repeating task and a reload can never
 * leave duplicate tasks behind: {@link #start()} is idempotent and {@link #stop()} cancels
 * everything.
 */
public final class TickScheduler {

    private record Job(String name, int periodSeconds, Runnable action) {}

    private final Plugin plugin;
    private final Logger log;
    private final List<Job> jobs = new ArrayList<>();
    private BukkitTask task;
    private long seconds;

    public TickScheduler(Plugin plugin, Logger log) {
        this.plugin = plugin;
        this.log = log;
    }

    /** Registers a job run every {@code periodSeconds} seconds. Register before {@link #start()}. */
    public void every(String name, int periodSeconds, Runnable action) {
        jobs.add(new Job(name, Math.max(1, periodSeconds), action));
    }

    public synchronized void start() {
        if (task != null) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        seconds++;
        for (Job job : jobs) {
            if (seconds % job.periodSeconds() != 0) {
                continue;
            }
            try {
                job.action().run();
            } catch (Throwable t) {
                // One failing job must never stop the others.
                log.log(Level.SEVERE, "Scheduled job '" + job.name() + "' failed", t);
            }
        }
    }
}
