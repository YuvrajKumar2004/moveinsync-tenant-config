import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Activity, BookOpen, Bus, Gauge, GitBranch, History as HistoryIcon, Radio, Server, ShieldAlert, Zap } from 'lucide-react'
import { useAuth } from '../state/AuthContext.jsx'
import { useLive } from '../state/LiveContext.jsx'
import { useToast } from '../state/ToastContext.jsx'
import { useAsync } from '../lib/useAsync.js'
import { formatTime, humanDuration, relativeTime } from '../lib/format.js'
import { metric, parsePrometheus } from '../lib/metrics.js'
import { LiveIndicator, useNow } from '../components/LiveIndicator.jsx'
import { Badge, Button, Card, EmptyState, ErrorBanner, Field, Input, PageHeader, Spinner, Stat } from '../components/ui.jsx'

export default function Dashboard() {
  const { api, caps, session } = useAuth()
  const live = useLive()
  const defs = useAsync(() => api.definitions(), [live.tick])
  const rollouts = useAsync(() => api.rollouts(), [live.tick], { enabled: caps.canManageRollouts })
  const recent = useAsync(() => api.history({ limit: 6 }), [live.tick], { enabled: caps.canReadHistory })

  return (
    <>
      <PageHeader title={`Welcome, ${session.username}`}
        description={caps.operator ? 'Operator view: all tenants, the full change log and live operations.'
          : `${caps.role === 'ADMIN' ? 'Administrator' : 'Read-only'} view for tenant ${caps.tenant}.`} />

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Stat icon={Gauge} label="Configuration version" value={live.version ?? '…'} hint={live.historyId ? `history ${live.historyId.slice(0, 8)}…` : 'per tenant view'} />
        <Stat icon={Radio} tone={live.status === 'live' || live.status === 'polling' ? 'emerald' : 'amber'} label="Live updates"
          value={live.status === 'live' ? 'SSE connected' : live.status === 'polling' ? 'Polling' : live.status}
          hint={live.error || (caps.canUseSync ? 'server-sent events' : 'every 5 s (sync stream is for operators/services)')} />
        <Stat icon={BookOpen} tone="sky" label="Settings" value={defs.data?.definitions?.length ?? '…'} hint={`${defs.data?.constraints?.length ?? 0} cross-key constraint(s)`} />
        {caps.canManageRollouts
          ? <Stat icon={GitBranch} tone="amber" label="Active rollouts" value={rollouts.data?.length ?? '…'} hint="staged for selected offices" />
          : <Stat icon={ShieldAlert} tone="rose" label="Access" value={caps.role} hint={`tenant ${caps.tenant}`} />}
      </div>

      <div className="mt-5 grid gap-5 lg:grid-cols-3">
        <div className="space-y-5 lg:col-span-2">
          {caps.canReadHistory && (
            <Card title="Recent changes" subtitle="Latest committed change sets" actions={<Link to="/history" className="text-sm font-medium text-indigo-600 hover:text-indigo-500">View all</Link>} bodyClassName="p-0">
              {recent.loading && !recent.data ? <Spinner /> : recent.error ? <ErrorBanner error={recent.error} className="m-4" /> :
                !recent.data?.length ? <EmptyState icon={HistoryIcon} title="No changes yet" /> : (
                  <ul className="divide-y divide-slate-100">
                    {recent.data.map((e) => (
                      <li key={e.version} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-3 text-sm sm:px-5">
                        <span className="w-12 font-semibold text-slate-900">v{e.version}</span>
                        <Badge tone={e.kind === 'ROLLBACK' ? 'rose' : e.kind === 'APPLY' ? 'indigo' : 'slate'}>{e.kind}</Badge>
                        <span className="min-w-0 flex-1 truncate text-slate-600" title={e.reason}>{e.reason}</span>
                        <span className="text-xs text-slate-500">{e.author} · {formatTime(e.committedAt)}</span>
                      </li>
                    ))}
                  </ul>
                )}
            </Card>
          )}
          <BookingCard />
          {caps.canSeeMetrics && <MetricsCard />}
        </div>
        <div className="space-y-5">
          <ActivityCard />
          {caps.canUseFaults && <FaultsCard />}
        </div>
      </div>
    </>
  )
}

