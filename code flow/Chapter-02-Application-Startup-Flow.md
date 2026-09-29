# CHAPTER 2 — Application Startup Flow

What really happens between typing `mvn spring-boot:run` (or `java -jar app.jar` in the [Dockerfile](../backend/Dockerfile)) and the log line `Started TempleRegistryApplication`?

## 2.0 The whole startup on one page

```text
java -jar app.jar
   │
   ▼
① TempleRegistryApplication.main()  →  SpringApplication.run(...)
   │
   ▼
② ENVIRONMENT: read settings
   application.yml ──► profile "dev" (default) ──► application-dev.yml ──► dev-secrets.properties
   + environment variables (DB_PASSWORD, SMTP_USERNAME, PORT, …) override the ${...} placeholders
   │
   ▼
③ COMPONENT SCAN of com.templeregistry.**
   finds 39 @RestController, 68 @Service, 32 @Component, 8 @Configuration,
   64 repository interfaces, MapStruct *Impl classes, 1 @Aspect
   │
   ▼
④ BEAN CREATION (dependency injection, in dependency order)
   ├─ keys:     ScopeHelper (public key) · JwtServiceImpl (private+public key)
   │            AesEncryptionConverter (app.encryption.key) · HmacUtil (app.hmac.key)
   ├─ DB:       HikariDataSource ──► TiDB Cloud (MySQL protocol, port 4000)
   ├─ Flyway:   FlywayConfig.repairThenMigrate()  → repair() (dev) → migrate() V1…V113
   ├─ JPA:      Hibernate scans 67 @Entity → ddl-auto=update → repository proxies
   │            JpaAuditConfig.auditorProvider() for created_by / updated_by
   ├─ Security: SecurityConfig.securityFilterChain() · BCrypt(12) · DaoAuthenticationProvider
   │            @EnableMethodSecurity → proxies for 307 @PreAuthorize usages
   ├─ AOP:      proxies for @Transactional (326 usages), @Async, @Cacheable, @DacvmGuard aspect
   ├─ Infra:    AsyncConfig (taskExecutor, exportExecutor) · CacheConfig (Caffeine)
   │            TotpConfig · OpenApiConfig · CorsConfig
   └─ Web:      DispatcherServlet maps every @GetMapping/@PostMapping… to a controller method
   │
   ▼
⑤ Embedded Tomcat listens on port ${PORT:8080}
   │
   ▼
⑥ ApplicationReadyEvent
   ├─ TrustDataRepairService.repairLiveTrustData()   (@Async — runs in background)
   └─ EmailStartupValidator.onApplicationEvent()     (checks email templates exist)
   │
   ▼
⑦ @Scheduled jobs start ticking (NotificationRouter every 5 s, EmailDeliveryService every 10 s, …)
   │
   ▼
READY — first HTTP request can arrive
```

Now each step, slowly.

---

## Step ① `main()` and the three annotations

[TempleRegistryApplication.java](../backend/src/main/java/com/templeregistry/TempleRegistryApplication.java):

```java
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class TempleRegistryApplication {
    public static void main(String[] args) {
        SpringApplication.run(TempleRegistryApplication.class, args);
    }
}
```

**In simple words first.** `main()` is an ordinary Java entry point. `SpringApplication.run(...)` says: *"Spring, take over. Build the whole application, start a web server, and keep running."* It creates the **ApplicationContext**, a big container that holds every object Spring manages. Each such object is called a **bean**.

**The annotations:**

| Annotation | Simple meaning | What it does *here* |
|---|---|---|
| `@SpringBootApplication` | A shortcut for three annotations: `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan` | **ComponentScan**: because this class is in `com.templeregistry`, Spring searches *every* sub-package (`controller`, `service`, `security`…). **AutoConfiguration**: Spring Boot sees jars on the classpath (Hibernate, MySQL driver, Flyway, Tomcat, Thymeleaf, Spring Security, Actuator) and configures each one automatically |
| `@EnableAsync` | "Methods marked `@Async` should run on a background thread." | Without it, `AuditServiceImpl`, `EmailServiceImpl`, `AsyncExportBean`, `NotificationRouter`… would all run **synchronously** and slow down requests. The thread pools come from `AsyncConfig` |
| `@EnableScheduling` | "Methods marked `@Scheduled` should run on a timer." | Turns on `OverdueWorkflowScheduler`, `NoticeExpiryScheduler`, `EmailDeliveryService`, `NotificationRouter`, `EmailRetryScheduler` |

