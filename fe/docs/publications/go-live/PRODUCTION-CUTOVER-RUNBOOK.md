# Production go-live — runbook and findings

**Purpose: production gets ONE pass.** Everything below was learned by running the legacy import against
the `t-dma.dk` test environment, where each fix cost a full build-and-deploy cycle. Seven deploys were spent
finding seven defects, the last of which only a SUCCESSFUL import could reveal. That budget does not exist
in production.

The go-live *process* — one window, identical rehearsal on test, the checklist that defines done — is ruled
in `GO-LIVE-PROCESS.md`; this file is the mechanics underneath it, and §6 says what has actually been
verified and what has not.

**The rule the whole window rests on.** Once the backend is deployed, the public list is the union of two
halves: a published issue in a publishing category is served, and a legacy row is served until the issue
imported from it has been published. There is no switch between them and no per-series step. The deploy
moves nothing, because the new half is empty until the import; the import is where the list changes hands,
row by row, each row keeping its id. **The go/no-go is `GET /publication-series/import-check` with zero
violations, and the abort at every step is a full database restore.**

---

## 1. The single most important finding

**A clean dry run does NOT mean the import will succeed.**

`POST /rest/publication-series/import-legacy/validate` reported `problems: []` over all 1,077 rows
immediately before three separate failures on those same rows. `plan()` never opens the database for write,
which is exactly what lets it promise not to touch anything — and exactly why it could not see a `NOT NULL`
column, a duplicate key, or a transaction timeout.

Four of the defects below were invisible to it for that reason. Three are now caught at plan time; the
timeout cannot be, because you cannot know how long a write takes without doing it.

**And a SUCCESSFUL import does not mean a correct one.** The run that finally landed wrote all 1,077 issues
and then failed the import check on three series serving several current editions at once (§10). Between "it
wrote nothing" and "it is ready to serve" there is a third state, and the import check is the only thing
that tells them apart.

**Consequence for production: budget for the import failing at least once, and make sure the maintenance
window is long enough to run it, read the error, undo it, and run it again.** The transaction is
all-or-nothing and every FAILED attempt left `importedIssues: 0`, so a failure is safe — it is only
expensive in wall-clock. At ~20 minutes a go, plan for at least an hour.

---

## 2. The defects, and whether they can recur

| # | Defect | Found by | Now caught at plan time? |
|---|---|---|---|
| 1 | `PublicationSeries.category` is `NOT NULL` and was never set | first real write | **Yes** — `planCategoryOf` |
| 2 | `repoPath` never validated | code review of the write path | **Yes** |
| 3 | No re-run guard: a second import hit the `publicId` unique key part way through | reasoning about a retry | **Yes** — `ALREADY_IMPORTED` |
| 4 | `IssueMember.frozenMainType` is `NOT NULL` and was never set | first real write | **Yes** — `assertMembersCanBeFrozen` |
| 5 | Two publications authored the same `seriesId` | first real write | **Yes** — `assertSeriesIdsAreUnique` |
| 6 | Transaction timeout at 240s | timing arithmetic on two failures | **No — structural, see §3** |
| 7 | Three series left serving several current issues at once (`I-18`) | **the first successful import**, via the import check | **Yes** — `closeSupersededIssues` |
| 8 | Every imported name and file path written with a null parent, so unreadable | **the admin UI**, rendering ids where titles belong | **Yes** — two back-reference assertions over the whole estate |
| 9 | All four OPEN issues stamped with their predecessor's release instant | **reading one row of the deployed API**, checking an unrelated sort order | **Yes** — the unreleased guard, plus two import-check assertions |
| 10 | Every tiling issue claimed the period **after** the one it contains, and 528 in-force issues carried a lower bound they must not have | **the frozen members**, via the import-check assertion added for defect 9 | **Yes** — `ContentIntervalTest`, seven assertions over both shapes |
| 11 | 45 cut-offs recovered from a stamp outside the period they close — an edit captured instead of the release | **the import check**, once defect 10 made the intervals correct enough to check against | **Yes** — the cascade is bounded, four assertions |
| 12 | `nominalCutoffMonth` / `nominalCutoffDayOfMonth` missing from the VO, so the four YEARLY series could never be activated | **building the settings screen** — the Kadence tab had nowhere to put them | **Yes** — a reflective entity↔VO round-trip probe |
| 13 | `/validate` judged the STORED series, so a create form was told a series it could not create had no problems | **building the settings screen** — the create flow had nothing to validate against | **Yes** — the endpoint body is a testable static, five assertions |
| 14 | Clicking a series in the list navigated to the app root; the issue workbench was reachable only by deep link | **using the screen** — every test used `goto` | **Yes** — an e2e that clicks a card |
| 15 | **Defect 1 again, on a second route**: a series created without a category 500s on the NOT NULL column | **saving a new series** from the settings screen | **Yes** — S-19, a rule rather than a check in `create()` |
| 16 | The create template failed S-8 and S-12 *against itself* — desc rows for languages it did not declare, `AUTO_ON_PUBLISH` on a one-off | **validating the template**, on the way to defect 15 | **Yes** — a set-difference assertion, not a list of expected errors |

Defects 1, 4 and 5 all had the same shape: a plan that looked complete, and a column the plan never
populated or never checked. Defect 8 is a different and worse shape, and worth reading twice before the
production run.

**Defect 15 is defect 1 wearing a different hat, and that is the finding.** `PublicationSeries.category` is
`NOT NULL`. The first time it was found — by the importer's first real write — the fix was `planCategoryOf`,
in the importer. Nothing generalised it, so the column sat unset on every other route, and the interactive
create path 500'd on it the first time anybody created a series by hand. The second fix is a RULE (S-19),
which covers validate, create and update at once. **When a database constraint is found unmet, the fix
belongs where the rules live, not where the write happened to be.**

**Defects 12 and 13 share a shape of their own, and it is the one worth carrying forward.** Both were
invisible from the backend, and not because the backend tests were thin — because *every one of them set the
field on the entity directly and never crossed the wire*. The rules were right; the VO was not, and nothing
compared the two. Four real series could never have been activated, and `/validate` reported "no problems"
for a series that could not be created. Both surfaced within an hour of a screen trying to USE the API
rather than test it, which is the argument for building the consumer before declaring the producer done.

## Two legacy data facts to carry into the window

Both were measured by sweeping the captured archive, and both are facts about the archive rather than about
any tool: they are true of the estate the production import will read.

**Eight groups of issues share an identical member set**, two of them three ways — a reused message tag:

