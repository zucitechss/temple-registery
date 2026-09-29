# CHAPTER 0 — PROJECT MAP

I went through the whole `backend/` folder first. These are the facts I verified in the code before explaining anything.

## 0.1 The numbers

| What | Count |
|---|---|
| Java source files (`src/main`) | **666** (about 19,900 lines) |
| Test files (`src/test`) | **105** |
| JPA entities (`@Entity`) | **67** |
| Controllers | **40** (in `controller/`) |
| DTO classes | **189** (in `dto/`) |
| Repositories | **65** |
| Service files (interfaces + implementations) | **155** |
| MapStruct mappers | **5** |
| Flyway migrations | **27** (`V1` … `V113`) |
| Email templates (Thymeleaf HTML) | **24** |

## 0.2 Tech stack (from [pom.xml](../backend/pom.xml))

| Library | What it does in this project |
|---|---|
| Spring Boot **3.4.4**, Java **21** | The base framework |
| `spring-boot-starter-web` | REST controllers and the embedded Tomcat server |
| `spring-boot-starter-data-jpa` + `mysql-connector-j` | Database access through Hibernate. The DB is **TiDB Cloud**, which is MySQL-compatible |
| `flyway-core` / `flyway-mysql` | Versioned SQL migrations in `db/migration` |
| `spring-boot-starter-security` + `jjwt` 0.12.6 | Stateless login with **RS256 JWT** tokens |
| `totp-spring-boot-starter` | MFA through authenticator-app codes (TOTP) |
| `spring-boot-starter-validation` | `@Valid`, `@NotNull` and similar checks on request DTOs |
| `spring-boot-starter-aop` | Needed by `PolicyEnforcementAspect` (the DACVM access-control engine) |
| `mapstruct` 1.6.3 + `lombok` | Generated entity→DTO mappers, and generated getters/setters/builders |
| `spring-boot-starter-mail` + `thymeleaf` | HTML emails |
| `spring-boot-starter-cache` + `caffeine` | In-memory cache for access-control policies |
| `itext-core`, `opencsv` | PDF and CSV exports |
| `springdoc-openapi` | Swagger UI at `/swagger-ui.html` |
| `logstash-logback-encoder` | JSON-structured logs |
| **Tests:** JUnit 5, Mockito, `spring-security-test`, H2, **Testcontainers MySQL**, **jqwik** (property-based testing), JaCoCo | |

## 0.3 Users of the system (roles)

From [RoleConstants.java](../backend/src/main/java/com/templeregistry/security/RoleConstants.java), the application has six roles:

| Role | Who it is (in plain words) |
|---|---|
| `SUPER_ADMIN` | State-level administrator. Can do everything |
| `DISTRICT_COLLECTOR` (DC) | Government officer who **approves / rejects / flags** data for temples in their district |
| `DC_STAFF` | The DC's office staff. Can read, plus a few limited writes |
| `TEMPLE_AUTHORITY` (TA) | A person managing **one temple**. Submits the temple's profile, trust details and asset declarations |
| `AUDITOR` | Read-only compliance officer. The only thing they can write is observations |
| `VIEWER` | Read-only |

## 0.4 The business domain in one paragraph

This is a **Karnataka government temple registry** (the OpenAPI config names the "HR&CE Department, Karnataka"). A **Temple Authority** registers a temple and fills in:
- the temple's **profile**
- its managing **trust**, with board members, meetings and financials
- a yearly **asset declaration** covering land, buildings, gold/silver, vehicles, artifacts, financial assets and so on

Everything the TA submits goes through a **governance workflow**:

```
DRAFT → SUBMITTED → UNDER_REVIEW → (CLARIFICATION ↔ RESUBMIT) → APPROVED / REJECTED
```

The **District Collector** of the temple's district reviews it. The DC can ask for clarification, order a physical site visit, then approve or reject. Every step writes an audit trail, sends notifications (in-app, email and live SSE push), and may create a versioned snapshot.

## 0.5 Module roadmap (what Chapters 10–11 will cover)

