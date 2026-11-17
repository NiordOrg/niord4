# Publications port inventory

What it takes to bring the publications redesign — the series/issue model that replaces the flat
legacy `Publication` row — out of the two repositories it was built in and into this one.

The redesign lives in two places and neither of them is this repository:

- **Frontend** — `C:/Users/<user>/IdeaProjects/niord-app`, branch `main`.
- **Backend** — `C:/Users/<user>/IdeaProjects/niord`, branch `feature/publications-redesign` (Quarkus).

Every count below comes from a command run against those working copies at the commits named in
section 1. Each section states the command shape once; the figures in it come from that shape.

---

## 1. What is being ported

**Frozen sources (step 0, executed 2026-09-14).** Every source repository carries the annotated tag
`publications-port/2026-09-14`; the port reads from that tag and from nothing later. `git rev-parse <tag>^{commit}`:

| Repository | Branch | Tag `publications-port/2026-09-14` |
|---|---|---|
| `niord-app` | `main` | `ef08c1cdd0e150c9f18ff59d9740a16d44131c2f` — 317 commits after the ancestor `18eef1a9` |
| `niord` | `feature/publications-redesign` | `fdae3873d707af875c19394bfd1659f792af6c28` — 265 commits after the fork point |
| `niord-dk` | `feature/publications-redesign` | `36c31fa53b7cf423d18263f9624cfbdbaf2e7de9` — 19 commits after `master` (`2d449158`) |
| `niord-ng` | `madsenjensen/publications-port` | `d8af104b82e6577d856b78fc587cd5f813ba7a85` — the HEAD the port starts from (no tag) |

The measurements in the rest of this document were taken at the earlier commits `1c9ab817` (`niord-app`),
`b400a1a0` (`niord`) and `b14d055e` (`niord-ng`); the seven `niord` and five `niord-app` commits since are
publications work (compilation backfill, late-member rule, render timing removal) and change counts, not
shape. `niord-dk` was not measured then: its branch delta is 12 files, of which the publications part is
two `nm-accumulated-report-pdf-intro_{da,en}.ftl` templates and four `pdf.accumulated.*` keys in
`pdf_da.properties`; the rest is Quarkus deployment configuration and S-124 menu keys that do not travel.

`niord`'s branch forked from `master` at `08d4b5cc18f79f7127e5a3a24e69fcd767185e9c`
(`git merge-base HEAD master`).

**How the frontend history was carried.** `fe/` in this repository is the rewritten niord-app history with a
`fe/` prefix, and the rewritten twin of the ancestor `18eef1a9` is `e6fd7eb2` (tree of `fe/` byte-identical).
The 317 newer niord-app commits were rewritten the same way — each commit's tree spliced under `fe/` on top of
`e6fd7eb2`'s other entries, merge commits preserved — onto the local branch `port/niord-app-main-fe`, which was
then merged into `madsenjensen/publications-port` with a plain three-way merge (base `e6fd7eb2`). Three
conflicts: two files upstream had deleted and this repository had edited for SECOM (their SECOM content now
lives in `fe/docs/features/sysadmin.md` and `fe/docs/styling-reference.md`), and the sysadmin section count
in `section-descriptors.spec.ts` (fourteen here, thirteen upstream).

**Frontend.** The Angular publications area is a complete admin section plus the citation surfaces in the
message browser: 103 files under `src/app/features/admin/sections/admin/publications/` (38 components and
rule modules, 45 spec files, 20 templates — 60,164 lines), two API services
(`publication-series-api.service.ts` 2,769 lines, `one-off-api.service.ts` 227 lines) with their specs, a
two-file `publication-categories` section, 1,053 Transloco leaf keys under `admin.publications.` in both
`en.json` and `da.json` (no `ai_`-prefixed Danish remains — the copy is accepted), 1,486 `it(` cases in
the unit specs and 9 Playwright tests across `e2e/admin-publications.spec.ts` and
`e2e/admin-publications-issue.spec.ts`. Alongside the UI travel the specifications and the go-live set
under `docs/publications/` (14 files, 7,066 lines) and the three guard scripts under
`scripts/publications/`. The citation half reaches into `src/app/features/messages/` — the publication
filter panel, `publications-field`, `message-publication`, `message-issue.model.ts`,
`publications-api.service.ts` and the filter serializer, 17 of the 57 changed files under that tree name a
publication or an issue.

**Backend.** Against the fork point the branch adds or changes 295 Java files and 89,463 lines under the
publications packages: 266 under `niord-core/.../org/niord/core/publication/` and its tests, 29 under
`niord-web/.../org/niord/web/publication/` and its tests. The trees themselves hold 187 main-source and
124 test files — 155 of the main files sit in the `series/` subtree (entities, services, `criteria/`,
`legacy/`, `resolve/`, `vo/`, `batch/`), the difference being untouched legacy classes. With them travel
18 Flyway migrations (1,112 lines), a 20 MB fixture
corpus (42 files, 323,400 lines), two generated manifests, a byte-deterministic schema baseline and four
Node tools under `scripts/`. It also changes nine files outside the publications packages —
`MessageSearchParams`, `MessageService`, `SystemMessageVo`, `FmTemplateService`, `HtmlToPdfRenderer`,
`Roles`, `BootstrapService`, `MailingListSchedulerService`, `PublicationRestService` — and adds two new
classes in `org.niord.core.message` (`MemberSetDesignation`, `PublicationMemberSetSource`) that the
publications code imports. The same branch also carries 31 files of unrelated S-124 MaDaMe promulgation
work; that is not part of this port.

**The freeze rule.** Both source branches are tagged before step 1 of the port and no further commit on
either is carried. Neither repository has a tag for this work today (`git tag` lists three release tags in
`niord`, none in `niord-app`, none in `niord-ng`), and `niord-app` `main` advanced by one commit during
the measurement of this document — so the port reads from a tag, never from a branch name. The two
generated manifests (`rule-ids.txt`, `entity-fields.json`) carry a SHA-256 of the specification slice they
came from, so a frontend tag and a backend tag that disagree are detected at build time rather than
guessed at.

---

## 2. Frontend: what `fe/` is, and what `niord-app` `main` adds

*Commands: `git ls-tree -r <commit> -- src public e2e | awk '{print $4" "$3}' | sort`, compared with
`comm`/`join`; `git diff --numstat <ancestor> main` grouped by path; `git rev-list --count`.*

### The common ancestor

`fe/` is a vendored copy, not a submodule: `git ls-tree HEAD fe` reports mode `040000` (a tree, not the
`160000` of a gitlink) and there is no `.gitmodules`. Its HEAD subject — "S-124: carry every model language
in datasets, restricted per series" — exists nowhere in `niord-app` (`git log --all --grep` finds nothing);
it is this repository's own commit, so the relation has to be established from content.

Its blobs are not all present in `niord-app`'s object database
(`git cat-file -t` on `HEAD:fe/package.json` and `HEAD:fe/angular.json` reports them absent; `HEAD:fe/src/app/app.routes.ts`
and `HEAD:fe/src/app/app.config.ts` resolve). Comparing the full `src/` + `public/` + `e2e/` tree listing
(1,285 files) against every candidate commit identifies the ancestor:

> **`18eef1a9` — 2026-08-17 — "test(setup): stub the jsdom Range rects that flaked the TipTap specs"**

At that commit only 57 path/blob entries differ, and the difference is entirely one-way:

- **33 files exist only in `fe/`** — the whole SECOM sysadmin section
  (`src/app/features/admin/sections/sysadmin/secom/**`, 28 files), `secom-admin-api.service.ts` and its
  spec, `e2e/sysadmin-secom.spec.ts`, and `src/environments/environment.local.ts`. This is `niord-ng`'s own
  work, added on top of the copy.
- **0 files exist only in `niord-app@18eef1a9`** — nothing was dropped when the copy was taken.
- **12 files have the same path and different content** — `public/assets/i18n/{en,da}.json`,
  `section-descriptors.{ts,spec.ts}`, `sysadmin.routes.ts`, `admin-area-shell.spec.ts`,
  `batch-log-file-dialog.html`, `editor-facade.spec.ts`, `src/environments/environment{,.development,.test}.ts`,
  `src/styles/components.css` — all of them the registration and wiring edits the SECOM section required.

