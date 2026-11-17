# Persistence layer modernization — plan

**Audience:** repo owner / senior engineer deciding whether and how to proceed
**Scope:** `niord/`, `niord-dk/`
**Governing constraint:** `CLAUDE.md` — minimal, behavior-preserving change; **no `pom.xml` edits without explicit authorization**

---

## 1. Executive summary

"Best practice" for a Spring Boot 4 / Spring Framework 7 / Hibernate ORM 7 app is a **composed Spring Data JPA repository layer** (interface-driven `JpaRepository` for CRUD/derived/named lookups, custom `*RepositoryImpl` fragments hosting the hand-written Criteria/spatial/native queries), **constructor injection** of immutable dependencies, **service-layer `@Transactional` boundaries** defaulting reads to `@Transactional(readOnly = true)`, `@Service`/`@Repository` stereotypes used for their semantics (the latter buys exception translation), and DTOs decoupled from entities behind dedicated mappers.

**Headline recommendation: do NOT rewrite. Modernize incrementally and additively, lowest-risk-first, each phase independently shippable with the full IT suite green** — a single proof-of-concept Spring Data repository on `ChartService` first, then query-heavy services via fragments, then mapper consolidation, retiring `BaseService` last. The fragment-composition model means we never have to choose between Spring Data convenience and the bespoke `SpatialIntersectsPredicate`/`CriteriaHelper` code — they coexist in one repository.

---

## 2. Current state

The persistence layer uses Spring stereotypes and Spring transactions, but none of the Spring Data idioms.

### 2.1 The DAO base class — `BaseService`
`niord/src/main/java/org/niord/core/service/BaseService.java` is the single hand-rolled DAO base, inherited by ~43 services.
- Class-level `@Transactional`, inherited by every subclass via the Spring proxy.
- `@Inject protected EntityManager em` — **field injection, not `@PersistenceContext`**. Zero `@PersistenceContext` in the tree.
- Methods: `getByPrimaryKey` (null-returning find), `remove` (merge-then-remove), `saveEntity` (merge-or-persist on `isPersisted()`), `getAll`, `count`, `persistedList`/`persistedSet`/`persistedListForIds`, static `sameEntities`, and `getNodeBuilder()` which casts `em.getCriteriaBuilder()` to Hibernate's internal `org.hibernate.query.sqm.NodeBuilder`.
- **Behavioral landmines that do NOT map to Spring Data derived methods:** `getAll()` and `count()` call `em.clear()` first, detaching all managed entities and discarding pending changes as a side effect of a read. `saveEntity` returns the merged copy — callers must reuse the reference.

### 2.2 Tree DAO — `TreeBaseService`
`niord/src/main/java/org/niord/core/service/TreeBaseService.java` extends `BaseService` for `Area` and `Category` only. Adds `getTree`, `getRootEntities`, `moveEntity`, `updateLineages`, `changeSortOrder`, `recomputeTreeSortOrder`. `moveEntity` runs a **dynamic bulk JPQL `UPDATE` string-formatted with the class name** then `em.flush()`, with an in-code TODO admitting it is a workaround. Recomputation runs `@Scheduled` hourly.

### 2.3 Stereotypes and injection
- ~76 `@Service`, no `@Repository`. `BaseService` is abstract (not a bean); its subclasses are beans (e.g. `ChartService`, `MessageService`, `AreaService`, `UserService`, `PublicationService`). `Area` and `Category` go via `TreeBaseService`.
- Constructor injection is the norm on new/refactored beans; **~167 `@Inject` field injections remain**, mostly the cyclic, batch and EntityManager-only cases.
- Setting injection is done by `SettingInjectionBeanPostProcessor`.

### 2.4 Transactions
- **Zero `jakarta.transaction.Transactional`** — everything is Spring `@Transactional`; `REQUIRES_NEW` maps to `Propagation.REQUIRES_NEW`.
- `rollbackOn`/`dontRollbackOn` are set nowhere. `rollbackFor = Exception.class` is set in **six** places, each a deliberate exception that rolls one unit of work back on a checked exception too: `MessageService.updateStatus` and `cancelMessage`, `MessageRestService.updateMessageStatus` and `updateMessageStatuses`, `MailingListExecutionService.executeScheduledTrigger` (`REQUIRES_NEW`) and `MessageTxRunner.call` (`REQUIRES_NEW`). Every other transactional method relies on the unchecked-only default (§4).
- `readOnly = true` is not rolled out.

