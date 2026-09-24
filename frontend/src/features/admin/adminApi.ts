import { createApi } from '@reduxjs/toolkit/query/react'
import { baseQueryWithReauth } from '@/services/baseQueryWithReauth'
import type { ApiResponse, PaginatedResponse } from '@/types'
import type { UserRole } from '@/constants/roles'

export interface UserAdminResponse {
  id: number; username: string; email: string; fullName: string
  mobile?: string; role: UserRole; active: boolean; aadhaarVerified: boolean
  aadhaarNumber?: string; districtId?: number; districtName?: string; cityId?: number
  templeId?: number; templeName?: string; designation?: string; accessType?: 'VIEW' | 'EDIT'
  lastLoginAt?: string; createdAt: string
}

export interface DistrictOption {
  id: number
  name: string
  cityId?: number
  code?: string
}

export interface CreateUserRequest {
  username: string; email: string; password: string; fullName: string
  mobile?: string; role: UserRole; districtId: number; cityId?: number
  /** Required when role = TEMPLE_AUTHORITY */
  aadhaarNumber?: string
  /**
   * true  → create a new temple (Case 1); templeName is required.
   * false → assign existing temple (Case 2); existingTempleId is required.
   * Defaults to true.
   */
  createTemple?: boolean
  /** Required when createTemple = true */
  templeName?: string
  /** Optional geo placement for the auto-created temple (createTemple = true) */
  talukId?: number
  hobliId?: number
  /** Required when createTemple = false */
  existingTempleId?: number
  /** Optional designation for TEMPLE_AUTHORITY users (e.g. "Trust Secretary") */
  designation?: string
  /** VIEW = read-only; EDIT = full write access. Defaults to EDIT. */
  accessType?: 'VIEW' | 'EDIT'
  /**
   * When true, the backend sends an account-created email to the user
   * containing their username and the plaintext password set by the admin.
   * Only evaluated on user creation — ignored on updates.
   */
  sendCredentialsEmail?: boolean
}

export interface TempleOption {
  id: number
  name: string
  registrationNumber: string
  districtName?: string
  grade?: string
  status?: string
  /** District ID — used for auto-filling the district field when assigning an existing temple. */
  districtId?: number
  /** City ID — used for auto-filling the city field when assigning an existing temple. */
  cityId?: number
}

export interface AuditEventResponse {
  id: number
  actorId: number
  actorName: string
  actorRole: string
  action: string
  entityType: string
  entityId: number
  entityName: string
  details?: string
  occurredAt: string
}

export interface AuthEventResponse {
  id: number; username: string; eventType: string; status: string
  ipAddress?: string; userAgent?: string; occurredAt: string
}

export interface StatewideDashboardResponse {
  totalTemples: number; totalActiveTemples: number; totalSuspendedTemples: number
  totalPendingDeclarations: number; totalOverdueDeclarations: number; totalPendingProfileReviews: number
  totalUsers: number; totalSuperAdmins: number; totalDistrictCollectors: number
  totalDcStaff: number; totalTempleAuthorities: number; totalAuditors: number
  recentAuditEventCount: number
  districtDistribution: Array<{ districtId: number; districtName: string; count: number }>
  gradeDistribution: Array<{ grade: string; count: number }>
}

export interface GovernanceHistoryResponse {
  id: number; entityId: number; entityType: string; workflowInstanceId?: number
  workflowTransitionId?: number; actorUserId?: number; actorName?: string; actorRole?: string
  action: string; comment?: string; timestamp: string
}

export interface NotificationRuleResponse {
  id: number; eventType: string; entityType: string; action: string
  recipientType: string; channel: string; priority: string
  templateKey: string; enabled: boolean; description?: string
}

export interface UpdateNotificationRuleRequest {
  enabled: boolean; priority?: string; description?: string
}

export interface SystemConfigResponse {
  id: number; configKey: string; configValue: string; dataType: string
  category: string; description?: string; active: boolean
}

export interface UpdateSystemConfigRequest {
  configValue: string; description?: string
}

