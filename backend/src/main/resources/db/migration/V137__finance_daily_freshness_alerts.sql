-- ============================================================================
-- V137: Daily data freshness and missed-entry alerts (FR17, FR18, FR19, FR20)
--
-- Three tables, because the requirement is three different statements:
--
--   fin_data_expectation    what a temple owes, and by when
--   fin_daily_data_status   whether one particular day arrived
--   fin_data_alert          the standing condition that it has not, and how bad
--
-- Collapsing them would each time lose something the specification asks for.
-- Without the expectation, "missed" is undefined -- there is nothing that says
-- this temple owes daily expenditure and that one does not. Without the per-day
-- status, FR19's bulk upload cannot report which of twelve dates it satisfied,
-- and FR20's clearing has nothing to re-evaluate. Without the alert, there is
-- no place for a severity that changes on day 8.
--
-- ============================================================================
-- WHY NOT THE EXISTING NOTIFICATION TABLES
-- ============================================================================
--
-- The platform already has in-app notifications, rules, an outbox, delivery
-- preferences and an SSE channel, and this feature uses all of them. But a
-- notification is a message that was sent. It cannot escalate, it cannot clear,
-- and it cannot answer "which temples are in breach right now" -- which is what
-- both dashboards display.
--
-- So: fin_data_alert holds the state, and the existing notification stack
-- delivers its transitions. Nothing here duplicates a notification table, and
-- nothing in the notification tables is asked to hold state it was not built
-- for.
--
-- ============================================================================
-- SEVERITY IS DERIVED, NEVER INCREMENTED
-- ============================================================================
--
-- FR18 sets LOW for the first seven days of missed entries and HIGH from day 8.
-- consecutive_missed_days is recomputed from fin_daily_data_status on every
-- evaluation rather than being counted up, and severity is a function of it.
--
-- That is what makes a partial backfill correct without a special case. A temple
-- twelve days behind is HIGH; it uploads days 3 to 12; two days remain missing;
-- the next evaluation recomputes 2 and the alert DE-ESCALATES to LOW rather than
-- closing or staying high. An incremented counter could not do this, and running
-- the job twice would double it.
--
-- It also makes the job idempotent, which matters because it runs hourly and a
-- restarted worker must not re-alert.
--
-- ============================================================================
-- THE CUTOFF IS LOCAL TIME, AND THAT IS NOT A DETAIL
-- ============================================================================
--
-- FR17 says end of day, 10 PM. Every existing scheduler in this codebase is
-- pinned to UTC. A 22:00 UTC cutoff is 03:30 the following morning in
-- Asia/Kolkata -- it would mark a day missed five and a half hours after the
-- deadline everyone agreed to, and would do it silently.
--
-- cutoff_local_time is therefore stored as a local wall-clock time, and it is
-- resolved against fin_source_system.source_timezone, which V118 already
-- records per source and defaults to Asia/Kolkata. The job runs hourly and
-- evaluates only the expectations whose local cutoff has just passed.
--
-- ============================================================================
-- A DELIBERATE ZERO MUST BE SUBMITTABLE
-- ============================================================================
--
-- status = NIL_RETURN exists so that "we spent nothing on Tuesday" is
-- recordable. Without it, a quiet day is indistinguishable from a forgotten one
-- and the temple is alerted for complying -- which is the same conflation of
-- absent and zero that ADR-007 exists to prevent, arriving through the back
-- door. A nil return fulfils the day and writes no fact, because no transaction
-- occurred.
--
-- WAIVED covers the other case the specification does not address: a day on
-- which nothing was expected. Without it, every closure day produces an alert
-- nobody can clear.
--
-- Whether a temple may declare its own nil returns, or whether that needs DC
-- assent, is a policy question the input service settles. The schema permits
-- both by recording who set it.
-- ============================================================================

