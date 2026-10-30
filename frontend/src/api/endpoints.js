// One function per backend endpoint actually exposed by config-server (see README "API") and the
// demo booking service. Paths and payloads mirror the Spring controllers exactly.

const qs = (params) => {
  const p = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v === undefined || v === null || v === '') return
    if (Array.isArray(v)) v.forEach((x) => p.append(k, x))
    else p.append(k, v)
  })
  const s = p.toString()
  return s ? `?${s}` : ''
}

export function endpoints(http) {
  return {
    // config-server: settings and resolution
    definitions: () => http.get('/api/v1/definitions'),
    defineSetting: (body) => http.post('/api/v1/definitions', body),
    resolve: ({ clientId, officeId, shiftId, keys, explain = true }) =>
      http.get(`/api/v1/resolve${qs({ clientId, officeId, shiftId, key: keys, explain })}`),

    // change sets and audit history
    commit: (body, idempotencyKey) =>
      http.post('/api/v1/change-sets', body, idempotencyKey ? { headers: { 'Idempotency-Key': idempotencyKey } } : {}),
    history: ({ before, limit = 25 } = {}) => http.get(`/api/v1/change-sets${qs({ before, limit })}`),
    changeSet: (version) => http.get(`/api/v1/change-sets/${encodeURIComponent(version)}`),

    // staged rollouts and rollback
    rollouts: () => http.get('/api/v1/rollouts'),
    stage: (body) => http.post('/api/v1/rollouts', body),
    promote: (version, reason) => http.post(`/api/v1/rollouts/${version}/promote`, { reason }),
    abort: (version, reason) => http.post(`/api/v1/rollouts/${version}/abort`, { reason }),
    rollback: (body) => http.post('/api/v1/rollback', body),

    // sync (SERVICE / OPERATOR only)
    serverVersion: () => http.get('/api/v1/sync/version'),
    snapshot: () => http.get('/api/v1/sync/snapshot'),

    // operations (OPERATOR only)
    faults: () => http.get('/api/v1/admin/faults'),
    setFaults: (body) => http.put('/api/v1/admin/faults', body),
    prometheus: () => http.text('/actuator/prometheus'),

    // demo booking service (no auth of its own)
    bookingStatus: () => http.booking('/config/status'),
    // 201 = accepted, 422 = rejected by the cut-off; both carry the decision as the body
    book: (body) => http.booking('/bookings', { method: 'POST', body, okStatuses: [422] }),
  }
}

/** Login does not carry a token. */
export async function requestToken(http, username, password) {
  return http.post('/api/v1/auth/token', { username, password }, { anonymous: true })
}
