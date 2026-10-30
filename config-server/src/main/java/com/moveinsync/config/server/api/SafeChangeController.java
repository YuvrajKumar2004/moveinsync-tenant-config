package com.moveinsync.config.server.api;

import com.moveinsync.config.core.change.Op;
import com.moveinsync.config.core.wire.WireCodec;
import com.moveinsync.config.server.api.dto.Dtos;
import com.moveinsync.config.server.security.CurrentUser;
import com.moveinsync.config.server.service.RollbackService;
import com.moveinsync.config.server.service.RolloutService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Staged rollouts (stage, promote, abort) and scoped rollback. */
@RestController
@RequestMapping("/api/v1")
public class SafeChangeController {

    private final RolloutService rollouts;
    private final RollbackService rollbacks;

    public SafeChangeController(RolloutService rollouts, RollbackService rollbacks) {
        this.rollouts = rollouts;
        this.rollbacks = rollbacks;
    }

    @GetMapping("/rollouts")
    public List<Dtos.RolloutView> list() {
        return rollouts.list(CurrentUser.get()).stream()
                .map(r -> new Dtos.RolloutView(r.version(), r.targets().stream().sorted().toList(),
                        r.items().stream().map(i -> WireCodec.entry(i.scope(), i.key(), i.value())).toList()))
                .toList();
    }

    @PostMapping("/rollouts")
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.ChangeSetResponse stage(@Valid @RequestBody Dtos.StageRequest req,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Set<String> targets = new LinkedHashSet<>();
        req.targets().forEach(t -> targets.add(t.key()));
        List<Op.SetOverride> items = req.items().stream().map(Dtos.OpRequest::toSet).toList();
        return ChangeSetController.toResponse(rollouts.stage(CurrentUser.get(), targets, items, req.reason(),
                ChangeSetController.checkKey(idempotencyKey)));
    }

    @PostMapping("/rollouts/{version}/promote")
    public Dtos.ChangeSetResponse promote(@PathVariable long version, @Valid @RequestBody Dtos.ReasonRequest req) {
        return ChangeSetController.toResponse(rollouts.promote(CurrentUser.get(), version, req.reason()));
    }

    @PostMapping("/rollouts/{version}/abort")
    public Dtos.ChangeSetResponse abort(@PathVariable long version, @Valid @RequestBody Dtos.ReasonRequest req) {
        return ChangeSetController.toResponse(rollouts.abort(CurrentUser.get(), version, req.reason()));
    }

    @PostMapping("/rollback")
    public RollbackService.Result rollback(@Valid @RequestBody Dtos.RollbackRequest req) {
        return rollbacks.rollback(CurrentUser.get(), req.scope(), req.toVersion(), req.reason(), req.dryRun());
    }
}
