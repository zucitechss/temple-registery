# CHAPTER 8 — DTOs & Mappers

A **DTO** (Data Transfer Object) is a plain class shaped exactly like the JSON the frontend sends or receives. A **mapper** copies data between DTOs and entities. This chapter shows why the app never sends entities directly, the three different ways conversion is done here, and where it goes wrong.

## 8.0 Why not just return the entity? Five real reasons from this codebase

| Reason | Concrete example here |
|---|---|
| **Secrets would leak** | `User` has `passwordHash`, `mfaSecret`, `passwordResetTokenHash`. `UserAdminResponse` has none of them |
| **Sensitive data must be masked** | `Trust.trustPANNumber` is decrypted on read. `TrustResponse` exposes only `maskedPanNumber` (`AB*****4F`) and `maskedBankAccountNumber` (`******1234`) |
| **The shape differs** | The entity stores `bankNameAndBranch` as one column. The DTOs split it into `bankName` + `bankBranch` (`joinBankNameAndBranch` / `splitBankNameAndBranch` in `TrustServiceImpl`) |
| **Extra computed data** | `TrustResponse.governanceStatus`, `workflowInstanceId`; `TempleResponse.districtName`; `photoUrl` rewritten to `/api/v1/temples/{id}/profile-photo/serve` |
| **Lazy proxies and cycles break JSON** | Section 8.5 shows the one place where entities *are* returned, and why that most likely fails |

There's also a boundary reason: request DTOs contain **only what the client may set**. A client can't sneak `status: "APPROVED"` or `createdBy: 1` into a create call, because those fields don't exist on the request DTO.

## 8.1 Request DTOs and validation

### Where validation happens
```text
JSON body ──► Jackson builds CreateTrustRequest ──► @Valid ──► Hibernate Validator checks every annotation
                                                                 │
                                           all OK ───────────────┴──► controller method runs
                                           any fail ──► MethodArgumentNotValidException
                                                         ──► GlobalExceptionHandler ──► 400
                                                             { "success": false, "errorCode": "VALIDATION_ERROR",
                                                               "errors": ["panNumber: Invalid PAN format (e.g. ABCDE1234F)", …] }
```
The check happens **before** your controller code runs. The service never sees an invalid DTO. (The exact `errors` format is covered in Chapter 9.)

### A real request DTO, annotation by annotation
[CreateTrustRequest.java](../backend/src/main/java/com/templeregistry/dto/request/trust/CreateTrustRequest.java):
```java
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder   // Jackson needs the no-arg constructor + setters
public class CreateTrustRequest {
    @NotBlank @Size(max = 255)                                        private String trustName;
    @NotNull                                                          private TrustType trustType;          // enum: bad value → 400 from Jackson
    @NotBlank @Size(max = 100) @Pattern(regexp = "^[A-Za-z0-9/\\-]+$") private String registrationNumber;
    @NotNull @PastOrPresent                                           private LocalDate dateOfRegistration;
    @NotBlank @Pattern(regexp = "^[A-Z]{5}[0-9]{4}[A-Z]$", message = "Invalid PAN format (e.g. ABCDE1234F)")
                                                                      private String panNumber;
    @NotBlank @Pattern(regexp = "^\\d{6,32}$")                        private String bankAccountNumber;
    @PositiveOrZero                                                   private BigDecimal annualIncome;      // optional (no @NotNull)
    ...
}
```

Every constraint type used in this project's request DTOs:

| Annotation | Meaning | Example |
|---|---|---|
| `@NotNull` | must be present | `dueDate`, `trustType` |
| `@NotBlank` | string not null and not just spaces | `trustName`, `username` |
| `@Size(min, max)` | length limits | password 8–128 |
| `@Pattern(regexp)` | regex | PAN, Aadhaar `^\d{12}$`, mobile `^[6-9]\d{9}$`, FY `\d{4}-\d{2}` |
| `@Min` / `@Max` | number range | vehicle `year` 1950–2100 |
| `@DecimalMin("0.0")`, `@PositiveOrZero` | non-negative money | `annualIncome` |
| `@PastOrPresent` | date not in the future | registration / appointment dates |
| `@Valid` **on a field** | "also validate the objects inside" | the 9 item lists in `CreateDeclarationRequest` |
| custom `@ValidFinancialYear` | see 8.2 | `SubmitTrustFinancialRequest` |

