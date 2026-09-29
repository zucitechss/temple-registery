# CHAPTER 4 — Database & JPA

This chapter is about how Java classes become tables and rows. Before reading any single entity, you need one fact that shapes the whole data layer.

## 4.0 The key fact: this codebase links tables in two different ways

I pulled every relationship annotation across all 67 entities. There are only **17 JPA relationship fields in total**. Most modules connect tables using a **plain `Long` id column** instead.

| Style | How it looks in Java | Where it's used |
|---|---|---|
| **A. Real JPA association** | `@ManyToOne private City city;` | Geo chain, `Temple → Hobli`, `TemplePhoto`, `RefreshToken`, clarifications, notices, workflow transitions/versions |
| **B. Plain foreign-key id** | `@Column(name="temple_id") private Long templeId;` | **Trust, BoardMember, TrustFinancial, BoardMeeting, AssetDeclaration, all 9 `Decl*` item tables, Employee, Contractor, User, Observation, Document**… |

Some consequences to hold on to for the rest of this chapter:
- With **style A**, you can write `district.getCity().getState().getName()` and Hibernate loads the linked rows for you.
- With **style B**, `trust.getTempleId()` gives you only a number. To get the temple, the *service* must call `templeRepository.findById(trust.getTempleId())` itself. That is why the services are large: they do the joining by hand.
- The **database still has foreign keys** for most style-B links. `V1__initial_schema.sql` contains 44 FK clauses, such as `fk_trust_temple FOREIGN KEY (temple_id) REFERENCES temples (id)`. So the database knows the links even where Java doesn't.

There are **no `@OneToOne` and no `@ManyToMany`** anywhere in the project. Only `@ManyToOne` and `@OneToMany` exist, so I explain only those two.

---

## 4.1 How one entity maps to one table

**In simple words:** an `@Entity` class is a template for one row. Each field is one column. Hibernate reads the annotations once at startup (Chapter 2) and afterwards generates SQL for you.

Here's `Temple`, trimmed to show every *kind* of annotation it uses ([Temple.java](../backend/src/main/java/com/templeregistry/entity/temple/Temple.java)):

```java
@Entity                                                   // ① "this class is a table row"
@Table(name = "temples", indexes = {                      // ② table name + indexes
    @Index(name = "idx_temples_district_id", columnList = "district_id"), ... })
@SQLRestriction("is_deleted = false")                     // ③ soft-delete filter (4.3)
@SQLDelete(sql = "UPDATE temples SET is_deleted = true, updated_at = NOW(6) WHERE id = ? AND version = ?")
@Getter @Setter @SuperBuilder @NoArgsConstructor @AllArgsConstructor   // ④ Lombok
public class Temple extends BaseEntity {                  // ⑤ inherits id + audit columns

    @Version @Column(name = "version", nullable = false)  // ⑥ optimistic locking (4.4)
    private Long version;

    @Column(name = "registration_number", nullable = false, unique = true, length = 50)   // ⑦
    private String registrationNumber;

    @Enumerated(EnumType.STRING)                          // ⑧ store enum as its NAME
    @Column(name = "grade", nullable = true, length = 5)
    private TempleGrade grade;

    @Column(name = "history", columnDefinition = "TEXT")  // ⑨ exact SQL type
    private String history;

    @Column(name = "linked_institutions", columnDefinition = "JSON")   // JSON column held as a String
    private String linkedInstitutions;

    @Column(name = "hobli_id") private Long hobliId;      // ⑩ writable FK id …
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hobli_id", insertable = false, updatable = false)
    private Hobli hobli;                                  //    … plus a read-only association on the SAME column

    @Column(name = "district_id", nullable = false) private Long districtId;   // style B
    @Builder.Default @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TempleStatus status = TempleStatus.ACTIVE;    // ⑪ default value for the builder
}
```

| # | Annotation | Plain meaning | Effect here |
|---|---|---|---|
| ① | `@Entity` | Hibernate manages this class | Required, or `TempleRepository` can't exist |
| ② | `@Table(name, indexes)` | Table name, and which indexes to create | The `indexes` are used only when Hibernate creates or updates the schema (`ddl-auto: update`). Flyway's SQL defines the real ones |
| ⑦ | `@Column(nullable, unique, length)` | Column name and constraints | `nullable=false` also makes Hibernate refuse to insert null before it even reaches the DB |
| ⑧ | `@Enumerated(EnumType.STRING)` | Store `"A"` rather than `0` | Safe: reordering enum constants won't corrupt data. The default `ORDINAL` would store numbers |
| ⑨ | `columnDefinition` | Raw SQL type | `TEXT` for long text; `JSON` is stored and read as a plain `String`. The services parse it with Jackson |
| ⑩ | `insertable=false, updatable=false` | "This field is **read-only**; another field writes this column" | Explained in 4.5 |
| ⑪ | `@Builder.Default` | Lombok: keep this default when using `.builder()` | Without it, `Temple.builder().build()` would leave `status` null |

