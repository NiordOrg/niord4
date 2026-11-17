# Is the migration re-runnable?

*"If we do a fresh prod-to-test DB restore and run the migration again, will all
these findings and rules be applied automatically to the new dataset?"* —
Rasmus.

The honest answer was **no, for two of them** — and both are now fixed. This is
the audit behind that, item by item, because the question deserves a list rather
than a reassurance.

## The test that keeps the answer true

`LegacyTemplateRulingsTest` runs the importer over the **captured real estate**
(`fixtures/legacy-estate/templates.json` + `publications.json`) and asserts the
rulings applied. It is not a hand-built fixture, so what it pins is what the real
templates produce. If a future ruling is applied to a database instead of to the
importer, this is the file that should have grown and did not.

---

## Encoded in the importer — a re-run reproduces these ✅

| What | Where |
|---|---|
| Which template-less publications are one series (11 NCAGS annexes, 8 ice-service annexes, Danish List of Lights, the double weeks…) | `LegacyOrphanGrouping` |
| **Which templates are not series at all** (`ncags-2021` → the NCAGS series) | `LegacyTemplateRulings` — **new** |
| **The domain a template does not name** (NM / NM Annex) | `LegacyTemplateRulings` — **new** |
| Cut-off recovery and its provenance cascade (`STAMPED`, `RECOVERED_FROM_*`) | `CutoffRecovery` |
| Nominal weekday/time/day-of-month read off each series' own history | `NominalSchedule`, applied by `applyNominalSchedules` |
| Legacy filter → criteria document, incl. refusing an unknown filter | `LegacyCriteriaTranslation`, `LegacyFilterTranslator` |
| Per-issue time relation from the release's OWN legacy filter | `IssueSnapshotDeriver` |
| Member snapshots and their provenance | `MemberSnapshotImport`, `MemberProvenanceRules` |
| Series/issue shape: cadence, content mode, numbering, report settings | `LegacySeriesTranslation`, `LegacyIssueTranslation` |
| Interval chaining, window derivation, the one-current-issue cap | `LegacyImportService` |

The importer also **upserts by `publicationId`** and its undo is scoped by
`importSource`, so re-running is a supported operation rather than a hazard.

## Was only in the deployed database — now encoded ✅

Both of these were the answer to Rasmus's question being "no", and both are the
same shape: *the legacy data does not contain the answer, so the importer either
invents one or records the one a human gave.*

1. **Domains.** `series.setDomain(template.getDomain())` was the only source, and
   some templates name none. Recorded in `LegacyTemplateRulings.DOMAIN_BY_SERIES`.

   > **This claim was WRONG for two of them, and the rehearsal proved it.**
   > See "What the first reseed rehearsal found" at the end of this document. Six
   > of the nine rulings were later withdrawn; of the three that remained, only
   > `accumulated-yearly-ntm` actually applied.
2. **`ncags-2021` is not a series.** It came from a legacy template, and the
   template path created one series per template unconditionally — the orphan
   grouping only ever looked at template-*less* publications. Now redirected, so
   its editions are filed under `nm-annex-ncags`.

## Not from the migration at all — a re-run will not recreate them ✅

- **`efs-rehearsal`** — no `importSource`, no `legacyTemplateId`. A hand-made
  artifact ("EfS prøvekørsel"), holding one RETIRED issue "Uge 32, 2026" from
  rehearsing the weekly cycle by hand. It is not migration output and a fresh
  import will not produce it.
- **`seed-probe-2026082310424` / `-433`** — probe series from a development
  session, retired rather than deleted, and correctly so: each holds a PUBLISHED
  issue, and the delete rule permits deleting never-published issues only. The
  guard worked.

## Operational state, deliberately NOT re-applied ⚠️

These are decisions about *running* the system, not about migrating data, and an
importer that reproduced them would be overstepping. After a fresh restore they
have to be redone by hand — which is correct, but worth knowing:

- **Series status.** The importer creates every series `DRAFT`, and a re-import
  returns an activated one to DRAFT. Activation is an operational act, worked in
  the owner's domain as step 7 of the sheet.
- **Curator overrides and per-issue `criteriaOverride`.** Authored after import,
  by people.

## Encoded in the importer — the six `dont-use-` templates ✅

Rasmus ruled: *"All dont-use templates should not be imported/migrated to their
own series. They are a fluke. The issues belonging to these are indeed issues of
another series - either NTM or NTM P&T."*

All six are now in `LegacyTemplateRulings.DESTINATION_BY_TEMPLATE`, and
`LegacyTemplateRulingsTest` pins both halves: no `dont-use` series survives the
import, and every ruled destination actually receives editions.