> **Beginner note — what is auto-configuration?** You never wrote a class that creates a `DataSource`, an `EntityManagerFactory`, a Tomcat server or a `JavaMailSender`. Spring Boot has built-in configuration classes that say *"if the MySQL driver is present and `spring.datasource.url` is set, create a Hikari connection pool."* You only supply **properties** in `application.yml`. Your own `@Configuration` classes are for the parts auto-config can't guess, such as security rules.

---

## Step ② Loading configuration (the Environment)

Before creating any object, Spring reads the settings. The order in *this* project:

```text
1. application.yml                     (always loaded)
      spring.profiles.default: dev      ← "if nobody picks a profile, use dev"
2. application-dev.yml                 (because profile = dev)
      spring.config.import: optional:file:./dev-secrets.properties
3. ./dev-secrets.properties            (file in backend/ — "optional:" = don't crash if missing)
4. OS environment variables            (highest priority of these)
```

Later sources **override** earlier ones. A placeholder like this means *"use env var `DB_PASSWORD`; if it's not set, use the text after the colon"*:

```yaml
password: ${DB_PASSWORD:<fallback>}
```

The two files differ in an important way:
- `application.yml` has a hard-coded fallback: the real DB password I flagged in Chapter 0.
- `application-dev.yml` uses `${DB_USERNAME}` with **no** fallback, so in dev the value must come from `dev-secrets.properties` or an env var.

Because dev loads after the base file, the dev value wins.

**To run with a different profile:** `java -jar app.jar --spring.profiles.active=prod`. There is no `application-prod.yml`, so prod would use only `application.yml` plus env vars. [logback-spring.xml](../backend/src/main/resources/logback-spring.xml) switches to **JSON logs** for `prod,staging` and readable logs for `dev`.

### Properties and who actually reads them

I grepped for every `@Value("${...}")`. Not every property in the YAML is used:

| Property | Read by | Effect if you change it |
|---|---|---|
| `server.port` / `PORT` | Spring Boot (Tomcat) | Port the API listens on |
| `spring.datasource.*` | Spring Boot → Hikari | Which DB it connects to |
| `spring.jpa.hibernate.ddl-auto: update` | Hibernate | Whether Hibernate alters tables (Step ④c) |
| `spring.flyway.enabled`, `validate-on-migrate` | Spring Boot Flyway auto-config | Whether migrations run, and whether checksums are checked |
| `app.flyway.auto-repair` (dev: `true`) | `FlywayConfig` | Runs `flyway.repair()` before migrating |
| `app.jwt.private-key-path` / `public-key-path` | `JwtServiceImpl`, `ScopeHelper` | Which RSA keys sign and verify tokens |
| `app.jwt.access-token-expiry-ms: 7200000` | `JwtServiceImpl` | Access-token lifetime = **2 hours** (code default if missing: 15 min) |
| `app.jwt.refresh-token-expiry-days` | `AuthServiceImpl` (default **7**) | Not in YAML, so 7 days is used |
| `app.cors.allowed-origins` | `CorsConfig` | Which frontends may call the API |
| `app.encryption.key` | `AesEncryptionConverter` | AES key for Aadhaar/PAN/bank columns. **Changing it makes existing encrypted data unreadable** |
| `app.hmac.key` | `HmacUtil` | Aadhaar duplicate-check hash. Changing it breaks duplicate detection for old rows |
| `app.storage.base-dir` | `LocalFileStorageServiceImpl` | Where uploads are saved (`./uploads`) |
| `trm.export.base-dir` | `DcExportController`, `DcExportServiceImpl`, `AsyncExportBean` | Where exports go (`./exports`) |
| `app.base-url` | `AuthServiceImpl`, `AdminServiceImpl`, `EmailServiceImpl` | Links inside emails (reset-password link, login link) |
| `spring.mail.enabled` | `EmailServiceImpl` (`@Value(... :false)`) | **Custom flag**, not a Spring Boot one. `false` in base, `true` in dev. Decides whether emails really go out |
| `spring.mail.from` | `EmailServiceImpl` | Sender address |
| `email.startup-validation.fail-fast` | `EmailStartupValidator` | `true` = refuse to start if an email template is missing |
| `app.legacy.enabled: false` | `@ConditionalOnProperty` on `StatusTransitionValidatorCompat` | `false` = that bean is **not created** |

These look like settings but, as far as I can tell, **nothing reads them**:
- `app.notification.email-enabled`: no code references it. The real switch is `spring.mail.enabled`.
- `spring.flyway.repair-on-migrate`: I don't believe Spring Boot recognizes this key. The repair you actually get comes from `FlywayConfig` + `app.flyway.auto-repair`.
- `MySQLContainerBase` (tests) sets `app.encryption.aes-key` and `cloud.aws.*`, but the code reads `app.encryption.key`, and AWS is gone. So those test overrides do nothing.

