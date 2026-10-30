package com.moveinsync.config.client;

import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.Op;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.snapshot.Resolution;
import com.moveinsync.config.core.wire.WireCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class ConfigClientTest {

    private static final Scope BLR1 = Scope.office("acme", "blr-1");
    private final List<ConfigClient> clients = new ArrayList<>();
    @TempDir
    Path dir;

    private ConfigClient client(FakeServer server, Path file) {
        ConfigClient c = ConfigClient.builder().transport(server).snapshotFile(file)
                .pollInterval(Duration.ofMillis(300)).backoff(Duration.ofMillis(20), Duration.ofMillis(200))
                .initialSyncTimeout(Duration.ofMillis(300)).name("t").build();
        clients.add(c);
        return c;
    }

    @AfterEach
    void close() {
        clients.forEach(ConfigClient::close);
    }

    private static String cutoff(ConfigClient c) {
        return c.view("acme", "blr-1", null).raw("booking.cutoff");
    }

    @Test
    void startsFromServerAndAppliesPushedChanges() {
        FakeServer server = new FakeServer();
        ConfigClient c = client(server, null).start();
        assertThat(c.version()).isEqualTo(1);
        await().atMost(2, SECONDS).until(() -> server.openStreams() == 1);
        server.commit(new Op.SetOverride(BLR1, "booking.cutoff", "PT30M"));
        await().atMost(1, SECONDS).until(() -> cutoff(c).equals("PT30M"));
        assertThat(c.metrics().eventsApplied()).isGreaterThanOrEqualTo(1);
        assertThat(c.view("acme", "blr-2", null).raw("booking.cutoff")).isEqualTo("PT1H");
        Resolution r = c.view("acme", "blr-1", null).explain("booking.cutoff");
        assertThat(r.level().name()).isEqualTo("OFFICE");
    }

    @Test
    void duplicateAndOutOfOrderEventsAreDroppedAndGapsFetchTheDelta() {
        FakeServer server = new FakeServer();
        server.dropPushes = true;
        ConfigClient c = client(server, null);
        c.start();
        ChangeEvent v2 = server.commit(new Op.SetOverride(BLR1, "booking.cutoff", "PT45M"));
        ChangeEvent v3 = server.commit(new Op.SetOverride(BLR1, "booking.cutoff", "PT30M"));
        ChangeEvent v4 = server.commit(new Op.SetOverride(BLR1, "booking.cutoff", "PT20M"));

        // v4 arrives first: gap (local is 1) -> delta fetch brings 2..4
        c.onEvent(WireCodec.toWire(v4));
        assertThat(c.version()).isEqualTo(4);
        assertThat(c.metrics().gapsDetected()).isEqualTo(1);
        // then the late v2 and a duplicate v4 are ignored
        c.onEvent(WireCodec.toWire(v2));
        c.onEvent(WireCodec.toWire(v4));
        c.onEvent(WireCodec.toWire(v3));
        assertThat(c.version()).isEqualTo(4);
        assertThat(cutoff(c)).isEqualTo("PT20M");
        assertThat(c.metrics().staleDropped()).isEqualTo(3);
        // a forged older version never rolls state back
        c.onEvent(WireCodec.toWire(new ChangeEvent(3, 0, ChangeKind.APPLY, List.of(new Op.SetOverride(BLR1, "booking.cutoff", "PT1M")))));
        assertThat(cutoff(c)).isEqualTo("PT20M");
    }

    @Test
    void missedPushIsHealedByThePeriodicCheckWithinFiveSeconds() {
        FakeServer server = new FakeServer();
        ConfigClient c = client(server, null).start();
        await().atMost(2, SECONDS).until(() -> server.openStreams() == 1);
        server.dropPushes = true;
        long t0 = System.nanoTime();
        server.commit(new Op.SetOverride(BLR1, "booking.cutoff", "PT10M"));
        await().atMost(5, SECONDS).until(() -> cutoff(c).equals("PT10M"));
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(5_000);
        assertThat(c.metrics().pollHeals()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void keepsServingWhenTheServerDiesAndCatchesUpWhenItReturns() {
        FakeServer server = new FakeServer();
        ConfigClient c = client(server, null).start();
        server.commit(new Op.SetOverride(BLR1, "booking.cutoff", "PT30M"));
        await().atMost(2, SECONDS).until(() -> cutoff(c).equals("PT30M"));

        server.down = true;
        for (int i = 0; i < 1000; i++) {
            assertThat(cutoff(c)).isEqualTo("PT30M");
        }
        server.down = false;
        server.dropPushes = true; // change made while clients were disconnected
        server.commit(new Op.SetOverride(BLR1, "booking.cutoff", "PT25M"));
        await().atMost(5, SECONDS).until(() -> cutoff(c).equals("PT25M"));
        assertThat(c.metrics().syncErrors()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void startsFromDiskWhenTheServerIsDown() throws Exception {
        Path file = dir.resolve("snap.json");
        FakeServer server = new FakeServer();
        ConfigClient first = client(server, file).start();
        server.commit(new Op.SetOverride(BLR1, "booking.cutoff", "PT30M"));
        await().atMost(2, SECONDS).until(() -> cutoff(first).equals("PT30M"));
        first.close();
        assertThat(Files.exists(file)).isTrue();

        server.down = true;
        ConfigClient second = client(server, file).start();
        assertThat(second.version()).isEqualTo(2);
        assertThat(cutoff(second)).isEqualTo("PT30M");
    }

    @Test
    void noServerAndNoDiskFailsFastUnlessAllowed() {
        FakeServer server = new FakeServer();
        server.down = true;
        assertThatThrownBy(() -> client(server, dir.resolve("missing.json")).start())
                .isInstanceOf(IllegalStateException.class);
        ConfigClient lenient = ConfigClient.builder().transport(server).requireInitialData(false)
                .initialSyncTimeout(Duration.ofMillis(300)).build();
        clients.add(lenient);
        assertThat(lenient.start().version()).isZero();
    }

    @Test
    void corruptOrTamperedSnapshotFilesAreRejected() throws Exception {
        Path file = dir.resolve("snap.json");
        FakeServer server = new FakeServer();
        new SnapshotStore(file).save(server.state, server.historyId);
        String good = Files.readString(file);

        Files.writeString(file, good.replace("PT1H", "PT9H")); // edited payload, stale checksum
        assertThatThrownBy(() -> new SnapshotStore(file).load()).isInstanceOf(SnapshotStore.CorruptSnapshotException.class)
                .hasMessageContaining("checksum");
        Files.writeString(file, good.substring(0, good.length() / 2)); // torn write
        assertThatThrownBy(() -> new SnapshotStore(file).load()).isInstanceOf(SnapshotStore.CorruptSnapshotException.class);

        server.down = true;
        assertThatThrownBy(() -> client(server, file).start()).isInstanceOf(IllegalStateException.class);
        server.down = false;
        ConfigClient c = client(server, file).start(); // corrupt file ignored, server used
        assertThat(c.version()).isEqualTo(1);
        assertThat(c.metrics().diskFailures()).isEqualTo(1);
        await().atMost(2, SECONDS).until(() -> new SnapshotStore(file).load().isPresent());
    }

    /**
     * Regression for the stale-snapshot defect: a service restarted with a disk snapshot from a server
     * whose database was later wiped must not keep its old state (the old v2 would hide the new v2).
     */
    @Test
    void snapshotFromAWipedServerIsDiscardedAndNewChangesApply() throws Exception {
        Path file = dir.resolve("snap.json");
        FakeServer server = new FakeServer();
        ConfigClient first = client(server, file).start();
        server.commit(new Op.SetOverride(BLR1, "booking.cutoff", "PT30M"));       // v2 in history A
        await().atMost(2, SECONDS).until(() -> cutoff(first).equals("PT30M"));
        await().atMost(2, SECONDS).until(() -> new SnapshotStore(file).load().map(l -> l.snapshot().version()).orElse(0L) == 2);
        first.close();

        server.resetHistory("history-B");                                         // wiped database: v1 again
        ConfigClient second = client(server, file).start();
        assertThat(second.version()).isEqualTo(1);
        assertThat(cutoff(second)).isEqualTo("PT1H");
        assertThat(second.metrics().historyResets()).isEqualTo(1);

        await().atMost(2, SECONDS).until(() -> server.openStreams() == 1);
        server.commit(new Op.SetOverride(BLR1, "booking.cutoff", "PT35M"));      // v2 in history B
        await().atMost(5, SECONDS).until(() -> cutoff(second).equals("PT35M"));
        assertThat(second.metrics().staleDropped()).isZero();
        assertThat(new SnapshotStore(file).load().orElseThrow().historyId()).isEqualTo("history-B");
    }

    @Test
    void runningClientNoticesTheServerHistoryChanged() {
        FakeServer server = new FakeServer();
        ConfigClient c = client(server, null).start();
        server.commit(new Op.SetOverride(BLR1, "booking.cutoff", "PT30M"));       // v2, history A
        await().atMost(2, SECONDS).until(() -> cutoff(c).equals("PT30M"));

        server.resetHistory("history-B");                                         // v1, history B
        server.commit(new Op.SetOverride(BLR1, "booking.cutoff", "PT35M"));      // v2, history B (pushed, looks stale)
        await().atMost(5, SECONDS).until(() -> cutoff(c).equals("PT35M"));
        assertThat(c.metrics().historyResets()).isEqualTo(1);
        assertThat(c.version()).isEqualTo(2);
    }

    @Test
    void legacySnapshotWithoutHistoryServesOfflineAndResyncsOnFirstContact() throws Exception {
        Path file = dir.resolve("legacy.json");
        FakeServer server = new FakeServer();
        server.commit(new Op.SetOverride(BLR1, "booking.cutoff", "PT30M"));
        // format-1 file as written before history ids existed
        var data = WireCodec.toWire(server.state);
        byte[] payload = com.moveinsync.config.client.transport.HttpConfigTransport.JSON.writeValueAsBytes(data);
        Files.write(file, com.moveinsync.config.client.transport.HttpConfigTransport.JSON.writeValueAsBytes(
                new com.moveinsync.config.core.wire.Wire.DiskFile(1, SnapshotStore.sha256(payload), null, data)));

        server.down = true;
        ConfigClient offline = client(server, file).start();
        assertThat(cutoff(offline)).isEqualTo("PT30M");                         // still usable offline
        offline.close();

        server.down = false;
        server.resetHistory("history-B");
        ConfigClient online = client(server, file).start();
        assertThat(online.metrics().historyResets()).isEqualTo(1);              // unknown history: trust the server
        assertThat(cutoff(online)).isEqualTo("PT1H");
    }

    @Test
    void backoffUsesFullJitterWithinTheCap() {
        Backoff b = new Backoff(Duration.ofMillis(100), Duration.ofSeconds(5));
        long max = 0;
        long min = Long.MAX_VALUE;
        for (int i = 0; i < 10_000; i++) {
            long d = b.delayMillis(10);
            max = Math.max(max, d);
            min = Math.min(min, d);
        }
        assertThat(max).isLessThanOrEqualTo(5_000).isGreaterThan(4_000);
        assertThat(min).isLessThan(500);
        assertThat(b.delayMillis(0)).isBetween(0L, 100L);
        for (int i = 0; i < 1000; i++) {
            assertThat(Backoff.jittered(Duration.ofSeconds(2), 0.2)).isBetween(1600L, 2400L);
        }
    }
}
