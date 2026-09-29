# Q4 — Network Path to the Kollur/SOHAM SQL Server

**Status: UNRESOLVED.**

This document converts the long-standing Q4 dependency (first opened in
[`MULTI_TEMPLE_FINANCE_ARCHITECTURE.md`](MULTI_TEMPLE_FINANCE_ARCHITECTURE.md) §42) into a
concrete checklist and acceptance criteria for the infrastructure team. **It is investigation
and documentation only** — no connector, driver, credential, or network configuration is
added by this document.

---

## 1. Required Decision

> Can the isolated `sync-worker` process establish a connection to the Kollur/SOHAM SQL
> Server database, and if so, by what mechanism?

That single question decides `fin_source_system.connector_type` for Kollur (currently
`PULL_JDBC`, stored only because the column is `NOT NULL` — see
[`HANDOFF.md` §"Seeded Rows"](HANDOFF.md)) and therefore which of the four already-modelled
mechanisms (`ConnectorType`: `PULL_JDBC`, `PUSH_AGENT`, `SOURCE_API`, `FILE_DROP`) FIN-041
is actually built against.

This decision is **entirely infrastructure's to make**, not engineering's. The codebase
already treats all four mechanisms as equally valid (`ConnectorType`, `SourceCredentials`),
specifically so that this decision costs no rework whichever way it goes.

---

## 2. The Mandatory Boundary (unchanged, not renegotiated by this task)

```text
Temple Registry (registry runtime)
        X
        |  MUST NOT directly connect
        v
Kollur / SOHAM SQL Server
```

```text
Temple Registry
      |
      | shared registry database only
      v
sync-worker  (separate process, separate network zone, separate credentials — ADR-001)
      |
      | source connector  <-- Q4 decides what this arrow is
      v
Kollur / SOHAM SQL Server
```

Only the isolated `sync-worker` may ever reach a temple operational database (ADR-001,
ADR-010). This document does not propose, and nothing below implies, any exception to that
boundary.

---

## 3. What the Repository Actually Proves Today (Known)

### 3.1 Source database — well understood, structurally

- **Product:** Microsoft SQL Server 2025 (RTM) 17.0.1000.7, Standard Developer Edition
  ([`KOLSOHAM_DATABASE_ANALYSIS.md`](../database/KOLSOHAM_DATABASE_ANALYSIS.md)).
- **Database name:** `KOLSOHAM_LOCAL`. **Temple code:** 43.
- **Schema:** 167 user tables, 170 views, 129 stored procedures, 1 table-valued function,
  45 primary keys, **zero foreign keys**, **zero unique constraints**, only 3 non-PK
  indexes, compatibility level 100 (SQL Server 2008).
- **Volume:** ~22.3M transaction rows; data file 14,993 MB, log file 10,668 MB.
- **Financial content:** analysed in full in
  [`KOLLUR_FINANCE_DATA_ANALYSIS.md`](KOLLUR_FINANCE_DATA_ANALYSIS.md) (revenue strong,
  expenditure absent).

**Tables a future connector would need to read** (documented, not yet implemented — included
here only so infra can size the read load, which architecture risk R4 flags as a real
operational concern for the temple):

| Table | Role | Scale |
|---|---|---|
| `DailySevaNew` | Authoritative revenue — `Amount` is the declared source of truth (FIN-023) | 5.37M live rows + 17.0M archived |
| `DailySevaNewOld` | **Must be excluded** — byte-exact duplicate of all six archives; including it doubles every historical year | 16,982,270 rows |
| `DailySevaNewDetails` | **Rejected** as a revenue source — 41% below the header total | — |
| `HKanikeItems` | Precious metals; `Qty` is real per-item weight in grams | 1,895 gold + 316 silver items |
| `FN_TRANSACTION` | General ledger — holds almost no expenditure | 524,296 rows |

Declared extraction filter (FIN-023):
`Deleteflag = 0 AND TempleCode = 43 AND ReceiptDate >= '2015-01-01' AND BillCancled = 0`.

**Source-specific connector assumptions:** `fin_source_system.connector_bean` names
`kollurFinanceConnector`, **a bean that does not exist** (HANDOFF limitation 3) — resolving it
throws today. Per [`IMPLEMENTATION_OWNERSHIP.md`](IMPLEMENTATION_OWNERSHIP.md), that class,
once written, is the *only* one permitted to name `KOLSOHAM_LOCAL`, `DailySevaNew`,
`HKanikeItems` or `TempleCode 43`. None of this is blocked by Q4 in itself — but none of it can
be tested against the real source until Q4 is answered.

