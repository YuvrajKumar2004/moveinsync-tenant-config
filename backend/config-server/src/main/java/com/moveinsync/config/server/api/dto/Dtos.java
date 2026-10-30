package com.moveinsync.config.server.api.dto;

import com.moveinsync.config.core.change.DiffEntry;
import com.moveinsync.config.core.change.Op;
import com.moveinsync.config.core.model.Level;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.wire.Wire;
import com.moveinsync.config.server.error.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Request and response bodies of the REST API. */
public final class Dtos {

    private Dtos() {
    }

    public record TokenRequest(@NotBlank @Size(max = 100) String username, @NotBlank @Size(max = 200) String password) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn, String role, String tenant) {
    }

    /** One SET or REMOVE. */
    public record OpRequest(@NotBlank String op, @NotBlank String level, @Size(max = 64) String clientId,
                            @Size(max = 64) String officeId, @Size(max = 64) String shiftId,
                            @NotBlank @Size(max = 100) String key, @Size(max = 1000) String value) {

        public Op toOp() {
            Scope scope = scope(level, clientId, officeId, shiftId);
            return switch (op.toUpperCase(Locale.ROOT)) {
                case "SET" -> {
                    if (value == null) {
                        throw ApiException.badRequest("SET needs a value");
                    }
                    yield new Op.SetOverride(scope, key, value);
                }
                case "REMOVE" -> new Op.RemoveOverride(scope, key);
                default -> throw ApiException.badRequest("op must be SET or REMOVE");
            };
        }

        public Op.SetOverride toSet() {
            if (!"SET".equalsIgnoreCase(op)) {
                throw ApiException.badRequest("staged items must be SET operations");
            }
            return (Op.SetOverride) toOp();
        }
    }

    public record ChangeSetRequest(@NotBlank @Size(max = 500) String reason, Long expectedVersion,
                                   @NotEmpty @Size(max = 500) List<@Valid @NotNull OpRequest> ops) {
    }

    public record DefinitionRequest(@NotBlank @Size(max = 100) String key, @NotBlank String type,
                                    Map<String, String> rules, @NotBlank @Size(max = 1000) String defaultValue,
                                    @Size(max = 500) String description, @NotBlank @Size(max = 500) String reason) {
    }

    public record ConstraintRequest(@NotBlank @Size(max = 100) String name, @NotBlank String type,
                                    Map<String, String> params, @NotBlank @Size(max = 500) String reason) {
    }

    public record Target(@NotBlank String clientId, @NotBlank String officeId) {
        public String key() {
            if (!Scope.validId(clientId) || !Scope.validId(officeId)) {
                throw ApiException.badRequest("target ids must match [A-Za-z0-9._-]{1,64}");
            }
            return clientId + "/" + officeId;
        }
    }

    public record StageRequest(@NotBlank @Size(max = 500) String reason,
                               @NotEmpty @Size(max = 100) List<@Valid @NotNull Target> targets,
                               @NotEmpty @Size(max = 500) List<@Valid @NotNull OpRequest> items) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record RollbackRequest(@NotBlank String level, String clientId, @NotNull Long toVersion,
                                  @NotBlank @Size(max = 500) String reason, boolean dryRun) {
        public Scope scope() {
            return Dtos.scope(level, clientId, null, null);
        }
    }

    public record ChangeSetResponse(long version, String kind, Instant committedAt, String author, String reason,
                                    List<DiffEntry> diff, boolean replayed) {
    }

    public record HistoryEntry(long version, String kind, Instant committedAt, String author, String reason,
                               List<Wire.Op> ops, List<DiffEntry> diff) {
    }

    public record ResolvedValue(String key, String value, String level, String scope, String source, Long rolloutVersion) {
    }

    public record ResolveResponse(long snapshotVersion, String context, List<ResolvedValue> values) {
    }

    public record RolloutView(long version, List<String> targets, List<Wire.OverrideEntry> items) {
    }

    public static Scope scope(String level, String clientId, String officeId, String shiftId) {
        Level l;
        try {
            l = Level.valueOf(level.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("level must be one of GLOBAL, CLIENT, OFFICE, SHIFT");
        }
        return Scope.of(l, clientId, officeId, shiftId);
    }
}
