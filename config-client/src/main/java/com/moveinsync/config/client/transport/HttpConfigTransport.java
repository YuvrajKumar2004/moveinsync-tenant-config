package com.moveinsync.config.client.transport;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.config.core.wire.Wire;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.stream.Stream;

/**
 * HTTP/1.1 transport: JSON for version/delta, server-sent events for push. The SSE parser handles the
 * subset the server emits: {@code event}, {@code id}, {@code data} lines and blank-line dispatch.
 */
public final class HttpConfigTransport implements ConfigTransport {

    public static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final HttpClient http;
    private final String base;
    private final TokenProvider tokens;
    private final Duration requestTimeout;

    public HttpConfigTransport(HttpClient http, String baseUrl, TokenProvider tokens, Duration requestTimeout) {
        this.http = http;
        this.base = baseUrl;
        this.tokens = tokens;
        this.requestTimeout = requestTimeout;
    }

    public static HttpClient defaultClient() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public long fetchVersion() throws IOException {
        return getJson("/api/v1/sync/version", JsonNode.class).get("version").asLong();
    }

    @Override
    public Wire.Delta fetchDelta(long since) throws IOException {
        return getJson("/api/v1/sync/delta?since=" + since, Wire.Delta.class);
    }

    private <T> T getJson(String path, Class<T> type) throws IOException {
        HttpRequest req = request(path).timeout(requestTimeout).header("Accept", "application/json").GET().build();
        HttpResponse<byte[]> res = send(req, HttpResponse.BodyHandlers.ofByteArray());
        check(res);
        return JSON.readValue(res.body(), type);
    }

    @Override
    public void stream(long since, StreamListener listener, StreamHandle handle) throws IOException {
        // the timeout covers the handshake (until response headers); the body itself streams indefinitely
        HttpRequest req = request("/api/v1/sync/stream?since=" + since).timeout(requestTimeout)
                .header("Accept", "text/event-stream").GET().build();
        HttpResponse<Stream<String>> res = send(req, HttpResponse.BodyHandlers.ofLines());
        try (Stream<String> lines = res.body()) {
            check(res);
            handle.attach(lines);
            String event = "message";
            StringBuilder data = new StringBuilder();
            Iterator<String> it = lines.iterator();
            while (!handle.isClosed() && it.hasNext()) {
                String line = it.next();
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        dispatch(event, data.toString(), listener);
                    }
                    event = "message";
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).stripLeading());
                }
                // "id:" is implied by the event's own version; ":" lines are comments
            }
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static void dispatch(String event, String data, StreamListener listener) throws IOException {
        switch (event) {
            case "change" -> listener.onChange(JSON.readValue(data, Wire.Event.class));
            case "heartbeat" -> listener.onHeartbeat(JSON.readTree(data).get("version").asLong());
            case "resync" -> listener.onResync(JSON.readTree(data).get("version").asLong());
            default -> {
                // unknown events are ignored for forward compatibility
            }
        }
    }

    private HttpRequest.Builder request(String path) throws IOException {
        return HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", "Bearer " + tokens.token());
    }

    private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return http.send(req, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    private void check(HttpResponse<?> res) throws HttpStatusException {
        int s = res.statusCode();
        if (s / 100 == 2) {
            return;
        }
        if (s == 401) {
            tokens.invalidate();
        }
        long retryAfter = res.headers().firstValue("Retry-After").map(v -> {
            try {
                return Long.parseLong(v.trim()) * 1000;
            } catch (NumberFormatException e) {
                return 1000L;
            }
        }).orElse(0L);
        throw new HttpStatusException(s, retryAfter);
    }
}
