import { describe, expect, it } from 'vitest'
import { ancestry, collectScopes, contextLevel, opScope, provenance, scopeLabel, validateContext } from './scope.js'
import { capabilities } from './permissions.js'
import { metric, parsePrometheus } from './metrics.js'
import { describeOp, humanDuration } from './format.js'

describe('scope helpers', () => {
  const shift = { clientId: 'acme', officeId: 'blr-1', shiftId: 'night' }

  it('uses the server scope labels and walks the hierarchy most specific first', () => {
    expect(scopeLabel(shift)).toBe('SHIFT:acme/blr-1/night')
    expect(scopeLabel({ clientId: 'acme' })).toBe('CLIENT:acme')
    expect(scopeLabel({})).toBe('GLOBAL')
    expect(ancestry(shift).map(scopeLabel)).toEqual(['SHIFT:acme/blr-1/night', 'OFFICE:acme/blr-1', 'CLIENT:acme', 'GLOBAL'])
    expect(contextLevel({ clientId: 'acme', officeId: 'x' })).toBe('OFFICE')
    expect(opScope(shift)).toEqual({ level: 'SHIFT', clientId: 'acme', officeId: 'blr-1', shiftId: 'night' })
  })

  it('rejects malformed contexts like the server does', () => {
    expect(validateContext({ officeId: 'blr-1' })).toMatch(/needs a client/)
    expect(validateContext({ clientId: 'acme', shiftId: 'n' })).toMatch(/needs an office/)
    expect(validateContext({ clientId: 'bad id!' })).toMatch(/may only contain/)
    expect(validateContext(shift)).toBeNull()
  })

  it('classifies where a resolved value comes from', () => {
    expect(provenance({ source: 'OVERRIDE', scope: 'OFFICE:acme/blr-1' }, 'OFFICE:acme/blr-1')).toBe('here')
    expect(provenance({ source: 'OVERRIDE', scope: 'CLIENT:acme' }, 'OFFICE:acme/blr-1')).toBe('inherited')
    expect(provenance({ source: 'DEFAULT', scope: 'GLOBAL' }, 'CLIENT:acme')).toBe('default')
    expect(provenance({ source: 'STAGED', scope: 'GLOBAL' }, 'OFFICE:acme/blr-1')).toBe('staged')
  })

  it('builds a client/office/shift tree from override entries', () => {
    const tree = collectScopes([
      { level: 'GLOBAL' }, { clientId: 'globex', officeId: 'pune-1' }, { clientId: 'acme', officeId: 'blr-1', shiftId: 'night' },
      { clientId: 'acme' }, { clientId: 'acme', officeId: 'blr-1' },
    ])
    expect(tree).toEqual([
      { clientId: 'acme', offices: [{ officeId: 'blr-1', shifts: ['night'] }] },
      { clientId: 'globex', offices: [{ officeId: 'pune-1', shifts: [] }] },
    ])
  })
})

describe('capabilities mirror the server rules', () => {
  it('operator may do everything', () => {
    const c = capabilities({ role: 'OPERATOR' })
    expect(c.canWriteScope({ level: 'GLOBAL' })).toBe(true)
    expect(c.canUseSync && c.canSeeMetrics && c.canRollback('GLOBAL')).toBe(true)
  })
  it('admin is limited to its own tenant and never GLOBAL', () => {
    const c = capabilities({ role: 'ADMIN', tenant: 'acme' })
    expect(c.canWriteScope({ level: 'OFFICE', clientId: 'acme' })).toBe(true)
    expect(c.canWriteScope({ level: 'OFFICE', clientId: 'globex' })).toBe(false)
    expect(c.canWriteScope({ level: 'GLOBAL' })).toBe(false)
    expect(c.canRollback('GLOBAL')).toBe(false)
    expect(c.canRollback('CLIENT', 'acme')).toBe(true)
    expect(c.canUseSync).toBe(false)
  })
  it('employee is read-only', () => {
    const c = capabilities({ role: 'EMPLOYEE', tenant: 'acme' })
    expect(c.canWriteScope({ level: 'CLIENT', clientId: 'acme' })).toBe(false)
    expect(c.canReadHistory || c.canManageRollouts).toBe(false)
    expect(c.readableClient).toBe('acme')
  })
})

describe('formatting and metrics', () => {
  it('humanises ISO durations', () => {
    expect(humanDuration('PT1H30M')).toBe('1 h 30 min')
    expect(humanDuration('PT35M')).toBe('35 min')
    expect(humanDuration('weird')).toBe('weird')
  })
  it('describes history ops', () => {
    expect(describeOp({ type: 'SET', level: 'OFFICE', clientId: 'acme', officeId: 'blr-1', key: 'k', value: 'v' }))
      .toEqual({ verb: 'Set', text: 'k = v', at: 'OFFICE:acme/blr-1' })
  })
  it('parses and sums Prometheus series', () => {
    const s = parsePrometheus([
      '# HELP x', 'config_version_current 12.0',
      'config_changes_rejected_total{reason="validation"} 3.0', 'config_changes_rejected_total{reason="forbidden"} 2.0',
      'config_push_lag_seconds{quantile="0.99"} 0.021',
    ].join('\n'))
    expect(metric(s, 'config_version_current')).toBe(12)
    expect(metric(s, 'config_changes_rejected_total')).toBe(5)
    expect(metric(s, 'config_push_lag_seconds', { quantile: '0.99' })).toBe(0.021)
    expect(metric(s, 'missing')).toBeNull()
  })
})
