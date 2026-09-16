# ADR-003 — Canonical Grain Is Daily Aggregate, Not Per-Receipt

**Status:** Proposed · **Date:** 2026-09-16

## Decision

`fin_revenue_fact` stores one row per **(temple, date, service, category, payment mode, counter)** — not one row per receipt. Individual receipts are not copied into the central platform.

## Context

Kollur holds **22,348,125** receipt rows across eight financial years. Measured directly against the source:

| Measurement | Value |
|---|---:|
| Raw receipt rows, all 8 FYs | **22,348,125** |
| Distinct (date × seva × counter) | **121,242** |
| **Reduction** | **184×** |
| FY2025-26 alone | 3,950,094 → **18,922** |

Every report in [FINANCE_REPORT_CATALOG.md](../FINANCE_REPORT_CATALOG.md) was checked against this grain. All are satisfiable.

## Options

| Option | Rows (Kollur) | Rows (100 temples) | Assessment |
|---|---:|---:|---|
| **A** — Per-receipt | 22.3 M | ~2.2 B | Full fidelity; copies devotee PII centrally; heavy |
| **B** — Daily grain | **121 k** | **~12 M** | Every catalogued report satisfied; no PII |
| **C** — Monthly grain | ~10 k | ~1 M | Smallest; **loses daily trend and festival-peak analysis** |
| **D** — Aggregates only, no facts | ~3 k | ~300 k | No drill-down, no re-aggregation without re-sync, no record-level reconciliation |

## Advantages of B

184× smaller than A — measured, not estimated. It preserves every dimension the catalog needs: date at day, month and financial-year grain; service; category; counter; operator; payment-mode indicator; and cancellation counts and amounts. New reports can be derived from stored facts without re-extracting from the source.

It also **stores no devotee names, addresses, mobile numbers or email addresses**, all four of which exist on the source table. That is a privacy benefit in its own right, not merely an acceptable loss — a central government platform holding personal data it has no reporting use for is a liability.

## Disadvantages

No receipt-level drill-down. Mitigated where it matters: cancellations (465 rows across the entire history) are stored at **full detail** in `fin_cancellation`, preserving the one drill-down with genuine audit value. Receipt-level detail can be enabled per temple per capability should a real requirement appear.

## Recommendation

**Option B**, with per-temple opt-in to finer grain if ever justified.

## Consequences

- Connectors `GROUP BY` **at the source**, so 22 M rows never cross the wire.
- `uk_frf_grain (temple_id, transaction_date, service_id, category_id, payment_mode, counter_ref)` makes loading idempotent and replayable.
- Devotee-level analytics are permanently out of scope — independently impossible anyway, since `PersonName` is free text with no stable key.
- Revisit if a report genuinely requires per-receipt data; the change is per-temple configuration, not a redesign.
