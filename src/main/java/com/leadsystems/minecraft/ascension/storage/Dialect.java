package com.leadsystems.minecraft.ascension.storage;

/**
 * SQL that differs between SQLite and MySQL/MariaDB. Everything else in the repositories is
 * portable ANSI SQL. (MySQL implementation arrives with multi-server support.)
 */
public interface Dialect {

    /** Dialect folder name for migration resources, e.g. {@code sqlite}. */
    String name();

    /** (uuid, rank_index, objective_id, amount): insert or raise to the larger amount. */
    String upsertProgressMax();

    /** (uuid, rank_index, objective_id, mark): insert, ignoring duplicates. */
    String insertMarkIgnore();

    /** (uuid, rank_index, accepted_at): insert an ACTIVE quest row unless one exists. */
    String insertQuestIgnore();

    /** (uuid, ability_key, ready_at): insert or replace the ready-at time. */
    String upsertCooldown();

    /** (uuid, stat_key, amount): insert or add to the existing amount. */
    String addStat();
}
