package com.moveinsync.config.server;

import com.moveinsync.config.server.support.Api;
import com.moveinsync.config.server.support.Measurements;
import com.moveinsync.config.server.support.ServerHarness;
import com.moveinsync.config.sim.Experiments;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small HTTP load test against the real server + PostgreSQL: throughput and p50/p99 for the explain
 * endpoint (read) and for change-set commits (write path: validate, row lock, insert, publish).
 * Client and server share one machine, so the numbers are a conservative lower bound.
 */
@Tag("scale")
class LoadIT {

    @Test
    void readAndWriteThroughputWithLatencyPercentiles() throws Exception {
        try (ServerHarness server = new ServerHarness("--logging.level.http=WARN").start()) {
            Api api = new Api(server.baseUrl());
            String service = api.token("booking-service");
            String operator = api.token("operator");

            Experiments.httpLoad("warmup", 16, Duration.ofSeconds(5), Experiments.resolveRequests(server.baseUrl(), service));
            Experiments.LoadReport reads = Experiments.httpLoad("GET /api/v1/resolve (explain)", 32, Duration.ofSeconds(15),
                    Experiments.resolveRequests(server.baseUrl(), service));
            Measurements.record(reads.describe());

            Experiments.httpLoad("warmup", 4, Duration.ofSeconds(3), Experiments.writeRequests(server.baseUrl(), operator));
            Experiments.LoadReport writes = Experiments.httpLoad("POST /api/v1/change-sets", 8, Duration.ofSeconds(10),
                    Experiments.writeRequests(server.baseUrl(), operator));
            Measurements.record(writes.describe());

            assertThat(reads.errors()).isZero();
            assertThat(writes.errors()).isZero();
            assertThat(reads.throughput()).isGreaterThan(100);
        }
    }
}
