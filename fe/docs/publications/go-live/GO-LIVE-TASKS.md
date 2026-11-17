# Remaining tasks before go-live to production — with the main actor

The backend that carries `V17` — the switch dropped, the import check as the go/no-go — is not deployed
anywhere yet; every environment runs the build loop #5 measured, and the test estate holds that loop's
import. **Loop #6 is therefore the first rehearsal of the sheet in
[`GO-LIVE-PROCESS.md`](GO-LIVE-PROCESS.md) §3, and it is the open item this list is built around.**
**Actor** = who must act; "both" = a joint session.

## A. Now — before loop #6

| # | Task | Actor | Notes |
|---|---|---|---|
| A1 | Deploy the backend carrying `V17` to the test environment | **Rasmus / IT** | V17 drops `PublicationSeries.publicAuthority`, deletes the `SERIES_AUTHORITY_CHANGED` audit rows and drops `ShadowDiffRun`. On the reseeded database it applies as part of the V1…V17 rebuild; on the estate as it stands it applies in place. |
| A2 | Deploy `niord-app` to the test environment, and set up its production deploy | **Rasmus / IT** | The new UI exists only on the dev servers. UAT "without a developer present" and the public go-live both need a deployed UI; step 0 of the sheet lists it. |
| A3 | Four Danish strings in the cross-domain publication panel — title, body, `noAccess`, `switch` — still carry the `ai_` prefix and render with it | **Rasmus** (Claude applies the file) | The rest of the Danish pass is accepted. |
| A4 | Fix loop for anything A1/A2 turn up; re-read the two specifications and the sheet against the deployed build | **Claude** | Background chores; nothing here needs a decision. |

## B. Loop #6 — the first rehearsal on the sheet as it stands

| # | Task | Actor | Notes |
|---|---|---|---|
| B1 | Fresh restore of a production backup; publication tables dropped (`drop-publication-tables.sql`); Flyway rebuilds V1…V17; `DELETE import-legacy` answers `409 nothing to undo` | **Rasmus / IT** | The undo refuses once a series has left DRAFT, so only a restore resets test. |
| B2 | Expected figures written down before the run (series / issues / members / kinds / cut-off sources / `seriesWithoutOwner`) | **Claude** | From the dry run on the fresh restore; runbook §4 is the sheet. |
| B3 | Steps 1–5: the public list BEFORE, dry run, import, **step 3b the schema check**, import check, the public list AFTER — including reading a series name and an issue name, and opening the newest file | **Claude** (3b needs a config switch on the deployed container: **IT / Rasmus**) | **3b has never been executed on any loop** and is a ticked row on the production sheet. Read the Azure setting `NIORD-HIBERNATE-DB-GENERATE` first; the runbook's loop #4 row says what the container can see that a local run cannot. |
| B4 | Steps 6–8: estate shape, activation per owner domain with no hand edit, consumers | **Claude** drives, **Rasmus** picks the moment (Wednesday, after the weekly release) | The boundary matters for production, where the newest weekly must be ACTIVE and none RECORDING. |
| B5 | **Rehearse the restore and measure it** — the abort path at every step, and the only one | **IT / ops** | Ruling 4 of the process. Nowhere recorded yet; the duration goes into step 0 of the sheet. |
| B6 | Step 9 UAT on test, no developer present — the checklist rows plus the new surfaces: availability control, "Move to domain", per-domain citation picker, orphaned-share chips | **Rasmus** | Everything else open on this sheet apart from 3b folds into the UAT. |
| B7 | Step 10 record: durations, figures and the ticked sheet | **Claude** | The measured rows go at the end of the runbook, beside loops #2–#5. |
| B8 | Review against [`../../definition-of-done.md`](../../definition-of-done.md), signed | **Rasmus** | The loop's exit condition. |

## C. Freeze and final rehearsal

| # | Task | Actor | Notes |
|---|---|---|---|
| C1 | Fix loop for UAT findings → new backend/frontend commits → pushed | **Claude** | Each fix re-runs the affected checklist rows. |
| C2 | Declare the decision freeze (ruling 11) | **Rasmus** decides, **Claude** writes | |
| C3 | Freeze the image tag (backend + niord-app + legacy frontend); write the tag into step 0 of the sheet and into the record of the window | **Rasmus** (tag), **Claude** (docs) | |
| C4 | **Final rehearsal**: fresh PRODUCTION backup taken with no users → restored on test → steps 0–8 once more at the frozen tag → 100 % green, no workaround | **IT / ops** (backup, restore), **Claude** (run), **Rasmus** (the moment) | Ruling 7: identical to production. |

## D. Production window

| # | Task | Actor | Notes |
|---|---|---|---|
| D1 | Schedule the window immediately after a Wednesday legacy weekly release (newest weekly rows ACTIVE, none RECORDING); notify editors; block access from the backup until the window closes | **Rasmus** (date), **IT / ops** (access) | Not scheduled yet. |
| D2 | Deploy backend, legacy frontend and niord-app at the frozen tag to production | **Rasmus / IT** | Manual Azure DevOps deploys. The deploy moves no row on the public site; the import does. |
| D3 | Run the sheet in production: steps 0–8, then step 10 record | **Rasmus** operates with **Claude** on the line | Same scripts and same sheet as the rehearsal. |
| D4 | Post-window watch: the first native weekly release from the new model (the Wednesday after), the public site, the mailing-list triggers | **Rasmus** (+ Claude) | |

## E. After go-live (ruled as sequencing, not omission)

| # | Task | Actor |
|---|---|---|
| E1 | Decommission the tag coupling and lock the legacy publications admin; gate the anonymous leak; build the auto-release abort path | **Claude** (after a ruling by **Rasmus**) |
| E2 | Delete the temporary `test-*` Keycloak users | **Rasmus** |
| E3 | The time-zone walk-through and the enforcement-mechanism ruling (outside publications) | **both** |
| E4 | The accessibility run (axe baseline) and the remaining non-blocking leftovers: the `messagePublication` lock on `publishedIssueCount`, the `CONCURRENT_PUBLISH` contract text, the concurrency 500s on parallel GETs (a deployment property), the one unexplained `weekly-ntm` release in the archive | **Claude** (500s: **IT / ops**) |

**Critical path:** A1–A2 → B1 → B3–B4 → B5 → B6 → B8 → C1–C3 → C4 → D1–D3.

## Open, and blocking nothing else until loop #6 runs

- **Step 3b, the schema check.** Never executed against a deployed container, on any loop. It needs two
  configuration properties and one restart, so it needs whoever owns the container.
- **The restore, timed.** It is the abort at every step of the window and nobody has measured it.
- **UAT without a developer present.** The one row that cannot be delegated to a script.
- **The four `ai_` Danish strings** in the cross-domain publication panel.
