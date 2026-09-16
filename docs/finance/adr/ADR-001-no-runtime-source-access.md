# ADR-001 — No Runtime Source Access; Extraction in a Separate Sync Worker

**Status:** Proposed · **Date:** 2026-09-16 · **Deciders:** Architecture review

## Decision

The Temple Registry runtime must never open a connection to a temple operational database. All extraction runs in a **Finance Sync Worker** — a separate process, built from the same codebase and activated by a Spring profile, deployed in a different network zone with different credentials.

## Context

The stated hard constraint is that Temple Registry must not directly query temple databases at runtime. Read narrowly this only forbids a JDBC call on a request thread. Read properly it forbids the registry runtime being *coupled to, credentialed for, or capable of reaching* temple operational databases.

Independent facts reinforce it. Kollur has three non-PK indexes across ~50 M rows and **no date index on any archive table**, so a live multi-year query is a multi-gigabyte scan. The source is already ~7 weeks stale, so real-time access buys nothing. And a single JVM holding read credentials for 100 temple databases is a concentration of risk no government platform should accept.

## Options

| Option | Assessment |
|---|---|
| **A** — Query source per request | Violates the constraint. Also fails on performance, availability and security |
| **B** — `@Scheduled` ETL inside the monolith | Satisfies the narrow reading, **violates the real one** — the monolith still holds every credential and reaches every temple network |
| **C** — Separate sync worker, same codebase, different profile | Satisfies both readings. One artifact, one pipeline, two runtime identities |
| **D** — Separate microservice, own repo and lifecycle | Also satisfies both, at the cost of a second repository, build, deployment and runbook |

## Advantages of C

Credential isolation becomes a deployment fact rather than a coding convention. Network isolation is enforceable by infrastructure rather than by review. Sync load cannot degrade API latency, and a hung extraction cannot exhaust the web server's thread pool. Source credentials rotate without redeploying the registry. Yet there is one repository, one build and one set of shared domain classes — none of the microservice tax.

## Disadvantages

A second deployment target and its runbook. Two processes to monitor. Profile-conditional bean wiring needs care so the web layer is genuinely disabled in the worker and connectors are genuinely absent from the monolith.

## Recommendation

**Option C.**

## Consequences

- Connector beans are annotated `@Profile("sync-worker")`; the monolith cannot instantiate them.
- `fin_source_system.credential_ref` stores an alias, never a secret.
- **Risk R12 is real.** Running the ETL inside the monolith "just for Kollur" is tempting and would silently convert the hard constraint into a comment. The profile split is therefore **Phase 1 scope, not a later optimisation**.
- Prerequisite: credential storage must be settled (Q5) — `application.yml` currently carries a committed fallback database username and password, and there is no secrets manager.
