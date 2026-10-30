package com.moveinsync.config.server;

import com.moveinsync.config.server.observability.ConfigMetrics;
import com.moveinsync.config.server.service.SubscriptionHub;
import com.moveinsync.config.server.support.Api;
import com.moveinsync.config.server.support.Measurements;
import com.moveinsync.config.server.support.ServerHarness;
import com.moveinsync.config.sim.AdminApi;
import com.moveinsync.config.sim.Experiments;
import com.moveinsync.config.sim.Fleet;
import com.moveinsync.config.sim.Stats;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scale proofs from the case study. Tagged "scale" (run with -Pscale) because they open hundreds of
 * connections and take a minute.
 */
@Tag("scale")
class ScaleIT {

    @Test
    void propagationTo200SimulatedClientsIsUnderFiveSeconds() throws Exception {
        try (ServerHarness server = new ServerHarness().start()) {
            Api api = new Api(server.baseUrl());
            AdminApi admin = new AdminApi(server.baseUrl(), "operator", Api.OPERATOR_PASSWORD);
            try (Fleet fleet = new Fleet(server.baseUrl(), api.token("booking-service"), Fleet.Options.defaults(200)).start()) {
                long deadline = System.currentTimeMillis() + 15_000;
                while (fleet.connectedStreams() < 200 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50);
                }
                assertThat(fleet.connectedStreams()).as(fleet::describeDisconnected).isEqualTo(200);
                Experiments.PropagationReport r = Experiments.propagation(fleet, admin, 20, Duration.ofSeconds(10));
                Measurements.record(r.describe());
                assertThat(r.missed()).isZero();
                assertThat(r.perClientMillis().max()).isLessThan(5_000);
            }
        }
    }

    /**
     * 500 clients are connected, the server is killed and restarted. Reconnects must be spread by jitter,
     * be served as small deltas (not full snapshots) and every client must catch up, without server errors.
     */
    @Test
    void reconnectStormOf500ClientsAfterServerRestart() throws Exception {
        try (ServerHarness server = new ServerHarness().start()) {
            Api api = new Api(server.baseUrl());
            String token = api.token("booking-service");
            Fleet.Options opts = new Fleet.Options(500, Duration.ofSeconds(2), Duration.ofMillis(250), Duration.ofSeconds(10), 10);
            try (Fleet fleet = new Fleet(server.baseUrl(), token, opts).start()) {
                long deadline = System.currentTimeMillis() + 30_000;
                while (fleet.connectedStreams() < 500 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50);
                }
                assertThat(fleet.connectedStreams()).as(fleet::describeDisconnected).isEqualTo(500);
                long fullBefore = fleet.sum(c -> c.metrics().fullSnapshots());

                server.kill();
                Thread.sleep(3_000);
                long t0 = System.nanoTime();
                server.start();
                String op = api.token("operator");
                Api.Response r = api.post("/api/v1/change-sets", op,
                        Api.set("after restart", "OFFICE", "acme", "blr-1", null, "booking.cutoff", "30m"));
                long v = r.json("version").asLong();

                boolean all = fleet.awaitVersion(v, Duration.ofSeconds(20));
                double caughtUpMs = (System.nanoTime() - t0) / 1e6;
                deadline = System.currentTimeMillis() + 30_000;
                while (fleet.connectedStreams() < 500 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50);
                }
                double reconnectedMs = (System.nanoTime() - t0) / 1e6;
                List<Double> visible = fleet.visibilityMillis(v, t0);
                Stats spread = Stats.of(visible);

                MeterRegistry reg = server.bean(MeterRegistry.class);
                double fullServed = count(reg, "config.sync.delta", "type", "full");
                double eventsServed = count(reg, "config.sync.delta", "type", "events");
                double emptyServed = count(reg, "config.sync.delta", "type", "empty");
                double rejected = reg.find("config.stream.handshake.rejected").counters().stream().mapToDouble(c -> c.count()).sum();
                double server5xx = reg.find("http.server.requests").timers().stream()
                        .filter(t -> t.getId().getTag("status") != null && t.getId().getTag("status").startsWith("5")
                                && !"503".equals(t.getId().getTag("status")))
                        .mapToDouble(t -> t.count()).sum();
                long fullAfter = fleet.sum(c -> c.metrics().fullSnapshots());

                Measurements.record(String.format("reconnect storm: 500 clients, server down 3s; all caught up=%s in %.0f ms; "
                                + "all streams back in %.0f ms; visibility after restart %s; server sync answers: events=%.0f empty=%.0f "
                                + "full=%.0f; client full snapshots during storm=%d; handshake rejections=%.0f; 5xx (non-503)=%.0f; subscribers=%d",
                        all, caughtUpMs, reconnectedMs, spread.format("ms"), eventsServed, emptyServed, fullServed,
                        fullAfter - fullBefore, rejected, server5xx, server.bean(SubscriptionHub.class).subscriberCount()));
                assertThat(all).isTrue();
                assertThat(fleet.connectedStreams()).as(fleet::describeDisconnected).isEqualTo(500);
                assertThat(fullAfter - fullBefore).as("clients resumed from their version, not full snapshots").isZero();
                assertThat(server5xx).isZero();
                assertThat(server.bean(ConfigMetrics.class)).isNotNull();
            }
        }
    }

    private static double count(MeterRegistry reg, String name, String tag, String value) {
        var c = reg.find(name).tag(tag, value).counter();
        return c == null ? 0 : c.count();
    }
}
