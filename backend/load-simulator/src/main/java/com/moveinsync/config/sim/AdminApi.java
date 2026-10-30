package com.moveinsync.config.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.config.client.transport.HttpConfigTransport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Tiny operator-side HTTP helper used by the simulator to publish changes. */
public final class AdminApi {

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).build();
    private final String base;
    private final String token;

    public AdminApi(String base, String username, String password) throws IOException {
        this.base = base;
        JsonNode r = post("/api/v1/auth/token", null, Map.of("username", username, "password", password));
        this.token = r.get("accessToken").asText();
    }

    public String token() {
        return token;
    }

    /** Sets one OFFICE-level value and returns the committed version. */
    public long setOffice(String clientId, String officeId, String key, String value) throws IOException {
        Map<String, Object> op = Map.of("op", "SET", "level", "OFFICE", "clientId", clientId, "officeId", officeId,
                "key", key, "value", value);
        return post("/api/v1/change-sets", token, Map.of("reason", "simulator", "ops", List.of(op))).get("version").asLong();
    }

    public JsonNode post(String path, String bearer, Object body) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(HttpConfigTransport.JSON.writeValueAsString(body)));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        try {
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + res.statusCode() + " for " + path);
            }
            return HttpConfigTransport.JSON.readTree(res.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }
}
