package com.moveinsync.config.bench;

import com.moveinsync.config.client.ConfigClient;
import com.moveinsync.config.client.transport.ConfigTransport;
import com.moveinsync.config.core.DemoCatalog;
import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.Op;
import com.moveinsync.config.core.model.ResolutionContext;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.core.wire.Wire;
import com.moveinsync.config.core.wire.WireCodec;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Local read cost on a realistically sized snapshot: {@code clients} tenants x 5 offices x 2 shifts,
 * 200 extra NUMBER settings, overrides at every level (about 30k overrides for 300 clients).
 *
 * <pre>java -jar benchmarks/target/benchmarks.jar</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class LocalReadBenchmark {

    @Param({"300"})
    public int clients;

    Snapshot snapshot;
    ConfigClient client;
    ResolutionContext shiftCtx;      // value set at SHIFT level: found at the first lookup
    ResolutionContext defaultCtx;    // nothing overridden for this key: walks all four levels to the default
    ChangeEvent nextChange;
    long counter;

    @Setup(Level.Trial)
    public void setup() {
        List<Op> ops = new ArrayList<>(DemoCatalog.bootstrapOps());
        for (int k = 0; k < 200; k++) {
            ops.add(new Op.DefineSetting("tune.k" + k, "NUMBER", Map.of("min", "0", "max", "100000"), "10", ""));
        }
        Snapshot s = Snapshot.empty().applyOps(1, ops);
        List<Op> overrides = new ArrayList<>();
        for (int c = 0; c < clients; c++) {
            String client = "c" + c;
            for (int k = 0; k < 50; k++) {
                overrides.add(new Op.SetOverride(com.moveinsync.config.core.model.Scope.client(client), "tune.k" + k, String.valueOf(c + k)));
            }
            overrides.add(new Op.SetOverride(com.moveinsync.config.core.model.Scope.client(client), DemoCatalog.BOOKING_CUTOFF, "PT45M"));
            for (int o = 0; o < 5; o++) {
                var office = com.moveinsync.config.core.model.Scope.office(client, "o" + o);
                overrides.add(new Op.SetOverride(office, DemoCatalog.BOOKING_CUTOFF, "PT30M"));
                for (int k = 50; k < 60; k++) {
                    overrides.add(new Op.SetOverride(office, "tune.k" + k, "7"));
                }
                for (int sh = 0; sh < 2; sh++) {
                    overrides.add(new Op.SetOverride(com.moveinsync.config.core.model.Scope.shift(client, "o" + o, "s" + sh),
                            DemoCatalog.BOOKING_CUTOFF, "PT20M"));
                }
            }
        }
        snapshot = s.applyOps(2, overrides);
        shiftCtx = ResolutionContext.of("c150", "o3", "s1");
        defaultCtx = ResolutionContext.of("c150", "o3", "s1");
        client = ConfigClient.builder().transport(new StaticTransport(snapshot)).build().start();
        nextChange = new ChangeEvent(3, 0, ChangeKind.APPLY,
                List.of(new Op.SetOverride(com.moveinsync.config.core.model.Scope.office("c7", "o1"), DemoCatalog.BOOKING_CUTOFF, "PT25M")));
        System.out.println("snapshot overrides=" + snapshot.overrideCount() + " definitions=" + snapshot.definitions().size());
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        client.close();
    }

    /** Value at SHIFT level (first hop). */
    @Benchmark
    public Object resolveShiftLevel() {
        return snapshot.resolve(shiftCtx, DemoCatalog.BOOKING_CUTOFF).value();
    }

    /** Key overridden nowhere for this context: four levels of misses, then the default. */
    @Benchmark
    public Object resolveFallThroughToDefault() {
        return snapshot.resolve(defaultCtx, "tune.k199").value();
    }

    /** What a service does per request: pin a view and read two related keys. */
    @Benchmark
    public long clientViewTwoKeys() {
        var view = client.view(shiftCtx);
        return view.time(DemoCatalog.NIGHT_START).toSecondOfDay() + view.time(DemoCatalog.NIGHT_END).toSecondOfDay();
    }

    /** Same, from 4 threads at once (reads take no lock). */
    @Benchmark
    @Threads(4)
    public Duration clientViewConcurrent4Threads() {
        return client.view(shiftCtx).duration(DemoCatalog.BOOKING_CUTOFF);
    }

    /** Cost of applying one pushed change to the large snapshot (copy-on-write of the touched key). */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public Snapshot applyOneChange() {
        return snapshot.apply(nextChange);
    }

    private record StaticTransport(Snapshot snapshot) implements ConfigTransport {
        @Override
        public long fetchVersion() {
            return snapshot.version();
        }

        @Override
        public Wire.Delta fetchDelta(long since) {
            return new Wire.Delta(since, snapshot.version(), true, List.of(), WireCodec.toWire(snapshot));
        }

        @Override
        public void stream(long since, StreamListener listener, StreamHandle handle) throws IOException {
            throw new IOException("offline benchmark");
        }
    }
}
