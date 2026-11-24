# AGENTS.md — backend

The Spring Boot backend of Niord: a two-module Maven reactor under `be/` — `niord/` (the country-neutral
base library: `org.niord.core`, `org.niord.web`, `org.niord.model`, the shared test harness and the
suite) and `niord-app/` (the generic English deployable). The Danish and Greenland deployables,
`niord-dk` and `niord-gl`, are repositories of their own since 2026-10-06 (`SIFA-DK/niord-dk`,
`SIFA-DK/niord-gl`): each builds on the published `niord-parent` and `niord-base` of a pinned version
and shares nothing with the others except through `niord` ([docs/cicd/design.md](docs/cicd/design.md)
§2.1). Repository-wide rules are in the [root AGENTS.md](../AGENTS.md).

Spring is permanent. The application was ported from Quarkus, and the Quarkus reactor is not in this
repository; Spring-specific code and Spring-specific tests are correct, not a compromise.

## Stack, in one line each

- Java 25, Spring Boot 4, Spring MVC (`@RestController`; every handler maps under `/rest/...`).
- PostgreSQL 18 + PostGIS; Liquibase always runs at startup and Hibernate ORM 7 then runs `ddl-auto=validate`.
  Identifiers are globally quoted and mixed-case (`public."PublicationSeries"`); write native SQL that way.
- Jackson 3 (`tools.jackson.*`, `JsonMapper.builderWithJackson2Defaults()`); Jackson 2 databind is on the
  classpath only for RESTEasy/SECOM and the criteria canonical-bytes trio — do not reach for it elsewhere.
- Static files (`META-INF/resources/`): only what the server's own output links to — `css/templates/` (PDF/mail
  CSS) and `img/` (logos, flags, the map-image placeholder, file-type icons). The frontends are in `../fe/`. A
  deployable image carries its country's build of the administration app and serves it at `/` (`WebAppConfig`,
  `niord.web-app.dir`); `GET /` redirects to the `adminAppUrl` setting when that names another origin.
- JBeret batch jobs (`META-INF/batch-jobs/*.xml`, artifacts `@Component @Scope("prototype") @Named`),
  FreeMarker reports, Keycloak (roles reach `@RolesAllowed` through the JWT converter and the `NiordDomain` header).

## Idioms that matter

- Services: `@Service` with field `jakarta.inject.Inject`; controllers: `private final` fields plus a
  package-private constructor; loggers static. Never `@Autowired` in main code.
- Transactions: `org.springframework.transaction.annotation.Transactional` (`BaseService` is class-level
  transactional; propagation through a dedicated bean, never self-invocation — see `SecomTxRunner`,
  `PublicationTxRunner`). There is no default transaction timeout.
- Startup work that touches the database: `@EventListener(ApplicationReadyEvent.class)` + `@Transactional`,
  never `@PostConstruct`; pin `@Order` when the listener writes rows others read.
- Security: `SecurityConfig` is `anyRequest().permitAll()` — method security (`@RolesAllowed`/`@PermitAll`,
  no `ROLE_` prefix) is the entire guard, and `RestControllerSecurityAnnotationTest` fails a handler without one.
  At start-up the country contract's guard (`CountryContractGuard`, run by `CountryContractInitializer` from
  `META-INF/spring.factories`) also refuses a registered handler without one, a protected bean or property
  replaced or duplicated from outside niord-base, and any CORS configuration.
- Errors: throw `NiordWebException` (renders as RFC 9457 problem detail); a `null` body yields 204.
- Mails: the FreeMarker templates print values unescaped, so HTML that users wrote reaches a mail only through
  `MailHtml`, which holds both allow-lists: a comment's (no classes or images) and a message text's (also the
  message-HTML classes of `fe/shared-html/message-html-classes.ts` and own-origin images); the stored text
  stays as it was written. Another value users type goes through `MailHtml` too (as an attachment's attributes
  do) or is escaped, before rendering (the greeting's names in `MessageMailService`, which covers templates
  stored in the database) or in the template (`?html`).
