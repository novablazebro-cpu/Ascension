package com.leadsystems.minecraft.ascension.storage;

import java.util.UUID;

/** One persisted assigned-player hunt. */
public record HuntRecord(UUID hunter, UUID target, long assignedAt, String state) {}
