import { describe, expect, it } from 'vitest'
import {
  activationRequests,
  capabilityRequests,
  financeOnboardingRequests,
  sourceOfTruthRequests,
} from '../financeOnboardingRequests'

describe('financeOnboardingRequests (FIN-140 slice 140-A)', () => {
  describe('URLs and methods', () => {
    it('lists source systems without naming a temple', () => {
      expect(financeOnboardingRequests.listSourceSystems()).toBe('/finance/source-systems')
    })

    it('reads one source system by id', () => {
      expect(financeOnboardingRequests.getSourceSystem(42)).toBe('/finance/source-systems/42')
    })

    it('reads readiness by id', () => {
      expect(financeOnboardingRequests.readiness(42)).toBe('/finance/source-systems/42/readiness')
    })

    it('registers with POST and sends the body through untouched', () => {
      const body = {
        templeId: 300001,
        systemCode: 'KOLSOHAM',
        systemName: 'Operational system',
        sourceTechnology: 'SQL_SERVER' as const,
        connectorType: 'PULL_JDBC' as const,
        connectorBean: 'kollurFinanceConnector',
      }

      expect(financeOnboardingRequests.registerSourceSystem(body)).toEqual({
        url: '/finance/source-systems',
        method: 'POST',
        body,
      })
    })

    it('works for any temple — no id is hard-coded anywhere in the surface', () => {
      const serialised = JSON.stringify(
        Object.values(financeOnboardingRequests).map((build) => String(build)),
      )

      expect(serialised).not.toContain('300001')
      expect(serialised).not.toContain('KOLSOHAM')
    })
  })

  describe('what this feature cannot do', () => {
    /**
     * Activation exists since 140-D, in `activationRequests`. What must stay true here is that
     * registering or editing a source system never enables it as a side effect: switching one on
     * is a separate, deliberate request, and `syncEnabled` is not a field a caller can set.
     */
    it('cannot enable a source system while registering or editing one', () => {
      const names = Object.keys(financeOnboardingRequests)

      expect(names.some((name) => /activat|enable|switchOn/i.test(name))).toBe(false)
      expect(
        JSON.stringify(Object.values(financeOnboardingRequests).map(String)),
      ).not.toContain('activation')

      const registered = financeOnboardingRequests.registerSourceSystem({
        templeId: 1,
        systemCode: 'X',
        systemName: 'X',
        sourceTechnology: 'MYSQL',
        connectorType: 'PULL_JDBC',
        connectorBean: 'x',
      })
      expect(JSON.stringify(registered)).not.toContain('syncEnabled')
    })

    it('has no request that probes or tests a connection', () => {
      const names = Object.keys(financeOnboardingRequests)

      expect(names.some((name) => /probe|testConnection|connect/i.test(name))).toBe(false)
    })

    it('sends no password, host, port or connection string field name', () => {
      const serialised = JSON.stringify(
        Object.values(financeOnboardingRequests).map((build) => String(build)),
      ).toLowerCase()

      expect(serialised).not.toContain('password')
      expect(serialised).not.toContain('jdbc:')
      expect(serialised).not.toContain('connectionstring')
    })

    it('only ever reads or creates — nothing deletes or mutates a source system in place', () => {
      // Built rather than stringified: the transpiled source of an arrow function is not a
      // contract, and a test that reads it passes or fails on the bundler's quoting style.
      const built = [
        financeOnboardingRequests.listSourceSystems(),
        financeOnboardingRequests.getSourceSystem(1),
        financeOnboardingRequests.readiness(1),
        financeOnboardingRequests.registerSourceSystem({
          templeId: 1,
          systemCode: 'X',
          systemName: 'x',
          sourceTechnology: 'MYSQL',
          connectorType: 'FILE_DROP',
          connectorBean: 'x',
        }),
      ]

      const methods = built
        .map((request) => (typeof request === 'string' ? 'GET' : request.method))
        .filter(Boolean)

      expect(methods).toEqual(['GET', 'GET', 'GET', 'POST'])
      expect(methods).not.toContain('DELETE')
      expect(methods).not.toContain('PATCH')
    })
  })
})