### 2.5 Queries
- **~109 `@NamedQuery` JPQL declarations across 35 entities**, ~101 call sites (Message 10, Publication/Category/Area 6 each). Simple finders + id-in-list loads.
- **`CriteriaHelper`** (`niord/src/main/java/org/niord/core/db/CriteriaHelper.java`) — custom null-tolerant dynamic-query builder used by ~12 services. Depends only on jakarta Criteria → ports to a fragment unchanged.
- **`SpatialIntersectsPredicate`** (`niord/src/main/java/org/niord/core/db/SpatialIntersectsPredicate.java`) — custom Hibernate SQM predicate branching on dialect. Used by intersecting Area/Chart queries and Message extent search.
- **SQL outside JPQL.** Native queries: `MessagePostgresIndex` (tsvector / `websearch_to_tsquery`), `BootstrapMarker` and `DefaultsLedger` (their tables `BootstrapState` and `DefaultsLedger` have no entity), `MissingPathReport` (reads), `LegacyImportService` (two deletes of `PublicationSeries` collection tables), one `BatchService` `SELECT DISTINCT`, and `MonitorRestService` `select 1`. JDBC on the transaction's connection: `RepoStore` (a `JdbcTemplate` over the table `RepoFile`, which has no entity), the writes of `SettingsService` (a batched insert and the state setting's upsert) and the batched insert of `DefaultsLedger` through `Session.doWork`, and the advisory locks taken through `doWork` in `DefaultsLedger`, `DictionaryService`, `FiringExerciseService` and `S124DatasetService`.
- Pagination is hand-rolled via `PagedSearchParamsVo`; `MessageService` materializes the full tuple result and `subList`s.
- **Zero Spring Data:** no `JpaRepository` anywhere, although `spring-boot-starter-data-jpa` is already a declared dependency (it is what supplies the EntityManager/transaction-manager wiring), so repository scanning needs **no new dependency**.

### 2.6 Entity / mapper layer
- ~71 `@Entity` classes over three `@MappedSuperclass` bases: `BaseEntity` (`GenerationType.AUTO` surrogate id, generic id type, **no `equals`/`hashCode`**), `VersionedEntity` (`@Version` + manual `@PrePersist`/`@PreUpdate` audit timestamps — not `@CreationTimestamp`), `TreeBaseEntity` (parent/children/lineage, `CascadeType.ALL`).
- **Most entities carry the live `toVo(DataFilter)` conversion logic** (e.g. `Chart.toVo`; `MessageRestService` calls entity `.toVo()` — this is the production path).
- There are ~26 `@Component`/`@Service` mappers, but only a few are wired into production (`ChartMapper` via `ChartRestService`/`BatchChartImportProcessor`, and `MessageMapper` and friends). The rest are additive and referenced only by each other and their unit tests — the cutover never happened.
- No entity overrides `equals`/`hashCode` → identity equality only.
- Search is external to JPA (standalone Lucene + PostgreSQL FTS) — **no `@Indexed`** anywhere.

### 2.7 Config
`application.yml` / core defaults: **`open-in-view: false`** (already correct), `ddl-auto: validate`. No custom EMF/DataSource/tx-manager bean (Boot autoconfig). Tests use Testcontainers PostGIS + a Liquibase-built schema; no `@DataJpaTest` (a custom `Tx.java` REQUIRES_NEW helper is used where a nested commit is needed).

---

## 3. Target architecture

1. **Spring Data JPA repositories as the default.** One interface per aggregate extending `JpaRepository<E, ID>`. Named queries bind automatically by `Entity.methodName` convention; simple finders become derived/`@Query` methods. This deletes the boilerplate finders without a paradigm change.
2. **Custom fragments for everything bespoke.** A `*RepositoryCustom` fragment interface + `*RepositoryImpl` (the documented `Impl` suffix) injecting `EntityManager` via `@PersistenceContext`. The existing `CriteriaHelper`, `SpatialIntersectsPredicate`, tuple search, and native FTS code move **verbatim** into these Impls. The repository extends both `JpaRepository` and the fragment, so callers see one bean. **Prefer plain fragments over `JpaSpecificationExecutor` for the spatial searches** specifically, because `SpatialIntersectsPredicate` needs the undocumented `(NodeBuilder) cb` cast that Specifications don't bless.
3. **Constructor injection** of `final` dependencies on every production bean (no `@Autowired` needed for single-constructor classes). Field injection stays only in test classes.
4. **Spring `@Transactional` at the service layer, class default `@Transactional(readOnly = true)`, write methods overriding to `@Transactional`.**
5. **`@Service` on service classes, `@Repository` on fragment Impls** (the latter gives free `DataAccessException` translation; Spring Data interfaces get it automatically).
6. **Mapper/DTO layer:** consolidate on the `@Component` mappers as the single conversion path; entities eventually shed `toVo()/DataFilter`. DataFilter flags become explicit boolean mapper params. **For read-only API projections, prefer Spring Data DTO/record projections** (narrower SELECT); reserve mappers for write paths that need a managed entity.
7. **The public/v1 contract gets frozen behind a firewall** (`org.niord.v1api.{mapper,vo,search}`).

