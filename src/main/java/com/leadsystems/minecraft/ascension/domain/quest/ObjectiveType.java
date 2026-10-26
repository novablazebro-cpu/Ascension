package com.leadsystems.minecraft.ascension.domain.quest;

import java.util.Locale;

/**
 * Every kind of quest objective. Config files reference these by name (case-insensitive, '-' or '_').
 */
public enum ObjectiveType {
    /** Experience levels gained from XP orbs after accepting (every level-up counts). */
    GAIN_LEVELS,
    /** Kill mobs matching {@code targets} (entity types) and/or {@code categories}. */
    KILL_MOBS,
    /** Kill N different mob types from the given categories/targets. */
    KILL_DISTINCT_MOBS,
    /** Mine natural (not player-placed, not fluid-formed) blocks of the given materials. */
    MINE_BLOCKS,
    /** Take smelted items of the given materials out of a furnace, smoker, or blast furnace. */
    SMELT_ITEMS,
    /** Obtain items from natural sources: block drops, own mob kills, loot, fishing, smelting. */
    OBTAIN_ITEMS,
    /** Hand items in through the book's Submit button (server verifies and removes them). */
    SUBMIT_ITEMS,
    /** Valid player kills, optionally with a minimum number of unique victims. */
    PLAYER_KILLS,
    /** Assigned-player hunts completed. */
    HUNTS,
    /** Raids won. */
    RAIDS,
    /** Boss defeats (participation share), optionally requiring the final hit and/or own summon. */
    DEFEAT_BOSS;

    /** Parses a config name such as {@code kill-mobs} or {@code KILL_MOBS}; returns null if unknown. */
    public static ObjectiveType parse(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (ObjectiveType t : values()) {
            if (t.name().equals(key)) {
                return t;
            }
        }
        return null;
    }
}
