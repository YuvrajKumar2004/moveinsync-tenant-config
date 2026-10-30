package com.moveinsync.config.server.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** JDBC access to the change log. Plain SQL that runs on PostgreSQL and H2 (PostgreSQL mode). */
@Repository
public class ChangeSetRepository {

    private static final String COLUMNS =
            "version, kind, author, reason, committed_at, tenants, touches_global, idempotency_key, ops, diff";

    private static final RowMapper<ChangeSetRecord> MAPPER = (rs, i) -> new ChangeSetRecord(
            rs.getLong("version"), rs.getString("kind"), rs.getString("author"), rs.getString("reason"),
            rs.getTimestamp("committed_at").toInstant(), rs.getString("tenants"), rs.getBoolean("touches_global"),
            rs.getString("idempotency_key"), rs.getString("ops"), rs.getString("diff"));

    private final JdbcTemplate jdbc;

    public ChangeSetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Locks the single version row for the rest of the transaction and returns the committed version. */
    public long lockCurrentVersion() {
        Long v = jdbc.queryForObject("SELECT current_version FROM config_version WHERE id = 1 FOR UPDATE", Long.class);
        return v == null ? 0 : v;
    }

    /** The change log's identity, created on first use. Safe if two instances race: the first write wins. */
    public String historyId(String candidate) {
        jdbc.update("UPDATE config_version SET history_id = ? WHERE id = 1 AND history_id IS NULL", candidate);
        return jdbc.queryForObject("SELECT history_id FROM config_version WHERE id = 1", String.class);
    }

    public long currentVersion() {
        Long v = jdbc.queryForObject("SELECT current_version FROM config_version WHERE id = 1", Long.class);
        return v == null ? 0 : v;
    }

    /** Must run in the same transaction as {@link #lockCurrentVersion()}. */
    public void append(ChangeSetRecord r) {
        jdbc.update("INSERT INTO change_set (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                r.version(), r.kind(), r.author(), r.reason(), Timestamp.from(r.committedAt()), r.tenants(),
                r.touchesGlobal(), r.idempotencyKey(), r.opsJson(), r.diffJson());
        int updated = jdbc.update("UPDATE config_version SET current_version = ? WHERE id = 1 AND current_version = ?",
                r.version(), r.version() - 1);
        if (updated != 1) {
            throw new IllegalStateException("version row moved concurrently");
        }
    }

    public Optional<ChangeSetRecord> findByIdempotencyKey(String author, String key) {
        return jdbc.query("SELECT " + COLUMNS + " FROM change_set WHERE author = ? AND idempotency_key = ?", MAPPER, author, key)
                .stream().findFirst();
    }

    public Optional<ChangeSetRecord> findByVersion(long version) {
        return jdbc.query("SELECT " + COLUMNS + " FROM change_set WHERE version = ?", MAPPER, version).stream().findFirst();
    }

    /** Streams change sets with version in (after, upTo] in order; used to rebuild state. */
    public void forEachInRange(long after, long upTo, Consumer<ChangeSetRecord> consumer) {
        jdbc.query("SELECT " + COLUMNS + " FROM change_set WHERE version > ? AND version <= ? ORDER BY version",
                rs -> {
                    consumer.accept(MAPPER.mapRow(rs, 0));
                }, after, upTo);
    }

    /** Newest first. {@code tenant} null means all change sets. */
    public List<ChangeSetRecord> history(String tenant, long beforeVersion, int limit) {
        if (tenant == null) {
            return jdbc.query("SELECT " + COLUMNS + " FROM change_set WHERE version < ? ORDER BY version DESC LIMIT ?",
                    MAPPER, beforeVersion, limit);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM change_set WHERE version < ? AND tenants LIKE ? "
                + "ORDER BY version DESC LIMIT ?", MAPPER, beforeVersion, "%," + tenant + ",%", limit);
    }
}
