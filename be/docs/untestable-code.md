# Untestable code

Code that cannot reasonably be unit- or integration-tested as it stands, with the reason and what would
make it testable later. **No production code was changed to accommodate testing** — an entry here is a
deliberate "not covered, and here is why", not a TODO.

One latent risk is worth keeping in view even though it is not an entry in its own right: the
scheduled mail writers (`ScheduledMailService.sendPendingMails()` and `deleteExpiredMails()`) have no
serialization guard between them. Since SCHED3 every `@Scheduled` job shares Boot's scheduler of four threads
(`spring.task.scheduling.pool.size` in `niord-core-defaults.yml`, pinned by `SchedulerPoolSizeITTest`), so the
two can run at the same time; a cron job is still never started again while its own last run is running. No
test pins what happens when they overlap. Two paths this paragraph once named as unpinned are
covered: `NiordIntegrationExecutionService` now re-imports before it cancels, and
`NiordIntegrationExecutionGapITTest.failedReimportLeavesOldCopyPublishedAndImportsNoReplacement` pins that a
failed re-import leaves the old copy published; `TwitterPromulgationService`'s `@Async` posting path is
driven to the wire by `TwitterPromulgationHttpITTest`.

---

# Untestable code — area package

## `AreaService.computeMessageAreaSortingOrder(Message)` — non-deterministic result

- **Location:** `niord/src/main/java/org/niord/core/area/AreaService.java`, `computeMessageAreaSortingOrder`.
- **Why it is hard to test:** The method deliberately adds a random jitter to every result so that no
  two messages share a sort value:
  ```java
  no += new Random().nextDouble() / 1000000.0;
  ```
  The return value is therefore non-deterministic and cannot be asserted exactly. The directional
  cases (`NS`/`SN`/`EW`/`WE` map to `±lat`/`±lon`) are otherwise pure, but the input is a fully-built
  `Message` (with associated areas and a geometry that `GeoJsonUtils.computeCenter(message.toGeoJson())`
  can reduce to a center), which is an expensive fixture to assemble.
- **What would make it testable:**
  - Extract the deterministic core (the `switch` on `AreaMessageSorting`) from the jitter, so the
    base sort value can be asserted to within `< 1e-6`.
  - Or inject the randomness source so a test can pin it.
  - Failing that, an integration test could assert only the **relative ordering** of two messages
    (e.g. a more-northern message sorts before a more-southern one under `NS`), tolerating the jitter
    — but only after building real `Message` fixtures, which is out of scope for the area package.

## `AreaService.recomputeTreeSortOrder()` — scheduled entry point

> **Scope:** the tree-sort enumerator itself *is* covered by the framework-neutral
> `AreaRecomputeTreeSortOrderTest` (depth-first sequential `treeSortOrder`, the message-sorting collapse,
> the changed-node reporting). Only the cron firing *time* and the DB/settings "skip-if-unchanged + stamp
> last-processed" wrapper remain untestable — see the consolidated "Scheduled (`@Scheduled`) jobs" section.

- **Location:** `niord/src/main/java/org/niord/core/area/AreaService.java`, the `@Scheduled(cron=...)`
  method.
- **Why it is hard to test:** The trigger is the Spring scheduler (cron `13 3 * * * ?`), driven by
  wall-clock time and the application lifecycle, not by a unit test. The recursive worker overload is
  `private`. The protected overload and the inherited `recomputeTreeSortOrder(Setting)` are reachable in
  an integration test, but asserting their effect needs a controlled multi-level tree plus the
  `areaLastUpdate` state setting, and the result interacts with the live `treeSortOrder` of the seeded tree.
- **What would make it testable:** Invoke the inherited `recomputeTreeSortOrder(AreaService.AREA_LAST_UPDATE)`
  against a known, isolated sub-tree in an integration test and assert the resulting `treeSortOrder`
  values (the last-processed stamp goes through `SettingsService.writeState`) — deferred here because it
  is entangled with the full seeded tree and the message-sorting override.

## `AreaService.getIntersectingAreas(..., domain=true)` — request-scoped domain

- **Location:** `niord/src/main/java/org/niord/core/area/AreaService.java`, `getIntersectingAreas`.
- **Why it is hard to test:** With `domain=true` it reads `domainService.currentDomain()`, which is
  the request-scoped "current domain" resolved from the authenticated principal — there is no current
  domain in a plain service-level integration test. The geometry path itself relies on a PostGIS-style
  spatial predicate (`SpatialIntersectsPredicate`) and seeded area geometries.
- **What would make it testable:** Drive it through the REST endpoint (`POST /areas/intersecting-areas`)
  as an authenticated request with a selected domain, or provide a way to set the current domain in a
  service test. The non-domain spatial path could be integration-tested with purpose-built geometries,
  deferred here as a larger spatial-fixture effort.

---

# Untestable code — `util` package

Code in `org.niord.core.util` that could not be meaningfully covered by the framework-neutral Tier-1
unit tests. The rest of the package **is** covered (430 passing tests across 14 classes); only the items
below were left out, with the reason and what would make them testable later. No production code was
changed.

## `CdiUtils.getBean(Class)` — thin Spring `ApplicationContext` passthrough

- **Location:** `niord/src/main/java/org/niord/core/util/CdiUtils.java`, `getBean`.
- **Why it is low-value to test:** in the Spring port `CdiUtils` is a `@Component` implementing
  `ApplicationContextAware`, and `getBean` is a one-line `return applicationContext.getBean(clazz);`
  passthrough. It is now trivially mockable (inject a stub `ApplicationContext`), but exercising it only
  re-verifies the Spring container, not niord logic — so it is left uncovered by design rather than by
  impossibility.

## CLI `main(...)` entry points — non-deterministic, stdout/network bound

- **Location:** `PositionFormatter.main(String[])` and `HtmlToPlainText.main(String...)`.
- **Why it is hard to test:** `PositionFormatter.main` only `System.out.println`s demo values (already
  covered directly via `format(...)` assertions). `HtmlToPlainText.main` performs a live network fetch
  (`Jsoup.connect(url).get()`, 5 s timeout) and writes to stdout, and throws via `Validate` on bad
  argument counts — non-deterministic and side-effecting.
- **What would make it testable:** Extract the body into a pure function (already effectively done — the
  real logic lives in `getPlainText`), or capture stdout and inject the fetched HTML. Not worth the
  brittle plumbing for demo `main`s.

## `TextUtils.printDocument` / `cleanHtml` — JDK/JTidy-version-brittle output

- **Location:** `niord/src/main/java/org/niord/core/util/TextUtils.java`, `printDocument` (and `cleanHtml`
  beyond a shallow root-element check).
- **Why it is hard to test:** `printDocument` serialises a `org.w3c.dom.Document` via the JDK
  `Transformer`; its exact indentation, whitespace, and XML-declaration formatting depend on the JDK's
  `TransformerFactory` and are brittle across JVMs. `cleanHtml` delegates to JTidy, whose attribute
  normalisation / void-element handling is specific to the pinned JTidy version. `cleanHtml` is asserted
  only to the extent that it returns a `Document` whose root tag is `html`.
- **What would make it testable:** A canonicalising XML comparator (e.g. XMLUnit) would make
  `printDocument` assertable structurally — but adding a new test dependency is out of scope under the
  current rules.

## Private constructors of utility classes

- **Location:** the `private` no-arg constructors of `GzipUtils`, `UidUtils`, `LuceneUtils`,
  `GraphicsUtils`, `PositionAssembler` helpers, etc.
- **Why it is hard to test:** They exist only to prevent instantiation and contain no logic; reaching them
  needs reflection (a pattern the project avoids) and would assert nothing meaningful. Intentionally left
  uncovered.

---

# Untestable code — `model.geojson` package

The GeoJSON VO model is otherwise fully covered (119 passing tests); only the items below were left out.

## `GeoJsonVo.toString()` — the serialization-failure branch

- **Location:** `niord/src/main/java/org/niord/model/geojson/GeoJsonVo.java`, `toString()`
  (the `catch (Exception e) { return "Error " + e; }` branch).
- **Why it is hard to test:** The success path (returns the Jackson JSON of the VO) is covered, but the
  `catch` only fires if `mapper.writeValueAsString(this)` throws — which it never does for the
  well-formed coordinate-array VOs in this package. The `mapper` is a `private static final ObjectMapper`,
  so there is no seam to inject a failing mapper. Forcing a Jackson failure would require a deliberately
  broken/cyclic field that the VO classes don't have.
- **What would make it testable:** Make the `ObjectMapper` injectable (or extract the serialization into
  a helper that accepts a mapper), so a test could pass one that throws. Not worth a production change here.

## Abstract / annotation-only members

- **`GeoJsonVo.visitCoordinates(Consumer)`** (abstract) and **`GeometryVo`** (an empty abstract marker)
  have no behaviour of their own; they are fully exercised through the concrete subclasses' overrides.
- The `@Schema` (MicroProfile OpenAPI) and `@Xml*` (JAXB) annotations describe the OpenAPI/XML binding,
  not runtime logic; verifying them would test those frameworks rather than niord code, and is out of
  scope for a framework-neutral unit suite.

---

# Untestable code — `model.message` package (+ `org.niord.model` base)

The message model is otherwise fully covered (295 passing tests). Nothing in it is *meaningfully*
untestable; only these no-logic items were intentionally skipped:

- **`DataFilter`'s private constructors** (`DataFilter()` and the copy constructor `DataFilter(DataFilter)`)
  — exercised indirectly via `get()`/`fields()`/`lang()`/`user()`/`forComponent()`; reaching them directly
  needs reflection (a pattern the project avoids) and would assert nothing beyond field copying that the
  immutability tests already prove.
- **`@Schema` / `@Xml*` binding annotations** on the VOs and enums — OpenAPI/JAXB metadata, not runtime
  logic; out of scope for a framework-neutral unit suite (they would test those frameworks).
- The `IJsonSerializable` marker interface has no behaviour to test.

