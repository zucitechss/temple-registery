# ADR-007 — Data Availability Is First-Class Data; Amounts Are Nullable

**Status:** Proposed · **Date:** 2026-09-16

## Decision

Data availability is modelled as **queryable data** (`fin_temple_capability`), carried through every API response, and enforced structurally by making **all amount fields nullable**. A metric that is not available returns `null` plus an availability status and a human-readable reason. It never returns `0`.

## Context

`0` and `NOT_AVAILABLE` are different types of statement, not different values of one:

- `expense = 0` asserts *"there were no expenses"*.
- `expense = NOT_AVAILABLE` asserts *"we do not know what the expenses were"*.

Silently converting the second into the first misinforms a Deputy Commissioner about a temple's finances. Kollur supplies three live cases:

| Case | Wrong rendering | Correct rendering |
|---|---|---|
| Expenditure | ₹0 — "this temple spends nothing" | `NOT_AVAILABLE` — the source records no expenditure |
| Nirantara payments after FY2023-24 | ₹0 — "collection collapsed" | `PARTIALLY_AVAILABLE` to FY2023-24 |
| Gold/silver FY2021-22, FY2022-23 | Zero bars — "no donations" | A gap in the series |

The middle case is the sharpest: the schedule table continues to generate ₹10.88 Cr of expected sevas in FY2025-26 over exactly the period where payment recording stops. A zero there would support a conclusion the data does not.

## Options

| Option | Assessment |
|---|---|
| **A** — Default missing to 0 | Simple; **actively misleading**; rejected by hard constraint C3 |
| **B** — Omit unavailable metrics from responses | No false zero, but the UI cannot distinguish "absent" from "forgotten", and cannot explain why |
| **C** — Nullable amounts + explicit availability + reason | Unambiguous; self-explaining; one uniform handling path |

## Advantages of C

`null` cannot be summed, charted or averaged by accident — the type system does the enforcing, not a code reviewer. Every response carries a reason the UI can show verbatim, so an empty panel explains itself instead of looking broken. Availability being queryable means the dashboard composes itself from capabilities rather than branching on temple identity. And an availability *matrix* becomes a report in its own right (R28), which matters when comparing temples in a district total.

## Disadvantages

Every consumer must handle null. Mitigated by keeping the response envelope identical in shape for available and unavailable metrics, so there is exactly one code path rather than two.

## Recommendation

**Option C.**

## Consequences

- Enum: `AVAILABLE`, `PARTIALLY_AVAILABLE`, `NOT_AVAILABLE`, `NOT_APPLICABLE`, `STALE`, `SYNC_FAILED`, `INVALID`, `PENDING_RECONCILIATION`.
- Amount columns in canonical and aggregate tables are nullable; API amount fields are nullable.
- `fin_temple_capability.availability_reason` is **user-facing text**, written during onboarding and reviewed, not a developer note.
- `fin_precious_metal_fact.value_basis` includes `NOT_RECORDED`, which is what structurally prevents the `ASSUMED_VALUE_PER_ITEM_RS = 12000` constant from returning.
- A UI component receiving `null` renders `<AvailabilityNotice>`, never a chart, never a zero.
