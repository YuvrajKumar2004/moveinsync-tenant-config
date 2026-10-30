import { useState } from 'react'
import { FileStack, Send, Trash2 } from 'lucide-react'
import { useAuth } from '../state/AuthContext.jsx'
import { useDraft, toWireOp } from '../state/DraftContext.jsx'
import { useLive } from '../state/LiveContext.jsx'
import { useToast } from '../state/ToastContext.jsx'
import { scopeText } from '../lib/format.js'
import { Badge, Button, Card, ErrorBanner, Field, Textarea } from './ui.jsx'

/** Pending change set: submitted as ONE atomic change set (all ops or none). */
export function DraftPanel({ baseVersion, onCommitted }) {
  const { api } = useAuth()
  const live = useLive()
  const toast = useToast()
  const { ops, remove, clear, idempotencyKey } = useDraft()
  const [reason, setReason] = useState('')
  const [lock, setLock] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  if (!ops.length) return null
  const expectedVersion = baseVersion ?? live.version

  const submit = async () => {
    if (!reason.trim()) {
      setError({ message: 'Please give a reason; it is stored in the audit history.' })
      return
    }
    setBusy(true)
    setError(null)
    try {
      const body = { reason: reason.trim(), ops: ops.map(toWireOp) }
      if (lock && expectedVersion != null) body.expectedVersion = expectedVersion
      const res = await api.commit(body, idempotencyKey)
      toast.success(`Committed version ${res.version}`, `${ops.length} change(s) applied atomically${res.replayed ? ' (replayed)' : ''}.`)
      clear()
      setReason('')
      onCommitted?.(res)
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card title={<span className="flex items-center gap-2"><FileStack className="h-4 w-4 text-indigo-500" />Pending change set</span>}
      subtitle="Applied together in one version, or not at all."
      actions={<Button variant="ghost" size="sm" icon={Trash2} onClick={clear}>Discard</Button>}
      className="border-indigo-200 ring-1 ring-indigo-100">
      <ul className="divide-y divide-slate-100">
        {ops.map((o) => (
          <li key={`${o.key}@${scopeText(o)}`} className="flex flex-wrap items-center gap-2 py-2 text-sm">
            <Badge tone={o.op === 'SET' ? 'indigo' : 'rose'}>{o.op}</Badge>
            <code className="font-medium text-slate-800">{o.key}</code>
            {o.op === 'SET' && <span className="text-slate-600">= <code>{o.value}</code></span>}
            <span className="text-xs text-slate-500">at {scopeText(o)}</span>
            <button onClick={() => remove(o)} className="ml-auto text-xs text-slate-400 hover:text-rose-600">remove</button>
          </li>
        ))}
      </ul>
      <div className="mt-3 space-y-3">
        <Field label="Reason (audit log)">
          {(id) => <Textarea id={id} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Why is this changing?" maxLength={500} />}
        </Field>
        <label className="flex items-start gap-2 text-xs text-slate-600">
          <input type="checkbox" className="mt-0.5" checked={lock} onChange={(e) => setLock(e.target.checked)} />
          <span>Reject if configuration changed after version {expectedVersion ?? '…'} (optimistic lock, <code>expectedVersion</code>)</span>
        </label>
        <ErrorBanner error={error} />
        <div className="flex justify-end">
          <Button icon={Send} loading={busy} onClick={submit}>Commit {ops.length} change{ops.length > 1 ? 's' : ''}</Button>
        </div>
      </div>
    </Card>
  )
}