| Template | Edition it holds | Destination |
|---|---|---|
| `6c5018df` | EfS uge 2 - 2025 | `weekly-ntm` |
| `b0fe5e0f` | EfS uge 52 - 2024 og uge 1 - 2025 | `weekly-ntm` |
| `aed6002d` | EfS uge 1 - 2026 | `weekly-ntm` |
| `1776882d` | Aktive P&T uge 2 - 2025 | `weekly-ntm-p-t` |
| `5dbe9e77` | Aktive P&T uge 52 - 2024 og uge 1 - 2025 | `weekly-ntm-p-t` |
| `0462f851` | Aktive P&T uge 1 - 2026 | `weekly-ntm-p-t` |

## Encoded in the importer — a series resolves under its newest edition's filter, not its template's ✅

The weekly EfS template (`a8e661ee`) carries **no** `messageTagFilter` — the sticky regime it was created with in 2017 — while its editions have carried the phase filter (`data.phase == 'msg-status-change' && msg.status == Status.PUBLISHED`) since 2019: 111 blank editions, 382 phase editions, the RECORDING week among them. `LegacySeriesTranslation` read the series' `timeRelation` + `aliveAtCutoff` off the **template**, so `weekly-ntm` was imported with `aliveAtCutoff = false` and its OPEN issue resolved under the sticky regime: every cancelled notice stayed in. UAT met it on uge 36/2026 (NM-814-26, cancelled 2026-08-28, still listed where legacy's tag had dropped it).

**Encoded:** `planSeries` translates each template with `LegacySeriesTranslation.regimeSourceOf(template, publications)` — the **newest edition filed under the template** (by `publishDateFrom`, then `created`, then id; a template with no editions is its own regime). Both filters are still translated, so an unknown filter on either refuses. `LegacyTranslationTest.theSeriesRegimeIsTheNewestEditionsNotTheTemplates` pins it over the captured estate and asserts that no other template moves. The imported issues are untouched (each carries its own snapshot regime via `IssueSnapshotDeriver`).

The flag is a series setting and can be corrected by hand on a running estate, but a reseed needs no such correction: the importer derives it.

## The duplication ruling has nothing to act on — measured ✅

Rasmus ruled: *"No duplication is not fine. One of the issues must have been
created last (and is newer) ... It's for sure certain that the oldest is or
should be retired."*

The rule is right. Applied to this estate it changes nothing, and the measurement
is why — filing the clones in does **not** produce a live duplicate.

Across all 1,077 publications, grouped by template + name + exact member set:

| Group | Statuses | Verdict |
|---|---|---|
| `Aktive P&T uge 1 - 2025` ×2 | INACTIVE / INACTIVE | already resolved |
| `EfS uge 1 - 2025` ×2 | INACTIVE / INACTIVE | already resolved |
| `NCAGS` ×2 | ACTIVE / INACTIVE | already resolved |
| `Skydeområder ...` ×2 | INACTIVE / DRAFT | already resolved |
| `EfS A - 2017` ×2 | ACTIVE / ACTIVE | **see below** |

Four of the six clones have a member-identical twin in their destination, and in
every case **the twin is already INACTIVE** — legacy retired it when the
replacement was swapped in. So the ruling is satisfied by the data as it stands:
the superseded half of each withdrawal is already the retired one.

`created` cannot be the discriminator in any case: clone and twin carry the
*identical* timestamp (`1483269014000` for every EfS pair), a 2017 bulk-import
artifact. Ordering by it would be ordering by noise.

### The one live pair is not a duplicate

`EfS A - 2017` appears twice, both ACTIVE, both with the same 28 members. They are
**editions 1 and 2**, with sequential public windows:

| | edition | public from | public to |
|---|---|---|---|
| `92ab125a` | 1 | 2017-01-01 | 2017-03-07 09:19 |
| `35046715` | 2 | 2017-03-07 09:11 | 2017-12-31 |

The members match because the annual annex content did not change between
editions. The windows overlap by **eight minutes** — somebody published edition 2
at 09:11 and capped edition 1 at 09:19. That is a publishing seam, not a fork.

### Why no automated check was added

A rule of the form "same name, same members, both live → retire the older" would
fire on exactly this pair, forever, on correct data — and an import check that is
permanently red teaches people to ignore it. Two further shapes would be caught
wrongly by looser versions of the same rule:

- **`Aktive P&T uge 13 - 2024` and `uge 14 - 2024`** share all 138 members and are
  both live. Different weeks, nothing changed between them — which is ordinary for
  an `IN_FORCE_AT_CUTOFF` series and not duplication at all.
- Any rule keyed on `created` would order the clone/twin pairs arbitrarily,
  because their timestamps are equal.

`I-18` already guards the shape that actually hurts: two issues in one series with
`publicTo IS NULL`, i.e. two editions current at once, which is the archive
forking in public. It correctly ignores the EfS A pair, because both carry a
closing date.

**If a future reseed produces a genuine live duplicate, `I-18` catches it.** If
Rasmus wants the stricter check anyway, the shape is known and the one false
positive is documented above.

## Correction — the redirects are a HISTORY fix, not a gap fix

