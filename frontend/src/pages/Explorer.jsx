import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { ChevronRight, Globe2, Building2, Clock3, Users, Pencil, Eraser, Search, ArrowRight, Lock } from 'lucide-react'
import { useAuth } from '../state/AuthContext.jsx'
import { useLive } from '../state/LiveContext.jsx'
import { useToast } from '../state/ToastContext.jsx'
import { useDraft, toWireOp } from '../state/DraftContext.jsx'
import { useAsync } from '../lib/useAsync.js'
import { ancestry, collectScopes, contextLevel, opScope, provenance, scopeLabel, validateContext } from '../lib/scope.js'
import { displayValue } from '../lib/format.js'
import { Badge, Button, Card, EmptyState, ErrorBanner, Field, Input, Modal, PageHeader, Select, Spinner, Textarea, cx } from '../components/ui.jsx'
import { ValueEditor, localProblem, rulesHint } from '../components/ValueEditor.jsx'
import { DraftPanel } from '../components/DraftPanel.jsx'

const LEVEL_ICON = { GLOBAL: Globe2, CLIENT: Users, OFFICE: Building2, SHIFT: Clock3 }
const PROVENANCE = {
  here: { tone: 'indigo', text: 'Set here' },
  inherited: { tone: 'slate', text: 'Inherited' },
  default: { tone: 'sky', text: 'Global default' },
  staged: { tone: 'amber', text: 'Staged rollout' },
}

async function loadKnownScopes(api, caps) {
  if (caps.canUseSync) {
    const s = await api.snapshot()
    const fromRollouts = (s.rollouts || []).flatMap((r) => [
      ...(r.items || []),
      ...(r.targets || []).map((t) => ({ clientId: t.split('/')[0], officeId: t.split('/')[1] })),
    ])
    return collectScopes([...(s.overrides || []), ...fromRollouts])
  }
  if (caps.canReadHistory) {
    const h = await api.history({ limit: 200 })
    const entries = h.flatMap((e) => (e.ops || []).flatMap((op) => [op, ...(op.items || []),
      ...(op.targets || []).map((t) => ({ clientId: t.split('/')[0], officeId: t.split('/')[1] }))]))
    return collectScopes(entries)
  }
  return []
}

