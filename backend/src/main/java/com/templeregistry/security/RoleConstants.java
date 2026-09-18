package com.templeregistry.security;

/**
 * Role string constants used in @PreAuthorize expressions across all service
 * implementations.
 * Roles match exactly what is stored in the JWT 'role' claim and in the users
 * table.
 */
public final class RoleConstants {

        private RoleConstants() {
        }

        public static final String SUPER_ADMIN = "SUPER_ADMIN";
        public static final String DISTRICT_COLLECTOR = "DISTRICT_COLLECTOR";
        public static final String DC_STAFF = "DC_STAFF";
        public static final String TEMPLE_AUTHORITY = "TEMPLE_AUTHORITY";
        public static final String AUDITOR = "AUDITOR";
        public static final String VIEWER = "VIEWER";

        // Spring Security role prefix forms (used in hasRole() expressions)
        public static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";
        public static final String ROLE_DISTRICT_COLLECTOR = "ROLE_DISTRICT_COLLECTOR";
        public static final String ROLE_DC_STAFF = "ROLE_DC_STAFF";
        public static final String ROLE_TEMPLE_AUTHORITY = "ROLE_TEMPLE_AUTHORITY";
        public static final String ROLE_AUDITOR = "ROLE_AUDITOR";
        public static final String ROLE_VIEWER = "ROLE_VIEWER";

        // Convenience SpEL expressions for @PreAuthorize
        public static final String CAN_APPROVE = "hasAnyRole('SUPER_ADMIN', 'DISTRICT_COLLECTOR')";

        /**
         * Write operations that involve governance actions (verifying, flagging,
         * approving).
         * Strictly for DISTRICT_COLLECTOR and SUPER_ADMIN.
         */
        public static final String CAN_ACT_DC = "hasAnyRole('SUPER_ADMIN', 'DISTRICT_COLLECTOR')";

        /**
         * Write operations that DC_STAFF can perform (e.g. marking notifications as
         * read).
         * Excludes AUDITOR which is strictly read-only.
         */
        public static final String CAN_WRITE_DC = "hasAnyRole('SUPER_ADMIN', 'DISTRICT_COLLECTOR', 'DC_STAFF')";

        /** Route-level guard for all DC-module endpoints (controllers). */
        public static final String IS_DC_ROLE = "hasAnyRole('SUPER_ADMIN', 'DISTRICT_COLLECTOR', 'DC_STAFF')";
        public static final String CAN_SUBMIT = "hasAnyRole('SUPER_ADMIN', 'TEMPLE_AUTHORITY')";
        public static final String CAN_READ_ALL = "hasAnyRole('SUPER_ADMIN', 'DISTRICT_COLLECTOR', 'DC_STAFF', 'AUDITOR', 'VIEWER')";
        /** Read access to DC temple search and profile endpoints — includes TEMPLE_AUTHORITY for cross-temple browsing. */
        public static final String CAN_READ_TEMPLES = "hasAnyRole('SUPER_ADMIN', 'DISTRICT_COLLECTOR', 'DC_STAFF', 'AUDITOR', 'VIEWER', 'TEMPLE_AUTHORITY')";
        public static final String ADMIN_ONLY = "hasRole('SUPER_ADMIN')";

        /**
         * Read access to finance integration configuration -- source systems, mapping rules and
         * the values a pipeline run could not classify (FIN-054A).
         *
         * <p>{@code CAN_READ_ALL} minus {@code VIEWER}. A mapping rule is not published information:
         * it is the configuration that decides which category a temple's income is counted under,
         * and the unmapped list beside it names income nobody has classified yet. Both belong to the
         * people who administer the integration, not to statewide read-only browsing.
         *
         * <p>{@code TEMPLE_AUTHORITY} is absent for the stronger reason: see {@code CAN_ACT_DC},
         * which governs the writes.
         */
        public static final String CAN_READ_FINANCE_CONFIG =
                        "hasAnyRole('SUPER_ADMIN', 'DISTRICT_COLLECTOR', 'DC_STAFF', 'AUDITOR')";

        /**
         * Strictly AUDITOR role — for audit-specific endpoints (compliance, audit trail).
         * NOTE: AUDITOR role is defined as read-only in the permission matrix for all
         * data mutation operations. The sole write exception is observation creation:
         * Auditors are compliance officers whose primary function is raising observations.
         * This deliberate exception is documented here and in the requirements addendum.
         * All other write paths (approve, reject, modify data) remain blocked for AUDITOR.
         */
        public static final String AUDITOR_ONLY = "hasRole('AUDITOR')";

        /**
         * Raising a compliance observation is the ONLY write permission granted to AUDITOR.
         * Assigning and closing observations are ADMIN_ONLY operations.
         * Using a named constant makes this intentional exception searchable and auditable.
         */
        public static final String CAN_RAISE_OBSERVATION = "hasAnyRole('AUDITOR', 'SUPER_ADMIN')";

    public static final String TEMPLE_AUTHORITY_ONLY =
            "hasRole('TEMPLE_AUTHORITY')";
}
