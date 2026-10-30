// Server-sent events over fetch(). The config server authenticates the stream with an
// `Authorization: Bearer` header; the browser EventSource API cannot send headers, and putting the
// token in the URL would leak it into logs and history. A streamed fetch keeps the header.

/** Incremental parser for the `text/event-stream` format (event/data/id fields, blank-line dispatch). */
export class SseParser {
  constructor() {
    this.buffer = ''
    this.reset()
  }

  reset() {
    this.event = 'message'
    this.data = []
    this.id = null
  }

  /** Feeds a chunk of text; returns the complete events it finished. */
  push(chunk) {
    this.buffer += chunk
    const out = []
    let idx
    while ((idx = this.buffer.search(/\r\n|\r|\n/)) >= 0) {
      const line = this.buffer.slice(0, idx)
      const sep = this.buffer[idx] === '\r' && this.buffer[idx + 1] === '\n' ? 2 : 1
      this.buffer = this.buffer.slice(idx + sep)
      if (line === '') {
        if (this.data.length) out.push({ event: this.event, data: this.data.join('\n'), id: this.id })
        this.reset()
        continue
      }
      if (line.startsWith(':')) continue
      const colon = line.indexOf(':')
      const field = colon < 0 ? line : line.slice(0, colon)
      let value = colon < 0 ? '' : line.slice(colon + 1)
      if (value.startsWith(' ')) value = value.slice(1)
      if (field === 'event') this.event = value
      else if (field === 'data') this.data.push(value)
      else if (field === 'id') this.id = value
    }
    return out
  }
}

/**
 * Opens the stream and calls onEvent for each event until it ends, fails or is aborted.
 * Resolves when the server closes the stream; rejects with {status} on HTTP errors.
 */
export async function openStream({ url, token, signal, onOpen, onEvent }) {
  const res = await fetch(url, {
    headers: { Accept: 'text/event-stream', Authorization: `Bearer ${token}` },
    signal,
    cache: 'no-store',
  })
  if (!res.ok || !res.body) {
    const err = new Error(`stream refused (HTTP ${res.status})`)
    err.status = res.status
    throw err
  }
  onOpen?.()
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  const parser = new SseParser()
  for (;;) {
    const { value, done } = await reader.read()
    if (done) return
    for (const ev of parser.push(decoder.decode(value, { stream: true }))) onEvent(ev)
  }
}