export default function Explorer() {
  const { api, caps } = useAuth()
  const live = useLive()
  const draft = useDraft()
  const [params, setParams] = useSearchParams()
  const ctx = {
    clientId: params.get('client') || '',
    officeId: params.get('office') || '',
    shiftId: params.get('shift') || '',
  }
  const go = (c) => {
    const next = {}
    if (c.clientId) next.client = c.clientId
    if (c.officeId) next.office = c.officeId
    if (c.shiftId) next.shift = c.shiftId
    setParams(next)
  }
  const ctxError = validateContext(ctx) || (caps.readableClient && ctx.clientId && ctx.clientId !== caps.readableClient
    ? `Your account can only read client "${caps.readableClient}".` : null)

  const defs = useAsync(() => api.definitions(), [live.tick])
  const chain = useAsync(
    () => Promise.all(ancestry(ctx).map((c) => api.resolve({ ...c, explain: true }))),
    [ctx.clientId, ctx.officeId, ctx.shiftId, live.tick],
    { enabled: !ctxError },
  )
  const scopes = useAsync(() => loadKnownScopes(api, caps), [live.tick])

  const [filter, setFilter] = useState('')
  const [editing, setEditing] = useState(null)
  const [removing, setRemoving] = useState(null)

  const level = contextLevel(ctx)
  const label = scopeLabel(ctx)
  const contexts = ancestry(ctx) // most specific first
  const byLevel = useMemo(() => {
    if (!chain.data) return null
    return chain.data.map((res) => Object.fromEntries(res.values.map((v) => [v.key, v])))
  }, [chain.data])
  const snapshotVersion = chain.data?.[0]?.snapshotVersion

  const rows = useMemo(() => {
    const list = defs.data?.definitions || []
    const q = filter.trim().toLowerCase()
    return list.filter((d) => !q || d.key.toLowerCase().includes(q) || (d.description || '').toLowerCase().includes(q))
  }, [defs.data, filter])

  const writable = (c) => caps.canWriteScope(opScope(c))

  return (
    <>
      <PageHeader title="Configuration explorer"
        description="Pick a scope to see every setting's effective value, which level supplies it, and the full GLOBAL → CLIENT → OFFICE → SHIFT inheritance chain." />

      <div className="grid gap-5 lg:grid-cols-[280px_minmax(0,1fr)]">
        <div className="space-y-5">
          <ScopePicker ctx={ctx} caps={caps} onGo={go} />
          <Card title="Known scopes" subtitle={caps.canUseSync ? 'Scopes that hold values (from the server snapshot)' : caps.canReadHistory ? "From your tenant's change history" : 'Type a scope above'} bodyClassName="p-2">
            {scopes.loading && !scopes.data ? <Spinner /> : scopes.error ? <ErrorBanner error={scopes.error} className="m-2" /> : (
              <ScopeTree tree={scopes.data || []} ctx={ctx} onGo={go} caps={caps} />
            )}
          </Card>
        </div>

        <div className="min-w-0 space-y-5">
          <Breadcrumb contexts={[...contexts].reverse()} current={label} onGo={go} />
          <DraftPanel baseVersion={snapshotVersion} onCommitted={() => chain.reload()} />

          <Card
            title={<span className="flex items-center gap-2">{(() => { const I = LEVEL_ICON[level]; return <I className="h-4 w-4 text-indigo-500" /> })()}{label}</span>}
            subtitle={snapshotVersion ? `Resolved at configuration version ${snapshotVersion}` : 'Resolving…'}
            actions={
              <div className="relative">
                <Search className="pointer-events-none absolute left-2.5 top-2.5 h-4 w-4 text-slate-400" />
                <Input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="Filter settings" className="w-48 pl-8" aria-label="Filter settings" />
              </div>
            }
            bodyClassName="p-0">
            {ctxError ? <ErrorBanner error={{ message: ctxError }} className="m-4" /> :
              (chain.loading && !chain.data) || (defs.loading && !defs.data) ? <Spinner label="Resolving configuration…" /> :
              chain.error || defs.error ? <ErrorBanner error={chain.error || defs.error} onRetry={() => { chain.reload(); defs.reload() }} className="m-4" /> :
              !rows.length ? <EmptyState title="No settings match" /> : (
                <ul className="divide-y divide-slate-100">
                  {rows.map((def) => {
                    const r = byLevel[0][def.key]
                    if (!r) return null
                    const prov = provenance(r, label)
                    const p = PROVENANCE[prov]
                    const canEdit = contexts.some(writable)
                    const canRemove = prov === 'here' && writable(ctx)
                    return (
                      <li key={def.key} className="px-4 py-3 sm:px-5" data-testid={`setting-${def.key}`}>
                        <div className="flex flex-wrap items-start gap-x-4 gap-y-2">
                          <div className="min-w-0 flex-1 basis-60">
                            <div className="flex flex-wrap items-center gap-2">
                              <code className="text-sm font-semibold text-slate-900">{def.key}</code>
                              <Badge>{def.type}</Badge>
                            </div>
                            <p className="mt-0.5 text-xs text-slate-500">{def.description}</p>
                          </div>
                          <div className="min-w-0 basis-40 text-right sm:text-left">
                            <p className="text-sm font-semibold text-slate-900" data-testid="effective-value">{displayValue(r.value, def.type)}</p>
                            <div className="mt-1 flex flex-wrap items-center gap-1 sm:justify-start">
                              <Badge tone={p.tone} title={`source ${r.source} at ${r.scope}`}>{p.text}</Badge>
                              {prov === 'inherited' && <span className="text-xs text-slate-500">from {r.level}</span>}
                              {prov === 'staged' && <span className="text-xs text-slate-500">rollout v{r.rolloutVersion}</span>}
                            </div>
                          </div>
                          <div className="flex gap-1.5">
                            <Button size="sm" variant="secondary" icon={canEdit ? Pencil : Lock} disabled={!canEdit}
                              title={canEdit ? 'Change this value' : 'Your role cannot change this scope'}
                              onClick={() => setEditing({ def, resolved: r })}>Edit</Button>
                            {prov === 'here' && (
                              <Button size="sm" variant="ghost" icon={Eraser} disabled={!canRemove}
                                title="Remove the value stored at this scope; it will inherit again" onClick={() => setRemoving({ def, resolved: r })}>Remove</Button>
                            )}
                          </div>
                        </div>
                        <InheritanceChain contexts={[...contexts].reverse()} results={[...byLevel].reverse()} keyName={def.key} type={def.type} />
                      </li>
                    )
                  })}
                </ul>
              )}
          </Card>
        </div>
      </div>

      {editing && (
        <EditModal def={editing.def} resolved={editing.resolved} contexts={contexts.filter(writable)} version={snapshotVersion}
          onClose={() => setEditing(null)} onCommitted={() => { setEditing(null); chain.reload() }} onDraft={(op) => { draft.add(op); setEditing(null) }} />
      )}
      {removing && (
        <RemoveModal def={removing.def} ctx={ctx} version={snapshotVersion}
          onClose={() => setRemoving(null)} onCommitted={() => { setRemoving(null); chain.reload() }} onDraft={(op) => { draft.add(op); setRemoving(null) }} />
      )}
    </>
  )
}

