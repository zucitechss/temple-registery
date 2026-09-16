# ADR-011 — Precomputed Aggregates, Published Only After Reconciliation Passes

**Status:** Proposed · **Date:** 2026-09-16

## Decision

Reports read **precomputed aggregate tables** (`fin_agg_*`), rebuilt deterministically from canonical facts after each sync. Aggregates are **published only when reconciliation passes**; on variance, the previous good aggregates remain in place and the temple is marked stale.

## Context

Reports must be fast across four scopes — temple, multi-temple, district, state — over data that will grow to tens of millions of canonical rows. Separately, every stage of the pipeline can produce a plausible-looking wrong number (see [FINANCE_RECONCILIATION.md](../FINANCE_RECONCILIATION.md) §1), and a wrong financial figure on a DC dashboard is worse than a slightly old one.

## Options

| Option | Assessment |
|---|---|
| **A** — Query canonical facts per request | No extra tables; repeated scans; district/state scope aggregates across every temple on every request |
| **B** — Database views over facts | No storage; computed per request; same cost as A |
| **C** — Materialized views | Precomputed; TiDB support and refresh semantics are not something to depend on here |
| **D** — Explicit aggregate tables rebuilt after sync | Full control over refresh, content and **publication gating**; costs storage and a rebuild step |

## Advantages of D

Reads become small, indexed lookups regardless of underlying volume, so a state-wide query never scans fact tables. Multi-temple and district scoping reduce to `SUM … GROUP BY` over a small table — which is what makes "all temples" possible **without contacting any source database**. Rebuild is deterministic and idempotent, so aggregates can always be regenerated from facts.

The decisive advantage is control over *when* aggregates become visible. Because publication is a distinct step, it can be gated on reconciliation. A batch that loads but fails reconciliation leaves the prior figures in place, so the dashboard shows **slightly old, correct** numbers rather than **fresh, wrong** ones — the right default for government financial reporting.

## Disadvantages

Storage duplication (small — ~3 k rows for Kollur, ~300 k at 100 temples), a rebuild step to maintain, and the possibility of aggregates drifting from facts. Drift is mitigated by deterministic rebuild plus a periodic full recompute.

## Recommendation

**Option D.**

## Consequences

- `fin_agg_revenue_period` and `fin_agg_revenue_service` at Stage 1; `fin_agg_district_*` deferred to Stage 3.
- Only **affected periods** are rebuilt after an incremental sync, not the whole history.
- Each aggregate row carries `computed_at`, `sync_batch_id` and `availability`, so the API never has to infer availability and a period with no data stays distinguishable from a period with zero revenue.
- **No caching layer on top of aggregates** — they are already precomputed, and caching would delay a reconciliation-triggered correction for no performance gain.
- Drill-down from an aggregate to canonical facts remains available for investigation.