```
dont-use-weekly-ntm-week-2  ==  weekly-ntm 2025-12-31  ==  weekly-ntm 2025-01-08
dont-use-weekly-ntm-week-52-and-1  ==  weekly-ntm 2024-12-25
firing-practice-areas 2022  ==  2021  ==  1970
weekly-ntm 2019-04-26  ==  weekly-ntm-p-t 1970-01-01
```

They are the New Year turnover editions, rescued through a throwaway template with the tag reused. **Two
issues claiming the same members cannot both be right about the period they cover**, and no interval logic
reconciles it. At least 11 issues in the archive permanently misstate their own content.

**The yearly in-force series were tagged in December and published in January.** On `firing-practice-areas`
and `efs-a`, roughly half of all members had a `publishDateTo` before their own issue's recovered cut-off —
often every member of an issue (33/33, 32/32, 34/34). An edition released on 5 January carries the notices
that expired on 31 December, so the moment membership was actually frozen is December while the recovered
cut-off says January. It is a limit on cut-off recovery rather than a defect.

**A frozen member records the message as it was at freeze.** Roughly three quarters of what a late reading
of an old tag calls missing is now `CANCELLED` or `EXPIRED` — the real tags carry 1,642 CANCELLED and 28
EXPIRED members across 24 P&T releases. Status decay is a property of the archive, not an import error, and
nothing in the window should be scored against it.

### The six `dont-use-*` series are real history — do not exclude them

Six imported series carry ids beginning `dont-use-`, from legacy templates titled *"DONT USE - ..."*. They
read as junk and are not. All six are **PUBLISHED editions**:

| series | members | public window |
|---|---|---|
| `dont-use-weekly-ntm-p-t-week-2` | 228 | 2025-01-08 → 2025-01-15 |
| `dont-use-weekly-ntm-p-t-week-52-and-01` | 126 | 2024-12-25 → 2025-01-01 |
| `dont-use-weekly-ntm-p-t-week-1-2026` | 123 | 2025-12-31 → 2026-01-07 |
| `dont-use-weekly-ntm-week-2` | 27 | 2025-01-08 → 2025-01-15 |
| `dont-use-weekly-ntm-week-52-and-1` | 24 | 2024-12-25 → 2025-01-01 |
| `dont-use-weekly-ntm-week-1-2026` | 2 | 2025-12-31 → 2026-01-07 |

Legacy had no way to vary one issue. When a publication was created wrong, or an edition had to span **two
weeks** — the week 52 / week 1 turnover — the only remedy was a throwaway template: publish one edition from
it, then mark the template DONT USE so nobody reused it. "DONT USE" describes the **template**, never the
edition.

Two consequences for the import, both correct rather than fixable:

- Each rescue template holds exactly **one** publication, so each is a single-issue chain and its edition
  gets `intervalFrom = null` — nothing records when its content period opened.
- They fragment `weekly-ntm`: the week 52 edition is not in that series at all, so its archive has a hole
  that is an artefact of the workaround rather than a missing release. **Gap detection must not be read as
  finding a real gap there.**

> **Do not exclude them at import.** Dropping them deletes real published editions, and the `dont-use-`
> prefix is the only thing that makes them look otherwise.

### Defects 12 and 13: two gaps only a consumer could see

**A YEARLY series could never have been activated.** `nominalCutoffMonth` and `nominalCutoffDayOfMonth` are
on the entity and required by S-6 for a MONTHLY or YEARLY cadence, and were absent from
`SystemPublicationSeriesVo`. Nothing was corrupted — `updateFromVo` never assigned them, so an update left
them alone — but they could not be SET or READ through the API at all. `accumulated-yearly-ntm`, `efs-a`,
`firing-practice-areas` and `ncags-2021` are all YEARLY. Every one would have failed activation at the
window with a validation error naming a field that had no input to fix it, and no amount of clicking would
have cleared it. `languageSpecific` went the same way: set by the importer, read by
`IssuePublicationMapping`, invisible to the API.

The regression test is **reflective** rather than a list. A listed-field test would have been written from
the same mental model that dropped these and would have dropped them again; this one walks the entity's
declared fields, fills each with a distinct non-default value, round-trips through the VO and reports every
field that does not come back. The two entity references resolved from ids are skipped by name with a
reason — a blanket skip is what would let the next one through.

**`/validate` answered a question nobody asked.** It took a series in the body, looked up whatever was saved
under that id, and validated that. A series with no stored row returned an EMPTY list, which reads as "no
problems" for a series the server will refuse — the admin finds out on save, against a screen that just told
them it was fine. An edit that BROKE a valid series also reported nothing, because the break existed only in
the body. And installation languages were passed as null, switching off S-11 entirely: the one rule needing
context from outside the series was the one rule that never ran.

Nothing caught it because every test of these rules called `SeriesValidator` directly, and the validator was
right throughout — the wiring handed it the wrong subject. The endpoint body is now a package-private static
and the tests call THAT, which is the path the endpoint runs rather than a paraphrase of it.

### Defect 11: a cut-off recovered from outside the period it closes

Found only because defect 10 made the intervals correct enough to check a cut-off against. With the
intervals fixed the import check dropped from 74 violations to 3 — and one of the three was not an interval
problem at all.

*NtM Week 52 - 2025* covers 17–24 December. Its recovered cut-off was **2 January 2025**, 349 days early,
taken from an `updated` stamp that looks like a placeholder row created in January for the year-end edition
and never touched again. Its 19 frozen members were all published **inside** the corrected interval, so
nothing else about the row was wrong — the row simultaneously confirmed defect 10's fix and exposed this one.

**The fix.** `recover()` now takes the issue's own interval as `Bounds`, and a stage whose answer falls
outside it is not believed — the cascade tries the next stage instead of returning it, because the next
witness is still better evidence than no witness. The lower bound is strict (a period cannot close before it
opens). The upper bound is deliberately loose — a full period past the nominal close — because the release
action legitimately runs a little **after** the bound it closes at, and every row in the archive has that
shape (a 10:00 close released at 10:30). A tight ceiling would have rejected all of them.

**It is not one row.** Bounding the cascade over the whole estate moves **45** rows to `MANUAL`:

| stage | unbounded | bounded |
|---|---|---|
| `RECOVERED_FROM_UPDATED` | 991 | 975 |
| `RECOVERED_FROM_NEXT_TAG` | 82 | 53 |
| `MANUAL` | 0 | **45** |
| `NOT_RELEASED` | 4 | 4 |

Every legacy row has an `updated` column, so before this the column was always believed and stage 4 was
unreachable. The 45 are rows whose stamp falls more than a full period after the interval closed — an edit
captured instead of the release, which is the failure the cascade was built to avoid and says so in its own
class comment.

