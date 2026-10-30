// Display helpers.

/** "PT1H30M" -> "1 h 30 min"; anything else is returned unchanged. */
export function humanDuration(iso) {
  const m = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/.exec(iso || '')
  if (!m || (!m[1] && !m[2] && !m[3])) return iso === 'PT0S' ? '0 min' : iso
  const parts = []
  if (m[1]) parts.push(`${Number(m[1])} h`)
  if (m[2]) parts.push(`${Number(m[2])} min`)
  if (m[3]) parts.push(`${Number(m[3])} s`)
  return parts.join(' ')
}

export function displayValue(value, type) {
  if (value === undefined || value === null) return '—'
  if (type === 'DURATION') return humanDuration(value)
  if (type === 'LIST') return value.split(',').join(', ')
  if (type === 'TIME_WINDOW') return value.replace('-', ' – ')
  return value
}

export function formatTime(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'medium' })
}

export function relativeTime(ts, now = Date.now()) {
  if (!ts) return 'never'
  const s = Math.max(0, Math.round((now - ts) / 1000))
  if (s < 5) return 'just now'
  if (s < 60) return `${s}s ago`
  if (s < 3600) return `${Math.round(s / 60)} min ago`
  return `${Math.round(s / 3600)} h ago`
}

export function scopeText(scope) {
  if (!scope) return 'GLOBAL'
  if (typeof scope === 'string') return scope
  const ids = [scope.clientId, scope.officeId, scope.shiftId].filter(Boolean).join('/')
  return ids ? `${scope.level}:${ids}` : scope.level || 'GLOBAL'
}

/** Short text for a wire op from the history endpoint. */
export function describeOp(op) {
  const at = scopeText(op)
  switch (op.type) {
    case 'SET':
      return { verb: 'Set', text: `${op.key} = ${op.value}`, at }
    case 'REMOVE':
      return { verb: 'Remove', text: op.key, at }
    case 'DEFINE':
      return { verb: 'Define', text: `${op.definition?.key} (${op.definition?.type}, default ${op.definition?.defaultValue})`, at: 'catalog' }
    case 'DEFINE_CONSTRAINT':
      return { verb: 'Constraint', text: `${op.constraint?.name} (${op.constraint?.type})`, at: 'catalog' }
    case 'STAGE':
      return { verb: 'Stage', text: `${op.items?.length || 0} item(s) for ${op.targets?.join(', ')}`, at: 'rollout' }
    case 'UNSTAGE':
      return { verb: 'Unstage', text: `rollout v${op.rolloutVersion}`, at: 'rollout' }
    default:
      return { verb: op.type, text: '', at }
  }
}
