# Finance Platform — Handoff

**Updated:** 2026-09-16 (FIN-051, FIN-052)
**Branch:** `feature/db-integration`
**Read first**, then [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md),
[IMPLEMENTATION_TASKS.md](IMPLEMENTATION_TASKS.md),
[IMPLEMENTATION_DECISIONS.md](IMPLEMENTATION_DECISIONS.md).

---

## Task Status

| Task | Status |
|---|---|
| FIN-051 — Canonical dimensions | **COMPLETE** (this session) |
| FIN-052 — `fin_revenue_fact`, daily grain | **COMPLETE** (this session) |
| FIN-031 — Connector registry | **COMPLETE** |
| FIN-030 — Connector contract | **COMPLETE** |
| FIN-021…024 — Kollur configuration | **COMPLETE** |
| FIN-016 — Registry / sync-worker split | **COMPLETE** |
| FIN-010…015 — Finance foundation | **COMPLETE** |

Both tasks are complete because the invariants they exist to create were observed holding
against a real database — and, for the grain, observed failing when the constraint is written
the way the design document specifies it.

---

## Current State

**What works.** The finance foundation (FIN-010…015), the registry / sync-worker runtime
boundary (FIN-016), the generic connector contract (FIN-030), connector resolution (FIN-031),
the complete Kollur configuration (FIN-021…024), and now the canonical revenue model
(FIN-051, FIN-052).

Both ends of the architecture now exist and neither knows anything about the other. The
platform can say, for one real temple, what it can and cannot report and which source field
is authoritative; and it has a generic place to put the answer, shaped so that every
catalogued revenue report is answerable without touching a source system. What it cannot do
is move data from one end to the other.

**What does not work yet.** No connector implementation, no staging table, no extraction, no
validation, no mapping stage, no loader, no aggregation, no API. The dashboard is still the
static HTML file. No row of temple financial data has been read, `fin_revenue_fact` is empty
and has no writer, and no credential exists anywhere.

---

## FIN-051 / FIN-052 — Canonical Revenue Model (this session)

### Files

| File | Change |
|---|---|
| `backend/src/main/resources/db/migration/V112__finance_canonical_revenue.sql` | new — 3 tables, 12 seeded category rows |
| `backend/src/main/java/com/templeregistry/entity/finance/FinRevenueCategory.java` | new |
| `backend/src/main/java/com/templeregistry/entity/finance/FinServiceDim.java` | new |
| `backend/src/main/java/com/templeregistry/entity/finance/FinRevenueFact.java` | new |
| `backend/src/main/java/com/templeregistry/entity/finance/enums/PaymentMode.java` | new |
| `backend/src/main/java/com/templeregistry/entity/finance/enums/PaymentModeConfidence.java` | new |
| `backend/src/test/java/com/templeregistry/migration/FinanceCanonicalRevenueMigrationTest.java` | new — 19 tests |

**Migration number:** `V112` — the repository's highest was `V111`. No existing table is
altered. No connector, staging table, service, repository, API or frontend file is touched,
and no dependency was added.

### The canonical grain

```
(temple_id, transaction_date, service_id, category_id, payment_mode, counter_ref, operator_ref)
```

Enforced by `uk_frf_grain`. One row per day per service per category per payment mode per
counter per operator — **not** one row per receipt (ADR-003): 22.3 M receipts on the first
source become ~121 k rows, and no devotee name, address, mobile or email is copied centrally
at all.

**Two corrections to the documented key, both found by building it:**

1. **It did not enforce the grain.** `service_id`, `counter_ref` and `operator_ref` are
   legitimately nullable — a donation-box collection has none of them — and MySQL and TiDB
   treat NULLs in a unique index as distinct. The documented key, written literally, accepts
   the same hundi fact twice. Stored generated columns (`grain_service_key`,
   `grain_counter_key`, `grain_operator_key`) substitute concrete values so the constraint
   bites (FIN-D-018). **Mutation-verified:** with the literal key, the duplicate insert
   succeeds and nothing complains.