---

# Untestable code — `core.geojson` package

The pure converters are covered (114 tests across `FeatureName`, `GeoJsonUtils` statics,
`UTMCoordinateConversion`, `PlainTextConverter`; `JtsConverter` is covered by the pre-existing
`JtsConverterRoundTripTest`). The following were intentionally left out:

## GeoTools-backed buffer methods in `GeoJsonUtils`

- **Location:** `GeoJsonUtils.createBufferGeometry`, `createAffectedRadius`, `addAffectedRadius`,
  `addAtonAffectedRadius`.
- **Why it is hard to test (here):** They decode an `AUTO:42001` CRS and run JTS coordinate transforms via
  the **GeoTools** EPSG/CRS factory stack; the meaningful output (the buffered geometry) cannot be asserted
  deterministically without that infrastructure, and the property-setting logic is not separable from the
  buffer computation (exceptions are swallowed to `null`). These are already exercised with real assertions
  by the existing `org.niord.core.GeoJsonTest` (`createAffectedAreaTest`, `createAffectedAreaFromAtonTest`),
  so they are not re-covered in the pure-utility test file.
- **What would make it testable:** Split the deterministic property/unit-scaling logic (nm→×1852, km→×1000)
  from the GeoTools transform so the former can be asserted in isolation.

## JPA entities and persistence services (Tier-2)

- **Location:** `Feature` / `FeatureCollection` (`@Entity extends BaseEntity`), `FeatureService` /
  `GeometryFormatService` (`@Service`).
- **Why it is hard to test (here):** Persistence and bean behaviour need the running application + DB, so
  they belong in `*ITTest`s (`@SpringBootTest` + Testcontainers), not the framework-neutral unit tier. Their
  VO-conversion helpers are intertwined with entity/JPA state.
- **What would make it testable at the unit tier:** extracting the pure VO-mapping helpers into static,
  entity-free methods.

`model.publication` and `model.search` have no meaningfully untestable code — only the usual
annotation/binding metadata and (for the abstract `PagedSearchParamsVo`) a no-logic abstract base that is
exercised via a tiny concrete test subclass.

# Untestable code — services & REST integration tier

Behaviour that was deliberately left out of the service/REST `*ITTest`s added for the domain packages
(`source`, `chart`, `category`, `message`, `publication`, `contact`, `dictionary`, `settings`, `domain`,
`user`). No production code was changed; each item notes what would make it testable later.

## Asynchronous batch-job upload endpoints — covered

- **Location:** the `POST …/upload-*` endpoints on `SourceRestService`, `ChartRestService`,
  `CategoryRestService`, `PublicationCategoryRestService`, `DictionaryRestService`, `DomainRestService`,
  `SettingsRestService` (each delegates to `AbstractBatchableRestService.executeBatchJobFromUploadedFile`).
- **Covered:** each upload is sent over HTTP with a real file, its job is awaited to its end, and the imported
  rows are asserted: domains, dictionaries and settings by `AdminDataImportRoundTripITTest`, charts and
  sources by `ReferenceDataImportRoundTripITTest`, categories by `CategoryImportRoundTripITTest`, publication
  categories by `PublicationCategoryWriteEndpointsITTest`. `UploadRefusalsImportRoundTripITTest` sends every
  one of them as a role that may not, as a file that is not JSON and as an empty list, and
  `BatchJobExecutionITTest` runs the chart and source jobs through `BatchService` to their end.

## Keycloak-mutating and request-context paths

- **Location:** `DomainService.getDomains(true, true)` / `createDomain(…, true)` / `createDomainInKeycloak`
  and the `DomainRestService` keycloak endpoints.
- **Why it is hard to test:** the Keycloak paths talk to the live realm admin API and **mutate shared
  realm state** that `@TestTransaction` cannot roll back (and there is no client-delete endpoint to clean
  up), so a positive assertion would leak state across runs; failures are also swallowed (`log.error`) and
  so are invisible at the service boundary. Only the no-token (401) negatives are asserted.
- **What would make it testable:** a disposable Keycloak realm per test (or a teardown that deletes created
  clients).
- **Covered:** `DomainService.currentDomain()` — how the header's thread-local and a resolved ticket combine
  for a bearer caller and for a caller whose only credential is the ticket — is unit-tested by
  `DomainServiceTest`, and over HTTP by the ticket cases of `MessageRestServiceITTest`.

## `TicketService` cache internals and 60-second expiry

- **Location:** `TicketService.getCache()` (overridden to throw) and the `LIFESPAN`/60s ticket expiry.
- **Why it is hard to test:** the cache is reached only through `super.getCache()`; the public override
  throws, so there is no test seam to inspect it, and the one-minute expiry can only be observed by
  waiting (a slow timing test, disallowed) or by reflection. The create/resolve/one-time-use semantics
  *are* covered; only the timed eviction is not.
- **What would make it testable:** an injectable clock or a configurable (short) lifespan for tests.

## Scheduled jobs, JavaScript AtoN filters, and Message-graph-dependent logic

- **Location:** `CategoryService.recomputeTreeSortOrder()` (`@Scheduled` cron) and the AtoN-filter
  path (`resolveAtonCategories`/`matchesAton`, reached via `searchCategories` with AtoNs, and
  via `DictionaryRestService /matches-aton` / `CategoryRestService PUT /search`);
  `MessageSeriesService.updateMessageIdsFromMessageSeries(Message, …)` + the inner `FormatResolver`.
- **Why it is hard to test:** the scheduled method reshuffles `treeSortOrder` across the *whole* seeded
  tree (its effect cannot be isolated under `@TestTransaction` without mutating shared seed state); the
  AtoN paths require constructing `AtonNode` graphs that deterministically match seed-specific filter
  expressions (the filter itself is unit-tested in `AtonFilterTest`); and the message-id format logic
  needs a fully-populated `Message`
  (publish dates, series, country), but **no `Message` rows are seeded**. (A happy-path AtoN match *is*
  covered in `DictionaryRestServiceITTest` where the filter and AtoN are constructed together.)
- **What would make it testable:** seeded `Message` fixtures (or a builder) for the format logic; extracting
  the scheduled reshuffle to operate on an isolated subtree; and a small set of canonical AtoN fixtures
  with known filter outcomes.

## `SettingsService` system-property/env precedence

- **Location:** the `System.getProperty` / `System.getenv` precedence branches of `get()`/`peek()`. The
  rest of start-up is covered: the constructor's file read (`DefaultSettings.read`, then
  `withServerOverlay` for `${niord.home}/niord.json`) by `DefaultSettingsTest`, and `init()`'s
  `addMissing()` by `SettingsAddMissingITTest`.
- **Why it is hard to test:** the precedence branches depend on process-wide JVM system properties /
  OS environment variables, which cannot be mutated per-test without leaking across the shared test JVM
  (violating test independence). The DB/cache/typed-getter/`${token}`-expansion behaviour *is* covered.
- **What would make it testable:** a property/environment source abstraction that can be stubbed per
  test, or a dedicated boot test in an isolated JVM.

# Untestable code — `message` package

## Live message-status listener loops — `MailingListMessageListener` consumer thread / `TwitterMessageListener`

- **Location:** `niord/src/main/java/org/niord/core/mailinglist/MailingListMessageListener.java`
  (`onStart`/`run`/`onMessage`) and `niord/src/main/java/org/niord/core/promulgation/TwitterMessageListener.java`
  (`onMessage`).
- **Why the *live async path* is hard to test:** `MailingListMessageListener` subscribes from
  `ApplicationReadyEvent` on a single-threaded executor and blocks in `consumer.receive()`; each received
  message prompts a drain of the mailing lists' status-change outbox on that background thread. (The former
  unconditional `Thread.sleep(10000)` XA-workaround hack is **gone** — the broadcast is now deferred to after
  commit, see potential-errors.md JMS3.) The end-to-end path — publish → background thread wakes →
  `executeStatusChangeTrigger` side effects — can be asserted only by polling with Awaitility, on a committed
  mailing list, trigger, recipient and template; `MailingListDeliveryFaultITTest` does so for the mailing lists
  (below). `TwitterMessageListener` is now wired the same self-subscribing way (JMS1 fixed), so it too receives
  broadcasts, but its live path has no such test.
- **What IS tested instead:** the *dispatch logic* of both listeners is unit-tested directly and
  framework-neutrally — `MailingListMessageListenerTest` drains a recording outbox through `onMessage(...)`,
  `drainOutbox()` and `sweep()`, and calls `checkStatusChangeMailingListExecution(...)` with recording service
  doubles (every due outbox row taken in order and deleted after its triggers; a row whose triggers cannot be looked
  up kept for the next pass; each trigger executed once for the message UID; a failing trigger does not abort the
  rest), and `TwitterMessageListenerTest` calls `onMessage(...)` with a `FakeMapMessage` (only `PUBLISHED`
  promulgates; the UID is forwarded; malformed/non-`MapMessage` payloads are swallowed). The broker delivery
  these methods consume is exercised separately (`MessageStatusTopicITTest`, `MessageStatusPublishITTest`), and
  the mailing lists' outbox on the database (`MailingListStatusChangeOutbox`: the row written with the status
  change, mailed by a drain when the broker was down, gone with a rolled-back status change, held by its lease) by
  `MailingListDeliveryFaultITTest`, whose status-change cases also run the live path end to end with Awaitility.
- **What is still open:** migrating the ad-hoc consumer loops to Spring `@JmsListener` would remove the
  remaining hand-rolled executor plumbing. Since 2026-11-13 the three loops' subscribe, receive and reconnect
  logic is in one place, `org.niord.core.util.JmsTopicLoop`, and unit-tested (`MailingListDeliveryFaultTest`,
  `TwitterMessageListenerFaultTest`, `SecomMessageStatusListenerFaultTest`).

## REST `createMessage` / `updateMessage` happy-path — covered

