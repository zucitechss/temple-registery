# Finance Platform — Handoff

**Updated:** 2026-09-16
**Branch:** `feature/db-integration`
**Read first**, then [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md),
[IMPLEMENTATION_TASKS.md](IMPLEMENTATION_TASKS.md),
[IMPLEMENTATION_DECISIONS.md](IMPLEMENTATION_DECISIONS.md).

---

## FIN-030 Status

**COMPLETE.** Contract and supporting types implemented, compiled, and verified by 33
passing tests. No implementation, no transport, no credential, no schema change.

---

## Current State

**What works.**

*Foundation (FIN-010 … FIN-015).* Seven tables, seven entities, seven repositories, eleven
enums. The schema records which external system feeds which temple, what each temple can
and cannot answer, which source field is authoritative, how source values map to canonical
ones, and the audit trail of every sync attempt and reconciliation.

*Runtime boundary (FIN-016).* Registry and sync worker are two runtimes from one artifact.
The registry runtime contains no bean that can resolve a temple credential or reach a source
system — asserted against the fully assembled application. The worker runs non-web and
refuses to start if it ever serves HTTP.

*Connector contract (FIN-030).* `TempleFinanceConnector` plus ten supporting types define
what a temple finance source must be able to do, without saying how it is reached.

**What does not work yet.** Nothing is connected to anything. There is no connector
implementation, no extraction, no canonical fact table, no API, and the dashboard is still
the static HTML file. No row of temple financial data has been read, and no credential exists
anywhere.

---

## Last Completed Task

**FIN-030 — the `TempleFinanceConnector` contract.**

The shape worth knowing:

```java
ConnectorMetadata            metadata();
Set<FinanceCapability>       describeCapabilities(SourceSystemDescriptor source);
SourceProbeResult            probe(SourceSystemDescriptor source);
Optional<SchemaFingerprint>  fingerprintSchema(SourceSystemDescriptor source);
Stream<RawRow>               extract(FinanceCapability capability, SyncContext context);
SourceTotals                 sourceTotals(FinanceCapability capability,
                                          SourceSystemDescriptor source,
                                          DateRange period);
```

Four properties a later change must not quietly remove:

1. **No transport anywhere.** No `getConnection()`, no `getClient()`, no stream or file
   handle. `probe()` is named for the question — "is this source usable" — because a push or
   file-drop source has no connection to test.
2. **No credential.** The connector gets an alias and resolves it inside the worker.
3. **No watermark return.** The framework fixes `SyncContext.changedUpTo()` before calling
   and stores it only on success (FIN-D-012). A connector has no way to advance one.
4. **No self-reconciliation.** `sourceTotals()` reports; the reconciliation layer judges.

---

## Currently In Progress

Nothing is half-built. Every committed file compiles and is covered by a passing test.

---

## Files Created / Modified

**Created — contract** (`backend/src/main/java/com/templeregistry/connector/finance/`)

`TempleFinanceConnector.java` · `ConnectorMetadata.java` · `SourceSystemDescriptor.java` ·
`SyncContext.java` · `DateRange.java` · `RawRow.java` · `SourceTotals.java` ·
`ReconMetric.java` · `SchemaFingerprint.java` · `SourceProbeResult.java` ·
`UnsupportedCapabilityException.java`

**Created — tests** (`backend/src/test/java/com/templeregistry/connector/finance/`)

`TempleFinanceConnectorContractTest.java` (25) · `ConnectorContractPurityTest.java` (8)

**Modified.** None. FIN-030 touched no existing file.

---

## Tests Added

| Class | Count | Proves |
|---|---:|---|
| `TempleFinanceConnectorContractTest` | 25 | Identity, capability declaration, sync context, source totals, raw rows, schema fingerprint — via a stub connector built from in-memory data alone |
| `ConnectorContractPurityTest` | 8 | No source-specific knowledge, no credential, no JPA, no transport, no reconciliation verdict |

The stub connector is the substantive evidence: a complete implementation of the contract
with **no database, no HTTP client, no file handle and no credential**. If the contract
assumed a transport, that class could not exist.

---

## Tests Executed

| Command | Result |
|---|---|
| `mvn -o compile -DskipTests` | BUILD SUCCESS |
| `mvn -o test -Dtest=TempleFinanceConnectorContractTest` | 25/25 pass |
| `mvn -o test -Dtest=ConnectorContractPurityTest` | 8/8 pass |
| `mvn -o test -Dtest='*Finance*,*SyncWorker*,*RegistryRuntime*,*SourceCredential*,*Connector*'` | **76/76 pass** |
| `mvn -o test` (full suite) | **931 run · 0 failures · 18 errors** |

**Mutation check.** A probe interface importing `java.sql.ResultSet`, declaring a `password`
parameter and mentioning the first temple by name was added to the contract package; **3**
purity tests failed with the intended messages; the probe was removed and all 8 passed again.
A guard that cannot fail is not a guard.

---

## Any Failures

**New failures: none.**

**Pre-existing: 18**, unchanged in count, cause and location across the whole branch
(866 → 888 → 898 → 931 tests, always the same 18 errors in the same 4 report files).

- **FIN-X-001** — `Schema-validation: missing column [field_names_json] in table
  [declaration_clarifications]`. Mapped by `DeclarationClarification`; created by no
  migration; present in running environments only because `ddl-auto: update` adds it.
  Affects `ApplicationContextIntegrationTest` (1) and `TrustIntegrationTest` (17). Confirmed
  pre-existing by stashing all finance code and reproducing identically. Architecture risk
  **R7**, observed. Fix is one additive `ALTER`, owned by the declaration module.
