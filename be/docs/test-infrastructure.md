# Test infrastructure

This document describes the automated-test infrastructure for the `niord` + `niord-app` reactor
(the Spring Boot port at the repo root; the Danish and Greenland deployables and their few tests are in
`SIFA-DK/niord-dk` and `SIFA-DK/niord-gl` since 2026-10-06). It covers how tests are named and separated, how to
run them, and which containers the integration tests start.

> Scope note: this is the **infrastructure** layer — Testcontainers, a shared integration-test base
> class, unit/integration separation — plus smoke tests and a full application-boot
> test that exercise it. Detailed domain/business tests are out of scope here.

## TL;DR

```bash
mvn test      # fast unit tests only (Surefire)
mvn verify    # unit tests + integration tests (Failsafe); requires Docker
```

Java 25 is required. `.java-version` sets this automatically for jenv/sdkman users; without a version
manager set `JAVA_HOME` first: `export JAVA_HOME=$(/usr/libexec/java_home -v 25)` (macOS).

## Test naming convention

| Kind              | Class-name pattern | Maven plugin | Maven phase            | Needs Docker |
|-------------------|--------------------|--------------|------------------------|--------------|
| Unit test         | `*Test`            | Surefire     | `test` (`mvn test`)    | no           |
| Integration test  | `*ITTest`          | Failsafe     | `verify` (`mvn verify`)| yes          |

Because `*ITTest` also ends in `Test`, Surefire is configured to **exclude** `*ITTest` (and `*IT`) so
that integration tests do not run as unit tests during `mvn test`. Failsafe is configured to
**include** `*ITTest` (its built-in defaults only match `*IT`/`*ITCase`).

This wiring lives in the **root `pom.xml`** so both modules share it:

