package com.leadsystems.minecraft.ascension.domain.quest;

import java.util.List;

/**
 * The combined quest for one rank stage. Completing it advances the player to the next stage
 * (or grants mastery on the last stage).
 *
 * @param stageId    rank stage id, e.g. {@code bronze_1}
 * @param rankIndex  index of that stage on the ladder
 * @param objectives objectives, in display order
 */
public record QuestDefinition(String stageId, int rankIndex, List<ObjectiveDefinition> objectives) {
    public QuestDefinition {
        objectives = List.copyOf(objectives);
    }

    /** Returns the objective with this id, or null. */
    public ObjectiveDefinition objective(String id) {
        for (ObjectiveDefinition o : objectives) {
            if (o.id().equals(id)) {
                return o;
            }
        }
        return null;
    }
}
