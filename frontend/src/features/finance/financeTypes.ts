/**
 * Source Mapper types (FIN-054B).
 *
 * Mirrors the backend DTOs exactly — see `docs/finance/API_CONTRACT.md` §7 and the records in
 * `com.templeregistry.dto.response.finance`. Field names and optionality are copied from the Java
 * records rather than from the prose contract, because the two can drift and the records are what
 * actually serialises.
 */

/**
 * The six declared mapping dimensions.
 *
 * Only `REVENUE_CATEGORY` is read by the pipeline, and it is the only one the backend accepts on
 * a create or edit. The rest exist because rows of those types are already in the database and
 * must stay visible.
 */
export type MappingType =
  | 'REVENUE_CATEGORY'
  | 'SERVICE'
  | 'PAYMENT_MODE'
  | 'STATUS'
  | 'FINANCIAL_YEAR'
  | 'METAL_TYPE'

/** The one mapping type that can be created or edited. Enforced by the backend, not by this. */
export const WRITABLE_MAPPING_TYPE: MappingType = 'REVENUE_CATEGORY'

export type MappingOutcome =
  | 'MAPPED'
  | 'UNMAPPED'
  | 'AMBIGUOUS'
  | 'NOT_APPLICABLE'
  | 'INVALID_CONFIGURATION'

/** Outcomes the unresolved view can ask for. `MAPPED` is refused by the backend with 422. */
export const UNRESOLVED_OUTCOMES = [
  'UNMAPPED',
  'AMBIGUOUS',
  'INVALID_CONFIGURATION',
  'NOT_APPLICABLE',
] as const satisfies readonly MappingOutcome[]

export interface SourceSystemSummary {
  id: number
  templeId: number
  templeName: string
  systemCode: string
  systemName: string
  active: boolean
  /** Active REVENUE_CATEGORY rules, counted server-side. Safe to show as a total. */
  activeRuleCount: number
}

export interface MappingRule {
  id: number
  sourceSystemId: number
  templeId: number
  mappingType: MappingType
  /** The staged field the rule reads. Null when `storedValue` names no field. */
  namespace: string | null
  /** The raw value the rule matches. Null when `storedValue` names no field. */
  sourceValue: string | null
  /** The single column as stored, e.g. `SEVA_CODE:430`. */
  storedValue: string
  /** False when the stored value names no field — such a rule can never match anything. */
  wellFormed: boolean
  sourceLabel: string | null
  canonicalValue: string
  /** False when the rule names a category that is no longer active. */
  canonicalValueKnown: boolean
  priority: number
  active: boolean
  notes: string | null
  /** Must be sent back on every write. A stale value is refused with 409. */
  version: number
  createdBy: number | null
  createdAt: string
  updatedBy: number | null
  updatedAt: string
}

export interface MappingRuleMutationResult {
  rule: MappingRule
  /**
   * The backend's own sentence about what saving did NOT do. Displayed verbatim — the wording is
   * the backend's contract, not this screen's opinion.
   */
  historicalEffect: string
  /** Legal but probably wrong, chiefly a namespace no staged payload carries. */
  warnings: string[]
}

export interface UnresolvedValue {
  /** The staged field the value was read from — the namespace a new rule needs. */
  namespace: string | null
  sourceValue: string | null
  affected: number
  lastSeenAt: string | null
}

export interface UnresolvedValues {
  sourceSystemId: number
  /** Null when nothing has been mapped yet: an empty list then means "not measured". */
  syncBatchId: number | null
  outcome: MappingOutcome
  observedAt: string | null
  values: UnresolvedValue[]
}

export interface NamespaceCatalogue {
  sourceSystemId: number
  /** Field names found in the sampled payloads. */
  observed: string[]
  /** Namespaces this source's rules already use. */
  inUseByRules: string[]
  /** Zero means nothing has been staged, so no namespace can be confirmed either way. */
  sampledRows: number
}

export interface CanonicalValue {
  categoryCode: string
  categoryName: string
  description: string | null
  displayOrder: number
}

export interface CreateMappingRuleRequest {
  sourceSystemId: number
  mappingType: MappingType
  namespace: string
  sourceValue: string
  sourceLabel?: string | null
  canonicalValue: string
  priority?: number
  active?: boolean
  notes?: string | null
}

export interface UpdateMappingRuleRequest {
  version: number
  namespace: string
  sourceValue: string
  sourceLabel?: string | null
  canonicalValue: string
  priority?: number
  active?: boolean
  notes?: string | null
}

export interface MappingRuleStatusRequest {
  active: boolean
  version: number
}

export interface MappingRuleListQuery {
  sourceSystemId: number
  mappingType?: MappingType
  active?: boolean
  canonicalValue?: string
  q?: string
  page?: number
  size?: number
  /** Allow-listed by the backend; anything else is refused with 422. See SORTABLE_KEYS. */
  sort?: string
}

/**
 * Sort keys the backend accepts, copied from `MappingAdminServiceImpl.SORTABLE`.
 *
 * Kept as a constant so a column cannot be marked sortable in the table without a matching
 * backend key — the failure otherwise is a 422 the user cannot act on.
 */
export const SORTABLE_KEYS = [
  'sourceValue',
  'canonicalValue',
  'mappingType',
  'priority',
  'active',
  'updatedAt',
  'createdAt',
] as const

export type SortableKey = (typeof SORTABLE_KEYS)[number]
