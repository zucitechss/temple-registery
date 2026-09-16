# ADR-008 — Source of Truth Is Explicit, Versioned, Reviewable Configuration

**Status:** Proposed · **Date:** 2026-09-16

## Decision

For every financial metric, the authoritative source object and field are recorded in `fin_source_of_truth_decl` — versioned, with the **rejected alternatives and the reason each was rejected**, and with business sign-off. Connectors reference the declaration; the version is stamped on every canonical fact.

## Context

Kollur offers three plausible revenue columns that disagree materially:

| Candidate | FY2025-26 total | Verdict |
|---|---:|---|
| `DailySevaNew.Amount` (header) | **₹90,61,62,936** | **Authoritative** |
| `DailySevaNewDetails.TotalAmount` | ₹53,77,53,226 | 41 % short |
| `DailySevaNewDetails.Amount × Qty` | ₹56,31,07,228 | Disagrees with the table's own column |

Header and detail have exact 1:1 row correspondence (3,950,094 each) and zero orphans, so the gap is not missing records — the detail table is internally inconsistent. A fourth trap, `DailySevaNewOld`, is a byte-exact duplicate of all six archives whose inclusion doubles history.

The detail table is the *more granular* one. To an engineer arriving later without this context, switching revenue to it would look like an improvement. Encoding the decision as a `WHERE` clause in a connector leaves nothing to stop that.

## Options

| Option | Assessment |
|---|---|
| **A** — Implicit in connector code | Fastest; invisible; a reasonable-looking future change silently breaks revenue by 41 % |
| **B** — Documented in prose only | Better; drifts from code; not machine-checkable; not versioned with the data |
| **C** — Declared in data, versioned, referenced by code, stamped on facts | Auditable; survives staff turnover; supports restatement |

## Advantages of C

The decision becomes a reviewable artefact with a named approver rather than a line of SQL. Recording the *rejected* alternatives is what gives it force — the next engineer sees not just what was chosen but that the tempting alternative was measured and found wrong. Versioning means a later change to the definition is a restatement with a date, so historical figures remain explicable. And `source_of_truth_version` on every fact row makes any published number traceable to the rule that produced it.

## Disadvantages

Extra configuration and an onboarding step. Justified by the size of the error it prevents — a 41 % revenue understatement on a government oversight dashboard.

## Recommendation

**Option C.**

## Consequences

- Kollur `REVENUE_AMOUNT` v1: `DailySevaNew.Amount`, filter `BillCancled=0 AND Deleteflag=0 AND ReceiptDate>='2015-01-01'`, with all three alternatives recorded in `rejected_alternatives_json` including their measured totals.
- `fin_revenue_fact.source_of_truth_version` stamps every row.
- Declaring source of truth is a **mandatory, signed-off onboarding gate** (step 7).
- Changing a declaration creates a new version and a restatement — it never edits history in place.
