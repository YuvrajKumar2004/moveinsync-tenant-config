package com.moveinsync.config.sim;

import com.moveinsync.config.client.transport.HttpConfigTransport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/** Measurements shared by the CLI and the automated tests. */
public final class Experiments {

    private Experiments() {
    }

    public record PropagationReport(int clients, int rounds, Stats perClientMillis, Stats worstPerRoundMillis, long missed) {
        public String describe() {
            return String.format(Locale.ROOT, "propagation to %d clients over %d changes: per-client %s | worst-per-change %s | missed=%d",
                    clients, rounds, perClientMillis.format("ms"), worstPerRoundMillis.format("ms"), missed);
        }
    }

    /**
     * Publishes {@code rounds} changes one at a time and measures, for each client, the time from just
     * before the commit request was sent until the client could read the new version.
     */
    public static PropagationReport propagation(Fleet fleet, AdminApi admin, int rounds, Duration timeout) throws Exception {
        List<Double> all = new ArrayList<>();
        List<Double> worst = new ArrayList<>();
        long missed = 0;
        for (int r = 0; r < rounds; r++) {
            long t0 = System.nanoTime();
            long v = admin.setOffice("acme", "sim-" + (r % 7), "ride.max-pool-size", String.valueOf(1 + r % 6));
            boolean ok = fleet.awaitVersion(v, timeout);
            List<Double> times = fleet.visibilityMillis(v, t0);
            missed += fleet.size() - times.size() + (ok ? 0 : 1);
            all.addAll(times);
            worst.add(times.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN));
            Thread.sleep(100);
        }
        return new PropagationReport(fleet.size(), rounds, Stats.of(all), Stats.of(worst), missed);
    }

    public record LoadReport(String name, int threads, double seconds, long requests, long errors, double throughput, Stats latencyMillis) {
        public String describe() {
            return String.format(Locale.ROOT, "%s: threads=%d duration=%.1fs requests=%d errors=%d throughput=%.0f req/s latency %s",
                    name, threads, seconds, requests, errors, throughput, latencyMillis.format("ms"));
        }
    }

    /** Closed-loop HTTP load: {@code threads} workers issue requests back to back for {@code duration}. */
    public static LoadReport httpLoad(String name, int threads, Duration duration, RequestFactory factory) throws Exception {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        long end = System.nanoTime() + duration.toNanos();
        AtomicLong errors = new AtomicLong();
        List<List<Double>> perThread = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<List<Double>>> fs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int worker = t;
                fs.add(pool.submit(() -> {
                    List<Double> lat = new ArrayList<>();
                    long i = 0;
                    while (System.nanoTime() < end) {
                        HttpRequest req = factory.create(worker, i++);
                        long s = System.nanoTime();
                        try {
                            HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
                            if (res.statusCode() / 100 != 2) {
                                errors.incrementAndGet();
                            }
                        } catch (IOException e) {
                            errors.incrementAndGet();
                        }
                        lat.add((System.nanoTime() - s) / 1_000_000.0);
                    }
                    return lat;
                }));
            }
            for (Future<List<Double>> f : fs) {
                perThread.add(f.get());
            }
        }
        List<Double> all = new ArrayList<>();
        perThread.forEach(all::addAll);
        double secs = duration.toMillis() / 1000.0;
        return new LoadReport(name, threads, secs, all.size(), errors.get(), all.size() / secs, Stats.of(all));
    }

    public interface RequestFactory {
        HttpRequest create(int worker, long iteration) throws IOException;
    }

    public static RequestFactory resolveRequests(String base, String token) {
        return (w, i) -> HttpRequest.newBuilder(URI.create(base + "/api/v1/resolve?clientId=acme&officeId=blr-" + (i % 20)
                        + "&key=booking.cutoff&explain=true"))
                .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(10)).GET().build();
    }

    public static RequestFactory writeRequests(String base, String token) {
        return (w, i) -> HttpRequest.newBuilder(URI.create(base + "/api/v1/change-sets"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(HttpConfigTransport.JSON.writeValueAsString(java.util.Map.of(
                        "reason", "load test", "ops", List.of(java.util.Map.of("op", "SET", "level", "OFFICE", "clientId", "loadtest",
                                "officeId", "w" + w, "key", "ride.max-pool-size", "value", String.valueOf(1 + i % 6)))))))
                .build();
    }
}
