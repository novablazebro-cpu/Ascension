package com.leadsystems.minecraft.ascension.storage;

/** Row of the {@code admin_audit} table. */
public record AuditEntry(long id, String actor, String action, String target, String details, long at) {}
