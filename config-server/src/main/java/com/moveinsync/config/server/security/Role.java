package com.moveinsync.config.server.security;

/**
 * <ul>
 *   <li>OPERATOR - MoveInSync staff: definitions, GLOBAL values, any tenant, rollouts, rollbacks.</li>
 *   <li>ADMIN - a client's administrator: changes, rollouts and rollbacks inside their own tenant.</li>
 *   <li>EMPLOYEE - read-only resolved values for their own tenant.</li>
 *   <li>SERVICE - an embedding service: sync endpoints for the tenants it is allowed to read.</li>
 * </ul>
 */
public enum Role {
    OPERATOR,
    ADMIN,
    EMPLOYEE,
    SERVICE
}
