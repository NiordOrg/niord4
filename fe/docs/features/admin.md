# Admin area (`/admin`)

The everyday administration area: the catalogues editors depend on (charts, areas, sources),
the people and addresses mail goes to, and the publication configuration. The second area,
`/sysadmin`, holds installation-level configuration and runs on the same shell —
see [`sysadmin.md`](sysadmin.md).

**Who may enter.** `/admin` requires the `admin` or `sysadmin` role as a Keycloak client role,
held either on the _currently selected_ session domain's client or on the base client
(`niord-client-master`). A role on the base client holds at every desk; one held on a domain's
client only is lost the moment the user switches domain. A Keycloak realm role grants nothing, on
the backend either. An authenticated user without the role gets an access-denied toast and lands
on home; an anonymous one is sent to login with the requested URL as the return target. Roles and guards:
[`shell-and-auth.md`](shell-and-auth.md).

**How the area is wired.** Rail entries, overview cards and child routes are all projected from
one descriptor list, and sections may carry a trailing route param, extra sub-pages or a
visibility gate. That machinery — and the master-detail tree scaffold Areas runs on — is
described once in [`../architecture.md`](../architecture.md). Adding a section means adding a
descriptor entry in `ui-apps/admin/src/app/features/admin/section-descriptors.ts` plus a component override in
`ui-apps/admin/src/app/features/admin/admin.routes.ts`.

## Sections

| Section                | Path                            | Owns                                     | Scope                | Who may change it                          |
| ---------------------- | ------------------------------- | ---------------------------------------- | -------------------- | ------------------------------------------ |
| Overview               | `/admin` → `/admin/overview`    | One launcher card per section            | —                    | —                                          |
| Charts                 | `/admin/charts`                 | Sea-chart catalogue + coverage extents   | Global               | admin                                      |
| Areas                  | `/admin/areas`                  | The area tree, message ordering per area | Global               | admin                                      |
| Publications           | `/admin/publications`           | Publication series, issues, one-offs     | Domain-owned         | admin                                      |
| Publication categories | `/admin/publication-categories` | What groups series on the public page    | Global               | admin                                      |
| Sources                | `/admin/sources`                | Message-source attributions              | Global               | admin                                      |
| Users & contacts       | `/admin/users`                  | Keycloak users, Niord contacts           | Domain-scoped groups | admin (+ a Keycloak client role for users) |
| Mailing lists          | `/admin/mailing-lists`          | Lists, triggers, recipients              | Global               | sysadmin edits, admin manages recipients   |

The frontend role checks mirror the backend; the backend re-checks every mutation and is the
enforcement.

## Conventions every section follows

**Page chrome.** `<app-admin-section-page>` draws the title and a one-line description. There is
no breadcrumb — the rail plus that header carry the location. A section that drills into a record
with its own header cluster hides the section header while that record is open, so only one
header per level is ever on screen.

**List shape.** Almost every section loads its whole catalogue in one request and filters and
pages it on the client, 20 rows per page (`ADMIN_PAGE_SIZE`); every filter change resets to page
one, and a shrinking list clamps back into range instead of stranding the reader on an empty
page. Contacts is the exception: it is server-paged (page is 1-based in the UI, 0-based on the
wire), commits its filter after a second of idle typing or immediately on blur, and re-searches at
the last valid page when a delete empties the current one.

