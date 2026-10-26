package com.leadsystems.minecraft.ascension.domain.quest;

/**
 * Quest lifecycle. The only valid path is {@code NOT_ACCEPTED -> ACTIVE -> COMPLETED -> REWARDED};
 * {@code COMPLETED -> ACTIVE} is allowed only to retry after a failed reward transaction.
 * NOT_ACCEPTED has no database row; the others are stored in {@code quests.state}.
 */
public enum QuestState {
    NOT_ACCEPTED,
    ACTIVE,
    COMPLETED,
    REWARDED
}
