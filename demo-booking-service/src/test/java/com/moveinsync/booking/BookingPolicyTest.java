package com.moveinsync.booking;

import com.moveinsync.config.client.ConfigClient;
import com.moveinsync.config.client.transport.ConfigTransport;
import com.moveinsync.config.core.DemoCatalog;
import com.moveinsync.config.core.change.ChangeSetValidator;
import com.moveinsync.config.core.change.Op;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.core.wire.Wire;
import com.moveinsync.config.core.wire.WireCodec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The case-study example: blr-1 cut-off 60 -> 30 minutes; a booking 45 minutes ahead is accepted there only. */
class BookingPolicyTest {

    @Test
    void bookingFortyFiveMinutesAheadIsAcceptedOnlyWhereTheCutoffIsThirty() {
        List<Op> ops = new ArrayList<>(DemoCatalog.bootstrapOps());
        Snapshot base = new ChangeSetValidator().validate(Snapshot.empty(), ops);
        Snapshot s = new ChangeSetValidator().validate(base, List.of(new Op.SetOverride(Scope.office("acme", "blr-1"), "booking.cutoff", "30m")));

        try (ConfigClient client = ConfigClient.builder().transport(new StaticTransport(s)).build().start()) {
            BookingPolicy policy = new BookingPolicy();
            var blr1 = policy.decide(client.view("acme", "blr-1", null), Duration.ofMinutes(45));
            var blr2 = policy.decide(client.view("acme", "blr-2", null), Duration.ofMinutes(45));
            assertThat(blr1.accepted()).isTrue();
            assertThat(blr1.cutoff()).isEqualTo(Duration.ofMinutes(30));
            assertThat(blr1.cutoffLevel()).isEqualTo("OFFICE");
            assertThat(blr2.accepted()).isFalse();
            assertThat(blr2.cutoff()).isEqualTo(Duration.ofMinutes(60));
            assertThat(policy.decide(client.view("acme", "blr-2", null), Duration.ofMinutes(60)).accepted()).isTrue();
        }
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
            throw new IOException("no push in this test");
        }
    }
}