**MANUAL is not a loss here.** For a tiling issue `intervalTo` **is** the nominal close, and
`effectiveCutoff` already coalesces onto it — so those 45 rows fall back to the release moment rather than
keeping a later edit. They get a better answer, not a missing one.

### Defect 10: the content interval was the public window

Legacy stores **one** window — `publishDateFrom` / `publishDateTo`, the period an edition is on the site.
The new model distinguishes that from the **content interval**, the period the issue covers. The importer
copied the one window it had into both pairs:

```java
// The public window, verbatim. The cut-off stamp is recovered afterwards.
issue.setPublicFrom(legacy.getPublishDateFrom());
issue.setPublicTo(legacy.getPublishDateTo());
issue.setIntervalFrom(legacy.getPublishDateFrom());   // unexplained
issue.setIntervalTo(legacy.getPublishDateTo());       // unexplained
```

The comment justifies the first pair. Nothing justified the second, and `intervalFrom == publicFrom` held
for **all 1,074** imported issues that have one — zero exceptions.

**Two consequences, both wrong in different ways.**

*Tiling series.* An EfS released on a Wednesday carries the week **ending** that Wednesday. Taking the public
window as the interval moved every issue forward by a full period. The frozen members settle it — they were
frozen from the legacy message tag, so they are the only witness independent of either window. Over 40
`weekly-ntm` issues spanning 2017–2026 (829 members):

| | |
|---|---|
| inside the **stated** interval | 50 (6.0%) |
| inside the interval **minus one period** | **770 (92.9%)** |
| neither | 9 (1.1%) |

Consistent in all ten years; not one year favours the stated interval. Worked example — *EfS uge 34 - 2026*
states 19–26 Aug, and all six of its frozen members were published on 13 Aug.

*In-force series.* `intervalFrom` is null for an `IN_FORCE_AT_CUTOFF` issue — not an omission, a fact: 531
production issues have no lower bound at all. The estate has exactly 531, and **528 carried one**. It is not
merely unknown there: an in-force issue holds whatever was still in force at its cut-off, so 2027's firing
areas legitimately contain a 2016 notice, and a lower bound is a false claim about the content rather than a
missing one.

**Why only `weekly-ntm` could be measured** — the question every reviewer asks, because `weekly-ntm-p-t` has
the same WEEKLY cadence and the same 498 issues. It has the opposite **content rule**. Measured from the
frozen members:

| issue | members | span of member publish dates |
|---|---|---|
| EfS uge 34 - 2026 | 6 | **0 days** |
| EfS uge 44 - 2025 | 23 | **6 days** |
| Aktive P&T uge 34 - 2026 | 169 | **2,036 days** |
| Aktive P&T uge 3 - 2025 | 122 | **1,461 days** |

The EfS carries what was published in the week ending at the cut-off, so it tiles. The P&T carries every
notice still **in force** at the cut-off — the Danish name says so, *"Aktive P&T uge 34"* — so issues overlap
heavily and every one of them starts at the same oldest date, `2021-01-15`, the oldest notice still active.
Asking whether its members fall inside one week is the same category error gap detection is gated off for.
That is 531 of 1,077 issues. Of the rest, eight series are `cadence = NONE` one-offs where "one period" is
undefined, and `accumulated-yearly-ntm` has no frozen members at all.

> **Cadence does not imply content.** Weekly rhythm, five-and-a-half years of content, on the same release
> schedule as a series that carries six days of it.

**Why nothing caught it.** No test asserted on the interval at all. The value was present, plausible, and
equal to a real date on the row. It surfaced only because the `CUTOFF_PRECEDES_ITS_INTERVAL` assertion added
for defect 9 flagged 77 rows — the subset where copying the window forward made the issue's own period run
backwards.

**The fix.** `applyContentInterval` in `LegacyIssueTranslation`, by shape:

| shape | `intervalFrom` | `intervalTo` |
|---|---|---|
| tiling, cadenced | the **previous release** in the chain | this release |
| tiling, head of chain | `null` | this release |
| `IN_FORCE_AT_CUTOFF` | `null` | this release (the cut-off, an upper bound) |
| one-off (`cadence = NONE`) | unchanged — no period to step back by | unchanged |

Taken from the **chain** rather than by subtracting a nominal period: a skipped week then produces one long
interval instead of a wrong short one plus a phantom gap. Every imported bound is marked `RECOVERED` — none
of it was recorded, all of it is read back out of the release chain, and a reader deciding whether to trust
an imported interval needs to see that. The head of a chain keeps a null lower bound rather than an invented
one; inventing bounds is what produced this.

`IssueSnapshotDeriver` copies `intervalFrom` into `snapshotIntervalFrom` after this runs, so the snapshot
header corrects itself.

> **Re-import required**, and it is the second one for this defect family. The fix changes what the importer
> writes, not what is already written.

### Defect 9: a cut-off recovered for an issue that was never released

`cutoffStampedAt` means *the publish action recorded this instant*. The recovery cascade ran for every
imported row without asking whether the row had ever been published, and its first stage reads `updated` — a
column every legacy row has.

On a never-published issue `updated` is when the row was **created**, and the thing that created it was the
release of the issue *before* it (legacy creates the successor at publish, the same way `nextIssueCreation =
AUTO_ON_PUBLISH` does now). So each OPEN issue inherited its predecessor's release instant, to the
millisecond:

```
weekly-ntm  week 33  PUBLISHED  interval 2026-08-12 → 2026-08-19   stamp 2026-08-12 10:30
weekly-ntm  week 34  OPEN       interval 2026-08-19 → 2026-08-26   stamp 2026-08-12 10:30  ← identical
```

Three of the four ended up with a cut-off dated **before their own interval had opened** — the 2027
firing-areas issue by a full year. The consequences are all quiet ones: the row sorts a period early and ties
with its predecessor, the "(stemplet)" marker claims a recorded value for something nobody recorded, and —
once a series goes ACTIVE — every gap measured from that anchor is measured from a date at which the issue
did not exist.

**Why nothing caught it.** Four rows out of 1,077, on a column that was populated, with a plausible recent
timestamp in it. The import check asserted the *shape* of the estate — one current issue per series, windows
DERIVED, ids unique — and every one of those held. The value was wrong, not missing, and it was found by
reading a single row of the deployed API while checking something else.

**The fix.** `CutoffRecovery.recover` now takes `released` as a **required** argument and returns
`NOT_RELEASED` with a null cut-off before any stage runs. Required rather than defaulted, because every stage
below it reads a timestamp that exists on an unreleased row too and each would happily return one.
`reconstructed` is `false`: nothing was reconstructed, and flagging it would put a "needs a human" badge on
every open issue forever.

