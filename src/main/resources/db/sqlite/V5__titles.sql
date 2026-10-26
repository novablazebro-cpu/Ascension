ALTER TABLE players ADD COLUMN equipped_title TEXT;

CREATE TABLE title_ownership (
    title_id    TEXT PRIMARY KEY,
    owner_uuid  TEXT NOT NULL,
    acquired_at INTEGER NOT NULL
);

CREATE INDEX idx_title_owner ON title_ownership (owner_uuid);
