package com.moveinsync.booking;

import com.moveinsync.config.client.ConfigClient;
import com.moveinsync.config.core.model.ResolutionContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Example consumer. Every request reads the cut-off from the embedded snapshot: no network call, and it
 * keeps working while the config server is down.
 */
@RestController
public class BookingController {

    private final ConfigClient config;
    private final BookingPolicy policy = new BookingPolicy();

    public BookingController(ConfigClient config) {
        this.config = config;
    }

    /** No personal data: the booking is identified only by where and when. */
    public record BookingRequest(String clientId, String officeId, String shiftId, Integer minutesBeforeDeparture) {
    }

    @PostMapping("/bookings")
    public ResponseEntity<BookingPolicy.Decision> book(@RequestBody BookingRequest req) {
        if (req.clientId() == null || req.minutesBeforeDeparture() == null || req.minutesBeforeDeparture() < 0) {
            throw new IllegalArgumentException("clientId and a non-negative minutesBeforeDeparture are required");
        }
        var view = config.view(ResolutionContext.of(req.clientId(), req.officeId(), req.shiftId()));
        BookingPolicy.Decision d = policy.decide(view, Duration.ofMinutes(req.minutesBeforeDeparture()));
        return ResponseEntity.status(d.accepted() ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY).body(d);
    }

    @GetMapping("/config/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", config.version());
        m.put("streamConnected", config.streamConnected());
        m.put("metrics", config.metrics().asMap());
        return m;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> bad(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("code", "BAD_REQUEST", "detail", e.getMessage()));
    }
}
