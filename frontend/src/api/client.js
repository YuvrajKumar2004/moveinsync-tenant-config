// Thin fetch wrapper: JSON in/out, bearer token, RFC 7807 problem responses turned into ApiError.

const API_BASE = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')
const BOOKING_BASE = (import.meta.env.VITE_BOOKING_BASE_URL || '/booking-api').replace(/\/$/, '')

export class ApiError extends Error {
  constructor(status, { code, detail, violations, requestId } = {}) {
    super(detail || `Request failed (HTTP ${status})`)
    this.status = status
    this.code = code || (status === 0 ? 'NETWORK_ERROR' : `HTTP_${status}`)
    this.violations = Array.isArray(violations) ? violations : []
    this.requestId = requestId
  }

  static fromResponse(status, body) {
    if (body && typeof body === 'object') {
      return new ApiError(status, body)
    }
    const generic = {
      401: 'Your session is not valid. Please sign in again.',
      403: 'You are not allowed to do this.',
      404: 'Not found.',
      502: 'The server is not reachable.',
      503: 'The server is busy or unavailable. Try again shortly.',
      504: 'The server did not answer in time.',
    }
    return new ApiError(status, { detail: generic[status] })
  }
}

async function send(url, { method = 'GET', body, token, headers = {}, signal, raw = false, okStatuses = [] } = {}) {
  let res
  try {
    res = await fetch(url, {
      method,
      signal,
      headers: {
        Accept: raw ? 'text/plain' : 'application/json',
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...headers,
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    })
  } catch (e) {
    if (e.name === 'AbortError') throw e
    throw new ApiError(0, { detail: 'Cannot reach the server. Is it running?' })
  }
  const text = await res.text()
  let data = text
  if (!raw && text) {
    try {
      data = JSON.parse(text)
    } catch {
      data = null
    }
  }
  if (!res.ok && !okStatuses.includes(res.status)) throw ApiError.fromResponse(res.status, raw ? null : data)
  return raw ? text : data || null
}

/** Creates an API bound to a token supplier; onUnauthorized fires on any 401 so the app can sign out. */
export function createHttp(getToken, onUnauthorized) {
  const call = async (path, opts = {}, base = API_BASE) => {
    try {
      return await send(base + path, { ...opts, token: opts.anonymous ? undefined : getToken() })
    } catch (e) {
      if (e instanceof ApiError && e.status === 401 && !opts.anonymous) onUnauthorized?.()
      throw e
    }
  }
  return {
    get: (path, opts) => call(path, opts),
    post: (path, body, opts) => call(path, { ...opts, method: 'POST', body }),
    put: (path, body, opts) => call(path, { ...opts, method: 'PUT', body }),
    text: (path, opts) => call(path, { ...opts, raw: true }),
    booking: (path, opts) => call(path, { ...opts, anonymous: true }, BOOKING_BASE),
    base: API_BASE,
  }
}

export { API_BASE }
