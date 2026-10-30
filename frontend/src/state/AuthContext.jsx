import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { createHttp } from '../api/client.js'
import { endpoints, requestToken } from '../api/endpoints.js'
import { capabilities } from '../lib/permissions.js'

// The access token is kept in sessionStorage: it survives a reload but not closing the tab, and is
// never written to localStorage, cookies or URLs. No credential is bundled with the app.
const KEY = 'tenant-config.session'
const AuthContext = createContext(null)

function readSession() {
  try {
    const s = JSON.parse(sessionStorage.getItem(KEY) || 'null')
    return s && s.expiresAt > Date.now() ? s : null
  } catch {
    return null
  }
}

export function AuthProvider({ children }) {
  const [session, setSession] = useState(readSession)
  const [notice, setNotice] = useState(null)
  const tokenRef = useRef(session?.token)
  tokenRef.current = session?.token

  const logout = useCallback((message) => {
    sessionStorage.removeItem(KEY)
    setSession(null)
    setNotice(message || null)
  }, [])

  const http = useMemo(
    () => createHttp(() => tokenRef.current, () => logout('Your session has expired. Please sign in again.')),
    [logout],
  )
  const api = useMemo(() => endpoints(http), [http])

  const login = useCallback(
    async (username, password) => {
      const res = await requestToken(http, username, password)
      const s = {
        token: res.accessToken,
        role: res.role,
        tenant: res.tenant || null,
        username,
        expiresAt: Date.now() + (res.expiresIn || 3600) * 1000,
      }
      sessionStorage.setItem(KEY, JSON.stringify(s))
      setNotice(null)
      setSession(s)
      return s
    },
    [http],
  )

  // sign out when the token expires while the app is open
  useEffect(() => {
    if (!session) return undefined
    const t = setTimeout(() => logout('Your session has expired. Please sign in again.'), session.expiresAt - Date.now())
    return () => clearTimeout(t)
  }, [session, logout])

  const value = useMemo(
    () => ({ session, caps: capabilities(session), api, login, logout, notice }),
    [session, api, login, logout, notice],
  )
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  return useContext(AuthContext)
}
