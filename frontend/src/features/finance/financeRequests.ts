import type {
  CreateMappingRuleRequest,
  MappingOutcome,
  MappingRuleListQuery,
  MappingRuleStatusRequest,
  UpdateMappingRuleRequest,
} from './financeTypes'

/**
 * Every request this feature is capable of making (FIN-054B).
 *
 * <p>Pure functions, used by `financeApi` and asserted directly by tests. Keeping them separate
 * lets a test prove a negative that matters here: that **nothing in this feature can re-run a
 * batch or write a financial fact**. Proving that by watching one render only covers the paths
 * that render happened to take; reading the whole set covers the surface.
 *
 * <p>Undefined parameters are left undefined rather than sent as empty strings — `fetchBaseQuery`
 * drops them, so an absent filter is absent from the query string instead of arriving as the
 * literal text "undefined".
 */
export const financeRequests = {
  listSourceSystems: () => '/finance/source-systems',

  canonicalValues: () => '/finance/canonical-values',

  namespaces: (sourceSystemId: number) => `/finance/source-systems/${sourceSystemId}/namespaces`,

  listMappingRules: ({
    sourceSystemId,
    mappingType,
    active,
    canonicalValue,
    q,
    page = 0,
    size = 20,
    sort,
  }: MappingRuleListQuery) => ({
    url: '/finance/mapping-rules',
    params: { sourceSystemId, mappingType, active, canonicalValue, q, page, size, sort },
  }),

  getMappingRule: (id: number) => `/finance/mapping-rules/${id}`,

  unresolved: ({ sourceSystemId, outcome }: { sourceSystemId: number; outcome: MappingOutcome }) => ({
    url: '/finance/mapping-rules/unresolved',
    params: { sourceSystemId, outcome },
  }),

  createMappingRule: (body: CreateMappingRuleRequest) => ({
    url: '/finance/mapping-rules',
    method: 'POST',
    body,
  }),

  updateMappingRule: ({ id, body }: { id: number; body: UpdateMappingRuleRequest }) => ({
    url: `/finance/mapping-rules/${id}`,
    method: 'PUT',
    body,
  }),

  setMappingRuleStatus: ({ id, body }: { id: number; body: MappingRuleStatusRequest }) => ({
    url: `/finance/mapping-rules/${id}/status`,
    method: 'PATCH',
    body,
  }),
} as const

export type FinanceRequestName = keyof typeof financeRequests
