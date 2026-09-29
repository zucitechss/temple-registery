# CHAPTER 7 — Repositories

Repositories are the **database clerks**: services ask them for data, and they turn those requests into SQL. In this project you write *almost no implementation code* for them.

## 7.0 What a repository is, and what you get for free

```java
@Repository
public interface DistrictRepository extends JpaRepository<District, Long> { ... }
//                                                       ▲        ▲
//                                              entity type   type of its @Id
```

It is an **interface**, with no class and no method bodies. At startup, Spring Data generates an implementation (a proxy, Chapter 2 §④d) and registers it as a bean. Here: **62 repository interfaces** extend `JpaRepository`. Three of them also extend `JpaSpecificationExecutor`: `TempleRepository`, `TempleSearchSummaryRepository` and `NoticeRepository`.

Methods inherited **for free** from `JpaRepository`, all of which this codebase uses:

| Method | SQL (roughly) | Notes |
|---|---|---|
| `save(entity)` | `INSERT` if `id == null`, otherwise `UPDATE` (via `merge`) | Returns the saved instance. **Use the return value** |
| `saveAll(list)` | many INSERT/UPDATE | `TempleSearchSummaryServiceImpl.rebuildAll` |
| `findById(id)` | `SELECT … WHERE id = ? AND is_deleted = false` | Returns `Optional<T>` |
| `findAll()` / `findAll(Sort)` / `findAll(Pageable)` | `SELECT …` (+ `ORDER BY`, + `LIMIT/OFFSET`) | `GeoServiceImpl.listAllDistricts` uses `findAll(Sort.by("name"))` |
| `existsById`, `count()` | `SELECT count(*) …` | |
| `delete(entity)` / `deleteById(id)` | the entity's `@SQLDelete` (soft) or a real `DELETE` | Chapter 4 §4.3 |
| `deleteAllInBatch()` | **one** `DELETE FROM table` with no per-row loading | `rebuildAll` clears `temple_search_summary` this way |
| `flush()` | push pending changes now | |

> **Why `Optional`?** `findById` can't promise the row exists. `Optional<Temple>` forces the caller to decide what "not found" means. In this codebase that's almost always `.orElseThrow(() -> new EntityNotFoundException("Temple", id))`, which becomes a 404 (Chapter 9).

Beyond the inherited methods, the codebase writes its own queries in **four styles**.

---

## 7.1 Style 1: derived queries (the method *name* is the query)

Spring Data **parses the method name** into a query at startup. Grammar:

```text
 find | exists | count | delete   [Top/First N]   By   <Property><Operator>   [And|Or ...]   [OrderBy<Property>Asc|Desc]
```

Real examples from this project, decoded:

| Method (real) | Generated JPQL / SQL (roughly) |
|---|---|
| `DistrictRepository.findAllByCityId(Long cityId)` | `WHERE d.city.id = ?` → `WHERE city_id = ?` |
| `DistrictRepository.findAllByCityStateId(Long stateId)` | walks `city` → `state` → `id`: `JOIN cities c ON d.city_id = c.id WHERE c.state_id = ?` |
| `UserRepository.findByUsername(String)` | `WHERE username = ?` → `Optional<User>` |
| `DeclarationRepository.findTopByTempleIdAndFinancialYearOrderByVersionNumberDesc(templeId, fy)` | `WHERE temple_id = ? AND financial_year = ? ORDER BY version_number DESC LIMIT 1` |
| `DeclarationRepository.existsByTempleIdAndStatusIn(templeId, statuses)` | `SELECT 1 … WHERE temple_id = ? AND status IN (?, ?, …) LIMIT 1` → `boolean` |
| `DeclarationRepository.countByTempleIdAndIsOverdueTrueAndStatusNotIn(templeId, statuses)` | `SELECT count(*) … WHERE temple_id = ? AND is_overdue = true AND status NOT IN (…)` |
| `DeclarationRepository.findByIsOverdueTrueAndDistrictId(districtId, pageable)` | `WHERE is_overdue = true AND district_id = ?` + `LIMIT/OFFSET` + count query |
| `DeclarationRepository.findAllByDistrictIdAndStatusAndFinancialYear(d, s, fy, pageable)` | three `AND`ed equals + paging |
| `WorkflowInstanceRepository.findByEntityTypeAndEntityId(type, id)` | `WHERE entity_type = ? AND entity_id = ?` (the unique pair, Chapter 4 §4.6) |
| `Decl*Repository.deleteByDeclarationId(id)` | **SELECT all matching rows, then one `DELETE` per row** (needs a transaction) |

