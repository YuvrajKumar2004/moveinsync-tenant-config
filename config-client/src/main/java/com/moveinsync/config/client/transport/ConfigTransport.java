package com.moveinsync.config.client.transport;

import com.moveinsync.config.core.wire.Wire;

import java.io.IOException;

/** How the client talks to the server. The HTTP implementation is the default; tests use fakes. */
public interface ConfigTransport extends AutoCloseable {

    /** Current server version and history id (cheap; used by the periodic check). */
    Wire.ServerVersion fetchVersion() throws IOException;

    /** Everything after {@code since}: contiguous events or a full snapshot. */
    Wire.Delta fetchDelta(long since) throws IOException;

    /**
     * Opens the push stream starting after {@code since} and blocks, delivering events to the listener,
     * until the stream ends or fails (then returns or throws). {@link StreamHandle#close()} ends it early.
     */
    void stream(long since, StreamListener listener, StreamHandle handle) throws IOException;

    @Override
    default void close() {
    }

    interface StreamListener {
        void onChange(Wire.Event event);

        void onHeartbeat(long serverVersion, String historyId);

        void onResync(long serverVersion);
    }

    /** Lets another thread cancel a blocking {@link #stream} call. */
    final class StreamHandle {
        private volatile AutoCloseable resource;
        private volatile boolean closed;

        public void attach(AutoCloseable r) {
            this.resource = r;
            if (closed) {
                closeQuietly();
            }
        }

        public void close() {
            closed = true;
            closeQuietly();
        }

        public boolean isClosed() {
            return closed;
        }

        private void closeQuietly() {
            AutoCloseable r = resource;
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                    // best effort
                }
            }
        }
    }
}