### 3.2 How that analysis was actually performed — and why it proves nothing about Q4

This is the fact this document exists to surface clearly, because it is easy to
misread the analysis above as partial progress on Q4. It is not.

> `KOLSOHAM_DATABASE_ANALYSIS.md`'s own header: *"Source database: `KOLSOHAM_LOCAL` (SQL
> Server 2025, **restored from the temple's `.bak`**)"*, verified via:
> ```
> sqlcmd -S localhost -d KOLSOHAM_LOCAL -E -C -Q "SELECT DB_NAME(), SUSER_SNAME();"
> -> KOLSOHAM_LOCAL / ZTSS\MiliSrivastava
> ```
> Server identified as `ZTLW-18` (default instance) — **a local workstation**, connected to
> over `localhost`, with Windows Authentication, by a named engineering account. The
> database's own `create_date` is the date of the analysis itself, "confirming this is a
> freshly restored copy of a production database, not production itself."

Consequences for Q4:

- **No live network connection to Kollur's actual production SQL Server has ever been
  established or attempted anywhere in this repository's history.** The entire structural
  and financial analysis was performed against an offline copy, on a local machine, with no
  network hop to the temple involved at all.
- **How the `.bak` file itself reached that local machine is not documented anywhere in the
  repository** (no email thread, transfer log, USB/courier note, or SFTP record is
  referenced). This is itself an open question, not an assumption this document resolves —
  and it is worth noting explicitly that transferring a backup file requires no live network
  path to production at all, so its existence is not evidence one exists.
- Nothing about server name, port, authentication mode, or firewall posture of the **real,
  live, production** Kollur database can be inferred from a local restore's connection
  string (`localhost`, trusted Windows Auth) — that string describes the analyst's own
  machine, nothing about the temple's network.

### 3.3 Network — nothing is known

