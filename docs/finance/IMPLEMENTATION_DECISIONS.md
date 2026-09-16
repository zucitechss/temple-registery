# Finance Implementation Decisions

Decisions made **during implementation**. Architectural decisions made before
implementation are in [adr/](adr/) and are not repeated here.

Each entry records what was decided, why, what was rejected, and what it affects.

---

## FIN-D-001 — Config entities extend `BaseEntity`; operational entities do not

**Date:** 2026-09-16 · **Affects:** all Phase 1 entities

**Decision.** `FinSourceSystem`, `FinTempleCapability`, `FinSourceOfTruthDecl` and
`FinMappingRule` extend `BaseEntity`. `FinSyncBatch`, `FinSyncError` and
`FinReconciliationResult` are standalone with `@CreationTimestamp` / `@UpdateTimestamp`.

**Reason.** The split already exists in this codebase and encodes a real distinction.
Configuration is authored by a person, reviewed, and meaningfully soft-deletable — it
wants `created_by`, `updated_by` and `is_deleted`. A sync batch is an append-mostly
operational record whose lifecycle is its status; soft-deleting one would destroy audit
evidence. `EmailOutbox` documents exactly this reasoning for the same reason, so following
it keeps one pattern in the codebase rather than two.

**Rejected.** Everything extending `BaseEntity` — would put a soft-delete flag on the
audit spine. Nothing extending it — would lose authorship on configuration that requires
sign-off.

**Verified.** `JpaAuditConfig.auditorProvider()` falls back to user `0` when there is no
security context, so audited config entities persist correctly from headless jobs. This
was checked, not assumed; it is what makes the sync worker able to write configuration.

---

## FIN-D-002 — No credential, host or connection field on `fin_source_system`

**Date:** 2026-09-16 · **Affects:** `FinSourceSystem`, V110, FIN-020

**Decision.** The table carries `credential_ref` — an alias — and nothing else connection
related. No host, port, JDBC URL or password column exists. `source_database_name` is
documentation and is never used to build a connection.

**Reason.** ADR-001 says the registry runtime must never reach a temple database. A
comment saying so can be overlooked; a schema with nowhere to put a password cannot be
misused. This converts the constraint from a rule people must remember into a property of
the system.

**Rejected.** Storing an encrypted connection string via the existing
`AesEncryptionConverter`. It would have worked technically and reused existing code, which
made it tempting — but it would place a decryptable route to every temple database inside
the registry process, which is precisely what the constraint forbids.

**Enforcement.** `FinanceFoundationRepositoryTest.should_haveNoCredentialOrConnectionFields_when_sourceSystemInspected`
inspects the entity by reflection and fails if a field containing `password`, `host`,
`port`, `jdbcurl` or `connectionstring` is ever added.

---

## FIN-D-003 — No foreign-key constraints in V110

**Date:** 2026-09-16 · **Affects:** V110

**Decision.** Relationships are enforced by the application and supported by indexes. No
`FOREIGN KEY` clauses.

**Reason.** Consistent with `V10__add_access_control_tables.sql`, which declares none, and
appropriate to the TiDB deployment target. Finance tables also reference `temples.id`
across a module boundary that the rest of the codebase does not constrain.

**Rejected.** Full referential integrity. It is the stronger choice in a single-node
MySQL, and worth revisiting if the platform ever moves off TiDB.

**Risk accepted.** An orphaned `source_system_id` becomes possible. Mitigated by the fact
that source systems are created through a single onboarding path, not ad hoc.

---

## FIN-D-004 — `sync_enabled` defaults to `false`

**Date:** 2026-09-16 · **Affects:** `FinSourceSystem`, V110

**Decision.** Column default `0`; entity `@Builder.Default false`.

**Reason.** Registering a source system is a description of reality. Starting to contact a
government temple's production database is an operational act. Defaulting to enabled would
merge the two, so that documenting a temple would begin generating traffic to it. Turning
sync on should be a deliberate, separately auditable step.

---

## FIN-D-005 — Latest *successful* batch is a separate query from latest batch

**Date:** 2026-09-16 · **Affects:** `FinSyncBatchRepository`

**Decision.** Two finder methods:
`findFirstBySourceSystemIdAndCapabilityOrderByIdDesc` (any outcome) and
`findFirstBySourceSystemIdAndCapabilityAndStatusOrderByIdDesc` (constrained to `SUCCESS`).

**Reason.** These answer different questions and conflating them is a data-loss bug. The
watermark and reported freshness must come from the last **successful** batch. If a failed
attempt could supply the watermark, the next run would resume after a window it never
loaded, and the missing revenue would never be noticed because nothing would report an
error. Keeping them separate at the repository level means a caller has to choose
consciously.

**Tested.** `should_distinguishLatestSuccessFromLatestAttempt_when_lastBatchFailed`.

---

## FIN-D-006 — Reconciliation tolerance defaults to zero

**Date:** 2026-09-16 · **Affects:** `FinReconciliationResult`, V110

**Decision.** `tolerance_pct` defaults to `0.0000`.

**Reason.** Revenue reconciliation is a comparison of two computations of the same
receipts. They should agree exactly. A non-zero default tolerance would silently absorb
the class of error the check exists to catch — and the Kollur analysis found a 41 %
discrepancy between two candidate revenue fields, which no sane tolerance would have
caught but a habit of tolerating small gaps would have normalised.

Tolerance remains a per-row column so that a genuinely approximate metric can set one
explicitly, with the value visible in the record.

---

## Inherited Data Decisions (restated, not re-litigated)

These were established during the analysis phase and are binding on implementation. They
are repeated here because implementation code must reference them, not to reopen them.

| # | Decision | Consequence for code |
|---|---|---|
| 1 | `DailySevaNew.Amount` is the authoritative revenue amount | Declared in `fin_source_of_truth_decl`, stamped on every fact |
| 2 | `DailySevaNewOld` must **not** be read | Excluded in FIN-043; it duplicates all six FY archives |
| 3 | `DailySevaNewDetails.TotalAmount` must **not** be treated as revenue | 41 % below header total; recorded as a rejected alternative |
| 4 | Revenue coverage begins FY2019-20 | `fin_temple_capability.coverage_from` |
| 5 | Metal weight comes from `HKanikeItems.Qty` | Real grams, per item |
| 6 | The 15 g per item assumption is **wrong** | Must not appear anywhere |
| 7 | Metal valuation is not available | `PRECIOUS_METAL_VALUE` = `NOT_AVAILABLE` |
| 8 | Expenditure data does not exist | `EXPENSE` = `NOT_AVAILABLE`, never `0` |
| 9 | Grant data does not exist | `GRANT` = `NOT_AVAILABLE` |
| 10 | Nirantara execution is not recorded | `fin_nirantara_execution` created empty by design |
| 11 | Payment mode is inferred, not recorded | `payment_mode_confidence = INFERRED` |
| 12 | Missing data is `NOT_AVAILABLE`, never zero | Enforced by nullable amounts throughout |
| 13 | Receipt counts are not devotee counts | Field named `transaction_count`; UI copy must match |
