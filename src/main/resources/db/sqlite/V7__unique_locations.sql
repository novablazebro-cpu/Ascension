CREATE TABLE unique_location_progress (
    uuid        TEXT    NOT NULL,
    category    TEXT    NOT NULL,
    world_uuid  TEXT    NOT NULL,
    identity    TEXT    NOT NULL,
    credited_at INTEGER NOT NULL,
    PRIMARY KEY (uuid, category, world_uuid, identity)
);
