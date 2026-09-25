import { lazy, Suspense } from 'react'
import { createBrowserRouter, RouterProvider, Navigate } from 'react-router-dom'
import { ErrorBoundary } from '@/components/feedback/ErrorBoundary/ErrorBoundary'
import { PrivateRoute } from './PrivateRoute'
import { RoleRoute } from './RoleRoute'
import { AppShell } from '@/layouts/AppShell/AppShell'
import { USER_ROLES } from '@/constants/roles'
import { ROUTE_PATHS } from '@/constants/routePaths'
import { TaTemplePage } from '@/features/temple-profile/pages/TaTemplePage/TaTemplePage'
import { TaTempleEditPage } from '@/features/temple-profile/pages/TaTempleEditPage/TaTempleEditPage'
import { TaTempleReviewPage } from '@/features/temple-profile/pages/TaTempleReviewPage/TaTempleReviewPage'

// ── Lazy-loaded pages ─────────────────────────────────────────────────────────
const LoginPage       = lazy(() => import('@/features/auth/pages/LoginPage/LoginPage').then(m => ({ default: m.LoginPage })))
const ForgotPasswordPage = lazy(() => import('@/features/auth/pages/ForgotPasswordPage/ForgotPasswordPage').then(m => ({ default: m.ForgotPasswordPage })))
const ResetPasswordPage = lazy(() => import('@/features/auth/pages/ResetPasswordPage/ResetPasswordPage').then(m => ({ default: m.ResetPasswordPage })))
const ForcePasswordChangePage = lazy(() => import('@/features/auth/pages/ForcePasswordChangePage/ForcePasswordChangePage').then(m => ({ default: m.ForcePasswordChangePage })))
const ProfilePage = lazy(() => import('@/features/profile/pages/ProfilePage/ProfilePage').then(m => ({ default: m.ProfilePage })))
const DcDashboardPage = lazy(() => import('@/features/dc/pages/DcDashboardPage/DcDashboardPage').then(m => ({ default: m.DcModuleDashboardPage })))
const DcTempleSearchPage = lazy(() => import('@/features/dc/pages/DcTempleSearchPage/DcTempleSearchPage').then(m => ({ default: m.DcTempleSearchPage })))
const DcTempleProfilePage = lazy(() => import('@/features/dc/pages/DcTempleProfilePage/DcTempleProfilePage').then(m => ({ default: m.DcTempleProfilePage })))
const DcDeclarationListPage = lazy(() => import('@/features/declaration/pages/DeclarationListPage/DeclarationListPage').then(m => ({ default: m.DeclarationListPage })))
const DcDeclarationDetailPage = lazy(() => import('@/features/dc/pages/DcDeclarationDetailPage/DcDeclarationDetailPage').then(m => ({ default: m.DcDeclarationDetailPage })))
const DcExportPage = lazy(() => import('@/features/dc/pages/DcExportPage/DcExportPage').then(m => ({ default: m.DcExportPage })))
const DcWorkflowDashboardPage = lazy(() => import('@/features/dc/pages/DcWorkflowDashboardPage/DcWorkflowDashboardPage').then(m => ({ default: m.DcWorkflowDashboardPage })))
const DcActivityPage = lazy(() => import('@/features/dc/pages/DcActivityPage/DcActivityPage').then(m => ({ default: m.DcActivityPage })))
const TaDeclarationListPage = lazy(() => import('@/features/declaration/pages/DeclarationListPage/DeclarationListPage').then(m => ({ default: m.DeclarationListPage })))
const TaDeclarationCreatePage = lazy(() => import('@/features/declaration/pages/DeclarationCreatePage/DeclarationCreatePage').then(m => ({ default: m.DeclarationCreatePage })))
const TaDashboardPage = lazy(() => import('@/features/dashboard/pages/TaDashboardPage/TaDashboardPage').then(m => ({ default: m.TaDashboardPage })))
const AdminDashboardPage = lazy(() => import('@/features/dashboard/pages/AdminDashboardPage/AdminDashboardPage').then(m => ({ default: m.AdminDashboardPage })))
const UserManagementPage = lazy(() => import('@/features/admin/pages/UserManagementPage/UserManagementPage').then(m => ({ default: m.UserManagementPage })))
const AuditLogPage = lazy(() => import('@/features/admin/pages/AuditLogPage/AuditLogPage').then(m => ({ default: m.AuditLogPage })))
const AdminToolsPage = lazy(() => import('@/features/admin/pages/AdminToolsPage/AdminToolsPage').then(m => ({ default: m.AdminToolsPage })))
const GeoManagementPage = lazy(() => import('@/features/admin/pages/GeoManagementPage/GeoManagementPage').then(m => ({ default: m.GeoManagementPage })))
const SystemConfigPage = lazy(() => import('@/features/admin/pages/SystemConfigPage/SystemConfigPage').then(m => ({ default: m.SystemConfigPage })))
const NotificationRulesPage = lazy(() => import('@/features/admin/pages/NotificationRulesPage/NotificationRulesPage').then(m => ({ default: m.NotificationRulesPage })))
const TempleGovernancePage = lazy(() => import('@/features/admin/pages/TempleGovernancePage/TempleGovernancePage').then(m => ({ default: m.TempleGovernancePage })))
const SaTempleEditPage = lazy(() => import('@/features/admin/pages/SaTempleEditPage/SaTempleEditPage').then(m => ({ default: m.SaTempleEditPage })))
const SaTempleTrustPage = lazy(() => import('@/features/admin/pages/SaTempleTrustPage/SaTempleTrustPage').then(m => ({ default: m.SaTempleTrustPage })))
const SaTempleEmployeesPage = lazy(() => import('@/features/admin/pages/SaTempleEmployeesPage/SaTempleEmployeesPage').then(m => ({ default: m.SaTempleEmployeesPage })))
const SaTempleContractorsPage = lazy(() => import('@/features/admin/pages/SaTempleContractorsPage/SaTempleContractorsPage').then(m => ({ default: m.SaTempleContractorsPage })))
const SaTempleDocumentsPage = lazy(() => import('@/features/admin/pages/SaTempleDocumentsPage/SaTempleDocumentsPage').then(m => ({ default: m.SaTempleDocumentsPage })))
const SaTempleDeclarationsPage = lazy(() => import('@/features/admin/pages/SaTempleDeclarationsPage/SaTempleDeclarationsPage').then(m => ({ default: m.SaTempleDeclarationsPage })))
const SaTempleDeclarationFormPage = lazy(() => import('@/features/admin/pages/SaTempleDeclarationFormPage/SaTempleDeclarationFormPage').then(m => ({ default: m.SaTempleDeclarationFormPage })))
const ObservationsPage = lazy(() => import('@/features/auditor/pages/ObservationsPage/ObservationsPage').then(m => ({ default: m.ObservationsPage })))
const ObservationDetailPage = lazy(() => import('@/features/auditor/pages/ObservationDetailPage/ObservationDetailPage').then(m => ({ default: m.ObservationDetailPage })))
const ComplianceReportPage = lazy(() => import('@/features/auditor/pages/ComplianceReportPage/ComplianceReportPage').then(m => ({ default: m.ComplianceReportPage })))
const AuditTrailPage = lazy(() => import('@/features/auditor/pages/AuditTrailPage/AuditTrailPage').then(m => ({ default: m.AuditTrailPage })))
const AuditorDashboardPage = lazy(() => import('@/features/auditor/pages/AuditorDashboardPage/AuditorDashboardPage').then(m => ({ default: m.AuditorDashboardPage })))
const ViewerDashboardPage = lazy(() => import('@/features/viewer/pages/ViewerDashboardPage/ViewerDashboardPage').then(m => ({ default: m.ViewerDashboardPage })))
const TaTrustPage = lazy(() => import('@/features/trust/pages/TaTrustPage/TaTrustPage').then(m => ({ default: m.TaTrustPage })))
const TaEmployeesPage = lazy(() => import('@/features/employee/pages/TaEmployeesPage/TaEmployeesPage').then(m => ({ default: m.TaEmployeesPage })))
const EmployeeDetailPage = lazy(() => import('@/features/employee/pages/EmployeeDetailPage/EmployeeDetailPage').then(m => ({ default: m.EmployeeDetailPage })))
const TaContractorsPage = lazy(() => import('@/features/contractor/pages/TaContractorsPage/TaContractorsPage').then(m => ({ default: m.TaContractorsPage })))
const ContractorFormPage = lazy(() => import('@/features/contractor/pages/ContractorFormPage/ContractorFormPage').then(m => ({ default: m.ContractorFormPage })))
const ContractorDetailPage = lazy(() => import('@/features/contractor/pages/ContractorDetailPage/ContractorDetailPage').then(m => ({ default: m.ContractorDetailPage })))
const TaDocumentsPage = lazy(() => import('@/features/document/pages/TaDocumentsPage/TaDocumentsPage').then(m => ({ default: m.TaDocumentsPage })))
const TaDeclarationDetailPage = lazy(() => import('@/features/declaration/pages/TaDeclarationDetailPage/TaDeclarationDetailPage').then(m => ({ default: m.TaDeclarationDetailPage })))
const TaProfileStatusPage = lazy(() => import('@/features/dashboard/pages/TaProfileStatusPage/TaProfileStatusPage').then(m => ({ default: m.TaProfileStatusPage })))
const TaActivityPage = lazy(() => import('@/features/dashboard/pages/TaActivityPage/TaActivityPage').then(m => ({ default: m.TaActivityPage })))
const NotificationInboxPage = lazy(() => import('@/features/notification/pages/NotificationInboxPage').then(m => ({ default: m.NotificationInboxPage })))
const NotificationPreferencesPage = lazy(() => import('@/features/notification/pages/NotificationPreferencesPage').then(m => ({ default: m.NotificationPreferencesPage })))
const PublicTempleSearchPage = lazy(() => import('@/features/search/PublicTempleSearchPage').then(m => ({ default: m.PublicTempleSearchPage })))
const TaTempleSearchPage = lazy(() => import('@/features/ta/pages/TaTempleSearchPage/TaTempleSearchPage').then(m => ({ default: m.TaTempleSearchPage })))
const TaTempleDetailPage = lazy(() => import('@/features/ta/pages/TaTempleDetailPage/TaTempleDetailPage').then(m => ({ default: m.TaTempleDetailPage })))
const AccessControlPage = lazy(() => import('@/features/access-control/pages/AccessControlPage/AccessControlPage').then(m => ({ default: m.AccessControlPage })))
const DcNoticesPage = lazy(() => import('@/features/notice/pages/DcNoticesPage/DcNoticesPage').then(m => ({ default: m.DcNoticesPage })))
const AdminNoticesPage = lazy(() => import('@/features/notice/pages/AdminNoticesPage/AdminNoticesPage').then(m => ({ default: m.AdminNoticesPage })))