export const adminApi = createApi({
  reducerPath: 'adminApi',
  baseQuery: baseQueryWithReauth,
  tagTypes: ['AdminUser', 'AuditEvent', 'AuthEvent', 'SystemConfig', 'NotificationRule', 'TempleSearch', 'Districts', 'TempleOption'],
  endpoints: (builder) => ({
    listUsers: builder.query<ApiResponse<PaginatedResponse<UserAdminResponse>>, { page?: number; size?: number; search?: string; role?: string }>({
      query: ({ page = 0, size = 20, search = '', role = '' } = {}) => ({
        url: '/admin/users',
        params: {
          page,
          size,
          ...(search && search.trim() ? { search: search.trim() } : {}),
          ...(role && role !== 'ALL' ? { role } : {}),
        },
      }),
      providesTags: ['AdminUser'],
    }),
    createUser: builder.mutation<ApiResponse<UserAdminResponse>, CreateUserRequest>({
      query: (body) => ({ url: '/admin/users', method: 'POST', body }),
      invalidatesTags: ['AdminUser'],
    }),
    updateUser: builder.mutation<ApiResponse<UserAdminResponse>, { id: number; body: Partial<CreateUserRequest> }>({
      query: ({ id, body }) => ({ url: `/admin/users/${id}`, method: 'PUT', body }),
      invalidatesTags: ['AdminUser'],
    }),
    deactivateUser: builder.mutation<ApiResponse<void>, number>({
      query: (id) => ({ url: `/admin/users/${id}/deactivate`, method: 'POST' }),
      invalidatesTags: ['AdminUser'],
    }),
    activateUser: builder.mutation<ApiResponse<void>, number>({
      query: (id) => ({ url: `/admin/users/${id}/activate`, method: 'POST' }),
      invalidatesTags: ['AdminUser'],
    }),
    listAllDistricts: builder.query<ApiResponse<DistrictOption[]>, void>({
      query: () => ({ url: '/geo/districts' }),
      providesTags: ['Districts'],
    }),
    searchTemples: builder.query<ApiResponse<PaginatedResponse<TempleOption>>, { q?: string; page?: number; size?: number }>({
      query: ({ q = '', page = 0, size = 20 } = {}) => ({
        url: '/admin/temples/search',
        params: { q, page, size },
      }),
      providesTags: ['TempleOption'],
    }),
    listAuditEvents: builder.query<ApiResponse<PaginatedResponse<AuditEventResponse>>, { page?: number; size?: number }>({
      query: ({ page = 0, size = 10 } = {}) => ({ url: '/admin/audit-events', params: { page, size } }),
      providesTags: ['AuditEvent'],
    }),
    listAuthEvents: builder.query<ApiResponse<PaginatedResponse<AuthEventResponse>>, { page?: number; size?: number }>({
      query: ({ page = 0, size = 10 } = {}) => ({ url: '/admin/auth-events', params: { page, size } }),
      providesTags: ['AuthEvent'],
    }),
    rebuildSearchSummary: builder.mutation<ApiResponse<void>, void>({
      query: () => ({ url: '/admin/search-summary/rebuild', method: 'POST' }),
    }),
    getPhysicalVerificationPending: builder.query<ApiResponse<PaginatedResponse<any>>, { page?: number; size?: number }>({
      query: ({ page = 0, size = 10 } = {}) => ({ url: '/admin/declarations/physical-verification-pending', params: { page, size } }),
    }),
    // ─── Statewide Dashboard ─────────────────────────────────────────────────
    getStatewideDashboard: builder.query<ApiResponse<StatewideDashboardResponse>, void>({
      query: () => ({ url: '/admin/dashboard/statewide' }),
    }),
    // ─── Governance History ──────────────────────────────────────────────────
    listGovernanceHistory: builder.query<ApiResponse<PaginatedResponse<GovernanceHistoryResponse>>, { page?: number; size?: number }>({
      query: ({ page = 0, size = 20 } = {}) => ({ url: '/admin/governance-history', params: { page, size } }),
    }),
    listGovernanceHistoryByEntity: builder.query<ApiResponse<GovernanceHistoryResponse[]>, { entityType: string; entityId: number }>({
      query: ({ entityType, entityId }) => ({ url: `/admin/governance-history/${entityType}/${entityId}` }),
    }),
    // ─── Notification Rules ──────────────────────────────────────────────────
    listNotificationRules: builder.query<ApiResponse<NotificationRuleResponse[]>, void>({
      query: () => ({ url: '/admin/notification-rules' }),
      providesTags: ['NotificationRule'],
    }),
    updateNotificationRule: builder.mutation<ApiResponse<NotificationRuleResponse>, { id: number; body: UpdateNotificationRuleRequest }>({
      query: ({ id, body }) => ({ url: `/admin/notification-rules/${id}`, method: 'PUT', body }),
      invalidatesTags: ['NotificationRule'],
    }),
    // ─── Temple Lifecycle ────────────────────────────────────────────────────
    suspendTemple: builder.mutation<ApiResponse<void>, { id: number; reason: string }>({
      query: ({ id, reason }) => ({ url: `/admin/temples/${id}/suspend`, method: 'POST', body: { reason } }),
      invalidatesTags: (_r, _e, { id }) => ['TempleSearch', { type: 'TempleSearch', id }],
    }),
    reactivateTemple: builder.mutation<ApiResponse<void>, { id: number; reason: string }>({
      query: ({ id, reason }) => ({ url: `/admin/temples/${id}/reactivate`, method: 'POST', body: { reason } }),
      invalidatesTags: (_r, _e, { id }) => ['TempleSearch', { type: 'TempleSearch', id }],
    }),
    freezeTemple: builder.mutation<ApiResponse<void>, { id: number; reason: string }>({
      query: ({ id, reason }) => ({ url: `/admin/temples/${id}/freeze`, method: 'POST', body: { reason } }),
      invalidatesTags: (_r, _e, { id }) => ['TempleSearch', { type: 'TempleSearch', id }],
    }),
    archiveTemple: builder.mutation<ApiResponse<void>, { id: number; reason: string }>({
      query: ({ id, reason }) => ({ url: `/admin/temples/${id}/archive`, method: 'POST', body: { reason } }),
      invalidatesTags: (_r, _e, { id }) => ['TempleSearch', { type: 'TempleSearch', id }],
    }),
    // ─── Observation Management (ADMIN_ONLY operations) ──────────────────────
    assignObservation: builder.mutation<ApiResponse<{ id: number }>, { id: number; assignedToUserId: number }>({
      query: ({ id, assignedToUserId }) => ({ url: `/observations/${id}/assign`, method: 'POST', params: { assignedToUserId } }),
    }),
    closeObservation: builder.mutation<ApiResponse<{ id: number }>, { id: number; resolutionNote: string }>({
      query: ({ id, resolutionNote }) => ({ url: `/observations/${id}/close`, method: 'POST', body: { resolutionNote } }),
    }),
    // ─── System Config ───────────────────────────────────────────────────────
    listSystemConfig: builder.query<ApiResponse<SystemConfigResponse[]>, { category?: string } | void>({
      query: (params) => ({ url: '/admin/config', params: params ?? {} }),
      providesTags: ['SystemConfig'],
    }),
    getSystemConfigByKey: builder.query<ApiResponse<SystemConfigResponse>, string>({
      query: (key) => ({ url: `/admin/config/${key}` }),
      providesTags: ['SystemConfig'],
    }),
    updateSystemConfig: builder.mutation<ApiResponse<SystemConfigResponse>, { key: string; body: UpdateSystemConfigRequest }>({
      query: ({ key, body }) => ({ url: `/admin/config/${key}`, method: 'PUT', body }),
      invalidatesTags: ['SystemConfig'],
    }),
  }),
})

export const {
  useListUsersQuery, useCreateUserMutation, useUpdateUserMutation,
  useDeactivateUserMutation, useActivateUserMutation,
  useListAllDistrictsQuery, useSearchTemplesQuery,
  useListAuditEventsQuery, useListAuthEventsQuery,
  useRebuildSearchSummaryMutation, useGetPhysicalVerificationPendingQuery,
  useGetStatewideDashboardQuery,
  useListGovernanceHistoryQuery, useListGovernanceHistoryByEntityQuery,
  useListNotificationRulesQuery, useUpdateNotificationRuleMutation,
  useSuspendTempleMutation, useReactivateTempleMutation,
  useFreezeTempleMutation, useArchiveTempleMutation,
  useAssignObservationMutation, useCloseObservationMutation,
  useListSystemConfigQuery, useGetSystemConfigByKeyQuery, useUpdateSystemConfigMutation,
} = adminApi