---

## 4. Transaction-rollback semantics (verified)

**A Spring `@Transactional` method rolls back on `RuntimeException`/`Error` and *commits* on checked exceptions**, and this codebase relies on that default everywhere.

Why this matters and why it is pinned:
- `rollbackOn`/`dontRollbackOn` are set nowhere and `rollbackFor` only at the six deliberate exceptions of §2.4, so every other transactional method uses `DefaultTransactionAttribute.rollbackOn()` = `(ex instanceof RuntimeException || ex instanceof Error)`.
- ~94 service methods declare `throws Exception`. Those methods **commit** when a checked exception escapes. Do **not** mass-add `rollbackFor = Exception.class` "to make things safe" — that is itself a behavior change. If rollback-on-checked is ever wanted, it is a separate, deliberate decision.
- Pinned by `niord/src/test/java/org/niord/core/tx/TransactionRollbackSemanticsITTest`: one test throws a checked exception and asserts the row is **committed**, one throws a `RuntimeException` and asserts **rollback**. Keep both green through every phase below.

---

## 5. Phased plan

Each phase is independently shippable and must leave the full unit + IT suite green.

### Phase 1 — Spring Data PoC on ONE simple entity (the proof)
**Scope:** introduce `ChartRepository` additively **alongside** `ChartService`, prove the composed pattern end-to-end, leave `ChartService` delegating. Create `ChartRepository extends JpaRepository<Chart, Integer>` with named-query-bound + `@Query` finders, plus a `ChartRepositoryCustom`/`ChartRepositoryImpl` fragment hosting `getIntersectingCharts` (the `SpatialIntersectsPredicate` code, moved verbatim). **Risk:** medium (first net-new layer; fragment naming is convention-bound and fails silently if misnamed → pin with a wiring IT). **This is the gate: do not start Phase 2 until this pattern is reviewed and signed off.** See §6. **Size:** M.

### Phase 2 — Migrate query-heavy services via fragments
**Scope:** roll the pattern out to the `CriteriaHelper` services and the spatial/FTS/tuple searches: `AreaService`, `CategoryService`, `MessageService`, `PublicationService`, `MailingListService`, `AtonService`, `ContactService`, and the `MessagePostgresIndex` native path. Migrate `PagedSearchParamsVo` callers to `Pageable`/`Page` where slicing semantics match (note `MessageService` uses exact-total subList — preserve that explicitly). **Risk:** medium — `MessageService.buildSearch` carries authorization filtering, so characterization tests over the domain/category/chart/tag/geometry filters must exist before touching it. Tree services port the raw UPDATE+flush **verbatim** into a fragment — **do NOT swap to `@Modifying`** (behavior change; document in `docs/potential-errors.md`). **Size:** L (multiple PRs).

### Phase 3 — Consolidate mapper/DTO layer
**Scope:** make the `@Component` mappers the single live conversion path; retire entity `toVo()/DataFilter`. Wire mappers into the REST/service call sites currently using entity `.toVo()`, one entity at a time, gated by parity tests asserting each mapper produces the same VO graph as the `toVo()` it replaces for representative `DataFilter` permutations. Introduce read-only DTO/record projections for list/search endpoints. Stand up the `org.niord.v1api` firewall before touching public VOs. **Risk:** medium — flag-gated DTO merges can **leak system-only fields to public consumers**; add a test asserting the public projection omits details fields. **Size:** L.

### Phase 4 — Retire `BaseService` / `TreeBaseService`
**Scope:** once all subclasses delegate to repositories, delete the DAO bases. **Explicitly decide** what happens to the `em.clear()` side effect in `getAll`/`count` (do not silently inherit it into shared fragment infra — document it first). **Risk:** medium — `em.clear()` and the tree UPDATE+flush are the two behaviors without clean Spring Data equivalents. **Size:** M.

### Optional, independent — `readOnly = true` rollout
Layer `@Transactional(readOnly = true)` on read paths as a separate, test-gated step. Deliberately not bundled with any phase above: it is the one change that can surface "unexpected write on a read path" failures, and isolating it keeps that signal clean.

---

## 6. Recommended proof-of-concept slice: `ChartService` → `ChartRepository`

**Convert `ChartService` first.** It is the ideal PoC because: (a) it is small and self-contained; (b) `ChartMapper` is already wired into production, so the DTO half of the pattern is already demonstrated for Chart; (c) it exercises **every** query category in one service — named queries (`Chart.findByLegacyId`/`findByChartNumber`/`findByChartNumbers`/`searchCharts`), an `em.createNamedQuery` post-filter, `getAll` (`getCharts`), and the **spatial** `getIntersectingCharts` using `SpatialIntersectsPredicate`. If the pattern holds for Chart, it holds everywhere.

