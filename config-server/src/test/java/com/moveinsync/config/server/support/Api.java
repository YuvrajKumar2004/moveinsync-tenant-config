package com.moveinsync.config.server.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Minimal JSON HTTP client for integration tests. */
public final class Api {

    public static final Map<String, String> PASSWORDS = Map.of(
            "operator", "REDACTED",
            "acme-admin", "REDACTED",
            "globex-admin", "REDACTED",
            "acme-employee", "REDACTED",
            "booking-service", "REDACTED",
            "acme-service", "REDACTED");

    public static final ObjectMapper JSON = new ObjectMapper();

    private final String base;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    public record Response(int status, JsonNode body, String raw, Map<String, List<String>> headers) {
        public JsonNode json(String field) {
            return body.get(field);
        }
    }

    public Api(String base) {
        this.base = base;
    }

    public String token(String user) {
        Response r = post("/api/v1/auth/token", null, Map.of("username", user, "password", PASSWORDS.get(user)));
        if (r.status() != 200) {
            throw new IllegalStateException("login failed for " + user + ": " + r.raw());
        }
        return r.body().get("accessToken").asText();
    }

    public Response get(String path, String token) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET(), token);
    }

    public Response post(String path, String token, Object body) {
        return post(path, token, body, Map.of());
    }

    public Response post(String path, String token, Object body, Map<String, String> headers) {
        try {
            String json = body instanceof String s ? s : JSON.writeValueAsString(body);
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            headers.forEach(b::header);
            return send(b, token);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public Response put(String path, String token, Object body) {
        try {
            return send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))), token);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Response send(HttpRequest.Builder b, String token) {
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<String> res = http.send(b.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node;
            try {
                node = res.body() == null || res.body().isBlank() ? JSON.nullNode() : JSON.readTree(res.body());
            } catch (IOException notJson) {
                node = JSON.nullNode();
            }
            return new Response(res.statusCode(), node, res.body(), res.headers().map());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Body of a single SET change set. */
    public static Map<String, Object> set(String reason, String level, String clientId, String officeId, String shiftId,
                                          String key, String value) {
        return Map.of("reason", reason, "ops", List.of(op("SET", level, clientId, officeId, shiftId, key, value)));
    }

    public static Map<String, Object> op(String op, String level, String clientId, String officeId, String shiftId,
                                         String key, String value) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("op", op);
        m.put("level", level);
        if (clientId != null) m.put("clientId", clientId);
        if (officeId != null) m.put("officeId", officeId);
        if (shiftId != null) m.put("shiftId", shiftId);
        m.put("key", key);
        if (value != null) m.put("value", value);
        return m;
    }
}
