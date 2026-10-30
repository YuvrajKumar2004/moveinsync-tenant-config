import { useState } from 'react'
import { Navigate } from 'react-router-dom'
import { KeyRound, SlidersHorizontal } from 'lucide-react'
import { useAuth } from '../state/AuthContext.jsx'
import { Button, ErrorBanner, Field, Input } from '../components/ui.jsx'

export default function Login() {
  const { session, login, notice } = useAuth()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  if (session) return <Navigate to="/" replace />

  const submit = async (e) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await login(username.trim(), password)
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-slate-900 via-slate-800 to-indigo-900 px-4 py-10">
      <div className="w-full max-w-md">
        <div className="mb-6 flex items-center justify-center gap-3 text-white">
          <div className="rounded-xl bg-indigo-500 p-2"><SlidersHorizontal className="h-6 w-6" /></div>
          <div>
            <p className="text-lg font-semibold">Tenant Config Console</p>
            <p className="text-sm text-indigo-200">Hierarchical configuration with live propagation</p>
          </div>
        </div>
        <form onSubmit={submit} className="space-y-4 rounded-2xl bg-white p-6 shadow-xl">
          <h1 className="text-lg font-semibold text-slate-900">Sign in</h1>
          {notice && <p className="rounded-lg bg-amber-50 p-2.5 text-sm text-amber-800">{notice}</p>}
          <Field label="Username">{(id) => <Input id={id} value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required autoFocus />}</Field>
          <Field label="Password">{(id) => <Input id={id} type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />}</Field>
          <ErrorBanner error={error} />
          <Button type="submit" className="w-full" icon={KeyRound} loading={busy}>Sign in</Button>
          <p className="text-xs leading-relaxed text-slate-500">
            Accounts come from the config server. With the demo profile the users are <code>operator</code>, <code>acme-admin</code>,
            <code> globex-admin</code> and <code>acme-employee</code>; their passwords are the <code>DEMO_*_PASSWORD</code> values in your local
            <code> .env</code>. Nothing is stored in this app.
          </p>
        </form>
      </div>
    </div>
  )
}
