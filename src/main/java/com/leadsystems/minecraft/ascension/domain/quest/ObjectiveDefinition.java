package com.leadsystems.minecraft.ascension.domain.quest;

import java.util.Set;

/**
 * One configured objective inside a rank quest. Immutable.
 *
 * @param id              key unique within its quest (progress is stored under this key)
 * @param type            objective kind
 * @param amount          required amount (kills, levels, items, distinct types...)
 * @param unique          minimum distinct marks (e.g. unique victims); 0 = no requirement
 * @param targets         upper-case entity-type / material / boss names; empty = any
 * @param categories      mob categories such as {@code hostile}, {@code nether}, {@code illager}; empty = any
 * @param submission      true if progress is made through the book's Submit button
 * @param description     short player-facing explanation
 * @param requireFinalHit boss objectives: the player must land the killing blow
 * @param requireSummon   boss objectives: the player must have summoned the boss
 */
public record ObjectiveDefinition(
        String id,
        ObjectiveType type,
        long amount,
        int unique,
        Set<String> targets,
        Set<String> categories,
        boolean submission,
        String description,
        boolean requireFinalHit,
        boolean requireSummon
) {
    public ObjectiveDefinition {
        targets = Set.copyOf(targets);
        categories = Set.copyOf(categories);
    }
}
