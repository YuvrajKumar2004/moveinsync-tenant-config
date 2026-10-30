-- Identity of this change log. Generated once (on first start) and never changed, so clients can tell
-- a reset or replaced database apart from the one their cached snapshot came from.
ALTER TABLE config_version ADD COLUMN history_id VARCHAR(64);
