# ADR-004 — Code Connectors for Extraction, Configuration for Value Mapping

**Status:** Proposed · **Date:** 2026-09-16

## Decision

**Extraction** is implemented in code — one `TempleFinanceConnector` implementation per source system. **Value mapping** is configuration held in `fin_mapping_rule`. A generic configuration-driven JDBC connector is provided for structurally simple sources.

## Context

Temples will differ in database technology, schema, naming and business process. Two extremes are available: write a connector per temple, or drive everything from a mapping DSL.

Kollur demonstrates why pure configuration fails. Correct extraction requires unioning exactly seven physical tables; **excluding `DailySevaNewOld`** because it duplicates all six archives and would double all history; rejecting `DailySevaNewDetails` because its `TotalAmount` is 41 % short and inconsistent with its own `Amount × Qty`; guarding dates because 1955, 1969 and 2004 values exist; handling a live table that currently holds two financial years at once; and aggregating at source to control volume.

That is business logic, not field mapping. Expressing it in a declarative DSL would mean inventing a programming language and then debugging programs written in it without a debugger, a type system or a test framework.

Conversely, mapping 164 seva codes to categories is pure data and should never require a deployment.

## Options

| Option | Assessment |
|---|---|
| **A** — Code connector per temple | Handles arbitrary complexity; implies a deployment per new temple |
| **B** — Pure configuration / DSL | No deployment needed; cannot express Kollur's rules without becoming a language |
| **C** — Hybrid: code extraction + configured mapping | Complexity where complexity lives, data where data lives |

## Advantages of C

Source quirks stay in code — testable, reviewable, debuggable, and covered by regression tests for exactly the traps above. Mapping churn (a new seva code, a re-categorisation) is a data change with no release. A generic `JdbcTableConnector` still covers simple sources with no new code, so "a connector per temple" is the exception rather than the rule.

## Disadvantages

Two places to look when onboarding, and a judgement call about which concerns belong where. The boundary is stated explicitly: anything that determines *which rows and which columns* is code; anything that determines *what a value means* is configuration.

## Recommendation

**Option C.**

## Consequences

- New complex source ⇒ one connector class (3–5 engineer-days for Kollur-like complexity).
- New simple source ⇒ configuration only.
- Re-categorising a service ⇒ one `fin_mapping_rule` row, no deployment.
- Unmapped source values route to `UNMAPPED`, are counted, and raise a warning — never a silent default, so a newly added seva code surfaces as an operational signal rather than quietly vanishing from a revenue total.