---

## Step ③ Component scanning: how Spring finds your classes

**Simple words:** Spring walks through every `.class` file under `com.templeregistry` and looks for "stereotype" annotations. Each one means *"please create one object of me and manage it."*

| Annotation | Meaning | Count here | Examples |
|---|---|---|---|
| `@RestController` | A web controller whose return values become JSON | 39 | `GeoController`, `AuthController` |
| `@Service` | A business-logic bean (same effect as `@Component`, different label) | 68 | `GeoServiceImpl`, `TrustDataRepairService` |
| `@Component` | A generic bean | 32 | `JwtAuthenticationFilter`, `ScopeHelper`, `JurisdictionGuard`, `RequestIdFilter`, `HmacUtil` |
| `@Configuration` | A class whose `@Bean` methods create beans | 8 | `SecurityConfig`, `AsyncConfig` |
| `@Aspect` (+`@Component`) | An AOP interceptor | 1 | `PolicyEnforcementAspect` |
| `@Repository` / `extends JpaRepository` | Data-access interface; Spring generates the class | 64 | `DistrictRepository` |
| `@Mapper(componentModel="spring")` | MapStruct generates `XxxMapperImpl` annotated `@Component` | 5 | `GeoMapper` → `GeoMapperImpl` |

Classes **without** these annotations are ignored. That is why the deprecated `OverdueScheduler` (no `@Component`) never runs, even though it has a `@Scheduled` method. The same goes for `AwsConfig` (package-private, no annotation) and every DTO and entity (entities are found separately by JPA, Step ④c).

> **Two classes named `ActionContextResolver`?** Two beans can't both be called `actionContextResolver`. That is why the controller one is annotated `@Component("controllerActionContextResolver")`: it gives the bean a different name so startup doesn't fail with a name clash.

---

## Step ④ Bean creation and dependency injection

### What is dependency injection? (the most important Spring idea)

Look at [GeoServiceImpl](../backend/src/main/java/com/templeregistry/service/impl/geo/GeoServiceImpl.java):

```java
@Service
@RequiredArgsConstructor          // Lombok writes a constructor for every `final` field
public class GeoServiceImpl implements GeoService {
    private final StateRepository    stateRepository;
    private final DistrictRepository districtRepository;
    private final GeoMapper          geoMapper;
    ...
}
```

`GeoServiceImpl` **never writes `new DistrictRepository()`**. It just declares *"I need these."* Lombok generates:

```java
public GeoServiceImpl(StateRepository s, ..., DistrictRepository d, GeoMapper m) { ... }
```

When Spring creates `GeoServiceImpl`, it sees that constructor, finds (or first creates) beans of those types, and **passes them in**. That's **dependency injection (DI)**: objects receive their collaborators instead of building them.

This project uses **constructor injection everywhere** (`@RequiredArgsConstructor` + `final` fields). I didn't see field-level `@Autowired` in the classes I read. That is the recommended style: fields can't be null, and tests can pass mocks straight into the constructor.

### The order is decided by the dependencies

Spring builds a graph and creates leaves first. A real chain from this project:

```text
SecurityConfig                          needs → JwtAuthenticationFilter, UserDetailsService
  └─ JwtAuthenticationFilter            needs → ScopeHelper
       └─ ScopeHelper(@Value("${app.jwt.public-key-path}") Resource)
            └─ reads classpath:keys/jwt-public.pem → RSAPublicKey
  └─ UserDetailsServiceImpl             needs → UserRepository
       └─ UserRepository                needs → EntityManagerFactory (JPA) → DataSource → Flyway done
```

So if `jwt-public.pem` is missing or broken, the `ScopeHelper` constructor throws, `JwtAuthenticationFilter` can't be built, `SecurityConfig` can't be built, and **the app refuses to start**. That fail-fast behaviour is useful: you learn about a broken key at boot, not on the first login.

### ④a Key-holding beans (they load secrets in their constructors)

```java
// ScopeHelper — verification only (public key)
public ScopeHelper(@Value("${app.jwt.public-key-path}") Resource publicKeyResource) throws Exception {
    this.publicKey = loadPublicKey(publicKeyResource);
}
```
- `@Value("${app.jwt.public-key-path}")` → Spring puts the property value `classpath:keys/jwt-public.pem` here.
- The parameter type is `Resource`, so Spring turns the string `classpath:...` into a file handle inside the jar.
- `loadPublicKey` removes the `-----BEGIN PUBLIC KEY-----` lines, Base64-decodes the rest, and builds an `RSAPublicKey`.

