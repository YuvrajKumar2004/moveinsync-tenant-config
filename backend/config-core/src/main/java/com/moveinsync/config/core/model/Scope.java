package com.moveinsync.config.core.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Where a value is attached. A scope is fully qualified: an OFFICE scope also names its client, a SHIFT
 * scope names its office and client. Office ids are unique within a client, shift ids within an office.
 */
public record Scope(Level level, String clientId, String officeId, String shiftId) {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    public static final Scope GLOBAL = new Scope(Level.GLOBAL, null, null, null);

    public Scope {
        Objects.requireNonNull(level, "level");
        switch (level) {
            case GLOBAL -> require(clientId == null && officeId == null && shiftId == null,
                    "GLOBAL scope must not name a client, office or shift");
            case CLIENT -> require(validId(clientId) && officeId == null && shiftId == null,
                    "CLIENT scope needs a valid clientId only");
            case OFFICE -> require(validId(clientId) && validId(officeId) && shiftId == null,
                    "OFFICE scope needs a valid clientId and officeId");
            case SHIFT -> require(validId(clientId) && validId(officeId) && validId(shiftId),
                    "SHIFT scope needs a valid clientId, officeId and shiftId");
        }
    }

    public static Scope client(String clientId) {
        return new Scope(Level.CLIENT, clientId, null, null);
    }

    public static Scope office(String clientId, String officeId) {
        return new Scope(Level.OFFICE, clientId, officeId, null);
    }

    public static Scope shift(String clientId, String officeId, String shiftId) {
        return new Scope(Level.SHIFT, clientId, officeId, shiftId);
    }

    /** Builds a scope from loosely supplied fields; throws IllegalArgumentException when inconsistent. */
    public static Scope of(Level level, String clientId, String officeId, String shiftId) {
        return new Scope(level, blankToNull(clientId), blankToNull(officeId), blankToNull(shiftId));
    }

    public static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** True when this scope belongs to the given tenant (GLOBAL belongs to nobody). */
    public boolean belongsTo(String tenant) {
        return clientId != null && clientId.equals(tenant);
    }

    /** "clientId/officeId" key used for staged rollout targets. Only valid for OFFICE and SHIFT scopes. */
    public String officeKey() {
        return officeId == null ? null : clientId + "/" + officeId;
    }

    @Override
    public String toString() {
        return switch (level) {
            case GLOBAL -> "GLOBAL";
            case CLIENT -> "CLIENT:" + clientId;
            case OFFICE -> "OFFICE:" + clientId + "/" + officeId;
            case SHIFT -> "SHIFT:" + clientId + "/" + officeId + "/" + shiftId;
        };
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