No document in this repository, and no field in `fin_source_system` or
`SyncWorkerProperties`, records any of: the sync-worker's own deployment location, the
production Kollur server's hostname/IP, a port, a VPN, a firewall rule, an allowlist, DNS,
a proxy, a bastion host, or a TLS requirement. This is by design at the schema level —
`fin_source_system` deliberately holds no host, URL, or connection field (mirrors
`SyncWorkerProperties`'s own javadoc: *"Deliberately contains no host, URL, username,
password or connection string"*) — but it means literally none of this exists to be read
back from the codebase. It has to come from infrastructure.

Compounding this: **Q10 (sync-worker deployment target) is also still unresolved**
([`MULTI_TEMPLE_FINANCE_ARCHITECTURE.md`](MULTI_TEMPLE_FINANCE_ARCHITECTURE.md) §42). Q4
cannot be fully answered independently of Q10 — a network path is a statement about *two*
endpoints, and today only one of them (the temple's database) has even preliminary
structural detail, from an offline copy, not the other (where the worker itself will run).

Also relevant context, already documented elsewhere and not re-litigated here: the
platform's own object storage previously used AWS and that support was removed in favour of
the local filesystem, and there is no secrets manager in the current deployment
([`MULTI_TEMPLE_FINANCE_ARCHITECTURE.md`](MULTI_TEMPLE_FINANCE_ARCHITECTURE.md) §"Two
existing issues"). No conclusion about Q4 is drawn from this — it is cited only so the
infra team knows there is currently no assumed cloud target either.

---

## 4. Q4 Dependency Checklist — for Infrastructure

Every line below is currently **UNKNOWN / REQUIRES INFRA CONFIRMATION** unless marked
otherwise from §3.

### Connectivity

| Item | Status |
|---|---|
| Worker runtime location (which network/host/cloud the sync-worker process will run in) | UNKNOWN / REQUIRES INFRA CONFIRMATION (this is Q10) |
| Kollur SQL Server's real network location (on-prem at the temple? hosted?) | UNKNOWN / REQUIRES INFRA CONFIRMATION |
| Whether the two sit on the same network or different networks | UNKNOWN / REQUIRES INFRA CONFIRMATION |
| Whether the worker runs containerised, and if so what its container/Docker network egress looks like | UNKNOWN / REQUIRES INFRA CONFIRMATION |
| Whether a private endpoint / private link is required or available | UNKNOWN / REQUIRES INFRA CONFIRMATION |
| Source hostname/IP (production, not the local restore's `localhost`) | UNKNOWN / REQUIRES INFRA CONFIRMATION |
| SQL Server TCP port reachable from the worker | UNKNOWN / REQUIRES INFRA CONFIRMATION |
| DNS resolution requirements | UNKNOWN / REQUIRES INFRA CONFIRMATION |
| Outbound firewall rules required from the worker's network | UNKNOWN / REQUIRES INFRA CONFIRMATION |
| Inbound firewall rules required on the SQL Server side | UNKNOWN / REQUIRES INFRA CONFIRMATION |
| IP allowlisting requirements (either direction) | UNKNOWN / REQUIRES INFRA CONFIRMATION |
| VPN / private-network requirement | UNKNOWN / REQUIRES INFRA CONFIRMATION |
| Routing requirements between the two zones | UNKNOWN / REQUIRES INFRA CONFIRMATION |
| Proxy / bastion / jump-host requirement | UNKNOWN / REQUIRES INFRA CONFIRMATION |

### SQL Server

| Item | Status |
|---|---|
| SQL Server product version | **KNOWN** — 2025 (RTM) 17.0.1000.7, Standard Developer Edition, on the restored copy (§3.1). Production version not independently confirmed. |
| Database name | **KNOWN** on the restored copy — `KOLSOHAM_LOCAL` (§3.1). Whether production uses the same name is not confirmed. |
| Named-instance requirements | UNKNOWN / REQUIRES INFRA CONFIRMATION |
| TCP/IP protocol enabled on the production instance | UNKNOWN / REQUIRES INFRA CONFIRMATION |
| Encryption / TLS requirements for the connection | UNKNOWN / REQUIRES INFRA CONFIRMATION |
| Certificate requirements (CA trust, self-signed handling) | UNKNOWN / REQUIRES INFRA CONFIRMATION |
| Read-only access enforceable at the SQL Server side | UNKNOWN / REQUIRES INFRA CONFIRMATION |

### Security

| Item | Status |
|---|---|
| Dedicated read-only source user provisioned for the worker | UNKNOWN / REQUIRES INFRA CONFIRMATION |
| Authentication mechanism (SQL auth vs. Windows/Kerberos — the local restore used trusted Windows Auth, which will not be available across a real network boundary) | UNKNOWN / REQUIRES INFRA CONFIRMATION |
| Credential ownership (who provisions/owns the read-only account) | UNKNOWN / REQUIRES INFRA CONFIRMATION — and explicitly **out of scope for Q4**; see §5 |
| Credential storage/retrieval mechanism | **Q5, not Q4** — see §5. Not decided here. |
| Rotation requirements | **Q5, not Q4** — see §5. Not decided here. |

---

## 5. Q4 vs. Q5 — kept separate on purpose

| Decision | Meaning | Current Status | Owner/Dependency |
|---|---|---|---|
| **Q4** | Network path from `sync-worker` to the Kollur SQL Server | **UNRESOLVED** | Infrastructure |
| **Q5** | Permanent credential storage/retrieval mechanism | **UNRESOLVED** | Security / Infrastructure / Architecture |

**The existence of a network route does not mean credentials are solved.** Even once
Q4 confirms a reachable host and port, `SourceCredentialProvider` still needs a real,
production-grade backing implementation — today it has an abstraction and an
environment-variable-backed implementation only, with no secrets manager in the
deployment ([`HANDOFF.md`](HANDOFF.md) Q5 Status, unchanged by this document).

**The existence of credentials does not mean the worker can reach SQL Server.** A
correctly provisioned read-only login is useless if no packet from the worker's network
zone can reach the SQL Server's port at all.

This document resolves neither. It resolves only the shape of what Q4 needs answered.

---

## 6. Minimum Connectivity Test (to run *after* Infra answers §4)

The smallest safe technical test, once infrastructure provides a confirmed endpoint,
answers exactly one question:

> Can the isolated `sync-worker` runtime establish a TCP/JDBC connection to the intended
> Kollur SQL Server endpoint?

**The test must:**

- Run from within the actual `sync-worker` deployment/network zone (not a developer laptop,
  not the registry runtime) — reachability from anywhere else answers a different question.
- Attempt only a TCP connection and, at most, a trivial authentication handshake
  (equivalent to `SELECT 1` or `SELECT DB_NAME()`), comparable in scope to the read-only
  probe already performed against the local restored copy in
  [`KOLSOHAM_DATABASE_ANALYSIS.md`](../database/KOLSOHAM_DATABASE_ANALYSIS.md) — but against
  the real endpoint, over the real network path, not `localhost`.
- Use a temporary, dedicated test login provisioned specifically for the probe, not a
  production application credential.

**The test must NOT:**

- Extract, read, or aggregate any business/financial data.
- Execute any query against a business table (revenue, seva, or otherwise).
- Modify any source data.
- Require the Temple Registry (web) runtime to connect — the boundary in §2 still applies
  to a test.
- Store any credential in Git.
- Log a password or a connection string containing a secret.
- Bypass the `sync-worker` isolation boundary in any way (e.g. by testing from the
  monolith "just this once").

**This is not the Kollur connector.** FIN-041 (`KollurFinanceConnector`) is a full,
tested, production extraction implementation with schema mapping, staging, and error
handling. This test is a reachability probe only, deliberately smaller in scope, and does
not implement or replace any part of FIN-040/FIN-041.

---

## 7. Acceptance Criteria for Q4

Q4 is not resolved until the repository (or an infrastructure sign-off document referenced
from it) contains recorded, evidenced answers to all twelve of the following — not
assumptions, not defaults:

1. Where does `sync-worker` run? *(also answers Q10)*
2. Where does the Kollur SQL Server run?
3. How does traffic travel between them (direct route, VPN, tunnel, agent)?
4. What hostname/IP does the worker use to reach it?
5. What TCP port is reachable?
6. What firewall/allowlist changes are required, on which side?
7. Is VPN or private routing required?
8. Is DNS resolution required, and by whom is it provided?
9. Is TLS/encryption required for the connection?
10. Has the worker runtime actually established a TCP connection to the real endpoint (not
    the local restored copy)?
11. Has a controlled SQL Server login test (§6) been performed and recorded?
12. Who owns and approves the connectivity, on record?

**Do not mark Q4 resolved on the strength of the existing structural/financial analysis.**
That analysis answers "what does the schema contain," a different and already-answered
question; it does not touch any of the twelve above.

---

## 8. Infra Handoff Checklist (send as-is)

- [ ] Confirm the network location of the production Kollur/SOHAM SQL Server (on-prem at
      the temple, or hosted elsewhere).
- [ ] Confirm where the `sync-worker` process will be deployed (this also resolves Q10).
- [ ] Provide the production hostname/IP and TCP port for the SQL Server instance.
- [ ] Confirm whether TCP/IP is enabled on that instance and whether it uses a named
      instance.
- [ ] State whether inbound access from the worker's network to the temple's database is
      permitted at all, as a policy matter, independent of technical feasibility — a
      refusal here is common across government temples and determines whether `PULL_JDBC`
      is even an option versus `PUSH_AGENT`/`FILE_DROP`/`SOURCE_API`.
- [ ] If permitted: specify required firewall rules (both directions), IP allowlisting, DNS,
      VPN/tunnel, proxy, bastion-host, or private-endpoint requirements — and, if the worker
      is containerised, what its container-network egress must look like.
- [ ] State the TLS/encryption and certificate requirements for the connection.
- [ ] Provision a dedicated, read-only SQL login for a connectivity test (§6) — not a
      production application account.
- [ ] Name who owns/approves this connectivity on record.
- [ ] Do **not** provide a credential, connection string, or secret through this checklist
      or in any document — that channel is what Q5 will decide, not this one.

---

## Engineering Dependency

**The Kollur connector (FIN-040/FIN-041) cannot proceed against the real source until the
network path is confirmed.** Nothing about the canonical model, aggregation, reconciliation,
APIs, or dashboard depends on Q4 — those are already built and tested against a synthetic
source (`ManualSyncTriggerE2ETest`, H2). Whichever way Q4 resolves, the cost is exactly one
`connector_type` value and one connector implementation, as already documented in
[`HANDOFF.md`](HANDOFF.md) and [`IMPLEMENTATION_TASKS.md`](IMPLEMENTATION_TASKS.md).

---

## Related Documents

- [`HANDOFF.md`](HANDOFF.md) — "Q4 Status" / "Q5 Status" sections (engineering summary).
- [`OPERATOR_RUNBOOK.md`](OPERATOR_RUNBOOK.md) §3 — why the CLI does not solve Q4.
- [`MULTI_TEMPLE_FINANCE_ARCHITECTURE.md`](MULTI_TEMPLE_FINANCE_ARCHITECTURE.md) §42 — where
  Q4 and Q10 were first opened.
- [`KOLLUR_FINANCE_DATA_ANALYSIS.md`](KOLLUR_FINANCE_DATA_ANALYSIS.md) and
  [`../database/KOLSOHAM_DATABASE_ANALYSIS.md`](../database/KOLSOHAM_DATABASE_ANALYSIS.md) —
  the offline structural/financial analysis this document explains is not a Q4 answer.
