import { describe, expect, it } from 'vitest'
import { financeRequests } from '../financeRequests'
import { SORTABLE_KEYS } from '../financeTypes'

/**
 * The request contract, asserted without a network.
 *
 * These are the requests this feature is *capable* of making — the whole set, not the subset a
 * particular render happened to produce. That is what makes the financial-safety assertion below
 * worth anything.
 */
describe('financeRequests — URLs and parameters', () => {
  it('reads source systems, canonical values and namespaces from the documented paths', () => {
    expect(financeRequests.listSourceSystems()).toBe('/finance/source-systems')
    expect(financeRequests.canonicalValues()).toBe('/finance/canonical-values')
    expect(financeRequests.namespaces(501)).toBe('/finance/source-systems/501/namespaces')
  })

  it('always scopes the rule list to one source system and pages on the server', () => {
    const req = financeRequests.listMappingRules({ sourceSystemId: 501 })

    expect(req.url).toBe('/finance/mapping-rules')
    expect(req.params.sourceSystemId).toBe(501)
    // A default size means the client never asks for an unbounded list in order to filter or
    // count locally.
    expect(req.params.size).toBe(20)
    expect(req.params.page).toBe(0)
  })

  it('omits absent filters rather than sending them as empty values', () => {
    const { params } = financeRequests.listMappingRules({ sourceSystemId: 501 })

    expect(params.q).toBeUndefined()
    expect(params.active).toBeUndefined()
    expect(params.mappingType).toBeUndefined()
    expect(params.canonicalValue).toBeUndefined()
  })

  it('passes filters through untouched when they are given', () => {
    const { params } = financeRequests.listMappingRules({
      sourceSystemId: 501,
      active: false,
      q: '  431 ',
      canonicalValue: 'SEVA',
      mappingType: 'REVENUE_CATEGORY',
      page: 2,
      size: 50,
      sort: 'priority,desc',
    })

    // The search term is not trimmed: matching is exact on the server, and quietly changing what
    // the user typed would search for something else.
    expect(params.q).toBe('  431 ')
    expect(params).toMatchObject({
      active: false,
      canonicalValue: 'SEVA',
      mappingType: 'REVENUE_CATEGORY',
      page: 2,
      size: 50,
      sort: 'priority,desc',
    })
  })

  it('only ever offers sort keys the backend allow-lists', () => {
    // Mirrors MappingAdminServiceImpl.SORTABLE. A key outside it is a 422 the user cannot act on.
    expect([...SORTABLE_KEYS].sort()).toEqual(
      ['active', 'canonicalValue', 'createdAt', 'mappingType', 'priority', 'sourceValue', 'updatedAt'],
    )
    SORTABLE_KEYS.forEach((key) => {
      const { params } = financeRequests.listMappingRules({ sourceSystemId: 1, sort: `${key},asc` })
      expect(params.sort).toBe(`${key},asc`)
    })
  })

  it('asks for unresolved values by source system and outcome', () => {
    const req = financeRequests.unresolved({ sourceSystemId: 501, outcome: 'AMBIGUOUS' })

    expect(req.url).toBe('/finance/mapping-rules/unresolved')
    expect(req.params).toEqual({ sourceSystemId: 501, outcome: 'AMBIGUOUS' })
  })
})

describe('financeRequests — writes', () => {
  it('creates with POST and carries the version-free create body', () => {
    const req = financeRequests.createMappingRule({
      sourceSystemId: 501,
      mappingType: 'REVENUE_CATEGORY',
      namespace: 'SEVA_CODE',
      sourceValue: '430',
      canonicalValue: 'SEVA',
    })

    expect(req).toMatchObject({ url: '/finance/mapping-rules', method: 'POST' })
    expect(req.body).not.toHaveProperty('version')
  })

  it('updates with PUT and always carries the version, so a stale edit can be refused', () => {
    const req = financeRequests.updateMappingRule({
      id: 9,
      body: { version: 4, namespace: 'SEVA_CODE', sourceValue: '430', canonicalValue: 'SEVA' },
    })

    expect(req).toMatchObject({ url: '/finance/mapping-rules/9', method: 'PUT' })
    expect(req.body.version).toBe(4)
  })

  it('never lets an update move a rule to another source system or mapping type', () => {
    const req = financeRequests.updateMappingRule({
      id: 9,
      body: { version: 1, namespace: 'A', sourceValue: 'B', canonicalValue: 'SEVA' },
    })

    // Both are immutable on the server. Sending them would be a request to change which temple's
    // scope the rule was authorized against.
    expect(req.body).not.toHaveProperty('sourceSystemId')
    expect(req.body).not.toHaveProperty('mappingType')
  })

  it('changes status with PATCH and the version', () => {
    const req = financeRequests.setMappingRuleStatus({ id: 9, body: { active: false, version: 2 } })

    expect(req).toMatchObject({ url: '/finance/mapping-rules/9/status', method: 'PATCH' })
    expect(req.body).toEqual({ active: false, version: 2 })
  })
})

describe('financeRequests — financial safety', () => {
  const allRequests = () => [
    financeRequests.listSourceSystems(),
    financeRequests.canonicalValues(),
    financeRequests.namespaces(1),
    financeRequests.listMappingRules({ sourceSystemId: 1 }),
    financeRequests.getMappingRule(1),
    financeRequests.unresolved({ sourceSystemId: 1, outcome: 'UNMAPPED' }),
    financeRequests.createMappingRule({
      sourceSystemId: 1, mappingType: 'REVENUE_CATEGORY',
      namespace: 'A', sourceValue: 'B', canonicalValue: 'SEVA',
    }),
    financeRequests.updateMappingRule({
      id: 1, body: { version: 1, namespace: 'A', sourceValue: 'B', canonicalValue: 'SEVA' },
    }),
    financeRequests.setMappingRuleStatus({ id: 1, body: { active: true, version: 1 } }),
  ]

  const urlOf = (r: string | { url: string }) => (typeof r === 'string' ? r : r.url)

  it('has no request that could re-run a batch or re-process history', () => {
    const urls = allRequests().map(urlOf)

    urls.forEach((url) => {
      expect(url).not.toMatch(/re-?run|re-?process|recalculat|replay|backfill/i)
    })
  })

  it('has no request that could write or delete a financial fact', () => {
    const urls = allRequests().map(urlOf)

    urls.forEach((url) => {
      expect(url).not.toMatch(/revenue-fact|fin_revenue|facts|aggregat|reconcil/i)
    })
  })

  it('never issues a DELETE — retirement is deactivation, which stays visible', () => {
    const methods = allRequests()
      .map((r) => (typeof r === 'string' ? 'GET' : (r as { method?: string }).method ?? 'GET'))

    expect(methods).not.toContain('DELETE')
    expect(new Set(methods)).toEqual(new Set(['GET', 'POST', 'PUT', 'PATCH']))
  })

  it('confines every request to the finance mapping administration surface', () => {
    allRequests().map(urlOf).forEach((url) => {
      expect(url.startsWith('/finance/')).toBe(true)
    })
  })

  it('exposes no request for structural source-to-staging mapping', () => {
    const names = Object.keys(financeRequests)

    // ADR-004: which tables and columns a connector reads is code, not configuration. A request
    // named for it here would be the first step towards a screen that promises otherwise.
    names.forEach((name) => {
      expect(name).not.toMatch(/table|column|schema|sql|connector|structural/i)
    })
    expect(names).toHaveLength(9)
  })
})
