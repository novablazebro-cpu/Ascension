CREATE TABLE hunt_assignments (
    hunter_uuid TEXT    PRIMARY KEY,
    target_uuid TEXT    NOT NULL,
    assigned_at INTEGER NOT NULL,
    state       TEXT    NOT NULL
);

CREATE TABLE hunt_history (
    hunter_uuid TEXT    NOT NULL,
    target_uuid TEXT    NOT NULL,
    completed_at INTEGER NOT NULL,
    PRIMARY KEY (hunter_uuid, target_uuid, completed_at)
);

CREATE INDEX idx_hunt_history_recent ON hunt_history (hunter_uuid, completed_at);
