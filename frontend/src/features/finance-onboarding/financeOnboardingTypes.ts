/**
 * TypeScript mirrors of the FIN-140 onboarding DTOs.
 *
 * There is deliberately **no `credentialRef` on any response type**. The backend record has no
 * field for it, so a type that declared one would be describing a payload that cannot arrive and
 * would invite a component to render it. `credentialRefSet` is the only thing a screen may know.
 */

export const SOURCE_TECHNOLOGIES = [
  'SQL_SERVER',
  'MYSQL',
  'POSTGRESQL',
  'ORACLE',
  'FILE',
  'API',
] as const
export type SourceTechnology = (typeof SOURCE_TECHNOLOGIES)[number]

export const CONNECTOR_TYPES = ['PULL_JDBC', 'PUSH_AGENT', 'SOURCE_API', 'FILE_DROP'] as const
export type ConnectorType = (typeof CONNECTOR_TYPES)[number]

/** Matches the backend enum exactly. `READY` is the absence of findings, never one of them. */
export type ReadinessStatus = 'READY' | 'WARNING' | 'BLOCKED'

export interface ReadinessFinding {
  code: string
  severity: Exclude<ReadinessStatus, 'READY'>
  subject: string | null
  message: string
}

export interface SourceSystemSummary {
  id: number
  templeId: number
  templeName: string
  systemCode: string
  systemName: string
  active: boolean
  activeRuleCount: number
}

export interface SourceSystemDetail {
  id: number
  templeId: number
  templeName: string | null
  systemCode: string
  systemName: string
  sourceTechnology: SourceTechnology
  connectorType: ConnectorType
  connectorBean: string
  sourceTempleCode: string | null
  sourceDatabaseName: string | null
  credentialRefSet: boolean
  syncScheduleCron: string | null
  syncEnabled: boolean
  stalenessThresholdHours: number | null
  sourceTimezone: string | null
  notes: string | null
  version: number
  createdAt: string | null
  updatedAt: string | null
}

export interface SourceSystemReadiness {
  sourceSystemId: number
  templeId: number
  templeName: string | null
  systemCode: string
  status: ReadinessStatus
  activationAllowed: boolean
  syncEnabled: boolean
  /** Always false in this slice, and the screen says so rather than leaving it to be inferred. */
  connectivityVerified: boolean
  connectivityNote: string
  blockingCount: number
  warningCount: number
  findings: ReadinessFinding[]
  evaluatedAt: string
}

export interface RegisterSourceSystemRequest {
  templeId: number
  systemCode: string
  systemName: string
  sourceTechnology: SourceTechnology
  connectorType: ConnectorType
  connectorBean: string
  sourceTempleCode?: string
  sourceDatabaseName?: string
  /** Write-only. Never comes back. */
  credentialRef?: string
  syncScheduleCron?: string
  stalenessThresholdHours?: number
  sourceTimezone?: string
  notes?: string
}

// ------------------------------------------------ capabilities (FIN-140-B)

/**
 * Availability vocabulary. Mirrors the backend enum.
 *
 * `NOT_AVAILABLE` says the source does not record something; `NOT_APPLICABLE` says the question
 * does not arise. Neither is zero, and neither is the same as no declaration at all.
 */
export type DataAvailability =
  | 'AVAILABLE'
  | 'PARTIALLY_AVAILABLE'
  | 'NOT_AVAILABLE'
  | 'NOT_APPLICABLE'

/**
 * A capability code. Deliberately a bare string rather than a union of nineteen literals: the
 * catalogue endpoint is the source of truth, and a hand-maintained union would silently stop
 * offering any capability added to the backend enum.
 */
export type FinanceCapabilityCode = string

export interface CapabilityOption {
  capability: FinanceCapabilityCode
  /** True when declaring this reportable makes source-of-truth and mapping configuration required. */
  drivesRevenueRequirements: boolean
}

export interface CapabilityCatalogue {
  capabilities: CapabilityOption[]
  availabilities: DataAvailability[]
  /** Metrics a source-of-truth declaration may name (FIN-140-C). Same endpoint, same cache. */
  metrics: MetricOption[]
}

export interface CapabilityDeclaration {
  id: number
  sourceSystemId: number
  templeId: number
  capability: FinanceCapabilityCode
  availability: DataAvailability
  availabilityReason: string | null
  coverageFrom: string | null
  coverageTo: string | null
  knownGaps: string[]
  lastReviewedAt: string | null
  version: number
  createdAt: string | null
  updatedAt: string | null
}

export interface DeclareCapabilityRequest {
  capability: FinanceCapabilityCode
  availability: DataAvailability
  availabilityReason?: string
  coverageFrom?: string
  coverageTo?: string
  knownGaps?: string[]
}

export interface UpdateCapabilityDeclarationRequest {
  version: number
  availability: DataAvailability
  availabilityReason?: string
  coverageFrom?: string
  coverageTo?: string
  knownGaps?: string[]
}

// --------------------------------------------- source of truth (FIN-140-C)

/**
 * One metric a source-of-truth declaration may name.
 *
 * `required` comes from the backend's `RevenueField`, which is what normalization actually reads.
 * A list kept here would be a second copy of the rule that decides whether a source can run at all.
 */
export interface MetricOption {
  metric: string
  field: string
  required: boolean
}

/** One considered-and-rejected candidate, exactly the keys the row carries. */
export type RejectedAlternative = Record<string, string>

export interface SourceOfTruthDeclaration {
  id: number
  sourceSystemId: number
  metric: string
  version: number
  /** Computed from `effectiveTo` server-side, never stored — so it cannot disagree with the
   *  column the pipeline reads. */
  inForce: boolean
  sourceObject: string
  sourceField: string
  filterPredicate: string | null
  rejectedAlternatives: RejectedAlternative[]
  rationale: string | null
  approved: boolean
  approvedBy: number | null
  approvedAt: string | null
  effectiveFrom: string | null
  effectiveTo: string | null
  createdAt: string | null
  updatedAt: string | null
}

export interface DeclareSourceOfTruthRequest {
  metric: string
  sourceObject: string
  sourceField: string
  filterPredicate?: string
  rejectedAlternatives?: { object: string; field?: string; measured?: string; reason: string }[]
  rationale?: string
  approved?: boolean
  effectiveFrom: string
  /** The version the caller was looking at, or omitted when they believe none is in force. */
  supersedesVersion?: number
}

// ------------------------------------------------- activation (FIN-140-D)

export interface SourceSystemActivation {
  sourceSystemId: number
  templeId: number
  templeName: string | null
  systemCode: string
  /** A permission, never an activity. See `activationNote`. */
  enabledForSync: boolean
  /** False when the source was already in the requested state — idempotency, made visible. */
  changed: boolean
  readinessStatus: ReadinessStatus
  blockingCount: number
  warningCount: number
  /** Always false: no production trigger reads the flag yet. */
  syncInfrastructureAvailable: boolean
  /** Always false: the connector registry is a sync-worker bean this runtime cannot see. */
  connectorDeploymentVerified: boolean
  activationNote: string
  warnings: string[]
  evaluatedAt: string
}

export interface SetSourceSystemActivationRequest {
  enabled: boolean
  /** Required when disabling. */
  reason?: string
}
