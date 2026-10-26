package com.leadsystems.minecraft.ascension.domain.title;

import java.util.List;

/**
 * One title requirement. Immutable; parsed from titles.yml.
 */
public sealed interface Requirement {

    /** Player-facing text, e.g. "Mine 500 stone or deepslate". */
    String label();

    /** Sum of lifetime statistics {@code keys} is at least {@code amount}. */
    record StatAtLeast(String label, List<String> keys, long amount) implements Requirement {
        public StatAtLeast {
            keys = List.copyOf(keys);
        }
    }

    /** Rank stage index reached (or mastery, when {@code mastery} is true). */
    record RankAtLeast(String label, int rankIndex, boolean mastery) implements Requirement {}

    /**
     * The player holds an item of {@code material}, optionally with {@code enchantment}
     * (lower-case key, or null). Checked against the live inventory at claim time only.
     */
    record Possess(String label, String material, String enchantment) implements Requirement {}

    /** Best deathless streak is at least {@code days}. */
    record StreakAtLeast(String label, int days) implements Requirement {}
}
