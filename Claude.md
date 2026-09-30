# CLAUDE.md

This repository contains the Temple Registry platform, a government workflow system for managing temples, trusts, assets, employees, contractors, compliance observations, and approval workflows.

## Repository Structure

| Directory | Stack | Responsibility |
|------------|--------|----------------|
| `backend/` | Java 21, Spring Boot 3.4, Maven | REST API |
| `frontend/` | React 18, TypeScript, Vite, RTK Query, Tailwind, shadcn/ui | UI |
| `e2e/` | Playwright, TypeScript | End-to-end & API tests |

### Architecture References

Read before major changes:

- `docs/architecture/CURRENT_ARCHITECTURE_FROM_CODE.md` (**source of truth**)
- `HLD_TEMPLE_REGISTRY.md`
- `LLD_TEMPLE_REGISTRY.md`
- `.github/instructions/*.instructions.md`
- `.github/GAPS.md`

---

# Mandatory Development Workflow (TDD)

Follow **Red → Green → Refactor** for every feature, bug fix, and workflow change.

## 1. Understand

- Review affected architecture and workflow.
- Identify business rules, security constraints, roles, and edge cases.

## 2. Write Failing Tests First (RED)

Backend:

```bash
mvn test -Dtest=<TestClass>
```

Frontend:

```bash
npx vitest run <test-file>
```

E2E:

```bash
npx playwright test <spec>
```

## 3. Implement Minimal Code (GREEN)

- Write only enough code to satisfy the failing test.
- Avoid over-engineering.

## 4. Refactor

- Improve readability.
- Remove duplication.
- Preserve behavior.

## 5. Verify

Backend:

```bash
mvn test
mvn verify -DskipITs
```

Frontend:

```bash
npm run lint
npm run test:coverage
```

## 6. Architecture Validation

Before completion ensure:

- No controller business logic.
- No entity leakage through APIs.
- No direct workflow state mutation.
- No authorization bypass.
- No migration modification.

## 7. Commit

Prefer Conventional Commits:

```text
feat(module): add trust approval validation
fix(workflow): prevent rejected transition
refactor(service): simplify policy evaluation
```

---

# Common Commands

## Backend

```bash
mvn spring-boot:run
mvn test
mvn verify
mvn verify -DskipITs
```

## Frontend

```bash
npm run dev
npm run build
npm run lint
npm test
npm run test:coverage
```

## E2E

```bash
npx playwright test
npm run test:api-core
npm run test:readonly-smoke
npm run report
```

---

# Backend Rules

## Layering (Non-Negotiable)

```text
Controller
↓
Service Interface
↓
ServiceImpl
↓
Repository
↓
Database
```

### Rules

- Business logic lives only in `service/impl`.
- Controllers contain no business logic.
- Controllers should not contain try/catch for application errors.
- Use DTOs for all API contracts.
- Use MapStruct for entity/DTO mapping.
- Centralized exception handling only.

## Entities

All entities extend `BaseEntity`.

Common fields:

- `id`
- `deleted`
- `createdAt`
- `updatedAt`
- `createdBy`
- `updatedBy`

Soft delete must be explicitly applied:

```java
@SQLRestriction("is_deleted = false")
```

## Database

- All schema changes use Flyway.
- Create new migrations only.
- Never modify applied migrations.
- Never use `ddl-auto=create` or `ddl-auto=update`.
- Check the highest existing migration version before creating a new one.

## API Standards

Use:

```java
ApiResponse<T>
```

Paginated endpoints must return:

```java
PaginatedResponse<T>
```

Default page size:

```text
10
```

Maximum page size:

```text
100
```

---

# Workflow Engine (Critical Domain Rule)

The workflow engine is the only valid mechanism for governed state transitions.

Use:

```java
WorkflowEngine
```

Never perform direct status updates:

```java
entity.setStatus(...)
repository.save(...)
```

### Governed Modules

- Temple Profile
- Declaration
- Trust
- Board Member

### Invariants

- `REJECTED` is terminal.
- Role validation required.
- District validation required.
- Workflow policy validation required.
- Optimistic locking validation required.

---

# Security

Canonical roles:

```text
SUPER_ADMIN
DISTRICT_COLLECTOR
DC_STAFF
TEMPLE_AUTHORITY
AUDITOR
VIEWER
```

Rules:

- JWT authentication only.
- Authorization via `@PreAuthorize`.
- Deny by default.
- `AUDITOR` may raise observations but remains otherwise read-only.

---

# Frontend Rules

## Layering

```text
Page
↓
Component
↓
Hook
↓
RTK Query
↓
Backend
```

## Rules

- Components are presentation-only.
- Business logic belongs in hooks.
- RTK Query calls belong in hooks/pages.
- Route protection lives in route guards.
- No inline authorization logic in pages.

## Authentication

- JWT stored only in HttpOnly cookies.
- Never store JWTs in:
  - localStorage
  - sessionStorage
  - Redux state

Use:

```ts
credentials: "include"
```

## UI Standards

Use only:

- Tailwind CSS
- shadcn/ui
- React Hook Form
- Zod

Zod schemas are the single source of truth.

Avoid duplicate interfaces when `z.infer` is available.

---

# Testing Standards

## Naming Convention

```text
should_<expectedBehavior>_when_<condition>
```

## Backend

Use:

- JUnit 5
- Mockito
- AssertJ

Rules:

- Service tests first.
- Success case required.
- Failure case required.
- Edge case required.
- No `@SpringBootTest` for unit tests.
- No `Thread.sleep()`.
- No placeholder assertions.

## Frontend

Use:

- Vitest
- React Testing Library
- MSW

Rules:

- Use `renderWithProviders`.
- Mock at network layer.
- Prefer user interactions over implementation testing.
- No snapshot-driven testing.
- No skipped tests.

## E2E

Use:

- Playwright
- Page Object Model
- Fixtures and test factories

Rules:

- Separate API specs from browser specs.
- Confirm environment before running write operations.
- Prefer read-only smoke tests when unsure.

---

# Performance Rules

## Avoid N+1 Queries

Always use:

```java
@EntityGraph
```

or

```sql
JOIN FETCH
```

Never rely on lazy loading inside loops.

## Optimistic Locking

Use `@Version` for concurrently edited entities.

Handle conflicts centrally.

---

# File Upload Rules

Allowed MIME types:

```text
image/jpeg
image/png
application/pdf
```

Maximum size:

```text
5 MB
```

Rules:

- Validate client-side.
- Validate server-side.
- Store file paths/URLs only.
- Never store file bytes in database columns.

---

# Logging Rules

Log:

- Action name
- Entity ID

Never log:

- Tokens
- Passwords
- Request payloads
- Secrets
- PII

---

# Configuration Rules

- Never commit secrets.
- Use environment-specific configuration.
- Frontend configuration must come from `VITE_*` variables.
- Do not hardcode URLs, credentials, or environments.

---

# Quick Checklist Before Completing Work

- [ ] Tests written first (TDD)
- [ ] Tests passing
- [ ] No controller business logic
- [ ] DTO-only API contracts
- [ ] WorkflowEngine used for governed entities
- [ ] Authorization enforced
- [ ] Flyway migration added (if schema changed)
- [ ] No N+1 queries
- [ ] No secret or PII logging
- [ ] Linting and tests pass