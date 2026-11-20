# Firing Area Schedule

`/schedule` is the editor's daily data-entry surface for firing areas: pick a day, see every firing
area of the session domain with its firing periods for that day, edit each area's time intervals
inline, and trigger regeneration of the firing-exercise messages on demand.

It is **not** the Firing Schedules configuration page. `/sysadmin/schedules` defines which domain
maintains firing areas, which target domain and message series generated exercises land in, the
schedule horizon and the active flag — see [sysadmin](sysadmin.md). The two pages share one backend
resource and nothing else; this page never reads, writes or lists the generated messages.

Code: `ui-apps/admin/src/app/features/schedule/`.

## Route and access

| Fact         | Value                                                                                                                                                                                               |
| ------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Route        | `/schedule`, a single lazy route (`schedule.routes.ts`), no params                                                                                                                                  |
| Route gate   | `roleGuard` with `EDITOR_AREA_ROLES` (`editor`, `admin`, `sysadmin`) in `ui-apps/admin/src/app/app.routes.ts`                                                                                       |
| Nav item     | shown only when the user passes that role gate **and** `domain.capabilities().firingSchedule` is true (`core/main-shell/menu.service.ts`)                                                           |
| Unauthorized | authenticated → redirect home with an access-denied toast; anonymous → Keycloak login with the current URL as return target                                                                         |
| Wrong domain | role holder whose session domain has no firing schedule reaches the route and sees only the empty state "Domain has no firing schedule"; filters, table, map and the update action are not rendered |
| Help anchor  | route data's key `firing-exercise-schedule` opens the editor manual's Firing Area Schedule chapter (`#firing-exercise-schedule`)                                                                    |

The two gates are independent and sit in **different places**: the role gate is on the route
(`canMatch: [roleGuard]` plus `data.rolesRequired`) and the page does not re-check roles; the domain
gate is inside the page (`ScheduleView.firingScheduleDomain` wraps the whole body in `@if`), read
from `domain.capabilities().firingSchedule`. The nav item enforces neither — hiding it only keeps
the entry out of the menu. Role resolution (a client role on the session domain) is
described in [shell and auth](shell-and-auth.md).

## Layout

One centred column: a filter bar (free-text area name + an area picker with removable chips), a day
navigator (prev arrow, date picker, next arrow), then the schedule table beside a firing-areas map.
Below `lg` the map stacks under the table. From `lg` the map is a **fixed** square block
(`lg:h-128 lg:w-lg lg:shrink-0`) top-aligned next to the table (`lg:items-start`) — deliberate, so a
long table never resizes the map.

## The selected day is a browser-local noon anchor

The day is a _day selector_, not an instant. `ScheduleFacade.date` holds **browser-local noon**
(`noonOfLocalDay`, `shared/utils/zoned-date.ts`) and the date picker runs in local mode
(`[local]="true"`), so the day the user clicks is the day they get regardless of the domain's zone.

The anchor travels to the server as epoch milliseconds and the day is resolved **there**, in the
session domain's zone — the same zone the firing periods are written in; a firing area carries no
zone of its own. Noon survives that reading for any offset up to ±12 hours, which covers every zone
in use. Midnight would not.

`offsetDate(±1)` adds a whole day of milliseconds and re-anchors to local noon: the re-anchor
absorbs the hour a daylight-saving transition adds or removes, so the day number always moves by
exactly one. Do not "simplify" either of these into a UTC or midnight anchor.

## Search and filters

Date, free-text query, selected areas and the active language form one trigger; the search is
debounced 250 ms and de-duplicated on value (`ScheduleFacade`). Two _distinct_ server-side filters
ride the same request and must stay wired separately — folding them into one control changes the
search semantics.

