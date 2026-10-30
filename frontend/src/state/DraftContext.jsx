import { createContext, useCallback, useContext, useMemo, useState } from 'react'

// A pending change set built across scopes in the explorer and committed atomically in one request.
const DraftContext = createContext(null)
const sameTarget = (a, b) =>
  a.key === b.key && a.level === b.level && a.clientId === b.clientId && a.officeId === b.officeId && a.shiftId === b.shiftId

const newKey = () => (globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random()}`).replace(/[^A-Za-z0-9._:-]/g, '')

export function DraftProvider({ children }) {
  const [ops, setOps] = useState([])
  // one idempotency key per draft content: retrying the same submit can never commit twice, and any
  // edit to the draft gets a fresh key so it is never mistaken for an earlier request
  const [idempotencyKey, setIdempotencyKey] = useState(newKey)

  const add = useCallback((op) => {
    setOps((list) => [...list.filter((o) => !sameTarget(o, op)), op])
    setIdempotencyKey(newKey())
  }, [])
  const remove = useCallback((op) => {
    setOps((list) => list.filter((o) => !sameTarget(o, op)))
    setIdempotencyKey(newKey())
  }, [])
  const clear = useCallback(() => {
    setOps([])
    setIdempotencyKey(newKey())
  }, [])

  const value = useMemo(() => ({ ops, add, remove, clear, idempotencyKey }), [ops, add, remove, clear, idempotencyKey])
  return <DraftContext.Provider value={value}>{children}</DraftContext.Provider>
}

export function useDraft() {
  return useContext(DraftContext)
}

/** Wire op as accepted by POST /api/v1/change-sets. */
export function toWireOp({ op, level, clientId, officeId, shiftId, key, value }) {
  const o = { op, level, key }
  if (clientId) o.clientId = clientId
  if (officeId) o.officeId = officeId
  if (shiftId) o.shiftId = shiftId
  if (op === 'SET') o.value = value
  return o
}