Keyword cheat-sheet (only words actually used here): `And`, `Or`, `In`, `NotIn`, `True`, `False`, `IsNull`, `OrderBy…Asc/Desc`, `Top`/`First`, `ContainingIgnoreCase`, `StatusIn`, and property paths like `CityState`.

In this codebase: **15 `countBy`, 18 `existsBy`, 11 `deleteBy`, 11 `findTop`/`findFirst`** methods, plus many `findBy`/`findAllBy`.

**The safety net:** a typo such as `findAllByCityStatId` fails **at startup** ("No property 'statId' found for type 'City'"), not at runtime.

---

## 7.2 Style 2: JPQL with `@Query` (71 of them)

When a method name would be unreadable, or the query needs something names can't express (`!=`, `LIKE` on an expression, `COUNT(...) > 0`, joins between unrelated entities), the code writes **JPQL**.

**JPQL in simple words:** SQL-like, but you write **Java class and field names**, not table and column names. Hibernate translates it.

```java
@Query("SELECT d FROM AssetDeclaration d WHERE d.districtId = :districtId AND d.status != 'DRAFT'")
Page<AssetDeclaration> findAllByDistrictIdExcludingDraft(Long districtId, Pageable pageable);
```
| Piece | Meaning |
|---|---|
| `AssetDeclaration d` | the **entity class**, not the table `asset_declarations` |
| `d.districtId` | the Java **field**, which Hibernate maps to `district_id` |
| `:districtId` | a **named parameter**, bound to the method argument of the same name. It works without `@Param` because `spring-boot-starter-parent` compiles with `-parameters` (keeps argument names). Some methods add `@Param` anyway, and both work |
| `'DRAFT'` | a string literal compared to the enum column. It works because the DB stores enum *names* |
| `Pageable` + `Page<>` | Spring adds `ORDER BY`/`LIMIT`/`OFFSET` and runs a second `COUNT` query (7.5) |
| soft delete | `@SQLRestriction` still adds `AND is_deleted = false` automatically |

**What this query is for:** DCs must not see TA drafts. The comment says *"DCs should not see TA workspace drafts."* There are four such `…ExcludingDraft` queries.

JPQL features you'll meet in this codebase:

**Text-block queries with `IN` lists** (`WorkflowInstanceRepository`, the DC dashboard):
```java
@Query("""
    SELECT wi FROM WorkflowInstance wi
    WHERE wi.districtId = :districtId
      AND wi.status IN :statuses            -- a List<WorkflowStatus> expands to IN (?, ?, ?)
      AND wi.deleted = false                -- written by hand: WorkflowInstance has no @SQLRestriction
    ORDER BY wi.statusUpdatedAt DESC
    """)
Page<WorkflowInstance> findByDistrictAndStatuses(Long districtId, List<WorkflowStatus> statuses, Pageable pageable);
```

**Joining two entities that have no JPA relationship** (Hibernate 6 "entity join"), in `TempleProfileStagingRepository`:
```java
@Query("SELECT s FROM TempleProfileStaging s JOIN WorkflowInstance wi " +
       "ON wi.entityId = s.id AND wi.entityType = 'TEMPLE_PROFILE' " +
       "WHERE s.templeId = :templeId AND wi.versionNumber = :versionNumber")
```
This is how the code bridges the polymorphic `(entity_type, entity_id)` link from Chapter 4. There's no `@ManyToOne` to follow, so the query states the join condition itself.

**Boolean from JPQL:** `SELECT COUNT(s) > 0 FROM TempleProfileStaging s JOIN WorkflowInstance wi … WHERE … wi.status = :status`

**`JOIN FETCH` to avoid N+1:** `TempleRepository.findAllWithFullGeo()` (Chapter 4 §4.7).

