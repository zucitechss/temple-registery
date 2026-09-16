# ADR-002 — Canonical Store in the Existing TiDB Registry Database

**Status:** Proposed · **Date:** 2026-09-16

## Decision

Canonical finance data and aggregates live in the **existing registry database**, in the same schema, under a `fin_` table prefix. No separate reporting database, analytics database or warehouse is introduced now.

## Context

`application.yml` points at `gateway01.ap-southeast-1.prod.aws.tidbcloud.com:4000` — the registry runs on **TiDB Cloud**, not plain MySQL. TiDB is a horizontally-scalable, MySQL-wire-compatible HTAP engine with an optional columnar replica (TiFlash). This is materially different from the usual "MySQL will not cope with analytics" starting point.

Projected volume after the grain decision (ADR-003) is ~470 k rows for Kollur's full history and ~47 M across 100 comparable temples.

## Options

| Option | Assessment |
|---|---|
| **A** — Same DB, `fin_` prefix | Simple; native joins to `temples`/`trusts`; one backup and DR story; one Flyway pipeline |
| **B** — Separate schema, same instance | Marginal isolation gain; cross-schema joins; second migration pipeline |
| **C** — Separate reporting database | Real isolation; adds cross-database joins, a second DR story, and registry↔reporting sync |
| **D** — Data warehouse (BigQuery / Redshift / ClickHouse) | Strong analytics; duplicates capability TiDB already provides; major cost and skill addition |

## Advantages of A

TiDB already provides distributed HTAP, so adding a warehouse would duplicate something the project is already paying for. Joins to `temples`, `trusts` and `trust_financials` stay native — which matters because multi-temple and district scoping resolve through `temples.district_id`. One Flyway pipeline, one backup, one DR plan. The `fin_` prefix gives logical grouping and a clean seam if extraction is ever needed.

## Disadvantages

Reporting and OLTP share an instance, so a runaway analytical query could affect transactional latency. Mitigated by reading only from small precomputed aggregates (ADR-011), and by TiFlash if analytical load grows.

## Recommendation

**Option A**, with a named trigger to revisit.

## Consequences

- All finance tables carry the `fin_` prefix and are Flyway-managed like everything else.
- **Do not rely on `ddl-auto: update` for `fin_*`.** Risk R7 — schema drift — has already occurred once (migration `V108__add_physical_verification_columns.sql`, commit "Fix schema drift").
- **Revisit trigger:** canonical exceeds ~500 M rows, or reporting measurably degrades OLTP latency. Not expected before ~100 temples.
- Because the tables are prefixed and self-contained, a future move to a separate store is a table migration, not a redesign.
