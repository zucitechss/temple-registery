# CHAPTER 11 — Testing

I classified all 105 test files and read representative ones of each kind. I also read the **real results** of the last local run in `target/surefire-reports/` (dated 2026-09-25).

## 11.0 What's there

| Kind | Tool | Files | Folder(s) | What it exercises |
|---|---|---|---|---|
| **Service unit tests** | JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)`, `@Mock`, `@InjectMocks`) | ~52 | `service/impl/**`, `service/**`, `util/`, `security/`, `governance/` | One class's logic, with every dependency faked |
| **Controller slice tests** | `@WebMvcTest` + `MockMvc` + `@MockBean` | 9 | `controller/**` | URL → controller → JSON; the service is faked |
| **Security unit tests** | plain JUnit + Mockito, reflection | 8 | `security/` | Guards, the JWT filter gate, `@PreAuthorize` strings |
| **Property-based tests** | **jqwik** (`@Property`, `@ForAll`, `@Provide`) | 14 | `property/`, a few in `service/impl/declaration` | Rules checked against hundreds of generated inputs |
| **Integration tests** | `@SpringBootTest` + **Testcontainers MySQL 8** | 8 | `integration/` | The real app + real DB |
| Bug-exploration / preservation | Mockito / jqwik | 2 + 2 | `bugfix/`, `property/Notification*` | Captured bug conditions |

Totals:
- **833** `@Test`/`@Property`/`@ParameterizedTest` methods (the report counts **867** executions, because parameterized cases count separately)
- **30** files use `@Nested` to group tests per endpoint or behaviour
- AssertJ `assertThat` in 92 files
- Mockito `verify(...)` in 48

**Last run: 867 tests, 0 failures, 0 errors, 0 skipped.**

## 11.1 How the tests actually run (build config)

From [pom.xml](../backend/pom.xml), `maven-surefire-plugin`:
```xml
<includes>
    <include>**/*Test.java</include>
    <include>**/*Tests.java</include>
    <include>**/*PropertyTest.java</include>
    <include>**/*IT.java</include>
</includes>
<excludes>
    <exclude>**/integration/**IT.java</exclude>      ← removes all 5 *IT classes again
</excludes>
```

| Test | Runs on `mvn test`? | Why |
|---|---|---|
| everything named `*Test` / `*PropertyTest` | ✅ | included |
| `integration/ApplicationContextIntegrationTest` | ✅ (needs Docker, else skipped) | name ends with `Test`, so not excluded |
| `integration/TrustIntegrationTest` | runs **0 tests** | class is `@Disabled` |
| `DeclarationHappyPathIT`, `DeclarationClarificationIT`, `DeclarationSiteVisitIT`, `DeclarationConcurrencyIT`, `OverdueSchedulerIT` | ❌ **never** | excluded by Surefire, and there is **no `maven-failsafe-plugin`**, so `mvn verify` doesn't run them either |

So the *only* test that runs against a real database is a one-line smoke test. The five end-to-end workflow tests exist but sit outside every build.

To run one by hand (needs Docker; `-Dtest` overrides the include/exclude lists):
```bash
mvn test -Dtest=DeclarationHappyPathIT
mvn test -Dtest=AuthServiceImplTest            # one class
mvn test -Dtest='AuthServiceImplTest#should_lock_account_after_five_failed_attempts'   # one method
```

**Three database setups are used by tests:**

| Setup | Where configured | Schema from | Used by |
|---|---|---|---|
| **None** (mocks) | — | — | all unit and slice tests |
| **H2 in-memory**, `MODE=MySQL` | `src/test/resources/application-test.yml` (`ddl-auto: create-drop`, Flyway **off**) | Hibernate, from entities | any `@SpringBootTest` with profile `test` that doesn't extend the container base |
| **MySQL 8 via Testcontainers** | `MySQLContainerBase`, `ApplicationContextIntegrationTest` (`@DynamicPropertySource`) | **Flyway migrations**, then `ddl-auto: validate` | the ITs + the context test |

**JaCoCo** coverage deliberately excludes `dto/**`, `entity/**`, `config/**` and the main class, so the coverage number reflects services, controllers, security and utils only.

---

## 11.2 Kind 1: a service unit test (`AuthServiceImplTest`)

```java
@ExtendWith(MockitoExtension.class)                  // Mockito creates the @Mock objects
class AuthServiceImplTest {
    @Mock UserRepository userRepository;              // fake repository: returns whatever we tell it
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    ... 5 more @Mock
    @InjectMocks AuthServiceImpl authService;         // REAL AuthServiceImpl, built with the fakes above

    @Test
    void should_lock_account_after_five_failed_attempts() {
        // ARRANGE
        activeUser.setFailedLoginCount(4);
        when(userRepository.findByUsername("dcuser")).thenReturn(Optional.of(activeUser));
        when(passwordEncoder.matches(any(), any())).thenReturn(false);
        // ACT + ASSERT (exception)
        assertThatThrownBy(() -> authService.login(new LoginRequest("dcuser", "bad")))
                .isInstanceOf(EntityNotFoundException.class);
        // ASSERT (interaction)
        verify(userRepository).save(argThat(u -> u.getLockedUntil() != null));
    }
}
```

| Piece | Plain meaning |
|---|---|
| `@Mock` | a stand-in object with no behaviour until you program it |
| `@InjectMocks` | build the class under test, passing the mocks into its constructor (constructor injection pays off here, Chapter 2) |
| `when(x).thenReturn(y)` | "if the code calls x, return y" |
| `assertThatThrownBy(...)` | AssertJ: "this call must throw this type" |
| `verify(mock).save(argThat(...))` | "the code must have called `save` with an argument matching this condition" |

```text
Test starts
 ↓ Arrange   user with 4 failures; repository returns it; password check returns false
 ↓ Act       authService.login(...)
 ↓ Assert    throws EntityNotFoundException; save() was called with lockedUntil set
Production code exercised: AuthServiceImpl.login — the lockout branch (Ch.3)
```

**The lesson in this test.** It passes, and it's correctly written for what it checks. But it checks *"was `save` called?"*, not *"did the row survive?"*. There's no real transaction in a Mockito test, so the rollback that undoes the lockout in production (Chapter 3, Defect #1) is invisible here. **Unit tests with mocks can't see transactional behaviour.** Only a test with a real Spring context and a real database can.

`verifyMfa` (Chapter 3, Defect #3) has **no test at all**. I searched `AuthServiceImplTest` for `verifyMfa` and found nothing.

## 11.3 Kind 2: a controller slice test (`TempleControllerTest`)

```java
@WebMvcTest(value = TempleController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
class TempleControllerTest {
    @Autowired MockMvc mockMvc;                        // sends fake HTTP requests into Spring MVC, no server
    @MockBean TempleService templeService;            // Mockito mock registered as a Spring bean
    @MockBean TempleProfileStagingService stagingService;
    @MockBean ScopeHelper scopeHelper;                // needed because JwtAuthenticationFilter is a @Component

    @Nested class Search {
        @Test void should_return200WithResults_when_searchPerformed() throws Exception {
            // ARRANGE
            when(templeService.search(any())).thenReturn(PaginatedResponse.of(new PageImpl<>(List.of(
                    TempleSearchResultResponse.builder().id(1L).name("Shiva Temple").build()), PageRequest.of(0,10), 1L)));
            // ACT + ASSERT
            mockMvc.perform(get("/api/v1/temples"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].name").value("Shiva Temple"));
        }
    }
}
```

**What `@WebMvcTest` loads.** Only the web layer: this controller, `@RestControllerAdvice` (`GlobalExceptionHandler`), Jackson, validation, and `Filter` beans. **No** services, repositories or database. You `@MockBean` what the controller needs.

| This kind of test **proves** | It **does not prove** |
|---|---|
| URL + verb map to the right method | anything about authorization: Spring Security auto-config is **excluded**, and `SecurityConfig` isn't loaded, so **`@PreAuthorize` is not evaluated** |
| `@Valid` rejects bad bodies → 400 | service logic (mocked) |
| JSON shape (`jsonPath("$.data…")`) and status codes | DB / transactions |
| exception → status mapping (mock the service to throw) | real Jackson serialization of *entities*: services return DTOs here, so the Chapter 8 entity-leak can't show up |

Its test name `should_return200_when_unauthenticated` passes because security is switched off, not because the endpoint is public. The real proof that `GET /api/v1/temples` is public is `SecurityConfig`, which this test never loads. Seven other slice tests do the same with `@AutoConfigureMockMvc(addFilters = false)`, and 21 places set the `SecurityContextHolder` by hand instead of sending a real token.

## 11.4 Kind 3: security tests

**a) The filter, tested directly** (`JwtAuthenticationFilterPasswordGateTest`):
```java
@Test void should_blockApplicationEndpoint_when_passwordChangeIsRequired() throws Exception {
    when(scopeHelper.parseFull(TOKEN)).thenReturn(new ScopeHelper.ParsedToken(CLAIMS, true));  // ARRANGE
    request.setRequestURI("/api/v1/temples/3");
    filter.doFilterInternal(request, response, filterChain);                                     // ACT
    assertThat(response.getStatus()).isEqualTo(403);                                             // ASSERT
    assertThat(response.getContentAsString()).contains("PASSWORD_CHANGE_REQUIRED");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(filterChain, never()).doFilter(any(), any());                                         // request stopped
}
```
This is a good, precise test of the gate (`MockHttpServletRequest` / `MockHttpServletResponse` stand in for Tomcat). But it **mocks `ScopeHelper`**, so it never parses a real token. The TEMP-token acceptance issue (Chapter 3, Defect #4) lives inside `parseFull` and can't show up here.

**b) Reflection "matrix" tests** (`PasswordManagementAuthorizationTest`):
```java
String expression = AdminServiceImpl.class.getDeclaredMethod("resetUserPassword", Long.class)
                        .getAnnotation(PreAuthorize.class).value();
assertThat(expression).isEqualTo(RoleConstants.ADMIN_ONLY);
```
These read the **annotation text**. They stop someone from silently deleting or widening a `@PreAuthorize`, which is cheap and useful. They don't prove Spring *enforces* it. The class Javadoc says enforcement is *"asserted live by `ApplicationContextIntegrationTest`"*, but that test only does `assertThat(mysql.isRunning()).isTrue()`, so **nothing currently tests `@PreAuthorize` enforcement end to end.**

The other security tests (`JurisdictionGuardTest`, `OwnershipGuardTest`, `AccessGuardTest`, `TokenRevocationGuardTest`, `RoleConstantsTest`, `UserDetailsServiceImplTest`) are unit tests of each guard with a hand-set `SecurityContextHolder`.

## 11.5 Kind 4: property-based tests (jqwik)

**The idea in simple words.** Instead of writing 3 examples, you describe **what must always be true**, and the library invents hundreds of inputs to try to break it.

```java
@Property(tries = 200)                                                    // run with 200 generated inputs
void acknowledgementNumberMatchesFormat(
        @ForAll @AlphaChars @StringLength(min = 2, max = 5) String districtCode,   // random letters
        @ForAll @IntRange(min = 2020, max = 2030) int year,
        @ForAll @IntRange(min = 1, max = 99) int sequence) {
    // Simulate the format generation logic from AcknowledgementServiceImpl
    String financialYear = year + "-" + String.format("%02d", (year + 1) % 100);
    String ackNumber = "ACK-" + districtCode.toUpperCase() + "-" + financialYear + "-" + String.format("%06d", sequence);
    assertThat(ACK_PATTERN.matcher(ackNumber).matches()).isTrue();
}
```

Look at the comment: *"Simulate the format generation logic."* The test **rebuilds the format itself** and checks its own string. It never calls `AcknowledgementServiceImpl.generate`. So if the real service changed its format, this test would still pass. The class promises "Acknowledgement Number **Uniqueness**", yet it can't catch the concurrency race (Chapter 6), because that needs two real transactions.

When reading the 14 jqwik classes, sort them into:
- tests that call **production code** with generated inputs (valuable), and
- tests that **re-implement** the rule inside the test (they only prove the test agrees with itself).

`StateTransitionValidatorPropertyTest` is `@Disabled("StateTransitionValidator is a no-op shim …")`, which is correct now that the engine owns transitions.

## 11.6 Kind 5: integration tests (Testcontainers)

**The shared base**, [MySQLContainerBase.java](../backend/src/test/java/com/templeregistry/integration/MySQLContainerBase.java):
```java
@Testcontainers(disabledWithoutDocker = true)             // no Docker → tests are skipped, not failed
public abstract class MySQLContainerBase {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withReuse(true);
    @DynamicPropertySource                                 // override application*.yml at startup
    static void configureDataSource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.flyway.enabled", () -> "true");                 // real migrations
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");     // fail if entities ≠ schema
        r.add("spring.datasource.hikari.connection-init-sql", () -> "SELECT 1");   // strip the TiDB-only SQL
        ...
    }
}
```
**Testcontainers in simple words:** it starts a real MySQL in Docker for the test run and points the app at it. That's as close to production as a test gets. Here it's MySQL 8, not TiDB, so TiDB-specific behaviour (isolation levels, FK enforcement) isn't covered.

**`ApplicationContextIntegrationTest`** is the only one that runs, and it **passed on the last run**. That proves more than its one assertion suggests:
- all ~300 beans construct (no missing bean, no circular dependency)
- all 27 Flyway migrations apply on a clean MySQL
- Hibernate `validate` finds **no mismatch** between the 67 entities and the migrated schema, as of 2026-09-25

That is the safety net Chapter 4 mentioned.

**`DeclarationHappyPathIT`**, which never runs:
```text
Arrange  delete all rows; set a TA SecurityContext; build State→City→District→Taluk→Hobli→Temple
Act      declarationService.create → governanceWorkflowService.submitDeclaration
         → (DC context) markUnderReview → approveDeclaration
Assert   status at each step · ack number matches ACK-.* · ≥2 versions · audit rows for each action
Production code exercised: the full Flow A of Chapter 10 against real MySQL
```
Its Javadoc still says *"Disabled because H2 does not support TINYINT(1)… Testcontainers MySQL base resolves this"*. It *does* extend the base now, so the comment is stale. The real reason it doesn't run is the Surefire exclude.

The other ITs, with the same status:

| IT | Would test | Note |
|---|---|---|
| `DeclarationClarificationIT` | clarify → respond rounds | would be the place to catch the round-2/3 notification suppression |
| `DeclarationSiteVisitIT` | schedule / complete / verify / fail | |
| `DeclarationConcurrencyIT` | concurrent actions | the natural home for the ack-number race test |
| `OverdueSchedulerIT` | **`service.declaration.OverdueScheduler`**, the **dead** class | `@Autowired OverdueScheduler` would fail, since it isn't a bean. It targets code that no longer runs, while the live `OverdueWorkflowScheduler` (and the unset `deadline_at`) is untested |
| `TrustIntegrationTest` | trust CRUD + security | `@Disabled`, 0 tests |

## 11.7 Could the tests have caught this? (findings × tests)

| Finding (chapter) | Existing test | Why it didn't catch it | Smallest test that would |
|---|---|---|---|
| Lockout rolled back (3) | `AuthServiceImplTest.should_lock_account…` ✅ passes | mocks, no transaction | `@SpringBootTest` + container: 5 bad logins, then `findByUsername` → assert `lockedUntil != null` |
| MFA not checked (3) | none | — | unit: `verifyMfa` with wrong `mfaCode` must throw `MfaVerificationException` |
| TEMP token accepted as login (3) | filter test mocks `ScopeHelper` | parsing never exercised | unit: real `ScopeHelper` with a TEMP token → `parse` should reject |
| Inactive user can log in (3) | none | — | unit: `isActive=false` → login throws |
| Temple photo upload by AUDITOR (5) | `TempleControllerTest` | security excluded | service unit with AUDITOR claims → expect `AccessDeniedException` |
| Declarations listed across districts (7) | — | — | service unit: DC claims for district 1, temple in district 2 → expect 404/403 |
| DC_STAFF approves via v2 (10) | `WorkflowEngineExecuteTest` (no `DC_STAFF` case; I found no `DC_STAFF` in workflow/governance tests) | case missing | engine unit: ctx role `DC_STAFF`, action APPROVE → expect `WorkflowException` |
| Ack-number race (6) | `AcknowledgementPropertyTest` (re-implements the format) | never calls the service; no concurrency | `DeclarationConcurrencyIT`-style: two approvals in parallel threads → distinct acks |
| `deadline_at` never set (10) | `OverdueSchedulerIT` → dead class, excluded | wrong target | IT: create + submit a declaration → assert `workflow_instances.deadline_at` not null |
| Duplicate emails / suppressed rounds (10) | `NotificationDispatchBehaviorTest`, `NotificationRouterTest` (unit, mocked repos) | both paths never run together; keys not compared across classes | IT: one transition → wait for the outbox tick → assert exactly 1 `email_outbox` row; two clarification rounds → 2 in-app rows |
| Stale version → 500 (9) | `GlobalExceptionHandlerTest` (handler mapping) | engine not involved | slice/unit: service throws `jakarta.persistence.OptimisticLockException` → expect 409 |
| `annualRent` = monthly (8) | — | — | unit: `DeclarationAssetMapper.toLeasedPropertyEntity(monthlyRent=5000).getAnnualRent() == 60000` (or null) |

The pattern: **the suite is large (867 green) but almost entirely mock-based**. The bugs we found live in the seams mocks remove: transactions, real token parsing, security enforcement, two components acting on the same event, and concurrency. The integration tests that would cover those seams exist but are switched off.

## 11.8 The five questions, for `MySQLContainerBase`

```text
Who calls me?   JUnit, via `extends MySQLContainerBase` (7 IT classes)
      ↓
[MySQLContainerBase]
      ↓
Who do I call?  Testcontainers → Docker (mysql:8.0) · Spring's DynamicPropertyRegistry

INPUT   nothing (static container, reused across classes in one JVM)
  ↓     start MySQL · override datasource/Flyway/ddl-auto/JWT/mail properties
OUTPUT  a real Spring context wired to a real, Flyway-migrated database for each IT
NOTE    some overrides target properties the app never reads (app.encryption.aes-key, cloud.aws.*) — harmless leftovers (Ch.2)
```

## 11.9 Writing a new test here: which kind, and a template

| You're testing | Use | Template |
|---|---|---|
| A service rule with no transaction semantics | Mockito unit | `@ExtendWith(MockitoExtension.class)`, `@Mock` repos/guards, `@InjectMocks XServiceImpl`; set claims with `SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(claims, null, List.of(new SimpleGrantedAuthority("ROLE_DC_STAFF"))))` |
| URL mapping, validation, JSON shape, error status | `@WebMvcTest(X.class, excludeAutoConfiguration = {Security…})` + `@MockBean` | as `TempleControllerTest`; remember it says **nothing** about authorization |
| That a role is **really** blocked | `@SpringBootTest` + `@AutoConfigureMockMvc` (filters **on**) + a real signed JWT from `JwtService` in the `Authorization` header | none exists yet; the most valuable one to add |
| Anything involving commit/rollback, events, schedulers, concurrency | `extends MySQLContainerBase` | as `DeclarationHappyPathIT`, **and** make it run: add `maven-failsafe-plugin` (binding `**/*IT.java`) or remove the Surefire exclude |
| A rule over many inputs | jqwik `@Property` | call the **production** method inside, don't re-implement it |

---

**Next: Chapter 12 — Complete Mental Model** (the final chapter). It covers:
- the office analogy mapped to this backend's actual classes
- full ASCII diagrams of the important flows with real class names
- the end-to-end "coding interview" walk-through of one API (the declaration approval)
- the **Backend Cheat Sheet**: architecture, packages, key classes, tables, relationships, APIs, auth flow, patterns
- the recommended reading order for this codebase
- one consolidated, ranked list of every finding from Chapters 0–11

Say **"continue"** when ready.
