package com.moveinsync.booking;

import com.moveinsync.config.client.ClientMetrics;
import com.moveinsync.config.client.ConfigClient;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** Embeds the config client and exposes its counters as Micrometer gauges (config_client_*). */
@Configuration
public class ConfigClientConfiguration {

    @Bean(destroyMethod = "close")
    ConfigClient configClient(@Value("${config.server-url}") String url,
                              @Value("${config.username}") String user,
                              @Value("${config.password}") String password,
                              @Value("${config.snapshot-file}") String snapshotFile,
                              @Value("${config.poll-interval:PT2S}") Duration poll) {
        return ConfigClient.builder()
                .server(url, user, password)
                .snapshotFile(Path.of(snapshotFile))
                .pollInterval(poll)
                .name("booking")
                .build()
                .start();
    }

    @Bean
    Object configClientMetrics(ConfigClient client, MeterRegistry registry) {
        ClientMetrics m = client.metrics();
        Gauge.builder("config.client.version", client, ConfigClient::version).register(registry);
        Gauge.builder("config.client.stream.connected", client, c -> c.streamConnected() ? 1 : 0).register(registry);
        for (String name : m.asMap().keySet()) {
            Gauge.builder("config.client." + name.replace('_', '.'), m, x -> valueOf(x.asMap(), name)).register(registry);
        }
        return m;
    }

    private static double valueOf(Map<String, Long> map, String key) {
        Long v = map.get(key);
        return v == null ? Double.NaN : v;
    }
}
