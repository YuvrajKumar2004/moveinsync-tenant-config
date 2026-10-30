import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { AuthProvider, useAuth } from './state/AuthContext.jsx'
import { LiveProvider } from './state/LiveContext.jsx'
import { ToastProvider } from './state/ToastContext.jsx'
import { DraftProvider } from './state/DraftContext.jsx'
import { Layout } from './components/Layout.jsx'
import Login from './pages/Login.jsx'
import Dashboard from './pages/Dashboard.jsx'
import Explorer from './pages/Explorer.jsx'
import History from './pages/History.jsx'
import Rollouts from './pages/Rollouts.jsx'
import Catalog from './pages/Catalog.jsx'
import { EmptyState } from './components/ui.jsx'

function RequireSession({ children }) {
  const { session } = useAuth()
  const location = useLocation()
  if (!session) return <Navigate to="/login" replace state={{ from: location.pathname }} />
  return children
}

function RequireCap({ cap, children }) {
  const { caps } = useAuth()
  if (!caps[cap]) return <EmptyState title="Not available for your role">Your account cannot use this page.</EmptyState>
  return children
}

export default function App() {
  return (
    <ToastProvider>
      <AuthProvider>
        <LiveProvider>
          <DraftProvider>
            <Routes>
              <Route path="/login" element={<Login />} />
              <Route element={<RequireSession><Layout /></RequireSession>}>
                <Route index element={<Dashboard />} />
                <Route path="explorer" element={<Explorer />} />
                <Route path="history" element={<RequireCap cap="canReadHistory"><History /></RequireCap>} />
                <Route path="rollouts" element={<RequireCap cap="canManageRollouts"><Rollouts /></RequireCap>} />
                <Route path="catalog" element={<Catalog />} />
                <Route path="*" element={<EmptyState title="Page not found" />} />
              </Route>
            </Routes>
          </DraftProvider>
        </LiveProvider>
      </AuthProvider>
    </ToastProvider>
  )
}
