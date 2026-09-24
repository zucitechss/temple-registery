import { createApi } from '@reduxjs/toolkit/query/react'
import { baseQueryWithReauth } from '../../services/baseQueryWithReauth'
import type { ApiResponse } from '@/types'
import {
  activationRequests,
  capabilityRequests,
  financeOnboardingRequests,
  sourceOfTruthRequests,
} from './financeOnboardingRequests'
import type {
  CapabilityCatalogue,
  CapabilityDeclaration,
  DeclareCapabilityRequest,
  DeclareSourceOfTruthRequest,
  RegisterSourceSystemRequest,
  SetSourceSystemActivationRequest,
  SourceOfTruthDeclaration,
  SourceSystemActivation,
  SourceSystemDetail,
  SourceSystemReadiness,
  SourceSystemSummary,
  UpdateCapabilityDeclarationRequest,
} from './financeOnboardingTypes'

/**
 * The source-system onboarding API (FIN-140 slices 140-A through 140-D).
 *
 * Registering, configuring, and granting the platform permission to contact a source in future.
 * **There is still no probe**, because the backend exposes none — the runtime serving these
 * endpoints holds no connector and no credential, so a connectivity check has nowhere to run.
 *
 * The activation mutation sets a permission and nothing else: no run is triggered, and today
 * nothing consumes the flag at all. See `financeOnboardingRequests` and the `activationNote` the
 * backend returns.
 *
 * Readiness is `keepUnusedDataFor: 0`. It is computed from current configuration and is never
 * stored, so a cached verdict is the one thing this screen must not show: an administrator told a
 * configuration is clean because it was clean before their last edit has been misinformed by us.
 */
export const financeOnboardingApi = createApi({
  reducerPath: 'financeOnboardingApi',
  baseQuery: baseQueryWithReauth,
  tagTypes: ['OnboardingSourceSystem', 'Readiness', 'CapabilityDeclaration', 'SourceOfTruth'],
  endpoints: (builder) => ({
    listOnboardingSourceSystems: builder.query<ApiResponse<SourceSystemSummary[]>, void>({
      query: financeOnboardingRequests.listSourceSystems,
      providesTags: ['OnboardingSourceSystem'],
    }),

    getSourceSystem: builder.query<ApiResponse<SourceSystemDetail>, number>({
      query: financeOnboardingRequests.getSourceSystem,
      providesTags: (_r, _e, id) => [{ type: 'OnboardingSourceSystem', id }],
    }),

    getSourceSystemReadiness: builder.query<ApiResponse<SourceSystemReadiness>, number>({
      query: financeOnboardingRequests.readiness,
      providesTags: (_r, _e, id) => [{ type: 'Readiness', id }],
      keepUnusedDataFor: 0,
    }),

    registerSourceSystem: builder.mutation<
      ApiResponse<SourceSystemDetail>,
      RegisterSourceSystemRequest
    >({
      query: financeOnboardingRequests.registerSourceSystem,
      invalidatesTags: ['OnboardingSourceSystem', 'Readiness'],
    }),

    // ---------------------------------------------- capabilities (FIN-140-B)

    getCapabilityCatalogue: builder.query<ApiResponse<CapabilityCatalogue>, void>({
      query: capabilityRequests.catalogue,
      // The vocabulary changes with a release, not with this screen.
      keepUnusedDataFor: 600,
    }),

    listCapabilityDeclarations: builder.query<ApiResponse<CapabilityDeclaration[]>, number>({
      query: capabilityRequests.list,
      providesTags: (_r, _e, id) => [{ type: 'CapabilityDeclaration', id }],
    }),

    declareCapability: builder.mutation<
      ApiResponse<CapabilityDeclaration>,
      { sourceSystemId: number; body: DeclareCapabilityRequest }
    >({
      query: capabilityRequests.declare,
      // Readiness is invalidated because a declaration changes it — that is the point of the
      // slice. It is recomputed server-side on the next read, never patched client-side.
      invalidatesTags: (_r, _e, { sourceSystemId }) => [
        { type: 'CapabilityDeclaration', id: sourceSystemId },
        { type: 'Readiness', id: sourceSystemId },
      ],
    }),

    updateCapabilityDeclaration: builder.mutation<
      ApiResponse<CapabilityDeclaration>,
      { sourceSystemId: number; declarationId: number; body: UpdateCapabilityDeclarationRequest }
    >({
      query: capabilityRequests.update,
      invalidatesTags: (_r, _e, { sourceSystemId }) => [
        { type: 'CapabilityDeclaration', id: sourceSystemId },
        { type: 'Readiness', id: sourceSystemId },
      ],
    }),

    // ------------------------------------------ source of truth (FIN-140-C)

    listSourceOfTruth: builder.query<ApiResponse<SourceOfTruthDeclaration[]>, number>({
      query: sourceOfTruthRequests.list,
      providesTags: (_r, _e, id) => [{ type: 'SourceOfTruth', id }],
    }),

    declareSourceOfTruth: builder.mutation<
      ApiResponse<SourceOfTruthDeclaration>,
      { sourceSystemId: number; body: DeclareSourceOfTruthRequest }
    >({
      query: sourceOfTruthRequests.declare,
      // A declaration supersedes the previous version, so the whole history is refetched rather
      // than the new row appended: the row that was in force a moment ago now carries an
      // effectiveTo this client never saw.
      invalidatesTags: (_r, _e, { sourceSystemId }) => [
        { type: 'SourceOfTruth', id: sourceSystemId },
        { type: 'Readiness', id: sourceSystemId },
      ],
    }),

    // ---------------------------------------------- activation (FIN-140-D)

    setSourceSystemActivation: builder.mutation<
      ApiResponse<SourceSystemActivation>,
      { sourceSystemId: number; body: SetSourceSystemActivationRequest }
    >({
      query: activationRequests.setActivation,
      // The detail record carries syncEnabled and readiness reports it, so both are refetched
      // rather than patched: a screen showing a stale "not enabled" beside a fresh audit line is
      // the one inconsistency this panel must not produce.
      invalidatesTags: (_r, _e, { sourceSystemId }) => [
        { type: 'OnboardingSourceSystem', id: sourceSystemId },
        { type: 'Readiness', id: sourceSystemId },
      ],
    }),
  }),
})

export const {
  useListOnboardingSourceSystemsQuery,
  useGetSourceSystemQuery,
  useGetSourceSystemReadinessQuery,
  useRegisterSourceSystemMutation,
  useGetCapabilityCatalogueQuery,
  useListCapabilityDeclarationsQuery,
  useDeclareCapabilityMutation,
  useUpdateCapabilityDeclarationMutation,
  useListSourceOfTruthQuery,
  useDeclareSourceOfTruthMutation,
  useSetSourceSystemActivationMutation,
} = financeOnboardingApi
