package com.moveinsync.config.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.config.server.support.Api;
import com.moveinsync.config.server.support.ServerHarness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Role-based access and tenant isolation: one client can never read or change another client's data. */
class TenantIsolationIT {

    static ServerHarness server;
    static Api api;
    static String operator, acmeAdmin, globexAdmin, acmeEmployee, acmeService, bookingService;

    @BeforeAll
    static void start() {
        server = new ServerHarness().start();
        api = new Api(server.baseUrl());
        operator = api.token("operator");
        acmeAdmin = api.token("acme-admin");
        globexAdmin = api.token("globex-admin");
        acmeEmployee = api.token("acme-employee");
        acmeService = api.token("acme-service");
        bookingService = api.token("booking-service");
        assertThat(api.post("/api/v1/change-sets", globexAdmin,
                Api.set("globex secret", "OFFICE", "globex", "pune-1", null, "booking.cutoff", "17m")).status()).isEqualTo(201);
        assertThat(api.post("/api/v1/change-sets", acmeAdmin,
                Api.set("acme value", "OFFICE", "acme", "blr-1", null, "booking.cutoff", "30m")).status()).isEqualTo(201);
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @Test
    void adminCannotWriteAnotherTenantOrGlobalOrDefinitions() {
        Api.Response other = api.post("/api/v1/change-sets", acmeAdmin,
                Api.set("x", "OFFICE", "globex", "pune-1", null, "booking.cutoff", "5m"));
        assertThat(other.status()).isEqualTo(403);
        Api.Response global = api.post("/api/v1/change-sets", acmeAdmin,
                Api.set("x", "GLOBAL", null, null, null, "booking.cutoff", "5m"));
        assertThat(global.status()).isEqualTo(403);
        Api.Response mixed = api.post("/api/v1/change-sets", acmeAdmin, Map.of("reason", "x", "ops", List.of(
                Api.op("SET", "OFFICE", "acme", "blr-1", null, "ride.max-pool-size", "3"),
                Api.op("SET", "OFFICE", "globex", "pune-1", null, "ride.max-pool-size", "3"))));
        assertThat(mixed.status()).isEqualTo(403);
        Api.Response define = api.post("/api/v1/definitions", acmeAdmin, Map.of("key", "x.y", "type", "NUMBER",
                "defaultValue", "1", "reason", "x"));
        assertThat(define.status()).isEqualTo(403);
        Api.Response stage = api.post("/api/v1/rollouts", acmeAdmin, Map.of("reason", "x",
                "targets", List.of(Map.of("clientId", "globex", "officeId", "pune-1")),
                "items", List.of(Api.op("SET", "GLOBAL", null, null, null, "booking.cutoff", "5m"))));
        assertThat(stage.status()).isEqualTo(403);
        assertThat(api.post("/api/v1/rollback", acmeAdmin, Map.of("level", "GLOBAL", "toVersion", 1, "reason", "x")).status())
                .isEqualTo(403);
        assertThat(api.post("/api/v1/rollback", acmeAdmin, Map.of("level", "CLIENT", "clientId", "globex", "toVersion", 1,
                "reason", "x")).status()).isEqualTo(403);
    }

    @Test
    void employeeIsReadOnlyAndOwnTenantOnly() {
        assertThat(api.get("/api/v1/resolve?clientId=acme&officeId=blr-1&key=booking.cutoff", acmeEmployee).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/resolve?clientId=globex&officeId=pune-1&key=booking.cutoff", acmeEmployee).status()).isEqualTo(403);
        assertThat(api.post("/api/v1/change-sets", acmeEmployee,
                Api.set("x", "OFFICE", "acme", "blr-1", null, "booking.cutoff", "5m")).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/change-sets", acmeEmployee).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/sync/delta?since=0", acmeEmployee).status()).isEqualTo(403);
    }

    @Test
    void adminHistoryAndRolloutsOnlyShowOwnTenant() {
        Api.Response h = api.get("/api/v1/change-sets?limit=200", acmeAdmin);
        assertThat(h.status()).isEqualTo(200);
        assertThat(h.raw()).doesNotContain("globex").doesNotContain("PT17M");
        Api.Response other = api.get("/api/v1/resolve?clientId=globex&key=booking.cutoff", acmeAdmin);
        assertThat(other.status()).isEqualTo(403);
    }

    @Test
    void serviceSnapshotsAndDeltasAreFilteredByTenant() {
        Api.Response acmeOnly = api.get("/api/v1/sync/delta?since=0", acmeService);
        assertThat(acmeOnly.status()).isEqualTo(200);
        JsonNode overrides = acmeOnly.json("snapshot").get("overrides");
        assertThat(overrides.findValuesAsText("clientId")).contains("acme").doesNotContain("globex");
        assertThat(acmeOnly.raw()).doesNotContain("pune-1");

        Api.Response events = api.get("/api/v1/sync/delta?since=1", acmeService);
        assertThat(events.json("full").asBoolean()).isFalse();
        assertThat(events.raw()).doesNotContain("globex");
        assertThat(events.json("events")).hasSize((int) (events.json("toVersion").asLong() - 1)); // gap-free even when filtered

        Api.Response all = api.get("/api/v1/sync/delta?since=0", bookingService);
        assertThat(all.raw()).contains("globex");
        assertThat(api.get("/api/v1/sync/delta?since=0", acmeAdmin).status()).isEqualTo(403);
    }

    @Test
    void faultEndpointIsOperatorOnly() {
        assertThat(api.get("/api/v1/admin/faults", acmeAdmin).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/admin/faults", operator).status()).isEqualTo(200);
        assertThat(api.get("/actuator/metrics", acmeAdmin).status()).isEqualTo(403);
    }
}