| Parameter  | Sent                           | Meaning                                                                                                         |
| ---------- | ------------------------------ | --------------------------------------------------------------------------------------------------------------- |
| `date`     | always                         | epoch ms of the noon anchor; the server takes the day in the domain's zone                                      |
| `query`    | only when non-empty            | free-text area-name filter                                                                                      |
| `area`     | repeated, once per ticked area | area subtree to search; the picker does **no** ancestor/descendant propagation, the server resolves the subtree |
| `lang`     | always                         | decides which language the returned area descriptions carry                                                     |
| `inactive` | never                          | defaults to false server-side, so inactive areas stay out                                                       |

The picker itself is fed by a fourth request the page makes directly: an `httpResource` in
`ScheduleView` for `GET /areas/area-roots?lang=`, read through a `hasValue()` guard because
`value()` throws in the error state — a failed roots call therefore leaves the picker empty instead
of blanking the page, and says so under the filter bar ("Error fetching data", with _Retry_). That list is the **whole** area-root tree, not only firing areas; the server
intersects the ticked subtrees with the session domain's firing areas when it searches.

The result is scoped server-side to the session domain's firing areas and arrives in tree-sort
order. Area names on this page are display-only and single-language: the search is language-scoped,
so `descs[0]` is the active language (`ScheduleView.areaName`). Nothing on the page edits area
translations.

A failed search sets an error flag; `retry()` bumps an internal tick so the same filters re-run.

## Grouping by top-level area

Rows are grouped by the top ancestor within **two** levels (`topAreaWithinLevels`,
`shared/utils/group-by-top-area.ts`, `GROUP_LEVELS = 2`) — the same grouping the message list uses.
The facade marks the first row of each new group with an `areaHeading`, and the template renders it
as a `<th colspan="2" scope="colgroup">` heading row. The heading is inline here, not the messages
area-heading component.

## Inline period editor

Each data row carries an `app-time-schedule-editor` for that area on the selected day. It is
**commit-on-save** over a working copy: entering edit seeds a draft from the row's periods (one
blank interval when the area has none), Add / Remove / Clear mutate only the draft, Cancel discards
it, Save PUTs the whole per-area list and replaces the shown periods with the server response. The
source row is never mutated before a successful save. A new search result resets the row: the editor
closes and adopts the fresh server periods.

**Zone.** A firing period is announced in the **session domain's** zone, so that is the zone the
entered times belong to — not the browser's. A stored instant is seeded into the time fields as the
wall clock it reads at in that zone; a typed clock is resolved back in that zone on the selected
day. With no session domain there is no zone to resolve against, and the editor refuses: the pencil
and Save are disabled with a "no time zone" title, rather than silently falling back to the editing
user's own clock.

**Stored periods keep their identity.** The draft carries each stored period whole (`original`: its
`id`, `legacyId` and stored instants). The backend updates a period sent with an id, adds one sent
without, and deletes a period of the day that the body lacks only when its id is in the body's
`loadedPeriodIds`; it copies `legacyId` from the body. So the editor sends every stored period back
with its `id` and `legacyId`, and sends the ids of the periods it loaded (the row's search result,
or the answer of its last save) as `loadedPeriodIds`, an empty list for a day it opened without
periods. A period another tab added after this one loaded the day is not among them and is kept; a
body without `loadedPeriodIds` would replace the day.

| Draft row                                 | Sent                                                                                                          |
| ----------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| stored, untouched                         | exactly as stored (`id`, `legacyId`, stored instants)                                                         |
| stored, a time changed                    | its `id` and `legacyId`; each changed clock normalised onto the selected day, an unchanged one kept as stored |
| stored, starts on an earlier day (locked) | exactly as stored                                                                                             |
| new                                       | normalised onto the selected day, no id                                                                       |

A period that crosses midnight is listed on both days (one that ends exactly at the next day's
00:00 is listed on its start day only). On its start day it is an ordinary row, saved unchanged
while untouched; a changed start keeps the stored end on the next day, and a changed end is read on
the selected day, so an end before the start is flagged with the advice to end at 00:00 and add a
period from 00:00 on the next day (`schedule.editor.endBeforeStartPastMidnight`) rather than the
plain "enter 00:00", which would cut off the part after midnight. On the later day it is
**locked**: shown as text with the note "Starts on an earlier day. Edit it on that day.", with no
time fields and no remove button; Clear keeps it, and it is sent back unchanged.

