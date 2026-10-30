package com.moveinsync.config.server.api;

import com.moveinsync.config.core.wire.Wire;
import com.moveinsync.config.server.error.ApiException;
import com.moveinsync.config.server.security.CurrentUser;
import com.moveinsync.config.server.service.SubscriptionHub;
import com.moveinsync.config.server.service.SyncService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/** Endpoints used by the embedded client library. */
@RestController
@RequestMapping("/api/v1/sync")
public class SyncController {

    private final SyncService sync;
    private final SubscriptionHub hub;

    public SyncController(SyncService sync, SubscriptionHub hub) {
        this.sync = sync;
        this.hub = hub;
    }

    /** Cheap periodic check that heals missed pushes. */
    @GetMapping("/version")
    public Map<String, Long> version() {
        return Map.of("version", sync.version());
    }

    @GetMapping("/delta")
    public Wire.Delta delta(@RequestParam long since) {
        return sync.delta(CurrentUser.get(), since);
    }

    @GetMapping("/snapshot")
    public Wire.SnapshotData snapshot() {
        return sync.snapshot(CurrentUser.get());
    }

    /** SSE stream starting after {@code since} (or the standard Last-Event-ID header). */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) Long since,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        long from;
        try {
            from = since != null ? since : lastEventId != null ? Long.parseLong(lastEventId) : 0;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Last-Event-ID must be a version number");
        }
        return hub.subscribe(CurrentUser.get(), from);
    }
}
