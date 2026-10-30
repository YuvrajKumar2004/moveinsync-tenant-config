import { useEffect, useState } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { BookOpen, GitBranch, History, LayoutDashboard, LogOut, Menu, Network, SlidersHorizontal, X } from 'lucide-react'
import { useAuth } from '../state/AuthContext.jsx'
import { LiveIndicator } from './LiveIndicator.jsx'
import { Badge, cx } from './ui.jsx'

function navItems(caps) {
  return [
    { to: '/', label: 'Dashboard', icon: LayoutDashboard, end: true },
    { to: '/explorer', label: 'Configuration explorer', icon: Network },
    caps.canReadHistory && { to: '/history', label: 'History & rollback', icon: History },
    caps.canManageRollouts && { to: '/rollouts', label: 'Staged rollouts', icon: GitBranch },
    { to: '/catalog', label: 'Settings catalog', icon: BookOpen },
  ].filter(Boolean)
}

function Nav({ caps, onNavigate }) {
  return (
    <nav className="flex flex-col gap-1">
      {navItems(caps).map(({ to, label, icon: Icon, end }) => (
        <NavLink key={to} to={to} end={end} onClick={onNavigate}
          className={({ isActive }) => cx(
            'flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition',
            isActive ? 'bg-white/10 text-white' : 'text-slate-300 hover:bg-white/5 hover:text-white',
          )}>
          <Icon className="h-4 w-4" />
          {label}
        </NavLink>
      ))}
    </nav>
  )
}

function Brand() {
  return (
    <div className="flex items-center gap-2.5 px-2">
      <div className="rounded-lg bg-indigo-500 p-1.5"><SlidersHorizontal className="h-4 w-4 text-white" /></div>
      <div className="leading-tight">
        <p className="text-sm font-semibold text-white">Tenant Config</p>
        <p className="text-[11px] text-slate-400">Configuration console</p>
      </div>
    </div>
  )
}

export function Layout() {
  const { session, caps, logout } = useAuth()
  const [open, setOpen] = useState(false)
  const location = useLocation()
  useEffect(() => setOpen(false), [location.pathname])

  const user = (
    <div className="flex items-center gap-2">
      <div className="hidden text-right sm:block">
        <p className="text-sm font-medium text-slate-800">{session.username}</p>
        <p className="text-xs text-slate-500">{caps.tenant ? `tenant ${caps.tenant}` : 'all tenants'}</p>
      </div>
      <Badge tone={caps.operator ? 'violet' : caps.role === 'ADMIN' ? 'indigo' : 'slate'}>{caps.role}</Badge>
      <button onClick={() => logout()} className="rounded-lg p-2 text-slate-500 hover:bg-slate-100 hover:text-slate-700" title="Sign out" aria-label="Sign out">
        <LogOut className="h-4 w-4" />
      </button>
    </div>
  )

  return (
    <div className="min-h-screen lg:pl-64">
      {/* desktop sidebar */}
      <aside className="fixed inset-y-0 left-0 hidden w-64 flex-col gap-6 bg-slate-900 px-3 py-5 lg:flex">
        <Brand />
        <Nav caps={caps} />
        <p className="mt-auto px-3 text-[11px] leading-relaxed text-slate-500">
          GLOBAL → CLIENT → OFFICE → SHIFT. Most specific value wins.
        </p>
      </aside>

      {/* mobile drawer */}
      {open && (
        <div className="fixed inset-0 z-40 lg:hidden">
          <div className="absolute inset-0 bg-slate-900/50" onClick={() => setOpen(false)} />
          <aside className="absolute inset-y-0 left-0 flex w-72 max-w-[85vw] flex-col gap-6 bg-slate-900 px-3 py-5">
            <div className="flex items-center justify-between">
              <Brand />
              <button onClick={() => setOpen(false)} className="rounded-md p-1 text-slate-400 hover:text-white" aria-label="Close menu"><X className="h-5 w-5" /></button>
            </div>
            <Nav caps={caps} onNavigate={() => setOpen(false)} />
          </aside>
        </div>
      )}

      <header className="sticky top-0 z-30 flex h-14 items-center gap-3 border-b border-slate-200 bg-white/90 px-4 backdrop-blur sm:px-6">
        <button onClick={() => setOpen(true)} className="rounded-md p-1.5 text-slate-600 hover:bg-slate-100 lg:hidden" aria-label="Open menu">
          <Menu className="h-5 w-5" />
        </button>
        <LiveIndicator />
        <div className="ml-auto">{user}</div>
      </header>

      <main className="mx-auto max-w-7xl px-4 py-6 sm:px-6 lg:px-8">
        <Outlet />
      </main>
    </div>
  )
}
