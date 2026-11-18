# Editor

The Editor area (`/editor`) is where a navigational warning (NW) or notice to mariners (NM) is
created, edited, moved through its lifecycle, commented on and inspected. It is one routed shell
with four view-mode tabs (Edit / Status / Comments / History) plus a full-page template-execution
workspace, all sharing a single in-memory working copy of the message.

Code lives in `ui-apps/admin/src/app/features/editor/`. The geometry/positions editor it embeds is shared
(`ui-apps/admin/src/app/shared/components/geometry-editor/`) because the admin Areas and Charts editors use the
same component.

---

## Routes and entry paths

All children hang off one shell route (`editor.routes.ts`) whose `providers` supply `EditorFacade`,
`EditorCommentsStore`, `EditorFieldsService`, `MessageStatusService` and the tag-action services, so
the shell and every child share one state instance.

| Route                             | Purpose                                                   |
| --------------------------------- | --------------------------------------------------------- |
| `/editor`                         | Start screen: create cards + recently-edited drafts       |
| `/editor/edit/:id`                | Open an existing message (pristine)                       |
| `/editor/copy/:id`                | Plain copy into a new draft                               |
| `/editor/copy/:id/:referenceType` | Copy carrying a reference relation to the original        |
| `/editor/edit`                    | Create from a transient seed (blank or a template result) |
| `/editor/execute-template`        | Template-execution workspace                              |
| `/editor/status/:id`              | Status page                                               |
| `/editor/comments/:id`            | Comment thread                                            |
| `/editor/history/:id`             | Revision history                                          |

There are five ways a working copy comes into existence:

1. **Open** an existing message by id — starts clean, Save disabled.
2. **Copy** an existing message, optionally with a reference relation.
3. **Create blank** — the Start screen seeds `{ id: '', mainType }` and enters `/editor/edit`.
4. **Create from a template** — the template-execution result is seeded and enters `/editor/edit`.
5. **Re-run a template** on an open message — the execution view applies the result back.

Paths 2-5 are _born dirty_: the loaded content is not what the database holds, so the message reads
_Unsaved changes_ and can be saved immediately without touching a field. Path 5 counts too, though
the message keeps its `created`: the template's text is only in the browser until saved.

`mainType` (NW vs NM) is chosen only at creation and is never rendered as an editable field. Which
main types may be created is gated per domain (`DomainFacade.supportsMainType`) and on the `editor`
role (`EditorFacade.canCreate`).

### Guards

| Guard                    | Where                                       | What it does                                                                                                                                                                                                                                                                                                                                    |
| ------------------------ | ------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `editorExitGuard`        | the shell route                             | Confirms before leaving the editor entirely — the shell teardown drops the working copy. Tab switches never deactivate the parent, so it cannot fire inside the editor.                                                                                                                                                                         |
| `unsavedChangesGuard`    | every child route except `execute-template` | Confirms when the _next_ URL is an edit-family route (`/editor/edit*`, `/editor/copy*`) that would **replace** the working copy, and when it is the Start screen while the message is not stored. Returning to the Edit tab of the same id passes silently (the load dedupes onto the live copy).                                               |
| `templateSeedGuard`      | `/editor/edit`                              | Redirects to the Start screen when the transient seed store is empty (a reload or deep link) instead of surfacing a dead error panel. Back from the execution page to a new draft it was opened from (on the bare `/editor/edit`) re-seeds the store from the working copy instead.                                                             |
| `templateExecutionGuard` | `/editor/execute-template`                  | Keys on the execution view's _own_ local dirtiness — the shared working copy is deliberately untouched until Execute — and applies the same Start-screen rule and the same replacing rule as `unsavedChangesGuard`: Back to a copy draft (`/editor/copy/:id`) or to another message's Edit tab asks while the working copy has unsaved changes. |

