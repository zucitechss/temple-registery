import { configureStore } from '@reduxjs/toolkit'
import { useDispatch, useSelector } from 'react-redux'
import { rootReducer } from './rootReducer'
import { authApi } from '@/features/auth/authApi'
import { geoApi } from '@/features/geo/geoApi'
import { trustApi } from '@/features/trust/trustApi'
import { employeeApi } from '@/features/employee/employeeApi'
import { contractorApi } from '@/features/contractor/contractorApi'
import { declarationApi } from '@/features/declaration/declarationApi'
import { documentApi } from '@/features/document/documentApi'
import { notificationApi } from '@/features/notification/notificationApi'
import { exportApi } from '@/features/export/exportApi'
import { adminApi } from '@/features/admin/adminApi'
import { dcApi } from '@/features/dc/dcApi'
import { governanceApi } from '@/features/governance/governanceApi'
import { workflowApi } from '@/features/governance/workflowApi'
import { governanceV2Api } from '@/features/governance/governanceV2Api'
import { templeApi } from '@/features/temple-profile/hooks/templeApi'
import { auditorApi } from '@/features/auditor/auditorApi'
import { viewerApi } from '@/features/viewer/viewerApi'
import { timelineApi } from '@/features/timeline/timelineApi'
import { accessControlApi } from '@/features/access-control/accessControlApi'
import { noticeApi } from '@/features/notice/noticeApi'
import { financeApi } from '@/features/finance/financeApi'

/** Global RTK Query error logger middleware — handles 4xx and 5xx */
const rtkQueryErrorLogger =
  () =>
  (next: (action: unknown) => unknown) =>
  (action: unknown) => {
    if (
      typeof action === 'object' &&
      action !== null &&
      'type' in action &&
      typeof (action as { type: string }).type === 'string' &&
      (action as { type: string }).type.endsWith('/rejected')
    ) {
      const payload = (action as { payload?: { status?: number; data?: { message?: string } } }).payload
      if (payload?.status) {
        if (payload.status === 401) {
          // Auth failure — logged at debug level; redirect handled in baseQueryWithReauth
          console.warn('[API Auth]', payload.status, payload.data?.message)
        } else if (payload.status === 403) {
          // Permission denied — surfaced as warning for observability
          console.warn('[API Forbidden]', payload.status, payload.data?.message)
        } else if (payload.status >= 400 && payload.status < 500) {
          console.warn('[API Client Error]', payload.status, payload.data?.message)
        } else if (payload.status >= 500) {
          console.error('[API Server Error]', payload)
        }
      }
    }
    return next(action)
  }

export const store = configureStore({
  reducer: rootReducer,
  middleware: (getDefaultMiddleware) =>
    getDefaultMiddleware().concat(
      rtkQueryErrorLogger,
      authApi.middleware,
      geoApi.middleware,
      templeApi.middleware,
      trustApi.middleware,
      employeeApi.middleware,
      contractorApi.middleware,
      declarationApi.middleware,
      documentApi.middleware,
      notificationApi.middleware,
      exportApi.middleware,
      adminApi.middleware,
      dcApi.middleware,
      governanceApi.middleware,
      workflowApi.middleware,
      governanceV2Api.middleware,
      auditorApi.middleware,
      viewerApi.middleware,
      timelineApi.middleware,
      accessControlApi.middleware,
      noticeApi.middleware,
      financeApi.middleware,
    ),
})

export type RootState = ReturnType<typeof store.getState>
export type AppDispatch = typeof store.dispatch

export const useAppDispatch = () => useDispatch<AppDispatch>()
export const useAppSelector = <T>(selector: (state: RootState) => T): T =>
  useSelector<RootState, T>(selector)

/**
 * Resets every RTK Query API cache in the store.
 * Call on logout to prevent stale data from a previous user session
 * being briefly shown to the next user who logs in on the same browser.
 */
export const resetAllApiCaches = () => (dispatch: AppDispatch) => {
  dispatch(authApi.util.resetApiState())
  dispatch(geoApi.util.resetApiState())
  dispatch(templeApi.util.resetApiState())
  dispatch(trustApi.util.resetApiState())
  dispatch(employeeApi.util.resetApiState())
  dispatch(contractorApi.util.resetApiState())
  dispatch(declarationApi.util.resetApiState())
  dispatch(documentApi.util.resetApiState())
  dispatch(notificationApi.util.resetApiState())
  dispatch(exportApi.util.resetApiState())
  dispatch(adminApi.util.resetApiState())
  dispatch(dcApi.util.resetApiState())
  dispatch(governanceApi.util.resetApiState())
  dispatch(workflowApi.util.resetApiState())
  dispatch(governanceV2Api.util.resetApiState())
  dispatch(auditorApi.util.resetApiState())
  dispatch(viewerApi.util.resetApiState())
  dispatch(timelineApi.util.resetApiState())
  dispatch(financeApi.util.resetApiState())
}