2. **`operator_ref` joined the key** (FIN-D-019). R27 reports revenue by counter *and*
   operator from this table, which the six-column key cannot answer — and a connector
   grouping by operator would emit colliding rows, so the loader would lose or overwrite one
   operator's takings. Whatever a connector groups by must be a subset of the key. ADR-003's
   "Consequences" section is superseded on that one point.

### Semantics worth knowing before writing the loader

- **Business date is `transaction_date`, and nothing else.** A source editing a two-year-old
  receipt corrects an old financial day; it does not move money into today. The load axis is
  `created_at` / `updated_at` plus the batch (FIN-D-012).
- **Cancellation has three states.** `cancelled_amount = 150.00` measured and deducted, `0`
  measured and none, `NULL` not recorded by this source. `net_amount` is a stored generated
  column, `gross − cancelled`, and is therefore NULL in the third case (FIN-D-020) — gross
  reported as net would assert that nothing was cancelled.
- **Every measure is nullable** (ADR-007). There is no sentinel zero anywhere in the table.
- **`UNMAPPED` is a seeded category.** FIN-054 routes unmappable source values there, never
  into `OTHER_INCOME`, whose own description forbids that use.
- **Provenance is mandatory.** `source_system_id` and `sync_batch_id` are `NOT NULL`: a
  published figure must always name the run that produced it.

### Tests

`FinanceCanonicalRevenueMigrationTest` — **19**, against a real MySQL 8.0 container with real
Flyway, asserting database behaviour rather than DDL text. Grain rejection including the
all-NULL case; six distinct dimensions coexisting on one date; upsert convergence (one row,
figure replaced, provenance following); two temples holding the identical grain
independently; business date surviving restatement; the three cancellation states; mandatory
and retained provenance; payment-mode confidence; exact decimal round-trips with no floating
point anywhere; and two purity scans over the committed DDL and the live schema.

One test compares every `@Column` on the three entities against `information_schema` — the
defect class behind FIN-X-001, for which this project otherwise has no working check.

### Architectural review

| Question | Answer |
|---|---|
| Is the revenue fact generic across temples? | **YES** |
| Is source schema copied into it? | **NO** |
| Is source transport represented? | **NO** |
| Are credentials represented? | **NO** |
| Is business date separate from sync time? | **YES** |
| Is cancellation distinguishable? | **YES** — and from zero as well as from unknown |
| Is provenance retained? | **YES** — and mandatory |
| Is the canonical grain explicit? | **YES** |
| Is the grain database-enforced? | **YES** — mutation-verified |
| Can two temples coexist? | **YES** |
| Can future connectors write without schema changes? | **YES** |
| Can the dashboard eventually operate without source DB access? | **YES** |

---

## FIN-031 — Connector Registry (previous session)

### Files

| File | Change |
|---|---|
| `backend/src/main/java/com/templeregistry/connector/finance/ConnectorRegistry.java` | new — 100 lines, plain Java, no Spring import |
| `backend/src/main/java/com/templeregistry/connector/finance/ConnectorConfigurationException.java` | new |
| `backend/src/main/java/com/templeregistry/service/finance/sync/SyncWorkerConfig.java` | `connectorRegistry` bean added |
| `backend/src/test/java/com/templeregistry/connector/finance/ConnectorRegistryTest.java` | new — 13 tests |
| `backend/src/test/java/com/templeregistry/service/finance/sync/RegistryRuntimeContextTest.java` | asserts the registry runtime holds no registry and no connector |

No migration, no schema change, no entity, no repository, no API, no frontend, no
dependency added to `pom.xml`.

### What it does, and what it deliberately does not

Resolution is one map lookup on the configured identifier — no branch, no switch, no
knowledge of any temple — so onboarding the tenth source system adds a connector bean and a
configuration row and changes nothing here. The registry creates no connector, resolves no
credential, opens nothing, selects no transport and runs no synchronization.

