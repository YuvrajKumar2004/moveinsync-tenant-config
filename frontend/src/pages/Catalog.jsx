import { useState } from 'react'
import { BookOpen, Link2, Plus } from 'lucide-react'
import { useAuth } from '../state/AuthContext.jsx'
import { useLive } from '../state/LiveContext.jsx'
import { useToast } from '../state/ToastContext.jsx'
import { useAsync } from '../lib/useAsync.js'
import { displayValue } from '../lib/format.js'
import { Badge, Button, Card, EmptyState, ErrorBanner, Field, Input, Modal, PageHeader, Select, Spinner, Textarea } from '../components/ui.jsx'

const TYPES = ['NUMBER', 'DURATION', 'TIME', 'TIME_WINDOW', 'ENUM', 'LIST']
const RULE_HINT = {
  NUMBER: 'min=1\nmax=6\ninteger=true',
  DURATION: 'min=PT0M\nmax=PT4H',
  TIME: '',
  TIME_WINDOW: 'allowCrossMidnight=true\nmaxLength=PT12H',
  ENUM: 'values=ALWAYS,NEVER',
  LIST: 'allowed=SEDAN,SUV\nminSize=1',
}

export default function Catalog() {
  const { api, caps } = useAuth()
  const live = useLive()
  const defs = useAsync(() => api.definitions(), [live.tick])
  const [creating, setCreating] = useState(false)
  const list = defs.data?.definitions || []
  const constraints = defs.data?.constraints || []

  return (
    <>
      <PageHeader title="Settings catalog"
        description="Every setting's type, validation rules and global default. Values outside these rules are rejected before anything is published."
        actions={caps.canDefine && <Button icon={Plus} onClick={() => setCreating(true)}>Define setting</Button>} />
      <div className="space-y-5">
        <Card title={`${list.length} settings`} subtitle={defs.data ? `Catalog at version ${defs.data.version}` : undefined} bodyClassName="p-0">
          {defs.loading && !defs.data ? <Spinner /> : defs.error ? <ErrorBanner error={defs.error} onRetry={defs.reload} className="m-4" /> :
            !list.length ? <EmptyState icon={BookOpen} title="No settings defined" /> : (
              <div className="overflow-x-auto">
                <table className="min-w-full text-sm">
                  <thead className="bg-slate-50 text-left text-xs font-medium uppercase tracking-wide text-slate-500">
                    <tr><th className="px-4 py-2.5">Key</th><th className="px-4 py-2.5">Type</th><th className="px-4 py-2.5">Rules</th><th className="px-4 py-2.5">Global default</th></tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {list.map((d) => (
                      <tr key={d.key} className="align-top">
                        <td className="px-4 py-3"><code className="font-semibold">{d.key}</code><p className="mt-0.5 text-xs text-slate-500">{d.description}</p></td>
                        <td className="px-4 py-3"><Badge>{d.type}</Badge></td>
                        <td className="px-4 py-3"><div className="flex flex-wrap gap-1">{Object.entries(d.rules || {}).map(([k, v]) => <Badge key={k} tone="slate">{k}: {v}</Badge>)}{!Object.keys(d.rules || {}).length && <span className="text-xs text-slate-400">none</span>}</div></td>
                        <td className="whitespace-nowrap px-4 py-3 font-medium">{displayValue(d.defaultValue, d.type)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
        </Card>
        <Card title="Cross-key constraints" subtitle="Validated together at every scope a change can affect">
          {!constraints.length ? <p className="text-sm text-slate-500">None.</p> : (
            <ul className="space-y-2">
              {constraints.map((c) => (
                <li key={c.name} className="flex flex-wrap items-center gap-2 text-sm">
                  <Link2 className="h-4 w-4 text-indigo-500" /><code className="font-semibold">{c.name}</code><Badge tone="violet">{c.type}</Badge>
                  {Object.entries(c.params || {}).map(([k, v]) => <Badge key={k}>{k}: {v}</Badge>)}
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>
      {creating && <DefineModal onClose={() => setCreating(false)} onDone={() => { setCreating(false); defs.reload() }} />}
    </>
  )
}

function DefineModal({ onClose, onDone }) {
  const { api } = useAuth()
  const toast = useToast()
  const [form, setForm] = useState({ key: '', type: 'DURATION', rules: RULE_HINT.DURATION, defaultValue: '', description: '', reason: '' })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))

  const submit = async () => {
    const rules = {}
    for (const line of form.rules.split('\n').map((l) => l.trim()).filter(Boolean)) {
      const i = line.indexOf('=')
      if (i < 1) return setError({ message: `Rule "${line}" must look like name=value.` })
      rules[line.slice(0, i).trim()] = line.slice(i + 1).trim()
    }
    if (!form.key.trim() || !form.defaultValue.trim() || !form.reason.trim()) return setError({ message: 'Key, default value and reason are required.' })
    setBusy(true)
    setError(null)
    try {
      const res = await api.defineSetting({ key: form.key.trim(), type: form.type, rules, defaultValue: form.defaultValue.trim(), description: form.description.trim(), reason: form.reason.trim() })
      toast.success(`Defined ${form.key} in version ${res.version}`)
      onDone()
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal open title="Define a setting" onClose={onClose} footer={<Button loading={busy} onClick={submit}>Define</Button>}>
      <div className="space-y-3">
        <p className="text-xs text-slate-500">Redefining an existing key is allowed only if every stored value still satisfies the new rules.</p>
        <Field label="Key" hint="lower-case, e.g. pickup.grace">{(id) => <Input id={id} value={form.key} onChange={(e) => set({ key: e.target.value })} />}</Field>
        <Field label="Type">{(id) => (
          <Select id={id} value={form.type} onChange={(e) => set({ type: e.target.value, rules: RULE_HINT[e.target.value] })}>
            {TYPES.map((t) => <option key={t}>{t}</option>)}
          </Select>
        )}</Field>
        <Field label="Rules" hint="One name=value per line.">{(id) => <Textarea id={id} rows={3} value={form.rules} onChange={(e) => set({ rules: e.target.value })} className="font-mono" />}</Field>
        <Field label="Global default">{(id) => <Input id={id} value={form.defaultValue} onChange={(e) => set({ defaultValue: e.target.value })} />}</Field>
        <Field label="Description">{(id) => <Input id={id} value={form.description} onChange={(e) => set({ description: e.target.value })} />}</Field>
        <Field label="Reason (audit log)">{(id) => <Textarea id={id} value={form.reason} onChange={(e) => set({ reason: e.target.value })} maxLength={500} />}</Field>
        <ErrorBanner error={error} />
      </div>
    </Modal>
  )
}