function ActivityCard() {
  const live = useLive()
  const now = useNow(5000)
  return (
    <Card title="Live activity" subtitle="Versions arriving at this browser" actions={<LiveIndicator detailed />}>
      {!live.events.length ? (
        <EmptyState icon={Activity} title="Waiting for changes">Commit a change anywhere and it appears here without a reload.</EmptyState>
      ) : (
        <ol className="space-y-2" data-testid="activity-feed">
          {live.events.map((e, i) => (
            <li key={`${e.version}-${i}`} className="flex items-center gap-2 text-sm">
              <Zap className="h-4 w-4 text-amber-500" />
              <span className="font-semibold">v{e.version}</span>
              <Badge tone={e.kind === 'HISTORY_RESET' ? 'rose' : 'indigo'}>{e.kind}</Badge>
              {e.ops !== null && <span className="text-xs text-slate-500">{e.ops} op</span>}
              <span className="ml-auto text-xs text-slate-400">{e.via} · {relativeTime(e.at, now)}</span>
            </li>
          ))}
        </ol>
      )}
    </Card>
  )
}

function BookingCard() {
  const { api, caps } = useAuth()
  const live = useLive()
  const status = useAsync(() => api.bookingStatus(), [live.tick])
  const [form, setForm] = useState({ clientId: caps.tenant || 'acme', officeId: 'blr-1', shiftId: '', minutes: '45' })
  const [result, setResult] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const s = status.data
  const inSync = s && live.version !== null && caps.canUseSync ? s.version === live.version : null

  const book = async (e) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      setResult(await api.book({ clientId: form.clientId.trim(), officeId: form.officeId.trim() || undefined, shiftId: form.shiftId.trim() || undefined, minutesBeforeDeparture: Number(form.minutes) }))
    } catch (err) {
      setResult(null)
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card title={<span className="flex items-center gap-2"><Bus className="h-4 w-4 text-indigo-500" />Booking service (embedded client)</span>}
      subtitle="A separate service that reads configuration from its own in-memory snapshot"
      actions={<Button size="sm" variant="ghost" onClick={status.reload}>Refresh</Button>}>
      {status.loading && !s ? <Spinner /> : status.error ? (
        <ErrorBanner error={{ message: 'Booking service is not reachable on its proxy (/booking-api).', code: status.error.code }} onRetry={status.reload} />
      ) : (
        <div className="grid gap-3 text-sm sm:grid-cols-4">
          <div><p className="text-xs text-slate-500">Snapshot version</p><p className="font-semibold">v{s.version} {inSync !== null && <Badge tone={inSync ? 'emerald' : 'amber'}>{inSync ? 'in sync' : 'catching up'}</Badge>}</p></div>
          <div><p className="text-xs text-slate-500">Push stream</p><p className="font-semibold">{s.streamConnected ? 'connected' : 'disconnected'}</p></div>
          <div><p className="text-xs text-slate-500">Changes applied</p><p className="font-semibold">{s.metrics?.events_applied ?? 0}</p></div>
          <div><p className="text-xs text-slate-500">Last server contact</p><p className="font-semibold">{s.metrics?.staleness_ms >= 0 ? `${Math.round(s.metrics.staleness_ms / 100) / 10}s ago` : '—'}</p></div>
        </div>
      )}
      <form onSubmit={book} className="mt-4 grid gap-2 border-t border-slate-100 pt-4 sm:grid-cols-5">
        <Field label="Client">{(id) => <Input id={id} value={form.clientId} disabled={!caps.operator} onChange={(e) => setForm({ ...form, clientId: e.target.value })} />}</Field>
        <Field label="Office">{(id) => <Input id={id} value={form.officeId} onChange={(e) => setForm({ ...form, officeId: e.target.value })} />}</Field>
        <Field label="Shift">{(id) => <Input id={id} value={form.shiftId} onChange={(e) => setForm({ ...form, shiftId: e.target.value })} placeholder="optional" />}</Field>
        <Field label="Minutes ahead">{(id) => <Input id={id} type="number" min="0" value={form.minutes} onChange={(e) => setForm({ ...form, minutes: e.target.value })} />}</Field>
        <div className="flex items-end"><Button type="submit" className="w-full" loading={busy}>Try booking</Button></div>
      </form>
      {result && <BookingResult r={result} />}
      <ErrorBanner error={error} className="mt-3" />
    </Card>
  )
}

