import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * Runs an async loader and tracks {data, error, loading}. Re-runs when deps change; `reload` re-runs
 * on demand. Keeps the previous data while reloading so live refreshes do not flash a spinner.
 */
export function useAsync(loader, deps, { enabled = true } = {}) {
  const [state, setState] = useState({ data: undefined, error: null, loading: enabled })
  const seq = useRef(0)
  const run = useCallback(async () => {
    if (!enabled) {
      setState({ data: undefined, error: null, loading: false })
      return
    }
    const id = ++seq.current
    setState((s) => ({ ...s, loading: true, error: null }))
    try {
      const data = await loader()
      if (id === seq.current) setState({ data, error: null, loading: false })
    } catch (error) {
      if (id === seq.current) setState((s) => ({ data: s.data, error, loading: false }))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, ...deps])
  useEffect(() => {
    run()
  }, [run])
  return { ...state, reload: run }
}