function InheritanceChain({ contexts, results, keyName, type }) {
  return (
    <ol className="mt-2 flex flex-wrap items-center gap-1 text-xs" aria-label="Inheritance chain">
      {contexts.map((c, i) => {
        const r = results[i]?.[keyName]
        const lbl = scopeLabel(c)
        const prov = r ? provenance(r, lbl) : 'default'
        const origin = prov === 'here' || prov === 'staged' || (prov === 'default' && i === 0)
        return (
          <li key={lbl} className="flex items-center gap-1">
            {i > 0 && <ChevronRight className="h-3 w-3 text-slate-300" />}
            <span className={cx('rounded-md px-1.5 py-0.5 ring-1 ring-inset',
              prov === 'here' ? 'bg-indigo-50 font-semibold text-indigo-700 ring-indigo-200' :
              prov === 'staged' ? 'bg-amber-50 font-semibold text-amber-800 ring-amber-200' :
              origin ? 'bg-sky-50 text-sky-700 ring-sky-200' : 'bg-white text-slate-500 ring-slate-200')}>
              {contextLevel(c)} {r ? displayValue(r.value, type) : '…'}
            </span>
          </li>
        )
      })}
    </ol>
  )
}

function Breadcrumb({ contexts, current, onGo }) {
  return (
    <nav aria-label="Scope" className="flex flex-wrap items-center gap-1 text-sm">
      {contexts.map((c, i) => {
        const lbl = scopeLabel(c)
        const Icon = LEVEL_ICON[contextLevel(c)]
        const ids = [c.clientId, c.officeId, c.shiftId].filter(Boolean)
        return (
          <span key={lbl} className="flex items-center gap-1">
            {i > 0 && <ChevronRight className="h-4 w-4 text-slate-300" />}
            <button onClick={() => onGo(c)} className={cx('flex items-center gap-1 rounded-md px-2 py-1',
              lbl === current ? 'bg-indigo-600 text-white' : 'bg-white text-slate-600 ring-1 ring-slate-200 hover:bg-slate-50')}>
              <Icon className="h-3.5 w-3.5" />
              {contextLevel(c)}{ids.length ? ` ${ids[ids.length - 1]}` : ''}
            </button>
          </span>
        )
      })}
    </nav>
  )
}