- **Location:** `niord/src/main/java/org/niord/web/MessageRestService.java`, `createMessage(SystemMessageVo)`
  (`POST /rest/messages/message`) and `updateMessage(...)` (`PUT /rest/messages/message/{id}`).
- **Covered:** `MessageCreateUpdateHandshakeITTest` performs the editing handshake over HTTP: it uploads an
  attachment into a temporary edit folder, creates and updates the message, and asserts that the file was
  copied into the message's permanent folder, that the stored paths were rewritten, and that an edit folder or
  an attachment path outside its folder is refused. `MessageRestServiceITTest` keeps the authorization matrix
  (401 unauthenticated; 403/200 by role) for these endpoints.

# Untestable code — `core.user` package (User / UserService)

## `UserService` Keycloak-admin methods — covered against a stub of Keycloak's admin interface

- **Location:** `niord/src/main/java/org/niord/core/user/UserService.java` — `addKeycloakUser`,
  `updateKeycloakUser`, `deleteKeycloakUser`, `searchKeycloakUsers`, `getKeycloakGroups`,
  `getKeycloakUserGroups`, `joinKeycloakGroup`, `leaveKeycloakGroup` (all delegating to
  `KeycloakIntegrationService`).
- **Why it is hard to test:** these call the Keycloak **admin REST API** at a fixed `localhost:8080` (the
  realm/admin base URL is not the container's mapped port), which is unreachable from the test JVM — every call
  fails with `Connection refused`. The read methods catch the exception and return `Collections.emptyList()`,
  and the mutators rethrow; the group join and leave answer 403 (Keycloak refused), 404 (no such user or
  group) or 502 (Keycloak failed or could not be reached) with a sentence (KEYCLOAK2, 2026-11-14; before, they
  logged the failure and answered 204). So with the IT setting alone the only observable behaviour is
  **graceful degradation to an empty list** on a read's error; the create/update/delete/group happy paths need
  a reachable admin interface, which the sweep below supplies with a stub.
  `UserRestServiceITTest` asserts the reachable parts — the authorization gate (401/403/200) and the
  `updateKeycloakUser` id/`keycloakId` path-vs-body mismatch (400, thrown before any Keycloak call) — that
  `/kc-users` / `/kc-groups` return `200` + an empty list (the swallowed-error path), and, against an `HttpStub`
  that answers 403, 404 or 503 or a closed port, the group join's and leave's refusals.
- **Covered over HTTP:** the happy paths of all eight wrapper methods. The system group of `EndpointSweepITTest`
  (`SystemSweepKeycloak`) points `KeycloakIntegrationService`'s `authServerUrl` at a `KeycloakAdminStub` loaded
  with a user and a group, and drives the user search, the groups, a user's groups, join and leave, add (read
  back from Niord's user list), update and delete through `/rest/users/...`, then points it back.
- **Covered:** the underlying `KeycloakIntegrationService` admin HTTP client
  is now directly tested. `KeycloakIntegrationServiceHttpTest` (Tier-1) sets `authServerUrl` to an `HttpStub`
  serving canned `ClientRepresentation`/`GroupRepresentation`/`UserRepresentation` JSON and asserts the real
  Apache-HttpClient request building, the `Authorization: Bearer ...` header, the Jackson→VO mapping
  (`getKeycloakDomainIds`/`getKeycloakGroups`/`searchKeycloakUsers`/`addKeycloakUser`), and the negative paths
  (unauthenticated → throws + **0** HTTP requests sent; upstream `500` → throws "status = 500"). A fake
  `UserService` supplies the caller identity, so no container is needed. `KeycloakIntegrationServiceAdminStubTest`
  covers the client's other admin operations (domain creation with its roles, a user's groups, a group's
  roles, join and leave, user update and delete) against `KeycloakAdminStub`.

# Untestable code — infrastructure services (`repo` / `mail` / `schedule` / `integration`)

Recorded while adding the infra-service test suites. These are not bugs — they are paths that cannot be
exercised meaningfully without external systems, real binaries or the Spring scheduler.

## `NiordIntegrationExecutionService` — remote-server import (Apache HttpClient) — covered

- **Location:** `niord/src/main/java/org/niord/core/integration/NiordIntegrationExecutionService.java`
  (`processNiordIntegration`, `fetchMessages`, `importMessage`).
- **Covered:** the remote hop needs no `src/main` change, because `integration.getUrl()` is configurable.
  `NiordIntegrationExecutionServiceHttpTest` (Tier-1) points `fetchMessages(...)` at an
  `org.niord.test.http.HttpStub` serving a canned `MessageVo` JSON list, and asserts the parsed result, the
  `/rest/public/v1/messages?messageSeries=...` URL composition and the HTTP-error paths. The
  `REQUIRES_NEW` orchestration of `processNiordIntegration(...)` is driven against the same stub with committed
  fixtures: `NiordIntegrationImportITTest` asserts each create, cancel, re-import and coercion branch, the new
  UIDs and the base data; `NiordIntegrationExecutionGapITTest` asserts the branches together in one run, several
  source series onto one target, an unknown target series, and that a failed re-import leaves the old copy
  published; `NiordIntegrationFaultITTest` asserts an empty answer, a cancelled last warning, duplicate own
  copies and unresolvable references. `NiordIntegrationServiceITTest` covers the CRUD, the pending query and
  the next-execution scheduling.
- **Threading caveat.** `processNiordIntegration` deliberately carries **no** `@Async`: it runs synchronously
  on the caller thread inside its own `REQUIRES_NEW` transaction. Adding `@Async` would move the
  `REQUIRES_NEW` boundary onto another thread and change observable behaviour. The ITs above read the result
  as soon as the call returns, so they would fail if it became asynchronous. See also **INT4** in
  `docs/potential-errors.md` for the swallowed-failure and one-transaction-per-target-series issues in the same
  method.

## `@Scheduled` cron hooks — not driven deterministically by tests

> **Scope:** the job *bodies* are covered by direct invocation — `RepositoryService.cleanUpTempRoot()`
> (`RepositoryServiceITTest`), `ScheduledMailService.sendPendingMails()` / `deleteExpiredMails()`
> (`ScheduledMailServiceITTest`) and `NiordIntegrationService.processNextPendingNiordIntegration()`
> (`NiordIntegrationServiceITTest`). Only what is listed below remains untestable — see also the
> consolidated "Scheduled (`@Scheduled`) jobs" section.

- **Locations:** `RepositoryService.cleanUpTempRoot()` (`@Scheduled cron="50 22 * * * ?"`),
  `ScheduledMailService.sendPendingMails()` / `deleteExpiredMails()`,
  `NiordIntegrationService.processNextPendingNiordIntegration()`.
- **Why hard to test:** the cron *firing time* cannot be asserted — these are no-arg, package-private, void
  methods invoked only by the Spring scheduler, and driving that would require controlling the scheduler or
  the clock. There is also no serialization guard between the two mail writers, which since SCHED3 can run
  at the same time on the scheduler's four threads; no test pins that path.
- **What would make it testable:** an injectable `Clock` plus a scheduler-control seam that can fire a job on
  demand — both `src/main` changes.

## `ThumbnailService` — the vips subprocess

- **Location:** `niord/src/main/java/org/niord/core/repo/ThumbnailService.java`, `createThumbnailUsingVips`.
- **Covered:** the plain-Java scaling is unit-tested by `ThumbnailServiceCreateThumbnailTest` through
  `createThumbnailFile` (with `vipsCmd` empty): the exact output size, the aspect ratio, never scaling up, the
  verbatim copy of a small image, the cache by modification time, the pixel cap read from the header before
  decoding, and the refusals of an image that cannot be decoded. `RepositoryServiceITTest` asks for thumbnails
  through `RepositoryService.getThumbnail`, which runs `ThumbnailService.getThumbnail` on its thread pool.
- **Why the rest is hard to test:** the `vips` branch (taken only when the `vipsPath` setting is set) shells out
  to the `vips` binary, which no workflow of the repository installs.
- **What would make it testable:** a CI image with `libvips`.

## `RepositoryServletFilter` — the broken-pipe swallow is covered, but not through the real stack

- **Location:** `niord/src/main/java/org/niord/core/repo/RepositoryServletFilter.java`.
- **Covered:** `RepositoryServletFilterTest` makes a Mockito chain throw the exception and asserts that only an
  `UnhandledException` whose cause is an `IOException` with the message "Broken pipe" is swallowed, and that
  every other cause is rethrown.
- **What stays untested:** the branch cannot be reached through the real stack. Nothing in the MVC request
  pipeline throws `org.jboss.resteasy.spi.UnhandledException` any more (RESTEasy is on the classpath only for
  the SECOM servlet, which this filter does not front); see the `REPOFILTER1` note in
  `docs/potential-errors.md`.

# Untestable / runtime-bound code — `core/message` services

The message-service test round (`MessageParamFilterService`, `CommentService`, `MessageMailService`,
`EditorFieldsService`, `ScheduledMessageService`, `MessageExportService`, `MessageScriptFilter*`, and the
message batch import processors) covered the business logic of all seven services directly. What remains below
is genuinely batch-runtime- or scheduler-bound. The pure logic was reached by instantiating the handlers
directly (see the note on the processors). No production code was changed.

## `MessageService.getMostRecentlyEditedMessages(...)` / `getMostRecentlyEditedDrafts(...)` — the *populated* path needs an authenticated current user

- **Location:** `niord/src/main/java/org/niord/core/message/MessageService.java`,
  `getMostRecentlyEditedMessages(int, Status...)` and `getMostRecentlyEditedDrafts(int)`.