Both unsaved-changes guards key on `hasUnsavedChanges`, not on the dirty latch. Opening a copy, a
template's result or a new message and leaving it unsaved asks before it is dropped; reverting an
edit on a stored message does not ask. Programmatic navigations (save → reload, Clear, cancel →
create-cancellation, template hand-off) are allow-listed **one hop at a time** through
`EditorFacade.bypassGuardOnce()`; `applyLoaded` drops any bypass a guard-less source route never
consumed, so it can never leak into a later genuine prompt. A `beforeunload` handler on the shell
covers tab close. A half-typed comment counts as unsaved for `editorExitGuard`, `unsavedChangesGuard`
and `beforeunload` (see [Comments](#comments)).

Going to the Start screen (the browser's Back included) with edits on a stored message passes
silently: opening the same message again dedupes onto the working copy, edits intact. A message the
database does not hold (no `created`: a copy, a new message, a template's result) cannot be reached
again from Start, so the guard of the page being left asks first, and on _Yes_ drops the working
state (`resetWorkingState`), so neither the shell's leave guard nor a later Forward asks again for
work that is gone. The rule is one helper (`confirmStartWithUnstoredMessage`), used by
`unsavedChangesGuard` and by `templateExecutionGuard`: Back from a new message made by Execute
lands on the execution page, which finds its seed used and goes to Start, and that move asks too
(one prompt, which also covers the page's own typed parameters).

`templateSeedGuard` finds the template store empty on every return to `/editor/edit` through the
history, because the edit form used the seed up. When the page being left is the execution page and
the working copy is a message the database does not hold (the new draft on the bare `/editor/edit`
that "Run template…" was opened on), it re-seeds the store from the working copy, as the execution
page's own Cancel does, so Back returns to the draft. From any other page it redirects to Start; when the working copy is unstored
it is dropped first, since that page's guard has already asked about replacing it, so the redirect
does not ask a second time. A reload, or a Forward after a discard, finds the facade empty and goes
to Start.

"Run template…" is offered on a copy too (`/editor/copy/:id`), and that route has no seed: going
back to it loads a fresh copy over the working copy. So Back from the execution page to a copy
draft is a replacement, and `templateExecutionGuard` applies `unsavedChangesGuard`'s replacing rule
(one helper, `replacesWorkingCopy`): while the working copy has unsaved changes, the move asks first
(one prompt, which also covers the page's own typed parameters), and _No_ keeps the copy with what
was typed into it. The same holds after Execute on a copy: Back to the execution page, _No_ at its
move to Start, then Back once more to the copy route asks before the executed message is replaced.
Going back to the Edit tab of the same stored message passes, as on every editor page.

The router runs with `canceledNavigationResolution: 'computed'` (`app.config.ts`): when a guard's
_No_ cancels the browser's Back or Forward, the history moves back to the entry the user was on, so
the page they came from stays in the history and a second Back or Forward still works. This holds
for every guarded route of the application, not only the editor's.

---

## Who may edit, who may only read

- The shell route admits `EDITOR_VIEW_ROLES` (`user`, `editor`, `admin`, `sysadmin`). A plain `user`
  may open, read and comment — every mutation is gated separately.
- The nav item is deliberately stricter than the route — a plain `user` reaches the area only by
  deep link or a message-row link; see [shell and auth](./shell-and-auth.md) → _Areas and who may
  enter them_ before widening either.
- A message is **editable** while `DRAFT` or `VERIFIED`, or always for an `admin` (admins may edit
  `PUBLISHED` and `CANCELLED` messages).
- `EditorFacade.editableNow() = editable() && isEditor()` is the single "may mutate now" gate. Every
  form-control `disabled` rule, every part control and the Save button key off it, so they cannot
  diverge; the facade's structural mutators refuse when it is false, so a broken UI cannot write
  either.

---

## The working copy

`EditorFacade` (route-scoped, not root) owns one `message` signal plus its lifecycle invariants.

### The flags

| Flag            | Meaning                                                                                                                                                                                                                                                             |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `dirty`         | One-way latch, born `true` on copy/create/template, cleared only by a load or a save. No control reads it any more.                                                                                                                                                 |
| `userTouched`   | Set only by a genuine user edit — never by a programmatic load.                                                                                                                                                                                                     |
| `changed`       | The working copy's canonical content signature differs from the immutable loaded baseline. Programmatic writes the user did not make (adjust copy-back, per-language desc padding, `''`/`undefined` normalisation, part re-indexing) compare equal and never count. |
| `_unstoredLoad` | Private: the loaded content is not stored. `applyLoaded` sets it from its `dirty` option (paths 2-5); an ordinary load, `reload`, the post-save reload and `resetWorkingState` clear it.                                                                            |

`hasUnsavedChanges = (userTouched && changed) || _unstoredLoad` drives the header's save-status
chip, the Save button, the Clear and Reload confirms, both discard guards, `beforeunload`, the
template page's replace confirm before _Execute_ and the status buttons. Reverting an edit on a
stored message clears it; on a message never saved (or a template's result not yet saved) it stays
until a save or a reload. The sticky `dirty` latch does not clear on a revert.

### Race guards

Two monotonic counters protect the working copy:

- `loadToken` is bumped before every async load/copy/create/reload; a response applies only if its
  token is still latest, so navigating to a sibling message mid-fetch cannot clobber the newer one.
- `adjustSeq` is bumped on every fired adjust **and** every load, so a superseded or racing adjust
  response is dropped. A load also cancels any pending debounced adjust.

`loadSeq` is a separate public counter bumped on every genuine load; the edit view repopulates its
form on that alone, never on the per-field patches it makes itself (which would loop).

### Trap: the route injector outlives the visit

Angular scopes a route's `providers` to the route **config**, not the activation, so `EditorFacade`
is _not_ recreated when the user leaves and re-enters the editor. `EditorShell.ngOnDestroy` calls
`resetWorkingState()`; without it a discarded draft would resurface through the same-id load dedupe.
Downstream session state (field expand/opt-ins) resets by keying on the cleared baseline.

### Errors

A failed load sets `loadStatus = 'error'` and every view (edit, Status, Comments, History) renders an
inline panel with the facade's `loadErrorPanel` text. Retry, which replays the last load action, shows
only while `canRetryLoad` holds; otherwise the panel offers _Back to the start screen_
(`leaveToStart()`, which drops the working state first). A `403` on a load or a copy means the message
is outside the session domain, or the user may not open it, and Retry can never fix it: the editor
drops the working state, stays on the address and says so ("not in the current domain, or you may not
open it; choose its domain in the header"; a copy says it cannot be copied in the current domain). A
domain switch reloads the application at the same address, so choosing the message's domain in the
header then opens it; the same reload is why a switch away from an open message shows this panel too
(the editor cannot tell it from a followed link). The 403 does not name the message's domain, so no
one-click switch is offered. A load with nothing to replay (no template, a refused create) shows no
Retry either. Mutation failures toast — see [coding standards](../coding-standards.md).

---

## The edit form

### One Signal Forms value model

`EditForm` holds a **single** Signal Forms value model carrying only the message's scalar and
per-language value fields: type, series id, number, short id, the three dates, the boolean flags,
`horizontalDatum`, and `Record<lang, string>` slices for titles, vicinities and sources (N
languages, from the configured language list — never a hardcoded two).

Everything structural — references, parts, geometry, areas/categories/charts membership,
attachments, promulgations, publication text, field opt-ins — is deliberately **not** mirrored
there. Each flows through a cohesive `EditorFacade` mutator, so there is never a second write path
for the same state.

The bridge is one guarded sync point in both directions:

- **facade → form** repopulates only when `loadSeq` bumps.
- **form → facade** runs on a serialized-snapshot diff: a genuine edit marks touched, patches the
  message, and derives the adjust cadence from the prev/next diff. No per-field `valueChange`
  handler writes the same slice twice.
- A third narrow effect mirrors an out-of-band `message.type`/series change (the promulgation
  auto-deduce) back into the model, re-seeding the guard first so it does not re-patch or re-adjust.

Editability is one schema `disabled` rule per control, all keyed on `editableNow()`; the two
date rules narrow that baseline further (below).

### Field visibility

The editable-message payload carries `editorFields`, a `fieldId → shown` map the backend recomputes
from the message's area, category and series (over the deployment's `editorFieldsBase` setting) on
every load **and** every adjust. The frontend consumes it read-only.

`EditorFieldsService` unions that map with a static field universe that supplies opt-in candidates
for keys the map omits and pins the order of the "Manage optional fields" menu
(`components/manage-optional-fields/`):

- `showField(id) = map says shown || the user opted in this session`. Opt-ins are session state and
  reset on load; opting in does not flip the backend map.
- Always-shown core rows (`type`, `id`, `title`, `areas`, `categories`, `publish_date`) are never
  opt-in-able and are excluded from that menu even when the map carries them present-but-false.
- `note` is excluded too: NOTE is a message-**part type** chosen from the part-type switcher, not a
  standalone row, so an opt-in would reveal nothing.
- References and Charts are _sticky-visible_: they render whenever the message already carries
  references or charts, whatever the map says.
- The map spells the event-dates gate `time`; call sites spell it `event_dates`. Both spellings are
  accepted everywhere and collapse to one canonical key.
- Four fields are **part-indexed** (`event_dates`, `positions`, `subject`, `description`): their
  expand/edited-state arrays hold one boolean per part and are spliced in lock-step with part add /
  remove / move.

There are six part types, each button of the part-type switcher gated separately, and each per-part
row gated on the part's type as well as on the map:

| Part type                              | Switcher gate |
| -------------------------------------- | ------------- |
| `DETAILS`, `TIME`, `POSITIONS`, `NOTE` | `part_types`  |
| `PROHIBITION`                          | `prohibition` |
| `SIGNALS`                              | `signals`     |

- A part already **of** a type always shows that type's button, so an existing type stays visible
  and changeable whatever the map says.
- When the backend omits the `part_types` key **entirely** the four-type group renders anyway — the
  switcher is never unreachable.
- Event dates need a `DETAILS` or `TIME` part (or intervals already on the part); positions need
  `DETAILS`, `POSITIONS` or `SIGNALS` (or geometry already on the part); the subject line is
  `DETAILS`-only; the description body renders on every part.

A fixed field list anywhere in the edit form is a bug. Per-deployment differences in which fields
appear ride entirely on `editorFields`, never on a frontend branch.

### Identifiers and series

- The series dropdown offers the session domain's series, narrowed to the message's `mainType`.
- A message type is offered when **any** matching series permits it; a series with an empty or
  absent `types` list permits all types. Picking a type when exactly one series permits it
  auto-selects that series.
- Number vs short-id editability are mutually exclusive and series-driven:
  `numberSequenceType === 'MANUAL'` **with** a short format makes the **number** hand-editable;
  `MANUAL` **without** one makes the **short id** hand-editable. Otherwise a read-only id badge is
  shown, and the whole short-id row is hidden when the sequence type is `NONE`.
- `rehydrateMessageSeries` replaces a pruned series with the full domain object on load. A series
  belonging to **another** domain is preserved untouched so an unrelated field edit cannot wipe it
  from the save payload.

### Dates

`publishDateFrom` is editable only while `DRAFT`/`VERIFIED`; `publishDateTo` and the follow-up date
stay editable through `PUBLISHED`. These rules **narrow** the `editableNow()` gate and never widen
it. Event-date intervals live on the part: an all-day interval is snapped to the whole-day bounds of
the **domain's** zone, and duplicating an interval with a day offset shifts the wall clock in that
zone (DST-safe). With no session domain there is no zone, and the editor refuses rather than
resolving a day in the browser's zone. See "Dates and time zones" in
[coding standards](../coding-standards.md).

### The adjust round-trip

`POST /messages/adjust-editable-message` re-derives server-owned values. It is the only thing that
refreshes the auto-generated title, the short id and the field-visibility map while editing.

| Trigger                                | Cadence                              |
| -------------------------------------- | ------------------------------------ |
| Type selected                          | immediate, `inclPromulgations: true` |
| Series selected                        | immediate                            |
| Areas changed                          | immediate                            |
| Part add / remove / move / type change | immediate                            |
| Number, vicinity, subject keystrokes   | debounced 500 ms, flushed on blur    |

It must **not** fire on: part body/details edits, categories, charts, geometry, references,
attachments, promulgation field edits, or publication text. Routing those through mutators that
never adjust is what keeps the cadence structural rather than a convention.

The merge (`applyAdjustment`) is selective, never a blind replace: titles are copied per language
only while `autoTitle` is on and only for languages already on the working copy; the short id
follows only when the number drives it; promulgations only when requested; `editorFields` always
follows. A failed adjust is logged, not toasted — nothing is lost, because the working copy is only
touched on a fresh response.

### Message parts

A message always has at least one part; removing the last one re-adds an empty part. Parts are
positional — the UI addresses them by array index while the wire field is `indexNo` — and are
re-indexed on every add, remove and move. Every structural commit also re-threads the message-wide
`startCoordIndex` across all parts (see [Geometry](#geometry-and-positions)). The Parts panel head
owns the Add affordance; each card carries a drag grip plus move-up/move-down/remove. Every one of
those operations pairs the facade mutator with the `EditorFieldsService` splice so per-part row
state follows its part.

The seeded first part carries `hideSubject: true` — as the only part, its subject would repeat the
title composed from that same subject — while a part added later leaves the flag unset, because with
two or more parts the subject is what tells them apart. It is a **default at creation, never a
correction**: nothing re-derives it afterwards, so a user who unticks it keeps that choice through
every later save.

### Live preview

The right-hand pane renders the working copy through the shared message-details renderer, with the
status stepper on top. Above 1080 px it is an inline splitter pane with a draggable (and
keyboard-resizable) width; at or below 1080 px it flips to a header-triggered drawer. Clicking a
region of the preview expands the matching edit-form row, scrolls it into view and focuses its head
— resolved from an explicit `data-pv-field` marker where present, otherwise from the nearest known
renderer child component.

---

## Saving

- Dispatch is `POST` (create) when `message.created` is false and `PUT` (update) when true — never on
  the presence of an id. A copy or template result carries an id but is not `created`.
- The first save is the safety-critical moment: the backend assigns the permanent id and moves the
  temporary edit repository folder to its permanent location, so the editor re-resolves the saved id
  to pick up the new `revision` and `editRepoPath` before anything else touches repository files.
- Pre-save validation is synchronous: main type + type required, series required, and the publish
  interval rejected only when **both** endpoints are set and `from > to` (a from-only interval is
  valid).
- Saving a `VERIFIED` message resets it to `DRAFT` server-side; the header confirms before saving.
- After a save from an edit-family route the editor navigates to the canonical `/editor/edit/:id`
  (the first save of a copy mints the real id). Saving from the Status / Comments / History tabs
  stays put.
- While a save is in flight the edit form's pane is `inert` and `aria-busy`: the save sends the
  message as it stood at the click and then repopulates the form from the server's copy, so an edit
  made in the round trip would be overwritten without a word. The header and the preview stay live;
  a field that had focus loses it for the round trip.
- Save lives on the shared editor header, not on the edit form, because the save gate spans the
  whole editor: unsaved edits survive tab switches, so the user must be able to save from any tab.
- `created` ("saved at least once") also gates the Status / Comments / History tabs, the Reload
  button, Print and the tag actions. The Edit tab is always present.

Concurrent edits are **last-write-wins**: the message REST API offers no locking or optimistic
concurrency, so two editors saving the same message overwrite each other.

---

## Status workflow

`MessageStatusService` owns the state machine. Every transition is double-enforced — the button is
rendered only for the right status _and_ the service re-checks before firing — and reloads the
canonical message on success.

```
DRAFT ──▶ VERIFIED ──▶ PUBLISHED ──▶ CANCELLED
  │   ◀── (un-verify)
  └──┬── DELETED ──┘ (from DRAFT or VERIFIED)
```

- **Verify and Publish share one validation gate** (`canPublish`): a type, a message series, at
  least one non-empty title in some language, and every `MANDATORY`-requirement promulgation set to
  promulgate. The failure toast lists exactly what is missing.
- **Un-verify** (`VERIFIED → DRAFT`) is exposed only by the preview stepper's clickable Draft node;
  the Status panel has no such button.
- **Cascade-cancel:** publishing offers to also cancel the `PUBLISHED` messages this one references
  with `CANCELLATION`, `REPETITION`, `REPETITION_NEW_TIME` or `UPDATE`. The set comes from a
  dedicated server query, not from walking the message's own reference list; on confirm the client
  sends a single `cancelReferencedMessages` flag and the backend performs the cancellations. The
  publish and the cancellations are one transaction: a referenced message the current domain cannot
  change answers 409 before anything changes, and a failed cancellation rolls the publish back.
- **A refused change** (409, e.g. a transition another officer's change made invalid, or one made
  in another tab) reloads the message, so the panel shows its status as it is now, and toasts the
  backend's reason (`editor.status.error.refusedReloaded`); any other failure keeps the action's own
  toast and the message as it is.
- **One status change at a time:** while a change is on its way to the backend, every status action
  is disabled (`MessageStatusService.busy`) and a change asked for then is ignored, so a second
  confirm cannot send it again.
- **Scheduled publishing:** a `VERIFIED` message carrying `publishDateFrom` shows no Publish button
  at all — the panel prints the scheduled date instead and the backend scheduler releases it. Any
  new publish affordance must respect that split, or a scheduled message gets published twice.
- **Cancel** applies to `PUBLISHED` only and optionally re-enters the copy flow to draft a
  cancellation message — offered for NM messages only.
- **Copy** is the one action with no status precondition; it is gated on `isEditor` alone.
- Every status action is disabled while the editor has unsaved changes (`hasUnsavedChanges`): save
  or discard first. An edit changed back enables them again; a message never saved keeps them
  disabled until its first save.
- The Status page also carries the rendered message with its own **content-language** switcher
  (independent of the UI language) and the publication-membership panel.

The stepper above the live preview maps the status onto `Draft → Verified → Published` and appends a
fourth `Expired`/`Cancelled` node once the message reaches a terminal post-publish state; a
`DELETED` message renders no flow. It carries only the forward action plus the un-verify step-back
— Delete, Cancel and Copy live on the Status page.

---

## Copy, cancel and reference flows

A copy may carry one of five relations to the original: `CANCELLATION`, `REPETITION`,
`REPETITION_NEW_TIME`, `UPDATE`, `REFERENCE`. Anything else on the route degrades to a plain copy.
The copy dialog picks between `/editor/copy/:id` and `/editor/copy/:id/REFERENCE` — the plain
`REFERENCE` is what the "include a reference to the copied message" checkbox sends (offered for NM
only), and the backend adds it to the new draft's references. `CANCELLATION` rides the
cancel → create-cancellation re-entry.

References themselves are structural rows on the working copy: add, delete, drag-reorder, retype,
autocomplete a target, and a per-language description. All of them mutate immutably through facade
methods and none of them adjust.

---

## Rich content, attachments and insert dialogs

### Description and publication text

The per-part **description** body is a `full`-preset rich-text editor per configured model language,
mounted only while the row is expanded. The message-level **External** (`publication`) and
**Internal** (`internalPublication`) bodies use the `inline` preset, which emits no root `<p>` and
preserves the server-owned `publication=<id>` span attribute untouched through load → unrelated
edit → save. A publication span otherwise keeps only `title`, `lang`, `dir` and a `class` narrowed
to the message-HTML vocabulary; every other attribute on it is dropped. Both bodies live in the
message's per-language `descs` and are written through a dedicated facade mutator that rebuilds the
descs immutably, seeds a language that has no entry yet, and never adjusts. Rendered message HTML is
sanitised — the collapsed description row included; the toolbar's table/cell class vocabulary is a
contract with the global stylesheet and the shared class vocabulary — see
[UI standards](../ui-standards.md). Every preset
narrows the `class` of a table, row, cell and link to that vocabulary where it parses content, and
gives a link with a `target` `rel="noopener noreferrer"`, as the sanitiser does.

The `full` and `basic` presets keep the two inline declarations the sanitiser renders: a stored
`padding-left` on a paragraph, heading, list, list item or table cell, and a stored `text-align` on
a table cell, validated and capped with the sanitiser's own rules. An indented block of dates
therefore shows indented on the editing surface as in the preview, and survives an edit elsewhere in
the body. The toolbar creates neither; the alignment buttons act on paragraphs and headings, so
aligning one paragraph in a cell overrides the cell's stored alignment for that paragraph only.

### Insert dialogs

| Dialog              | Behaviour                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| ------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Insert time         | Entirely client-side. A part picker appears only for a multi-part message; the chosen part's event-date intervals render as a checkbox list, previewed through the shared interval phrasing in the **target body language**, joined with `<br>`. An empty selection previews the "until further notice" phrase. The "include time zone" toggle persists in `localStorage`.                                                                                                                      |
| Insert locations    | Lists the chosen part's geometry features **excluding** buffered ones (features carrying parent ids), with a map picker alongside the checkbox list, and posts the selection to the backend's geometry formatter with a template (numbered list / bullet list / table / from-to table / text) and a coordinate format (`dec`, `dec-2`, `dec-1`, `sec`, `navtex`). Preferences persist in `localStorage`; a stored template value of `list` is rewritten to `numbered-list` on read.             |
| Insert publications | Edit mode when the caret sits inside a `publication=<id>` span (prefilled by id), else insert. The chosen publication's own format decides whether a `parameters` field appears (its template carries a `${parameters}` placeholder); a `link` field appears for a LINK-type publication. Both actions post `update-message-publications` (omitting `lang` updates every language) and the server-rendered per-language HTML is upserted into the working copy — never regenerated client-side. |
| Image picker        | Lists the message's own **image** attachments only (`type` starting `image/`) — the only shape that renders as an inline `<img>` — and inserts the repository file URL.                                                                                                                                                                                                                                                                                                                         |
| Link                | Plain link insert/edit over the current selection.                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| Add sources         | Offers `active` sources only (the catalogue endpoint returns all with a flag, so the filter is client-side). Each selection may carry a date, resolved and printed in the **session domain's** zone; with no domain, Add refuses. The composed text is produced per configured language.                                                                                                                                                                                                        |

### Attachments and thumbnail

File surfaces are facade-driven, not form controls.

- Uploads go to the message's attachment repository path, one request per file. The shared uploader
  sends them one at a time in pick order (also across picks made in a row), so files picked together
  are appended in that order; the backend keeps the list order it is sent, and the public site and
  the PDF show it.
- The attachments table reorders by a grip on each row: dragged (CDK drag-drop on the `<tbody>`), or
  moved one place by the up and down arrow keys on the grip, which keeps the focus. Both go through
  `moveAttachment(from, to)`, which refuses out-of-range and no-op moves and marks touched.
- Removing an attachment always drops it from the model, but deletes the **physical** repository file
  only when its path lies under `editRepoPath/revision/` — the current unsaved revision. A file from
  an earlier saved revision is a model-only removal, so revision history stays intact. The thumbnail
  follows the same rule.
- Attachment display is `''` (none) / `ABOVE` / `BELOW` / `SEPARATE_PAGE`; width and height are
  CSS-unit strings such as `50%` or `200px`, not numbers. Choosing "none" deletes display, width and
  height together so a hidden attachment carries no orphaned dimensions.
- The thumbnail comes from either a file upload or a map-snapshot capture dialog; both answer with
  the new repository path, which is then set on the message.

---

## Promulgation channels

A message carries one promulgation entry per channel configured for its domain. The entry set is
**server-computed** and replaced wholesale whenever adjust runs with promulgations included — so a
type change discards unsaved per-channel edits.

- Each promulgation type has a requirement of `MANDATORY`, `OPTIONAL` or `DEFAULT`. The asymmetry is
  enforced in the facade as well as the checkbox: a `MANDATORY` channel that is already promulgating
  can be switched **on** but never off. A `MANDATORY` channel left off blocks verify and publish.
- Enabling a channel while `message.type` is still unset auto-deduces the type when the promulgation
  type maps to exactly one message type — routed through the _same_ type-select cadence, so there is
  one adjust path, not two.
- **Generate** posts the whole message and merges the single returned promulgation back into the
  matching entry **by `typeId`**: present fields overwrite recursively, arrays wholesale, absent
  fields survive, the serialization discriminator and `type.typeId` always survive, and sibling
  channels keep their unsaved edits. Generate-all confirms once and regenerates every enabled
  channel.
- Endpoint roots differ by operation and are easy to get wrong: generate and the type lookup sit
  under the **plural** `/promulgations/…` root (`/promulgations/generate/{typeId}`,
  `/promulgations/promulgation-type/{typeId}`), while reformat sits under the **singular** root with
  a doubled service segment (`PUT /promulgation/{serviceId}/reformat-{serviceId}`). Both live in
  `features/messages/services/api/promulgations-api.service.ts`.

Per-channel editors are a **dependency-injection registry**, not a switch: `PROMULGATION_EDITORS` is
a multi-provider token keyed by `serviceId`, and an outlet dispatches on
`promulgation.type.serviceId`. An unknown id renders nothing. Each editor receives a
`PromulgationEditorContext` (patch / generate / reformat / busy / message status) as an input, so it
never reaches for the facade itself.

| Channel   | Editor specifics                                                                                                                                                                                                                |
| --------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| NAVTEX    | Read-only preamble, a checkbox per configured transmitter, a monospace body with a 40-column guide, priority `NONE`/`ROUTINE`/`IMPORTANT`/`VITAL`, and a Format action that replaces only the body text.                        |
| SafetyNET | A radio list of the type's areas (with a warning when promulgating with none), a monospace body, priority limited to `SAFETY`/`URGENCY`, and a SafetyNET id field that is not rendered at all until the message is `PUBLISHED`. |
| Twitter   | A single tweet field capped at 140 characters; no Format action.                                                                                                                                                                |
| Audio     | Free-text body plus Generate.                                                                                                                                                                                                   |

Core registers all four editors in `features/editor/promulgation/core-editors.providers.ts`, and
their viewers in `features/messages/promulgation/core-renderers.providers.ts`. A country adds the
editor and viewer of a service of its own through `provideCountry()` in its `country.providers.ts`,
which in the base product (`country-specific/default/country.providers.ts`) registers nothing.
There is no country conditional anywhere in core. See
[country deployments](../country-deployments.md).

---

## Geometry and positions

Geometry lives on each message **part**. The editor is `<app-geometry-editor>` in
`ui-apps/admin/src/app/shared/components/geometry-editor/`, opened in a near-full-window modal from the positions
row (and lazily loaded, so the buffer maths stays out of the initial bundle).

### The shared editor

It is a **value editor, not a form**: `[geometry]` (a GeoJSON `FeatureCollection` in EPSG:4326) goes
in, `(geometryChange)` comes out on **commit only**. Escape or Cancel discards the working set; OK
with no actual edit closes like Cancel rather than manufacturing a change on the host. Working state
is one signal of OpenLayers features that the map interactions, the coordinate tree, the import
dialogs and the buffer/ops actions all build on, with its own undo/redo history. It provides its own
`MapShellService` so the layer switcher — lifted out of the map into the collapsible left overlay —
shares the same shell and layer registry.

`editType` parameterises it for three surfaces:

| Value      | Used by                                                | Effect                                                                                      |
| ---------- | ------------------------------------------------------ | ------------------------------------------------------------------------------------------- |
| `message`  | the editor's part card and the template execution card | Per-feature property panel (names per language, restriction), affected-area buffers, Import |
| `features` | admin Areas and Charts extent editors                  | A plain multi-feature collection; no property panel, no affected areas                      |
| `feature`  | —                                                      | Reserved; suppresses the edit-as-text action                                                |

Draw modes come from the `drawControls` CSV, default
`point,polyline,polygon,select,remove,measure` (`box` is opt-in). Geometry operations offered are
**merge, split and convert** only (convert targets `LineString`, `Polygon`, `MultiPoint`); set
operations (subtract / intersect / union) are deliberately not offered.

### Coordinate naming and indexing

Per-coordinate names are feature properties keyed `name:<coordIndex>:<lang>`, where `coordIndex` is
a breadth-first index over the feature's serialized coordinates that **matches the on-map label
index**. Per-feature names use `name:<lang>`. Any operation that changes coordinate order (merge,
split, convert, vertex insert/delete in the tree) must offset those indices, or labels silently
attach to the wrong points; folder nodes in the tree carry the `[coordIndex, coordIndex + count)`
span they cover, including a polygon ring's skipped closing vertex.

At the message level, `startCoordIndex` is re-threaded across all parts on every structural or
geometry commit **and on every load**, delegating each feature's coordinate count to the geometry
serializer so the skip rules for buffered features stay in one place.

Feature ids inside the editor are transient client-side UUIDs, never server identifiers.
Serialization on commit strips only the transient `selected` flag; every other property survives.
Geometry equality tolerates a small coordinate epsilon, because the editor round-trips through
EPSG:3857 and back and that perturbs coordinates far below the smallest real edit.

### Affected areas (buffers)

An affected area is a **derived** feature identified solely by carrying `parentFeatureIds` (a CSV of
its parents' transient ids), alongside `restriction`, `bufferType`, `bufferRadius` and
`bufferRadiusType`. It is recomputed whenever a parent moves or the radius changes, is locked in the
feature tree (read-only card, no checkbox, no drag handle), and is offered only in `message` mode.

| Action              | Requires                                  |
| ------------------- | ----------------------------------------- |
| Add affected radius | ≥ 1 selected feature of any geometry type |
| Add affected path   | ≥ 2 selected **point** features           |
| Add affected area   | ≥ 3 selected **point** features           |

Rules a maintainer must not "fix":

- Radius maths is hybrid **by design**: an all-point selection produces exact geodesic circles; any
  other selection is offset with a planar-buffer library after an EPSG round-trip. There is no
  `1/cos(latitude)` correction anywhere — both paths are already latitude-correct.
- A path or area buffer also accepts a positive radius, giving a corridor or an expanded ring, so the
  radius control is shown for **any** buffer feature.
- A zero, blank or non-numeric radius yields the bare merged parent geometry — never an error, never
  a deletion. A degenerate buffer result falls back the same way.
- A buffer is cascade-deleted **only** when none of its parents survive. Losing some parents keeps it
  alive and it rebuilds as its declared type once the values are valid again.
- `bufferType` must round-trip through the saved GeoJSON even though it is not displayed, or a path
  or area buffer cannot be recomputed after the message is reopened.
- The buffer property keys and `isBufferFeature` live in their own module, separate from the buffer
  maths, so a read-only consumer (the preview layer) can recognise an affected feature without
  pulling the geometry library into its bundle. Keep that split.

Affected areas are computed client-side and persisted inside the part's GeoJSON by the ordinary
message save; there is no buffer endpoint. Map views render the stored polygon as-is and never
re-buffer.

### Import and text

The import dialog has three tabs: **From Area** (an area that carries a geometry — supplied through
the `AREA_GEOMETRY_SOURCE` token so the shared component does not depend on a feature service),
**From Text** and **From UTM**, the latter two parsed by the backend. Drag-and-drop accepts GeoJSON,
KML and WKT; a dropped feature carrying an `mrgid` property is special-cased because such exports
order coordinate pairs lat,lon. "Edit as text" prefills from the backend's geometry formatter.
Apart from those three parse/format endpoints and the injected area source, the editing surface makes
no HTTP calls.

### Compute from locations

Three geometry-driven derivations, all disabled unless the message is editable and at least one part
defines geometry, and each mutating only in its success callback so a failure toasts and leaves the
working copy intact:

| Action              | Sends                                                                                                                                         | Adjusts after |
| ------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- | ------------- |
| Compute areas       | the geometry **excluding** `affected`-restriction features                                                                                    | yes           |
| Compute charts      | all features                                                                                                                                  | no            |
| Copy area locations | fetches the selected areas with geometry in every language and flattens their descs into `name:<lang>` feature properties onto the first part | no            |

GeoJSON `properties: null` must be normalised to `{}` before it reaches the backend.

### The sandbox

`/dev/geo-editor` hosts the geometry editor standalone and is registered only when
`environment.production` is false, so it never ships. It is the editor's regression harness — the
spec that drives it documents what is deliberately left manual (vertex modify, shift-delete,
box-select, translate and file drop all need canvas-pixel precision).

---

## Template execution

### Templates are categories

A "template" is a **category** whose `type === 'TEMPLATE'`. The metadata lives on the category:
`scriptResourcePaths` (the server-side scripts), `stdTemplateFields` (which standard message fields
its execution form exposes), `templateParams` (its own parameters) and `messageId` (a linked example
message). A category is executable only when it is a `TEMPLATE` **and** carries script resources.
Authoring happens in the Sysadmin area — see [sysadmin](./sysadmin.md).

Execution is gated by the session domain's `templates` capability and by the `editor` role; the
catalogue search is always domain-scoped, so only the active domain's templates are offered. A
template's linked example message id may be a UUID or a short id.

Parameter types come from the backend catalogue and are polymorphic: `STANDARD` (identified by name
— text, number, boolean, date, light character, call sign, AtoN name), `LIST` (a values array whose
selected **value object** is stored, not a key) and `COMPOSITE` (nested parameters, one level). The
catalogue is fetched **without** a language filter on purpose, so a LIST value keeps every language's
descriptions and re-localizes per body language at execute time. Light-character and call-sign
inputs validate server-side while typing and commit the backend-normalised value.

### Entry points and the two transient stores

- **Start screen → "from a template"** opens an anchored template-search popover; picking one seeds
  the execution store with a placeholder (`blank`: only the main type and the template) and enters
  the execution view directly, which swaps the placeholder for a fresh server blank of that type.
- **Categories row → "Run template…"** in an open message seeds a clone of the working message and
  the current URL as the return target. The execution view keeps that clone as it is, saved or not
  (a new message or a copy), unsaved changes included.

Two root-provided stores carry state in opposite directions and are deliberately separate, so the
shared execute route has no consume-ordering coupling:

- `TemplateExecutionSeedStore` carries the seed **into** the execution view, plus the return URL.
- `TemplateMessageStore` carries the finished result **out**, into the `/editor/edit` route.

### The execution view

A full page under the editor shell, not a dialog. Left column: a Templates card (chosen templates as
chips, with Change and reorder), a Base fields card, and one parameter card per selected template.
Right column: the live preview, which flips to a drawer at the same 1080 px breakpoint as the edit
form. Template selection is a right **drawer** (category filter, searchable checklist, and an example
panel rendering the hovered template's linked example message), reopenable via Change; it opens
automatically when nothing is chosen. There is deliberately no numbered-step breadcrumb.

The execution form renders **exactly** the standard fields and parameters the selected templates
declare — no more, no less. A standard field shows when **any** selected template declares it.

**Load-bearing invariant:** `templates[]`, `values[]` (one parameter record per template) and the
working copy's leading `parts[]` are three parallel, index-aligned arrays. Every add, remove and
reorder — from the drawer, the chips or the card controls — must update all three atomically. Each
template owns one `DETAILS` part, created with an empty geometry collection and an event-date array
when the message has too few.

Positions inside a template card behave exactly like the editor's: a read-only mini-map plus the full
geometry editor in `message` mode, committing on OK. A geometry feature counts as a template
**position** only when it has no `parentFeatureIds` and its restriction is not `affected`. A
parameter flagged `positionList` renders once per derived position; everything else renders once per
template. Editing a part's geometry derives the message's areas from it — but only when no area is
picked yet and never applying an empty answer, so an unattended background call cannot eat a
hand-picked area; the explicit compute button is the override.

### Preview and execute

There is **no preview endpoint**. The live preview calls the same `PUT /templates/execute` as the
final Execute and gets a whole re-rendered message back, debounced 500 ms with supersede-cancellation
and an immediate Refresh control as a fallback. The last good render is kept on error, so a failing
keystroke never blanks the pane. The working copy is local to the view until Execute, so no preview
round-trip dirties the shared editor state.

The execute payload is `{ ...workingCopy, categories: [...nonTemplateCategories, ...templates] }`
plus one value record per template — non-template categories survive, and the order of the template
categories **is** the execution order. Each value record is
`{ posDecimals, positions, …one entry per paramId }`, where `positions` is derived from the
template's part geometry (one entry per non-buffer, non-`affected` feature, so a `positionList`
parameter can repeat over it). Each derived position carries its geometry in the **nested
coordinates** shape the template scripts iterate, built by `serializeReadableCoordinates` in
`components/template-execution/template-execute-fields.ts` — never raw GeoJSON; the wire shape is
in [backend API notes](../backend-api-notes.md) → _Templates_.

Execute is held back while a `mandatory` parameter is empty (`missingMandatory` in
`template-execution-view.ts`; the header names the labels, and the button's `aria-describedby`
points at that line). Empty is `undefined`, `null`, a blank string or an empty array; a boolean is
never empty. A `positionList` parameter is checked in each derived position record. A COMPOSITE's
children are checked one level down, and only when the composite is itself mandatory or one of its
children has a value; a mandatory composite with no mandatory child needs one child filled. The
preview keeps running, and the backend checks nothing: a direct `PUT /templates/execute` runs with
the parameter empty.

Execute is a whole-message replace, never a field merge: the rendered result overwrites the working
message. A saved result is applied straight to the facade and lands back on its own edit URL; a new
or draft result is seeded to the transient store and enters the born-dirty template route. Execute
asks first ("this will replace your message") while the editor has unsaved changes — an edit, or a
copy or new message never saved — and not for a saved message whose edits were undone. Cancel
confirms when the view is dirty and restores the untouched working message.

Date handling is asymmetric and must survive every round-trip: the standard `publish_date` and
`event_dates` fields commit ISO strings, while date-typed template **parameters** commit epoch
milliseconds.

There is zero country-specific behaviour in this UI. A deployment's template bodies are backend
script resources imported as `TEMPLATE` categories per domain; which templates exist and what each
exposes is runtime catalogue data.

---

## Comparison, comments, history

### Comparison dialog

Three tabs — Side-by-side, Changes (a word-level HTML diff) and Data (a JSON snapshot diff) — with
two entry points: the messages list when exactly two messages are selected (both sides prefilled in
selection order) and the editor action menu for a saved message (which seeds the open message).
Each side loads the full all-languages message by id (UUID or short id both accepted), and a language
toggle switches the **content** language of both sides independently of the UI language.

### Comments

`EditorCommentsStore` owns the thread at the **editor route**, not on the comments page, because the
tab badge must show the comment **total** on every tab and the message payload carries only the
unacknowledged count — there is no total in it. Fetching at the shell (the action menu injects the
store, creating it on entry) makes the badge correct before Comments is ever opened. It is
deliberately not on `EditorFacade`, which many specs construct bare.

- The thread is language-tagged, so it refetches on a language change and on every message reload.
  The editable working copy never refetches on a language change — that would discard edits.
- Comments render in the order the server returns them. A comment is editable when it is new, when
  it is the current user's own, or when the user is an `admin`.
- Acknowledge is offered for a saved, not-yet-acknowledged comment; the acknowledging user and date
  are recorded.
- Entering e-mail addresses flips Save to "Save and send" — the endpoint is the same, and the backend
  mails because addresses are present. The address field offers a typeahead over known users.
- Comment bodies are sanitised before rendering.
- A logged-in non-editor can read the thread and create and acknowledge comments.
- The compose draft (the "Add comment" text and recipients) is kept on `EditorCommentsStore` for the
  visit, not in the page: a switch to another tab and back, or a trip to the Start screen and back
  to the same message, finds it again. It is cleared only when the thread itself changes (another
  message, or none) or its own post succeeds — a reply or edit saved from another card must not
  wipe it, and a failed post keeps it. While it holds text or recipients, leaving the editor
  (`editorExitGuard`), a navigation that replaces the working copy (`unsavedChangesGuard`) and a
  reload or tab close (the shell's `beforeunload`) ask first, as for unsaved message edits; the
  shell's teardown drops it. The reply and edit editors of existing comments are not kept.

### History

A revision table (version, timestamp, user — "System" when the change was not made by a person, and
status) beside a recursive JSON-diff pane. At most **two** revisions can be selected; an unselected
row's checkbox disappears once two are chosen. The selection is ordered oldest → newest so the diff
is independent of click order. Selecting exactly one revision lists the attachments captured in that
snapshot. History is not language-tagged and does not refetch on a language change; it does refetch
on a message reload. The snapshot itself is opaque server-produced JSON.

### Publication membership

The Status page also shows which publication series the message appears in, one row per **series**
(an in-force series re-lists a message in every edition, so the row says how many issues), with three
claims: published, withdrawn and pending. See
[publications domain rules](../publications/domain-rules.md).

---

## Deliberate limits

- The live-preview pane width is not persisted; it returns to its default on every open.
- `/editor/execute-template` is not deep-linkable: the seed travels only through the in-memory store,
  and a reload or pasted URL returns to the Start screen.
- The template selection list has no arrow-key roving focus.
- The two insert-dialog preference keys in `localStorage` are unprefixed.
- Concurrency is last-write-wins (above).

---

## Where to look

| Concern                                                                                                                                          | Files                                                                                                                                                                                 |
| ------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| State spine, all mutators                                                                                                                        | `ui-apps/admin/src/app/features/editor/services/editor-facade.ts`                                                                                                                     |
| Pure rules (adjust merge, part algebra, series rules, publish validation, change signature, promulgation merge, coordinate re-index, repo paths) | `ui-apps/admin/src/app/features/editor/util/`                                                                                                                                         |
| Field visibility and row state                                                                                                                   | `ui-apps/admin/src/app/features/editor/services/editor-fields.service.ts`                                                                                                             |
| Status machine                                                                                                                                   | `ui-apps/admin/src/app/features/editor/services/message-status.service.ts`                                                                                                            |
| Form host                                                                                                                                        | `ui-apps/admin/src/app/features/editor/views/edit-form/`                                                                                                                              |
| Template execution                                                                                                                               | `ui-apps/admin/src/app/features/editor/views/template-execution-view/`, `components/template-execution/`                                                                              |
| Geometry editor                                                                                                                                  | `ui-apps/admin/src/app/shared/components/geometry-editor/`                                                                                                                            |
| Promulgation registry                                                                                                                            | `ui-apps/admin/src/app/features/editor/promulgation/`, `ui-apps/admin/src/app/features/messages/promulgation/`, `ui-apps/admin/src/app/country-specific/default/country.providers.ts` |

End-to-end coverage: `ui-apps/admin/e2e/editor-shell.spec.ts`, `ui-apps/admin/e2e/editor-start.spec.ts`,
`ui-apps/admin/e2e/editor-edit-form.spec.ts`, `ui-apps/admin/e2e/editor-template-execute.spec.ts`,
`ui-apps/admin/e2e/geometry-editor-sandbox.spec.ts`. Conventions in [testing](../testing.md).

Related: [architecture](../architecture.md) for the layer and import rules,
[messages](./messages.md) for the list the editor returns to, [UI standards](../ui-standards.md)
for the shared controls this area composes.
