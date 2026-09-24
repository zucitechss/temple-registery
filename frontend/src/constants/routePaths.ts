export const ROUTE_PATHS = {
  // Public
  PUBLIC_SEARCH: '/search',

  // Auth
  LOGIN: '/login',
  MFA_VERIFY: '/mfa-verify',
  REGISTER: '/register',
  FORGOT_PASSWORD: '/forgot-password',
  UNAUTHORIZED: '/403',

  // DC / DC Staff
  DC_DASHBOARD: '/dc/dashboard',
  DC_NOTICES: '/dc/notices',
  DC_TEMPLES: '/dc/temples',
  DC_TEMPLE_DETAIL: '/dc/temples/:templeId',
  DC_DECLARATIONS: '/dc/declarations',
  DC_DECLARATION_DETAIL: '/dc/declarations/:id',
  DC_EXPORT: '/dc/export',
  DC_WORKFLOW_DASHBOARD: '/dc/workflow',
  DC_ACTIVITY: '/dc/activity',

  // Temple Authority
  TA_DASHBOARD: '/ta/dashboard',
  TA_TEMPLE: '/ta/temple',
  TA_TEMPLE_EDIT: '/ta/temple/edit',
  TA_TEMPLE_REVIEW: '/ta/temple/review',
  TA_TEMPLE_SEARCH: '/ta/temples',
  TA_TEMPLE_DETAIL: '/ta/temples/:templeId',
  TA_TRUST: '/ta/trust',
  TA_EMPLOYEES: '/ta/employees',
  TA_EMPLOYEE_DETAIL: '/ta/employees/:id',
  TA_CONTRACTORS: '/ta/contractors',
  TA_CONTRACTOR_NEW: '/ta/contractors/new',
  TA_CONTRACTOR_EDIT: '/ta/contractors/edit/:id',
  TA_CONTRACTOR_DETAIL: '/ta/contractors/:id',
  TA_DECLARATIONS: '/ta/declarations',
  TA_DECLARATION_NEW: '/ta/declarations/new',
  TA_DECLARATION_DETAIL: '/ta/declarations/:id',
  TA_DOCUMENTS: '/ta/documents',
  TA_PROFILE_STATUS: '/ta/profile-status',
  TA_ACTIVITY: '/ta/activity',

  // Admin
  ADMIN_DASHBOARD: '/admin/dashboard',
  ADMIN_USERS: '/admin/users',
  ADMIN_AUDIT: '/admin/audit',
  ADMIN_GEO: '/admin/geo',
  ADMIN_TOOLS: '/admin/tools',
  ADMIN_TEMPLE_GOVERNANCE: '/admin/temple-governance',
  ADMIN_TEMPLE_EDIT: '/admin/temples/:templeId/edit',
  ADMIN_SYSTEM_CONFIG: '/admin/system-config',
  ADMIN_NOTIFICATION_RULES: '/admin/notification-rules',
  ADMIN_ACCESS_CONTROL: '/admin/access-control',
  ADMIN_NOTICES: '/admin/notices',

  // SA temple module edit routes
  ADMIN_TEMPLE_TRUST: '/admin/temples/:templeId/trust',
  ADMIN_TEMPLE_EMPLOYEES: '/admin/temples/:templeId/employees',
  ADMIN_TEMPLE_CONTRACTORS: '/admin/temples/:templeId/contractors',
  ADMIN_TEMPLE_DOCUMENTS: '/admin/temples/:templeId/documents',
  ADMIN_TEMPLE_DECLARATIONS: '/admin/temples/:templeId/declarations',
  ADMIN_TEMPLE_DECLARATION_NEW: '/admin/temples/:templeId/declarations/new',

  // Auditor
  AUDITOR_DASHBOARD: '/auditor/dashboard',
  AUDITOR_TEMPLES: '/auditor/temples',
  AUDITOR_TEMPLE_DETAIL: '/auditor/temples/:templeId',
  AUDITOR_DECLARATIONS: '/auditor/declarations',
  AUDITOR_DECLARATION_DETAIL: '/auditor/declarations/:id',
  AUDITOR_OBSERVATIONS: '/auditor/observations',
  AUDITOR_OBSERVATION_DETAIL: '/auditor/observations/:id',
  AUDITOR_AUDIT_TRAIL: '/auditor/audit-trail',
  AUDITOR_COMPLIANCE: '/auditor/compliance',

  // Viewer (State Government / Audit Bodies — read-only)
  VIEWER_DASHBOARD: '/viewer/dashboard',
  VIEWER_TEMPLES: '/viewer/temples',
  VIEWER_TEMPLE_DETAIL: '/viewer/temples/:templeId',
  VIEWER_DECLARATIONS: '/viewer/declarations',
  VIEWER_DECLARATION_DETAIL: '/viewer/declarations/:id',
  VIEWER_COMPLIANCE: '/viewer/compliance',
  VIEWER_AUDIT_TRAIL: '/viewer/audit-trail',
  VIEWER_EXPORT: '/viewer/export',

  // Finance integration administration (FIN-054B)
  FINANCE_SOURCE_MAPPER: '/finance/source-mapper',
  FINANCE_SOURCE_SYSTEMS: '/finance/source-systems',

  // Notifications (all authenticated users)
  NOTIFICATIONS: '/notifications',
  NOTIFICATION_PREFERENCES: '/notifications/preferences',
} as const
