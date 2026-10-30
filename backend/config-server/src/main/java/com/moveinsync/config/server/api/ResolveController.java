package com.moveinsync.config.server.api;

import com.moveinsync.config.core.model.ResolutionContext;
import com.moveinsync.config.core.snapshot.Resolution;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.server.api.dto.Dtos;
import com.moveinsync.config.server.security.AuthUser;
import com.moveinsync.config.server.security.CurrentUser;
import com.moveinsync.config.server.service.AccessPolicy;
import com.moveinsync.config.server.service.ConfigStateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.TreeSet;

/**
 * Resolved values for a context, with explain mode (which level supplied each value). This is for
 * humans and debugging; services read through the embedded client instead.
 */
@RestController
@RequestMapping("/api/v1")
public class ResolveController {

    private final ConfigStateService state;
    private final AccessPolicy access;

    public ResolveController(ConfigStateService state, AccessPolicy access) {
        this.state = state;
        this.access = access;
    }

    @GetMapping("/resolve")
    public Dtos.ResolveResponse resolve(@RequestParam(required = false) String clientId,
                                        @RequestParam(required = false) String officeId,
                                        @RequestParam(required = false) String shiftId,
                                        @RequestParam(required = false) List<String> key,
                                        @RequestParam(defaultValue = "false") boolean explain) {
        AuthUser user = CurrentUser.get();
        ResolutionContext ctx = ResolutionContext.of(clientId, officeId, shiftId);
        access.checkCanRead(user, ctx.clientId());
        Snapshot s = state.current();
        List<String> keys = key == null || key.isEmpty() ? List.copyOf(new TreeSet<>(s.definitions().keySet())) : key;
        List<Dtos.ResolvedValue> values = keys.stream().map(k -> {
            Resolution r = s.explain(ctx, k);
            return explain
                    ? new Dtos.ResolvedValue(k, r.value().raw(), r.level().name(), r.scope().toString(), r.source().name(), r.rolloutVersion())
                    : new Dtos.ResolvedValue(k, r.value().raw(), null, null, null, null);
        }).toList();
        return new Dtos.ResolveResponse(s.version(), ctx.toString(), values);
    }
}
