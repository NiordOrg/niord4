# Publications redesign — REST API contract

> **A shared specification, not a repository-local document.** This file and `DATA-MODEL.md` are the
> publications specification, held here and read from both repositories: this one (the Angular client)
> and `niord` (the backend that serves the endpoints). Ownership splits cleanly. **The backend owns the
> surface** — the endpoints, the access tiers, the domain scoping and the error catalogue are what its
> code does, so where the code and this document disagree the code is what ships and this file is
> corrected to match. **This repository owns the client half**: the publication API services under
> `ui-apps/admin/src/app/features/admin/services/api/` and `ui-apps/admin/src/app/features/messages/services/api/` are where these
> endpoints are called and where the server vocabularies are transcribed and unit-checked.
> `DATA-MODEL.md` is the entity half of the same specification.
>
> **`scripts/publications/spec-check.js` guards the pair**, comparing the two documents for the defect
> classes that have actually occurred here: one identifier described two ways, one error code carrying
> two HTTP statuses, two routes sharing a path template, a section reference pointing at nothing. It
> also re-runs the two exports generated from `DATA-MODEL.md` into the backend repository. Run it after
> any edit to either specification file, and do not renumber or restructure the headings — the
> generators and the cross-reference checks key on them.

*Companion to the data-model document. The two must agree field-for-field, and as of the reconciliation pass of 2026-08-21 they do: every seam between them is closed **in the text on both sides**, never as a note or an appendix. §0.4 records the six rulings that got them there, all of them now applied in the data model as well as here — including the one this contract has **withdrawn** (`snapshotIntervalFrom` **is** a real column).*

**Baseline read:** `niord` @ the currently checked-out tree (branch `master` in the working copy; the project baseline is `MaDaMe`, which offsets `MessageSearchRestService.java` by roughly **+10 lines** against the citations below — every other cited file matched line-for-line). Every convention is cited to a file and line that was opened and read, not inferred.

---

## Amendment index — read this before the body

**The body below is the contract as specified in August 2026. The amendment sections at the foot are the
contract as it shipped, and they win wherever the two disagree.** The body is deliberately not rewritten, so
the history stays readable — which is only safe if a reader knows the amendments exist. In order:

| Section | What it settles |
|---|---|
| [Amendments — 2026-08-27 (Stage A)](#amendments--2026-08-27-stage-a-of-the-work-queue) | `cutoffDefault`; `publishedAt` vs `cutoffStampedAt`; the publish body (`cutoff`, acknowledgements, `regenerate`); preview replaces I20–I23; coverage-based gap rows; `VISIBILITY_CAPPED` and `IMPORTED` audit |
| [Amendments — 2026-08-28 (Stages B and C)](#amendments--2026-08-28-stages-b-and-c-of-the-work-queue) | amend (I17); new edition; curation bodies and `clear`; S10 transitions; one category resource; `fieldErrors`; S-22/S-23 |
| [Amendments — 2026-08-28 (verification pass)](#amendments--2026-08-28-verification-pass-over-stages-ad) | `publishedAt` is always the release instant; retire's reason; preview file names; weekly naming tolerance; the admin-only tooling endpoints that were undocumented |
| [Amendments — 2026-08-28 (B-5, B-11, B-12, C-5, C-8)](#amendments--2026-08-28-b-5-b-11-b-12-c-5-c-8-as-shipped) | the three consumer endpoints I27/I28/I29; member `drift[]`/`current`; `GET /overrides`; `maxSize` paging; the issue draft (S12) and copy (S7); `publishedIssueCount`; the import check's `series[…]` rows |
| [Amendments — 2026-08-28 (external review 2, backend batch 2)](#amendments--2026-08-28-external-review-2-backend-batch-2-tiers-the-mapper-the-public-surface) | **the tier matrix — this supersedes §1.3's table** · the exception mapper · `?publication=` and the search union · upload-over · the import's `notes[]` |
| [Amendments — 2026-08-28 (external review 2, backend batch 1)](#amendments--2026-08-28-external-review-2-backend-batch-1-the-native-issue-shape-and-the-publish-gate) | `IssueShape` — one derivation of interval, week/year and name · the rail as a gate · live members on an OPEN issue · S-1/S-4/C-6 |
| [Amendments — 2026-08-28 (R-xxi, domain-scoped writes)](#amendments--2026-08-28-r-xxi-domain-scoped-admin-writes) | every admin write is scoped to the caller's domain; `403 NOT_IN_DOMAIN` |
| [Amendments — 2026-08-28 (§7.3 catalogue reconciled)](#amendments--2026-08-28-73-catalogue-reconciled-against-publicationerrorcataloguejava) | the renamed, restatused, dropped and added error codes — **§7.3's table is superseded by `PublicationErrorCatalogue`** |
| [Amendments — 2026-08-29 (R-xxvi: the owner domain and availability)](#amendments--2026-08-29-r-xxvi-the-owner-domain-and-availability) | `domainId` is **required** and means the OWNER · `availability` + `availableDomainIds` · S1/S2 and the one-off list filter by owner · the transfer endpoint · the visible-from predicate on I27 and `/publications/search` · `SERIES_WITHOUT_OWNER` · `OWNER_ASSIGNED` / `OWNER_DOMAIN_NOT_FOUND` · S-20a / S-20b · `OWNER_TRANSFERRED` |
| [Amendments — 2026-09-07 (one resolve per screen; the load-time batch)](#amendments--2026-09-07-one-resolve-per-screen-the-load-time-batch) | **I30** the issue workbench (CUR, one response, one resolve) · **I31** `recent-by-domain` (AD) · `GET /one-off-publications/{seriesId}` (AD) · the omissions cut-off moves to the members' instant · `NOT_ALIVE_AT_CUTOFF` on the live path is the withdrawal-instant class · §3.8's bounding paragraph corrected · `candidates` / `missCount` shrink on in-force series · a non-admin curator gets the screen without the rail |
| [Amendments — 2026-09-07 (deleting an issue)](#amendments--2026-09-07-deleting-an-issue) | **I32** `DELETE /issue/{publicId}` (AD, owner domain, version-checked) — an OPEN or RETIRED issue only when no message cites it (widened to OPEN 2026-09-09), a PUBLISHED one never (`ISSUE_PUBLISHED_NOT_DELETABLE`, `ISSUE_CITED` naming the citing messages) · the issue's own audit trail goes with it, one series-level `DELETED` entry stays · **I33** `GET /publication-series/{seriesId}/history` (AD) reads the series-level entries |
| [Amendments — 2026-09-08 (the batch of nine)](#amendments--2026-09-08-the-batch-of-nine) | `regenerate` **removed** from I16 and I17 (publish and amend always render; `PREVIEW_STALE` retired) · `names?` on I16 and I7 (issue names editable at release and at create, through the one rename path) · `CriteriaMissVo.messageId` · the render loads a document’s members in one pass per association (no wire change) · frontend-only: desk switch leaves a foreign series, absolute document links, the series page waits for its first answers |
| [Amendments — 2026-09-08 (round three)](#amendments--2026-09-08-round-three-naming-a-message-structured-rail-details-the-chain-rows) | every message reference = `messageUid` + `messageId` + `title` (`CriteriaMissVo.title`, `IssueOverrideVo.messageId/title`, `citingMessages[].title`, S13 `sample[]` rows; `?lang=` on I32, `GET /overrides`, S13) · `PublishCheckRowVo.detailCode` + `detailParams` (28 codes; the client translates) · `INTERVAL_CHAINED` not applicable on non-tiling series, `OVERLAPPING_ISSUE` on in-force ones · frontend-only: dialog notice reset, "Exclude", series field labels |
| [Amendments — 2026-09-09 (round four)](#amendments--2026-09-09-round-four-the-edition-the-public-window-by-hand-the-audit-order) | `edition` on the I8 body (`EDITION_INVALID`, audited `EDITION_CHANGED`); the create derives it and the draft proposes `"1"` everywhere · `OneOffVo.categoryPublish` + `.publicTo`; `409 PUBLIC_WINDOW_CLOSED` on the active toggle; **new `PUT /one-off-publications/{seriesId}/public-window`** (AD, owner domain, series `version`, `NOT_ONE_OFF`), audited `VISIBILITY_WINDOW_CHANGED` · **I11 and the workbench audit are newest first**; the vocabulary is 31 actions · frontend-only: `publicNowNoneHistoric`, the category `publish` gate on "Public now" · **`A-iv` amended** (a withdrawal + its replacement inside the withdrawn window are one release; gap synthesis ignores a period a published cut-off falls inside) · cadence-less cut-off bounded by the replacement's release (13 rows) · ruled names for shared orphan series · one-off desk switch, non-public category text, e2e test ids |
| [Amendments — 2026-09-10 (one-off visibility)](#amendments--2026-09-10-one-off-visibility-status-public-period-category) | a one-off is status + public period + category, never "active" · `PUT …/public-window` takes **both ends**, a future start = scheduled, `400 PUBLIC_WINDOW_INVALID` · publish keeps a prepared window · `OneOffVo` carries `issueStatus`/`publicFrom`/`publicTo`/`categoryPublish`, `active` deprecated · Public column in the one-offs table |
| [Amendments — 2026-09-10 (round six)](#amendments--2026-09-10-round-six-the-expected-public-window-firing-areas-everywhere-the-danish-accepted) | an OPEN issue has no public window: the list rows gain `expectedPublicFrom`/`expectedPublicTo` (planned cut-off → + one period; the predecessor's expected end), the import copies no window onto OPEN rows and never caps by an unreleased successor · `firing-practice-areas` availability `ALL_DOMAINS` · every `ai_` Danish proposal accepted · frontend-only: names-only insert picker with a wider, loading-aware dropdown, legend removed, history collapsed, reactive menu labels |
| [Amendments — 2026-09-10 (round seven)](#amendments--2026-09-10-round-seven-a-cadence-opens-its-own-next-issue) | S-8 both ways: a cadenced series is `AUTO_ON_PUBLISH`, a cadence-less one `MANUAL`, healed on save and before activation · `createSuccessorIfDue` drops the tiling clause (a non-tiling successor has no period start and an end one period on) · the publisher is named, not logged in · a double week carries `weekTo` on a non-tiling series too · frontend-only: open-ended windows read as "since", an upcoming period offers create, the next-issue control is stated rather than chosen |
| [Amendments — 2026-09-10 (round nine)](#amendments--2026-09-10-round-nine-what-the-tailoring-does-not-need) | the per-issue **second-week label is withdrawn** one day after it shipped: `weekToLabel` is gone from the entity, the wire and the schema (V16), and `printedWeekTo` now answers from the derived `weekTo` alone · `IssueDraftVo` gains `chainedFromName` so a draft names its predecessor instead of addressing it · the issue-draft endpoint takes `lang` · frontend-only: the criteria source badge, three Document-card facts and the issues table’s copy icon are removed, the file name is a real value rather than a placeholder, and a member’s status wears the standard status colours |
| [Amendments — 2026-09-13 (compilation series)](#amendments--2026-09-13-compilation-series-a-third-membership-regime) | `TimeRelation` gains **`COMPILED_FROM_SOURCE`** · series `sourceSeriesId` (ADMIN only; blank clears; `SOURCE_SERIES_NOT_FOUND` 400; S-24 / S-25) · issue `snapshotSourceSeriesId` / `snapshotSourceIssueIds` (**I-20**) · member `sourceIssue` + `COMPILED` + `FROM_SOURCE_ISSUE` · I30 `sources[]`, no `omissions`, no late section · the **fifteenth** rail row `SOURCE_ISSUES_COMPLETE` with `SOURCE_ISSUES_INCOMPLETE` as its acknowledge code · `MEMBER_LIMIT` and `CANCELLED_MEMBERS_ALIVE_AT_CUTOFF` not applicable (`BOUNDED_BY_SOURCES`, `JUDGED_AT_SOURCE`, `NOT_COMPILED`) · the probe refuses the relation · the document is grouped by source issue |
| [Amendments — 2026-09-14 (a release or an amend in flight)](#amendments--2026-09-14-a-release-or-an-amend-in-flight-is-the-issues-own-fact) | **I30** gains `inFlight { action: PUBLISH \| AMEND, since }` — the server's own record of a release or an amend still running, absent when idle and absent again once it ends · **I16 and I17** refuse a second one with **409 `ISSUE_BUSY`** · a client that did not start the work re-reads the workbench while the record stands |
| [Amendments — 2026-09-10 (round eight)](#amendments--2026-09-10-round-eight-what-one-edition-says-differently) | the per-issue overrides drawer: free-text printed numbering (`weekLabel`/`yearLabel`, and a `weekToLabel` withdrawn in round nine) resolved by one helper for names, file names, citations and the report · per-language `fileNames` · a per-issue `reportId` · the clear convention (absent = leave alone, empty = clear) · and the year that printed as "2.026" |
| [Amendments — 2026-11-13 (the error hunt's fix stage)](#amendments--2026-11-13-the-error-hunts-fix-stage) | **400 `FILE_NAME_TAKEN`** on I24 and the RETIRED issue's in-place upload · I21's optional `{ at }` and **400 `INVALID_INSTANT`** · I6's `MISSED` membership · I30 `publishedSinceRelease` · **400 `LINK_INVALID`** · a repeated New edition answers with the open successor · a database race answers `STALE_VERSION` · C-4 at activation and in the series import |

---

## 0. How to read this document

### 0.1 What it covers

The wire contract for the publication **series**, **issue**, **member**, **override**, **audit**, **preview** and **category** surfaces, plus the two surfaces that cross the boundary into existing code: the anonymous public adapter (§8) and `?publication=` (§9.1). It is written against the data model as specified — the same entity names, the same column names, the same nullability.

### 0.2 What it does not cover

The entity/DDL layer, the criteria document's internal schema, the resolver predicate, and the importer's translation table. Those live in the data-model document and in the criteria/resolver section; this contract references them and stops. Where an endpoint's behaviour is *defined* by one of them (publish freezing order, the five legal filter combinations), it is restated only as a precondition or an error, never re-specified.

### 0.3 The single most important rule in this document

> **Every DTO field below is either a column on an entity in the data model, or a value derived from columns by a rule stated here.** There are no wire-only inventions except the five explicitly marked **(derived, not persisted)**: `computedStatus`, `dormant`, `gapDetection` / the gap pseudo-row, `previewStale`, and `MemberReasonVo.kind`. If a reviewer finds a sixth, it is a defect in this document.

### 0.4 Contradictions inside the data model that this contract had to resolve

The data model contradicted itself in six places. A REST contract cannot be written on an ambiguity, so each was ruled — and **every ruling below is now applied in the data model's own text as well as here.** Nothing in this table is outstanding, and none of it is a question any longer; the rows survive because the rulings are load-bearing and a reader who meets an older fragment must be able to see which way each one went.

**One of the six was decided the other way and is recorded as withdrawn:** this contract originally ruled `snapshotIntervalFrom` **not a column** (old X5), on the argument that amend never moves `intervalFrom`. That ruling is **withdrawn.** Retro-creation and the interval edit on **I8** can both move an interval bound in the neighbour chain around an already-published issue, so the live column stops describing what that issue was resolved against — and reading a live column at response time is exactly the pattern the frozen snapshot exists to remove (**B6**). `snapshotIntervalFrom` is a real column on `PublicationIssue`, written by publish (§4.3 step 7) and echoed by the member list (§3.8). The X-number is retired rather than reused.

| # | The contradiction | Ruling, applied on both sides | Why |
|---|---|---|---|
| **X1** | Public-window column names: §3.6.3 and the index in §3.6.7 said **`publicFrom` / `publicTo` / `publicWindowSource`**; §8.2 I-14 and the index list in §8.3 said **`visibleFrom` / `visibleTo` / `visibleWindowSource`**. | **`publicFrom` / `publicTo` / `publicWindowSource`** — **and the rename has landed.** The data model now reads `public*` in its field table, its indexes, its invariants and its I-14 resolution, which names `visibleFrom`/`visibleTo`/`visibleWindowSource` as retired spellings that must not appear anywhere. **Closed; do not reopen it.** | The §3.6.3 field table and the §3.6.7 `@Table(indexes=…)` block were the *normative field-level* statements; §8.2's was prose in a rules table. Also `public*` names the thing the column actually gates (public listing eligibility), and `visible*` collides with nothing but reads like a UI concern. |
| **X2** | `PublicationVo.type`: §2.5 lists `legacyType` under **"deliberately absent columns"** and says the adapter derives `type` from `contentMode` by a total pure mapping; §7.12 said `PublicationType` is **"stored on the series as `legacyType` and never re-derived per request"**. | **Derived, no column** — applied: the data model's §7.12 now states the derivation and §2.5 keeps `legacyType` in the deliberately-absent table. | §7.2 proves the mapping is bijective and total (`GENERATED_FROM_QUERY↔MESSAGE_REPORT`, `UPLOADED_FILE↔REPOSITORY`, `EXTERNAL_LINK↔LINK`, `NONE↔NONE`). A bijection cannot drift; a second column can. §2.5's argument is the stronger one and it is in the "recorded so nobody restores them" table. |
| **X3** | `IssueMember.reasonCode`: §3.8 declared it `NOT NULL`; the duplicate §6.4 field table explicitly **dropped it** as "a stored derivable enum is a second source of truth". | **No `reasonCode` column. The why-line kind is a wire field**, `MemberReasonVo.kind`, derived from `source` + `snapshotTimeRelation`. `reasonNote` survives as a column (it carries the one honest import note). Applied: the duplicate §6.4 field table is **deleted** and §3.8 no longer declares the column. | §7.13 already lists `MemberReasonKind` as **(wire)**, owned by the resolver — which is exactly the reconciliation. The merge decision was the later, explicitly-argued one. |
| **X4** | The stale-override flag was `IssueOverride.appliedAtPublish` (§3.9 field table) and `IssueOverride.staleAtFreeze` (§4.4 step 8, §6.4). | **One column, `appliedAtPublish`** (the field table's name). `staleAtFreeze` is its logical negation and is not a second column. Applied: the data model's §4.4 step 8 now writes `appliedAtPublish`. | Two names for one boolean is how a second column gets added by accident. The field table wins on naming. |
| **X6** | `messageReferenceEnabled`: §2.5 says the boolean is deliberately absent because **`messagePublication != NONE` is "reference enabled"**; the admin fragment's DTO carried it. | **Not on the wire.** DTOs carry `messagePublication`; clients derive. | One source of truth, and `messagePublication` is already needed on the wire for the citation picker (ADD-020). |

### 0.5 One resolution that *simplifies* the contract

The data model mints `publicId` **at issue CREATE**, not at publish (§3.6.1, §4.7). The admin-REST fragment assumed publish-time minting and therefore carried **two** issue identifiers (`issueId` for admin routes, `publicId` for the public value space).

> **Ruling: there is exactly one issue identifier, `publicId`.** There is no `issueId`, on any surface. Admin routes are `/publication-issues/issue/{publicId}`, `?publication=` takes a `publicId`, and an OPEN issue is addressable from the moment it exists — which is precisely what OPTIONAL §3.4 needs for live preview and the C9 editor tier.

This also kills open question 8 of the admin fragment ("does `?publication=` accept issueId, publicId, or both?") outright: there is nothing else it could accept.

### 0.6 House style this contract copies

Every row was opened and read.

| Convention | Verified at |
|---|---|
| Resource class = `@Path` + `@RequestScoped` + `@Transactional` + class-level `@PermitAll`, per-method `@RolesAllowed`, `@GZIP`, `@NoCache`, `@Produces("application/json;charset=UTF-8")` | `PublicationRestService.java:74-78, 104-108`; `PublicationCategoryRestService.java:45-49, 59-63` |
| Lean `XVo` vs `SystemXVo` on the same endpoint family, selected per-method by role | `PublicationRestService.java:109-136` (`PublicationVo`, no `@RolesAllowed`) vs `:142-175` (`SystemPublicationVo`, `@RolesAllowed(Roles.USER)` when read; **`Roles.ADMIN` since 2026-09-25**, like S2) |
| Paging params are **`page` + `maxSize`**, both `@DefaultValue` | `PublicationRestService.java:118-119` (`maxSize` default `100`, `page` default `0`) |
| Paged envelope `{ data, total, size, description }` | `PagedSearchResultVo.java:34-41` |
| A directly-constructed search params object is **unpaged**: `maxSize = Integer.MAX_VALUE` | `PagedSearchParamsVo.java:34` |
| Sort order enum `ASC` / `DESC` | `PagedSearchParamsVo.java:32` |
| Comma-separated ids on a path segment for picker hydration; unknown ids silently omitted | `PublicationRestService.java:223-232` |
| GET returning an **unsaved, fully-populated** template VO | `PublicationRestService.java:255-270`, `:278-319` |
| CRUD shape `POST /x/`, `PUT /x/{id}` with `throw new WebApplicationException(400)` on a path/body id mismatch, `DELETE /x/{id}` | `PublicationRestService.java:326-395`; `PublicationCategoryRestService.java:110-116` |
| State change = `PUT` + a small `IJsonSerializable` param class; failure → `WebApplicationException(e.getMessage(), 400)` | `PublicationRestService.java:404-424`, `:736-769`; message text built at `PublicationService.java:510` — literally `"Invalid state transition " + curStatus + " -> " + status` (the old system; this backend's `PUT /publications/update-status` answers that refusal 409 and a body without a status 400 "A status is required" since 2026-11-14) |
| A message **selection** is a bare `List<String> messageUids` body on a `PUT .../add-messages`-shaped sub-path | `MessageTagRestService.java:200-230` |
| "Which containers hold this message" lookup | `MessageTagRestService.java:86-99` (`GET /tags/message/{messageUid}`) |
| Multipart upload: `@Consumes(MULTIPART_FORM_DATA)`, first file only, JSON descriptor in the `data` part, `400` when either is absent | `PublicationRestService.java:598-650` |
| Temp-repo path containment check before writing | `PublicationRestService.java:658-672` → `RepositoryService.validateTempRepoPath` |
| JSON import = start a chunked batch job from the uploaded file | `PublicationRestService.java:712-719`; `AbstractBatchableRestService.executeBatchJobFromUploadedFile` |
| Export must work from a plain browser download, so the admin check is **programmatic** — `userService.isCallerInRole(Roles.ADMIN)` — which lets a `?ticket=` through | `PublicationRestService.java:685-691`; `UserService.isCallerInRole` resolves ticket-carried roles for an anonymous identity (`UserService.java:182-193`) |
| One-time, **1-minute**, domain+user+roles ticket, obtained from `GET /rest/tickets/ticket?role=admin` | `TicketRestService.java:67-89`; `TicketService.LIFESPAN = 60 * 1000` |
| Role constants, composite `sysadmin ⊃ admin ⊃ editor ⊃ user`, declared as `String` constants **specifically so they work in `@RolesAllowed`** | `Roles.java:19-34` (the javadoc says exactly this) |
| Per-language payload = a `*DescVo` list, one row per configured language; `descDefined()` filters empty rows out | `PublicationDescVo.java:44-46`; `Publication.java:259-264` |
| `lang` on a read narrows `descs` via `DataFilter.get().lang(lang)` | `PublicationRestService.java:132` |
| Public link externalisation: prefix `app.getBaseUri()` onto any non-blank link not matching `^(?i)(https?)://.*$` | `ApiRestService.java:466-477` |

**Wire types.** `Date` → **epoch milliseconds** (Jackson default; the shipped frontend already assumes it, and `/public/v1` exposes `dateFormat=UNIX_EPOCH|ISO_8601` to opt into ISO — `ApiRestService.java:365-366`). Enums → `STRING` name. `null` and *absent* are **not** interchangeable in one place only, and it is deliberate: see the `curation` object in §3.8.

**The backend this client now talks to serialises a `Date` on the internal endpoints as an ISO 8601 string with an offset** (`"2019-12-31T22:59:59.999+00:00"`), not as a number, and the hand-serialised `/public/v1` surface is unaffected. Every field typed as epoch milliseconds in this document stays one on the client: the three publication API services normalise each response at the boundary through `withEpochDates` (`ui-apps/admin/src/app/shared/utils/epoch-dates.ts`), so the model types below are the types the section actually reads. Requests are unchanged — an epoch number deserialises into a `Date`.

---

## 1. Scope, base paths, versioning, and the C9 tier model

### 1.1 Base paths

| Base path | Class | New or existing |
|---|---|---|
| `/rest/publication-series` | `org.niord.web.PublicationSeriesRestService` | **new** |
| `/rest/publication-issues` | `org.niord.web.PublicationIssueRestService` | **new** |
| `/rest/publication-categories` | `org.niord.web.PublicationCategoryRestService` | **existing, extended** — not forked, because a documented anonymous export link points at `/all` and must keep working at the same address |
| `/rest/public/v1/publications`, `/rest/public/v1/publication/{publicationId}` | `org.niord.web.api.ApiRestService` | **existing, re-sourced** (§8) |
| `/rest/publications`, `/rest/publications/publication/{ids}` | `org.niord.web.PublicationRestService` | **existing, untouched during transition** (§9.2) |

New VOs live in `org.niord.core.publication.series.vo` in **niord-core**, not in `niord-model`. Two reasons: `niord-model` is the published-XSD module and nothing new may enter it (§8.4), and the OpenAPI scan list lives in the `niord-dk` overlay and deliberately excludes `org.niord.web` (OQ-12).

### 1.2 Versioning

There is **no URL version segment on the admin API**. The three versioned things are versioned where they live:

| Thing | Versioning mechanism |
|---|---|
| The public API | Already `/rest/public/v1/…`. **This contract adds no v2 and changes no shape.** |
| The criteria document | `schemaVersion` **inside** the document (data model §5.3). A resolver refuses an unknown version loudly. Adding a node kind, an operator value or `match:"ANY"` is additive and does *not* bump it. |
| The JSON interchange payload | `formatVersion` on the export envelope (§3.13). |
| Individual entities | Optimistic lock `version` from `VersionedEntity`, carried on every write body and returned on every read. |

Rationale for no path version: the admin API has exactly one consumer (this frontend), shipped in lockstep. A path version buys nothing and would have to be maintained forever.

### 1.3 The three read tiers, expressed as the `SystemXVo` / `XVo` split

> **Superseded as the tier list (2026-08-28).** The shipped matrix is the one asserted by
> `PublicationTierMatrixTest` and written down in the *external review 2, backend batch 2* amendment at the
> foot of this file — it moved I3, I10, I11 and `/upload-series`, and named the curator tier's endpoints
> (rulings D16, R-xiii, R-xiv). The **structure** below — tiers as a `SystemXVo` / `XVo` split, and the
> hidden-from-editor table — stands unchanged.

C9's tiers map onto the repo's existing structural split, not onto field-level redaction. `Publication.toVo(Class<P>, DataFilter)` branches on `instanceof SystemPublicationVo` (`Publication.java:247-291`) and the resource picks the class per method (`PublicationRestService.java:109` vs `:148`); the SystemXVo branch is at `Publication.java:267`. Copy that exactly.

| Tier | Gate | Class served | Reaches |
|---|---|---|---|
| **ANONYMOUS** | class-level `@PermitAll`, no `@RolesAllowed` | `PublicationVo` (frozen legacy shape) | **No enumeration in `/rest/publication-series` or `/rest/publication-issues`.** Only the public adapter (§8), PUBLISHED issues only. **Two inherited exceptions**, both by-explicit-id or documented-link: `GET /publication-categories/all` stays anonymous, and **I28 `GET /publication-issues/by-ids` stays anonymous** — it hydrates ids the caller already holds, ignores `status` and `category.publish`, and silently omits unknown ids (§9.3, P4). Hydrating a held id is not enumeration; **I27 `GET /picker` is not anonymous** (§2.2). |
| **EDITOR** | `@RolesAllowed(Roles.USER)` — the composite means every editor, admin and sysadmin passes (`Roles.java:21-27`) | `PublicationSeriesVo`, `PublicationIssueVo`, `IssueMemberListVo`, `IssueMembershipVo`, `PublicationIssuePickerVo` | Series list + one series; issue list + one issue; an issue's resolved member list **with criteria why-lines**; "which issue(s) does this message belong to / would it land in"; the picker (I27). Read-only. |
| **ADMIN** | `@RolesAllowed(Roles.ADMIN)` | `SystemPublicationSeriesVo`, `SystemPublicationIssueVo`, `SystemIssueMemberListVo`, `IssueAuditEntryVo`, `IssuePreviewVo`, `SystemPublicationCategoryVo` | Everything else: all writes, criteria JSON, print/report settings, repo and archive paths, automation, patterns, audit, preview, import/export, the resolver probe, and **override author + reason**. |
| **CURATOR** | `@RolesAllowed(Permissions.PUBLICATION_CURATE)` | — | The three override endpoints only (C1), specified as actions in §4.11 — plus an I17 amend whose body carries a `curation` delta (§4.4). |

> **Amended 2026-08-31 (R-xxviii).** The `PUBLISHED` entry's `detail` carries `checklist: [{ code, severity: "OK"|"WARN"|"BLOCK", passed, acknowledged }]` — every applicable checklist row as it stood in the publish transaction, `acknowledged` = the code was in the caller's acknowledged set — beside the existing `stampedAt`, `memberCount`, `releaseMode` and `unacknowledgedWarnings`. Entries written before the change have no `checklist` key; the history disclosure "Tjeklisten ved publiceringen" renders only when it is present.

**What the editor tier never sees, and why each one is out.** The line is drawn where C9 drew it — narrower than restoring `/search-details`, which handed any logged-in user the full system model (`PublicationRestService.java:142-175`). **Closed 2026-09-25:** the legacy `/publications/search-details` is `@RolesAllowed(Roles.ADMIN)`, the tier of its new-model twin S2.

| Hidden from EDITOR | Reason |
|---|---|
| `criteria`, `criteriaOverride`, `criteriaSnapshot` (the raw documents) | Authoring surface. The editor gets `criteriaSummary` — resolved, rendered chips — which is all a why-line needs. It is on **both** `PublicationSeriesVo` (§3.1) **and** `PublicationIssueVo` (§3.4, resolving override-vs-series and snapshot-vs-live server-side); without the issue-level copy this row would hide the criteria from the editor entirely. |
| `reportId`, `pageSize`, `pageOrientation`, `mapThumbnails`, `reportParams`, `messageSortBy`, `messageSortOrder` | Operational render configuration. `printSettings` leakage is the specific thing C9 objects to. |
| `repoPath`, `filePath`, `fileHash`, `fileSize`, `archivePath` | Filesystem layout of a `@PermitAll` repository (`RepositoryService` is class-level `@PermitAll`). Publishing internal paths to every logged-in user is the same class of mistake as putting the archive under the repo root. |
| `releaseMode`, `nextIssueCreation`, `firstIssueStartsAt` | Automation controls. Read-only *display* of `releaseMode` is a settled UX requirement but it is displayed on the admin screens, which are admin-tier. |
| `nameSuggestionPattern`, `fileNamePattern`, `linkPattern`, series-level `messageReferenceFormat` | Patterns are authoring. The *expanded* per-issue values are on the editor tier, which is what the citation dialog actually consumes. |
| `IssueOverride.author`, `IssueOverride.reason` | **C9's explicit amendment to the settled why-line decision.** Delivered as an absent-vs-present nested object, §3.8. |
| `statusChangedBy`, `statusChangeReason`, `publishedBy`, `retiredBy`, `retiredReason`, `importSource`, `legacyTemplateId`, `legacyPublicationId`, `membershipProvenanceNote` | Operator identity and provenance prose. |

**`Permissions.PUBLICATION_CURATE`.** The repo has no permission framework — only the four composite role constants. C1 requires a *named permission, not a role literal*, so:

```java
package org.niord.core.user;

/** Named permissions, checked with @RolesAllowed exactly as Roles constants are. */
public interface Permissions {
    /** Grants the right to add or remove an issue member by hand (C1). */
    String PUBLICATION_CURATE = "publication-curate";
}
```

`@RolesAllowed` accepts it because it is a compile-time `String` constant, exactly as `Roles.ADMIN` is (`Roles.java:24-27` states this is why they are strings). It is a Keycloak **client role** per domain client, added to each `admin` composite. Ticket-carried roles satisfy it too, because `UserService.isCallerInRole` reads `ticketData.getRoles()` for an anonymous identity (`UserService.java:182-193`). **It fails closed for every existing admin until Keycloak is edited — see OQ-1.**

---

## 2. Endpoint table

Tier: **A** anonymous · **E** editor (`Roles.USER`) · **AD** admin (`Roles.ADMIN`) · **SA** sysadmin (`Roles.SYSADMIN`) · **CUR** (`Permissions.PUBLICATION_CURATE`).
All methods carry `@GZIP @NoCache @Produces("application/json;charset=UTF-8")` unless the response column says otherwise.

### 2.1 Series — `/rest/publication-series`

| # | Verb + path | Tier | Purpose |
|---|---|---|---|
| **S1** | `GET /search` | **E** | Series list, lean VO. Default `status=ACTIVE`. *Amended 2026-08-29 (R-xxvi): `?domain=` narrows by **owner** (§6.3); omitting it returns the estate.* |
| **S2** | `GET /search-details` | **AD** | Same query, `SystemPublicationSeriesVo`. *Amended 2026-08-29 (R-xxvi): same owner filter.* |
| **S3** | `GET /series/{seriesId}` | **E** | One series, lean. |
| **S4** | `GET /editable-series/{seriesId}` | **AD** | One series, system VO, **all configured languages** (no `lang` narrowing — C5 makes the payload uneditable otherwise). |
| **S5** | `GET /series-by-ids/{seriesIds}` *(comma-separated)* | **E** | Bulk hydration for pickers and restored filters. Unknown ids omitted. **The path is deliberately *not* `/series/{seriesIds}`:** that template is character-for-character identical to S3's `/series/{seriesId}` — same literal count, one template param, no regex, same verb, same `@RolesAllowed` — so JAX-RS 3.1 §3.7.2 puts the two in one equivalence class and which one wins is implementation-defined. Legacy avoids the collision the same way: the single admin read is `/editable-publication/{id}` (`PublicationRestService.java:238-241`) while the bulk read owns `/publication/{ids}` (`:223-224`). |
| **S6** | `GET /new-series-template` | **AD** | Unsaved `SystemPublicationSeriesVo`: `status=DRAFT`, defaults by `contentMode`, one desc row per configured language. |
| **S7** | `GET /copy-series-template/{seriesId}` | **AD** | Unsaved copy: blank `seriesId`, `status=DRAFT`, `legacyTemplateId`/`importSource` cleared, patterns + criteria carried. |
| **S8** | `POST /series/` | **AD** | Create. |
| **S9** | `PUT /series/{seriesId}` | **AD** | Update. |
| **S10** | `PUT /series/{seriesId}/status` | **AD** | Lifecycle transition (§4.7). |
| **S11** | `DELETE /series/{seriesId}` | **AD** | Delete — only a series with **zero** issues (§4.8). |
| **S12** | `GET /series/{seriesId}/issue-draft` | **AD** | The one draft endpoint serving "＋ Ny udgave", retro-create prefill, and the gap row's live preview (§4.2). |
| **S13** | `POST /resolve-preview` | **AD** | Resolver probe against unsaved criteria. Persists nothing (§3.7, `source=PROBE`). |
| **S14** | `GET /name-tokens` | **E** | The `${…}` vocabulary, filtered by `cadence`/`numberingScheme`, so the frontend's `format-token-input` menu cannot drift from the server validator. |
| **S15** | `POST /validate` | **AD** | Dry-run validation of a series body. Persists nothing. Returns the structured error list without a 4xx. |
| **S16** | `GET /export` | **AD** *(programmatic check, `?ticket=` allowed)* | JSON interchange export, whole-estate by default (§3.13). |
| **S17** | `POST /upload-publication-series` | **AD** | Import the same JSON as a batch job. |
| **S18** | `POST /import-legacy/validate` | **AD** | Dry-run the legacy `publications.json` importer. Writes nothing. |
| **S19** | `POST /import-legacy` | **SA** | Run it. Fails the whole import on the first unknown filter (C4). *Amended 2026-09-25: sysadmin — the run writes into every domain at once, so no single domain's admin decides it.* |
| **S20** | `DELETE /import-legacy` | **SA** | Undo S19: deletes every series carrying the import source, with their issues, members and descs, in every domain; `409` with every reason when an imported series has left DRAFT. Sysadmin for the same reason as S19. |
| **S21** | `PUT /series/{seriesId}/owner` | **AD** | **New 2026-08-29 (R-xxvi).** Move the publication to another domain. `@DomainScoped` + `@VersionChecked`; body `{domainId, reason, version}`; audited `OWNER_TRANSFERRED`. Full spec in the R-xxvi amendment at the foot. |

**`{seriesId}` on every route above is the authored `VARCHAR(64)` business key** (`PublicationSeries.seriesId`), never a UUID and never the surrogate `id` — ruling **R3**, matching `MessageSeries.seriesId`, `PublicationCategory.categoryId`, `FmReport.reportId` and `Domain.domainId`. Legacy provenance travels separately in `legacyTemplateId VARCHAR(36)`. It is the same value the JSON envelope upserts on (§3.13) and the same value the wire field `publicationSeriesId` carries (§3.1).

### 2.2 Issues — `/rest/publication-issues`

| # | Verb + path | Tier | Purpose |
|---|---|---|---|
| **I1** | `GET /search` | **E** | Issue list + merged gap/upcoming pseudo-rows (§6.4). |
| **I2** | `GET /search-details` | **AD** | Same, `SystemPublicationIssueVo`. |
| **I3** | `GET /issue/{publicId}` | **E** | One issue, lean. |
| **I4** | `GET /editable-issue/{publicId}` | **AD** | One issue, system VO, all languages. |
| **I6** | `GET /by-message/{messageUid}` | **E** | **C9's named editor capability**: the issues a message *is in*, plus, for OPEN issues of matching series, the one it *would land in*. Served by the `issue_member_uid_k` index. |
| **I7** | `POST /issue/` | **AD** | Create (accepts S12's draft verbatim). |
| **I8** | `PUT /issue/{publicId}` | **AD** | Update an OPEN issue: names/descs/interval/`criteriaOverride`/`reportParams`/**`edition`** (amended 2026-09-09). |
| **I9** | `DELETE /issue/{publicId}` | **AD** | Delete — never-published only (§4.10). |
| **I10** | `GET /issue/{publicId}/members` | **E** | The resolved member list with why-lines (§3.7). LIVE for OPEN, FROZEN for PUBLISHED/RETIRED — keyed off status, **no request parameter**. |
| **I11** | `GET /issue/{publicId}/audit` | **AD** | The Historik panel (§3.11). **Newest first** — `created DESC, id DESC`, the same order as I33 (amended 2026-09-09). |
| **I12** | `PUT /issue/{publicId}/overrides/include` | **CUR** | Add a message **selection** to the member set (§4.11). |
| **I13** | `PUT /issue/{publicId}/overrides/exclude` | **CUR** | Remove a selection (§4.11). |
| **I14** | `PUT /issue/{publicId}/overrides/clear` | **CUR** | Drop overrides for a selection (§4.11). **Reason required**, like the other two. |
| **I15** | `GET /issue/{publicId}/publish-checklist` | **AD** | Server-authoritative release rail (§3.10). |
| **I16** | `PUT /issue/{publicId}/publish` | **AD** | §4.3. |
| **I17** | `PUT /issue/{publicId}/amend` | **AD** | §4.4. |
| **I18** | `PUT /issue/{publicId}/retire` | **AD** | §4.5. |
| **I19** | `PUT /issue/{publicId}/reactivate` | **AD** | §4.6. |
| **I20** | `POST /issue/{publicId}/next-issue` | **AD** | Create the successor manually (§4.9). |
| **I21** | `POST /issue/{publicId}/preview` | **AD** | Render per-language previews (§5). |
| **I22** | `GET /issue/{publicId}/preview` | **AD** | Preview metadata + computed `stale`. |
| **I23** | `GET /issue/{publicId}/preview.pdf` | **AD** *(programmatic check, `?ticket=`)* | Stream one preview PDF. `application/pdf`. |
| **I24** | `POST /issue/{publicId}/file/{lang}` | **AD** | Multipart upload of a per-language file (C6). **Legal on OPEN *and* on PUBLISHED** — the post-publish correction path (§4.12). |
| **I25** | `DELETE /issue/{publicId}/file/{lang}` | **AD** | Clear a per-language file on an OPEN issue (§4.12). |
| **I26** | `GET /issue/{publicId}/archive/{auditEntryId}/{lang}` | **AD** *(programmatic check, `?ticket=`)* | Stream an archived superseded file (C3). Resolves against **any** audit entry carrying a non-null `archivePath` — `AMENDED`, `PUBLISHED` **or `FILE_REPLACED_MANUALLY`** (§3.11). |
| **I27** | `GET /picker` | **E** | The replacement publication picker for the four shipped frontend consumers (§9.3). `@RolesAllowed(Roles.USER)`. |
| **I28** | `GET /by-ids` | **A** | Picker/chip hydration carrying `status`. Anonymous, **by explicit id only**; ignores `status` and `category.publish`; unknown ids silently omitted. *2026-09-25 (security review F-X85):* a caller without the `user` role gets `link: null` on every desc of an issue that is not `PUBLISHED` or `RETIRED`; the row itself, title and `status` included, still resolves. |
| **I29** | `GET /recent?publicationSeriesId=a,b,c&periods=N` | **E** | The dashboard's per-series timeline strip, in **one** request (§6.7). |
| **I30** | `GET /issue/{publicId}/workbench?lang=` | **CUR** | **New 2026-09-07.** The whole issue screen in one response, from **one** resolution: I4's issue, I10's members, I11's audit, `GET /overrides`, I15's rail (OPEN + admin only) and the omissions the criteria probe used to answer. Field-for-field the shapes of the endpoints it composes, all of which stay. Full spec in the 2026-09-07 amendment at the foot. |
| **I31** | `GET /recent-by-domain?domain=&periods=&unscheduledIssues=&lang=[&cadenced=]` | **AD** | **New 2026-09-07.** I29's strip for a **desk** rather than for a named list: the server picks the series the domain owns by the dashboard's own rule, so the strip goes out beside the series list instead of after it. **One call answers both kinds** (evening amendment): cadenced series get `periods` cells, cadence-less series their newest `unscheduledIssues` rows; `cadenced` is optional and, when given, narrows to that one kind. `domain` required (`400 DOMAIN_REQUIRED`); an empty desk answers `[]`. I29 is untouched. Full spec in the 2026-09-07 amendment at the foot. |

> **I27 is not anonymous.** `@RolesAllowed(Roles.USER)`. The earlier justification — "the print flow is anonymous-reachable" — is **false and retired**: printing is `report.pdf` behind a one-time ticket, and all four shipped consumers (§9.3) are authenticated. An anonymous `GET /picker` with the §6.3 issue-status default of "none (all three)" would let an unauthenticated caller enumerate OPEN and RETIRED issues with their names and links — precisely the leak C9 and §10 say is deliberately not reproduced.
>
> **I28 is anonymous, and that is not enumeration.** It resolves only ids the caller already holds, which is exactly the resolvability floor P4 (§9.5) and the frozen §9.3 contract ("anonymous comma-separated hydration … ignore status … silently omit unknown ids") demand — a chip for a **retired** issue must still render its title. It therefore applies **no** status narrowing; narrowing it to PUBLISHED would break the retired-citation chip.
>
> I27/I28 duplicate I1/I3 in shape but not in payload: they are the *frontend picker* contract and their DTO is deliberately minimal (§3.16). Keeping them separate is what lets I1/I3 evolve with the admin UI without touching the citation dialog. **Issue hydration by comma-separated ids lives at I28 and nowhere else** — there is no `GET /issue/{publicIds}`, because that template collides with I3's `GET /issue/{publicId}` exactly as S3/S5 would (see §2.1); §9.4 already re-points the shipped hydration call to I28. **The identifier I5 is retired** and its number is deliberately left as a gap so no later reference can silently re-bind to something else.

### 2.3 Categories — `/rest/publication-categories` (existing resource, extended)

Categories are **not re-modelled** — 15 of the 17 ledger rows are PRESERVED. The redesign changes one entity default, surfaces one existing column, and fixes two live defects.

| # | Verb + path | Tier | Change |
|---|---|---|---|
| **C1** | `GET /all?lang=&limit=` | **A** | **Unchanged, including the parameter name `limit`** (verified `PublicationCategoryRestService.java:66` — `@QueryParam("limit") @DefaultValue("1000")`). Note the asymmetry with `/publications/all`, which uses `maxSize` (`PublicationRestService.java:188`). The asymmetry is ugly and is the shipped external contract; do not "fix" it. |
| **C2** | `GET /search` | **AD** | **New.** Paged, adds `usage` counts. |
| **C3** | `GET /publication-category/{categoryId}` | **A** | **Fix:** returns **404** for an unknown id. Today `findByCategoryId` returns `null` and `.toVo(...)` is called on it unguarded (`PublicationCategoryRestService.java:81-84`) → a 500. |
| **C4** | `POST /publication-category/` | **AD** | Entity default `priority` becomes **100** (data model §3.4, and §9.1's altered-table row, which owns the ratification). **Not an OQ of this contract** — the earlier "ratification is OQ-8" pointed at the preview-URL question and was simply the wrong number; the decision is the data model's, and this row must not re-open it under a second identifier. |
| **C5** | `PUT /publication-category/{categoryId}` | **AD** | Unchanged 400 on path/body mismatch; `categoryId` **immutable after create**. |
| **C6** | `DELETE /publication-category/{categoryId}` | **AD** | **Fix:** pre-flight count → **409 `CATEGORY_IN_USE`** naming the number of series and legacy publications still referencing it. Today only the DB FK stops it, surfacing as an opaque transaction error. |
| **C7** | `POST /upload-publication-categories` | **AD** | Unchanged. Upsert by `categoryId`. |

**Two things that must not change.** C1 and C3 stay anonymous: the admin "Export…" menu item is a literal anonymous `<a href="/rest/publication-categories/all">` with no ticket and no role, and external consumers read categories anonymously. Tightening either silently breaks the documented export path.

**Closing CAT-004 requires no schema change.** `PublicationCategoryDesc.description` already exists as `VARCHAR(255)` and `descDefined()` already counts it. The gap is *visibility*: it must be carried on both VOs (it already is) **and rendered** on the new categories page. That, plus showing `categoryId` in the list — legacy hides the interchange key behind the edit form — is the whole fix.

---

## 3. DTO definitions

Legend: **N?** = nullable on the wire. **Hides** lists the entity columns the DTO deliberately omits at that tier.

### 3.1 `PublicationSeriesVo` — EDITOR

**Exposes** (all from `PublicationSeries` / `PublicationSeriesDesc` unless noted):

| Field | Java type | N? | Source column | Semantics |
|---|---|---|---|---|
| `publicationSeriesId` | `String` | no | `seriesId` | The **authored `VARCHAR(64)`** business key (ruling **R3**) — authored by the admin, never generated, never a UUID, never the surrogate `id`. **Never named `seriesId` on any wire surface** — that word means *message* series in `MessageSeries.seriesId` and in `MessageSearchParams.seriesIds`, and this contract must never put a bare `seriesId` on an endpoint that also accepts message criteria. Legacy provenance is carried separately by `legacyTemplateId VARCHAR(36)`. |
| `descs[]` | `List<PublicationSeriesDescVo>` | no | `descs` | One row per configured language. |
| `languages[]` | `List<String>` | no | `languages` (ordered `@ElementCollection`) | The configured language list, **in order**. Order is meaningful — it is the render order of every per-language UI row. |
| `category` | `PublicationCategoryVo` | no | `category` | Whole nested VO; unchanged code path. |
| `domainId` | `String` | ~~yes~~ **no** | `domain.domainId` | **Amended 2026-08-29 (R-xxvi): the OWNER, and required.** The one domain the publication is listed in, administered from, and whose timezone its cut-offs are read in — for every kind, one-offs included. `null` is no longer a value on the wire or in the column (S-20a; `domain_id` made `NOT NULL` by V13). Reachability from other domains is `availability`'s job (§3.3). An absent `domainId` on an update **leaves the stored owner alone**; it no longer clears it. <br>*Superseded text, kept for the reader who meets it elsewhere:* "**`null` means global.** 17 production rows are domain-free and are reachable from every domain." |
| `contentMode` | `ContentMode` | no | `contentMode` | `GENERATED_FROM_QUERY \| UPLOADED_FILE \| EXTERNAL_LINK \| NONE`. |
| `cadence` | `SeriesCadence` | no | `cadence` | `NONE \| DAILY \| WEEKLY \| MONTHLY \| YEARLY`. `NONE` **is** the one-off. |
| `nominalCutoff` | `NominalCutoffVo` | **yes** | the five `nominalCutoff*` columns | `{ day, dayOfMonth, month, time, timeZone }`, nested purely for readability; every member is a column with the `nominalCutoff` prefix stripped by the nesting, so `day` **is** the `nominalCutoffDay` column. Ruling **R4**: the weekday column is `nominalCutoffDay` and nothing else — **never `dayOfWeek`, never `nominalCutoffDayOfWeek`**; the data-model field table is authoritative and this VO carries no third name. `null` when `cadence = NONE`. |
| `numberingScheme` | `NumberingScheme` | no | `numberingScheme` | |
| `timeRelation` | `TimeRelation` | **yes** | `timeRelation` | Required iff `contentMode = GENERATED_FROM_QUERY`. **The field that decides whether tiling, gap detection and chaining run at all.** |
| `aliveAtCutoff` | `Boolean` | **yes** | `aliveAtCutoff` | **Ruling R2.** Nullable Boolean: **`null` exactly when the series is not query-backed** (`contentMode ≠ GENERATED_FROM_QUERY`, i.e. `timeRelation` is null) — and in that case only. It is **`true`, constrained, under `IN_FORCE_AT_CUTOFF`**, because that relation *is* `publishDateFrom <= cutoff AND (publishDateTo IS NULL OR publishDateTo >= cutoff)` and the second half is the alive clause itself; `null` there would falsely read as "not applied", and the constraint requiring `TRUE` is what 531 of the 1,031 message-backed series would otherwise fail to save against. Free (`true`/`false`) under `PUBLISHED_IN_INTERVAL`. So the resolver reads **one non-null boolean for every query-backed series**, with no special case, and the frozen echo (`snapshotAliveAtCutoff`, §3.4) is always populated for a query-backed issue. |
| `messagePublication` | `MessagePublication` | no | `messagePublication` | `NONE \| INTERNAL \| EXTERNAL`. Drives the citation picker (ADD-020) and `PublicationUtils`' choice of message field. |
| `status` | `SeriesStatus` | no | `status` | `DRAFT \| ACTIVE \| RETIRED`. |
| `dormant` | `boolean` | no | **(derived, not persisted)** | `status = ACTIVE && cadence != NONE && lastIssueAt older than N cadence periods`. Suppresses gap warnings; nothing else. `DORMANT` is **not** a status value. |
| `criteriaSummary[]` | `List<CriteriaSummaryDescVo>` | no | **(derived)** from `criteria` | Rendered, read-only, per-language chips: `[{ lang, chips: [{ kind, operator, values: [{ value, label }] }] }]`. Labels resolved live. Enough to draw a why-line; never the document. |
| `issueCounts` | `IssueCountsVo` | no | **(derived)** | `{ open, published, retired, gaps }`. `gaps` is `null` when `gapDetection = false`. It is a **count, not a timeline** — it can say *four gaps*, never *which weeks*; the dashboard's per-series strip comes from I29 (§6.7). |
| `lastIssueAt` | `Date` | yes | **(derived)** — max `cutoffStampedAt` | Dashboard sort key and the dormancy input. |
| `sortOrder` | `Integer` | yes | `sortOrder` | Stored and editable; no list reads it (§8.4). |

**Hides:** `id`, `version`, `created`, `updated`, `legacyTemplateId`, `importSource`, `statusChangedAt/By/Reason`, `firstIssueStartsAt`, `criteria`, `reportId`, `pageSize`, `pageOrientation`, `mapThumbnails`, `messageSortBy`, `messageSortOrder`, `reportParams`, `releaseMode`, `nextIssueCreation`, `languageSpecific`.

### 3.2 `PublicationSeriesDescVo` — EDITOR

**Exposes:** `lang`, `name`.
**Hides:** `nameSuggestionPattern`, `fileNamePattern`, `messageReferenceFormat`, `linkPattern` — all authoring patterns. The *expanded* per-issue values are on `PublicationIssueDescVo`, which is what the citation dialog consumes.

### 3.3 `SystemPublicationSeriesVo extends PublicationSeriesVo` — ADMIN

**Adds**, every one a column:

| Field | Java type | N? | Column | Note |
|---|---|---|---|---|
| `version` | `Integer` | no | `version` | **Required on every PUT.** |
| `created` / `updated` | `Date` | yes | `created` / `updated` | Read-only. `updated` is a last-write stamp and **must never be rendered as a release moment**. |
| `legacyTemplateId` | `String` | yes | `legacyTemplateId` | Import provenance; the IMP-008 relink key. |
| `importSource` | `String` | yes | `importSource` | Free-text provenance. |
| `statusChangedAt` / `statusChangedBy` / `statusChangeReason` | `Date` / `String` / `String` | yes | same | `statusChangedBy` serialises as the user's **name**, matching `MessageHistory.toVo()`'s `history.setUser(user.getName())`. |
| `firstIssueStartsAt` | `Date` | yes | same | The chain's base case. Required iff `timeRelation = PUBLISHED_IN_INTERVAL`. |
| `availability` | `SeriesAvailability` | no | `availability` | **New 2026-08-29 (R-xxvi).** `OWNER_ONLY \| SELECTED_DOMAINS \| ALL_DOMAINS` — who besides the owner may **see and cite** the publication. Never grants a write: the guard compares the owner and nothing else, so a shared publication is read-only wherever it is shared, and it never reaches `/public/v1/*` (the public site stays category-driven and domain-blind). Omitted on **update** = unchanged; omitted on **create** = defaulted from `contentMode` — `GENERATED_FROM_QUERY` → `OWNER_ONLY`, everything else (uploaded file, external link, `NONE`) → `ALL_DOMAINS`, the same rule the new-series template, the one-off create and the legacy importer apply. |
| `availableDomainIds` | `List<String>` | no | `PublicationSeries_AvailableDomain` | **New 2026-08-29 (R-xxvi).** The domains `SELECTED_DOMAINS` names, by `Domain.domainId`. **Never carries the owner** — it is dropped on the way in rather than refused, because the owner is already the strongest form of "visible from here" and storing it would round-trip to a different list. A **full representation**, unlike `domainId`: an absent or empty list means "shared with nobody", which is a state the editor must be able to save. Sent with any other `availability` it is ignored and comes back empty. An unknown **or inactive** domain id is `400 SERIES_INVALID` (an inactive domain is ignored by the visible-from predicate, so the row would read as a setting that is switched on and does nothing). |
| `criteria` | `IssueCriteriaVo` | yes | `criteria` | §3.6. |
| `reportId` | `String` | yes | same | Soft reference to `FmReport.reportId`. |
| `pageSize` | `PageSize` | yes | same | All **eight** legacy values (`A3 A4 A5 B4 B5 LETTER LEGAL LEDGER`). Typing the field must not shrink the enumeration. |
| `pageOrientation` | `PageOrientation` | yes | same | |
| `mapThumbnails` | `Boolean` | yes | same | |
| `messageSortBy` / `messageSortOrder` | `String` / `SortOrder` | yes | same | Promoted out of the untyped `printSettings` map. |
| `reportParams` | `Map<String,Object>` | no | same | Free-form **series-level** render parameters. **`week`, `weekTo`, `weeks`, `year`, `edition`, `yearNumber` and `weekNumber` are rejected** — injected from the issue. The four strings print as typed, labels and all; the two numbers (`Integer`, absent where the issue has none) carry what the numbering derived, for the one thing a template computes from a year rather than prints. An issue may carry its own map (`SystemPublicationIssueVo.reportParams`, §3.6); at render time the issue map is overlaid on this one key-by-key (issue wins; a key absent from the issue map keeps the series value), and the injected numbering is applied last. **`weeks`** is the issue's weeks as the series' name pattern writes them, in the name the series suggests for the issue, which the weekly reports' heading prints: the week part of the pattern in the report's language, expanded as that name is (`36` under `Uge ${week}, ${year}`, `36 og 37` under `Uge ${week} og ${weekTo}`, `36+37` or `36-38` under the pair form `${week}+${weekTo}`, which is also what a pattern without a week gives; a single week under a pattern that writes `${weekTo}` is its week alone, where the name keeps the joiner, ISSUE13), or the week label whole. The pattern is read at the render, so an issue renamed by hand or named before the pattern changed is headed by the pattern's weeks; the week label heads one issue otherwise. |
| `releaseMode` | `ReleaseMode` | no | same | `MANUAL_GATE \| AUTO_RELEASE`. **Writable only through S9.** |
| `nextIssueCreation` | `NextIssueCreation` | no | same | |
| `languageSpecific` | `boolean` | no | same | Legacy parity only. |
| `descs[]` | `List<SystemPublicationSeriesDescVo>` | no | | Adds `nameSuggestionPattern`, `fileNamePattern`, `messageReferenceFormat`, `linkPattern`. |

**Still hides:** `id` (the surrogate PK never leaves the backend, on any tier — it is not an interchange key and exposing it invites a client to key on it).

**There is no `printSettings` map on the wire, at any tier.** The six typed columns replace it. The passthrough is *deleted*, and an import carrying any key outside `{report, pageSize, pageOrientation, mapThumbnails, fileName, debug, sortBy, sortOrder}` fails loudly with `PRINT_SETTING_NOT_ALLOWED` naming the key. The reason is mechanical and verified: an untyped map is carried as a **query string**, and `report.pdf` instantiates **both** `MessagePrintParams` and `MessageSearchParams` from that one string (`MessageReportRestService.java:288-291`) while `MessagePrintParams` reads only six keys — so a `printSettings` entry named `sortBy`, `status`, `type`, `area`, `messageSeries`, `fromDate` or `query` falls through to the **message search** and silently rewrites what is in the PDF and in what order. The second reason the passthrough is deleted rather than carried forward: the publication renderer is now **in-process and takes typed parameters plus an ordered member list** (§4.3 step 10) — it has no query string for an untyped key to leak into, so a map would be a settings channel with no defined consumer.

### 3.4 `PublicationIssueVo` — EDITOR

| Field | Java type | N? | Source | Semantics |
|---|---|---|---|---|
| `publicId` | `String` | **yes** | `publicId` | Lowercase UUID. **`null` only on a pseudo-row** (§3.14) — every real issue has one from creation. |
| `publicationSeriesId` | `String` | no | `series.seriesId` | |
| `descs[]` | `List<PublicationIssueDescVo>` | no | `descs` | |
| `status` | `IssueStatus` | no | `status` | `OPEN \| PUBLISHED \| RETIRED` — the only three persisted. |
| `computedStatus` | `ComputedIssueStatus` | no | **(derived, not persisted)** | `UPCOMING \| OPEN \| PUBLISHED \| RETIRED \| MISSING`. §6.3. |
| `intervalFrom` | `Date` | **yes** | `intervalFrom` | `null` for `IN_FORCE_AT_CUTOFF` — **not an omission, a fact**: 531 production issues have no lower bound at all. |
| `intervalTo` | `Date` | **yes** | `intervalTo` | The nominal cut-off. Present for both time relations; for `IN_FORCE_AT_CUTOFF` it is an upper bound and **not** the end of an interval. |
| `intervalFromSource` / `intervalToSource` | `IntervalBoundSource` | yes | `intervalFromSource` / `intervalToSource` — **real columns** | `STAMPED \| NOMINAL \| RECOVERED \| MANUAL`. Drives the required "(stemplet)" / "(nominel)" marker on every interval. **Ruling R4: these are stored, not derived.** A derivation from the chain plus `cutoffSource` cannot produce `MANUAL` at all — an admin who types an interval by hand leaves no trace the derivation can read — so the enum declared a value the wire could never emit. The writer is whoever sets the bound: create and `issue-draft` chaining write `STAMPED` or `NOMINAL`, retro-creation writes `RECOVERED`, an explicit admin edit through I8 writes `MANUAL`. |
| `cutoffStampedAt` | `Date` | yes | `cutoffStampedAt` | Millisecond precision. Null until published. |
| `effectiveCutoff` | `Date` | yes | **(derived)** `cutoffStampedAt ?? intervalTo` | Emitted so no client re-implements the coalesce. |
| `cutoffSource` | `CutoffSource` | yes | `cutoffSource` | Six values (§ data model 7.8). |
| `cutoffReconstructed` | `boolean` | no | `cutoffReconstructed` | The issue-list badge predicate. |
| `publicFrom` / `publicTo` | `Date` | yes | same | **Ruling X1.** The public *visibility* window — offset from the content interval by one period. Never render these as the interval. |
| `publicWindowSource` | `PublicWindowSource` | no | same | `DERIVED \| MANUAL`. |
| `membershipProvenance` | `MembershipProvenance` | yes | same | `EXACT \| EXPLAINED_DIFF \| UNION_SNAPSHOT \| NO_MEMBERSHIP`. |
| `memberCount` | `Integer` | yes | **(derived)** from the `memberCount` column (frozen) / the live count (OPEN) | The column is `INTEGER NOT NULL DEFAULT 0` and stays that way; the **wire** field is tri-state and derived by one stated rule: **`membershipProvenance = NO_MEMBERSHIP` → `null`, otherwise the column** (frozen) or the live resolved count (OPEN). Without the rule the ~48 `NO_MEMBERSHIP` publications emit `0`, which this contract's own text reads as "resolved, empty" — the exact confusion `membershipProvenance` exists to prevent, reintroduced one field along. `0` therefore means "resolved, empty"; `null` means "this series has no membership semantics". Invariant I-11 (member row count = `memberCount`) is a clean equality against the **column**, unaffected. |
| `week` / `weekTo` / `year` | `Integer` | yes | same | ISO week + **ISO week-year** of the *effective cut-off*, in the series' `nominalCutoffTimeZone`. |
| `edition` | `String` | yes | `edition` | **String, not integer** — `"2025"`, `"v2"` and three live rows carrying a literal `${year}` all round-trip. |
| `supersedesPublicId` | `String` | yes | `supersedes.publicId` | The FK, projected onto the external handle. |
| `publishedAt` | `Date` | yes | `publishedAt` | The publish *action*. Distinct from `cutoffStampedAt`, by design. |
| `snapshotTimeRelation` / `snapshotAliveAtCutoff` | `TimeRelation` / `Boolean` | yes | same | Echoed so the issue screen can state the rule that was applied without re-reading the series (which is editable after publish). Nullability is the data-model **field table's**, which is the single normative statement — the duplicate table is deleted and cross-referenced, not restated here (ruling **R4**). Per ruling **R2**, `snapshotAliveAtCutoff` is `null` **exactly when the issue's series was not query-backed at freeze**; for a query-backed issue it is always populated, and it is `true` whenever `snapshotTimeRelation = IN_FORCE_AT_CUTOFF`. |
| `criteriaSummary[]` | `List<CriteriaSummaryDescVo>` | no | **(derived)** from the **effective** criteria | Same shape as §3.1's: `[{ lang, chips: [{ kind, operator, values: [{ value, label }] }] }]`, labels resolved **server-side**. The effective document is `criteriaSnapshot` for PUBLISHED/RETIRED and `criteriaOverride ?? series.criteria` for OPEN. **This is the issue screens' only criteria source** — the raw documents stay admin-only (§1.3), so without it an editor sees no criteria at all, an admin has to client-side join stable keys to labels against the areas/categories/series endpoints, and an issue carrying a `criteriaOverride` would render the **series'** criteria beside an override-resolved member list. |
| `criteriaOverridden` | `boolean` | no | **(derived)** | `true` iff the effective document above came from `criteriaOverride` (OPEN) or from a `criteriaSnapshot` that differs from `series.criteria` at freeze. Lets the panel label itself *"(fra serien)"* vs *"(tilpasset for denne udgave)"* without diffing two documents client-side. |
| `cadence` | `SeriesCadence` | no | `series.cadence` | Echoed from the series so a row can render its own rhythm — and so the dashboard can tell a one-off (`NONE`) from a cadenced issue without a second fetch. |
| `releaseMode` | `ReleaseMode` | no | `series.releaseMode` | Echoed **read-only**. Display of `releaseMode` beside an issue is a settled UX requirement; it is writable only through S9, and §1.3 hides only the *series-tier* automation controls, not this echo. |
| `pseudo`, `sortKey`, `wouldMatchCount`, `precedingPublicId`, `followingPublicId`, `suggestedDescs[]` | | | | Pseudo-row fields only; §3.14. |

**Hides:** `id`, `version`, `repoPath`, `legacyPublicationId`, `publishedBy`, `retiredAt`, `retiredBy`, `retiredReason`, `membershipProvenanceNote`, `snapshotFrozenAt`, `snapshotIntervalFrom`, `snapshotSeriesIds`, `snapshotDomainId`, `snapshotSortBy`, `snapshotSortOrder`, `criteriaSnapshot`, `criteriaOverride`. The editor tier reaches the frozen lower bound where it is actually needed — as `intervalFrom` on a FROZEN member list (§3.8), which serves it from `snapshotIntervalFrom`.

### 3.5 `PublicationIssueDescVo` — EDITOR

**Exposes:** `lang`, `name`, `fileName`, `link`, `messageReferenceFormat`, `fileSource`.

- `link` is served **repo-relative** (`/rest/repo/file/…`) on the admin/editor API. Only the public adapter externalises it (§8.5). Storing or serving an absolute repo URL makes `externalize` a no-op and bakes the wrong host into every environment.
- `messageReferenceFormat` is the **already-substituted** per-issue text (`EfS 27/2026`, possibly still carrying `${parameters}`). It is on the editor tier because the citation dialog reads it to compute `hasParams()`.
- `fileSource` (`GENERATED | UPLOADED`) is on the editor tier so the list can render C6's badge. It is **never** on `PublicationVo` — which could not carry it anyway (§8.1).

**Hides:** `filePath`, `fileSourceSticky`, `replacedBy`, `replacedAt`, `fileGeneratedAt`, `fileSize`, `fileHash`, `nameOverridden`.

`SystemPublicationIssueDescVo` adds all eight — but two of them are **read-only echoes, never accepted from a request body**: `nameOverridden` is set by the server whenever a saved `name` differs from the name currently stored for that language — **not** from the pattern expansion, so a client that wants a name to keep following the series sends `''` (the clear) and never the expanded text, which would pin the name to today's rendering of the pattern — and `fileSourceSticky` is written only by I24 / I25 and by publish step 10 (§4.12, §4.3). A client that sends either is ignored, not obeyed.

### 3.6 `SystemPublicationIssueVo extends PublicationIssueVo` — ADMIN

**Adds:** `version`, `created`, `updated`, `repoPath`, `legacyPublicationId`, `publishedBy` (name), `retiredAt`, `retiredBy` (name), `retiredReason`, `membershipProvenanceNote`, `snapshotFrozenAt`, `snapshotIntervalFrom`, `snapshotSeriesIds[]`, `snapshotDomainId`, `snapshotSortBy`, `snapshotSortOrder`, `criteriaSnapshot`, `criteriaOverride`, `reportParams`, and three derived operational fields:

| Field | Type | Derived from |
|---|---|---|
| `overrideCounts` | `{ include: int, exclude: int, ineffective: int }` | live count of `IssueOverride` rows; `ineffective` = stale EXCLUDEs + redundant INCLUDEs (computed, never stored) |
| `previewStale` | `boolean` | §5.3 |
| `archiveEntryCount` | `int` | count of `IssueAuditEntry` rows with a non-null `archivePath` — `AMENDED`, `PUBLISHED` **and `FILE_REPLACED_MANUALLY`** entries alike (§3.11) |

> **`reportParams` is a column on `PublicationIssue`, not only on `PublicationSeries`.** I8 makes it writable per issue, so it needs somewhere to persist: `reportParams TEXT` with `@Convert(JpaPropertiesAttributeConverter)` on `PublicationIssue`, exactly as the series column is declared. Legacy already carried render parameters **per publication** (`Publication.java:240-241, 285-287`), so a series-only column drops a capability that is still in use. It takes the same key validation as the series map and the **same rejection of `week`, `year` and `edition`** (§3.3) — those are injected from the issue itself and an authored copy would silently disagree with the derived one. Merge rule at render time, stated once: **series map, then the issue map overlaid key-by-key, then the injected `week`/`year`/`edition` last.** Without this column the field is neither a column nor derived by a stated rule, which is precisely what §0.3 forbids.

### 3.7 `IssueCriteriaVo` — the criteria document (ADMIN only)

Serialised verbatim as the stored document. Envelope and node shape are owned by the criteria section; restated here only as the wire contract.

```json
{
  "schemaVersion": 1,
  "match": "ALL",
  "criteria": [
    { "kind": "messageSeries", "operator": "IN", "values": ["dma-nm"] },
    { "kind": "messageType",   "operator": "IN", "values": ["PRELIMINARY_NOTICE", "TEMPORARY_NOTICE"] }
  ]
}
```

- Discriminator property is **`kind`**, not `type`. The repo's two precedents (`ParamTypeVo`, `SafetyNetAreaVo`) use `"type"` because neither had a collision; here `type` is already the message-type domain word and `{"type":"messageType"}` is exactly the confusion this estate cannot afford.
- `criteria` has **`minItems: 1`** — at least one `messageSeries` or `domain` node. An empty array would mean "every message in the installation", which is legacy's `MEM-009` foot-gun.
- `operator: "NOT_IN"` is **schema-legal and Phase-1 rejected** (`400 CRITERIA_INVALID`, "reserved, not yet supported"). That is C4's pattern applied literally: extensible union, no untested predicate shipped.
- `values[]` holds **stable string keys only** — `seriesId`, enum names, `domainId`, area/category **MRNs** (purely-numeric operands rejected), chart numbers. Never surrogate ids, never labels.
- The server **canonicalises before persisting** (nodes sorted by `(kind, operator)`, `values` sorted ascending). Two documents that mean the same thing compare byte-equal, so a re-save cannot mint a spurious `CRITERIA_OVERRIDDEN` audit entry. A non-canonical document is normalised, not rejected — so a `PUT` may return a `criteria` that is reordered relative to the request body.

**`criteriaSnapshot` is a resolved envelope**, not the bare document — it adds a `resolved` object carrying the inputs that actually ran:

```json
{ "schemaVersion": 1, "match": "ALL", "criteria": [ … ],
  "resolved": { "timeRelation": "PUBLISHED_IN_INTERVAL", "aliveAtCutoff": true,
                "seriesIds": ["dma-nm"], "intervalFrom": 1750000000000, "cutoff": 1751000000000,
                "sortBy": "area", "sortOrder": "ASC",
                "labels": { "messageType": { "PRELIMINARY_NOTICE": "Foreløbig efterretning" } } } }
```

`resolved.labels` is display-only, captured at freeze so a renamed area cannot leave a stale caption on a published issue. It is never re-parsed and never a key.

`resolved.aliveAtCutoff` is the **effective** boolean that ran, with the R2 semantics of §3.1: present and non-null for every query-backed issue, and `true` whenever `resolved.timeRelation = IN_FORCE_AT_CUTOFF`. It is absent only where there is no `resolved` envelope at all, because there was no query.

### 3.8 Member list, member, and the reason payload

#### `IssueMemberListVo` (E) / `SystemIssueMemberListVo` (AD)

| Field | Type | N? | Semantics |
|---|---|---|---|
| `publicId` | `String` | no | The issue |
| `source` | `MemberListSource` | no | `LIVE` (OPEN) \| `FROZEN` (PUBLISHED/RETIRED) \| `PROBE` (S13). **Keyed off issue status; there is no request parameter.** |
| `resolvedAt` | `Date` | no | |
| `cutoff` | `Date` | yes | The instant the resolution used |
| `intervalFrom` / `intervalTo` | `Date` | yes | `intervalFrom` is `null` for `IN_FORCE_AT_CUTOFF`. **Normative source rule, and it differs by list source:** on a **FROZEN** list (`PUBLISHED` / `RETIRED`) `intervalFrom` is the **frozen echo `snapshotIntervalFrom`** — the bound the resolver actually used at freeze — never the live `intervalFrom` column; on a **LIVE** or **PROBE** list it is the live `intervalFrom` column, because there is no snapshot yet. `intervalTo` is the issue row's column on every list. Reading the live column on a frozen list is wrong whenever the neighbour chain has moved since: a retro-created gap issue rewrites its neighbours' chaining, and I8's interval edit can move a bound directly, so the live value can stop describing what the published issue was resolved against. This is the same freeze argument that governs `snapshotTimeRelation` and `snapshotAliveAtCutoff`, one field along. |
| `timeRelation` | `TimeRelation` | yes | Echoed — the UI must be able to state the rule it applied |
| `aliveAtCutoff` | `Boolean` | yes | Echoed, and it carries **the same R2 semantics as the series field** (§3.1) — the two must not be described as different things. On a FROZEN list it is `snapshotAliveAtCutoff`, on a LIVE list the series' `aliveAtCutoff`; either way it is the boolean that actually ran, `null` **exactly when the series is not query-backed**, and `true` whenever `timeRelation = IN_FORCE_AT_CUTOFF`. It is therefore non-null for every query-backed list, members or none. |
| `sortBy` / `sortOrder` | `String` / `SortOrder` | no | The **resolved** order (§6.6) |
| `sortDomainId` | `String` | yes | The **issue's series'** domain, never the caller's |
| `membershipProvenance` | `MembershipProvenance` | yes | |
| `gapDetection` | — | — | *Not here.* It is an issue-**list** concept (§6.4). |
| `total` | `long` | no | Included members |
| `data[]` | `List<IssueMemberVo>` | no | Included, in `sortIndex` order |
| `omitted[]` | `List<OmittedMessageVo>` | no | C2's visible **"Udeladt af kriterierne (N)"** panel. Bounded — see below. |
| `omittedBounded` | `boolean` | no | `true` when `omitted[]` is the bounded "senest udeladt" set rather than an exhaustive one — see below. It belongs **here**, on the member list that produced the omissions, not on the issue-list envelope. |
| `warnings[]` | `List<ResolutionWarningVo>` | no | §3.9 |
| `limitExceeded` | `boolean` | no | `total > 1000` |

**How `omitted[]` is bounded** — **amended 2026-09-07; this paragraph now says what ships, and the design below it never did.** There is no `omitted[]` on the member list and no `OmittedMessageVo` in `…/series/vo/`. The "Udeladt af kriterierne (N)" panel is fed by the **resolution's own misses**: one `CriteriaMissVo {messageUid, code, detail}` for every candidate the predicate rejected, plus the null-dated ones. Two endpoints carry them, with one shape and one cap:

1. **I30**, the issue workbench — `omissions {missCount, misses[]}`, taken from the same resolution as the member rows beside it, so the panel and the list can never answer for different instants. Absent on a frozen issue and where nothing resolved.
2. **S13** `/resolve-preview` — `missCount` + `misses[]` for an unsaved criteria document, which is what the criteria editor's preview reads.

`missCount` is the **full** count; `misses[]` is a **sample of the first 50** in whatever order the candidate query returned them — not a page, not ordered, and not stable between two calls (neither candidate query carries an `ORDER BY`, and the projection may hash them differently than an entity read did). Fifty is one constant, `IssueOmissionsVo.PROBE_SAMPLE`, read by both endpoints so the two screens cannot cap differently. A client renders the rows it got and states `missCount` beside them; a panel that showed the sample and called it the answer would understate an over-narrow document by two orders of magnitude.

*Superseded design (never built):* an `omitted[]` array on the member-list envelope, defined as every EXCLUDE-override target ∪ every ex-member of the previous frozen snapshot ∪ the complement of a **relaxed query** (the predicate with the alive clause and all criteria nodes dropped), bounded under `IN_FORCE_AT_CUTOFF` by the previous issue's stamp or by an `omissionLookbackDays` window and flagged `omittedBounded: true`. `omittedBounded` and `omissionLookbackDays` (OQ-9) are retired with it. The one surviving idea is the bound itself, and it lands in a different place: the resolver's own liveness prefilter (RI-4's date half, in SQL) means an in-force issue no longer *produces* a `NOT_ALIVE_AT_CUTOFF` miss for every long-expired notice in its history — see the 2026-09-07 amendment.

#### `IssueMemberVo` (E) / `SystemIssueMemberVo` (AD)

| Field | Type | N? | Source | Note |
|---|---|---|---|---|
| `uid` | `String` | no | `messageUid` | **THE key. Never `shortId`.** `dma-fa` holds 357 messages under 331 distinct shortIds; a shortId-keyed comparison silently reported 0-diff on issues differing by eight members. |
| `shortId` | `String` | yes | `frozenShortId` (FROZEN) / live (LIVE) | Display and diff aid only. |
| `title` | `String` | yes | live message, requested language | **Implemented 2026-08-31 (R-xxvii)** — the VO had not carried it until then. **Never frozen.** Only facts that decide membership or order are frozen; if a message's text is corrected the snapshot does not change and the archived PDF is the record of what was printed. |
| `mainType` / `type` / `status` | enums | no | `frozenMainType` / `frozenType` / `frozenStatus` (FROZEN); live (LIVE) | On a FROZEN list these are the **frozen** values. |
| `publishDateFrom` / `publishDateTo` | `Date` | **yes** | `frozenPublishDateFrom` / `frozenPublishDateTo` (FROZEN) | Both nullable and both load-bearing: `publishDateTo = null` means *still open*, which is exactly what a NULL-unsafe alive clause drops. |
| `sortIndex` | `int` | no | `sortIndex` | Dense, 0-based, over the **post-override union**. |
| `membership` | `MembershipKind` | no | `source` | `CRITERIA \| OVERRIDE_INCLUDE \| IMPORTED` |
| `reason` | `MemberReasonVo` | no | **(derived)** | Below. |
| `drift[]` | `List<String>` | yes | computed on FROZEN only | Field names where the frozen value ≠ today's: `status`, `type`, and `exists` when the message is gone. **Amended 2026-09-11:** `publishDateTo` is no longer compared — it acquires a value as a side effect of every cancel and expiry and never says anything the status does not. A row that drifted is still a member: it was published when the issue was decided at its cut-off, and what changed happened afterwards. **Surfaced, never healed** — the member list itself never changes. Absent where nothing moved, and absent on a LIVE list. |
| `current` | `{ exists, publiclyVisible, type, status }` | yes | live message | **Amended 2026-09-11:** present on **every** FROZEN member, not only where something drifted — the status a client shows beside a frozen row is the live one, and the frozen one is `PUBLISHED` for every member by construction. Absent on a LIVE list. |

**Hides at EDITOR tier:** nothing on the member row itself. The admin-only data is one hop away, inside `reason.curation` — which is why `IssueMember.override` is an FK rather than copied columns: the redaction is one `instanceof` branch in one place.

#### `MemberReasonVo` — a discriminated union on `kind` **(derived, not persisted — ruling X3)**

`kind` is computed from `IssueMember.source` plus the header's `snapshotTimeRelation`. Three variants:

**1 · `CRITERIA_MATCH`** — fully derivable from the frozen scalars + the snapshot header; nothing is stored per member.
```json
{ "kind": "CRITERIA_MATCH",
  "publishedAt": 1751000000000,
  "withinInterval": true,          // null for IN_FORCE_AT_CUTOFF
  "aliveAtCutoffApplied": true,
  "hits": [ { "kind": "messageType", "operator": "IN",
              "matchedValue": "PRELIMINARY_NOTICE", "label": "Foreløbig efterretning" } ] }
```
Renders `✓ Matcher: publiceret 01.07 13:20, type NM(P)` — past tense on a FROZEN list.

**2 · `OVERRIDE_INCLUDE`**
```json
{ "kind": "OVERRIDE_INCLUDE",
  "createdAt": 1751200000000,
  "outsideCriteriaBecause": [ { "code": "BEFORE_INTERVAL",
                                "publishDateFrom": 1749000000000, "intervalFrom": 1750000000000 } ],
  "curation": { "author": "R. Jensen", "reason": "dækket i uge 26" }   // ADMIN ONLY — see below
}
```
`outsideCriteriaBecause` is the field with no precedent anywhere and the one that makes the line useful: an include override is *by definition* a row the query did not return, so **why not** is the only interesting fact. It reuses the miss vocabulary of §3.9.

**3 · `SNAPSHOT_ONLY`** — required, not optional.
```json
{ "kind": "SNAPSHOT_ONLY",
  "provenance": "UNION_SNAPSHOT",
  "note": "Importeret fra tidligere system" }   // = IssueMember.reasonNote
```
Four annual issues are the answer to **no query at any instant** and 10 annexes have no recoverable member list at all. Without this variant the UI must render a why-line it cannot compute, and an implementer will fabricate one. `note` is the only stored string on a member row.

> **The `curation` object is ABSENT — not `null` — for anything below ADMIN.** On both `OVERRIDE_INCLUDE` and `OVERRIDE_EXCLUDE`. This is the whole of C9's amendment to the settled why-line decision, and it is deliberately one nested object so the redaction is a single branch. Jackson `@JsonInclude(NON_NULL)` on that one property; the system VO populates it, the lean VO never does.

### 3.9 Omissions and warnings

```jsonc
OmittedMessageVo { …same identity fields as IssueMemberVo…, "reason": OmissionReasonVo }

OmissionReasonVo =
    { "kind": "OVERRIDE_EXCLUDE", "createdAt": …, "stale": false,
      "curation": { "author": …, "reason": … } }      // ADMIN ONLY
  | { "kind": "CRITERIA_MISS", "misses": [ CriteriaMissVo, … ] }
```

`CriteriaMissVo.code` — the complete miss vocabulary, one value per way the predicate can drop a row:

| `code` | Extra fields | Renders as |
|---|---|---|
| `BEFORE_INTERVAL` | `publishDateFrom`, `intervalFrom` | publiceret før intervallets start |
| `AFTER_CUTOFF` | `publishDateFrom`, `cutoff` | publiceret efter skæringen |
| `NO_PUBLISH_DATE` | — | ingen publiceringsdato |
| `NOT_ALIVE_AT_CUTOFF` | `publishDateTo`, `withdrawnAt`, `cutoff` — whichever of the two instants fell before the cut-off dropped it; the other may be null (R-xxxii). <br>**Amended 2026-09-07.** The `detail` shape is unchanged, but on the **live path** this code now names the **withdrawal-instant** class alone: RI-4's date half is applied in SQL as well (`MemberResolutionService.narrow()`, only under `aliveAtCutoff`), so a candidate whose `publishDateTo` fell before the cut-off is no longer handed to the predicate and is therefore **removed rather than reported**. What survives under the code is exactly R-xxxii's class — date-alive but withdrawn before the cut-off. This is a report, not a rule: the predicate's own branch is unchanged, and `MembershipPredicate.decide` still emits the date-half miss for facts handed to it directly (which is what `ResolutionDiagnosticsTest` asserts). The reason it matters is magnitude — on an in-force series the removed class is the series' whole expired history, ~5,900 rows on the P&T list | ikke i kraft ved skæringen |
| `STATUS_NOT_PUBLIC` | `status` | ikke publiceret |
| `CRITERION_MISMATCH` | `kind`, `operator`, `expected[]`, `actual` | matcher ikke kriteriet |

`ResolutionWarningVo { code, messageUids[], count, acknowledgeable }`:

| `code` | Fires when | Consequence |
|---|---|---|
| `CANCELLED_BUT_DATE_ALIVE` | member `status ∈ {CANCELLED, EXPIRED}` **and** still a member — `publishDateTo >= cutoff` **and** (`withdrawnAt >= cutoff` or the withdrawal is undated). A message withdrawn *before* the cut-off is a `NOT_ALIVE_AT_CUTOFF` miss instead (R-xxxii) | **C2's signed release-rail mirror.** Surfaces on the checklist as `CANCELLED_MEMBERS_ALIVE_AT_CUTOFF`, **`WARN` and acknowledgeable** — the one severity §3.10 gives it; it must be echoed in `PublishIssueParam.acknowledgedWarnings` or publish refuses with `WARNING_NOT_ACKNOWLEDGED`. An exclusions panel is structurally blind to this class; it is confirmed three times independently. |
| `TYPE_DRIFT` | frozen `type` ≠ current `type` | Informational. 12 messages are `PERMANENT_NOTICE` today inside 26 published P&T issues. |
| `NULL_PUBLISH_FROM_DROPPED` | ≥1 in-scope public-status message has `publishDateFrom IS NULL` | Makes the exclusion **visible instead of silent**. NM-780-18 leaks into 8 issues under the legacy `overlaps()` semantics. |
| `STALE_OVERRIDE` | an EXCLUDE whose target the query no longer returns, or an INCLUDE it now returns anyway | Warning on the checklist; never auto-cleared. |
| `OVERLAPPING_ISSUE` | `IN_FORCE_AT_CUTOFF` and ≥1 member shared with the previous issue | Purely informational, so nobody reads the 31-of-32 firing-areas overlap as a bug. |
| `LIMIT_EXCEEDED` | `total > 1000` | Resolve still returns everything; **publish and preview refuse** (§7.3). |

The two warning-name sets that appeared in the source fragments (`CANCELLED_OR_EXPIRED_ALIVE_AT_CUTOFF` / `TYPE_MUTATED_SINCE_FREEZE` / `PUBLISH_DATE_NULL`) are **dropped, not aliased** — the set above is the fuller one and each name states the mechanism rather than the symptom.

### 3.10 `PublishChecklistVo` — the server-authoritative release rail

```jsonc
{ "ready": false,
  "items": [ { "code": "FILE_PRESENT_PER_LANGUAGE", "severity": "BLOCK",
               "applicable": true,
               "detail": { "missingLanguages": ["en"] }, "acknowledgeable": false }, … ] }
```

`severity ∈ { OK, WARN, BLOCK }`. The legacy rules that live only client-side must be reproduced **here**, not in the frontend.

| `code` | Severity | Check |
|---|---|---|
| `ISSUE_OPEN` | BLOCK | `status = OPEN` and `series.status ∈ {ACTIVE, RETIRED}` |
| `INTERVAL_PRESENT` | BLOCK | `intervalTo` set when query-backed; `intervalFrom` set **iff** `PUBLISHED_IN_INTERVAL` |
| `INTERVAL_CHAINED` | WARN | `intervalFrom` equals the predecessor's `cutoffStampedAt`, or an `INTERVAL_CHANGED` audit entry explains the deviation |
| `FILE_PRESENT_PER_LANGUAGE` | BLOCK | **Only where bytes must already exist.** Checked for `contentMode ∈ {UPLOADED_FILE, EXTERNAL_LINK}` — every configured language carries a non-blank `link` — and, on a `GENERATED_FROM_QUERY` series, for each **single language carrying `fileSourceSticky = true`**, whose bytes publish will deliberately not regenerate. **Not checked at all for `contentMode = NONE`, and not checked for the non-sticky languages of a generated series** |
| `REPORT_CONFIGURED` | BLOCK | `reportId` set for `GENERATED_FROM_QUERY`. **This is the whole pre-flight guarantee for a generated series' non-sticky languages**; the bytes themselves are guaranteed by the post-render assertion in §4.3 step 10c, inside the same transaction |
| `REFERENCE_FORMAT_COMPLETE` | BLOCK | every configured language carries `messageReferenceFormat` — **only when `messagePublication ≠ NONE`**, or the flagship weekly EfS series is blocked |
| `CUTOFF_AFTER_PREVIOUS` | BLOCK | strictly greater than the predecessor's stamp, the predecessor being the same series' issue with the greatest `cutoffStampedAt` strictly below this one and **`status IN (PUBLISHED, RETIRED)`** — the same neighbour filter as the row below and as §4.3 step 13. Its refusal code is `CUTOFF_BEFORE_PREVIOUS` (§7.3) |
| `CUTOFF_BEFORE_SUCCESSOR` | BLOCK | strictly **less** than the successor's stamp, when a successor with **`status IN (PUBLISHED, RETIRED)`** exists — the mirror of the row above, the **same neighbour filter §4.3 step 13 caps against**, and what makes retro-recovering uge 41/2024 safe (ruling **R5**). Retire leaves the file and the public window in place, so a retired issue still occupies its bracket and must still bound the stamp. Without this the retro-created issue caps this week's live issue and becomes the site's current publication. Its refusal code is `CUTOFF_AFTER_SUCCESSOR` (§7.3) — checklist items name the condition, error codes name the violation |
| `CUTOFF_NOT_FUTURE` | BLOCK | the checklist row; a chosen `cutoff` in the future is refused at I16 with the error code **`CUTOFF_IN_FUTURE`** (there is no `allowFuture`, ruling D7) |
| `MEMBERS_RESOLVED` | OK/WARN | resolver ran; `memberCount` reported |
| `MEMBER_LIMIT` | BLOCK | ≤ 1000 |
| `PREVIEW_FRESH` | WARN | `previewStale = false` | **[`PREVIEW_FRESH` removed 2026-09-08 — amendment row 16; the rail has fourteen rows.]**
| `NO_INEFFECTIVE_OVERRIDES` | WARN | no stale EXCLUDE / redundant INCLUDE outstanding |
| `CANCELLED_MEMBERS_ALIVE_AT_CUTOFF` | WARN, **acknowledgeable** | C2's mirror; must be echoed in `PublishIssueParam.acknowledgedWarnings` |
| `OVERLAPPING_ISSUE` | WARN | `IN_FORCE_AT_CUTOFF` and ≥1 member shared with the previous issue. Purely informational, so nobody reads the 31-of-32 firing-areas overlap as a bug — the rail counterpart of the member-list warning of the same name (§3.9) and of the data model's **P11** |

**Amended 2026-08-30 (UI rework): `applicable`.** Every row carries `applicable: boolean` (**default `true`**; absent must be read as `true`). It says whether this issue can be in the condition the row describes *at all*. All fifteen rows are still emitted for every issue — a client that renders only what it received cannot tell "this check passed" from "this check does not exist" — but a row about a question this issue does not raise is not an answer about this issue, and **the verdict, the failing-row list and the "N checks passed" fold are computed over the rows with `applicable = true` only**. **No inapplicable row is a `BLOCK` row that fails**, which is the invariant the publish gate rests on (§4.3 refuses on `BLOCK` rows that did not pass), so nothing reading `passed` changes; the one inapplicable row that can still report `passed: false` is `MEMBERS_RESOLVED`, a `WARN`. An inapplicable row's `detail` reads `not applicable: <reason>`. The rows that can be inapplicable, and when:

| `code` | inapplicable when |
|---|---|
| `INTERVAL_PRESENT` | `contentMode ≠ GENERATED_FROM_QUERY` — the interval is a property of query membership |
| `INTERVAL_CHAINED` | no predecessor: there is no chain to be in |
| `FILE_PRESENT_PER_LANGUAGE` | `contentMode ∈ {GENERATED_FROM_QUERY, NONE}` — publish writes the file, or there is no document. *As shipped the check is per content mode and does not single out a generated series' sticky language; that half of the row above is not built.* |
| `REPORT_CONFIGURED` | `contentMode ≠ GENERATED_FROM_QUERY` — nothing is rendered |
| `REFERENCE_FORMAT_COMPLETE` | `messagePublication = NONE` — nothing can cite the series |
| `CUTOFF_AFTER_PREVIOUS` | no predecessor, or it carries no stamp |
| `CUTOFF_BEFORE_SUCCESSOR` | no successor, or it carries no stamp |
| `CUTOFF_NOT_FUTURE` | `allowFuture` — the check was waived, not satisfied |
| `MEMBERS_RESOLVED` · `MEMBER_LIMIT` · `NO_INEFFECTIVE_OVERRIDES` · `CANCELLED_MEMBERS_ALIVE_AT_CUTOFF` · `OVERLAPPING_ISSUE` | the issue resolves **no member list** — no resolve is attempted, which is `NOT (contentMode = GENERATED_FROM_QUERY AND timeRelation ≠ null)` **and** the issue carries no `INCLUDE` override. A resolve that *was* attempted and failed stays applicable and still warns. Curation alone constitutes membership, so one `INCLUDE` on an uploaded issue makes all five applicable again. `CANCELLED_MEMBERS_ALIVE_AT_CUTOFF` keeps `acknowledgeable` and `acknowledgeCode` either way — the gate compares that code, and a row that dropped it would be a refusal the dialog has no control for. |

Without the last row an uploaded or link-backed issue carried `MEMBERS_RESOLVED` as a standing `WARN` reading "0 members" — a warning nobody can clear, because there is no query to run and no curation to fix. Only `ISSUE_OPEN` and `PREVIEW_FRESH` are unconditionally applicable; on a first uploaded issue of a non-citable series exactly four rows apply (`ISSUE_OPEN`, `FILE_PRESENT_PER_LANGUAGE`, `CUTOFF_NOT_FUTURE`, `PREVIEW_FRESH`). Nothing else changes: the envelope keeps `rows`, `canPublish` and `blockingCodes`, every code still ships every time, and the acknowledgement mechanism (`acknowledgeable` + `acknowledgeCode`, echoed in `PublishIssueParam.acknowledgedWarnings`) is untouched. **[`PREVIEW_FRESH` removed 2026-09-08 — amendment row 16; the rail has fourteen rows.]**

> **Why `FILE_PRESENT_PER_LANGUAGE` is scoped rather than universal.** A generated issue's files are written by **§4.3 step 10, which is the only writer of `link` for those languages** — preview writes nothing to the publication repo (§5.1) and I24 is the only other writer, per sticky language. A pre-flight BLOCK on `link` for a `GENERATED_FROM_QUERY` series is therefore a check on a value that cannot exist yet, and it would make **the first publish of every weekly EfS return `409 MISSING_FILE_FOR_LANGUAGE`** — the flagship flow, unpublishable, permanently. The guarantee is not dropped, it is moved to where it can actually hold: `REPORT_CONFIGURED` before the render, and the step 10c assertion after it, inside the transaction that rolls back. **The same split must be mirrored in the data model's P4** (publish-precondition list) — the two documents state one rule.

### 3.11 `IssueAuditEntryVo` — ADMIN

```jsonc
{ "id": 40122,
  "created": 1755424218000,
  "action": "AMENDED",
  "actorKind": "USER",
  "actor": "R. Jensen",              // = actorLabel, snapshotted at write time
  "reason": "Forkert medlem i uge 27",
  "archive": { "auditEntryId": 40122,
               "files": [ { "lang": "da", "fileName": "EfS-Uge-27-2026.pdf",
                            "fileSource": "GENERATED", "sizeBytes": 1841233, "hash": "9f2c…" } ] },
  "detail": { "added": [], "removed": ["b1f0…"], "regenerated": ["da","en"],
              "kept": [], "orderChanged": false } }
```

| Field | Source | Note |
|---|---|---|
| `id` | `id` | The **one** place a surrogate id is on the wire, because I26 needs to address a specific audit event's archive folder. It is opaque and never an interchange key. |
| `created` | `created` | Immutable, `@PrePersist`, no `updated` — the row is append-only. |
| `action` | `action` | The values enumerated in the data model §7.11 — **all twenty**, including the three file actions this contract's §4.12 requires (`FILE_UPLOADED`, `FILE_REPLACED_MANUALLY`, `FILE_CLEARED`) and `AUTO_RELEASE_ABORTED` for a scheduled run stopped by a `BLOCK` precondition (§4.3). The enumeration is the data model's; nothing here adds to it. <br>**Amended 2026-08-29 (R-xxvi): the shipped vocabulary is 29 values**, gaining `OWNER_TRANSFERRED` — a series-level entry, written on the series rather than on an issue, with `detail {from, to}` (both `domainId`s, `from` may be null on a row that had no owner) and a **required** reason. It is its own action rather than a note on an edit because it is the one change that makes a publication leave one desk's screens and appear on another's. *(The count is 29, not the "26" the 2026-08-28 §7.11 amendment states: that figure was already two short of the shipped `AuditAction` enum, which held 28 before this ruling.)* <br>**Amended 2026-09-09: the vocabulary is 31 values**, gaining `EDITION_CHANGED` (an edition retyped on an OPEN issue — its own action rather than a `NAME_CHANGED`, because the edition tells two documents of the same period apart and expands into the file-name pattern) and `VISIBILITY_WINDOW_CHANGED` (the public window's end set or cleared **by hand**, distinct from `VISIBILITY_CAPPED`, which is a neighbour's publish closing a window as a side effect of taking over — and it is the only action that can put an expired publication back on the public site). Both are labelled client-side under `admin.publications.action.*`, as is `FILE_REPLACED_MANUALLY`, which had been in the enum without one. |
| `actorKind` | `actorKind` | `USER \| SYSTEM \| IMPORT`. **Required, because a `null` actor must be distinguishable from a bug**: `releaseMode = AUTO_RELEASE` publishes with no human, and the importer must not fabricate one. Rendered as *"Systemet — automatisk publicering"* / *"Importeret fra tidligere system"*. |
| `actor` | `actorLabel` | Snapshotted display name — survives a rename or a deleted account. `user` (the FK) is never on the wire. |
| `reason` | `reason` | Copied onto the row at write time, so the panel needs no join and the reason outlives a deleted override or a cleared `retiredReason`. |
| `archive` | `archivePath` + `detail.archived[]` | Present on `AMENDED` when files were overwritten, **on `PUBLISHED` when §4.3 step 9 archived**, and on **`FILE_REPLACED_MANUALLY`** when I24 overwrote a published issue's file (§4.12) — publish reaches the archive on the cleared-sticky-flag path, the file-replacement path reaches it directly, and an archive no audit row can carry is an archive nobody can reach. All three actions therefore accept `archivePath` and `archived: [{lang, fileName, size, hash, fileSource}]` in the data model's §3.10 validation and its §7.11 detail shapes. **`archivePath` itself is not emitted** — a filesystem path outside the repo root has no business on the wire. The client addresses the archive through I26, which resolves against **any** audit entry with a non-null `archivePath`, not `AMENDED` alone; `archiveEntryCount` (§3.6) counts the same set. C3 is a one-way door and this is the door. |
| `detail` | `detail` | Per-action JSON, shape per action (data model §7.11). |

Two entries are written **on a row other than the one being acted on**, and both appear in that row's audit list: `VISIBILITY_CAPPED` on the **predecessor** when a successor publishes, and `SUPERSEDED_BY` on the **old** issue when `supersedes` is set. Without the first, a change to public visibility happens with no trace.

`CREATED_FROM_PREVIOUS_PUBLISH` is written on the **successor** — which is why there is no separate `NEXT_ISSUE_CREATED` action. One event, one entry, on the row it describes.

**Order — amended 2026-09-09: newest first.** I11 and the workbench's `audit` both answer `ORDER BY created DESC, id DESC`, which is what I33 (the series history) already answered. The two orders used to be deliberately opposite — an issue's trail read as a story forwards, a series' as a log — and the distinction did not survive contact with the screens: both panels sit in the same column of the same page, an issue's trail grows past a screenful the moment it is amended or curated, and the question asked at either of them is "what happened recently". The client sorts nothing, in either panel; the order on the wire is the order rendered.

**No legacy history migrates.** The importer writes exactly one `IMPORTED` entry per issue and fabricates nothing else.

### 3.12 Override request and the reason payload

```jsonc
IssueOverrideRequestVo {
  "messageUids": ["a1b2…", "c3d4…"],   // 1..500, distinct, valid UUIDs
  "reason": "dækket i uge 26",         // REQUIRED on all three — include, exclude AND clear
  "version": 7                          // the ISSUE's version, not the override's
}
```

- **A selection, not a single uid** (C1), matching `MessageTagRestService.java:200-230`'s bare `List<String> messageUids`. It is wrapped in an object only because the reason is mandatory and a bare array cannot carry one.
- `reason` is `@NotBlank`, 3..512, **on all three endpoints including I14 `clear`**. Mandatory **in the model**, precisely so the prototype's gap cannot ship: the prototype *displays* quoted reasons but `toggleRow()` writes a hardcoded string and no input field exists on any of its six toggle buttons. It is required on `clear` because clearing writes an `OVERRIDE_REMOVED` audit entry whose `reason` the data model declares required for that action (`DATA-MODEL.md` §3.10, the `IssueAuditEntry.reason` row, and §7.11's `OVERRIDE_REMOVED` shape), and because dropping a reasoned curation without saying why is the one move that leaves the record less explicable than it was. **Cited by section, not by line** — the two documents are edited independently and a line number goes stale silently, which is exactly how a wrong citation survives a review.
- **Idempotent.** Re-including an already-included uid updates the reason and timestamp; it is not an error.
- **All-or-nothing.** Any uid that resolves to no `Message` rejects the **whole** selection with `UNKNOWN_MESSAGE_UID` listing every offender. A partially-applied curation is exactly the silent wrongness being removed.
- Response is the re-resolved `SystemIssueMemberListVo`, so the client never has to re-fetch.

`IssueOverrideVo` (read shape, embedded in member/omission reasons): `{ messageUid, kind, createdAt, appliedAtPublish, curation? }`. **Ruling X4:** the column is `appliedAtPublish` (nullable `Boolean`, `null` while OPEN, set at freeze); `staleAtFreeze` is its negation and is not a second field.

### 3.13 Import / export envelopes

```jsonc
PublicationSeriesExportVo {
  "formatVersion": 1,
  "exportedAt": 1755424218000,
  "categories": [ PublicationCategoryVo ],
  "series":     [ SystemPublicationSeriesVo ],
  "issues":     [ SystemPublicationIssueVo ]
}
```

- Upsert is **by business key** — `categoryId`, the authored `VARCHAR(64)` **`seriesId`** (ruling **R3** — an authored key, never a UUID, never the surrogate `id`), and `publicId` — never by database id.
- A referenced **category** that does not exist is auto-created. A referenced **domain, report or message series** that does not exist **fails the row loudly**; silently nulling it is a hazard, not a feature.
- Import may set `status`, `cutoffStampedAt`, `created` and `publicFrom`/`publicTo` directly, **bypassing the action machine** — the historical importer needs it and the DK deployment seed depends on it. This is the one sanctioned write path that does not go through §4.
- **Imported issues carry `repoPath`, `filePath` and every `descs[].link` verbatim from legacy** (ruling **R6**). Legacy stores bytes at `<repoPath>/<revision>/<fileName>` and *that exact string* is written into stored message HTML as the citation href, so re-deriving a flat path on import would 404 every imported file and every citation pointing at it — 1,077 public URLs. The no-revision-segment rule of §8.6 is therefore scoped to **newly published** issues; `repoPath` is importable rather than always re-derived, and the containment constraint relaxes to `filePath startsWith issue.repoPath`.
- **`publicWindowSource` on import is `DERIVED` for any series with a cadence** (ruling **R8**) and `MANUAL` only for genuinely open-ended one-offs. A historical window is a derived fact, not an admin choice, and `MANUAL` is precisely what §4.3 step 13 skips — so importing the newest issue as `MANUAL` would leave it uncapped and put **two current EfS issues** on the public site at the first native publish. The importer must also assert, as an import check, that **each series ends with at most one issue whose `publicTo IS NULL`** (the same invariant R5 states for the live model).

```jsonc
LegacyImportReportVo {
  "total": 1077, "accepted": 1029,
  "rejected": [ { "publicationId": "…", "title": "…",
                  "code": "UNKNOWN_LEGACY_FILTER",
                  "detail": "messageTagFilter = \"msg.type == Type.TEMPORARY_NOTICE && …\"" } ] }
```

C4's acceptance is **mechanical**: any legacy publication whose `messageTagFilter` is not one of the five known combinations is rejected, and **the whole import fails rather than importing a partial estate**. Matching is on the normalised verbatim string (trim, collapse whitespace runs, case-sensitive) — no parsing, no prefix stripping, no fuzzy matching. There are exactly four filter values in nine years; anything else is unknown *by construction*.

> **Blocking prerequisite for S18/S19 only:** the four canonical strings have never been read verbatim. The consent document abbreviates them (`msg.type==T`) while the code's own documented example spells `msg.type == Type.TEMPORARY_NOTICE`. They must be dumped from the production snapshot and committed as a test resource **before** the importer is written. Nothing else in this contract depends on it.

### 3.14 The gap pseudo-row

A full row in `data[]`, discriminated by `pseudo`. It has **no entity** and therefore no `publicId`.

| Field | Type | N? | Semantics |
|---|---|---|---|
| `pseudo` | `IssueRowKind` | no | `MISSING` \| `UPCOMING`. **Absent on real rows** — not `null`, absent, so a client cannot accidentally treat it as a value. |
| `publicId` | `String` | **always null** | A gap has no entity. |
| `publicationSeriesId` | `String` | no | |
| `suggestedDescs[]` | `List<PublicationIssueDescVo>` | no | One row per configured language, `name` from the ISO week of `intervalTo`, `fileName` from the per-language pattern (C5) |
| `intervalFrom` | `Date` | no | The **preceding** issue's `cutoffStampedAt`, falling back to its nominal cut-off |
| `intervalTo` | `Date` | no | The **following** issue's `intervalFrom`, falling back to the nominal cadence step |
| `intervalFromSource` / `intervalToSource` | `IntervalBoundSource` | no | `STAMPED` \| `NOMINAL` \| `RECOVERED` — drives the required marker. On a pseudo-row these are **computed**, because a pseudo-row has no entity to store them on; on a real row they are the stored columns (§3.4). `MANUAL` cannot occur here — nobody has authored this interval yet |
| `wouldMatchCount` | `Integer` | yes | Live count the criteria would select in that interval — the prototype's "11 ville matche". `null` unless `includeGapCounts=true` |
| `precedingPublicId` / `followingPublicId` | `String` | yes | `null` at the head / tail |
| `sortKey` | `long` | no | Same key space as real rows, so the merged list sorts as one sequence |
| `computedStatus` | `ComputedIssueStatus` | no | `MISSING` or `UPCOMING` |

`GET /publication-series/series/{seriesId}/issue-draft` (S12) returns exactly the body `POST /publication-issues/issue/` (I7) accepts for the same interval, so "Opret med tilbagevirkende kraft" is: gap row → S12 (prefill + live preview) → I7 → I16.

### 3.15 `IssueDraftVo` (S12)

An unsaved `SystemPublicationIssueVo` — the same idiom as `new-publication-template` returning an unsaved `SystemPublicationVo` (`PublicationRestService.java:255-270`) — plus `wouldMatchCount`, `chainedFromPublicId`, and `warnings[]`.

S12 serves three UIs from one endpoint. Given `afterPublicId` it chains `intervalFrom` from that issue's stamped cut-off; given an explicit interval it validates and re-derives the numbering; given neither it uses the newest issue plus the nominal cadence.

> **The name suggestion derives from the ISO week of the *cut-off* — the interval END — never the interval start.** On the canonical Wed→Wed interval, deriving from the start returns "Uge 26+27, 2026" where production says "EfS uge 27". The prototype's `suggestName()` does exactly that and must not be copied.

### 3.16 `PublicationIssuePickerVo` (I27/I28) and `IssueMembershipVo` (I6)

`PublicationVo` is a published XSD with a fixed `@XmlType(propOrder = {"category","type","publishDateFrom","publishDateTo","descs"})` and `publicationId`/`created`/`updated` as `@XmlAttribute` (verified `PublicationVo.java:36-38, 102-127`). **It cannot gain a `status` field.** The picker needs one, so it gets its own minimal DTO rather than `/search-details`, which would hand any logged-in user the full system model (and is ADMIN since 2026-09-25).

```java
PublicationIssuePickerVo {
    String publicId;
    String publicationSeriesId;          // grouping in the browse popover
    PublicationType type;                // DERIVED from series.contentMode — ruling X2
    IssueStatus status;                  // the field PublicationVo cannot have
    MessagePublication messagePublication;
    Date publicFrom;
    List<PublicationIssuePickerDescVo> descs;   // lang, title(=name), link, messagePublicationFormat
}
```
**Hides:** everything else. Notably no criteria, no report config, no repo path, no member count.

```java
IssueMembershipVo {
    String publicId; String publicationSeriesId;
    List<PublicationIssueDescVo> descs;   // name per language
    IssueStatus status; ComputedIssueStatus computedStatus;
    MembershipKind membership;            // how it is in
    boolean prospective;                  // true = "would land in this OPEN issue", not yet frozen
    Date cutoffStampedAt;
}
```
`prospective` is what serves the P2.6 line *"indgår i EfS uge 29 (åben)"* honestly — the message is not yet a member of anything, it is what the live query currently returns.

---

## 4. The transactional actions

Every action below states: **who**, **preconditions**, **effects in order**, **idempotency/locking**, **returns**.

### 4.1 Rules common to all of them

**Locking.** Every action takes a **pessimistic lock on the series row** for the duration of the transaction. A double-clicked publish must yield exactly one stamped cut-off. Failure to acquire → `409 CONCURRENT_PUBLISH`.

**Optimistic locking.** Every write body carries the entity's `version`; mismatch → `409 STALE_VERSION`. Override writes carry the **issue's** version, not the override's, because a curation changes the member set and two admins curating concurrently must collide.

**Atomicity.** Each action is one transaction. Any failure rolls the whole thing back. In particular `ARCHIVE_FAILED` **must** roll back: C3 is a one-way door and an overwrite without an archive is unrecoverable.

**Audit.** Every action writes exactly one `IssueAuditEntry`, plus the two cross-row entries named in §3.11.

### 4.2 Create an issue — `POST /publication-issues/issue/` (I7) · **ADMIN**

| | |
|---|---|
| **Preconditions** | `series.status = ACTIVE` (`409 SERIES_INACTIVE`). For `PUBLISHED_IN_INTERVAL`: **`intervalFrom` equals the `cutoffStampedAt` of the PUBLISHED issue with the greatest `cutoffStampedAt` strictly below this issue's `intervalTo`**, or `series.firstIssueStartsAt` at the head of the chain. For `IN_FORCE_AT_CUTOFF`: `intervalFrom` **must be null** (`400 INTERVAL_INVALID`). Overlap check runs **only** for `PUBLISHED_IN_INTERVAL` (`409 ISSUE_INTERVAL_OVERLAP`). |
| **Effects, in order** | mint `publicId`; derive `repoPath` = `publications/<h1>/<h2>/<publicId>`; derive nominal `intervalTo` from the cadence and set **`intervalToSource = NOMINAL`**; set **`intervalFromSource`** — `STAMPED` when `intervalFrom` chains off the predecessor's stamped cut-off, `NOMINAL` at the head of the chain (from `series.firstIssueStartsAt`), `RECOVERED` on a retro-creation prefilled by the importer, `MANUAL` when the admin typed the bound; derive `week`/`weekTo`/`year`/`edition`; create one `PublicationIssueDesc` per configured language with the expanded per-language name and file name; set `membershipProvenance = NO_MEMBERSHIP` when the content mode has none. |
| **Both source columns are written here, not left to publish** | They are real columns (§3.4, ruling **R4**) with `I-17` binding each to its own bound: `intervalFromSource IS NOT NULL ⟺ intervalFrom IS NOT NULL`, likewise for `To`. A row created by this endpoint alone with the bounds set and the sources null fails the data model's `ck_pubissue_ivsrc` at insert, and publish never revisits them — it stamps `cutoffStampedAt`/`cutoffSource` and deliberately leaves `intervalTo` and `intervalToSource` describing the *nominal* bound (§4.3, "does not overwrite `intervalTo`"). This list is the data model's §4.3 T0 effects, field-for-field. |
| **Does not** | touch the predecessor's visibility window; resolve or store any member. |
| **Idempotency** | None — it is a create. Two calls make two issues, and the overlap check is what stops that for a tiling series. |
| **Audit** | `CREATED` \| `CREATED_RETROACTIVELY` (when the interval sits between two existing issues) |
| **Returns** | `201` + `SystemPublicationIssueVo` |

> **Both interval checks operate on the effective interval `[intervalFrom, effectiveCutoff)` — half-open**, which is exactly the coalesce (`cutoffStampedAt ?? intervalTo`) §3.14 already uses to bound a gap row. Publish step 14's internally-created successor runs the **same** check against the **same** operands, so the automatic and the manual paths cannot disagree.
>
> This is why the chaining rule reads "*strictly below this issue's `intervalTo`*" rather than "the previous issue". Publish does **not** overwrite `intervalTo` with the stamp, so an early release — uge 27 stamped 11:42 against a nominal 12:00 — leaves the predecessor with `intervalTo = 12:00` while the successor's mandated `intervalFrom` is `11:42`. Under a closed interval and a naive "previous issue" lookup that is an 18-minute overlap: `409 ISSUE_INTERVAL_OVERLAP` **raised inside the publish transaction**, which §4.1 then rolls back entirely. Half-open bounds and a stamp-ordered predecessor make both the auto-created successor and the retro-created gap issue expressible; without them neither can be created at all.

### 4.3 Publish — `PUT /publication-issues/issue/{publicId}/publish` (I16) · **ADMIN**

**Request** — `PublishIssueParam`:

| Field | Type | N? | Default | Semantics |
|---|---|---|---|---|
| `cutoff` | `Date` | yes | `null` = NOW | The stamped instant. NOW and an admin-chosen instant are **stamped identically**; only `cutoffSource` distinguishes them. |
| `allowFuture` | `boolean` | no | `false` | |
| `version` | `Integer` | no | — | |
| `nextIssue` | `Boolean` | yes | `series.nextIssueCreation = AUTO_ON_PUBLISH` | |
| `regenerate` | `boolean` | no | `true` | **Superseded 2026-09-08 — the `regenerate` option no longer exists; see "Amendments — 2026-09-08 (the batch of nine)".**  `false` **promotes** the already-rendered preview files instead of re-rendering — step 10b, which is a real copy, not a no-op. Requires a preview for every non-sticky configured language (`409 NO_PREVIEW`) and a fresh one (`409 PREVIEW_STALE`). |
| `acknowledgedWarnings[]` | `List<String>` | no | `[]` | Must contain **every** acknowledgeable BLOCK/WARN code the checklist reports, or `400 WARNING_NOT_ACKNOWLEDGED` listing the missing ones. The rail is server-authoritative. |

> **Amended 2026-09-11:** `names` is **no longer accepted** on the publish body. The names of an issue are tailored on the open issue through I8 (the drawer of round eight) and a release publishes the issue as it stands; the "last moment to rename" of the 2026-09-08 amendment is withdrawn.

**Preconditions** — the §3.10 checklist, evaluated server-side. `BLOCK` items refuse with the mapped 4xx.

**One further precondition, and it is request-dependent so it is not a checklist row:** when `regenerate = false` **Superseded 2026-09-08 — the `regenerate` option no longer exists; see "Amendments — 2026-09-08 (the batch of nine)".** , a preview file must exist **for every non-sticky configured language**, else `409 NO_PREVIEW`. Preview files live in a purge-swept location (§5.1) and `PREVIEW_STALE` covers a preview that is *out of date*, never one that is *gone* — so without this check an issue whose preview was swept by the hourly purge publishes with **no document at all**, after which §4.4 makes `fileName` and `filePath` immutable forever.

Note `series.status ∈ {ACTIVE, RETIRED}`, not `= ACTIVE`. Gating publish on ACTIVE would strand in-flight work with no recovery verb — an admin retiring a weekly series on a Tuesday could never release the issue already assembled for Wednesday. Issue *creation* requires ACTIVE; issue *publication* does not.

**Effects, in order** (this ordering is the contract; C3, C6 and C8 all mutate the same transaction):

1. Lock the series row.
2. **Stamp the cut-off** — `cutoffStampedAt`, `cutoffSource = STAMPED_AT_PUBLISH | STAMPED_MANUAL_TIME`, `cutoffReconstructed = false`. This happens **before** the resolve, because the resolve is *against* the cut-off.
3. **Resolve members in-process** through the member resolver's own JPA predicate — **never** through `MessageSearchRestService`, which calls `params.adjustDateInterval(currentDomain.timeZone())` (`MessageSearchRestService.java:181`), rounding the window to whole days and pulling in the exact messages that belong to the next issue, and which silently rewrites `seriesIds` (`:144-160`).
4. Apply overrides: `members = (Q \ activeExcludes) ∪ activeIncludes`, keyed on `uid`.
5. Compute the order (§6.6); assign dense 0-based `sortIndex` over the **post-override** union. Overrides are ordered like any other member; they are **not** appended at the end.
6. Write `IssueMember` rows with the frozen scalars.
7. Write the snapshot header: `snapshotFrozenAt`, `memberCount`, `snapshotSortBy`, `snapshotSortOrder`, `snapshotDomainId`, `snapshotSeriesIds`, `criteriaSnapshot`, `snapshotTimeRelation`, `snapshotAliveAtCutoff`, `snapshotIntervalFrom`, `membershipProvenance = EXACT` (or `NO_MEMBERSHIP`). This list is the data model's §4.4 step 7 field-for-field; `snapshotIntervalFrom` is a real column, it records the interval lower bound the resolve actually used, and it is what §3.8 echoes as `intervalFrom` on a FROZEN member list.
8. Freeze the overrides — each records `appliedAtPublish`. A stale EXCLUDE freezes as `false`, so the published record does not claim a removal that never happened.
9. **Archive**, if and only if a byte-carrying file already sits at the target published path (the deliberately-cleared-sticky-flag case). The outgoing files go to the same archive layout amend uses (§4.4), and the `{lang, fileName, size, hash, fileSource}` records plus the `archivePath` are carried on **this action's `PUBLISHED` audit entry** (§3.11) — publish reaches this path, so `AMENDED` cannot be the only action that can record it.
10. **Render and write the files**, one of two ways:
    - **10a · `regenerate = true` (default) — render in-process.** The render is performed by an **in-process rendering service that takes the resolved, ordered member list** — the `List<MessageVo>` in frozen `sortIndex` order — plus the typed print settings and the merged `reportParams` (§3.6). **No temp `MessageTag` is created, at any point.** No HTTP self-call is made, no `?tag=` is passed, no admin ticket is minted. The render **must read the snapshot frozen in this same transaction**, which is only possible in-process: an HTTP self-call is a separate request, a separate transaction and a separate connection, so anything written by this uncommitted transaction is invisible to it — a temp tag created here would be found by nothing, the search would take its restriction branch, and the official PDF would contain **the domain's entire published corpus truncated at 1000 messages, with no error**. That is also why the frozen `sortIndex` is reachable at all: it is passed as the order, not re-derived from a query. **The claim that "the FmReport pipeline is unchanged" is retired** — the FreeMarker report templates and their data model are reused, the HTTP entry point is not.
      Write each language to `<repoPath>/<fileName>`, **bypassing `RepositoryService.uploadFile`/`getUniqueFile`**, which appends `" 2"` on collision and would silently make the stable public URL stop pointing at the current document. Skip any language with `fileSourceSticky = true`.
    - **10b · `regenerate = false` — promote the preview.** For each requested language, **copy** its preview file from the preview generation folder (§5.1) to `<repoPath>/<fileName>`, bypassing `getUniqueFile` exactly as 10a does; recompute `fileSize` and `fileHash` from the copied bytes; set `filePath` and the per-language `link`. Sticky languages are skipped here too. This step is what makes `regenerate = false` mean something: nothing else in this transaction moves preview bytes into the publication repo.
    - **10c · post-render assertion, inside this transaction.** After 10a/10b, assert that **every non-sticky configured language now carries a non-blank `link`** and a file at `filePath`. On failure the transaction **rolls back** with `500 REPORT_GENERATION_FAILED` naming the languages. This is the guarantee that replaces the pre-flight `FILE_PRESENT_PER_LANGUAGE` BLOCK for generated series (§3.10) — the check is made where the value can actually exist. **It is a 5xx and not a 4xx, and that is a ruling:** the renderer ran and produced no link for a language, which is a system failure and not a condition an admin can fix by filling in a field. `409 MISSING_FILE_FOR_LANGUAGE` survives **only** as the pre-flight check for `UPLOADED_FILE`, `EXTERNAL_LINK` and sticky languages, where the bytes must pre-exist and the admin can act (§3.10, §7.3). The data model's §4.4 P4 and step 10 say this in the same words.
11. `publishedAt` = now; `publishedBy` = caller (**null** under `AUTO_RELEASE`); `status = PUBLISHED`.
12. `publicFrom = cutoffStampedAt`; `publicTo` per step 13. **Skipped when `publicWindowSource = MANUAL` *and* `publicTo IS NOT NULL`** — a genuinely open-ended one-off owns its own window, but a row mis-marked `MANUAL` that is still uncapped is capped anyway, exactly as the predecessor is in step 13.
13. **Cap symmetrically — the predecessor *and* this issue** (ruling **R5**), both against the same −1 ms rule:
    - **The neighbour filter is `status IN (PUBLISHED, RETIRED)`, on both lookups and on the `CUTOFF_BEFORE_SUCCESSOR` checklist item (§3.10) alike** — one filter, stated identically at all three sites and in the data model's §4.4 steps 12–13 and P6b. Retire deliberately leaves the file and the public window in place (§4.5), so a **retired issue still occupies its bracket**; skipping it would leave the predecessor of a retired issue permanently uncapped and make the "at most one uncapped issue per series" invariant below unsatisfiable by legal data.
    - **Predecessor.** *P* = the same series' issue with the greatest `cutoffStampedAt` strictly **below** this one, `status ∈ {PUBLISHED, RETIRED}`: set `P.publicTo = this.cutoffStampedAt − 1 ms`, write `VISIBILITY_CAPPED` **on P**. Cap `P` when `P.publicWindowSource = DERIVED` **or when `P.publicTo IS NULL`** whatever its source — the belt-and-braces clause of ruling **R8**, which is what stops an imported issue mis-marked `MANUAL` from staying uncapped and leaving two current issues on the public site.
    - **Successor.** *S* = the same series' issue with the smallest `cutoffStampedAt` strictly **above** this one, `status ∈ {PUBLISHED, RETIRED}`: if *S* exists, set `this.publicTo = S.cutoffStampedAt − 1 ms` and write `VISIBILITY_CAPPED` **on this issue**. The self-cap runs when **this** issue's `publicWindowSource = DERIVED` **or its `publicTo IS NULL`** — the same belt-and-braces disjunct the predecessor half carries, and it is needed for the same reason: a row mis-marked `MANUAL` would otherwise never self-cap, and a retro-created gap issue would keep an open-ended window below issues that have already released, violating the invariant below with data the model calls legal. `publicTo` stays `null` **only when no successor exists**. Without this half, recovering uge 41/2024 caps this week's live EfS and makes the 2024 issue the site's current publication — the flagship gap-recovery flow, publishing the wrong document.
    - **Invariant, enforced:** a series has **at most one** issue with `publicTo IS NULL`. It is asserted after this step and again as an import check over the imported estate (§3.13).
14. Create the successor if the cadence, series status and `nextIssueCreation` allow — T0 with `intervalFrom = cutoffStampedAt`, audited as `CREATED_FROM_PREVIOUS_PUBLISH` **on the successor**. T0 runs §4.2's preconditions against the half-open effective interval, on the same operands §4.2 states.
15. Write the `PUBLISHED` audit entry, carrying `archivePath` and `archived[]` when step 9 archived.

> **Step 13 runs for every series, both time relations.** *Membership* tiling is gated on `timeRelation`; the *public visibility window* is not. Legacy tiled the visibility window even for the overlapping annuals — Skydeområder 2017 ed1 `01.01 → 28.06 09:59`, then ed2 `28.06 10:00 → 31.12` — while the two editions share almost all their members. The gate is `publicWindowSource = DERIVED`, widened by R8's `publicTo IS NULL` clause above.
>
> **The −1 ms is required**, not cosmetic: `CriteriaHelper.overlaps()` is closed at both ends, so an exactly-equal boundary would make two issues publicly current for one instant.
>
> **What step 10 changes in C8, and what it does not.** C8's clause *"rendering uses a discarded temp tag created inside the publish transaction"* is **superseded** — no `MessageTag` is created at any point, by publish, amend or preview, and the resolved ordered member list goes to the renderer directly. **Nothing else in C8 changes:** no tag is ever minted for an issue, the three `?publication=` prerequisites stand (§7.2a, §9.1), and mirroring membership into tags remains rejected.

**Idempotency.** A repeat call against an already-PUBLISHED issue returns `409 ISSUE_ALREADY_PUBLISHED` and **never re-stamps**. This is deliberate rather than "idempotent success": a second publish attempt after a network timeout is indistinguishable from a real double-click, and re-stamping is the one unrecoverable outcome.

**Under `AUTO_RELEASE` nobody can acknowledge a warning.** `BLOCK` items abort the automatic run — the issue stays OPEN, nothing is stamped, and an **`AUTO_RELEASE_ABORTED`** audit entry (data model §7.11; `actorKind = SYSTEM`, `detail = {blockingCodes:[…], attemptedCutoff}`) records the abort with the failing codes. `WARN` items do not abort but are recorded on the `PUBLISHED` entry's **`unacknowledgedWarnings`**, which is materially different from a human having confirmed them and must render differently. How an aborted automatic release reaches a human is **OQ-6**.

**Returns** `SystemPublicationIssueVo`.

### 4.4 Amend — `PUT /publication-issues/issue/{publicId}/amend` (I17) · **ADMIN**

**Request** — `AmendIssueParam { languages[]?, regenerate: boolean, **Superseded 2026-09-08 — the `regenerate` option no longer exists; see "Amendments — 2026-09-08 (the batch of nine)".**  reason: String (required), curation?: { include[], exclude[] }, version }`.

| | |
|---|---|
| **Preconditions** | `status = PUBLISHED` — **not** `RETIRED` (`409 ISSUE_NOT_PUBLISHED`; reactivate first, §4.6). `reason` non-blank. A `curation` delta requires `PUBLICATION_CURATE`. |
| **Effects, in order** | 1. Lock the series row. 2. Apply the explicit curation delta, if any, to the frozen snapshot. 3. **Archive first** — copy every language's outgoing file to `<publicationArchiveRootPath>/publications/<h1>/<h2>/<publicId>/<epochMillis>/<fileName>`, recording `{lang, fileName, size, hash, fileSource}` per file. 4. Regenerate only languages with `fileSourceSticky = false`, to the **same** `filePath` and `fileName`, through the **same in-process renderer publish uses** (§4.3 step 10a) — handed the amended snapshot's members in stored `sortIndex` order. No temp `MessageTag`, no report-pipeline HTTP call, no ticket. 5. If membership changed: update `snapshotFrozenAt`, `memberCount`, re-densify `sortIndex`. 6. Write the `AMENDED` entry with `archivePath`, the member delta, the regenerated/kept language lists and the archived-file records. |
| **Never re-resolves the query** | Re-running it reads `type`, `publishDateFrom` and `publishDateTo`, all mutable and unversioned — a live re-resolve of a published P&T issue silently drops the twelve messages that are `PERMANENT_NOTICE` today and were P or T at release, across 26 issues. *Re-running the query is forbidden; editing the frozen snapshot by an admin-chosen uid selection carrying a reason is permitted and audited.* That distinction is the whole of it. |
| **Unchanged, load-bearing** | `publicId` · `cutoffStampedAt` · `cutoffSource` · `intervalFrom` / `intervalTo` · **`publicFrom` / `publicTo`** · `filePath`, `fileName`, every `link` · `status` · `criteriaSnapshot`, `snapshotSortBy/Order/DomainId/SeriesIds`, `snapshotIntervalFrom` · `membershipProvenance` · the predecessor's cap. **`publicTo` belongs in this list**, matching the data model's §4.5: amend never caps anything, so the only writer of `publicTo` is a *later* issue's publish transaction (§4.3 step 13). The immutability carve-out stated in the paragraph below is for that write and for nothing else — it is not a licence for amend to touch the column. |
| **Idempotency** | None. Each amend is a distinct event with its own archive folder keyed on `epochMillis`. Two identical amends produce two archive folders and two audit entries — correct, because each really did overwrite a file. |
| **Returns** | `SystemPublicationIssueVo` |

`fileName` and `filePath` are **immutable once `publishedAt` is set**, including for an UPLOADED replacement through I24 (§4.12), which is written to the same path and name regardless of the uploaded file's own name. C3's stable URL beats the `Content-Disposition` fallback. A later edit of the series' file-name pattern must not move a published file — that changes the public URL and dangles every citation, which is the thing C3 exists to prevent.

**The immutability rule, stated exactly** (ruling **R4**): an issue's fields are **immutable after publish except `publicTo`, which the successor's publish caps once** (§4.3 step 13) when `publicWindowSource = DERIVED` or `publicTo IS NULL`. The two statements are not in tension — capping is not an amendment, it is the one write the publish transaction of a *later* issue is required to make on an *earlier* one, and `VISIBILITY_CAPPED` on the capped row is its audit trail. `publicFrom` has no such exception and never moves.

**The archive root must sit outside the repository root, and so must the preview root.** `RepositoryService` is class-level `@PermitAll` (`:67-69`), `getTempRepoRoot()` is literally `getRepoRoot().resolve("temp")` (`RepositoryService.java:125-126`), and `streamFile` does `repoRoot.resolve(path)` with **no authentication and no containment check** (`:210-218`) — `validateTempRepoPath` (`:481-489`) guards uploads only. Archiving or previewing *inside* the repository root therefore makes superseded and unpublished official notices reachable at guessable public URLs that cannot be recalled. Startup must assert **`!archiveRoot.startsWith(repoRoot)`** and **`!previewRoot.startsWith(repoRoot)`** (§5.1) and refuse to boot otherwise.

### 4.5 Retire — `PUT .../retire` (I18) · **ADMIN**

**Request** — `IssueReasonParam { reason (required, 3..512), version }`.

| | |
|---|---|
| **Preconditions** | `status = PUBLISHED` (`409 ISSUE_NOT_PUBLISHED`); reason non-blank. |
| **Does** | `status = RETIRED`; `retiredAt` / `retiredBy` / `retiredReason`; the issue leaves `/public/v1/publications`; `/public/v1/publication/{publicId}` returns the same indistinguishable 404 it returns for a missing id. |
| **Does not** | delete, move or rename **any file** — the PDF stays at its public URL; clear or alter the frozen snapshot; break `?publication=` (a RETIRED issue stays resolvable to its frozen members on both call sites, so citations do not dangle); drop out of the comma-separated hydration lookup, which ignores status; change `publicFrom`, `publicTo`, `cutoffStampedAt`, the interval, `publicId`, `membershipProvenance` or any member row; **un-cap the predecessor**. |
| **Idempotency** | Retiring an already-RETIRED issue → `409 ISSUE_NOT_PUBLISHED`. |
| **Returns** | `SystemPublicationIssueVo` |

> **There is no verb, in legacy or in this redesign, that makes a released file unreachable.** The confirm dialog must say so: *"Udgaven fjernes fra publikationslisten. Filen forbliver tilgængelig på sit link."*
>
> **Retiring the newest published issue leaves the series with no current issue on the public site.** The predecessor's `publicTo` stays capped. This is legacy parity — capping is by date, deactivation is by status. Un-capping was considered and rejected: it silently republishes a document that was deliberately superseded, and it would fire on every retire including the ones where the hole is intended. **The dialog must state this too** (OQ-7).

### 4.6 Reactivate — `PUT .../reactivate` (I19) · **ADMIN**

Preconditions: `status = RETIRED`, reason non-blank. Sets `status = PUBLISHED`, clears the retire triple (the reason survives on the `RETIRED` audit entry). **Does not re-resolve, re-freeze, regenerate, re-cap or re-derive anything** — the stored public window is reused verbatim. Returns `SystemPublicationIssueVo`.

### 4.7 Series status — `PUT /publication-series/series/{seriesId}/status` (S10) · **ADMIN**

**Request** — `UpdateSeriesStatusParam { status, reason, version }`.

| From → To | Reason | Guard |
|---|---|---|
| `DRAFT → ACTIVE` | optional | All series validation invariants green for the chosen `contentMode` |
| `ACTIVE → RETIRED` | **required** | none — a series with OPEN issues may be retired, and those issues stay publishable |
| `RETIRED → ACTIVE` | **required** | invariants re-run (a series may have been retired *because* it was misconfigured) |
| `ACTIVE\|RETIRED → DRAFT` | — | **Illegal** |

Refusal text is legacy parity, verbatim: `400` carrying `"Invalid state transition " + from + " -> " + to` — the exact string `PublicationService.java:510` builds, surfaced the way `PublicationRestService.java:415-423` surfaces it. A missing reason is a separate `400 REASON_REQUIRED`.

**Retiring a series retires nothing.** Published issues stay on the public listing, files stay at their links, `?publication=` keeps resolving. The confirm copy must state the real effect: *"Serien kan ikke længere bruges til nye udgaver. Allerede publicerede udgaver bliver på den offentlige liste, og deres filer bliver liggende på deres links."* — quoted from `admin.publications.series.editor.retireEffect`, which is the copy that ships; keep the two in step.

Audit: with the three-column model (`statusChangedAt/By/Reason`), a reactivate **overwrites** the retire reason, so the columns alone do not satisfy C7's "audited". Whether to generalise `IssueAuditEntry` to carry series events is **OQ-4**, and it must be settled **before the schema is applied**.

### 4.8 Delete a series — `DELETE /publication-series/series/{seriesId}` (S11) · **ADMIN**

Legal iff **zero** `PublicationIssue` rows reference the series (C7, literal), and `status ∈ {DRAFT, RETIRED}`. Otherwise `409 SERIES_NOT_EMPTY` naming the issue count. Cascade-deletes descs and the language list. Returns `204`.

### 4.9 Next issue — `POST /publication-issues/issue/{publicId}/next-issue` (I20) · **ADMIN**

The manual counterpart to publish step 14, for a series with `nextIssueCreation = MANUAL` or where the automatic creation was declined.

| | |
|---|---|
| **Preconditions** | `status = PUBLISHED` (`409 ISSUE_NOT_PUBLISHED`); `series.cadence != NONE` (`409 NO_CADENCE`); `series.status = ACTIVE`; no successor already exists (`409 NEXT_ISSUE_EXISTS`). |
| **Effects** | T0 with `intervalFrom = this.cutoffStampedAt` — **exactly** the stamped cut-off, which is what removes the drift a nominal +7d produced (production shows six 14-day gaps, one 5-day and three 0-day between consecutive weekly issues). |
| **Idempotency** | Guarded by `NEXT_ISSUE_EXISTS`, so a retry is safe and reports the existing successor's `publicId` in `details`. |
| **Audit** | `CREATED_FROM_PREVIOUS_PUBLISH` on the **successor** |
| **Returns** | `201` + the successor's `SystemPublicationIssueVo` |

For an `IN_FORCE_AT_CUTOFF` series this creates the next *edition*, not the next tile — there is no interval to chain. Whether a mid-year re-issue deserves its own `new-edition` action that also sets `supersedes` and caps the predecessor is **OQ-10**.

### 4.10 Delete an issue — `DELETE /publication-issues/issue/{publicId}` (I9) · **ADMIN**

```
legal  ⟺  status = OPEN  AND  cutoffStampedAt IS NULL  AND  publishedAt IS NULL
```

C7's signed text reads *"an issue that never published (no stamped cut-off, no publicId)"*. Because `publicId` is now minted at create (§0.5), the second clause is restated as `publishedAt IS NULL`. **This is the same set of issues C7 meant** — the citation picker only ever offers PUBLISHED issues, so a never-published issue is *provably* never cited — but it amends signed wording and needs ratification (**OQ-3**).

Effects: cascade-delete descs, overrides, audit entries and any member rows, **and delete the issue's repo folder**. The only thing that folder can contain at that point is a file uploaded for a language of an issue that never published; leaving it behind means orphaned bytes reachable at a guessable URL under the `@PermitAll` repository root. This is a deliberate deviation from legacy.

A PUBLISHED or RETIRED issue is **never** deletable → `409 ISSUE_NOT_DELETABLE`. A hard-deleted published issue takes its `publicId` with it and dangles every stored citation carrying it, and **there is no join table with which to find them**.

### 4.11 Curate the member set — `PUT .../overrides/{include|exclude|clear}` (I12, I13, I14) · **CURATOR**

The three override endpoints are transactional actions like every other, and they are specified here rather than left to §3.12's request shape — a request DTO is not a status guard, a lock or an audit contract.

**Request** — `IssueOverrideRequestVo` (§3.12): a 1..500 uid **selection**, a required `reason`, and the **issue's** `version`.

| | |
|---|---|
| **Who** | `@RolesAllowed(Permissions.PUBLICATION_CURATE)` — `403 NOT_CURATOR` otherwise. |
| **Preconditions** | `status = OPEN`, else **`409 ISSUE_NOT_OPEN`**. Post-publish curation is not done here: it goes through I17's `curation` delta (§4.4), which archives the outgoing files, regenerates and writes an `AMENDED` entry. Without this guard the three endpoints would silently edit a frozen snapshot with no archive and no regeneration, leaving the official PDF and the official record disagreeing. · `reason` non-blank, 3..512 (`400 OVERRIDE_REASON_REQUIRED`) — on **all three**, `clear` included. · Every uid resolves to a `Message`, or the whole selection is rejected with `400 UNKNOWN_MESSAGE_UID` listing every offender. · On `include`, a uid the live query already returns → `409 OVERRIDE_REDUNDANT`. |
| **Effects, in order** | 1. Lock the series row (§4.1). 2. Insert / update / delete the `IssueOverride` rows for the selection, idempotently — re-including an already-included uid updates its reason and timestamp. 3. **Force-bump the issue row's optimistic-lock version** — `LockModeType.OPTIMISTIC_FORCE_INCREMENT` on the `PublicationIssue`. 4. Re-resolve the member list. 5. Write **one** audit entry for the call. |
| **Locking** | The series lock **plus** the forced version bump, and the second is not optional. Inserting or deleting an `IssueOverride` **child** row does not touch `PublicationIssue.version`, so §4.1's declared "override writes carry the issue's version, so two admins curating concurrently must collide" would silently never fire: both requests read version 7, both write children, both commit at version 7, and the losing curation is invisible. `OPTIMISTIC_FORCE_INCREMENT` is what makes the declared collision real → `409 STALE_VERSION`. |
| **Audit** | Exactly one entry per call — `OVERRIDE_ADDED` (include), `OVERRIDE_REMOVED` (exclude and clear) — with `detail = { kind, messageUids[], shortIds[], count }`. The detail shape is **plural on purpose**: the endpoint takes a 1..500-uid selection, and a single-uid detail shape could not record what one call did without writing 500 entries, which §4.1's "exactly one `IssueAuditEntry` per action" forbids. `reason` is copied onto the row, as everywhere. |
| **Idempotency** | Idempotent per uid (§3.12). A repeat of the same selection updates timestamps and reasons and writes a second audit entry — the curation really was re-asserted. |
| **Returns** | The re-resolved `SystemIssueMemberListVo`, so the client never re-fetches. |

### 4.12 Per-language file upload and clear — `POST` / `DELETE .../file/{lang}` (I24, I25) · **ADMIN**

C6's flagship scenario is *a published issue whose PDF turned out to be wrong*. That path is specified here, and `fileSourceSticky` is written here — it is read by §4.3 step 10 and §4.4 step 4, and without a writer it is a column that is always `false`, so **an amend silently regenerates over a hand-corrected PDF**.

**Request** — multipart, `@Consumes(MULTIPART_FORM_DATA)`, first file only, the legacy idiom at `/upload-publication-file/{folder:.+}` (`PublicationRestService.java:598-650`). `AmendIssueParam` stays pure JSON and gains **no** file part; the two concerns do not share a request.

| | |
|---|---|
| **Who** | `@RolesAllowed(Roles.ADMIN)`. |
| **I24 preconditions** | `status ∈ {OPEN, PUBLISHED}` — **legal on PUBLISHED**, which is the whole point; `409 ISSUE_NOT_OPEN` for RETIRED (reactivate first). `lang` is a configured language of the series. `415 UNSUPPORTED_FILE_TYPE` otherwise. |
| **I24 effects, in order** | On an OPEN issue: write the bytes, set `fileSource = UPLOADED`, `fileSourceSticky = true`, `fileSize`/`fileHash`/`fileGeneratedAt`, `filePath` and `link`. **On a PUBLISHED issue, in one transaction:** 1. lock the series row; 2. **archive the outgoing file first** into the C3 archive layout (§4.4 step 3), recording `{lang, fileName, size, hash, fileSource}` — `500 ARCHIVE_FAILED` **rolls the whole thing back**; 3. overwrite **in place**, at the immutable `filePath` / `fileName` (§4.4) regardless of the uploaded file's own name; 4. set `fileSource = UPLOADED`, `fileSourceSticky = true`, `replacedBy` = caller, `replacedAt` = now, and recompute `fileSize` / `fileHash`. |
| **I24 audit** | `FILE_UPLOADED` the first time a language gets bytes; `FILE_REPLACED_MANUALLY` when it overwrote an existing file, carrying `archivePath` and `archived[]` so I26 can reach the superseded bytes (§3.11). |
| **`replacedBy` / `replacedAt`** | Written **only on the replacement path** — they mean *"this upload replaced bytes that were already there"*, not *"`fileSource` is `UPLOADED`"*. A first hand-upload to an OPEN issue sets `fileSource = UPLOADED` and leaves both **null**, because it replaced nothing; that is the same line the two audit actions above draw. The data model's **D-6** states it in these words, and its `ck_pubissuedesc_upl` constrains only that the two travel together. |
| **I25** | `status = OPEN` only (`409 ISSUE_NOT_OPEN`) — a published issue's document is never removed, only replaced; §4.5 already promises that no verb makes a released file unreachable. Clears the bytes, `fileSource`, `filePath`, `link`, `fileSize`, `fileHash`, and **clears `fileSourceSticky`**, so the next publish regenerates that language. Audit: `FILE_CLEARED`. |
| **Why sticky matters** | `fileSourceSticky = true` is the only thing that stops §4.3 step 10 and §4.4 step 4 from regenerating over a hand-corrected file. Set it here and nowhere else; clear it only through I25. |
| **Returns** | `SystemPublicationIssueVo`. |

---

## 5. Preview — the endpoint nobody has specified

The prototype shows PDFs generated *before* publish. No task defines the artefact, its lifetime or its authorization. Specified here.

### 5.1 The artefact is ephemeral and is not part of the record

`POST /publication-issues/issue/{publicId}/preview` (**I21**, ADMIN) renders per-language PDFs — through the **same in-process renderer publish uses** (§4.3 step 10a), handed the live-resolved member list in the order §6.6 gives it — into a folder under a **new preview root that sits outside the repository root**:

```
<publicationPreviewRootPath>/publication-issues/{publicId}/preview/{generation}/{fileName}
```

`publicationPreviewRootPath` is a new setting alongside `publicationArchiveRootPath`, with the **same boot assertion** the archive root gets — `!previewRoot.startsWith(repoRoot)`, refuse to boot otherwise (§4.4) — and an **equivalent sweep** to the hourly temp purge, since previews must still expire. Written paths are containment-checked against `previewRoot` itself; `RepositoryService.validateTempRepoPath` is **not** reused, because it is bound to the temp repo folder that is precisely what this root replaces.

> **Why not the temp repo folder.** `getTempRepoRoot()` is literally `getRepoRoot().resolve("temp")` (`RepositoryService.java:125-126`), `RepositoryService` is class-level `@PermitAll` (`:67-69`), and `streamFile` does `repoRoot.resolve(path)` with no authentication and no containment check (`:210-218`) — `validateTempRepoPath` (`:481-489`) guards **uploads only**. `publicId`, `fileName` and a small integer `generation` are all editor-tier knowledge, so a temp-repo preview is a **world-readable unpublished official notice set at a guessable URL**, which is exactly what §5.4 promises cannot happen. The guarantee is made true by moving the bytes, not by restating it.

**Nothing is written to the publication repo**, no `IssueMember` row is created, no `publicId` is minted (it already exists), and no status changes. This is precisely why `DELETE /issue/{publicId}` stays legal after a preview.

`{generation}` increments per render, so a concurrent render cannot half-overwrite a served file. Publish with `regenerate = false` **promotes** the newest generation's files into the publication repo (§4.3 step 10b); that copy is the only path by which preview bytes ever become official.

**Request** — `PreviewIssueParam { languages[]? }` (empty = all configured).
**Returns** `IssuePreviewVo`:

```jsonc
{ "generation": 4,
  "generatedAt": 1755424218000,
  "stale": false,
  "fingerprint": "sha256:7c1f…",
  "memberCount": 28,
  "files": [ { "lang": "da", "fileName": "EfS-Uge-27-2026.pdf", "sizeBytes": 1841233 } ],
  "warnings": [ ResolutionWarningVo, … ] }
```
**Hides:** the preview path, absolutely. The client never receives a URI of any kind for a preview — see §5.4.

### 5.2 What *is* persisted

Exactly one thing: a `PREVIEW_GENERATED` audit entry with `detail = {langs:[…], memberCount}`. The audit entry **survives the file**, which is what makes the prototype's history line *"Forhåndsvisning regenereret"* honest after the purge has run.

### 5.3 Staleness is computed, never stored **(derived, not persisted)**

```
fingerprint = sha256( ordered member uid list ‖ sortBy ‖ sortOrder ‖ intervalFrom ‖ intervalTo
                    ‖ series.version ‖ issue.version ‖ per-language fileName list )
stale       = previewFingerprint != currentFingerprint
```

A stale preview is still downloadable — it is **labelled, not deleted**. `PREVIEW_FRESH` reports `WARN` on the checklist, and publishing with `regenerate = false` while stale is refused (`409 PREVIEW_STALE`). Publishing with `regenerate = true` simply re-renders and does not consult the preview at all. **[`PREVIEW_FRESH` removed 2026-09-08 — amendment row 16; the rail has fourteen rows.]**

`GET .../preview` (**I22**, ADMIN) returns the same VO with `stale` recomputed; `404 NO_PREVIEW` if none was generated or the purge has run.

### 5.4 Authorization — never through the repository

`RepositoryService` is class-level `@PermitAll` and serves anything resolvable under the repo root with no authentication. **An unpublished official notice set is precisely the thing that must not be world-readable.** Therefore:

- **The preview bytes do not live under the repository root at all** (§5.1). Nothing served by `GET /rest/repo/file/…` can reach a preview or an archive, which is what makes this section a guarantee rather than a convention.
- The preview PDF is served **only** by `GET .../preview.pdf?lang=` (**I23**), which checks admin **programmatically** — `if (!userService.isCallerInRole(Roles.ADMIN)) throw new WebApplicationException(403);`, the exact shape at `PublicationRestService.java:689-691` — so a `?ticket=` obtained from `GET /rest/tickets/ticket?role=admin` works for plain browser navigation.
- The response is streamed. **No URI for a preview is ever returned in any payload.**
- The same rule governs the C3 archive (**I26**): the archive root sits outside the repo root and is reachable only through the admin-gated, ticketed stream. Preview root and archive root are two roots under one rule.

### 5.5 The UX consequence, stated so it is not discovered

**A preview link is not shareable.** Tickets are one-time and expire after **60 seconds** (`TicketService.LIFESPAN = 60 * 1000`). The copy-link affordance on an unpublished issue must copy the **admin deep link to the issue**, not a file URL; only PUBLISHED files get a copyable public URL. The prototype's copy icon on a preview row points at an invented public preview path and must be corrected. Funding a signed, expiring, admin-scoped preview URL instead is **OQ-8**.

### 5.6 Preconditions and errors

| Condition | Response |
|---|---|
| `series.contentMode != GENERATED_FROM_QUERY` | `409 SERIES_NOT_GENERATED` |
| resolved member count > 1000 | `409 MEMBER_LIMIT_EXCEEDED` naming the issue and the count |
| the in-process renderer throws | `500 REPORT_GENERATION_FAILED` — same code the legacy 500 at `PublicationRestService.java:461-465` used for a non-200 from the old HTTP call; there is no HTTP status to mirror any more (§4.3 step 10a), so the trigger is an exception, not a status code |
| issue is PUBLISHED/RETIRED | allowed — the preview renders from the **frozen** snapshot, which is how an admin checks what an amend would produce |

---

## 6. Paging, sorting, filtering

### 6.1 Paging

`page` + **`maxSize`**, both `@DefaultValue`, envelope `{data, total, size, description}`.

> **`limit` is never a paging parameter on a new endpoint.** The shipped `publications-api.service.ts:51` sends `limit` where the backend declares `maxSize` (`PublicationRestService.java:118`), so **every publication picker in the shipped frontend silently caps at 100 rows today**, including the `limit: 200` browse call. The new endpoints accept `maxSize` only; the frontend fix ships independently (§9.4). The single exception is `GET /publication-categories/all`, which keeps `limit` because an anonymous export link depends on it.

**Paging is server-side, in the DB.** Legacy loads every matching id and paginates in memory (`PagedSearchResultVo.paginate` at `:88-109` over a fully-materialised list). One production series holds 1,077 issues and the dashboard renders per-series, so in-memory paging is not acceptable here. A failing search must return an error, never an empty result — today a caller cannot distinguish "no matches" from "the query blew up".

| Endpoint | `maxSize` default |
|---|---|
| S1, S2, C2, I27, I28 | `100` |
| I1, I2 | `20` |
| I10 (members) | `Integer.MAX_VALUE` — **unpaged by default.** A member list is an official set; truncating one by accident is the failure mode this redesign removes. Paging is available but opt-in. |
| S13 (probe) | `50` |
| I29 (dashboard timeline) | **not paged.** It is bounded by its own arguments instead: at most 50 `publicationSeriesId` values × `periods` (default `8`, max `52`) rows |
| public adapter | **unpaged**, matching legacy: `AbstractApiService.searchPublications` never sets `maxSize` and `PagedSearchParamsVo.maxSize` defaults to `Integer.MAX_VALUE` (`:34`) |

### 6.2 Sorting

| Endpoint | `sortBy` values | Default |
|---|---|---|
| S1/S2 | `name` · `category` · `updated` · `lastIssueAt` | `name` / `ASC` |
| I1/I2 | `interval` · `cutoff` · `name` · `edition` · `week` · `updated` | `interval` / `DESC` |
| C2 | `priority` · `name` | `priority` / `ASC` |
| I10 | — | **not sortable.** The order is `sortIndex`, which is data (§6.6). |

`sortOrder ∈ {ASC, DESC}`, reusing `PagedSearchParamsVo.SortOrder`.

### 6.3 Filtering

| Param | Type | Applies to | Semantics |
|---|---|---|---|
| `lang` | string | all reads | Narrows `descs` via `DataFilter.get().lang(lang)`. **Admin edit endpoints (S4, I4) ignore it and always return every configured language** — C5 makes a lang-narrowed payload uneditable. |
| `title` | string | S1/S2, I1/I2, **I27** | Substring, case-insensitive, matched **within the requested language** (legacy behaviour). The picker needs it: a browse popover over 1,077 issues is unusable without a type-ahead, and §9.3 signs the picker contract. |
| `status` | repeatable enum | S1/S2, I1/I2 | Series default `ACTIVE`; issue default **none** (all three). **I27 is not on this row and takes no `status` default of "all three"** — see the picker paragraph below. **I28 takes no `status` param at all** and ignores status entirely (§2.2). |
| `computedStatus` | repeatable enum | I1/I2 | Post-filter over the derived value, including `MISSING`/`UPCOMING`. |
| `domain` | string | S1/S2, **the one-off list** (`GET /one-off-publications/`) | **Amended 2026-08-29 (R-xxvi): an OWNER filter.** A series matches only when it is *owned* by the named domain; availability is deliberately not consulted here, because a publication shared with this desk is read-only here and every control on such a row would answer 403. **Omitting the parameter returns the estate** — that is load-bearing, not a fallback: the export, every script, and the estate-wide screens (the import report and the import check) read the list unscoped. Still sent explicitly by the admin lists rather than inherited from the `NiordDomain` header. Reads stay unscoped on the wire generally; what refuses a foreign owner is the client — the admin detail screens, which will not open a publication another desk owns. <br>*Superseded text:* "**Inclusive**: a series with `domain = null` matches **every** domain." |
| `category` | string (`categoryId`) | S1/S2 | |
| `cadence` | repeatable enum | S1/S2, I1/I2 | `NONE \| DAILY \| WEEKLY \| MONTHLY \| YEARLY`. **Required by the dashboard**, which partitions into "the cadenced series" and "the one-offs" — a one-off is `cadence = NONE` and nothing else discriminates it, since `contentMode` says how a document is produced, not how often. Without this parameter the two sections cannot be separated server-side at all and the landing screen has to fetch everything and filter client-side. |
| `contentMode`, `timeRelation`, `messagePublication` | enum | S1/S2, I27 | |
| `type` | enum (`PublicationType`) | I27 | The **legacy** vocabulary — `MESSAGE_REPORT \| REPOSITORY \| LINK \| NONE` — mapped through ruling **X2** onto `series.contentMode`. The shipped `PublicationsField` sends `type='MESSAGE_REPORT'` (`:24`) and `InsertPublicationsDialog` sends `[type]="null"`, and §9.3 signs both; omitting the param must still reach `LINK` rows. |
| `publicationSeriesId` | string | I1/I2 | |
| `from` / `to` | epoch ms | I1/I2 | Over `effectiveCutoff`. |
| `includeGaps` | boolean | I1/I2 | Default `true` **iff** `publicationSeriesId` is set, else `false`. **On a mixed-series page it is `false` and cannot be forced true** — "the interval span the page covers" has no meaning across series with different cadences, so gap synthesis needs exactly one series. The dashboard gets its per-series `MISSING` cells from I29 (§6.7) instead. Pickers pass `false`. |
| `includeGapCounts` | boolean | I1/I2 | Default `false` — `wouldMatchCount` costs one query per gap. |
| `includeExcluded` | boolean | I10 | Default `true`. |

**Picker status.** I27 is `@RolesAllowed(Roles.USER)` (§2.2), so it may carry `status` — but it does **not** inherit the issue default of "none (all three)". Its default is `{PUBLISHED, OPEN}`, matching the re-pointed frontend default (§9.4); an explicit `status` narrows within what the caller may see. I28 takes no `status` param and applies no status narrowing, because it hydrates ids the caller already holds and must keep resolving retired issues for the citation chips (P4, §9.5).

**Picker domain scoping.** I27/I28 are **not** domain-scoped, matching the shipped pickers. If they were, the four domain-free citation-only publications and the external links would vanish from the citation dialog. S1/S2 accept `domain` and the admin list sends it. That asymmetry is deliberate and is **OQ-11**.

> **Amended 2026-08-29 (R-xxvi) — OQ-11 is closed, and the asymmetry is now stated as two different questions.** I27 **does** take `domain`, and the message-editor citation picker sends the **session** domain; the narrowing is the **visible-from** predicate, not ownership:
>
> ```
> visible-from(series, X)  ≡  owner == X
>                          ∨  availability == ALL_DOMAINS
>                          ∨  (availability == SELECTED_DOMAINS ∧ X ∈ availableDomainIds)
> ```
>
> An **inactive** domain in the availability list is ignored by the predicate (a switched-off domain is not a desk anybody is sitting at). Naming no domain asks for no narrowing. The same predicate serves `/rest/publications/search?domain=` (anonymous, the issue half — it replaces the old `domain IS NULL OR domain = X`) and one shared fragment implements all of them, because a publication the picker offers and the search withholds is a citation that can be made and then not resolved. **I28, the deep links, by-message, member lists and the `?publication=` resolver stay unscoped** — a citation that exists must always resolve. The messages-list `?publication=` filter chips send the **search** domain, as the area/category/series filter panels do; resolution of a chosen publication stays unscoped.

> **The legacy half of the union follows its twin (R-xxvi).** The frozen legacy resource carries the old nullable `domain` column and can only answer `domain IS NULL OR domain = X`. Where a legacy row has an **imported twin** (matched by `publicationId`), the twin's visible-from verdict decides, and the merge drops the legacy row when the new rule hides it — otherwise the legacy row would show exactly the publication the new rule just hid. A legacy row with **no** imported twin keeps the old rule; nothing states who may cite it.

### 6.4 The issue list, gap detection, and one visible paging consequence

```
gapDetection = series.timeRelation == PUBLISHED_IN_INTERVAL
            && series.cadence != NONE
            && series.status == ACTIVE
            && !dormant
```

For an `IN_FORCE_AT_CUTOFF` series the response emits **no** pseudo-rows and the retro-create affordance is not offered. Those 531 issues **overlap rather than tile** — the 2026 and 2027 firing-areas issues share 31 of 32 members — so a "missing year" is a category error. This must be **asserted by a test**, not merely omitted.

**Amended 2026-08-24 — `gapDetection` is an object, and `gapCount` is ABSENT when the gate is closed.** This paragraph previously specified `gapDetection: false, gapCount: 0`. Implementing it surfaced that the two shapes a reader must distinguish — *nobody looked for gaps* and *somebody looked and found none* — serialize identically under it: both are a falsy flag beside a zero. The estate makes that the common case rather than an edge one, because the importer lands every series as `DRAFT` and `status == ACTIVE` is a gate term, so on cutover day **all twenty series report `gapCount: 0`** and the screen reads as a clean bill of health for an archive nothing examined. `0` is now reserved for the finding it describes, and the key is omitted otherwise:

```jsonc
{
  "publicationSeriesId": "weekly-ntm",
  "data": [ /* real issues and pseudo-rows, one sequence, newest first */ ],
  "total": 23,                       // REAL issues only — never the pseudo-rows
  "gapDetection": {                  // always present
    "enabled": false,
    "reasonCode": "SERIES_NOT_ACTIVE",
    "reason": "only an ACTIVE series is expected to keep producing issues"
  }
  // gapCount: absent. Present, and an integer, exactly when enabled is true.
}
```

`reasonCode` is one of `CADENCED_SERIES` (the gate is open) | `NO_CADENCE` | `SERIES_NOT_ACTIVE` | `SERIES_DORMANT` | `CADENCE_PERIOD_UNKNOWN`. **Amended 2026-09-11:** the time relation no longer closes the gate — `RELATION_NOT_TILING` is gone from the wire, and an active in-force series with a cadence reports `enabled: true`, a `gapCount`, its `UPCOMING` row and any `MISSING` rows after its newest cut-off exactly as a tiling series does. What the relation decides is how coverage *between* two releases is read: intervals on a tiling series; on every other series two consecutive releases cover the stretch between them by definition, and a period between them is `MISSING` only where a release was cut off in it and later retired. A client switches on the code and translates it; `reason` is the same fact in prose for a log or a diagnostic, and matching on the sentence breaks the first time the sentence is improved. `CADENCE_PERIOD_UNKNOWN` is not reachable from the enum as it stands — it fires if a cadence constant is ever added without a period length, which would otherwise make the synthesizer return an empty list and reintroduce this exact confusion one cadence along.

`IssueListResultVo` extends the paged envelope with `gapDetection: GapDetectionVo` and `gapCount: Integer`. It does **not** carry `omittedBounded` — omissions are a member-list concept and the flag belongs on `IssueMemberListVo` (§3.8), beside the `omitted[]` array it qualifies.

> **Absent, throughout.** Every VO here inherits `@JsonInclude(NON_NULL)` from `IJsonSerializable`, so a null field is omitted rather than emitted as `null` — including `publicId` on a pseudo-row, which §3.14 describes as "always null". Read those cells as *absent*. `pseudo` depends on it: a client tells a pseudo-row from a real one by the key being there at all.

> **Stated because it is visible:** gaps are synthesized **after** the DB page is selected, over the interval span the page covers, plus one row of look-ahead each side to resolve the boundary neighbours. Therefore **`total` and `size` count real issues only, and a page may render more rows than `maxSize`.** `gapCount` is reported separately. Moving gap synthesis to a separate `/gaps` call the UI merges client-side is **OQ-13**.

### 6.5 Live vs frozen — no request parameter

Keyed off issue status: OPEN (incl. computed UPCOMING) → **LIVE re-resolve**; PUBLISHED / RETIRED → **FROZEN snapshot**; anonymous → **FROZEN, published only**. **I30**, the issue workbench, keys off status in exactly the same way and adds no parameter of its own — a frozen issue's members are the record, and it is the same switch that withholds its rail and its omissions.

**No `publicationSnapshot=frozen|live` hatch ships.** It would create deep links whose meaning changes over time — the exact non-reproducibility the redesign exists to remove. It stays purely additive if ever genuinely needed.

### 6.6 Member order

Not a request parameter. Resolution order, promoted to **every** surface:

1. the series' explicit `messageSortBy` / `messageSortOrder`, if set;
2. else the **issue's series'** `domain.messageSortOrder` (split on space, per `MessageSearchParams.checkSortByDomain`);
3. else `AREA` / `ASC`.

**Never the caller's thread-local domain.** Today the surfaces disagree — the admin path uses the caller's current domain, the public API uses the first named publication's domain, and release uses whatever domain the releasing admin happened to be in — so the same issue can print in two different orders. `sortBy` decides more than sequence: `areaHeadings` is derived from `sortByArea()` (verified `MessageReportRestService.java:314`), so it decides whether the PDF has area headings **at all** — which is why the resolved `sortBy` is an explicit input to the in-process renderer (§4.3 step 10a), not something the renderer re-reads from a request.

On a FROZEN list the order is the stored `sortIndex`; the response echoes the `sortBy`/`sortOrder`/`sortDomainId` that produced it. Replay needs no new machinery — `MessageService.getMessages` already re-sorts to a caller-supplied id order via `ids.indexOf`, the same idiom as `PublicationService.java:124`. **The renderer is handed that order directly** (§4.3 step 10a): it receives the member list already sequenced by `sortIndex`, so the frozen order is what prints. Under an HTTP render the order would be re-derived from a query and the frozen `sortIndex` would be unreachable on the print and export paths — which is the second reason R1 was resolved the way it was.

### 6.7 The dashboard timeline — `GET /publication-issues/recent` (I29)

The landing screen renders, per series, a short strip of recent periods with a cell per period. Built from I1, that is **one request per series** — roughly 60 on the production estate — and it still could not render a `MISSING` cell, because `includeGaps` defaults `true` only when `publicationSeriesId` is set and "the interval span the page covers" is undefined for a mixed-series page. §3.1's `issueCounts.gaps` is a bare integer: it can say *four gaps*, never *which weeks*.

```
GET /rest/publication-issues/recent?publicationSeriesId=a,b,c&periods=8
```

**Request:** `publicationSeriesId` (comma-separated, 1..50, required — this endpoint never scans the estate) and `periods` (default `8`, max `52`), counted back in each series' own cadence from its newest effective cut-off. A `cadence = NONE` series returns its most recent `periods` issues in `effectiveCutoff DESC` order and no synthesized cells.

**Response:** one group per requested series, each carrying rows of

```jsonc
{ "publicationSeriesId": "weekly-ntm",
  "rows": [ { "label": "Uge 33", "week": 33, "year": 2026,
              "computedStatus": "PUBLISHED", "memberCount": 214,
              "publicId": "5cc9…", "intervalFrom": 1755000000000, "intervalTo": 1755600000000 }, … ] }
```

`publicId` is `null` on a synthesized cell, exactly as on a pseudo-row (§3.14). The rows are produced by **the same synthesizer §6.4 already specifies** — same `gapDetection` predicate, same interval-bound derivation, same `computedStatus` vocabulary including `MISSING` and `UPCOMING` — so the named gap week and the missing cell come for free and cannot drift from the issue list. `memberCount` follows §3.4's rule, `null` included. `wouldMatchCount` is **not** offered here: it is one query per cell and this endpoint exists to remove requests, not to move them.

**Tier:** `@RolesAllowed(Roles.USER)` — it carries nothing the editor tier does not already get from I1.

---

## 7. Error model

### 7.1 Shape, and the one piece of new house style

Legacy throws `new WebApplicationException(message, status)` with a plain-text body and **there is no JAX-RS `ExceptionMapper` anywhere in `niord-web`** — verified: the only match in the tree is a comment in `RepositoryServletFilter.java:36` saying *"The usual method would be to define a @Provider-annotated ExceptionMapper class for IOException, but…"*. A text body cannot tell a UI apart "wrong cut-off" from "missing file" from "not a curator".

**Add one `@Provider ExceptionMapper` scoped to the new package** emitting:

```json
{ "code": "ISSUE_INTERVAL_OVERLAP",
  "message": "Issue interval overlaps issue efs-uge-27-2026",
  "details": { "publicId": "5cc9…", "conflictingPublicId": "a8e6…" },
  "fieldErrors": [ { "field": "descs[1].fileName", "code": "FILE_NAME_NOT_DISTINCT" } ] }
```

`code` is a stable enum; `message` is server-rendered English **for the log, not for the user** — the UI renders from `code` + `details` through Transloco. `fieldErrors[]` is present only for validation failures. Anything outside the catalogue keeps the legacy bare-`WebApplicationException` behaviour, so nothing existing changes. Scoping it to the new package rather than changing global error behaviour is deliberate and is **OQ-14**.

### 7.2 The two loud failures

These are the ones C4 and C8 signed, and neither may be softened.

**(a) Unresolvable publication — C8 prerequisite 3.**

| Surface | Today | Required |
|---|---|---|
| `GET /rest/public/v1/messages?publication=<bogus>` | **returns the entire published corpus (244 messages)** — empty tags + empty domains + empty series falls through to "everything published" via `AbstractApiService.java:193-202`. There is no error branch at all: `:194` returns `new PagedSearchResultVo<>()`. | **`400 PUBLICATION_UNRESOLVABLE`**, body `Unknown or unavailable publication: <id>[, <id>…]` |
| `GET /rest/messages/search?publication=<bogus>` | same class of failure | `400 PUBLICATION_UNRESOLVABLE` |
| SOAP `ApiWebService.search` | shares the public method | the shared method throws; JAX-WS maps it to a SOAP fault. No separate code path. |
| I28 (comma-separated hydration — the only such endpoint, §2.2) | — | `200 []` — hydration **never** 404s or 400s, parity with `Publication.findByPublicationIds`. Unknown ids are silently omitted. |

Resolution order, and it **never widens**: new `PublicationIssue.publicId` → legacy `Publication.publicationId` → **fail loudly**.

Four cases, ruled:

| Case | Response |
|---|---|
| id resolves to nothing | `400` |
| id resolves but is not servable to the caller's tier (an OPEN issue on the anonymous path) | `400`, **identical message text** — no existence oracle |
| id resolves, is servable, and the series **declares no membership** (`contentMode ∈ {UPLOADED_FILE, EXTERNAL_LINK, NONE}`, or `membershipProvenance = NO_MEMBERSHIP`) | **`200` + empty result.** "This publication has no messages" is a true, well-defined answer; "this id does not exist" is not. |
| one id of several fails | **`400` for the whole request** — a partially-honoured filter is exactly the silent wrongness being removed |

> The third row **splits from C8's signed text**, which groups "the 27 tagless ACTIVE publications" with the bogus id and would make them a 400. The split changes live behaviour for bookmarked `?publication=<Dansk Fyrliste>` links (today: 236 messages). Recorded as **OQ-2** — it is a real product choice, not an editorial one.

**(b) Unknown filter on import — C4's signed acceptance criterion.**

`POST /publication-series/import-legacy` (S19) → **`422 UNKNOWN_LEGACY_FILTER`**, body a `LegacyImportReportVo` naming the `publicationId`, the title and the **verbatim** filter string. The whole import fails; nothing is written. Not a warning, not a skip, not a partial estate. The same code is returned by S18 (dry-run) with `422` and the full report, so an admin can see every offender at once.

Same treatment for print settings: any imported publication carrying a `printSettings` key outside the typed set → `422 PRINT_SETTING_NOT_ALLOWED` naming the key. Those keys silently change what is in the PDF and in what order, so importing them "best effort" is worse than failing.

### 7.3 Catalogue

> **Superseded (2026-08-28).** The shipped catalogue is `PublicationErrorCatalogue.java` — one status per
> code, enforced at class-init. Sixteen codes below were renamed, restatused or never built, and about thirty
> exist that this table never named. The reconciliation is the last amendment section at the foot of this
> file; read it before branching on any code here.

| Code | HTTP | Raised by | Meaning |
|---|---|---|---|
| `SERIES_NOT_FOUND` / `ISSUE_NOT_FOUND` / `CATEGORY_NOT_FOUND` | 404 | reads | Never a null-dereference 500 |
| `SERIES_ID_TAKEN` / `CATEGORY_ID_TAKEN` | 409 | S8, C4 | Duplicate authored key |
| `SERIES_ID_IMMUTABLE` / `CATEGORY_ID_IMMUTABLE` | 400 | S9, C5 | The interchange key may not change |
| `SERIES_NOT_EMPTY` | 409 | S11 | Names the issue count |
| `SERIES_INACTIVE` | 409 | I7, S12 | New issues only on an ACTIVE series |
| `SERIES_NOT_QUERY_BACKED` | 409 | I10 | `contentMode` has no membership semantics |
| `SERIES_NOT_GENERATED` | 409 | I21 | Preview asked for on an uploaded/link/none series |
| `INVALID_STATUS_TRANSITION` | 400 | S10 | Body carries the exact legacy text |
| `REASON_REQUIRED` | 400 | S10, I17–I19 | The override endpoints raise `OVERRIDE_REASON_REQUIRED` instead — one condition, one code per surface |
| `ISSUE_INTERVAL_OVERLAP` | 409 | I7, I8 | **Only for `PUBLISHED_IN_INTERVAL`.** `IN_FORCE_AT_CUTOFF` issues legitimately overlap and must never be blocked. |
| `INTERVAL_INVALID` | 400 | I7, I8, S12 | `to <= from`; an interval on a series that has none; a missing one on a series that requires it |
| `ISSUE_NOT_OPEN` / `ISSUE_NOT_PUBLISHED` / `ISSUE_NOT_RETIRED` | 409 | writes, actions; `ISSUE_NOT_OPEN` specifically from I8, **I12–I14** (§4.11) and **I25** (§4.12) | |
| `ISSUE_ALREADY_PUBLISHED` | 409 | I16 | Double-click safety; the first call won |
| `ISSUE_NOT_DELETABLE` | 409 | I9 | §4.10 |
| `CUTOFF_BEFORE_PREVIOUS` | 400 | I16 | Would break chaining. Decisive to the second — one production issue's member set changes **4 seconds** after its stamp. |
| `CUTOFF_AFTER_SUCCESSOR` | 400 | I16 | The mirror of the row above, and the violation the `CUTOFF_BEFORE_SUCCESSOR` checklist item reports (§3.10, ruling **R5**). A retro-recovered issue must stamp **below** the successor that already published |
| `CUTOFF_IN_FUTURE` | 400 | I16 | Unless `allowFuture` |
| `MISSING_FILE_FOR_LANGUAGE` | 409 | I16 | The `FILE_PRESENT_PER_LANGUAGE` violation, and it is **scoped exactly as that checklist item is** (§3.10): `contentMode ∈ {UPLOADED_FILE, EXTERNAL_LINK}`, plus a sticky language of a generated series. Never raised for `contentMode = NONE`, and **never for the non-sticky languages of a `GENERATED_FROM_QUERY` series** — those are guaranteed by the step 10c assertion instead, which fails as `REPORT_GENERATION_FAILED` |
| `REFERENCE_FORMAT_MISSING_LANGUAGE` | 400 | S8, S9, **I16** | **Only when `messagePublication != NONE`.** On I16 it is the refusal code for the `REFERENCE_FORMAT_COMPLETE` checklist item (§3.10) — checklist items name the condition, error codes name the violation. It is raised at publish as well as at save because the save-time rule is a *series* invariant (data model **S-13**, enforced at DRAFT→ACTIVE), so a series activated before the citation option was turned on carries no format and would otherwise be blocked by the rail with no code to report |
| `FILE_NAME_NOT_DISTINCT` | 400 | S8, S9, I8 | Otherwise every language renders to one path and the last language wins |
| `UNKNOWN_TOKEN` | 400 | S8, S9 | A `${…}` outside the vocabulary for this cadence/scheme. Production serves a real PDF at `…/Skydeomraader-%24%7Byear%7D.pdf` because an unresolved token reached a file name. |
| `PRINT_SETTING_NOT_ALLOWED` | 400 / 422 | S8, S9 / S19 | |
| `CRITERIA_INVALID` | 400 | S8, S9, S13 | `details` carries the JSON Pointer |
| `ADVANCED_EXPRESSION_NOT_SUPPORTED` | 400 | S8, S9 | C4 cut the hatch from Phase 1 |
| `UNKNOWN_LEGACY_FILTER` | **422** | S18, S19 | §7.2(b) |
| `NOT_CURATOR` | 403 | I12–I14, **I17 when the body carries a `curation` delta** | `Permissions.PUBLICATION_CURATE` absent |
| `OVERRIDE_REASON_REQUIRED` | 400 | I12, I13, **I14** | Required on `clear` too (§3.12, §4.11) |
| `OVERRIDE_REDUNDANT` | 409 | I12 | INCLUDE of a uid the query already returns — two competing why-lines for one row |
| `UNKNOWN_MESSAGE_UID` | 400 | I12–I14 | Whole selection rejected; `details.messageUids[]` lists every offender |
| `MEMBER_LIMIT_EXCEEDED` | 409 | I16, I21 | A loud stop for an official set, naming the issue and the count. **The 1000 is now a product choice, not an inherited constraint**: its old justification was `report.pdf`'s silent `maxSize(1000)` truncation (`MessageReportRestService.java:289`), and the in-process renderer (§4.3 step 10a) takes that pipeline off the publication path entirely. Largest live member set is 228, so any figure in this range is headroom. Threshold is **OQ-15**, to be re-decided on that basis. |
| `WARNING_NOT_ACKNOWLEDGED` | 400 | I16 | `details.codes[]` |
| `ISSUE_BUSY` | 409 | I16, I17 | A release or an amend is already running on this issue. Added 2026-09-14; `details` carries the running `action` and its `since` |
| `PREVIEW_STALE` | 409 | I16 | Publishing `regenerate=false` against a **stale** preview | **Retired 2026-09-08 — the code is removed from the catalogue with the option.**
| `NO_PREVIEW` | 404 | I22, I23 · **409 on I16** | Never generated, or swept. On I16 it is the `regenerate = false` precondition (§4.3): no preview file for some non-sticky configured language, so there are no bytes to promote | **2026-09-08: the I16 409 form is gone with the option; the code survives as the 404 of I22/I23 only.**
| `ARCHIVE_ENTRY_NOT_FOUND` | 404 | I26 | |
| `UNSUPPORTED_FILE_TYPE` | 415 | I24 | |
| `REPORT_GENERATION_FAILED` | 500 | I16, I17, I21 | The in-process renderer threw, or §4.3 step 10c found a language with no file. Same code the legacy 500 at `PublicationRestService.java:461-465` used; the trigger is now an exception rather than a non-200 |
| `ARCHIVE_FAILED` | 500 | I16, I17, **I24 on a PUBLISHED issue** | **The transaction rolls back.** C3 is a one-way door. |
| `CATEGORY_IN_USE` | 409 | C6 | Replaces an opaque FK transaction error |
| `MESSAGE_PUBLICATION_IMMUTABLE` | 400 | S9 | P5 (§9.5): the citation channel may not flip once any issue of the series has published |
| `REFERENCE_FORMAT_WITHOUT_NAME` | 400 | I8, I7 | §8.5: a desc row with a `messageReferenceFormat` and a blank `name` fails `descDefined()` and vanishes from the public payload |
| `STALE_VERSION` | 409 | every write, **including I12–I14** — the forced version bump of §4.11 is what makes it fire there | |
| `CONCURRENT_PUBLISH` | 409 | **every §4 action** — §4.1 takes the series row lock for all of them, not only publish and amend | Series row lock not acquired |
| `PUBLICATION_UNRESOLVABLE` | 400 | `?publication=` on both call sites | §7.2(a) |

---

## 8. The public adapter — field by field

### 8.1 Which endpoint is the contract

| Endpoint | Is it the public contract? |
|---|---|
| `GET /rest/public/v1/publications` → `AbstractApiService.searchPublications` (`:239-252`) | **YES.** `.statuses(ACTIVE).published(true).mainType(PUBLICATION)`, `from = to = now` default (`ApiRestService.java:369-372`). |
| `GET /rest/public/v1/publication/{publicationId}` → `AbstractApiService.getPublication` (`:228-233`) | **YES.** `status == ACTIVE && category.isPublish()`, **ignoring** the date window — which is what makes deep links to historical issues resolve. |
| `GET /rest/publications/search` (`PublicationRestService.java:104-136`) | **NO.** It is the *picker* endpoint, not the public site's. Anonymous (`@PermitAll` at `:77`); **since 2026-09-25 (OQ-18) a caller who is not a signed-in user reads the public tier** — `status=ACTIVE`, `published=true`, `mainType=PUBLICATION` whatever the query asks, PUBLISHED issues in a publishing category, and no legacy row an issue has taken over (`publishedAt IS NOT NULL`). A signed-in user keeps the unfiltered search. `/all` is narrowed the same way. |
| `GET /rest/publications/publication/{ids}` (`:223-232`) | **NO**, but it is the **hydration** contract for every picker and every URL-restored filter, and must keep resolving the whole historical id space (§9.5). |

**`PublicationVo` is frozen.** `@XmlType(propOrder = {"category","type","publishDateFrom","publishDateTo","descs"})` with `publicationId`/`created`/`updated` as `@XmlAttribute` (`PublicationVo.java:36-38, 102-127`) and `PublicationDescVo` with `propOrder = {"title","titleFormat","link","fileName","messagePublicationFormat"}` (`:30`). **No field may be added, removed, renamed or reordered.** This is why the picker gets its own DTO (§3.16) and why `fileSource` is structurally impossible to leak here.

**Required refactor:** change `AbstractApiService.searchPublications(...)` to return `List<PublicationVo>` and `getPublication(...)` to return `PublicationVo`, and split `ApiRestService.toPublicationVo` (`:456-480`) into a pure `externalize(PublicationVo, baseUri)`. That lets the adapter emit VOs for **both halves of the public union** (§8.6) without fabricating a transient `Publication` entity. The 404 branch, the `dateFormat` handling and `@GZIP` are untouched.

### 8.2 The visibility-window derivation — the one that must not be got wrong

> **V1.** `publishDateFrom = issue.publicFrom = this issue's `cutoffStampedAt``
> **V2.** `publishDateTo = issue.publicTo = the NEXT issue's `cutoffStampedAt` − 1 ms`; `null` when there is no next issue.

The public window is **offset from the content interval by one period**. Live: `EfS uge 33 - 2026` carries `publishDateFrom = 2026-08-12T10:00Z`, `publishDateTo = 2026-08-19T09:59Z`, while its release stamp is `2026-08-12T10:30:18Z`. The content interval *ends* at the cut-off; the public window *starts* there.

**Mapping `intervalFrom`/`intervalTo` onto `publishDateFrom`/`publishDateTo` — the only mapping the entity names suggest — gives every issue the previous period's window, and under the `from = to = now` default the newest issue is never current.** The site would show last week's EfS from the moment of cutover. This is the single failure the capability ledger flags twice.

They are **stored columns, not per-request derivations**, because the date filter is a SQL predicate and because publishing issue *N+1* must retroactively cap issue *N* (§4.3 step 13).

| Series shape | `intervalFrom` | `intervalTo` | `cutoffStampedAt` | `publicFrom` | `publicTo` |
|---|---|---|---|---|---|
| Weekly EfS (`PUBLISHED_IN_INTERVAL`) | prev stamped cut-off | nominal Wed 12:00 | actual release moment | = `cutoffStampedAt` | next cut-off − 1 ms |
| P&T / firing areas (`IN_FORCE_AT_CUTOFF`) | **NULL** | nominal cut-off | actual release moment | = `cutoffStampedAt` | next cut-off − 1 ms |
| One-off (`NO_MEMBERSHIP`, `cadence = NONE`) | **NULL** | **NULL** | **NULL** | admin-set, `MANUAL` | **NULL** — forever current |

**`MANUAL` means "genuinely open-ended", and nothing else** (ruling **R8**). It is not the importer's default: an imported issue of any series **with a cadence** is written `DERIVED`, because a historical visibility window is a derived fact rather than an admin choice. `MANUAL` is reserved for the one-off row above. Marking imported cadenced issues `MANUAL` would exempt the newest of them from §4.3 step 13's cap — which skips `MANUAL` by design — and the first native publish would leave **two current EfS issues** on the public site. Publish therefore also caps a predecessor whose `publicTo IS NULL` regardless of its source, and cutover asserts at most one uncapped issue per series (§3.13).

**Recorded parity difference (OQ-16).** Legacy `publishDateFrom` is the *nominal* cut-off hour (Wed 12:00 local); the exact stamp arrives 15–30 min later. Emitting the exact stamp is strictly more correct but changes the dates any external consumer *renders*. The alternative is to round `publicFrom` to the series' nominal cut-off time when the series has a cadence.

### 8.3 The eligibility predicate

```
issue.status = PUBLISHED                                  -- OPEN and RETIRED both excluded
AND issue.publicFrom IS NOT NULL
AND series.category.publish = true
AND overlaps(issue.publicFrom, issue.publicTo, from, to)  -- CriteriaHelper.overlaps, verbatim
```

Three things that must **not** enter it:

1. **The series lifecycle status.** A dormant or retired *series* must keep serving its already-published issues, or every citation into that back catalogue goes dark — and citations live in stored message HTML with no endpoint that can clear them.
2. **The series `domain`.** `/public/v1/publications` has never been domain-scoped; the 17 domain-free rows are global.
3. **Paging.** Keep it unpaged (§6.1). The union must merge before anything is truncated.

`from`/`to` default unchanged: `if (from == null && to == null) from = to = System.currentTimeMillis();`. Arbitrary historical windows keep working, which is how the download site renders past years.

### 8.4 Ordering

Legacy is `category.priority ASC, publishDateFrom DESC`, server-side, **with no tiebreak** — so the EfS row and its P&T twin sort non-deterministically today.

```
ORDER BY series.category.priority ASC,
         issue.publicFrom          DESC,
         issue.publicId            ASC               -- new: total order
```

The order is applied in `PublicationPublicAdapter` after the new and the legacy halves are merged (a legacy row sorts by its own category's priority, `publishDateFrom` and `publicationId`; a missing priority or date sorts last). `series.sortOrder` is stored, not read: it was once planned as a third key before the id and was never wired in.

The id key is a **deliberate design choice**, not parity: it gives the public list a total order, at the cost of changing the emitted order of the EfS/P&T twin pair. It is stated as a design choice, never as parity; the publications domain rules are in [`domain-rules.md`](domain-rules.md) (**OQ-17**).

### 8.5 Field-by-field mapping

| `PublicationVo` field | Source | Rule |
|---|---|---|
| `publicationId` *(@XmlAttribute)* | `issue.publicId` | Verbatim. Byte-identical to the legacy id for an imported issue. |
| `created` *(@XmlAttribute)* | `issue.created` | |
| `updated` *(@XmlAttribute)* | `issue.updated` | Last-write stamp — **not** the release moment. Parity preserved deliberately; the release moment is `publishDateFrom`. |
| `category` | `series.category.toVo(dataFilter)` | Unchanged code path. |
| `type` | **derived** from `series.contentMode` (**ruling X2**): `GENERATED_FROM_QUERY→MESSAGE_REPORT`, `UPLOADED_FILE→REPOSITORY`, `EXTERNAL_LINK→LINK`, `NONE→NONE` | Total and bijective. A per-language `fileSource = UPLOADED` (C6) does **not** change it — C6 is explicit that it is not a content type per issue. |
| `publishDateFrom` | `issue.publicFrom` | **V1.** Not `intervalFrom`. |
| `publishDateTo` | `issue.publicTo` | **V2.** Not `intervalTo`. `null` = open-ended. |
| `descs[]` | `issue.getDescs(compFilter)` then `.filter(PublicationDescVo::descDefined)` | Must go through `ILocalizable.getDescs(DataFilter)` so the **fallback to `descs.get(0)`** fires — that is how a Danish-only annex appears in an English query. Filtering exactly as `Publication.java:259-264` does. |
| `descs[].lang` | `desc.lang` | |
| `descs[].title` | `desc.name` | |
| `descs[].titleFormat` | **always `null`** | A template field; published rows carry a concrete title. |
| `descs[].link` | `desc.link`, externalised | §8.6 |
| `descs[].fileName` | `desc.fileName` | |
| `descs[].messagePublicationFormat` | `desc.messageReferenceFormat` | Already token-substituted for this issue. Drives the citation dialog's `${parameters}` field. |

**Never emitted, at all:** `fileSource`, `fileSourceSticky`, `filePath`, `fileHash`, `fileSize`, `repoPath`, `status`, `criteria*`, `membershipProvenance`, `memberCount`, `cutoffStampedAt`, `cutoffSource`, `intervalFrom`, `intervalTo`, `edition`, `week`, `year`, `supersedes`, `legacyPublicationId`, `publicWindowSource`. Most are structurally impossible (the XSD has no slot); the list is written down so nobody proposes widening the XSD to add one.

> **A save-time validation the adapter depends on.** `PublicationDescVo.descDefined()` is `fieldsDefined(title, titleFormat, link, fileName)` (verified `:44-46`) — it does **not** count `messagePublicationFormat`. A desc row carrying *only* a reference format therefore round-trips to nothing and its citation text silently disappears from the public payload. **Reject saving an issue desc that has a non-blank `messageReferenceFormat` and a blank `name`** (`400 REFERENCE_FORMAT_WITHOUT_NAME`).

### 8.6 `externalize`

Unchanged: with `externalize=true` (default), every non-blank `descs[].link` not matching `^(?i)(https?)://.*$` is prefixed with `app.getBaseUri()` (`ApiRestService.java:466-477`).

Obligations this places on the new model:
- Repository-hosted files store a **relative** link, exactly as `repositoryService.getRepoUri(path)` produces. An absolute stored URL makes `externalize` a no-op and bakes the wrong host into every environment.
- `EXTERNAL_LINK` issues store an absolute `https://…` and are skipped by the regex.
- **A newly published issue's path must contain no revision segment.** Legacy emits `…/publications/5/cc/<uuid>/1/EfS-Uge-33-2026.pdf` — the `1` is `Publication.revision`, and `toVo` hands out `revision + 1` on **every read** (`Publication.java:269`, comment and all: `// NB: Increase revision number`), so a save always lands in a new folder. That is precisely why an amend changes the public URL today and why only 26 of 68 publications at revision ≥ 2 still serve their previous revision. C3 requires a stable URL, so there is **no `revision` column** and no new issue ever writes a revision segment; the outgoing file is copied to an archive root outside the repository before being overwritten in place.
- **The rule is scoped to *newly published* issues** (ruling **R6**). **Imported issues keep `repoPath`, `filePath` and every `descs[].link` exactly as legacy wrote them, revision segment and all** — that literal string is the citation `href` inside stored message HTML, so re-deriving a flat path would 404 the file *and* every citation pointing at it, across 1,077 public URLs. `repoPath` is therefore **importable** rather than always re-derived, and the containment constraint is `filePath startsWith issue.repoPath` — which holds for both layouts. The alternative, a one-time file move, would require a verification pass over every stored link plus a documented answer for the 42 rows whose bytes are already gone. An imported issue that is later **amended** archives and overwrites in place at its own stored path, so its URL stays stable too.

### 8.7 `/public/v1/publication/{publicationId}`

Same predicate minus the date window, with `status = PUBLISHED`. **Preserve the indistinguishable 404** — body `"No publication found with ID: " + publicationId` (`ApiRestService.java:428`) — for missing / OPEN / RETIRED / unpublished-category alike. It is an existence-oracle guard, not an accident.

> **A RETIRED issue 404s here while its file link and its `?publication=` membership stay live.** That is legacy parity and it is exactly what C7's confirm copy promises. Write it down; it is the kind of asymmetry that gets "fixed" by accident.

### 8.8 The public union

Both models hold the same weeks for as long as an imported issue has not been published. Serving both would put two "EfS uge 33" rows on the public site.

```
publications(from, to, lang) = NEW_HALF ∪ LEGACY_HALF
```

- **`NEW_HALF`** = every issue satisfying §8.3. No column narrows it: a published issue in a publishing category is served.
- **`LEGACY_HALF`** = the existing `PublicationSearchParams` query, **minus** every publication whose `publicationId` appears in
  `SELECT i.legacyPublicationId FROM PublicationIssue i WHERE i.legacyPublicationId IS NOT NULL AND i.publishedAt IS NOT NULL`.
- **The exclusion is row by row, and it turns on `publishedAt`** — set at publish, never cleared, including by retire. A legacy row stands until the issue imported from it has been published; from that publish on the issue stands in its place. A series whose rows were never imported keeps being served from the legacy half indefinitely. The predicate is *having been published*, not *being published now*, and gating it on the current status gets one case wrong in each direction: an issue sitting at OPEN between import and first publish has **not** taken over, so excluding on it removes the legacy row with nothing in its place and the publication vanishes from the public site; an issue published and then RETIRED **has** taken over, permanently, so gating on `status = PUBLISHED` lets the exclusion lapse on retire and the ACTIVE legacy row silently returns to the public list carrying its original uncapped window.
- **Dedup key = the emitted `publicationId`.** Because an imported issue *reuses* the legacy id as its `publicId`, the naive union emits the same id twice. After merging, assert `distinct(publicationId)`; on violation **log ERROR naming both sources and drop the later row**. Never serve duplicates; never silently pick one. **It is a safety net, not a route anything is expected to take**: a released imported issue always carries a `publishedAt` — the witnessed release where one exists, otherwise the issue's own `publicFrom` (§3.1.8 of `DATA-MODEL.md`) — so the exclusion fires for every one of them, and `import-check`'s `PUBLISHED_ISSUE_WITHOUT_STAMP` refuses an estate where it would not.
- **Sort after merging**, using §8.4's key over the projected VOs — both halves expose `category.priority` and `publishDateFrom`.
- **Nothing switches the two halves on or off.** There is no per-series flag, no estate-wide setting and no direction to run one in; the union is a fact about the data. Backing out of a go-live window is a full database restore, and the import refuses to run twice (`ALREADY_IMPORTED`, keyed on the legacy ids), so a rehearsal always starts from a fresh restore.

The same union governs `/public/v1/publication/{id}`, `/rest/publications/publication/{ids}` and the `?publication=` resolver: one shared `PublicationResolver.resolve(publicId)` that tries the new model first, then the legacy table, then fails loudly.

---

## 9. Backwards compatibility

### 9.1 `?publication=` — the four call sites

| # | Site | Verified at | Auth |
|---|---|---|---|
| 1 | Admin/authenticated message search — publications folded into `params.getTags()` | `MessageSearchRestService.java:131-133` (MaDaMe ≈ `:141-143`) | class `@PermitAll`; branch runs only when a search domain exists |
| 2 | Public message API — an independent second copy of the same resolution | `AbstractApiService.java:118-135` | anonymous |
| 3 | SOAP `ApiWebService.search` | **shares site 2's method**, so it is carried automatically | anonymous |
| 4 | Mailing-list triggers | call `messageService.search(params)` directly, so `publication=` parses and is **silently discarded** — `MessageService.java:1154-1161` matches on **tags only** | — |

Sites 1 and 2 are C8's mandate. Site 4 is a pre-existing defect that the fix cures for free.

**The re-point.** Add an issue-membership predicate **beside** the tag predicate in `MessageService.searchPagedMessageIds` and delete both REST-layer resolution sites:

```java
// Tags OR publication-issue membership — a UNION, matching legacy tag-merge semantics
List<Predicate> setPredicates = new ArrayList<>();
if (!param.getTags().isEmpty())         setPredicates.add(<existing tag predicate at :1154-1161>);
if (!param.getPublications().isEmpty()) setPredicates.add(msgRoot.get("id").in(resolution.memberDbIds()));
if (!setPredicates.isEmpty())           criteriaHelper.add(builder.or(setPredicates.toArray(Predicate[]::new)));
```

- **It must be an `or`, not a second conjunct.** Today multiple publications become multiple tags which `MessageService` ORs; moving the predicate into its own conjunct would silently turn a **union into an intersection**.
- Multiple `publication=` ids ⇒ union of member sets, deduplicated by `uid`.
- **Dependency direction:** `PublicationService` already injects `MessageService`, so `MessageService` must not inject the publication service back. Inject a narrow `IssueMembershipPredicateSource` interface implemented in the publication package.
- `MessageSearchParams.publications` must become a `LinkedHashSet` so "the first publication" (the sort-domain source) is deterministic — it is a `HashSet` today.
- **Side benefit:** a member set is a uid list, not a date window, so the re-point removes `adjustDateInterval`'s whole-day rounding hazard for publication-filtered searches.

**Reproducing the two widenings from the frozen snapshot.** Both are triggered by *tag presence* today. Replace the trigger with `resolution.designatesMemberSet()`, defined (ruling **R7**) as **"at least one `publication=` id resolved to an issue the caller may see"** — *independent of how many members that issue has*. Zero members is a resolved answer, not a failure to resolve: defining the predicate on member count instead would let a `NO_MEMBERSHIP` publication fall through to the widening branch and return **the entire published corpus**, which is live defect 1 reinstated inside the model built to remove it. The predicate builder must therefore emit an **explicit empty disjunction** for an empty member set rather than `in(emptyCollection)`, which several JPA providers render as a no-op. A fixture must assert that a `NO_MEMBERSHIP` issue returns **0** messages, not 244.

| Location | Today | New |
|---|---|---|
| `AbstractApiService.java:137` | `boolean tagsSpecified = !params.getTags().isEmpty();` | `boolean memberSetDesignated = resolution.designatesMemberSet();` |
| `:142` (default to published domains) | `!tagsSpecified && !domainsSpecified && !messageSeriesSpecified` | `!memberSetDesignated && …` |
| `:193` (return empty) | `params.getTags().isEmpty() && params.getSeriesIds().isEmpty()` | `!memberSetDesignated && params.getSeriesIds().isEmpty()` |
| `:198-202` (status set) | tags empty → `{PUBLISHED}`; else the hardcoded triple | `!memberSetDesignated` → `{PUBLISHED}`; else **derive** `Arrays.stream(Status.values()).filter(Status::isPublic)` — `{PUBLISHED, CANCELLED, EXPIRED}` |
| `MessageSearchRestService.java:144-176` (the whole domain/series/status restriction block, gated on `params.getTags().isEmpty()`) | tag presence | **`!memberSetDesignated && params.getTags().isEmpty()`** — the **union** of both triggers, not a replacement |

> **Why that one row is a union and the four above it are replacements.** On `/rest/messages/search`, `params.getTags()` is fed from **two** sources: the `tag=` query parameter (`MessageSearchParams.java:149`) and the publication-to-tag conversion (`MessageSearchRestService.java:132`). Once the conversion is deleted only the first remains — so *replacing* the gate with `!memberSetDesignated` silently re-imposes the restriction block on every **plain `tag=` search**, dropping cross-domain tagged messages through the `seriesIds` restriction (`:144-160`) and stripping non-public statuses (`:163, :174`). That is a behaviour change to an endpoint this redesign is not supposed to touch. The four `AbstractApiService` rows stay plain replacements: `/rest/public/v1/messages` declares **no `tag` parameter at all**, so `tagsSpecified` there really is publication-driven and nothing else can set it.

The status widening is what keeps a published issue serving members cancelled or expired *after* release: **all 2,324 blank-era members are EXPIRED or CANCELLED and zero are PUBLISHED**, so a literal `status == PUBLISHED` filter empties every historical issue.

The restriction lift must branch on *"resolved to a member set"*, not on *"tags non-empty"* — otherwise a publication-only query is silently series-restricted to the caller's current domain and an official issue prints truncated.

> **Correction, recorded because the wrong justification is in circulation.** The signed brief justifies the restriction-lift with *"this is what keeps the Greenland section inside EfS"*. That is false and must not be repeated: `niord-nm` resolves to exactly one message series (`dma-nm`), Greenland lives in `ako-nw`/`niord-gl-nw` with no publication bound to it, and all 7,887 distinct members across 498 replayed issues are `dma-nm`. **Keep the behaviour, change the reason:** (i) a frozen snapshot is an explicit uid list and must be served verbatim regardless of the caller's series scope, or the record and the query disagree; (ii) 17 live publications are domain-free and legitimately cross-domain; (iii) an admin browsing in another domain would otherwise see a truncated official issue.

**Legacy fallback, with the missing gate added.** During transition the resolver falls back to `Publication.findTagsByPublicationIds` for a `publicId` that has no issue yet. Two fixes while there: (1) the named query has **no status filter**, so a DRAFT/RECORDING publication's tag resolves anonymously today even though the single-publication read gates on ACTIVE — add `and p.status = 'ACTIVE'` on the PUBLIC path; (2) resolve publication → tag **by the stored join only, never by scanning the tag table** — six tags are shared by multiple publications and nine are orphans, and two tags are each shared by **three** publications, so `?publication=` on any of them returns the others' contents today.

**Tier and freshness per id:**

| Issue state | anonymous | authenticated | Members from |
|---|---|---|---|
| `PUBLISHED` | resolvable | resolvable | frozen snapshot |
| `RETIRED` | resolvable | resolvable | frozen snapshot — C7 requires citations not to dangle |
| `OPEN` | **not** resolvable → `400` | resolvable (C9 editor tier) | live re-query |
| unknown | `400` | `400` | — |
| no membership semantics | `200`, empty | `200`, empty | — |
| any, in a non-publishing category *(2026-09-25)* | **not** resolvable → `400`, same text as unknown | resolvable | as its state says |

The last row applies to a legacy row as well: an ACTIVE publication in a category that does not publish designates nothing anonymously, which is the gate the single read (`/public/v1/publication/{id}`) already applied.

### 9.2 Endpoints that stay exactly as they are

| Endpoint | Why |
|---|---|
| `GET /rest/publications/search`, `/all`, `/publication/{ids}`, `/editable-publication/{id}`, the whole legacy CRUD and release path | The legacy `Publication` table is **not dropped**: the public union reads it, the citation resolvability floor reads it, and the legacy JSON export contract points at it. |
| `GET /rest/publication-categories/all` | Anonymous, `limit` param, documented export link. |
| `POST /rest/messages/extract-message-publication`, `POST /rest/messages/update-message-publications` (`MessageRestService.java:878-923`, both `@RolesAllowed(Roles.EDITOR)`) | **Unchanged signature.** The new model must provide `PublicationResolver.findByPublicId(String)` returning something convertible to `SystemPublicationVo` carrying `publicationId`, `messagePublication`, and per-language `link` + `messagePublicationFormat` — because that is exactly what `PublicationUtils` reads. Both endpoints return `null` for an unknown id, which the frontend treats as "not embedded in this message"; **preserve that silent branch**. |
| `report.pdf`, `export.zip`, `message-mail/send` | All call `MessageSearchParams.instantiate(...)` and delegate to site 1, so they inherit the re-point for free. Do not re-plumb them. They keep serving **interactive** print and export unchanged; what changes is that **publication rendering no longer goes through `report.pdf`** — publish, amend and preview call the in-process renderer directly (§4.3 step 10a), so no publication flow depends on this endpoint, its query string, its admin ticket or its `maxSize(1000)`. |

### 9.3 The four shipped frontend consumers

| # | Consumer | Binds to |
|---|---|---|
| 1 | `PublicationsApiService` | `GET /publications/search` (`:65`), `GET /publications/publication/{ids}` (`:81`) |
| 2 | `PublicationsField` | #1; defaults `type='MESSAGE_REPORT'` (`:24`), `statuses=['ACTIVE','RECORDING']` (`:26`) |
| 3 | `PublicationFilterPanel` | #2 multi-select; the `publication=` URL param (`messages-filter-params.serializer.ts:131-135` emit, `:371-377` restore) |
| 4 | `InsertPublicationsDialog` | #2 with `[type]="null"`, `[statuses]="['ACTIVE']"`, `[messagePublication]="kind()"`; plus the two citation endpoints |
| 5 | `PublicationField` (hosts #4) | the `publicationSpan` / `publicationLinkAttribute` TipTap marks that round-trip `publication="<id>"` |

**Must keep working, unchanged:**

| Contract | Requirement on the new API |
|---|---|
| URL param name and value space `publication=<publicId>` | **Frozen.** Renaming it breaks every saved named filter and every shared deep link. The *label* may become "Udgaver" — one i18n line, and Danish strings are legacy-sourced or `da_<verbatim English>` placeholders, never invented. |
| Anonymous comma-separated hydration | **Served by I28 `GET /publication-issues/by-ids`** (§2.2). Must resolve the **whole historical id space** (legacy ids and new `publicId`s — the same strings), ignore status and `category.publish`, silently omit unknown ids, stay anonymous. This is why a chip for a retired issue still renders its title — and why I28 applies no `PUBLISHED` narrowing. |
| `messagePublication=EXTERNAL\|INTERNAL` | Maps to `PublicationSeries.messagePublication`, or the internal-vs-external citation picker breaks silently. |
| **No implicit domain scoping** on the picker | The pickers never send `domain`. If the new endpoint defaults to the caller's domain, the four domain-free citation-only publications vanish from the picker. |
| `type=MESSAGE_REPORT` narrowing; `type` omitted reaching LINK rows | Keep the param and the four `PublicationType` values; serve the derived value (ruling X2). |
| `descs[].messagePublicationFormat` and `descs[].link` on the **light** payload | Both must be present for the picked issue, per language — `hasParams()` and `showLink()` read them. |
| `<span publication="…">` / `<a publication="…">` round-trip | No change, **provided the id space is stable**. The marks preserve whatever string is there; a changed id renders as a dead span with no error. |
| Print / Export carrying `publication=` inside the opaque filter string | Inherits §9.1 for free. |

### 9.4 What changes, and when

**Ship now — independent of the redesign, against the legacy backend:**

| Fix | Where |
|---|---|
| **`limit` → `maxSize`** | `publications-api.service.ts:51`. Every publication picker silently caps at 100 rows today, including the `limit: 200` browse call, which does nothing. Hard precondition for any browse affordance. |
| Treat a `400` from `/messages/search` as a **filter-level** error | messages facade / `PublicationFilterPanel`. Once §7.2(a) lands, a saved filter or shared link naming a deleted publication returns 400 on **every keystroke**. The panel must mark the offending chip and offer to drop it, not put the list into a permanent error state. No consumer expects a 4xx for a filter *value* today. |

**With C8 (the phase that mints the first new issue):** the re-point lands. **Nothing in the frontend changes** — `publication=` keeps working, print/export inherit.

**Before the first imported issue is published** — all four must land first, because that publish is what moves a series' public rows from the legacy half to the new one:

| Change | Where | New value |
|---|---|---|
| Filter status default | `publications-field.ts:26` | `['ACTIVE','RECORDING']` → `['PUBLISHED','OPEN']`. Without `OPEN`, an editor cannot filter by the issue they are working on. |
| Citation status default | `insert-publications-dialog.ts:126` | `['ACTIVE']` → `['PUBLISHED']` — legacy's citation picker offers only ACTIVE. |
| Picker endpoint | `publications-api.service.ts:65` | `/publications/search` → **I27** `/rest/publication-issues/picker`, for the per-row status badge `PublicationVo` cannot carry. I27 requires `Roles.USER`; all four consumers run inside the authenticated admin app, so nothing is lost, and the anonymous leak the legacy `/publications/search` had (`OQ-18`, closed 2026-09-25) is not carried forward. |
| Hydration endpoint | `:81` | `/publications/publication/{ids}` → **I28** `/rest/publication-issues/by-ids`, so restored chips carry status. The legacy path stays alive, unchanged, for external consumers. |
| `PublicationRef.status` | `messages-filter-params.model.ts:103` | Declared today but **never populated** — becomes real once the picker moves. |

**Status token acceptance during the whole transition.** The shipped frontend sends `status=ACTIVE&status=RECORDING` until it is re-pointed, so every new endpoint accepts **both vocabularies** and translates rather than 400-ing:

| Legacy token | New |
|---|---|
| `DRAFT`, `RECORDING` | `OPEN` |
| `ACTIVE` | `PUBLISHED` |
| `INACTIVE` | `RETIRED` |

The mapping is **accepting, not emitting** — `PublicationVo` carries no status field at all, so nothing in the public payload changes. An unrecognised token → `400`.

### 9.5 The message-HTML citation coupling — six invariants

The message↔publication relation exists **only** as `publication="<id>"` inside stored message HTML. `PublicationUtils` writes it and parses it back; **there is no join table, no `Message`↔`Publication` association, and no endpoint that removes a citation.** The archive is effectively immutable and every id-affecting operation must be designed on that assumption.

**P1 — `publicId` is immutable for life.** Assigned once at issue creation; never reassigned on amend, retire, reactivate, edition bump, interval edit, name change, series move or re-import. Legacy's `Publication.assignNewPublicationId()` (used by the copy flow at `PublicationRestService.java:299`) must have **no counterpart**.

**P2 — id-space continuity across the import.** Every imported legacy `publicationId` becomes the `publicId` of the one issue it maps to; `legacyPublicationId` records it and equals `publicId`. **Never mint a fresh UUID for an imported row** — a re-keyed import dangles every citation in the archive with no way to find them. Enforce with the unique index plus an import assertion that fails loudly. Never key an imported issue on title or tag name: three publications carry a literal `${year}` in title, fileName *and* tag name.

**P3 — `publicId` must remain a lowercase UUID string, and this is a hard technical constraint.** Verified in the source: the citation is found with

```java
String pubAttr = "[publication=" + publication.getPublicationId() + "]";
Element e = doc.select("a" + pubAttr + ",span" + pubAttr).first();
```

— an **unquoted** CSS selector built by string concatenation. Any id containing a CSS-special character (space, `.`, `#`, `:`, `]`, quote) makes the selector match nothing, and `updateMessagePublications` then takes its else-branch —

```java
} else {
    pubHtml += " " + updatedPubHtml;
}
```

— and **appends a duplicate citation** instead of replacing in place. Human-readable ids (`efs-uge-27-2026`) are therefore **forbidden** as `publicId`, however tempting for the URL. A slug may exist as a separate, non-citable admin field. Enforce `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`.

**P4 — the resolvability floor.** For every `publicId` that has ever appeared in stored message HTML, the by-ids lookup must keep returning at least `publicationId`, `type`, `category`, and per-language `link` + `messagePublicationFormat` — exactly what `PublicationUtils.extractMessagePublication` and `computeMessagePublication` read. Consequences: delete is forbidden for any issue that has ever published (§4.10); retire keeps the row resolvable on the by-ids lookup while removing it from the public listing; amend keeps `descs[].link` at the same URL.

**P5 — `messagePublication` is immutable once any issue of the series has published.** `extractMessagePublication` chooses `internalPublication` vs `publication` from `publication.getMessagePublication() == INTERNAL` (verified in the read above). Flip the channel after citations exist and **every existing citation becomes unfindable** — it lives in the other message field — and re-applying **appends a duplicate** rather than replacing. Enforce server-side (`400 MESSAGE_PUBLICATION_IMMUTABLE`). There is no rewrite path and none should be built.

**P6 — citation-time format resolution, written down.** Resolve in this order: the **issue** desc's `messageReferenceFormat`; else the **series** desc's pattern with this issue's tokens substituted; else **refuse**. Legacy writes the literal `" null"` into that language's field when no format exists — that must not be reproduced. The save-time validation (`REFERENCE_FORMAT_MISSING_LANGUAGE`, scoped to `messagePublication != NONE`) is what prevents it. A wrong-language or `null` citation is publicly visible and removable only by hand-editing rich text, message by message.

---

## 10. Deliberately not in this contract

| Not here | Where it lives | Why |
|---|---|---|
| Entity field tables, DDL, indexes, CHECK constraints | Data-model document §§3, 8, 9 | This contract *consumes* those names; duplicating them is how the two drift. |
| The criteria document's node vocabulary and its validation rules (V-C1…V-C10) | Criteria section §§5.4–5.5 | §3.7 states only the wire shape and the three rules a client can observe (canonicalisation, `minItems: 1`, `NOT_IN` rejected). |
| The resolver predicate and its fifteen invariants (RI-1…RI-15) | Data-model §8.4 | The contract states only what the *response* echoes. A client must never re-derive membership. **RI-15 is the member-set designation predicate** added by ruling R7 — `designatesMemberSet()` is "an id resolved to an issue the caller may see", independent of member count — and §9.1 depends on it directly, so the count is kept current here. |
| The importer's translation table and the five legal filter combinations | Data-model §5.5.6 | §3.13 states only the envelope and the fail-loudly rule. |
| Physical schema delivery | Data-model §9.3, open | No Flyway, no Liquibase, no `.sql` anywhere; `quarkus.hibernate-orm.database.generation` is `none` outside `%dev` and lives in the **niord-dk** overlay. **This blocks the first schema task regardless of what any document says.** |
| Keycloak realm changes | Deployment, owned by the project lead | OQ-1. |
| Frontend components, routes, i18n keys, Danish copy | The batch implementation plan | §9.3–9.4 state only the API-side obligations. |
| Anonymous enumeration of series or issues | — | Not added. Legacy `/publications/search` was anonymous with no status default and leaked DRAFT and unreleased rows; that is **not reproduced**, and since 2026-09-25 the legacy search itself serves an anonymous caller the public tier only (`OQ-18`). |
| A `/search-details` equivalent for editors | — | Not added. C9 objects to it by name: it carries `printSettings`, `repoPath` and the tag filter, which nobody in that tier needs. The editor tier is a different class, not a filtered one. The legacy `/publications/search-details` itself is ADMIN since 2026-09-25. |
| Bulk curation handoff from the messages list | Deferred by C1 at no schema cost | |
| A `publicationSnapshot=frozen\|live` request parameter | — | Deliberately not shipped (§6.5). Purely additive if ever needed. |
| Any new field on `PublicationVo` / `PublicationDescVo` | — | Published XSD with a fixed `propOrder`. Frozen. |
| A `new-edition` action for `IN_FORCE_AT_CUTOFF` series | OQ-10 | I20 covers it today; whether it deserves its own verb that also sets `supersedes` and caps the predecessor is undecided. |
| OpenAPI publication of the admin API | OQ-12 | The smallrye scan list lives in `niord-dk` and excludes `org.niord.web`; adding the package naively would publish the entire legacy admin API. |

---

## 11. Open questions

Ordered by what they block. **OQ-1 through OQ-4 must be answered before code is written**; the rest can be answered during implementation. **OQ-5 is closed** — the §0.4 rulings are all applied in both documents — and the number is kept rather than reused so no later reference re-binds to something else.

| # | Question | Blocks | Recommendation |
|---|---|---|---|
| **OQ-1** | **`PUBLICATION_CURATE` realm provisioning.** The role must be created on all six domain clients and added to each `admin` composite. Live realms are not bootstrapped from `niord-bootstrap-realm-dk.json`, so `@RolesAllowed(Permissions.PUBLICATION_CURATE)` **fails closed for every existing admin** until Keycloak is edited. Who does it, and is a documented transitional fallback (`isCallerInRole(PUBLICATION_CURATE) \|\| isCallerInRole(ADMIN)`, removed after one release) acceptable? | I12–I14 | Ship the transitional fallback, documented and dated, and remove it in the release after the realm change. |
| **OQ-2** | **Loud failure vs honest empty for no-membership publications** (§7.2a, row 3). C8's signed text groups the 27 tagless ACTIVE rows with the bogus id, which would make them `400`. This contract returns `200` + empty for a publication that legitimately declares no membership. The split changes live behaviour for bookmarked `?publication=<Dansk Fyrliste>` links (today: 236 messages). | §7.2 | `200` + empty. "No messages" is a true answer; "no such id" is not. |
| **OQ-3** | **Ratify the delete-guard wording.** `publicId` is minted at create, so C7's literal *"no publicId"* becomes `status = OPEN AND cutoffStampedAt IS NULL AND publishedAt IS NULL`. Same issues, different words — but it amends a signed decision. | I9 | Ratify as restated. |
| **OQ-4** | **Series audit shape.** Three columns (`statusChangedAt/By/Reason`), or generalise `IssueAuditEntry` with a nullable `series_id`? A reactivate overwrites the retire reason, so the columns alone do not satisfy C7's "audited". **If generalised, the three columns must be dropped BEFORE the schema is applied** — retrofitting is exactly the hand-rolled migration that has no tooling. | S10, and a `GET /series/{seriesId}/audit` endpoint that does not exist in §2.1 | Generalise. A lifecycle event that overwrites its own predecessor is not an audit trail. |
| **OQ-5** | ~~**The six data-model contradictions of §0.4.**~~ **CLOSED — nothing outstanding.** All six are settled and applied in both documents: **X1** (`public*` naming — the rename landed), **X2** (`legacyType` derived, no column), **X3** (`reasonCode` derived on the wire, no column; the duplicate `IssueMember` field table deleted), **X4** (`appliedAtPublish`, `staleAtFreeze` retired), **X6** (`messageReferenceEnabled` not on the wire), and **old X5 withdrawn** — `snapshotIntervalFrom` **is** a real column, written by §4.3 step 7 and echoed by §3.8, because retro-creation and I8 both move interval bounds in the neighbour chain. No ratification remains. | — | — |
| **OQ-6** | **How an aborted automatic release reaches a human.** Under `AUTO_RELEASE`, a BLOCK precondition aborts and writes an audit entry — but nothing surfaces it to the admin who expected an issue on Wednesday. Mailing list, dashboard warning, or "the gap row is the notification"? | §4.3 | The gap row plus a dashboard warning. A mailing list is a second delivery channel to maintain. |
| **OQ-7** | **Retire leaves a public hole.** Retiring the newest published issue does not re-open the predecessor's `publicTo`, so the series shows no current issue. Confirm this is intended and that the confirm dialog says so. | §4.5 copy | Confirm. Un-capping silently republishes something deliberately superseded. |
| **OQ-8** | **Preview URLs are not shareable** (one-time, 60-second tickets). The approved UX puts a copy-link icon on preview rows and users called copy-URL "a major win". Confirm that copying the *issue* deep link is acceptable, or fund a signed, expiring, admin-scoped preview URL. | §5.5 | Copy the issue deep link. |
| **OQ-9** | **`omissionLookbackDays` default** for the `IN_FORCE_AT_CUTOFF` omission panel when there is no previous issue to bound it. Product judgement, not a data fact. | §3.8 | 90 days, with the panel labelled "senest udeladt". |
| **OQ-10** ✅ **DECIDED 2026-08-22 — a dedicated `new-edition` action** (Rasmus). It sets `supersedes` and caps the predecessor atomically, because the multi-step version is where step three is forgotten and two current editions reach the public site. | **Retro-create vs new-edition for `IN_FORCE_AT_CUTOFF` series.** Gaps do not exist there, but the documented four-step mid-year re-issue does. Same `POST /issue/` with an explicit cut-off, or a dedicated action that also sets `supersedes` and caps the predecessor? | I20 | A dedicated action. The three-step version is exactly where an admin forgets step three. |
| **OQ-11** | **Picker domain scoping.** Pickers are cross-domain today while the admin list always sends `domain`. Confirm the asymmetry, since whatever is chosen must keep the 17 domain-free citation publications reachable. | I27/I28, S1 | Confirm as specified. |
| **OQ-12** | **OpenAPI.** The smallrye scan list lives in `niord-dk` and excludes `org.niord.web`; `/q/openapi` 404s today and adding the package naively would publish the entire legacy admin API. Add a scan entry for `org.niord.web.publication` only, or drop the "OpenAPI documented" acceptance criterion? | acceptance | Scoped scan entry. |
| **OQ-13** | **Gap rows inside a paged list** mean a page can render more rows than `maxSize` and `total` counts real issues only. Accept, or move gaps to a separate `/gaps` call the UI merges client-side? | §6.4 | Accept, documented. A second call doubles the round-trips on the busiest screen. |
| **OQ-14** | **The `ExceptionMapper` is new house style** — none exists anywhere in `niord-web` today. Confirm scoping one to the new package rather than changing global error behaviour. | §7.1 | Confirm, scoped. |
| **OQ-15** | **`MEMBER_LIMIT_EXCEEDED` threshold — to be re-decided.** The old framing ("hard-fail at the report pipeline's 1000, or touch `MessageReportRestService`") no longer applies: the render is in-process (§4.3 step 10a) and `report.pdf`'s `maxSize(1000)` is off the publication path, so **the number is now a free product choice** rather than an inherited cap. Largest live set is 228. | §7.3 | Keep 1000 as the loud stop unless a real set approaches it — but choose it as a product limit, not by citing a pipeline that is no longer in the path. |
| **OQ-16** | **`publicFrom` precision.** Emit the exact stamped cut-off (`10:30:18Z`) or round to the series' nominal cut-off hour (`10:00Z`) as legacy does? The exact stamp is more correct but changes the dates any external consumer *renders*. Related: does the download site render these as visible text? If so this needs the site owner's sign-off before the go-live window. | §8.2 | Emit the exact stamp; verify the download site first. |
| **OQ-17** | **The deterministic public sort tiebreak** (`publicId`; `series.sortOrder` is stored, not read) changes the emitted order of the EfS/P&T twin pair. It is a deliberate design choice rather than a parity claim; the publications domain rules are in [`domain-rules.md`](domain-rules.md). | §8.4 | Settled as a design choice; nothing outstanding. |
| **OQ-18** | ~~**Legacy anonymous leaks.**~~ **CLOSED 2026-09-25 (owner ruling: anonymous callers get the public tier everywhere).** `/rest/publications/search` and `/all` serve a caller who is not a signed-in user the public tier only — ACTIVE, main type PUBLICATION, publishing category, PUBLISHED issues — whatever the query asks; the signed-in pickers keep the unfiltered list. `/publications/publication/{ids}` keeps resolving at `INTERNAL` (X-4), and withholds the link and file name of an OPEN issue or a DRAFT/RECORDING legacy row from an anonymous caller. | §9.2 | — |
| **OQ-19** | **Is `import-legacy` (S18/S19) permanent or one-time?** The DK deployment seed imports `publications.json` in the legacy shape. Permanent converter, or a migration tool that is removed after go-live? | S17–S19 packaging | One-time, unless the deployment seed keeps the legacy shape — which is a `niord-dk` decision. |
| **OQ-20** | **Export scope.** Legacy export is whole-database and unbounded, and is the only backup of a publication's filters and print settings. Keep whole-estate as the default with optional per-series scoping (as specified), or make per-series the default now that one series holds 1,077 issues? | S16 | Keep whole-estate default; it is a backup, and a partial backup is worse than a slow one. |
| **OQ-21** | ~~**Editor tier and domain scope.**~~ **Answered: yes, and the refusal is the client's.** An editor in `niord-nm` may read the member list of a `niord-fa` issue. Reads are unscoped on the wire — a citation that exists must always resolve, and a deep link has to open from whichever desk the reader is sitting at — so the narrowing that matters is the **visible-from** predicate on the pickers and the search (§6.3), not a per-request domain gate on the read. What refuses a foreign owner is the **admin detail screens on the client**: they will not open a publication another desk owns, and every write behind them answers `403 NOT_IN_DOMAIN` anyway. | I1, I3, I10, I6 | Yes for lists and membership (matching the cross-domain pickers); the detail screens refuse a foreign owner client-side. |


---

## Amendments — 2026-08-27 (Stage A of the work queue)

*Recorded against the shipped code after the reconciliation. Where a row below disagrees with the sections above, this section wins; the sections above are not rewritten so the history stays readable.*

| Surface | Change |
|---|---|
| `SystemPublicationSeriesVo` | **`cutoffDefault`** (`RELEASE_MOMENT` \| `PERIOD_START` \| `PERIOD_END`, ruling D20): where the cut-off falls by default at publish. Omitted on write = unchanged. S-21: only a YEARLY series may be cut off at a period boundary. |
| `SystemPublicationIssueVo` | `publishedAt` / `publishedBy` are the **publication moment** — when the release action ran — and are distinct from `cutoffStampedAt`, the end of the content period. For an imported issue `publishedAt` is the legacy row's stamp where that is credible as the release, and the issue's own `publicFrom` where nothing witnessed one; only an OPEN imported issue carries none. `cutoffSource` gains the values **`NOMINAL_CLOSE`** (the nominal close stood in for an incredible stamp) and **`PUBLIC_WINDOW`** (a yearly issue's boundary read off its window). |
| **I16** `PUT /issue/{publicId}/publish` | Body `{ cutoff?: epoch ms, acknowledgedWarnings: string[], regenerate: boolean }`. `cutoff` is the chosen end of the content period and may be in the **past only** — `400 CUTOFF_IN_FUTURE` otherwise (ruling D7); absent = now. The caller is recorded as `publishedBy`. An unacknowledged resolution warning is refused **before anything is frozen** with `409 WARNING_NOT_ACKNOWLEDGED` and the body carries `unacknowledgedWarnings: string[]`. Under `AUTO_RELEASE` the warnings are recorded in the audit instead (the abort path is post go-live, D17). Publish **renders the document** in-process from the frozen, ordered member list; `regenerate=false` promotes the newest preview; a generated series that ends without a file is refused with `500 RENDER_FAILED`. |
| **Preview** (replaces I20–I23) | `POST /issue/{publicId}/preview` (admin) renders the OPEN issue's live member list into the preview store and returns `[{ lang, renderedAt, href }]`; `GET /issue/{publicId}/preview/{lang}` (admin) serves the newest preview as `application/pdf`. `404 NO_PREVIEW`, `409 REPORT_NOT_CONFIGURED`, `409 ISSUE_NOT_OPEN`. The checklist's `PREVIEW_FRESH` reads the preview's age against the issue's own `updated` stamp, which every edit and curation moves. No tickets. | **[`PREVIEW_FRESH` removed 2026-09-08 — amendment row 16; the rail has fourteen rows.]**
| I18 / I19 retire / reactivate | The caller is recorded as the actor (was null). |
| Gap rows (I5) | A `MISSING` pseudo-row exists only where **no issue's interval covers the period** (coverage-based). A double-week issue chained from the previous close produces none. While the newest issue is OPEN no pseudo-rows follow it: it is late, not missing, and is its own row. |
| Audit | New action **`VISIBILITY_CAPPED`** (written on the issue whose public window was closed by a neighbour's publish, detail `{publicTo, byIssueId, byPublicId}`); the importer writes one **`IMPORTED`** entry per issue (actorKind `IMPORT`). |
| Import (S18/S19) | The plan checks `ALREADY_IMPORTED` with two set lookups instead of one query per row (a 75-second phase became sub-second) and logs its phase timings. |

## Amendments — 2026-08-28 (Stages B and C of the work queue)

| Surface | Change |
|---|---|
| **I17** `PUT /publication-issues/issue/{publicId}/amend` (admin) | Body `{ reason (required, 3–512), regenerate (default true), acknowledgedWarnings }`. Re-runs the resolution against the issue's OWN cut-off and replaces the document in place; `publicId`, `filePath`, `link`, `publicFrom`, `publicTo`, `cutoffStampedAt` and `publishedAt` are all unchanged. Returns `{publicId, stampedAt, memberCount, unacknowledgedWarnings, archivePaths}`. Refusals: `409 ISSUE_NOT_PUBLISHED`, `409 ISSUE_IMPORTED` (an imported issue is a historical record; its members and document are never re-decided), `400 REASON_REQUIRED` (3–512, trimmed), `409 WARNING_NOT_ACKNOWLEDGED`, `409 ISSUE_BUSY` (a release or an amend is already running on this issue — see the 2026-09-14 amendment), `500 ARCHIVE_FAILED`. |
| **New edition** `POST /publication-issues/issue/{publicId}/new-edition` (admin) | Body `{ intervalFrom? }`. Sets `supersedes` and audits `CREATED_NEW_EDITION` + `SUPERSEDED_BY`; the predecessor's window is capped by the successor's publish, not here (ruling B-i). |
| **I14** `DELETE /publication-issues/issue/{publicId}/overrides/{messageUid}?reason=` (curate/admin) | Withdraws a curation decision; the reason is kept on the `OVERRIDE_REMOVED` audit entry. `404 OVERRIDE_NOT_FOUND`, `409 ISSUE_NOT_OPEN`, `400 OVERRIDE_REASON_REQUIRED`. |
| **I12 / I13** override include / exclude | Accept `{messageUid, reason}` **or** `{messageUids: [...], reason}` (≤ 500), all-or-nothing; `409 OVERRIDE_MESSAGE_NOT_FOUND` names every unresolvable uid; reason 3–512 required. *Amended 2026-09-25:* an **include** (I12) names only messages in a public status (RI-1: `PUBLISHED`, `EXPIRED`, `CANCELLED`); `409 OVERRIDE_MESSAGE_NOT_PUBLIC` names every offender with its status and nothing is written. An exclude (I13) is not checked. |
| **S10** `PUT /publication-series/series/{seriesId}/status?reason=` | Refuses a return to DRAFT with `400 INVALID_STATUS_TRANSITION`; requires a reason (3–512, trimmed) when **leaving ACTIVE** (`400 REASON_REQUIRED`) — the first activation and a reinstatement are a confirm, with no reason (D9); audits `SERIES_ACTIVATED` / `SERIES_RETIRED` (reason kept where one was given). The same status twice is a no-op that writes nothing. Accepts both status vocabularies; an unknown token is `400 INVALID_STATUS`, never a 500. |
| **C2 / C7** categories | One resource (`org.niord.web.publication`). `POST /publication-categories/publication-category/` creates (priority defaults to 100); `POST /publication-categories/upload-publication-categories` keeps the JSON bootstrap. Delete counts series **and** legacy publications before refusing with `409 CATEGORY_IN_USE`. |
| Error envelope | A validation refusal now carries `fieldErrors: [{rule, field, message}]` beside `code` and `message`. |
| Series validation | **S-22** refuses `releaseMode = AUTO_RELEASE`; **S-23** refuses `week`, `weekTo`, `weeks`, `year`, `edition`, `yearNumber` and `weekNumber` as report-parameter keys (case- and whitespace-insensitive) — they are injected from the issue: the four strings as the issue prints them, typed labels and all, the two numbers as the numbering derived them (`Integer`, absent where the issue has none), so a template computing a volume line off a year reads a number and not a label, and `weeks` as the series' name pattern writes the issue's weeks. Both are **hard rules**: refused on **every** S8/S9 save whatever the status, as `400 SERIES_INVALID` with `fieldErrors[{rule: "S-22" \| "S-23", field, message}]` (`field` = `releaseMode` / `reportParams.<key>`). The `/validate` pre-flight lists them the same way. (`NOT_YET_SUPPORTED` and `PRINT_SETTING_NOT_ALLOWED` are in the catalogue but no endpoint raises them.) *Amended 2026-08-29 (R-xxvi): the hard-rule set is now **`S-20a`, `S-20b`, `S-22`, `S-23`**, and the one-off writes run the same gate — see the R-xxvi amendment.* |

## Amendments — 2026-08-28 (verification pass over Stages A–D)

| Surface | Change |
|---|---|
| **I16** publish | `publishedAt` is **always the instant the release action ran**, whatever `cutoff` was chosen; `cutoffStampedAt` is the chosen cut-off and `publicFrom` opens there. The header's *Skæring* / *Publiceret* pair therefore differs for every late or back-dated release. |
| **I18** retire | `reason` is required at 3–512 (trimmed), `400 REASON_REQUIRED`; reactivate takes none. |
| Preview file names | Named from the cut-off the publish would use — `cutoffDefault` applied to the issue's period — not from the clock, so a January preview of last year's accumulated list carries last year's tokens. |
| Weekly naming | A window of fewer than 1¾ periods (13 whole days) is one week (a week released up to five days late is not a double week); `weekTo` appears from 1¾ periods up. *Amended 2026-11-19 (NAMING-1):* from 1¾ periods up, the issue is named for every ISO week after the one the previous cut-off fell in, up to the cut-off week, and `weekTo` appears only when that is two weeks or more; the weeks are counted in ISO weeks, not in days. *Amended 2026-11-20:* two weeks print as a pair (`36+37`), three or more as a range (`36-38`; `52-2` across a year's end, beside the later year): the `+` a pattern writes between `${week}` and `${weekTo}` prints as a hyphen then. The wire is unchanged. |
| Import | A yearly issue whose archive row had no end date gets one at the end of its year with `intervalToSource = RECOVERED`; an archive-given end stays `NOMINAL`. |
| **S8/S9** | See the S-22/S-23 row above: the hard rules are refused on every save, in any status. |
| **S10** | Lives in `PublicationSeriesService.transition` (core), so the rules are tested without the web layer; the resource delegates. |
| Admin-only tooling, previously undocumented | `GET /publication-series/import-check` (admin) — the table the runbook reads. `POST /publication-series/upload-series` (sysadmin, multipart) — the series import. `/one-off-publications` (admin): `GET /`, `POST /`, `PUT /{seriesId}` — the one-off editor's own resource. |
| Catalogue | `ISSUE_IMPORTED` 409 added. |

## Amendments — 2026-08-28 (B-5, B-11, B-12, C-5, C-8 as shipped)

| Surface | Change |
|---|---|
| **I27** `GET /publication-issues/picker` (`Roles.USER`) | Params `lang, title, publicationSeriesId, status` (repeatable or comma-joined; default `PUBLISHED,OPEN`; the legacy tokens `ACTIVE/RECORDING/INACTIVE` are accepted; an unknown token is `400 INVALID_STATUS`), `messagePublication, type, domain` (optional and explicit — never inherited from the header; a null-domain series matches every domain), `page` (0), `maxSize` (100). Returns the `{data, total, size, description}` envelope of `PublicationIssuePickerVo` rows ordered newest-first with a total order (`COALESCE(cutoffStampedAt, intervalTo) DESC, publicId DESC`). The row carries `publicId, publicationSeriesId, type` (from `series.contentMode`, ruling X2), `status, messagePublication, publicFrom, languageSpecific, descs[{lang, title, link, messagePublicationFormat}]` — nothing else. Legacy publications are not in it; consumers union it with `/publications/search` for as long as legacy rows are still being served. |
| **I28** `GET /publication-issues/by-ids?ids=a,b,c&lang=` (anonymous) | Ids only, at most 200 (`400 TOO_MANY_IDS`); no status narrowing; unknown ids omitted; `200 []`. Same row shape; without the `user` role, `link` is null on the descs of an unreleased (`OPEN`) issue (§2.2 I28). |
| **I29** `GET /publication-issues/recent?publicationSeriesId=a,b,c&periods=N&lang=` (`Roles.USER`) | 1..50 ids required (`400 NO_SERIES_IDS` / `TOO_MANY_SERIES_IDS`), `periods` clamped to 1..52 (default 8). One group per requested series `{publicationSeriesId, gapDetection, rows[]}`; an unknown id yields an empty group. Rows `{label, week, year, computedStatus, memberCount, publicId (null on a synthesized cell), intervalFrom, intervalTo}` come from the SAME synthesizer as the issue list (`IssueListService`), so the list's gap row and the strip's missing cell are one fact. A real cell's label is the issue's own name; a synthesized one is the name-suggestion pattern expanded from the period's close. |
| **I10** members | On PUBLISHED/RETIRED issues each row carries `current {exists, publiclyVisible, type, status}` — on **every** row since 2026-09-11 — and, where something moved since the release, `drift[]` (frozen ≠ live: `status`, `type`, `exists`; `publishDateTo` is not compared); a deleted message drifts as `["exists"]`. Override rows carry `curation {kind, author, at, reason}`. Both absent on an OPEN issue. |
| **new** `GET /publication-issues/issue/{publicId}/overrides` (`{publication-curate, admin}`) | The standing curation decisions: `[{messageUid, kind, author, at, reason}]`. Needed because an EXCLUDE never appears in the member list. |
| **I1/I5** `GET /publication-issues/series/{seriesId}` | Gains `page` + `maxSize`, opt-in: absent = the whole archive (the admin screen sorts and pages client-side and the gap envelope is computed over the whole set). The envelope gains `size` and `description`. `limit` is accepted nowhere. |
| **S12** `GET /publication-series/series/{seriesId}/issue-draft` (admin) | Params `afterPublicId` \| `intervalFrom`+`intervalTo` (epoch ms) \| none. Returns `IssueDraftVo` = an unsaved `SystemPublicationIssueVo` (interval bounds + sources, week/weekTo/year, per-language name in `descs`) + `wouldMatchCount` (null when the criteria do not resolve), `chainedFromPublicId`, `warnings[{code, message}]` (`INTERVAL_NOT_CHAINED`, `IN_FORCE_HAS_NO_LOWER_BOUND`, …). Nominal end = `intervalFrom + period(cadence)`; an explicit bound that lands on an issue's close inherits that issue's source, one equal to `firstIssueStartsAt` is `NOMINAL`, any other is `MANUAL`. An in-force draft emits `intervalFrom = null`. Persists nothing. Refusals: `INTERVAL_INVERTED`, `ISSUE_INTERVAL_OVERLAP`, `ISSUE_NOT_FOUND`, `SERIES_NOT_FOUND`. |
| **S7** `GET /publication-series/copy-series-template/{seriesId}` (admin) | Unsaved copy: blank `seriesId`, `status = DRAFT`, `legacyTemplateId`/`importSource`/`firstIssueStartsAt`/`created`/`updated` cleared, `publishedIssueCount = 0`; patterns, criteria, report config, languages and descs carried. |
| **S2/S4/S16** | `SystemPublicationSeriesVo.publishedIssueCount` (read-only, derived: issues with `status <> OPEN`; one grouped query for a list). |
| Import check | `GET /publication-series/import-check` gains `series: {seriesId: {seriesId, status, kind, gapCount, gapDetectionEnabled, gapReasonCode}}` — the row repeats its own map key — and `counts` gains `series`, `uncoveredPeriods`, `seriesOfKindSCHEDULED|UNSCHEDULED|ONE_OFF`. `clear` is unchanged. **None of the series rows is a violation** — a gap is a fact about an archive that predates this system, and folding one into `clear` would stop the check passing on an estate in exactly the state everybody expects. <br>**Amended 2026-08-29 (R-xxvi):** `violations[]` gains **`SERIES_WITHOUT_OWNER`** — one entry per series with no `domain`, naming the `seriesId` — and `counts` gains **`seriesWithoutOwner`**, which is **written even when it is zero** (an absent number and a zero read alike on a checklist, and this is a line somebody ticks). It is a violation, not a note: the count must read `0` for the go-live window to proceed. <br>**Amended 2026-09-13:** `violations[]` gains **`PUBLISHED_ISSUE_WITHOUT_STAMP`** — one entry per imported issue whose `status` is `PUBLISHED` or `RETIRED` while `publishedAt` is null, naming the `publicId` — and `counts` gains **`publishedIssuesWithoutStamp`**, **written even when it is zero**. What it protects is the public union (§8.8): the legacy half is excluded on `publishedAt IS NOT NULL`, so an unstamped released issue leaves its legacy row in the list and the same document is served twice, once from each half, under one `publicationId`. An OPEN imported issue is **not** a violation — it has taken over nothing, and its legacy row is correctly still the one being served. A violation, not a note: the count must read `0` for the window to proceed. <br>**Amended 2026-09-14:** `violations[]` gains **`MEMBER_PUBLISHED_AFTER_ISSUE`** — one entry per IMPORTED member row whose `frozenPublishDateFrom` falls after the end of the day (in the series' cut-off zone) that lies **fourteen days** past the issue's `cutoffStampedAt`, or past its `publishedAt` where no cut-off is stamped — the same grace the importer applies (S18/S19 below), so a freshly imported estate reads zero and the check only ever catches what got through — naming the issue's `publicId` as subject and the member's frozen short id in the detail — and `counts` gains **`membersPublishedAfterIssue`**, **written even when it is zero**. The import copies a legacy publication's message tag verbatim, and a tag can hold a notice published long after the publication went out: such a row cannot have been in the printed document, and a compilation would attribute the message to the wrong week. The importer leaves such a row out and reports it (S18/S19); the check names anything that nevertheless got through. Natively resolved rows (CRITERIA, COMPILED, curated) are never reported. <br>**Amended 2026-09-14 (later):** `counts` gains **`compilationIssuesCreated`** — the annuals the import opened for the compilation series (issues with no legacy id in an imported `COMPILED_FROM_SOURCE` series), a count and never a violation — and **`PUBLISHED_ISSUE_WITHOUT_STAMP`** exempts a RETIRED issue whose legacy row is not ACTIVE: neither half of the public union serves such a document (a legacy row only while ACTIVE, an issue only while PUBLISHED), so it cannot be served twice; a PUBLISHED issue without a stamp, and a RETIRED one whose legacy row is still ACTIVE, remain violations. <br>**Amended 2026-11-19:** `counts` gains **`importedLinksNamingAnotherFile`** — imported descs whose `link` is a repository link (`/rest/repo/file/…`) of which no spelling, as written or decoded, is the desc's `filePath` — a count and never a violation, **written even when it is zero**. The import takes `filePath` from the link wherever the link names a file under the issue's `repoPath` (the old product raised a publication's revision each time it was opened for editing, so a released file can sit in an older revision folder than the current one); what the count still finds is a link elsewhere, which the public site serves while the repository import's check, the archive before a replacement and a corrected upload read `filePath`. |
| Catalogue | `TOO_MANY_IDS`, `NO_SERIES_IDS`, `TOO_MANY_SERIES_IDS`, `INVALID_FILTER_VALUE` (400). |

## Amendments — 2026-08-28 (external review 2, backend batch 2: tiers, the mapper, the public surface)

| Surface | Change |
|---|---|
| **Tier matrix** (asserted by `PublicationTierMatrixTest`) | **Editor** (`Roles.USER`): S1 `/publication-series/search` (ACTIVE only, honours `lang`, lean VO — no patterns), S3, S5, S14 `/name-tokens`, I3 `/issue/{publicId}`, I6 `/by-message`, I27 `/picker`, I29 `/recent`. **Curator** (`{publication-curate, admin}`): I4 `/editable-issue`, I10 `/members`, I11 `/audit`, `GET /overrides`, I12/I13/I14. **Anonymous**, exactly three on the new resources: `/publication-categories/all`, `/publication-categories/publication-category/{id}`, I28 `/by-ids`. **Admin**: everything else, including `/upload-series` (was sysadmin — R-xiv). **Sysadmin** *(2026-09-25)*: S19 `POST /import-legacy` and S20 `DELETE /import-legacy`, which reach every domain at once; the dry run S18 and `/import-check` stay admin. **X-4**: `GET /publications/publication/{ids}` stays anonymous and resolves at `Audience.INTERNAL`, unchanged and recorded; *since 2026-09-25* an anonymous caller is not handed the link or file name of an OPEN issue or a DRAFT/RECORDING legacy row there. The legacy `/publications/search-details` is admin *(2026-09-25)*. The editor tier cannot reach override author/reason, criteria, repo/file/archive paths, the report/print or automation columns (asserted on a real conversion). |
| Error mapping | One base `PublicationException` (code + fieldErrors) that every mapped refusal extends; the mapper is `ExceptionMapper<PublicationException>` — a legacy bare `WebApplicationException(4xx)` and an unmatched route keep their status (they were becoming 500s). A new refusal type must extend the base or it is not mapped. |
| `?publication=` resolution (§9.1) | A RETIRED issue resolves to its frozen members for the PUBLIC audience too (only OPEN is refused); the single public read stays PUBLISHED-only and applies the `category.publish` gate on the issue branch exactly as on the legacy branch (a PUBLISHED issue in a non-publishing category is the indistinguishable 404). |
| `/publications/search`, `/search-details` (§8.8) | The union is ordered once and then paged: `page`/`maxSize` advance the result and `total` is the union count (issues were prepended to every page and `total` was issues + one legacy page). |
| **I24** upload | The distinct-name guard runs BEFORE any byte is written; on a PUBLISHED issue the replacement is written IN PLACE at the desc's existing file name (a different name is `400 FILE_NAME_IMMUTABLE`), archived first, with `replacedBy/replacedAt` and an audit action **`FILE_REPLACED_MANUALLY`** carrying the archive path. |
| **S13** `/resolve-preview` | Response gains `missCount`; `misses` is capped at 50 rows (R-xi). |
| **S18/S19** import | Undo deletes overrides and series languages; a half-dated legacy row gets cut-off `MANUAL` and a line in the report's new `notes[]` (same shape as `problems[]`), never a fabricated stamp (R-viii); ruling ids no longer baked into archive rows; one `seriesId` slug; `year` in the series' zone. The batch series import validates like S8/S9 and never nulls the category. |
| Categories | C1 `/all`: `limit` absent or 0 = the default bound (was unbounded); the desc-language fallback is back; C4 PUT guards the path/body id (`400 CATEGORY_ID_IMMUTABLE`) and a null body. |
| Inputs | The hand-parsed query/body inputs on the series and category resources answer a catalogued 400, never a 500. |

## Amendments — 2026-08-28 (external review 2, backend batch 1: the native issue shape and the publish gate)

| Surface | Change |
|---|---|
| Create (I7), retro-create, new edition, the auto-successor | ONE helper (`IssueShape`) derives and stores the nominal `intervalTo` + `intervalToSource = NOMINAL` (an in-force series gets the boundary as its single bound; cadence NONE none), `week`/`weekTo`/`year` and the per-language name from the CUT-OFF (interval end) — the same arithmetic the draft (S12) uses. Publish step 2 re-derives the numbers and the name from the stamped cut-off unless an admin renamed the issue (`NAME_CHANGED`). `${year}` follows the numbering scheme: ISO week-year for `ISO_WEEK_YEAR`, the period's calendar year for `YEAR_EDITION` and the period defaults. **I7 accepts `intervalTo`** (validated: `INTERVAL_INVERTED`, overlap). |
| **I16** publish | Refuses every failing BLOCK row of the rail before stamping: `SERIES_NOT_ACTIVE`, `INTERVAL_INVALID`, `MISSING_FILE_FOR_LANGUAGE` (uploaded/link series), `REFERENCE_FORMAT_MISSING_LANGUAGE` (when `messagePublication ≠ NONE`), `REPORT_NOT_CONFIGURED` (query-backed), `CUTOFF_BEFORE_PREVIOUS` / `CUTOFF_AFTER_SUCCESSOR` (400), `MEMBER_LIMIT_EXCEEDED` (409). Only acknowledgeable warnings can refuse (`WARNING_NOT_ACKNOWLEDGED`); a non-acknowledgeable one never blocks. A release or an amend already running on the issue refuses this one with 409 `ISSUE_BUSY` (see the 2026-09-14 amendment). `cutoffSource` = `STAMPED_AT_PUBLISH` or `STAMPED_MANUAL_TIME`; `cutoffReconstructed` is cleared. Publish locks the SERIES row before the issue row; the auto-successor is not created when a later or OPEN issue exists. |
| **I15** `/publish-checklist` | Takes optional `cutoff` (epoch ms) and computes for that instant; resolves WITH the issue's curation (one resolve, shared with publish); `OVERLAPPING_ISSUE` can fail; `TYPE_DRIFT` is gone from the vocabulary (drift is the member marker); each acknowledgeable row carries **`acknowledgeCode`** — the resolution-warning code the gate compares. The neighbour pivots on `intervalFrom` inclusively. |
| **I10** members | LIVE for an OPEN issue: the criteria + overrides resolved on read (PROBE, nothing persisted), mapped to the same rows; frozen rows once released. The include guard (O-4) asks the live resolution. |
| **I8** edit | An interval edit runs the overlap rule; `intervalFromSource = MANUAL` only when that bound changed; `intervalToSource` written likewise. |
| Series validation | **S-1** requires `reportId` for `GENERATED_FROM_QUERY` (R-xvi); **S-4** does not apply to `kind = ONE_OFF` (R-i); **C-6** refuses a criteria document with zero nodes at save and at resolve; the `domain` kind resolves through `DomainSeriesExpander` at every resolve site incl. S13 and the replay. `updateFromVo`: `releaseMode`, `nextIssueCreation`, `messagePublication` absent = unchanged. |
| One-off (`/one-off-publications`) | `active: false` + `reason` (3–512) retires the issue; `active: true` on a retired one reactivates; `links` arrive one row per declared language with a nullable `value` — null/blank clears. <br>**Amended 2026-08-30.** On the way **out** — `GET /`, and the bodies `POST /` and `PUT /{seriesId}` return — `links[].value` is now the **fetchable address** of the document rather than the desc's `link` column: an explicit link wins, otherwise the repository URL `/rest/repo/file/<filePath>` (`IssuePublicationMapping.linkOf`, the same rule the public list and the citation picker answer with). A one-off whose file was uploaded natively has a `filePath` and no `link`, so it used to list no address at all and the admin list had nothing to point at, while the imported rows beside it linked fine because those carry their address verbatim in the column. **The row shape is unchanged** — still one row per declared language, `value` nullable, and a reference-only publication (no file, no link) still lists a row with `value: null`. On the way **in**, a `value` equal to the desc's own derived repository address is **ignored** rather than stored as an explicit link: the detail page sends the body back unchanged when it activates or deactivates, and storing the derived address would freeze it against a file that can be replaced. Everything else about a link write is as before — an absolute link is stored, null/blank clears. <br>**Amended 2026-09-10.** `active` is deprecated on the way out: a one-off is described by its status, its public period and its category — see "Amendments — 2026-09-10 (one-off visibility)". The `active` **transition** on the way in still behaves as described above, but **no screen uses it for a status change** any more: it is computed from `isActive`, which folds the public window in, so it silently does nothing for a scheduled or a historic publication. Withdraw, publish again and release go to the issue's own `retire` / `reactivate` / `publish` endpoints; the save carries whatever value changes nothing. |
| Preview | The POST no longer returns `href`; the client composes the URL and fetches with its bearer. |
| **I27/I28** rows | Gain `seriesNames {lang: name}`. |
| `SystemPublicationIssueVo` | Gains `cutoffSource` (`STAMPED_AT_PUBLISH` \| `STAMPED_MANUAL_TIME` \| the import provenances), so the bound marker can say whether a chosen instant or the release moment stamped the issue. |

## Amendments — 2026-08-28 (R-xxi: domain-scoped admin writes)

| Surface | Change |
|---|---|
| Every admin write naming a series or an issue (S8–S11, one-off create/update, I7–I9, I12–I14, I16–I19, new edition, upload/clear/link, preview generation) | Scoped to the caller’s current domain (the `NiordDomain` header): a series with a domain is writable only from that domain; a series with **no domain is writable by any admin**; an issue inherits its series’ domain. Refusal `403 NOT_IN_DOMAIN` — the message names the domain to switch to; sysadmin is not exempt by role (the header decides, as everywhere in the admin area). S9 and the one-off update check **both ends** (the stored series and the body’s domain), so a series cannot be handed out of the caller’s domain. Reads, pickers and citation surfaces are unchanged. <br>**Amended 2026-08-29 (R-xxvi).** Every series now has an owner, so "no domain" is a **safety valve on a database whose backfill could not run**, not a state the model permits — and being allowed to write such a row is *not* being allowed to adopt it: the save still fails `S-20a`, and assigning an owner is S21's job. **Sharing never grants a write** — the guard compares the owner and nothing else, so a desk that may cite a series still cannot retire, rename or unpublish it. The pickers and citation surfaces are now narrowed by the **visible-from** predicate (§6.3), which is a read rule, not a write one. |
| Unscoped, deliberately | `/resolve-preview`, `/validate`, `/import-legacy/validate` (persist nothing); `/import-legacy` (+ undo), `/upload-series` (estate operations); the category writes (no domain column). *Amended 2026-09-25:* `/upload-series` is scoped **per row** instead — the batch processor skips and logs every row that would create or update a series outside the domain the upload was made from, by the rule of `assertWritable` and `assertMayAssign` — and the `/import-legacy` run and undo are sysadmin. The web tier-matrix test asserts every non-GET method is either `@DomainScoped` and asks the guard, or is on the declared exemption list. |
| Catalogue | `NOT_IN_DOMAIN` 403 (the catalogue’s only 403). |

## Amendments — 2026-08-28 (§7.3 catalogue reconciled against `PublicationErrorCatalogue.java`)

*Queue row B-9 left this "pending". Read against the backend as it stood that day. The shipped catalogue is a single
`LinkedHashMap` that refuses a duplicate code at class-init, so there is exactly one status per code; a code
it does not know is a 500, which is the failure the catalogue exists to prevent. **Where §7.3 and this section
disagree, this section is what the server sends.***

**Sixteen §7.3 codes are not in the shipped catalogue.** Nine were renamed, three restatused with a new name,
four were never built.

| §7.3 code | Shipped as | Why |
|---|---|---|
| `SERIES_NOT_EMPTY` | **`SERIES_HAS_ISSUES`** 409 | Same refusal, plainer name. |
| `SERIES_INACTIVE` | **`SERIES_NOT_ACTIVE`** 409 | One name for the "series is not ACTIVE" refusal across create and publish. |
| `SERIES_NOT_GENERATED` | **`REPORT_NOT_CONFIGURED`** 409 | The condition is the missing report, not the content mode — S-1 now refuses such a series at save (`R-xvi`). |
| `SERIES_NOT_QUERY_BACKED` | **`CRITERIA_NOT_APPLICABLE`** 400 | Raised where a criteria override is written to a series with no membership semantics. 400, not 409: no change of state makes that request right. |
| `UNKNOWN_MESSAGE_UID` | **`OVERRIDE_MESSAGE_NOT_FOUND`** 409 | Names every unresolvable uid (`B-iii`); 409 because it conflicts with what exists, not with the request's shape. |
| `OVERRIDE_REDUNDANT` | **`OVERRIDE_ALREADY_A_MEMBER`** 409 | Same condition, named for what it collides with. |
| `REPORT_GENERATION_FAILED` | **`RENDER_FAILED`** 500 | The render is in-process now (R1); `FILE_WRITE_FAILED` 500 splits off the case where the bytes exist and the write failed. |
| `UNSUPPORTED_FILE_TYPE` 415 | **`NO_FILE`**, **`TOO_MANY_FILES`**, **`NO_FILE_NAME`**, **`BAD_FILE_NAME`** — all 400 | The multipart body is refused for what is actually wrong with it. Nothing rejects a *type*: the series decides what it renders, and an uploaded replacement must match the existing name (`FILE_NAME_IMMUTABLE` 400). Since 2026-09-25 the backend's multipart reader reduces every part's file name to a bare name and itself refuses a part whose name is blank or only a path, with a plain 400 that carries no code, so over HTTP `NO_FILE_NAME` and `BAD_FILE_NAME` are the endpoint's second line and no longer reached. |
| `FILE_NAME_NOT_DISTINCT` 400 | same name, **409** | It conflicts with the name another language already holds; changing that language makes the same request succeed. |
| `NOT_CURATOR` 403 | *(none)* | `@RolesAllowed(publication-curate, admin)` — the container answers before any of this runs, and a role the caller does not hold is not fixable by the caller. `NOT_IN_DOMAIN` is the catalogue's only 403 and is a different refusal (`R-xxi`). |
| `PREVIEW_STALE` 409 | *(none)* | `regenerate=false` promotes the newest preview and is refused only when there is none (`NO_PREVIEW`). Freshness is shown on the rail (`PREVIEW_FRESH`), where an admin can act on it, rather than thrown after they pressed publish. | **[`PREVIEW_FRESH` removed 2026-09-08 — amendment row 16; the rail has fourteen rows.]**
| `ARCHIVE_ENTRY_NOT_FOUND` 404 | same name, **404** | **CLOSED.** I26 is built: `GET /publication-issues/issue/{publicId}/archive/{auditEntryId}/{lang}`, `@PermitAll` with the admin role checked in code so a `?ticket=` works. The one code answers all five refusals — an entry of another issue, an entry that archived nothing, a language it did not archive, a stored location outside the archive root, and a file no longer on disk — because distinguishing them would let a caller establish which audit ids exist elsewhere. The history line carries `archive: {auditEntryId, files:[{lang, fileName}]}` and **no `archivePath`**, per §3.11. |
| `STALE_VERSION` 409 | *(none)* | **OPEN — queue row B-13**, ruled to land before UAT (`R-vi`). |
| `CONCURRENT_PUBLISH` 409 | *(none)* | The series row lock is taken (publish locks the series before the issue) but a contended lock waits rather than refusing, so no code is emitted. Revisit with B-13. |
| `ADVANCED_EXPRESSION_NOT_SUPPORTED` 400 | *(none)* | The hatch was never built, by a settled decision, so nothing can raise it. |
| `UNKNOWN_LEGACY_FILTER` 422 | *(none)* | The importer's five-combination rule reports through the import report's `problems[]` / `notes[]`, which name every offending row — one refusal per run, not per row. |

**Codes the shipped catalogue adds.** Grouped by what they guard; every one is in
`PublicationErrorCatalogue` with the status shown.

| Group | Codes |
|---|---|
| Criteria and operands | `EMPTY_OPERAND` 400 · `UNRESOLVABLE_OPERAND` 400 (the RI-6 hazard: an unresolvable MRN refuses rather than `or()`-ing over nothing) |
| Series save | `SERIES_INVALID` 400 (carries `fieldErrors`, `C-v`) · `SERIES_IS_ONE_OFF` 409 · `SERIES_NOT_ONE_OFF` 400 · `INVALID_STATUS` 400 |
| Issue edit and create | `INTERVAL_INVERTED` 400 · `NAME_BLANK` 400 · `NO_SUCH_LANGUAGE` 400 · `RETRO_CREATE_NOT_APPLICABLE` 400 · `PREDECESSOR_NOT_PUBLISHED` 409 · `ISSUE_IMPORTED` 409 |
| Curation | `NO_MESSAGES` 400 · `TOO_MANY_MESSAGES` 400 · `OVERRIDE_NOT_FOUND` 404 · `OVERRIDE_MESSAGE_NOT_PUBLIC` 409 (*2026-09-25*: an include naming a message not in a public status; 409 because it succeeds once the message is published) |
| Files | `FILE_NAME_IMMUTABLE` 400 · `FILE_WRITE_FAILED` 500 |
| Citations | `CITATION_FORMAT_MISSING` 400 (legacy wrote the literal `" null"` into the field instead) |
| Categories | `CATEGORY_INVALID` 400 |
| Consumers | `TOO_MANY_IDS` · `NO_SERIES_IDS` · `TOO_MANY_SERIES_IDS` · `INVALID_FILTER_VALUE` — all 400 |
| Domains | `NOT_IN_DOMAIN` 403 — *amended 2026-08-29 (R-xxvi): also the **target-side** refusal on S21, whose message names **which** end the caller is not admin in, because the two have opposite remedies (switch domain vs. ask for a role in a domain you do not work in)* |
| Owner and availability *(2026-08-29, R-xxvi)* | `SERIES_INVALID` 400 with `fieldErrors[{rule: "S-20a", field: "domainId"}]` (no owner) · `…{rule: "S-20b", field: "availability" \| "availableDomainIds"}` (no availability, or `SELECTED_DOMAINS` naming nobody) · `SERIES_INVALID` 400 on S21 for a missing/unknown `domainId`, a target equal to the current owner, or an **inactive** target · `SERIES_INVALID` 400 for an availability list naming an unknown or inactive domain · `REASON_REQUIRED` 400 on S21 (3–512, trimmed) · `NOT_IN_DOMAIN` 403 both ends · `STALE_VERSION` 409 (S21 is `@VersionChecked`). **No new public vocabulary** beyond the two `availability` fields |

**Two rules the catalogue enforces that §7 did not state.** A refusal type must extend the base
`PublicationException` or the mapper does not see it (an unmapped throw is a 500); and `RailRefusalCatalogueTest`
pins the rail-row → refusal-code mapping, which is where the one deliberate asymmetry lives — the rail row is
`CUTOFF_NOT_FUTURE`, the refusal is `CUTOFF_IN_FUTURE`.

## Amendments — 2026-08-28 (B-13 optimistic locking, C-9)

| Surface | Change |
|---|---|
| `version` on the wire | `SystemPublicationSeriesVo`, `SystemPublicationIssueVo` and `OneOffVo.series` carry the entity’s `version` (Integer) on every system-tier read and write response; never on the lean VOs. `updateFromVo` never assigns it. |
| Writes | A write MAY send `version`; when present and different from the stored value the server answers **`409 STALE_VERSION { storedVersion, submittedVersion }`** and changes nothing; absent = last-write-wins (the legacy admin sends none). Body field `version`: S9, the single flip, I8 (`UpdateIssueRequest.version`), I16/I17/new-edition (the predecessor’s), `PUT .../link/{lang}`, I12/I13 (the ISSUE’s version), the one-off PUT (`series.version` covers series + issue). Query `?version=`: S10, S11, I18, I19, I9, I24, I25, I14. Exempt: the creates, the dry runs, the estate operations, the bulk flip, preview. The domain guard runs first (403 before 409). A curation forces the issue’s version up (`OPTIMISTIC_FORCE_INCREMENT`) so two admins on one issue collide. Hibernate’s own `OptimisticLockException` (a true interleave inside one transaction) is not mapped and still surfaces as a 500 — recorded, not fixed. |
| **C-4 on the series save** | S8, S9, `/validate` and both one-off writes resolve every criteria operand (area, category, chart, domain, message series) through one `PublicationOperandResolver`; a dangling one is `400 SERIES_INVALID` with `fieldErrors[{rule: "C-4", field: "criteria/criteria/<i>/values/<j>", message}]`. The issue’s criteria override runs the same resolver. A DRAFT may still be half-authored (C-1/C-6 stay activation rules); imports stay unscoped. |
| Catalogue | `STALE_VERSION` 409. |

## Amendments — 2026-08-29 (loop #1 rehearsal fixes)

| Surface | Change |
|---|---|
| S18/S19 import, undo, `import-check` | Run **outside** any ambient JTA transaction (`NOT_SUPPORTED` on the service methods and the endpoints; the phases keep their own `requiringNew` transactions with their own timeouts). `BaseService` is class-level `@Transactional` and the annotation is inherited, so an unannotated service method silently ran on the 240 s budget — the import of the whole estate (784 s) committed its phases and then answered a 500 from the reaped ambient transaction. Pinned by `PublicationTransactionContractTest`. `/upload-series` stays as it is (it only starts a batch job). |
| Imported issues | Carry `week` / `weekTo` / `year` derived by the same `IssueShape` numbering as native issues, from the recovered cut-off, after the chain walk; names stay the archive’s. At four of the seven weekly seams the archive’s title and the numbers describe different halves of one event (a title names the weeks the edition would be current for; the numbers count content) — expected, not corrected. Imported annuals report their calendar year. |
| **I29** rows | Gain `weekTo` and `edition` (absent on a synthesized cell), so a double week and a second edition are distinguishable in the strip. |
| Annual in-force cut-off (R-xxii) | `YEARLY` + `IN_FORCE_AT_CUTOFF`: the cut-off is the end of the day the window opens, in the series’ zone (`CutoffDefault.endOfDay`), for the importer and for a native publish with `PERIOD_START`. `cutoffSource` stays `PUBLIC_WINDOW`. |

## Amendments — 2026-08-29 (R-xxvi: the owner domain and availability)

*The spec is `DOMAIN-OWNER-AVAILABILITY.md`, which is authoritative for names and behaviour; this section is
what it means on the wire. The rows above that this supersedes are marked
in place: §3.1 `domainId`, §6.3 `domain`, §6.3's picker-scoping paragraph and the R-xxi amendment.*

| Surface | Change |
|---|---|
| **`SystemPublicationSeriesVo`** (and `OneOffVo.series`) | `domainId` **required** (§3.1, amended); `availability` and `availableDomainIds[]` added (§3.3). One VO, so a one-off answers exactly as a series does. |
| **S1 / S2 / `GET /one-off-publications/`** | `?domain=` = **owner** filter (§6.3, amended). Omitted = the estate. |
| **S6** `new-series-template` | Pre-fills `availability = OWNER_ONLY` — the template's content mode is `GENERATED_FROM_QUERY`, and the form starts on what the server would default to if the control were never touched. |
| **S7** `copy-series-template` | Carries `availability` **and the list** (they are configuration an admin copies a publication to keep); **clears `domainId`**, which the form re-seeds from the session domain. Copying somebody else's publication must not author a new one straight into their domain. |
| **S8 / S9 / one-off create + update** | `availability` absent on **create** → defaulted from `contentMode`; absent on **update** → unchanged (a client written before the field existed omits it, and re-deciding the default there would silently re-share a publication somebody narrowed). `availableDomainIds` is a **full representation** at both. `domainId` absent → the stored owner is left alone, never cleared and never adopted. Both one-off writes now run the same hard-rule gate the series editor runs. |
| **S15** `/validate` | Stands an availability list in as nameless `Domain` placeholders, as it already does for the owner, so `S-20b` can ask whether "selected domains" *names* anybody without a persistence context. Whether each id **exists** stays create/update's question. |
| **S21** `PUT /publication-series/series/{seriesId}/owner` — **new** | Tier **AD**, `@DomainScoped`, `@VersionChecked`. Body `{ domainId, reason, version }`. <br>**Permission is both ends**: the ordinary write guard for the **source** (the caller must be sitting at the owning desk — `403 NOT_IN_DOMAIN` naming the domain to switch to) **and** admin in the **target**, answered from the token's per-domain roles because the container evaluated roles against the header domain — `403 NOT_IN_DOMAIN` naming *that* side. Sysadmin is not exempt. <br>**Refusals:** `403 NOT_IN_DOMAIN` (either side) · `400 SERIES_INVALID` — no `domainId` named, no such domain, the target **is already** the owner, or the target is **inactive** · `400 REASON_REQUIRED` (3–512, trimmed) · `409 STALE_VERSION`. The guard runs before the version check, which runs before the body checks. <br>**What moves:** the owner, and with it the zone **future** cut-offs are read in. Nothing stamped changes — re-reading a published issue's cut-off in another zone would silently renumber the archive. The target is **removed from the availability list** if it was on it. <br>**Audited** `OWNER_TRANSFERRED` on the series, `detail {from, to}` + the reason. Returns the updated `SystemPublicationSeriesVo`. One-offs use the same endpoint; they are series. |
| **I27 picker · `/rest/publications/search?domain=` · the legacy half of the union** | The **visible-from** predicate, defined in §6.3 (amended). I28, the deep links, `by-message`, member lists and the `?publication=` resolver stay unscoped: reads are unscoped on the wire, and a foreign owner is refused by the admin detail screens on the client (OQ-21). |
| **Sort domain for `?publication=`** | Follows the owner (it was null for the six formerly domain-free series) — accepted. |
| **`/public/v1/*`** | Untouched. Availability never reaches the public adapter; what goes public is `category.publish`, which is domain-blind. |
| **Import check** | `SERIES_WITHOUT_OWNER` violation + `counts.seriesWithoutOwner` — see the amended import-check row above. |
| **S18 / S19 import report** | `notes[]` gains **`OWNER_ASSIGNED`** — written only where the template named no domain, saying which domain the series is owned by and whether it came *by ruling* or *by default*. `problems[]` gains **`OWNER_DOMAIN_NOT_FOUND`**, which **refuses the import**: a missing owner used to leave the series domainless and let S-20 refuse the activation later, which was proportionate while null was legal; the column is `NOT NULL` now, so such a row would take the whole write down mid-flush with a message naming a column. The importer fills every owner from three sources in order — the template's own domain, the ruling table, then `niord-annex` — and sets availability from the content mode. <br>**Amended 2026-09-14:** `notes[]` gains **`MEMBER_LEFT_OUT_PUBLISHED_AFTER_ISSUE`** — one note per imported issue whose legacy tag held rows published more than **fourteen days** after the day the issue closed on (`cutoffStampedAt`, else `publishedAt`, end of day in the series' cut-off zone), naming the publication, the count, and the first five short ids with their publish instants — and the report gains **`membersLeftOutPublishedAfterIssue`**, written even when it is zero. Such a row cannot have been in the printed document and would file the notice under the wrong week in every compilation, so the importer does not freeze it: it is neither a member nor counted, `sortIndex` stays contiguous over the rows kept, and the dry run reports the same numbers without writing. The fortnight covers the reconstructed cut-offs that fall a few days before the real sitting. Nothing in the legacy tables is changed, and a note never fails the import. <br>**Amended 2026-09-14 (compilation backfill):** for the series the compilation ruling shapes — and that series alone, keyed on the ruling and never on the cadence — the import opens one OPEN issue per calendar year from the year after the newest imported annual to the current year in the series' cut-off zone: period 1 January 00:00 to 31 December 23:59:59.999, both bounds `NOMINAL`, names from the series' patterns per language, calendar-year numbering, no members, no stamp, no legacy id. `notes[]` gains **`COMPILATION_ISSUE_CREATED`** (one per year, subject the series id) and the report gains **`compilationIssuesCreated`**, written even when it is zero; the dry run reports the same without writing. A year already covered, or a series with no imported annual at all, gets nothing. The undo removes them with every other issue of an imported series. Publishing them is an operational step after activation, oldest first, through I16 with the rail in force; the current year stays open as the running annual. |
| **Validation** | **`S-20a`** `domainId` required on **every** kind; **`S-20b`** `availability` required and `SELECTED_DOMAINS` must name at least one domain (an empty list is a sharing setting that looks switched on and behaves as switched off — choose *this domain only* to share with nobody). Both are **hard rules** (`HARD_RULES = {S-20a, S-20b, S-22, S-23}`), refused on every save in any status, because the columns behind them are `NOT NULL`. **S-20 itself is unchanged and stays cadence-only**: it is now purely the *timezone* half — a series with a cadence must have an owner whose zone is readable — and the "a cadenced series must have a domain" clause moved to S-20a for every kind. |
| **`SERIES_INVALID` field names** | `domainId` (S-20a) · `availability` / `availableDomainIds` (S-20b) — the names the editor's controls carry. |

## Amendments — 2026-08-30 (UI rework: the release rail says which rows apply)

*Backend half in `niord`; the row list and the rationale are amended in place in §3.10, which is authoritative.*

| Surface | Change |
|---|---|
| **I15** `/publish-checklist` rows | Gain **`applicable: boolean`** (default `true`; absent reads as `true`). Thirteen of the fifteen rows can report `applicable: false` — the eight per-condition rows (`INTERVAL_PRESENT`, `INTERVAL_CHAINED`, `FILE_PRESENT_PER_LANGUAGE`, `REPORT_CONFIGURED`, `REFERENCE_FORMAT_COMPLETE`, `CUTOFF_AFTER_PREVIOUS`, `CUTOFF_BEFORE_SUCCESSOR`, `CUTOFF_NOT_FUTURE`) and the five membership rows (`MEMBERS_RESOLVED`, `MEMBER_LIMIT`, `NO_INEFFECTIVE_OVERRIDES`, `CANCELLED_MEMBERS_ALIVE_AT_CUTOFF`, `OVERLAPPING_ISSUE`) when the issue resolves no member list — under the conditions tabulated in §3.10. **No inapplicable row is a `BLOCK` row that fails**; `MEMBERS_RESOLVED` is the one that can be inapplicable and `passed: false`, and it is a `WARN`. An inapplicable row's `detail` reads `not applicable: <reason>`. |
| The envelope | **Unchanged.** `rows`, `canPublish`, `blockingCodes`. The verdict is the client's to compute, over the applicable rows. |
| Every code, and the acknowledgement | **Unchanged** (A-i, R-iii). All fifteen codes still ship on every issue; `acknowledgeable` / `acknowledgeCode` and `PublishIssueParam.acknowledgedWarnings` are untouched, and so is the publish gate, which reads `passed` and `severity` alone. |

## Amendments — 2026-09-07 (one resolve per screen; the load-time batch)

*Executes `PERF-PLAN-2026-09-07.md` over `PERF-ANALYSIS-2026-09-07.md` §3 phases 1, 2 and 4 and §6.3 items 13–21;
§3 item 10 (server paging of the series archive) is deferred by ruling and nothing here pages it. The measured
starting point is that a request which does almost nothing costs **0.13–0.18 s** on this deployment, so a screen
can afford about three, and that the resolve costs **≈ 0.1 s per 1,000 candidates** — of which an in-force
series' candidate set was its whole published history. **Three routes are added and none is removed:** every
endpoint the workbench composes stays, because each has other consumers.*

| Surface | Change |
|---|---|
| **I30** `GET /publication-issues/issue/{publicId}/workbench?lang=` — **new** | Tier **CUR** (`@RolesAllowed({publication-curate, admin})`), `@GZIP @NoCache`, not `@DomainScoped` — a read, like the five it composes. `404 ISSUE_NOT_FOUND` through the same `required(publicId)`. Body `IssueWorkbenchVo { issue, members, audit, overrides, checklist?, omissions? }`, and **every part is field-for-field the shape its own endpoint returns**: `issue` = I4's `SystemPublicationIssueVo`, `members` = I10's `IssueMemberVo[]` (frozen or live by status, §6.5), `audit` = I11's `IssueAuditEntryVo[]`, `overrides` = `GET /overrides`' `IssueOverrideVo[]` **in `ORDER BY o.id`, the order that endpoint already had**, `checklist` = I15's envelope. No new DTO section is needed: §3.6, §3.7, §3.9, §3.10 and §3.11 already define every field. `lang` titles the member rows and nothing else — audit, overrides, rail and omissions are language-independent. |
| **I30** `checklist` — when it is absent | Two absences, both facts rather than failures. **A frozen issue carries none**: the rail is a pre-release gate that re-resolves against today's corpus, and against a released issue it answers a question nobody asked (measured on a weekly that released 20 messages and whose gate counted 26, and drew two red rows on a successful release — one of them failing *because* the issue is published). **A curator who is not an admin carries none**: the gate is the least-privileged of the five parts, and the admin-only half is withheld in the body (`userService.isCallerInRole(ADMIN)`) rather than by a second annotation. The rail is computed with `allowFuture = false` — waiving a future cut-off is a choice made in the publish dialog, which asks I15 for its own rail at the instant it is offering. |
| **I30** `omissions` | `IssueOmissionsVo { missCount, misses[] }` — the full count, and the first **50** rows behind it. The cap is one constant, `IssueOmissionsVo.PROBE_SAMPLE`, which S13 now reads too, so the two screens cannot drift. Absent where nothing was resolved, which includes every frozen issue (a frozen issue takes no resolve at all). Same `CriteriaMissVo {messageUid, code, detail}` rows as S13, so one client type reads both. See §3.8, whose bounding paragraph is corrected in place. |
| **I30** — the resolve | **One** `IssueResolution` serves the members, the rail and the omissions. Before, the screen took three resolves of the same issue over the same interval plus a fourth for the dashboard badge; the three were literally the same call (`EffectiveCriteria.resolvedFor` + the same interval + the same includes/excludes). The visible consequence is that they can no longer disagree: a rail counting 214 beside a list of 213 is not a state this screen can reach. |
| **I30** — **the omissions cut-off moves.** Visible; **accepted by Rasmus 2026-09-11** (the phase-2 go-ahead covered the one-call workbench, not this delta — it was recorded as open in `PERF-PLAN-2026-09-07.md` W1 until then) | The probe used to ask at `issue.intervalTo ?? cutoffStampedAt ?? now`; the workbench answers at **now**, the instant the members beside it were resolved at. On an OPEN weekly issue whose nominal period closes in the future the two differ, and a message with a **future** `publishDateFrom` moves from *member* to an `AFTER_CUTOFF` omission. It is a correctness fix — the panel and the list stop answering for different intervals — and it is the one wire-visible behaviour change I30 makes. |
| **I3 / I4 / I10 / I11 / I15 / `GET /overrides` / S13** | **All stay**, unchanged, for their other consumers (the publish dialog's own rail, the criteria editor's preview, the series card, the citation surfaces). I30 composes them; it does not replace them. |
| **I15** `/publish-checklist` — response type only | Now returns `PublishChecklistVo` / `PublishCheckRowVo` instead of a `Map` built key by key in the endpoint, so the rail has **one** definition of its wire shape for its two readers. **The JSON is byte-identical, nulls included**: both VOs are `@JsonInclude(ALWAYS)` with an explicit `@JsonPropertyOrder`, deliberately *not* `IJsonSerializable`, because that interface suppresses nulls and `acknowledgeCode` is null on fourteen of the fifteen rows — a client reads that null as "this row cannot be acknowledged". |
| **I31** `GET /publication-issues/recent-by-domain?domain=&periods=&unscheduledIssues=&lang=[&cadenced=]` — **new** | Tier **AD**, `@GZIP @NoCache`. Response `List<IssueTimelineVo>` — **the same shape I29 returns**, so no client type changes. A **second route rather than a parameter on I29**: I29 is `Roles.USER` and its contract says it never scans the estate, and "which series does this desk own" is the admin series list's question. `domain` is **required** — missing or blank is `400 DOMAIN_REQUIRED` — and an **empty** desk is `200 []`, never a refusal: the dashboard fires this before it knows whether the desk owns anything, so a refusal would put an error banner on a page that is simply empty. |
| **I31** — which series the server picks | `PublicationSeriesService.findTimelineSeries(domainId, cadenced, limit)`, which reproduces the dashboard's own rule in one place instead of leaving it copied on the client: **owner** = the named domain (a series owned elsewhere is absent even where it is shared and citable here — R-xxvi), `kind ≠ ONE_OFF`, `status = ACTIVE` **or** (`DRAFT` **and** no `importSource`), and `cadence ≠ NONE` / `= NONE` by the `cadenced` flag when one is given — **`null` drops that predicate and returns both kinds in one ordered list** (evening amendment below). Ordered by cadence rank (DAILY, WEEKLY, MONTHLY, YEARLY, NONE) **ranked in Java, not by the native ENUM column**, ties on `seriesId`. `MAX_TIMELINE_SERIES = 50` is a **slice** here, not I29's refusal: refusing because a desk owns fifty-one would blank its dashboard, where a caller that *named* fifty-one chose the list itself. Above the cap the two sides may keep different fifties (the client breaks ties on the localized name); a card whose strip did not arrive renders without one, exactly as it already does beyond the client's own slice. |
| **I31** — `periods`, and the clamp that was wrong | Clamped to `1..MAX_TIMELINE_PERIODS (52)` when `cadenced=true`, and to `1..`**`MAX_TIMELINE_ISSUES (200)`** when `cadenced=false`. A cadence-less series has no periods — its strip *is* its archive, and the dashboard counts the rows it gets back as the series' whole issue count — so the client's request for 100 was being silently halved to 52 by I29's single ceiling and the card would have reported 52 for anything longer. Not bitten today (the longest unscheduled series has eleven editions) and not left hidden. **I29 is untouched** and still clamps at 52. |
| **I31** — **one call for both kinds** (evening amendment, measured) | The dashboard draws cadenced cards (eight cells) and cadence-less cards (their archive) from one desk and was paying the request floor twice for one question — measured on test as 4 requests / 509 ms against a 3 / 500 budget. `cadenced` is now **optional**: absent, the answer covers **both** kinds the desk owns, each series sized by its own cadence — `periods` (default 8, clamp 1..52) for cadenced series, the new **`unscheduledIssues`** (default 100, clamp 1..`MAX_TIMELINE_ISSUES` 200) for cadence-less ones; the `MAX_TIMELINE_SERIES` slice applies to the union, in the same cadence-rank-then-`seriesId` order (cadence-less last). Given, `cadenced=true|false` narrows to that kind exactly as before, so nothing that called the first form breaks. Same response type, same tier, same refusals. The client makes one desk read and derives both card kinds from it — a shared derivation, not a cache: it lives and dies with the dashboard view. |
| **I29 / I31** — how the strip is read | `IssueListService.recent()` now reads the newest `periods + 2` issues instead of the series' whole archive (~503 rows with their descs, to draw eight cells). Two queries, because Hibernate paginates **in memory** under a collection fetch join: a bounded select, then a descs prime on the ids kept. **No wire change** — `IssueTimelineVo` carries no count over the archive, and the only rows a window can lose close at or below its oldest issue, which is already past the cap the caller applied. The slack of 2 absorbs `GapDetection.uncovered`'s rounding, whose last synthesized gap can close up to half a period past the issue it was measured from. |
| **`GET /one-off-publications/{seriesId}`** — **new** | Tier **AD**, `@GZIP @NoCache`, **unscoped** like `GET /` with no `domain`: a deep link into a one-off has to resolve from whichever desk the reader is sitting at, and narrowing by owner would answer "no such publication" for one that exists and is open on somebody else's screen. Body is the same `OneOffVo` the list rows carry, through the same `toVo` — which is the point: `active` is not a column, it folds series `ACTIVE` + issue `PUBLISHED` + the public window into the one dot the screen shows, and a client that re-derived it would be a second definition of visibility. Refusal is **`SERIES_NOT_FOUND` → 404** for a missing series **and** for one that is not a one-off: this collection *is* the one-off publications, so a weekly series' id names nothing in it, which is the same answer the list gives by leaving the row out. The save's `SERIES_NOT_ONE_OFF` **400** is a different refusal and stays where it is — there the caller holds a form that would force cadence NONE onto a scheduled series. |
| Catalogue | **`DOMAIN_REQUIRED` 400**, beside `NO_SERIES_IDS` / `TOO_MANY_SERIES_IDS`. `SERIES_NOT_FOUND` 404 is already catalogued and is reused unchanged. |
| Tier matrix | Three rows added to `PublicationTierMatrixTest`, which fails until they exist: `GET /publication-issues/issue/{publicId}/workbench` → **CURATOR**, `GET /publication-issues/recent-by-domain` → **ADMIN**, `GET /one-off-publications/{seriesId}` → **ADMIN**. I30 is deliberately **not** `@ProgrammaticAdmin` — the matrix refuses that together with `@RolesAllowed`, and the admin check here decides what is *in* the payload, not who may call. |
| **S13** `/resolve-preview` — magnitude, not shape | Unchanged on the wire, and its numbers move. RI-4's date half is now applied in SQL as well (below), so on an **in-force** series both `candidates` and `missCount` shrink by roughly two orders of magnitude — the P&T list measured **6,066 candidates without the clause, 174 with it** (`MemberResolutionDifferentialTest`'s printed `[B1]` line). The criteria editor's "from {{candidates}} candidate(s)" stays accurate; it is now counting the rows the rule could plausibly take rather than every notice the series ever published. Series with `aliveAtCutoff = false` are untouched, and so is every weekly series, whose interval already bounded the set. |
| **§3.9 `NOT_ALIVE_AT_CUTOFF`**, **§3.8 bounding** | Amended in place — see those sections. In short: the date half of the alive clause is emitted in SQL (`publishDateTo IS NULL OR publishDateTo >= :cutoff`, NULL-safe, **only** under `aliveAtCutoff`), as the exact negation of `MembershipPredicate`'s own branch, so it removes only rows the predicate was going to reject and the candidate set stays a superset by construction. The status half — the withdrawal instant — stays in Java alone. **No index was added**; see DATA-MODEL §8.3. |
| **Two further consequences of that clause, both visible, both ruled** | (1) An **EXCLUDE override naming a notice that has since expired** is no longer a candidate, so `STALE_OVERRIDE` fires for it and the rail's `NO_INEFFECTIVE_OVERRIDES` row reports it — **`WARN`, never `BLOCK`**, so it cannot refuse a release. That is the truth about such an override: it changes nothing. (2) `IssueOverride.appliedAtPublish` freezes **`false`** for such an exclude where it used to freeze `true`, which is what the freezing rule already says it means ("an exclude naming a uid the query never returned freezes false: it changed nothing"). Already-published issues are unaffected — the flag is written once, at publish. |
| **I15 `/publish-checklist` — two behaviours the shared resolve makes explicit** | (1) On a **PUBLISHED or RETIRED** issue no live resolve is taken any more (the members are a record, §6.5), so the rail reports `MEMBERS_RESOLVED` as `WARN` / `passed: false` and `MEMBER_LIMIT` as `0 of 500`. The app never asks for the rail on a frozen issue — I30 omits `checklist` there and the screen shows no rail — so this is what a direct caller sees, and it is honest: nothing was resolved. (2) A `GENERATED_FROM_QUERY` series with a `timeRelation`, **no** criteria document and one or more `INCLUDE` overrides now resolves the **curated** list — the includes are the members — where the rail used to report the pre-curation count. Same `membership` condition as §3.10's `applicable` rule, read off one resolve. Both pinned by `IssueWorkbenchTest` (`theStandaloneRailDoesNotResolveAFrozenIssue`, `aQueryBackedSeriesWithNoDocumentReportsItsIncludesOnBothFormsOfTheRail`). |
| **A non-admin curator's issue screen** | **Changed, and better.** Today `publish-checklist` is admin tier and the screen's chain had no branch for its 403, so a curator without the admin role got the whole page as an error. Through I30 they get the full screen with `checklist` absent, which renders as no rail. Recorded here as a ruling rather than left as an implementation detail: whether a release may be made is not their decision, but reading the issue is. |
| Not changed | The canonical membership rule, RI-1's status conjunct, RI-4's NULL-safety, RI-7's unbounded in-force interval, the frozen snapshots, `?publication=`, the public adapter, and the `/public/v1/*` surface. Phase 1 changes only **which rows the SQL hands to the predicate**; every decision the predicate makes is the same, and the differential test over the corpus — now run with `aliveAtCutoff` both `false` and `true`, plus a direct prefilter-vs-predicate guard — is what says so. For an **in-force** series the equivalence is carried by that differential alone (`MemberResolutionDifferentialTest`, SQL-with-clause versus the pure predicate over the same rows): `LocalEstateReplayTest` prints but does not assert the member set of an in-force series, so the replay's agreement is transitive through the differential rather than measured directly. |

## Amendments — 2026-09-07 (deleting an issue)

*Ruled by Rasmus on 2026-09-07 (evening), after finding that no issue could be deleted at all. The previous system let an inactive publication be deleted; the new model had only retire, which withdraws an issue from the public list but keeps it as a record. Retire is right for a real week of notices that was withdrawn and wrong for the other reason people delete — a duplicate period, a test release, junk from the import — so a real delete with real guards is added. A hidden "deleted" status was considered and rejected: a tombstone every query and every future coder has to remember to exclude is the same class of trap as a cache.*

| Item | Amendment |
|---|---|
| **The rules** | An **OPEN** issue may be deleted by an admin of the owner domain **unless a message cites it** (ruled 2026-09-09; corrected 2026-11-16: the picker offers PUBLISHED issues only, but an imported issue keeps its legacy id and sits at OPEN until it is first published, so the messages that cited the legacy row already cite it) — the same `409 ISSUE_CITED` refusal as for a retired issue, naming the citing messages. A **PUBLISHED** issue is **never** deletable: retire first, as the previous system required inactive first, so publishing stays one-way. A **RETIRED** issue may be deleted by an admin of the owner domain **only when no message cites it**; if any does, the refusal names them, because a dangling publication reference is a silent error. |
| **I32** `DELETE /publication-issues/issue/{publicId}?version=` — **new** | Tier **AD**, `@DomainScoped` on the owner domain and `@VersionChecked`, exactly as `PUT /issue/{publicId}/retire`; an optional `reason`. **204** on success. Refusals: `404 ISSUE_NOT_FOUND`; **`409 ISSUE_PUBLISHED_NOT_DELETABLE`** for a PUBLISHED issue; **`409 ISSUE_CITED`** for a RETIRED issue some message still cites; the body carries **`citingMessages: [{ messageId, uid }]` (the first 20) and `citingCount` (the exact total) at the top level** beside `code` and `message`, the convention the exception mapper already uses for `stampedAt` / `unacknowledgedWarnings`. **"Cites" is defined as built:** a message whose stored publication text — `MessageDesc.publication` or `internalPublication` — carries the marker `publication="<publicId>"` that `PublicationUtils` writes when an editor picks a publication (one spelling, `PublicationUtils.citationMarker`; an imported issue carries the legacy id as its `publicId`, so one lookup covers both). **Membership is not citation**: the resolver's `?publication=` resolution answers "which messages are IN this issue", and those rows are deleted with it; the delete guard asks the reverse question, "which messages point AT it", through `PublicationResolver.citingMessages(publicId, limit)`. The `LIKE` pattern escapes `_` and `%` (`ESCAPE '!'`) so an id with an underscore cannot be blocked by another publication's citations. Soft-deleted messages (`Status.DELETED`) **count** as citing — conservative, a deleted message can be restored — recorded as an open decision below. `ISSUE_NOT_DELETABLE` is **removed** from the catalogue: it became unreachable, and one code could not tell a client which of the two next steps to offer. |
| **What goes** | Done by the new `IssueDeleteService` (one delete path; the old `IssueLifecycleService.deleteIssue` is removed). The issue row, its descs, its `IssueMember` and `IssueOverride` rows, **its own `IssueAuditEntry` rows**, and the files under its `repoPath` (containment-checked against the repository root; a missing directory is tolerated; a failure to remove it is logged, never fatal — the trace is written before the rows go, so it exists either way). **One write on another issue, and only one:** a successor edition points back at its predecessor through `supersedes`, a plain FK with no `ON DELETE SET NULL`, so a retired predecessor could not be deleted without nulling that pointer first — the successor is a released document of its own and survives untouched otherwise; its `snapshotIntervalFrom` is a stored column and stands. The strip shows the period as **missing** afterwards, which is the model's existing way of saying the archive has a hole. Not swept: archive copies named on the deleted audit rows and the preview folder under `previewRoot/<publicId>` (the preview TTL sweep takes the latter). |
| **What stays: the trace** | One **series-level** `IssueAuditEntry` — `issue = null`, `series` set, `action = DELETED`, the actor, the reason, and `detail { publicId, statusAtDeletion, names per language, intervalFrom, intervalTo, publishedAt, memberCount, legacyPublicationId? }` — written before the rows are removed, so it exists even if the file cleanup only warns. Series-level entries (`issue IS NULL`) already exist for the `SERIES_*` actions; this follows their shape. |
| **I33** `GET /publication-series/{seriesId}/history` — **new** | Tier **AD**, `@GZIP @NoCache`, not domain-scoped (a read). `List<IssueAuditEntryVo>` — I11's shape — of the series-level entries (`issue IS NULL`) for that series, newest first. `404 SERIES_NOT_FOUND`. **No `lang` parameter**: the VO carries no localised field, so a client that sends one is simply ignored. The series page reads it only when its History panel is opened, and again on the page's own refresh while the panel is open; it adds no request to the page load. |
| Catalogue | `ISSUE_PUBLISHED_NOT_DELETABLE` 409, `ISSUE_CITED` 409 added; **`ISSUE_NOT_DELETABLE` removed** (unreachable). |
| **Rulings on the three open points** | (1) **Settled 2026-09-09 (Rasmus): soft-deleted messages count as citing** and therefore block the deletion of a RETIRED issue — the conservative reading (a deleted message can be restored and its citation with it); the code does this and stays so. (2) **An OPEN issue can already be cited**: not through the picker, which offers PUBLISHED issues only, but through an import — an imported issue keeps its legacy id and sits at OPEN until first published, so the messages that cited the legacy row cite it, and `PublicationResolver.systemVo` stays ungated so such a citation can still be opened and updated (corrected 2026-11-16; the earlier text said editors pick an issue while it is being prepared) — so the ruling's premise "nothing can cite an open issue" does not hold literally, and deleting an OPEN issue can leave a dangling reference. **Settled 2026-09-09 (Rasmus): the guard is widened to OPEN** — `IssueDeleteService.delete` runs the citation lookup for every non-PUBLISHED issue and refuses with `ISSUE_CITED` naming the messages; the confirm text says so for both statuses. (3) **Settled 2026-09-09 (Rasmus):** no reason is asked for on delete: the confirm asks whether, not why; the wire carries an optional `reason` should a dialog be wanted. |
| Tier matrix | Two rows: `DELETE /publication-issues/issue/{publicId}` → **ADMIN**, `GET /publication-series/{seriesId}/history` → **ADMIN**. |
| UI | "Delete issue…" in the issue screen's menu, shown only for an OPEN or RETIRED issue to a writer of the owner domain, behind the danger confirm, whose text says the issue and its history are deleted, cannot be undone, and that the series keeps a one-line record. A refused RETIRED delete lists the citing messages inline. The series page gains a collapsible **History** panel (closed by default, read on open). |

## Amendments — 2026-09-08 (the batch of nine)

*Nine fixes asked for by Rasmus on the evening of 2026-09-07 and built overnight; niord and niord-app commits of 2026-09-08. Items 4, 5/5a, 7 and 8 are frontend-only and change no wire.*

| Item | Amendment |
|---|---|
| **1 · `regenerate` removed** | The publish body (I16) is **`{ cutoff?: epoch ms, acknowledgedWarnings: string[], names?: { lang: name }, version? }`** and the amend body (I17) **`{ reason, acknowledgedWarnings, version? }`**. Publish and amend **always render** the document from the ordered frozen member list (the former step 10a); the step that promoted the newest fresh preview into the repository (10b) is deleted with everything only it used. `PREVIEW_FRESH` stays on the rail — it is about what the admin had in front of them, not about the document. **`PREVIEW_STALE` is retired** from the catalogue; **`NO_PREVIEW`** survives only as the 404 of I22/I23. Both bodies are read as maps, so a client that still sends `regenerate` is not refused — the key is ignored and the release renders; the frontend no longer sends it. | **[`PREVIEW_FRESH` removed 2026-09-08 — amendment row 16; the rail has fourteen rows.]**
| **2 · names at release** | `names?` on I16: `{ "<lang>": "<name>" }`, **only the languages the admin actually retyped** (omit a language to leave it alone, omit the object to rename nothing). Validated FIRST — `400 NAME_BLANK` for a null/blank value, `400 NO_SUCH_LANGUAGE` for a language the issue has no row for — before anything is stamped; then applied **after the restamp** (which re-derives suggested names from the stamped period) and **before the freeze and the render**, through the one rename path (`IssueEditService.applyNames`): the desc’s manual-name flag is set and `NAME_CHANGED` is audited only when the value actually changed. |
| **3 · names at create** | I7 `POST /publication-issues/issue/` body gains the same optional `names` map, applied after the create through the same path (the create derives the suggestions being corrected). The draft dialog’s name fields are editable and prefilled with the suggestions; a retyped language keeps what was typed when a bound moves, an untouched one follows the suggestion. **Naming rule corrected in passing:** a rename in ONE language no longer freezes every language’s name at the next restamp — the audit-trail fallback is consulted only when no desc carries the manual flag. |
| **6 · omissions name the message** | `CriteriaMissVo` is **`{ messageUid, messageId, code, detail }`** — `messageId` the message’s short id, `null` when it has none — on both I30 `omissions.misses[]` and S13 `misses[]`. Filled for the capped sample in **one** query (`MemberResolutionService.omissions`), never per row. The workbench panel renders the short id as the same link the member rows use. |
| **9 · the render loads members once, per association** | No wire change. Preview and publish used to load every member by uid **per language** and let the document lazily initialise each collection it reads — on test, ~1,300 statements for the 171-member P&T issue and the dominant term of a 7 s preview. `IssueMessageLoader` (stateless, no cache; the map dies with the render) loads the ordered uids once per render: roots, then one `LEFT JOIN FETCH` per association over the whole set, then the second level; both languages read the same map. Measured locally: 1,320 → 12 statements, 1,155 → 0 lazy fetches on 165 real members; document content asserted identical over a member carrying two of every association. **Server timings to be re-measured after deploy** (predicted P&T ≈ 1.0–1.5 s, the 62-page NtM tt ≈ 2.5–3.5 s, the rest being FreeMarker + PDF per language). |
| **10 · the publish dialog counts the live members** | Frontend-only. The dialog read the issue's stored `memberCount` column, which is `0` (not null) for an issue that has never been frozen, so a native open issue offered "0 messages" beside a list of 193. It now counts the workbench's live member list for an OPEN issue. |
| **11 · stored previews ride in the workbench** | I30 gains **`previews: [{ lang, renderedAt (epoch ms), stale }]`** — one row per language with a stored preview (newest generation), in the issue's desc order; **`stale = renderedAt < issue.updated`**, the one expression the `PREVIEW_FRESH` rail row is computed from (`IssuePreviewService.isStale`; no file = stale, a null stamp = fresh), so row and rail can never disagree. The key is always present: `[]` when nothing is stored, `[]` for a frozen issue, and `[]` for a reader who may not render or open a preview (narrowed exactly like `checklist`, since the preview endpoints are admin-only). The screen seeds its preview rows from every workbench read, so a refresh keeps the previews the server still holds (they are swept after seven days) instead of offering to generate again while the rail says the preview is current, and a stale row is badged on the row itself. `POST …/preview` still answers `[{ lang, renderedAt }]` without `stale` — a fresh render is current by definition. No extra request. | **[`PREVIEW_FRESH` removed 2026-09-08 — amendment row 16; the rail has fourteen rows.]**
| **12 · a retired issue no longer covers its period** | Ruled 2026-09-08 as the correction path for an issue that cannot be amended (every imported one): **withdraw it, then create, curate and publish a new issue for the same period.** Two rules move to make that possible, backend only: gap detection ignores `RETIRED` issues when deciding coverage, so the withdrawn period is synthesised as a `MISSING` row (the retro-create offer the strip already renders) while the retired row itself stays in the list with its file, window and citations; and `ISSUE_INTERVAL_OVERLAP` tests **`PUBLISHED` neighbours only** — a withdrawn issue is the statement that what went out for its period should not stand, and the new issue is the correction. Two rows for one period, one Withdrawn and one Published, is the intended result. No wire change. |
| **13 · one instant drives the open issue screen** | I30 gains **`?at=<epoch ms>`** (OPEN issues only, ignored when frozen; absent = now; must satisfy `intervalFrom ≤ at ≤ now`, else **`400 INVALID_INSTANT`**): `members`, `checklist` (its `proposedCutoff` = `at`) and `omissions` are all computed at that instant, echoed as **`viewedAt`**. New **`afterPlannedCutoff: IssueMemberVo[]`** — the members of the **as-of-now** resolution whose `publishDateFrom` lies after the issue's `intervalTo` (the *planned cut-off*), same row shape and order as `members`; `[]` when `intervalTo` is null or not yet past, and for a frozen issue; computed from the as-of-now resolution whatever `at` is (no second resolve when `at` = now). The screen shows a view switch only when that list is non-empty, lists those rows as the first, collapsible section of the member list, and reads members, rail, left-out panel and headline count from the one response at the viewed instant. The publish dialog pre-selects the viewed instant and shows the count and warnings of the **chosen** instant from I15 (`memberCount` on the envelope) — the headline count no longer ignores a chosen cut-off. Default view: *as of now* for `RELEASE_MOMENT` (and no default), *as of the planned cut-off* for `PERIOD_END` once it has passed. |
| **14 · one period everywhere — edit mode** (row 13's rule, second half) | Measured on test (NtM tt, 2026-09-08): every server path agrees for equal inputs — workbench `?at=`, its rail, I15 `?cutoff=` and S13 with the same start and cut-off give members 141 / 178 / 195 / 209 / 195 / 193 at 15 Jan / 1 Mar / planned 19 Aug / 1 Jun / 19 Aug / now, with the same cancelled-after counts. The screen disagreed only in **edit mode**, where the criteria editor was bound to the *saved* period. Rule: every number on the open issue screen derives from one (period start, instant) pair — in read mode the stored start and the viewed instant; **while editing, the start and the planned cut-off as typed in the form** (the instant clamped to now), driving the member list, its count, the rail, the left-out panel **and** the criteria editor's count live, with the view switch hidden. Wire: I30 gains **`?from=<epoch ms>`** — a what-if period start for an OPEN issue (ignored when frozen; `from ≤ at`, else `400 INVALID_INSTANT`); the resolution and the membership rows use `Interval(from, at)`, the chain rows (`INTERVAL_PRESENT`, `INTERVAL_CHAINED`, `CUTOFF_AFTER_PREVIOUS`, `CUTOFF_BEFORE_SUCCESSOR`, `CUTOFF_NOT_FUTURE`) keep answering for the stored interval; **`viewedFrom`** echoes the start used (`null` where the resolution has no lower bound — an in-force issue — and the key is always present). Nothing is saved by a preview read. `afterPlannedCutoff` — see row 15: measured against the cut-off being looked at (`at` when named, the stored plan otherwise), echoed as `lateAfter`. The "Published after the planned cut-off" section is **collapsed by default**. Client rule, no wire: a period the server would refuse — `from ≥ at`, or a start later than now — is **not sent**. The screen holds the answer it has and names the state beside the boxes, because the refusal used to arrive as a failed read of the whole screen and took the edit panel that caused it off the page. For the same reason a refused **re-read** keeps the issue it had, with the banner over it; only a first read that fails leaves nothing. |
| **15 · the late list follows the cut-off being looked at** (row 13's rule, third half) — **Implemented 2026-09-08 (evening), backend and client** | Supersedes the `afterPlannedCutoff` sentence of row 14. **Rule:** `afterPlannedCutoff` becomes relative to the instant being treated as the cut-off — **`at` when given** (the planned view, or the planned cut-off as typed while editing, clamped to now), **the stored planned cut-off only when no `at` is given** (the plain as-of-now view) — echoed as **`lateAfter`**; it is the as-of-now members published after that instant, so it moves with the cut-off (NtM tt: 159 / 153 / 118 / 35 / 0 at 15 Jan / 1 Mar / 1 Jun / 19 Aug / now). **Dialog, same ruling: two options only — publish at the planned cut-off (unavailable while it lies in the future) or now; no free "another time".** Changing the cut-off from the dialog **edits the plan** (the issue's `intervalTo`, through the existing issue update) and the dialog's numbers then follow it, so a cut-off changed in the dialog and one changed in edit mode are the same act with the same numbers. I16 would still accept an explicit `cutoff`, which the dialog would send only as the planned instant. |
| **16 · the "preview is out of date" concept is removed** (Rasmus, 2026-09-08) | Publishing always renders the document, so a stale preview can never go out; the `PREVIEW_FRESH` rail row only said "what you looked at may differ", read like an instruction, and made a preview look required before publishing. Removed everywhere: the rail row and its acknowledgement (the checklist has **fourteen** rows; §3.10 and the "applicable" tables read accordingly), `IssuePreviewVo.stale` (rows are `{ lang, renderedAt }`), the staleness computation, the per-row "out of date" badge. The "Generate preview" button lives permanently with the preview rows in the Document card for an OPEN issue whose series has a report. `POST …/preview` is unchanged. |
| Frontend-only | **4** — a desk switch is a full reload onto the same address, so the picker leaves a one-shot mark in session storage that the domain service reads and clears at start-up; a series screen that starts under that mark and is not owned by the new desk navigates to the dashboard (a deep link from another desk still opens read-only with its note). **5/5a** — every publication document `href` and copy-link value resolves through one `absoluteApiUrl()` against `apiBaseUrl`’s origin (dev/test: the backend host; production: the page’s own). **7** — the series page renders neither the current-issue card nor the archive until the first series AND archive answers have arrived. **8** — the twelve remaining `da_` placeholders replaced by `ai_` proposals. |

## Amendments — 2026-09-08 (round three: naming a message, structured rail details, the chain rows)

*Six fixes asked for by Rasmus on the evening of 2026-09-08 and built while away from keyboard; niord and niord-app commits of 2026-09-08. Items 1, 4 and 5 are frontend-only and change no wire.*

| Item | Amendment |
|---|---|
| **1 · one rule for naming a message** | Every row of the publication API that refers to a message carries **`messageUid`** (the key), **`messageId`** (the short id, `null` for a message not numbered yet) and **`title`** (the desc title in the request's `lang`, else the first available, `null` when none). Filled in **one** query per list by `MessageNaming.namesOf(uids, lang)` (a LEFT JOIN over the descs, chunked at 1000), never per row. New this round: `CriteriaMissVo.title` (I30 `omissions.misses[]`, S13 `misses[]`); `IssueOverrideVo.messageId` + `.title` (I30 `overrides[]` and `GET /overrides`; these two keys are omitted when null, the VO suppresses nulls); `ISSUE_CITED`'s `citingMessages[]` gain `title`; and the criteria probe's **`sample[]`** (S13 `resolve-preview`) becomes rows **`{ messageUid, messageId, title }`** instead of bare uids (`NamedMessageVo`; a uid nothing was found for still yields a row with both names null, so the sample length keeps matching the count beside it). To carry the language, **`?lang=`** is accepted by `DELETE /issue/{publicId}` (I32), `GET /issue/{publicId}/overrides` and `POST /publication-series/resolve-preview` (S13). `IssueMemberVo` already had `frozenShortId` + `title`. **Client rule:** a uid is never printed — the short id is the link where present, otherwise the title with a "not numbered yet" note, linked by uid (the messages screen's `?messageId=` resolves a uid first, then a short id); one shared piece renders every message name in the publications area — member rows, the late table, excluded rows (struck through, the same shape as a member row), the left-out panel, the criteria probe sample, the citing-messages list of a refused delete. |
| **2 · rail details are codes, translated by the client** | Analysis of every user-facing text the redesign added to the backend: the whole existing REST layer ships codes and lets the client translate (the `web` + `message` dictionaries served by `GET /rest/dictionaries/dictionary/{name}/entries`); server-side translation exists only for FreeMarker documents and one mail subject. The rail's `detail` sentences were the one family shipping prose with no code beside it. Now `PublishCheckRowVo` carries **`detailCode`** (a stable string per sentence variant, e.g. `MEMBERS_RESOLVED.count`, `CUTOFF_NOT_FUTURE.at`, `NOT_APPLICABLE.NO_PREDECESSOR`) and **`detailParams`** (an object of the values the sentence uses — `count`, `limit`, `at` / `*At` as epoch ms, `zone`, `lang`, `codes[]`, `present`, `status`, `seriesStatus`, `timeRelation`, `publicId`); both keys are **always present** (`{}` when the sentence takes no values), and the English `detail` stays for API readers. 28 codes: 18 sentence variants + 10 `NOT_APPLICABLE.<reason>` from the `Inapplicable` enum (`NOT_QUERY_BACKED`, `NO_PREDECESSOR`, `NO_SUCCESSOR`, `PUBLISH_GENERATES_FILE`, `NO_DOCUMENT`, `NOTHING_RENDERED`, `NOT_CITABLE`, `FUTURE_ALLOWED`, `NO_MEMBERSHIP`, `IN_FORCE_SERIES`). The client renders `admin.publications.checkDetail.<detailCode>` with the params (instants through the house formatter in the session zone; enum-valued params through their existing label keys; `.one` / `.present` / `.absent` variants chosen client-side) and falls back to the English `detail` only when no key exists; the rail row's heading sentence `checkDetail.<ROW_CODE>` is a different sentence (what a failing row *means*) and stays. The per-row English hover is gone. |
| **3 · the chain rows on series whose issues do not tile** | `INTERVAL_CHAINED` asks whether the period opens where the predecessor's stamped cut-off closed; only a `PUBLISHED_IN_INTERVAL` issue is given a lower bound at all (`IssueShape.shapeInterval`), so on every other series the row could only ever warn, from the second issue on, for ever. It is now **not applicable on every series that does not tile**, with the reason that fits the kind: `NOT_APPLICABLE.IN_FORCE_SERIES` for `IN_FORCE_AT_CUTOFF`, `NOT_APPLICABLE.NOT_QUERY_BACKED` for uploaded / link-backed / one-off series (null `timeRelation`, as S-1 requires). `OVERLAPPING_ISSUE` — overlap is what an in-force series does — is `NOT_APPLICABLE.IN_FORCE_SERIES` for `IN_FORCE_AT_CUTOFF` and keeps `NOT_APPLICABLE.NO_MEMBERSHIP` where there is no member list. `CUTOFF_AFTER_PREVIOUS` / `CUTOFF_BEFORE_SUCCESSOR` stay applicable everywhere (they order stamped cut-offs, not periods). Tiling series are unchanged. The open P&T issue no longer carries two standing warnings. |
| Frontend-only | **1** — the publish dialog captures the refusal notice and error standing at open and shows only what arrives after it, so a cancelled attempt's "unacknowledged warnings" does not greet the next open; the screen also clears the facade's error before opening. **4** — the English exclude flow says "Exclude" (button, modal title, confirm, effect line); the Danish already did. **5** — every field name `SeriesValidator` can put in `SERIES_INVALID.fieldErrors` has a label key (`status`, `categoryId`, `criteria`, `descs` were missing; criteria JSON pointers and `reportParams.<key>` resolve), pinned by a parity spec. |

## Amendments — 2026-09-09 (round four: the edition, the public window by hand, the audit order)

*Rulings from the fourth review round; niord and niord-app commits of 2026-09-09. Item 4 is frontend-only and changes no wire.*

| Item | Amendment |
|---|---|
| **1 · the edition is editable, and the create derives it** | **I8 `PUT /publication-issues/issue/{publicId}` gains an optional `edition`** (string, trimmed, 1–64 characters, **OPEN issues only**; absent or `null` leaves it unchanged; an empty or blank string is **`400 EDITION_INVALID`**), applied through the one edit path (`IssueEditService.applyEdition`) and audited as **`EDITION_CHANGED`** only when the value actually changed — the same shape the `names` rule of 2026-09-08 already has. `SystemPublicationIssueVo.edition` is unchanged and stays. **The CREATE derives it**, never the client: `"1"` for a fresh period, the predecessor's numeric edition **+ 1** on `/new-edition`, and `IssueDraftService.nextEdition` is reconciled with the same implementation so the draft dialog and the create cannot propose different numbers. It is frozen at publish like every other issue field. **Consequence worth stating:** a draft is by definition a fresh period, so the draft VO now proposes `"1"` on every series — the client shows a proposed edition only where it is **not** the first one, which is the rule the issue header and the archive strip already use. |
| **2 · the one-off's public window is its own action** | **`OneOffVo` gains `categoryPublish`** (boolean, from `series.category.publish` — the adapter's own gate, so the screen can say "this category is not published" instead of promising a public listing that `PublicationPublicAdapter` would never emit) **and `publicTo`** (epoch ms or `null`) beside the existing `publicFrom` / `active`. **`PUT /one-off-publications/{seriesId}` with `active: true`** on a publication whose *only* closed state is an expired public window is **refused with `409 PUBLIC_WINDOW_CLOSED`**, whose body carries `publicTo`: the dot toggles the series and the issue, and re-opening the public site is a different decision that must not ride along on it. **New: `PUT /one-off-publications/{seriesId}/public-window`**, body `{ publicTo: epoch ms \| null, version }` — tier **AD**, `@DomainScoped`, `@VersionChecked` (**the series' `version`**, as §"`version` on the wire" already rules for every one-off write: one revision covers series and issue). It sets or clears the end of the one-off's issue window, `publicWindowSource = MANUAL`, **`ONE_OFF` series only**; refusals are `SERIES_NOT_FOUND` (404), `ISSUE_NOT_FOUND`, and **`409 NOT_ONE_OFF`** for a real series of the wrong kind — a cadenced issue's window is closed by the issue that succeeds it, so clearing an end there would hand the public site two current editions. It writes a **`VISIBILITY_WINDOW_CHANGED`** audit entry and answers the refreshed `OneOffVo`. A `null` `publicTo` means open-ended, the shape four of the five archived one-offs already have, and it is the value that puts an expired publication back on the site. |
| **3 · the issue trail is newest first** | I11 and the workbench `audit` answer `ORDER BY created DESC, id DESC` — see §3.11. The series history (I33) is unchanged; the two panels now agree. |
| **4 · frontend-only** | The series header's "nothing public" line splits in two: **`publicNowNone`** ("Nothing public right now") for a series that has **never** published, and **`publicNowNoneHistoric`** ("No active public right now, only historic publications") where every published window has closed — the old single sentence claimed history the computed never checked, and it was equally the sentence a brand-new series got. The `publicNow` computed also gates on the series' category `publish` flag, mirroring `PublicationPublicAdapter.newHalf`'s `c.publish = TRUE`, so the card cannot name an issue as public that the adapter would never serve; the flag is read lazily per series and a category that answers nothing leaves the rule as it was. |
| **5 · a withdrawal and its replacement are one release** (ruling `A-iv` amended) | The false "NtM Week 1 (2026) missing" row: on 31 December 2025 the weekly went out with the previous year's title and tag, was withdrawn six days later and replaced by an issue back-dated three hours after the original. The importer paired a withdrawal with its replacement only when released within five minutes, so the replacement was chained off the withdrawn issue with a three-hour content period, and gap synthesis — which drops RETIRED issues from coverage (the batch of nine, item 12) but kept the chain link — reported one missing week. **Import rule:** two rows are siblings when released within five minutes **or** when the earlier row is `INACTIVE` and the later one was released strictly inside the earlier row's public window. Measured over the estate: 12 sibling pairs in all, the 4 newly matched all genuine withdraw-and-replace pairs (weekly-ntm w1/2026, weekly-ntm-p-t w1/2026, firing-practice-areas 2020, efs-a 2020); the variant without the `INACTIVE` guard would have collapsed 67 pairs. Takes effect on the next re-import. **Display guard, effective now:** gap synthesis skips a MISSING period when a non-retired issue's effective cut-off falls strictly inside it, so a period re-covered by a published replacement is never reported missing — also for native withdraw-and-retro-create. P&T showed no row only because gap detection is gated off for in-force series; its chain carried the same defect. |
| **6 · the cadence-less cut-off has a ceiling** | The 2023 ice-service edition sorted after 2026 because its cut-off was recovered from the withdrawal write made 47 seconds after the 2026 edition went public; the cadence-less branch of the cascade had no upper bound. Rule and measured blast radius (13 NM Annex rows) in [`CUTOFF-MODEL.md`](CUTOFF-MODEL.md), amendment 2026-09-09; takes effect on the next re-import. |
| **7 · a shared orphan series is named by ruling** | A series assembled from template-less legacy rows took its name, file-name pattern and link pattern verbatim from its newest member's title, so the edition's year landed in the series name. The placement ruling that groups the rows now carries the per-language name — `nm-annex-ice-service`: "Meddelelse fra Marinestaben om istjeneste samt om ismeldinger m.m." / the same with " (Danish Only)"; `danish-list-of-lights`: "Dansk Fyrliste" / "Danish List of Lights" — the year moves into `nameSuggestionPattern` and `fileNamePattern` as the year token, and a per-issue repository link is copied into a series `linkPattern` for external-link series only. Two series, twelve issues; nothing public depends on a series name. Open: the Danish List of Lights link pattern still names the 2022 PDF. |
| **8 · frontend-only, continued** | **3** — the one-off screen leaves for the dashboard on a desk switch exactly as the series screen does (the one-shot mark), keeping the deep-link carve-out and the other-desk note. **7** — the one-off card names which of series status, issue status and public window is off, renders a closed window with both ends, offers "Re-open on the public site" instead of a toggle that did nothing, and for a category that is not published says the publication is not on the public site, is reached from citing messages, and that deactivating governs whether new messages may cite it (`categoryPublish` read tri-state: absent keeps today's sentences). **E2E** — the series card carries `data-testid="series-card"`; the publications specs locate the card and its title link by it and assert the link's address before clicking. |

## Amendments — 2026-09-10 (one-off visibility: status, public period, category)

*Ruled by Rasmus on 2026-09-10 after the round-four card exposed the fold: a one-off's "Active" dot combined three facts and read a closed public window as "not active", the opposite of what the public site does with it. One-offs only; series issues keep their derived windows.*

| Item | Amendment |
|---|---|
| **The model** | A one-off publication is described by three independent facts, never by a boolean. **Status** — Draft (issue OPEN), Published, Withdrawn (issue RETIRED): withdrawn is gone from the public site, history included, while the file stays at its link so citing messages keep resolving. **Public period** — `publicFrom` (required once published) and optional `publicTo`, hand-set (`publicWindowSource MANUAL`), both editable, a future start allowed so a one-off can be prepared and go public on a set day. **Category** — the publication category's `publish` flag: not public is never on the public site and is reached from citing messages only. The public API already implements exactly this (`PublicationPublicAdapter.newHalf`: PUBLISHED, category `publish`, window overlapping the requested range; RETIRED excluded). The screens derive one line from the three — "Not public (<category>)", "Withdrawn", "Not published yet", "Public from <from> (scheduled)", "Public <from> to <to> (historic)", "Public <from> to <to>", "Public since <from>" — and the word "Active" leaves the one-off screens. |
| **`PUT /one-off-publications/{seriesId}/public-window`** — widened | Body **`{ publicFrom: epoch ms \| null, publicTo: epoch ms \| null, version }`** (AD, owner domain, version-checked against the series revision, ONE_OFF only). `publicFrom` may be null only while the issue is OPEN (`400 PUBLIC_WINDOW_INVALID` for a published one-off without a start); `publicTo` optional; `publicFrom` not after `publicTo` (`400 PUBLIC_WINDOW_INVALID`); a future `publicFrom` is allowed. Sets both ends, `publicWindowSource = MANUAL`, audit `VISIBILITY_WINDOW_CHANGED` with `{ fromBefore, fromAfter, toBefore, toAfter }`; a no-op when nothing changes. Response = the refreshed `OneOffVo`. |
| **Publish keeps a prepared window** | Publishing a one-off whose issue already carries a hand-set `publicFrom` keeps that window (a MANUAL window is never overwritten by the publish stamp); with no window set, publish stamps `publicFrom` at the publish instant as before. A scheduled one-off is therefore Published, absent from today's public list and present for a range covering its start. |
| **`OneOffVo`** | Carries `issueStatus`, `issueVersion`, `publicFrom`, `publicTo`, `categoryPublish` and `categoryName` (request language) on the list and the detail alike. `active` stays one more release, deprecated, with no frontend consumer. |
| **Status transitions go to the ISSUE, not to the save** — *corrected 2026-09-10* | Withdraw = **`PUT /rest/publication-issues/issue/{issuePublicId}/retire?reason=&version=`**; Publish again = **`…/reactivate?version=`**; releasing a draft = **`PUT …/publish`** with `{ acknowledgedWarnings: [], version }`. **NOT** the one-off update with `active: false` / `active: true`, which the earlier wording specified. That endpoint reads `active` as a *transition* worked out from `isActive` — series ACTIVE **and** issue PUBLISHED **and** the public window open at this instant — so for a one-off that is Published but *scheduled* (future `publicFrom`) or *historic* (expired `publicTo`) the requested `false` equals the computed `false`, `activeAction` returns `NOTHING`, and the call answers **200 with the row unchanged**: the dialog closes over a publication that is still published. The same fold leaves a Draft (series ACTIVE + issue OPEN) with no reachable transition at all. The issue endpoints act on the status alone, refuse what they cannot do, and version-check against **`issueVersion`** (the series revision stays the token for the save and for the public window). The one-off save keeps carrying `active` only because the field is non-optional: the editor derives the value that changes nothing (`transitionThatChangesNothing`), so a rename stays a rename. |
| **Frontend** | The one-off card shows the three facts with the derived line as its headline; actions are "Publish" (draft that is `publishable`), "Withdraw from the public site" (reason), "Publish again" (withdrawn only) and "Edit public period" (a dialog with both ends through the house pickers; start required when published, end optional, end not before start, a future start marked as scheduled). The dashboard one-offs table replaces the dot with a **Public** column carrying the compact derived line, sortable by period start. The editor carries no status toggle: status changes are actions. `PUBLIC_WINDOW_INVALID` is mapped to a sentence. |

## Amendments — 2026-09-10 (round six: the expected public window, firing areas everywhere, the Danish accepted)

*Seven findings from Rasmus on 2026-09-10; niord and niord-app commits of the same day. Items 1, 3, 4 and 6 are frontend-only.*

| Item | Amendment |
|---|---|
| **1 · an OPEN issue has no public window — it has a projection** | The series overview showed "NtM Week 36" with a public period of 2–9 September although nothing had been released: the previous system pre-created next week's publication with a nominal window, the import copied it, and the import also capped week 35 at that nominal start. In the new model the public window is stamped by publish (`publicFrom` = the cut-off, the predecessor capped at it), so before publish there is nothing stored — only what publish **will** stamp. **Import (W2):** an issue translated to OPEN receives no public window; a PUBLISHED issue is never capped by an unreleased successor (`publicTo` stays open-ended). **Wire (W1):** the series issue list rows (`SystemPublicationIssueVo`) gain **`expectedPublicFrom`** / **`expectedPublicTo`** (epoch ms, nullable), filled for display only: an OPEN issue of a cadenced series expects `[planned cut-off, planned cut-off + one period)`; the PUBLISHED issue whose next issue is OPEN expects its end at that successor's planned cut-off, whatever the stored `publicTo` says (that is the value `capPredecessor` stamps). Cadence-less and synthesized rows carry none. The Public period column renders the expected values through the expected-date marker, the stored ones otherwise. Existing test data keeps its imported windows until the next re-import; moving a planned cut-off now moves the expected window with it. |
| **2 · the firing practice areas are cited from every domain** (Rasmus) | Import ruling: `firing-practice-areas` availability **`ALL_DOMAINS`** (owner stays `niord-fa`). DOMAIN-OWNER-AVAILABILITY §3 row updated. Takes effect on the next re-import; an admin can set it in the series editor on test meanwhile. |
| **7 · the Danish proposals are accepted** (Rasmus) | Every `ai_`-prefixed value in `da.json` was accepted on 2026-09-10 and the prefix removed. From now on accepted Danish carries no prefix and a **new** proposal carries `ai_` until accepted; DANISH-TODO.md stays as the record of what was proposed. |
| Frontend-only | **1a–d** — the message editor's insert-publications picker lists the publication name only (the filter panel keeps status, series and date); the picker dropdown is at least as wide as its field and grows to its content instead of cropping; a search in flight shows the spinner instead of "No results found". **3** — the dashboard's cell legend between the filter and the New button is removed with its seven keys. **4** — the issue history card starts collapsed and expands on its header line. **6** — the New edition (and the other issue menu) labels follow a language change (built from translate signals, as the sibling screens already did). |

## Amendments — 2026-09-10 (round seven: a cadence opens its own next issue)

*Four findings from Rasmus on 2026-09-10 after the first releases on test; niord and niord-app commits of the same day.*

| Item | Amendment |
|---|---|
| **1 · a cadence means automatic** | Week 38 never opened on the NtM tt weekly, and Active P&T opened nothing either. Two causes met: every series carried `nextIssueCreation = MANUAL` — the new-series template ships it (correct for its own cadence of NONE) and nothing re-derived it once an admin picked a frequency, while the import set it on every series deliberately — and `createSuccessorIfDue` additionally required a **tiling** time relation, which an in-force series can never satisfy. **Rule (S-8, both halves under the one id): the cadence decides who opens the next issue.** A cadenced series is `AUTO_ON_PUBLISH` and a cadence-less one is `MANUAL`; the validator refuses the other pairing, `updateFromVo` normalises a stored value so an existing row heals on its next save rather than being refused, the same normalisation runs before the activation check so a status flip cannot be blocked by it, and the import writes `AUTO_ON_PUBLISH` for a cadenced series. **`createSuccessorIfDue` drops the tiling clause:** the gate is cadence + ACTIVE + automatic, whatever the time relation. A tiling successor is unchanged (it opens at the stamp); a **non-tiling** successor is created with **no period start** and its end pinned at the stamp plus one cadence period (`NOMINAL`), which is the shape an in-force issue has by design. The guard refusing to mint while another issue is open, or while one is stamped later, is untouched. The editor no longer offers the control at all: it states the answer the cadence gives, in both directions. |
| **2 · the publisher is named** | `PublicationIssue.toVo` put the login on the wire where every sibling mapping puts the display name, so a release line read as an account id while the history two cards below it read as a person. One shared helper now answers "who did this" for the three actor sites (the trail, a curation decision, the release), falling back to the login only when a user row carries no name. No wire shape change; API-CONTRACT §3.6 already specified the name. |
| **3 · a double week carries both numbers** | An in-force issue has no period start, and the second week number was derived from the span between start and cut-off — so Active P&T could never carry one, whatever its interval said. The shaping now hands the naming a **span start**: the issue's own period start where it has one, the predecessor's effective cut-off where it has none. The threshold and every existing rule are unchanged, and the printed form stays `week+weekTo` ("36+37") for a pair (three weeks or more print as a range, "36-38", since 2026-11-20), which the strip, the archive and the issue header already agreed on. Scope, stated deliberately: the span is measured in weeks, so this reaches weekly series only — a monthly or yearly double period has no column to express it. |
| Frontend-only | An **open-ended public window** now reads as one phrase ("Offentlig siden 9. sep. 2026") in the archive column and the header line, instead of a date followed by a dash; both route through the shared visibility rule the one-off screens already use. An **upcoming period** offers the create action when the series has no open issue, so a series that fell behind can be caught up by hand. The **next-issue control** is gone from the series editor in both directions — a cadenced series states that it opens its own, a cadence-less one that each issue is made by hand. |
| Not changed | Publish's window handling stays exactly as it was (Rasmus, 2026-09-10): `openWindow`, `capPredecessor` and `capSelfAgainstSuccessor` are byte-identical. The Active P&T issue on test therefore keeps the closed window its import copied, until that data is imported again. |

## Amendments — 2026-09-10 (round nine: what the tailoring does not need)

*Seven findings from Rasmus on 2026-09-10, the first working session with the tailoring drawer in
front of him. Six are subtractions. niord and niord-app commits of the same day.*

| Item | Amendment |
|---|---|
| **1 · a draft names its predecessor** | The New issue dialog rendered `IssueDraftVo.chainedFromPublicId` raw, so "Chained from" was followed by an identifier. `IssueDraftVo` gains **`chainedFromName`**, the chained-from edition’s name in the REQUEST language, resolved through `IssueListService.nameOf` — the one localised-issue-name resolver, widened to package-private rather than copied. The endpoint `GET /publication-series/series/{id}/issue-draft` gains **`?lang=`**, following the convention `members`, `workbench`, `picker` and `recent` already use, and the client sends the active language. The public id stays on the wire, because a link to that issue is built from it. Where the predecessor has no name in any language the field is **null and the row is not rendered at all** — there is no fallback to the id, since the id on screen is the defect. |
| **2 · the second-week label is withdrawn** (amends round eight's model, one day old) | `weekToLabel` shipped in round eight and is removed end to end: the entity column, `SystemPublicationIssueVo`, `IssueEditService`’s `IssueEdit` record, `PrintedNumbering`, `UpdateIssueRequest`, the frontend model and the drawer control. `IssueNaming.Labels` loses its `weekTo` component with it, which is the positive statement of the rule: **`${weekTo}` is always the derived number now.** Flyway **V16** drops the column, idempotent by the V15 procedure idiom. `printedWeekTo` is KEPT and answers from the derived `weekTo` alone, so the report parameter and the drawer read-back are unaffected. **What did not change:** an in-force issue spanning two weeks still prints "36+37" by itself, from the derived pair and the span start the shaping hands the naming (round seven). A person who wants a different label types the whole thing into the WEEK label, which is free text. |
| **3 · the criteria source badge is gone** | The criteria section wore a pill reading *Tailored for this issue* / *From the series*, rendered twice (collapsed summary and expanded section). Both are removed and the two keys with them. `criteriaOverridden` itself is untouched — it still drives the tailoring notice and the edit affordance. |
| **4 · the Document card keeps only the parameters** | Of the four tailoring facts the card listed — file name, numbering, report, report parameter — the first three are removed. The count sentence above them now counts **tailored report parameters**, which is exactly what the card renders, and its wording says so. Consequence, stated deliberately: the header mark still asks the whole-issue question, so an issue tailored only in its file name wears the mark and shows no facts. The way into the drawer is unchanged. |
| **5 · the file name is a value, not a placeholder** | The drawer showed the resolved suggestion as placeholder text, so adjusting it meant retyping it. It is now a real value in the box, following the convention the name field already used: prefilled with the suggestion, still FOLLOWING while its text equals the current suggestion (so it moves as the numbering is typed), an override the moment it differs, handed back by clearing it. "Follow the series again" restores the suggestion **without pinning it**. |
| **6 · no copy icon in the series issue table** | The rightmost column carried a copy-the-document-URL button beside the create action. The button is removed from this table; `app-copy-link-button` stays everywhere else it is used. |
| **7 · a member’s status wears the status colours** | The member rows rendered a bare `status-badge` with no state class, so every message status was grey while the same statuses are colour-coded everywhere else in the app. It now takes the standard message-status class. |
## Amendments — 2026-09-10 (round eight: what one edition says differently)

*The per-issue overrides drawer, designed on a canvas and approved by Rasmus on 2026-09-10, plus the defect it was diagnosed beside. niord and niord-app commits of the same day.*

| Item | Amendment |
|---|---|
| **The model** | One drawer on an OPEN issue holds everything the series decides that this edition needs to say differently: its printed numbering, its name per language, its file name per language, the report and the values that report fills its headings with. A field left alone follows the series and moves when the series moves; a field touched is marked and stops following. |
| **1 · the printed numbering is a label, and it is free text** | Week, week-to and year are two different facts that shared one column: the PERIOD the cut-off decides, which the machine uses for ordering, the timeline and gap detection, and the LABEL that gets printed. `PublicationIssue` gains `weekLabel` and `yearLabel` (`weekToLabel` shipped here too and was withdrawn the next day — see round nine) (nullable text, no format check beyond a length and a control-character rule — ruled by Rasmus: these carry no number check, so a week may read "36+37" or "36 & 37"). The derived numbers are untouched. One helper resolves what is PRINTED — the label where there is one, the derived number otherwise — and everything that prints goes through it: the name pattern, the file-name pattern, the citation format and the report injection. `edition` was already free text. |
| **2 · the year no longer prints as "2.026"** | The engine never changed; what feeds it did. The previous system substituted the report values as text before the template saw them, while the release put the issue's own numbers into the model as integers — and FreeMarker formats a number with the render language's grouping, so Danish printed 2026 as "2.026". The injection now hands over the printed numbering as text by construction. Every shipped template only interpolates these tokens, so none changed. |
| **3 · the file name is typed, not only derived** | `PublicationIssueDesc` gains `fileNameOverridden`; I8 gains `fileNames` (a map of language to name). One resolution — a hand-set name, else the series pattern expanded with the printed labels, else what the language already carries, else the public id, always ending `.pdf` — now serves the release, the preview and the screen, so they cannot name a file three ways. A path is stripped and the suffix added exactly as the upload endpoint does, the names must be distinct across languages, and a published issue is unchanged (`FILE_NAME_IMMUTABLE`). |
| **4 · the report and its parameters, per issue** | `PublicationIssue` gains a nullable `reportId` — an override of the series value, resolved through one helper used by the release, the preview and the rail row. Report parameters were already per issue on the wire and merged over the series map at render; they simply had no screen. The four injected names stay refused as typed parameters, judged against what a request adds or changes rather than against what an issue already stores. |
| **The clear convention** | For every string-shaped override on I8 — the three labels, the report id, a file name, a name — **absent or null means leave alone** and an **empty string means clear**, so a partial save cannot wipe a field it never mentioned. The criteria override keeps its own flag, because a document has no empty form. |
| **Frontend** | The drawer carries the five sections in the approved order with a per-field mark, a "follow the series again" per section and a reset for everything; there is no standing banner — one quiet line appears only when something is overridden. The issue screen gains the entry point, a mark beside the status, and the file name, numbering, report and tailored-parameter count as facts on the Document card. |

## Amendments — 2026-09-13 (compilation series: a third membership regime)

*A series whose issues hold what another series' published issues already printed, grouped by the issue
that printed each message. The whole of it is one new value of `timeRelation` plus the fields that value
needs; no endpoint is added, no tier moves, and nothing outside the compiled regime changes shape.*

| Item | Amendment |
|---|---|
| **1 · the third time relation** | `TimeRelation` gains **`COMPILED_FROM_SOURCE`** beside `PUBLISHED_IN_INTERVAL` and `IN_FORCE_AT_CUTOFF`, on every shape that carries the field, the issue's frozen `snapshotTimeRelation` included. A series in that regime keeps `contentMode = GENERATED_FROM_QUERY` — it renders a document like any generated series — and its issues **tile**, so the interval, the chain rows, gap detection and the retro-create flow all read as they do for `PUBLISHED_IN_INTERVAL`. What differs is where the members come from: the union of another series' frozen member rows, never a query. A client with a closed vocabulary must add the value; one that switches on two values and falls through will render a compilation as an in-force issue and be wrong about every interval row. |
| **2 · the series names its source** | `SystemPublicationSeriesVo` gains **`sourceSeriesId`** (`String`, nullable): the source series' immutable `seriesId`, never its row id. **ADMIN shape only** — the lean `PublicationSeriesVo` must not carry it, and the contract test's leak list says so. Required exactly where `timeRelation = COMPILED_FROM_SOURCE` (**S-24**); the source must be another series, `GENERATED_FROM_QUERY`, and not itself a compilation (**S-25**). **Write semantics, which differ from `categoryId` and `domainId` on purpose: blank or absent clears it.** Absence cannot mean "unchanged" here, because switching a series off the compiled regime has to be expressible in one save — and S-24 refuses a source on a series that is not a compilation, so a client that omits the field while asking for that regime is refused rather than silently left compiled. An id naming no series is refused with the new code below, never dropped. Resolved in the REST resource and in the interchange import, never inside `updateFromVo`, exactly as the category and the owner are. The `/validate` endpoint resolves it too, or S-24 and S-25 could not be judged before a save; a dangling id there produces **one** S-25 row rather than an S-24 row as well. Series **export** carries it (it is `toVo`), the **copy** template carries it, and the batch import resolves it by `seriesId` and skips the row with a sentence in the log where the installation has no such series — the treatment the category already gets. |
| **3 · `SOURCE_SERIES_NOT_FOUND`** | New catalogued code, **400**. Its neighbour `CATEGORY_NOT_FOUND` is a 404 and this one deliberately is not: the resource being addressed exists and the save is well-formed — one *value inside the body* names nothing. A 404 would tell a client the series it is saving is gone, which is the opposite of what happened. |
| **4 · the issue records what it compiled** | `SystemPublicationIssueVo` gains **`snapshotSourceSeriesId`** (`String`) and **`snapshotSourceIssueIds`** (`List<String>` of issue `publicId`s, in cut-off order). Written at freeze on a compiled issue, null on every other regime, and the answer to "what did this annual actually cover" — which cannot be re-derived, because a source issue retired or published afterwards moves the union. The list comes from the survey, not from the frozen rows: a source period that contributed no member is still named. Its invariant is **I-20**. These two fields are where a released compilation's record lives, and the `sources[]` below is rebuilt from them once the issue is frozen — one record read two ways rather than two answers that can disagree. |
| **5 · the member says which issue printed it** | `IssueMemberVo` gains **`sourceIssue`** — `{ publicId, seriesId, week, weekTo, year, cutoff, name } \| null` — on both halves of the member list: from the frozen `sourceIssuePublicId` on a FROZEN list, from the live derivation on a LIVE one. **Null for a manual include and on every non-compiled issue**, so a client renders the grouped list off its presence alone. The name is read live in the requested language, in one query per list, and where the source issue no longer exists the row carries the `publicId` alone with every other field null — the compilation's record survives the deletion of a row in another series. `membership` gains **`COMPILED`** and the derived reason gains **`FROM_SOURCE_ISSUE`** ("its source issue printed it"), which is an automatic membership like a criteria match, not a curation. |
| **6 · the workbench (I30) on a compilation** | Gains **`sources[]`** — `[{ publicId, seriesId, week, weekTo, year, cutoff, status, memberCount, name, intervalFrom }]` — one row per source-series period this issue covers, in cut-off order. While the issue is **OPEN** it is the live survey: the PUBLISHED issues that contributed, the **OPEN** ones that contributed nothing, and synthesised rows for a stretch with no issue at all, whose `status` reads `MISSING` and whose `publicId` is null. Once the issue is released it is the same list rebuilt from the frozen header (item 4), in the frozen cut-off order the union was taken in — **every row `PUBLISHED`**, because only a published issue ever contributed; `memberCount` counted off the frozen rows and **`0` where a source period printed nothing**; and a source issue deleted since keeping a row carrying its `publicId`, `seriesId`, status and count and nothing else, exactly as a member's `sourceIssue` does in item 5. Present on **every issue of the compiled regime**, and **absent on every other regime**, where the question does not apply. So three states stay distinguishable: no survey to show (absent — another regime), a compilation whose period covers no source issue at all (empty), and a compilation that covers some (rows). A client draws the sources panel from the PRESENCE of the field, never from the regime: drawn from the regime, an issue answered by a server that carries no survey would be reported as a year holding no source issue at all. The consequence for a released compilation is that the survey, not the member rows, is the record of what it covered — a week that printed nothing has no member row and is part of the record all the same. **`omissions` is absent** for a compilation — there is no candidate set to reject, so there is nothing to omit — and **`lateAfter` is null with `afterPlannedCutoff` empty**, because a member's own publish date says nothing about whether the issue that printed it fell inside the window. `viewedFrom` echoes the period start as on any tiling issue, and `?at=` keeps its meaning: the union as of that instant, so a running annual can be looked at mid-year. |
| **7 · the rail has fifteen rows** | **`SOURCE_ISSUES_COMPLETE`** is appended last: `WARN`, **acknowledgeable**, acknowledge code **`SOURCE_ISSUES_INCOMPLETE`** — a resolver warning code like `CANCELLED_BUT_DATE_ALIVE`, so the publish gate refuses an unacknowledged one through the mechanism that already exists. It passes when every source period inside the compilation's period has a published issue; it warns when one is still open or uncovered, which is what happens if the annual is released in early January before the last week is out. Detail codes: **`SOURCE_ISSUES_COMPLETE.complete`** `{count}` and **`SOURCE_ISSUES_COMPLETE.incomplete`** `{count, openCount, missingCount, openWeeks: ["51/2023", …], missingFrom: [epoch, …], missingTo: [epoch, …], zone}` — `openWeeks` names the weeks, while an uncovered stretch is bounded by two cut-offs and has no week number of its own, so it is given as two parallel epoch lists read pairwise in `zone`; the client reads `missingFrom` and `missingTo` pairwise and renders them as one list of stretches, never as two lists of instants. Not a compilation → not applicable, reason `NOT_COMPILED`. |
| **8 · two rail rows are not applicable to a compilation** | `MEMBER_LIMIT` → not applicable, new reason **`BOUNDED_BY_SOURCES`**: the ceiling protects against a runaway query, and a union of issues the engine already published one at a time cannot be one — seven of the nine years in the estate exceed 1,000 members. `CANCELLED_MEMBERS_ALIVE_AT_CUTOFF` → not applicable, new reason **`JUDGED_AT_SOURCE`**: liveness was decided at each source issue's own cut-off, and re-judging it at the compilation's would drop what those issues rightly carried. It **keeps** its `acknowledgeable` flag and acknowledge code either way, as the inapplicable-row rule already requires. `MEMBERS_RESOLVED`, `NO_INEFFECTIVE_OVERRIDES` and `OVERLAPPING_ISSUE` apply to a compilation as to any tiling series. |
| **9 · the probe refuses a compiled relation** | `resolve-preview` takes a time relation and answers what a criteria document would select. A compilation has no predicate to preview, so a `COMPILED_FROM_SOURCE` probe is refused with the existing coded refusal for an unsupported relation rather than answered with an empty set. The client hides the probe panel for the regime and shows the source picker in its place; the **sources panel** on the issue screen is the compiled counterpart of the probe. |
| **10 · the rendered document is grouped** | The render request gains an ordered **`groups`** list — one per source issue in printed order, each naming its messages, plus a trailing group for manual includes — and it is null for every other regime, so a template that does not read it renders exactly as before. A section is discovered from the **ordered member list**, so a source period that contributed no member prints no heading; what the year *covered* is the issue header's `snapshotSourceIssueIds`, what it *printed* is `groups`. Not a wire shape: the request never leaves the server. Stated here because it is the one place the two lists differ, and because the document a reader downloads is the contract's real output. |
| **11 · curation is unchanged, and that is a decision** | Include and exclude apply to a compilation exactly as to a query-backed issue (an exclude drops a row the year would otherwise carry; an include adds a message no source printed, listed last and in its own section of the document). An include naming a message a source issue **already** printed is refused as already a member, the same refusal a criteria match earns — the compiled derivation is an automatic membership, so the two must answer alike. A `criteriaOverride` on a compilation is refused: there are no criteria to tailor. |
| **12 · `SERIES_IS_COMPILED`** | New catalogued code, `SERIES_IS_COMPILED` → **409**, raised by S11 (`DELETE /publication-series/series/{seriesId}`) when another series names this one in `sourceSeriesId`. The message names every publication that depends on it, because "something compiles this" leaves an admin searching the estate by hand. Why 409 and not 400: the request is well formed and the identical one succeeds once the compilation is pointed at another source or deleted first, so what stands in the way is the state of another row and not a value inside this request — the same argument that puts `SERIES_HAS_ISSUES` in the 409 group. Judged before the delete runs, so the refusal arrives as a sentence naming the dependent publication; left to the flush it reaches an admin as a constraint violation naming a column, on a screen whose only other option is to retire the series. |
| **13 · the export lists sources before compilations** | **S16** (`GET /publication-series/export`) emits every series carrying no source first, then every series that compiles one, and the order the query established is preserved inside each tier — so an estate with no compilation exports exactly the bytes it always did. Two tiers are the whole dependency graph, because S-25 forbids a chain: a compilation's source is never itself a compilation. The guarantee is what makes an export → import round trip keep a compilation. THE FILE IS READ IN THE ORDER IT IS WRITTEN and the importer resolves `sourceSeriesId` against what the installation already holds, so a compilation written ahead of its source arrives before there is anything to resolve — and the estate's own ids sort precisely that way (`accumulated-yearly-ntm` before `weekly-ntm`), which made plain alphabetical order the losing one. The batch importer is unchanged: a row naming a source this installation does not hold is dropped with a line in the job log naming the id and saying to import the source first, the treatment an unknown category already gets. |

**What does not change.** No endpoint, no path, no tier, no permission. The public adapter is untouched — a compilation is an issue of a `MESSAGE_REPORT` series in a published category, listed by its window like any other, and the source-issue list reaches the public only through the PDF. The issue picker, the message editor's citation, `?publication=`, the archive and the audit vocabulary are all unchanged.

## Amendments — 2026-09-14 (a release or an amend in flight is the issue's own fact)

*A publish and an amend each render the document again, per language, and take tens of seconds on a
compiled annual — the request outlives the dialog it was pressed from. Held in the browser that pressed
the button, that state reached exactly one reader: a refreshed page, a second tab and another admin all
saw an idle issue while the document was being written, and were offered the button that would write it
a second time. The work is a fact about the ISSUE, so the issue is where it is recorded.*

| Item | Amendment |
|---|---|
| **1 · `inFlight` on the workbench (I30)** | The response gains **`inFlight { action: "PUBLISH" \| "AMEND", since: epoch ms }`**, optional. Present exactly while such work is running on the issue and younger than the server's own ceiling; **absent when the issue is idle**, and absent again the moment the request ends. Absence is the statement that nothing is running, so a client carries the field through as it arrived and never substitutes a value for a missing one. `action` is named rather than a bare flag because the two are shown in different places — a release is the publish control, an amend is the document list it is about to replace — and `since` is what lets a screen say how long the work has been going without reading its own clock against a server stamp it does not have. A server older than the field sends nothing, which reads as idle: the same answer every reader got before. |
| **2 · the ceiling, and why the record cannot outlive the work** | The server clears the record when the request ends, whether it committed or threw, and **treats a record older than its ceiling as stale** — it stops reporting it, and a release or an amend is accepted again. Without that second rule a process killed mid-render would leave an issue no admin could ever publish, with no verb anywhere to release it; a state that can only be entered and never left is not a state this model has. |
| **3 · `ISSUE_BUSY` on I16 and I17** | New catalogued code, **409**, raised by publish and by amend while such work is running. Body `{ code: "ISSUE_BUSY", message }` like every other coded refusal, with `details` carrying the running `action` and its `since`. 409 rather than 400 because the request is well formed and the identical one succeeds once the running work has finished — what stands in the way is the state of the row, the same argument that puts `ISSUE_ALREADY_PUBLISHED` in the 409 group. It is refused **before anything is frozen or archived**: two renders over one issue would write the same per-language files at once, and the loser would overwrite the winner's. |
| **4 · what a client does with it** | The screen reads its busy indicators from **its own action OR `inFlight`**, so the spinner on the release control, the sentence on the document card and the disabled submit inside both dialogs stand for a reader who did not press anything. Every guard that refuses a second action reads the same pair, so the dialogs do not open while the work is running. A client that did **not** start the work has nothing to wait on — the fact lives on the issue and nothing pushes it — so it **re-reads the workbench every five seconds while `inFlight` stands**, and stops on the first answer that carries none. A client that started the work does not poll beside its own request: its response is what tells it the work is over, and a re-read landing mid-publish would redraw the screen from a transaction that has not finished. Meeting `ISSUE_BUSY` triggers one workbench re-read, which is what fetches the fact the refusal is about — the sentence alone would leave the page looking idle beside a message saying it is not. |

**What does not change.** No endpoint, no path, no tier, no permission, and no other shape. `inFlight` is
one optional field on one read, and the refusal is one code on two writes that already refuse for a dozen
other reasons.

## Amendments — 2026-11-13 (the error hunt's fix stage)

*The error hunt of 2026-11-12 found faults in the publications backend, and its fix stage of 2026-11-13 fixed
them. Most fixes changed behaviour behind an unchanged wire; the rows below are the ones a client of this
contract can see.*

| Item | Amendment |
|---|---|
| **1 · I24 upload on an issue that is not OPEN** | On a **RETIRED** issue, an upload under the document's own file name replaces it in place and archives the old file first, audited **`FILE_REPLACED_MANUALLY`** with the archive path, as on a PUBLISHED issue; under a new name the address moves and the released file stays where it is. On any issue that is not OPEN, an upload that is not in place, under a name whose file already exists, is refused with **400 `FILE_NAME_TAKEN`** before a byte is written: a released document is never overwritten by a second upload. 400, as `FILE_NAME_IMMUTABLE`: the same request never succeeds later. Catalogue row: Files, beside `FILE_NAME_IMMUTABLE`. |
| **2 · I21 preview at an instant** | `POST /publication-issues/issue/{publicId}/preview` takes an optional body **`{ at }`** (epoch ms). The members are resolved over (period start, `at`] and the preview's file name is the one the issue would have at `at`; an `at` after now is taken as now; one at or before the period start, or not a number, is **400 `INVALID_INSTANT`**. No body keeps the old behaviour (the series' default cut-off). The admin app sends the instant on screen: the planned cut-off in the planned view, now in the as-of-now view. |
| **3 · I6 by-message: the membership values** | Each row's `membership` is **`FROZEN`** (a member of a released issue, recorded at the release), **`LIVE`** (an OPEN issue would hold it if it closed now) or **`MISSED`** (new): a released issue whose period holds the message's publish date, and which went out without it. A VERIFIED message is judged as if it were published. A client that does not know a value draws the row as neither frozen nor live. |
| **4 · I30 `publishedSinceRelease`** | The workbench gains **`publishedSinceRelease`**, a list of `{ messageUid, messageId, title }`: the messages published into a released issue's period after its release. Present only on a PUBLISHED issue of a tiling query series whose membership was not imported; empty there when nothing was missed; absent otherwise. An amend is still the only way to take such a message in. |
| **5 · `LINK_INVALID`** | New catalogued code, **400**: a publication's link must be an address a reader can follow: an absolute `http` or `https` address with a host, or a path under `/rest/repo/` (a file the repository hosts); a `javascript:` address, another scheme, plain text and a double quote are refused, and a blank link clears it. A link already stored is checked only when it is saved again. Raised by `PUT /publication-issues/issue/{publicId}/link/{lang}` and by the one-off resource's create and update. |
| **6 · New edition, one open successor at a time** | A repeated New edition while an edition superseding the issue is OPEN answers with that edition and writes no audit entry, instead of making another one. |
| **7 · `STALE_VERSION` from a race at the database** | Two saves of one revision arriving together: the one that loses at the database is answered **409 `STALE_VERSION`** (was 500). That refusal carries no `storedVersion` or `submittedVersion`; the refusal of a stale body read before the write still does. |
| **8 · C-4 at activation and in the series import** | The status transition to ACTIVE (S10, and a reinstatement from RETIRED) asks the activation rules with the real operand resolver, so a criteria operand that names nothing refuses the activation, as the series save already did. The series import (`POST /publication-series/upload-series`) asks C-4 on every row: a row with an operand that names nothing is skipped with a sentence in the job log, and over an ACTIVE series the activation rules are asked too. |

**What does not change.** No endpoint, no path, no tier, no permission. Item 2 adds an optional body to a
write that had none, items 3 and 4 add a value and an optional field to two reads, and items 1 and 5 add two
catalogued 400s.