| # | Module | Main URL prefix | Heaviest class |
|---|---|---|---|
| 1 | **Auth / Registration / Profile / MFA** | `/api/v1/auth`, `/api/v1/profile` | `AuthServiceImpl`, `RegistrationServiceImpl` |
| 2 | **Geo hierarchy** (State→City→District→Taluk→Hobli) | `/api/v1/geo` | `GeoServiceImpl` |
| 3 | **Temple** (profile, photos, staging, search) | `/api/v1/temples` | `TempleServiceImpl`, `TempleProfileStagingServiceImpl` |
| 4 | **Trust** (board members, meetings, financials) | `/api/v1/...trusts` | `TrustServiceImpl` |
| 5 | **Asset Declaration** (+ versions, acknowledgement, conversation) | `/api/v1/declarations` | `DeclarationServiceImpl` (892 lines) |
| 6 | **Governance / Workflow Engine** | `/api/v1/governance`, `/api/v2/workflow` | `GovernanceWorkflowServiceImpl` (941 lines), `WorkflowEngineImpl` |
| 7 | **DC module** (dashboard, verification, compliance, export) | `/api/v1/dc/**` | `DcTempleProfileServiceImpl` |
| 8 | **TA dashboard** | `/api/v1/ta` | `TaDashboardServiceImpl` |
| 9 | **Admin** (users, system config, notification rules, **access control / DACVM**) | `/api/v1/admin/**` | `AdminServiceImpl`, `PolicyEvaluationServiceImpl` |
| 10 | **Auditor / Observations** | `/api/v1/auditor`, `/api/v1/observations` | `AuditorServiceImpl` |
| 11 | **Notices** (notice board) | `/api/v1/notices` | `NoticeServiceImpl` |
| 12 | **Notifications** (in-app, email outbox, SSE) | `/api/v1/notifications` | `NotificationRouter`, `EmailDeliveryService` |
| 13 | **Documents** (file upload) | `/api/v1/documents` | `DocumentServiceImpl` + `LocalFileStorageServiceImpl` |
| 14 | **Employees / Contractors** | `/api/v1/...` | `EmployeeServiceImpl`, `ContractorServiceImpl` |
| 15 | **Export** (CSV/PDF, async) | `/api/v1/export`, `/api/v1/dc/export` | `ExportServiceImpl`, `AsyncExportBean` |
| 16 | **Timeline** | `/api/v1/timeline` | `TempleTimelineServiceImpl` |
| 17 | **Viewer dashboard** | `/api/v1/viewer` | `ViewerDashboardServiceImpl` |

## 0.6 Background work (things that run without a user request)

| Kind | Where | When |
|---|---|---|
| `@Scheduled` | `OverdueWorkflowScheduler` | Daily at 20:30 and 03:30 UTC (marks overdue items) |
| `@Scheduled` | `NoticeExpiryScheduler` | Daily at 01:00 |
| `@Scheduled` | `EmailDeliveryService` | Every 10 s (send outbox), every 5 min (retries) |
| `@Scheduled` | `NotificationRouter` | Every 5 s and every 60 s (process the notification outbox) |
| `@Scheduled` | `EmailRetryScheduler` | Every 10 min (monitoring) |
| `@Async` | `AuditServiceImpl`, `EmailServiceImpl`, `NotificationServiceImpl`, `TempleSearchSummaryServiceImpl`, `NoticeServiceImpl` | Fire-and-forget work on the `taskExecutor` thread pool |
| `@Async("exportExecutor")` | `AsyncExportBean` | Export jobs on a separate, bounded pool |
| `@TransactionalEventListener(AFTER_COMMIT)` | `NotificationRouter`, `GovernanceDomainEventTimelineListener` | Runs only **after** a DB transaction successfully commits |
| `@EventListener(ApplicationReadyEvent)` | `TrustDataRepairService`, `EmailStartupValidator` | Once, when the app finishes starting |

## 0.7 Things I noticed that you should know (not fixing them, as you asked)

1. **Secrets in the repo.** [application.yml](../backend/src/main/resources/application.yml) has a real-looking TiDB username and password as the *default fallback* in `${DB_PASSWORD:...}`, and default encryption/HMAC keys. [src/main/resources/keys/](../backend/src/main/resources/keys/) contains `jwt-private.pem` and `private.pem`. Anyone with repo access can connect to the DB and **sign valid JWTs**. You should rotate these values.
2. **Flyway *and* `ddl-auto: update` are both on.** Two different systems can change the schema: Flyway runs the SQL files, then Hibernate also "fixes up" tables from the entities. That is why migrations like `V111__fix_entity_schema_drift.sql` exist. (Covered in Chapter 4.)
3. **Old and new code live side by side.** The code went through a "Phase 5" refactor to a central `WorkflowEngine` and `NotificationRouter`, and the old pieces are still present:
   - `StatusTransitionValidator` (two copies: in `util/` and `service/declaration/`) — deprecated.
   - `NotificationHelper` — deprecated, but still injected by `DcTempleVerificationServiceImpl`, `DeclarationWorkflowServiceImpl` and `TempleProfileWorkflowServiceImpl`.
   - `OverdueScheduler` has **no** `@Component`, so Spring never creates it. It is dead code, replaced by `OverdueWorkflowScheduler`.
4. **Same class name in two packages.** This is easy to trip over while reading:
   - `IdempotencyRecord`: `entity/dc/` → table `idempotency_records`; `entity/workflow/` → table `workflow_idempotency_records`.
   - `ActionContextResolver`: `controller/governance/` and `service/workflow/`.
   - `NotificationEventPublisher`: `service/notification/` (a class) and `service/dc/` (an interface).
5. `config/AwsConfig.java` is an empty leftover. S3 was replaced by local disk storage in `./uploads`.

---