Two import-check assertions now cover it — `OPEN_ISSUE_IS_STAMPED`, and the one that generalises past this
cause, `CUTOFF_PRECEDES_ITS_INTERVAL`: a period cannot close before it begins, whatever the cut-off's
provenance.

**Estate impact.** Cascade distribution moves from 994/83/0/0 to **991/82/0/0 plus 4 `NOT_RELEASED`** (three
off stage 1, one off stage 2). Pinned in `CutoffRecoveryTest`.

### Defect 8: the import succeeded, the check was clear, and the names were gone

`descs` is mapped `mappedBy = "entity"`, so the desc row owns the foreign key. The translation built each
desc with `new` and handed the list to `setDescs`, which never sets the back-reference. Hibernate cascaded
them on save exactly as asked and wrote every row with `entity_id = NULL`.

Nothing failed. The import reported success, the check returned `clear: true`, and all three of its
assertions passed, because every row it counts genuinely existed. What was lost was the name of all twenty
series and all 1,077 issues, plus each issue's file path — which for an archived issue is most of what the
issue *is*. It surfaced only because the admin list rendered a series id where a title belongs, and it
surfaced then only because that column falls back to the id instead of drawing a blank row.

The lesson for the production run is narrow and specific: **the import check counts rows, not content.** It
answers "did every row arrive" and it cannot answer "is what arrived readable". After the production import,
open the admin list and read a name — of a series, and of an issue inside it — before doing anything else.
That check takes ten seconds and is the only one that would have caught this. It is step 5 of the sheet.

Now caught at translation time by `everySeriesDescIsAttachedToItsSeries` and
`everyIssueDescIsAttachedToItsIssue`, which assert the back-reference rather than the list over all 1,077
real rows. Both were confirmed to fail against the old code before the fix landed.

`undo()` deletes descs before their parents and also sweeps rows with a null parent, so a re-import after a
broken one does not leave unreachable rows behind. A production database that has already taken a broken
import must be undone, not imported over.

---

## 3. The import takes about twenty minutes, and cannot be made much faster

**This is not a performance bug. It is the id generator, and it is not safe to change.**

Every entity in Niord draws its id from **one shared single-row `hibernate_sequence` with an increment of
1**. Each row therefore costs a synchronous `SELECT next_val ... FOR UPDATE` plus an `UPDATE` before its
insert can even be queued.

| Measurement | Value |
|---|---|
| Rows the import writes | **65,347** (64,250 `IssueMember` + 1,077 `PublicationIssue` + 20 `PublicationSeries`) |
| Per-allocation cost, local DB, no network | **5.4 ms** |
| Plan time (read + translate) | **~20 s** |
| **Total, measured over two successful runs** | **1176 s and 1080 s ≈ 18–20 minutes** |
| Platform transaction default | **240 s** ← the wall it hit, twice |
| Budget now set by `IMPORT_TIMEOUT_SECONDS` | **1800 s** |

**Budget the window for 20 minutes, not 5.** An earlier draft of this document said "about five minutes",
derived by dividing the 220 s a failed attempt had spent by 65,347 rows. That arithmetic was wrong: the 220 s
was a write **cut off by the timeout**, not a completed one, so a lower bound was being used as a
measurement. The real figure is 1176 s. The 1800 s budget holds, but with far less headroom than intended —
**re-measure before assuming it covers production.**

**JDBC insert batching does not help.** It was tried; the write went 240.2s → 240.8s. The allocations are
serial regardless of how the inserts are grouped. (The same change *did* halve the plan time, 41s → 20s, by
collapsing 1,077 per-publication member queries into one — that part was real and is kept.)

**Three alternatives were weighed and rejected:**

- **Raise the sequence increment.** Changes id allocation for _every_ entity in the system. Production's
  `hibernate_sequence` is mid-count and the AngularJS frontend still reads from it.
- **Reserve a block and assign ids by hand.** Bypasses `@GeneratedValue` for one table, so the
  publications tables would allocate ids by a rule no other entity in the system follows.
- **Split into several transactions.** Gives up all-or-nothing. A half-imported archive is worse than none:
  the rows that landed look correct and nothing marks them as partial.

**If production's estate is materially larger than 65,347 rows, re-do this arithmetic before the window.** At
the observed **18 ms/row** the 1800 s budget covers roughly **100,000 rows** — not the 500,000 an earlier bad
figure implied. Anything approaching that needs the budget raised first.

---

## 4. Numbers to check against — the check sheet

This section is the *figures* half of the checklist in `GO-LIVE-PROCESS.md` §3: what to read at each step, on
one page, so a run can be scored without paging between documents. §3 is the procedure and wins; this table
adds nothing to it and omits the rows that carry no number.

**The figures are deliberately blank.** They are filled from the dry run on the reseeded estate, and that
filled sheet — not this one — is the expectation for the run after it. The measured figures from each loop
are at the end of this file. Any red row means fix, reseed, and rehearse from the top.

**Step 0 — written down before starting** *(the figures the run is scored against)*

| Figure | This estate |
| --- | ---: |
| Backup date | `____` |
| Frozen image tag | `____` |
| Restore duration, measured | `____` |
| Series / issues / members expected | `____` / `____` / `____` |
| Gaps expected per series | `____` |

**Step 1 — the public list BEFORE** · `/public/v1/publications` `____` entries, `/publications/search`
`____` entries, both saved. This is the signature everything after the import is diffed against.

> The search figure is the size of the feed, not a page of it: `/publications/search` caps at
> `maxSize=100` unless a caller says otherwise, and `snap` passes an explicit high `maxSize` so that
> BEFORE and AFTER are the same set rather than two windows onto it. A recorded figure of exactly
> `100` was read at the default and is the cap.
>
> Since 2026-09-25 `snap` reads the **public tier** of that feed, because it calls it anonymously:
> ACTIVE publications in publishing categories and PUBLISHED issues only, so the figure is smaller
> than the estate and than the figures recorded before that date. BEFORE and AFTER are read the same
> way, so the diff between them still means what it says.

**Step 2 — dry run** `POST /import-legacy/validate` · *hard stop if either fails*

| Figure | Expected | Read |
| --- | --- | ---: |
| `problems` | **`[]`** | `____` |
| `seriesImported` | equals step 0 | `____` |
| `issuesImported` | equals step 0 | `____` |
| `issuesByStatus` | PUBLISHED / RETIRED / OPEN | `____` / `____` / `____` |
| `issuesByCutoffSource` | `NOMINAL_CLOSE`, `PUBLIC_WINDOW`, `RECOVERED_FROM_UPDATED`, `RECOVERED_FROM_NEXT_TAG`, `MANUAL`, `NOT_RELEASED` | `____` |
| `categoriesSeen` / `categoriesCreated` | | `____` / `____` |