- **Code, data and configuration** ([../docs/code-data-config-plan.md](../docs/code-data-config-plan.md) says
  what each of its phases changes). What an operator can see and edit in admin or sysadmin is data: every
  template, script and report, the built-ins included. Anything else is code; Spring properties are
  configuration, read at start and never written to the database.
  - A repository change never changes or deletes a deployment's rows. It may only add inert keys a
    deployment never had (text keys and setting keys), added when missing and never changed afterwards.
  - Data reaches a deployment only by the bootstrap of a fresh database or a sysadmin's import, recorded
    with the user. The Script resources and Dictionaries imports show a preview first, add what is missing and
    overwrite only what the sysadmin ticks; the Script resources import records the user and the source
    `import` in `ScriptResourceHistory` (the page's own saves record `editor`), and it refuses a file
    `ScriptResourceCheck` refuses, as the bootstrap does. A batch set refuses the script-resource, dictionary
    and settings imports and `script-executor` (`BatchSetService.BATCH_SET_JOBS`); no watched folder starts a
    job.
    The base product's bootstrap is `niord/bootstrap/` (its README says what is there): applied once, by
    the bootstrap command (`POST /rest/bootstrap`, `be/scripts/bootstrap.sh`), to a database whose marker
    (`BootstrapMarker`, table `"BootstrapState"`) reads `pending` and that still holds no data at the claim
    (`BootstrapMarker.firstDataTable()`, checked in the claim's locked transaction; the rows a first start writes by
    itself, the mirrored users and the queued mails a scheduled alert writes are not data, `BootstrapMarker.NOT_DATA`;
    `LiquibaseBootstrapITTest` names a table a start-up or first-request writer fills); a database that held data at
    its first start reads `existing` and is never bootstrapped. The base bootstrap's data is in English only; a
    country's bootstrap adds its languages. Within that one run a later archive's values win (the owner's ruling
    of 2026-10-24, D4): for an entry an earlier archive of the run created, a value the later archive gives
    replaces the earlier one, a value it leaves out is kept, what is new is added, and nothing is removed
    (`BootstrapRun`, the code/data/config plan's §7.2). A list given explicitly, even empty, replaces; a field left
    out or null keeps; an empty string counts as left out (`Given`; the owner, 2026-10-25). Every sysadmin's import and every release folder is
    unchanged by this. The first archive is the base product's (the command does not check it). There is no
    retry: a failed or broken-off bootstrap is refused for good (409), the remedy is a database recreated empty
    and the same command with the same archives (D4), and a bootstrap job is never restarted from the batch page
    (409). A
    template the code names, a built-in report or other data a fresh deployment needs goes into the
    bootstrap, never into a start-up writer. A template path the code names
    goes into `RequiredTemplates` and `niord/bootstrap/templates/` (`RequiredTemplatesTest` fails otherwise).
    A new or changed template ships in a release folder, `niord/bootstrap/releases/<yyyy-mm-dd>-<topic>/`
    (generated by `ReleaseFilesGenerator`; never changed once merged into main), which the release notes of
    the first version that contains it name; a sysadmin imports it. A Java change a template depends on stays
    compatible with the templates deployments hold, or ships the template that way.
    The sample (`niord/sample/`, its README says what is there; the code/data/config plan's kind (f)) is
    Danish sample data loaded only into the loopback `niord_sample` by `../start-local.sh --sample` (a
    country's launcher: `niord_sample_<cc>`), with `psql` after the base bootstrap
    (`niord/scripts/load-sample.sh`); its own ids start at 1,000,001 and it names base rows by natural key,
    so it loads on any country's backend. It is never in `src/main/resources`, a jar
    or an image (`CoreResourcePackagingTest`; `k8s/scripts/check-no-sample.sh` in CI, over jars, zips and
    docker export tars); its messages are written for it, never exported from any environment, and
    `SampleFolderTest` holds it to the safety rules and, in CI, its guard's changesets to the changelog.
    Regenerate it with `niord/scripts/regen-sample.sh` after a change to the base bootstrap, the schema or
    its sources, never by hand. At load time the guard refuses a sample made on another schema (cure:
    regenerate) and a database whose sample id range is taken or whose base rows do not resolve by natural
    key (cure: `../start-local.sh --sample --reset`).
    The integration suite's fixture (`db/niord-base-data.sql`, in the test-jar) is a dump of the base bootstrap
    plus the sample, rebuilt by `niord/scripts/regen-test-seed-postgres.sh` after `regen-sample.sh`, never edited.
  - No read writes a row, there is no reload from the jar and no reset to default, and what an operator
    deleted stays deleted. No template or script is read from the jar: since the plan's C4.2a
    `FmTemplateLoader` and `JsResourceService` read the database only (copy-on-use and the temporary mapping
    are gone), and no jar holds a template (`CoreResourcePackagingTest`). A path data names that has no row is
    listed by the missing-path report (`MissingPathReport`, at start and `GET /rest/script-resources/missing-paths`).
    (The settings insert-on-read went in C3b; the start-up re-creation of the built-in reports, the standard
    parameter types and the master domain in C4.1. The standard parameter types are code, held in memory by
    `TemplateExecutionService`.)
  - Base product defaults live under `niord/src/main/resources/defaults/base/` (its README says what is there),
    a country's under `defaults/country/`. Texts are layered key by key and added only when missing;
    `DefaultsLedger` remembers what start-up added and what an operator deleted. A dictionary key Java reads
    goes into `RequiredTexts` (its literal test fails otherwise), and the text checks
    (`BaseProductTextsCheckTest`) hold the texts under `defaults/` to the markup, length, control-character and
    placeholder rules.
  - FreeMarker's `?new` admits only classes directly in `org.niord.core.script.directive` (no
    sub-package: a new directive goes in that package; `DirectiveClassResolver`), and `?api` is off
    (`FmTemplateService.newConfiguration`).
  - Settings: `@Setting` fields are DB-backed and env/system-property overridable; they are not Spring
    properties. A saved setting takes effect at once: the save (Settings page or settings import) sets every
    `@Setting` field of the key again after commit, other nodes follow at the change-count poll (about 30 s),
    so an editable `@Setting` field is `volatile` and request code reads the field, not `SettingsService`. A
    restart setting is the exception: on `SettingInjectionBeanPostProcessor.RESTART_KEYS` and on
    `SettingsCatalogCurrentTest`'s list with its reason, its description ending "Applies at the next start."
    A value that cannot change under a running server (a folder, `niord.home` among them, the Keycloak address) is deployment
    configuration, `editable = false`, not on the Settings page; the page and the save take `editable` from
    the catalog, not the row. A repository settings file holds no real secret (a password setting is empty
    there). A setting is declared in code, as an `@Setting` field or a static
    `Setting` constant (never only a bare key string), with its type, scope (`operator`, or `state` for a
    value the system writes), `web` and `editable`; a key only the frontends read goes into
    `FrontendSettings`. Start-up adds an operator setting only where the database lacks it and the code that
    declares it runs on that node (`SettingsService.addMissing`), never on a read; a `state` setting is
    written only by `SettingsService.writeState`; the public `set` (the Settings page) and the settings import
    refuse a `state` key and a key no catalog declares. After a declaration change, regenerate
    `defaults/base/settings-catalog.json` (the command is in that folder's README; its `declaredIn` changes
    with the declaring class), which `SettingsCatalogCurrentTest` checks (a new or removed key also changes
    the key count that test expects); the settings checks (`BaseProductSettingsCheckTest`) hold the settings
    files under `defaults/` to the type, undeclared-key, state-key, secret and completeness rules.
  - niord-base holds no country data. `country` and `organisation` are `required` settings with no code
    default: the base product's settings file gives the generic deployable neutral values (`XX`, `Niord`),
    and every country's file sets its own. S-124 takes the warnings' nationality, ids and MRN from `country`
    (`S124Service.country()`, which refuses a value that is not two letters), and the exchange-set
    organisation from the `msg.responsible.agency` text, else `organisation` (both blank: an error naming
    the setting). The only Danish code is the listed exception `core/publication/series/legacy/`
    (the legacy publication import), until DK's cutover. `CountryWordsTest` (the base product guard's backend
    half, with its list `niord/src/test/resources/build/country-words.json`; a unit test outside the country
    build group) fails on a country word, a Danish locale chosen in a template or a Danish or Faroese place
    name in niord-base's `src/main`, its `bootstrap/` or niord-app's `src/main`. An allowed hit gets an entry
    in the list with its reason, and an entry that permits nothing fails.
  - The backend contract a country builds on is [docs/country-api.md](docs/country-api.md): its extension
    points, the protected beans and properties, the country build group and the override check. A test that
    must also run in every country build carries `@CountryBuildGroup` (`CountryBuildGroupTest` lists them), and
    a protected bean, property or extension point changes there together with `CountryContractGuard`
    (`CountryApiDocTest` holds the document to the guard's lists).
  - A new Liquibase changeset changes the schema, and reshapes only the values a schema change requires;
    it never inserts content, deletes rows or changes what a value says, so a setting row goes only by
    hand SQL. The applied exceptions stay as they are: `V005`'s retirement of three settings, and the seed
    changelog `changelog-with-seed.yaml` (its seed and the retirement that repeats `V005`; niord-base's test
    resources since C4.1, kept in the test-jar, and selected by the integration tests only).
- The repository (attachments, publication files, the archive) is rows of the `RepoFile` table, read and written
  through `RepoStore` (no entity; JDBC, one row per file, read whole, a weak ETag of `updated` and `size`); a path
  is a repository-relative string, normalised by `RepoPaths`, and `RepositoryService` serves `messages/`,
  `publications/` and `temp/` only. The volume (`niord.home`) is a cache: thumbnails and map images under
  `cache/<repository path>` (`RepoCache`), tiles, the Lucene index and the batch files, made again on a miss and
  backed up by nothing. Nothing writes a durable file to disk; a new file of the application is a row.
- Time: the domain's time zone formats; nothing may depend on the JVM default zone.

## Build, run, test

`./run.sh`, `./reload.sh`, `./test-it`, `./dev doctor` and the exact Maven forms are in [README.md](README.md).
Two rules that are not in the output:

- **Integration tests run on Docker Desktop** through `~/.testcontainers.properties`; **Kubernetes runs on
  OrbStack** (`k8s/`). Never repoint one at the other. `docker ps` is not a readiness check when two engines
  exist — `./dev doctor` is.
- A run in which the ITs skipped reports `Tests run: 0` with BUILD SUCCESS. `./test-it` fails that run;
  a bare `mvnw verify` does not.

Offline Maven works from a warm `~/.m2` (`./mvnw -o -pl niord ...`); the first Failsafe run on a machine
must be online to fetch the JUnit provider.

## Changing things

- Change as little as possible; a change should look like the code around it. Do not alter behaviour that
  the task does not name.
- `pom.xml` changes are deliberate: say why in the change, never as a side effect.
- A change to what a user, an API client, a country or an operator sees or does updates the guide that describes
  it in the same pull request (`../guides/`, `../country-kit/docs/`, [docs/country-api.md](docs/country-api.md)):
  the rule is in the [root AGENTS.md](../AGENTS.md) (_Working here_), and which guide serves which reader in
  [`../guides/README.md`](../guides/README.md#keeping-the-guides-level-with-the-product).
- `../fe/shared-map/portrayal/` (the portrayal definition and its symbols) is copied by Maven into niord-base
  (`niord/pom.xml`), where the map-image renderer reads it (`PortrayalDefinition`): a change there runs Backend CI
  and is measured in both web applications and in the server-made map images.
- The map-image renderer draws the `osmUrl` base map only, never the WMS or OpenSeaMap, and never pre-fetches
  tiles or pre-builds images (OSM tile policy): _Server-made map images_ in
  [map-portrayal.md](../fe/docs/map-portrayal.md).
- A suspected bug outside the task is documented in [docs/potential-errors.md](docs/potential-errors.md)
  (open items only, `## CODE. title`, location / description / why it looks wrong / what exposed it), not
  fixed in passing. Code that cannot be tested without a production change goes to
  [docs/untestable-code.md](docs/untestable-code.md).
- A new or changed REST handler fails the endpoint table's tests until its entry in
  `niord/src/test/resources/endpoints/` is decided and swept, and the frontend's `npm run test:guards` reads the same
  table: _The endpoint sweep_ in [docs/test-infrastructure.md](docs/test-infrastructure.md#the-endpoint-sweep). A
  `knownFault` there names an open entry of `docs/potential-errors.md` (or of `../fe/docs/backlog.md`).
- **Writing tests is its own task with its own rules** — follow [docs/test-authoring-guide.md](docs/test-authoring-guide.md)
  (scope, hard rules, tiers, quality bar) and [docs/test-infrastructure.md](docs/test-infrastructure.md)
  (naming, `ITTest`, containers, seed, where output goes). In short: `*Test` = Surefire unit, `*ITTest` =
  Failsafe on `ITTest`; `@Autowired`/`@PersistenceContext` in tests; a `@Transactional` test rolls back,
  committed fixtures go through `org.niord.test.Tx.requiringNew()`, and a committed cleanup ends the
  test-managed transaction first (`TestTransaction.end()`) or it deadlocks on the rows the test touched.

## Reading order (`docs/`)

1. [test-infrastructure.md](docs/test-infrastructure.md) — how the suite runs
2. [test-authoring-guide.md](docs/test-authoring-guide.md) — how tests are written here
3. [cicd/design.md](docs/cicd/design.md) — the target repository and pipeline layout;
   [country-api.md](docs/country-api.md) — the backend contract a country builds on
4. [potential-errors.md](docs/potential-errors.md), [untestable-code.md](docs/untestable-code.md) — open findings
5. [secom-design.md](docs/secom-design.md), [secom-go-live.md](docs/secom-go-live.md) — the SECOM/S-124 subsystem
6. [lucene-postgres-toggle.md](docs/lucene-postgres-toggle.md) — the message full-text backend switch
