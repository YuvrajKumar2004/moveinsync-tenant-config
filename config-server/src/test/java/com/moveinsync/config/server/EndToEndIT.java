package com.moveinsync.config.server;

import com.moveinsync.config.client.ConfigClient;
import com.moveinsync.config.client.transport.PasswordTokenProvider;
import com.moveinsync.config.client.transport.HttpConfigTransport;
import com.moveinsync.config.server.support.Api;
import com.moveinsync.config.server.support.Measurements;
import com.moveinsync.config.server.support.ServerHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Real clients against the real server over HTTP/SSE. */
class EndToEndIT {

    ServerHarness server;
    Api api;
    String operator;
    final List<ConfigClient> clients = new ArrayList<>();
    @TempDir
    Path dir;

    @BeforeEach
    void start() {
        server = new ServerHarness().start();
        api = new Api(server.baseUrl());
        operator = api.token("operator");
    }

    @AfterEach
    void stop() {
        clients.forEach(ConfigClient::close);
        server.close();
    }

    private ConfigClient client(String user, Path snapshotFile) {
        var http = HttpConfigTransport.defaultClient();
        ConfigClient c = ConfigClient.builder()
                .server(server.baseUrl(), new PasswordTokenProvider(http, server.baseUrl(), user, Api.PASSWORDS.get(user)))
                .httpClient(http)
                .snapshotFile(snapshotFile)
                .pollInterval(Duration.ofSeconds(2))
                .backoff(Duration.ofMillis(100), Duration.ofSeconds(2))
                .name(user)
                .build();
        clients.add(c);
        return c.start();
    }

    private long set(String level, String client, String office, String key, String value) {
        Api.Response r = api.post("/api/v1/change-sets", operator, Api.set("e2e", level, client, office, null, key, value));
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        return r.json("version").asLong();
    }

    private static String cutoff(ConfigClient c, String office) {
        return c.view("acme", office, null).raw("booking.cutoff");
    }

    @Test
    void bengaluruExamplePropagatesByPushWithinFiveSeconds() {
        ConfigClient booking = client("booking-service", null);
        await().atMost(5, SECONDS).until(booking::streamConnected);
        long t0 = System.nanoTime();
        long v = set("OFFICE", "acme", "blr-1", "booking.cutoff", "30m");
        await().atMost(5, SECONDS).until(() -> booking.version() >= v);
        double ms = (System.nanoTime() - t0) / 1e6;
        Measurements.record(String.format("e2e push propagation (1 client, commit->visible): %.1f ms", ms));
        assertThat(ms).isLessThan(5_000);
        assertThat(cutoff(booking, "blr-1")).isEqualTo("PT30M");
        assertThat(cutoff(booking, "blr-2")).isEqualTo("PT1H");
        // the booking rule from the case study: 45 minutes before departure is now allowed at blr-1 only
        Duration before = Duration.ofMinutes(45);
        assertThat(before.compareTo(booking.view("acme", "blr-1", null).duration("booking.cutoff")) >= 0).isTrue();
        assertThat(before.compareTo(booking.view("acme", "blr-2", null).duration("booking.cutoff")) >= 0).isFalse();
    }

    @Test
    void droppedPushIsHealedByHeartbeatOrPollWithinFiveSeconds() {
        ConfigClient c = client("booking-service", null);
        await().atMost(5, SECONDS).until(c::streamConnected);
        server.faults(true, false, false);
        long t0 = System.nanoTime();
        long v = set("OFFICE", "acme", "blr-1", "booking.cutoff", "20m");
        await().atMost(5, SECONDS).until(() -> c.version() >= v);
        double ms = (System.nanoTime() - t0) / 1e6;
        Measurements.record(String.format("missed-push healing (push dropped by fault): %.1f ms, pollHeals=%d heartbeatHeals=%d",
                ms, c.metrics().pollHeals(), c.metrics().heartbeatHeals()));
        assertThat(ms).isLessThan(5_000);
        assertThat(c.metrics().pollHeals() + c.metrics().heartbeatHeals()).isPositive();
    }

    @Test
    void duplicateAndOutOfOrderPushesNeverMoveAClientBackwards() {
        ConfigClient c = client("booking-service", null);
        await().atMost(5, SECONDS).until(c::streamConnected);
        AtomicLong lastSeen = new AtomicLong();
        AtomicBoolean regressed = new AtomicBoolean();
        c.addListener(s -> {
            if (s.version() < lastSeen.getAndSet(s.version())) {
                regressed.set(true);
            }
        });
        server.faults(false, true, true);
        long v = 0;
        for (int i = 1; i <= 20; i++) {
            v = set("OFFICE", "acme", "blr-1", "booking.cutoff", i + "m");
        }
        long last = v;
        await().atMost(5, SECONDS).until(() -> c.version() >= last);
        await().atMost(2, SECONDS).until(() -> c.metrics().staleDropped() > 0);
        assertThat(cutoff(c, "blr-1")).isEqualTo("PT20M");
        assertThat(regressed).isFalse();
        Measurements.record("duplicate+out-of-order pushes: staleDropped=" + c.metrics().staleDropped()
                + " gaps=" + c.metrics().gapsDetected() + " finalVersion=" + c.version());
    }

