package com.moveinsync.config.client;

import com.moveinsync.config.client.transport.ConfigTransport;
import com.moveinsync.config.core.DemoCatalog;
import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.ChangeSetValidator;
import com.moveinsync.config.core.change.Op;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.core.wire.Wire;
import com.moveinsync.config.core.wire.WireCodec;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory server for client unit tests, with switchable faults. */
final class FakeServer implements ConfigTransport {

    private final ChangeSetValidator validator = new ChangeSetValidator();
    private final List<ChangeEvent> log = new CopyOnWriteArrayList<>();
    private final Set<StreamListener> streams = ConcurrentHashMap.newKeySet();
    volatile Snapshot state = Snapshot.empty();
    volatile boolean down;
    volatile boolean dropPushes;

    FakeServer() {
        commit(DemoCatalog.bootstrapOps().toArray(Op[]::new));
    }

    synchronized ChangeEvent commit(Op... ops) {
        Snapshot next = validator.validate(state, List.of(ops));
        ChangeEvent e = new ChangeEvent(next.version(), System.currentTimeMillis(), ChangeKind.APPLY, List.of(ops));
        state = next;
        log.add(e);
        if (!dropPushes && !down) {
            streams.forEach(l -> l.onChange(WireCodec.toWire(e)));
        }
        return e;
    }

    int openStreams() {
        return streams.size();
    }

    @Override
    public long fetchVersion() throws IOException {
        failIfDown();
        return state.version();
    }

    @Override
    public Wire.Delta fetchDelta(long since) throws IOException {
        failIfDown();
        Snapshot s = state;
        if (since == 0 || since > s.version()) {
            return new Wire.Delta(since, s.version(), true, List.of(), WireCodec.toWire(s));
        }
        List<Wire.Event> events = log.stream().filter(e -> e.version() > since && e.version() <= s.version())
                .map(WireCodec::toWire).toList();
        return new Wire.Delta(since, s.version(), false, events, null);
    }

    @Override
    public void stream(long since, StreamListener listener, StreamHandle handle) throws IOException {
        failIfDown();
        for (ChangeEvent e : log) {
            if (e.version() > since) {
                listener.onChange(WireCodec.toWire(e));
            }
        }
        listener.onHeartbeat(state.version());
        streams.add(listener);
        try {
            while (!handle.isClosed()) {
                if (down) {
                    throw new IOException("connection reset");
                }
                Thread.sleep(10);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            streams.remove(listener);
        }
    }

    private void failIfDown() throws IOException {
        if (down) {
            throw new IOException("connection refused");
        }
    }
}