- `maven-surefire-plugin` — `includes **/*Test.java`, `excludes **/*ITTest.java`, `**/*IT.java`, and
  `redirectTestOutputToFile` (see [Where test output goes](#where-test-output-goes))
- `maven-failsafe-plugin` — `includes **/*ITTest.java`, bound to the `integration-test` + `verify` goals

> Every forked test JVM prints a handful of banners before the tests start — Mockito's inline
> mock-maker self-attaching as a Java agent, the JDK's matching "A Java agent has been loaded
> dynamically" warnings, and a HotSpot note that class data sharing is degraded. They are noise, not
> a problem, and they are left alone deliberately: silencing them costs more build configuration than
> the six lines is worth.

## The `ITTest` base class

`niord/src/test/java/org/niord/test/ITTest.java` is the **abstract** shared base class for
integration tests. Integration tests extend it:

```java
class SomeFeatureITTest extends ITTest {
    @Test
    void something() {
        // dbJdbcUrl(), brokerAmqpUrl(), smtpHost()/smtpPort(), keycloakRealmUrl() ...
    }
}
```

It holds **only generic infrastructure** — a Docker-availability guard, the `@SpringBootTest`
wiring, and accessors for the shared containers — and no business logic.

`ITTest` is `abstract` on purpose: although its name matches the `*ITTest` Failsafe include pattern,
Surefire/Failsafe never instantiate abstract classes, so it is never executed as a test itself.

If Docker is unavailable, `ITTest` makes the integration tests **skip** (JUnit 5 assumption) rather
than fail, so `mvn verify` still succeeds on a machine/CI without Docker.

### Several application contexts in one JVM

Failsafe runs every integration class in one JVM, and Spring's test context cache gives most of them one shared
application context. A class with `@MockitoSpyBean`, `@MockitoBean` or properties of its own gets a context of its
own, started beside the shared one (and closed after the class when it has `@DirtiesContext`). A deployment has one
context; the suite has several at once, and they share what a deployment never shares:

- **`CdiUtils`' static application context.** It is set when a context starts, so it is left on the last one
  started; once that one is closed, a template script on the shared context (`templates/tmpl/common.js`) fails with
  "has been closed already". `ITTest`'s `@BeforeEach` points it at the running test's context.
- **The Lucene index folder** (`${NIORD_HOME}/message-index`). Every context empties it when it starts
  (`MessageLuceneIndex.init`, `messageIndexDeleteOnStartup` is on), and its start-up pass runs five seconds later;
  a context closed within those five seconds leaves the folder empty, and the shared context's incremental pass then
  commits onto it from its own, older marker. The index no longer holds the fixture's messages.
- **The database's sequences.** Hibernate's pooled optimiser gives each context a block of ids of its own, above
  the block the shared context is still using. A row a class with its own context leaves behind has an id above the
  shared context's next one, which breaks a test that asserts a new id is above every existing one.
- **The mailing lists' status-change outbox** (`"MailingListStatusChange"`, `MailingListStatusChangeOutbox`). Every
  status change writes a row, and a context's listener takes every due row, whichever context wrote it, on its own
  JMS prompt and once at its start. The per-minute sweep would take them from every live context at once, out of
  the order a test expects, so the suite switches it off (`niord.mailinglist.status-change-sweep-cron=-` on
  `ITTest`, and in the classes that repeat its properties). A test that asserts a row before it is mailed holds the
  listener's drain lock. The suite does not clean the table: a test that defines status-change triggers removes
  its messages' outbox rows before the messages (as `MailingListDeliveryFaultITTest` does), so no later drain runs
  a trigger for a message that is gone and leaves an ERROR mail that a later class may count.
- **The settings' change count** (the `state` setting `settingsChangeCount`). Every save adds one, and each
  context's poll (`SettingsRefresher.pollForChanges`) that finds it changed refreshes that context's `@Setting`
  fields, so a save in one context would reach another's fields at a moment no test chooses. The suite switches the
  poll off (`niord.settings.change-poll-cron=-` on `ITTest`); a save still refreshes the fields of its own context,
  and a test that needs another context's save followed calls `pollForChanges()` itself.

The class order differs between machines: Failsafe's default `runOrder` is `filesystem`, which is alphabetical on
NTFS (Windows) and directory-hash order on Linux (CI). A class that passes after one set of classes can fail after
another.

A test therefore:

- rebuilds the free-text index from the committed rows (`messageLuceneIndex.recreateIndex()`) before it searches,
  rather than waiting for a start-up pass, and restores the folder and reader when it swaps them;
- removes the rows it committed when its class has a context of its own (with their children: periods, messages
  and their histories), so no id from its block is left in the shared database;
- reaches the services through its own injected beans, never through a static set by some other context, and does
  not override `ITTest`'s `@BeforeEach` methods (the one that re-points `CdiUtils` among them);
- passes alone, in the whole run and in reverse order (`-Dfailsafe.runOrder=reversealphabetical`).

## How to run the tests

```bash
# Unit tests only (fast, no Docker)
mvn test

# Unit + integration tests (requires a running Docker daemon)
mvn verify

# A single module
mvn -pl niord test
mvn -pl niord verify

# A single integration test
mvn -pl niord -Dit.test=TestInfrastructureITTest verify

# Integration tests only: Failsafe runs, Surefire selects no test (-DskipTests would skip both)
./test-it --its-only
./test-it --its-only -pl niord -Dit.test=TestInfrastructureITTest
```

`--its-only` is `test-it`'s own flag, not Maven's: the script takes it off the arguments and appends
`-Dtest=_none_ -Dsurefire.failIfNoSpecifiedTests=false` to the Maven call, so Surefire selects no test
and accepts that while Failsafe (which selects by `it.test` and reads neither) runs as usual. Surefire
has no skip of its own — `-DskipTests` skips Failsafe too, and `test-it` refuses it — and the pair given
by hand is the right spelling for one class but not for a whole suite: a `-Dtest=` on the command line
lowers `test-it`'s floor of 50 executed integration tests to one, as a narrowed run should, whereas the
flag appends the pair after the floor is read and keeps it at 50. The pom is untouched, so the country
repositories that inherit it see nothing. The backend CI's integration job runs `./test-it --its-only`
and so runs **Failsafe only**: its `Unit tests` job runs the unit suite in parallel, and the integration
job used to repeat it (a minute) before the first container started. The same job pulls the four
Testcontainers images in the background while Maven compiles, the names read from the test sources by
`be/scripts/it-images.sh`, so the first integration-test class finds them locally.

> **Module-scoped runs resolve reactor siblings from the local repository.** `-pl niord-app`
> (without `-am`) takes the `niord-base` jar from `~/.m2`, **not** from the
> working tree. After changing `niord/` sources, either add `-am` (`mvn -pl niord-app -am verify`)
> or refresh the installed artifact once with `mvn -pl niord-app -am install -DskipTests`. A stale
> jar typically fails fast at context startup with a confusing, seemingly unrelated error — e.g. an
> unresolvable `@Value` placeholder for a property that no longer exists anywhere in the sources.

## Where test output goes

`mvn test` prints **no test output at all** — only Maven's own per-class `Running …` / `Tests run: …`
lines, and, when something breaks, the failure. Everything a unit test writes to `System.out` or
`System.err` is redirected to

```
<module>/target/surefire-reports/<fully.qualified.ClassName>-output.txt
```

one file per test **class** (not per method), created only for classes that actually printed something.

This is deliberate. The unit suite is loud on the happy path: print-style legacy tests dump OSM XML and
GeoJSON, and the error-path tests feed FreeMarker and the batch importers input they are *supposed* to
reject, so each one logs a full stack trace. That was ~3 400 lines of expected output per **green** run
— which is exactly what a real failure used to get lost in. A green `mvn test` is now ~700 lines, all of
it Maven's.

**Nothing is hidden, and failures are unaffected.** Surefire's reporting is a separate path from the
captured output, so a failing test still prints its assertion message and full stack trace to the
console twice — once per class as it happens, and once in the `Results:` / `Failures:` block at the end
— and the build still goes red. The captured output is in *two* places afterwards: the `-output.txt`
file, and the `<system-out>`/`<system-err>` elements of `TEST-<Class>.xml`. (The per-class
`<Class>.txt` is a run summary — `Tests run: …` and failure detail — and has never held captured
output.) Both CI jobs upload `be/**/target/surefire-reports/**` wholesale, so both are archived on a
red run too.

> **Expected stack traces in `-output.txt` are not failures.** Many of them are the point of the test:
> the FreeMarker and script tests feed the engine input it is supposed to reject, and it logs the
> rejection. Read the `Tests run:` line, not the trace.

To get the old firehose back for one run — occasionally useful when a test hangs and you want to see
how far it got — pass `-Dniord.test.redirectOutput=false`.

This applies to **unit tests only**. The integration suite deliberately keeps printing to the console:
it is slow, and its live output is the only progress signal while containers boot. `mvn verify` output
therefore still shows Testcontainers' startup chatter.

The integration-test suite lives in `niord` (the base library — the tests boot it through the
`NiordTestApplication` test twin). The Danish deployable, in `SIFA-DK/niord-dk` since 2026-10-06, keeps
only the genuinely Danish tests, six ITs and a handful of `Dk*Test` units. Five ITs extend the test-jar's
`ITTest` and run on this fixture: the boot smokes that exercise the real `NiordDkApplication` with its own
`application.yml` and `defaults/country/` (`ContextLoadsITTest`, `AppBootSmokeITTest`,
`CoreDefaultsPrecedenceITTest`; DK has no root `niord.json` since its C6, which `NiordDkSettingsResourceTest`
asserts), and the template ITs `DkDgpsTemplateITTest` and `DkLightDestroyedTemplateITTest`, which render DK's
bootstrap templates as rows from the database (`DkBootstrapTemplates` writes them). `DkBootstrapITTest`
bootstraps a database of its own container with DK's archive. They reuse the shared harness through the
`niord-base` **test-jar**, which that repository resolves from the published version it pins.
The Greenland deployable, in `SIFA-DK/niord-gl` since 2026-10-06, has two ITs: `GlBootstrapITTest`
bootstraps a container of its own with the pinned `niord-bootstrap` and GL's archive in one run (the
marker applied, what GL's archive adds, no missing path of `expected-template-paths.txt`), and
`GlFiringExercisesScriptITTest` extends `ITTest` and runs GL's `fetch-firing-exercises.js` as a
mailing-list trigger does, over GL's templates written onto the fixture. GL's whole suite is
`NIORD_IT_MIN=10 ./test-it` then `scripts/check-country-build-group.sh` (its `CLAUDE.md`), and its
`ci.yml` runs `./test-it`.

## Containers started by the integration tests

Integration tests use [Testcontainers](https://www.testcontainers.org/) (managed by the Spring Boot
BOM). Containers are defined once in
`org.niord.test.NiordTestContainers`, started **lazily on first use**, shared across all integration
tests (the Testcontainers "singleton container" pattern), and reaped automatically by Testcontainers'
Ryuk sidecar at JVM exit. They bind to **dynamic host ports**, so tests never depend on a developer's
local Docker-Compose stack or on fixed ports.

| Service             | Image (matches `docker-compose.yml`) | Exposed (in-container) ports | Exposed test config                                   |
|---------------------|--------------------------------------|------------------------------|-------------------------------------------------------|
| PostgreSQL + PostGIS| `imresamu/postgis:18-3.6`            | 5432                         | `dbJdbcUrl()`, `dbUsername()`, `dbPassword()`         |
| ActiveMQ Artemis    | `apache/activemq-artemis:2.31.2`     | 5672 (AMQP), 61616, 8161     | `brokerAmqpUrl()`, `brokerUsername()`, `brokerPassword()` |
| Mailhog (SMTP)      | `mailhog/mailhog:v1.0.1`             | 1025 (SMTP), 8025 (UI)       | `smtpHost()`, `smtpPort()`                            |
| Keycloak            | `quay.io/keycloak/keycloak:22.0.5`   | 8080 (`/auth`)               | `keycloakAuthServerUrl()`, `keycloakRealmUrl()`       |

> The database runs on **PostgreSQL 18 + PostGIS** — the multi-arch `imresamu/postgis`
> image is used because the official `postgis/postgis:18-*` is amd64-only.

Two of the containers carry fixtures so the real application can boot against them (see
[Database bootstrap](#database-bootstrap-liquibase-first-dump-on-top) and
[Keycloak realm import](#keycloak-realm-import)):

- **PostgreSQL/PostGIS** starts **empty**. When the application boots, Liquibase builds the schema (the
  production V001 baseline) and the committed fixture (the base product's bootstrap and the sample) is then
  layered on top — "Liquibase first, dump on top".
- **Keycloak** starts with `--import-realm`, importing the **`niord`** realm.

The integration tests:

- `TestInfrastructureITTest` — starts **PostgreSQL/PostGIS + Artemis + Mailhog** and proves each is
  reachable (a real `SELECT 1` and `SELECT PostGIS_Lib_Version()` over JDBC; the running Artemis and
  Mailhog containers).
- `KeycloakITTest` — starts **Keycloak** and proves the imported **`niord`** realm's OIDC discovery
  document is served.
- `AppBootSmokeITTest` (in the `niord-dk` repository) — boots the **whole niord-dk Spring Boot application**
  against all four containers and asserts `GET /rest/actuator/health` returns `200 {"status":"UP"}`. It is
  the slowest test of that suite by design (full boot + realm import + startup mass-indexing).

## How Testcontainers configuration is injected into the application

The `ITTest` base class is annotated
`@SpringBootTest(classes = NiordTestApplication.class, webEnvironment = RANDOM_PORT)` — the test twin
carrying the exact annotation set of a deployable's application class (the DK ITs in the `niord-dk`
repository re-declare `classes = NiordDkApplication.class` to boot the real thing) — and binds the
containers' dynamic coordinates into the Spring `Environment` via a `@DynamicPropertySource` method.
It registers the standard Spring keys the application reads — `spring.datasource.url`/`username`/`password`,
`spring.liquibase.change-log`, `spring.security.oauth2.resourceserver.jwt.issuer-uri`,
`spring.mail.host`/`port` (and `niord.smtp.host`/`port`) — each resolved lazily from the shared
`NiordTestContainers` singletons, so no fixed/hardcoded ports are used anywhere. When `NIORD_HOME` is
neither a system property nor an environment variable, or is set blank, the base class sets a `NIORD_HOME`
system property to a throwaway temp dir (which `niord.home` itself resolves to, ahead of the row, in
`SettingsService.override`, and which is also the value of the `${NIORD_HOME}` token of `niord.home` in the test
tree's `niord.json`, which the IT start writes as settings; the fixture holds no `Setting` row), so the app's
startup services have a writable home for its cache (the Lucene index, previews, thumbnails, map images and
tiles) and the batch jobs; without one, the start is refused (`SettingsService.refuseAnUnresolvableHome`). The
repository's files are rows of the database, not files under the home. The folder (`java.io.tmpdir/niord`, or the exported
`NIORD_HOME`) is not emptied between runs: `IssuePreviewAndFileITTest.theSweepRemovesOnlyExpiredGenerations`
counts the issue previews under it and is red in a full run once earlier runs have left generations older
than the preview TTL (seven days) there. The failing sweep removes them, so the test is green when run
again alone; clearing that folder's previews before a full run avoids the red. Its `batch-jobs` folder is the
exception: `ITTest` empties it once per test JVM, before any context starts. A job's log lies in
`batch-jobs/<job>/execution/<year>/<month>/<job number>` and is appended to, and the job numbers start again in
every fresh database, so a kept folder gave a job the log lines of an earlier run (a line a test looks for, or a
failure that breaks a test asserting a silent log). CI and `verify-local` start from an empty home, so for them
this deletes nothing.

## Database bootstrap (Liquibase first, dump on top)

The integration-test database is built exactly the way a real deployment is built, then populated with
test fixtures:

1. **Liquibase builds the schema** when the application starts. The container starts **empty**; Liquibase
   applies the production V001 baseline (`db/changelog/changes/V001__baseline_schema.sql`) — 91 tables,
   per-entity sequences, the PostGIS extension — and Hibernate then runs `ddl-auto=validate` against it,
   so a `V001`/entity drift fails the test (Hibernate validation aborts startup).
2. **The fixture is layered on top** as a Liquibase changeset of its own: the committed
   `niord/src/test/resources/db/niord-base-data.sql`, a dump of a database bootstrapped with the base product's
   archive (`niord/bootstrap/`) and loaded with the sample (`niord/sample/`, the country-split plan's P9) —
   the base product's master domain, its 45 categories, its 32 parameter types, the `aton` dictionary, the NAVTEX,
   Twitter and Audio promulgation types, its 80 templates and scripts and its three reports, and the sample's five
   domains, six series, the `dgps` parameter-type pair (34 in all), the
   promulgation types' domain links (Audio's to `niord-nw` among them), the `aton` dictionary's Danish texts and
   its four DGPS stations, charts, areas, sources, publications, mailing lists, its four scripts and its 34
   messages (`niord/sample/README.md`) — so integration tests can query realistic reference data.

Tests bind this via `spring.liquibase.change-log=classpath:db/changelog/changelog-with-seed.yaml`
(see `org.niord.test.ITTest`), which `include`s the production master changelog and then adds the
`seed-base-data` changeset. The data is loaded with foreign-key triggers disabled — the seed file carries
the `ALTER TABLE … DISABLE/ENABLE TRIGGER ALL` brackets — because V001's circular / self-referential
foreign keys (`Area`, `Category`, `Domain`↔`FiringSchedule`, `Publication`) are non-deferrable and would
otherwise reject the bulk load. The seed is loaded as the file says, by `org.niord.test.VerbatimSqlFileChange`
(a Liquibase custom change in the test-jar), not by Liquibase's `sqlFile`: `sqlFile` replaces each `${name}` with
a changelog parameter, and Liquibase makes every environment variable and system property one, matched without
regard to case, so on GitHub's runners (`LANG=C.UTF-8`) the templates' `${lang}` reached the test database as
`C.UTF-8`. `SeedFixtureVerbatimTest` keeps it so. The changeset after `seed-base-data` removed rows the old dump carried but the
application had retired (`retire-map-image-settings-after-seed`); it stays as applied history, and since C4b,
with no `Setting` row in the fixture, it deletes nothing. The dump itself is never edited, because a changed
file changes the checksum of the `seed-base-data` changeset a seeded database has already applied, and
Liquibase refuses to start on the mismatch. **The same holds for every
applied changeset under `db/changelog/changes/`, its comment lines included**: in a formatted SQL changelog
the comments are part of the changeset's text. On 2026-10-06 a path in a comment of `V001` was edited and
both staging environments went down on `Validation Failed: changesets check sum`; the integration tests
start from an empty database and cannot catch that. `ChangelogChecksumPinTest` (a unit test) now pins the
checksum of every changeset in the master changelog — a new changeset is added there with its checksum,
an applied one is never edited.

The changelog and the fixture are niord-base's **test resources** since the code/data/configuration plan's
C4.1 (`docs/code-data-config-plan.md` §7.4), at the same class-path paths as before. The fixture was
regenerated in C4b, which changed the `seed-base-data` changeset's checksum; only a fresh IT database applies
it, so nothing else notices. They stay in the published test-jar, because a country's ITs extend the
test-jar's `ITTest` and load the same fixture. No deployment loads them: every deployment uses the bare
master, and a new database gets its data from the bootstrap command, once. Liquibase always runs; there is
no `liquibase.enabled` flag anywhere.

The dump leaves out what a build writes that is not data: the bootstrap marker, the defaults ledger,
Liquibase's tables, users, batch data and the batch jobs' numbers (the `BATCH_JOB_*` `Sequence` rows; a job's first
run in an IT creates its counter), histories, settings and the search index (`regen-sample.sh`'s residue). The id
sequences of the left-out tables (`BatchData_SEQ`, `ScriptResourceHistory_SEQ`) keep the value the build advanced
them to, which is harmless: at or past every id.
A seeded database therefore reads `existing` to the bootstrap marker (`BootstrapSeededITTest`), and its first
start writes the settings from its own class path (niord-base's test tree, or a country's) and adopts the
fixture's texts into the ledger, as on any deployment's first start. Since C4b the fixture carries what
`ITTest` used to complete before each test until then: the base product's three reports (`standard`, `draft`,
`public`) and its 80 templates and scripts as `ScriptResource` rows (since C4.2a a template is read from the
database only; no jar holds one). Those two completions are gone.

Rows the suite pins that neither the bootstrap nor the sample carries come from a named fixture or from the test
that needs them:

- `org.niord.test.ReportFixtures.ensure(...)` — DK's `nm-report` and `nm-tp-report` (linked to `niord-nm`), on
  their base product templates, which the old IT seed carried (DK's bootstrap has them); used by
  `FmReportServiceITTest`, `MessageReportRestServiceITTest` and `IssueRenderRealITTest`. The two rows are
  committed and stay for the rest of the JVM, as `ScriptResourceFixtures`' do, so no IT asserts the fixture's
  report count exactly (five rows from the first of those classes on, three before it).
- `org.niord.test.ScriptResourceFixtures.ensure(...)` — Niord's own test templates and scripts
  (`templates/it/`, `templates/mailinglist/it-test.ftl`, `templates/messages/it-message-mail*.ftl`,
  `test-scripts/`) as rows, in the `@BeforeEach` of the test that renders them; they are not in the test-jar.
- Legacy rows of an older deployment (the 7 standard `ParamType` rows, a setting no catalog declares) and a
  Danish database's text state are planted by the test that needs them and removed afterwards
  (`ParamTypeServiceITTest`, `SettingsRestServiceITTest`, `DictionaryDefaultsLayeringITTest`); so is a missing
  path for the missing-path report, which the fixture misses none of (`MissingPathReportITTest`,
  `ScriptResourceImportITTest`).

Since the fixture holds the sample's 34 messages, a test never asserts a bare count of a series or of the
database: it counts what was there before plus its own rows, or names its own. A test finds a sample message by
its short id (NW-001-26), never by the uid in `messages.json`: the fixture's uids carry its own prefix.

The fixture's four `Attachment` rows (the sample's generated PNGs, their paths under the fixture's uid prefix)
have no files behind them in an IT's repository: nothing stages the PNGs for the suite (only `load-sample.sh`
copies them, into the regeneration's own home, which is discarded). A test that needs an attachment's bytes copies
the file into `${NIORD_HOME}/repo` itself.

Two fixture files are committed:

- **`src/test/resources/db/niord-base-data.sql`** — data only (`pg_dump --data-only --column-inserts
  --disable-triggers`), loaded on top of the Liquibase schema by the integration tests, and by nothing
  else.
- **`src/test/resources/db/niord-test-seed.sql`** — the full schema + data `pg_dump`. The human-readable
  reference for "what's in the fixture" and the source the hand-maintained V001 baseline is derived from. Not
  loaded at runtime, and not in the test-jar.

`TestFixtureCurrentTest` (a unit test) fails when the fixture's `ScriptResource` rows differ from
`niord/bootstrap/templates/` plus `niord/sample/archive/templates/` (80 + 4 files), and when either dump carries
the sample's sentinel line or message uid prefix (`theFixtureCarriesNoSampleMarker`, both read from `sample/` at
run time), naming the script: no test reads the bootstrap's templates live, so a template edit reaches the
integration tests only through a regeneration.

### Regenerating the fixture

The fixture is static; regenerate it when the base product's bootstrap, the sample, the JPA entities or the
schema change, never by hand. Run `niord/scripts/regen-sample.sh` first when the sample is due too: the sample's
SQL refuses a base it was not made on, naming that script.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 25) niord/scripts/regen-test-seed-postgres.sh
JAVA_HOME=$(/usr/libexec/java_home -v 25) NIORD_SEED_OUT=/tmp/seed niord/scripts/regen-test-seed-postgres.sh   # to compare
```

The script starts the suite's own application through `org.niord.test.SeedRegen` (no deployable is built or
booted, since 2026-10-06) once against a throwaway PostGIS container (`imresamu/postgis:18-3.6`, published on
loopback only, its database named `niord_sample`). Its class path is `niord/target/test-classes` without its
`defaults/` (the stand-in `da` pack), the root-level `*.properties` and `niord.json` (the test-jar's own
exclusions), so the first start writes the base product's texts and settings only, the rows niord-app writes for
`regen-sample.sh`: with the stand-in pack one more key (`web` `lang.da`, in Danish) shifts the `DictionaryEntry`
ids, and the sample's guard refuses ("DictionaryEntry already holds a row where the sample's go"). Liquibase builds
the schema from the production master changelog (`ddl-auto=validate`; `DefaultsLedger` and `BootstrapState` have
no JPA entity, so a schema Hibernate built would lack them), and `SeedRegen` bootstraps the new database with the
bootstrap runner and the base product's archive (`niord/bootstrap/`, zipped with the JDK's `jar` tool), and exits
with the outcome. The script then prunes the unused PostGIS sub-extensions (tiger/topology), loads the sample with
its own loader (`niord/scripts/load-sample.sh --container`, with every rule of P9 and the SQL's guard; anything but
a load stops the run), stops when the `modelLanguages` setting is NULL or missing (a start-up regression since
C3b), sets every row's `created` and `updated` to the sample's epoch (2026-10-22), replaces the sample's message
uid prefix with the fixture's own (its first three hex digits kept, so the repository folders stay) in
`Message.uid`, `repoPath` and `thumbnailPath`, `Attachment.path`, `Reference.messageId` and the issue members' and
overrides' `messageUid` (the prefix is a marker of the no-sample check, which reads niord-base's test-jar), orders
each table by its primary key, and `pg_dump`s the result into **both** files, each without the residue above, and
ends by running `k8s/scripts/check-no-sample.sh` over the dumps' folder (with `NIORD_SEED_OUT`, a folder that holds
the two dumps only). Two runs give the same bytes, with LF (`be/.gitattributes`): the build starts from `clean`, and
the dumps leave out `pg_dump`'s version banner, so a rebuild of the floating image tag changes no byte.

### The empty-database deploy path (`LiquibaseBootstrapITTest`)

Every IT now exercises Liquibase + `ddl-auto=validate`, but always *with* the base-data seed. The one path
that remains uniquely covered by a dedicated test is the **brand-new install with no seed**:
`LiquibaseBootstrapITTest` boots the full application against a separate **fresh, un-seeded** PostGIS
container with the **bare production master changelog** (no seed changeset). It asserts the schema came
from Liquibase, the reserved-word / PascalCase quoted identifiers resolve, the tables are empty, start-up
writes no domain, category, report, parameter type or script resource (since C4.1 the built-in reports,
the standard parameter types' rows and the master domain are no longer created at start; a new database
gets them from the bootstrap), and the bootstrap marker reads `pending`. `BootstrapITTest` then bootstraps
such a database with the base product's bootstrap. It is the only IT that does not extend `ITTest` (which would bind the
seeded singleton container and the seed-loading changelog).

Both connect as a new deployment's database user does: a user that owns the database and is not a superuser
(`org.niord.test.ApplicationDatabaseUser`, which makes it in the fresh container and hands it the database, as
the chart does on a new volume; `docs/database-user-plan.md`). So a changeset or a start-up statement that needs
a superuser fails there and not at a deployment's first start. The seeded suite keeps the container's superuser:
its fixture is loaded with the foreign-key triggers disabled, which only a superuser may do, and no deployment
loads a fixture.

## Keycloak realm import

The Keycloak container starts with `--import-realm` and a static realm at
`niord/src/test/resources/keycloak/niord-realm.json`, derived from `niord-bootstrap-realm-dk.json` of the
original niord-dk deployment. This imports the **`niord`** realm, whose clients include
`niord-client-master` — the OIDC client the application authenticates with — and one client per domain of
the fixture: `niord-nw`, `niord-nm`, `niord-fa`, `niord-almanac` and `niord-annex`, the sample's domain IDs and
the local realm's (renamed from `niord-client-*` in C4b, with every IT that names them). It is no longer a copy:
a fresh copy of the DK realm would bring the `niord-client-*` domain clients back, which no fixture domain
matches. The chart's realm, `k8s/helm/niord/files/niord-realm.json`, is this file byte for byte:
`RealmFilesTest` holds the two equal and every domain client to a domain id of the base bootstrap and the
sample, and `k8s/scripts/deploy.sh` stops on a difference. The `ITTest`
`@DynamicPropertySource` binds the realm URL into the app as
`spring.security.oauth2.resourceserver.jwt.issuer-uri`, so authenticated tests have a valid OIDC
provider. The realm sets `sslRequired: none` and no fixed `frontendUrl`, so OIDC discovery resolves
against the container's dynamic host/port.

## Test-scope dependencies

`niord/pom.xml` carries (all test scope, versions managed by the Spring Boot BOM):

- `org.springframework.boot:spring-boot-starter-test` — JUnit 5 (Jupiter), Mockito, AssertJ, and the
  Spring test support (`@SpringBootTest`, `@DynamicPropertySource`).
- `org.testcontainers:testcontainers`, `:testcontainers-postgresql`, `:testcontainers-junit-jupiter`.
- `com.github.dasniko:testcontainers-keycloak` — the Keycloak container.
- `io.rest-assured:rest-assured` — REST assertions for the HTTP integration tests.
- `org.awaitility:awaitility` — readiness waiting.
- test-scoped copies of the deployment-tier runtime pieces the ITs exercise (`org.postgresql:postgresql`,
  `spring-boot-starter-liquibase`, `spring-boot-starter-artemis` + `artemis-jakarta-server`).

`niord` additionally publishes its test tree as a **test-jar** (eight exclusions in `be/niord/pom.xml`:
`application.yml`, `niord.json` and the root-level `*.properties` (this module's FreeMarker test bundle only), so
they can never shadow a deployable's real configuration or texts; `defaults/**`, since the test tree's
`defaults/packs/da/` is a stand-in `da` pack (DK's Danish bundles, test data) that niord-base's ITs read as a
country's staged pack, and on a country's test class path `DefaultsGuard` would refuse it beside the country's
own; `db/niord-test-seed.sql`, `templates/**`, `test-scripts/**` and `bootstrap-fixtures/**`,
which only niord-base's own tests read; the test tree's root-level `niord.json`
stands for a country's root settings file, which start-up reads in place of `defaults/base/settings.json` until
the countries have moved theirs under `defaults/country/`); a country repository
consumes it, from the published version it pins, for the tests it keeps, and declares the same
Testcontainers/RestAssured/Awaitility test dependencies for them (test-jar dependencies are never
transitive).

The root `pom.xml` carries the `maven-surefire-plugin` and `maven-failsafe-plugin` configuration.

### The country build group

The test-jar also carries the **country build group**: niord-base's security tests and the country checks, which
run in every country build as well (docs/fe-country-split-plan.md §4.11 rule 3, [country-api.md](country-api.md)).
A member carries `@CountryBuildGroup` (`org.niord.test`), whose JUnit tag is `niord-country-build`;
`CountryBuildGroupTest` lists the members and fails when a class gains or loses the annotation. The root
`pom.xml` (niord-parent) runs the group with two executions of its own beside the default ones:
`niord-country-build` (Surefire) and `niord-country-build-it` (Failsafe, with its own summary file). Each scans
only niord-base's test-jar (`dependenciesToScan`) and selects the tag, so a country runs exactly the group and none
of niord-base's other tests; its own tests carry no tag and run in the default executions as before. Both are
switched by `niord.countryGroup.skip`: false in the root pom, so a country inherits the group with its `<parent>`,
and true in `niord`, `niord-app` and `niord-bootstrap`, since `niord` runs the same tests in its own suites,
`niord-app` builds on the same code without the IT test dependencies, and `niord-bootstrap` has no tests. The group's
Surefire execution does not redirect its output, so the settings report reaches the Maven log, and it sets
`failIfNoSpecifiedTests` false, so a country's `-Dtest=<its own test>` does not fail it.

Two members skip themselves where they do not apply: `MailHtmlTest`'s frontend check
(`theMessageClassesAreTheFrontendsVocabulary`) outside a base product checkout, since the frontend's list is checked in
the base product's own build, and `CountryDefaultsChecksTest` where the class path holds no `defaults/country/` or
`defaults/packs/` (a country before the code/data/config plan's C6); niord-base's own run reads the stand-in `da`
pack, so the test runs there. A member reads nothing outside
its class path, and the guard's configuration and controller fixtures live in `org.example.country`, outside
`org.niord`, where `NiordTestApplication` does not scan.

## The endpoint sweep

Every REST handler has an entry in the **endpoint table**, `niord/src/test/resources/endpoints/*.json`, one file per
controller group (`sources`, `messages`, `publication-issues`, `publications`, `reference-data`, `delivery`,
`system`); each file names the controllers it holds. The tests of `org.niord.web.sweep` hold the table to the code
and call every entry over HTTP as it says:

- `EndpointTableCompletenessTest` (unit, condition-blind, the SECOM administration interface included) and
  `EndpointTableCompletenessITTest` (the running handler mapping) fail on a handler without an entry, an entry
  whose handler is gone, a changed annotation, and a path an entry lists that no longer reaches its handler (the
  generator adds paths, only a person removes one: the frontend may still call it). `EndpointTableFormatTest` holds
  each entry to the format: known kinds that fit the verb, identities, a refusal with its detail sentence (never a
  blank one), a 404 only with one, never a 405 or 415, fixture rows and cases the group declares, known faults whose
  code is in a record and whose cell the entry can run, covering tests that are test methods; and a reviewed entry
  to decisions that assert something: a read names rows or a size (`expect`) or says why it is empty
  (`emptyByDesign`), each rule has its cells (`owner`: a cell on another owner's row, `identity@row`; `domain`: a
  cell of another domain; `ticket`: a ticket cell; `realmRole`: a cell that holds the role and one that does not), a
  code-checked handler has a cell the annotation does not decide, an expected log line names a message or an
  exception (not a level or a package), an exempt entry still runs refused cells. `SweepSelfTest` pins the engine's
  own guarantees without a server.
- `EndpointSweepITTest` runs in the context every `ITTest` shares; `SecomEndpointSweepITTest` runs the entries of
  the SECOM administration interface (`context: "secom"`) in the SECOM context. Each finds the `SweepGroup`s of its
  context and runs, in a fixed order, every group's fixture, the reads, the writes and uploads, the global entries
  (refused cells only), the settle point, every group's tear-down, and the summaries (identities missing, rows left
  behind, entries still pending (it fails on one), the timing in `target/endpoint-sweep-timing.txt`, the background
  errors of the run). Each request is first resolved against the handler mapping and fails unless it reaches its
  entry's handler; it carries
  `X-Request-Id: sweep-<group>-<n>`, and the log events of that id (and of an upload's job) must hold no ERROR, no
  lazy-loading failure or stack overflow (in the throwable or named in the message), and no other throwable the
  entry does not list in `expectedLog`; an ERROR of that id logged after the entry's test ended (an `@Async` task)
  is listed by the run's last test. An entry passes only if a request of its own test reached its own handler and
  was answered below 400 (unless it is refused-only or `coveredBy`): a sweep step that calls nothing, or calls a
  neighbour's path, fails.

**An entry** (the generator writes the facts, a person the decisions): `key` (`"VERB /path"`), `handler`, `alsoAt`
(the other paths), `kind` (`read`, `compute`, `write`, `writeByGet`, `upload`, `global`, `globalIdempotent`),
`context` (`secom` for the SECOM interface, swept in its own class), `guard`, `codeCheck` (`found` or `none-found`,
the generator's suggestion from the bytecode, two levels deep, with `codeCheckEvidence`), `params` (or `"*"`),
`rules`, `realmRole`, `answers` (cell name to `"ok"` or `{"status": 403, "detail": "..."}`), `case`
(`"<SweepGroup class>#<name>"`), `coveredBy`, `expect` (`rows` the fixture declares, `at`, `minBytes`,
`contentType`), `expectedLog`, `knownFault`, `exempt`, `reviewed`. A cell name is an identity, optionally `@` and a
fixture row: `anonymous`, `anonymous+header`, `user`, `userOtherDomain` (the user's token with `NiordDomain:
niord-nm`), `editor`, `admin`, `sysadmin`, `domainEditor`, `adminElsewhere` (the last two hold their
roles on a domain client only; `KeycloakTestTokens` lists the realm), and `ticket(<identity>)` or
`ticket(<identity>:<role>)`, a ticket that identity mints just before the request. An entry with no code check runs
one anonymous call when it is `@RolesAllowed`, and its sweep as the least privileged identity the annotation admits;
an entry with a code check runs exactly the cells of its `answers`. A role or domain that the default run must see
(a read that fails for one role, a header the handler reads) is written as a cell in `answers`: the default run
runs every written cell, the derived ones only with `-Dniord.sweep.full=true`.

`rules` and `realmRole` are what the cells must cover (the format test asks for them); the engine runs the cells,
not the rules. `coveredBy` (`"<TestClass>#<testMethod>"`, a JUnit test method) skips the entry's sweep step and
keeps its cells, probes and log rule, so the entry still needs a case for the request its cells send. `settle: true`
goes with `ctx.settle(subject)` in the case (or `ctx.settle(null)` for a write that queues no mail but whose
listeners run after the answer): the settle point awaits the mail and a quiet background, and a mismatch between
the two fails. A group names the tables its rows live in (`SweepGroup.tables()`): they are compacted after the
tear-downs (`VACUUM FULL`), so the free space of the sweep's deletes does not reorder rows that later tests make
and update there, and lists in `leftByDesign()` what no endpoint can remove (an upload's batch instance).

**Adding a handler.** The completeness tests name a handler without an entry, and an entry whose handler, path or
annotation changed. Run the generator (below): it writes a skeleton into the file of the controller's group (add the
controller to that file's `controllers` first), with the facts filled in, `"case": "pending"` and `reviewed: false`;
the format test and the sweep fail on it until a person decides how the entry is treated:

- **swept**, the usual: a `case` of the group's `SweepGroup` (`SourcesSweepGroup` is the worked example). A read's
  case sends the request, and the entry names what the answer must hold (`expect`) or why it is empty
  (`emptyByDesign`); a write's or an upload's `sweep` step writes on a row it makes itself, reads back every field it
  sent and removes the row in `finally`.
- **`global`**: a write on state the whole deployment shares (an import of a whole data set, the firing runs, a
  batch set, the SECOM reconcile) that the shared test database cannot take: only its refused cells run.
  **`globalIdempotent`**: such a write that is harmless to repeat (a recompute of every sort order, the SECOM
  security reload): its sweep runs, after every other entry.
- **`exempt`** (`partner`, `framework`, `external` or `refusalOnly`, with the reason and what is still asserted): a
  handler the sweep cannot drive, such as a partner's protocol (`POST /rest/secom/security/renew`) or the bootstrap.
  It still runs its refused cells, so it keeps a case.
- **`coveredBy`** (`"<TestClass>#<testMethod>"`): an existing test method owns the round trip; the sweep step is
  skipped, the cells, the probes and the log rule still run.

Then the cells. A handler without a code check (`codeCheck: none-found`) is decided by its annotation, and usually no
cell is written. For one with a code check (`found`: the generator's suggestion from `codeCheckEvidence`, which a
person confirms or corrects) write a cell for each identity the code tells apart, each refusal with its `detail`
(`null` for an answer without one), and the cells `rules` and `realmRole` ask for. A cell on a row
(`admin@sweep-messages-draft`) names a row the group's fixture declares in `rows()`; the fixture makes each row through
the API, finds it again by name on a second run, and the tear-down removes it and reads it gone. `userOtherDomain`
holds no role in `niord-nm`, so its cells show that domain's role floor, not a domain rule: a domain rule is shown by
`adminElsewhere` or `sysadmin` with the request's domain set to `niord-nm` (`SweepRequest.domain`), or by a `niord-nw`
identity on a `niord-nm` row. Set `reviewed: true` and run the group, then the group in full:

```bash
./mvnw -pl niord test -Dtest=EndpointTableCompletenessTest -Dniord.endpoints.write=true   # writes src/test/resources/endpoints
./test-it --its-only -pl niord -Dit.test='EndpointSweepITTest,EndpointTableCompletenessITTest' -Dniord.sweep.groups=sources
./test-it --its-only -pl niord -Dit.test=EndpointSweepITTest -Dniord.sweep.groups=sources -Dniord.sweep.full=true
```

`-Dniord.sweep.groups=a,b` (or `NIORD_SWEEP_GROUPS`) runs only those groups; without it every group runs.
`-Dniord.sweep.full=true` (or `NIORD_SWEEP_FULL`) adds the bad-input probes, the variants and the role cells the
annotation decides, which the default run (the one CI pays for) leaves to `RestControllerSecurityAnnotationTest`,
`PublicationTierMatrixTest` and `RoleAuthorizationMatrixITTest`. **The cost**, every group, from the first fixture to
the summaries of both contexts, on a developer's Windows workstation: the default run (about 2,300 calls) about 70 s
when the machine is otherwise idle and about 140 s beside two other Maven builds, the full run (about 4,100 calls)
about 95 s and 175 s. Each class also starts its application context, which a whole IT run shares. One group takes
seconds; the timing file lists each group's calls and time, and the ten slowest calls.

**Reading a failure**: each entry is one dynamic test named by its key, listing every mismatch as

```
GET /rest/sources/source/{id}   [SourceRestService.getSource, endpoints/sources.json]
  as sysadmin (NiordDomain: niord-nw), id=987654321 (unknown-id probe), request id sweep-sources-0412
  expected: 404 "No such source"   real: 500
  log of the request: ERROR ...
  body: {...}   (first 300 characters)
```

If the new answer is intended, change the entry; if not, it is a fault: record it, mark the entry's `knownFault`
with the record's code and what it gives today so the sweep stays green, and do not fix it in the sweep's change.
The code names an open record, and the format test refuses any other: the code of a `## CODE.` heading of
`potential-errors.md` that is not marked fixed, or a phrase (at least 20 characters) of an entry of
`fe/docs/backlog.md` before its "Deliberately not built". A fault of background work (no sweep request) goes into its
file's `backgroundKnownFaults` the same way, by logger and message. The forms:

- `{"code": "...", "cell": "<cell>", "answer": 500}` for a table cell (`"editor"`, `"admin@otherOwnerRow"`);
- `"cell": "<identity>"` for the calls a case sends as that identity to the entry's own handler (`"admin"`: the
  sweep of an entry swept as admin), or `"cell": "sweep" | "allowed" | "probes" | "variants"` for a step of the
  case, `"*"` for every call. A case's call that shows the answer passes and ends its step (the rest is the fault's
  consequence; the case's `finally` blocks still run);
- `"log": "<substring>"` for a log line, alone or with `answer`;
- `{"code": "...", "cell": "*", "check": "<text of the case's check>"}` for a field the backend drops: the case's
  `ctx.checkEquals(expected, actual, "<text>", ...)` may then be false.

A known fault that applied to a call of the entry's test and showed in none fails the entry ("no longer shows"),
so the record is closed with the fix, and the mark leaves the table in the same change. A fault of the probes or the
variants applies only in the full run.

**The shared database.** The sweep runs in the shared context and database, so its rows are seen by the classes
after it. Every row it makes is named `sweep-<group>-...`; a group's fixture makes only rows of kinds no existing IT
counts exactly (the group checks that and writes it down); a write case removes its own rows in `finally`; a group's
tear-down removes its fixture, reads each row gone and names what it cannot remove (the run's summary fails on
it); reads assert only named rows, never a total; a row's name is found as a value of its own (`sweep-x-a` is not
found in `sweep-x-a-da`). The sweep passes alone, in the whole run and in reverse order
(`-Dfailsafe.runOrder=reversealphabetical`), and no other IT changes its result with it.

**The frontend's half.** `fe/scripts/endpoint-roles.test.mjs` (in the frontend's `npm run test:guards`) reads the
same table and holds every HTTP call of the administration app and the public site to it: each call must reach an
entry, send only parameters its handler reads, ask a ticket role a ticket cell accepts, and reach a public-site
handler as anonymous; every role-gated item is allowed by the endpoints it calls. A new handler the frontend calls,
a changed `params` or a cell that now refuses an offered identity fails it; `fe/docs/testing.md`, "The frontend
against the backend's endpoint table", says what to change. A change to the table therefore runs Frontend CI's
administration jobs as well as Backend CI (the folder is in `fe-ci.yml`'s filter and in `verify-local`'s
`ADMIN_RULE`).

**What the sweep does not cover.**

- A refused cell stops at the first gate it meets, so a check that sits behind another on the same row (the
  publication series' and one-off saves' `assertMayAssign` behind `assertWritable`; the issue lifecycle's two-stage
  guards) is reached only by a round trip on a row the caller may write, which the cells do not make.
- A write's round trip reads back the fields it sent: a save that drops a field the case leaves unchanged goes
  unseen. A case that changes every field the save copies, then reads each back, closes that.
- Cells that need rows another group owns: the 409 of `DELETE /rest/dictionaries/dictionary/{name}/{key}` for a key
  a list parameter type holds, and a category's `templateParams` (each names a parameter type).
- In the frontend, the role-gated components the spec does not render (`role-offers.json`, `notRendered`).

## Requirements

- **Java 25** (`java.version=25` in the root POM). `.java-version` sets this automatically for jenv/sdkman users.
  Without a version manager: `export JAVA_HOME=$(/usr/libexec/java_home -v 25)` (macOS).
- **Docker** must be running for the integration tests. Without Docker, `mvn verify` still succeeds —
  the integration tests skip rather than fail.

## Known limitations and follow-up

- **The app-boot test covers boot, not behaviour.** `AppBootSmokeITTest` proves the app boots healthy
  against the containers; it does not drive endpoints. Detailed business/domain tests (services,
  repositories, REST endpoints, imports, promulgation, navigational warnings, ATONs, S-124) are out of
  scope here.
- **The fixture is static** — regenerate it with `niord/scripts/regen-test-seed-postgres.sh` when the
  schema, the base product's bootstrap or the sample changes (see
  [Regenerating the fixture](#regenerating-the-fixture)).