> **Why `@NoArgsConstructor`?** Hibernate builds entity objects by calling an empty constructor and then setting fields. Every entity needs one.

---

## 4.2 `BaseEntity`: the six columns most tables share

```java
@MappedSuperclass                                  // not a table; its fields are copied into each child table
@EntityListeners(AuditingEntityListener.class)     // lets Spring fill @CreatedBy / @LastModifiedBy
public abstract class BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)      private Long id;
    @Builder.Default @Column(name = "is_deleted", nullable = false) private boolean deleted = false;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)                    private LocalDateTime updatedAt;
    @CreatedBy      @Column(name = "created_by", nullable = false, updatable = false) private Long createdBy;
    @LastModifiedBy @Column(name = "updated_by", nullable = false)                    private Long updatedBy;

    @PrePersist protected void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }
    @PreUpdate  protected void onUpdate() { updatedAt = LocalDateTime.now(); }

    public boolean equals(Object o) { ... return id != null && id.equals(other.id); }
    public int hashCode() { return id != null ? id.hashCode() : System.identityHashCode(this); }
}
```

| Piece | Beginner explanation |
|---|---|
| `@MappedSuperclass` | "Copy my columns into every table that extends me." There's no `base_entity` table |
| `GenerationType.IDENTITY` | Use MySQL `AUTO_INCREMENT`. Hibernate **must run the INSERT immediately** to learn the new id, so it can't batch inserts. Fine at this scale |
| `updatable = false` | Hibernate never includes this column in an `UPDATE`, so `created_at` can't be overwritten by accident |
| `@PrePersist` / `@PreUpdate` | **Lifecycle callbacks**: Hibernate calls these just before an INSERT or UPDATE |
| `@CreatedBy` / `@LastModifiedBy` | Filled by `AuditingEntityListener` from `JpaAuditConfig.auditorProvider()`. The current JWT's `userId`, or `0` for background jobs |
| `equals`/`hashCode` on `id` | Two objects for the same row count as equal. A new, unsaved entity (id null) is only equal to itself. This is the recommended JPA pattern |

**Who extends `BaseEntity`?** 38 of the 67 entities. The rest have their own `@Id`, and therefore **no soft delete, no `created_by`, no `updated_at`**:
- audit tables: `AuditAuthEvent`, `AuditDataEvent`, `AuditExportEvent`, `GovernanceActionHistory`
- all 9 `Decl*` item tables, e.g. `DeclMovVehicle`
- `RefreshToken`, `InAppNotification`, `NotificationEvent`, `EmailDeliveryLog`, `NoticeRead`
- `TempleSearchSummary`, `TempleProfileCurrent`/`History`, `AcknowledgementSequence`, `ExportJobRecord`, `RateRequestLog`, `DcIdempotencyRecord`, `PhysicalVerificationHistory`, `DeclarationClarification`

That's deliberate for append-only logs. For the `Decl*` items, it means deleting them is a **real, hard `DELETE`** (see 4.6).

---

## 4.3 Soft delete: `@SQLRestriction` + `@SQLDelete`

**In simple words:** in a government registry you don't want to lose data. So "delete" marks the row as hidden instead of removing it.

```java
@SQLRestriction("is_deleted = false")
@SQLDelete(sql = "UPDATE trusts SET is_deleted = true, updated_at = NOW(6) WHERE id = ? AND lock_version = ?")
public class Trust extends BaseEntity { ... }
```

| You write | Hibernate actually runs |
|---|---|
| `trustRepository.findById(5)` | `SELECT ... FROM trusts WHERE id = 5 AND (is_deleted = false)` |
| `trustRepository.findAll()` | `SELECT ... FROM trusts WHERE (is_deleted = false)` |
| any JPQL `@Query` on `Trust` | the restriction is added automatically |
| `trustRepository.delete(trust)` | `UPDATE trusts SET is_deleted = true, updated_at = NOW(6) WHERE id = 5 AND lock_version = 3` |

**Where the restriction does *not* apply:**
1. **Native SQL** (`nativeQuery = true`, 3 places). Those must add `is_deleted = false` themselves.
2. **Unique constraints.** `users` has `uk_users_username`. A soft-deleted user still *holds* that username in the table. So `findByUsername("x")` says "not found", yet `INSERT` of a new user `x` fails with a duplicate-key error. The same applies to `uk_users_email` and `temples.registration_number`. This is a classic soft-delete trap; worth knowing when admin "delete user, re-create user" fails.
3. **Repository methods that also say `...AndDeletedFalse`** (for example `existsByTempleIdAndDeletedFalse`, `findByEnabledTrueAndDeletedFalse`). These are harmless but redundant: the filter is already applied.

