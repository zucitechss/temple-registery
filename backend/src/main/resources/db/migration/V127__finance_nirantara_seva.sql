-- ============================================================================
-- V127: Nirantara seva -- subscriptions and their payments (FR11)
--
-- TWO TABLES, NOT THE FOUR ADR-009 DESIGNS
--
-- ADR-009 models the Nirantara lifecycle as four tables -- subscription,
-- payment, schedule and execution -- to answer "what is booked, and is it
-- actually taking place?". The second half of that question is a real one, and
-- the ADR records that the first onboarded source cannot answer it, so
-- fin_nirantara_execution was designed to be created and remain empty.
--
-- FR11 does not ask it. It asks for the list of Nirantara sevas, how many
-- bookings each has, and per booking: booking id, type, start date and income
-- for the financial year to date. That is subscriptions and payments.
--
-- Building schedule and execution now would add two tables that no report reads
-- and no source fills. They remain in ADR-009 as the design to follow if
-- execution tracking is ever required; they are not created here, and deleting
-- a table later is harder than adding one.
--
-- PAYMENT IS SEPARATE FROM SUBSCRIPTION, AND FROM REVENUE
--
-- A perpetual booking is a standing arrangement made once; money against it
-- arrives many times, sometimes annually, sometimes as a single endowment. One
-- table with an amount column would force a choice between losing the payment
-- history and repeating the booking on every payment.
--
-- The money may ALSO appear in fin_revenue_fact, because a Nirantara receipt is
-- a receipt like any other and the connector extracting revenue will see it.
-- That is not double counting as long as nothing sums the two: FR11 reports
-- per-booking income from this table, and FR2, FR3 and FR10 report revenue from
-- the revenue facts. No report adds them together, and none should. The rule is
-- recorded here because it is the mistake this shape makes easy.
--
-- SUBSCRIBER IS A PSEUDONYMOUS HANDLE
--
-- Consistent with the revenue fact holding no devotee name, address, mobile or
-- email. A central oversight platform holding personal data it cannot use is a
-- liability; the temple system keeps the identity, and this platform keeps the
-- handle needed to count bookings and trace one back.
-- ============================================================================

CREATE TABLE fin_nirantara_subscription (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    temple_id               BIGINT        NOT NULL  COMMENT 'Registry temple id. Isolation key, never hardcoded anywhere',
    source_system_id        BIGINT        NOT NULL,
    sync_batch_id           BIGINT        NOT NULL,
    source_of_truth_version INT           NULL,
    source_record_ref       VARCHAR(200)  NOT NULL  COMMENT 'Row-exact provenance handle. In the grain',

    booking_ref             VARCHAR(100)  NULL      COMMENT 'FR11 booking id -- "if available", so nullable by requirement. The source booking number, not this table id',
    service_id              BIGINT        NULL      COMMENT 'fin_service_dim.id. NULL until service resolution can place this seva in the catalogue',
    seva_type               VARCHAR(150)  NOT NULL  COMMENT 'FR11 type of Nirantara seva. Canonical, resolved via mapping_type = NIRANTARA_TYPE. Present even when service_id is not, because FR11 groups by it',
    subscriber_ref          VARCHAR(100)  NULL      COMMENT 'Pseudonymous handle. Never a devotee name, address, mobile or email',

    start_date              DATE          NOT NULL  COMMENT 'FR11 start date. BUSINESS date the arrangement begins',
    end_date                DATE          NULL      COMMENT 'NULL = perpetual, which is the usual case and the meaning of Nirantara',
    status                  VARCHAR(30)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE | LAPSED | CANCELLED | COMPLETED',
    financial_year          VARCHAR(10)   NOT NULL  COMMENT 'FY the booking was made in',

    created_at              DATETIME(6)   NOT NULL,
    updated_at              DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_fns_record UNIQUE (source_system_id, source_record_ref)
);

CREATE INDEX idx_fns_temple_type   ON fin_nirantara_subscription (temple_id, seva_type, status);
CREATE INDEX idx_fns_temple_start  ON fin_nirantara_subscription (temple_id, start_date);
CREATE INDEX idx_fns_booking       ON fin_nirantara_subscription (temple_id, booking_ref);
CREATE INDEX idx_fns_batch         ON fin_nirantara_subscription (sync_batch_id);

CREATE TABLE fin_nirantara_payment (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    temple_id               BIGINT        NOT NULL  COMMENT 'Denormalised from the subscription so every isolation check is one table deep',
    subscription_id         BIGINT        NOT NULL  COMMENT 'fin_nirantara_subscription.id',
    source_system_id        BIGINT        NOT NULL,
    sync_batch_id           BIGINT        NOT NULL,
    source_record_ref       VARCHAR(200)  NOT NULL  COMMENT 'Row-exact provenance handle. In the grain',

    paid_on                 DATE          NOT NULL  COMMENT 'BUSINESS date of the payment',
    financial_year          VARCHAR(10)   NOT NULL  COMMENT 'FY of the payment. FR11 income is the FY-to-date sum over this column',
    amount                  DECIMAL(18,2) NULL      COMMENT 'NULL = not recorded by this source (ADR-007)',
    currency                CHAR(3)       NOT NULL DEFAULT 'INR',
    payment_mode            VARCHAR(30)   NOT NULL DEFAULT 'UNRECORDED',
    covers_from             DATE          NULL      COMMENT 'Period this payment covers, where the source states one. Not the same as paid_on',
    covers_to               DATE          NULL,

    created_at              DATETIME(6)   NOT NULL,
    updated_at              DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_fnp_record UNIQUE (source_system_id, source_record_ref)
);

CREATE INDEX idx_fnp_subscription ON fin_nirantara_payment (subscription_id, paid_on);
CREATE INDEX idx_fnp_temple_fy    ON fin_nirantara_payment (temple_id, financial_year);
CREATE INDEX idx_fnp_batch        ON fin_nirantara_payment (sync_batch_id);
