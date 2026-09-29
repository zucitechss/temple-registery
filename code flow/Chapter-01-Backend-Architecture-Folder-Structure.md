# CHAPTER 1 — Backend Architecture & Folder Structure

## 1.1 The big idea: layers

Before we look at folders, here is the one idea the whole backend is built on.

> A request from the frontend passes through **layers**, like passing through the rooms of an office. Each room has **one job** and only talks to the room next to it.

```
Frontend (React, port 5173)
   │  HTTP + "Authorization: Bearer <JWT>"
   ▼
[ Filters ]      RequestIdFilter → JwtAuthenticationFilter     (security/, util/)
   ▼
[ Controller ]   receives HTTP, reads JSON into a DTO           (controller/)
   ▼
[ Service ]      business rules, transactions, permissions       (service/)
   ▼
[ Repository ]   talks to the database                           (repository/)
   ▼
[ Entity ]       Java object = one row of a table                (entity/)
   ▼
TiDB / MySQL database                                            (db/migration/)
```

The response travels back up. The **Mapper** (`mapper/`) turns the entity into a **response DTO** (`dto/response/`). The controller wraps that in **`ApiResponse`** (`common/`). If anything throws, **`GlobalExceptionHandler`** (`exception/`) builds the error reply.

## 1.2 The actual folder tree

```text
backend/
├── pom.xml                         ← dependencies + build (Maven)
├── Dockerfile                      ← container build
├── dev-secrets.properties          ← local secrets, imported by application-dev.yml
├── uploads/                        ← uploaded files land here (LocalFileStorageServiceImpl)
├── exports/                        ← generated CSV/PDF export files
└── src/
    ├── main/
    │   ├── java/com/templeregistry/
    │   │   ├── TempleRegistryApplication.java   ← main() — the starting point
    │   │   ├── common/          (2)   ApiResponse, PaginatedResponse
    │   │   ├── config/          (9)   Security, CORS, Async, Cache, Flyway, JPA audit, OpenAPI, TOTP
    │   │   ├── security/        (10)  JWT filter, guards, roles, DACVM aspect
    │   │   ├── controller/      (40)  REST endpoints, grouped by module
    │   │   │   ├── admin/ auditor/ auth/ contractor/ dc/ declaration/ document/
    │   │   │   ├── employee/ export/ geo/ governance/ notice/ notification/
    │   │   │   └── observation/ ta/ temple/ trust/ viewer/ TempleTimelineController
    │   │   ├── dto/             (189)
    │   │   │   ├── request/<module>/    ← JSON coming IN
    │   │   │   └── response/<module>/   ← JSON going OUT
    │   │   ├── service/         (155)
    │   │   │   ├── <module>/            ← INTERFACES (the contract)
    │   │   │   ├── impl/<module>/       ← IMPLEMENTATIONS (the real code)
    │   │   │   ├── workflow/            ← the governance WorkflowEngine + policies
    │   │   │   ├── governance/          ← status resolver, edit guard, visibility policy
    │   │   │   ├── clarification/       ← unified DC↔TA clarification engine
    │   │   │   └── notification/        ← router, email outbox, SSE
    │   │   ├── repository/      (65)  Spring Data JPA interfaces, per module
    │   │   ├── entity/          (109) JPA entities + enums + converters, per module
    │   │   │   └── base/BaseEntity.java  ← shared id/audit/soft-delete columns
    │   │   ├── mapper/          (5)   MapStruct: declaration, geo, notice, temple, trust
    │   │   ├── event/           (48)  Spring application events (domain events)
    │   │   ├── exception/       (20)  custom exceptions + GlobalExceptionHandler
    │   │   ├── validation/      (2)   custom @ValidFinancialYear annotation
    │   │   └── util/            (11)  encryption, HMAC, ack numbers, request-id filter, PDF
    │   └── resources/
    │       ├── application.yml, application-dev.yml, application-notification-example.yml
    │       ├── logback-spring.xml
    │       ├── db/migration/     V1 … V113 (Flyway)
    │       ├── db/seeds/         extra seed SQL (not run by Flyway — wrong folder)
    │       ├── keys/             JWT RSA keys
    │       └── templates/email/  24 Thymeleaf email templates
    └── test/
        ├── java/com/templeregistry/  controller/ service/ security/ integration/ property/ util/ ...
        └── resources/                application-test.yml (H2), jwt-test.pub
```

