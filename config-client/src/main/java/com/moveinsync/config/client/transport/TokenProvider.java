package com.moveinsync.config.client.transport;

import java.io.IOException;

/** Supplies the bearer token; {@link #invalidate()} is called after a 401 so the next call refreshes it. */
public interface TokenProvider {

    String token() throws IOException;

    default void invalidate() {
    }

    static TokenProvider fixed(String token) {
        return () -> token;
    }
}