It is built in `SyncWorkerConfig` from `getBeansOfType(TempleFinanceConnector.class)`, so a
connector reaches the registry by being declared as a worker `@Bean` and by no other route.
Nothing is scanned: a discovery mechanism would have removed the registration FIN-D-008
depends on and let a connector class drift into the registry runtime by nothing more than
being on the classpath.

### The missing-connector behaviour

`resolve` returns a `TempleFinanceConnector` or throws `ConnectorConfigurationException`.
There is no `Optional`, no nullable return, no default connector and no "skip this source"
branch (FIN-D-017). Four distinguishable failures, each naming the connector identifier and
the source system identity and none naming a credential reference or value:

| Condition | Result |
|---|---|
| Configured connector not registered | throws, listing what *is* registered |
| Source system names no connector | throws |
| Connector implements a different `ConnectorType` than the source declares | throws |
| Connector registered under a name it does not declare as its `connectorId` | throws at worker startup |

**Kollur is the live case.** It is completely configured — source system, 19 capabilities,
source of truth, 9 mapping rules — and names `kollurFinanceConnector`, which does not exist.
Resolving it throws today, and the test asserts exactly that, reading the connector name out
of `V111` rather than hardcoding it. No placeholder connector was created to make anything
pass; the absence is the verification.

### Tests

`ConnectorRegistryTest` — **13**, no database and no Docker required. Successful resolution
by identity; missing connector; empty registry; unnamed connector; no API that can express
absence; three connectors across three integration mechanisms resolved generically; type
contradiction; name/metadata contradiction; the Kollur name read from the seed; the assembled
sync worker registering a registry and zero connectors; the registry runtime registering
neither; and a source scan proving the registry holds no source-specific branch, transport,
persistence or credential.

**Mutation-verified.** Replacing the missing-connector throw with `return null` failed 4
tests, including the Kollur one. Restored afterwards.

| Check | Result |
|---|---|
| Missing connector → explicit failure | YES |
| Null / no-op / `Optional` fallback | NO |
| Source-specific code in the registry | NO |
| Credential resolution | NO |
| Transport, filesystem or database access | NO |
| Reachable from the registry runtime | NO |
| Worker-only infrastructure | YES |

---

## Kollur configuration (FIN-021…024, previous session)

### Migration file

`backend/src/main/resources/db/migration/V111__kollur_finance_configuration.sql`

Configuration data only. Creates no table, alters no schema, enables no synchronization,
stores no credential, no host, no port, no URL and no connection string.

**Every statement is conditional on temple 300001 existing** (FIN-D-014). No migration in
this repository creates that temple — `V100` seeds temples with ids `100`… — so an
unconditional seed would have left a source system, 19 capability rows, a source-of-truth
declaration and 9 mapping rules orphaned in every fresh developer and CI database.

> **Operational consequence that must not be forgotten.** A versioned migration runs once.
> In an environment where temple 300001 is created *after* V111 has run, the seed never
> applies and Kollur configuration must be applied through the onboarding path (FIN-140) or
> by re-running the statements manually. V111 is idempotent, so re-running is safe.

---

## Seeded Rows

| Table | Rows |
|---|---:|
| `fin_source_system` | 1 |
| `fin_temple_capability` | 19 |
| `fin_source_of_truth_decl` | 1 |
| `fin_mapping_rule` | 9 |

### Source system (FIN-021)

`temple_id=300001` · `system_code=KOLSOHAM` · `source_temple_code=43` ·
`source_database_name=KOLSOHAM_LOCAL` (documentation only) · `credential_ref=kollur-readonly`
(an **alias**) · `sync_enabled=0` · `sync_schedule_cron=NULL`.

`connector_type` is **`PULL_JDBC` and provisional**, recorded as such in the row's own
`notes` (FIN-D-016). Q4 is unresolved and the column is `NOT NULL`, so a value had to be
written; it reflects how the source was analysed, not a finding that the platform can reach
it. If Q4 resolves to `PUSH_AGENT`, that is one `UPDATE` and nothing else changes — which is
what the FIN-030 contract exists to guarantee.