Some tables soft-delete differently:
- `InAppNotification` uses a `deleted_at` timestamp, set through `@Modifying` bulk queries.
- `EmailOutbox` and `TempleTimelineEvent` have `@SQLRestriction` without extending `BaseEntity`.

---

## 4.4 Optimistic locking: `@Version`

Six entities have a `@Version` field:
- `Temple` (column `version`)
- `Trust`, `BoardMember`, `AssetDeclaration`, `WorkflowInstance` (column `lock_version`)
- `Notice`

**The problem it solves.** A DC and a TA both open declaration #7 at version 3. The DC approves and saves. The TA then saves an edit based on the stale copy. Without protection, the TA's save silently overwrites the approval.

**How it works.** Hibernate adds the version to every UPDATE:
```sql
UPDATE asset_declarations SET status = ?, ..., lock_version = 4
WHERE id = 7 AND lock_version = 3
```
- If 1 row changes → success, and the Java object now has version 4.
- If 0 rows change (someone else already moved it to 4) → Hibernate throws `OptimisticLockException`. Spring wraps it as `OptimisticLockingFailureException`, and `GlobalExceptionHandler` returns **409 `OPTIMISTIC_LOCK_CONFLICT`**. The whole transaction rolls back.

This is also why the `@SQLDelete` statements on versioned entities end with `AND version = ?` / `AND lock_version = ?`: Hibernate passes the id and then the version to a custom delete on a versioned entity.

`WorkflowInstance.lockVersion` is the important one. Every approve/reject goes through it (Chapter 10), so two DCs clicking *Approve* at the same moment can't both succeed.

---

## 4.5 The JPA associations (style A), one by one

### The full map of all 17
```text
                   State ◄──────── City ◄──────── District ◄──────── Taluk ◄──────── Hobli ◄────────  Temple
                  states  state_id cities city_id districts district_id taluks taluk_id hoblis hobli_id temples
                                                                                                        ▲
                                                                                        TemplePhoto ────┘ temple_id

  User ◄─────── RefreshToken   (user_id)

  WorkflowInstance ◄──── WorkflowTransition     (workflow_instance_id)
                   ◄──── EntityVersion          (workflow_instance_id)
                   ◄──── ClarificationThread    (workflow_instance_id)
                               │ 1                 ▲
                               │ @OneToMany        │ @ManyToOne
                               ▼ *                 │
                         ClarificationMessage ─────┘ thread_id
                               │ 1  ▲
                               ▼ *  │
                         ClarificationAttachment   (message_id)

  Notice 1 ──@OneToMany──► * NoticeAttachment ──@ManyToOne──► Notice   (notice_id)

  ◄── = @ManyToOne (arrow points at the "one" side; the FK column lives on the left)
```

### Relationship type 1: unidirectional `@ManyToOne` (the geo chain)

```java
public class District extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "city_id", nullable = false, foreignKey = @ForeignKey(name = "fk_districts_city"))
    private City city;
}
// City has NO List<District> field — the link is one-way.
```

| Aspect | Answer |
|---|---|
| Java side | `District.city` (many districts → one city) |
| Database side | `districts.city_id BIGINT NOT NULL` + `CONSTRAINT fk_districts_city FOREIGN KEY (city_id) REFERENCES cities(id)` |
| Owning side | **District**. The side holding `@JoinColumn` owns the FK: changing `district.setCity(x)` changes `city_id` |
| Inverse side | None (unidirectional) |
| `optional = false` | Hibernate treats the link as required. It can use an inner join and refuses to save with `city == null` |
| `fetch = LAZY` | Loading a District does **not** load its City until you call `district.getCity().getName()` (4.8) |
| **INSERT** | `District.builder().city(city).name(..).build()` then `save(d)` → `INSERT INTO districts (city_id, name, code, is_deleted, created_at, ...) VALUES (city.getId(), ...)` |
| **UPDATE** | `d.setCity(otherCity)` in a transaction → `UPDATE districts SET city_id = ?, updated_at = ?, ... WHERE id = ?` |
| **DELETE** | `districtRepository.delete(d)` → `@SQLDelete` → `UPDATE districts SET is_deleted = true ...`. The City is untouched: no cascade |
| Deleting a **City** with districts | Also just a soft-delete `UPDATE`, so the FK never complains. The districts stay, pointing at a hidden city. Lazy-loading `district.getCity()` for a soft-deleted city will likely fail with "entity not found", because `@SQLRestriction` hides it |

