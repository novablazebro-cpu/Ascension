-- Persist anti-farming credit so relogging and restarts cannot reset the victim cooldown.
CREATE TABLE pvp_kill_credit (
    killer_uuid TEXT    NOT NULL,
    victim_uuid TEXT    NOT NULL,
    credited_at INTEGER NOT NULL,
    PRIMARY KEY (killer_uuid, victim_uuid)
);

CREATE INDEX idx_pvp_kill_credit_at ON pvp_kill_credit (credited_at);
