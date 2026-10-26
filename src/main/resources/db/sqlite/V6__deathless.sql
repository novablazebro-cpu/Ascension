CREATE TABLE daily_activity (
    uuid           TEXT NOT NULL,
    activity_day   TEXT NOT NULL,
    active_seconds INTEGER NOT NULL,
    PRIMARY KEY (uuid, activity_day)
);

CREATE TABLE deathless_streaks (
    uuid               TEXT PRIMARY KEY,
    current_days       INTEGER NOT NULL,
    best_days          INTEGER NOT NULL,
    last_qualified_day TEXT,
    last_death_at      INTEGER NOT NULL DEFAULT 0
);
