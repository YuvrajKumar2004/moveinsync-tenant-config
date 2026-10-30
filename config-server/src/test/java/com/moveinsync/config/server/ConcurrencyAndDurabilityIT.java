package com.moveinsync.config.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.config.server.support.Api;
import com.moveinsync.config.server.support.ServerHarness;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyAndDurabilityIT {

    /** 16 writers committing concurrently produce unique, gap-free, increasing versions. */
    @Test
    void concurrentWritersGetGapFreeVersions() throws Exception {
        try (ServerHarness server = new ServerHarness().start()) {
            Api api = new Api(server.baseUrl());
            String token = api.token("operator");
            int writers = 16, perWriter = 15;
            List<Long> versions = Collections.synchronizedList(new ArrayList<>());
            try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
                List<Future<?>> fs = new ArrayList<>();
                for (int w = 0; w < writers; w++) {
                    int writer = w;
                    fs.add(pool.submit(() -> {
                        for (int i = 0; i < perWriter; i++) {
                            Api.Response r = api.post("/api/v1/change-sets", token, Api.set("w" + writer,
                                    "OFFICE", "acme", "o-" + writer, null, "ride.max-pool-size", String.valueOf(1 + i % 6)));
                            assertThat(r.status()).as(r.raw()).isEqualTo(201);
                            versions.add(r.json("version").asLong());
                        }
                        return null;
                    }));
                }
                for (Future<?> f : fs) {
                    f.get();
                }
            }
            List<Long> sorted = new ArrayList<>(versions);
            Collections.sort(sorted);
            assertThat(sorted).doesNotHaveDuplicates().hasSize(writers * perWriter);
            assertThat(sorted.get(0)).isEqualTo(2);
            assertThat(sorted.get(sorted.size() - 1)).isEqualTo(1 + writers * perWriter);
        }
    }

    /** Committed changes survive a crash: the server rebuilds its state from the durable log. */
    @Test
    void committedChangesSurviveRestart() {
        try (ServerHarness server = new ServerHarness().start()) {
            Api api = new Api(server.baseUrl());
            String token = api.token("operator");
            api.post("/api/v1/change-sets", token, Api.set("durable", "OFFICE", "acme", "blr-1", null, "booking.cutoff", "30m"));
            long before = api.get("/api/v1/sync/version", token).json("version").asLong();
            JsonNode snapshotBefore = api.get("/api/v1/sync/snapshot", token).body();

            server.restart();

            String token2 = api.token("operator"); // the signing key is configuration, so old tokens would also still work
            assertThat(api.get("/api/v1/sync/version", token).json("version").asLong()).isEqualTo(before);
            assertThat(api.get("/api/v1/sync/snapshot", token2).body()).isEqualTo(snapshotBefore);
            assertThat(api.get("/api/v1/resolve?clientId=acme&officeId=blr-1&key=booking.cutoff", token2)
                    .json("values").get(0).get("value").asText()).isEqualTo("PT30M");
            // and the version sequence continues without reuse
            assertThat(api.post("/api/v1/change-sets", token2, Api.set("after restart", "OFFICE", "acme", "blr-1", null,
                    "booking.cutoff", "20m")).json("version").asLong()).isEqualTo(before + 1);
        }
    }
}
