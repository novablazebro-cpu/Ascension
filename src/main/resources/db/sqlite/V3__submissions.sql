CREATE TABLE item_submissions (
    operation_id TEXT    PRIMARY KEY,
    uuid         TEXT    NOT NULL,
    rank_index   INTEGER NOT NULL,
    objective_id TEXT    NOT NULL,
    material     TEXT    NOT NULL,
    amount       INTEGER NOT NULL,
    before_count INTEGER NOT NULL,
    state        TEXT    NOT NULL,
    created_at   INTEGER NOT NULL,
    finished_at  INTEGER
);

CREATE INDEX idx_item_submissions_pending ON item_submissions (uuid, state);
