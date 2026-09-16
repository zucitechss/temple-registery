# ADR-010 — Finance Domain in the Monolith; Only Ingestion Is a Separate Deployment

**Status:** Proposed · **Date:** 2026-09-16

## Decision

The Finance **domain** — APIs, report services, aggregation, dashboard — stays inside the existing modular monolith. Only **ingestion** (connectors, extraction, normalization, sync scheduling) is deployed separately: the same codebase and artifact, activated by a `sync-worker` Spring profile, running in a different network zone with different credentials.

## Context

The existing backend is a layered Spring Boot application — 109 entities, 65 repositories, 155 services, 39 controllers — with `@EnableScheduling`, `@EnableAsync`, bounded executors, Caffeine caching and Flyway. There is no existing integration component, no second datasource, and no message broker.

Two forces pull in opposite directions. ADR-001 requires that the registry runtime cannot reach temple databases, which argues for separation. The stated preference against reflexive microservices, and the operational cost of a second service in a government deployment, argue against it.

## Options

| Option | Assessment |
|---|---|
| **A** — Everything in the monolith | Simplest; **the monolith holds every temple credential — fails ADR-001's real intent** |
| **B** — Finance API in monolith, ingestion as a separate profile of the same artifact | Credential and network isolation; one repo, one build, one pipeline |
| **C** — Separate ingestion microservice, own repo and lifecycle | Same isolation; a second repository, build, deployment, runbook and set of shared DTOs |
| **D** — Full finance microservice (API + ingestion) | Maximum isolation; duplicates auth, RBAC, DACVM, audit; heaviest |

## Advantages of B

It draws the boundary exactly where a real constraint sits — around credentials and network reach — and nowhere else. Domain classes, DTOs, entities and repositories are shared without an API contract or a published library. Deployment is `--spring.profiles.active=sync-worker` on the same jar, so there is one CI pipeline and one version to reason about. The Finance API continues to reuse the existing JWT, roles, DACVM, audit and export infrastructure rather than reimplementing any of it.

Notably, **D would force reimplementing authentication, authorization, the DACVM policy engine and audit logging**, all of which already exist and work. That cost buys nothing the constraint actually asks for.

## Disadvantages

Two deployments of one artifact can confuse operations if poorly documented. Profile-conditional wiring must be correct and tested — the web layer genuinely disabled in the worker, connector beans genuinely absent from the monolith. A shared codebase means a worker-only change still triggers a full build.

## Recommendation

**Option B.**

## Consequences

- Connectors and sync schedulers: `@Profile("sync-worker")`. Finance controllers: `@Profile("!sync-worker")`.
- Both processes share the registry database; only the worker also reaches temple sources.
- Scaling: batches are already independent per temple, so workers can later partition by temple with no design change.
- **Revisit trigger:** ingestion needs an independent release cadence, an independent scaling profile, or a different technology stack. None applies today.
- **Risk R12:** collapsing this into Option A "just for Kollur" is the single most likely way the architecture silently degrades. The profile split belongs in Phase 1, not in a later hardening phase.
