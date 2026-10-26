package com.leadsystems.minecraft.ascension.domain.progress;

import java.util.Set;

/**
 * A validated gameplay fact produced by listeners after all anti-exploit checks.
 * Quest objectives and lifetime statistics consume facts; they never see raw Bukkit events.
 */
public sealed interface Fact {

    /** Experience levels gained from an XP orb pickup. */
    record LevelsGained(int levels) implements Fact {}

    /** A mob killed by the player. {@code categories} such as hostile/nether/illager. */
    record MobKilled(String entityType, Set<String> categories) implements Fact {
        public MobKilled {
            categories = Set.copyOf(categories);
        }
    }

    /** A natural block broken by the player. */
    record BlockMined(String material) implements Fact {}

    /** Smelted items taken out of a furnace-type block. */
    record ItemSmelted(String material, int amount) implements Fact {}

    /** Items obtained from a natural source (block drops, own kill drops, loot, fishing, smelting). */
    record ItemObtained(String material, int amount) implements Fact {}

    /** Items handed in through the book's Submit button (already removed from the inventory). */
    record ItemSubmitted(String material, int amount) implements Fact {}

    /** A valid player kill. {@code victimId} is the victim's UUID string. */
    record PlayerKilled(String victimId) implements Fact {}

    /** An assigned hunt completed. */
    record HuntCompleted() implements Fact {}

    /** A raid won. */
    record RaidCompleted() implements Fact {}

    /** A boss defeat credited to the player. */
    record BossDefeated(String boss, boolean finalHit, boolean summonedByPlayer) implements Fact {}

    /** A validated lifetime statistic that does not contribute directly to rank quests. */
    record Statistic(String key, long amount) implements Fact {
        public Statistic {
            if (key == null || key.isBlank() || amount <= 0) {
                throw new IllegalArgumentException("Statistic needs a key and positive amount");
            }
        }
    }
}
