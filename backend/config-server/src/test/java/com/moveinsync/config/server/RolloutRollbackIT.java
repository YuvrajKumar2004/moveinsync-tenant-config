package com.moveinsync.config.server;

import com.moveinsync.config.server.support.Api;
import com.moveinsync.config.server.support.ServerHarness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Staged rollout to two offices, promotion, abort, and scoped rollback with a diff. */
class RolloutRollbackIT {

    static ServerHarness server;
    static Api api;
    static String operator, acmeAdmin;

    @BeforeAll
    static void start() {
        server = new ServerHarness().start();
        api = new Api(server.baseUrl());
        operator = api.token("operator");
        acmeAdmin = api.token("acme-admin");
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private String cutoff(String client, String office) {
        return api.get("/api/v1/resolve?clientId=" + client + "&officeId=" + office + "&key=ride.max-duration&explain=true", operator)
                .json("values").get(0).get("value").asText();
    }

    @Test
    void stageToTwoOfficesThenPromoteToEveryone() {
        Api.Response staged = api.post("/api/v1/rollouts", operator, Map.of("reason", "pilot longer rides",
                "targets", List.of(Map.of("clientId", "acme", "officeId", "blr-1"), Map.of("clientId", "globex", "officeId", "pune-1")),
                "items", List.of(Api.op("SET", "GLOBAL", null, null, null, "ride.max-duration", "100m"))));
        assertThat(staged.status()).isEqualTo(201);
        long rollout = staged.json("version").asLong();

        assertThat(cutoff("acme", "blr-1")).isEqualTo("PT1H40M");
        assertThat(cutoff("globex", "pune-1")).isEqualTo("PT1H40M");
        assertThat(cutoff("acme", "blr-2")).isEqualTo("PT1H30M");
        var explain = api.get("/api/v1/resolve?clientId=acme&officeId=blr-1&key=ride.max-duration&explain=true", operator)
                .json("values").get(0);
        assertThat(explain.get("source").asText()).isEqualTo("STAGED");
        assertThat(explain.get("rolloutVersion").asLong()).isEqualTo(rollout);
        assertThat(api.get("/api/v1/rollouts", operator).body()).hasSize(1);

        // a plain change to the staged key/scope is refused while the rollout is in progress
        assertThat(api.post("/api/v1/change-sets", operator,
                Api.set("x", "GLOBAL", null, null, null, "ride.max-duration", "80m")).status()).isEqualTo(422);

        Api.Response promoted = api.post("/api/v1/rollouts/" + rollout + "/promote", operator, Map.of("reason", "pilot ok"));
        assertThat(promoted.status()).isEqualTo(200);
        assertThat(promoted.json("kind").asText()).isEqualTo("PROMOTE");
        assertThat(cutoff("acme", "blr-2")).isEqualTo("PT1H40M");
        assertThat(cutoff("initech", "x-1")).isEqualTo("PT1H40M");
        assertThat(api.get("/api/v1/rollouts", operator).body()).isEmpty();
        assertThat(api.post("/api/v1/rollouts/" + rollout + "/promote", operator, Map.of("reason", "again")).status()).isEqualTo(404);
    }

    @Test
    void adminCanStageAndAbortInsideOwnTenant() {
        Api.Response staged = api.post("/api/v1/rollouts", acmeAdmin, Map.of("reason", "try escort rule",
                "targets", List.of(Map.of("clientId", "acme", "officeId", "hyd-1"), Map.of("clientId", "acme", "officeId", "hyd-2")),
                "items", List.of(Api.op("SET", "CLIENT", "acme", null, null, "escort.rule", "ALWAYS"))));
        assertThat(staged.status()).isEqualTo(201);
        long rollout = staged.json("version").asLong();
        assertThat(api.get("/api/v1/resolve?clientId=acme&officeId=hyd-1&key=escort.rule", acmeAdmin)
                .json("values").get(0).get("value").asText()).isEqualTo("ALWAYS");
        assertThat(api.post("/api/v1/rollouts/" + rollout + "/abort", acmeAdmin, Map.of("reason", "not now")).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/resolve?clientId=acme&officeId=hyd-1&key=escort.rule", acmeAdmin)
                .json("values").get(0).get("value").asText()).isEqualTo("FEMALE_FIRST_OR_LAST");
    }

    @Test
    void rollbackOneClientToAnEarlierVersionWithDiff() {
        long v0 = api.post("/api/v1/change-sets", operator,
                Api.set("baseline", "OFFICE", "initech", "x-1", null, "ride.max-pool-size", "2")).json("version").asLong();
        api.post("/api/v1/change-sets", operator, Map.of("reason", "bad change", "ops", List.of(
                Api.op("SET", "OFFICE", "initech", "x-1", null, "ride.max-pool-size", "6"),
                Api.op("SET", "CLIENT", "initech", null, null, "cab.types-allowed", "TEMPO"))));
        api.post("/api/v1/change-sets", operator, Api.set("other tenant", "CLIENT", "umbrella", null, null, "ride.max-pool-size", "1"));

        Api.Response preview = api.post("/api/v1/rollback", operator, Map.of("level", "CLIENT", "clientId", "initech",
                "toVersion", v0, "reason", "undo", "dryRun", true));
        assertThat(preview.status()).isEqualTo(200);
        assertThat(preview.json("newVersion")).isNull();
        assertThat(preview.json("diff")).hasSize(2);
        assertThat(preview.raw()).contains("\"from\":\"6\"", "\"to\":\"2\"", "TEMPO");

        Api.Response done = api.post("/api/v1/rollback", operator, Map.of("level", "CLIENT", "clientId", "initech",
                "toVersion", v0, "reason", "undo"));
        long newVersion = done.json("newVersion").asLong();
        assertThat(newVersion).isGreaterThan(v0 + 2); // a rollback moves forward
        assertThat(api.get("/api/v1/change-sets/" + newVersion, operator).json("kind").asText()).isEqualTo("ROLLBACK");
        assertThat(api.get("/api/v1/resolve?clientId=initech&officeId=x-1&key=ride.max-pool-size", operator)
                .json("values").get(0).get("value").asText()).isEqualTo("2");
        assertThat(api.get("/api/v1/resolve?clientId=umbrella&key=ride.max-pool-size", operator)
                .json("values").get(0).get("value").asText()).isEqualTo("1");

        assertThat(api.post("/api/v1/rollback", operator, Map.of("level", "CLIENT", "clientId", "initech",
                "toVersion", 999999, "reason", "x")).status()).isEqualTo(400);
    }
}
