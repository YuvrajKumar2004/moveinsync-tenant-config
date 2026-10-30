import { useState } from 'react'
import { GitBranch, Plus, Rocket, Trash2, XCircle } from 'lucide-react'
import { useAuth } from '../state/AuthContext.jsx'
import { useLive } from '../state/LiveContext.jsx'
import { useToast } from '../state/ToastContext.jsx'
import { useAsync } from '../lib/useAsync.js'
import { LEVELS, validateContext } from '../lib/scope.js'
import { scopeText } from '../lib/format.js'
import { Badge, Button, Card, EmptyState, ErrorBanner, Field, Input, Modal, PageHeader, Select, Spinner, Textarea } from '../components/ui.jsx'
import { ValueEditor, localProblem, rulesHint } from '../components/ValueEditor.jsx'

export default function Rollouts() {
  const { api } = useAuth()
  const live = useLive()
  const rollouts = useAsync(() => api.rollouts(), [live.tick])
  const defs = useAsync(() => api.definitions(), [])
  const [action, setAction] = useState(null)
  const [creating, setCreating] = useState(false)

  return (
    <>
      <PageHeader title="Staged rollouts"
        description="Stage a change for chosen offices first; only reads from those offices see it. Promote it to everyone in one atomic change, or abort it."
        actions={<Button icon={Plus} onClick={() => setCreating(true)}>New rollout</Button>} />

      <Card title="Active rollouts" subtitle="Ordered by the version that staged them" bodyClassName="p-0">
        {rollouts.loading && !rollouts.data ? <Spinner /> : rollouts.error ? <ErrorBanner error={rollouts.error} onRetry={rollouts.reload} className="m-4" /> :
          !rollouts.data.length ? <EmptyState icon={GitBranch} title="No active rollouts">Use <b>New rollout</b> to pilot a change on a few offices.</EmptyState> : (
            <ul className="divide-y divide-slate-100">
              {rollouts.data.map((r) => (
                <li key={r.version} className="flex flex-wrap items-start gap-4 px-4 py-4 sm:px-5" data-testid={`rollout-${r.version}`}>
                  <div className="min-w-0 flex-1 space-y-2">
                    <div className="flex items-center gap-2"><Badge tone="amber">Rollout v{r.version}</Badge></div>
                    <div className="flex flex-wrap items-center gap-1.5 text-xs text-slate-600">Targets:
                      {r.targets.map((t) => <Badge key={t} tone="sky">{t}</Badge>)}
                    </div>
                    <ul className="space-y-1 text-sm">
                      {r.items.map((i, n) => (
                        <li key={n}><code className="font-medium">{i.key}</code> = <code>{i.value}</code> <span className="text-xs text-slate-500">at {scopeText(i)}</span></li>
                      ))}
                    </ul>
                  </div>
                  <div className="flex gap-2">
                    <Button size="sm" icon={Rocket} onClick={() => setAction({ kind: 'promote', rollout: r })}>Promote</Button>
                    <Button size="sm" variant="secondary" icon={XCircle} onClick={() => setAction({ kind: 'abort', rollout: r })}>Abort</Button>
                  </div>
                </li>
              ))}
            </ul>
          )}
      </Card>

      {action && <ActionModal {...action} onClose={() => setAction(null)} onDone={() => { setAction(null); rollouts.reload() }} />}
      {creating && <CreateModal defs={defs.data?.definitions || []} onClose={() => setCreating(false)} onDone={() => { setCreating(false); rollouts.reload() }} />}
    </>
  )
}

function ActionModal({ kind, rollout, onClose, onDone }) {
  const { api } = useAuth()
  const toast = useToast()
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const promote = kind === 'promote'
  const submit = async () => {
    if (!reason.trim()) return setError({ message: 'Please give a reason; it is stored in the audit history.' })
    setBusy(true)
    setError(null)
    try {
      const res = promote ? await api.promote(rollout.version, reason.trim()) : await api.abort(rollout.version, reason.trim())
      toast.success(promote ? `Promoted as version ${res.version}` : `Aborted in version ${res.version}`,
        promote ? 'The staged values now apply to everyone.' : 'The staged values were removed.')
      onDone()
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }
  return (
    <Modal open title={`${promote ? 'Promote' : 'Abort'} rollout v${rollout.version}`} onClose={onClose}
      footer={<Button variant={promote ? 'primary' : 'danger'} loading={busy} onClick={submit}>{promote ? 'Promote to everyone' : 'Abort rollout'}</Button>}>
      <div className="space-y-3">
        <p className="text-sm text-slate-600">{promote
          ? 'In one atomic change set the staging is removed and its values are stored for everyone.'
          : 'The staged values disappear; target offices fall back to the live values.'}</p>
        <Field label="Reason (audit log)">{(id) => <Textarea id={id} value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} />}</Field>
        <ErrorBanner error={error} />
      </div>
    </Modal>
  )
}

const blankItem = (tenant) => ({ level: tenant ? 'CLIENT' : 'GLOBAL', clientId: tenant || '', officeId: '', shiftId: '', key: '', value: '' })