**Projections of one column:** `SELECT d.acknowledgementNumber FROM AssetDeclaration d WHERE d.acknowledgementNumber LIKE :prefix%` returns `List<String>`. It's used by the ack generator from Chapter 6. I found **no interface/DTO projections** (`interface XView {…}`); queries return entities or single columns.

---

## 7.3 Style 3: native SQL (3 queries)

`nativeQuery = true` means "send this SQL to the DB exactly as written". It uses real table names, applies **no** `@SQLRestriction`, and is DB-specific.

| Query | Why native | Soft delete? |
|---|---|---|
| `AccessControlPolicyRepository.findByTargetKeyAndSubjectIncludingDeleted` — `SELECT * FROM access_control_policies WHERE target_key = … LIMIT 1` | To **see soft-deleted rows** on purpose, so a batch upsert can revive a deleted policy (its Javadoc says so) | intentionally ignored |
| `TempleProfileStagingRepository.findMaxVersionNumberByTempleId` — `SELECT MAX(version) FROM temple_profile_staging WHERE temple_id = …` | Next version number must count deleted drafts too, so numbers aren't reused | intentionally ignored |
| `RateRequestLogRepository.upsertCount` — `INSERT … ON DUPLICATE KEY UPDATE request_count = request_count + 1` | An **atomic upsert** for rate limiting, with no read-then-write race. MySQL/TiDB-specific syntax | n/a (no soft delete) |

Rule for reviewing a native query in this codebase: **ask whether it should have `AND is_deleted = false`.** Here, all three are right to leave it out.

---

## 7.4 Style 4: Specifications (dynamic filters)

**The problem:** the temple search has about 12 optional filters. With derived names you'd need a method per combination. A **Specification** builds the `WHERE` clause **at runtime** from whichever filters are present.

[TempleServiceImpl.buildSpec](../backend/src/main/java/com/templeregistry/service/impl/temple/TempleServiceImpl.java#L210-L229):
```java
private Specification<TempleSearchSummary> buildSpec(TempleSearchFilterRequest f, Long districtId) {
    return (root, query, cb) -> {                              // ① a lambda Spring calls when building the query
        List<Predicate> predicates = new ArrayList<>();         // ② collect WHERE conditions
        if (districtId != null) predicates.add(cb.equal(root.get("districtId"), districtId));   // ③
        if (f.getTalukId() != null) predicates.add(cb.equal(root.get("talukId"), f.getTalukId()));
        if (f.getGrade() != null && !f.getGrade().isEmpty())
            predicates.add(root.get("grade").in(f.getGrade()));                                  // IN ('A','B')
        if (f.getKeyword() != null && !f.getKeyword().isBlank())
            predicates.add(cb.like(cb.lower(root.get("name")), "%" + f.getKeyword().toLowerCase() + "%"));
        ...
        return cb.and(predicates.toArray(new Predicate[0]));   // ④ AND them all (empty list → no WHERE)
    };
}
// caller:
Page<TempleSearchSummary> page = summaryRepository.findAll(spec, PageRequest.of(filter.getPage(), size, sort));
```
| # | Piece | Plain meaning |
|---|---|---|
| ① | `(root, query, cb)` | `root` = "the table row" (`TempleSearchSummary`); `query` = the query being built; `cb` = **CriteriaBuilder**, a factory for conditions |
| ③ | `cb.equal(root.get("districtId"), 3)` | `district_id = ?` (bound as a parameter, so **no SQL injection**) |
| ④ | `cb.and(...)` | joins everything with `AND` |
| call | `findAll(spec, pageable)` | comes from `JpaSpecificationExecutor` |

For `?districtId=3&grade=A&grade=B&keyword=shiva` the SQL is roughly:
```sql
SELECT … FROM temple_search_summary
WHERE district_id = 3 AND grade IN ('A','B') AND LOWER(name) LIKE '%shiva%'
ORDER BY name ASC LIMIT 10 OFFSET 0;
SELECT COUNT(*) FROM temple_search_summary WHERE …same…;
```

Two things to know about this spec:
- **Three filters are silently ignored.** `TempleSearchFilterRequest` has `cityId`, `hasApprovedDeclaration` and `pendingProfileReview`, but `buildSpec` never reads them. A frontend sending them gets unfiltered results.
- **LIKE wildcards aren't escaped.** A keyword containing `%` or `_` acts as a wildcard. This is harmless (it's still a bound parameter, not injection), just imprecise.

