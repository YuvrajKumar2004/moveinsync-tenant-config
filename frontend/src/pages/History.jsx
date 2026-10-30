import { useEffect, useMemo, useState } from 'react'
import { History as HistoryIcon, RotateCcw, Search, Eye } from 'lucide-react'
import { useAuth } from '../state/AuthContext.jsx'
import { useLive } from '../state/LiveContext.jsx'
import { useToast } from '../state/ToastContext.jsx'
import { describeOp, formatTime, scopeText } from '../lib/format.js'
import { Badge, Button, Card, EmptyState, ErrorBanner, Field, Input, Modal, PageHeader, Select, Spinner, Textarea } from '../components/ui.jsx'

const KIND_TONE = { APPLY: 'indigo', ROLLBACK: 'rose', STAGE: 'amber', PROMOTE: 'emerald', ABORT_ROLLOUT: 'slate', DEFINE: 'violet', BOOTSTRAP: 'sky' }
const PAGE = 25

export default function History() {
  const { api } = useAuth()
  const live = useLive()
  const [entries, setEntries] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [more, setMore] = useState(true)
  const [filter, setFilter] = useState('')
  const [kind, setKind] = useState('')
  const [selected, setSelected] = useState(null)

  // first page; re-read whenever the live layer reports a new version
  useEffect(() => {
    let cancelled = false
    setError(null)
    api.history({ limit: PAGE })
      .then((page) => {
        if (cancelled) return
        setEntries((old) => {
          const fresh = new Set(page.map((e) => e.version))
          return [...page, ...old.filter((e) => !fresh.has(e.version) && e.version < (page.at(-1)?.version ?? Infinity))]
        })
        setMore(page.length === PAGE)
      })
      .catch((e) => !cancelled && setError(e))
      .finally(() => !cancelled && setLoading(false))
    return () => { cancelled = true }
  }, [api, live.tick])

  const loadOlder = async () => {
    const before = entries.at(-1)?.version
    setLoading(true)
    try {
      const page = await api.history({ before, limit: PAGE })
      setEntries((old) => [...old, ...page])
      setMore(page.length === PAGE)
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }

  const shown = useMemo(() => {
    const q = filter.trim().toLowerCase()
    return entries.filter((e) => (!kind || e.kind === kind) &&
      (!q || `${e.version} ${e.author} ${e.reason} ${JSON.stringify(e.ops)}`.toLowerCase().includes(q)))
  }, [entries, filter, kind])

  return (
    <>
      <PageHeader title="History & rollback"
        description="Every change set with its version, author and reason. Open one to see exactly what changed, or restore a scope to how it was at that version." />
      <Card bodyClassName="p-0"
        actions={<div className="flex flex-wrap gap-2">
          <div className="relative">
            <Search className="pointer-events-none absolute left-2.5 top-2.5 h-4 w-4 text-slate-400" />
            <Input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="Search author, reason, key" className="w-56 pl-8" aria-label="Search history" />
          </div>
          <Select value={kind} onChange={(e) => setKind(e.target.value)} className="w-40" aria-label="Kind">
            <option value="">All kinds</option>
            {Object.keys(KIND_TONE).map((k) => <option key={k}>{k}</option>)}
          </Select>
        </div>}
        title="Change sets" subtitle="Newest first">
        <ErrorBanner error={error} className="m-4" />
        {loading && !entries.length ? <Spinner /> : !shown.length ? (
          <EmptyState icon={HistoryIcon} title="No change sets">{entries.length ? 'Nothing matches the filter.' : 'Nothing has been committed yet.'}</EmptyState>
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full text-sm">
              <thead className="bg-slate-50 text-left text-xs font-medium uppercase tracking-wide text-slate-500">
                <tr><th className="px-4 py-2.5">Version</th><th className="px-4 py-2.5">Kind</th><th className="px-4 py-2.5">When</th><th className="px-4 py-2.5">Author</th><th className="px-4 py-2.5">Reason</th><th className="px-4 py-2.5">Changes</th><th className="px-4 py-2.5" /></tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {shown.map((e) => (
                  <tr key={e.version} className="hover:bg-slate-50">
                    <td className="px-4 py-2.5 font-semibold text-slate-900">v{e.version}</td>
                    <td className="px-4 py-2.5"><Badge tone={KIND_TONE[e.kind]}>{e.kind}</Badge></td>
                    <td className="whitespace-nowrap px-4 py-2.5 text-slate-600">{formatTime(e.committedAt)}</td>
                    <td className="px-4 py-2.5 text-slate-700">{e.author}</td>
                    <td className="max-w-xs truncate px-4 py-2.5 text-slate-600" title={e.reason}>{e.reason}</td>
                    <td className="px-4 py-2.5 text-slate-500">{e.ops.length} op · {e.diff.length} diff</td>
                    <td className="px-4 py-2.5 text-right"><Button size="sm" variant="secondary" icon={Eye} onClick={() => setSelected(e)}>Details</Button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {more && entries.length > 0 && (
          <div className="border-t border-slate-100 p-3 text-center">
            <Button variant="secondary" loading={loading} onClick={loadOlder}>Load older</Button>
          </div>
        )}
      </Card>
      {selected && <ChangeSetModal entry={selected} onClose={() => setSelected(null)} />}
    </>
  )
}

function ChangeSetModal({ entry, onClose }) {
  const [rollback, setRollback] = useState(false)
  return (
    <Modal open wide title={`Version ${entry.version} · ${entry.kind}`} onClose={onClose}
      footer={!rollback && <Button variant="secondary" icon={RotateCcw} onClick={() => setRollback(true)}>Restore a scope to this version…</Button>}>
      <dl className="grid grid-cols-1 gap-3 text-sm sm:grid-cols-3">
        <div><dt className="text-xs text-slate-500">Committed</dt><dd className="font-medium">{formatTime(entry.committedAt)}</dd></div>
        <div><dt className="text-xs text-slate-500">Author</dt><dd className="font-medium">{entry.author}</dd></div>
        <div><dt className="text-xs text-slate-500">Reason</dt><dd className="font-medium break-words">{entry.reason}</dd></div>
      </dl>
      <h4 className="mt-5 text-xs font-semibold uppercase tracking-wide text-slate-500">Operations</h4>
      <ul className="mt-2 space-y-1.5 text-sm">
        {entry.ops.map((op, i) => {
          const d = describeOp(op)
          return (
            <li key={i} className="flex flex-wrap items-center gap-2 rounded-lg bg-slate-50 px-3 py-2">
              <Badge tone="indigo">{d.verb}</Badge><code className="break-all">{d.text}</code><span className="text-xs text-slate-500">at {d.at}</span>
            </li>
          )
        })}
        {!entry.ops.length && <li className="text-slate-500">No operations visible to your account.</li>}
      </ul>
      <h4 className="mt-5 text-xs font-semibold uppercase tracking-wide text-slate-500">Effect on stored values</h4>
      <DiffTable diff={entry.diff} />
      {rollback && <RollbackPanel version={entry.version} onDone={onClose} />}
    </Modal>
  )
}

export function DiffTable({ diff }) {
  if (!diff?.length) return <p className="mt-2 text-sm text-slate-500">No stored values changed.</p>
  return (
    <div className="mt-2 overflow-x-auto rounded-lg ring-1 ring-slate-200">
      <table className="min-w-full text-sm">
        <thead className="bg-slate-50 text-left text-xs text-slate-500"><tr><th className="px-3 py-2">Scope</th><th className="px-3 py-2">Key</th><th className="px-3 py-2">Before</th><th className="px-3 py-2">After</th></tr></thead>
        <tbody className="divide-y divide-slate-100">
          {diff.map((d, i) => (
            <tr key={i}>
              <td className="px-3 py-2 text-slate-600">{scopeText(d.scope)}</td>
              <td className="px-3 py-2"><code>{d.key}</code></td>
              <td className="px-3 py-2 text-rose-700">{d.from ?? <span className="text-slate-400">(not set)</span>}</td>
              <td className="px-3 py-2 text-emerald-700">{d.to ?? <span className="text-slate-400">(removed)</span>}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function RollbackPanel({ version, onDone }) {
  const { api, caps } = useAuth()
  const toast = useToast()
  const [level, setLevel] = useState(caps.operator ? 'GLOBAL' : 'CLIENT')
  const [clientId, setClientId] = useState(caps.tenant || '')
  const [reason, setReason] = useState('')
  const [preview, setPreview] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const allowed = caps.canRollback(level, clientId)
  const body = (dryRun) => ({ level, ...(level === 'CLIENT' ? { clientId: clientId.trim() } : {}), toVersion: version, reason: reason.trim() || 'preview', dryRun })

  const run = async (dryRun) => {
    if (level === 'CLIENT' && !clientId.trim()) return setError({ message: 'Enter the client to roll back.' })
    if (!dryRun && !reason.trim()) return setError({ message: 'Please give a reason; it is stored in the audit history.' })
    setBusy(true)
    setError(null)
    try {
      const res = await api.rollback(body(dryRun))
      if (dryRun) setPreview(res)
      else {
        toast.success(res.newVersion ? `Rolled back as version ${res.newVersion}` : 'Nothing to roll back', `${res.diff.length} value(s) restored.`)
        onDone()
      }
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="mt-6 space-y-3 rounded-xl border border-amber-200 bg-amber-50/50 p-4">
      <p className="text-sm font-semibold text-slate-900">Restore to version {version}</p>
      <p className="text-xs text-slate-600">Creates a new version whose stored values in the chosen scope equal those at v{version}. Versions never go backwards; staged rollouts in the scope are aborted.</p>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Scope">{(id) => (
          <Select id={id} value={level} onChange={(e) => { setLevel(e.target.value); setPreview(null) }}>
            {caps.operator && <option value="GLOBAL">GLOBAL values</option>}
            <option value="CLIENT">One client (all its values)</option>
          </Select>
        )}</Field>
        {level === 'CLIENT' && (
          <Field label="Client">{(id) => <Input id={id} value={clientId} disabled={!caps.operator} onChange={(e) => { setClientId(e.target.value); setPreview(null) }} placeholder="e.g. acme" />}</Field>
        )}
      </div>
      {!allowed && <p className="text-xs text-rose-600">Your role cannot roll back this scope.</p>}
      {preview && (
        <div>
          <p className="text-xs font-semibold text-slate-700">Preview: {preview.diff.length} change(s){preview.abortedRollouts?.length ? `, aborts rollouts ${preview.abortedRollouts.map((v) => 'v' + v).join(', ')}` : ''}</p>
          <DiffTable diff={preview.diff} />
        </div>
      )}
      <Field label="Reason (audit log)">{(id) => <Textarea id={id} value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} />}</Field>
      <ErrorBanner error={error} />
      <div className="flex flex-wrap justify-end gap-2">
        <Button variant="secondary" loading={busy && !preview} disabled={!allowed} onClick={() => run(true)}>Preview diff</Button>
        <Button variant="danger" icon={RotateCcw} loading={busy && !!preview} disabled={!allowed || !preview || !preview.diff.length} onClick={() => run(false)}>Apply rollback</Button>
      </div>
    </div>
  )
}
