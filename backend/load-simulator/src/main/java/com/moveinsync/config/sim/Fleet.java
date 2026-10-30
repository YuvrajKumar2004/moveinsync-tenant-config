package com.moveinsync.config.sim;

import com.moveinsync.config.client.ConfigClient;
import com.moveinsync.config.client.transport.HttpConfigTransport;
import com.moveinsync.config.client.transport.TokenProvider;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * N embedded clients in one JVM, each with its own SSE stream and poller, recording when every version
 * became visible to it. Used for propagation measurements and the reconnect storm.
 */
public final class Fleet implements AutoCloseable {

    public record Options(int clients, Duration pollInterval, Duration backoffBase, Duration backoffCap, int httpClients) {
        public static Options defaults(int clients) {
            return new Options(clients, Duration.ofSeconds(2), Duration.ofMillis(250), Duration.ofSeconds(10),
                    Math.max(1, clients / 50));
        }
    }

    private final List<ConfigClient> clients = new ArrayList<>();
    /** per client: version -> System.nanoTime() when first visible. */
    private final List<ConcurrentSkipListMap<Long, Long>> seen = new ArrayList<>();

    public Fleet(String serverUrl, String token, Options options) {
        List<HttpClient> https = new ArrayList<>();
        for (int i = 0; i < options.httpClients(); i++) {
            https.add(HttpConfigTransport.defaultClient());
        }
        for (int i = 0; i < options.clients(); i++) {
            ConcurrentSkipListMap<Long, Long> times = new ConcurrentSkipListMap<>();
            ConfigClient c = ConfigClient.builder()
                    .server(serverUrl, TokenProvider.fixed(token))
                    .httpClient(https.get(i % https.size()))
                    .pollInterval(options.pollInterval())
                    .backoff(options.backoffBase(), options.backoffCap())
                    .name("sim-" + i)
                    .build();
            c.addListener(s -> times.putIfAbsent(s.version(), System.nanoTime()));
            clients.add(c);
            seen.add(times);
        }
    }

    /** Starts every client (in parallel) and records the initial version time. */
    public Fleet start() throws Exception {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < clients.size(); i++) {
                int idx = i;
                fs.add(pool.submit(() -> {
                    clients.get(idx).start();
                    seen.get(idx).putIfAbsent(clients.get(idx).version(), System.nanoTime());
                    return null;
                }));
            }
            for (Future<?> f : fs) {
                f.get();
            }
        }
        return this;
    }

    public List<ConfigClient> clients() {
        return clients;
    }

    public int size() {
        return clients.size();
    }

    public boolean allAtLeast(long version) {
        return clients.stream().allMatch(c -> c.version() >= version);
    }

    public long countAtLeast(long version) {
        return clients.stream().filter(c -> c.version() >= version).count();
    }

    public long connectedStreams() {
        return clients.stream().filter(ConfigClient::streamConnected).count();
    }

    /** Blocks until every client has {@code version} or the timeout passes; returns whether all did. */
    public boolean awaitVersion(long version, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (allAtLeast(version)) {
                return true;
            }
            Thread.sleep(5);
        }
        return allAtLeast(version);
    }

    /** Milliseconds from {@code startNanos} until each client first saw a version >= {@code version}. */
    public List<Double> visibilityMillis(long version, long startNanos) {
        List<Double> out = new ArrayList<>();
        for (ConcurrentSkipListMap<Long, Long> times : seen) {
            Map.Entry<Long, Long> e = times.ceilingEntry(version);
            if (e != null) {
                out.add((e.getValue() - startNanos) / 1_000_000.0);
            }
        }
        return out;
    }

    /** Metrics of up to three clients whose stream is not connected, for failure messages. */
    public String describeDisconnected() {
        return clients.stream().filter(c -> !c.streamConnected()).limit(3)
                .map(c -> "v" + c.version() + " " + c.metrics().asMap()).toList().toString();
    }

    public long sum(java.util.function.ToLongFunction<ConfigClient> f) {
        return clients.stream().mapToLong(f).sum();
    }

    @Override
    public void close() {
        clients.parallelStream().forEach(ConfigClient::close);
    }
}
