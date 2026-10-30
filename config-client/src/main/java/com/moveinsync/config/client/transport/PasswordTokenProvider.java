package com.moveinsync.config.client.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Logs in with service credentials and caches the token until shortly before it expires. */
public final class PasswordTokenProvider implements TokenProvider {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final URI tokenUri;
    private final String username;
    private final String password;
    private volatile String token;
    private volatile long expiresAtMillis;

    public PasswordTokenProvider(HttpClient http, String baseUrl, String username, String password) {
        this.http = http;
        this.tokenUri = URI.create(baseUrl + "/api/v1/auth/token");
        this.username = username;
        this.password = password;
    }

    @Override
    public synchronized String token() throws IOException {
        if (token != null && System.currentTimeMillis() < expiresAtMillis) {
            return token;
        }
        HttpRequest req = HttpRequest.newBuilder(tokenUri)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("username", username, "password", password))))
                .build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        if (res.statusCode() != 200) {
            throw new HttpStatusException(res.statusCode(), 0);
        }
        JsonNode body = JSON.readTree(res.body());
        token = body.get("accessToken").asText();
        long ttl = body.get("expiresIn").asLong();
        expiresAtMillis = System.currentTimeMillis() + Math.max(10, ttl - 60) * 1000;
        return token;
    }

    @Override
    public synchronized void invalidate() {
        token = null;
    }
}