    /** Readers on the client never see the new night-window start with the old end, while it flips over the network. */
    @Test
    void continuousClientReadsNeverSeeAMixedNightWindow() throws Exception {
        ConfigClient c = client("booking-service", null);
        await().atMost(5, SECONDS).until(c::streamConnected);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<String> mixed = new AtomicReference<>();
        AtomicLong reads = new AtomicLong();
        CountDownLatch ready = new CountDownLatch(4);
        List<Thread> readers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> {
                ready.countDown();
                while (!stop.get()) {
                    var view = c.view("acme", "blr-1", "night");
                    LocalTime start = view.time("night.window.start");
                    LocalTime end = view.time("night.window.end");
                    String pair = start + "-" + end;
                    if (!pair.equals("20:00-06:00") && !pair.equals("22:00-04:00")) {
                        mixed.compareAndSet(null, pair);
                    }
                    reads.incrementAndGet();
                }
            });
            t.start();
            readers.add(t);
        }
        ready.await();
        long v = 0;
        for (int i = 0; i < 40; i++) {
            boolean b = i % 2 == 0;
            Api.Response r = api.post("/api/v1/change-sets", operator, Map.of("reason", "flip", "ops", List.of(
                    Api.op("SET", "CLIENT", "acme", null, null, "night.window.start", b ? "22:00" : "20:00"),
                    Api.op("SET", "CLIENT", "acme", null, null, "night.window.end", b ? "04:00" : "06:00"))));
            assertThat(r.status()).as(r.raw()).isEqualTo(201);
            v = r.json("version").asLong();
        }
        long last = v;
        await().atMost(5, SECONDS).until(() -> c.version() >= last);
        stop.set(true);
        for (Thread t : readers) {
            t.join();
        }
        Measurements.record("atomic change-set reads over network: reads=" + reads.get() + " flips=40 mixed=" + mixed.get());
        assertThat(mixed.get()).isNull();
        assertThat(reads.get()).isGreaterThan(1000);
    }

    /**
     * Kill-the-server test: a change is committed but its push is lost (as if the server crashed right
     * after committing), then the server is killed. Every client keeps answering reads from its snapshot,
     * a new service instance cold-starts from disk, and once the server returns everyone gets the change.
     */
    @Test
    void killTheServerReadsContinueAndTheLastChangeArrivesAfterRestart() throws Exception {
        Path disk = dir.resolve("booking.snapshot.json");
        ConfigClient a = client("booking-service", disk);
        ConfigClient b = client("booking-service", null);
        await().atMost(5, SECONDS).until(() -> a.streamConnected() && b.streamConnected());
        long v1 = set("OFFICE", "acme", "blr-1", "booking.cutoff", "30m");
        await().atMost(5, SECONDS).until(() -> a.version() >= v1 && b.version() >= v1);

        server.faults(true, false, false);
        long v2 = set("OFFICE", "acme", "blr-1", "booking.cutoff", "15m"); // committed, never pushed
        server.kill();

        // server is down: reads keep working from memory
        long readsDuringOutage = 0;
        long end = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < end) {
            assertThat(cutoff(a, "blr-2")).isEqualTo("PT1H");
            assertThat(cutoff(b, "blr-1")).isIn("PT30M", "PT15M");
            readsDuringOutage++;
        }
        // a new instance of the service starts with the server still down, from a's disk snapshot
        a.close();
        clients.remove(a);
        ConfigClient coldStart = client("booking-service", disk);
        assertThat(coldStart.version()).isGreaterThanOrEqualTo(v1);
        assertThat(cutoff(coldStart, "blr-1")).isIn("PT30M", "PT15M");

        long t0 = System.nanoTime();
        server.start(); // same port, same database, faults reset
        await().atMost(15, SECONDS).until(() -> b.version() >= v2 && coldStart.version() >= v2);
        double recoveryMs = (System.nanoTime() - t0) / 1e6;
        assertThat(cutoff(b, "blr-1")).isEqualTo("PT15M");
        assertThat(cutoff(coldStart, "blr-1")).isEqualTo("PT15M");
        Measurements.record(String.format("kill-the-server: %d reads served during outage, cold start from disk ok, "
                + "all clients had the pre-crash change %.0f ms after server start returned", readsDuringOutage, recoveryMs));
    }

    @Test
    void serviceClientOnlyReceivesItsTenantsData() {
        set("OFFICE", "globex", "pune-1", "booking.cutoff", "17m");
        ConfigClient acmeOnly = client("acme-service", null);
        set("OFFICE", "acme", "blr-1", "booking.cutoff", "25m");
        long v = set("OFFICE", "globex", "pune-1", "booking.cutoff", "18m");
        await().atMost(5, SECONDS).until(() -> acmeOnly.version() >= v);
        assertThat(acmeOnly.snapshot().override(com.moveinsync.config.core.model.Scope.office("globex", "pune-1"), "booking.cutoff")).isNull();
        assertThat(acmeOnly.view("globex", "pune-1", null).raw("booking.cutoff")).isEqualTo("PT1H"); // only the global default
        assertThat(cutoff(acmeOnly, "blr-1")).isEqualTo("PT25M");
    }
}