function BookingResult({ r }) {
  if (r.accepted === undefined) {
    return <p className="mt-3 rounded-lg bg-rose-50 p-3 text-sm text-rose-700">{r.detail || 'The booking service rejected the request.'}</p>
  }
  return (
    <div className={`mt-3 rounded-lg p-3 text-sm ${r.accepted ? 'bg-emerald-50 text-emerald-800' : 'bg-rose-50 text-rose-700'}`} data-testid="booking-result">
      <b>{r.accepted ? 'Accepted' : 'Rejected'}</b> — booking {humanDuration(r.timeBeforeDeparture)} before departure; cut-off {humanDuration(r.cutoff)} from {r.cutoffLevel} ({r.cutoffSource.toLowerCase()}), config v{r.configVersion}.
    </div>
  )
}

function MetricsCard() {
  const { api } = useAuth()
  const live = useLive()
  const m = useAsync(async () => parsePrometheus(await api.prometheus()), [live.tick])
  const v = (name, where) => {
    const x = m.data ? metric(m.data, name, where) : null
    return x === null ? '—' : Number.isInteger(x) ? x : x.toFixed(3)
  }
  const lag = m.data ? metric(m.data, 'config_push_lag_seconds', { quantile: '0.99' }) : null
  return (
    <Card title={<span className="flex items-center gap-2"><Server className="h-4 w-4 text-indigo-500" />Server metrics</span>} subtitle="From /actuator/prometheus (operator only)"
      actions={<Button size="sm" variant="ghost" onClick={m.reload}>Refresh</Button>}>
      {m.loading && !m.data ? <Spinner /> : m.error ? <ErrorBanner error={m.error} /> : (
        <dl className="grid gap-3 text-sm sm:grid-cols-3">
          {[
            ['Committed change sets', v('config_changes_committed_total')],
            ['Rejected changes', v('config_changes_rejected_total')],
            ['Open push streams', v('config_stream_subscribers')],
            ['Push queue depth', v('config_stream_queue_depth')],
            ['Push lag p99', lag === null ? '—' : `${Math.round(lag * 1000)} ms`],
            ['Server errors', v('config_errors_total')],
          ].map(([k, val]) => (
            <div key={k} className="rounded-lg bg-slate-50 p-3"><dt className="text-xs text-slate-500">{k}</dt><dd className="text-lg font-semibold">{val}</dd></div>
          ))}
        </dl>
      )}
    </Card>
  )
}

function FaultsCard() {
  const { api } = useAuth()
  const toast = useToast()
  const faults = useAsync(() => api.faults(), [])
  const [busy, setBusy] = useState(null)
  if (faults.error?.status === 404) return null // fault injection disabled on this server
  const toggle = async (name) => {
    setBusy(name)
    try {
      const next = { ...faults.data, [name]: !faults.data[name] }
      await api.setFaults(next)
      toast.info(`${name} ${next[name] ? 'enabled' : 'disabled'}`)
      faults.reload()
    } catch (e) {
      toast.error('Could not change fault injection', e.message)
    } finally {
      setBusy(null)
    }
  }
  const items = [
    ['dropPushes', 'Drop pushes', 'Committed changes are not pushed; clients must heal via heartbeat / poll.'],
    ['duplicatePushes', 'Duplicate pushes', 'Every pushed change is sent twice.'],
    ['outOfOrderPushes', 'Out-of-order pushes', 'An older version is re-sent after each change.'],
  ]
  return (
    <Card title="Failure injection" subtitle="Test the live-update safety nets (operator only)">
      {faults.loading && !faults.data ? <Spinner /> : faults.error ? <ErrorBanner error={faults.error} /> : (
        <ul className="space-y-3">
          {items.map(([k, title, desc]) => (
            <li key={k} className="flex items-start gap-3">
              <button role="switch" aria-checked={!!faults.data[k]} aria-label={title} disabled={busy === k} onClick={() => toggle(k)}
                className={`relative mt-0.5 inline-flex h-5 w-9 shrink-0 rounded-full transition ${faults.data[k] ? 'bg-rose-500' : 'bg-slate-300'}`}>
                <span className={`absolute top-0.5 h-4 w-4 rounded-full bg-white shadow transition ${faults.data[k] ? 'left-[18px]' : 'left-0.5'}`} />
              </button>
              <div><p className="text-sm font-medium">{title}</p><p className="text-xs text-slate-500">{desc}</p></div>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}
