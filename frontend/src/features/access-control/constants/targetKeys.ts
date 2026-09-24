/**
 * Typed target key constants — mirrors the seed data from V10 + V11 Flyway migrations.
 * Format: {type}.{module}.{action}
 *
 * These are the keys used in backend @DacvmGuard annotations and in frontend PolicyGate / usePermissions.
 */
export const TARGET_KEYS = {
  // ── Admin sidebar pages ───────────────────────────────────────────────────
  PAGE_ADMIN_DASHBOARD:         'page.admin.dashboard',
  PAGE_ADMIN_USERS:             'page.admin.users',
  PAGE_ADMIN_AUDIT:             'page.admin.audit',
  PAGE_ADMIN_GEO:               'page.admin.geo',
  PAGE_ADMIN_SYSTEM_CONFIG:     'page.admin.system_config',
  PAGE_ADMIN_NOTIFICATION:      'page.admin.notification_rules',
  PAGE_ADMIN_ACCESS_CONTROL:    'page.admin.access_control',
  PAGE_ADMIN_GOVERNANCE:        'page.admin.governance',

  // ── DC sidebar pages ──────────────────────────────────────────────────────
  PAGE_DC_DASHBOARD:            'page.dc.dashboard',
  PAGE_DC_EXPORT:               'page.dc.export',
  PAGE_DC_WORKFLOW:             'page.dc.workflow',
  PAGE_DC_ACTIVITY:             'page.dc.activity',

  // ── Auditor sidebar pages ─────────────────────────────────────────────────
  PAGE_AUDITOR_COMPLIANCE:      'page.auditor.compliance',
  PAGE_AUDITOR_OBSERVATIONS:    'page.auditor.observations',
  PAGE_AUDITOR_AUDIT_TRAIL:     'page.auditor.audit_trail',
  PAGE_AUDITOR_DASHBOARD:       'page.auditor.dashboard',

  // ── Viewer sidebar pages ──────────────────────────────────────────────────
  PAGE_VIEWER_DASHBOARD:        'page.viewer.dashboard',
  PAGE_VIEWER_EXPORT:           'page.viewer.export',

  // ── Temple Authority sidebar pages ────────────────────────────────────────
  PAGE_TA_DASHBOARD:            'page.ta.dashboard',
  PAGE_TA_TRUST:                'page.ta.trust',
  PAGE_TA_EMPLOYEES:            'page.ta.employees',
  PAGE_TA_CONTRACTORS:          'page.ta.contractors',
  PAGE_TA_DOCUMENTS:            'page.ta.documents',
  PAGE_TA_DECLARATIONS:         'page.ta.declarations',
  PAGE_TA_TEMPLE_SEARCH:        'page.ta.temple_search',

  // ── DC temple profile page tabs ───────────────────────────────────────────
  TAB_DC_TEMPLE_OVERVIEW:        'tab.dc.temple.overview',
  TAB_DC_TEMPLE_DECLARATIONS:    'tab.dc.temple.declarations',
  TAB_DC_TEMPLE_TRUST:           'tab.dc.temple.trust',
  TAB_DC_TEMPLE_STAFF:           'tab.dc.temple.staff',
  TAB_DC_TEMPLE_CONTRACTORS:     'tab.dc.temple.contractors',
  TAB_DC_TEMPLE_DOCUMENTS:       'tab.dc.temple.documents',
  TAB_DC_TEMPLE_TIMELINE:        'tab.dc.temple.timeline',
  TAB_DC_TEMPLE_FINANCE:         'tab.dc.temple.finance',
  TAB_DC_TEMPLE_PROFILE_HISTORY: 'tab.dc.temple.profile_history',

  // ── TA own-temple page tabs ───────────────────────────────────────────────
  TAB_TA_TEMPLE_OVERVIEW:        'tab.ta.temple.overview',
  TAB_TA_TEMPLE_HISTORY:         'tab.ta.temple.history',
  TAB_TA_TEMPLE_TIMELINE:        'tab.ta.temple.timeline',

  // ── Buttons / Actions ─────────────────────────────────────────────────────
  BUTTON_TA_EMPLOYEES_ADD:      'button.ta.employees.add',
  BUTTON_TA_CONTRACTORS_ADD:    'button.ta.contractors.add',
  BUTTON_TA_TRUST_EDIT:         'button.ta.trust.edit',
  BUTTON_DC_APPROVE:            'button.dc.approve',
  BUTTON_DC_REJECT:             'button.dc.reject',
  BUTTON_ADMIN_USER_DEACTIVATE: 'button.admin.user.deactivate',
  BUTTON_ADMIN_USER_RESET_PW:   'button.admin.user.reset_password',

  // ── Fields ────────────────────────────────────────────────────────────────
  FIELD_TEMPLE_BANK_ACCOUNT:    'field.temple.bank_account',
  FIELD_TEMPLE_AADHAAR:         'field.temple.aadhaar',
  FIELD_USER_MOBILE:            'field.user.mobile',

  // ── Reports ───────────────────────────────────────────────────────────────
  REPORT_COMPLIANCE_FULL:       'report.compliance.full',
  REPORT_EXPORT_CSV:            'report.export.csv',
  REPORT_AUDIT_TRAIL:           'report.audit_trail',

  // ── Admin Dashboard KPI cards ─────────────────────────────────────────────
  KPI_ADMIN_TOTAL_USERS:           'kpi.admin.total_users',
  KPI_ADMIN_TEMPLES_REGISTERED:    'kpi.admin.temples_registered',
  KPI_ADMIN_PENDING_DECLARATIONS:  'kpi.admin.pending_declarations',
  KPI_ADMIN_AUDIT_EVENTS:          'kpi.admin.audit_events',

  // ── DC Dashboard KPI cards ────────────────────────────────────────────────
  KPI_DC_TOTAL_TEMPLES:            'kpi.dc.total_temples',
  KPI_DC_PENDING_DECLARATIONS:     'kpi.dc.pending_declarations',
  KPI_DC_OVERDUE_DECLARATIONS:     'kpi.dc.overdue_declarations',
  KPI_DC_PROFILE_REVIEWS:          'kpi.dc.profile_reviews',

  // ── Auditor Dashboard KPI cards ───────────────────────────────────────────
  KPI_AUDITOR_OPEN_OBSERVATIONS:   'kpi.auditor.open_observations',
  KPI_AUDITOR_COMPLIANCE_ANOMALIES:'kpi.auditor.compliance_anomalies',
  KPI_AUDITOR_OVERDUE_DECLARATIONS:'kpi.auditor.overdue_declarations',
  KPI_AUDITOR_ASSIGNED_REVIEWS:    'kpi.auditor.assigned_reviews',

  // ── Viewer Dashboard KPI cards ────────────────────────────────────────────
  KPI_VIEWER_COMPLIANCE_ANOMALIES: 'kpi.viewer.compliance_anomalies',
  KPI_VIEWER_OVERDUE_DECLARATIONS: 'kpi.viewer.overdue_declarations',
  KPI_VIEWER_OPEN_OBSERVATIONS:    'kpi.viewer.open_observations',
  KPI_VIEWER_ASSIGNED_REVIEWS:     'kpi.viewer.assigned_reviews',

  // ── TA Temple Search page KPI cards ───────────────────────────────────────
  KPI_TA_SEARCH_TOTAL_TEMPLES:     'kpi.ta.search.total_temples',
  KPI_TA_SEARCH_OVERDUE:           'kpi.ta.search.overdue',
  KPI_TA_SEARCH_PENDING:           'kpi.ta.search.pending',
  KPI_TA_SEARCH_PROFILE_REVIEWS:   'kpi.ta.search.profile_reviews',

  // ── Temple Search page filter sections ────────────────────────────────────
  SECTION_DC_SEARCH_DECLARATION_STATUS: 'section.dc.search.declaration_status',
  SECTION_DC_SEARCH_TRUST_REGISTERED:   'section.dc.search.trust_registered',
  SECTION_DC_SEARCH_SAVED_FILTERS:      'section.dc.search.saved_filters',
  SECTION_DC_SEARCH_CARD_STATUS:        'section.dc.search.card_status',
  SECTION_DC_SEARCH_CARD_TRUST:         'section.dc.search.card_trust',
  SECTION_TA_SEARCH_DECLARATION_STATUS: 'section.ta.search.declaration_status',
  SECTION_TA_SEARCH_TRUST_REGISTERED:   'section.ta.search.trust_registered',
  SECTION_TA_SEARCH_SAVED_FILTERS:      'section.ta.search.saved_filters',
  SECTION_TA_SEARCH_CARD_STATUS:        'section.ta.search.card_status',
  SECTION_TA_SEARCH_CARD_TRUST:         'section.ta.search.card_trust',
} as const

export type TargetKey = typeof TARGET_KEYS[keyof typeof TARGET_KEYS]