A note on `db/seeds/`: Flyway only reads `classpath:db/migration` by default, and I found no config pointing it at `db/seeds`. So those files look like **manual** scripts. I'll confirm this in Chapter 4.

## 1.3 Each folder explained

### `TempleRegistryApplication.java` — the ignition key
```java
@SpringBootApplication   // "scan everything under com.templeregistry and auto-configure"
@EnableAsync             // makes @Async methods run on background threads
@EnableScheduling        // makes @Scheduled methods run on timers
public class TempleRegistryApplication { main() → SpringApplication.run(...) }
```
The class sits in the **root package** `com.templeregistry`, so Spring finds every class in every sub-package. Chapter 2 traces this step by step.

### `common/` — the envelope every response goes in
> **This folder contains** the standard response wrapper. **Its job is** to give every API the same JSON shape. **Controllers use it because** the frontend always wants to read `success`, `message` and `data` in the same way.

```text
File:     ApiResponse.java
Purpose:  Generic wrapper: { success, message, data, errorCode, errors, timestamp, requestId }
Used by:  Every controller (success) and GlobalExceptionHandler + JwtAuthenticationFilter (error)
Uses:     MDC "requestId" (put there by RequestIdFilter) → so every response carries its log id
Key:      static factories success(msg, data), error(msg, code), validationError(msg, errors)
```
Beginner note: `@JsonInclude(NON_NULL)` means *null fields are left out of the JSON*. A success response therefore has no `errorCode` key at all.

`PaginatedResponse<T>` takes Spring's `Page<T>` (one "page" of results plus the total count) and flattens it to `{content, page, size, totalElements, totalPages, last}`.

### `config/` — the settings room
> **This folder contains** `@Configuration` classes. A configuration class is a factory that tells Spring *"create these objects (beans) and set them up this way."*

| File | What it creates / controls |
|---|---|
| `SecurityConfig` | The **security filter chain**: public URLs, stateless sessions, the JWT filter, BCrypt(12) password encoder, and a 401 response for missing tokens |
| `CorsConfig` | Which frontend origins may call the API (localhost:5173/5174 and the Vercel site) |
| `AsyncConfig` | Two thread pools: `taskExecutor` (general, 4–10 threads) and `exportExecutor` (2–5 threads, queue of 10, **rejects** when full so the caller can answer HTTP 503) |
| `CacheConfig` | Caffeine cache `dacvmPolicies`: 5-minute expiry, 10k entries |
| `FlywayConfig` | Runs `repair()` before `migrate()` when `app.flyway.auto-repair=true` (dev only) |
| `JpaAuditConfig` | Tells JPA **who** the current user is, so `created_by`/`updated_by` get filled automatically (0 = system) |
| `OpenApiConfig` | Swagger title, plus an "Authorize: Bearer" button |
| `TotpConfig` | Beans for generating and verifying MFA codes |
| `AwsConfig` | Empty leftover |

### `security/` — the security guards
> **This folder contains** everything that decides *"who are you?"* (authentication) and *"are you allowed?"* (authorization).

| File | Job in one line |
|---|---|
| `JwtAuthenticationFilter` | Runs on **every** request. Reads the `Bearer` token, validates it, and puts the user into the `SecurityContext` |
| `ScopeHelper` | Parses and verifies the RS256 JWT and returns a `Claims` record (userId, role, districtId, templeId…) |
| `UserDetailsServiceImpl` | Loads a `User` from the DB by username. Used **only during login** by Spring's `DaoAuthenticationProvider` |
| `RoleConstants` | Role names plus ready-made `@PreAuthorize` expressions such as `ADMIN_ONLY` and `CAN_ACT_DC` |
| `JurisdictionGuard` | "A DC can only touch temples **in their own district**" |
| `OwnershipGuard` | "A TA can only touch **their own temple**" |
| `AccessGuard` | "A TA with access type VIEW cannot edit" |
| `TokenRevocationGuard` | Blocks revoked refresh tokens |
| `DacvmGuard` + `PolicyEnforcementAspect` | A custom annotation plus an AOP interceptor. Service methods marked `@DacvmGuard` are checked against **DB-stored access policies** (the admin-editable "access control" module) |