const PageLoader = () => (
  <div className="flex h-64 items-center justify-center">
    <div className="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent" />
  </div>
)

const router = createBrowserRouter([
  // ── Public routes ─────────────────────────────────────────────────────────
  { path: ROUTE_PATHS.PUBLIC_SEARCH, element: <Suspense fallback={<PageLoader />}><PublicTempleSearchPage /></Suspense> },
  { path: ROUTE_PATHS.LOGIN, element: <Suspense fallback={<PageLoader />}><LoginPage /></Suspense> },
  { path: ROUTE_PATHS.FORGOT_PASSWORD, element: <Suspense fallback={<PageLoader />}><ForgotPasswordPage /></Suspense> },
  { path: ROUTE_PATHS.RESET_PASSWORD, element: <Suspense fallback={<PageLoader />}><ResetPasswordPage /></Suspense> },
  { path: ROUTE_PATHS.UNAUTHORIZED, element: <div className="flex min-h-screen items-center justify-center"><h1 className="text-2xl font-bold text-destructive">403 — Access Denied</h1></div> },

  // ── Protected routes ──────────────────────────────────────────────────────
  {
    element: <PrivateRoute />,
    children: [
      // Forced password change — deliberately outside AppShell: while it is pending the
      // backend blocks every other endpoint, so there is nothing to navigate to.
      { path: ROUTE_PATHS.CHANGE_PASSWORD, element: <Suspense fallback={<PageLoader />}><ForcePasswordChangePage /></Suspense> },
      {
        element: <AppShell />,
        children: [
          // DC / DC Staff / Super Admin — core DC routes
          {
            element: <RoleRoute allowedRoles={[USER_ROLES.DISTRICT_COLLECTOR, USER_ROLES.DC_STAFF, USER_ROLES.SUPER_ADMIN]} />,
            children: [
              { path: ROUTE_PATHS.DC_DASHBOARD, element: <Suspense fallback={<PageLoader />}><DcDashboardPage /></Suspense> },
              { path: ROUTE_PATHS.DC_NOTICES, element: <Suspense fallback={<PageLoader />}><DcNoticesPage /></Suspense> },
              { path: ROUTE_PATHS.DC_DECLARATIONS, element: <Suspense fallback={<PageLoader />}><DcDeclarationListPage /></Suspense> },
              { path: ROUTE_PATHS.DC_DECLARATION_DETAIL, element: <Suspense fallback={<PageLoader />}><DcDeclarationDetailPage /></Suspense> },
              { path: ROUTE_PATHS.DC_EXPORT, element: <Suspense fallback={<PageLoader />}><DcExportPage /></Suspense> },
              { path: ROUTE_PATHS.DC_WORKFLOW_DASHBOARD, element: <Suspense fallback={<PageLoader />}><DcWorkflowDashboardPage /></Suspense> },
              { path: ROUTE_PATHS.DC_ACTIVITY, element: <Suspense fallback={<PageLoader />}><DcActivityPage /></Suspense> },
            ],
          },
          // Temple search and profile — DC roles + TEMPLE_AUTHORITY (read-only view for TA)
          {
            element: <RoleRoute allowedRoles={[USER_ROLES.DISTRICT_COLLECTOR, USER_ROLES.DC_STAFF, USER_ROLES.SUPER_ADMIN, USER_ROLES.TEMPLE_AUTHORITY]} />,
            children: [
              { path: ROUTE_PATHS.DC_TEMPLES, element: <Suspense fallback={<PageLoader />}><DcTempleSearchPage /></Suspense> },
              { path: ROUTE_PATHS.DC_TEMPLE_DETAIL, element: <Suspense fallback={<PageLoader />}><DcTempleProfilePage /></Suspense> },
            ],
          },
          // Temple Authority
          {
            element: <RoleRoute allowedRoles={[USER_ROLES.TEMPLE_AUTHORITY]} />,
            children: [
              { path: ROUTE_PATHS.TA_DASHBOARD, element: <Suspense fallback={<PageLoader />}><TaDashboardPage /></Suspense> },
              { path: ROUTE_PATHS.TA_TEMPLE, element: <Suspense fallback={<PageLoader />}><TaTemplePage /></Suspense> },
              { path: ROUTE_PATHS.TA_TEMPLE_EDIT, element: <Suspense fallback={<PageLoader />}><TaTempleEditPage /></Suspense> },
              { path: ROUTE_PATHS.TA_TEMPLE_REVIEW, element: <Suspense fallback={<PageLoader />}><TaTempleReviewPage /></Suspense> },
              { path: ROUTE_PATHS.TA_TRUST, element: <Suspense fallback={<PageLoader />}><TaTrustPage /></Suspense> },
              { path: ROUTE_PATHS.TA_EMPLOYEES, element: <Suspense fallback={<PageLoader />}><TaEmployeesPage /></Suspense> },
              { path: ROUTE_PATHS.TA_EMPLOYEE_DETAIL, element: <Suspense fallback={<PageLoader />}><EmployeeDetailPage /></Suspense> },
              { path: ROUTE_PATHS.TA_CONTRACTORS, element: <Suspense fallback={<PageLoader />}><TaContractorsPage /></Suspense> },
              { path: ROUTE_PATHS.TA_CONTRACTOR_NEW, element: <Suspense fallback={<PageLoader />}><ContractorFormPage /></Suspense> },
              { path: ROUTE_PATHS.TA_CONTRACTOR_EDIT, element: <Suspense fallback={<PageLoader />}><ContractorFormPage /></Suspense> },
              { path: ROUTE_PATHS.TA_CONTRACTOR_DETAIL, element: <Suspense fallback={<PageLoader />}><ContractorDetailPage /></Suspense> },
              { path: ROUTE_PATHS.TA_DOCUMENTS, element: <Suspense fallback={<PageLoader />}><TaDocumentsPage /></Suspense> },
              { path: ROUTE_PATHS.TA_DECLARATIONS, element: <Suspense fallback={<PageLoader />}><TaDeclarationListPage /></Suspense> },
              { path: ROUTE_PATHS.TA_DECLARATION_NEW, element: <Suspense fallback={<PageLoader />}><TaDeclarationCreatePage /></Suspense> },
              { path: ROUTE_PATHS.TA_DECLARATION_DETAIL, element: <Suspense fallback={<PageLoader />}><TaDeclarationDetailPage /></Suspense> },
              { path: ROUTE_PATHS.TA_PROFILE_STATUS, element: <Suspense fallback={<PageLoader />}><TaProfileStatusPage /></Suspense> },
              { path: ROUTE_PATHS.TA_ACTIVITY, element: <Suspense fallback={<PageLoader />}><TaActivityPage /></Suspense> },
              { path: ROUTE_PATHS.TA_TEMPLE_SEARCH, element: <Suspense fallback={<PageLoader />}><TaTempleSearchPage /></Suspense> },
              { path: ROUTE_PATHS.TA_TEMPLE_DETAIL, element: <Suspense fallback={<PageLoader />}><TaTempleDetailPage /></Suspense> },
            ],
          },
          // Super Admin
          {
            element: <RoleRoute allowedRoles={[USER_ROLES.SUPER_ADMIN]} />,
            children: [
              { path: ROUTE_PATHS.ADMIN_DASHBOARD, element: <Suspense fallback={<PageLoader />}><AdminDashboardPage /></Suspense> },
              { path: ROUTE_PATHS.ADMIN_USERS, element: <Suspense fallback={<PageLoader />}><UserManagementPage /></Suspense> },
              { path: ROUTE_PATHS.ADMIN_AUDIT, element: <Suspense fallback={<PageLoader />}><AuditLogPage /></Suspense> },
              { path: ROUTE_PATHS.ADMIN_GEO, element: <Suspense fallback={<PageLoader />}><GeoManagementPage /></Suspense> },
              { path: ROUTE_PATHS.ADMIN_TOOLS, element: <Suspense fallback={<PageLoader />}><AdminToolsPage /></Suspense> },
              { path: ROUTE_PATHS.ADMIN_SYSTEM_CONFIG, element: <Suspense fallback={<PageLoader />}><SystemConfigPage /></Suspense> },
              { path: ROUTE_PATHS.ADMIN_NOTIFICATION_RULES, element: <Suspense fallback={<PageLoader />}><NotificationRulesPage /></Suspense> },
              { path: ROUTE_PATHS.ADMIN_TEMPLE_GOVERNANCE, element: <Suspense fallback={<PageLoader />}><TempleGovernancePage /></Suspense> },
              { path: ROUTE_PATHS.ADMIN_TEMPLE_EDIT, element: <Suspense fallback={<PageLoader />}><SaTempleEditPage /></Suspense> },
              { path: ROUTE_PATHS.ADMIN_TEMPLE_TRUST, element: <Suspense fallback={<PageLoader />}><SaTempleTrustPage /></Suspense> },
              { path: ROUTE_PATHS.ADMIN_TEMPLE_EMPLOYEES, element: <Suspense fallback={<PageLoader />}><SaTempleEmployeesPage /></Suspense> },
              { path: ROUTE_PATHS.ADMIN_TEMPLE_CONTRACTORS, element: <Suspense fallback={<PageLoader />}><SaTempleContractorsPage /></Suspense> },
              { path: ROUTE_PATHS.ADMIN_TEMPLE_DOCUMENTS, element: <Suspense fallback={<PageLoader />}><SaTempleDocumentsPage /></Suspense> },
              { path: ROUTE_PATHS.ADMIN_TEMPLE_DECLARATIONS, element: <Suspense fallback={<PageLoader />}><SaTempleDeclarationsPage /></Suspense> },
              { path: ROUTE_PATHS.ADMIN_TEMPLE_DECLARATION_NEW, element: <Suspense fallback={<PageLoader />}><SaTempleDeclarationFormPage /></Suspense> },
              { path: ROUTE_PATHS.ADMIN_ACCESS_CONTROL, element: <Suspense fallback={<PageLoader />}><AccessControlPage /></Suspense> },
              { path: ROUTE_PATHS.ADMIN_NOTICES, element: <Suspense fallback={<PageLoader />}><AdminNoticesPage /></Suspense> },
            ],
          },
          // Auditor (read-only) — SUPER_ADMIN can also access auditor pages via sidebar links
          {
            element: <RoleRoute allowedRoles={[USER_ROLES.AUDITOR, USER_ROLES.SUPER_ADMIN]} />,
            children: [
              { path: ROUTE_PATHS.AUDITOR_DASHBOARD, element: <Suspense fallback={<PageLoader />}><AuditorDashboardPage /></Suspense> },
              { path: ROUTE_PATHS.AUDITOR_TEMPLES, element: <Suspense fallback={<PageLoader />}><DcTempleSearchPage /></Suspense> },
              { path: ROUTE_PATHS.AUDITOR_TEMPLE_DETAIL, element: <Suspense fallback={<PageLoader />}><DcTempleProfilePage /></Suspense> },
              { path: ROUTE_PATHS.AUDITOR_DECLARATIONS, element: <Suspense fallback={<PageLoader />}><DcDeclarationListPage /></Suspense> },
              { path: ROUTE_PATHS.AUDITOR_DECLARATION_DETAIL, element: <Suspense fallback={<PageLoader />}><DcDeclarationDetailPage /></Suspense> },
              { path: ROUTE_PATHS.AUDITOR_OBSERVATIONS, element: <Suspense fallback={<PageLoader />}><ObservationsPage /></Suspense> },
              { path: ROUTE_PATHS.AUDITOR_OBSERVATION_DETAIL, element: <Suspense fallback={<PageLoader />}><ObservationDetailPage /></Suspense> },
              { path: ROUTE_PATHS.AUDITOR_COMPLIANCE, element: <Suspense fallback={<PageLoader />}><ComplianceReportPage /></Suspense> },
              { path: ROUTE_PATHS.AUDITOR_AUDIT_TRAIL, element: <Suspense fallback={<PageLoader />}><AuditTrailPage /></Suspense> },
            ],
          },
          // Viewer (State Government / Audit Bodies — read-only, statewide)
          {
            element: <RoleRoute allowedRoles={[USER_ROLES.VIEWER]} />,
            children: [
              { path: ROUTE_PATHS.VIEWER_DASHBOARD, element: <Suspense fallback={<PageLoader />}><ViewerDashboardPage /></Suspense> },
              { path: ROUTE_PATHS.VIEWER_TEMPLES, element: <Suspense fallback={<PageLoader />}><DcTempleSearchPage /></Suspense> },
              { path: ROUTE_PATHS.VIEWER_TEMPLE_DETAIL, element: <Suspense fallback={<PageLoader />}><DcTempleProfilePage /></Suspense> },
              { path: ROUTE_PATHS.VIEWER_DECLARATIONS, element: <Suspense fallback={<PageLoader />}><DcDeclarationListPage /></Suspense> },
              { path: ROUTE_PATHS.VIEWER_DECLARATION_DETAIL, element: <Suspense fallback={<PageLoader />}><DcDeclarationDetailPage /></Suspense> },
              { path: ROUTE_PATHS.VIEWER_COMPLIANCE, element: <Suspense fallback={<PageLoader />}><ComplianceReportPage /></Suspense> },
              { path: ROUTE_PATHS.VIEWER_AUDIT_TRAIL, element: <Suspense fallback={<PageLoader />}><AuditTrailPage /></Suspense> },
              { path: ROUTE_PATHS.VIEWER_EXPORT, element: <Suspense fallback={<PageLoader />}><DcExportPage /></Suspense> },
            ],
          },
          // Notifications (all authenticated users)
          {
            children: [
              { path: ROUTE_PATHS.NOTIFICATIONS, element: <Suspense fallback={<PageLoader />}><NotificationInboxPage /></Suspense> },
              { path: ROUTE_PATHS.NOTIFICATION_PREFERENCES, element: <Suspense fallback={<PageLoader />}><NotificationPreferencesPage /></Suspense> },
            ],
          },
          // Profile (all authenticated users — no RoleRoute, every role has a profile)
          {
            children: [
              { path: ROUTE_PATHS.PROFILE, element: <Suspense fallback={<PageLoader />}><ProfilePage /></Suspense> },
            ],
          },
        ],
      },
    ],
  },

  // ── Catch-all redirect ────────────────────────────────────────────────────
  { path: '/', element: <Navigate to={ROUTE_PATHS.LOGIN} replace /> },
  { path: '*', element: <Navigate to={ROUTE_PATHS.LOGIN} replace /> },
])

export function AppRouter() {
  return (
    <ErrorBoundary>
      <RouterProvider router={router} />
    </ErrorBoundary>
  )
}
