package com.leadsystems.minecraft.ascension.session;

import com.leadsystems.minecraft.ascension.domain.quest.QuestDelta;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One player's unsaved changes, drained from the session on the main thread and written in one
 * storage transaction.
 *
 * @param uuid       player
 * @param rankIndex  stage the quest delta belongs to (-1 if none)
 * @param quest      quest progress delta, or null
 * @param cooldowns  changed cooldowns
 * @param statDeltas statistic increments
 */
public record FlushBatch(UUID uuid, int rankIndex, QuestDelta quest, Map<String, Long> cooldowns,
                         Map<String, Long> statDeltas) {

    public FlushBatch {
        cooldowns = Map.copyOf(cooldowns);
        statDeltas = Map.copyOf(statDeltas);
    }

    public boolean isEmpty() {
        return (quest == null || quest.isEmpty()) && cooldowns.isEmpty() && statDeltas.isEmpty();
    }

    /**
     * Combines two unsaved batches for the same player (older first): quest amounts take the max,
     * marks are unioned, the newer cooldown wins, statistic increments are summed.
     */
    public static FlushBatch merge(FlushBatch older, FlushBatch newer) {
        QuestDelta quest = newer.quest();
        if (older.quest() != null && older.rankIndex() == newer.rankIndex()) {
            Map<String, Long> amounts = new HashMap<>(older.quest().amounts());
            if (newer.quest() != null) {
                newer.quest().amounts().forEach((k, v) -> amounts.merge(k, v, Math::max));
            }
            Map<String, Set<String>> marks = new HashMap<>();
            older.quest().newMarks().forEach((k, v) -> marks.computeIfAbsent(k, x -> new HashSet<>()).addAll(v));
            if (newer.quest() != null) {
                newer.quest().newMarks().forEach((k, v) -> marks.computeIfAbsent(k, x -> new HashSet<>()).addAll(v));
            }
            quest = new QuestDelta(amounts, marks);
        }
        Map<String, Long> cooldowns = new HashMap<>(older.cooldowns());
        cooldowns.putAll(newer.cooldowns());
        Map<String, Long> stats = new HashMap<>(older.statDeltas());
        newer.statDeltas().forEach((k, v) -> stats.merge(k, v, Long::sum));
        return new FlushBatch(newer.uuid(), newer.rankIndex(), quest, cooldowns, stats);
    }
}