### Capabilities (FIN-022) — 19, every value of the canonical vocabulary

| Availability | Capabilities |
|---|---|
| `AVAILABLE` (10) | `REVENUE`, `SEVA`, `DONATION`, `PRASADAM_SALE`, `CANCELLATION`, `PRECIOUS_METAL_COUNT`, `PRECIOUS_METAL_WEIGHT`, `IN_KIND_DONATION`, `NIRANTARA_SUBSCRIPTION`, `NIRANTARA_SCHEDULE` |
| `PARTIALLY_AVAILABLE` (2) | `NIRANTARA_PAYMENT`, `PAYMENT_MODE` |
| `NOT_AVAILABLE` (7) | `PRECIOUS_METAL_VALUE`, `NIRANTARA_EXECUTION`, `EXPENSE`, `EXPENSE_CATEGORY`, `GRANT`, `GRANT_UTILISATION`, `WORKS` |

**The brief listed 18; the canonical vocabulary has 19.** The missing one was
`IN_KIND_DONATION`, and Kollur genuinely has it — donated sarees recorded with a donor-stated
value, 182,675 records from FY2016-17. Seeded `AVAILABLE`, with the reason recording that
the value is **donor-declared, not appraised**, and that it **must not be summed with auction
proceeds for the same articles** — the auction realises the value of the identical sarees, so
adding both counts them twice.

Coverage windows: revenue `2019-04-01 → 2026-07-26`; precious metals
`2015-04-01 → 2026-07-25` with FY2021-22 and FY2022-23 recorded as gaps; Nirantara payments
`2017-04-01 → 2024-03-31`; Nirantara schedule `2019-05-01 → **2027**-07-26`. Capabilities
with no reliable coverage — `NIRANTARA_SUBSCRIPTION` and all seven unavailable ones — carry
no dates rather than invented ones.

The reasons are user-facing copy, and the distinctions they preserve are the point:

- **Expenditure** — "the source system does not record expenditure … it is not zero."
- **Payment mode** — "no digital payment was recorded … **not** that the payment was made in
  cash." A receipt where the operator left the card field blank cannot be distinguished from
  a genuine cash sale.
- **Metal value** — weight is measured and reported; a monetary value "cannot be derived
  without an assumed rate, which would be an invention rather than a measurement."
- **Nirantara execution** — the preparation flag "is also set on schedules dated into 2027 —
  so it means that a schedule was generated, **not that a seva took place**."

### Source of truth (FIN-023)

`REVENUE_AMOUNT` v1 → `DailySevaNew.Amount`, effective from `2019-04-01`, `effective_to`
NULL (in force).

Filter: `Deleteflag = 0 AND TempleCode = 43 AND ReceiptDate >= '2015-01-01' AND
BillCancled = 0` for recognised revenue. The declaration also records that extraction
*retains* cancelled rows so cancelled counts and amounts can be reported separately — net is
gross minus cancelled. That resolves an apparent contradiction between ADR-008 and the
extraction sketch in `FINANCE_DATA_INTEGRATION.md`: they operate at different levels, and
the declaration now says so explicitly rather than leaving it latent.

All three measured alternatives are recorded with their evidence:

| Rejected | Measured | Why |
|---|---:|---|
| `DailySevaNewOld` | 16,982,270 rows | Byte-exact duplicate of all six archives; including it doubles every historical year |
| `DailySevaNewDetails.TotalAmount` | ₹53,77,53,226 | 41 % below the header total, with 1:1 row correspondence and zero orphans — internal inconsistency, not missing records |
| `DailySevaNewDetails.Amount × Qty` | ₹56,31,07,228 | Disagrees with the header *and* with the detail table's own column |

The rationale also carries the two correctness rules that are easiest to get wrong later:
the **rate-card price is a list price and is never revenue**, and **receipt counts are
receipts, not devotees**.

