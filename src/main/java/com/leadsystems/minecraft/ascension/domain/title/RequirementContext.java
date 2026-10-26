package com.leadsystems.minecraft.ascension.domain.title;

/**
 * What requirement evaluation may read about a player. Implemented over a live session.
 */
public interface RequirementContext {

    long stat(String key);

    int rankIndex();

    boolean mastery();

    int bestStreakDays();

    /** Live inventory check, or null when not checked (e.g. background eligibility evaluation). */
    Boolean possesses(String material, String enchantment);
}