### Nested validation: `CreateDeclarationRequest`
```java
@NotBlank @Pattern(regexp = "\\d{4}-\\d{2}") private String financialYear;
@NotNull                                     private LocalDate dueDate;
@Valid private List<VehicleItemRequest>  vehicles  = new ArrayList<>();
@Valid private List<BuildingItemRequest> buildings = new ArrayList<>();
... 7 more lists
```
Without the `@Valid` on each list, the rules inside `VehicleItemRequest` (`@NotBlank registrationNumber`, `@Min(1950) year`, …) would be **skipped**. With it, an error reports a path like `vehicles[2].year: Year must be after 1950`. The lists default to `new ArrayList<>()`, so a missing list is empty rather than `null`. That's why `replaceAssetItems` can loop safely.

### Two layers of validation
| Layer | Where | What it checks | Error |
|---|---|---|---|
| **Annotations** on DTOs | before the controller | format, presence, ranges: things visible in *one request* | 400 `VALIDATION_ERROR` |
| **Service rules** | inside the service (`TrustValidationService`, `FinancialYearValidationService`, checks like "one trust per temple") | things that need the **database** or other context | 409 / 422 / custom |

Beginners often try to do DB checks in annotations. This codebase keeps them in services, which is correct.

### Where validation silently doesn't run
- **Missing `@Valid`**: `TempleController.search(TempleSearchFilterRequest filter)` (Chapter 5).
- **DTOs without constraints**: the nested records in `WorkflowController` (`WorkflowActionHttpRequest` and others) are marked `@Valid` but contain no annotations.
- **`MfaVerifyRequest.mfaCode`** has `@Size` but no `@NotBlank`, so it can be absent (Chapter 3).

### DTO styles used
There are **33** classes with `@Data` (Lombok getters, setters, `equals`, `hashCode`, `toString`), **3** Java `record`s, and the rest use explicit `@Getter/@Setter/@Builder`. One caution about `@Data` on request DTOs: its generated `toString()` prints **every field**. `CreateBoardMemberRequest` uses `@Getter @Setter` rather than `@Data`, so an accidental `log.info("{}", request)` there prints only the object reference, not the Aadhaar number. Keep it that way for anything carrying PII.

## 8.2 The custom constraint: `@ValidFinancialYear`

This is how you write your own validation annotation. Three pieces work together:

```java
// 1. The annotation — what you put on a field
@Constraint(validatedBy = ValidFinancialYearValidator.class)     // "this class does the checking"
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)                               // must survive to runtime
public @interface ValidFinancialYear {
    String message() default "Financial year must be valid and in YYYY-YY format.";
    Class<?>[] groups() default {};                               // required boilerplate
    Class<? extends Payload>[] payload() default {};              // required boilerplate
}

// 2. The validator — a Spring bean, so it can inject services
@Component @RequiredArgsConstructor
public class ValidFinancialYearValidator implements ConstraintValidator<ValidFinancialYear, String> {
    private final FinancialYearValidationService financialYearValidationService;
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.trim().isBlank()) return false;
        try { financialYearValidationService.normalizeAndValidate(value); return true; }
        catch (IllegalArgumentException ex) {
            context.disableDefaultConstraintViolation();                                  // drop the generic message
            context.buildConstraintViolationWithTemplate(ex.getMessage()).addConstraintViolation();  // use the specific one
            return false;
        }
    }
}

// 3. The actual rule — FinancialYearValidationServiceImpl.normalizeAndValidate
"^\\d{4}-\\d{2}$"                     → shape
endYearShort == (startYear + 1) % 100 → "2025-26" ok, "2025-27" rejected
startYear <= current FY start         → no future years (FY starts in April)
```

The rule lives in a **service** so both the annotation *and* other code (e.g. `TrustValidationServiceImpl`) share it.

**It's used on exactly one field:** `SubmitTrustFinancialRequest.financialYear`. `CreateDeclarationRequest.financialYear` uses only `@Pattern("\\d{4}-\\d{2}")`, so a declaration can be created for `"2025-99"` or a future year, while a trust financial can't. That's worth aligning.

## 8.3 Response DTOs

A typical response DTO is built once and never modified:
```java
@Getter @Builder
public class DistrictResponse { private Long id; private Long cityId; private String name; private String code; }
```

Patterns you'll see in response DTOs:

| Pattern | Example | Why |
|---|---|---|
| `@Builder` (often no setters) | `TrustResponse.builder()….build()` | Build once, read-only afterwards |
| `@JsonInclude(NON_NULL)` | `ApiResponse`, `WorkflowEnvelope` (6 classes) | Omit null keys from JSON |
| **Masking** | `maskPan`: `pan.substring(0,2) + "*****" + last2`; `maskBankAccount`: `"******" + last4`; `BoardMember.getMaskedAadhaar()`: `"XXXX-XXXX-" + last4` | PII never leaves in full |
| **Flattening** | `DistrictResponse.cityId` from `district.city.id` | No nested entity graphs in JSON |
| **Enrichment** | `TempleResponse.districtName` (`enrichTempleResponse`), `TrustResponse.governanceStatus` (`GovernanceStatusResolver`) | Data from other tables |
| **URL rewriting** | `photoUrl` → `/api/v1/temples/{id}/profile-photo/serve` | Hide storage paths |

**⚠️ The exception to masking:** `UserAdminResponse` has `private String aadhaarNumber;`, filled from the plaintext `User.aadhaarNumber` (Chapter 4). The admin user list returns **full Aadhaar numbers**.

## 8.4 Mappers: three different styles in one codebase

| Style | Where | How it looks |
|---|---|---|
| **A. MapStruct** (code generated at compile time) | `GeoMapper`, `TempleMapper`, `NoticeMapper` (used); `TrustMapper` (**never used**) | interface + `@Mapping` annotations |
| **B. Hand-written `@Component` mapper** | `DeclarationAssetMapper` | Plain Java: 18 methods, `toXEntity(request, declarationId)` / `toXResponse(entity)` |
| **C. Private builder methods inside the service** | `TrustServiceImpl.toResponse` / `toBoardResponse` / `toFinancialResponse`, `DeclarationServiceImpl.buildCompleteResponse` / `toSummaryResponse`, most DC / TA / admin services | `XResponse.builder().a(e.getA())….build()` |

Most conversion in this codebase is **style C**. So "where is entity X turned into JSON?" usually means **"look for `toResponse` / `build…Response` at the bottom of the service"**, not in `mapper/`.

### A. MapStruct: how it works

**The idea:** you write an interface saying *what* maps to what. During `mvn compile`, the MapStruct annotation processor **writes the implementation class for you**. It is plain getter/setter code with no reflection at runtime.

You write ([GeoMapper.java](../backend/src/main/java/com/templeregistry/mapper/geo/GeoMapper.java)):
```java
@Mapper(componentModel = "spring")                 // generated class gets @Component → injectable
public interface GeoMapper {
    @Mapping(target = "cityId", source = "city.id")  // name differs / nested → say so
    DistrictResponse toDistrictResponse(District entity);   // id, name, code match by name → automatic
}
```
MapStruct generates this (actual file: `target/generated-sources/annotations/com/templeregistry/mapper/geo/GeoMapperImpl.java`):
```java
@Component
public class GeoMapperImpl implements GeoMapper {
    public DistrictResponse toDistrictResponse(District entity) {
        if ( entity == null ) return null;
        DistrictResponse.DistrictResponseBuilder districtResponse = DistrictResponse.builder();  // uses the Lombok builder
        districtResponse.cityId( entityCityId( entity ) );   // null-safe helper for entity.getCity().getId()
        districtResponse.code( entity.getCode() );
        districtResponse.id( entity.getId() );
        districtResponse.name( entity.getName() );
        return districtResponse.build();
    }
}
```
That's all it is. Open that file in `target/` whenever a MapStruct mapping puzzles you.

The build wiring (from `pom.xml`, Chapter 0):
- annotation processors in order **Lombok → MapStruct → `lombok-mapstruct-binding`**. MapStruct must see the getters and builders Lombok generates.
- `-Amapstruct.defaultComponentModel=spring`, so the generated classes become Spring beans.

MapStruct annotations used here:

| Annotation | Meaning | Real use |
|---|---|---|
| `@Mapping(target, source)` | copy `source` path into `target` field | `cityId ← city.id`; `TempleSearchResultResponse.id ← templeId` |
| `@Mapping(target, ignore = true)` | don't set this field | `TempleMapper.fromCreateRequest` ignores **22** fields (`id`, audit columns, `version`, `status`, `verificationStatus`, `hobli`, …) |
| `@Mapping(target, expression = "java(…)")` | compute with Java code | `TrustMapper`: `active = trust.getStatus() == TrustStatus.ACTIVE` |
| `@BeanMapping(builder = @Builder(disableBuilder = true))` | use `new Entity()` + setters instead of the builder | Needed for entities with Lombok `@SuperBuilder` (all `BaseEntity` children), which MapStruct can't drive |
| `@MappingTarget` | **update** an existing object instead of creating one | `TrustMapper.updateFromRequest(rq, @MappingTarget Trust trust)` |
| `imports = TrustStatus.class` | make a class usable inside `expression` | `TrustMapper` |

**Why so many `ignore = true`?** MapStruct warns about every target field it can't fill. The long lists do two jobs: they silence those warnings, and they **document that a client can't set these fields**. For example, `TempleMapper.fromCreateRequest` can never set `status` or `verificationStatus` from the request. That is a security property expressed as mapping config.

### B. `DeclarationAssetMapper`: hand-written, with renames
The request DTO field names (frontend vocabulary) and the entity/column names (DB vocabulary) differ, and this class is the translation table:

| Request field (JSON) | Entity field (column) | Note |
|---|---|---|
| `AgriLandItemRequest.village` | `DeclImmovAgriLand.location` | |
| `.ownerOfRecord` | `.encumbrance` | **different concepts**: owner vs. legal burden on the land |
| `.pattaStatus` | `.ownershipType` | |
| `BuildingItemRequest.totalAreaSqft` / `yearBuilt` / `valuationInr` | `areaSqft` / `yearOfConstruction` / `valuation` | |
| `LeasedPropertyItemRequest.monthlyRent` | `monthlyRent` **and** `annualRent` | ⚠️ `annualRent(request.getMonthlyRent())` stores the monthly value as annual |
| `ArtifactItemRequest.itemDescription` | `description` **and** `name` | same value twice |
| `ArtifactItemRequest.material` | `artifactType` **and** `material` | same value twice |
| `VehicleItemRequest.makeModel` / `year` / `purpose` | `makeAndModel` / `yearOfPurchase` / `usagePurpose` | the entity's `currentValue`, `vehicleType`, `insuranceValidTill` are **never filled**: the request has no such fields |
| `FinancialAssetItemRequest.bankName` / `investmentType` / `amount` | `institutionName` / `description` / `currentValue` | |