### Mapping rules (FIN-024) — 9

`SANNIDHI:DS → SEVA` · `SANNIDHI:SS → SPECIAL_SEVA` · `SANNIDHI:KN → DONATION` ·
`SANNIDHI:PS → PRASADAM_SALE` · `SEVA_CODE:430 → HUNDI_DONATION` ·
`STREAM:SAREE_DONATION → IN_KIND_DONATION` · `STREAM:SAREE_AUCTION → ASSET_REALISATION` ·
`METAL_TYPE 2 → GOLD` · `METAL_TYPE 1 → SILVER`

Source values are namespaced and **the more specific rule wins** (FIN-D-015). Without the
`SEVA_CODE:430` override, donation-box collections — 13 records averaging over a crore each —
would be reported as ordinary donations and would dominate any ranking of services devotees
purchased.

No `PAYMENT_MODE` rules are seeded: for this source, payment mode is inferred from the
*absence* of card details, which is connector logic rather than a value mapping.

No canonical value was invented; all nine come from the taxonomy already in
`FINANCE_DATA_MODEL.md`, and a test enforces that.

---

## Tests

**Added:** `backend/src/test/java/com/templeregistry/migration/KollurFinanceConfigurationMigrationTest.java`
— 27 tests.

It drives Flyway directly against a MySQL 8.0 Testcontainer and reads the seeded rows back
over JDBC, deliberately **not** loading the Spring context: every full-context test in this
project is red for two pre-existing reasons unrelated to finance (FIN-X-001, FIN-X-002), and
configuration that could only be verified after someone else fixed those would not be
verified at all. The side benefit is that it exercises the migration exactly as it will run
in production.

| Command | Result |
|---|---|
| `mvn -o test -Dtest=FinanceCanonicalRevenueMigrationTest` | **19/19 pass** (FIN-051/052) |
| `mvn -o test -Dtest=ConnectorRegistryTest` | **13/13 pass** (FIN-031, no Docker needed) |
| `mvn -o test -Dtest=KollurFinanceConfigurationMigrationTest` | **27/27 pass** |
| `mvn -o test -Dtest='*Finance*,*SyncWorker*,*RegistryRuntime*,*SourceCredential*,*Connector*,*Kollur*'` | **135/135 pass** |
| `mvn -o test` (full suite) | **990 run · 0 failures · 18 errors** |

What is asserted, beyond row counts: the conditional guard (zero rows *before* the temple is
created), `sync_enabled = 0`, that **every persisted value** in `fin_source_system` contains
no endpoint or credential pattern, that the table has no credential column at all, that each
of the 19 reasons is substantive rather than filler, that unavailable capabilities are not
expressed as zero, the coverage windows and gaps, all three rejected alternatives with their
measured figures, that no mapping value contains SQL or a source table name, and idempotency
by re-applying the whole seed.

---

## Failures

**New: none.** Pre-existing: **18**, unchanged in count, cause and location across the entire
branch (866 → 888 → 898 → 931 → 958 → 971 → 990 tests, always the same 18 errors in the
same 4 report files).

- **FIN-X-001** — `Schema-validation: missing column [field_names_json] in table
  [declaration_clarifications]`. Mapped by the entity, created by no migration, present in
  running environments only because `ddl-auto: update` adds it. Architecture risk **R7**,
  observed. Fix is one additive `ALTER`, owned by the declaration module.
- **FIN-X-002** — the `test` profile cannot boot a full context: the test JWT public key is a
  placeholder that fails to parse, and `application.yml` carries a TiDB-only
  `connection-init-sql` that H2 rejects.

---

## Architectural Decisions

- **FIN-D-014** — the seed is conditional on temple 300001 existing. Rejected seeding
  unconditionally (orphan configuration in every fresh database) and creating the temple in
  the migration (the finance platform does not own temple records).
- **FIN-D-015** — mapping source values are namespaced and the more specific rule wins.
  Rejected a rule per service code (164 rows the source already classifies for us) and a new
  `MappingType` value (vocabulary change to solve a naming problem).
