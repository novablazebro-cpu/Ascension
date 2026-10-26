package com.leadsystems.minecraft.ascension.storage;

/**
 * SQLite 3.24+ upsert syntax (Paper 1.21.4 ships sqlite-jdbc 3.47.0.0).
 */
public final class SqliteDialect implements Dialect {

    @Override
    public String name() {
        return "sqlite";
    }

    @Override
    public String upsertProgressMax() {
        return "INSERT INTO quest_progress (uuid, rank_index, objective_id, amount) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (uuid, rank_index, objective_id) DO UPDATE SET amount = MAX(amount, excluded.amount)";
    }

    @Override
    public String insertMarkIgnore() {
        return "INSERT INTO quest_marks (uuid, rank_index, objective_id, mark) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (uuid, rank_index, objective_id, mark) DO NOTHING";
    }

    @Override
    public String insertQuestIgnore() {
        return "INSERT INTO quests (uuid, rank_index, state, accepted_at) VALUES (?, ?, 'ACTIVE', ?) "
                + "ON CONFLICT (uuid, rank_index) DO NOTHING";
    }

    @Override
    public String upsertCooldown() {
        return "INSERT INTO cooldowns (uuid, ability_key, ready_at) VALUES (?, ?, ?) "
                + "ON CONFLICT (uuid, ability_key) DO UPDATE SET ready_at = excluded.ready_at";
    }

    @Override
    public String addStat() {
        return "INSERT INTO lifetime_stats (uuid, stat_key, amount) VALUES (?, ?, ?) "
                + "ON CONFLICT (uuid, stat_key) DO UPDATE SET amount = amount + excluded.amount";
    }
}