**Step 3 — import** · `____` s against a 1800 s budget, which must leave room for one retry inside the
window. HTTP 200; a 500 near a round number is the timeout of §3, not a data defect.

**Step 3b — schema check** *(between the import and the import check)* — one boot with
`quarkus.hibernate-orm.database.generation=validate` and `quarkus.flyway.migrate-at-start=false` starts
cleanly. Entity/schema drift fails **here**, loudly, rather than mid-window. Result: `____`

**Step 4 — import check** `GET /publication-series/import-check` — **the go/no-go**

| Figure | Expected | Read |
| --- | --- | ---: |
| `clear` over `importedIssues` | `true` over a non-zero count — **`clear: true` over 0 asserts nothing** (§7) | `____` |
| `violations` / `triggerAudit` / `idCollisions` | `[]` / `[]` / `0` | `____` |
| `duplicateMemberships` / `duplicateOverrides` | `0` / `0` | `____` / `____` |
| `counts.seriesWithoutOwner` | **`0`** — a `SERIES_WITHOUT_OWNER` violation is a hard stop; the count is written even when zero | `____` |
| `counts.publishedIssuesWithoutStamp` | **`0`** — a `PUBLISHED_ISSUE_WITHOUT_STAMP` violation is a hard stop: the public list drops a legacy row only once the issue imported from it carries a publish stamp, so an unstamped released issue serves the same document twice; the count is written even when zero | `____` |
| `counts.seriesOfKind*` | SCHEDULED / UNSCHEDULED / ONE_OFF as ruled | `____` / `____` / `____` |
| `counts.uncoveredPeriods` | the ruled expectation | `____` |
| `series[…]` — per series | `gapCount` equals the ruled expectation; `gapDetectionEnabled` on where it should be | `____` |

**Step 5 — the public list AFTER** · same ids, same category-priority order, the newest week present exactly
once (`____`); a series name and an issue name read as names, not ids (`____`); the newest issue's file opens
(`____`).

**Step 6 — estate shape** · gap count per series (`____`), `SeriesKind` counts (`____`), no residue series
(`seed-probe-*`, `efs-rehearsal`), every ruled domain in place, every series to be activated passes S-20, and
**every series carries its expected owner** — the table below — with the six citation publications reading
`availability: ALL_DOMAINS`.

**Step 7 — activate, per owner domain.** Activation is an admin write, and every admin write on a publication
is scoped to the domain in the **`NiordDomain` header**, which must equal the series' **owner**. **Sysadmin
is not exempt** — the header decides. Switch domain, activate that desk's series, then move on; a series
outside the header domain is refused `403 NOT_IN_DOMAIN`. The imported `reportParams` are stripped of the
derived keys by the importer, so **no hand edit is needed**; if one is, the deploy did not take.