- **Why it is only partially testable:** both methods start with
  `User user = userService.currentUser(); Domain domain = domainService.currentDomain();` and short-circuit to
  `Collections.emptyList()` when either is null. They then run the `MessageHistory.findRecentChangesByUser`
  named query keyed on `:user` (the current user). In a service-layer IT there is **no authenticated user** —
  `currentUser()` finds no authentication in the `SecurityContextHolder` and returns `null` (the same
  graceful-null behaviour relied on by `MessageTagServiceITTest`) — so the query is never reached and the
  populated result cannot be asserted. A test *can* populate the holder with a built `Jwt` in a
  `JwtAuthenticationToken` (as `UserServiceCurrentUserITTest` does), which would make this reachable. The history rows
  themselves are also written with a `null` user (`MessageService.saveHistory` stamps
  `hist.setUser(userService.currentUser())`), so even if the query ran it would match nothing.
- **What is covered instead:** `MessageServiceLifecycleITTest` pins the three documented empty branches
  (no current user even with a domain installed via `DomainService.setDomainForCurrentThread`; no current
  domain; and the `getMostRecentlyEditedDrafts` delegation) against real, edited messages — proving the guard,
  not just a trivially-empty database.
- **What would make the populated path testable:** the same `CurrentUserProvider` seam noted for
  `UserService.currentUser()` (a test-populatable current user), or driving these queries through a REST
  endpoint under a real Keycloak token so the history rows and the query both carry a non-null user. Until
  then the user-keyed result set is best exercised through the authenticated REST suites.

## `MessageService.searchPagedMessageIds(...)` — the geometry/extent and free-text (Lucene) filter branches are not exercised in-transaction

- **Location:** `niord/src/main/java/org/niord/core/message/MessageSearchService.java` (moved here from
  `MessageService` in the Spring port), `searchPagedMessageIds(...)` — specifically the
  `param.getExtent() != null` (spatial) block (`SpatialIntersectsPredicate` over `parts → geometry →
  features`) and the `param.requiresLuceneSearch()` block (delegating to `messageLuceneIndex.searchIndex(...)`).
  The **extent** branch is in-transaction testable (PostGIS `SpatialIntersectsPredicate` over flushed rows
  — same pattern as `getIntersectingAreas`); only the **free-text** sub-branch stays genuinely hard, because
  the Lucene index only sees committed rows.
- **Why they are skipped:** `MessageSearchFilterMatrixITTest` (like its sibling `MessageSearchServiceITTest`)
  searches **inside** the `@TestTransaction`, against flushed-but-uncommitted rows, so that each test sees
  exactly the data it created and nothing else. Neither of these two branches can work that way:
  - **Free-text / Lucene** (`requiresLuceneSearch()` true when `query` is non-blank) delegates to the
    filesystem Lucene index, which `MessageLuceneIndex` rebuilds by re-querying the DB in its **own**
    transaction — it therefore only ever sees *committed* rows, never the in-transaction fixtures. (This is
    covered separately, with committed data and explicit cleanup, by `MessageLuceneIndexITTest`.)
  - **Geometry / extent** relies on the PostGIS `SpatialIntersectsPredicate` and on message-part
    `FeatureCollection` geometries; building valid spatial fixtures and asserting intersect/disjoint/no-pos
    behaviour against the container's spatial functions is a substantial spatial-fixture effort (the same
    class of difficulty already noted for `AreaService.getIntersectingAreas`), out of scope for the
    in-transaction filter-matrix suite.
- **What would make them testable here:** for free text, the committed-data + new-transaction
  cleanup pattern (as in `MessageLuceneIndexITTest`) plus a forced `recreateIndex()`; for geometry, a small set
  of purpose-built JTS extents and message-part geometries with known intersect/disjoint relationships,
  asserted through the spatial predicate — deferred as a dedicated spatial-search effort.

## `ScheduledMessageService` — the `@Scheduled` cron triggers (timing only)

- **Location:** `niord/src/main/java/org/niord/core/message/ScheduledMessageService.java`,
  `checkForExpirablePublishedMessages` (`@Scheduled(cron = "${niord.schedule.auto-expire-cron:28 * * * * ?}")`)
  and `checkForPublishableMessages` (`@Scheduled(cron = "${niord.schedule.auto-publish-cron:7 * * * * ?}")`).
- **What is covered:** each method is the call of a package-private seam (`publishDueMessages`,
  `expireDueMessages`) with every active domain, so `ScheduledMessageServiceITTest` calls the seams
  *directly* with a domain of its own and committed fixtures, and asserts the auto status transitions
  (VERIFIED→PUBLISHED on a past publish-from date, PUBLISHED→EXPIRED on a past publish-to date, and the no-op
  cases); `DeliveryFaultInjectionITTest` asserts that one failing message leaves the others changed. Only the cron *firing/timing* itself is
  not asserted (the Spring scheduler is not driven), which is acceptable — the transition logic is the value.
  The cron *strings* are pinned by `ScheduledCronExpressionTest` (property name plus shipped default), and the
  IT suite sets both properties to `-` (the `@TestPropertySource` on `ITTest`) so neither sweep can fire
  behind a test's back.

## Message batch import readers & writers — covered but for an archive's attachments

- **Location:** `niord/src/main/java/org/niord/core/message/batch/{BatchMessageImportReader,
  BatchMsgArchiveImportReader,BatchMessageImportWriter,BatchMsgArchiveImportWriter}.java`.
- **Covered:** the archive import runs end to end: `MessageArchiveImportRoundTripITTest` exports messages
  (`GET /rest/message-io/export.zip`), imports the archive through the Messages page's upload
  (`POST /rest/message-io/import`, job `msg-archive-import`) and from a batch set, waits for the job and
  asserts the imported messages field by field, with each of the import dialog's four choices. The parts are
  also tested on their own: the readers by `BatchMessageImportReaderTest` (`open()` with a Mockito
  `JobContext` and `BatchService` pointed at a JSON file), `BatchMessageImportReaderWriterITTest`,
  `BatchMsgArchiveImportReaderTest` and `BatchMsgArchiveImportReaderGapITTest`; the writers by
  `BatchMessageImportReaderWriterITTest` and `BatchMsgArchiveImportWriterGapITTest`; the processors by
  `BatchMessageImportProcessorITTest` and `BatchMsgArchiveImportProcessorITTest`; the tag assignment by
  `MessageImportTagAssignmentTest`.
- **What stays untested:** the messages of those archives carry no attachments, and
  `BatchMsgArchiveImportWriterGapITTest` gives its items a blank edit folder, so no test sees the archive
  writer copy an archive's attachments into the message's folder
  (`messageService.updateMessageFromTempRepoFolder`; the same method is covered for the REST save by
  `MessageCreateUpdateHandshakeITTest`). The JSON `message-import` job is never started by the batch runtime
  in a test; its reader, processor and writer are tested as above.
- **What would make the rest testable:** a message with an attachment in the round trip's archive, and an
  assertion that the file arrives in the imported message's folder.

---

# Untestable code — `core.batch` package

