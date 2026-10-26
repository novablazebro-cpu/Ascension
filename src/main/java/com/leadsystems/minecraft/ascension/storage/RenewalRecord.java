package com.leadsystems.minecraft.ascension.storage;

import java.util.UUID;

/** Durable seven-day rank renewal assignment. */
public record RenewalRecord(UUID uuid, int heldRankIndex, String branch, String objectiveType, int required, int progress,
                            UUID target, String state, long assignedAt) {
    public boolean active() { return "ACTIVE".equals(state); }
}
