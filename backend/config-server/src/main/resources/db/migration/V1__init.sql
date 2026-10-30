-- Append-only change log. The current state is rebuilt by replaying it; nothing is updated in place.

CREATE TABLE config_version (
    id              INT PRIMARY KEY,
    current_version BIGINT NOT NULL
);
INSERT INTO config_version (id, current_version) VALUES (1, 0);

CREATE TABLE change_set (
    version          BIGINT PRIMARY KEY,
    kind             VARCHAR(32)   NOT NULL,
    author           VARCHAR(100)  NOT NULL,
    reason           VARCHAR(1000) NOT NULL,
    committed_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    -- ",acme,globex," : tenants whose data the change touched, for tenant-scoped history queries
    tenants          VARCHAR(4000) NOT NULL,
    touches_global   BOOLEAN       NOT NULL,
    idempotency_key  VARCHAR(100),
    ops              TEXT          NOT NULL,
    diff             TEXT          NOT NULL,
    CONSTRAINT uq_change_set_idempotency UNIQUE (author, idempotency_key)
);

CREATE INDEX ix_change_set_committed_at ON change_set (committed_at);