The **`annualRent` bug has a visible effect.** `DcTempleProfileServiceImpl` (line 353) sends `.annualRent(e.getAnnualRent())` to the DC's full profile view. So the DC sees a leased property's *annual* rent **12× too low** (it's actually the monthly figure). The TA's own view uses `toLeasedPropertyResponse`, which prefers `monthlyRent`, so the TA never notices.

### C. Service builder methods: `TrustServiceImpl.toResponse`
```java
private TrustResponse toResponse(Trust t) {
    String[] bankParts = splitBankNameAndBranch(t.getBankNameAndBranch());
    return TrustResponse.builder()
        .id(t.getId())
        .workflowInstanceId(workflowEngineAdaptor.getWorkflowInstanceId(WorkflowEntityType.TRUST, t.getId()))  // ← DB query
        .maskedPanNumber(maskPan(t.getTrustPANNumber()))
        .maskedBankAccountNumber(maskBankAccount(t.getBankAccountNumber()))
        .bankName(bankParts[0]).bankBranch(bankParts[1])
        .governanceStatus(governanceStatusResolver.resolve(WorkflowEntityType.TRUST, t.getId()))              // ← DB query
        ...
        .build();
}
```
Style C gives full control (masking, enrichment), which is probably why the unused `TrustMapper` was abandoned. The cost: this "mapper" makes **2 DB queries per trust**. For a list endpoint, that's the N+1 pattern from Chapter 4, hidden inside a method whose name sounds harmless. The same pattern (`governanceStatusResolver.resolve` per item) appears in declaration and staging responses.

The unused `TrustMapper.fromCreateMemberRequest` maps `aadhaarNumber → aadhaarEncrypted` while *ignoring* `aadhaarHash` and `aadhaarLast4`. If anyone switched to it, duplicate detection and masking would silently break. Deleting the unused mapper would remove that trap.

## 8.5 The one place entities go straight to JSON (and why it likely fails)

`WorkflowController` returns **entities** from `ClarificationEngine`, typed loosely as `ApiResponse<?>`:
```java
var thread = clarificationEngine.requestClarification(instanceId, request, ctx.getActorId(), null);  // ClarificationThread entity
return ResponseEntity.ok(ApiResponse.success("Clarification requested.", thread));
// likewise: respond(...) → ClarificationMessage entity; getThreads(...) → List<ClarificationThread>
```
When Jackson serializes a `ClarificationThread`:
1. `workflowInstance` is `@ManyToOne(fetch = LAZY)`, so it's a **Hibernate proxy**. Jackson finds the proxy's internal `hibernateLazyInitializer` field and, without the `jackson-datatype-hibernate` module (not in `pom.xml`), normally throws *"No serializer found for class …ByteBuddyInterceptor"*.
2. `messages` → each `ClarificationMessage.thread` → back to the same thread → `messages` → … is an **infinite loop**, because there's no `@JsonIgnore` / `@JsonManagedReference` anywhere on these classes.

**Likely result: HTTP 500** on these four endpoints, all called by [workflowApi.ts](../frontend/src/features/governance/workflowApi.ts#L60-L105):
- `POST /api/v2/workflow/{id}/clarification`
- `POST …/clarification/{threadId}/respond`
- `GET …/clarification`
- (`POST …/resolve` returns 204, so it's unaffected)

Worse, for the two POSTs, serialization happens **after** the service's transaction has committed. The clarification is **saved**, but the user sees an error, and a retry creates a **duplicate**. This is a textbook example of why this chapter's first rule exists. I haven't executed it; one `curl` against the running app would confirm.

## 8.6 Envelope DTOs: responses inside responses

Two big composite responses:

**`WorkflowEnvelope<T>`** (`dto/response/workflow`), assembled by `WorkflowEnvelopeAssembler` for the `/api/v2/declarations/{id}`, `/api/v2/trusts/{id}`, … endpoints:
```json
{ "success": true, "message": "...",
  "data": {                                   ← WorkflowEnvelope
      "data":         { …DeclarationResponse… },
      "workflow":     { "instanceId": 88, "status": "UNDER_REVIEW", "version": 4,
                        "currentActor": "DC", "availableActions": [ {…APPROVE…}, {…SEND_BACK…} ],
                        "hasUnapprovedChanges": false, "versionSummary": {…}, "auditSummary": {…} },
      "clarification": { … },
      "notifications": { … } } }
```
The idea is **one response tells the UI everything**: the record, its workflow state, and *which buttons to show* (`availableActions`). Note the double `data`, because it is `ApiResponse.data` → `WorkflowEnvelope.data`.

**`TempleFullProfileResponse`** (`dto/response/dc`) is built by `DcTempleProfileServiceImpl` from 24 repositories (Chapter 6). It includes the `DeclImmov*Response` / `DeclMov*Response` DTOs, which are separate from the TA-side `*ItemResponse` DTOs. So **the same asset has two response shapes**: TA-side via `DeclarationAssetMapper`, DC-side built by hand in `DcTempleProfileServiceImpl`. That's how the `annualRent` discrepancy above appears only on the DC side.

## 8.7 The full round trip, with real classes

```text
REQUEST (create declaration)
POST /api/v1/temples/42/declarations
{ "financialYear":"2025-26", "dueDate":"2026-03-31",
  "vehicles":[{"registrationNumber":"KA01AB1234","makeModel":"Tata Ace","year":2019,"purpose":"Prasadam delivery"}],
  "leasedProperties":[{"propertyAddress":"Shop 3","lesseeName":"R. Kumar","monthlyRent":5000, ...}] }
      │ Jackson
      ▼
CreateDeclarationRequest  ──@Valid──► VehicleItemRequest/LeasedPropertyItemRequest checked (nested @Valid)
      │ DeclarationServiceImpl.create
      ▼
AssetDeclaration.builder()… (header)                               INSERT asset_declarations
replaceAssetItems → DeclarationAssetMapper.toVehicleEntity(rq, id) INSERT decl_mov_vehicle (make_and_model='Tata Ace', year_of_purchase=2019 …)
                  → toLeasedPropertyEntity(rq, id)                 INSERT decl_immov_leased (monthly_rent=5000, annual_rent=5000 ⚠)
      │
      ▼
RESPONSE
buildCompleteResponse(saved) → DeclarationAssetMapper.toVehicleResponse / toLeasedPropertyResponse
      → CompleteDeclarationResponse → ApiResponse.success("Declaration created.", …) → 201 JSON
```

## 8.8 The five questions, for two mappers

```text
Who calls me?   DeclarationServiceImpl (replaceAssetItems, buildCompleteResponse)
      ↓
[DeclarationAssetMapper]   (@Component, hand-written, no MapStruct)
      ↓
Who do I call?  nothing — pure field copying

INPUT   9 kinds of *ItemRequest + declarationId   |  9 kinds of Decl* entities
  ↓     rename frontend vocabulary ↔ DB vocabulary (8.4 table)
OUTPUT  Decl* entities (unsaved)                   |  *ItemResponse DTOs
```
```text
Who calls me?   TempleServiceImpl (create, getById, getCurrentProfile, search)
      ↓
[TempleMapper] → generated TempleMapperImpl (@Component)
      ↓
Who do I call?  nothing (generated getters/setters)

INPUT   CreateTempleRequest | Temple | TempleSearchSummary
  ↓     22 ignored targets on create (client can't set status/verification/audit fields)
OUTPUT  Temple (new, unsaved) | TempleResponse (districtName/cityName added later by the service) | TempleSearchResultResponse
```

## 8.9 Findings from this chapter

| Severity | Finding | Where |
|---|---|---|
| 🟠 | **Entities serialized directly** (`ClarificationThread` / `ClarificationMessage`, lazy proxy + bidirectional cycle): likely 500 on clarification create/respond/list, *after* commit, so retries duplicate | `WorkflowController` 2 POSTs + 1 GET ← `ClarificationEngine` |
| 🟠 | `annualRent` stored as the monthly value; DC full profile shows annual rent 12× too low | `DeclarationAssetMapper.toLeasedPropertyEntity`, `DcTempleProfileServiceImpl:353` |
| 🔴 (repeat) | `UserAdminResponse.aadhaarNumber` returns full plaintext Aadhaar | `AdminServiceImpl` |
| 🟡 | Declaration FY validated only by regex (future / inconsistent years accepted); `@ValidFinancialYear` used on one field only | `CreateDeclarationRequest` |
| 🟡 | "Mapper" methods that query the DB per item (N+1 in lists) | `TrustServiceImpl.toResponse`, similar in declaration/staging |
| 🟡 | Semantic renames that look wrong (`ownerOfRecord → encumbrance`); vehicle value/type never captured | `DeclarationAssetMapper` |
| ⚪ | `TrustMapper` entirely unused; its board-member mapping would skip Aadhaar hash/last-4 if ever adopted | `mapper/trust` |
| ⚪ | Two response shapes per asset type (TA vs DC) | `dto/response/declaration` vs `dto/response/dc` |

## 8.10 Adding a DTO: the checklist for this codebase

1. **Request:** `dto/request/<module>/XRequest` with `@Getter @Setter @NoArgsConstructor`. Add constraints on every field the client must send, and `@Valid` on nested objects and lists. Avoid `@Data` if the DTO carries PII.
2. **Response:** `dto/response/<module>/XResponse` with `@Getter @Builder`. Include only what the screen needs; **mask** PII; **never** put an entity (or a `List` of entities) inside.
3. **Mapping:** simple one-to-one → a MapStruct interface in `mapper/<module>/`, with `ignore = true` for everything the client must not set, and `disableBuilder = true` when the target extends `BaseEntity`. Needs masking or enrichment → a private `toResponse` in the service, but **don't query the DB inside it** for list endpoints; batch-load first.
4. **Controller:** `@Valid @RequestBody XRequest`, returning `ApiResponse<XResponse>`, never `ApiResponse<?>`. The wildcard is what let entities slip through in 8.5.

---

**Next: Chapter 9 — Exception Handling.**
- all 19 custom exceptions and the HTTP status each maps to in `GlobalExceptionHandler`, including the catch-all `Exception → 500`
- how validation errors are formatted
- what happens to exceptions thrown in filters vs controllers vs `@Async` threads vs schedulers
- which Spring/JPA exceptions are *not* handled and what the client then sees
- how rollback relates to each exception type

Say **"continue"** when ready.
