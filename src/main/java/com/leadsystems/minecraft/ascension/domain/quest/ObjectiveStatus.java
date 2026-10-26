package com.leadsystems.minecraft.ascension.domain.quest;

/**
 * Read-only progress view of one objective, used by menus and commands.
 *
 * @param objective the objective definition
 * @param current   current amount (clamped at the required amount)
 * @param marks     distinct marks collected (unique victims, distinct mob types)
 * @param complete  whether both the amount and the unique requirement are met
 */
public record ObjectiveStatus(ObjectiveDefinition objective, long current, int marks, boolean complete) {
}
