# ADR-005 — Scheduled Batch Synchronization, Not CDC or Streaming

**Status:** Proposed · **Date:** 2026-09-16

## Decision

Finance data synchronizes by **scheduled batch**, nightly per temple, configurable per source. No CDC, no streaming, no message broker.

## Context

Kollur's latest transaction is **2026-07-26**, roughly seven weeks before this analysis. The dashboard is a DC oversight tool reviewed periodically, not an operational console, and no stated requirement calls for intraday finance data.

The source is also constrained: zero foreign keys, three non-PK indexes across ~50 M rows, and hard constraint C8 forbids modifying it.

## Options

| Option | Assessment |
|---|---|
| **A** — Scheduled batch | Simple, auditable, restartable, replayable; freshness measured in hours |
| **B** — CDC (Debezium / SQL Server CDC) | Best freshness, lowest steady-state load; **requires enabling CDC on the source — violates C8 for Kollur**; adds Kafka and per-technology expertise |
| **C** — Streaming / event-driven | Requires temples to emit events; no temple does, and none can be compelled to |
| **D** — Near-real-time polling (minutes) | Repeated scans of an unindexed source for no business gain |

## Advantages of A

It matches the actual freshness requirement with a wide margin — a source seven weeks stale does not benefit from second-level latency. It requires no source modification, which C8 makes decisive. It introduces no new infrastructure. It is naturally batched, which gives reconciliation a well-defined unit of work. Failures are restartable and replayable from staging. And it reuses `@EnableScheduling`, already active in the codebase alongside four existing schedulers.

## Disadvantages

Hours-scale latency, accepted and surfaced explicitly rather than hidden (§28 of the architecture). Batch windows lengthen as temples are added, mitigated first by staggering schedules and later by partitioning workers by temple.

## Recommendation

**Option A.**

## Consequences

- `fin_source_system.sync_schedule_cron` is per temple.
- The dashboard always shows `lastSyncedAt` **and** `sourceDataThrough`, and never implies real-time data.
- **Revisit trigger:** a temple genuinely requires intraday finance data *and* its source permits CDC without violating an equivalent of C8. Kollur meets neither condition.
