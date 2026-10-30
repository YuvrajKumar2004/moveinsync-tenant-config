package com.moveinsync.config.server.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real PostgreSQL for integration tests without Docker (zonky embedded binaries), shared by all tests in
 * the JVM; each caller gets a fresh database. Set -Dtest.db=h2 to use in-memory H2 instead.
 */
public final class TestDatabase {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static EmbeddedPostgres postgres;

    private TestDatabase() {
    }

    public record Db(String url, String user, String password) {
    }

    public static boolean usingH2() {
        return "h2".equalsIgnoreCase(System.getProperty("test.db"));
    }

    public static synchronized Db fresh() {
        String name = "cfg" + COUNTER.incrementAndGet();
        if (usingH2()) {
            return new Db("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        }
        try {
            if (postgres == null) {
                postgres = EmbeddedPostgres.builder().start();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        postgres.close();
                    } catch (IOException ignored) {
                        // best effort
                    }
                }));
            }
            try (Connection c = postgres.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
                s.execute("CREATE DATABASE " + name);
            }
            return new Db(postgres.getJdbcUrl("postgres", name), "postgres", "postgres");
        } catch (Exception e) {
            throw new IllegalStateException("could not start embedded PostgreSQL (try -Dtest.db=h2)", e);
        }
    }
}
