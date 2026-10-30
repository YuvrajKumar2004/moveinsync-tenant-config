import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, createHttp } from './client.js'
import { endpoints } from './endpoints.js'

const respond = (status, body) => Promise.resolve(new Response(body === undefined ? '' : JSON.stringify(body), { status }))

describe('http client', () => {
  afterEach(() => vi.restoreAllMocks())

  it('sends the bearer token and JSON body to the real endpoint paths', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(() => respond(201, { version: 7 }))
    const api = endpoints(createHttp(() => 'tok'))
    await api.commit({ reason: 'r', ops: [] }, 'key-1')
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/v1/change-sets')
    expect(init.method).toBe('POST')
    expect(init.headers.Authorization).toBe('Bearer tok')
    expect(init.headers['Idempotency-Key']).toBe('key-1')
    expect(JSON.parse(init.body)).toEqual({ reason: 'r', ops: [] })
  })

  it('encodes resolve parameters and repeats key', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(() => respond(200, { values: [] }))
    await endpoints(createHttp(() => 't')).resolve({ clientId: 'acme', officeId: '', keys: ['a', 'b'] })
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/resolve?clientId=acme&key=a&key=b&explain=true')
  })

  it('turns problem responses into ApiError with violations and request id', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(() => respond(422, {
      status: 422, code: 'VALIDATION_FAILED', detail: 'the change was rejected', requestId: 'r1',
      violations: [{ code: 'INVALID_VALUE', target: 'k@OFFICE:a/b', message: 'must not be negative' }],
    }))
    const err = await endpoints(createHttp(() => 't')).commit({ reason: 'r', ops: [] }).catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err.status).toBe(422)
    expect(err.code).toBe('VALIDATION_FAILED')
    expect(err.violations[0].message).toBe('must not be negative')
    expect(err.requestId).toBe('r1')
  })

  it('signals 401 so the app can sign out, but not for the login call itself', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(() => respond(401, { code: 'UNAUTHENTICATED' }))
    const onUnauthorized = vi.fn()
    const http = createHttp(() => 't', onUnauthorized)
    await endpoints(http).definitions().catch(() => {})
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
    await http.post('/api/v1/auth/token', {}, { anonymous: true }).catch(() => {})
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
  })

  it('returns the booking decision for a rejected (422) booking and calls the proxy path without a token', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(() => respond(422, { accepted: false, cutoff: 'PT1H' }))
    const r = await endpoints(createHttp(() => 'secret')).book({ clientId: 'acme', minutesBeforeDeparture: 45 })
    expect(r.accepted).toBe(false)
    expect(fetchMock.mock.calls[0][0]).toBe('/booking-api/bookings')
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBeUndefined()
  })

  it('reports an unreachable server as a network error', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(() => Promise.reject(new TypeError('Failed to fetch')))
    const err = await endpoints(createHttp(() => 't')).definitions().catch((e) => e)
    expect(err.code).toBe('NETWORK_ERROR')
  })
})