**List rows vs. the editor.** Where the list is language-resolved server-side — sources, mailing
lists, areas — a row shows the requested language's desc only, so opening the editor re-fetches the
record with _all_ languages, because that is what is saved back; sending a language parameter on a
details fetch silently truncates the form. Sources and Areas offer a **Model language** picker
that refetches the list in any configured model language, independently of the UI. Mailing lists
continue to use the UI language. An open editor is unaffected because it already holds every language. The rest carry
no language-resolved payload — charts, users and contacts have no localized descs, and publication
categories are listed in all languages — so the list row _is_ the whole record and no details fetch
is made. Publication categories also offer the picker, resolving names client-side with the
default-language fallback. These selections are page-scoped display preferences and do not change
editable records; see [country-deployments.md](../country-deployments.md#languages).

**Drawer editors.** The page owns visibility: the editor takes a model input where `undefined`
means closed. The form is rebuilt only when the drawer (re)opens, so a language switch mid-edit
cannot wipe unsaved input. Save is gated on the serialized-baseline comparison of
[`../angular-conventions.md`](../angular-conventions.md) → _Signal Forms_, never on `dirty`. A
failed save keeps the drawer open with the values intact. `[dirty]` on `<app-drawer>` arms the
discard prompt on every close path (button, ✕, Esc, backdrop).

**Text limits.** A text input whose database column has a limit carries `<app-length-counter>`
under it, "n of max characters": the category, series and one-off names and the category
description, the area, source and chart texts (255 characters each), and in `/sysadmin` a message
series' short and NAVTEX formats (255), a dictionary value and long version (1,000) and a text
setting (1,998, since the value is stored as JSON with its two quotes in the column's 2,000). Nothing
is cut: a longer text stays in the field, the line turns red and says by how much, and Save waits
until it fits. The backend answers a longer value that still arrives with 400 naming the limit.

**Per-language text** follows the configured language list in either of two shapes, and the
difference is load-bearing. Sources and Mailing lists pad the record's descs to every configured language in
configured-list order through `padAndOrderDescs`
(`ui-apps/admin/src/app/features/admin/utils/localized-descs.ts`), which keeps descs in _unconfigured_ languages
at the end so they round-trip through edit and save untouched. Areas and Publication categories
instead rebuild their per-language text from the configured list alone and emit only the languages
they built, so a desc the server holds in an unconfigured language is not preserved by a save
there.

**Delete** always goes through the destructive confirm, naming the row. `ConfirmService` is
provided per page, not globally.

**Import and export** hang off the section's cog menu.

|                        | Export                        | Notes                                                                                    |
| ---------------------- | ----------------------------- | ---------------------------------------------------------------------------------------- |
| Charts, areas, sources | opened directly               | the read endpoints are public, so no ticket is fetched                                   |
| Contacts               | one-time `admin` ticket       | the endpoint checks the role programmatically and 403s without it                        |
| Mailing lists          | one-time `sysadmin` ticket    | a wrong-role ticket 403s                                                                 |
| Publication categories | saved from the response bytes | the export _is_ an import file, so it is never re-serialized through this client's model |

Imports (except publication categories, below) use the shared drop-zone dialog: one file,
auto-uploaded on pick or drop, the backend's raw response shown in a read-only area, and the
dialog deliberately stays open so the operator can read it. A file whose extension is not accepted
is dropped silently. The list refetches whenever the dialog closes, by any path. **An upload
answers with a job submission, not a result** — imports run as backend batch jobs, so imported
rows are eventually consistent and may not be on screen when the dialog closes. A consumer can
point the dialog at the batch-job page for the job it starts.

**Unsaved work** is guarded twice in the tree sections (Areas here, Categories in `/sysadmin`): a
route `CanDeactivate` guard blocks rail or back-button navigation while the inline editor is
dirty, and a `beforeunload` host binding covers reload and tab close. A changed drawer, in any
section of either area, asks through its own gate on Cancel, X and Escape, through
`drawerUnsavedChangesGuard` (on every section route) on the rail, Back or a link, and through the
drawer's `beforeunload` on reload and tab close.

**Help.** Each section descriptor carries a `helpSection` key resolved through `HELP_URLS`;
the overview uses a key mapped to the manual's overview chapter, and SECOM, which the guides do not
cover yet, has none and shows no help link. See
[`shell-and-auth.md`](shell-and-auth.md).

**No caching.** Server data lives exactly as long as the view showing it; every mutation refetches
rather than patching a local copy.

## Overview

One navigable card per section — title plus the section's one-line description — mirroring the
rail one-to-one and filtered through the same visibility gates, so a hidden rail entry never
leaves an orphan card.

## Charts

A global, domain-agnostic catalogue: no domain picker, and a domain switch changes nothing here.
Charts carry no localized text, so a UI language switch does not refetch them either.

- A chart holds `chartNumber` (the string primary key, required), an optional international
  number, name, scale (rendered `1:{scale}` with the active locale's grouping separator; the
  editor refuses a scale below 1 and leaves an empty one allowed),
  horizontal datum (free text, `WGS84` on a new chart), an active flag, and an optional coverage
  extent. `chartNumber` is **immutable** — the field is disabled on edit, because it is the path
  id the update is sent to.
- The single filter box matches four fields case-insensitively: chart number, international
  number, horizontal datum and name. Horizontal datum is searchable although it is not a column;
  `scale` deliberately is not.
- The list payload carries each chart's geometry, which is why the map beside the list needs no
  extra request. That map is a read-only overview of the **current page's** extents only, re-fitted
  on every page change, each outline labelled with its chart number. Its colours come from CSS
  tokens read at runtime (an OpenLayers canvas cannot resolve `var()`), so a re-skin changes tokens,
  never the layer code.
- An extent is stored as a single bare geometry, not a feature collection. The editor expands a
  stored `GeometryCollection` into one feature per member for editing and collapses on commit:
  one feature stays bare, several become a `GeometryCollection`, none clears the extent. The
  geometry editor is opened with the box, polygon, select and remove draw controls only — an
  extent is an area, never a point or a line.

## Areas

The area tree: a filterable, drag-to-re-parent tree beside an inline editor. Areas are global —
only the firing-area generation below depends on the session domain.

An area holds an active flag, a name per configured language (the first configured language's name
is required), an MRN, a type (blank, `COUNTRY` or `FIRING_AREA`), additional editor fields, a
message-sorting strategy and an extent geometry. A blank type or strategy is saved as _unset_,
never as an empty string.

**Additional editor fields** are message-editor fields this area forces on for any message filed
under it ([`editor.md`](editor.md)). The pick-list is a pinned frontend catalogue,
`EDITOR_FIELD_CANDIDATES` in `ui-apps/admin/src/app/features/admin/utils/editor-field-candidates.ts` — the
backend supplies no list of its own, so a deployment whose message fields differ must revise it.

**Message sorting** decides how published messages are ordered within the area:

| Value               | Meaning                                                                                      |
| ------------------- | -------------------------------------------------------------------------------------------- |
| blank               | area order — the order an editor sets by dragging in the sort dialog (see below)             |
| `NS` `SN` `EW` `WE` | geometric axis order                                                                         |
| `CW` `CCW`          | around an origin point — latitude, longitude and an angle, shown and sent only for these two |

An area may define its own strategy **only if no ancestor defines one**; when an ancestor does, the
whole control group is hidden and the child inherits. The selected area is fetched with its
ancestor chain precisely so this can be decided.

A dragged order does not last everywhere. A draft's order is recomputed from its area's strategy
each time the draft is saved, so a drag before the next save is lost. The re-index below gives every
published message whose first area has no strategy, on itself or on an ancestor, or which has no
geometry, the same order value, so a manual order does not survive it either.

Toolbar and tree:

- **New** creates a child of the current selection, or a root when nothing is selected; the fully
  loaded parent drives the sorting inheritance above. Add is disabled while a node is selected but
  its detail has not arrived, so a new area can never be parented against a stale record.
- **Move up / down** reorder siblings and are enabled only while editing an existing area with no
  unsaved changes. Re-parenting is drag-only: dropping on a node moves the area under it, dropping
  on empty space moves it to the root, and a confirm names the target parent or the root.
- Every mutation refetches the tree — the server is the authority on the resulting order.

Cog actions:

| Action                        | Effect                                                                                                                                                                                                                                                                                   |
| ----------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Export                        | downloads the whole tree as JSON from the public areas endpoint                                                                                                                                                                                                                          |
| Import                        | uploads a JSON file, which starts the backend area-import job                                                                                                                                                                                                                            |
| Re-index areas                | recomputes the tree's sort order                                                                                                                                                                                                                                                         |
| Re-index published messages   | recomputes the per-area order of every published message in the session domain's message series from the areas' strategies. **This overwrites every manual drag order an editor set**: where no strategy applies, or the message has no geometry, every such message gets the same value |
| Generate firing-area messages | shown only when the session domain has the firing-schedule capability; asks for a message series (the session domain's) and an optional tag. The backend refuses a series belonging to another domain                                                                                    |

The editor's message ordering pairs with the sort dialog in Messages
([`messages.md`](messages.md)); the Firing Area Schedule area is separate
([`schedule.md`](schedule.md)).

## Sources

A source is the attribution a navigational warning cites — the authority or publication it came
from. A flat, global catalogue; the endpoints ignore the domain header.

- A source has a server-assigned numeric id, an active flag, and one desc per language carrying a
  name (**required in the default language** — Save stays disabled until it is filled; the other
  languages are optional) and an optional abbreviation. A language left with neither a name nor an
  abbreviation is not sent.
- The active flag is the point of the section: the message editor's source picker offers active
  sources only (it filters client-side on the flag), so deactivating a source hides it from editors
  without touching historical references.
- Export downloads the all-language catalogue from the public endpoint; import starts the
  backend source-import job.

## Users and contacts

Two page-local tabs with no URL state — a reload lands on the first tab the user's roles allow.

The **Users** tab additionally requires the Keycloak client role `manage-users` on the
`realm-management` client. The check is verbatim: no fall-back to a Niord realm role and none to
the session domain's client roles. Without it the tab is hidden, Contacts becomes the default, and
the Keycloak-proxy fetches never start.

### Users

Users are not stored in Niord — the tab is a thin client over the backend's Keycloak proxy. The
whole user list is fetched once (the Keycloak search returns no total count) and filtered and paged
on the client; the realm holds operators, not end users, so one fetch is fine.

- **Two identifiers, never interchangeable.** `keycloakId` is the path id for update, delete and
  every group call. `username` is the human key mailing-list membership is looked up by, and it is
  immutable once the user exists — renames belong in Keycloak's own console, linked from the
  toolbar (the URL and realm come from the live Keycloak adapter, with `niord` as a last-resort
  realm fallback).
- The password field is **write-only**: blank means unchanged and stays off the wire. The three
  required-action checkboxes (update profile, update password, verify email) are sent **on create
  only**; they cannot be re-applied through this form.
- A preferred language is a single code, not per-language text; blank is omitted from the payload
  rather than sent empty.
- The **Groups** tab shows the Keycloak group tree read-only and **scoped to the session domain**
  server-side, so switching domain changes which groups and memberships are visible. Only active
  groups can be joined; groups the user already belongs to are pruned from the available tree, but
  a joined parent's still-unjoined children surface in its place. Re-parenting groups is not
  possible here. A join or leave that Keycloak does not carry out is never shown as done: the
  backend answers 403 (Keycloak refused), 404 (no such user or group) or 502 (Keycloak failed or
  could not be reached) with a sentence, which the error toast shows.
- The **Mailing lists** tab toggles membership by `username`; a successful toggle is quiet and
  re-resolves the flags.

### Contacts

Contacts are Niord-stored email recipients used by mailing lists: a numeric id, an email, a name
and a preferred language, edited in their own drawer with a membership tab keyed by **email**.

- **Import is a paste of addresses, not a file upload.** The backend splits, trims, validates and
  de-duplicates against existing contacts _and_ users, and answers with a plain-text result that is
  shown verbatim.
- Export is the ticketed CSV described above.

## Mailing lists

A mailing list is a named recipient set (users and contacts) plus a list of triggers; **the backend
sends the mails**. Each trigger names the script resources that render the mail body and carries
one subject per language.

**Permission split.** The section opens to admin and sysadmin, but create, edit, copy, delete,
import and export are sysadmin-only — a plain admin sees the list and manages recipients. The id is
a user-entered string primary key, immutable after create and URL-encoded into every call; **Copy**
loads the details and clears the id so the save creates rather than overwrites.

**Triggers.** Two kinds, edited as collapsible cards inside the drawer:

|                 | Fires on                                                   | Carries                                             |
| --------------- | ---------------------------------------------------------- | --------------------------------------------------- |
| `STATUS_CHANGE` | a message entering `PUBLISHED`, `CANCELLED` or `EXPIRED`   | an optional filter expression evaluated server-side |
| `SCHEDULED`     | daily or one weekday, at a wall-clock time in a named zone | a message query and a public-report flag            |

- The presence of a per-language subject row **is** the enabled state for that language server-side.
  The per-language checkbox is only a representation: disabling one clears its subject, and
  disabled rows are stripped from the payload.
- A scheduled trigger without a time zone **cannot be saved**: a wall-clock time names no instant
  until the zone is known. Such a card opens expanded on the fault so the disabled Save button is
  never a guess. Switching a trigger to scheduled seeds the zone from the trigger's own, else the
  session domain's, and the time as that zone reads it now; with no zone to seed from it seeds
  nothing and asks.
- `publicReport` on a scheduled trigger is what makes it appear as a runnable report in the
  Messages action menu ([`messages.md`](messages.md)).
- **Test** posts the whole current — possibly unsaved — list plus the trigger's _positional_ index,
  so edits can be tried before saving; the index is read at click time because reordering or
  deleting shifts it. A status-change trigger needs a test message (remembered in the browser
  across sessions); the produced mails list in a table and the selected body renders in a sandboxed
  iframe, never as injected HTML.
- Expanding or collapsing a card never marks the form dirty.

**Recipients.** The count on each row opens the recipients drawer, which is available to plain
admins. Users and contacts are separate dual-list transfers, each with its own Save that is an
**authoritative bulk replace** of that side's selection. Saving toasts and keeps the drawer open so
both sides can be worked in one visit; an unsaved selection on either side guards the close. The
bulk add-all and remove-all buttons are deliberately **filter-aware** — they move the currently
filtered subset and show its count, never the whole list. Membership is keyed by `username` for
users and by `email` for contacts.

## Publications

The largest section, and the only one with a domain model of its own: series, issues, one-offs,
membership, cut-offs and the public window. All of it is written up in
[`../publications/domain-rules.md`](../publications/domain-rules.md). Mechanically it is the same
shell as the rest — a list page, a series detail reached by a trailing route param, and a one-off
detail as a sibling page under the same section.

Two screens branch on the series' membership regime: the series editor offers a third one — a
**compilation**, which names a source series instead of carrying criteria — and an issue of such a series
draws its member table grouped by the source issue that printed each row, with a Sources card beside it
while the issue is open.
Both are described in the domain rules.

## Publication categories

A section of its own rather than a tab inside Publications: a category is what a series _points
at_, shared across every domain's series, and it carries the `publish` flag that decides whether a
whole group of published issues reaches the public site at all. Every series must belong to one, so
without a way to create categories an admin could be blocked from saving a series by a required
field nothing in the product could extend.

- `categoryId` is the key series point at and the key an import file is written against —
  immutable once the category exists. A new id may not hold `/`, `\`, `%`, `;` or a control
  character, nor be `.` or `..`: the server's firewall refuses such an address segment, so the
  category could never be opened or deleted again. The rule (`publication-id.ts`, shared with the
  series editor's id) names exactly those characters and is no slug pattern; the server's create
  refuses the same.
- `priority` orders categories on the public page. A new category starts at 100, in the middle of
  the existing order, so it can be placed either side without renumbering. It is required, and it
  must be a whole number within the column's 32-bit range; while it is empty or outside that, the
  field says so and Save waits. The column cannot hold "no order", so a cleared box is never sent
  (the server keeps an absent priority as it is and refuses an explicit `null`).
- Only languages **with a name** are sent — a nameless row would render namelessly in the picker
  every series uses. At least one language must have a name (the server refuses a nameless
  category); until one has, Save waits and a line under the names says why. The description travels
  with its name and is sent even when blank, so clearing it clears it on the server.
- A refused save says why through the section's code mapping (`publication-errors.ts`): an id
  already taken (`CATEGORY_ID_TAKEN`) and a category the server's own checks refuse
  (`CATEGORY_INVALID`) each have a sentence; any other failure keeps "The category could not be
  saved". A refused save leaves the editor open.
- The list shows how many series and one-off publications point at each category. That count comes
  from one unscoped read of the whole estate (a category is shared across domains, and the refusal
  below counts every domain's series); until it resolves the column reads `—` rather than `0`,
  because `0` is the one value an admin acts on.
- **Delete is reachable only from the open editor** — the thing being deleted is the thing on
  screen. The server refuses while anything still points at the category; that refusal is
  recognised by its code and re-stated in the reader's language with the counts lifted out of it.
  The server's own sentence is never shown, and a refusal that carries no counts is stated without
  them rather than with a guess. A refused delete leaves the editor open.
- Import does not use the shared drop dialog: it confirms first, naming what the file overwrites
  (every category the file names is rewritten field for field; one that exists only here is left
  standing), then opens the picker. The upload runs as a batch job, so the toast reports acceptance,
  not completion.

## Where the code lives

|                                                                                                        | Path                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| ------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Section descriptors, routes, shell                                                                     | `ui-apps/admin/src/app/features/admin/`                                                                                                                                                                                                                                                                                                                                                                                                                         |
| Section pages                                                                                          | `ui-apps/admin/src/app/features/admin/sections/admin/<section>/`                                                                                                                                                                                                                                                                                                                                                                                                |
| API adapters                                                                                           | `ui-apps/admin/src/app/features/admin/services/api/` — except Sources and the area-sort re-index, which call adapters owned by `features/messages` (`ui-apps/admin/src/app/features/messages/services/api/sources-api.service.ts`, `.../messages-area-sort-api.service.ts`); messages is the one hub other features may import from ([`../architecture.md`](../architecture.md))                                                                                |
| Shared admin components (import dialog, recipient transfer, localized text input, master-detail shell) | `ui-apps/admin/src/app/features/admin/components/`                                                                                                                                                                                                                                                                                                                                                                                                              |
| End-to-end coverage                                                                                    | `ui-apps/admin/e2e/admin-shell.spec.ts`, `ui-apps/admin/e2e/admin-areas.spec.ts`, `ui-apps/admin/e2e/admin-charts.spec.ts`, `ui-apps/admin/e2e/admin-sources.spec.ts`, `ui-apps/admin/e2e/admin-users.spec.ts`, `ui-apps/admin/e2e/admin-mailing-lists.spec.ts`, `ui-apps/admin/e2e/admin-publications.spec.ts`, `ui-apps/admin/e2e/scenarios/weekly-publication.spec.ts` (a weekly series from creation to an amended issue, on the sample deployment; opt-in) |
