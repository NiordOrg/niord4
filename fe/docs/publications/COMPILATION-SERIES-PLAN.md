# Compilation series — implementation plan

> **IMPLEMENTED — read this banner before the body.** The regime described below is built, in both
> repositories and in the client. This document is kept for the design reasoning that exists nowhere
> else — why the seam is the time relation rather than a new criterion kind or a new content mode — and
> for the measured estate table. **The rules as they shipped are in
> [`domain-rules.md`](domain-rules.md), [`DATA-MODEL.md`](DATA-MODEL.md) and
> [`API-CONTRACT.md`](API-CONTRACT.md), and those win wherever this plan disagrees.**
>
> **What shipped differently from the text below:**
>
> - The schema change is **`V18__compilation_series.sql`**, not V14 — three other migrations landed first.
> - `TimeRelation` carries exactly two derived answers, `tiles()` and `compiled()`. There is no
>   `hasLowerBound()`: it would be the same set as `tiles()` under a second name.
> - The release rail has **fifteen** rows. The new one is `SOURCE_ISSUES_COMPLETE`, and the warning the
>   publish gate refuses unacknowledged is `SOURCE_ISSUES_INCOMPLETE`.
> - The fork the derivation hangs off is a named regime — one answer to "what decides this series'
>   members", replacing five separate booleans that could drift apart — rather than a second factory
>   beside the curated resolution.
> - §2.1's call-site list was written before gap detection stopped consulting the relation, and it was
>   re-derived from the code rather than followed: the gate keys on cadence, status and the next-issue
>   setting, and the relation only sets the tiling flag that says how coverage between two releases is
>   read.
> - The nine retroactive years and the source-drift banner (§2.8) are still ahead, and are recorded in
>   [`../backlog.md`](../backlog.md) rather than here.

*Draft 2026-09-09. Rasmus's thought experiment of the same evening: a yearly publication whose member list is the union of what the year's weekly NtM issues actually contained, so the PDF reads like the weekly PDFs stapled together. Scouted against both repos (four read-only readers, 208 facts with file:line) and measured on test. Four decisions taken with Rasmus the same evening are recorded in §0. Written before anything was implemented; the banner above says what shipped and where the rules now live.*

---

## 0. What was decided, and what is assumed

**The target already exists.** `accumulated-yearly-ntm` ("Akkumuleret Årlig EfS" / "Accumulated Yearly NtM", category `dk-dma-weekly-nm-publications`, owner `niord-nm`, `OWNER_ONLY`) is imported today as an **uploaded-PDF** series: 16 PUBLISHED issues 2002–2016 and a RETIRED 2017, all `NO_MEMBERSHIP`, the legacy file for 2018 literally named `2018 Akkumuleret 1-52.pdf`. The legacy system produced these by hand. This plan makes the series **generated**, by compiling the weekly NtM.

**Decisions (Rasmus, 2026-09-09):**

| # | Decision | Ruling |
|---|---|---|
| K1 | Where the compilation lands | **Convert `accumulated-yearly-ntm` in place.** Same seriesId and category; the 16 uploaded issues keep their files untouched. Done as an **import ruling** so every rehearsal and the go-live import produce the converted series. |
| K2 | The retroactive years 2017–2025 | **By hand after go-live.** Each missing year shows on the strip as a gap; an admin creates it from the gap, reviews the compiled list, publishes with the 31 December cut-off. Nine sessions, one review per year, nothing new inside the go-live window. |
| K3 | The PDF | **One cover, a heading per week.** One front page for the year, then each source week as a section headed by the weekly issue's name, its week number and cut-off date, messages in the week's own frozen order. |
| K4 | Curation | **Exclude and include allowed**, audited with a reason as on any issue. A manual include has no source week and is listed in a final section of its own; an exclude removes the row from the year. |

**Assumptions (mine; say so if any is wrong):**