An older period stored with its end before its start (see the order check below) whose end, moved
one day on in the domain's zone, falls after the next day's 00:00 (for example 22:00 to 02:00 of the
same day, which was meant to run past midnight) is also listed on the day after it starts, for its
early hours. There it is locked like any period of an earlier day: it is never flagged, holds no
Save back, and is sent back exactly as stored, which the backend keeps without its order check. It
is corrected on the day it starts. One stored as 09:00 to 00:00 of the same day (until midnight, no
early hours) or 08:00 to 23:00 of the day before is listed on its start day only.

**Save normalisation** (`rebaseOntoDate`, for a new row and a changed clock) — data integrity, not cosmetics:

| Draft interval                            | Stored                                                                   |
| ----------------------------------------- | ------------------------------------------------------------------------ |
| both ends blank                           | dropped silently                                                         |
| `from` blank                              | `00:00:00` on the selected day, in the domain zone                       |
| `to` blank                                | `23:59:59` on the selected day, in the domain zone                       |
| `to` is 00:00                             | `00:00:00` of the next day in the domain zone ("until midnight")         |
| a time entered                            | its hour and minute on the selected day, seconds and milliseconds zeroed |
| an end at or before its start (not 00:00) | refused inline: nothing is sent                                          |

The calendar day always comes from the day the user is looking at; only the clock is resolved in the
zone. That is what keeps a zone west of the browser from rolling a period onto the neighbouring day.
The next day's 00:00 is resolved in the zone too, so on a day the zone changes offset it is not
24 hours on.

