// Minimal parser for the Prometheus text exposition format served by /actuator/prometheus.

export function parsePrometheus(text) {
  const out = []
  for (const line of (text || '').split('\n')) {
    if (!line || line.startsWith('#')) continue
    const m = /^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{([^}]*)\})?\s+(\S+)/.exec(line)
    if (!m) continue
    const labels = {}
    if (m[3]) {
      for (const pair of m[3].matchAll(/([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"/g)) labels[pair[1]] = pair[2]
    }
    out.push({ name: m[1], labels, value: Number(m[4]) })
  }
  return out
}

/** Sum of all series with this name whose labels match `where`. */
export function metric(samples, name, where = {}) {
  const hits = samples.filter((s) => s.name === name && Object.entries(where).every(([k, v]) => s.labels[k] === v))
  return hits.length ? hits.reduce((a, s) => a + s.value, 0) : null
}