`Taluk → District`, `Hobli → Taluk` and `City → State` are identical in shape.

### Relationship type 2: the "double mapping" on `Temple.hobli_id`

```java
@Column(name = "hobli_id") private Long hobliId;                    // WRITES the column
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "hobli_id", insertable = false, updatable = false)
private Hobli hobli;                                                // READS the same column as an object
```
**Why both?** Two Java fields map to one column. Hibernate would be confused about which one writes it, so one must be read-only.
- **Writes** use the plain id. A service does `temple.setHobliId(12)` without loading a `Hobli`.
- **Reads** can walk the chain: `temple.getHobli().getTaluk().getDistrict().getId()`. That's exactly what `JurisdictionGuard.assertDistrictScope` does (Chapter 3).

**A trap:** after `temple.setHobliId(99)` in the same transaction, `temple.getHobli()` still returns the *old* hobli object until the entity is reloaded. The two fields only agree after a fresh read.

`Temple` also stores `talukId`, `cityId`, `districtId` as plain columns, so the geo chain is **denormalized**: the same fact is stored twice. `assertDistrictScope` prefers the chain and falls back to the flat `districtId` for temples with no hobli.

### Relationship type 3: bidirectional `@OneToMany` / `@ManyToOne` with cascade

This happens in three places: `ClarificationThread ↔ ClarificationMessage`, `ClarificationMessage ↔ ClarificationAttachment`, and `Notice ↔ NoticeAttachment`.

```java
// Parent (inverse side)
public class Notice extends BaseEntity {
    @OneToMany(mappedBy = "notice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<NoticeAttachment> attachments = new ArrayList<>();
}
// Child (owning side)
public class NoticeAttachment extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notice_id", nullable = false)
    private Notice notice;
}
```

| Term | Plain meaning here |
|---|---|
| **Bidirectional** | You can navigate both ways: `notice.getAttachments()` and `attachment.getNotice()` |
| **Owning side** = `NoticeAttachment.notice` | The side with `@JoinColumn`. **Only this field decides what goes into `notice_attachments.notice_id`** |
| `mappedBy = "notice"` | On the parent: "I'm the mirror. The real FK is managed by the field called `notice` in the child." Adding to the list alone does **not** set the FK. You must also call `attachment.setNotice(notice)` |
| `cascade = ALL` | Whatever you do to the parent (`persist`, `merge`, `remove`), Hibernate also does to each child in the list |
| `orphanRemoval = true` | If you **remove a child from the list**, Hibernate deletes that child |
| `fetch = LAZY` | The list loads only when first touched. `NoticeRepository` has `@EntityGraph(attributePaths = "attachments")` to load it in one query when needed |

What happens for each operation:

| Operation | Java | SQL |
|---|---|---|
| **INSERT** | `a.setNotice(n); n.getAttachments().add(a); noticeRepository.save(n);` | `INSERT notices ...` then `INSERT notice_attachments (notice_id=...)`, via cascade PERSIST |
| **UPDATE** | change `n.getTitle()` and `a.getFileName()` inside a transaction | one `UPDATE` each, via dirty checking (4.9) |
| **Remove one child** | `n.getAttachments().remove(a)` | orphanRemoval → delete `a` → its `@SQLDelete` → `UPDATE notice_attachments SET is_deleted = true` |
| **DELETE parent** | `noticeRepository.delete(n)` | cascade REMOVE → each attachment's `@SQLDelete`, then the notice's `@SQLDelete (... AND version = ?)`. Everything is **soft**-deleted |

For the clarification tables, the children *don't* extend soft-delete (`ClarificationMessage`/`Attachment` are `B--`: BaseEntity but no `@SQLRestriction`/`@SQLDelete`). So removing a child from the list is a **real `DELETE`**. The DB also has `fk_cm_thread` / `fk_ca_message` constraints.

### Relationship type 4: many children → `WorkflowInstance` (unidirectional)

```java
public class WorkflowTransition extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_instance_id", nullable = false, updatable = false)
    private WorkflowInstance workflowInstance;
    ... every column is updatable = false
}
```
`updatable = false` on *every* column makes a transition row **immutable** once written: an append-only history. `WorkflowInstance` has no list of transitions; you query them via `WorkflowTransitionRepository`. `EntityVersion` and `ClarificationThread` follow the same pattern.

### The remaining two
- **`RefreshToken → User`**: `@ManyToOne(LAZY, optional=false)` on `user_id`. The DB has `ON DELETE CASCADE`, but users are only soft-deleted, so it never fires.
- **`TemplePhoto → Temple`**: `@ManyToOne(LAZY)` on `temple_id`, with DB `ON DELETE CASCADE`.

---

## 4.6 The plain-id links (style B): where most of the data lives

