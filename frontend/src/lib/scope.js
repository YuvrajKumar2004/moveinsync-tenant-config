// Hierarchy helpers that mirror the backend's Scope / ResolutionContext rules.

export const LEVELS = ['GLOBAL', 'CLIENT', 'OFFICE', 'SHIFT']
export const ID_PATTERN = /^[A-Za-z0-9._-]{1,64}$/

export function contextLevel({ clientId, officeId, shiftId } = {}) {
  if (shiftId) return 'SHIFT'
  if (officeId) return 'OFFICE'
  if (clientId) return 'CLIENT'
  return 'GLOBAL'
}

/** Same text the server uses for scopes, e.g. "OFFICE:acme/blr-1". */
export function scopeLabel({ clientId, officeId, shiftId } = {}) {
  const level = contextLevel({ clientId, officeId, shiftId })
  if (level === 'GLOBAL') return 'GLOBAL'
  return `${level}:${[clientId, officeId, shiftId].filter(Boolean).join('/')}`
}

/** Context plus its ancestors, most specific first (what resolution walks). */
export function ancestry(ctx = {}) {
  const out = []
  if (ctx.shiftId) out.push({ clientId: ctx.clientId, officeId: ctx.officeId, shiftId: ctx.shiftId })
  if (ctx.officeId) out.push({ clientId: ctx.clientId, officeId: ctx.officeId })
  if (ctx.clientId) out.push({ clientId: ctx.clientId })
  out.push({})
  return out
}

/** Returns an error message, or null when the context is well formed. */
export function validateContext({ clientId, officeId, shiftId } = {}) {
  for (const [name, v] of [['client', clientId], ['office', officeId], ['shift', shiftId]]) {
    if (v && !ID_PATTERN.test(v)) return `The ${name} id may only contain letters, digits, '.', '_' and '-' (max 64).`
  }
  if (shiftId && !officeId) return 'A shift needs an office.'
  if (officeId && !clientId) return 'An office needs a client.'
  return null
}

/**
 * Classifies a resolved value relative to the context being viewed:
 * 'here' (stored at exactly this scope), 'staged', 'inherited' (from an ancestor) or 'default'.
 */
export function provenance(resolved, contextScope) {
  if (resolved.source === 'STAGED') return 'staged'
  if (resolved.source === 'DEFAULT') return 'default'
  return resolved.scope === contextScope ? 'here' : 'inherited'
}

/** Scope fields for an op at the context's own level. */
export function opScope(ctx) {
  const level = contextLevel(ctx)
  const s = { level }
  if (ctx.clientId) s.clientId = ctx.clientId
  if (ctx.officeId) s.officeId = ctx.officeId
  if (ctx.shiftId) s.shiftId = ctx.shiftId
  return s
}

/** Distinct scopes (clients, offices, shifts) that appear in snapshot overrides, rollouts or history ops. */
export function collectScopes(entries) {
  const clients = new Map()
  for (const e of entries) {
    if (!e || !e.clientId) continue
    const c = clients.get(e.clientId) || { clientId: e.clientId, offices: new Map() }
    clients.set(e.clientId, c)
    if (e.officeId) {
      const o = c.offices.get(e.officeId) || { officeId: e.officeId, shifts: new Set() }
      c.offices.set(e.officeId, o)
      if (e.shiftId) o.shifts.add(e.shiftId)
    }
  }
  return [...clients.values()]
    .sort((a, b) => a.clientId.localeCompare(b.clientId))
    .map((c) => ({
      clientId: c.clientId,
      offices: [...c.offices.values()]
        .sort((a, b) => a.officeId.localeCompare(b.officeId))
        .map((o) => ({ officeId: o.officeId, shifts: [...o.shifts].sort() })),
    }))
}
