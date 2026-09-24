import { combineReducers } from '@reduxjs/toolkit'
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
import { auditorApi } from '@/features/auditor/auditorApi'
import { viewerApi } from '@/features/viewer/viewerApi'
import { timelineApi } from '@/features/timeline/timelineApi'
import authReducer from '@/features/auth/authSlice'
import templeReducer from '@/features/temple-profile/hooks/templeSlice'
import declarationReducer from '@/features/declaration/declarationSlice'
import notificationReducer from '@/features/notification/notificationSlice'
import { templeApi } from '@/features/temple-profile/hooks/templeApi'
import { accessControlApi } from '@/features/access-control/accessControlApi'
import { noticeApi } from '@/features/notice/noticeApi'
import { financeApi } from '@/features/finance/financeApi'
import { financeReportApi } from '@/features/finance-reporting/financeReportApi'
import { financeOnboardingApi } from '@/features/finance-onboarding/financeOnboardingApi'

export const rootReducer = combineReducers({
  // RTK Query caches
  [authApi.reducerPath]: authApi.reducer,
  [geoApi.reducerPath]: geoApi.reducer,
  [templeApi.reducerPath]: templeApi.reducer,
  [trustApi.reducerPath]: trustApi.reducer,
  [employeeApi.reducerPath]: employeeApi.reducer,
  [contractorApi.reducerPath]: contractorApi.reducer,
  [declarationApi.reducerPath]: declarationApi.reducer,
  [documentApi.reducerPath]: documentApi.reducer,
  [notificationApi.reducerPath]: notificationApi.reducer,
  [exportApi.reducerPath]: exportApi.reducer,
  [adminApi.reducerPath]: adminApi.reducer,
  [dcApi.reducerPath]: dcApi.reducer,
  [governanceApi.reducerPath]: governanceApi.reducer,
  [workflowApi.reducerPath]: workflowApi.reducer,
  [governanceV2Api.reducerPath]: governanceV2Api.reducer,
  [auditorApi.reducerPath]: auditorApi.reducer,
  [viewerApi.reducerPath]: viewerApi.reducer,
  [timelineApi.reducerPath]: timelineApi.reducer,
  [accessControlApi.reducerPath]: accessControlApi.reducer,
  [noticeApi.reducerPath]: noticeApi.reducer,
  [financeApi.reducerPath]: financeApi.reducer,
  [financeReportApi.reducerPath]: financeReportApi.reducer,
  [financeOnboardingApi.reducerPath]: financeOnboardingApi.reducer,

  // UI slices
  auth: authReducer,
  temple: templeReducer,
  declaration: declarationReducer,
  notification: notificationReducer,
})