The application tree is byte-identical across the fourteen commits from `18eef1a9` (2026-08-17) through
`e3550821` (2026-08-21) — all of them documentation-only — so any of them is equally consistent with
`fe/` and `18eef1a9` is the oldest. The first commit that diverges is `79e3db87` (2026-08-21, "docs(publications):
add the backend and frontend implementation plans"), and two independent facts confirm the copy predates it:

- `fe/` still carries `public/prototype-publikationer.{html,js}`, which `79e3db87` deletes.
- `fe/eslint.config.js` is blob `65b7008e`, the content introduced in `8ca89fed` (2026-07-10) to exclude
  that prototype from lint and format. `79e3db87` changes it to `cb952c80`.

A later fork point is therefore excluded: measured against `96ba0c37` (2026-08-24, the commit immediately
before the publications skeleton lands) the same comparison yields 61 differing entries instead of 57 —
13 files with differing content and two files present in `fe/` and already deleted upstream. Restricting
the comparison to `src/` and dropping `public/` hides both signals and makes `96ba0c37` look like the
better match; it is not.

### What `main` brings over the ancestor

`git diff --shortstat 18eef1a9 1c9ab817` → **665 files changed, 97,419 insertions, 97,097 deletions**
across **312 commits**. The deletions are dominated by one cleanup, not by lost code.

| Area | Files | + | − | Comes along with the port? |
|---|---:|---:|---:|---|
| Publications feature (`sections/admin/publications/`) | 103 | 60,164 | 0 | yes |
| Publications API services (`publication-*`, `one-off-*`) | 4 | 5,077 | 0 | yes |
| `publication-categories` section | 2 | 136 | 0 | yes |
| Publications docs (`docs/publications/**`) | 14 | 7,066 | 0 | yes |
| Publications scripts (`scripts/publications/**`) | 3 | 677 | 0 | yes |
| Publications e2e (2 specs) | 2 | 305 | 0 | yes |
| `features/messages/**` (citation pieces + unrelated editor work) | 57 | 2,248 | 714 | partly — 17 files name a publication or an issue |
| i18n (`public/assets/i18n/{en,da}.json`) | 2 | 2,605 | 15 | yes — 1,053 of the keys are `admin.publications.*` |
| Docs, non-publications (`docs/**`, `CLAUDE.md`, `README.md`) | 22 | 7,918 | 63 | decision — see below |
| `.claude/**` | 59 | 670 | 4,304 | decision — see below |
| `migration_docs/**` (deleted wholesale) | 224 | 0 | 87,073 | no — deletion only |
| Everything else | 173 | 10,553 | 4,928 | decision — listed below |

**Everything else, rolled up** (`git diff --name-status`, grouped by directory), so the porter can decide
what else travels:

| Path | Files | What changed |
|---|---:|---|
| `src/app/features/admin/**` (outside publications) | 35 | `admin.routes.ts`, `area-routes.*`, `section-descriptors.*`, `localized-text-input`, mailing-list editor + `trigger-card` (new spec), sysadmin `domains`, `mails`, `series` (incl. `series-rules.*`), `domains-admin-api`, `message-reports-admin-api`, the admin shell (`admin-area-shell`, `admin-section-page` + new spec) |
| `src/app/features/editor/**` | 23 | editor work unrelated to publications |
| `src/app/shared/forms/**` | 19 | shared Signal Forms helpers |
| `src/app/shared/components/**` | 19 | shared CDK/aria wrappers |
| `src/**` (top level: `styles/`, `environments/`, `main.ts`, …) | 10 | token and style changes the publications screens rely on |
| `src/app/core/main-shell/**` | 8 | shell |
| `memory/**` | 8 | deleted |
| `src/app/features/schedule/**` | 7 | firing schedule |
| `src/app/shared/utils/**`, `directives/**`, `testing/**` | 14 | shared helpers and the test harness |
| `public/**` | 5 | assets |
| `e2e/**` (outside publications) | 4 | `admin-shell.spec.ts`, `sysadmin-domains.spec.ts`, new `messages-row-links.spec.ts`, `auth/README.md` |
| `src/app/core/{auth,config,domain}/**` | 6 | `auth.facade.ts`, `auth-ticket.service.ts`, new `absolute-api-url.{ts,spec.ts}`, `domain.facade.*` |
| `scripts/**` (outside publications) | 2 | new `docs-check.mjs`, new `perf/publications-requests.mjs` |
| Root config | ~12 | `package.json`, `package-lock.json`, `eslint.config.js`, `vitest.config.ts`, `playwright.config.ts`, `transloco.config.ts`, `.prettierignore`, `.nvmrc`, `.gitignore`, `LICENSE`, `app.routes.ts`, `app.config.ts` |
| `.ai/mcp/mcp.json` | 1 | deleted |

The publications screens are not separable from the shared layer: they consume the Signal Forms helpers,
the shared components and the Layer-3 classes as they exist on `main`. Porting the feature without
`src/app/shared/**` and `src/styles/**` is not a smaller change, it is a broken one.

### `fe/.claude` is pre-cleanup

`fe/.claude/` still holds the old convention set — `AGENTS.md`, `frontend-architecture.md`,
`angular-guidelines.md`, `typescript-guidelines.md`, `coding-standards.md`, `ui-standards.md`,
`styling-reference.md`, `ai-agent-playbook.md`, `ai-prompts/`, `niord-migration-checklist.md`,
`feature-inventory-template.md`, `country-specific-deployment-strategy.md`, `definition-of-done.md`, and
twelve skills including six `*-fable` duplicates — alongside `fe/migration_docs/` and `fe/memory/`.
Its vendored `skills/angular-developer/` is 42 files and carries **no `VENDORED.md`**, so the upstream
commit it was copied from is unrecorded. `niord-app` retired all of it in `b232857a` (2026-09-12, "docs:
turn main into a forward-looking handover repo"), which deleted `.claude/AGENTS.md` and the 224
`migration_docs/` files and created `docs/` in their place. The replacement is the numbered `docs/` set (`architecture.md`, `platform.md`,
`angular-conventions.md`, `typescript-conventions.md`, `coding-standards.md`, `ui-standards.md`,
`styling-reference.md`, `testing.md`, `definition-of-done.md`, `country-deployments.md`,
`backend-api-notes.md`, `backlog.md`, `features/`, `publications/`) plus `CLAUDE.md` and the two vendored
skills `.claude/skills/angular-developer` and `.claude/skills/niord-review`. `fe/` has no `docs/`
directory at all. Four more strays date the copy and go the same way: `public/prototype-publikationer.{html,js}`
(the publications prototype, deleted upstream in `79e3db87`), `potentiel-bugs.txt`, `proxy.conf.json` and
`.ai/mcp/mcp.json`. Carrying `fe/.claude` forward alongside the new `docs/` set produces two contradictory
rule sets; the port replaces it.

---

## 3. Backend target facts

*Sources: `be/MIGRATION.md`, `be/docs/test-infrastructure.md`, `be/CLAUDE.md`, and verification against the
tree (`grep -rn` over `be/**/pom.xml`, `be/docker-compose.yml`, `be/niord/src/main/java`).*

`be/MIGRATION.md` is the record of intent and is **stale on three points that matter to this port**. The
table gives both what it says and what the tree does.

| Construct | `MIGRATION.md` says | `be` actually does | Consequence |
|---|---|---|---|
| CDI scope / injection | `@ApplicationScoped`→`@Component`/`@Service`, `@Inject`→constructor injection, `@Produces`→`@Bean`, `@Observes`→`@EventListener`, `@PostConstruct` kept | as stated | mechanical |
| `@Transactional` | keep `jakarta.transaction.Transactional`; audit 80 sites for `rollbackFor` | **switched** to `org.springframework.transaction.annotation.Transactional`; 88 main-source files import it, **zero** import the jakarta one. `BaseService` still carries a bare class-level `@Transactional` | rollback semantics differ; `TxType` constants do not exist |
| Programmatic transactions | `QuarkusTransaction` not mentioned | `TransactionTemplate` / `PlatformTransactionManager` idiom | 3 call sites to rewrite (section 4.3) |
| JAX-RS resources | keep JAX-RS on `spring-boot-starter-jersey`, app path `/rest` | **Jersey removed.** `niord/pom.xml:78` — "The JAX-RS REST layer was migrated to Spring MVC; spring-boot-starter-jersey is removed." 45 `@RestController` classes; `jakarta.ws.rs` survives only in the SECOM subsystem on RESTEasy at `/api/secom/*` | every publications resource class is a rewrite |
| Role checks | `@EnableMethodSecurity(jsr250Enabled = true)`; `@RolesAllowed`/`@PermitAll` kept | as stated — `niord/src/main/java/org/niord/core/conf/SecurityConfig.java:81`. 323 `@RolesAllowed` in 83 files, 202 `@PermitAll` in 82. HTTP layer is `anyRequest().permitAll()`; method security is the whole guard | JSR-250 works; `RestControllerSecurityAnnotationTest` fails any handler without an explicit annotation |
| Scheduler | Quarkus `@Scheduled`→Spring `@Scheduled` + `@EnableScheduling`, review each cron | as stated; `@EnableScheduling` on all three application classes, 23 `@Scheduled` methods. `ScheduledCronExpressionTest` pins one row per production cron | no publications impact (section 4.7) |
| Config properties | `application.properties`/`@ConfigProperty` → `application.yml`/`@Value`/`@ConfigurationProperties` | `@Setting` + `SettingInjectionBeanPostProcessor` is the dominant mechanism (DB-backed `Setting` table); `@Value` in 12 files; `@ConfigurationProperties` **unused**. Framework defaults ship as `config/niord-core-defaults.yml` via an `EnvironmentPostProcessor` | `@Setting` ports verbatim — the publications code uses it once |
| JPA / Hibernate | `spring-boot-starter-data-jpa`, Hibernate ORM 7; plain `EntityManager` + Criteria + `@NamedQuery`, zero Panache | Hibernate ORM **7.2.12.Final**, BOM-managed; `ddl-auto: validate` | `@NamedQuery` and Criteria port 1:1; schema drift aborts startup |
| Database migrations | Flyway not discussed | **Liquibase**, always on. `niord/src/main/resources/db/changelog/db.changelog-master.yaml` including `changes/V001__baseline_schema.sql` (4,094 lines, 91 tables, 57 sequences, PostGIS), `V002__message_search_text.sql`, `V003__secom.sql`. Plus `changelog-with-seed.yaml` (master + a `seed-base-data` changeset) and `niord-app`'s `changelog-local-demo.yaml` (note of 2026-10-22: since `docs/code-data-config-plan.md` C4.1, 2026-10-21, `changelog-local-demo.yaml` is deleted with `niord-app`'s demo profile, and `changelog-with-seed.yaml` and `db/niord-base-data.sql` are in niord-base's test resources, shipped in the test-jar only; a new database is bootstrapped instead) | the 18 Flyway migrations become Liquibase changesets (section 4.5) |
| Test harness | rewrite 164 `@QuarkusTest` ITs to `@SpringBootTest` + Testcontainers | `*Test` = Surefire unit, `*ITTest` = Failsafe integration, both configured in the root POM. ITs extend `org.niord.test.ITTest` (`@SpringBootTest(classes = NiordTestApplication.class, webEnvironment = RANDOM_PORT)` + `@DynamicPropertySource`); Docker absent → skip, not fail | `@QuarkusTest` + injected beans map onto `ITTest`; **the class must be renamed to `*ITTest` or it will not run** |
| Database for tests | "Testcontainers MySQL" | **PostgreSQL 18 + PostGIS 3.6** (`imresamu/postgis:18-3.6`), lazily started singleton containers, plus Artemis 2.31.2, MailHog v1.0.1, Keycloak 22.0.5. Schema from Liquibase, then the committed `db/niord-base-data.sql` seed on top | **no MySQL anywhere**; the publications suite's MySQL assumptions do not transfer |
| Batch / JBeret | keep JBeret as Spring beans, provide a `JobOperator` and a Spring-aware `ArtifactFactory` | as stated — `core/conf/BatchConfig.java` (`JobOperator` bean on `org.jberet.operations.JobOperatorImpl`), `core/conf/SpringArtifactFactory.java`, `core/conf/JobContextDelegate.java`; JBeret 3.1.0.Final | the `publication-series-import` job definition ports as-is |
| File repository service | not discussed | `org.niord.core.repo.RepositoryService` — now itself a `@RestController @RequestMapping("/rest/repo") @PermitAll` as well as the path-resolution service; `getRepoRoot`, `getRepoUri`, `getRepoPath`, `getHashedSubfolder`, `validateTempRepoPath`, `moveRepoFolder` all intact | `PublicationPathService` and `IssueFileService` port against the same API |
| FreeMarker reports | keep FreeMarker verbatim | FreeMarker 2.3.34, Flying Saucer 9.7.2, Batik 1.19; `FmReportService`, `FmReport`, `FmTemplateService` with `ProcessFormat{TEXT,PDF}`, `script/pdf/HtmlToPdfRenderer` | the report pipeline ports; the branch's `ResourceFetchLog` addition comes with it |
| Java / Spring | Java 21 → 25, Spring Boot 4.0.6 | Java **25**, Spring Boot **4.0.7** | the source is Java 21; no source-level barrier |

Module layout in `be` is a three-module reactor: `niord/` (base library, holds the shared IT harness and
the bulk of the suite), `niord-app/` (generic English deployable), `niord-dk/` (Danish deployable). The
Quarkus `niord-core` and `niord-web` both map onto `be/niord/` — `org.niord.web.*` already lives inside
that module.

---

## 4. Backend coupling inventory

*Commands: `rg -N -o -I '<pattern>' <paths> | sort | uniq -c` and `rg -l` over the four publications trees —
`niord-core/src/main/java/org/niord/core/publication`, `niord-web/src/main/java/org/niord/web/publication`
and the two matching test trees (187 main files, 124 test files).*

Size of the surface: **38,627** lines of core main, **5,528** of web main, **41,172** of core test,
**7,147** of web test, **1,112** of SQL migrations.

### 4.1 `io.quarkus.*` imports

Only **three main-source files** import anything from `io.quarkus` — the framework coupling is
concentrated, not spread:

| File | Import |
|---|---|
| `core/publication/series/IssueWorkMarker.java` | `io.quarkus.runtime.StartupEvent`, `io.quarkus.narayana.jta.QuarkusTransaction` |
| `core/publication/series/PublicationPathService.java` | `io.quarkus.runtime.StartupEvent` |
| `core/publication/series/legacy/LegacyImportService.java` | `io.quarkus.narayana.jta.QuarkusTransaction` |

In the tests, **54 files** import `io.quarkus.test.junit.QuarkusTest`; one of them
(`series/IssueRenderMemberLoadTest.java`) also imports `QuarkusTransaction`. No other Quarkus API appears
anywhere in the publications code.

### 4.2 CDI annotations

| Annotation | Main (occurrences / files) | Test |
|---|---:|---:|
| `@Inject` | 160 | 193 |
| `@ApplicationScoped` | 39 | 1 |
| `@Named` | 9 (the three JBeret batch artifacts and the criteria converters) | 0 |
| `@Observes` | 2 — `IssueWorkMarker.clearMarkersAtBoot(@Observes StartupEvent)`, `PublicationPathService.init(@Observes StartupEvent)` | 0 |
| `@Singleton` | 0 | 0 |
| `@Startup` | 0 | 0 |

The two `@Observes StartupEvent` methods become `@EventListener(ApplicationReadyEvent.class)`. Everything
else is the mechanical `@ApplicationScoped`→`@Service`/`@Component` and `@Inject`→constructor-injection
transform `be` already applied to 382 core files.

### 4.3 Transactions

`@Transactional` appears **74 times in main** and **544 times in the test tree**. Three shapes matter:

1. **Class-level inheritance.** `org.niord.core.service.BaseService` carries a bare class-level
   `@Transactional` (`jakarta.transaction.Transactional`, which is `@Inherited`), so every service
   extending it is transactional by default. `be`'s `BaseService` keeps the class-level annotation but
   with `org.springframework.transaction.annotation.Transactional`, which is **not** `@Inherited` — Spring
   resolves class-level `@Transactional` on the superclass through its own attribute lookup, so behaviour
   is preserved, but the `TxType` vocabulary is gone.
2. **`NOT_SUPPORTED` suppression.** The three legacy-import entry points
   (`LegacyImportService.plan/apply/undo`, lines 174/230/296) and four endpoints in
   `PublicationSeriesRestService` (lines 1444/1463/1492/1522) are `@Transactional(TxType.NOT_SUPPORTED)`.
   The header at `LegacyImportService.java:82` states it in capitals: without the method-level binding the
   ambient inherited transaction is reaped mid-run and a full-database commit surfaces as a 500. The
   Spring equivalent is `@Transactional(propagation = Propagation.NOT_SUPPORTED)`.
3. **`QuarkusTransaction.requiringNew()`** — 5 main call sites (`LegacyImportService` ×3 with
   `.timeout(1800)`, `IssueWorkMarker:221`) and 4 in `IssueRenderMemberLoadTest`. Plus two
   `@Transactional(TxType.REQUIRES_NEW)` methods on `IssueWorkMarker` (lines 121, 265). These become a
   `TransactionTemplate` with `PROPAGATION_REQUIRES_NEW` and an explicit timeout; the 1800-second timeout
   is load-bearing and must survive.

### 4.4 Test framework and the properties the tests depend on

**54 `@QuarkusTest` classes**, all in `niord-core`; **0** in `niord-web` (`niord-web` has no
`@QuarkusTest` and no test `application.properties` — its tests need no container). The Quarkus tests
read `niord-core/src/test/resources/application.properties`, whose load-bearing keys are:

| Key | Value | Why |
|---|---|---|
| `quarkus.datasource.db-kind` | `mysql` | the suite exercises MySQL behaviour deliberately |
| `quarkus.datasource.jdbc.url` | `jdbc:mysql://localhost:13306/niord?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true` | without the time-zone parameters every `DATETIME` comes back shifted by the local offset, consistently, and a membership rule that turns on release-moment precision breaks invisibly |
| `QUARKUS_DATASOURCE_JDBC_URL` (env) | overrides the above and reaches the surefire fork | how a run gets an isolated schema |
| `quarkus.hibernate-orm.database.generation` | `none` | Flyway owns the schema, exactly as in a deployment |
| `quarkus.flyway.migrate-at-start` / `baseline-on-migrate` / `baseline-version` / `locations` | `true` / `true` / `0` / `db/migration` | the container is seeded once from `schema/baseline-MaDaMe.sql`, Flyway baselines at 0 and applies on top |
| `hibernate.id.db_structure_naming_strategy` | `legacy` | matches the deployment; changing it changes how ids are allocated |
| `quarkus.transaction-manager.default-transaction-timeout` | `600` | whole-corpus differential scans exceed the 60 s default and abort mid-scan with `ARJUNA016102` |
| `quarkus.scheduler.enabled` | `false` | scheduled jobs raced the tests' own transactions — the Lucene indexer ending `LegacyImportServiceTest`'s dry-run transaction, three to five errors per run |
| `quarkus.oidc.enabled`, `quarkus.keycloak.policy-enforcer.enable` | `false` | no identity provider in a unit test |

`niord-core/src/test/java/org/niord/core/DatabaseAvailable.java` probes `localhost:13306` (3 attempts,
5 s timeout, overridable with `-Dniord.test.db.host/-Dniord.test.db.port`); the database-backed tests skip
when it is absent. In `be` the equivalents are `ITTest`'s `@DynamicPropertySource` and the Docker
assumption guard — which means **every `@QuarkusTest` class must be renamed to `*ITTest`**, or Failsafe
will never run it and Surefire will exclude it.

### 4.5 Flyway migrations

The chain is **V1–V19 with V11 and V14 absent**: `V11__membership_uniqueness.sql` was deleted in
`ce839d46` (2026-08-28) before it was ever released, and V14 never existed (`git log --all -- 'V14*'`
returns nothing). One line each:

| Migration | Lines | What it does |
|---|---:|---|
| `V1__publication_series.sql` | 47 | creates the model: `PublicationSeries`, `PublicationSeriesDesc`, `PublicationSeries_languages`, `PublicationIssue`, `PublicationIssueDesc`, `IssueMember`, `IssueOverride`, `IssueAuditEntry` — **21 native `ENUM(...)` columns**. Deliberately does not create `hibernate_sequence`: production already has it, mid-count |
| `V2__audit_created_stamp.sql` | 25 | gives `IssueAuditEntry` its own `created` stamp |
| `V3__cutoff_day_all_seven.sql` | 31 | widens `nominalCutoffDay` from `enum('MONDAY','SUNDAY')` to all seven |
| `V4__membership_provenance_imported.sql` | 29 | adds `IMPORTED` to `membershipProvenance` |
| `V5__shadow_diff.sql` | 54 | creates `ShadowDiffRun` + a unique key and an index |
| `V6__membership_lookup_indexes.sql` | 66 | the indexes answering "which issues is this message in" |
| `V7__membership_identity.sql` | 56 | one message is in one issue once |
| `V8__series_kind.sql` | 59 | `SeriesKind ENUM('SCHEDULED','UNSCHEDULED','ONE_OFF')` |
| `V9__cutoff_default.sql` | 49 | `CutoffDefault ENUM('RELEASE_MOMENT','PERIOD_START','PERIOD_END')` |
| `V10__criteria_snapshot_operands.sql` | 50 | the remaining resolved operands on a published issue |
| `V12__issue_read_path_indexes.sql` | 60 | the indexes the issue read paths need |
| `V13__series_owner_and_availability.sql` | 134 | `domain_id` owner + `SeriesAvailability ENUM('OWNER_ONLY','SELECTED_DOMAINS','ALL_DOMAINS')` and the join table |
| `V15__issue_overrides.sql` | 85 | the `IssueOverride` reshape |
| `V16__drop_issue_week_to_label.sql` | 52 | drops the stored closing-week label — it is arithmetic |
| `V17__drop_public_authority.sql` | 98 | drops `publicAuthority` **and `DROP TABLE IF EXISTS ShadowDiffRun`** — the new model serves the public and nothing switches it |
| `V18__compilation_series.sql` | 149 | a series whose members are another series' published issues |
| `V19__issue_work_marker.sql` | 68 | the record of a release or amend that is RUNNING |

**Twelve of them — V6, V7, V8, V9, V10, V12, V13, V15, V16, V17, V18, V19 — use the `information_schema`
stored-procedure idiom** (`grep -l -i 'information_schema'` and `grep -l 'CREATE PROCEDURE'` return the same
twelve). MySQL 8 has no `ADD/DROP COLUMN IF EXISTS` and no `CREATE INDEX IF NOT EXISTS`, so each reshaping
statement is wrapped in a procedure that consults `information_schema` first; the canonical drop form is in
V16 and the add-if-absent form in V15. Every migration from V6 onward is re-runnable, and `MigrationIdiomTest`
enforces it.

**Proposal: one fresh Liquibase changeset, not eighteen.** The Flyway chain is a *history* — V1 creates
`ShadowDiffRun` and V17 drops it; V1 creates `publicAuthority` and V17 drops it; V1 creates the stored
issue week label and V16 drops it; V3, V4, V8, V9 and V13 exist only to widen enums V1 created too
narrowly. Replaying that history into a database that has never held any of it buys nothing and costs the
whole `information_schema` apparatus. The port adds **`V004__publication_series.sql`** expressing the *net*
schema — the eight tables plus the join table as V19 leaves them, with the indexes from V6, V7, V12 and the
constraints from V15 and V18 — registered in `db.changelog-master.yaml` after `V003__secom.sql`.

What replaces the idiom: **Liquibase preconditions**. The `information_schema` procedures exist because
Flyway cannot express "only if absent"; Liquibase can, with `<preConditions onFail="MARK_RAN">` and
`columnExists`/`indexExists`/`tableExists`. In a fresh changeset expressing the net schema, no precondition
is needed at all — the guard was for re-running a reshape against a database in an unknown state, and a
create-from-nothing changeset has no such state. Preconditions return only if a later changeset reshapes
what V004 created.

Three MySQL-isms do not survive and are not options:

- **Native `ENUM`.** 21 columns in V1 plus five more added by V3/V8/V9/V13. PostgreSQL has no MySQL
  `ENUM`; `be` has **zero** `CREATE TYPE` in V001 and **zero** `columnDefinition` containing "enum".
  Hibernate 7 renders `@Enumerated(STRING)` as `character varying(255)` plus a named CHECK constraint
  (`Publication_status_check` and so on). The closed-set guarantee survives — but **adding an enum
  constant now needs a changeset that drops and recreates the CHECK**, where MySQL needed an `ALTER TABLE`
  on the `ENUM`. The rule "a new constant needs a migration beside it" holds; only its shape changes.
- **The shared `hibernate_sequence`.** `be` has none. `V001__baseline_schema.sql` defines **57
  `CREATE SEQUENCE` statements**, one per entity, each `INCREMENT BY 50`. `grep -i hibernate_sequence` over
  V001 returns nothing. `BaseEntity`'s `@GeneratedValue(strategy = AUTO)` is unchanged; Hibernate 7's
  default interpretation is what differs. **This deletes a documented performance constraint**: the
  3.4 ms-per-row cost of `SELECT … FOR UPDATE` + `UPDATE` on one shared row, which is why the legacy import
  opens an 1800-second transaction and why "JDBC batching does not help, so reduce rows", does not apply
  here. The 1800 s timeout should be kept until it is measured against PostgreSQL, not removed on the
  argument that the cause is gone.
- **`TEXT` columns.** 14 `columnDefinition = "TEXT"` across `Publication`, `PublicationIssue`,
  `PublicationSeries`, `IssueAuditEntry` (plus 3 `varchar(255)`), carrying the criteria documents and
  snapshots through `JpaCriteriaAttributeConverter` and the shared `JpaJsonAttributeConverter` /
  `JpaPropertiesAttributeConverter`. `be` already uses `columnDefinition = "TEXT"` in 29 places against
  PostgreSQL; the `AttributeConverter`s are JPA-standard and port unchanged.
- **Spatial types** are not a publications concern: the publications code has **no** geometry field. The
  three matches for "geometry" under the publications tree are comments in `IssueMemberListService` and
  `IssueMessageLoader` explaining that a member's geometry is loaded as a to-one and should not be fetched
  per member. `be` maps geometries with `hibernate-spatial` 7.2.12 over PostGIS.

### 4.6 REST surface, roles and the marker annotations

Four resource classes under `niord-web/.../publication/`, with class-level paths — in `be` these become
`@RequestMapping("/rest/…")`, which is the same wire path because Quarkus mounts the JAX-RS application at
`/rest`:

| Class | Path | Lines |
|---|---|---:|
| `PublicationSeriesRestService` | `/publication-series` | 1,656 |
| `PublicationIssueRestService` | `/publication-issues` | 1,661 |
| `OneOffRestService` | `/one-off-publications` | 1,216 |
| `PublicationCategoryRestService` | `/publication-categories` | 295 |

Across the four: **68 `@Path`**, **61 `@Produces`**, **62 `@RolesAllowed`**, **9 `@PermitAll`**. Every one
is a rewrite to Spring MVC — `@GET`→`@GetMapping`, `@PathParam`→`@PathVariable`,
`@QueryParam`→`@RequestParam`, `MultipartFormDataInput`→`MultipartHttpServletRequest`, and the
`{folder:.+}` capture-the-rest form→`{*folder}` (whose value arrives with a leading slash; `be`'s
`PublicationRestService` already handles that case). `@RolesAllowed` and `@PermitAll` themselves are kept
verbatim: `SecurityConfig:81` declares `@EnableMethodSecurity(jsr250Enabled = true)`, the single
declaration in the repository, and `RestControllerSecurityAnnotationTest` fails the build for any handler
method carrying neither.

The three marker annotations — `@DomainScoped` (23 uses), `@VersionChecked` (19), `@ProgrammaticAdmin` (2)
— are `@Retention(RUNTIME) @Target(METHOD)` no-ops in `org.niord.web.publication`. They carry no
behaviour and need none: their teeth come from `PublicationTierMatrixTest` and
`PublicationOptimisticLockContractTest`, which reflect over the resource classes. An interceptor is the
wrong shape because the entity is resolved inside the method from a path parameter and an interceptor
would guard the wrong row. They port as-is, and the two contract tests port with them.

**`publication-curate` does not exist in `be`.** `be`'s `org.niord.core.user.Roles` declares exactly
`USER`, `EDITOR`, `ADMIN`, `SYSADMIN`; upstream `Roles.java:50` additionally declares
`PUBLICATION_CURATE = "publication-curate"`. A repository-wide grep for the string in `be` returns nothing.
Adding it is a one-line constant plus a Keycloak realm role — no plumbing, since role→authority mapping runs
through `NiordDomainFilter` and `SecurityConfig.jwtAuthConverter()`.

### 4.7 Error catalogue and exception mappers

`PublicationErrorCatalogue` (394 lines) is a `LinkedHashMap<String,Integer>` of **75 codes**: 42 mapped to
400, 25 to 409, 5 to 404, 3 to 500. One status per code; an unmapped code defaults to 500 and is a bug.
`PublicationExceptionMapper` (155 lines) is `@Provider ... implements ExceptionMapper<PublicationException>`.

In `be` a JAX-RS `ExceptionMapper` has nothing to attach to. The equivalent is a
`@RestControllerAdvice` with an `@ExceptionHandler(PublicationException.class)` — `be` already has two
(`core/conf/RestExceptionStatusAdvice.java`, `core/conf/NullBodyNoContentAdvice.java`), so the pattern and
its ordering are established. The catalogue class itself is framework-free and ports unchanged;
`PublicationExceptionMapperContractTest` and `RailRefusalCatalogueTest` port with it.

### 4.8 Scheduler

**None remains in the publications domain.** `rg '@Scheduled|io.quarkus.scheduler|Scheduler'` over both
publications main trees returns zero hits. The only scheduled work the redesign ever had was the
shadow-diff, whose table V5 created and V17 dropped; `rg -i 'shadowdiff|shadow_diff'` over `src/` outside
`*.md` matches only the two migration files. Nothing has to be added to `ScheduledCronExpressionTest`.

### 4.9 Hibernate specifics, and what `MIGRATION.md` says about Hibernate 7

`MIGRATION.md` treats ORM 7 as a dependency consequence rather than a source change — persistence is plain
`EntityManager` + Criteria + `@NamedQuery` with zero Panache, so it "ports near 1:1". The publications code
fits that: **11 `@Entity`**, **12 `@NamedQuery`**, **31 `@Enumerated`**, **1 `@Converter`**, no Panache, no
Hibernate-proprietary API. The one real Hibernate-7 consequence `MIGRATION.md` calls out is Hibernate
Search: 6.2/orm6 is incompatible with ORM 7, the recommendation is 8.x, and `be` runs Hibernate Search 8.2.
The publications code does not use Hibernate Search directly; it reaches full-text search only through
`MessageService`/`MessageSearchService`.

---

## 5. What must already exist in `be` for the ported code to compile

The publications code imports 40 distinct classes from outside its own packages. Grouped by package, the
import census over the two main trees is: `org.niord.model` 43, `org.niord.core.service` 25,
`org.niord.model.publication` 23, `org.niord.core.user` 20, `org.niord.core.domain` 20,
`org.niord.model.message` 19, `org.niord.core.message` 18, `org.niord.core.model` 12,
`org.niord.core.batch` 10, `org.niord.core.util` 9, `org.niord.core.db` 5, `org.niord.core.category` 4,
`org.niord.core.area` 4, `org.niord.core.report` 3, `org.niord.core.chart` 3, `org.niord.core.repo` 2,
and singletons from `script`, `mailinglist`, `geojson` and `settings.annotation`.

*Command: `rg -N -o -I '^import org\.niord\.(core|web|model)\.[A-Za-z0-9_.]+' <publications trees> | sort -u`,
then `rg -l`/`find` for each name under `be/niord/src/main/java`.*

| Dependency | In `be`? | Class / path (under `be/niord/src/main/java/`) | Note |
|---|---|---|---|
| Legacy `Publication` entity | yes | `org/niord/core/publication/Publication.java` (+ `PublicationDesc`, `PublicationSearchParams`, `PublicationService`, `PublicationUtils`, `PublicationTemplateUpdateCtx`) | faithful; the port adds `PublicationMapper` (VO mapping extracted from the entity) |
| `PublicationRestService` legacy search (the public union's legacy half) | yes | `org/niord/web/PublicationRestService.java` | `@RestController @RequestMapping("/rest/publications")` with class-level `@PermitAll`; `GET /search` at L116 is open, as are `/all`, `/publication/{ids}`, `/export`; ~~`/search-details` is USER~~, the rest ADMIN. Wire path identical to Quarkus. *2026-09-25:* anonymous `/search` and `/all` serve the public tier only, and `/search-details` is ADMIN |
| `PublicationCategory` | yes | `org/niord/core/publication/PublicationCategory{,Desc,Service}.java` | faithful. `be` still has `org/niord/web/PublicationCategoryRestService.java` at the **old** location; the redesign moves it into `org.niord.web.publication` and deletes the old one |
| `Domain`, `DomainService` | yes | `org/niord/core/domain/Domain.java`, `DomainService.java` | plus port additions `DomainLookupService`, `DomainResolver`, `DomainMapper` |
| `User`, `UserService`, `Roles` | yes (Roles incomplete) | `org/niord/core/user/{User,UserService,Roles}.java` | **`publication-curate` missing** — see 4.6 |
| `MessageSearchParams` | yes | `org/niord/core/message/MessageSearchParams.java` | faithful |
| `MessageService.search(MessageSearchParams)` | **moved** | `org/niord/core/message/MessageSearchService.java:125` | `be`'s `MessageService` has **no** `search(...)`; the port extracted it into `MessageSearchService` (Criteria-based). Every publications call site must be re-pointed. `MessagePostgresIndex`, `MessageSearchTextBuilder`, `MessageQueryService` are also new |
| `MessageTag`, `MessageTagService` | yes | `org/niord/core/message/MessageTag.java`, `MessageTagService.java` | faithful |
| `MemberSetDesignation`, `PublicationMemberSetSource` | **no** | — | added by the redesign branch in `org.niord.core.message`; must travel with the port |
| `RepositoryService` (file repository) | yes | `org/niord/core/repo/RepositoryService.java` | also a `@RestController` now; path API intact. Siblings `FileTypes`, `IRepoBackedVo`, `ThumbnailService` present |
| `FmReportService`, `FmReport` | yes | `org/niord/core/report/FmReportService.java`, `FmReport.java` | faithful |
| `FmTemplateService` (+ `ProcessFormat`) | yes | `org/niord/core/script/FmTemplateService.java` | `ProcessFormat{TEXT,PDF}`, `process(...)` at L244; PDF via `script/pdf/HtmlToPdfRenderer.java`. The branch's `ResourceFetchLog` addition is not in `be` |
| `MailingListTrigger` (publication trigger) | partly | `org/niord/core/mailinglist/MailingListTrigger.java` | the classes exist but `TriggerType` is `{STATUS_CHANGE, SCHEDULED}` and `grep -ri publication` over the whole mailinglist package returns **zero** — there is no publication/publish trigger. `MailingListMessageListener` is a `Runnable @Component` on the message-status topic, with a `// PORT:` note to re-wire as `@JmsListener` |
| Audit entry | **no** | — | no `org.niord.core.audit` package and no `AuditEntry`-style entity. `IssueAuditEntry`/`IssueAuditService` have no counterpart and come with the port. What does exist is `org/niord/core/model/VersionedEntity.java` (`@Version`, `created`, `updated`, `@PrePersist`/`@PreUpdate`) — byte-for-byte faithful, and what `PublicationIssue` and `PublicationSeries` extend |
| `BaseService` | yes | `org/niord/core/service/BaseService.java` | class-level `@Transactional`, but `org.springframework.transaction.annotation.Transactional` — see 4.3 |
| `CriteriaHelper`, `JpaJsonAttributeConverter`, `JpaPropertiesAttributeConverter` | yes | `org/niord/core/db/` | faithful |
| `AbstractBatchableRestService`, `AbstractItemHandler` | yes | `org/niord/core/batch/` | JBeret wired, `SpringArtifactFactory` resolves `@Named` artifacts from the Spring context |
| `Area`/`AreaService`, `Category`/`CategoryService`, `Chart`/`ChartService`, `FeatureCollection`, `@Setting` | yes | respective packages | faithful |

**No publications-redesign code exists in `be` today.** `rg 'PublicationSeries|PublicationIssue|publication\.series'`
over the whole of `be` returns no files; `find be -type d -name publication` finds four directories, none
with a `series/` subtree; `find be -name 'Publication*.java'` finds 55 files, every one legacy. Every
`be` commit on record is SECOM/S-124 work.

---

## 6. Everything outside Java

*Commands: `find <dir> -type f | sort`, `wc -l`, `rg -n 'EXPORT_PATH|NIORD_REPO' scripts/publications/*.js`.*

**The two generated manifests and their generators.** `niord-core/src/test/resources/rule-ids.txt`
(105 lines) and `entity-fields.json` are exported *from* `niord-app`, carry the SHA-256 of the
specification slice they were generated from, and must not be hand-edited. They are produced by
`niord-app/scripts/publications/gen-rule-ids.js` (from `docs/publications/DATA-MODEL.md` section 8) and
`gen-field-manifest.js` (from sections 3.1–3.10), and checked by `spec-check.js` (16.9 KB), which shells
out to both. Repointing is already parameterised: each generator resolves its target as `--out <path>`,
else `$NIORD_REPO/<EXPORT_PATH>`, else the sibling `../../../niord` checkout. **`EXPORT_PATH` itself must
change** — it is hard-coded as `niord-core/src/test/resources/<file>` in both generators, and in this
repository the module is `be/niord`, so the path becomes `be/niord/src/test/resources/<file>` with
`NIORD_REPO` pointing at the repository root.

**The specifications and the go-live set.** `docs/publications/` is 14 files / 7,066 lines:
`DATA-MODEL.md`, `API-CONTRACT.md`, `COMPILATION-SERIES-PLAN.md`, `domain-rules.md`, and
`go-live/` (`GO-LIVE-PROCESS.md`, `GO-LIVE-TASKS.md`, `PRODUCTION-CUTOVER-RUNBOOK.md`, `RE-RUNNABILITY.md`,
`drop-publication-tables.sql`, and four `scripts/rehearsal-*.sh` + `lib.sh`). The go-live set is kept
verbatim until go-live and the two specifications are guarded by `spec-check.js`;
`niord-app/scripts/docs-check.mjs` excludes both from its vocabulary guard for exactly that reason.

*Proposal:* `fe/docs/publications/` — beside the rest of the frontend documentation set that replaces
`fe/.claude`, and the location the generators' relative fallback (`../../docs/publications/DATA-MODEL.md`)
already assumes. The root `docs/` here holds only external standards PDFs (`docs/specifications/`), and
splitting the publications specification from the `docs/` set it cross-links into would break every
relative link in it. The counter-argument is real — the specifications describe the *backend* contract and
the guard runs against `be/` — and it is a decision for the owner (section 8).

**`scripts/*.mjs` in `niord`.** Four Node ESM tools, run by hand and never by CI (1,086 lines):
`capture-publication-fixtures.mjs` (288), `capture-legacy-publication-estate.mjs` (281),
`seed-dev-database.mjs` (213), `seed-publication-series.mjs` (304). A full `seed-publication-series.mjs`
run cannot be undone — it leaves an undeletable published issue behind. They drive the real REST endpoints,
so they survive the framework change untouched; only the module path in any relative output changes.

**Fixtures and the schema baseline.** `niord-core/src/test/resources/fixtures/` is 42 files and **20 MB**:
`publications/` (the hazard fixtures plus `manifest.json` and `hashes.txt`), `legacy-estate/`
(`estate-harvest.json`, `publications.json`, `templates.json`, `members.json`, `member-anomalies.json`,
`message-tag-filters.json`, `seams.json`, `profile.json`, `hashes.txt`) and `import-check-trigger-audit.md`.
`FixtureCoverageTest` matches 19 hazard fixtures by name and by hash, never merely by count.
`src/test/resources/schema/` holds `baseline-MaDaMe.sql` (255 lines, 37 KB) and its `README.md` — the
byte-deterministic MySQL baseline the test container is seeded from, regenerated by Hibernate (which emits
CRLF on Windows; the committed form is LF). `.gitattributes` pins LF on `*.mjs`, `fixtures/**`, `schema/**`
and `rule-ids.txt` because each is hashed or compared byte for byte, and a CRLF checkout fires the drift
guards on a clean clone — **this repository needs the same `.gitattributes` entries, rooted at `be/`,
before the files land.** The MySQL baseline itself has no target: `be` builds its schema from Liquibase
V001 against PostgreSQL and layers `db/niord-base-data.sql` on top, regenerated by
`be/niord/scripts/regen-test-seed-postgres.sh`.

**CI.** `be` is built by `.github/workflows/be-ci.yml` at the repository root (282 lines, deliberately
not under `be/` — GitHub only reads workflows from the root). Two parallel jobs: `./mvnw verify -DskipITs`
(Surefire, no Docker) and `./test-it` (Surefire + Failsafe/Testcontainers, which fails a zero-test run
instead of reporting it green). `pull_request` is deliberately not path-filtered so required checks always
report. **There is no frontend workflow in this repository** — `niord-app` has `ci.yml` and an Azure Static
Web Apps workflow of its own, neither of which has an equivalent here. `niord`'s own
`niord-app-azure-pipelines.yml` runs no MySQL, so its database tests skip on CI by design; in `be` the
Testcontainers job removes that gap, which is a net improvement the port inherits for free.

---

## 7. Test inventory

*Commands: `rg -N --no-filename '^\s*@Test\b' <dir> | wc -l`, `rg -l '^\s*@Test\b' <dir> | wc -l`,
`rg -l '^\s*@QuarkusTest' <dir> | wc -l`.*

**1,250 `@Test` methods in 118 test classes.**

| Package | Files | Classes with `@Test` | `@Test` methods |
|---|---:|---:|---:|
| `org.niord.core.publication` (root: `CitationFormatTest`, `PublicationExportRoundTripTest`, `PublicationResolutionTest`, `TestIds`) | 4 | 3 | 37 |
| `…publication.series` (root) | 64 | — | 680 |
| `…publication.series.legacy` | 26 | — | 245 |
| `…publication.series.resolve` | 5 | — | 80 |
| `…publication.series.criteria` | 2 | — | 18 |
| `…publication.series.vo` | 2 | — | 8 |
| `…publication.series.batch` | 1 | — | 4 |
| **niord-core total** | **104** | **98** | **1,072** |
| `org.niord.web.publication` | 20 | 20 | 178 |
| **Total** | **124** | **118** | **1,250** |

Six of the 104 core files are helpers with no `@Test`: `TestIds`, `series/BindsRule`,
`series/StubIssueRenderService`, `series/TestOwnerDomain`, `series/legacy/EstateSlice`,
`series/legacy/LegacyEstateFixture`.

**54 of the 104 core files are `@QuarkusTest` against the MySQL container; the other 44 classes that carry
a `@Test` are plain JUnit** (plus the 6 helpers). All 20 `niord-web` classes are plain JUnit — `niord-web`
has no `@QuarkusTest` and no test `application.properties`, because the web tests are contract tests that
reflect over the resource classes rather than calling them. The 54 become `*ITTest` on `be`'s `ITTest`
base class; the 64 plain classes (44 core + 20 web) stay Surefire units and are the cheapest thing to land
first.

**Guard tests that must be re-homed.** Six of them, and only two live inside the publications packages:

| Guard | Current location | What it holds |
|---|---|---|
| `TestSuiteGuardTest` | `niord-core/src/test/java/org/niord/core/` | discovers everything under `org.niord` and asserts `FLOOR = 590` (line 57), excluding its own tests so the floor stays honest. Raised as tests are added, never lowered. Exists because `quarkus-junit5` flips Surefire to the JUnit Platform provider and without `junit-vintage-engine` the JUnit 4 suite goes undiscovered as "Tests run: 0 … BUILD SUCCESS". **In `be` the framework reason is gone** (JUnit 5 throughout, 504 test classes / 6,010 `@Test` already) but the floor idea is not — it needs a new number against the merged suite |
| `InvariantManifestTest` | `…/core/publication/series/` | every id in `rule-ids.txt` bound to a named assertion via `@BindsRule` (84 uses); `pending` only for a task in the closed list |
| `EntityContractTest` | `…/core/publication/series/` | no entity brings its own id generator, and `entity-fields.json` and the fields agree in both directions, types included. **Its first assertion is about the shared `hibernate_sequence` and must be restated for `be`'s 57 per-entity sequences** |
| `MigrationIdiomTest` | `niord-core/src/test/java/org/niord/core/` | V6 onward re-runnable. **Has no meaning against a single fresh Liquibase changeset** — retire it or replace it with a Liquibase-precondition check |
| `PublicationTierMatrixTest` | `niord-web/src/test/java/org/niord/web/publication/` | every publication endpoint and caller in one declared table of four tiers; a new endpoint fails the build until its tier is written down. The enforcement behind `@DomainScoped`/`@VersionChecked`/`@ProgrammaticAdmin` |
| `FixtureCoverageTest` | `niord-core/src/test/java/org/niord/core/` | 19 hazard fixtures matched by name and hashed, reading `/fixtures/publications/` |

Two more in the same directory travel with them or are replaced: `FlywayBaselineTest` (no Flyway in `be`)
and `CoreQuarkusBootstrapTest` (asserts the server really reports MySQL 8.0.x so the suite fails rather
than passes with no container — `be`'s `ITTest` Docker assumption and `LiquibaseBootstrapITTest` cover the
same ground for PostgreSQL). `DatabaseAvailable` is superseded by `ITTest`.

Alongside these sit the API, exception-mapper, optimistic-lock, transaction, rail-refusal and payload
contract tests in `org.niord.web.publication` (`PublicationApiContractTest`,
`PublicationExceptionMapperContractTest`, `PublicationOptimisticLockContractTest`,
`PublicationTransactionContractTest`, `RailRefusalCatalogueTest`, `PublicationSearchUnionTest`,
`SeriesUpdateContractTest`, `SeriesValidateEndpointTest`, `SeriesDraftAndCopyContractTest`,
`IssueConsumerEndpointContractTest`, and the report/template tests).

---

## 8. Proposed port sequence

Each step has one acceptance criterion, and each is a commit that leaves the reactor green.

| # | Step | Acceptance |
|---|---|---|
| 0 | Tag both sources; record the two tag names and the three HEADs in this file | `git rev-parse <tag>` in each source repository matches section 1 |
| 1 | **Frontend fold-in.** Replay `18eef1a9..<niord-app tag>` onto `fe/`, keeping `fe/`'s SECOM section and the 12 wiring files; delete `fe/.claude`, `fe/migration_docs`, `fe/memory`; bring `docs/` + the two vendored skills | `npm run verify` in `fe/` green; `npm run docs:check` green; the SECOM section still routes and its 33 files are untouched |
| 2 | **Backend: non-publications prerequisites.** `MemberSetDesignation`, `PublicationMemberSetSource`, the `MessageSearchParams`/`SystemMessageVo`/`FmTemplateService`/`HtmlToPdfRenderer`/`ResourceFetchLog` deltas, `Roles.PUBLICATION_CURATE` | `mvn -pl niord test` green; the new role constant appears in the Keycloak realm JSON used by the ITs |
| 3 | **Liquibase changeset.** `V004__publication_series.sql` expressing the net V1–V19 schema, registered in `db.changelog-master.yaml` | `LiquibaseBootstrapITTest` green against a fresh un-seeded container: Liquibase builds the schema and `ddl-auto=validate` passes |
| 4 | **Entities and value objects.** The 11 `@Entity` classes, the `vo/` package, the enums, `JpaCriteriaAttributeConverter` | `mvn -pl niord test` green; Hibernate validates every new table against V004 at IT startup |
| 5 | **Pure rule modules.** `resolve/`, `criteria/`, `series/` calculators and validators — everything with no injector | the 70 plain-JUnit classes run as Surefire units and pass unchanged |
| 6 | **Services.** `series/*Service`, `legacy/`, `batch/`, with CDI→Spring DI, `@Observes StartupEvent`→`@EventListener(ApplicationReadyEvent)`, `QuarkusTransaction`→`TransactionTemplate` (1800 s kept) | context starts; `LegacyImportServiceTest` and `IssueWorkMarkerTest` pass as `*ITTest` |
| 7 | **REST layer.** Four resource classes rewritten to `@RestController`, the catalogue, the `@RestControllerAdvice`, the three marker annotations | `PublicationTierMatrixTest` and `RestControllerSecurityAnnotationTest` both green |
| 8 | **Tests and fixtures.** 54 `@QuarkusTest`→`*ITTest`, the 20 web contract tests, the fixtures, the two generated manifests, the re-homed guards with restated floors | `./test-it` green; `FixtureCoverageTest` matches all 19 hazard fixtures by hash |
| 9 | **Guards and CI.** `.gitattributes` LF pins under `be/`; `spec-check.js` and the generators repointed at `be/niord/src/test/resources/` | `node scripts/publications/spec-check.js` clear; a deliberate one-character edit to `DATA-MODEL.md` section 8 makes it fail |
| 10 | **Rehearsal.** Fresh restore → deploy → `POST /rest/publication-series/import-legacy/validate` → `import-legacy` → `import-check` | as below |

**The rehearsal acceptance, in full** (it is the only acceptance that matters):

1. Fresh restore of a production copy; publication tables dropped; schema rebuilt by Liquibase alone.
2. Deploy at the frozen image tag; `import-check` on the empty estate reads `importedIssues: 0`.
3. `/public/v1/publications` and `/rest/publications/search` captured **before** anything is written.
4. Dry run returns `problems: []` and figures equal to the expectation written down beforehand. Either
   failing is a hard stop.
5. Import returns 200 inside the 1800 s budget with room for one retry.
6. One boot with schema validation on and migration off starts cleanly — entity/schema drift fails here,
   loudly, not mid-window.
7. `import-check` is **clear: true over a non-zero `importedIssues`**, with `violations: []`,
   `triggerAudit: []`, `idCollisions: 0`, `duplicateMemberships: 0`, `duplicateOverrides: 0`,
   `counts.seriesWithoutOwner: 0`, `counts.publishedIssuesWithoutStamp: 0`.
8. The public list after the import has the same ids and the same category-priority order as step 3, with
   the newest week present exactly once, and **a name read from the admin list** — the import check counts
   rows, not content, and a name rendered as an id is the one defect it cannot see.
9. Anonymously: `/public/v1/publications` equals the legacy public tier, the newest issue's file opens, and
   a message detail page renders its citation and resolves `publication=<id>` back to a filtered list.

### Decisions the owner takes before step 1

1. **Changelog strategy.** One fresh `V004__publication_series.sql` expressing the net schema (recommended,
   section 4.5) versus eighteen changesets replaying the Flyway history. The fresh changeset loses the
   per-migration headers, which are the written record of why each reshape happened — if those are to be
   kept, they belong in V004's header or in `domain-rules.md`, not in dead changesets.
2. **Where the documentation lives.** `fe/docs/publications/` (recommended) versus root `docs/publications/`
   versus splitting the specifications to `be/docs/`. Whatever is chosen fixes the generators' relative
   fallback and `docs-check.mjs`'s exclusion list.
3. **Whether the legacy import stays in the ported code after go-live.** `legacy/` is 13 main classes and
   26 test files carrying 245 of the 1,250 `@Test` methods — and it is one-shot by construction
   (`ALREADY_IMPORTED`, and `DELETE /import-legacy` refused once an imported series has left DRAFT). If it
   is to be removed after go-live, porting it is throwaway work; if it is to stay as the record of how the
   estate was built, it is ported and kept green forever. Either way it cannot be skipped *before* go-live:
   the rehearsal in step 10 is the import.
4. **Testcontainers per module versus one shared container.** `be`'s ITs share lazily-started singleton
   containers. The publications suite is 1,250 tests, some of them whole-corpus scans that needed a
   600-second transaction timeout on a warm MySQL. Whether those run against the shared singleton (fast,
   but ordering-sensitive and they leave state) or a dedicated container (isolated, slower) decides how
   `ITTest` is subclassed and whether the timeout stays at 600 s.
5. **Tag names.** One name per source repository, recorded here.

---

## 9. Risks

**`be/MIGRATION.md` is the wrong map in three places, and it is the document a porter reads first.** It
promises Jersey and `@Path` kept ("resources + RestAssured ITs port ~1:1"); `niord/pom.xml:78` says Jersey
is removed and 45 `@RestController` classes say Spring MVC. It promises `jakarta.transaction.Transactional`
kept; zero main-source files import it and 88 import Spring's. It describes Testcontainers MySQL;
`docker-compose.yml` and `NiordTestContainers` run PostgreSQL 18 + PostGIS. A plan built on the document
rather than the tree will size the REST rewrite at zero.

**The REST rewrite is the largest single mechanical risk.** 68 `@Path`, 62 `@RolesAllowed`, 61 `@Produces`
and 9 `@PermitAll` across four classes totalling 5,528 lines, and the failure mode of getting method
security wrong is fail-**open**, not fail-closed. `SecurityConfig` sets `anyRequest().permitAll()` at the
HTTP layer because tickets travel as `?ticket=` query parameters, so `@RolesAllowed` is the entire guard.
`RestControllerSecurityAnnotationTest` catches a *missing* annotation; it cannot catch a *wrong* one.

**`publication-curate` does not exist here.** It is outside the `user < editor < admin < sysadmin` ladder,
it gates every hand-edit of an issue's members, and endpoints needing it also accept ADMIN as a
transitional fallback. Porting the code without adding the realm role silently promotes curation to
"any admin" — which is what the transitional fallback looks like from the outside, so it will not fail a
test.

**Native `ENUM` has no PostgreSQL equivalent, and the guarantee it provided is the model's spine.** 26
enum columns across the eight tables; `be` renders them as `varchar(255)` + a named CHECK. The rule
"closed sets reject unknown values, so a new constant needs a migration beside it" survives, but its
enforcement moves from the column type to a constraint that a careless changeset can drop and not
recreate.

**The shared-sequence performance model does not transfer, and neither do the numbers derived from it.**
The 1800 s import transaction, "bulk writes are latency-bound on the shared `hibernate_sequence`", and
"JDBC batching does not help, so reduce rows" are all measurements of a MySQL single-row sequence at
3.4 ms per id. `be` has 57 per-entity sequences with `INCREMENT BY 50` and no `hibernate_sequence` at all.
The import may be far faster — or bounded by something else entirely — and the only honest response is to
re-measure during the rehearsal rather than to relax the timeout on the argument.

**The 54 `@QuarkusTest` classes fail silently if renamed wrongly.** `be` splits on the class name:
Surefire includes `**/*Test.java` and excludes `**/*ITTest.java`; Failsafe includes only `**/*ITTest.java`.
A class left as `FooTest` that needs a container will run as a unit test and fail confusingly; a class
renamed to `FooITTest` on a machine without Docker skips, and a skipped IT suite reports "Tests run: 0"
with BUILD SUCCESS — which `be/CLAUDE.md` and `be/docs/test-infrastructure.md` both flag, and which
`./test-it` exists to catch.

**The source branch mixes two features.** `feature/publications-redesign` carries 31 files of S-124 MaDaMe
promulgation (`niord-s124-madame/`, plus renames moving the old `S124RestService`/`S124Service` under
`s124/old/`) that have nothing to do with publications, and this repository has its own, newer S-124 work
(`b14d055e`, `c5fa844c`, `166de222`). Taking the branch wholesale would regress it.

**Both source branches are live.** `niord-app` `main` advanced from `d672fcd3` to `1c9ab817` while this
inventory was being measured, and neither repository has a tag for this work. Without step 0 the port
reads a moving target, and the `rule-ids.txt`/`entity-fields.json` source hashes — the only mechanism that
detects frontend/backend specification drift — will be comparing against a specification that has since
changed.

**Guard tests lose their meaning quietly rather than loudly.** `MigrationIdiomTest` asserts a Flyway idiom
that a Liquibase port removes; `FlywayBaselineTest` has no Flyway; `CoreQuarkusBootstrapTest` asserts
MySQL 8.0.x; `EntityContractTest`'s first assertion is about a sequence that no longer exists;
`TestSuiteGuardTest`'s floor of 590 is far below `be`'s existing 6,010 `@Test` methods. Each of these
passes-by-deletion or passes-vacuously if it is ported without being restated — and the whole point of the
set is that it fails when a rule breaks.

**`.gitattributes` is not optional.** `fixtures/**` (20 MB, hash-matched), `schema/**`, `rule-ids.txt` and
`*.mjs` are compared byte for byte. Landing them in this repository before the LF pins are in place gives
every subsequent clone on Windows a CRLF checkout and a set of drift-guard failures whose cause is
invisible in the diff.

---

## 10. Execution record (2026-09-14 to 2026-09-15)

What was done against the sequence in section 8, and what it left open. Every count is from the run named
beside it.

| # | Step | Outcome |
|---|---|---|
| 0 | Tag both sources | done — `publications-port/2026-09-14` on all three repositories (section 1) |
| 1 | Frontend fold-in | done — history-preserving merge (section 1), staged and uncommitted; `npm run lint`, `test` (5,107), `build`, `build:dk`, `format:check`, `docs:check` all green; SECOM files untouched |
| 2 | Backend prerequisites | done — `MemberSetDesignation`, `PublicationMemberSetSource`, `MessageSearchParams`/`MessageSearchService` deltas (the search lives in `MessageSearchService` here), `FmTemplateService`, `Roles.PUBLICATION_CURATE` plus both realm exports, the two FreeMarker report templates, `pdf_en`/`pdf_da` keys, the niord-dk intro templates |
| 3 | Liquibase changeset | done — `V004__publication_series.sql` cut from the DDL Hibernate 7 itself generates for the ported entities (a throwaway IT with `jakarta.persistence.schema-generation.scripts.action=create`), reformatted in V001's style, registered after V003; `LiquibaseBootstrapITTest` green |
| 4 | Entities and value objects | done — 11 entities, 31 `vo/` files, the criteria converter; `ddl-auto=validate` passes against V004 |
| 5 | Pure rule modules | done — the plain-JUnit classes run as Surefire units unchanged |
| 6 | Services | done — `PublicationTxRunner` (REQUIRES_NEW, 1800 s import budget) replaces `QuarkusTransaction`; `@EventListener(ApplicationReadyEvent)` replaces `@Observes StartupEvent`; a bounded executor replaces `ManagedExecutor` |
| 7 | REST layer | done — four `@RestController` classes (68 mappings, roles and marker annotations verbatim), `PublicationExceptionAdvice` with the mapper's exact body shape; `RestControllerSecurityAnnotationTest` and `PublicationTierMatrixTest` green; the old `org.niord.web.PublicationCategoryRestService` and its IT deleted |
| 8 | Tests and fixtures | done — 141 test files; 54 `@QuarkusTest` classes are `*ITTest` on `ITTest`; ported Surefire units 742/742; whole-module units 4,294 with 3 pre-existing Windows/locale failures on untouched files; publications integration selection 708 tests, 0 failures, 0 errors, 27 skipped (61 classes, `LiquibaseBootstrapITTest` included); whole-module integration run 2,964 tests with 10 pre-existing Windows path-separator failures in two untouched classes |
| 9 | Guards and CI | done — `be/.gitattributes` LF pins (fixtures, manifests, golden files, `*.mjs`); `fe/scripts/publications/*` resolve `be/niord`; `spec-check.js` clean; `FixtureCoverageTest` matches all 19 hazard fixtures by hash; `TestSuiteGuardTest` restated with a floor of 6,745 |
| 10 | Rehearsal | **run locally on 2026-09-15** on the `niord_test` dump with the go-live scripts (`NIORD_API=http://localhost:8888/rest`, local Keycloak, `sysadmin`), after a `pg_dump` snapshot of the database: precheck clean (0 series, import check clear over 0, public feed 5 entries, search feed 1,081, dry run 13 series / 1,081 issues / 0 problems); import 200 in **24 s** against the 1,800 s budget with the same figures; import check `clear: true` over 1,081 imported issues, 0 violations, 0 trigger-audit rows, 0 id collisions, 0 duplicate memberships or overrides, 0 series without owner, 0 published issues without stamp; the public feed identical before and after (ids, order, types); the search feed gained exactly the 8 compilation issues the import creates and lost nothing (the script's type column pairs by document order and mis-pairs new-model rows, so read its id diff, not its type diff); all 13 series activated in their owner domains, feeds unchanged by activation; anonymously the newest week's PDF opens (44 KB), `publication=<id>` returns its 26 members, the admin picker offers it, the frontend chip names the issue and lists the members, and a message detail renders its citation. Then the extra step: the open week issue of `weekly-ntm` (two languages, 10 members) released through the real renderer in 3 s, both PDFs written (27 and 32 KB), successor opened, the new issue resolves to its 10 members. Step 6 (a restart with validation on) is the owner's restart of the running stack. The earlier smoke (same day) had covered the read-only steps: `start-local.sh --dk` booted `niord-dk` on the `niord_test` dump (Liquibase applied V004, `ddl-auto=validate` passed, start in 19 s); anonymously `/rest/public/v1/publications`, `/rest/publications/search` and `/rest/publication-categories/all` answer 200 with the legacy data and the admin routes answer 401; as sysadmin `import-check` reads `clear: true` over `importedIssues: 0`, and `import-legacy/validate` returns `wouldSucceed: true`, `problems: []` over 5 categories, 13 series and 1,081 issues (4 OPEN, 1,046 PUBLISHED, 31 RETIRED; 8 compilation issues). No import was run |

**The five decisions.** (1) One fresh changeset, with the three history breadcrumbs in its header. (2) The
specifications and the go-live set live in `fe/docs/publications/`; the generators default to this
repository's `be/niord` module. (3) The legacy import is ported and kept green. (4) The shared singleton
containers; the whole-corpus scans skip through `Assumptions` because the IT seed holds no `Message` rows
(see the gaps below). (5) Tag name `publications-port/2026-09-14`.

**Rulings taken during the port** (each is recorded at the site in a `PORT:` comment):

- Jackson 3 everywhere, except `CriteriaSerialization`, `CriteriaValidator` and `JpaCriteriaAttributeConverter`,
  which keep Jackson 2 so the canonical criteria bytes the fixture hashes depend on do not move.
- Services keep field `@Inject`; controllers take constructors; loggers are static.
- Spring `@Transactional` (`Propagation.NOT_SUPPORTED` on the legacy-import entry points); `PublicationTxRunner`
  carries the 1800 s budget, unmeasured on PostgreSQL.
- Native SQL names are quoted mixed-case; the five nullable descending sorts carry `NULLS LAST` (PostgreSQL
  puts nulls first), plus the three sibling queries pinned for consistency.
- Membership searches built in code set `kinds(WARNING)`, so S-124 auto-cancellation warnings stay out of issues.
- SECAUD-6: a designated member set bypasses the status floor, but an unauthorised caller is clamped to
  PUBLISHED/EXPIRED/CANCELLED.
- `@GZIP`/`@NoCache` are gone; the document endpoints set `Cache-Control: no-store` explicitly.
- Three columns are deliberately unchecked in V004 (`IssueAuditEntry.action`, `PublicationIssue.intervalToSource`,
  `snapshotTimeRelation`), as their mappings pin; the availability join table keeps V13's `ON DELETE CASCADE`.
- Tests: Spring rollback by default, `Tx.requiringNew()` where a commit is required, and a committed
  cleanup ends the test-managed transaction first — the first run hung for nine hours on exactly that lock
  wait, so every IT run now also sets a 60 s PostgreSQL `lock_timeout`.
- `StubIssueRenderService` is a `@Primary` test bean and therefore module-global.
- Hibernate 7 forced three changes the source never needed: an issue's members, overrides and audit trail are
  removed through the persistence context (bulk JPQL deletes left managed rows pointing at the removed issue
  and Hibernate 7 refuses the flush); `StaleVersionGuard` bumps a revision by dirtying the `updated` stamp
  (the `PESSIMISTIC_FORCE_INCREMENT` lock request is a no-op once the row was written in the transaction —
  PORT-12); `IssueListService.issuesOf()` lost a `DISTINCT` that PostgreSQL rejects beside its `ORDER BY`
  and that Hibernate 6+ made redundant.
- `AnnualCutoffITTest` carried three expectations the source itself no longer met (its Quarkus twin was
  `@EnabledIf`-gated and had been skipping since commit `137ad4cf` changed `publishedAtOf`); the port restated
  them to the rule as shipped.
- The IT seed names its domains `niord-client-nm` and so on, while the legacy-import rulings name `niord-nm`
  and `niord-annex`; the tests that exercise the ruled path create those desks inside their own rolled-back
  transaction. A real import against the seeded IT database would report `OWNER_DOMAIN_NOT_FOUND`; the
  test-environment restore carries the production domain ids and is unaffected.

**Running the backend suite here.** Windows needs `NIORD_HOME` exported with forward slashes before any
integration test (PORT-1 in `be/docs/potential-errors.md`), and the Failsafe provider must be fetched once
online. Then, from `be/`:

```bash
./mvnw -o -pl niord test                                                        # units
NIORD_HOME=C:/Users/<you>/.niord-ng-it ./mvnw -o -pl niord verify -Dtest=_none_ \
  -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='**/publication/**/*ITTest' \
  "-Dspring.datasource.hikari.connection-init-sql=SET lock_timeout = '60s'"     # publications ITs
```

**Gaps this record leaves open.**

1. **No message corpus in the IT seed.** `PublicationResolutionITTest` (12 of 27), `MemberResolutionDifferentialITTest`
   (5 of 10), `ResolutionDiagnosticsITTest`, `IssueRenderMemberLoadITTest` and a few single cases skip. The
   SQL-versus-rule differential and the `publication=` resolution proof are therefore silent in CI until a
   corpus seed is decided (a Liquibase seed changeset for ITs, or a scheduled run on the test environment).
2. **Suspected issues** PORT-1 to PORT-11 in `be/docs/potential-errors.md`; the three pre-existing Windows/locale
   unit failures are described in the wave report, not fixed.
3. **`fe/docs/publications/API-CONTRACT.md` §7.1** shows an error body with a `details` object and
   `{field, code}` field errors; the mapper and now the advice emit top-level keys and `{rule, field, message}`.
   The code is the contract the frontend was written against; the section needs correcting.
4. **The frontend has not been driven against the ported backend** (Playwright publications specs, a local
   `start-local.sh` boot); the rehearsal on the test environment is the acceptance.
5. **Local realm:** `niord-web` carries no Niord roles in `be/keycloak/niord-realm-local.json` (PORT-11).
6. **Two source-design questions the final review raised, ported verbatim and not changed:**
   ~~`PublicationResolver.designatable` ignores the category's publish flag while `publicVo` honours it, so an
   anonymous `publication=<issue in a non-publishing category>` returns that issue's member composition
   (public-status messages only) although the issue itself is invisible~~ — closed 2026-09-25: `designate()`
   applies the category's publish flag to both halves for the public tier, and such an id is refused like a
   missing one; and `/rest/publications/publication/{ids}`
   on the `@PermitAll` legacy resource now resolves at the internal tier, so anonymous callers can read titles and
   links of OPEN issues. The second stays by the owner's ruling of 2026-09-25, mitigated: the anonymous
   `/search` and `/all` serve the public tier only, so such an id is not discoverable anonymously, and an
   anonymous caller is not handed the link or file name of an OPEN issue or a DRAFT/RECORDING legacy row,
   here or on the issue hydration `/rest/publication-issues/by-ids`.
   Deriving the audience from the caller remains the one-line tightening if wanted.
7. **A late release skips a week in its label** (NAMING-1 in `be/docs/potential-errors.md`): the estate's open
   week-36 issue, released 19 days after its window opened, was named `37+38` — the naming rule counts
   periods as `floor(days / 7 + 0.25)` and labels backwards from the closing week, so week 36, which the
   window fully covered, went unnamed while the partial closing week did not. The rule is the source's,
   the port did not change it, and the test environment's first release will be as late.
9. **Dates on the internal REST are ISO 8601 strings, not epoch milliseconds** (found in the local rehearsal
   when the publish dialog refused every planned cut-off): Spring's Jackson builder emits ISO on the internal
   endpoints and the rest of this frontend types backend dates as strings, while the publications section and
   its contract assume epoch numbers. Fixed on the frontend side on 2026-09-15: the three publications API
   services normalise ISO date-times to epoch at the boundary (`shared/utils/epoch-dates.ts`); the contract
   and `backend-api-notes.md` say so. The public API (`/rest/public/v1`) already emitted epoch and is unaffected.
8. **The real renderer had no test:** every publications IT renders through the `@Primary` stub. A dedicated
   opt-out IT that publishes a two-language issue through the real `IssueRenderService` was added on 2026-09-15
   (`IssueRenderRealITTest`, green: two languages drawn concurrently on pool threads, `%PDF` to `%%EOF`); the
   two-language publish should still be an explicit step of the rehearsal. It surfaced four more findings,
   RENDER1 to RENDER4 in `be/docs/potential-errors.md` — the one to read before the rehearsal is RENDER2: two
   render workers can both insert a cold `Setting` key (`mode`, `country`) and the second commit fails on
   `Setting_pkey`, taking the publish with it; a database whose settings are already seeded is not affected.
   RENDER2 was fixed, and its entry deleted, in C3b of `docs/code-data-config-plan.md` (§5.3): a settings
   read no longer writes a row.

**Commit plan.** Commit 1 is the staged frontend merge (message prepared in `.git/MERGE_MSG`; the
`fe/scripts/publications` repoint and its two doc paragraphs are left unstaged because they point at `be/`).
The backend follows in commits per layer — main sources, then V004 with the tests, then guards, scripts and
docs — staged by explicit path. After commit 1 the scaffolding can go: branch `port/niord-app-main-fe`,
`refs/port/niord-app-main`, `refs/port/niord-app-tag`, `refs/original/refs/heads/port/niord-app-main-fe`.
