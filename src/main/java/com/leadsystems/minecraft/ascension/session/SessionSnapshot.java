package com.leadsystems.minecraft.ascension.session;

import com.leadsystems.minecraft.ascension.storage.PlayerRecord;
import com.leadsystems.minecraft.ascension.storage.QuestRecord;
import com.leadsystems.minecraft.ascension.storage.RenewalRecord;

import java.util.Map;
import java.util.Set;

/**
 * Everything loaded from storage for one player, produced on the storage thread and applied to a
 * {@link PlayerSession} on the main thread.
 *
 * @param player    the players row
 * @param quest     the quest for the current stage, or null if the player has mastery
 * @param cooldowns ability cooldowns
 * @param stats     lifetime statistics
 */
public record SessionSnapshot(PlayerRecord player, QuestRecord quest, RenewalRecord renewal, Map<String, Long> cooldowns,
                              Map<String, Long> stats, Set<String> ownedTitles, String equippedTitle) {
    /** Compatibility for callers that do not need a renewal assignment. */
    public SessionSnapshot(PlayerRecord player, QuestRecord quest, Map<String, Long> cooldowns,
                           Map<String, Long> stats, Set<String> ownedTitles, String equippedTitle) {
        this(player, quest, null, cooldowns, stats, ownedTitles, equippedTitle);
    }
    public SessionSnapshot {
        cooldowns = Map.copyOf(cooldowns);
        stats = Map.copyOf(stats);
        ownedTitles = Set.copyOf(ownedTitles);
    }
}