- **Source issues are PUBLISHED only.** A RETIRED weekly is the statement that what went out for its period should not stand (ruled 2026-09-08: a retired issue no longer covers its period); its replacement, once published, is what the annual compiles.
- **A source issue belongs to the year its stamped cut-off falls in.** The weekly's own `week`/`year` numbering is not consulted. The two boundary notices measured on test (in week 1 of both 2025 and 2026) therefore land in the year of the issue whose cut-off is earlier.
- **Duplicates collapse to one row, owned by the earliest source issue.** Measured on test: 14 of 10,224 weekly memberships appear in more than one weekly (13 inside 2019, weeks 15–19; 2 on the 2025/2026 boundary). The member table's unique key `(issue, messageUid)` forces one row anyway.
- **Two PDFs, one per configured language**, as every generated series produces today. The legacy file was one PDF for both languages; that is a legacy limitation, not a requirement.
- **The compilation's member set is the union of the source issues' FROZEN rows.** It never re-runs the weekly's criteria. That is the whole point: it reproduces what went out, curation of the weeklies included.
- **Titles and bodies render live**, exactly as every other issue does (DATA-MODEL §6.5: the archived PDF is the record of what was printed). A 2018 annual rendered in 2026 prints the 2018 member list with each message's current text.

**Measured on test (2026-09-09), the estate the plan has to carry:**

| Year | Weekly issues | Unique messages |
|---|---|---|
| 2017 | 50 | 1,067 |
| 2018 | 50 | 1,003 |
| 2019 | 51 | 831 |
| 2020 | 53 | 881 |
| 2021 | 52 | 1,027 |
| 2022 | 52 | 1,086 |
| 2023 | 52 | 1,200 |
| 2024 | 52 | 1,272 |
| 2025 | 51 | 1,063 |

All 498 weekly issues from 2017 on are PUBLISHED; provenance 495 `EXACT`, 6 `UNION_SNAPSHOT`, 1 `EXPLAINED_DIFF`. Seven of nine years exceed the engine's current `MEMBER_LIMIT = 1000` (§2.6).

---

## 1. The design in one paragraph

A compilation is a **third membership regime**, not a criterion and not a content mode. `TimeRelation` gains `COMPILED_FROM_SOURCE` beside `PUBLISHED_IN_INTERVAL` and `IN_FORCE_AT_CUTOFF`, and a series in that regime names a **source series**. Its content mode stays `GENERATED_FROM_QUERY`, so every gate that already asks "does this series produce a document" — the report requirement, the render step, the preview, the checklist's document rows, the public adapter's `MESSAGE_REPORT` type, the frontend's document card — keeps working unchanged. Its issues **tile** like interval issues (a yearly period with a lower bound, chained off the predecessor's stamp), so the shaping, gap detection, the chain rows and the retro-create flow all apply as they stand. What is new is one derivation — *the union of the frozen member rows of the source series' PUBLISHED issues whose cut-off falls inside this issue's period* — produced at the one fork where the engine today chooses between "run the query" and "overrides only", carrying per member **which source issue it came from**, frozen at publish, grouped in the PDF and on the screen by that source issue.

### 1.1 Why not the two alternatives

| Alternative | Why not |
|---|---|
| A new **criterion kind** ("in issues of publication series X") | Criteria are conjuncts over message facts, evaluated by a pure predicate (`MembershipPredicate.decide(MessageFacts, ResolvedCriteria, Interval)`) with a SQL narrowing that must stay a superset of it (the differential test). "Which issues printed this message" is not a message fact; it is a query over `IssueMember`. Modelling it as a criterion would AND a union with filters, need a fake facet, widen the scope rule C-6, and put a second table scan inside a contract built to have none. |
| A new **content mode** (`COMPILATION`) | `ContentMode` is a total bijection onto the public API's `PublicationType`, which has no fifth value; the search adapter inverts that bijection for the message editor's picker; ten-plus `== GENERATED_FROM_QUERY` gates across core, web and the frontend would each need a third arm; S-1 would refuse the time relation the compilation needs. |

