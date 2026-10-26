-- Persist continuous absence across restarts for automatic renewal hunt rollover.
ALTER TABLE rank_renewals ADD COLUMN target_absent_since INTEGER NOT NULL DEFAULT 0;