`JwtServiceImpl` does the same with **both** keys. It needs the private key to **sign** tokens at login. `AesEncryptionConverter` and `HmacUtil` receive `app.encryption.key` and `app.hmac.key` in their constructors the same way.

> **Why two classes read the public key.** The Javadoc on `ScopeHelper` says: *"Used only by the filter; the full JwtService (sign + verify) lives in the auth module."* The filter (security layer) only needs to **check** tokens. The auth service **creates** them. Chapter 3 explores this split.

### ④b DataSource: the connection pool to TiDB

Spring Boot auto-config sees `spring.datasource.url` and creates a **HikariCP** pool:

```yaml
url: jdbc:mysql://gateway01.ap-southeast-1.prod.aws.tidbcloud.com:4000/test?useSSL=true...
hikari:
  minimum-idle: 2          # keep 2 connections open even when idle
  maximum-pool-size: 8     # at most 8 queries can run at the same moment
  keepalive-time: 120000   # ping every 2 min so TiDB's gateway doesn't drop idle connections
  max-lifetime: 540000     # recycle connections after 9 min
  connection-init-sql: "SET tidb_enable_noop_functions=1"   # TiDB-specific: accept MySQL functions TiDB only fakes
```

**Simple words:** opening a DB connection is slow, so Hikari opens a few at startup and **lends** them to each transaction. `maximum-pool-size: 8` is small. If 8 requests hold transactions at once, the 9th waits up to `connection-timeout: 20000` ms (20 s) and then fails. Remember this when we look at `@Async` work, because background threads also borrow from this same pool.

A small detail: the database name in the URL is `test`.

### ④c Flyway runs **before** Hibernate

Spring Boot guarantees this order: *Flyway finishes migrating before the JPA `EntityManagerFactory` is created.* Your [FlywayConfig](../backend/src/main/java/com/templeregistry/config/FlywayConfig.java) replaces the default "just migrate" behaviour:

```java
@Bean
public FlywayMigrationStrategy repairThenMigrate() {
    return flyway -> {
        if (autoRepair) {          // app.flyway.auto-repair — true in application-dev.yml
            log.warn("[FlywayConfig] Auto-repair is ENABLED — only safe in dev/test environments.");
            flyway.repair();       // fix the history table: remove failed entries, realign checksums
        }
        flyway.migrate();          // run every V*.sql not yet recorded, in version order
    };
}
```

- `FlywayMigrationStrategy` is a Spring Boot hook: *"when it's time to migrate, call my code instead."*
- Flyway keeps a table `flyway_schema_history` listing every migration already applied. On each start it compares that table with the files in `db/migration`, and runs only the new ones, **in numeric order** (V1, V2, … V9, V10, … V113, not alphabetical).
- `V1__initial_schema.sql` creates **61 tables**. Later files add columns, seed data and fixes.
- `validate-on-migrate: false` means Flyway won't complain if someone **edited** an already-applied migration file. Together with `repair()` this is forgiving in dev, but it hides mistakes.

### ④d Hibernate / JPA