- **FIN-X-002** — the `test` profile cannot boot a full context: `jwt-test.pub` is a
  placeholder that fails to parse, and `application.yml` carries a TiDB-only
  `connection-init-sql` H2 rejects. Finance tests override both locally.

---

## Architectural Decisions

**FIN-D-011 — reuse the shared finance enums rather than duplicate them.** The contract
imports `FinanceCapability`, `ConnectorType`, `SourceTechnology` and `SyncType` from
`entity.finance.enums`. Duplicating them would create two vocabularies for one concept, and
the first drift would mean a capability a connector declared no longer matched the capability
stored against the temple. The package is named `entity`, but these are plain Java enums with
**zero imports and zero annotations** — verified, not assumed, and a test now fails the build
if any of them ever imports `jakarta.*`, `org.springframework.*` or `org.hibernate.*`.

**FIN-D-012 — the framework owns the watermark; a connector cannot propose one.** Extends
FIN-D-005 into the contract shape. Also separates two axes that are easy to conflate:
`changedSince`/`changedUpTo` is the change axis, `businessDateRange` is the business-date
axis. Filtering reconciliation by modification time would silently exclude older untouched
records, and the total would look correct because both sides compared the same subset.

**FIN-D-013 — extraction returns raw string values, streamed.** Strings because staging
exists so a malformed value *lands and is rejected with a reason* rather than aborting a
batch — the source is known to contain impossible dates. Money is unaffected: a decimal
rendered as text and parsed back is exact. Streamed because a first historical load runs to
tens of millions of records.

---

## Remaining Risks

| Risk | State |
|---|---|
| A connector implementation leaks source vocabulary upward | Contract is clean and guarded, but `ConnectorContractPurityTest` scans only the contract package. When FIN-040 lands, the equivalent guard must cover *staging output*, not just the connector — the leak would come through field names in `RawRow`, which are legitimately source-specific |
| `extract` and `sourceTotals` collapse into one shared query | Mitigated by different signatures and axes, and documented as binding. **Not structurally enforceable** — a connector implementation can still call one from the other. FIN-044 must be reviewed specifically for this |
| **R12** — the profile split is collapsed | Mitigated by two runtime tests and the classpath guard |
| **R9** — silent source schema change | Contract supports it via `fingerprintSchema`; nothing compares fingerprints yet — that is FIN-042 |
| **R7** — `ddl-auto: update` masking missing migrations | Unchanged; observed twice |
| Stream leak from `extract` | Contract documents that callers must close it. No implementation exists yet to leak one; the pipeline code in FIN-05x must use try-with-resources |

---

## Q4 Status

**UNRESOLVED, and still not assumed.** FIN-030 strengthened this rather than deferring it:
the contract names no transport at all, so `PULL_JDBC`, `PUSH_AGENT`, `SOURCE_API` and
`FILE_DROP` are equally implementable — proven by a test parameterized over every
`ConnectorType` value.

If inbound access to Kollur is refused, the change is a `connector_type` value and a
different connector implementation. The canonical model, aggregation, Finance APIs and
dashboard are untouched.

One point for FIN-040 that Q4 will settle: the contract deliberately carries **no endpoint**
— no host, URL or file path. A `PULL_JDBC` connector will need one, and it must come from
worker configuration alongside the credential, not from `fin_source_system`. That keeps
FIN-D-002 intact and is worth deciding explicitly rather than by accident.

## Q5 Status

**UNRESOLVED, abstraction unchanged.** No temple credential exists in the repository, the
database, or any configuration file. FIN-030 added no credential surface: the contract
carries only `SourceSystemDescriptor.credentialRef()`, an alias, and a purity test fails the
build if `SourceCredentials` or `SourceCredentialProvider` is ever referenced from the
contract package.

Choosing the permanent store still changes one `@Bean` method in `SyncWorkerConfig`.

---

## How To Continue

1. Read this file, then `IMPLEMENTATION_STATUS.md` and `IMPLEMENTATION_TASKS.md`.
2. `git status` and `git log --oneline -8` on `feature/db-integration`.
3. Confirm the baseline:
   `cd backend && mvn -o test -Dtest='*Finance*,*SyncWorker*,*RegistryRuntime*,*SourceCredential*,*Connector*'`
   — expect **76 passing, 0 failures**.
4. Implement FIN-021 … FIN-024 only. Do not start a connector implementation.
5. Anything new that can reach a source system goes in `SyncWorkerConfig` as a `@Bean`, never
   as a `@Component`.
6. Run the suite, update the four tracking documents, commit as `FIN-021 ...`.

Do not repeat the architectural analysis. It is complete and in `docs/finance/`.

---

## NEXT ACTION

Implement **FIN-021 … FIN-024**: a seed migration registering the Kollur source system
(`temple_id=300001`, `system_code=KOLSOHAM`, `source_temple_code=43`,
`connector_bean=kollurFinanceConnector`, `sync_enabled=0`, `credential_ref` alias only),
its 19 capability rows with user-facing reasons, its `REVENUE_AMOUNT` source-of-truth
declaration including the three measured rejected alternatives, and its mapping rules.

Configuration data only — no connector, no credential, no network access. The capability
reasons are user-facing copy that the dashboard renders in place of a number, so write them
to be read by a Deputy Commissioner, not by a developer.