An earlier version of this document, and of the comment in `LegacyTemplateRulings`,
said the six `dont-use-` clones would close one of the nine gaps gap detection
reports on `weekly-ntm` — specifically "EfS uge 2 - 2025". **That was wrong.**
Measured: the gap count is nine with the clones and nine without. None is created,
none is closed.

It was a name coincidence. A MISSING pseudo-row is *named* from the ISO week of its
interval end, so "EfS uge 2 - 2025" names both a gap (2025-01-01 → 2025-01-08) and a
clone whose actual window is a week later.

What the clones actually are is the other half of a **withdrawal**. Each holds a
member set identical to an issue already in the destination, which legacy retired
when it swapped in the replacement — verified by comparing frozen member sets: 27 of
27 uids for "EfS uge 2 - 2025", 228 of 228 for the P&T equivalent.

The ruling still stands: they are not series. But filing them in **reunites a
withdrawal with its replacement inside one series** rather than recovering a missing
week — and it puts two issues with identical members into that series, which is a
decision rather than a tidy-up.

### A blocking defect this surfaced

The `ncags-2021` redirect shipped on 2026-08-26 made a fresh import **refuse to run
at all**. Redirects were resolved inside `planSeries`, which can only see
template-derived series — and `nm-annex-ncags` is authored by the ORPHAN pass, later.
So the ruling reported `RULED_DESTINATION_MISSING`, and because the import is
all-or-nothing that one problem refused all 1,077 issues.

It was invisible to every test, because they all inspected `plan.series()`, which is
read at the end after the orphan pass has added the destination. Fixed by resolving
redirects in their own step between `planOrphanSeries` and `planIssues`, and pinned
by `norulingRefusesTheImport`.

**This is exactly the failure mode the reseed rehearsal exists to catch**, and it is
the strongest argument for doing one soon: the defect lived in a deployed,
green-testing codebase for a day, and only a from-nothing import would have hit it.

---

## What the first reseed rehearsal found

A full prod-to-test restore was done, the publication tables were dropped so
Flyway rebuilt them (`drop-publication-tables.sql`), and the import was run from
nothing. It found two defects, neither of which was reachable any other way.

### The annex domain rulings never applied

`applyDomainRuling` ran inside `planSeries`, which only sees series a TEMPLATE
produced. Both annex rulings name series the ORPHAN pass produces, so neither
could ever apply: `nm-annex-ncags` and `nm-annex-ice-service` imported domainless
on every run.

It hid twice over, and the second reason is the one worth remembering:

- the deployed estate had those domains set **by hand** on the same day the
  ruling was written down, so the estate looked correct; and
- the only test asserting a ruled domain used `accumulated-yearly-ntm`, which is
  template-derived and therefore took the working path.

So the doc and the estate agreed with each other and both were wrong. Fixed by
running the rulings as a single pass over every planned series after both passes
-- the same shape as the redirect-ordering fix, and for the same reason. Pinned by
`aruledDomainReachesAnOrphanGroupedSeriesToo`, which creates the annex domain
rather than assuming it: this test database ships with `niord-nm` and little else,
which is exactly why the gap survived.

### A completed import reported failure

`PublicationSeriesRestService` carries a class-level `@Transactional` and
`importLegacy` did not override it, so every import ran inside TWO transactions:
its own `requiringNew().timeout(1800)`, and an ambient one on the DEFAULT timeout.
The inner one committed; the reaper had aborted the outer one long before, and the
commit afterwards failed with `ARJUNA016102: The transaction is not active`.

Measured: **65,734 rows written in 980.7s of an 1800s budget, followed by an HTTP
500**, with every row committed. An operator would re-run an import that had
worked. The three long endpoints are `NOT_SUPPORTED` now, and `dryRun` owns its
transaction for the same reason.

### What the rehearsal confirmed

| Check | Result |
|---|---|
| Flyway rebuilds the dropped schema | yes, verified twice |
| `problems[]` after a from-nothing plan | **0** |
| The six `dont-use` redirects | no stray series; editions filed |
| The six WITHDRAWN domain rulings | correctly did **not** apply |
| `SeriesKind` | 5 SCHEDULED, 5 ONE_OFF, 3 UNSCHEDULED |
| Series status | all DRAFT -- operational state correctly not reproduced |
| Import check | 0 violations, 0 id collisions, 0 duplicate memberships |
| Undo | 13 series, 1,081 issues, 64,640 members removed in 12.5s, no refusals |

### What the reseed takes with it

Every series comes back DRAFT. That is correct -- activation is an operational act
the importer deliberately does not reproduce -- but it means the estate has no
active series until somebody activates one, in the owner's domain, as step 7 of
the sheet.

Nothing else survives a reseed to be carried forward: there is no stored evidence
about the estate, and the public list is read from the rows themselves, so a
freshly imported estate serves what it holds the moment the import commits.
