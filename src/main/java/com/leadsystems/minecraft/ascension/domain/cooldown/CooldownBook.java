package com.leadsystems.minecraft.ascension.domain.cooldown;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-player ability cooldowns as absolute ready-at timestamps (epoch millis). Main-thread only.
 * <p>
 * Keys are ability keys, never title or equip keys, so swapping titles, relogging, dying or
 * restarting never resets a cooldown. Wall-clock timestamps are used because cooldowns such as
 * Refuse Death's 24 h must survive restarts; a backward clock change lengthens them (safe) and a
 * forward change shortens them (documented limitation).
 */
public final class CooldownBook {

    private final Map<String, Long> readyAt = new HashMap<>();
    private final Set<String> dirty = new HashSet<>();

    /** True if the ability is off cooldown at {@code now}. */
    public boolean isReady(String key, long now) {
        return now >= readyAt.getOrDefault(key, 0L);
    }

    /** Milliseconds until ready (0 if ready). */
    public long remainingMillis(String key, long now) {
        return Math.max(0L, readyAt.getOrDefault(key, 0L) - now);
    }

    /** Starts a cooldown of {@code durationMillis} from {@code now}. */
    public void start(String key, long now, long durationMillis) {
        long duration = Math.max(0L, durationMillis);
        long expiry = duration > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + duration;
        readyAt.put(key, expiry);
        dirty.add(key);
    }

    /** Makes the ability ready immediately (admin reset). Persisted as ready-at 0. */
    public void reset(String key) {
        readyAt.put(key, 0L);
        dirty.add(key);
    }

    /** Keys with a stored cooldown (for admin reset-all). */
    public Set<String> keys() {
        return Set.copyOf(readyAt.keySet());
    }

    /** Loads persisted cooldowns without marking them dirty. */
    public void load(Map<String, Long> stored) {
        readyAt.putAll(stored);
    }

    /** Replaces state after an ordered save and authoritative database reload, including resets. */
    public void replace(Map<String, Long> stored) {
        readyAt.clear();
        readyAt.putAll(stored);
        dirty.clear();
    }

    /** Returns changed cooldowns since the last drain and clears the dirty set. */
    public Map<String, Long> drainDirty() {
        if (dirty.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> out = new HashMap<>();
        for (String k : dirty) {
            out.put(k, readyAt.getOrDefault(k, 0L));
        }
        dirty.clear();
        return out;
    }

    /** Re-queues cooldowns whose flush failed. */
    public void restoreDirty(Map<String, Long> failed) {
        dirty.addAll(failed.keySet());
    }
}
