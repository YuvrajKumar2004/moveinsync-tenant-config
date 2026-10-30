import { useEffect, useState } from 'react'
import { useLive } from '../state/LiveContext.jsx'
import { relativeTime } from '../lib/format.js'
import { cx } from './ui.jsx'

const LOOK = {
  live: { dot: 'bg-emerald-500', ring: 'bg-emerald-400', text: 'Live · SSE', pulse: true },
  polling: { dot: 'bg-sky-500', ring: 'bg-sky-400', text: 'Live · polling 5 s', pulse: false },
  connecting: { dot: 'bg-amber-500', ring: 'bg-amber-400', text: 'Connecting…', pulse: true },
  reconnecting: { dot: 'bg-amber-500', ring: 'bg-amber-400', text: 'Reconnecting…', pulse: true },
  offline: { dot: 'bg-rose-500', ring: 'bg-rose-400', text: 'Offline', pulse: false },
  idle: { dot: 'bg-slate-400', ring: 'bg-slate-300', text: 'Idle', pulse: false },
}

export function useNow(ms = 1000) {
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), ms)
    return () => clearInterval(t)
  }, [ms])
  return now
}

export function LiveIndicator({ detailed }) {
  const live = useLive()
  const now = useNow()
  const look = LOOK[live.status] || LOOK.idle
  return (
    <div className="flex min-w-0 items-center gap-2 text-sm" title={live.error || undefined} data-testid="live-indicator">
      <span className="relative flex h-2.5 w-2.5">
        {look.pulse && <span className={cx('absolute inline-flex h-full w-full animate-ping rounded-full opacity-60', look.ring)} />}
        <span className={cx('relative inline-flex h-2.5 w-2.5 rounded-full', look.dot)} />
      </span>
      <span className="font-medium text-slate-700">{look.text}</span>
      {live.version !== null && <span className="hidden text-slate-500 sm:inline">· v{live.version}</span>}
      {detailed && <span className="text-slate-400">· {relativeTime(live.lastContact, now)}</span>}
    </div>
  )
}