function ScopePicker({ ctx, caps, onGo }) {
  const [form, setForm] = useState(ctx)
  const ctxKey = scopeLabel(ctx)
  // follow navigation done elsewhere (tree, breadcrumb)
  useEffect(() => setForm(ctx), [ctxKey]) // eslint-disable-line react-hooks/exhaustive-deps
  const [error, setError] = useState(null)
  const locked = !!caps.readableClient
  const submit = (e) => {
    e.preventDefault()
    const c = { clientId: form.clientId.trim(), officeId: form.officeId.trim(), shiftId: form.shiftId.trim() }
    const problem = validateContext(c)
    if (problem) return setError(problem)
    setError(null)
    onGo(c)
  }
  return (
    <Card title="Open a scope" subtitle="Leave fields empty to go up the hierarchy.">
      <form onSubmit={submit} className="space-y-3">
        <Field label="Client" hint={locked ? `Your account is limited to tenant ${caps.readableClient}.` : undefined}>
          {(id) => locked ? (
            <Select id={id} value={form.clientId} onChange={(e) => setForm({ ...form, clientId: e.target.value })}>
              <option value="">(GLOBAL)</option>
              <option value={caps.readableClient}>{caps.readableClient}</option>
            </Select>
          ) : (
            <Input id={id} value={form.clientId} onChange={(e) => setForm({ ...form, clientId: e.target.value })} placeholder="e.g. acme" />
          )}
        </Field>
        <Field label="Office">{(id) => <Input id={id} value={form.officeId} onChange={(e) => setForm({ ...form, officeId: e.target.value })} placeholder="e.g. blr-1" />}</Field>
        <Field label="Shift">{(id) => <Input id={id} value={form.shiftId} onChange={(e) => setForm({ ...form, shiftId: e.target.value })} placeholder="e.g. night" />}</Field>
        {error && <p className="text-xs text-rose-600">{error}</p>}
        <Button type="submit" className="w-full" icon={ArrowRight}>Open scope</Button>
      </form>
    </Card>
  )
}

function ScopeTree({ tree, ctx, onGo, caps }) {
  const isCur = (c) => scopeLabel(c) === scopeLabel(ctx)
  const item = (c, text, depth, Icon) => (
    <button key={scopeLabel(c)} onClick={() => onGo(c)} style={{ paddingLeft: 8 + depth * 14 }}
      className={cx('flex w-full items-center gap-2 rounded-md py-1.5 pr-2 text-left text-sm',
        isCur(c) ? 'bg-indigo-50 font-medium text-indigo-700' : 'text-slate-600 hover:bg-slate-50')}>
      <Icon className="h-3.5 w-3.5 shrink-0" /><span className="truncate">{text}</span>
    </button>
  )
  const clients = tree.length ? tree : caps.readableClient ? [{ clientId: caps.readableClient, offices: [] }] : []
  return (
    <div className="space-y-0.5">
      {item({}, 'GLOBAL', 0, Globe2)}
      {clients.map((c) => (
        <div key={c.clientId}>
          {item({ clientId: c.clientId }, c.clientId, 1, Users)}
          {c.offices.map((o) => (
            <div key={o.officeId}>
              {item({ clientId: c.clientId, officeId: o.officeId }, o.officeId, 2, Building2)}
              {o.shifts.map((s) => item({ clientId: c.clientId, officeId: o.officeId, shiftId: s }, s, 3, Clock3))}
            </div>
          ))}
        </div>
      ))}
      {!clients.length && <p className="px-2 py-3 text-xs text-slate-500">No client-level values yet.</p>}
    </div>
  )
}

