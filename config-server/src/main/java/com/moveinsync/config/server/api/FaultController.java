package com.moveinsync.config.server.api;

import com.moveinsync.config.server.error.ApiException;
import com.moveinsync.config.server.service.FaultInjector;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Failure injection for tests and the demo. Returns 404 unless app.faults.enabled=true. OPERATOR only. */
@RestController
@RequestMapping("/api/v1/admin/faults")
public class FaultController {

    private final FaultInjector faults;

    public FaultController(FaultInjector faults) {
        this.faults = faults;
    }

    @GetMapping
    public FaultInjector.Faults get() {
        requireEnabled();
        return faults.get();
    }

    @PutMapping
    public FaultInjector.Faults set(@RequestBody FaultInjector.Faults f) {
        requireEnabled();
        faults.set(f);
        return faults.get();
    }

    private void requireEnabled() {
        if (!faults.enabled()) {
            throw ApiException.notFound("endpoint");
        }
    }
}
