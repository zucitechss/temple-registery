# ADR-009 — Booking, Payment, Schedule and Execution Are Separate Entities

**Status:** Proposed · **Date:** 2026-09-16

## Decision

The Nirantara (perpetual seva) lifecycle is modelled as **four separate tables** — `fin_nirantara_subscription`, `fin_nirantara_payment`, `fin_nirantara_schedule`, `fin_nirantara_execution` — not one table with status columns. `fin_nirantara_execution` is created and remains **empty for Kollur by design**.

## Context

The business question is *"what Nirantara Sevas are booked, and are they actually taking place?"* Kollur can answer the first half and cannot answer the second. The evidence:

| Signal | Finding |
|---|---|
| `seva_List.seva_Prepared` / `Seve_closed` / `LetterPrinted` | **`0` on all 15,091 rows** |
| `Sevaattendedlist` (attendance) | **0 rows** |
| `seva_sevakartaDetails.NoOfSevaIssue` | **`0` on all 6,659 rows** |
| `seva_List_Fridaypooja.seva_Prepared` | `1` on ~100 % of rows — **including 17,808 future-dated rows into 2027** |
| `ACTIVE` / `SEVACLOSED` / `Renewal` | Constant on all 6,659 rows |
| `FDAMOUNT` (endowment corpus) | Positive on **zero** rows |

The fourth row is the trap. `seva_Prepared = 1` is present, populated and looks exactly like a fulfilment flag — but a flag already set for rituals scheduled in 2027 cannot mean "performed". It means "schedule row generated". Any single-table model with a status column invites someone to read it as execution.

## Options

| Option | Assessment |
|---|---|
| **A** — One table with status columns | Compact; **makes the misreading easy**; one careless query reports fabricated fulfilment |
| **B** — Two tables (subscription, payment) | Better; still conflates schedule with execution |
| **C** — Four tables, one per lifecycle stage | Verbose; **makes the misreading structurally impossible** |

## Advantages of C

Execution lives in a different table from scheduling, so there is no query that accidentally turns one into the other. `fin_nirantara_execution` existing with zero rows, backed by capability `NOT_AVAILABLE`, is an explicit statement that fulfilment is unknown — stronger than a comment and visible in the schema. Kollur's `seva_Prepared` maps to `fin_nirantara_schedule.schedule_generated`, a name that cannot be mistaken for performance. And the day the temple begins recording fulfilment, rows simply appear and the capability flips — no schema change, no migration, no code change.

## Disadvantages

Four tables where one might do, and more joins. Accepted: the alternative is a dashboard that reports a fulfilment percentage no data supports, on a government oversight platform.

## Recommendation

**Option C.**

## Consequences

- `fin_nirantara_execution` is created empty; capability `NIRANTARA_EXECUTION` = `NOT_AVAILABLE` with the reason: *"The source system records scheduled sevas but never records performance. Booking data alone cannot prove execution."*
- `subscription_status` loads as `UNKNOWN` with `status_confidence = ASSUMED` for Kollur, because constant columns carry no information and rendering "6,659 active" would assert what the source does not know.
- Report R18 renders an availability notice, never a figure. Reports R16 and R17 display the execution caveat alongside booking data.
- `corpus_amount` is `NULL`, not `0` — consistent with ADR-007.