Chapter 3 is entirely about how these fit together.

### `controller/` — the reception desks
> **This folder contains** the 40 `@RestController` classes. **Their job is** to receive HTTP, turn the JSON into a DTO, call **one** service method, and wrap the answer in `ApiResponse`. They should contain **no business logic**.

They are grouped by **who uses them**:
- `auth/` — anyone (login, registration, own profile)
- `ta/`, `temple/`, `trust/`, `declaration/` — mostly Temple Authority
- `dc/` — District Collector's screens (10 controllers)
- `governance/` — workflow actions (v1 and **v2**; v2 is the newer unified engine)
- `admin/`, `auditor/`, `viewer/` — role dashboards
- `notification/`, `notice/`, `document/`, `export/`, `geo/`, `employee/`, `contractor/`, `observation/`

### `dto/` — the forms
> A **DTO** (Data Transfer Object) is a plain class shaped exactly like the JSON the frontend sends or receives.

- `dto/request/...` — what comes **in**, for example `LoginRequest`, `CreateDeclarationRequest`. Validation annotations (`@NotBlank` and so on) sit here.
- `dto/response/...` — what goes **out**, for example `TempleResponse`, `AuthTokenResponse`.

**Why not send the entity directly?** Entities contain things the client must never see: password hash, encrypted Aadhaar, soft-delete flags. They also have lazy-loaded relationships that would crash JSON serialization or leak whole object graphs. The DTO is a **safe, controlled copy**. (Chapter 8.)

### `service/` — the managers (the heart of the app)
> **This folder contains** the business logic: *"is this action allowed right now? what changes? who gets notified?"*

The pattern used everywhere is **interface + implementation**:
```
service/geo/GeoService.java            ← interface: WHAT the service offers
service/impl/geo/GeoServiceImpl.java   ← @Service class: HOW it does it
```
The controller depends on the **interface**. Spring injects the implementation. That is why the `impl/` folder mirrors the module folders.

Four sub-packages are **not** plain CRUD. They are the "engine room":

| Package | What it is |
|---|---|
| `service/workflow/` | **`WorkflowEngine`**: the single entry point for all status changes (submit, approve, reject…). The `TransitionRuleRegistry` holds rules of the form `(entityType, fromStatus, action) → toStatus`. `WorkflowPolicy` beans in `policy/` add business vetoes, for example `SiteVisitBlocksApprovalPolicy`. `VersionService` takes JSON snapshots |
| `service/governance/` | `GovernanceWorkflowService` (Trust + Declaration workflows), `GovernanceStatusResolver` (reads the "true" status from `WorkflowInstance`), `TempleVisibilityPolicy` |
| `service/clarification/` | `ClarificationEngine`: one engine for DC↔TA "please clarify" threads across all modules |
| `service/notification/` | `NotificationRouter` (reads `NotificationRule`s from the DB to decide who gets what), `EmailDeliveryService` (DB outbox + retries), `SseNotificationService` (live push) |

### `repository/` — the database clerks
> **This folder contains** interfaces that extend `JpaRepository<Entity, Long>`. **You write no SQL for basic operations.** Spring generates the code at startup.

The repositories in this project use three styles. All three appear in [DistrictRepository.java](../backend/src/main/java/com/templeregistry/repository/geo/DistrictRepository.java):
```java
List<District> findAllByCityId(Long cityId);          // 1. derived from the method NAME
List<District> findAllByCityStateId(Long stateId);    //    → joins city → state automatically

@Query("SELECT d.city.id FROM District d WHERE d.id = :id")   // 2. JPQL (uses Java names, not tables)
Optional<Long> findCityIdById(@Param("id") Long id);
```
A third style, **Specification** (dynamic filters), appears in `repository/notice/NoticeSpecification.java`.