`NoticeSpecification` is the same idea as reusable static factories:
- `forDistrictManager(districtId, status, priority, search)` builds `(district_id = ? OR scope = 'GLOBAL') AND …`. If a misconfigured DC account has a null `districtId`, it falls back to **GLOBAL only**, which fails safe.
- `forAdmin(districtId, scope, status, priority, search)`.

`NoticeServiceImpl` (lines 172 and 185) passes these to `noticeRepository.findAll(spec, pageable)`.

---

## 7.5 Pagination, end to end

```text
?page=2&size=10                                   (controller: @RequestParam int page, int size)
      ▼
DeclarationServiceImpl.listByTemple(templeId, 2, 10)
      PageRequest pageable = PageRequest.of(
            2,                                     // zero-based: this is the THIRD page
            paginationUtil.clampSize(10),          // 1..100, default 10
            Sort.by(desc("financialYear"), desc("versionNumber"), desc("id")));
      ▼
declarationRepository.findAllByTempleId(templeId, pageable)          → Page<AssetDeclaration>
      SQL 1: SELECT … WHERE temple_id=? AND is_deleted=false ORDER BY financial_year DESC, version_number DESC, id DESC LIMIT 10 OFFSET 20
      SQL 2: SELECT COUNT(*) … WHERE temple_id=? AND is_deleted=false          ← for totalElements/totalPages
      ▼
.map(this::toSummaryResponse)                      → Page<DeclarationResponse>   (entity → DTO, keeps paging info)
      ▼
PaginatedResponse.of(page)                          → {content, page, size, totalElements, totalPages, last}
```

| Type | What it is |
|---|---|
| `Pageable` / `PageRequest.of(page, size, sort)` | "Which slice do I want, in what order?" |
| `Page<T>` | the slice **plus** totals. Costs **two queries** |
| `page.map(fn)` | convert each element but keep the paging metadata. The standard way to go from entities to DTOs here |
| `PaginatedResponse<T>` | the project's own JSON shape (Chapter 1), so the frontend doesn't depend on Spring's `Page` JSON |

A stable sort matters for paging. Adding `id DESC` as the last sort key (as `listByTemple` does) guarantees no row appears on two pages. `TempleServiceImpl.search` sorts by `name` only, so temples with the same name could shuffle between pages.

---

## 7.6 `@Modifying` bulk updates (11 of them)

```java
@Modifying
@Query("UPDATE AssetDeclaration d SET d.isOverdue = true, d.overdueFlaggedAt = :now " +
       "WHERE d.dueDate < :today AND d.status NOT IN :terminalStatuses AND d.isOverdue = false")
int markOverdue(LocalDate today, LocalDateTime now, List<DeclarationStatus> terminalStatuses);
```
- `@Modifying` is required for JPQL `UPDATE`/`DELETE`. It returns the **number of rows changed**.
- The caller must be in a **write transaction**, or you get `TransactionRequiredException`.
- It runs **directly in SQL**, so (Chapter 4 §4.8) it skips `@PreUpdate` (`updated_at` unchanged), skips `@Version`, and leaves already-loaded entities **stale**. None of the 11 uses `clearAutomatically = true`. If a method loads an entity, bulk-updates it, and then reads it again in the same transaction, it sees the old value.

The 11 bulk operations cover:
- refresh-token revocation (`revokeAllByUserId`)
- MFA recovery codes
- notification read / soft delete (`InAppNotificationRepository`, 4 methods)
- notice status
- expired idempotency cleanup (`DcIdempotencyRecordRepository.deleteExpiredBefore`)
- the rate-limit upsert
- `markOverdue`

---

