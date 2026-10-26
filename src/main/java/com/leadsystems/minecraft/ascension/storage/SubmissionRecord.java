package com.leadsystems.minecraft.ascension.storage;

import java.util.UUID;

/** A prepared item removal that may need recovery after a crash. */
public record SubmissionRecord(String operationId, UUID uuid, int rankIndex, String objectiveId,
                               String material, int amount, int beforeCount) {}
