package com.leadsystems.minecraft.ascension.storage;

import com.leadsystems.minecraft.ascension.domain.quest.QuestState;

import java.util.Map;
import java.util.Set;

/** A stored quest (NOT_ACCEPTED when no row exists). */
public record QuestRecord(int rankIndex, QuestState state, long acceptedAt,
                          Map<String, Long> amounts, Map<String, Set<String>> marks) {}