The time relation is the right seam: it is already "how the period selects content", it is stored as a native enum that has been widened before (V4's `MODIFY` pattern), and the frontend's regime select becomes the switch.

---

## 2. Backend

### 2.1 Series shape

| Field | Compilation value | Rule |
|---|---|---|
| `contentMode` | `GENERATED_FROM_QUERY` | unchanged vocabulary |
| `timeRelation` | **`COMPILED_FROM_SOURCE`** (new) | `TimeRelation` gains `tiles()` (true for `PUBLISHED_IN_INTERVAL` and `COMPILED_FROM_SOURCE`) and `hasLowerBound()`; every `== PUBLISHED_IN_INTERVAL` that means *tiling* switches to `tiles()` (`IssueShape.shapeInterval`, `IssuePublishService.createSuccessorIfDue`, `PublishChecklistService` interval rows, `GapDetection.gate`, `IssueLifecycleService.assertNoOverlap`, S-4). |
| `sourceSeries` | **new** `@ManyToOne PublicationSeries`, nullable | **S-24**: set iff `timeRelation = COMPILED_FROM_SOURCE`. **S-25**: the source is not this series, is `GENERATED_FROM_QUERY`, and is not itself a compilation (no chains). Both are activation rules like S-1, not HARD_RULES. Wire: `SystemPublicationSeriesVo.sourceSeriesId` (string, the immutable seriesId; resolved in the REST resource and in the batch import processor with the drop-the-row treatment, never inside `updateFromVo`). |
| `criteria` | null | S-1 adjusted: criteria required iff query-backed **and** `timeRelation ≠ COMPILED_FROM_SOURCE`; `EffectiveCriteria.resolvedFor` is not consulted for a compilation. |
| `aliveAtCutoff` | null | S-2 adjusted: set iff query-backed and not compiled (liveness was judged at each source issue's cut-off). |
| `cadence` / `cutoffDefault` | `YEARLY` / `PERIOD_END` | already the declared "accumulated annual" shape (`CutoffDefault.forShape`), passes S-21. Other cadences are legal (a quarterly digest) but out of scope for the estate. |
| `firstIssueStartsAt` | 2017-01-01 00:00 Europe/Copenhagen | S-4 applies (tiles). |
| `reportId` + print settings | `nm-accumulated`, A4 portrait, `mapThumbnails = false` | S-1 third leg, S-9. |

**Migration `V14__compilation_series.sql`:** `MODIFY` `PublicationSeries.timeRelation` enum to add the value; add `PublicationSeries.sourceSeries_id` (nullable FK, index) with the information_schema guard; `MODIFY` `IssueMember.source` enum to add `COMPILED`; add `IssueMember.sourceIssuePublicId varchar(36)` (nullable, indexed); `MODIFY` `PublicationIssue.membershipProvenance` enum to add `COMPILED`; add `PublicationIssue.snapshotSourceSeriesId varchar(64)` and `snapshotSourceIssueIds TEXT`. Boots against the three database shapes V13 names; the backfill is a no-op by construction (no compiled rows exist).

### 2.2 The membership rule (RI-16)

For a compilation issue *C* with period `(intervalFrom, at]`, where `at` is the instant being looked at while OPEN (the planned cut-off, or an earlier `?at=`, clamped to now) and the stamped cut-off once frozen:

1. **Sources** = issues of `C.series.sourceSeries` with `status = PUBLISHED` and `effectiveCutoff() ∈ (intervalFrom, at]`, ordered by cut-off ascending. One query, indexed by series and cut-off.
2. **Union** = the frozen `IssueMember` rows of those issues, one query (`WHERE m.issue IN (:sources) ORDER BY i.cutoffStampedAt, m.sortIndex`, backed by `issue_member_issue_sort_k`), deduplicated by `messageUid`; the **owner** of a repeated uid is the earliest source issue.
3. **Curation** applies as RI-10: `(union − excludes) ∪ includes`, includes carrying no source issue.
4. **Order (RI-17)**: rows with a source issue by `(source cut-off asc, source sortIndex asc)`; manual includes after them in the series' ordinary sort; `sortIndex` dense over the whole. This is what "stapled" means, and it is the order the PDF, the preview and the live member list all share because they share `IssueOrdering`.

Implemented as a second factory beside `Resolution.curated(...)`: `Resolution.compiled(Map<uid, SourceRef> owners, Set<uid> includes, Set<uid> excludes)` where every decision carries `MembershipReason.FROM_SOURCE_ISSUE` and the resolution carries the **source map** (uid → source issue publicId, cut-off, sortIndex) and the **source list** (each source issue's publicId, week, year, cut-off, member count). The fork that chooses it is the one `hasMembership` boolean in `IssuePublishService.publish` and its four mirrors (`IssueResolutionService.resolutionOf`, `IssuePublishService.preview`, `IssueCurationService.selectedByCriteria`, `MessageIssueLookup.wouldContain`), replaced by one `MembershipRegime.of(series)` so the arms cannot drift. `MembershipPredicate`, the SQL narrowing and the differential test are untouched: a compilation never reaches them.

**Misses / omissions:** none. A compilation has no candidate set to reject; the workbench's `omissions` is absent for it, and the panel is replaced by the sources panel (§2.7).

### 2.3 Freeze and provenance

- `freezeMembers` (step 6) stamps `source = COMPILED` and `sourceIssuePublicId` from the resolution's source map for compiled rows; `OVERRIDE_INCLUDE` for includes as today. `MembershipReason.FROM_SOURCE_ISSUE` is added; `CriteriaMissCode.of`'s total switch gains the arm; `IssueMemberListService.deriveReason` returns `FROM_SOURCE_ISSUE` for `source = COMPILED` (frozen half) and from the resolution (live half).
- **Why a publicId text and not an FK:** the compiled row is the compilation's own frozen record. A retired source issue is deletable today; an FK would either block that or need a cascade rule, and both make one issue's record depend on another's row surviving. The publicId stays readable after a deletion; the name, week and year are read live from `PublicationIssue` by publicId in **one** query per list (the `MessageNaming` pattern), and fall back to the publicId's frozen week/year on the header when the source is gone.
- Snapshot header (step 7): `snapshotSourceSeriesId` and `snapshotSourceIssueIds` (ordered, comma-separated publicIds), by the same argument that produced `snapshotSeriesIds` — the operand is a series, the answer is a set of issues that cannot be re-derived once more issues fall into the period. `membershipProvenance = COMPILED` (a new honest value; `UNION_SNAPSHOT` is reserved for irreproducible legacy annuals and steers the replay). **I-20**: a compiled issue's header names every source issue whose rows it holds.
- Amend works as for any native issue: re-derives at the original cut-off from the sources' frozen rows (stable unless a source was retired or amended since; §2.8).

### 2.4 Rendering

- `RenderRequest` gains **`groups`**: an ordered list of `{ publicId, name (in the render language), week, weekTo, year, cutoff, messageIds }` plus one trailing group for manual includes, or `null` for every other regime. `IssueRenderService.renderTo` puts it into the FreeMarker model as `groups` beside `messages`; a template that does not read it is unaffected.
- **New template `nm-accumulated-report-pdf.ftl`** in `niord-web/src/main/resources/templates/messages/` (per-language front matter through `_da`/`_en` include stubs in niord-dk, the weekly's own pattern). One cover, then per group a heading — the weekly issue's name, "Uge {week}" / "Week {week}", the cut-off date, the group's own number range — and `@renderMessageList` over the group's messages with area headings inside the week (the accumulate-into-sequences idiom `nm-report-pdf.ftl` already uses for NM vs miscellaneous). The cover's date and volume come from the injected `year` (volume = `year − 1884`), **never** from `.now`, which the weekly cover uses and which would stamp today's date on last year's annual.
- **Report row `nm-accumulated`** (`FmReport`: templatePath, domain `niord-client-nm`, properties `{ mapThumbnails: false, ISSN }`), seeded in `niord-dk-dev-basedata.zip → reports.json` for dev/test and created through the existing sysadmin report editor in production. `pdf.*` dictionary keys for the headings in `pdf_en.properties` (niord-web) and `pdf_da.properties` (niord-dk); Danish values legacy-sourced.
- **Template contract test** in niord-web (plain JUnit, FreeMarker over the template with a `StringTemplateLoader`, the `ScriptResourceTest` pattern): members of week 12 print under the week-12 heading in frozen order; a document without `groups` still renders. `StubIssueRenderService` learns to **capture** the `RenderRequest` so niord-core can assert what reaches the renderer (order, groups, params) — nothing asserts that today.
- **Size and time are a real risk with no measurement**: the whole document becomes one HTML string, then one in-memory PDF, inside the publish transaction; a 2024 annual is 1,272 messages with parts, geometry and attachments loaded (three chunks of the one-pass loader, roughly 40 queries). Phase 1 ships behind the flag of the series' regime and is **measured on test before Phase 2 starts** (§4).

### 2.5 Public site and consumers

No change. The compilation is an issue of a `MESSAGE_REPORT` series in a published category; the public adapter lists it by its window (`publicFrom` = the stamped 31 December, capped by the next annual), ordered by category priority. The message editor's publication picker sees it like any other issue. `PublicationVo` is a frozen XSD, so the source-issue list reaches the public only through the PDF.

### 2.6 Checklist rows on a compilation

| Row | Verdict |
|---|---|
| `ISSUE_OPEN`, `INTERVAL_PRESENT`, `INTERVAL_CHAINED`, `CUTOFF_AFTER_PREVIOUS`, `CUTOFF_BEFORE_SUCCESSOR`, `CUTOFF_NOT_FUTURE`, `REPORT_CONFIGURED`, `REFERENCE_FORMAT_COMPLETE` | Apply unchanged (the compilation tiles and renders). |
| `FILE_PRESENT_PER_LANGUAGE` | Not applicable, `PUBLISH_GENERATES_FILE` (as for any generated series). |
| `MEMBERS_RESOLVED` | Applies: the union's size. |
| `MEMBER_LIMIT` | **Not applicable**, new reason `BOUNDED_BY_SOURCES`: the union is bounded by issues the engine already published one by one; the 1,000 ceiling protects against a runaway query, which a compilation cannot be. Seven of nine years exceed it. |
| `NO_INEFFECTIVE_OVERRIDES` | Applies, against the union (an exclude naming a message no source carries is ineffective). |
| `CANCELLED_MEMBERS_ALIVE_AT_CUTOFF` | **Not applicable**, new reason `JUDGED_AT_SOURCE`: liveness was decided at each weekly's cut-off; re-judging at 31 December would drop what the weeklies rightly carried. |
| `OVERLAPPING_ISSUE` | Applies, and means something: this year's union shares no member with last year's frozen rows (the query already compares frozen rows of the predecessor). |
| **`SOURCE_ISSUES_COMPLETE`** (new, WARN, acknowledgeable) | Every source-series period inside the compilation's period has a PUBLISHED issue: no OPEN weekly, no gap. Fires when the annual is released in early January before week 52 is out. Detail code carries the missing/open weeks. Fifteen rows; `RailRefusalCatalogueTest`'s warnings list, `IssueLifecycleTest`'s fourteen-row assertion, `CHECK_CODES` and three key families in both bundles change together. |

### 2.7 Workbench (I30) for a compilation

- `members[]` rows carry **`sourceIssue: { publicId, seriesId, week, weekTo, year, cutoff, name } | null`** (name live in `lang`; null for a manual include).
- New **`sources[]`**: `[{ publicId, week, weekTo, year, cutoff, status, memberCount }]` for every source-series issue in the period, PUBLISHED and OPEN alike (OPEN ones contribute nothing and are what `SOURCE_ISSUES_COMPLETE` warns about), plus synthesised rows for periods with no issue at all. Present for the compiled regime only.
- `omissions` absent; `afterPlannedCutoff` / `lateAfter` absent (a member's publish date says nothing about whether its source issue fell inside the window); `viewedFrom` echoes the start as for any tiling issue.
- `?at=` keeps its meaning: the union as of that instant, so the running annual can be looked at mid-year and the numbers move with the cut-off exactly as on an open issue's screen (`API-CONTRACT.md`, the batch of nine, item 13).
- Live member count (series list) = a count over the sources' member rows, through `liveMemberCount` like every other regime.

### 2.8 Later, not in this plan

- **Source drift**: a PUBLISHED compilation whose recorded `snapshotSourceIssueIds` no longer equals the PUBLISHED sources in its period (a weekly retired or amended since) shows a banner "sources changed since release — amend to refresh". Cheap once the header column exists; sequenced after the first annual is out.
- Compilations at other cadences, or over an in-force source (where repeated members would make the owner rule visible on every row).

### 2.9 Import ruling (K1)

`LegacyTemplateRulings` gains a **shape override** for `accumulated-yearly-ntm`: `contentMode GENERATED_FROM_QUERY`, `timeRelation COMPILED_FROM_SOURCE`, `sourceSeries weekly-ntm`, `reportId nm-accumulated`, print settings, `cutoffDefault PERIOD_END`, `firstIssueStartsAt 2017-01-01`. Applied by `LegacySeriesTranslation` after the type-derived defaults so the import produces the converted series on every rehearsal. The 17 imported issues are untouched: their descs keep sticky uploaded files, their headers `NO_MEMBERSHIP`; a frozen issue takes no checklist and no resolve, so nothing asks them for a report. `LegacyTemplateRulingsTest` pins the override; `AnnualCutoffTest`'s 2003/2018 bounds still hold. Gap detection then shows 2017–2025 as MISSING (the retired 2017 is ignored: a retired issue covers no period), which is what K2's by-hand flow starts from.

### 2.10 Rule ids to add to DATA-MODEL §8 (and regenerate `rule-ids.txt`)

| Id | Rule |
|---|---|
| S-24 | `sourceSeries` is set iff `timeRelation = COMPILED_FROM_SOURCE`; a compilation carries neither `criteria` nor `aliveAtCutoff`. |
| S-25 | The source series is not the series itself, is `GENERATED_FROM_QUERY`, and is not a compilation. |
| RI-16 | The compiled member set is the uid-union of the frozen member rows of the source series' PUBLISHED issues whose effective cut-off lies in the compilation's period, a repeated uid owned by the earliest source issue, then RI-10. |
| RI-17 | Compiled rows are ordered by source cut-off then the source's frozen `sortIndex`; manual includes follow in the series' sort; `sortIndex` is dense over all. |
| I-20 | A compiled issue's snapshot header names its source series and every source issue whose rows it holds. |

S-19..S-23 exist in code but not in the manifest today; adding S-24/S-25 to §8 is the moment to add those five as well so the manifest binds them.

---

## 3. Frontend

- **API types**: `TIME_RELATIONS` gains `COMPILED_FROM_SOURCE`; `PublicationSeries.sourceSeriesId?`; `MEMBER_SOURCES` gains `COMPILED`; `MEMBER_REASONS` gains `FROM_SOURCE_ISSUE`; `MEMBERSHIP_PROVENANCES` gains `COMPILED`; `IssueMember.sourceIssue?`; `IssueWorkbench.sources?`; `CHECK_CODES` gains `SOURCE_ISSUES_COMPLETE`; `CHECK_DETAIL_CODES` its variants and the two new `NOT_APPLICABLE` reasons. The vocabulary specs in `publication-series-api.service.spec.ts` change with them.
- **Series editor**: the regime select on the Criteria tab offers the third value with its own help line. `series-rules.ts` gains `isCompilation(model)`; when true the tab shows a **source-series picker** instead of the criteria editor and hides the liveness checkbox; `firstIssueStartsAt` shows (tiles); `buildSeriesPayload` strips criteria/liveness and carries `sourceSeriesId`; `toFormModel` round-trips it. The picker is a single-select over `searchSeries()` filtered client-side to ACTIVE, `GENERATED_FROM_QUERY`, not compiled, not self, owner domain first — the server's S-25 stays the authority. The probe panel is not shown for a compilation (there is no predicate to preview); the sources panel on the issue screen is its counterpart. Specs: `series-rules.spec.ts` predicates and stripping, `series-editor.spec.ts` regime options and visibility.
- **Issue screen**: the member table is drawn **grouped by source week** — a header row per source issue (name as a link to `/admin/publications/<seriesId>?issue=<publicId>`, week, cut-off date, count), members beneath in frozen order, manual includes in a final "Added by hand" group — through a grouped computed in the facade over the same `members()` signal, so `tableMembers`, selection and select-all keep working on the flat list underneath. The band says "Compiled from N issues of <source series>". The view switch, the late section, the left-out panel and the criteria panel are hidden for a compilation; a **Sources** card lists `sources[]` with OPEN and missing weeks marked. `FROM_SOURCE_ISSUE` joins `AUTOMATIC_REASONS` so rows render quietly; `admin.publications.reason.FROM_SOURCE_ISSUE` and the new check keys go into both bundles (Danish as `ai_` proposals until reviewed). `MEMBER_REASONS` is added to the label-coverage table in `publication-status.spec.ts`, which does not cover it today.
- **Dashboard, strip, create menu, one-off editor, public site**: no change. The series is `SCHEDULED`/`YEARLY` and is listed, gapped and retro-created like any other tiling series.

---

## 4. Phases

| Phase | Scope | Exit |
|---|---|---|
| **1 · Model and derivation** (niord) | V14; `TimeRelation.COMPILED_FROM_SOURCE` + `tiles()`; `sourceSeries`; S-1/S-2/S-4 adjusted, S-24/S-25; `MembershipRegime` replacing the five `hasMembership` forks; `Resolution.compiled`; freeze with `COMPILED` + `sourceIssuePublicId`; snapshot header columns; provenance `COMPILED`; checklist verdicts incl. `SOURCE_ISSUES_COMPLETE`; I30 `sourceIssue` and `sources[]`; export/import of `sourceSeriesId`; import ruling for `accumulated-yearly-ntm`; what verifies the compiled union is the import check and the public list — the check must report no violation for the converted series, and a published annual must appear on the public list exactly once. Tests: `SeriesValidatorTest`, a new `CompilationResolutionTest` (union, owner rule, curation, order, live vs frozen, `?at=`), `IssuePublishTest` (freeze + header), `IssueWorkbenchTest` (sources, no omissions), `IssueLifecycleTest` (fifteen rows, verdicts), `LegacyTemplateRulingsTest`, contract tests (`PublicationApiContractTest`, `IssueMemberWireTest`, `RailRefusalCatalogueTest`, tier matrix). | Package green; deployed to test; **measured**: the 2024 union (1,272) resolves under 1 s live, and a render through the existing `nm-report` template (flat, no groups) completes with recorded time and PDF size. |
| **2 · Rendering** (niord + niord-dk) | `RenderRequest.groups`; `nm-accumulated-report-pdf.ftl` + `_da`/`_en` stubs; `nm-accumulated` report row + dictionary keys; the captured-request stub; the niord-web template contract test; preview of a compilation on test. | 2024 rendered on test as the K3 shape in both languages; time and size within what Rasmus accepts (target: under 60 s, under 30 MB; to be confirmed against the measurement). |
| **3 · Frontend** (niord-app) | §3 in full, with specs; `.claude/` conventions updated where a new vocabulary is added. | `ng test` green, lint, build; the 2024 issue reviewed end to end in the browser on test: create from gap, grouped list, rail, publish dialog, publish, PDF download. |
| **4 · Docs and manifest** | API-CONTRACT amendment "compilation series" (I30 `sources`/`sourceIssue`, series `sourceSeriesId`, the fifteenth row, the two inapplicable reasons, `TimeRelation`); DATA-MODEL §3.1/§3.6/§3.8/§6/§8 (rule ids above, `rule-ids.txt` regenerated, both repos committed together); the decisions record; CUTOFF-MODEL's accumulated column corrected from "messages published in the year" to "members of the year's weekly issues"; the go-live process (kept outside this repository): the converted series is verified in the rehearsal, no new window step (K2). | Reviewer check of every touched doc against the code. |
| **5 · Retro years** (K2, after go-live) | Admin session per year 2017–2025: create from the gap, review the grouped list and the sources card (all 50–53 weeks PUBLISHED, `SOURCE_ISSUES_COMPLETE` green), publish with the 31 December cut-off. Order: oldest first, so each window is capped by its successor. | Nine PUBLISHED annuals on the public site; `2017` retired uploaded issue remains beside the new one as the rule for a retired issue intends. |
| **Z · DoD** | The batch DoD checklist filled with evidence: suites and totals per repo, the measurements of phase 1/2, the browser walk of phase 3, the doc diff of phase 4. | — |

Phase 1 is the long one (roughly two working sessions); 2 and 3 about one each; 4 half; 5 is Rasmus's time, not development.

---

## 5. Risks and how the plan meets them

| Risk | Where it bites | Mitigation |
|---|---|---|
| Render time and memory for 1,000–1,300 messages in one PDF inside the publish transaction | Phase 2 | Measured at the end of phase 1 before the template is written; `mapThumbnails` off by report property; if the measurement is bad, the fallback is rendering outside the transaction and hashing afterwards, which is a contained change in step 10. |
| A weekly amended or retired after the annual is out | Live | Out of scope for v1; the header records the sources so the drift banner (§2.8) is a follow-up, and amending the annual re-derives from the sources at the original cut-off. |
| Duplicate memberships across weeklies | 14 cases in the estate | Owner rule (earliest source) is deterministic and tested; the unique key forbids the alternative. |
| The public window of a retro annual | Phase 5 | `openWindow` sets `publicFrom` to the stamped cut-off and the successor caps it, so 2017's window is 2018, not 2017 as the legacy import had it. Acceptable for an annual roll-up; if not, the window is editable (`publicWindowSource MANUAL`). |
| Convert-in-place and the go-live import | Every rehearsal | The override is an import ruling, pinned by test; the next rehearsal loop verifies the converted series and the nine gaps on the strip. |
| Fifteen rows instead of fourteen | Both repos | One change set: `CODES`, the refusal switch, the catalogue, the FE code lists, three key families, two pinned tests. |

---

## 6. Open for Rasmus (none blocking)

- Whether the 2018-style file name should read `2018 Akkumuleret 1-52.pdf` (first and last compiled week) or the pattern's ordinary `${year}`; `week`/`weekTo` on a compilation would then mean the first and last **source** week, which is a small change in `IssueShape.applyNumbers` for the regime.
- Whether the retro annuals' public window should be the content year (legacy) rather than the following year (derived); decide after seeing 2024 on the public test site.
