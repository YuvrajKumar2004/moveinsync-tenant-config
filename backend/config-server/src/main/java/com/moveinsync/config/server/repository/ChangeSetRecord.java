package com.moveinsync.config.server.repository;

import java.time.Instant;

/** One row of the append-only change log. {@code opsJson} holds the wire-format operations. */
public record ChangeSetRecord(long version, String kind, String author, String reason, Instant committedAt,
                              String tenants, boolean touchesGlobal, String idempotencyKey,
                              String opsJson, String diffJson) {
}
