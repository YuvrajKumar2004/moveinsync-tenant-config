package com.moveinsync.config.core.snapshot;

import com.moveinsync.config.core.model.ResolutionContext;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.moveinsync.config.core.DemoCatalog.NIGHT_END;
import static com.moveinsync.config.core.DemoCatalog.NIGHT_START;
import static com.moveinsync.config.core.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class SnapshotHolderTest {

    @Test
    void versionsOnlyMoveForward() {
        SnapshotHolder h = new SnapshotHolder(base()); // v1
        assertThat(h.offer(event(2, set(BLR1, "booking.cutoff", "PT30M")))).isEqualTo(SnapshotHolder.Outcome.APPLIED);
        assertThat(h.offer(event(3, set(BLR1, "booking.cutoff", "PT20M")))).isEqualTo(SnapshotHolder.Outcome.APPLIED);
        // duplicate of 3 and an old 2 arriving late are both dropped
        assertThat(h.offer(event(3, set(BLR1, "booking.cutoff", "PT99M")))).isEqualTo(SnapshotHolder.Outcome.STALE);
        assertThat(h.offer(event(2, set(BLR1, "booking.cutoff", "PT10M")))).isEqualTo(SnapshotHolder.Outcome.STALE);
        assertThat(h.current().override(BLR1, "booking.cutoff").raw()).isEqualTo("PT20M");
        // 5 after 3 means 4 was missed: not applied, caller must fetch the delta
        assertThat(h.offer(event(5, set(BLR1, "booking.cutoff", "PT5M")))).isEqualTo(SnapshotHolder.Outcome.GAP);
        assertThat(h.version()).isEqualTo(3);
        assertThat(h.replaceIfNewer(Snapshot.empty())).isFalse();

        // switching to another server history installs that state even though its version is lower
        h.reset(base());
        assertThat(h.version()).isEqualTo(1);
        assertThat(h.offer(event(2, set(BLR1, "booking.cutoff", "PT35M")))).isEqualTo(SnapshotHolder.Outcome.APPLIED);
    }

    /**
     * The atomic change-set guarantee: readers loop continuously while a writer flips the night window
     * between two valid states (start and end changed together). No reader may ever see the new start
     * with the old end.
     */
    @Test
    void continuousReadersNeverSeeAMixedNightWindow() throws Exception {
        List<String[]> states = List.of(new String[]{"20:00", "06:00"}, new String[]{"22:00", "04:00"});
        SnapshotHolder holder = new SnapshotHolder(commit(base(), set(ACME, NIGHT_START, "20:00"), set(ACME, NIGHT_END, "06:00")));
        ResolutionContext ctx = ResolutionContext.of("acme", "blr-1", "night");
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<String> mixed = new AtomicReference<>();
        AtomicLong reads = new AtomicLong();
        int readers = 4;
        CountDownLatch started = new CountDownLatch(readers);
        Thread[] threads = new Thread[readers];
        for (int i = 0; i < readers; i++) {
            threads[i] = new Thread(() -> {
                started.countDown();
                while (!stop.get()) {
                    Snapshot s = holder.current(); // pinned view
                    LocalTime start = (LocalTime) s.resolve(ctx, NIGHT_START).value();
                    LocalTime end = (LocalTime) s.resolve(ctx, NIGHT_END).value();
                    String pair = start + "-" + end;
                    if (!pair.equals("20:00-06:00") && !pair.equals("22:00-04:00")) {
                        mixed.compareAndSet(null, pair);
                    }
                    reads.incrementAndGet();
                }
            });
            threads[i].start();
        }
        started.await();
        for (int i = 0; i < 2_000; i++) {
            String[] next = states.get((i + 1) % 2);
            long v = holder.version() + 1;
            assertThat(holder.offer(event(v, set(ACME, NIGHT_START, next[0]), set(ACME, NIGHT_END, next[1]))))
                    .isEqualTo(SnapshotHolder.Outcome.APPLIED);
        }
        stop.set(true);
        for (Thread t : threads) {
            t.join();
        }
        assertThat(mixed.get()).as("mixed state observed").isNull();
        assertThat(reads.get()).isGreaterThan(2_000);
        assertThat(holder.version()).isEqualTo(2_002);
    }
}
