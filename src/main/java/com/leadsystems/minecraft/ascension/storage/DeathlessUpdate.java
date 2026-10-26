package com.leadsystems.minecraft.ascension.storage;

import java.util.UUID;

/** Result of adding active playtime for one player. */
public record DeathlessUpdate(UUID uuid, int currentDays, int bestDays, boolean newlyQualified) {}
