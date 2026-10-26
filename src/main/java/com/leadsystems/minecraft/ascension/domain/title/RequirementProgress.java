package com.leadsystems.minecraft.ascension.domain.title;

/**
 * Progress on one requirement; {@code current} is capped at {@code required}.
 */
public record RequirementProgress(Requirement requirement, long current, long required, boolean met) {}