## 7.7 Locking in queries

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT d FROM AssetDeclaration d WHERE d.id = :id")
Optional<AssetDeclaration> findByIdWithLock(Long id);     // → SELECT … FOR UPDATE
```
**Pessimistic lock in simple words:** "lock this row until my transaction ends. Anyone else who wants to lock it must wait." It's the opposite of `@Version` (optimistic: "detect the conflict at save time").

The Javadoc says it *"prevents concurrent approve/reject/clarify operations on the same row."* However, **its only caller is `DeclarationWorkflowServiceImpl`, which isn't a Spring bean** (Chapter 6). So in the live code, concurrent approvals are protected **only** by optimistic locking on `WorkflowInstance.lockVersion` and `AssetDeclaration.lockVersion`, plus the idempotency key. That's adequate for the same declaration. It doesn't cover the acknowledgement-number race between *different* declarations (Chapter 6 §6.2).

---

## 7.8 Soft delete × repositories: the inconsistency to watch

| Entity has `@SQLRestriction`? | What the repositories do | Result |
|---|---|---|
| **Yes** (Temple, Trust, Declaration, User, geo, …) | Some methods also add `…AndDeletedFalse` (e.g. `existsByTempleIdAndDeletedFalse`) | Redundant but harmless |
| **No**: `WorkflowInstance` (it extends `BaseEntity`, so it has `is_deleted`, but no restriction) | The JPQL dashboard queries add `AND wi.deleted = false` **by hand**. The derived ones (`findByEntityTypeAndEntityId`, `existsByEntityTypeAndEntityId`, `countByDistrictIdAndStatusIn`, `countByTempleIdAndStatusIn`) **don't** | If a workflow instance is ever soft-deleted, dashboard *lists* hide it but badge *counts* and lookups still include it |

In practice I found nothing that soft-deletes `WorkflowInstance`, so this is latent. Still, the rule for this codebase is: **for entities without `@SQLRestriction`, check every query for the deleted filter.**

---

## 7.9 The repositories that matter most

| Repository | Key methods | Used by | Why it matters |
|---|---|---|---|
| `UserRepository` | `findByUsername`, `findByEmail`, `findByPasswordResetTokenHash`, `findById` | `AuthServiceImpl`, `UserProfileServiceImpl`, `AdminServiceImpl` | Login and reset (Chapter 3) |
| `RefreshTokenRepository` | `findByTokenHash`, `@Modifying revokeAllByUserId` | `AuthServiceImpl`, `TokenRevocationGuard` | Token rotation |
| `TempleRepository` | `findWithGeoById` (`@EntityGraph`), `findAllWithFullGeo` (`JOIN FETCH`), `existsByRegistrationNumber`, `searchForAssignment`, Specifications | nearly all modules, guards | Scope checks need the geo chain |
| `TempleSearchSummaryRepository` | `findAll(spec, pageable)`, `deleteByTempleId`, `deleteAllInBatch` | `TempleServiceImpl.search`, `TempleSearchSummaryServiceImpl` | Public + DC search |
| `DeclarationRepository` | `…ExcludingDraft` (4), `findTopBy…OrderByVersionNumberDesc`, `countActivePendingByDistrict`, `markOverdue`, `findAcknowledgementNumbersByPrefix` | Declaration, Governance, DC dashboard, summary, ack | Versioning + DC visibility |
| `Decl*Repository` (9) | `findAllByDeclarationId`, `deleteByDeclarationId` | `DeclarationServiceImpl`, `SnapshotServiceImpl`, `DcTempleProfileServiceImpl` | Asset line items |
| `WorkflowInstanceRepository` | `findByEntityTypeAndEntityId`, `findByDistrictAndStatuses` / `…EntityTypesAndStatuses` (paged), `count…StatusIn`, `findOverdueInstances`, `findApproachingDeadlineInstances` | `WorkflowEngineImpl`, adaptor, DC dashboard, `OverdueWorkflowScheduler` | The heart of governance |
| `WorkflowTransitionRepository` | history by instance | `WorkflowController` (directly), `WorkflowHistoryServiceImpl` | Audit trail |
| `TrustRepository` / `BoardMemberRepository` | `existsByTempleIdAndDeletedFalse`, by-trust lists, Aadhaar-hash lookups | `TrustServiceImpl`, validation, repair job | One trust per temple, duplicate Aadhaar |
| `NoticeRepository` | Specifications, `@EntityGraph(attachments)`, `@Modifying` status | `NoticeServiceImpl`, `NoticeExpiryScheduler` | Notice board |
| `InAppNotificationRepository` | 4 × `@Modifying` read/soft-delete | `NotificationServiceImpl` | Inbox |

## 7.10 The five questions, for `DeclarationRepository`

```text
Who calls me?   DeclarationServiceImpl · GovernanceWorkflowServiceImpl · DcDashboardServiceImpl
                · DcTempleProfileServiceImpl · TempleSearchSummaryServiceImpl · AcknowledgementServiceImpl
                · OverdueWorkflowScheduler path · AdminServiceImpl
      ↓
