# ADR-006 — Watermark Incremental Sync with Bounded Restatement

**Status:** Proposed · **Date:** 2026-09-16

## Decision

After a one-off historical load, synchronize incrementally using a **per-source watermark**, plus a **bounded restatement window** (current + previous financial year) recomputed on every run. Closed periods are frozen after a confirming checksum, and verified thereafter by a **periodic full-history checksum**.

## Context

Kollur's live `DailySevaNew` carries `inx_Modifieddate` on `ModifiedDate` — the only usable change column, and conveniently one of only three non-PK indexes in the database. The six archive tables have **no date index at all**, so re-scanning them nightly would be prohibitively expensive. They are also immutable in practice.

Four complications must be absorbed: receipts back-dated after extraction; cancellations applied to earlier receipts; source corrections to closed periods; and hard deletes, which no watermark can structurally detect.

## Options

| Option | Assessment |
|---|---|
| **A** — Full reload every run | Simplest and always correct; 22 M rows nightly against an unindexed source |
| **B** — Pure watermark | Cheapest; misses late arrivals, post-hoc cancellations and hard deletes |
| **C** — Watermark + bounded restatement + periodic checksum | Cheap steady state; catches late data and cancellations; detects deletes |

## Advantages of C

Nightly cost is proportional to change, not to history. The restatement window catches the two common cases — late entries and cancellations applied after extraction — without any special-case logic, because loading is idempotent on `uk_frf_grain`; the same rows simply overwrite themselves with corrected values. The periodic checksum catches the rare case that a watermark cannot see at all.

## Disadvantages

Three mechanisms rather than one, and correspondingly more to reason about. A hard delete inside a frozen period stays invisible until the next checksum run — acceptable at monthly cadence given how rarely temples delete historical receipts, and detectable when it happens.

## Recommendation

**Option C.**

## Consequences

- `fin_sync_batch.watermark_before` / `watermark_after` record the movement. **The watermark advances only on `SUCCESS`**, so a batch that loads but fails reconciliation re-reads the same window next run rather than skipping past a problem.
- The restatement window is per-temple configuration, not a constant.
- A monthly full-period checksum (row count + sum per financial year) triggers a targeted backfill on divergence.
- Kollur additionally re-scans the last 7 days by `ReceiptDate` to catch back-dated entries that `ModifiedDate` alone would order incorrectly.