These areas of the batch subsystem were first left out because they need real asynchronous JBeret batch
jobs, the Spring scheduler or a live JSR-352 `JobContext`. The pure logic (`BatchData` path layout,
`BatchExecutionVo`/`BatchInstanceVo` flag rules) is covered by framework-neutral unit tests, and the
persistence / authorization behaviour by integration tests. Real jobs are now run to their end and awaited by
`BatchJobExecutionITTest` (through `BatchService`, polling the `JobOperator`) and by the import round trips
(`AdminDataImportRoundTripITTest`, `MessageArchiveImportRoundTripITTest`,
`LegacyPublicationImportRoundTripITTest`: through the upload endpoints, polling `GET /rest/batch/{job}/instances`
and reading the job's log files); each entry below says what is still left. No production code was changed.

## `BatchService.startBatchJob*` / `stopExecution` / `restartExecution` / `abandonExecution` — launch a real job operator

- **Location:** `niord/src/main/java/org/niord/core/batch/BatchService.java`
  (`startBatchJob`, `startBatchJobWithJsonData`, `startBatchJobWithDataFile`,
  `stopExecution`, `restartExecution`, `abandonExecution`, `getJobInstances`, `getStatus`).
- **Why it is hard to test:** these delegate to the injected JSR-352 `JobOperator`, which starts/stops/restarts
  *real* chunk-based batch jobs asynchronously. A successful `start*` would schedule a genuine job execution
  (writing log files, mutating data, running on a background thread) and `getJobInstances`/`getStatus` read
  live JBeret runtime state; for an unknown job name the operator throws `NoSuchJobException`. Asserting these
  deterministically would require a fully wired, named batch job and control over the async runtime, which is
  out of scope for a behaviour test. The *non-launch* surface (the `BatchData` persistence, path computation,
  data-file round-trip, log-file error paths, and the REST authorization boundaries) is tested instead.
- **What would make it testable:** a seam over the job operator (or a tiny no-op test batch job registered in
  `META-INF/batch-jobs`) plus a way to await execution completion deterministically.
- **Driven since the owner's bootstrap ruling of 2026-10-24 (P7-bs):** `restartExecution`'s two cases.
  `BatchRestServiceITTest.aBootstrapJobsRestartIsRefused` starts a `domain-import` that fails, carrying the
  bootstrap run's token, through `startBatchJobWithDataFile`, waits for it to end (`BatchJobs.awaitCompletion`) and
  asserts that the restart answers 409 and starts no execution; `aSysadminImportsRestartIsAccepted` does the same
  without the token and asserts that the restart is accepted with a new execution, which it waits for. The rest of
  this entry stays as written.
- **Driven since the error hunt's fix stage (2026-11-13):** the refusals of `stopExecution`, `restartExecution`
  and `abandonExecution`. `BatchExecutionActionRefusalsITTest` starts `script-executor` jobs through
  `startBatchJobWithDataFile`, one of them a script that waits until the test sets a system property, and asserts
  404 for an unknown execution, 409 for stop and restart of a completed one and for restart and abandon of a
  running one (which still completes), and that abandon of a completed one and stop of a running one still work.
- **Covered:** the start of a real job. `BatchJobExecutionITTest` starts `chart-import` and `source-import` through
  `startBatchJobWithJsonData`, waits for them (`BatchJobs.awaitCompletion`) and asserts COMPLETED with the rows
  written, and FAILED for a data file the reader cannot parse; the import round trips start their jobs through
  the upload endpoints, which call `startBatchJobWithDataFile` (`AbstractBatchableRestService`), and wait for
  them through `GET /rest/batch/{job}/instances`, which is `getJobInstances`.

## `AbstractItemHandler` / `BatchJobListener` — covered by running real jobs

- **Location:** `niord/src/main/java/org/niord/core/batch/AbstractItemHandler.java` and
  `BatchJobListener.java`.
- **Covered:** both are driven by the batch runtime in the ITs that run real jobs. `BatchJobExecutionITTest`
  asserts the `BatchData` row `BatchJobListener` persists for a started job (job name and data-file name). The
  import round trips read the log files `AbstractItemHandler.getLog()` writes into the batch-job folder, through
  `GET /rest/batch/instance/{id}/logs`, and assert lines in them (`AdminDataImportRoundTripITTest`: "Ignoring
  existing domain"; `MessageArchiveImportRoundTripITTest`: "Skipping existing message").
- **What stays untested:** the lifecycle and the listener callbacks called directly, outside a running job;
  that would need a constructor/setter seam for `JobContext` and `BatchData`.

## `BatchSetService` — item dispatch on a `java.util.Timer`

- **Location:** `niord/src/main/java/org/niord/core/batch/BatchSetService.java`
  (`executeBatchSet`/`executeBatchSetItem`). The `niord.batch-set` start path (`init()`) was deleted in the
  code/data/configuration plan's C4.1; the bootstrap runner, which awaits each job's execution, replaced it and
  is tested (`BootstrapITTest`). No folder watcher starts a batch set.
- **Why it is hard to test:** once a batch-set manifest contains items, each is dispatched after `delay` ms on a
  fresh `java.util.Timer`, which then calls `BatchService.startBatchJobWithDataFile` — i.e. launches a real job
  on a background thread, with no handle to await or assert. The synchronous paths are asserted in
  `BatchSetServiceITTest`: the manifest parsing (empty / missing / invalid `batch-set.json`), the allow-list
  (`BATCH_SET_JOBS`: a manifest naming the script-resource, dictionary or settings import or `script-executor`
  is refused whole and schedules nothing) and the scheduling line of an allowed item; `BatchRestActionITTest`
  asserts the refusal through `/execute-batch-set`, and that no `dictionary-import` job starts.
- **Covered:** the `Timer` dispatch of an allowed item. `MessageArchiveImportRoundTripITTest` posts a batch set
  naming `msg-archive-import` with a `delay` of 0 to `/rest/batch/execute-batch-set`, asserts the answer names
  the scheduled job, and waits for the job by its data file's name through `GET /rest/batch/{job}/instances`;
  the imported messages are then compared field by field.
- **What stays untested:** a `delay` above 0 and a set of several items, whose order of start is the timers'.

## `BatchService.cleanUpExpiredBatchJobFiles` — `@Scheduled` cron job

> **Scope:** the method *is* covered by `BatchServiceITTest` (expired execution files deleted and now-empty dirs
> pruned, recent files kept); only *when* the scheduler calls it is not asserted.

- **Location:** `niord/src/main/java/org/niord/core/batch/BatchService.java`, the one `@Scheduled` method.
- **Why it is hard to test:** a package-private, no-arg, void method invoked only by the Spring scheduler on a
  cron trigger; it walks `[jobName]/execution` folders deleting files older than `batchFileExpiryDays` and prunes
  empty directories. The cron trigger itself is not asserted.
- **What would make it testable:** an injectable `Clock` and a scheduler-control seam.

# Untestable / hard-to-test — `core.promulgation` package

## `TwitterPromulgationService` — Twitter4J posting path — covered but for the media upload

- **Location:** `niord/src/main/java/org/niord/core/promulgation/TwitterPromulgationService.java`
  (`checkPromulgateMessage`, the private `promulgateMessage`/tweet-building and media-upload helpers).
- **Covered:** the `@Async` posting path to the wire. `TwitterApiStub` starts an `HttpStub` and points the
  `twitter4j.restBaseURL` system property at it, so the real client posts there:
  `TwitterPromulgationHttpITTest` publishes a committed message with valid settings and asserts a `POST` to a
  `statuses/update` path carrying the tweet text, and `TwitterPromulgationGenerationITTest` asserts that nothing
  is posted for a message that is not `PUBLISHED`, for invalid credentials and without settings. The tweet's
  composition from `DEFAULT_TWEET_FORMAT` and from a stored format is unit-tested in
  `TwitterPromulgationServiceTest` (`computeTweet`). Also covered: the *routing* contract
  (`TwitterMessageListenerTest`), the `TwitterSettings` entity incl. the secret-suppression in `toVo()`
  (`TwitterSettingsTest`), and the REST auth boundary on `/promulgation/twitter-settings`
  (`PromulgationFormatRestServiceITTest`).
- **What stays untested:** the media upload of a message with a location (see "Additional untestable /
  deliberately-not-tested areas" below), and the cut of a tweet at `MAX_TWEET_LENGTH` (140 characters).

## `PromulgationManager` — the plug-in registry (`instantiatePromulgationService`)

- **Location:** `niord/src/main/java/org/niord/core/promulgation/PromulgationManager.java`.
- **Why it is hard to test (here):** the registry is populated in `@PostConstruct` from the injected
  `List<BasePromulgationService>` of every promulgation-service bean, and `instantiatePromulgationService`
  resolves a registered service *class* back to its bean. Both need a running application context with the
  real set of promulgation beans — there is nothing meaningful to assert about them in isolation.
- **What IS covered instead:** `promulgationServices()` (the registry listing) is asserted end-to-end via
  `PromulgationRestServiceITTest` (the `/promulgation-services/all` endpoint returns navtex/safetynet/twitter),
  which is the observable contract.
- **Known fragility:** the registry is populated eagerly in `@PostConstruct` rather than by each service
  self-registering on `ApplicationReadyEvent`, because the event-based variant did not fire reliably for every
  inherited bean method under `@SpringBootTest`. A service that is a bean but not assignable to
  `BasePromulgationService` would silently never register.

## `BatchPromulgationTypeImportReader` / `BatchPromulgationTypeImportWriter` — JBeret item handlers — covered

- **Location:** `niord/src/main/java/org/niord/core/promulgation/batch/`.
- **Covered:** the reader→processor→writer chain runs in a real `promulgation-type-import` job.
  `AdminDataImportRoundTripITTest.aPromulgationTypeExportedDeletedAndImportedComesBackAndAnImportOverItOverwritesIt`
  exports a promulgation type, deletes it, uploads the export to `/rest/promulgations/upload-promulgation-types`,
  waits for the job to complete and compares the type with the export; it then imports over it, unchanged and
  changed (priority, domains, message types, script resources), and asserts the file's values. The processor
  alone is also unit-tested (`BatchPromulgationTypeImportProcessorTest`).

---

# Untestable code — `core.mailinglist` package

## `MailingListSchedulerService.checkExecuteScheduledTriggers` — `@Scheduled` cron

- **Location:** `niord/src/main/java/org/niord/core/mailinglist/MailingListSchedulerService.java`.
- **Now driven (since 2026-11-13):** the method calls `executeScheduledTrigger` alone, which computes the next
  execution in the same `REQUIRES_NEW` transaction as the mails, and postpones a trigger whose run fails with
  `postponeFailedScheduledTrigger`. `MailingListSchedulerServiceITTest` and `MailingListDeliveryFaultITTest`
  drive it with committed pending triggers whose `nextScheduledExecution` is in the past. Only the cron firing
  time stays the scheduler's (residue item 1 below).

## Real seeded Freemarker/JavaScript template rendering — the base product's templates are covered, the sample's `audio-broadcast.ftl` and `fetch-firing-exercises.js` are not

- **Location:** referenced by the fixture's `MailingListTrigger.scriptResourcePaths` (the sample's mailing
  lists). Since C4b the fixture holds them as `ScriptResource` rows: the base product's mailing-list templates
  from its bootstrap, and `audio-broadcast.ftl` and `fetch-firing-exercises.js`, the sample's copies of DK's,
  from the sample.
- **Covered:** every mailing-list template of the base product's bootstrap (`publish-navwarn.ftl`,
  `cancel-navwarn.ftl`, `cancel-navtex.ftl`, `cancel-safetynet.ftl`, `active-navwarns.ftl`, `publish-navtex.ftl`,
  and `publish-safetynet.ftl` after `publish-safetynet.js`) is rendered for a sample message through the
  sysadmin's trigger test by `BootstrapReportRenderITTest`, which asserts one mail per contact and that nothing
  is left unresolved in it (no `null`, `${`, FreeMarker error or `???`), and reads named parts of the NAVTEX,
  SafetyNET and NAVWARN mails.
- **What stays untested:** the sample's `audio-broadcast.ftl` and `fetch-firing-exercises.js`, which no test
  renders or runs.

## `MailingListRestService.testExecuteTrigger` (POST `/test-execute-trigger`) happy path — covered

- **Location:** `niord/src/main/java/org/niord/web/MailingListRestService.java`.
- **Covered:** the 200 path. `BootstrapReportRenderITTest` creates a mailing list with one contact, posts a
  `MailingListVo` with a trigger and its script resources to `/test-execute-trigger` with a `messageId`, and
  asserts the answer is 200 with one rendered mail for the contact, for status-change and scheduled triggers.
  The authorization boundary (401 anonymous / 403 non-sysadmin) is asserted in `MailingListRestServiceITTest`.

# Untestable code — web REST services (`web`, `web/aton`, `web/map`, `web/wms`)

These accompany the new `*ITTest` suite for the previously-untested web REST endpoints (Publication,
MessageComment/Mail/Export/Report/ParamFilter, AtoN, AtoN icon/tile, message-map-image, WMS proxy, Monitor,
TemplateExecution, ScriptResource). The endpoints' authorization, validation and data contracts are
asserted; the paths below are the parts that could not be reasonably exercised in-process.

## PDF generation — `MessageReportRestService` `/message/{id}.pdf` and `/report.pdf`

- **Location:** `niord/src/main/java/org/niord/web/MessageReportRestService.java`.
- **Why it is hard to test:** both endpoints stream a PDF produced by `FmTemplateService` rendering
  `message-list-pdf.ftl` and then an HTML-to-PDF conversion. A meaningful assertion on a rendered message
  would need a committed message fixture, the full Freemarker dictionary/template stack, and the PDF renderer
  toolchain; the fixture's 34 sample messages are a start, not a corpus written for these templates.
- **Covered:** what the request decides — the endpoints are `@PermitAll`, but only a caller the backend knows
  gets the `debug=true` HTML and the `param:` values, an anonymous list is rendered in the `public` report
  and one message in the standard report, each refused for naming any other, the page layout is
  allow-listed, the search criteria reach the template escaped, the zone is resolved before the body
  streams, an unknown message is a 404, and only `niord.reports.anonymous-pdf-concurrency` anonymous PDFs
  are made at once (a further one is answered 503, and the permit is back once the document is made) — by
  `MessageReportRestServiceTest` with the renderer stood in for, and over HTTP by
  `MessageReportRestServiceITTest`: on empty lists (anonymous `%PDF`, signed-in HTML,
  the `public` report, the 403 for another one) and on one public message the test commits and removes
  (anonymous `%PDF`, the 403 for another report, a signed-in caller's named report), and on a draft in a
  PUBLIC tag (a 403 for an anonymous caller, the `%PDF` for the user role of another domain). The shipped list
  template is rendered as text, in two zones and with and without its front page, by
  `FmTemplateBuilderTimeZoneTest`.
- **What would make the rest testable:** a committed message-list fixture, so a list of published messages
  can be rendered through the endpoint, and an assertion on what a rendered document says rather than that
  it is a PDF; the `core.script` render harness such an assertion would reuse already exists
  (`HtmlToPdfRendererTest` / `FmTemplateServiceITTest`).

## Outbound WMS fetch — `WmsProxyServlet.doGet`

- **Location:** `niord/src/main/java/org/niord/web/wms/WmsProxyServlet.java`.
- **Why it is hard to test:** the success path opens a `URLConnection` to the configured `wmsProvider`,
  reads a tile image and re-encodes it as PNG. No WMS provider is configured in the test app, so the servlet
  takes its blank-image fallback. `WmsProxyServletITTest` asserts the auth gate (anonymous → 403) and the
  blank-image fallback (authenticated → 302 to `/img/blank.png`); the actual proxying + colour-masking is
  untestable without an outbound HTTP stub.
- **What would make it testable:** the missing outbound-HTTP stub fixture (WireMock/MockWebServer) plus a
  `System.setProperty("wmsProvider", stubUrl)` test-resource (the same `@Setting`-override trick the mail
  wire tests use), then assert the returned PNG.
- **Covered:** **closed.** The new `org.niord.test.http.HttpStub` (JDK
  `HttpServer`, no new dependency) is exactly that fixture. `WmsProxyHttpITTest` + `WmsProviderStubResource`
  set `wmsProvider`/`wmsPassword`/`wmsLayers` at a PNG-serving stub and assert the authenticated request
  returns `200` with a decodable image (not the `302` fallback) and that the proxy appends `TOKEN=`/`LAYERS=`
  to the upstream request. (The colour-masking `transformWhiteToTransparent` is dead — commented out at the
  call site — so it is intentionally not exercised. The omitted response `Content-Type` is filed as WEB5 in
  potential-errors.md.)

## Auto-generated map image — `MessageMapImageRestService` `GET /{uid}.png` (happy path)

- **Location:** `niord/src/main/java/org/niord/web/map/MessageMapImageRestService.java` +
  `MessageMapImageGenerator`.
- **Why it is hard to test:** for a message with geometry the generator fetches OpenStreetMap tiles over
  HTTP (`TileFetcher`), paints the message's portrayal on them and writes the image into the repository.
  `MessageMapImageRestServiceITTest` asserts the reachable contracts — unknown UID → 404, the
  `@RolesAllowed(EDITOR)` boundaries on PUT/POST, the temp-repo-path validation and the data-URI prefix check
  (→ 400).
- **Covered:** the making of the image is closed at unit level. `MessageMapImagePipelineTest` (Tier-1) drives
  `MessageMapImageGenerator.generate` end to end against an `HttpStub` tile server: the tiles and zoom level
  requested, the marks painted (dashed pen, no area fill, the tag sprite at the interior point, the buffer
  fill, the point symbols), the PNG written whole with the message's change time, and no file when there is
  nothing to draw or no tile to draw on. `MapImagePlannerTest` checks the marks against the web maps' golden
  draw lists, `TileFetcherTest` the tile fetch and its caches.
- **Covered:** the **endpoint** half is **closed.** `MessageMapImageRestGapITTest` drives `GET /{uid}.png` over
  committed messages with geometry and real repository files, with a stand-in for the generator on the
  running `MapImageRegenerator`: the current image served as it is, a superseded one served with a short expiry
  while one background generation draws the current one, the generation while the request waits, one
  generation for concurrent requests, the deferred deletion of superseded images, and the placeholder when the
  generator fails or has nothing to draw.
- **Covered:** the **rest** is **closed end to end.** `MessageMapImageEndToEndITTest` drives the whole chain with
  the real renderer: the endpoint, the snapshot, the regenerator, the generator, the tile fetch and its caches,
  the painter and the repository file, against an `HttpStub` tile server that the `osmUrl` system property points
  at (the tile settings are read live, and a system property of the same name wins, so the shared context is
  left as it was). A point and an area message are drawn in the portrayal's magenta at the image's centre, into
  the file named with the running portrayal version and stamped with the message's change time; the tile server
  is asked for one to four tiles, each with the `Niord/` User-Agent naming `mapTileContact`, and the tiles land
  in `mapTileCachePath`; a second request, and a second message at the same place, ask for no tile; a tile server
  answering 500 gives the placeholder and no file; and a message PDF with map thumbnails, whose renderer loads
  the image from the server under test (`baseUri`), embeds the drawn image and carries the OpenStreetMap credit.

## On-disk tile caching + ETag-304 paths — `AtonTileRestService`

- **Location:** `niord/src/main/java/org/niord/web/aton/AtonTileRestService.java`.
- **Why it is hard to test:** the `AtonNode` table is empty in the seed, so every tile is a blank PNG and
  the "tile exists in repo + ETag precondition → 304" branch and the real-AtoN raster-generation branch are
  never reached. The tests assert the blank-tile contract (200, `image/png`, non-empty bytes) and the
  refusal of tiles outside the served zoom/column/row range (400, nothing cached or written); the
  cached/304 branch and real raster output are not exercised. `AtonIconRestService.getAtonOverviewIcon`
  no longer caches on disk — it answers the rendered PNG, which its tests decode — so it is not part of
  this entry.
- **What would make it testable:** committed AtoN fixtures with geometry inside the requested tile bounds +
  an ETag round-trip helper (`If-None-Match`).

## Admin write happy-paths covered at the service tier — Publication / MessageExport / ScriptResource batch imports

- **Location:** `PublicationRestService` (create/update/release with the temp-repo handshake),
  `MessageExportRestService.importMessages`, `ScriptResourceRestService.importScriptResources`,
  `TemplateExecutionRestService.applyTemplate`.
- **Why it is hard to test over HTTP:** the create/update/release publication flows require the temp-repo
  folder handshake (a `SystemPublicationVo` with an `editRepoPath` plus copied resources); the import
  endpoints launch JBeret batch jobs from an uploaded archive. The new REST ITs assert the authorization
  boundaries (401/403), the mismatched-id 400s and the import **validation guard** (an archive lacking
  `messages.json` is rejected with the error returned as text). The underlying persistence/state-machine
  behavior is covered by `PublicationServiceITTest`, `BatchServiceITTest` and the batch import-processor coverage.
- **Covered since:** the two imports over HTTP. `MessageArchiveImportRoundTripITTest` uploads message archives to
  `POST /rest/message-io/import` (`importMessages`), one and two in a request, waits for each `msg-archive-import`
  job to complete and asserts the two messages each archive brings;
  `AdminDataImportRoundTripITTest.scriptResourcesExportedDeletedAndImportedComeBack` uploads an export of script
  resources to `/upload-script-resources` (`importScriptResources`) and compares what comes back with it.
  `applyTemplate` is driven past the USER boundary by `TemplateExecutionRestServicePositiveITTest` (the 400s, the
  403 and 404 of a category, and the returned message of a valid body).
- **What would make the rest testable:** a REST-tier committed temp-repo fixture helper for the publication flows,
  then assert the persisted publication.

---

# Untestable / hard-to-test code — `core.script` package (rendering & scripting)

The bulk of the scripting subsystem turned out to be testable: the 13 Freemarker directives are driven as
framework-neutral unit tests through a real Freemarker `Configuration`, and the service/persistence/
full-stack-rendering paths are covered by integration tests. The items below are the paths that remain hard
to assert meaningfully without more fixtures or production seams.

## `AreaLineageDirective` — the real area-title computation

- **Location:** `niord/src/main/java/org/niord/core/script/directive/AreaLineageDirective.java`.
- **Why it is hard to test:** the meaningful path needs `CdiUtils.getBean(NiordApp/MessageService)` (a static
  lookup into the Spring `ApplicationContext`), the DB (it resolves persisted `Area`s via
  `messageService.persistedList` and calls `Message.computeAreaTitle`), and a current domain/locale. In a
  Tier-1 unit test only two branches are reachable: the missing-`message` validation (→ `TemplateException`)
  and the no-context robustness fallback (→ empty output). The NAVTEX post-processing (strip "the",
  `maxLineLength(40)`, upper-case) is entangled with the context/DB lookups and is not asserted on a real
  area title.
- **What would make it testable:** an integration test that seeds a message + areas + a current domain and
  renders a template invoking `<@areaLineage message=msg format="navtex"/>`, asserting the exact title.
  `FmTemplateServiceITTest` exercises the rendering pipeline but does not assert the area title.

## `CallSignDirective` / `LightCharacterDirective` — the "verbose" translation

- **Location:** the verbose branches calling `CdiUtils.getBean(LightCharacterService).formatTelephonyCode` /
  `formatLightCharacter`.
- **Why it is hard to test in a unit test:** they require the container-managed `LightCharacterService`. The
  Tier-1 tests pin only the no-context fallback (the catch re-emits the raw input). The actual telephony /
  light-character translation (e.g. `"OUXR2"` → `"Oscar Uniform X-ray Romeo Two"`,
  `"Gp.L.Fl(2+1)G. 5s"` → a sentence) is not asserted here.
- **Covered in an IT since wave 3b:** `AtonDirectiveMissingTemplateITTest` renders both directives with
  `format="verbose"` inside the application context and asserts the translated strings ("Fl(2) W 10s" → "2 white
  flashes every 10 seconds", "OXYZ" → "Oscar-Xray-Yankee-Zulu"), then the raw fallback and its WARN with the two
  template rows renamed.

## `MultiResourceBundleModel.isEmpty()/size()/keySet()`

- **Location:** `niord/src/main/java/org/niord/core/script/directive/MultiResourceBundleModel.java`.
- **Why it is hard to test directly:** these delegate to `getResourceBundle()`, which calls
  `freemarker.core.Environment.getCurrentEnvironment()` — non-null only **during** template processing. They
  cannot be invoked from a plain assertion outside a render. The locale-switching, method-call (`text(key)`)
  and hash-access (`text.key`) behaviours ARE covered via rendered templates; the collection-introspection
  methods are not separately exercised.

## PDF encryption — correctness not asserted

- **Location:** `HtmlToPdfRenderer.render()` / `FmTemplateService` PDF path with an encryption password.
- **Why it is hard to test:** there is no PDF parser on the test classpath, so the tests assert only that an
  encrypted render still produces valid `%PDF` bytes — not that the document is actually encrypted or that
  the password decrypts it. Verifying real encryption would need a PDF library (e.g. PDFBox) able to detect
  the `/Encrypt` dictionary and attempt a password open.

---

# AtoN subsystem (`core/aton`, `web/aton`)

Recorded while covering the AtoN subsystem.

## `AtonDefaultsService.init()` — async 3-second `Timer` load on startup

- **Location:** `niord/src/main/java/org/niord/core/aton/AtonDefaultsService.java`.
- **Why hard to test as wired:** the node-type defaults are loaded by
  `new java.util.Timer().schedule(generateDefaults, 3000)` fired from a `StartupEvent` observer, so the
  data is not deterministically available at any known time and the observer/timer wiring cannot be
  unit-tested directly (and an IT would race the 3-second delay).
- **What IS tested instead:** `AtonDefaultsServiceTest` drives the private `generateDefaults()`
  synchronously (after reflectively setting the `@ConfigProperty` fields) and then exercises all query
  logic — `getNodeTypeNames`, `mergeAtonWithNodeTypes`, `computeKeysForAton`, `getValuesForAtonAndKey`.
  The REST endpoints (`/atons/defaults/*`) are additionally smoke-tested in `AtonRestServiceITTest` with a
  short await loop to tolerate the startup delay.
- **What would make it cleaner:** a public, synchronous "ensure loaded" seam (or constructor-time load)
  instead of the fire-and-forget timer.

## `AtonBlankTileCache` — Infinispan `BaseCache` needs a running container

- **Location:** `niord/src/main/java/org/niord/web/aton/AtonBlankTileCache.java`.
- **Why hard to unit-test:** it extends the abstract Infinispan `BaseCache`, whose `@PostConstruct`
  starts a cache manager; there is no plain-object seam. It is exercised indirectly by
  `AtonImageRestServiceITTest` (the blank-tile path of `AtonTileRestService` puts/reads the cache), the
  same approach used for `MailAttachmentCache`.

## `TemplateExecutionService` — per-promulgation-type script loop has no seeded fixture

- **Location:** `niord/src/main/java/org/niord/core/category/TemplateExecutionService.java`, the
  `executeTemplate` loop over `promulgationTypeService.getPromulgationTypes(message, true)` that runs
  `executeScriptResources(type.getScriptResourcePaths(), promulgationContextData)` for each promulgation
  type with non-empty script resources.
- **Why it is awkward to test as wired:** none of the three seeded `PromulgationType` rows (navtex/audio/
  twitter) has any `scriptResourcePaths`, so against the stock seed this loop never executes a script.
- **What IS tested instead:** `TemplateExecutionServiceITTest.executeTemplate_runsPerPromulgationTypeScripts`
  attaches a test `.ftl` to the seeded `navtex` `PromulgationType` in-transaction (`em.flush()`, rolled
  back) and gives the message a `navtex` promulgation with `promulgate=true`, then asserts the script
  populated the promulgation text. The simpler, realistic path (a *category* template populating a
  promulgation field) is covered by `executeTemplate_mainTemplatePopulatesPromulgationField` without any
  type mutation.
- **What would make it cleaner:** a seeded promulgation type carrying a script resource, or a builder seam
  for promulgation-type script resources, so the loop has a first-class fixture.

---

# Scheduled (`@Scheduled`) jobs — direct-invocation coverage and the residual untestable surface

This round addressed the long-standing gap *"no injectable Clock / scheduler-control seam — no way to fire a
`@Scheduled` job deterministically or time-travel"* (niord-base has 26 `@Scheduled` methods today; they share
Boot's scheduler of four threads since SCHED3). No production code was changed. The approach is
the one already proven by `ScheduledMessageServiceITTest`: a **same-package** test invokes the (usually
package-private) scheduled method **directly** with pre-seeded data and asserts the observable side effect.
Wall-clock dependence is sidestepped by seeding rows/files with far-past or far-future timestamps (day- or
year-sized margins, robust to JVM/DB timezone skew) rather than by controlling the clock.

## What remains genuinely untestable without a production change

1. **Cron firing *time* — every job.** Whether a job actually fires at its scheduled wall-clock instant is
   owned by the Spring scheduler. Tests invoke the method directly; they never assert *when* the scheduler
   would call it. Driving this deterministically needs an injectable `Clock`/scheduler seam or
   Spring scheduler test hooks — out of scope and impossible without touching `src/main`.
2. **Concurrent runs of different jobs.** No `@Lock` annotation exists in the code. A cron job is never
   started again while its own last run is running, but since SCHED3 two different jobs can run at the same
   time on the scheduler's four threads; `SchedulerPoolSizeITTest` asserts the four threads and that four tasks run side by side. `ScheduledMailService.sendPendingMails()` and
   `deleteExpiredMails()` have no guard between them, and no test runs them at the same time.
   `MessageLuceneIndex.updateLuceneIndex()` writes under a `ReentrantLock`, one per index folder for the whole JVM:
   `MessageLuceneIndexWriterLockTest` holds one instance inside its writer (a latch, no clock) and asserts that a
   second instance's `recreateIndex()` waits for it.
3. **`FiringExerciseService.updateFiringExercises()` full orchestration** is no longer residue: the tests commit
   schedules, firing areas with periods and target series of their own through `org.niord.test.DeliveryFixtures`
   and call the run's seam with them (`FiringExerciseServiceITTest`, `FiringExerciseServiceGapITTest`,
   `DeliveryFaultInjectionITTest`; see the per-schedule loop's entry below).

**What would make the residue testable:** an injectable `Clock` plus a scheduler-control seam (to fire a job
on demand and time-travel), a `src/main` change. (The batch jobs need no seam any more: the ITs launch real jobs
and await them, see the `core.batch` entries.)

---

# Untestable code — JSR-352 batch subsystem (residual)

Most of the batch subsystem is covered: the VO→entity processors are unit-tested framework-neutrally, the
service-dependent processors are driven via a same-package `TestableProcessor` subclass inside a test
transaction, and the full reader→processor→writer pipeline is exercised end-to-end by
`BatchJobExecutionITTest` (service submission + `JobOperator` polling) and `BatchUploadRestServiceITTest`
(REST upload) — which also covers the readers' and writers' `open`/`readItem`/`writeItems` indirectly. The
following remain untested, and why:

- **`BatchSetService` / `/execute-batch-set` + `/execute-javascript`** (sysadmin) — no longer residue. Their
  authorization boundary is asserted (`BatchRestServiceITTest`, `BatchRestActionITTest`), and so is
  `/execute-batch-set`'s allow-list refusal (`BatchRestActionITTest`). An accepted set is run by
  `MessageArchiveImportRoundTripITTest` (see the `BatchSetService` entry above), and a script is posted to
  `/execute-javascript`, run to COMPLETED and read back by the endpoint sweep (`EndpointSweepITTest`, the
  system group's batch cases in `SystemSweepBatch`); a blank script answers "OK" (`BatchRestGapITTest`).
- **The batch-folder `FileHandler` logger** (`AbstractItemHandler.getLog()`) is deliberately bypassed in the
  processor ITs (overridden to a no-op) because it opens a file under the batch-job folder; its file-logging
  side effect is infrastructure, not business logic.

---

# Untestable code — `web.map` map-image compositing

The map-image renderer (`MessageMapImageGenerator`, `MapImagePlanner`, `MapImagePainter`, `PortrayalSymbols`,
`CustomThumbnailScaler`) is covered by `MessageMapImagePipelineTest`, `MapImagePlannerTest`,
`PortrayalSymbolsTest` and `CustomThumbnailScalerTest`; the item below is the residue that cannot be
meaningfully reached.

## The non-atomic move of a written map image

- **Location:** `MessageMapImageGenerator.write`, the `catch (AtomicMoveNotSupportedException e)` arm that
  replaces the image with a plain `REPLACE_EXISTING` move.
- **Why it is untestable:** the temporary PNG is written in the image's own folder, and every file system the
  backend runs on (ext4 and overlayfs in the container, NTFS and APFS on a workstation) renames within a folder
  atomically, so the fallback is never taken. Reaching it would need a file system provider that refuses
  `ATOMIC_MOVE`, which the generator does not take as a seam.

# Untestable code — batch reader/writer wiring

- **Reader/writer wiring under the live JBeret runtime** (the `@PostConstruct init()` that resolves
  `BatchData`, the chunk checkpoint/commit machinery, the per-chunk transaction boundary) is still only
  exercised end-to-end by `BatchJobExecutionITTest` / `BatchUploadRestServiceITTest`; the unit/IT tests
  drive the reader/writer methods directly, by design.

---

# Additional untestable / deliberately-not-tested areas

- **`ThumbnailService.createThumbnailUsingVips`** — runs the external command the `vipsPath` setting names (`vipsthumbnail`); environment-dependent (Linux + libvips), not testable in CI (see the `ThumbnailService` entry above; `getThumbnail` on the container's bean is reached by `RepositoryServiceITTest`).
- **`org.niord.core.promulgation.TwitterPromulgationService` media-upload branch** — fetches `app.getBaseUri()+/rest/message-map-image/{uid}.png` over a real URL and builds the twitter4j client via a field-level `TwitterFactory` with no injection seam; the include-thumbnail+location media path cannot be driven deterministically without a production seam. (The no-location success path is covered.)
- **`FiringExerciseService` dictionary-fallback strings** (`formatTimeDescription`/`formatSubject` hard-coded fallbacks) — unreachable against the seeded DB because the seed supplies the `msg.time.*` and `firing_exercises` dictionary entries; would need a dictionary-less profile to exercise.
- **JBeret job orchestration** (`BatchService`/`BatchSetService` start/stop/restart/abandon, `BatchSetService` `Timer` dispatch) — exercised end to end only, by `BatchJobExecutionITTest`, the import round trips, `BatchExecutionActionRefusalsITTest` and the endpoint sweep (see the `core.batch` entries); the unit tests drive reader/writer/service methods directly by design. The `@Scheduled` firing time stays untested (residue item 1 above).

---

# Dead / unreferenced code

- **`org.niord.core.model.Extent`** — dead/unused: no `import org.niord.core.model.Extent` and no `new Extent(...)` outside `Extent.java`, and `MessageSearchParams.getExtent()` returns a JTS `Geometry`, not this class. **Kept, not deleted** — it is pure logic and its `normalize()` antimeridian/zero-meridian splitting is guarded by `org.niord.core.model.ExtentTest`, so it is safe to leave in place. Delete `Extent.java` + `ExtentTest.java` together only on request; otherwise exclude from the coverage denominator.

# Untestable / hard-to-test code — publication, mailing-list and batch REST

Areas where a *meaningful* assertion cannot be made without a production seam or a new fixture. Each item
lists what would unblock it.

## `FiringExerciseService.updateFiringExercises()` — the outer per-schedule loop with >1 active schedule

- **Location:** `niord/src/main/java/org/niord/core/schedule/FiringExerciseService.java`, the loop over the active
  schedules in `updateFiringExercises(Set<Integer> scheduleIds)`.
- **Now covered.** `FiringSchedule` carries a UNIQUE constraint on its source-domain `@OneToOne` column, so at most one
  schedule per source domain is allowed, and the seed has one firing source domain (`niord-fa` / `dma-fa`). Since the
  error hunt's fix of the hourly firing run (2026-11-13) the tests commit source and target domains and series of
  their own through `org.niord.test.DeliveryFixtures` and call the run's seam with their own schedules:
  `DeliveryFaultInjectionITTest#aScheduleWhoseTemplateSearchFailsIsSkippedAndTheOtherSchedulesRun` runs two schedules
  in one run and asserts that a schedule whose reads fail is skipped while the other issues its exercises. The
  single-schedule reconciliation is covered by `FiringExerciseServiceITTest` and `FiringExerciseServiceGapITTest`.

## `PublicationRestService.releasePublication()` / `generatePublicationReport()` — live PDF round-trip

- **Location:** `niord/src/main/java/org/niord/web/PublicationRestService.java`, `releasePublication` (~538-560) and `generatePublicationReport` (~448-497).
- **Why it is hard to test:** both perform a live HTTP round-trip from the running server back to
  `/rest/message-reports/report.pdf`, which renders a PDF through the message-report FreeMarker/print pipeline. Driving
  a success needs a `MESSAGE_REPORT` publication in `RECORDING` with a real `MessageTag` and `printSettings.report`,
  plus the renderer producing a 200 PDF — not constructible purely through the public REST surface, and heavyweight/
  brittle for an endpoint test. `PublicationRestReleaseITTest` therefore drives every reachable branch up to (but not
  including) the rendering round-trip (e.g. release of a non-report publication, the auth oracle, validation negatives).
- **What would make it testable:** a fixture/seed providing a fully-formed `MESSAGE_REPORT` publication plus a stub
  `/rest/message-reports/report.pdf` returning a canned PDF; or an injectable seam replacing the HTTP round-trip with a
  test double so the status-to-`ACTIVE` and next-issue-creation outcomes can be asserted in isolation.

## `PublicationRestService.importPublications()` (`POST /upload-publications`) — async JBeret import — covered

- **Location:** `PublicationRestService.java` → `AbstractBatchableRestService.executeBatchJobFromUploadedFile()`.
- **Covered:** `LegacyPublicationImportRoundTripITTest.aLegacyPublicationComesBackFromItsExportAndImportsOverItself`
  exports a publication, deletes it, uploads the export to `/rest/publications/upload-publications`, waits for the
  `publication-import` job to complete (through `GET /rest/batch/{job}/instances`) and compares the publication with
  the export, then imports over it and asserts that only the save stamp and the revision change.
  `PublicationRestReleaseITTest` asserts the synchronous answer (`200` + `"Started batch job …"`).

## MailingList user-recipient handshake — no isolated `User`-creation seam

- **Location:** `org.niord.web.MailingListRestService#updateRecipientUsers` / `updateRecipientStatus` (user branch).
- **Why it is hard to test:** unlike the contact side (`POST /rest/contacts/contact/` persists a `Contact` with a chosen
  email), there is no isolated REST path to persist a `User` with a non-blank email — `addKeycloakUser` hits the Keycloak
  ADMIN API (unreachable from the test container, and would mutate the shared realm), and there are no seeded `User` rows.
  `availableRecipients` filters `allUsers()` to non-blank-email users, so the user-add handshake is only reachable by
  priming `currentUser()` as a side effect (the new IT hits `GET /rest/filters/all`, whose `checkUserLoggedIn()` resolves
  `currentUser()` and copies the token's email claim onto a new row). That prime runs in the server's own
  (non-rollback-able) transaction, so the `testadmin` `User` row leaks into the shared DB.
- **What would make it testable:** a test seam to persist a Niord `User` with a chosen username+email without touching
  Keycloak (a `UserService` test fixture / `@TestTransaction` persister, a local-only create-user endpoint, or a couple
  of seeded `User` rows with emails), so the user-recipient handshake can use unique, isolated data per test like contacts.

## `BatchRestService` action endpoints — async JBeret lifecycle (stop/restart/abandon, execute-javascript, execute-batch-set)

- **Location:** `niord/src/main/java/org/niord/web/BatchRestService.java`: `stopExecution`/`restartExecution`/`abandonExecution`
  (PUT `/execution/{id}/…`), `executeJavaScript` (POST `/execute-javascript`), `executeBatchSet` (POST `/execute-batch-set`).
- **Why it is hard to test:** these delegate to the JSR-352 `JobOperator` / `BatchService.startBatchJobWithDataFile` /
  `BatchSetService` — mutating a real asynchronous JBeret job lifecycle with file-system side effects under `batchJobRoot`
  and persisted `BatchData`, none isolated by `@TestTransaction` (the job runs on a separate thread/transaction). Launching
  one is timing-dependent and per-JVM JBeret state is not reset between tests, so happy-path success would be flaky, and
  `getJobInstanceCount` itself throws `NoSuchJobException` for a never-run job (not a reliable read-back oracle). Only the
  authorization boundaries (401/403) and the deterministic blank-script-returns-`"OK"`-starts-no-job contract are asserted
  (`BatchRestActionITTest`, `BatchRestServiceITTest`, `BatchRestGapITTest`). Since 2026-11-13 the three execution
  actions' 404 and 409 answers, and abandon of a completed and stop of a running execution, are driven too
  (`BatchExecutionActionRefusalsITTest`; see the `BatchService` entry).
- **What would make it testable:** a synchronous/awaitable batch-execution facility plus a test-controllable `batchJobRoot`
  (or an injectable seam capturing `startBatchJobWithDataFile` arguments), so a launched instance can be driven to a known
  terminal state and its persisted `BatchData`/output asserted, and so stop/restart/abandon can run against a known executionId.
- **Driven since the owner's bootstrap ruling of 2026-10-24 (P7-bs):** `PUT /execution/{id}/restart` as a sysadmin, for a
  failed job started in the test: 409 for a bootstrap job (its `BatchData` carries the run's token) with no new execution,
  and accepted for a sysadmin's import (`BatchRestServiceITTest.aBootstrapJobsRestartIsRefused` and
  `aSysadminImportsRestartIsAccepted`, the core entry above).
- **Driven since then:** the rest of the happy paths. The endpoint sweep (`EndpointSweepITTest`, the system group's
  batch cases in `SystemSweepBatch`) posts a script to `/execute-javascript` and asserts its job COMPLETED and its data
  file read back, and stops, restarts and abandons executions of its own scripts, asserting the statuses STOPPED,
  COMPLETED and ABANDONED; `MessageArchiveImportRoundTripITTest` runs an accepted set through `/execute-batch-set`.
