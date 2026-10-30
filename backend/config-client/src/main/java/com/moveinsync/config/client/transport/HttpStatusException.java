package com.moveinsync.config.client.transport;

import java.io.IOException;

/** A non-2xx answer. {@code retryAfterMillis} is set when the server asked us to back off (503). */
public class HttpStatusException extends IOException {

    private final int status;
    private final long retryAfterMillis;

    public HttpStatusException(int status, long retryAfterMillis) {
        super("HTTP " + status);
        this.status = status;
        this.retryAfterMillis = retryAfterMillis;
    }

    public int status() {
        return status;
    }

    public long retryAfterMillis() {
        return retryAfterMillis;
    }
}
