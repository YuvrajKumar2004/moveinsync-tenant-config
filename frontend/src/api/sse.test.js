import { describe, expect, it } from 'vitest'
import { SseParser } from './sse.js'

describe('SseParser', () => {
  it('parses events as the config server sends them', () => {
    const p = new SseParser()
    const out = p.push('event:heartbeat\ndata:{"version":1,"historyId":"h"}\n\nevent:change\ndata:{"version":2}\nid:2\n\n')
    expect(out).toEqual([
      { event: 'heartbeat', data: '{"version":1,"historyId":"h"}', id: null },
      { event: 'change', data: '{"version":2}', id: '2' },
    ])
  })

  it('handles events split across chunks, CRLF, comments and multi-line data', () => {
    const p = new SseParser()
    expect(p.push('event: change\r\nda')).toEqual([])
    expect(p.push('ta: line1\r\n: keep-alive comment\r\ndata: line2\r')).toEqual([])
    expect(p.push('\n\r\n')).toEqual([{ event: 'change', data: 'line1\nline2', id: null }])
  })

  it('ignores blank dispatches without data and defaults the event name', () => {
    const p = new SseParser()
    expect(p.push('\n\ndata:x\n\n')).toEqual([{ event: 'message', data: 'x', id: null }])
  })
})
