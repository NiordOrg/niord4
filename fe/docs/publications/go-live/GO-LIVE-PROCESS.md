# Go-live process

*How the publications redesign reaches production. This file is the binding process; the runbook
(`PRODUCTION-CUTOVER-RUNBOOK.md`) carries the mechanics and the findings, and `RE-RUNNABILITY.md` says what a
reseed reproduces and what it does not.*

**What the deployed backend does.** The public list is the union of two halves: a published issue in a
publishing category is served, and a legacy row is served until the issue imported from it has been
published. Nothing switches between them — there is no per-series flag, no estate-wide setting, and no
direction to run one in. The series status is not consulted. So the deploy itself moves no row on the public
site, because the new half is empty until the import: **the import is the moment the list changes hands**,
row by row, and every row it takes over keeps the id it had. An abort is a full database restore.

## 1. The rulings

| # | Ruling | Why |
|---|---|---|
| 1 | **One window, not two.** Import, verify, activate and open happen in the same service window. There is no passive period in production. | A passive period would have editors publishing in legacy while the new model's chain falls behind, ending in an undo the importer refuses once a series has left DRAFT. |
| 1a | **The process is identical on test and production.** Whatever is rehearsed on test is what runs in production, step for step; anything else rehearses something that is not the same. | |
| 2 | **The window opens immediately after a legacy weekly release** (a Wednesday), so the newest week is imported as PUBLISHED, the public list is unchanged across the import, and the next OPEN issue chains from the stamped cut-off. | Importing mid-window leaves a gap on the public site for the week people are reading. |
| 3 | **The go/no-go is `GET /publication-series/import-check`, and its `violations` must be zero.** There is no evidence to accumulate beforehand and nothing to sign off per series. | The check reads the estate that was just written. Anything earned earlier scores a different estate. |
| 4 | **The abort is a full database restore, at every step.** The restore procedure is rehearsed and its duration measured before the window opens. | The import refuses to run twice (`ALREADY_IMPORTED`, keyed on the legacy ids) and its undo refuses once a series is no longer DRAFT, so the restore is the only path that always exists. |
| 5 | **The operational steps the importer deliberately does not reproduce are part of the rehearsal**: activate the series in their owner domains, delete residue. They are a numbered checklist (§3) and the checklist is rehearsed, not only the import. | After every reseed they are redone by hand; a step nobody rehearsed is a step somebody forgets in production. |
| 6 | **"100 % perfect" is the checklist in §3.** Every row green; any red row → fix → reseed → rehearse again from the top. No partial credit. | Otherwise "perfect" is a feeling. |
| 7 | **The image is frozen after the final rehearsal.** The last rehearsal validates a specific image tag; production deploys that exact tag, and the tag is part of the window's record. The rules the release is checked against are in [`../domain-rules.md`](../domain-rules.md). Any fix afterwards means another full loop. **No users on production during the final rehearsal's backup or the window — no new data.** | |
| 8 | **The production dry run is a hard stop**, not a formality. The rehearsal proves the mechanism; the dry run on the day proves the data of the day. | |
| 9 | Carried into every rehearsal: `hibernate_sequence` is mid-count in production (never reset); the concurrency 500s (runbook §9) mean nobody on the system during the import and sequential verification afterwards. | |
| 10 | **UAT on test runs after the import on test**, and covers the consumers that are not ours: the public site has a test instance at **https://nautiskinformation.t-dma.dk/** pointed at this test environment — it is part of UAT. The legacy publications admin UI is not used for an imported series; decommissioning the tag coupling lands after go-live. | |
| 11 | **Every decision is frozen before the loop starts.** A ruling taken mid-loop changes the import and invalidates the rehearsal that just passed. | |
| 12 | Test may be reseeded as often as needed; test is ours for the duration of the loop. | |

## 2. The loop

```
decisions frozen (§1.11)
   │
   ▼
fix errors + missing pieces ──► reseed test from a prod backup ──► REHEARSAL (§3, steps 0–8)
   ▲                                                                       │
   └───────────────── any red row ◄────────────────────────────────────────┤ all green
                                                                           ▼
                                                             UAT on test (§3 step 9)
                                                                           │ signed off
                                                                           ▼
                                   freeze the image tag ──► fresh PRODUCTION backup (no users)
                                                                           │
                                                                           ▼
                                                        FINAL REHEARSAL on test from that backup
                                                                           │ 100 % green
                                                                           ▼
                                                        PRODUCTION window — same steps 0–8
```

