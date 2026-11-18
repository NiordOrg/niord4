# Test authoring guide — scope, rules, and how to add package tests

The standing testing task and how to carry it out. This is the durable home for **what** the task
requires (scope, rules, coverage priorities, quality bar) **and how** to satisfy it in this codebase —
written so every package is covered the same way the first one (**`area`**) was. Use the `area` tests as
worked examples, and complement this with [`test-infrastructure.md`](./test-infrastructure.md) (the
container/Surefire/Failsafe machinery).

> The task in one line: produce a stronger, **meaningful** test suite one package at a time, plus
> documentation of any suspected bugs and untestable areas — **without changing
> production code or build files.**

## TL;DR

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)

# Tier 1 — framework-neutral unit tests (fast, no Docker)
mvn -o -pl niord test -Dtest='Xxx*Test'

# Tier 2 — integration tests (needs Docker running)
mvn -o -pl niord verify -Dit.test=XxxServiceITTest -Dtest=_none_ -Dsurefire.failIfNoSpecifiedTests=false

# Final per-package gate
mvn -o verify
```

> `-Dtest=pkg.*` (a lone trailing star) selects **zero** tests — use `pkg.*Test` or `pkg.**`. Separate
> multiple patterns with commas. `-Dsurefire.failIfNoSpecifiedTests=false` turns a no-match into a
> false green, so only pass it when you are deliberately filtering one plugin out of a `verify` run.

## Hard rules (do not break these)

- **Do not modify production code under `src/main`.** Do not refactor application code and do not change
  application behaviour.
- **Do not modify build files** (`pom.xml`, etc.) unless explicitly instructed.
- **Do not introduce new testing frameworks, assertion libraries, containers, fixtures, or architectural
  patterns** unless the project already uses them. The sanctioned stack is: JUnit 5 (Jupiter) plus
  `spring-boot-starter-test` + RestAssured + Hamcrest + Testcontainers.
- **Only create or modify:** test code under `src/test`, test resources under `src/test/resources`, and
  documentation under `/docs`.
- **A fixture a test reads lives under `src/test/resources`** — the one place a Markdown file still
  runs Backend CI: documentation under `be/` (`be/docs/`, any other `*.md`) runs nothing
  (`.github/workflows/be-ci.yml`), so a Markdown file a test read from anywhere else could change untested.
- **If a production-code issue blocks a test, do not fix it — document it** (see "Document, don't change
  production" below). Never weaken or bypass security in production code to make a test pass.

## Prefer narrow, framework-light tests

**Prefer plain unit tests** for pure logic that verify business behaviour, API contracts, persistence,
validation, and security decisions. Reserve `@SpringBootTest` integration tests for behaviour that
cannot reasonably be tested otherwise — application wiring, REST endpoints, transactions, persistence,
security integration, configuration, and DI behaviour.

## Scope, order, and coverage priority

- **One package at a time.** Start with `area`; do not move on until a package has the **best practical
  coverage**. Repeat per package. Aim for **meaningful** coverage, not line coverage.
- **Prioritise (high → low):** 1) business-critical behaviour, 2) security & authorization, 3) persistence
  & transactions, 4) validation & error handling, 5) edge cases, 6) getters/setters **only if they hold
  logic**.

## What every test must do (the quality bar)

- **Cover observable behaviour**, including: normal success flows, positive and negative functional
  cases, validation errors, edge cases, null/empty/boundary values, persistence, transactional behaviour,
  security, error scenarios, and missing/invalid input. (No performance tests.)
- **No weak tests.** Never assert merely that a method can be called, and don't assert non-null unless
  null-vs-non-null *is* the behaviour under test. Every test must assert a real outcome: returned values,
  persisted/database state, HTTP status, response bodies, emitted events, security decisions, or thrown
  exceptions.
- **Prefer narrow tests.** Unit-test pure logic; reserve integration tests for persistence, repositories,
  REST endpoints, transactions, security, and wiring that can't be tested in isolation. **Avoid booting
  the full application context unless the behaviour needs it.**
- **Follow the existing project style**, naming, and libraries. Don't test private implementation detail
  directly; avoid brittle tests that break on a harmless refactor.
- **Mocking:** don't overuse mocks. Prefer real objects for domain logic; mock only dependencies that are
  external, slow, non-deterministic, hard to construct, or out of scope. **Never mock the class under
  test.** (In practice the `area` work used no mocks — real domain objects for Tier 1, the real
  service/DB for Tier 2.)
- **REST endpoints — verify:** HTTP status code, response body, validation errors, authorization, important
  headers where relevant, and persistence side effects where relevant. Test **both valid and invalid**
  requests.
- **Persistence — verify actual database state.** Don't just assert a service returned something; confirm
  the row was inserted/updated/deleted/left unchanged (flush, clear, re-find).
- **Security — test both signs:** correct-role allowed; wrong-role rejected; unauthenticated rejected;
  missing/invalid permissions rejected; users can't reach data they shouldn't.

## The two tiers (and the module split)

| Tier | Covers | Module / location | Framework | Runner |
|------|--------|-------------------|-----------|--------|
| 1 — unit | pure logic: VOs, entity helpers, mappers, validation, value computations | **`niord`**, `src/test/java/<prod package>` | **JUnit 5** (`org.junit.jupiter`) | Surefire (`*Test`) |
| 2 — integration | persistence, transactions, REST contract, security, wiring | **`niord`**, `src/test/java/<prod package>` | **JUnit 5** + `@SpringBootTest` + RestAssured + Hamcrest | Failsafe (`*ITTest`) |

Both tiers live in **`niord`** (the base library). The integration tiers boots the library through the
`NiordTestApplication` test twin, so the whole suite sits next to the code it covers.
The bootable Danish deployable — the `SIFA-DK/niord-dk` repository since 2026-10-06 — keeps only the
genuinely Danish tests: the boot smokes
that exercise the real `NiordDkApplication` with its real `application.yml`/`niord.json`
(`ContextLoadsITTest`, `AppBootSmokeITTest`, `CoreDefaultsPrecedenceITTest`) and the tests of
Danish-only classpath resources (`DkDgpsTemplateITTest`, `DkTemplateResourcesTest`, and a couple of
`Dk*Test` units). They reuse the shared harness through the `niord-base` **test-jar**.

Write a new test in the `niord-dk` repository only when it genuinely needs the DK deployable's own artifacts;
everything else goes in `niord`. Maximise Tier 1 (fast); use Tier 2 only where behaviour needs the
running app/DB. Naming: `*Test` → `mvn test`; `*ITTest` → `mvn verify`.

A test that every country build must also run (a security test, a country check) carries `@CountryBuildGroup` and
is listed in `CountryBuildGroupTest` (test-infrastructure.md, "The country build group"). Its fixtures do not depend
on files outside the class path, and its configuration and controller fixtures go outside `org.niord`
(`org.example.country`), where the integration tests' application does not scan.

### Tier 1 — framework-neutral unit tests

Put tests in the **same package** as the class under test so they can reach package-private/`protected`
members (e.g. `Area.geometryChanged`). JUnit 5 (Jupiter) assertions only:

```java
package org.niord.core.area;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AreaHasChangedTest {
    @Test public void differentTypeChanged() {
        Area a = AreaTestFactory.area(1, "da", "Danmark");
        Area template = AreaTestFactory.area(1, "da", "Danmark");
        template.setType(AreaType.FIRING_AREA);
        assertTrue(a.hasChanged(template));          // asserts a real outcome, not non-null
    }
}
```

Add **one test-data factory per package** (e.g. `AreaTestFactory`) with **no `Test` suffix** so Surefire
ignores it. It holds builders only (no assertions). Build geometries with the production converter
(`JtsConverter.toJtsPoint` / `wktToJts`) so equality matches production semantics; build `DataFilter`s
with `DataFilter.get().fields(...)`.

### Tier 2 — service integration tests

Extend `ITTest` (which is the `@SpringBootTest` base), isolate with Spring's **`@Transactional`** on the
test method (rolls back; keeps the shared seed DB pristine), and assert **actual DB state** by flushing,
clearing, then re-finding:

```java
class AreaServiceITTest extends ITTest {
    @Autowired AreaService areaService;
    @Autowired EntityManager em;

