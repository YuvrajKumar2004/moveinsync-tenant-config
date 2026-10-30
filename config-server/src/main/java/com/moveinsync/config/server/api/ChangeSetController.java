package com.moveinsync.config.server.api;

import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.Op;
import com.moveinsync.config.core.snapshot.TenantFilter;
import com.moveinsync.config.core.wire.Wire;
import com.moveinsync.config.server.api.dto.Dtos;
import com.moveinsync.config.server.error.ApiException;
import com.moveinsync.config.server.repository.ChangeSetRecord;
import com.moveinsync.config.server.repository.ChangeSetRepository;
import com.moveinsync.config.server.security.AuthUser;
import com.moveinsync.config.server.security.CurrentUser;
import com.moveinsync.config.server.security.Role;
import com.moveinsync.config.server.service.ChangeSetService;
import com.moveinsync.config.server.service.EventJson;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Atomic change sets and the audit history. */
@RestController
@RequestMapping("/api/v1")
public class ChangeSetController {

    private final ChangeSetService changes;
    private final ChangeSetRepository repository;
    private final EventJson json;

    public ChangeSetController(ChangeSetService changes, ChangeSetRepository repository, EventJson json) {
        this.changes = changes;
        this.repository = repository;
        this.json = json;
    }

    @PostMapping("/change-sets")
    public ResponseEntity<Dtos.ChangeSetResponse> commit(@Valid @RequestBody Dtos.ChangeSetRequest req,
                                                         @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        List<Op> ops = req.ops().stream().map(Dtos.OpRequest::toOp).toList();
        ChangeSetService.CommitResult r = changes.commit(new ChangeSetService.CommitRequest(ChangeKind.APPLY,
                CurrentUser.get(), req.reason(), ops, checkKey(idempotencyKey), req.expectedVersion()));
        return ResponseEntity.status(r.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(toResponse(r));
    }

    @GetMapping("/change-sets")
    public List<Dtos.HistoryEntry> history(@RequestParam(required = false) Long before,
                                           @RequestParam(defaultValue = "50") int limit) {
        AuthUser user = CurrentUser.get();
        String tenant = user.role() == Role.OPERATOR ? null : user.tenant();
        int n = Math.max(1, Math.min(limit, 200));
        return repository.history(tenant, before == null ? Long.MAX_VALUE : before, n).stream()
                .map(r -> toHistory(r, user.readFilter()))
                .toList();
    }

    @GetMapping("/change-sets/{version}")
    public Dtos.HistoryEntry get(@PathVariable long version) {
        AuthUser user = CurrentUser.get();
        ChangeSetRecord r = repository.findByVersion(version).orElseThrow(() -> ApiException.notFound("change set " + version));
        if (user.role() != Role.OPERATOR && !r.tenants().contains("," + user.tenant() + ",")) {
            throw ApiException.notFound("change set " + version);
        }
        return toHistory(r, user.readFilter());
    }

    static Dtos.ChangeSetResponse toResponse(ChangeSetService.CommitResult r) {
        return new Dtos.ChangeSetResponse(r.version(), r.kind().name(), r.committedAt(), r.author(), r.reason(), r.diff(), r.replayed());
    }

    /** Ops and diff entries of other tenants are removed before returning history to a tenant user. */
    private Dtos.HistoryEntry toHistory(ChangeSetRecord r, TenantFilter filter) {
        List<Wire.Op> ops = json.wireOps(r.opsJson()).stream().filter(op -> visible(op, filter)).toList();
        var diff = json.diff(r.diffJson()).stream().filter(d -> filter.allows(d.scope())).toList();
        return new Dtos.HistoryEntry(r.version(), r.kind(), r.committedAt(), r.author(), r.reason(), ops, diff);
    }

    private static boolean visible(Wire.Op op, TenantFilter filter) {
        if (filter.all()) {
            return true;
        }
        if ("STAGE".equals(op.type())) {
            return op.items().stream().allMatch(i -> i.clientId() == null || filter.allowsTenant(i.clientId()))
                    && op.targets().stream().allMatch(t -> filter.allowsTenant(t.substring(0, t.indexOf('/'))));
        }
        return op.clientId() == null || filter.allowsTenant(op.clientId());
    }

    static String checkKey(String key) {
        if (key == null) {
            return null;
        }
        if (!key.matches("[A-Za-z0-9._:-]{1,100}")) {
            throw ApiException.badRequest("Idempotency-Key must match [A-Za-z0-9._:-]{1,100}");
        }
        return key;
    }
}