describe('capabilityRequests (FIN-140-B)', () => {
  it('reads the catalogue from the server', () => {
    expect(capabilityRequests.catalogue()).toBe('/finance/capability-catalogue')
  })

  it('lists declarations nested under the source system, with no temple id in the path', () => {
    const url = capabilityRequests.list(7)

    expect(url).toBe('/finance/source-systems/7/capabilities')
    expect(url).not.toMatch(/temple/)
  })

  it('declares with POST and sends no temple id — the server resolves it', () => {
    const built = capabilityRequests.declare({
      sourceSystemId: 7,
      body: { capability: 'REVENUE', availability: 'AVAILABLE' },
    })

    expect(built).toEqual({
      url: '/finance/source-systems/7/capabilities',
      method: 'POST',
      body: { capability: 'REVENUE', availability: 'AVAILABLE' },
    })
    expect(JSON.stringify(built)).not.toContain('templeId')
  })

  it('updates with PUT and carries the version', () => {
    const built = capabilityRequests.update({
      sourceSystemId: 7,
      declarationId: 11,
      body: { version: 2, availability: 'NOT_AVAILABLE', availabilityReason: 'No module.' },
    })

    expect(built.url).toBe('/finance/source-systems/7/capabilities/11')
    expect(built.method).toBe('PUT')
    expect(built.body.version).toBe(2)
  })

  it('has no request that deletes or withdraws a declaration', () => {
    const names = Object.keys(capabilityRequests)

    expect(names.some((name) => /delete|remove|withdraw|retire/i.test(name))).toBe(false)
  })

  it('declaring a capability neither enables a source nor probes one', () => {
    const names = [...Object.keys(financeOnboardingRequests), ...Object.keys(capabilityRequests)]

    expect(names.some((name) => /activat|probe|testConnection|enable/i.test(name))).toBe(false)
  })
})

describe('sourceOfTruthRequests (FIN-140-C)', () => {
  it('lists declarations nested under the source system, with no temple id in the path', () => {
    const url = sourceOfTruthRequests.list(7)

    expect(url).toBe('/finance/source-systems/7/source-of-truth')
    expect(url).not.toMatch(/temple/)
  })

  it('declares with POST and carries the version it supersedes', () => {
    const built = sourceOfTruthRequests.declare({
      sourceSystemId: 7,
      body: {
        metric: 'REVENUE_AMOUNT',
        sourceObject: 'DailyReceipts',
        sourceField: 'Amount',
        effectiveFrom: '2026-09-23',
        supersedesVersion: 1,
      },
    })

    expect(built.url).toBe('/finance/source-systems/7/source-of-truth')
    expect(built.method).toBe('POST')
    expect(built.body.supersedesVersion).toBe(1)
    expect(JSON.stringify(built)).not.toContain('templeId')
  })

  it('sends no effectiveTo — supersession derives it, so a caller cannot make it inconsistent', () => {
    const built = sourceOfTruthRequests.declare({
      sourceSystemId: 7,
      body: {
        metric: 'REVENUE_AMOUNT',
        sourceObject: 'DailyReceipts',
        sourceField: 'Amount',
        effectiveFrom: '2026-09-23',
      },
    })

    expect(JSON.stringify(built)).not.toContain('effectiveTo')
  })

  it('has no update or delete builder — every change is a new version', () => {
    const names = Object.keys(sourceOfTruthRequests)

    expect(names.some((name) => /update|edit|delete|remove|withdraw/i.test(name))).toBe(false)
  })

  it('still has no request that probes anything', () => {
    const names = [
      ...Object.keys(financeOnboardingRequests),
      ...Object.keys(capabilityRequests),
      ...Object.keys(sourceOfTruthRequests),
    ]

    expect(names.some((name) => /probe|testConnection|fingerprint/i.test(name))).toBe(false)
  })
})

describe('activationRequests (FIN-140-D)', () => {
  it('posts to the activation sub-resource, with no temple id in the path', () => {
    const built = activationRequests.setActivation({
      sourceSystemId: 7,
      body: { enabled: true },
    })

    expect(built.url).toBe('/finance/source-systems/7/activation')
    expect(built.method).toBe('POST')
    expect(JSON.stringify(built)).not.toContain('templeId')
  })

  it('carries the desired end state rather than a toggle, which is what makes it idempotent', () => {
    const on = activationRequests.setActivation({ sourceSystemId: 7, body: { enabled: true } })
    const off = activationRequests.setActivation({
      sourceSystemId: 7,
      body: { enabled: false, reason: 'Migrating.' },
    })

    expect(on.body.enabled).toBe(true)
    expect(off.body.enabled).toBe(false)
    expect(off.body.reason).toBe('Migrating.')
    // No "toggle" builder exists: a caller can never ask for "the other one", which is the
    // request that would be unsafe to repeat.
    expect(Object.keys(activationRequests)).toEqual(['setActivation'])
  })

  it('sends no version — a repeated activation must not become a conflict', () => {
    const built = activationRequests.setActivation({ sourceSystemId: 7, body: { enabled: true } })

    expect(JSON.stringify(built)).not.toContain('version')
  })

  /**
   * The activation builder exists now, so the old blanket "nothing activates" assertion would be
   * false. What must stay true is narrower and more useful: activation sets a permission, and no
   * builder anywhere asks for a run, a batch, a probe or the worker.
   */
  it('has no request that triggers a run, a batch, a probe or the worker', () => {
    const names = [
      ...Object.keys(financeOnboardingRequests),
      ...Object.keys(capabilityRequests),
      ...Object.keys(sourceOfTruthRequests),
      ...Object.keys(activationRequests),
    ]

    expect(names.some((name) => /probe|testConnection|connect|sync(Now|Run)|run|batch|extract|trigger|worker/i.test(name)))
      .toBe(false)
  })
})