Beginner note: `Optional<Long>` means *"maybe a value, maybe nothing"*. It forces the caller to handle "not found" explicitly, usually with `.orElseThrow(...)`.

### `entity/` — the database's reflection in Java
> An **`@Entity`** is a Java class where **one object = one row** in a table. Hibernate reads the annotations and knows how to turn the object into `INSERT`/`UPDATE`/`SELECT` statements.

Almost every entity extends **`BaseEntity`**, which gives each table the same six columns:

```java
@MappedSuperclass                       // "not a table itself — copy my columns into child tables"
@EntityListeners(AuditingEntityListener.class)  // lets @CreatedBy / @LastModifiedBy work
public abstract class BaseEntity {
    @Id @GeneratedValue(strategy = IDENTITY) Long id;   // MySQL AUTO_INCREMENT
    boolean deleted = false;              // column is_deleted → SOFT delete
    LocalDateTime createdAt, updatedAt;   // set by @PrePersist / @PreUpdate
    @CreatedBy Long createdBy;            // filled from JpaAuditConfig.auditorProvider()
    @LastModifiedBy Long updatedBy;
}
```

The entities also use **soft delete**. Look at [District.java](../backend/src/main/java/com/templeregistry/entity/geo/District.java):
```java
@SQLRestriction("is_deleted = false")    // every SELECT silently adds "AND is_deleted = false"
@SQLDelete(sql = "UPDATE districts SET is_deleted = true ... WHERE id = ?")
                                         // repository.delete(x) runs this UPDATE, not a DELETE
```
So in this app, "delete" almost never removes a row. It hides it. Chapter 4 goes deep on this.

Besides entities, `entity/` holds **enums** (such as `DeclarationStatus` and `UserRole`) and **`AttributeConverter`s** (such as `DeclarationStatusConverter`). A converter translates between the Java enum and the string stored in the DB.

### `mapper/` — the translators
> MapStruct **writes the conversion code for you at compile time**.

```java
@Mapper(componentModel = "spring")          // generated class becomes a Spring bean
public interface GeoMapper {
    @Mapping(target = "cityId", source = "city.id")   // flatten district.city.id → cityId
    DistrictResponse toDistrictResponse(District entity);
}
```
When you run `mvn compile`, MapStruct generates `GeoMapperImpl` under `target/generated-sources` with plain getters and setters. Only **5** mappers exist. Many services build DTOs **by hand** with `.builder()` instead, so don't expect a mapper for every module. (Chapter 8.)

### `event/` — the office announcement system
> **This folder contains** event classes. A service can **announce** "something happened" (`publisher.publishEvent(...)`) without knowing who is listening. Listeners elsewhere react.

- **Newer design:** `event/workflow/GovernanceDomainEvent` (a Java `record`) is the *one* event the `WorkflowEngine` emits after every transition. `NotificationRouter` and `GovernanceDomainEventTimelineListener` listen with `@TransactionalEventListener(AFTER_COMMIT)`, which means *"only react if the DB save actually succeeded."*
- **Older design:** ~45 module-specific events in `event/board`, `event/declaration`, `event/temple`, etc., all extending `BaseNotificationEvent`. The Javadoc on `GovernanceDomainEvent` says it *replaces* these. Chapter 10 will show which ones are still published.

### `exception/` — the complaint desk
- **19 custom exceptions**, for example `EntityNotFoundException`, `JurisdictionAccessDeniedException`, `InvalidStateTransitionException`, `RateLimitExceededException`, `AccountLockedException`.
- **`GlobalExceptionHandler`**: a `@RestControllerAdvice` that catches them and turns each into the right HTTP status plus an `ApiResponse.error(...)`. (Chapter 9.)

### `validation/` — a custom rule
`@ValidFinancialYear` is a home-made validation annotation, like `@NotBlank` but custom. `ValidFinancialYearValidator` checks the value, probably the `2025-26` format — I'll confirm the exact rule in Chapter 8. It delegates to `FinancialYearValidationService`.

