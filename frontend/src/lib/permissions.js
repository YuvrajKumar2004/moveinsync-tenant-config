// UI capabilities per role. These only decide what the UI offers; the server enforces every rule
// again (roles in the security filter chain, tenants in AccessPolicy).

export function capabilities(session) {
  const role = session?.role
  const tenant = session?.tenant || null
  const operator = role === 'OPERATOR'
  const admin = role === 'ADMIN'
  return {
    role,
    tenant,
    operator,
    canReadHistory: operator || admin,
    canManageRollouts: operator || admin,
    canDefine: operator,
    canUseSync: operator || role === 'SERVICE',
    canSeeMetrics: operator,
    canUseFaults: operator,
    /** Clients this user may read; null means all. */
    readableClient: operator ? null : tenant,
    canWriteScope(scope) {
      if (operator) return true
      if (!admin || !tenant) return false
      return scope.level !== 'GLOBAL' && scope.clientId === tenant
    },
    canRollback(level, clientId) {
      if (operator) return true
      return admin && level === 'CLIENT' && clientId === tenant
    },
  }
}
