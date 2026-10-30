import { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { API_BASE } from '../api/client.js'
import { openStream } from '../api/sse.js'
import { useAuth } from './AuthContext.jsx'

// Keeps the UI in step with the server's configuration version.
//  - OPERATOR: the same SSE stream the embedded client library uses (/api/v1/sync/stream), opened
//    with fetch() so the bearer token stays in a header. Reconnects with jittered backoff and
//    resumes from the last seen version.
//  - ADMIN / EMPLOYEE: the server only lets SERVICE and OPERATOR tokens use the sync endpoints, so
//    these roles poll /api/v1/resolve (allowed for their own tenant) and read snapshotVersion.
const LiveContext = createContext(null)
const POLL_MS = 5000
const MAX_EVENTS = 40

export function LiveProvider({ children }) {
  const { session, caps, api } = useAuth()
  const [state, setState] = useState({ status: 'idle', version: null, historyId: null, lastContact: null, error: null })
  const [events, setEvents] = useState([])
  const [tick, setTick] = useState(0)
  const versionRef = useRef(null)
  const historyRef = useRef(null)

  useEffect(() => {
    versionRef.current = null
    historyRef.current = null
    setEvents([])
    setState({ status: 'idle', version: null, historyId: null, lastContact: null, error: null })
    if (!session) return undefined

    const ctrl = new AbortController()
    let stopped = false
    const bump = () => setTick((t) => t + 1)
    const record = (version, extra = {}) => {
      const changed = versionRef.current !== null && version > versionRef.current
      versionRef.current = Math.max(versionRef.current ?? 0, version)
      setState((s) => ({ ...s, version: versionRef.current, lastContact: Date.now(), error: null, ...extra }))
      return changed
    }
    const pushEvent = (e) => setEvents((list) => [{ ...e, at: Date.now() }, ...list].slice(0, MAX_EVENTS))
    const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

    if (caps.canUseSync) {
      ;(async () => {
        let attempt = 0
        while (!stopped) {
          setState((s) => ({ ...s, status: attempt ? 'reconnecting' : 'connecting' }))
          try {
            if (versionRef.current === null) {
              const v = await api.serverVersion()
              historyRef.current = v.historyId
              record(v.version, { historyId: v.historyId })
            }
            const since = versionRef.current ?? 0
            await openStream({
              url: `${API_BASE}/api/v1/sync/stream?since=${since}`,
              token: session.token,
              signal: ctrl.signal,
              onOpen: () => {
                attempt = 0
                setState((s) => ({ ...s, status: 'live', error: null }))
              },
              onEvent: (ev) => {
                const data = safeJson(ev.data)
                if (!data) return
                if (ev.event === 'heartbeat') {
                  if (historyRef.current && data.historyId && data.historyId !== historyRef.current) {
                    // the server's change log was replaced: versions restart, so take the server's as-is
                    versionRef.current = data.version
                    historyRef.current = data.historyId
                    setState((s) => ({ ...s, version: data.version, historyId: data.historyId, lastContact: Date.now() }))
                    pushEvent({ version: data.version, kind: 'HISTORY_RESET', ops: null, via: 'heartbeat' })
                    bump()
                    return
                  }
                  historyRef.current = data.historyId || historyRef.current
                  if (record(data.version, { historyId: data.historyId })) bump()
                } else if (ev.event === 'change') {
                  record(data.version)
                  pushEvent({ version: data.version, kind: data.kind, ops: data.ops?.length ?? 0, via: 'SSE push' })
                  bump()
                } else if (ev.event === 'resync') {
                  record(data.version)
                  bump()
                }
              },
            })
          } catch (e) {
            if (stopped || e.name === 'AbortError') return
            setState((s) => ({ ...s, error: e.status === 403 ? 'Not allowed to open the live stream.' : e.message }))
            if (e.status === 401 || e.status === 403) {
              setState((s) => ({ ...s, status: 'offline' }))
              return
            }
          }
          if (stopped) return
          attempt = Math.min(attempt + 1, 6)
          setState((s) => ({ ...s, status: 'reconnecting' }))
          await sleep(Math.random() * Math.min(15000, 500 * 2 ** attempt))
        }
      })()
    } else {
      setState((s) => ({ ...s, status: 'polling' }))
      ;(async () => {
        let probeKey = null
        while (!stopped) {
          try {
            if (!probeKey) probeKey = (await api.definitions()).definitions?.[0]?.key
            const r = await api.resolve({ clientId: caps.readableClient || undefined, keys: probeKey ? [probeKey] : undefined, explain: false })
            if (record(r.snapshotVersion, { status: 'polling' })) {
              pushEvent({ version: r.snapshotVersion, kind: 'CHANGE', ops: null, via: 'poll' })
              bump()
            }
          } catch (e) {
            if (stopped) return
            setState((s) => ({ ...s, status: 'offline', error: e.message }))
          }
          await sleep(POLL_MS)
        }
      })()
    }
    return () => {
      stopped = true
      ctrl.abort()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session?.token])

  const value = useMemo(() => ({ ...state, events, tick }), [state, events, tick])
  return <LiveContext.Provider value={value}>{children}</LiveContext.Provider>
}

function safeJson(s) {
  try {
    return JSON.parse(s)
  } catch {
    return null
  }
}

export function useLive() {
  return useContext(LiveContext)
}
