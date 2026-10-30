package com.moveinsync.config.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The caller's position in the hierarchy (for example: client "acme", office "blr-1", shift "night").
 * The lookup chain is computed once so that repeated reads with the same context allocate nothing.
 */
public final class ResolutionContext {

    public static final ResolutionContext GLOBAL = new ResolutionContext(null, null, null);

    private final String clientId;
    private final String officeId;
    private final String shiftId;
    private final Scope[] chain;
    private final String officeKey;

    private ResolutionContext(String clientId, String officeId, String shiftId) {
        if (shiftId != null && officeId == null) {
            throw new IllegalArgumentException("a shift context needs an officeId");
        }
        if (officeId != null && clientId == null) {
            throw new IllegalArgumentException("an office context needs a clientId");
        }
        this.clientId = clientId;
        this.officeId = officeId;
        this.shiftId = shiftId;
        List<Scope> scopes = new ArrayList<>(4);
        if (shiftId != null) {
            scopes.add(Scope.shift(clientId, officeId, shiftId));
        }
        if (officeId != null) {
            scopes.add(Scope.office(clientId, officeId));
        }
        if (clientId != null) {
            scopes.add(Scope.client(clientId));
        }
        scopes.add(Scope.GLOBAL);
        this.chain = scopes.toArray(Scope[]::new);
        this.officeKey = officeId == null ? null : clientId + "/" + officeId;
    }

    public static ResolutionContext of(String clientId, String officeId, String shiftId) {
        return new ResolutionContext(blankToNull(clientId), blankToNull(officeId), blankToNull(shiftId));
    }

    /** Context that resolves exactly as a read at the given scope would. */
    public static ResolutionContext at(Scope scope) {
        return new ResolutionContext(scope.clientId(), scope.officeId(), scope.shiftId());
    }

    /** Scopes from most specific to GLOBAL. Callers must not modify the array. */
    public Scope[] chain() {
        return chain;
    }

    public String clientId() {
        return clientId;
    }

    public String officeId() {
        return officeId;
    }

    public String shiftId() {
        return shiftId;
    }

    /** "clientId/officeId", or null for contexts above office level. */
    public String officeKey() {
        return officeKey;
    }

    @Override
    public String toString() {
        return chain[0].toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
