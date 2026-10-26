package com.leadsystems.minecraft.ascension.domain.quest;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Progress changed since the last flush: absolute amounts per objective and marks added.
 */
public record QuestDelta(Map<String, Long> amounts, Map<String, Set<String>> newMarks) {
    public QuestDelta {
        amounts = Map.copyOf(amounts);
        Map<String, Set<String>> copy = new HashMap<>();
        newMarks.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
        newMarks = Map.copyOf(copy);
    }

    public boolean isEmpty() {
        return amounts.isEmpty() && newMarks.isEmpty();
    }
}