```text
                                    temples (Temple)
          ┌──────────────┬──────────────┼───────────────┬───────────────┬─────────────────┐
     temple_id      temple_id       temple_id        temple_id       temple_id         temple_id
          │              │              │               │               │                 │
  trusts (Trust)  asset_declarations  employees    contractors     observations    temple_profile_staging
          │        (AssetDeclaration)                                              temple_profile_current/history
   trust_id│              │ declaration_id
    ┌──────┼──────┐        ├── decl_immov_agri_land   ├── decl_mov_artifact
    │      │      │        ├── decl_immov_building    ├── decl_mov_equipment
 board_  board_  trust_    ├── decl_immov_leased      ├── decl_mov_financial
 members meetings financials├─ decl_immov_other       ├── decl_mov_precious_metal
                           │                          └── decl_mov_vehicle
                           ├── asset_declaration_versions  (snapshots)
                           └── physical_verification_history

  users.temple_id → temples   users.district_id → districts (scope for DC / TA)
  documents: (owner_type, owner_id)  — polymorphic, no FK
```

Take `AssetDeclaration → DeclMovVehicle` as the example:

| Aspect | Answer |
|---|---|
| Java side | `DeclMovVehicle.declarationId` (a `Long`). `AssetDeclaration` has **no** list of vehicles |
| Database side | `decl_mov_vehicle.declaration_id` + `fk_dmv_declaration ... REFERENCES asset_declarations(id) ON DELETE CASCADE` |
| Owning side | Not a JPA concept here; the child just stores a number |
| **INSERT** | Service saves the declaration first (to get its id), then `vehicleRepository.save(DeclMovVehicle.builder().declarationId(decl.getId())...)` |
| **UPDATE** | `DeclarationServiceImpl` (lines ~626–634) **deletes all items and re-inserts** them: `agriLandRepository.deleteByDeclarationId(id); buildingRepository.deleteByDeclarationId(id); ...`. These are hard deletes (the `Decl*` classes have no soft delete) |
| **DELETE parent** | The declaration is soft-deleted (`UPDATE ... is_deleted = true`), so the DB's `ON DELETE CASCADE` **never fires**. The item rows remain |

About `deleteByDeclarationId`: a **derived delete** method in Spring Data first **SELECTs** the matching rows, then issues **one `DELETE` per row**. It needs a surrounding transaction. For 9 tables × N items that's many statements, and it's the reason these methods must run inside `@Transactional`.

**Why would a team choose style B?**
- **Pros:** no lazy-loading surprises, no accidental cascades, simple JSON serialization, and each module can load exactly what it needs.
- **Cons:** the services do all joining by hand (the extra `findById` calls you'll see everywhere), the compiler can't catch "wrong id type", and nothing in Java stops you saving a `templeId` that doesn't exist. Only the DB FK catches that.

On TiDB, FK constraints are only enforced from TiDB v6.6 onward. I can't tell your cluster version from the code, so check whether these FKs are actually enforced.

### The polymorphic link: `WorkflowInstance(entity_type, entity_id)`
```java
@Enumerated(EnumType.STRING) @Column(name = "entity_type", updatable = false)
private WorkflowEntityType entityType;   // TEMPLE_PROFILE, DECLARATION, TRUST, BOARD_MEMBER, EMPLOYEE, CONTRACTOR
@Column(name = "entity_id", updatable = false)
private Long entityId;                   // id in THAT type's table
// @Index(name = "idx_wi_entity", columnList = "entity_type, entity_id", unique = true)
```
One `workflow_instances` row can point to a row in *any* of six tables. That can't be a real FK, since a FK must target a single table. The **unique index** guarantees **at most one workflow per entity**. `WorkflowInstance` also copies `temple_id` and `district_id` so the DC dashboard can filter without joining. `Document` uses the same trick with `(owner_type, owner_id)`.

---

## 4.7 Fetching: LAZY, N+1, and `@EntityGraph`

**`LAZY` in simple words:** when you load a `Temple`, `temple.getHobli()` returns a **proxy**, a placeholder object with only the id. The first time you call a method on it, such as `.getTaluk()`, Hibernate runs a `SELECT` on `hoblis`. **Every association in this project is LAZY.** That's the right default.

**The N+1 problem.** In `JurisdictionGuard.assertDistrictScope`, walking `temple → hobli → taluk → district` lazily costs **3 extra queries per temple**. For a list of 50 temples, that's 1 + 150 queries.

**The fix used here**, from [TempleRepository.java](../backend/src/main/java/com/templeregistry/repository/temple/TempleRepository.java):
```java
@EntityGraph(attributePaths = {"hobli", "hobli.taluk", "hobli.taluk.district"})
Optional<Temple> findWithGeoById(Long id);          // one SELECT with LEFT JOINs across 4 tables

@Query("SELECT t FROM Temple t LEFT JOIN FETCH t.hobli h LEFT JOIN FETCH h.taluk ta " +
       "LEFT JOIN FETCH ta.district d LEFT JOIN FETCH d.city")
List<Temple> findAllWithFullGeo();                   // same idea in JPQL, for the whole table
```
- `@EntityGraph` = "for this one query, treat these paths as EAGER."
- `JOIN FETCH` = the JPQL way to say the same.

The Javadoc explicitly ties `findWithGeoById` to `assertDistrictScope`. **Rule for reading DC-module code:** if a method calls `assertDistrictScope`, check that it loaded the temple with `findWithGeoById`.

**Why `LazyInitializationException` doesn't blow up here.** A lazy proxy can only load while a DB session is open. `spring.jpa.open-in-view` isn't set in `application.yml`, so Spring Boot's default **`true`** applies. The session stays open for the whole HTTP request, including the controller and JSON serialization. That's convenient, but it can hide N+1 queries running *after* the service returns. Spring Boot logs a warning about this at startup. Background jobs (`@Async`, `@Scheduled`) don't get this, so lazy access there must happen inside `@Transactional`.

---

## 4.8 The persistence context and dirty checking

**In simple words:** during a transaction, Hibernate keeps a **notebook** (the *persistence context*) of every entity it loaded. At commit, it compares each entity with the snapshot it took on load. Any changed field becomes an `UPDATE`, **even if you never call `save()`**.

A real example, `TrustDataRepairService` (Chapter 2):
```java
@Transactional
public void repairLiveTrustData() {
    for (Trust trust : trustRepository.findAll()) {             // every Trust is now "managed"
        if (trust.getDateOfRegistration().isAfter(LocalDate.now()))
            trust.setDateOfRegistration(LocalDate.now());       // just a setter …
    }
}   // … at commit, Hibernate sees the change → UPDATE trusts SET date_of_registration=?, lock_version=?+1 ... WHERE id=? AND lock_version=?
```
Many services still call `repository.save(entity)` on already-loaded entities. That's harmless (it's a `merge` on a managed object, which does nothing extra) but not required.

