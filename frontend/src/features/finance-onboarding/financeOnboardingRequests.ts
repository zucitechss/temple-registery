import type {
  DeclareCapabilityRequest,
  DeclareSourceOfTruthRequest,
  RegisterSourceSystemRequest,
  SetSourceSystemActivationRequest,
  UpdateCapabilityDeclarationRequest,
} from './financeOnboardingTypes'

/**
 * Every request this feature is capable of making (FIN-140 slice 140-A).
 *
 * Pure functions, used by `financeOnboardingApi` and asserted directly by tests. Separate from the
 * slice so a test can prove the negatives that matter here by reading the whole surface rather
 * than whichever paths one render happened to take:
 *
 * - **nothing starts or runs a synchronisation.** Slice 140-D added `activationRequests`, which
 *   grants the platform permission to contact a source in future. No builder here triggers a run,
 *   requests a batch or asks the worker for anything, and the tests assert that by name.
 * - **nothing probes a source system.** The runtime serving these endpoints holds no connector and
 *   no credential, so a "test connection" request would have nowhere to go.
 */
export const financeOnboardingRequests = {
  listSourceSystems: () => '/finance/source-systems',

  getSourceSystem: (id: number) => `/finance/source-systems/${id}`,

  readiness: (id: number) => `/finance/source-systems/${id}/readiness`,

  registerSourceSystem: (body: RegisterSourceSystemRequest) => ({
    url: '/finance/source-systems',
    method: 'POST',
    body,
  }),
} as const

export type FinanceOnboardingRequestName = keyof typeof financeOnboardingRequests

/**
 * Capability declaration requests (FIN-140-B).
 *
 * Kept in the same object so the negative assertions above cover them too: still nothing that
 * activates a source system, and still nothing that probes one. Declaring a capability is a
 * statement about what a temple records, and it starts no traffic.
 */
export const capabilityRequests = {
  catalogue: () => '/finance/capability-catalogue',

  list: (sourceSystemId: number) => `/finance/source-systems/${sourceSystemId}/capabilities`,

  declare: ({
    sourceSystemId,
    body,
  }: {
    sourceSystemId: number
    body: DeclareCapabilityRequest
  }) => ({
    url: `/finance/source-systems/${sourceSystemId}/capabilities`,
    method: 'POST',
    body,
  }),

  update: ({
    sourceSystemId,
    declarationId,
    body,
  }: {
    sourceSystemId: number
    declarationId: number
    body: UpdateCapabilityDeclarationRequest
  }) => ({
    url: `/finance/source-systems/${sourceSystemId}/capabilities/${declarationId}`,
    method: 'PUT',
    body,
  }),
} as const

/**
 * Source-of-truth declaration requests (FIN-140-C).
 *
 * Still nothing that activates and nothing that probes — the negative assertions above cover
 * these too. Note there is **no update and no delete builder**: every change is a POST creating a
 * new version, because a fact already loaded carries the version that produced it and an
 * in-place edit would leave that stamp pointing at a row which no longer says what it said.
 */
export const sourceOfTruthRequests = {
  list: (sourceSystemId: number) => `/finance/source-systems/${sourceSystemId}/source-of-truth`,

  declare: ({
    sourceSystemId,
    body,
  }: {
    sourceSystemId: number
    body: DeclareSourceOfTruthRequest
  }) => ({
    url: `/finance/source-systems/${sourceSystemId}/source-of-truth`,
    method: 'POST',
    body,
  }),
} as const

/**
 * Activation (FIN-140-D).
 *
 * The one builder here sets a **permission**, and it is named for that rather than for an action.
 * There is still nothing that probes, and nothing that triggers a run: enabling a source system
 * tells the platform it may contact it in future, which is why the negative assertions in the
 * tests exclude this name explicitly rather than by accident.
 */
export const activationRequests = {
  setActivation: ({
    sourceSystemId,
    body,
  }: {
    sourceSystemId: number
    body: SetSourceSystemActivationRequest
  }) => ({
    url: `/finance/source-systems/${sourceSystemId}/activation`,
    method: 'POST',
    body,
  }),
} as const
