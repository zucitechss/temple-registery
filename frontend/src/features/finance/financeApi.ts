import { createApi } from '@reduxjs/toolkit/query/react'
import { baseQueryWithReauth } from '../../services/baseQueryWithReauth'
import type { ApiResponse, PaginatedResponse } from '@/types'
import { financeRequests } from './financeRequests'
import type {
  CanonicalValue,
  CreateMappingRuleRequest,
  MappingOutcome,
  MappingRule,
  MappingRuleListQuery,
  MappingRuleMutationResult,
  MappingRuleStatusRequest,
  NamespaceCatalogue,
  SourceSystemSummary,
  UnresolvedValues,
  UpdateMappingRuleRequest,
} from './financeTypes'

/**
 * The finance administration API (FIN-054B), against the endpoints FIN-054A-BE implemented.
 *
 * <p>Read-and-configure only. There is deliberately **no endpoint here that re-processes a batch
 * or touches a financial fact**, because the backend exposes none: a saved rule changes how future
 * pipeline runs classify a value and leaves every published figure alone. A mutation that appeared
 * to correct the dashboard would be the most damaging thing this screen could do.
 *
 * <p>Request shapes live in `financeRequests` so they can be asserted without a network.
 */
export const financeApi = createApi({
  reducerPath: 'financeApi',
  baseQuery: baseQueryWithReauth,
  tagTypes: ['MappingRule', 'Unresolved', 'SourceSystem', 'Namespaces'],
  endpoints: (builder) => ({
    listSourceSystems: builder.query<ApiResponse<SourceSystemSummary[]>, void>({
      query: financeRequests.listSourceSystems,
      providesTags: ['SourceSystem'],
    }),

    listCanonicalValues: builder.query<ApiResponse<CanonicalValue[]>, void>({
      query: financeRequests.canonicalValues,
      // The revenue taxonomy is platform-wide and changes with a migration, not with this screen.
      keepUnusedDataFor: 600,
    }),

    getNamespaces: builder.query<ApiResponse<NamespaceCatalogue>, number>({
      query: financeRequests.namespaces,
      providesTags: (_r, _e, id) => [{ type: 'Namespaces', id }],
    }),

    listMappingRules: builder.query<ApiResponse<PaginatedResponse<MappingRule>>, MappingRuleListQuery>({
      query: financeRequests.listMappingRules,
      providesTags: ['MappingRule'],
    }),

    getMappingRule: builder.query<ApiResponse<MappingRule>, number>({
      query: financeRequests.getMappingRule,
      providesTags: (_r, _e, id) => [{ type: 'MappingRule', id }],
    }),

    listUnresolvedValues: builder.query<
      ApiResponse<UnresolvedValues>,
      { sourceSystemId: number; outcome: MappingOutcome }
    >({
      query: financeRequests.unresolved,
      providesTags: ['Unresolved'],
    }),

    createMappingRule: builder.mutation<ApiResponse<MappingRuleMutationResult>, CreateMappingRuleRequest>({
      query: financeRequests.createMappingRule,
      // 'Unresolved' is invalidated so the list reflects the new rule's existence. Note that the
      // counts themselves will not change until the batch is processed again — the panel says so.
      invalidatesTags: ['MappingRule', 'Unresolved', 'SourceSystem'],
    }),

    updateMappingRule: builder.mutation<
      ApiResponse<MappingRuleMutationResult>,
      { id: number; body: UpdateMappingRuleRequest }
    >({
      query: financeRequests.updateMappingRule,
      invalidatesTags: (_r, _e, { id }) => [{ type: 'MappingRule', id }, 'MappingRule', 'SourceSystem'],
    }),

    setMappingRuleStatus: builder.mutation<
      ApiResponse<MappingRuleMutationResult>,
      { id: number; body: MappingRuleStatusRequest }
    >({
      query: financeRequests.setMappingRuleStatus,
      invalidatesTags: (_r, _e, { id }) => [{ type: 'MappingRule', id }, 'MappingRule', 'SourceSystem'],
    }),
  }),
})

export const {
  useListSourceSystemsQuery,
  useListCanonicalValuesQuery,
  useGetNamespacesQuery,
  useListMappingRulesQuery,
  useGetMappingRuleQuery,
  useListUnresolvedValuesQuery,
  useCreateMappingRuleMutation,
  useUpdateMappingRuleMutation,
  useSetMappingRuleStatusMutation,
} = financeApi