## 3. The rehearsal checklist

> **The scripts that run it** live in [`scripts/`](scripts/): `rehearsal-precheck.sh` (steps 0–2, read-only),
> `rehearsal-import.sh` (steps 1–5, ~15 min), `rehearsal-activate.sh` (step 7), `rehearsal-smoke.sh` (step 8).
> Credentials come from `NIORD_USER` / `NIORD_PASSWORD`, the backend from `NIORD_API` (default: test), the
> audited reason from `REASON`, and every response lands under `$OUT`; the runbook §4.1 lists them. The owner
> list is read with `while read … || [ -n "$DOM" ]`, because a plain `while read` drops a last line that has
> no trailing newline — and that line is the firing-areas domain.

Identical on test and production except where marked *(test only)*. Every row is ticked with the measured
value written next to it; the ticked sheet is the window's record, kept with the release it validated.

**Step 0 — preconditions**
- [ ] Nobody is on the system; production: no users since the backup was taken.
- [ ] Window opens after the legacy weekly release; the newest `weekly-ntm` and `weekly-ntm-p-t` legacy rows are `ACTIVE`, none `RECORDING`.
- [ ] Backend, legacy frontend and niord-app deployed at the frozen image tag: ________
- [ ] The restore procedure for this environment is at hand, with its measured duration: ________ — it is the only abort.
- [ ] *(test only)* restore from backup dated ________; publication tables dropped (`drop-publication-tables.sql`); Flyway rebuilt V1..V17.
- [ ] Expected figures for this estate written down before starting (series / issues / members / gaps per series) — from the last dry run on the same backup.

**Step 1 — the public list BEFORE**
- [ ] `/public/v1/publications` and `/rest/publications/search` responses saved — the signature the import is diffed against

**Step 2 — dry run** `POST /rest/publication-series/import-legacy/validate`
- [ ] `problems: []`
- [ ] figures equal the expected figures above (series ____ issues ____ members ____)
- [ ] **HARD STOP if either fails.**

**Step 3 — import** `POST /rest/publication-series/import-legacy`
- [ ] HTTP 200 (a 500 no longer means anything by itself — read the import check before concluding)
- [ ] duration ______ s (budget 1800 s; must leave room for one retry inside the window)

**Step 3b — schema check** *(after the import, before the import check)*
- [ ] one boot against the restored copy with `quarkus.hibernate-orm.database.generation=validate` and `quarkus.flyway.migrate-at-start=false` starts cleanly — entity/schema drift fails here, loudly, not mid-window

**Step 4 — import check** `GET /rest/publication-series/import-check` — **the go/no-go**
- [ ] `clear: true` over `importedIssues = ____` (**never** over 0)
- [ ] `violations: []`, `triggerAudit: []`, `idCollisions: 0`
- [ ] `duplicateMemberships: 0`, `duplicateOverrides: 0`
- [ ] **`counts.seriesWithoutOwner: 0`** — every publication names the desk that owns it; a `SERIES_WITHOUT_OWNER` violation is a hard stop, and the count is written even when it is zero
- [ ] **`counts.publishedIssuesWithoutStamp: 0`** — every released imported issue carries a publish stamp; without one the legacy row it replaced stays on the public list and the document is served twice, so a `PUBLISHED_ISSUE_WITHOUT_STAMP` violation is a hard stop, and the count is written even when it is zero
- [ ] `series[…]`: `gapCount` per series equals the ruled expectation; `counts.seriesOfKind*` as expected — neither moves `clear`, both are read
- [ ] **Anything red here ends the window: restore.**

**Step 5 — the public list AFTER**
- [ ] diff against step 1: same ids, same category-priority order, the newest week present exactly once
- [ ] **read a name** — one series and one issue inside it, in the admin list. The import check counts rows, not content; a name rendered as an id is the one defect it cannot see
- [ ] the newest issue's file opens from the public list

**Step 6 — estate shape**
- [ ] gap count per series equals the ruled expectation (weekly-ntm: ____)
- [ ] `SeriesKind` counts as expected (SCHEDULED ____ / UNSCHEDULED ____ / ONE_OFF ____)
- [ ] no residue series (`seed-probe-*`, `efs-rehearsal`) present
- [ ] every ruled domain in place; every series that is to be activated passes S-20
- [ ] **every series carries its expected OWNER** (the table below) and the six citation publications read `availability: ALL_DOMAINS`

