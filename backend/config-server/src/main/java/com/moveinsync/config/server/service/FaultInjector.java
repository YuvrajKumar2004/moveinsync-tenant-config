package com.moveinsync.config.server.service;

import com.moveinsync.config.server.config.AppProperties;
import org.springframework.stereotype.Component;

/**
 * Switchable faults for failure-injection tests and the live demo. All off by default, and the HTTP
 * endpoint that flips them exists only when {@code app.faults.enabled=true}.
 *
 * <ul>
 *   <li>dropPushes - committed events are not pushed (simulates lost messages / crash before publish)</li>
 *   <li>duplicatePushes - every pushed event is sent twice</li>
 *   <li>outOfOrderPushes - after each event, the previous event is sent again (old version arriving late)</li>
 * </ul>
 */
@Component
public class FaultInjector {

    public record Faults(boolean dropPushes, boolean duplicatePushes, boolean outOfOrderPushes) {
        public static final Faults NONE = new Faults(false, false, false);
    }

    private final boolean enabled;
    private volatile Faults faults = Faults.NONE;

    public FaultInjector(AppProperties props) {
        this.enabled = props.faults() != null && props.faults().enabled();
    }

    public boolean enabled() {
        return enabled;
    }

    public Faults get() {
        return faults;
    }

    public void set(Faults f) {
        this.faults = f == null ? Faults.NONE : f;
    }
}