- **FIN-D-016** — `connector_type` seeded provisionally rather than made nullable. Rejected
  weakening the column for every temple to express uncertainty about one, and rejected an
  `UNDECIDED` enum value that would add permanent vocabulary for a temporary state.
- **FIN-D-017** — a configured connector that is not registered is a failure, not an absence.
  Rejected `Optional<TempleFinanceConnector> find(...)` (it reads as the safer API and is the
  opposite: it moves the decision to every caller and the failure it invites is silent), a
  no-op connector for unregistered names, and classpath discovery of connector
  implementations, which would have removed the `@Bean` registration FIN-D-008 depends on.
- **FIN-D-018** — the grain is enforced through generated key columns, because NULL is not
  equal to NULL. Rejected making the three columns `NOT NULL` with sentinels (it destroys the
  distinction between "no service was involved" and "service 0") and enforcing uniqueness in
  the loader (an invariant that holds only while the code is correct is not an invariant).
- **FIN-D-019** — `operator_ref` belongs in the grain, extending ADR-003's six-column key.
  Rejected serving R27 from a separate aggregate, which defers the same decision and leaves an
  operator column in the fact that somebody will eventually populate without knowing it is
  outside the key.
- **FIN-D-020** — `net_amount` is computed by the database and is NULL when cancellations are
  unknown. Rejected computing it in the loader (every future stage would have to reproduce the
  same arithmetic) and defaulting net to gross (which asserts that nothing was cancelled).

---

## Known Limitations

1. **The seed is a no-op where temple 300001 does not yet exist** (FIN-D-014). This is
   correct behaviour, but it means the live database is the only place the configuration
   actually lands today, and only if the temple predates the migration. Onboarding
   (FIN-140) is the durable answer.
2. **`connector_type` is provisional** pending Q4.
3. **`connector_bean = kollurFinanceConnector` names a bean that does not exist.** Since
   FIN-031 this is a *detected* condition rather than a latent one: resolving it throws,
   naming the connector and the source system. Nothing resolves it yet in production because
   nothing synchronizes, so the failure will first be seen by whoever wires the sync
   orchestration — which is the intended moment.
4. **No source-of-truth declaration for `PRECIOUS_METAL_WEIGHT`.** Deliberately out of FIN-023
   scope, but it is the natural defence against the discarded "assume 15 g and ₹12,000 per
   item" approach returning. One row whenever wanted.
5. **Capability rows carry `last_reviewed_at` set at migration time and no reviewer.** The
   reasons are user-facing copy about a government temple's finances and would benefit from a
   named business sign-off before the dashboard renders them.
6. **Nothing calls the registry yet** (FIN-031). It resolves correctly and fails correctly,
   but until a sync orchestrator exists the failure path runs only under test. Whoever builds
   that orchestrator must let the exception fail the batch — recording it against
   `fin_sync_batch` / `fin_sync_error` — and must not catch it into a "skipped" outcome, which
   would restore precisely the silence FIN-D-017 exists to prevent.
7. **`fin_revenue_fact` has no writer.** The constraint that makes loading idempotent is
   proven, but the loader that relies on it is FIN-056. Until then the canonical tables are
   empty by design, and the upsert shape the test demonstrates
   (`INSERT … ON DUPLICATE KEY UPDATE`) is the intended write path, not a guess.
8. **`financial_year` is stored, not derived by the database.** It is functionally dependent
   on `transaction_date`, so a loader that computes it wrongly — or with the wrong financial
   year start — will produce facts that disagree with their own dates. FIN-055 owns that
   computation and should have a test that a date in early April lands in the new year.
9. **The generated grain columns are verified on MySQL 8.0, not on TiDB.** TiDB supports
   stored generated columns and indexes over them, and no other syntax in V112 is unusual,
   but the deployment target has not run this migration. Worth confirming on the first
   deployment rather than assuming.