**Step 7 — activate, PER OWNER DOMAIN**

Activation is an admin **write**, and every admin write on a publication is scoped to the domain in the
**`NiordDomain` header** — which must equal the series' **owner**. **Sysadmin is not exempt**; the header
decides, as everywhere in the admin area. So step 7 is worked once per owner domain, not once for the
estate: switch domain, activate that desk's series, move on. A series outside the header domain is refused
(`403 NOT_IN_DOMAIN`).

The public list does not wait on this step — it changed hands at the import. What waits on it is the next
release: publishing from a DRAFT series is refused (`SERIES_NOT_ACTIVE`), its gap detection is off, and the
message editor's publication lookup passes over it. The estate has to be ACTIVE before editors come back.

| Owner domain | Series to activate |
|---|---|
| `niord-nm` | weekly-ntm · weekly-ntm-p-t · accumulated-yearly-ntm |
| `niord-almanac` | efs-a |
| `niord-fa` | firing-practice-areas |
| `niord-annex` | nm-annex-ice-service · nm-annex-ncags · journal-number · aids-to-navigation · list-of-wrecks · www-danskehavnelods-dk · danish-list-of-lights · navigation-through-danish-waters · **and every other imported series whose template named no domain** (the importer's default is `niord-annex`) |

- [ ] `NiordDomain` = the series' owner for every call (sysadmin included)
- [ ] each returns ACTIVE; gap detection switches on; public list still identical to step 5
- [ ] a series with no owner is **not** activated blind — it is a `SERIES_WITHOUT_OWNER` violation at step 4 and the window did not get here
- [ ] **no hand edit was needed** — the importer strips the derived keys from the imported `reportParams`, so the two weeklies activate as they are
- [ ] the newest issue of each series is PUBLISHED; the next issue is OPEN and chained from the stamped cut-off

**Step 8 — consumers**
- [ ] https://nautiskinformation.t-dma.dk/ *(test)* / the production public site lists publications and opens the latest PDF
- [ ] message editor: publication picker lists the issues; citation chip resolves; `?publication=` filter in the messages list resolves to the issue's own members
- [ ] from a domain that owns **none** of them, the six citation publications (journal number, list of lights, wrecks, havnelods, aids to navigation, navigation guide) are still offered by the picker and still resolve — and the admin Publications list in that domain does **not** show them
- [ ] no mailing-list trigger names a weekly tag (`triggerAudit` from step 4)

**Step 9 — UAT** *(test only, no developer present)*
- [ ] open issue: curate (include AND exclude, with reason, why-line visible)
- [ ] publish NOW → successor auto-created and chained
- [ ] retro-create a gap week with a chosen cut-off time → publish → predecessor/successor capping correct
- [ ] amend a published issue (replace in place, same links)
- [ ] retire + reactivate an issue
- [ ] one-off: upload a file, set a link, publish
- [ ] create a new series from scratch; edit criteria; preview members
- [ ] categories: create/edit/reorder; JSON export + import
- [ ] the new surfaces: availability control, "Move to domain", per-domain citation picker, orphaned-share chips
- [ ] every result visible on nautiskinformation.t-dma.dk

**Step 10 — record**
- [ ] durations, figures and the ticked sheet kept as the record of the window

**Pass = every applicable row ticked. Anything else = loop.**

## 4. The abort, once

**Every abort is the same abort: restore the database from the backup the window started on, and redeploy
the previous backend if the restore predates the schema.** There is no application-level way back — no
switch to return, no per-series step to undo — and the two narrower mechanisms both run out:

- `DELETE /rest/publication-series/import-legacy` clears an import **only while every imported series is
  still DRAFT**, and it refuses with 409 and every reason once one is not. It is the abort path between the
  import and step 7 and nowhere else.
- A second import is refused outright (`ALREADY_IMPORTED`, keyed on the legacy ids). **So a rehearsal always
  starts from a fresh restore**, and so does a retry after an abort.

What has to be rehearsed, therefore, is the restore itself: run it once on test from a real backup, measure
it, and write the duration into step 0. A window whose abort path has never been timed has no abort path —
it has an intention.