| Owner domain | Series |
|---|---|
| `niord-nm` | weekly-ntm · weekly-ntm-p-t · accumulated-yearly-ntm |
| `niord-almanac` | efs-a |
| `niord-fa` | firing-practice-areas |
| `niord-annex` | nm-annex-ice-service · nm-annex-ncags · journal-number · aids-to-navigation · list-of-wrecks · www-danskehavnelods-dk · danish-list-of-lights · navigation-through-danish-waters · and every other imported series whose template named no domain (the importer's default) |

**Steps 8–10 — consumers, UAT, record** · as `GO-LIVE-PROCESS.md` §3. The public list does not wait on
activation; it changed hands at the import.

### 4.1 Running the scripts

[`scripts/`](scripts/) holds the four scripts the sheet is run with. They share `lib.sh` and read everything
about the environment from the shell, so the same scripts run against test and production:

| Variable | What it is |
| --- | --- |
| `NIORD_API` | the REST base — default `https://niord.t-dma.dk/rest` |
| `NIORD_AUTH` | the Keycloak token endpoint — default the test realm |
| `NIORD_USER` | an admin user; sysadmin for the estate-wide calls. **Required** |
| `NIORD_PASSWORD` | its password — never written into a script. **Required** |
| `OUT` | where every response lands — default a folder under the temp dir |
| `REASON` | the audited reason for the writes of step 7 |
| `SMOKE_SERIES` | the series step 8 takes its citation from — default `weekly-ntm` |

In order, and each one refuses to go on if the step before it did not land:

1. `rehearsal-precheck.sh` — read-only. The backend answers, the new-model estate is empty, the legacy estate
   is there to read, and the dry run prints the figures step 0 writes down.
2. `rehearsal-import.sh` — the window itself: undo, the BEFORE snapshot, dry run (hard stop on any problem),
   import with its duration, import check (hard stop on any violation or on `importedIssues: 0`), the AFTER
   snapshot and the diff against BEFORE. ~15 minutes; run it where a shell can wait.
3. `rehearsal-activate.sh` — step 7: the owner list, then activation in each owner's domain, the feed
   re-checked, and the chain on the two weeklies.
4. `rehearsal-smoke.sh` — step 8 from the outside: the public list, one publication resolved by id, the
   messages its citation filter returns, and the admin picker.

---

## 5. Order of operations

1. **Dry run** — `POST /rest/publication-series/import-legacy/validate`. Read every figure in §4. Expect
   ~20s. This writes nothing and is safe to repeat.
2. **Stop if `problems` is non-empty.** The report names every offending row, not just the first.
3. **Import** — `POST /rest/publication-series/import-legacy`. Expect **~20 minutes**. A 500 with `Error
   invoking subclass method` and a time close to a round number is a _timeout_, not a data defect — check §3
   before changing anything.
4. **Confirm nothing partial** if it failed — `GET /rest/publication-series/import-check` should report
   `importedIssues: 0`. Every failure so far has.
5. **Import check** — `GET /rest/publication-series/import-check`. Want `clear: true` over the
   `importedIssues` count step 0 wrote down. **`clear: true` over `importedIssues: 0` asserts nothing** — see
   §7. This is the go/no-go: violations are not negotiated inside the window.
6. **Then the public list, the estate shape and activation** — steps 5 to 7 of the sheet. The public list
   changed hands at step 3; activation is what lets the next release be authored.

**To clear a bad import and re-run:** `DELETE /rest/publication-series/import-legacy`. Scoped by
`importSource`, so it can only touch rows the importer created — a hand-authored series has no `importSource`
and is invisible to it. Returns **409 with every reason** if any imported series has left `DRAFT`.

**A partial import cannot happen, but a wrong one can.** The transaction is all-or-nothing, so a failure
leaves nothing behind; the undo exists for the case where it succeeded and the *result* was wrong — which is
exactly what happened on the first successful run (see §10).

**Once a series has been activated the undo is gone, and the only abort is a restore.** That is true of the
production window from step 7 onward and of every rehearsal from the same point — and it is why the restore
is rehearsed and timed before the window opens (`GO-LIVE-PROCESS.md` §4).

Auth for all four endpoints: Keycloak password grant on the `niord-web` client, role `admin` — and
`sysadmin` for the import and its undo, which reach every domain at once (since 2026-09-25).

---

## 6. What has actually been verified, and what has not

Being precise about this, because "we ran it" and "it worked" are different claims.

**Verified on `t-dma.dk`:**

- **The import runs end to end at full volume**, on a fresh restore of a production backup, repeatedly.
- The dry run reports the §4 figures with `problems: []` over the real estate.
- Failures roll back completely: three failed imports, `importedIssues: 0` after every one.
- The import check's assertions run against real rows and **caught a real violation** — see §10.
- A full undo → re-import → clear check cycle, end to end on real data (§10).
- The translation layer, checked row by row against the captured estate in the `niord-core` suite.
- Activation of the whole estate in the owner domains, 13 of 13, with no hand edit.

**NOT verified:**

- **Step 3b, the schema check**, has never been run against a deployed container. It is a ticked row on the
  production sheet and it has been deferred out of every loop so far.
- **The restore**, as an abort. It is the only abort there is and nobody has timed it.
- **The endpoint returns HTTP 500 even when the import succeeds** — twice out of twice on the earliest runs.
  The cause is not known; see §11. Later loops returned 200, which is consistent with the ambient-transaction
  fix, not proof of it.
- Whether 1800 s is enough headroom. The longest measured run is 865 s on the fresh restore and 1176 s
  before the plan-time fix — 48–65% of the budget, tighter than intended. See §3.

**Treat §4 as necessary but not sufficient.** Matching those figures means the estate translated; it does not
mean the result is readable. Only step 5 says that.

---

## 7. The import check's own failure mode

`GET /rest/publication-series/import-check` returns `clear: true` when it finds no violations — **including
when there is nothing to check**. Over `importedIssues: 0` every assertion passes vacuously. Read the
`counts` block, never the `clear` flag alone; the scripts refuse to go on when the count is zero.

The same trap already bit the mailing-list trigger audit once: the first version scanned `messageQuery` only,
which 12 of 15 triggers do not carry, so it read a fifth of the triggers and reported clean. It now scans
`messageQuery`, `messageFilter` and `scriptResourcePaths`. Findings are committed at
`niord-core/src/test/resources/fixtures/import-check-trigger-audit.md` — including the finding that no
mailing list touches Notices to Mariners at all.

---

## 8. Housekeeping before the production run

- **The test environment carries two `seed-probe-*` series** left by earlier seeding. Harmless and
  non-colliding, but they should not exist in production and are worth deleting from test so the series list
  is clean for review.
- **`hibernate_sequence` in production is mid-count.** `V1__publication_series.sql` deliberately does not
  create it. Do not let a fresh-schema assumption reset it.
- **The import is not idempotent by design.** It refuses rather than merging: a second run reports
  `ALREADY_IMPORTED` for every row. To run it again the previously imported rows must be gone — by the undo
  while every series is still DRAFT, and by a restore after that.

---

## 9. Open items that touch the window

| Item | State |
| --- | --- |
| Cut-off cascade **stage 3** (printed PDF cover dates) | Unreachable — the captured estate carries metadata, not PDFs. The 27 annual cut-offs attributable to covers currently fall to stage 1 or 2. Decide before the production run whether the covers need extracting. |
| Concurrent requests return 500 | Reproducible outside the app, and **not** confined to the import — see below. |
| One `weekly-ntm` release whose frozen membership nothing explains | Measured against the archive: `missing=0, extra=25` over the interval 2025-11-12 → 2025-11-19, against 19 frozen members. `missing=0` means everything the legacy tag froze was found; the 25 are additional messages the resolver considers in-window and the tag did not carry. The obvious explanation — the previous issue's members leaking across the boundary — is wrong: none of that issue's members has a publish date inside this window. A hand-pruned legacy tag is the remaining candidate. Not a defect, and it touches history rather than anything the window publishes. |

### The 500 is not only the import's

The import returning HTTP 500 on success is known and handled elsewhere. What is worth carrying into the
window is that the same signature — `Error invoking subclass method` — appears on ordinary **GETs under
concurrency**, and there it loses the response rather than merely mislabelling a completed job.

Measured against the deployed test environment, six concurrent requests:

| Endpoint | Payload | Result |
| --- | --- | --- |
| `GET /publication-issues/series/weekly-ntm` | ~314 KB, 498 issues | **4 of 6 failed** |
| `GET /publication-series/search-details` | small | **1 of 6 failed** |

It scales with payload size and it affects an endpoint that predates this work, so it is a property of the
deployment rather than of the publications code. Sequential requests have never failed.

Two consequences for the production run. The import holds one long transaction for ~20 minutes, so whatever
the limit is, that window is the worst time to have people using the admin area — **run it when nobody else
is on the system**. And any post-import verification that fans out concurrently can report false failures;
verify sequentially, or the 500s will be read as import damage that is not there.

---

## 10. The first successful import, and what it found

**2026-08-24, `t-dma.dk`.** The run took **1176 s** and committed: 20 series (all `DRAFT`), 1,077 issues,
64,250 members. HTTP returned **500** *after* the commit — the data was complete and consistent, and the
check confirmed `importedIssues: 1077`. **A 500 from this endpoint does not mean nothing was written. Always
read the check before concluding anything.**

### The check was not clear

```
clear: false
counts: { importedIssues: 1077, seriesWithACurrentIssue: 10, idCollisions: 0 }
```

Three `I18_TWO_CURRENT_ISSUES` violations, all on series the grouping ruling created:

| Series | Issues left open |
| --- | ---: |
| `nm-annex-ncags` | **7** |
| `danish-list-of-lights` | **4** |
| `nm-annex-ice-service` | **4** |

**The legacy rows genuinely have no end date.** From 2021 onward nobody set one, because in the old model
each publication stood alone and nothing ever needed to end. Standalone that was harmless. Grouped into one
series it means four Danish List of Lights editions all claiming to be current — the archive forking in
public. **The data was always like this; the ruling is what made it visible.**

### The rule (Rasmus)

An issue closes where its successor opens — the only end date available that is not invented, since it comes
from a row that exists and says when it took over. **A recorded legacy `publishDateTo` is never
overwritten:** the 2017 NCAGS edition really did end on 23 December, and the nine-day gap before the 2018
edition is real data, not an artefact to tidy away.

Ordered by `publicFrom`, then the legacy `updated` stamp, then `publicationId`. **The middle key is
load-bearing.** Three NCAGS rows share 2023-01-04 and two share 2026-01-07; the one that should stay open is
the one legacy marks `ACTIVE`, which is the most recently updated and is *not* the one `publicationId` order
would pick.

Applied to **every** imported series, not only the grouped ones — the invariant is about a series with two
current issues, not about how the series came to exist.

### What this means for production

This defect could only be found by an import that **succeeded**. Every earlier failure rolled back, so there
was nothing for the check to read and it returned `clear: true` over an empty set.

**So the production sequence has three outcomes, not two:** it fails and writes nothing; it succeeds and the
check is clear; or it succeeds and the check is not. The third is the one that looks like success from the
HTTP response alone.

### The re-run — **`clear: true`**

Undo, re-import with the closing rule, check:

```
DELETE /import-legacy           200     5.1 s     20 series, 1,077 issues, 64,250 members removed
POST   /import-legacy/validate  200    18.5 s     seriesImported 20, issuesImported 1077, problems []
POST   /import-legacy           500  1080.0 s     <-- see section 11; the data landed anyway
GET    /import-check            200
         clear: true
         importedIssues 1077, seriesWithACurrentIssue 10, idCollisions 0
         violations []   triggerAudit []
```

**All three assertions passed over 1,077 real rows.** The three series that were serving several current
editions each now serve one.

This was the first time the check asserted anything and passed. Every earlier green was over an empty set.

---

## 11. Open: the import can return HTTP 500 on success

**Twice out of twice, on the earliest runs.** Both returned:

```
{"details":"Error invoking subclass methodError id <uuid>","stack":""}
```

— and both had committed everything. The data was complete and consistent each time, confirmed by the check
immediately afterwards.

The direct cause of those two was found: `PublicationSeriesRestService` carries a class-level
`@Transactional` and `importLegacy` did not override it, so every import ran inside TWO transactions — its
own `requiringNew().timeout(1800)`, and an ambient one on the DEFAULT 240 s timeout. The inner one committed;
the reaper had aborted the outer one long before, and the commit afterwards failed with `ARJUNA016102: The
transaction is not active`. Measured: **65,734 rows written in 980.7s of an 1800s budget, followed by an HTTP
500**, with every row committed. The three long endpoints are `NOT_SUPPORTED` now, and `dryRun` owns its
transaction for the same reason; every loop since has returned 200.

**The operational rule stands anyway: read the check, not the HTTP status.** `importedIssues: 0` means it
really failed; a full count means it worked. An operator reading a 500 will conclude the import failed, and
the unsafe reaction is to undo a perfectly good import and burn another 20 minutes, or to abandon the window
believing the migration is broken.

**A 20-minute synchronous HTTP request is the underlying design problem.** The proper fix is to return 202
with a job id and let the caller poll. That is a larger change than the window needs, but it is the reason
this failure mode exists at all.

---

## Measured — loop #2 on the test environment, 2026-08-29 (backend df64e763)

| Step | Measured |
|---|---|
| 0 undo | 16 s (loop #1: 12 s) |
| 1 dry run | 8 s, `problems: 0`, `notes: 4` (rows with no recoverable cut-off, imported without an invented date) |
| 2 import | **799 s**, HTTP 200 (loop #1: 784 s but a 500 from the reaped ambient transaction — fixed in df64e763); budget 1,800 s holds one retry |
| 3 check | `clear: true`; importedIssues **1,081**; series **13** (SCHEDULED 5 / UNSCHEDULED 3 / ONE_OFF 5); seriesWithACurrentIssue 10; openIssues 4; idCollisions 0; duplicateMemberships 0; duplicateOverrides 0; triggersNamingAWeeklyTag 0; uncoveredPeriods 0; violations 0 |
| 4 estate shape | gap counts not measurable while every series is DRAFT (gap detection switches on at activation) |

Both loops ran on the same restored copy (2026-08-26 backup); the fresh-restore figure for step 0 was still
open at this point.

## Measured — loop #3 on the test environment, 2026-08-29 16:00 (backend 258e4d8b)

| Step | Measured |
|---|---|
| 0 undo | 14 s (13 series, 1,081 issues, 64,640 members, 2,188 descs) |
| 1 dry run | 8 s, `problems: 0`, the same 4 `CUTOFF_NOT_RECOVERABLE` notes as loop #2 |
| 2 import | **786 s**, HTTP 200 |
| 3 check | `clear: true`, 1,081 issues / 13 series / 0 violations / 0 trigger findings |

## Measured — activation rehearsal on the test environment, 2026-08-29 (backend 3bbfde9d, after loop #3)

| Step | Result |
|---|---|
| public list BEFORE | `/public/v1/publications` 7 entries, `/publications/search` 100 — saved |
| activate | 13 of 13 ACTIVE. Two findings: (a) **sysadmin is not exempt from the domain guard** — every series write needs `NiordDomain` = the series' owner (correct, and the sheet now says so); (b) the two weeklies refused activation on imported `reportParams` (S-23) and were fixed by hand, which the importer now does instead |
| consumers | https://nautiskinformation.t-dma.dk/#/publications listed all seven from the new model, downloads resolved to `/rest/repo/file/...`; the picker, the `?publication=` filter (26 members for uge 35) and the anonymous hydration all answered; `triggerAudit` 0 |

Note for the production sheet, first written here: with any series out of DRAFT, `DELETE import-legacy`
refuses by design (409), so the abort path from activation onward is the database restore.

## Measured — loop #4, the CLEAN loop on the fresh restore, 2026-09-04 08:02–08:24 (backend ≥ e501639c)

**Estate:** the test database restored from the production backup of Wednesday 2026-09-02, taken *before*
that day's weekly release (newest human-edited message NM-819-26 / Local Warning-230-26 of 2026-09-01; the
week-36 rows exist, no week-37 row yet). Publication tables dropped before the boot, Flyway rebuilt them;
`DELETE import-legacy` answered `409 nothing to undo`, which is the proof of an empty estate. **No hand edit
and no config switch anywhere in the loop.**

| Step | Measured |
|---|---|
| 0 figures written before the run | 13 series / 1,081 issues (PUBLISHED 1,046 · RETIRED 31 · OPEN 4); cut-off sources RECOVERED_FROM_UPDATED 967 · NOMINAL_CLOSE 39 · RECOVERED_FROM_NEXT_TAG 38 · PUBLIC_WINDOW 29 · MANUAL 4 · NOT_RELEASED 4; 5 categories seen, 0 created; 19 criteria documents, 2 series without |
| 1 dry run | 6 s, `problems: 0`, 13 `OWNER_ASSIGNED` notes (accumulated-yearly-ntm → niord-nm; the six citation publications + the two annexes + the two desk uploads → niord-annex) |
| 2 import | **817 s**, HTTP 200, figures equal to step 0 |
| 2b schema check | **Deferred** — but executed LOCALLY the same day against a Flyway-built schema on MySQL 8.0.35, and it **failed on a real defect**: `IssueAuditEntry.action` is `varchar(255)` in the schema while the entity (an enum) lacked the `columnDefinition` pin its sibling enum columns carry, so the validator expected a native `ENUM`. Inert at runtime (`generation=none`, names round-trip through the varchar); only the gate failed. **Fixed in niord `c81b7f16`** (pin added, `EntityContractTest.everyStringEnumColumnKeepsTheTypeTheMigrationsGaveIt` guards every enum-typed column in the package, the validate boot passes locally). All 91 legacy tables and the nine redesign tables validated clean. For the container run: read the Azure setting `NIORD-HIBERNATE-DB-GENERATE` on test and production first (repo default `none`; an `update` there changes the premise), then the two env vars, one restart, read the log, revert. What only the container can see: hand-made drift in the restored legacy tables — a locally generated schema is Hibernate's own DDL and cannot show it. |
| 3 check | `clear: true` over 1,081 issues · `seriesWithoutOwner: 0` · 0 violations · 0 trigger findings · 0 id collisions · 0 duplicate memberships/overrides · kinds SCHEDULED 5 / UNSCHEDULED 3 / ONE_OFF 5 · 10 series with a current issue, 4 OPEN |
| 4 estate shape | owners as ruled: niord-nm × 3 (OWNER_ONLY), niord-almanac efs-a, niord-fa firing-practice-areas, niord-annex × 8 — the six citation publications `ALL_DOMAINS`, the two annexes `OWNER_ONLY`; no residue series |
| 5 public list | 5 entries, search 100 — *five, because the backup pre-dates the release: uge 35 had expired on 2 Sept and uge 36 was still unreleased in the data. A production backup taken right after the release carries both* |
| 6 activate | 13 of 13 → ACTIVE, each in its owner domain (`NiordDomain` header), HTTP 200 every one, 28 s; feeds unchanged (ids, order, types). *One operator slip, not a system finding: the owner file's last line lacked a newline, so `firing-practice-areas` was sent in a second pass 40 s later — same result* |
| 7 chain | weekly-ntm: uge 35 PUBLISHED (cut-off 26 Aug 10:21, 26 members), uge 36 OPEN (nominal cut-off 2 Sept 10:00, 9 members so far); P&T: week 35 PUBLISHED 174, week 36 OPEN 171 |
| 8 consumers | NM picker offers Journal Number (annex, ALL_DOMAINS) and not NM Annex NCAGS (annex, OWNER_ONLY); the FA picker offers the firing areas and Journal Number and not the NM weeklies; the NM admin series list = its three owned series, its one-off list = 0; `?publication=` for uge 35 → 26 = the issue's 26 members; anonymous hydration answers; nautiskinformation.t-dma.dk reachable |
| undo timing | n/a by design — with series out of DRAFT `DELETE import-legacy` refuses (409); the abort path is the restore, as the sheet says |

## Measured — loop #5 on the fresh restore, 2026-09-11 09:24–09:44 (backend 3d9fb5d3)

**Estate:** the same 2026-09-02 production backup as loop #4, restored again with the publication tables
dropped and Flyway rebuilt; `DELETE import-legacy` answered `409 nothing to undo`. The deployed backend
carries the calendar-keyed gap gate, the importer that freezes every member as PUBLISHED and the release-slot
fallback fix. Run for the first time with the scripts under `scripts/`. **No hand edit and no config
switch.**

| Step | Measured |
|---|---|
| 0 figures | 13 series / 1,081 issues (PUBLISHED 1,046 · RETIRED 31 · OPEN 4); cut-off sources RECOVERED_FROM_UPDATED 959 · PUBLIC_WINDOW 41 · NOMINAL_CLOSE 39 · RECOVERED_FROM_NEXT_TAG 36 · NOT_RELEASED 4 · MANUAL 2; 5 categories seen, 0 created; 19 criteria documents, 2 series without — identical to loop #4 except the cut-off-source split, which moved with the cut-off amendments between the two loops |
| 1 dry run | 8 s, `problems: 0`, 11 `OWNER_ASSIGNED` notes |
| 2 import | **865 s**, HTTP 200, figures equal to step 0 |
| 2b schema check | **still waiting**: not run in this loop either; a ticked row on the production sheet |
| 3 check | `clear: true` over 1,081 issues · `seriesWithoutOwner: 0` · 0 violations · 0 trigger findings · 0 id collisions · 0 duplicate memberships/overrides · kinds SCHEDULED 5 / UNSCHEDULED 3 / ONE_OFF 5 · 9 series with a current issue, 4 OPEN · `uncoveredPeriods: 0` |
| 4 estate shape | owners and availability exactly as loop #4; no residue series |
| 5 public list | 5 entries, search 100; **AFTER == BEFORE** (ids, order, types). The legacy `/publications/search` feed swaps two adjacent MESSAGE_REPORT rows of equal priority between calls — seen before anything was written in this loop too, so it is a tie-order wobble of the old search, not a change; the committed `compare` names it as such |
| 6 activate | 13 of 13 → ACTIVE in their owner domains, HTTP 200 every one; public list unchanged. *Operator finding, now fixed in the committed scripts: the owner file's last line had no trailing newline and a plain `while read` dropped it, so `firing-practice-areas` (niord-fa) was activated in a second pass 30 s later — the same slip as loop #4's, which had been read as a one-off* |
| 7 chain | weekly-ntm: uge 35 PUBLISHED (26 members), uge 36 OPEN (9); P&T: week 35 PUBLISHED (174), week 36 OPEN (171) |
| 8 consumers | not re-walked this loop (unchanged code paths since loop #4) |
| gap gate on the wire | P&T issue list: `gapDetection {enabled: true, reasonCode: CADENCED_SERIES}`, `gapCount: 0`, no synthesized row while week 36 is OPEN |
| frozen status on the wire | P&T week 35 members: 174 rows, `frozenStatus` PUBLISHED on all 174, `current` on all 174, 8 drifted (live CANCELLED) — the shape the status column and its icons render from |

**What loops #2 to #5 measured is still the baseline for durations, counts and estate shape.** What they did
not rehearse is the sheet as it now stands, which ends at the import check, the public list and the smoke
test. **Loop #6 is the first rehearsal of the sheet in `GO-LIVE-PROCESS.md` §3**, and it is the open item in
`GO-LIVE-TASKS.md`.
