package com.moveinsync.config.server;

import com.moveinsync.config.server.support.Api;
import com.moveinsync.config.server.support.ServerHarness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** REST API + persistence: change sets, explain, validation, conflicts, idempotency, history. */
class ApiIT {

    static ServerHarness server;
    static Api api;
    static String operator;

    @BeforeAll
    static void start() {
        server = new ServerHarness().start();
        api = new Api(server.baseUrl());
        operator = api.token("operator");
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private long version() {
        return api.get("/api/v1/sync/version", operator).body().get("version").asLong();
    }

    @Test
    void authenticationIsRequiredAndBadCredentialsAreRejectedGenerically() {
        assertThat(api.get("/api/v1/definitions", null).status()).isEqualTo(401);
        assertThat(api.get("/api/v1/definitions", "not-a-token").status()).isEqualTo(401);
        Api.Response bad = api.post("/api/v1/auth/token", null, Map.of("username", "operator", "password", "wrong"));
        assertThat(bad.status()).isEqualTo(401);
        assertThat(bad.json("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        Api.Response unknown = api.post("/api/v1/auth/token", null, Map.of("username", "nobody", "password", "x"));
        assertThat(unknown.json("detail").asText()).isEqualTo(bad.json("detail").asText());
    }

    @Test
    void bootstrapCatalogIsVersionOne() {
        Api.Response r = api.get("/api/v1/definitions", operator);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("definitions").findValuesAsText("key")).contains("booking.cutoff", "night.window.start");
        Api.Response v1 = api.get("/api/v1/change-sets/1", operator);
        assertThat(v1.json("kind").asText()).isEqualTo("BOOTSTRAP");
    }

    @Test
    void bengaluruOfficeCutoffChangeAppliesToThatOfficeOnlyAndIsExplained() {
        Api.Response c = api.post("/api/v1/change-sets", operator,
                Api.set("cut-off trial for blr-1", "OFFICE", "acme", "blr-1", null, "booking.cutoff", "30m"));
        assertThat(c.status()).isEqualTo(201);
        assertThat(c.json("diff").get(0).get("to").asText()).isEqualTo("PT30M");
        assertThat(c.json("author").asText()).isEqualTo("operator");

        Api.Response blr1 = api.get("/api/v1/resolve?clientId=acme&officeId=blr-1&key=booking.cutoff&explain=true", operator);
        assertThat(blr1.json("values").get(0).get("value").asText()).isEqualTo("PT30M");
        assertThat(blr1.json("values").get(0).get("level").asText()).isEqualTo("OFFICE");
        assertThat(blr1.json("values").get(0).get("source").asText()).isEqualTo("OVERRIDE");

        Api.Response blr2 = api.get("/api/v1/resolve?clientId=acme&officeId=blr-2&key=booking.cutoff&explain=true", operator);
        assertThat(blr2.json("values").get(0).get("value").asText()).isEqualTo("PT1H");
        assertThat(blr2.json("values").get(0).get("source").asText()).isEqualTo("DEFAULT");
    }

    @Test
    void invalidValuesAreRejectedAndNothingIsPublished() {
        long before = version();
        Api.Response r = api.post("/api/v1/change-sets", operator,
                Api.set("bad", "OFFICE", "acme", "blr-1", null, "booking.cutoff", "-30m"));
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.json("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(r.json("violations").get(0).get("code").asText()).isEqualTo("INVALID_VALUE");
        assertThat(r.json("requestId")).isNotNull();

        Api.Response window = api.post("/api/v1/change-sets", operator,
                Api.set("bad window", "OFFICE", "acme", "blr-1", null, "escort.window", "06:00-22:00"));
        assertThat(window.status()).isEqualTo(422);

        // half a night-window change is rejected because start and end are validated together
        Api.Response half = api.post("/api/v1/change-sets", operator,
                Api.set("half", "CLIENT", "acme", null, null, "night.window.start", "07:00"));
        assertThat(half.status()).isEqualTo(422);
        assertThat(half.json("violations").get(0).get("code").asText()).isEqualTo("CONSTRAINT_VIOLATION");
        assertThat(version()).isEqualTo(before);

        Api.Response both = api.post("/api/v1/change-sets", operator, Map.of("reason", "both ends", "ops", List.of(
                Api.op("SET", "CLIENT", "acme", null, null, "night.window.start", "22:00"),
                Api.op("SET", "CLIENT", "acme", null, null, "night.window.end", "05:00"))));
        assertThat(both.status()).isEqualTo(201);
        assertThat(both.json("version").asLong()).isEqualTo(before + 1);
    }

    @Test
    void optimisticConcurrencyAndIdempotency() {
        long v = version();
        Api.Response stale = api.post("/api/v1/change-sets", operator, Map.of("reason", "stale", "expectedVersion", v - 1,
                "ops", List.of(Api.op("SET", "CLIENT", "acme", null, null, "ride.max-pool-size", "3"))));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.json("code").asText()).isEqualTo("VERSION_CONFLICT");

        var body = Api.set("idempotent", "CLIENT", "acme", null, null, "ride.max-pool-size", "2");
        Api.Response first = api.post("/api/v1/change-sets", operator, body, Map.of("Idempotency-Key", "req-123"));
        Api.Response retry = api.post("/api/v1/change-sets", operator, body, Map.of("Idempotency-Key", "req-123"));
        assertThat(first.status()).isEqualTo(201);
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.json("replayed").asBoolean()).isTrue();
        assertThat(retry.json("version").asLong()).isEqualTo(first.json("version").asLong());
    }

    @Test
    void historyRecordsAuthorReasonAndIncreasingVersions() {
        api.post("/api/v1/change-sets", operator, Api.set("history check", "CLIENT", "acme", null, null, "cab.types-allowed", "SUV"));
        Api.Response h = api.get("/api/v1/change-sets?limit=5", operator);
        assertThat(h.status()).isEqualTo(200);
        long previous = Long.MAX_VALUE;
        for (var e : h.body()) {
            assertThat(e.get("version").asLong()).isLessThan(previous);
            assertThat(e.get("author").asText()).isNotBlank();
            assertThat(e.get("reason").asText()).isNotBlank();
            previous = e.get("version").asLong();
        }
    }

    @Test
    void malformedRequestsGetGenericErrorsWithoutInternals() {
        Api.Response r = api.post("/api/v1/change-sets", operator, "{not json");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.json("code").asText()).isEqualTo("MALFORMED_REQUEST");
        assertThat(r.raw()).doesNotContain("Exception").doesNotContain("jackson").doesNotContain("at com.");

        Api.Response badLevel = api.post("/api/v1/change-sets", operator, Api.set("x", "PLANET", "acme", null, null, "booking.cutoff", "1m"));
        assertThat(badLevel.status()).isEqualTo(400);
        assertThat(badLevel.raw()).doesNotContain("No enum constant");

        Api.Response missingReason = api.post("/api/v1/change-sets", operator, Map.of("ops", List.of()));
        assertThat(missingReason.status()).isEqualTo(400);

        assertThat(api.get("/api/v1/resolve?key=nope", operator).status()).isEqualTo(404);
    }

    @Test
    void piiInReasonsIsNotEchoedInErrors() {
        Api.Response r = api.post("/api/v1/change-sets", operator,
                Api.set("requested by priya@acme.com +91 98765 43210", "OFFICE", "acme", "blr-1", null, "booking.cutoff", "-5m"));
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.raw()).doesNotContain("priya@acme.com").doesNotContain("98765");
    }

    @Test
    void metricsExposeOnCallCounters() {
        api.post("/api/v1/change-sets", operator, Api.set("bad", "OFFICE", "acme", "blr-1", null, "booking.cutoff", "-1m"));
        Api.Response m = api.get("/actuator/prometheus", null);
        assertThat(m.status()).isEqualTo(200);
        assertThat(m.raw()).contains("config_version_current", "config_changes_rejected_total", "config_stream_subscribers",
                "config_stream_queue_depth", "config_changes_committed_total");
    }
}