[DeclarationRepository]  (interface — Spring Data generates the code)
      ↓
Who do I call?  Hibernate → JDBC → Hikari → TiDB table asset_declarations

INPUT   ids · templeId/districtId · status / status lists · financial year · Pageable · dates
  ↓     derived queries · JPQL (@Query) · one @Lock query (unused) · one @Modifying bulk update
OUTPUT  AssetDeclaration / Optional / Page / List<String> / long / boolean / int (rows updated)
        — every SELECT silently filtered by is_deleted = false
```

## 7.11 Findings from this chapter

| Severity | Finding | Where |
|---|---|---|
| 🟠 | **Declaration lists are readable across districts, drafts included.** `GET /api/v1/temples/{templeId}/declarations` → `DeclarationServiceImpl.listByTemple` has only `isAuthenticated()` + `ownershipGuard` (TA-only). It uses `findAllByTempleId` (not `…ExcludingDraft`) and makes no jurisdiction check. Any DC/DC_STAFF/AUDITOR/VIEWER can list any temple's declarations, including TA drafts | `DeclarationServiceImpl.java:137-146` |
| 🟡 | Search silently ignores `cityId`, `hasApprovedDeclaration`, `pendingProfileReview` | `TempleServiceImpl.buildSpec` |
| 🟡 | `findByIdWithLock` (pessimistic lock) is only used by dead code; live code relies on optimistic locking | `DeclarationRepository`, `DeclarationWorkflowServiceImpl` |
| 🟡 | `WorkflowInstance` soft-delete filter applied in JPQL lists but not in derived lookups/counts (latent) | `WorkflowInstanceRepository` |
| ⚪ | Bulk `@Modifying` without `clearAutomatically`: stale entities if reused in the same transaction | 11 methods |
| ⚪ | Search sorts by `name` only (unstable paging); LIKE wildcards unescaped | `TempleServiceImpl.search` |
| ⚪ | Derived `deleteByDeclarationId` issues SELECT + N DELETEs × 9 tables per save; a `@Modifying` bulk delete would be one statement each | `Decl*Repository` |

## 7.12 Which query style to use when adding one here

1. **Simple equality or `In`, up to about 3 conditions** → a derived method name (`findAllByDistrictIdAndStatus`).
2. **`!=`, `LIKE` on expressions, joins without a relationship, counts, or single-column results** → JPQL `@Query`.
3. **Many optional filters** → a `Specification` (add `JpaSpecificationExecutor<T>` to the repository).
4. **DB-specific tricks** (upsert, reading soft-deleted rows) → native, and write down why you're bypassing the soft-delete filter.
5. **Bulk change** → `@Modifying` + `@Query`. Call it inside `@Transactional`, and set `updated_at` yourself.
6. **Need related rows** → `@EntityGraph` or `JOIN FETCH`, never a loop of `findById`.

---

**Next: Chapter 8 — DTOs & Mappers.**
- why the 189 DTOs exist, and the request/response split
- validation annotations and the custom `@ValidFinancialYear`
- the MapStruct mappers (`GeoMapper`, `TempleMapper`, `TrustMapper`, `NoticeMapper`, `DeclarationAssetMapper`) and what their generated code looks like
- the hand-written builders used elsewhere
- masking and encryption at the DTO boundary
- the envelope DTOs (`WorkflowEnvelope`, `TempleFullProfileResponse`)

Say **"continue"** when ready.