function CreateModal({ defs, onClose, onDone }) {
  const { api, caps } = useAuth()
  const toast = useToast()
  const tenant = caps.operator ? '' : caps.tenant
  const [targets, setTargets] = useState([{ clientId: tenant, officeId: '' }, { clientId: tenant, officeId: '' }])
  const [items, setItems] = useState([blankItem(tenant)])
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const levels = caps.operator ? LEVELS : LEVELS.filter((l) => l !== 'GLOBAL')

  const setTarget = (i, patch) => setTargets((t) => t.map((x, n) => (n === i ? { ...x, ...patch } : x)))
  const setItem = (i, patch) => setItems((t) => t.map((x, n) => (n === i ? { ...x, ...patch } : x)))

  const submit = async () => {
    const ts = targets.filter((t) => t.clientId.trim() || t.officeId.trim()).map((t) => ({ clientId: t.clientId.trim(), officeId: t.officeId.trim() }))
    if (!ts.length) return setError({ message: 'Add at least one target office.' })
    for (const t of ts) {
      const p = !t.clientId || !t.officeId ? 'Each target needs a client and an office.' : validateContext(t)
      if (p) return setError({ message: p })
    }
    const its = []
    for (const it of items) {
      const def = defs.find((d) => d.key === it.key)
      if (!def) return setError({ message: 'Choose a setting for every item.' })
      const p = localProblem(def, it.value)
      if (p) return setError({ message: `${it.key}: ${p}` })
      const o = { op: 'SET', level: it.level, key: it.key, value: String(it.value).trim() }
      if (it.level !== 'GLOBAL') o.clientId = it.clientId.trim()
      if (it.level === 'OFFICE' || it.level === 'SHIFT') o.officeId = it.officeId.trim()
      if (it.level === 'SHIFT') o.shiftId = it.shiftId.trim()
      its.push(o)
    }
    if (!reason.trim()) return setError({ message: 'Please give a reason; it is stored in the audit history.' })
    setBusy(true)
    setError(null)
    try {
      const res = await api.stage({ reason: reason.trim(), targets: ts, items: its })
      toast.success(`Staged as rollout v${res.version}`, `${its.length} item(s) for ${ts.length} office(s).`)
      onDone()
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal open wide title="New staged rollout" onClose={onClose} footer={<Button loading={busy} onClick={submit}>Stage rollout</Button>}>
      <div className="space-y-5">
        <section>
          <h4 className="text-sm font-semibold">Target offices</h4>
          <p className="text-xs text-slate-500">Only reads from these offices see the staged values.</p>
          <div className="mt-2 space-y-2">
            {targets.map((t, i) => (
              <div key={i} className="flex gap-2">
                <Input aria-label="Target client" value={t.clientId} disabled={!!tenant} onChange={(e) => setTarget(i, { clientId: e.target.value })} placeholder="client, e.g. acme" />
                <Input aria-label="Target office" value={t.officeId} onChange={(e) => setTarget(i, { officeId: e.target.value })} placeholder="office, e.g. blr-1" />
                <Button variant="ghost" icon={Trash2} aria-label="Remove target" onClick={() => setTargets((x) => x.filter((_, n) => n !== i))} disabled={targets.length === 1} />
              </div>
            ))}
            <Button size="sm" variant="secondary" icon={Plus} onClick={() => setTargets((x) => [...x, { clientId: tenant, officeId: '' }])}>Add target</Button>
          </div>
        </section>
        <section>
          <h4 className="text-sm font-semibold">Staged values</h4>
          <p className="text-xs text-slate-500">A value at GLOBAL or CLIENT level is still only visible to the target offices.</p>
          <div className="mt-2 space-y-3">
            {items.map((it, i) => {
              const def = defs.find((d) => d.key === it.key)
              return (
                <div key={i} className="space-y-2 rounded-xl border border-slate-200 p-3">
                  <div className="grid gap-2 sm:grid-cols-4">
                    <Select aria-label="Level" value={it.level} onChange={(e) => setItem(i, { level: e.target.value })}>
                      {levels.map((l) => <option key={l}>{l}</option>)}
                    </Select>
                    {it.level !== 'GLOBAL' && <Input aria-label="Client" value={it.clientId} disabled={!!tenant} onChange={(e) => setItem(i, { clientId: e.target.value })} placeholder="client" />}
                    {(it.level === 'OFFICE' || it.level === 'SHIFT') && <Input aria-label="Office" value={it.officeId} onChange={(e) => setItem(i, { officeId: e.target.value })} placeholder="office" />}
                    {it.level === 'SHIFT' && <Input aria-label="Shift" value={it.shiftId} onChange={(e) => setItem(i, { shiftId: e.target.value })} placeholder="shift" />}
                  </div>
                  <div className="grid gap-2 sm:grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)_auto]">
                    <Select aria-label="Setting" value={it.key} onChange={(e) => setItem(i, { key: e.target.value, value: defs.find((d) => d.key === e.target.value)?.defaultValue || '' })}>
                      <option value="">Choose setting…</option>
                      {defs.map((d) => <option key={d.key} value={d.key}>{d.key}</option>)}
                    </Select>
                    {def ? <ValueEditor def={def} value={it.value} onChange={(v) => setItem(i, { value: v })} /> : <div />}
                    <Button variant="ghost" icon={Trash2} aria-label="Remove item" onClick={() => setItems((x) => x.filter((_, n) => n !== i))} disabled={items.length === 1} />
                  </div>
                  {def && <p className="text-xs text-slate-500">{rulesHint(def)}</p>}
                </div>
              )
            })}
            <Button size="sm" variant="secondary" icon={Plus} onClick={() => setItems((x) => [...x, blankItem(tenant)])}>Add value</Button>
          </div>
        </section>
        <Field label="Reason (audit log)">{(id) => <Textarea id={id} value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} />}</Field>
        <ErrorBanner error={error} />
      </div>
    </Modal>
  )
}
