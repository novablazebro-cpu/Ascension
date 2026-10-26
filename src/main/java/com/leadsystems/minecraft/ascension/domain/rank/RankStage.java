package com.leadsystems.minecraft.ascension.domain.rank;

/**
 * One stage on the rank ladder, e.g. Gold II.
 *
 * @param index     position on the ladder (0 = starting stage)
 * @param id        config key, e.g. {@code gold_2}
 * @param name      display name, e.g. {@code Gold II}
 * @param tier      tier key, e.g. {@code gold} (used by {@code /rank set <player> <rank> <stage>})
 * @param stage     stage number within the tier (1-based)
 * @param color     hex color for display
 * @param abilityId key of this stage's ability package in abilities.yml
 */
public record RankStage(int index, String id, String name, String tier, int stage, String color, String abilityId) {
}