    @Test @Transactional
    void createAreaPersistsRootWithLineage() {
        Area created = areaService.createArea(newArea("IT-Root", "en"), null);
        em.flush(); em.clear();                                  // force a real DB read
        Area reloaded = em.find(Area.class, created.getId());
        assertEquals("/" + created.getId() + "/", reloaded.getLineage());
    }
}
```

Use an **`IT-` name prefix** absent from the seed data so searches stay isolated. Constraint violations
and cascades are only visible here (assert `PersistenceException` for a unique-constraint breach;
`em.find(...) == null` after a cascade delete).

### Tier 2 — REST integration tests

RestAssured + Hamcrest against the running app. **The REST API is Spring MVC controllers mounted under
`/rest`**, so a wrong path returns 404 (GET) / 405 (non-GET). A `@Transactional` test cannot roll back the
server-side HTTP transaction, so REST tests use **unique `IT-` data + `@AfterEach` cleanup**:

```java
@AfterEach void cleanup() {
    for (Integer id : createdAreaIds)
        given().auth().oauth2(accessToken()).delete("/rest/areas/area/" + id);
    createdAreaIds.clear();
}
```

- **Authentication:** call the inherited `accessToken()` (sysadmin) or `accessToken(role)` (one of
  `"sysadmin"`/`"admin"`/`"editor"`/`"user"`) on `ITTest` — a single, TTL-aware token cache shared by every
  REST IT. **Do not hand-roll a per-class `cachedToken` field/`token()` helper** (that idiom was removed
  from ~48 ITs; re-introducing it duplicates the cache and risks the realm's 300s token expiry on long
  runs). Under the hood `accessToken` mints via `org.niord.test.KeycloakTestTokens` — a password grant
  through the **public `niord-web`** client (the app's own `niord-client-master` is *bearer-only* and
  cannot issue tokens). The `sysadmin`/`sysadmin` user carries composite `admin`/`editor` roles read from
  `resource_access/niord-client-master/roles`. Package-agnostic — reuse as-is.
- **Cover both signs:** unauthenticated write → `401`; authenticated `admin`/`editor` allowed; assert the
  validation guard (e.g. PUT id-mismatch → `400`) and the response body. A multipart endpoint needs a part
  in the request to reach the auth layer (otherwise it returns `415` before the security check).
- **Every handler is also in the endpoint table** (`niord/src/test/resources/endpoints/`): the endpoint sweep calls
  it as each test identity and holds its answers and its log to the table. A new or changed handler fails the
  table's completeness tests until its entry is decided; a fault the sweep shows is recorded and marked as the
  entry's `knownFault`, not fixed in the same change. How: "The endpoint sweep" in
  [`test-infrastructure.md`](./test-infrastructure.md#the-endpoint-sweep). A REST IT still owns what the sweep does
  not reach (the list is there).

## Test independence

Tests must be independent and repeatable — no reliance on execution order; each must pass **alone** and
**in the full suite**. For DB tests, isolate via one of: transaction rollback (Spring `@Transactional`),
cleanup, truncation, or unique per-test data. (`area` uses `@Transactional` for service tests and
unique-data + `@AfterEach` cleanup for REST tests.) Unique per-test data stays in the shared database for
the rest of the run (the publication-series ITs commit `ms-<uuid>` message series and leave them), and
Failsafe orders the classes as the file system lists them, so never assert a shared table's total: name
the rows you expect, or compare with the count before the test's own writes.

### Landmine: the background schedulers vs committed message fixtures

`NiordTestApplication` is `@EnableScheduling`, so every `@Scheduled` job in the application is live
while the IT suite runs. Three of them **mutate message state on their own** in the shared
Testcontainers database, two once a minute and one once an hour:

| Job | Cron property (default) | What it does to your fixtures |
|-----|-------------------------|-------------------------------|
| `ScheduledMessageService.checkForPublishableMessages` | `niord.schedule.auto-publish-cron` (`7 * * * * ?`) | publishes **every** VERIFIED message whose `publishDateFrom` is in the past |
| `ScheduledMessageService.checkForExpirablePublishedMessages` | `niord.schedule.auto-expire-cron` (`28 * * * * ?`) | expires **every** PUBLISHED message whose `publishDateTo` is in the past |
| `FiringExerciseService.updateFiringExercises` | `niord.schedule.firing-exercise-cron` (`50 21 */1 * * ?`) | creates, replaces or cancels firing exercises for **every** active firing schedule, the firing tests' committed ones included |

The search behind both is a plain JPA criteria query over the message table (not Lucene), so a
just-committed fixture is visible to the very next tick — no index refresh is needed first.

The consequence: a committed fixture parked at **VERIFIED with a past `publishDateFrom`** is not stable
test data — it is a message the application is actively trying to publish. When a tick landed inside a
test's window, the test's own publish failed (`Invalid status transition PUBLISHED -> PUBLISHED` from
the service, an HTTP 500 over REST), or the system publish broadcast a JMS status change that drove the
real downstream listeners (`TwitterMessageListener`, mailing lists, SECOM) — a roughly
window-width-over-60s, "passes on re-run" flake that hit real runs.

**The defuse: the IT suite switches exactly these three jobs off.** The schedules are properties, and a
`@TestPropertySource` on `ITTest` sets them to `-` — Spring's `Scheduled.CRON_DISABLED` — so the three
tasks are never registered with the scheduler at all:

```java
@TestPropertySource(properties = {
        "niord.schedule.auto-publish-cron=-",
        "niord.schedule.auto-expire-cron=-",
        "niord.schedule.firing-exercise-cron=-",
        "niord.mailinglist.status-change-sweep-cron=-"
})
public abstract class ITTest { … }
```

The fourth line is of another kind: the mailing lists' status-change outbox sweep changes no message, but it takes
the outbox rows of every application context of the run at once (`docs/test-infrastructure.md`, several
application contexts). A context still drains the outbox on its own JMS prompt and at its start, and a test that
needs a drain calls `MailingListMessageListener.drainOutbox()`.

It lives on the base class rather than in an `application.yml` because that file is module-local — the
niord-base one is excluded from the test-jar — while a country repository's ITs, and the country build group's ITs
run in a country build, extend the same `ITTest` and boot with the country deployable's own `application.yml`. An `ITTest` `@BeforeEach`
(`requireMessageStatusCronsDisabled`) fails every IT unless the disable is still in effect: it resolves
the placeholder off each live `@Scheduled` annotation against the `Environment`, so it fires if a
schedule is renamed on only one side, if the config stops disabling one, or if a third
`@Scheduled` method appears on the class — forcing a decision instead of silently running (or silently
killing) a job. It checks the firing-exercise job's schedule the same way. Disabling rather than clock-dodging, because only disabling is deterministic: the app's
cron jobs share one scheduler of **four threads** (`spring.task.scheduling.pool.size` in
`niord-core-defaults.yml`, SCHED3), so a sweep can start seconds late when every thread is busy, and two
jobs can run at the same time — the wall-clock `dodgeAutoPublishTick()` guard an earlier revision used
could return before a delayed sweep had even begun. (Until SCHED3 the scheduler had Boot's default of one
thread, and auto-publish shared second :37 with the mailing-list run; it now runs at second :07.) (An intermediate revision cancelled the two
`ScheduledTask`s through `ScheduledAnnotationBeanPostProcessor`; the properties replaced it, so nothing
reaches into scheduler internals any more.) Coverage is unaffected — `ScheduledMessageServiceITTest`
calls both sweeps directly rather than waiting for the timer, and the firing tests call the firing run's
seam — and every other `@Scheduled` job
(Lucene indexing, mail, batch, …) keeps running exactly as in production.

A test of the sweeps calls their package-private seams, `publishDueMessages(Set<String> domainIds)` and
`expireDueMessages(...)`, with domains of its own, never the cron methods (they sweep every domain and would
publish or expire other tests' committed messages). Each sweep changes each message in a transaction of its own,
which cannot see rows of a test's own transaction, so the fixtures are committed: `org.niord.test.DeliveryFixtures`
creates a series, a domain and the messages, committed, and removes them after the test.

The hourly firing-exercise run is tested the same way: a test calls its seam,
`FiringExerciseService.updateFiringExercises(Set<Integer> scheduleIds)`, with its own schedule only, never the
cron method (it runs every active schedule of the database; its timer is switched off in the suite, see above). The run reads each schedule in a transaction of its
own and creates or cancels each area's exercise in a transaction of that area's own, so `DeliveryFixtures` commits
the source and target series and domains, the firing areas, templates, periods and the schedule
(`firingDomains`, `firingArea`, `firingTemplate`, `firingPeriod`, `firingSchedule`), reads the exercises back
committed (`firingExercises`), and removes all of it, with the exercises the run issued, after the test.
`firingPeriod` goes through `FiringScheduleService.addFiringPeriod`, which refuses a period that does not end after
it starts; a period stored backwards, as the schedule editor stored "until midnight" before the fix of 2026-11-13, is
committed past that check with `storedFiringPeriod`.

The fixture classification below still applies — case 1's date rule is load-bearing for an unrelated
reason (the yearly number sequences), the rest is cheap defence-in-depth should the jobs ever run
again (the same reasoning applies to `publishDateTo` and the expiry job):

1. **The fixture stays unpublished** (parked at DRAFT or VERIFIED for the whole test): give it a
   **future** `publishDateFrom` — the job's `publishDateFrom <= now` search can never match it. Use
   year 2999, the idiom `ScheduledMessageServiceITTest.FUTURE_PUBLISH_DATE` established. A future date
   is NOT interchangeable with the far-past date for fixtures that get published: on publish,
   `MessageService.updateStatus` stomps a future `publishDateFrom` back to `now`, and the minted number
   would land on the current-year sequence that `MessageSeriesRestServiceITTest` asserts is untouched.
   Keep the "year 2001" past date for everything that ends up PUBLISHED.

2. **The test publishes the fixture itself through `MessageService`**: walk
   DRAFT → VERIFIED → PUBLISHED inside **one committed transaction** —
   ```java
   Tx.requiringNew().run(() -> {
       messageService.updateStatus(uid, Status.VERIFIED);
       messageService.updateStatus(uid, Status.PUBLISHED);
   });
   ```
   The job's search reads committed state only, so the atomic walk never exposes a committed
   VERIFIED + past-date row for it to seize, while the past date survives to publish (no stomp — the
   date is not in the future) and the number stays on the 2001 sequence. Two *separate* commits
   instead expose exactly the window described above — the race that flaked real runs before the
   jobs were switched off.

3. **The committed VERIFIED window spans separate commits by necessity** — the publish goes over REST
   (two HTTP requests, each a server-side commit), or the test's own semantics require VERIFIED
   committed separately (e.g. `MessageStatusPublishITTest` commits VERIFIED before subscribing so the
   consumer only sees the PUBLISHED broadcast): nothing to do — the disable above is what makes these
   windows safe. (A wall-clock `dodgeAutoPublishTick()` guard previously covered them; it was removed as
   probabilistic — it synchronized with the *schedule*, not the *execution* — once the jobs were
   switched off.)

**Audit, 2026-08-22 (second pass, after a run flaked on the "residual").** All IT suites that touch
`publishDateFrom`/`publishDateTo` were checked against the three conditions (committed · at VERIFIED
with a past date · across a window):

- **Stay-unpublished fixtures** → future date (case 1): `TwitterPromulgationGenerationITTest` (the 2 s
  settle), `MessageRestStatusBatchITTest` (batch-verify fixtures), `MessageStatusPublishITTest`
  (the stay-VERIFIED broadcast fixture).
- **Service-level publish helpers** → atomic walk (case 2): `TwitterPromulgationGenerationITTest`,
  `TwitterPromulgationHttpITTest`, `MessageRestServiceITTest`, `MessageRestStatusBatchITTest`,
  `MessageRestCopyReferencedITTest` (×2), `MessageCancellationWarningITTest`,
  `MessageSearchRestServiceITTest`, `ApiRestServiceITTest`. (`SecomSecurityReloadITTest` already
  wrapped its walk in one `Tx`; `SecomITTest.publishNw` is only called from rolled-back
  `@Transactional` tests or inside such a `Tx`.)
- **Committed-VERIFIED windows across separate commits** → safe via the disable (case 3): the
  REST publish-flow test in `MessageRestServiceITTest`, the two cancel-referenced REST publishes in
  `MessageRestStatusBatchITTest`, the cascade REST publish in `MessageCancellationWarningITTest`, and
  the publish-broadcast test in `MessageStatusPublishITTest`.
- **Immune — the rest.** Fixtures created inside a rolled-back `@Transactional`/`TestTransaction` test
  are never committed, so the job's own transaction cannot see them. There is no `flagForCommit`
  anywhere in this set, so every one of those suites rolls back. This is also why the **expiry** job
  has zero exposure today: every past `publishDateTo` in the suite is inside a rolled-back test.

If you add a committed fixture, classify it against the three cases above rather than trusting this list.

**On the production side.** The two cron expressions used to be hardcoded string literals, which is why
the harness once had to fish their tasks out of the running scheduler. They are now
`${niord.schedule.auto-publish-cron:7 * * * * ?}` / `${niord.schedule.auto-expire-cron:28 * * * * ?}`
— one line of config to disable or re-tune per environment (auto-publish's default moved from second 37
to second 7 with SCHED3).
`ScheduledCronExpressionTest` pins both the property names and the shipped defaults, so a rename cannot
silently orphan the IT override.

## Document, don't change production

When something can't be tested as-is, record it instead of changing `src/main`:

| You found… | Put it in… | Each entry must include |
|------------|-----------|--------------------------|
| a suspected bug / inconsistent behaviour | [`potential-errors.md`](./potential-errors.md) | location; short description; why it looks wrong; which test/scenario exposed it |
| code that can't reasonably be tested (randomness, private math, scheduled, request-scoped) | [`untestable-code.md`](./untestable-code.md) | location; why it's hard/impossible to test; what would make it testable later |

Convention: when a test pins **current, incorrect** behaviour, name it `…_documentsBug` and cross-link the
entry, so a later fix fails the test loudly instead of silently.

## Running & verifying

- **Run as you go:** run the relevant tests after each test class or small group; at minimum run a
  package's whole suite before moving on. Use the `-Dtest='Xxx*Test'` / `-Dit.test=Xxx*ITTest` filters
  during development, and the two-command final gate (TL;DR) before declaring a package done.
- **If a run fails because of a suspected production bug** → document it in `potential-errors.md` and
  continue with the other tests where possible. If it fails because the code can't be tested without
  production changes → `untestable-code.md`.
- **Anything your test prints is not on the console.** `mvn test` redirects unit-test stdout/stderr to
  `<module>/target/surefire-reports/<fully.qualified.ClassName>-output.txt` (one file per class). The
  assertion message and stack trace of a *failing* test still print in full, so you rarely need the
  file — reach for it when you want a `System.out.println` you added while debugging, or the log a
  service emitted on the way to the failure. `-Dniord.test.redirectOutput=false` restores
  console output for one run. Integration tests are unaffected. See
  [`test-infrastructure.md`](./test-infrastructure.md#where-test-output-goes).

## Reusable assets (already available — don't re-create)

- `org.niord.test.ITTest` — the `@SpringBootTest` base class (Docker guard + `@DynamicPropertySource`
  container wiring + container accessors + the shared, TTL-cached `accessToken()` / `accessToken(role)`
  token helpers — use these, don't re-roll a per-class cache).
- `org.niord.test.KeycloakTestTokens` — the underlying token minter (one method per role tier); prefer
  the cached `ITTest.accessToken(role)` over calling these directly.
- Per package, add your own `<Package>TestFactory` for Tier 1 builders.

## Per-package checklist

1. List the package's classes; order the work by the coverage priorities above.
2. **Tier 1:** add `<Package>TestFactory` + JUnit 5 unit tests in `niord/src/test/java/<prod package>` for
   all pure logic; run `mvn -o -pl niord test -Dtest='<Package>*Test'` after each class until green.
3. **Tier 2:** add `<X>ServiceITTest` (`@Transactional`, assert DB state) and, for endpoints,
   `<X>RestServiceITTest` (reads + negative `401` + positive role-based via `KeycloakTestTokens`, paths
   under `/rest`, both valid and invalid requests). Run
   `mvn -o -pl niord verify -Dit.test=<X>*ITTest -Dtest=_none_ -Dsurefire.failIfNoSpecifiedTests=false`.
4. Record any suspected bugs / untestable code in the two docs above.
5. Final gate: `mvn -o verify` green → move to the next package. Treat `Tests run: 0` as a **failure**,
   even when Maven exits successfully — it usually means Testcontainers could not reach Docker.

## Output expectation per package

Add or improve tests until the package has **strong practical coverage**; prefer clear, maintainable tests
over clever ones; keep tests narrow and framework-light where practical; and
leave the `/docs` files updated with anything found. The end state for each package is a stronger
suite **plus** documented issues and untestable areas.