```java
// niord/src/main/java/org/niord/core/chart/ChartRepository.java
@Repository
public interface ChartRepository
        extends JpaRepository<Chart, Integer>, ChartRepositoryCustom {

    // Binds to @NamedQuery "Chart.findByChartNumber" by convention (Entity.methodName)
    @Query(name = "Chart.findByChartNumber")
    Optional<Chart> findByChartNumber(@Param("chartNumber") String chartNumber);

    @Query(name = "Chart.findByLegacyId")
    Optional<Chart> findByLegacyId(@Param("legacyId") String legacyId);

    @Query(name = "Chart.findByChartNumbers")
    List<Chart> findByChartNumbers(@Param("chartNumbers") List<String> chartNumbers);

    @Query(name = "Chart.searchCharts")
    List<Chart> searchChartsRaw(@Param("term") String term,
                                @Param("active") Set<Boolean> active);
}

// niord/src/main/java/org/niord/core/chart/ChartRepositoryCustom.java
public interface ChartRepositoryCustom {
    List<Chart> getIntersectingCharts(Geometry geometry);
}

// niord/src/main/java/org/niord/core/chart/ChartRepositoryImpl.java
@Repository
class ChartRepositoryImpl implements ChartRepositoryCustom {
    @PersistenceContext
    private EntityManager em;

    @Override
    public List<Chart> getIntersectingCharts(Geometry geometry) {
        // EXACT body of ChartService.getIntersectingCharts,
        // SpatialIntersectsPredicate + CriteriaHelper moved verbatim;
        // getNodeBuilder() becomes (NodeBuilder) em.getCriteriaBuilder().
    }
}
```

`ChartService` then becomes a thin `@Service` (constructor-injecting `ChartRepository`) preserving its exact public methods — `findByChartNumber` wraps `repo.findByChartNumber(n).orElse(null)` to keep the null-returning contract. **Acceptance:** every existing Chart unit test and Chart IT passes unchanged, plus a new fragment-wiring IT. Get this reviewed and signed off before Phase 2.

---

## 7. Risks & mitigations

- **CLAUDE.md minimal-change constraint.** The whole plan is additive/incremental by design. Ship per-package, full suite green each step; document — never fix — any production hazard discovered.
- **Build-file changes need explicit authorization.** `spring-boot-starter-data-jpa` is already declared, so repository scanning likely needs **no** pom change — but confirm before assuming, and get sign-off for anything that does.
- **Open-in-view.** Already `false` — good. Any new repository read path must resolve LAZY associations in-tx (`JOIN FETCH`/EntityGraph/projection). Keep conversion inside the transactional service boundary; test for `LazyInitializationException`.
- **`equals`/`hashCode` on entities.** None exist (identity equality). Spring Data does not require them. Do **not** add them as part of this work (behavior change); keep using `BaseEntity.sameEntities`.
- **Hibernate spatial coupling.** `SpatialIntersectsPredicate` extends Hibernate-internal SQM types — **fragile across Hibernate upgrades regardless of repository style.** Prefer plain fragments over `JpaSpecificationExecutor` here. Search indexing is **external to JPA** — tests must drive `indexMessage` explicitly.
- **Tree / bulk-update edge cases.** `TreeBaseService.moveEntity` runs raw `UPDATE`+`flush` then keeps using the still-managed entity (stale-context risk, documented TODO). `getAll`/`count` call `em.clear()`. Port verbatim into fragments; do **not** "upgrade" to `@Modifying`. `Message.onPersist()` deliberately disables `@PreUpdate` and relies on a manual call in `MessageService.saveMessage()` — any new save path that bypasses that method skips recomputation. Test it.
- **Do not centralize authorization into broad `HttpSecurity` matchers** during mapper/firewall work: Spring evaluates URL rules before method annotations, so a blanket `permitAll` matcher silently overrides `@RolesAllowed` (fail-OPEN info disclosure). Pin the JSR-250 semantics with negative tests before any cutover.
- **Fragment wiring fails silently.** A misnamed/misplaced `Impl` doesn't wire and breaks the repository at startup quietly. Pin every fragment with a wiring IT.
- **`MessageService` pagination semantic.** It returns an exact total via `subList`; `Slice` does not produce an exact count. Preserve the exact-total semantic explicitly.

---

## 8. Open questions

1. **Scope / how far?** Commit to the full Spring Data + mapper-consolidation + v1 firewall end state, or stop after the PoC?
2. **Additive-alongside vs. cutover per service?** Recommended: additive (new repository beside the existing service, delegate, never delete until the end).
3. **MapStruct vs. hand-written mappers?** The existing mappers are hand-written `@Component`s. Recommendation: stay hand-written (no new dependency, CLAUDE.md-friendly).
4. **`readOnly = true` rollout — opt-in or default?** Recommendation: a separate, test-gated step (see §5).
