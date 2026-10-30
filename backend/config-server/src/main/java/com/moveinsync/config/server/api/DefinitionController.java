package com.moveinsync.config.server.api;

import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.Op;
import com.moveinsync.config.core.wire.Wire;
import com.moveinsync.config.core.wire.WireCodec;
import com.moveinsync.config.server.api.dto.Dtos;
import com.moveinsync.config.server.security.CurrentUser;
import com.moveinsync.config.server.service.ChangeSetService;
import com.moveinsync.config.server.service.ConfigStateService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Setting definitions and cross-key constraints. Changing them is a versioned change set too. */
@RestController
@RequestMapping("/api/v1")
public class DefinitionController {

    private final ChangeSetService changes;
    private final ConfigStateService state;

    public DefinitionController(ChangeSetService changes, ConfigStateService state) {
        this.changes = changes;
        this.state = state;
    }

    @GetMapping("/definitions")
    public Map<String, Object> list() {
        Wire.SnapshotData s = WireCodec.toWire(state.current());
        return Map.of("version", s.version(), "definitions", s.definitions(), "constraints", s.constraints());
    }

    @PostMapping("/definitions")
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.ChangeSetResponse define(@Valid @RequestBody Dtos.DefinitionRequest req) {
        Op op = new Op.DefineSetting(req.key(), req.type(), req.rules(), req.defaultValue(), req.description());
        return ChangeSetController.toResponse(changes.commit(new ChangeSetService.CommitRequest(ChangeKind.DEFINE,
                CurrentUser.get(), req.reason(), List.of(op), null, null)));
    }

    @PostMapping("/constraints")
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.ChangeSetResponse constrain(@Valid @RequestBody Dtos.ConstraintRequest req) {
        Op op = new Op.DefineConstraint(req.name(), req.type(), req.params());
        return ChangeSetController.toResponse(changes.commit(new ChangeSetService.CommitRequest(ChangeKind.DEFINE,
                CurrentUser.get(), req.reason(), List.of(op), null, null)));
    }
}
