package com.moveinsync.config.server.security;

import com.moveinsync.config.core.snapshot.TenantFilter;

import java.util.Set;

/**
 * The authenticated caller as carried in the access token. Only an opaque subject (username) is kept:
 * no names, emails or phone numbers.
 */
public record AuthUser(String subject, Role role, String tenant, Set<String> serviceTenants) {

    public AuthUser {
        serviceTenants = serviceTenants == null ? Set.of() : Set.copyOf(serviceTenants);
    }

    /** Which tenants' data this caller may read. */
    public TenantFilter readFilter() {
        return switch (role) {
            case OPERATOR -> TenantFilter.ALL;
            case SERVICE -> TenantFilter.of(serviceTenants);
            case ADMIN, EMPLOYEE -> TenantFilter.of(tenant == null ? Set.of() : Set.of(tenant));
        };
    }

    public boolean canRead(String clientId) {
        return clientId == null || readFilter().allowsTenant(clientId);
    }
}