**`@Transactional(readOnly = true)`** tells Hibernate not to take snapshots and not to flush. It's faster, and changes made by accident are *not* written. You saw it in `GeoServiceImpl.list*`.

**Bulk `@Modifying` queries skip the notebook entirely.** Example:
```java
@Modifying
@Query("UPDATE RefreshToken rt SET rt.revokedAt = :now WHERE rt.user.id = :userId AND rt.revokedAt IS NULL")
void revokeAllByUserId(Long userId, LocalDateTime now);
```
It runs SQL directly. **No `@PreUpdate`**, so `updated_at` isn't touched; **no `@Version` increment**; and entities already in the notebook become stale. There are 11 of these (notification read/unread, idempotency cleanup, notice status, and so on).

---

## 4.9 Converters: turning Java values into column values

### Enum converters (legacy-tolerant)
Most enums use `@Enumerated(EnumType.STRING)`. Five use a custom `AttributeConverter` instead, all with `autoApply = false`, so they're applied only where `@Convert` names them:

| Converter | Used on | Why |
|---|---|---|
| `DeclarationStatusConverter` | `AssetDeclaration.status` | Maps old names (`PENDING_REVIEW` → `SUBMITTED`, `CLARIFICATION_REQUESTED` → `CLARIFICATION_REQUIRED`, …) and returns **null** for unknown values instead of crashing the whole query |
| `PhysicalVerificationStatusConverter` | `AssetDeclaration.physicalVerificationStatus` | same idea |
| `TempleProfileStagingStatusConverter` | `TempleProfileStaging.status` | same idea |
| `ServiceTypeConverter`, `PaymentStatusConverter` | `Contractor` | same idea |

These exist because status names changed over time. Its Javadoc mentions "Migration V42", which no longer exists as a file (see 4.11). The flip side: a declaration whose status reads as `null` will behave oddly in the workflow code.

### `AesEncryptionConverter`: transparent encryption of personal data
Applied with `@Convert(converter = AesEncryptionConverter.class)` to:
- `Trust.trustPANNumber`, `Trust.bankAccountNumber`
- `BoardMember.aadhaarEncrypted`
- `TempleProfileStaging` / `TempleProfileCurrent` / `TempleProfileHistory` (one field each)