function EditModal({ def, resolved, contexts, version, onClose, onCommitted, onDraft }) {
  const { api } = useAuth()
  const toast = useToast()
  const [target, setTarget] = useState(scopeLabel(contexts[0] || {}))
  const [value, setValue] = useState(resolved.value || '')
  const [reason, setReason] = useState('')
  const [lock, setLock] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const ctx = contexts.find((c) => scopeLabel(c) === target) || {}
  const op = { op: 'SET', ...opScope(ctx), key: def.key, value: String(value).trim() }
  const problem = localProblem(def, value)

  const save = async () => {
    if (problem) return setError({ message: problem })
    if (!reason.trim()) return setError({ message: 'Please give a reason; it is stored in the audit history.' })
    setBusy(true)
    setError(null)
    try {
      const body = { reason: reason.trim(), ops: [toWireOp(op)] }
      if (lock && version != null) body.expectedVersion = version
      const res = await api.commit(body)
      const d = res.diff?.[0]
      toast.success(`Saved as version ${res.version}`, d ? `${def.key}: ${d.from ?? '(inherited)'} → ${d.to ?? '(inherited)'}` : 'No effective change.')
      onCommitted()
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal open title={`Edit ${def.key}`} onClose={onClose}
      footer={<>
        <Button variant="secondary" onClick={() => (problem ? setError({ message: problem }) : onDraft(op))}>Add to change set</Button>
        <Button loading={busy} onClick={save}>Save now</Button>
      </>}>
      <div className="space-y-4">
        <div className="rounded-xl bg-slate-50 p-3 text-sm">
          <p className="text-slate-600">{def.description}</p>
          <p className="mt-1 text-xs text-slate-500">Currently <b>{displayValue(resolved.value, def.type)}</b> from {resolved.scope} ({resolved.source.toLowerCase()})</p>
        </div>
        <Field label="Store the value at" hint="Lower levels override higher ones for everything below them.">
          {(id) => (
            <Select id={id} value={target} onChange={(e) => setTarget(e.target.value)}>
              {contexts.map((c) => <option key={scopeLabel(c)} value={scopeLabel(c)}>{scopeLabel(c)}</option>)}
            </Select>
          )}
        </Field>
        <Field label="New value" hint={rulesHint(def)}>{(id) => <ValueEditor id={id} def={def} value={value} onChange={setValue} />}</Field>
        <Field label="Reason (audit log)">{(id) => <Textarea id={id} value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} placeholder="Why is this changing?" />}</Field>
        <label className="flex items-start gap-2 text-xs text-slate-600">
          <input type="checkbox" className="mt-0.5" checked={lock} onChange={(e) => setLock(e.target.checked)} />
          Reject if configuration changed after version {version} (optimistic lock)
        </label>
        <ErrorBanner error={error} />
      </div>
    </Modal>
  )
}

function RemoveModal({ def, ctx, version, onClose, onCommitted, onDraft }) {
  const { api } = useAuth()
  const toast = useToast()
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const op = { op: 'REMOVE', ...opScope(ctx), key: def.key }
  const save = async () => {
    if (!reason.trim()) return setError({ message: 'Please give a reason; it is stored in the audit history.' })
    setBusy(true)
    setError(null)
    try {
      const res = await api.commit({ reason: reason.trim(), expectedVersion: version, ops: [toWireOp(op)] })
      toast.success(`Removed in version ${res.version}`, `${def.key} now inherits at ${scopeLabel(ctx)}.`)
      onCommitted()
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }
  return (
    <Modal open title={`Remove ${def.key} at ${scopeLabel(ctx)}`} onClose={onClose}
      footer={<>
        <Button variant="secondary" onClick={() => onDraft(op)}>Add to change set</Button>
        <Button variant="danger" loading={busy} onClick={save}>Remove now</Button>
      </>}>
      <div className="space-y-3">
        <p className="text-sm text-slate-600">The value stored at this scope is deleted; reads here fall back to the parent level.</p>
        <Field label="Reason (audit log)">{(id) => <Textarea id={id} value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} />}</Field>
        <ErrorBanner error={error} />
      </div>
    </Modal>
  )
}