CREATE TABLE fin_data_expectation (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    temple_id           BIGINT       NOT NULL  COMMENT 'Registry temple id. Isolation key, never hardcoded anywhere',
    source_system_id    BIGINT       NOT NULL  COMMENT 'fin_source_system.id. Expectations exist only for MANUAL_ENTRY and FILE_UPLOAD channels -- FR17 applies to temples without an automated connector, and that is exactly what connector_type says',
    capability          VARCHAR(50)  NOT NULL  COMMENT 'What is owed, e.g. EXPENSE. Per capability, because a temple may owe daily expenditure and nothing else',
    cadence             VARCHAR(20)  NOT NULL DEFAULT 'DAILY' COMMENT 'DAILY for now. The column exists because WEEKLY is the first thing anyone will ask for, and adding it later would mean backfilling every row',
    cutoff_local_time   TIME         NOT NULL DEFAULT '22:00:00' COMMENT 'FR17 end of day. LOCAL wall clock, resolved against fin_source_system.source_timezone. Never UTC',
    active_from         DATE         NOT NULL  COMMENT 'Expected days are generated from here. Set at onboarding so a new temple does not immediately owe a year of history',
    active_to           DATE         NULL      COMMENT 'NULL = still expected',
    notes               TEXT         NULL,
    is_deleted          TINYINT(1)   NOT NULL DEFAULT 0,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    created_by          BIGINT       NOT NULL DEFAULT 0,
    updated_by          BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_fde_temple_source_capability UNIQUE (temple_id, source_system_id, capability)
);
CREATE INDEX idx_fde_active ON fin_data_expectation (is_deleted, active_from, active_to);

CREATE TABLE fin_daily_data_status (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    temple_id              BIGINT       NOT NULL,
    expectation_id         BIGINT       NOT NULL  COMMENT 'fin_data_expectation.id -- which obligation this day belongs to',
    capability             VARCHAR(50)  NOT NULL  COMMENT 'Denormalised from the expectation so the dashboard query is one table deep',
    business_date          DATE         NOT NULL  COMMENT 'The day being accounted for. A BUSINESS date, never the entry date',
    status                 VARCHAR(20)  NOT NULL  COMMENT 'EXPECTED | SUBMITTED | NIL_RETURN | MISSED | WAIVED',
    fulfilled_by_batch_id  BIGINT       NULL      COMMENT 'fin_sync_batch.id that satisfied this day. NULL while EXPECTED or MISSED',
    fulfilled_at           DATETIME(6)  NULL      COMMENT 'When it was satisfied. Later than business_date for a historical upload, which is the normal case for FR19',
    waived_by              BIGINT       NULL      COMMENT 'Who declared this day not required, for WAIVED',
    waiver_reason          VARCHAR(500) NULL,
    created_at             DATETIME(6)  NOT NULL,
    updated_at             DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_fdds_temple_capability_date UNIQUE (temple_id, capability, business_date)
);
-- The gap query: the missing days for one temple and capability, in date order.
CREATE INDEX idx_fdds_gap    ON fin_daily_data_status (temple_id, capability, status, business_date);
-- The job sweep: everything still outstanding across all temples.
CREATE INDEX idx_fdds_sweep  ON fin_daily_data_status (status, business_date);
CREATE INDEX idx_fdds_batch  ON fin_daily_data_status (fulfilled_by_batch_id);

CREATE TABLE fin_data_alert (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    temple_id                BIGINT       NOT NULL,
    expectation_id           BIGINT       NOT NULL  COMMENT 'fin_data_expectation.id',
    capability               VARCHAR(50)  NOT NULL,
    first_missed_date        DATE         NOT NULL  COMMENT 'Earliest still-missing day in the current gap. Moves later when an early day is backfilled',
    last_missed_date         DATE         NOT NULL  COMMENT 'Most recent missing day, normally the last evaluated cutoff',
    consecutive_missed_days  INT          NOT NULL  COMMENT 'DERIVED on every evaluation from fin_daily_data_status, never incremented. See the header',
    severity                 VARCHAR(20)  NOT NULL  COMMENT 'LOW for 1-7 days, HIGH from 8 (FR18). A function of the column above, so it can go down as well as up',
    status                   VARCHAR(20)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN | CLOSED. There is no ACKNOWLEDGED: FR20 clears on submission, and a dismissable alert could be cleared without supplying the data',
    opened_at                DATETIME(6)  NOT NULL,
    escalated_at             DATETIME(6)  NULL      COMMENT 'When it first became HIGH. Kept through a later de-escalation, because the fact that it was once high is part of the record',
    closed_at                DATETIME(6)  NULL,
    last_evaluated_at        DATETIME(6)  NOT NULL  COMMENT 'When the job last recomputed this row. Proves the job is running, which a quiet alert otherwise cannot',
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
);
-- One open alert per temple and capability. A partial index is unavailable on
-- MySQL and TiDB, so uniqueness is enforced by the alert service and this index
-- is what makes its check, and the dashboard query, cheap.
CREATE INDEX idx_fda_open      ON fin_data_alert (temple_id, capability, status);
CREATE INDEX idx_fda_dashboard ON fin_data_alert (status, severity, temple_id);
CREATE INDEX idx_fda_expectation ON fin_data_alert (expectation_id, status);