10. **No `fin_cancellation` detail table yet.** The fact carries `cancelled_count` and
   `cancelled_amount`, which satisfies every catalogued cancellation report;
   `FINANCE_DATA_MODEL.md` §6.2 also specifies a full-detail table (465 rows across the first
   temple's entire history) that no task in the plan currently owns.
11. **The registry is built once at worker startup.** A connector bean added at runtime would
   not appear, which is correct for an artifact whose connectors are compiled in, and worth
   knowing before anyone attempts dynamic connector loading.

---

## Q4 Status

**UNRESOLVED, and not resolved by assumption.** The seed stores `PULL_JDBC` only because the
column is `NOT NULL`, and says so in its own notes. Nothing in the configuration presumes the
platform can reach Kollur: there is no endpoint, no host, no URL, and synchronization is
disabled. `ConnectorType` still treats all four mechanisms as equals, and `SourceCredentials`
carries an *optional* principal so a push agent's shared key fits the same shape as a
database user.

If the answer is `PUSH_AGENT`: one `UPDATE` to `connector_type`, and a different connector
implementation. The canonical model, aggregation, APIs and dashboard are untouched.

## Q5 Status

**UNRESOLVED, and no credential was introduced.** `credential_ref = 'kollur-readonly'` is an
alias. No credential exists in Git, YAML, SQL, the migration, the tests, or
`fin_source_system` — and a test scans **every persisted value** in that table for endpoint
and credential patterns, so a future edit that smuggles one into a notes field fails the
build.

Choosing the permanent store still changes one `@Bean` method in `SyncWorkerConfig`.

---

## How To Continue

1. Read this file, then `IMPLEMENTATION_STATUS.md` and `IMPLEMENTATION_TASKS.md`.
2. `git status` and `git log --oneline -10` on `feature/db-integration`.
3. Confirm the baseline:
   `cd backend && mvn -o test -Dtest='*Finance*,*SyncWorker*,*RegistryRuntime*,*SourceCredential*,*Connector*,*Kollur*'`
   — expect **135 passing, 0 failures**. (Requires Docker for the migration test.)
4. Implement one task. Do not implement a connector, and do not implement Kollur-specific
   anything outside a connector.
5. Anything that can reach a source system is registered in `SyncWorkerConfig` as a `@Bean`,
   never as a `@Component` — `FinanceIntegrationBoundaryTest` fails the build otherwise.
6. Run the suite, update the four tracking documents, commit as `FIN-0xx ...`.

Do not repeat the architectural analysis. It is complete and in `docs/finance/`.

---

## NEXT ACTION

Implement **FIN-050**: `fin_stg_revenue`, the immutable staging table and its entity.

It is the other end of the same pipeline and needs neither Q4 nor a connector. The task list
records it as depending on FIN-043 (revenue extraction), but that dependency was written when
staging was imagined alongside a working extract; the table itself depends on nothing but the
foundation. Once both ends exist, the stages between them (FIN-053 validation, FIN-054
mapping, FIN-055 normalization, FIN-056 load) can be built and tested end to end with
fabricated staging rows, which is the only way any of it can be tested until the network
question is answered.

Shape it per `FINANCE_DATA_MODEL.md` §4: `raw_json` rather than typed columns, so a source
schema change lands in staging and is caught by validation instead of breaking extraction,
with `validation_status` and `rejection_reason` so a rejected row is visible rather than
missing. Rejections belong in `fin_sync_error` (FIN-053), and a batch that rejects rows must
not be able to look like a batch that loaded them.

Then **FIN-056** is the piece that matters most for correctness: the loader writing through
`uk_frf_grain` with `INSERT … ON DUPLICATE KEY UPDATE`, replacing a restated day rather than
adding to it. The shape is demonstrated in `should_convergeOnOneRow_when_upsertRepeated`.

Alternative if a smaller task is wanted: **FIN-032** — probe and capability declaration
wiring into onboarding. It can only be exercised against fake connectors until FIN-040.
