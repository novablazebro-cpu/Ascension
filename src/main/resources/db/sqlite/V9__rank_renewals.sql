-- A legacy rank becomes renewable seven days after this migration, rather than expiring at upgrade.
ALTER TABLE players ADD COLUMN rank_active INTEGER NOT NULL DEFAULT 1;
ALTER TABLE players ADD COLUMN rank_granted_at INTEGER NOT NULL DEFAULT 0;
UPDATE players SET rank_granted_at = CAST(strftime('%s','now') AS INTEGER) * 1000 WHERE rank_granted_at = 0;

CREATE TABLE rank_renewals (
    uuid TEXT PRIMARY KEY,
    held_rank_index INTEGER NOT NULL,
    branch TEXT NOT NULL,
    objective_type TEXT NOT NULL DEFAULT 'HOSTILE',
    required INTEGER NOT NULL,
    progress INTEGER NOT NULL DEFAULT 0,
    target_uuid TEXT,
    state TEXT NOT NULL,
    assigned_at INTEGER NOT NULL,
    completed_at INTEGER
);