**Order check.** A row that, as it would be saved, does not end after it starts gets an inline
`role="alert"` line ("The period must end after it starts. To fire until midnight, enter 00:00 as
the end."), and Save is disabled while any row is in error. A period the editor stored backwards
before this check existed (for example 09:00 to 00:00 of the same day, or 08:00 to 23:00 of the day
before) is listed on its start day and flagged the moment that day is opened. Its time fields show
only the two clocks, which can read as a valid period ("8:00 AM - 11:00 PM"), so as long as its end
is the stored one the row has a line of its own that names the stored end and asks for the end time
again (`schedule.editor.storedEndBeforeStart`: "This period was saved with its end at 24 Nov 2026,
11:00 PM CET, before its start. Choose the end time again to correct it."); an end typed before the
start gets the plain line, and the advice for a period past midnight keeps its own case. In the day
list such a period carries a warning mark whose accessible name and title say what is wrong and what
to do (`schedule.editor.storedBackwards`). Picking the end again (the time field emits a pick of the
time it already shows) puts it on the selected day, so the row saves with its id and the end on its
start day, or the next day's 00:00 for an end of 00:00. The backend refuses such a period with a 400
too, so only an API client meets that. The firing warning's text prints a period until midnight as
"hours 09:00 - 24:00".

Display (`FiringPeriodFormatter`) is time-only for a period within one day: `08:00 - 12:00 CEST`, in
the active locale's 12/24-h convention, with the zone abbreviation on the `to` end; when a clock
change lies between the two ends each carries its own (`01:30 CET - 03:30 CEST`), as Insert time's
intervals do, since the end's label alone would put the start an hour off. A period whose
ends fall on different days in the domain's zone (one until midnight, or one across midnight) shows
the date on both ends. So does a period whose end is not after its start, by its stored instants:
an older one stored as 22:00 to 02:00 of 24 Nov is listed on 25 Nov too, where `22:00 - 02:00 CET`
would read as that night, so it reads `24 Nov 2026, 10:00 PM - 24 Nov 2026, 2:00 AM CET` beside its
warning mark. Formatting goes through `LocaleFormatService` — see the dates and time zones
rules in [coding standards](../coding-standards.md).

A failed save toasts an error and keeps the editor open with the rows as typed, so Save can be
pressed again; the view behind it keeps the last server state.

## Update Firing Exercises

The header action (visible only in a firing-schedule domain) opens a neutral confirm and, on accept,
`PUT`s `/firing-schedules/update-firing-exercises`. The call answers when the run has finished, with
the number of schedules, areas and cancellations it could not do: all three 0 toasts success, any
other count a warning with their sum (the hourly run tries them again), and a failed call an error.
The button is disabled while the request runs. The server runs one schedule at a time, so a press
during the hourly run waits for it.

The server runs that same regeneration on a recurring job — **currently hourly**, not nightly — so
the button is only an on-demand re-run of it, nothing more. Know the blast radius before pressing
it: the job walks **every active firing schedule in the installation**, generating into each
schedule's own target domain and message series, not only the domain the button was pressed in.
That is why the e2e flow opens the confirm and cancels rather than accepting it against a shared
backend; only the opt-in duty-officer scenario, which runs on the sample deployment alone, accepts it.

The confirm's English text says the exercises are updated "every hour", which matches that
cadence; a language pack's own wording is its own. This page never shows the generated messages.

Firing-area message generation for a _specific_ area is a different action and lives in the areas
admin — see [admin](admin.md).

## Map

`app-ol-map` with the projected areas layer from the messages map kit — a sanctioned cross-feature
import (`features/messages` is the one-way hub; see [architecture](../architecture.md)). Facts
worth knowing:

- Base layers are **OSM plus the configured WMS only**; OpenSeaMap is explicitly switched off
  (`[openSeaMap]="false"`), unlike the messages map.
- The layer switcher sits bottom-left, clear of the zoom buttons and the map's credit line at the bottom right;
  the areas layer appears in it.
- `[fitExtent]="true"` — the view fits the returned areas on every result instead of persisting a
  view.
- Hovering a table row sets `hoveredAreaId`, which draws a hover halo on that area's polygon
  (`[hovered]`). Leaving the row clears it.
- `MapShellService` is provided on `ScheduleView` so the projected layers and the switcher share
  this page's map instance. Any map nested inside another map's subtree needs its own provider —
  see [architecture](../architecture.md).

## Loading, empty and error states

Three mutually exclusive branches in the table column, in this order:

1. **Error** — an error banner with Retry; the table is not rendered and the latch adopts the empty
   result, so the map clears rather than showing stale areas under the banner.
2. **Empty** — settled with zero rows: "No firing areas for this date".
3. **Rows** — the table.

An overlay progress bar is mounted above whichever branch is showing and is visible while
`facade.loading()` is true. Loading is therefore _not_ a fourth branch: rows and map areas are
_latched_ (`latchWhileSettled`), so a refetch keeps the table mounted and updates in place instead
of flashing empty — the empty branch is guarded on `!loading` for exactly that reason.

The gated-out state (no firing-schedule domain) replaces all of it.

## State and persistence

The page holds **no** URL state and persists nothing: day, text query and selected areas are
page-local signals on a route-scoped `ScheduleFacade` and reset when the page is left. Search
results are never cached beyond the view. The one persisted thing that affects the page is the
session domain id in `localStorage`, read during bootstrap — which is how the e2e spec arranges a
firing-schedule domain.

Switching domain reloads the browser, which is what resets the page for the new domain; there is no
in-place reset path.

## Deliberate limits

| Limit                   | Note                                                                                   |
| ----------------------- | -------------------------------------------------------------------------------------- |
| No dirty-cancel confirm | Cancel discards the draft immediately                                                  |
| Add does not move focus | the new row's `from` field is not focused (the shared time field exposes no focus API) |
| No deep-linkable day    | adding one means introducing a `date` query param, which does not exist today          |
| No inactive areas       | `inactive` is never sent                                                               |

An inactive firing area is also left out of the hourly firing-exercise run (`FiringExerciseService.getFiringAreas`,
`inactive(false)`, and `getFiringAreaMessages`, which keeps only warnings with an active firing area). A warning that
is in force when its area is set inactive is therefore neither updated nor cancelled: it stays out until its own end,
an editor can cancel it by hand, and setting the area active again lets the next run take it up. The old system did
the same (the two filters are unchanged since 2017), and the owner decided on 2026-11-12 to keep it; the error hunt's
candidate E3-4 was closed on that.

None of these is an accident. Anything worth revisiting is a row in the [backlog](../backlog.md).

## Tests

- `ui-apps/admin/src/app/features/schedule/schedule.facade.spec.ts` — grouping, request parameters, the noon
  anchor across zones, whole-day offsets across daylight-saving changes.
- `ui-apps/admin/src/app/features/schedule/components/time-schedule-editor/time-schedule-editor.spec.ts` — seeding
  and saving in the domain zone, the blank-end defaults, dropping empty intervals, the day-keeping
  rule for a zone west of the browser, the refusal with no zone, an end of 00:00 as the next day's
  midnight (also on the autumn change day), the order check, `id` and `legacyId` sent back, a period
  across midnight locked on the later day and saved unchanged on its start day, a stored backward
  period flagged and corrected, its own line naming the stored end, its warning mark in the list, and
  a stored backward period of the day before locked, not flagged and sent back unchanged.
- `ui-apps/admin/src/app/features/schedule/services/firing-period-formatter.spec.ts` — the time-only
  interval, the dates on both ends of a period across midnight and of one whose end is not after its
  start, the zone on the `to` end, a zone on each end across the spring and the autumn change, and
  one end on its own with its date.
- `ui-apps/admin/src/app/features/schedule/schedule-view.spec.ts` — the domain gate, the error/empty/rows branches
  and the latched table under the loading overlay, date
  navigation, picker and chip wiring, the update-exercises confirm and its toasts.
- `ui-apps/admin/e2e/schedule.spec.ts` — the route resolves and the shell mounts for an editor.
- `ui-apps/admin/e2e/schedule-flows.spec.ts` — the full flow against a seeded firing-schedule domain, including an
  add/save/remove/save round-trip that restores the row. "Update Firing Exercises" is only opened
  and cancelled there: accepting it regenerates messages on a shared backend.
- `ui-apps/admin/e2e/scenarios/firing-schedule.spec.ts` — the duty officer's day on the sample
  deployment (opt-in, `E2E_SCENARIOS=1`): periods entered on two days, Update Firing Exercises
  accepted, the warning read in both APIs, on the Status page, in its PDF and on the public site; a
  start changed, a period removed, a period across midnight kept, and a backend restart in between
  (`scenarios/README.md`).

## Files

| Path                                                                           | Role                                                                                     |
| ------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------- |
| `ui-apps/admin/src/app/features/schedule/schedule.routes.ts`                   | the single route                                                                         |
| `ui-apps/admin/src/app/features/schedule/schedule-view.ts` / `.html`           | page shell, filters, day navigation, map, update action                                  |
| `ui-apps/admin/src/app/features/schedule/schedule.facade.ts`                   | page-scoped state, debounced search, grouping, latching                                  |
| `ui-apps/admin/src/app/features/schedule/services/api/schedule-api.service.ts` | the three `/firing-schedules/*` calls (the area-root list is fetched by the view itself) |
| `ui-apps/admin/src/app/features/schedule/services/firing-period-formatter.ts`  | interval rendering: time-only within a day, dates on both ends otherwise                 |
| `ui-apps/admin/src/app/features/schedule/components/time-schedule-editor/`     | the inline interval editor                                                               |
| `ui-apps/admin/src/app/features/schedule/models/firing-area-periods.model.ts`  | wire shapes                                                                              |

Endpoint and permission details are in [backend API notes](../backend-api-notes.md); the short
version is that the search is an unrestricted read while both `PUT`s require the editor role, and
the `/firing-schedules/firing-schedule/*` CRUD on the same resource belongs to the sysadmin page.