### `util/` — the toolbox
| File | Why it exists |
|---|---|
| `RequestIdFilter` | `@Order(1)` servlet filter. Gives every request an ID (from the `X-Request-ID` header or a new UUID), stores it in the logging MDC, and returns it in `ApiResponse.requestId` |
| `AesEncryptionConverter` | JPA converter that **encrypts PII** (Aadhaar, PAN, bank account) with AES-256-GCM before storing it, and decrypts it on read |
| `HmacUtil` | Deterministic hash of Aadhaar, so duplicates can be detected even though the encrypted value is random each time |
| `AcknowledgementNumberGenerator` | Sequential DB-backed receipt numbers per financial year |
| `TemporaryPasswordGenerator` | Random first-login passwords for admin-created users |
| `PaginationUtil` | Small helper for building `Pageable` |
| `pdf/` | iText PDF layouts for acknowledgements and export reports |
| `StatusTransitionValidator*` | Deprecated shims, see 0.7 |

### `resources/`
| File / folder | Role |
|---|---|
| `application.yml` | Base config: port 8080, TiDB datasource, Hikari pool (max 8), `ddl-auto: update`, Flyway on, mail **off**, JWT key paths, 2-hour access token, CORS origins |
| `application-dev.yml` | Active by default (`spring.profiles.default: dev`). Imports `dev-secrets.properties`, turns **mail on**, enables Flyway auto-repair |
| `db/migration/` | Schema history, `V1__initial_schema.sql` → `V113__...` |
| `templates/email/` | Thymeleaf HTML, selected by `template_key` in the `notification_rules` table |
| `keys/` | RSA key pair for signing and verifying JWTs |

## 1.4 How the folders connect: one real walkthrough

Let's watch the folders cooperate on the simplest real endpoint in the codebase, **`GET /api/v1/geo/states/{stateId}/districts`**. The frontend uses it to fill a "District" dropdown.

```text
Browser
  │  GET /api/v1/geo/states/1/districts
  ▼
util/RequestIdFilter            → requestId = UUID, put in MDC
  ▼
security/JwtAuthenticationFilter→ token optional here (…/geo/** is in PUBLIC_PATHS)
  ▼
config/SecurityConfig rules     → "/api/v1/geo/**" permitAll → let it through
  ▼
controller/geo/GeoController.listDistrictsByState(@PathVariable Long stateId = 1)
  ▼
service/geo/GeoService (interface)  →  service/impl/geo/GeoServiceImpl.listDistrictsByState(1)
  │   @Transactional(readOnly = true) → opens a read-only DB transaction
  ▼
repository/geo/DistrictRepository.findAllByCityStateId(1)
  │   Spring reads the method NAME: District → city → state → id = ?
  ▼
entity/geo/District  (+ @SQLRestriction adds is_deleted = false)
  │   SQL, roughly:
  │   SELECT d.* FROM districts d JOIN cities c ON d.city_id = c.id
  │   WHERE c.state_id = 1 AND d.is_deleted = false
  ▼
List<District>
  ▼
mapper/geo/GeoMapper.toDistrictResponse(each)  → DistrictResponse{id, cityId, name, code}
  ▼  (transaction closes)
GeoController wraps: ApiResponse.success("Districts retrieved.", list)
  ▼
HTTP 200
{ "success": true, "message": "Districts retrieved.",
  "data": [ { "id": 5, "cityId": 2, "name": "Bengaluru Urban", "code": "BLR" } ],
  "timestamp": "...", "requestId": "..." }
```
(The values inside `data` are just an example.)

The **write** version shows one more folder. `POST /api/v1/geo/districts` goes to `GeoServiceImpl.createDistrict`:

```java
@PreAuthorize(RoleConstants.ADMIN_ONLY)      // security/ — only SUPER_ADMIN
@Transactional                               // read-write transaction
public DistrictResponse createDistrict(CreateDistrictRequest request) {
    City city = cityRepository.findById(request.getCityId())          // SELECT city
            .orElseThrow(() -> new EntityNotFoundException("City", request.getCityId())); // exception/ → 404
    District d = District.builder().city(city).name(request.getName()).code(request.getCode()).build();
    return geoMapper.toDistrictResponse(districtRepository.save(d));  // INSERT, then map
}
```
Line by line:
- `@PreAuthorize(...)` — before the method runs, Spring checks the logged-in user has role `SUPER_ADMIN`. It appears on **both** the controller method and the service method in this module.
- `@Transactional` — "everything in this method is one all-or-nothing DB unit."
- `findById(...)` returns `Optional<City>`. `.orElseThrow` says "if there is no such city, stop and throw." `GlobalExceptionHandler` then turns the exception into an error response.
- `District.builder()...build()` — Lombok's `@SuperBuilder` generated this builder.
- `save(d)` — Hibernate runs `INSERT INTO districts (...)`. `BaseEntity` fills `created_at`/`updated_at` through `@PrePersist`, and `created_by` through `JpaAuditConfig`.

One detail to note: `/api/v1/geo/**` is fully **public** in `SecurityConfig`, so the *only* thing protecting this POST is `@PreAuthorize`. It works, but it means the URL rule and the method rule disagree. We'll check exactly what an anonymous POST returns in Chapter 3.

## 1.5 The five questions, answered for each layer

| Layer | Why does it exist? | Who calls me? | Who do I call? | Data in | Data out |
|---|---|---|---|---|---|
| **Filter** (`JwtAuthenticationFilter`) | Identify the user once, for every request | Tomcat / Spring Security | `ScopeHelper` | Raw HTTP request + header | `SecurityContext` filled with `Claims` |
| **Controller** (`GeoController`) | Translate HTTP ↔ Java | Spring MVC `DispatcherServlet` | One service interface | Path vars, query params, request DTO | `ResponseEntity<ApiResponse<ResponseDTO>>` |
| **Service** (`GeoServiceImpl`) | Business rules, transactions, permission checks | Controllers (and other services) | Repositories, mappers, guards, other services, event publisher | Request DTO / ids | Response DTO (or throws) |
| **Repository** (`DistrictRepository`) | Hide SQL | Services only | Hibernate (generated code) | ids / filter values | Entities, `Optional`, `Page` |
| **Entity** (`District`) | Mirror a table row | Repositories / Hibernate | — | DB row | Java object |
| **Mapper** (`GeoMapper`) | Entity → DTO without hand-written code | Services | — | Entity | Response DTO |
| **ExceptionHandler** | One place that decides how errors look | Spring, when anything throws | `ApiResponse.error` | Exception | Error JSON + HTTP status |

## 1.6 "If I add a feature, where do I touch?" (first version)

Say you add a **new module** "X":
1. `db/migration/V114__create_x.sql` — the table
2. `entity/x/X.java` — `extends BaseEntity`, plus `@SQLRestriction`/`@SQLDelete` like the others
3. `repository/x/XRepository.java` — `extends JpaRepository<X, Long>`
4. `dto/request/x/CreateXRequest.java` and `dto/response/x/XResponse.java`
5. `mapper/x/XMapper.java` (optional; many modules use builders instead)
6. `service/x/XService.java` + `service/impl/x/XServiceImpl.java` — `@Transactional`, `@PreAuthorize(RoleConstants...)`, and `JurisdictionGuard`/`OwnershipGuard` if the data is district- or temple-scoped
7. `controller/x/XController.java` — `@RequestMapping("/api/v1/x")`, return `ApiResponse`
8. If X has approve/reject steps: **don't** write your own status logic. Add rules to `TransitionRuleRegistry` and go through `WorkflowEngine` (Chapter 10).

---

**Next: Chapter 2 — Application Startup Flow.** It follows `main()` → component scan → `SecurityConfig` / `AsyncConfig` / `FlywayConfig` beans → Hikari pool to TiDB → Flyway migrations → Hibernate `ddl-auto: update` → JPA repositories → the filter chain → `ApplicationReadyEvent` listeners (`TrustDataRepairService`, `EmailStartupValidator`) → schedulers starting.

Say **"continue"** to get it. If you'd rather jump straight to Security (Chapter 3), tell me.