```text
WRITE:  "ABCDE1234F" ──► random 12-byte IV ──► AES-256-GCM(key, IV) ──► Base64(IV + ciphertext + tag) ──► TEXT column
READ:   column ──► Base64 decode ──► split IV / ciphertext ──► AES-GCM decrypt ──► "ABCDE1234F"
```
- A **new random IV each time** means the same PAN encrypts differently on every save. Good for privacy, but you **can't search** or compare by the encrypted column.
- That's why `BoardMember` also has **`aadhaar_hash`** (`HmacUtil`: HMAC-SHA256 with `app.hmac.key`). It's deterministic, so duplicates can be found with `WHERE aadhaar_hash = ?`. There's also **`aadhaar_last4`**, used by `getMaskedAadhaar()` → `"XXXX-XXXX-1234"` for display.
- **Legacy tolerance:** a value that isn't valid Base64, or is too short, is returned **as-is** (treated as old plaintext) with a warning.
- **⚠️ Failure returns `null`.** If decryption fails (for example, the key changed), `convertToEntityAttribute` returns `null` rather than throwing. Hibernate updates **all columns** of an entity by default (there's no `@DynamicUpdate`). So if that entity is then modified and saved, it would likely write `NULL` over the ciphertext and destroy the only copy. For `Trust`, the `nullable = false` columns would make the save fail instead. For `BoardMember.aadhaarEncrypted` (nullable), it would succeed silently. I haven't reproduced this, but the mechanism is standard Hibernate behaviour.

**⚠️ Not encrypted:** `User.aadhaarNumber` is a plain `VARCHAR(12)` with no converter. `AdminServiceImpl` writes it (line 115) and **returns it in the admin user response** (line 347). That's full Aadhaar numbers stored and served in plaintext, unlike the careful treatment in `BoardMember`.

---

## 4.10 Read-model / denormalized tables

Some tables exist purely to make reads fast or to keep history:

| Table | Purpose | Filled by |
|---|---|---|
| `temple_search_summary` | Pre-joined, flat row per temple for the public/DC search | `TempleSearchSummaryServiceImpl.refresh()` / `rebuildAll()` (`@Async`) after workflow actions |
| `temple_profile_staging` | TA's **draft / submitted** profile edits awaiting DC approval | `TempleProfileStagingServiceImpl` |
| `temple_profile_current` / `temple_profile_history` | Approved profile + every past approved version | DC approval flow |
| `asset_declarations` summary columns (`gold_grams`, `vehicles_count`, …) | Totals, next to the detailed `decl_*` rows | `DeclarationServiceImpl` |
| `asset_declaration_versions`, `entity_versions` | Immutable JSON snapshots at workflow events | `SnapshotServiceImpl`, `VersionService` |
| `workflow_instances.temple_id/district_id` | Copies, so the dashboard filters without joins | `WorkflowEngineImpl` |

The rule when you change data: **find out which read-model must be refreshed too.** Otherwise search results show old values.

---

## 4.11 Migrations: how the schema got here

```text
V1__initial_schema.sql            "consolidated final state of V1–V98 migrations"  → 61 tables, 44 FK clauses
V2__master_seed_data.sql          geo hierarchy, roles/users, notification rules …
V3  add FK users.temple_id        V4  designation/access_type on users
V5, V7  make temple grade nullable (same change twice)
V6  fix tradition enum values     V8  normalize asset declaration status
V9  trust financial-year uniqueness
V10 access-control tables         V11 DACVM tab/page policies   V12 TA temple-search policies
      ─── (no V13–V99: the old history was squashed into V1) ───
V100 temple seed data             V101 complete temple profiles   V102 fix draft trusts/declarations
V103 temple photos in DB          V104 fix invalid enum values    V105 email outbox
V106 fix notification template keys  V107 notice board schema     V108 physical-verification columns
V109 align aadhaar_last4 type     V110 password-management columns  (must_change_password, reset token …)
V111 fix entity schema drift      V112 fix column type drift      V113 fix staging column type drift
```

**How to read this history:**
1. **V1 is a squash.** Someone replaced 98 migrations with one file. On an *existing* database that had already run the old V1–V98, the checksum of V1 differs. That's likely why `validate-on-migrate: false` and auto-repair are switched on (Chapter 2).
2. **V111–V113 are "drift" fixes.** They align the SQL schema with what the entities (and Hibernate's `ddl-auto: update`) expect. This is the cost of having two schema owners.
3. **`db/seeds/*.sql` are not migrations.** Flyway only reads `db/migration`, so those are manual scripts.
4. **Tests take a stricter line:**
   - `MySQLContainerBase` runs Flyway on a real MySQL 8 and sets `ddl-auto: validate`. That setting means "fail if the entities don't match the tables", which is the **only place schema drift gets caught**.
   - The H2 tests use `create-drop` and skip Flyway entirely.

**To add a column the right way:**
1. `V114__add_x_to_temples.sql` with `ALTER TABLE temples ADD COLUMN x ...`
2. Add the `@Column` field to `Temple`.
3. Run the Testcontainers ITs, since `validate` catches mismatches.

Don't rely on `ddl-auto: update` to add it.

---

## 4.12 Table catalogue (grouped by module)

| Module | Tables |
|---|---|
| Geo | `states`, `cities`, `districts`, `taluks`, `hoblis` |
| Auth | `users`, `refresh_tokens`, `mfa_recovery_codes` |
| Temple | `temples`, `temple_photos`, `temple_profile_staging`, `temple_profile_current`, `temple_profile_history`, `temple_search_summary` |
| Trust | `trusts`, `board_members`, `board_meetings`, `trust_financials` |
| Declaration | `asset_declarations`, `asset_declaration_versions`, `declaration_clarifications`, `physical_verification_history`, `acknowledgement_sequences`, 9 × `decl_immov_*` / `decl_mov_*` |
| Workflow / governance | `workflow_instances`, `workflow_transitions`, `workflow_idempotency_records`, `entity_versions`, `governance_action_history`, `clarification_threads`, `clarification_messages`, `clarification_attachments` |
| Staff | `employees`, `contractors` |
| Documents | `documents`, `document_access_logs` |
| Notifications | `notification_rules`, `notification_events`, `notification_outbox`, `in_app_notifications`, `email_outbox`, `email_delivery_logs`, `user_notification_preferences` |
| Notices | `notices`, `notice_attachments`, `notice_reads` |
| Access control | `access_control_policies`, `access_control_field_masks`, `access_control_audit_log` |
| Audit / ops | `audit_auth_events`, `audit_data_events`, `audit_export_events`, `observations`, `temple_timeline_events`, `system_config`, `export_job_records`, `idempotency_records`, `rate_request_log` |

## 4.13 The five questions, for the three central entities

| Entity | Why does it exist? | Who uses it? | What does it link to? | Written when | Read when |
|---|---|---|---|---|---|
| `BaseEntity` | Shared id, audit, soft delete | 38 entities extend it | `JpaAuditConfig` (who), `@PrePersist`/`@PreUpdate` (when) | every INSERT/UPDATE | always |
| `Temple` | The registry's core record | `TempleServiceImpl`, `DcTempleProfileServiceImpl`, guards, almost every module | `Hobli` (JPA, read-only); `district_id`, `taluk_id`, `city_id` (plain); referenced by id from ~12 tables | registration, admin create, DC-approved profile | search, dashboards, every scope check |
| `WorkflowInstance` | One governance state machine per entity | `WorkflowEngineImpl`, `GovernanceStatusResolverImpl`, DC dashboard | `(entity_type, entity_id)` polymorphic; parent of transitions, versions, clarification threads | every submit/approve/reject/clarify | status badges, "pending" counts, allowed actions |

## 4.14 Findings from this chapter (unchanged, for your team)

| Severity | Finding |
|---|---|
| 🔴 | `User.aadhaarNumber` is stored **and returned** in plaintext (`AdminServiceImpl` lines 115, 347). Elsewhere, Aadhaar is AES-encrypted + HMAC-hashed + masked |
| 🟠 | `AesEncryptionConverter` returns `null` on decryption failure. A later save of that entity can overwrite ciphertext with NULL (likely for `BoardMember.aadhaarEncrypted`) |
| 🟠 | Soft delete + unique constraints: re-creating a soft-deleted username/email/registration number fails with a duplicate-key error while the app says "not found" |
| 🟡 | Two schema owners (Flyway + `ddl-auto: update`). V1 squash, no validation, drift-fix migrations. Only the Testcontainers ITs validate |
| 🟡 | DB `ON DELETE CASCADE` on the `decl_*` tables never fires (parents are soft-deleted). Item rows stay behind for soft-deleted declarations |
| 🟡 | `open-in-view` left at default `true`. Lazy loads can happen during JSON rendering |
| ⚪ | Bulk `@Modifying` queries skip `updated_at` and `@Version`. `Temple.hobli` goes stale after `setHobliId`. `V5`/`V7` duplicate each other |

---

**Next: Chapter 5 — Controllers.** It covers:
- how Spring MVC turns a URL into a method call
- `@RestController`, `@RequestMapping`, `@PathVariable`, `@RequestParam`, `@RequestBody`, `@Valid`, `@AuthenticationPrincipal`, `ResponseEntity`
- how `ApiResponse` / `PaginatedResponse` are built
- a walk through the main controller groups: temple, trust, declaration, DC, governance v1 vs **v2 `WorkflowController`**, SSE, export/file downloads, multipart uploads

For each group I'll show exactly which service method it hands off to.

Say **"continue"** when ready.
