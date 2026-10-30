import { Input, Select } from './ui.jsx'

const split = (s) => (s || '').split(/[,|]/).map((x) => x.trim()).filter(Boolean)

/** Human summary of a definition's rules. */
export function rulesHint(def) {
  if (!def) return ''
  const r = def.rules || {}
  switch (def.type) {
    case 'DURATION':
      return `Duration like 30m, 2h or PT45M${r.min || r.max ? ` · allowed ${r.min || '0'} … ${r.max || '∞'}` : ''}`
    case 'NUMBER':
      return `${r.integer === 'true' ? 'Whole number' : 'Number'}${r.min || r.max ? ` · ${r.min ?? '−∞'} … ${r.max ?? '∞'}` : ''}`
    case 'TIME_WINDOW':
      return `Start and end time${r.allowCrossMidnight === 'true' ? ', may cross midnight' : ', end after start'}${r.maxLength ? ` · max ${r.maxLength}` : ''}`
    case 'TIME':
      return 'Time of day (HH:mm)'
    case 'LIST':
      return `${r.minSize ? `At least ${r.minSize} · ` : ''}${r.maxSize ? `at most ${r.maxSize}` : ''}`.replace(/ · $/, '') || 'Comma-separated list'
    case 'ENUM':
      return 'One of the allowed values'
    default:
      return ''
  }
}

/** Basic client-side check; the server performs the authoritative validation. */
export function localProblem(def, value) {
  if (value === undefined || value === null || String(value).trim() === '') return 'A value is required.'
  if (def?.type === 'TIME_WINDOW' && !/^\d{2}:\d{2}-\d{2}:\d{2}$/.test(value)) return 'Choose a start and an end time.'
  if (def?.type === 'NUMBER' && Number.isNaN(Number(value))) return 'Enter a number.'
  return null
}

export function ValueEditor({ def, value, onChange, id }) {
  const r = def?.rules || {}
  switch (def?.type) {
    case 'ENUM':
      return (
        <Select id={id} value={value} onChange={(e) => onChange(e.target.value)}>
          <option value="">Select…</option>
          {split(r.values).map((v) => <option key={v} value={v}>{v}</option>)}
        </Select>
      )
    case 'LIST': {
      const allowed = split(r.allowed)
      if (!allowed.length) return <Input id={id} value={value} onChange={(e) => onChange(e.target.value)} placeholder="A,B,C" />
      const selected = new Set(split(value))
      const toggle = (v) => {
        const next = new Set(selected)
        next.has(v) ? next.delete(v) : next.add(v)
        onChange(allowed.filter((a) => next.has(a)).join(','))
      }
      return (
        <div id={id} className="flex flex-wrap gap-2">
          {allowed.map((v) => (
            <label key={v} className={`cursor-pointer rounded-lg px-2.5 py-1.5 text-sm ring-1 ring-inset ${selected.has(v) ? 'bg-indigo-50 text-indigo-700 ring-indigo-300' : 'bg-white text-slate-600 ring-slate-300'}`}>
              <input type="checkbox" className="sr-only" checked={selected.has(v)} onChange={() => toggle(v)} />
              {v}
            </label>
          ))}
        </div>
      )
    }
    case 'TIME':
      return <Input id={id} type="time" value={value} onChange={(e) => onChange(e.target.value)} />
    case 'TIME_WINDOW': {
      const [start = '', end = ''] = (value || '').split('-')
      return (
        <div id={id} className="flex items-center gap-2">
          <Input type="time" aria-label="Window start" value={start} onChange={(e) => onChange(`${e.target.value}-${end}`)} />
          <span className="text-slate-400">to</span>
          <Input type="time" aria-label="Window end" value={end} onChange={(e) => onChange(`${start}-${e.target.value}`)} />
        </div>
      )
    }
    case 'NUMBER':
      return <Input id={id} type="number" value={value} min={r.min} max={r.max} step={r.integer === 'true' ? 1 : 'any'} onChange={(e) => onChange(e.target.value)} />
    default:
      return <Input id={id} value={value} onChange={(e) => onChange(e.target.value)} placeholder="e.g. 30m" />
  }
}