Once the schema exists, Spring Boot builds the **EntityManagerFactory** (Hibernate's engine):

1. **Scan entities.** Hibernate finds all 67 `@Entity` classes under `com.templeregistry`. It reads `@Table`, `@Column`, `@ManyToOne`… and builds an in-memory model: "class `District` ↔ table `districts`, field `city` ↔ FK `city_id`".
2. **`ddl-auto: update`.** Hibernate compares that model with the real tables and runs `ALTER TABLE ... ADD COLUMN` / `CREATE TABLE` for anything **missing**. It never drops anything. So **the final schema = Flyway's SQL + whatever Hibernate adds.** In an ideal setup it would be `validate` (as the Testcontainers tests use), so Flyway alone owns the schema. Migrations such as `V111__fix_entity_schema_drift.sql` and `V112__fix_entity_column_type_drift.sql` exist because the two drifted apart.
3. **Converters.** `@Converter` classes such as `AesEncryptionConverter`, `DeclarationStatusConverter` and `PaymentStatusConverter` are registered. `AesEncryptionConverter` is also a `@Component` so it can receive the key through its constructor.
4. **Repositories.** For each interface like `DistrictRepository extends JpaRepository<District, Long>`, Spring Data creates a **proxy class at runtime**. It parses method names (`findAllByCityStateId` → `WHERE city.state.id = ?`) and `@Query` strings **now, at startup**. A misspelled property in a method name, like `findAllByCityStatId`, **fails startup** with "No property 'statId' found". This is another fail-fast safety net.
5. **Auditing.** `@EnableJpaAuditing(auditorAwareRef = "auditorProvider")` in [JpaAuditConfig](../backend/src/main/java/com/templeregistry/config/JpaAuditConfig.java) registers a callback. Whenever an entity with `@CreatedBy`/`@LastModifiedBy` is saved, it calls:

```java
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
if (auth == null || !auth.isAuthenticated()) return Optional.of(0L);        // schedulers, startup jobs
if (auth.getPrincipal() instanceof ScopeHelper.Claims claims) return Optional.ofNullable(claims.userId());
return Optional.of(0L);
```

This is our first sight of `SecurityContextHolder`. **Simple words:** it's a per-thread "sticky note" that says *who is making the current request*. `JwtAuthenticationFilter` writes the note (Chapter 3), and here JPA reads it to fill `created_by`. For background jobs nobody wrote a note, so `0` = "system".

### ④e Security beans

[SecurityConfig](../backend/src/main/java/com/templeregistry/config/SecurityConfig.java) is created at this point:

| Annotation / bean | What it sets up at startup |
|---|---|
| `@EnableWebSecurity` | Registers Spring Security's master filter (`FilterChainProxy`) with Tomcat |
| `@EnableMethodSecurity` | Makes `@PreAuthorize` work: every bean with a `@PreAuthorize` method gets wrapped in a proxy (next section) |
| `securityFilterChain(http)` | The URL rules: `PUBLIC_PATHS` permitAll, `GET /api/v1/temples` permitAll, the rest authenticated; CSRF off; **STATELESS** (no HTTP session, no `JSESSIONID`); 401 entry point; `jwtAuthenticationFilter` inserted *before* `UsernamePasswordAuthenticationFilter` |
| `authenticationProvider()` | `DaoAuthenticationProvider` = "look up the user with `UserDetailsServiceImpl`, compare passwords with BCrypt". Used at **login** only |
| `authenticationManager(...)` | Exposes Spring's `AuthenticationManager` as a bean so `AuthServiceImpl` can call `authenticate(...)` |
| `passwordEncoder()` | `BCryptPasswordEncoder(12)`: strength 12 = 2¹² hashing rounds, roughly 250 ms per check. Slow on purpose, so password guessing is expensive |

### ④f Proxies: the invisible wrappers (read this carefully)

Many annotations in this project **only work because Spring wraps your bean in a proxy**:

```text
Controller calls geoService.createDistrict(rq)
        │
        ▼
┌──────────────── Spring-generated PROXY of GeoServiceImpl ─────────────────┐
│ 1. @PreAuthorize  → check role, else throw AccessDeniedException          │
│ 2. @Transactional → borrow a Hikari connection, BEGIN                     │
│ 3. ──► call the REAL GeoServiceImpl.createDistrict(rq)                    │
│ 4. @Transactional → COMMIT (or ROLLBACK if a RuntimeException escaped)    │
└───────────────────────────────────────────────────────────────────────────┘
```

Annotations handled by proxies in this app:

| Annotation | Enabled by | Wrapper does |
|---|---|---|
| `@Transactional` (326 uses) | Spring Boot JPA auto-config | begin / commit / rollback |
| `@PreAuthorize` (307 uses) | `@EnableMethodSecurity` | role check before the method |
| `@Async` | `@EnableAsync` | hands the call to a thread pool and returns immediately |
| `@Cacheable` / `@CacheEvict` | `@EnableCaching` in `CacheConfig` | returns a cached answer / clears the cache |
| `@DacvmGuard` | `PolicyEnforcementAspect` (`@Aspect`) | checks DB-stored policies |

> **The self-invocation trap.** A proxy only intercepts calls that come **from outside** the bean. If a method inside `ExportServiceImpl` did `this.doExport()`, the call would skip the proxy and `@Async` would silently do nothing. This codebase knows about it. From [AsyncExportBean](../backend/src/main/java/com/templeregistry/service/impl/dc/AsyncExportBean.java):
> *"@Async MUST live on a separate bean, not on ExportServiceImpl itself. Self-invocation (this.doExport()) would bypass Spring's AOP proxy, making @Async a no-op."*
> That is the reason `AsyncExportBean` exists as a separate class.

### ④g Infrastructure beans

- **`AsyncConfig`** creates two thread pools, **started immediately** (`executor.initialize()`):
  - `taskExecutor`: 4 core / 10 max threads, queue 100. Waits up to 30 s on shutdown so emails in flight can finish.
  - `exportExecutor`: 2 / 5 threads, queue 10, `AbortPolicy`. The 16th concurrent export is **rejected**, and the export service turns that into HTTP 503 (`ExportQueueFullException`).
- **`CacheConfig`** creates a `CaffeineCacheManager` with one cache, `dacvmPolicies`. It starts empty and fills on first use.
- **`TotpConfig`** creates the beans used later by `MfaServiceImpl`:
  - `SystemTimeProvider` (current time)
  - `DefaultCodeGenerator` (6-digit codes)
  - `DefaultCodeVerifier` (checks a code against the secret and the time)
  - `DefaultSecretGenerator` (new MFA secrets)
- **`OpenApiConfig`** provides Swagger metadata. `springdoc` then scans all controllers and serves `/v3/api-docs` and `/swagger-ui.html`.
- **`CorsConfig`** builds one CORS configuration **two ways**: a `CorsConfigurationSource` bean (used by Spring Security's `.cors(Customizer.withDefaults())`) and `WebMvcConfigurer.addCorsMappings` (used by Spring MVC). Both hold the same values. The Security one is the one that matters, because it runs first and handles the browser's `OPTIONS` preflight.

### ④h The web layer

Spring MVC's **DispatcherServlet** is created. It inspects every `@RestController` and builds a lookup table:

```text
GET  /api/v1/geo/states                    → GeoController.listStates()
POST /api/v1/geo/districts                 → GeoController.createDistrict(CreateDistrictRequest)
GET  /api/v1/geo/states/{stateId}/districts→ GeoController.listDistrictsByState(Long)
...  (every endpoint of all 39 controllers)
```

Two controllers mapping the **same** method + URL would fail startup with "Ambiguous mapping". `AuthController` and `RegistrationController` both use `@RequestMapping("/api/v1/auth")`, which is fine as long as their method paths differ.

**Actuator** (`spring-boot-starter-actuator`) adds `/actuator/health` and `/actuator/info`, both public in `SecurityConfig`. `management.health.mail.enabled: false` stops the health check from failing just because SMTP is unreachable.

### ④i The servlet filter order

Every HTTP request passes through a chain of servlet **filters** before reaching the DispatcherServlet. Two of your classes are filters **and** `@Component`s, and Spring Boot automatically registers *every* `Filter` bean with Tomcat. From Spring Boot's default ordering, I believe the real order is:

```text
Tomcat
  │
  ├─ Spring Boot's own filters (character encoding, etc.)       order: very high
  ├─ Spring Security FilterChainProxy                           order: -100
  │     ├─ CorsFilter
  │     ├─ ... (logout, etc.)
  │     ├─ JwtAuthenticationFilter   ← added by SecurityConfig.addFilterBefore(...)
  │     ├─ UsernamePasswordAuthenticationFilter (unused: no form login)
  │     ├─ ExceptionTranslationFilter → turns "not authenticated" into 401
  │     └─ AuthorizationFilter        → applies permitAll / authenticated rules
  ├─ RequestIdFilter                                           order: 1  (@Order(1))
  ├─ JwtAuthenticationFilter again (auto-registered @Component) order: lowest
  │     → skipped: OncePerRequestFilter sees "already ran for this request"
  └─ DispatcherServlet → your controller
```

Two things follow from that order:
1. **`JwtAuthenticationFilter` is registered twice.** Once inside the security chain, once as a plain filter. It still runs only **once** per request because it extends `OncePerRequestFilter`, which marks the request after the first run. It's harmless, but it's why many projects add a `FilterRegistrationBean` with `setEnabled(false)`. I found none here.
2. **`@Order(1)` puts `RequestIdFilter` *after* Spring Security (-100), not first.** So when security rejects a request, `requestId` is not in the MDC yet:
   - the 401 from the entry point
   - the "password change required" response written inside `JwtAuthenticationFilter`

   That means those rejection log lines show `no-rid` (from the dev log pattern `%X{requestId:-no-rid}`), and `ApiResponse` falls back to a fresh random UUID. To be sure of this, you can check the startup log at DEBUG level or set a breakpoint; I haven't run the app.

A related detail in `JwtAuthenticationFilter`: it does `MDC.put("userId", ...)` and `MDC.put("role", ...)` but never removes them. Tomcat reuses threads, so a later request on the same thread with no token could log the *previous* user's id. (`RequestIdFilter` does clean up its own key in `finally`.) We'll go deeper in Chapter 3.

---

## Step ⑤ Tomcat starts

When every bean exists, embedded Tomcat binds to `${PORT:8080}`. The log shows `Tomcat started on port 8080 (http)` and then `Started TempleRegistryApplication in N seconds`.

## Step ⑥ `ApplicationReadyEvent`: code that runs once, after startup

Spring publishes an **event** saying "everything is up." Two beans listen for it.

### 1. `EmailStartupValidator` (synchronous)

```java
public class EmailStartupValidator implements ApplicationListener<ApplicationReadyEvent> {
    public void onApplicationEvent(ApplicationReadyEvent event) {
        List<NotificationRule> rules = notificationRuleRepository.findByEnabledTrueAndDeletedFalse();
        for (var rule : rules) {
            if (!"EMAIL".equals(channel) && !"BOTH".equals(channel)) continue;
            String resolved = templateResolver.resolve(rule.getTemplateKey()); // "submission-notification" → "email/submission-notification"
            if (!templateExists(resolved)) missing.add(resolved);
        }
        // also checks the fixed templates: password-reset, account-created, temporary-password, notification
        if (!missing.isEmpty() && failFast) throw new IllegalStateException(...);
        ...
```

- **Why it exists:** notification rules live in the DB table `notification_rules`, each with a `template_key`. If an admin sets a key with no matching HTML file in `templates/email/`, emails would fail *later*, at delivery time. This checks every key at boot.
- **Input:** DB rows + the Thymeleaf `TemplateEngine`. **Output:** log lines only, unless `email.startup-validation.fail-fast=true`, in which case it **stops the app**.
- `templateExists` renders each template with dummy variables (`templeName`, `resetLink`…) to prove it parses.

### 2. `TrustDataRepairService.repairLiveTrustData()` (asynchronous)

```java
@EventListener(ApplicationReadyEvent.class)   // "call me when the app is ready"
@Async("taskExecutor")                        // "…but on a background thread, don't block startup"
@Transactional                                // "…inside one DB transaction"
public void repairLiveTrustData() {
    repairTrustDatesAndTempleFlags();
    repairEncryptedBoardMembers();
}
```

On **every** startup this method:
- Loads **all** trusts. Any `dateOfRegistration` in the future is set to today.
- Loads **all** temples. It re-syncs `temple.trustRegistered` to match whether an active trust exists, which runs one `existsByTempleIdAndDeletedFalse` query **per temple**.
- Loads **all** board members. It recomputes the `current` flag from `tenureEndDate`.
- Does **not** call `save()`. Because the method is `@Transactional`, Hibernate's **dirty checking** notices the changed fields and issues `UPDATE`s automatically at commit. (Chapter 4 explains dirty checking.)

Things to understand about it:
- `@Async` + `@Transactional` on the same method works: the proxy hands the call to a thread, and the transaction opens on that thread.
- `updated_by` for these rows will be `0` (no user in the `SecurityContext`).
- It is a full table scan of three tables, plus N queries, on every boot. That's fine at today's data size and slow at government scale. It looks like a one-time data fix left permanently switched on.

## Step ⑦ Schedulers start

`@EnableScheduling` starts a scheduler thread (Spring's default is a **single** thread unless configured, and I found no `TaskScheduler` bean). Each `@Scheduled` method registers:

| Method | Schedule | First run |
|---|---|---|
| `NotificationRouter` (line 77) | `fixedDelay = 5 s` | ~immediately |
| `EmailDeliveryService` (line 94) | `fixedDelay = 10 s` | ~immediately |
| `NotificationRouter` (line 105) | `fixedDelay = 60 s` | ~immediately |
| `EmailDeliveryService` (line 112) | `fixedDelay = 5 min` | ~immediately |
| `EmailRetryScheduler` | `fixedDelay = 10 min` | ~immediately |
| `OverdueWorkflowScheduler` | cron `0 30 20 * * *` UTC (02:00 IST) and `0 30 3 * * *` UTC (09:00 IST) | next matching time |
| `NoticeExpiryScheduler` | cron `0 0 1 * * *` (**server** time zone, no `zone=`) | next 01:00 |

`fixedDelay` means "wait N after the previous run **finishes**", so a slow run never overlaps itself. With one scheduler thread, a slow email batch delays the notification router too. Keep that in mind when we reach Chapter 10.

---

## 2.1 What can make startup fail (a debugging table)

| Symptom in log | Cause | Where |
|---|---|---|
| `Communications link failure` / Hikari timeout | Wrong DB URL, wrong credentials, or TiDB unreachable | `spring.datasource.*`, `dev-secrets.properties` |
| `Could not resolve placeholder 'DB_USERNAME'` | Dev profile with no secrets file and no env var | `application-dev.yml` |
| `FlywayException: Validate failed` / migration SQL error | A broken `V*.sql` | `db/migration` |
| `No property 'x' found for type 'Y'` | Typo in a derived repository method name | `repository/**` |
| `Error creating bean 'scopeHelper'` / `InvalidKeySpecException` | Missing or bad PEM | `resources/keys/`, `app.jwt.*` |
| `IllegalArgumentException` from `AesEncryptionConverter` | Encryption key wrong length (must be 32 bytes for AES-256) | `app.encryption.key` |
| `Ambiguous mapping` | Two controller methods on the same URL + method | `controller/**` |
| `IllegalStateException [EmailStartupValidator]` | Missing template **and** `fail-fast=true` | `templates/email/` |
| `BeanCurrentlyInCreationException` | Circular constructor dependencies between services | `service/impl/**` (Chapter 12 will check for these) |

## 2.2 The five questions, for the startup classes

```text
Who calls me?  JVM
      ↓
[TempleRegistryApplication.main]
      ↓
Who do I call? SpringApplication.run → builds everything
```

| Class | Why does it exist? | Who calls it? | What does it call? | In | Out |
|---|---|---|---|---|---|
| `TempleRegistryApplication` | Entry point; turns on scan, async, scheduling | JVM | `SpringApplication.run` | CLI args | Running context |
| `SecurityConfig` | Define URL security + password hashing | Spring (once, at boot) | `HttpSecurity`, `JwtAuthenticationFilter`, `UserDetailsServiceImpl` | Injected beans | `SecurityFilterChain`, `PasswordEncoder`, `AuthenticationManager` beans |
| `FlywayConfig` | Optional repair before migrate | Spring Boot Flyway auto-config | `Flyway.repair()`, `migrate()` | `app.flyway.auto-repair` | Migrated schema |
| `JpaAuditConfig` | Fill `created_by`/`updated_by` | Hibernate, on every save | `SecurityContextHolder` | Current auth | `Optional<Long>` user id |
| `AsyncConfig` | Thread pools for `@Async` | Spring at boot; `@Async("…")` at runtime | `ThreadPoolTaskExecutor` | — | `taskExecutor`, `exportExecutor` |
| `CacheConfig` | Cache DACVM policies | `@Cacheable` in `PolicyEvaluationServiceImpl` | Caffeine | — | `CacheManager` |
| `ScopeHelper` | Verify JWTs | `JwtAuthenticationFilter` | jjwt parser | Public-key file; later, token strings | `Claims` record |
| `EmailStartupValidator` | Catch missing templates early | Spring (`ApplicationReadyEvent`) | `NotificationRuleRepository`, `TemplateEngine` | DB rules | Logs / startup failure |
| `TrustDataRepairService` | Self-heal trust/board data | Spring (`ApplicationReadyEvent`, async) | `TrustRepository`, `TempleRepository`, `BoardMemberRepository`, `TrustValidationService` | All rows | UPDATEs via dirty checking |

## 2.3 Chapter 2 in one sentence

> `main()` hands control to Spring. Spring reads **yml + dev profile + secrets**, **scans** `com.templeregistry`, **injects** constructors (keys first, then DB → **Flyway** → **Hibernate** `update` → repositories), **wraps** beans in proxies for `@Transactional` / `@PreAuthorize` / `@Async`, wires the **security filter chain** with `JwtAuthenticationFilter`, starts **Tomcat on 8080**, runs the two **ready-listeners**, and starts the **schedulers**.

## 2.4 Things from this chapter worth raising with your team (not changed)

1. `ddl-auto: update` alongside Flyway: two owners of the schema.
2. `validate-on-migrate: false` plus auto-repair: edited migrations go unnoticed.
3. Unused or misnamed properties: `app.notification.email-enabled`, `spring.flyway.repair-on-migrate`, and the test-only `app.encryption.aes-key` / `cloud.aws.*`.
4. `RequestIdFilter` at `@Order(1)` runs after security, so rejected requests have no correlation id.
5. `JwtAuthenticationFilter` is registered twice (harmless) and leaks MDC `userId`/`role` across pooled threads.
6. `TrustDataRepairService` scans three whole tables on every boot.
7. The default single-threaded scheduler is shared by five jobs.

---

**Next: Chapter 3 — Security & Authentication.** It covers:
- **login**: `AuthController` → `AuthServiceImpl` → `AuthenticationManager` → `DaoAuthenticationProvider` → `UserDetailsServiceImpl` → BCrypt → MFA temp token → `JwtServiceImpl.generateAccessToken` → refresh tokens
- **every later request**: `JwtAuthenticationFilter` → `ScopeHelper.parseFull` → `Claims` in the `SecurityContext` → `@PreAuthorize(RoleConstants…)` → `JurisdictionGuard` / `OwnershipGuard` / `AccessGuard` → the DACVM `@DacvmGuard` aspect
- the forced password-change gate, and exactly what 401 vs 403 look like

Say **"continue"** when you're ready.
