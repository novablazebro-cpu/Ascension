-- V1: core player, quest, progress, cooldown, statistics and audit tables (milestone M1).
-- Later milestones add titles, hunts, combat and submission tables in new migration files.

CREATE TABLE players (
    uuid            TEXT    PRIMARY KEY,
    last_name       TEXT    NOT NULL,
    rank_index      INTEGER NOT NULL DEFAULT 0,
    mastery         INTEGER NOT NULL DEFAULT 0,
    book_nonce      TEXT    NOT NULL,
    book_pending    INTEGER NOT NULL DEFAULT 0,
    first_join      INTEGER NOT NULL,
    last_seen       INTEGER NOT NULL,
    last_ip_hash    TEXT,
    pending_notice  TEXT,
    row_version     INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_players_name ON players (last_name);

-- One row per accepted quest. No row = NOT_ACCEPTED.
CREATE TABLE quests (
    uuid         TEXT    NOT NULL,
    rank_index   INTEGER NOT NULL,
    state        TEXT    NOT NULL,
    accepted_at  INTEGER NOT NULL,
    completed_at INTEGER,
    rewarded_at  INTEGER,
    PRIMARY KEY (uuid, rank_index)
);

CREATE TABLE quest_progress (
    uuid         TEXT    NOT NULL,
    rank_index   INTEGER NOT NULL,
    objective_id TEXT    NOT NULL,
    amount       INTEGER NOT NULL,
    PRIMARY KEY (uuid, rank_index, objective_id)
);

-- Distinct marks per objective: unique victims, distinct mob types.
CREATE TABLE quest_marks (
    uuid         TEXT    NOT NULL,
    rank_index   INTEGER NOT NULL,
    objective_id TEXT    NOT NULL,
    mark         TEXT    NOT NULL,
    PRIMARY KEY (uuid, rank_index, objective_id, mark)
);

CREATE TABLE lifetime_stats (
    uuid     TEXT    NOT NULL,
    stat_key TEXT    NOT NULL,
    amount   INTEGER NOT NULL,
    PRIMARY KEY (uuid, stat_key)
);

-- Absolute ready-at timestamps (epoch millis), keyed by ability, never by title or equip.
CREATE TABLE cooldowns (
    uuid        TEXT    NOT NULL,
    ability_key TEXT    NOT NULL,
    ready_at    INTEGER NOT NULL,
    PRIMARY KEY (uuid, ability_key)
);

CREATE TABLE ability_state (
    uuid        TEXT NOT NULL,
    state_key   TEXT NOT NULL,
    state_value TEXT NOT NULL,
    PRIMARY KEY (uuid, state_key)
);

CREATE TABLE admin_audit (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    actor   TEXT    NOT NULL,
    action  TEXT    NOT NULL,
    target  TEXT,
    details TEXT,
    at      INTEGER NOT NULL
);

CREATE INDEX idx_admin_audit_at ON admin_audit (at);
