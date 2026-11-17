# Messages

The Messages area (`/messages`) is the reading surface for maritime safety messages: one search, five
ways of looking at its result, and every bulk action an operator performs on messages. It is the only
top-level area with **no role requirement** — anonymous visitors reach all five views, and the backend
decides what they may see. Source: `ui-apps/admin/src/app/features/messages/`.

Other features may import from `features/messages` (it is the one-way hub); it imports from no other
feature. See [../architecture.md](../architecture.md).

## Route shape and view modes

`/messages` is one shell route (`messages.routes.ts`) whose children are the five view modes, each
lazy-loaded. `''` redirects to `table`, and `**` falls back to `table`.

| Path                 | View     | What it is for                                                           |
| -------------------- | -------- | ------------------------------------------------------------------------ |
| `/messages/table`    | Table    | Six columns (ID, Dates, Title, Areas, Actions, Selection); the default   |
| `/messages/grid`     | Grid     | Thumbnail-first cards for visual triage — recognising a message by shape |
| `/messages/details`  | Details  | Full rendered message per row, with a map thumbnail                      |
| `/messages/map`      | Map      | Results drawn on an OpenLayers map; panning is a real spatial filter     |
| `/messages/selected` | Selected | A projection of the current selection — no search runs here              |

The shell route provides three things every child depends on, and the app would misbehave without
them: `TAG_SELECTION_STORE` → `LastTagSelectionService`, `MessageTagActionsService`, and
`MESSAGE_TAGS_DATA_SOURCE` → `TagsApiService` (the token's root default returns empty arrays, so
without this override the tag filter would silently never find a tag). A `drawerUnsavedChangesGuard`
covers leaving the area with a dirty drawer open.

`MessagesShellFacade` derives the view-mode policy from the URL alone:

- `showFilter` — false only on Selected, so that view has no filter panel (the filter and action
  dropdowns stay in the tab strip everywhere).
- `requiresSearch` — false on Selected **and** off the area entirely. The list facade is an
  app-lifetime singleton, so this flip is what re-runs the search when you come back from the editor.
- `maxPageSize` — 1000 in map view, 200 everywhere else.

Every view-mode tab link uses `queryParamsHandling="preserve"`, so switching views keeps the filter.

A switch between views that runs no search — Table, Grid, Details and Selected share the loaded list,
so only a switch to or from Map searches again — is made to answer the click as a search does, not
after the new list stands (the browser paints nothing while a task runs):

- The shell route's `canActivateChild` guard (`paintBeforeView`) lets the next view replace the one on
  screen only after one painted frame, and the tab strip marks the tab a navigation is on its way to
  (`Router.currentNavigation()`), not the one `routerLinkActive` would mark when it has ended. The first
  frame after a click shows the clicked tab as chosen.
- A list view renders no rows until it has been painted once (`paintedOnce()` in
  `shared/utils/next-paint.ts`): it shows what it shows while a search is under way — its header and
  `MessageListStatus`'s bar (`[pending]`) — and builds its rows, still in one go, in the task after.

A page that is not visible waits for neither, since it paints no frames. Query-only navigations (a
filter change) do not run the guard.

## Filter model

`MessagesFilterFacade` (root-scoped) holds **sixteen named filters**. Each is a typed value slice plus
an independent `enabled` flag, so switching a filter off and on again restores what was in it:

`domain`, `messageSeries`, `text`, `type`, `status`, `tag`, `publication`, `promulgationType`, `user`,
`comments`, `reference`, `aton`, `chart`, `area`, `category`, `date` — plus `sortBy` / `sortOrder`.

Enabling and clearing are separate operations: `setEnabled(name, false)` resets the slice **and**
disables it, `clearFilter(name)` does both explicitly, `setSliceValue` writes a value without touching
the flag. Panels read `facade.slice(name)` and `facade.enabled(name)`; both are memoised per name, so
reading them in a template is free.

Two behaviours worth knowing:

- Switching a filter on **from the menu** sets `panelFocusRequest`, and a single-field panel takes the
  caret when it appears. Restoring the same filter from a URL or a saved filter deliberately does not.
- One `effect` mirrors the `domain` slice into `DomainService.searchDomain`. That is the single source
  the type/status option sets and the public-search mask derive from, so every writer (URL restore,
  saved filter, clear-all, toggling the filter off) keeps them correct.

`aton` has model, serializer and URL support but **no UI** — the parameter round-trips opaquely and the
filter menu skips it. Do not document an AtoN field to users.

## Filter ↔ URL contract

`messages-filter-params.serializer.ts` is the single source of the URL contract. The filter state _is_
the URL: what a shared link carries, and what a saved filter stores verbatim.

| Parameter                                                 | Shape            | Notes                                                       |
| --------------------------------------------------------- | ---------------- | ----------------------------------------------------------- |
| `domain`                                                  | scalar           | `domainId`; unresolvable ids are held and retried           |
| `query`                                                   | scalar           | free text                                                   |
| `mainType`, `type`                                        | scalar, repeated | `NW`/`NM`; one `type` per checked message type              |
| `status`                                                  | repeated         | masked outbound in public search (below)                    |
| `tag`, `publication`, `promulgationType`                  | repeated         | opaque ids                                                  |
| `username`, `userType`                                    | scalar           | `CREATED_BY` / `UPDATED_BY` / `LAST_UPDATED_BY`             |
| `comments`                                                | scalar           | `ANY` / `OWN` / `ANY_UNACK` / `OWN_UNACK`; empty is omitted |
| `messageId`, `referenceLevels`                            | scalar           | the reference filter; levels clamped to 0–5, default 1      |
| `aton`                                                    | repeated         | opaque, no UI                                               |
| `chart`                                                   | repeated         | chart **numbers**, not numeric ids                          |
| `messageSeries`                                           | repeated         | `seriesId`                                                  |
| `area`, `category`                                        | repeated         | numeric ids                                                 |
| `fromDate`, `toDate`, `dateType`                          | scalar           | epoch ms; `dateType` emitted only with at least one date    |
| `sortBy`, `sortOrder`                                     | scalar           | emitted only when they differ from the domain default       |
| `zoom`, `lon`, `lat`, `minLon`/`minLat`/`maxLon`/`maxLat` | scalar           | map view only                                               |

Rules the contract depends on:

- Keys are emitted in deterministic alphabetic order; repeated keys keep the user's input order.
- A filter contributes nothing unless it is enabled **and** holds a value. Page and page size are never
  in the URL.
- Map parameters are **written** only in map view but **parsed** unconditionally, so map → table → map
  preserves the extent while a `/messages/table` link never carries a stale one.
- Opaque ids hydrate later: the field components resolve `tag`, `publication`, `chart` (by number),
  `messageSeries`, `area` and `category` against the backend and write the resolved entities back into
  the slice. A tag restored from a URL is a stub branded `_placeholder: true` until it resolves; the
  chip shows only its id until then. See `EntityFilterPanelBase.writeValue`, which deliberately skips
  the echo guard so hydration is not mistaken for a no-op.

Changing a parameter name silently invalidates every saved filter. Treat the table above as a public
API.

The reference filter's picker (`MessageIdField`) searches `search-message-ids`, which matches a typed
string by database id, short id or free text and says which one hit; the field shows that match type
beside each suggestion. It waits for three characters and **always** sends an explicit `deleted` flag
rather than leaning on the backend default (`false` today), because this filter never wants deleted
messages. The same field
serves the comparison dialog, which opts into deleted messages. For a guest (no USER role) the backend
matches published, cancelled and expired messages only and ignores `deleted`.

### The `messageId` collision

`messageId` means two different things: the reference filter's backend key, and the details dialog's
deep-link key. `MessagesFilterUrlSync` resolves this by renaming the filter to **`refMessageId`** on
the URL (restoring `messageId` only for the backend request) and skipping `MESSAGE_ID_QUERY_PARAM` in
both sync directions — inbound so opening the dialog does not refetch the list with a one-message
filter and wipe the scroll position, outbound so a filter change does not close the open dialog.

The URL sync writes with `replaceUrl: true` after a 200 ms debounce and canonicalises its own output
to recognise and skip the echo of its own navigation.

## Anonymous and public masking

Two independent masks, with deliberately different semantics.

| Filter                   | When the viewer is browsing publicly                                                                                                                                                         |
| ------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `status`                 | `effectiveStatusFilter` and the serializer strip anything outside `PUBLISHED_MESSAGE_STATUSES` (`PUBLISHED`, `EXPIRED`, `CANCELLED`). The user's checkbox selections survive in the UI.      |
| `comments` OWN/OWN_UNACK | Accepted inbound, kept in the UI, masked to nothing outbound while logged out — logging back in restores the choice.                                                                         |
| `user`                   | The opposite: the whole filter is hidden from the menu, inbound `username` parameters are discarded entirely, and logging out clears the slice. A session-less "my messages" has no meaning. |

"Browsing publicly" is `DomainFacade.searchPublicMessages()`: true when the viewer lacks the backend's
`user` role in the session domain (`AuthFacade.hasDomainUserRole`: the client role on the session
domain or on the base client; signed in alone is not enough) **or** when the chosen search domain
differs from the session domain.

The `user` filter itself is offered to every signed-in account, but its field searches the user
directory (`GET /rest/users/search`, role `user`) only with the domain role: without it the search
is disabled, a restored user name is shown as it is, and **Me** still sets the account's own user.

Frontend gating is affordance only. The backend re-validates every call — see
[../backend-api-notes.md](../backend-api-notes.md) for the search endpoint's own defaults and traps.

## Domain-driven filter visibility

The search domain (the `domain` filter, falling back to the session domain) decides what the filter can
offer: the NW/NM switch appears only when the domain has series of both main types, a per-type checkbox
appears only when some series supports that type, and the area / category / series pickers are narrowed
to that domain. With no domain resolved at all, everything is permissive. The domain also supplies the
default sort — its `messageSortOrder`, falling back to `AREA ASC` when the domain configures none or an
unparseable value — which is why `sortBy`/`sortOrder` are omitted from the URL when they match it.

## Named filters (saved searches)

`NamedFiltersFacade` is root-scoped and lazily gated: the `httpResource` URL stays `undefined` until
the user is authenticated **and** the filter dropdown has been opened once, so logging out returns the
resource to idle with no manual reset. Reads go through `hasValue()` because `value()` throws in the
error state.

- A saved filter stores the serialized query string verbatim; applying one runs it back through
  `applyQueryParams`, which restores the whole filter set — and deliberately does **not** request panel
  focus.
- Applying respects the same anonymous rules as a pasted link: a saved `username` is dropped for a
  logged-out viewer, a saved `comments=OWN` is kept but contributes nothing until they log in.
- Saving requires a non-blank name; deleting asks for confirmation naming the filter. Both re-fetch the
  list rather than patching it locally.
- Anonymous visitors see no saved filters, and **Clear all filters** / **Save current filter** are
  disabled for them.

## Search pipeline

`MessageListFacade` merges four drivers into one `switchMap`ped search: filter-driven, load-more, retry
and refresh.

- The filter branch debounces 100 ms, then `distinctUntilChanged`, and **only then** filters on
  `requiresSearch`. That order is load-bearing: `requiresSearch` goes false when no message view is
  mounted, and that key must still reach the comparator so returning to a view refetches a list whose
  filter never changed. The compared key is `{ params, requiresSearch, isMap, lang }` — the active UI
  language is part of the request identity, because list results are translated server-side and a
  payload fetched in one language is never reused in another.
- An `effect` clears list/total/page synchronously when `isMap` flips, so the transition never shows
  stale rows. Switching between table, grid and details keeps the loaded result — those three share it.
- Map mode requests 1000 rows and adds `viewMode=map` + `includeNoPos=true`; neither flag is in the
  URL, so the same link pasted into a table view genuinely returns a different result set. Dropping
  `includeNoPos` silently loses messages without geometry from the map.
- **Load more** appends the next page and re-asserts the server total; its label reads
  "from – to of total" with locale-formatted numbers, and it hides once everything is loaded or while
  an error is pending (a failed page must be retried, never skipped, so the loaded range has no gap).
  **Retry** re-issues exactly the failed request, keeping accumulated pages.
- After every successful search, selected messages present in the result have their stored snapshot
  refreshed.
- Results are grouped into `GroupedMessageEntry` rows carrying an `areaHeading` when the top area
  changes; this only happens while sorting by `AREA`. The heading area is found by climbing at most two
  levels up from the message's first area; messages with no area group under `NO_AREA_SENTINEL_ID`
  (`-999999`), rendered as the translated label "General".
- The empty state distinguishes zero rows with total 0 ("no matches") from zero rows with a non-zero
  total ("N matches").

A **publication filter refusal** is reported per chip, not as a broken list: a `400` whose
`error.code` starts with `PUBLICATION` is matched by prefix (not an enumeration) and its server message
rendered under the publication chip. As a list-level failure it would read "search is broken"; unsaid
it would read "this publication contains nothing", which is worse because it looks like an answer. What
a publication resolves to is defined in [../publications/domain-rules.md](../publications/domain-rules.md).

The first search does not wait for opaque ids to hydrate: a search fires as soon as the filter settles,
and the pickers write the resolved entities back afterwards.

## Selection

`MessageSelectionService` is root-scoped and in-memory only.

- Selection survives filter changes, new searches, view switches and domain switches. It is **never**
  persisted, so a page reload empties it — deliberate.
- It stores a snapshot of each message, not just an id, so the Selected view can render messages that
  have dropped out of the current result.
- Selection is keyed by `Message.id` (the backend UUID), never `shortId`.
- Anonymous visitors can select; it is the downstream actions that are gated.
- "Select all" from the action menu adds every **loaded** message (`selectLoaded`) — never the whole
  backend result set, which matters once paging is active. The table's header star is a toggle
  (`toggleAllLoaded`): anything selected clears everything, otherwise it selects all loaded.
- After a bulk status update the snapshots are patched directly (`applyStatusUpdates`), because a
  message whose new status drops it out of the filter would otherwise keep a stale badge.

## The five views

**Table.** Six columns: ID, Dates, Title, Areas, Actions, Selection. Sortable: ID, Dates, Areas —
Title is not. The Dates header is a split control: its label sorts by the date type currently shown,
its dropdown switches which date set the column displays (Event / Publish / Follow-up). Switching the
displayed type is presentation-only and does not re-issue the search; it persists per browser under the
`dateColumnType` localStorage key and is deliberately not in the URL. Choosing a date sort anywhere
(the action menu, for instance) moves the column to match. Area heading rows appear only while sorting
by area.

**Grid.** A responsive card grid, thumbnail-dominant. A card carries the thumbnail, the ID badge, the
title and the parts' HTML — no charts, publication, source, references or attachments, and part subject
headings are suppressed. Area headings span the full row. A small dimmed flag follows the title when the
message's first description language differs from the UI language; it is decorative (empty `alt`) and its
path comes from the configured language list.

**Details.** Three tracks per row: a 220×170 map-image thumbnail (`/message-map-image/{id}.png`) that is
the row's link and its single tab stop, the rendered message body, and the per-message cog. An
`originalInformation` message shows a star above the body. Below `md` the row stacks into one column.

**Map.** One full-bleed OpenLayers map with the message layer, optional chart and area overlays (each
visible only when that filter is enabled and non-empty), a hover tooltip and the layer switcher. A
left-column overlay carries the map-extent chip, the collapsible result-list panel (collapsed by
default; its heading says "partial" when fewer messages are loaded than the reported total) and the
switcher. Clicking a feature or a result row opens the details dialog; clicking never toggles
selection. Rows without geometry are listed but have no zoom button. Result rows carry the same dimmed
language-mismatch flag as the grid cards — the cue is implemented separately in each of those
components. Initial view = the current map extent (restored from the URL or carried over from the last
pan/zoom), else the domain's configured centre and zoom, else the deployment config's map defaults. For
symbology and layer mechanics see
[../map-portrayal.md](../map-portrayal.md).

**Selected.** The selection, rendered with the same row component as Details. No search, no paging, no
loading bar, no error banner, no area grouping. Every star is filled; clicking one removes that message.
There is no clear-all inside the view — the count badge and its inline × live in the tab strip.

Rows and cards in the table, grid, details and selected views are real links (`appRowLink` /
`appRowLinkAnchor`) carrying `?messageId=` with `queryParamsHandling: 'merge'`, so a modifier or middle
click opens the message in a new tab while a plain click runs the in-page activation. The map view's
result-list rows are plain buttons that open the dialog directly — no href, no new-tab behaviour.

## Message details dialog

A right-hand drawer over the current messages route, addressed by `?messageId=<id>`. Presence of the
param **is** the open state — `MessageDetailsDialogService.rootMessageId` mirrors
`Router.routerState.root.queryParamMap` and `isOpen()` is just `rootMessageId() !== null`. The service
is root-scoped, so it must not inject `ActivatedRoute` (that would resolve to `/` and wipe the active
route on every open); it navigates with empty commands, no `relativeTo`, and
`queryParamsHandling: 'merge'`.

- **Prev/next** walks a navigation context — an id list snapshot frozen by the opener at `open()` time.
  Each view supplies its own (the current result page; the Selected view supplies the selection).
  Arrow navigation uses `replaceUrl: true` so arrowing does not pile up history; open and close use
  `replaceUrl: false`, so one Back press undoes an open.
- **Drilling** into a referenced message pushes onto an in-memory stack that is never written to the
  URL. A refresh restores the root message and discards the drill — a shared link points at one
  message.
- The two navigation modes are mutually exclusive: prev/next shows only when the root is in the
  supplied context **and** the drill stack is at depth 1; Back shows only at depth > 1. Opening from a
  deep link or a map popup therefore shows no arrows.
- The embedded map is hidden when the message has no geometry **and** when the message carries an
  attachment with `display === 'ABOVE'` — that attachment claims the same slot.
- The dialog owns its own `MapShellService` provider so its map can never inherit another view's shell.
- A `null` body from the backend is a distinct state from a fetch error: `null` renders an in-drawer
  "not found" warning and leaves the drawer open; an error raises a toast, keeps the drawer open and
  renders no body. A refusal (401 or 403, as a guest's link to a message that is not public gets) counts
  as not found: the warning, and no toast, since a retry cannot succeed. The last resolved message is
  retained only across the loading window of prev/next and drill, so the map does not unmount while the
  next message loads.
- The selection star acts on the message currently **displayed** (a drilled reference included) and
  matches on the loaded message's UUID, because a reference drill navigates by short id.
- The tag manager is also a right drawer and drawers never stack. Opening it captures the full
  navigation snapshot (root id, prev/next context, drill stack), closes the details drawer and restores
  exactly that state — drilled reference included — when the manager closes.

## Per-message menu

The cog on a row/card and inside the dialog (`MessageDetailsMenu`) acts on **one** message; the toolbar
action menu acts on the result set or the selection. Both are on screen at once.

In the table, grid, details and selected views the cog is `MessageRowMenu`: until it is first used it
is only a stand-in button — `MenuTrigger`'s markup with the attributes CDK gives a closed menu's
trigger — and the first click, Enter, Space or Down/Up arrow puts `MessageDetailsMenu` in its place and
opens it with the focus CDK's own trigger would give it. A list builds a row for each of up to a few
hundred messages and the menu of one of them at most is opened, and the full menu (a CDK trigger with
a document listener, the items, the tag lookup and a complete tag picker) was about a quarter of the
time a table took to build. The dialog uses `MessageDetailsMenu` directly.

| Item                                           | Shown when                                                                   |
| ---------------------------------------------- | ---------------------------------------------------------------------------- |
| Edit                                           | `user` role **and** the message's series is editable in the active domain    |
| Copy                                           | the Edit conditions **plus** the `editor` role (it mints a new message)      |
| Print                                          | always — including anonymous readers; the print endpoint serves a public PDF |
| Add to tag… / Add to _last tag_ / Remove from… | `hasDomainUserRole` (the `user` role in the session domain)                  |

Opening the editor is open to any logged-in user whose domain owns the series: a plain user lands on a
read-only working copy and can still comment. Mutation is gated inside the editor, not by hiding Edit —
see [editor.md](editor.md).

The message's current tags are fetched **lazily, on first open, and re-fetched on every subsequent
open** (an `openTick` counter drives the `httpResource`). Without that, a list of N rows would fire N
tag requests on mount, and "Remove from…" would go stale after a bulk tag change elsewhere. That fetch
sends `locked=false`, so a locked tag never appears under "Remove from…" either, even when the message
is in one. Remove is a
submenu so the top-level menu height cannot grow with the tag count; a disabled "Loading…" child stands
in while the fetch is in flight, and the parent disappears entirely if the message is in no tags.

Role checks go through `AuthFacade.hasRole` and `hasDomainUserRole`, which read the resource roles on
the session domain and on the base client — so Edit, Copy and the tag items appear and disappear as the
user switches domain, with no reload.

## Action menu (toolbar)

`MessagesActionMenu` builds its items from login state, role and selection size. "Domain role" is
`AuthFacade.hasDomainUserRole`, the backend's `user` role in the session domain: every item gated on it
reaches an endpoint that asks for that role (the selection's temporary tag, the tags, the mailing-list
reports), so a signed-in account without it is not offered what would be refused with 403.

| Item                                                          | Condition                                          |
| ------------------------------------------------------------- | -------------------------------------------------- |
| Print (current search)                                        | always                                             |
| Print selection                                               | domain role **and** selection > 0                  |
| Mailing-list report                                           | domain role                                        |
| Send selection by mail                                        | `editor` **and** domain role **and** selection > 0 |
| Add / Remove selection to/from tag                            | domain role **and** selection > 0                  |
| Manage tags…                                                  | domain role                                        |
| Select all                                                    | always                                             |
| Clear selection                                               | selection > 0                                      |
| Compare                                                       | exactly 2 selected                                 |
| Export results                                                | logged in                                          |
| Export selection                                              | domain role **and** selection > 0                  |
| Import                                                        | `admin` or `sysadmin`                              |
| Update status                                                 | `editor` **and** selection > 0                     |
| Sort area messages                                            | `editor`                                           |
| Sort by (Areas, ID, Event date, Publish date, Follow-up date) | always                                             |

Tag management is reached only from here — there is no standalone Tags toolbar button. The sort
button on an area heading follows the same rule as Send selection by mail: `editor` **and** domain role,
since it too starts with a temporary tag (see Area sort below).

## Message tags

A tag is `{ tagId, name, type: PRIVATE|DOMAIN|PUBLIC, expiryDate?, messageCount, locked }`. Visibility
is **backend-enforced and never filtered client-side**: private to its owner, domain to the matching
session domain, public to everyone. A consequence to live with: a tag picked in one domain may not
exist after switching.

**Tag picker** (`TagPicker`, an anchored popover) serves every tag surface in two modes:

- `add` — a membership editor for the target messages. Each row's checkbox is checked (all targets in
  the tag), indeterminate (some) or unchecked; toggling fires the idempotent add/remove immediately,
  optimistically, and reverts that one row if the call fails. **Locked tags are excluded** (`locked=false`
  on every add-mode fetch) because they can neither gain nor lose messages. `membershipOnly` narrows the
  list to the tags the selection is already in — the "Remove from tag…" flavour.
- `filter` — owns no state; the consumer passes `selectedIds` and applies each `toggled` event live.
  Locked tags **are** listed and selectable here, and the mode works for anonymous users. Its
  **Manage** link, which opens the tag manager, is offered with the domain role only, as
  **Manage tags…** is: the tag writes ask for that role.

Scope chips (private / domain / public) multi-select; none selected means all scopes; the private chip
exists only for logged-in users.

**Last-tag shortcut.** Picking a tag in add mode stores it as the last selection in `sessionStorage`
(`niord.lastTagSelection` + `niord.lastTagDomain`). A locked tag is never stored. The memory is keyed by
domain — read back under a different session domain it returns nothing — and is cleared outright on a
domain switch. It powers the one-click "Add to _tag_" entry, which hides when nothing is stored for this
domain or when the message is already in that tag. `TAG_SELECTION_STORE` is a core token with a no-op
root default; the messages route aliases it to `LastTagSelectionService`, which is why the
domain-switch wipe lives in this feature rather than in the domain service.

**Tag manager** (`MessageTagsDialog`, a right drawer from **Manage tags…**) is CRUD only; picking
happens in the picker. Editing is inline: New is a blank top row, Duplicate is a prefilled new row with
a blank id and the lock cleared, Edit is in place. An open draft marks the drawer dirty, so closing or
navigating away is change-gated.

- A tag's **name is its identity and is immutable on edit** (the field is disabled); Duplicate is how
  you get a differently named copy.
- **Clear messages** empties a tag without deleting it, and is offered only for an unlocked tag with
  messages. **Delete** removes the tag and is confirm-gated; deleting the stored last tag clears that
  memory.
- A locked tag offers no edit, duplicate or delete. **Unlock** is offered only to `admin`/`sysadmin`;
  everyone else sees a static lock glyph.
- A tag's **type** is changed on edit only by `admin`/`sysadmin` or by the owner of a private tag
  (the backend lists a private tag to its owner only). For a shared (domain or public) tag everyone
  else sees the type control disabled, with a hint; the backend answers 403 to such a change, and 400
  to one that would leave a tag with no owner or no domain. A refused save names the backend's reason
  in its error toast and keeps the row open. New and Duplicate take any type.
- Anonymous visitors get a read-only manager: no private chip, no New tag, no action column — they can
  still browse and pick.
- Sorting is server-side on name, type, created, expiry and message count. The table renders from a
  latched last-good result, so filtering or sorting never flashes the list empty.

There is deliberately no confirmation before removing messages from a tag — re-adding undoes it. After
a tag mutation neither the message list nor the open menu's tag list is refreshed; the menu closes and
the next open re-fetches, so a failed removal correctly still shows the tag.

## Downloads: print, mail, export, import

Every PDF and ZIP download out of this area is **ticketed**: the browser cannot attach the bearer token, so the
client fetches a fresh one-time ticket and embeds it in the URL, then navigates the browser to it.
Tickets are never cached. The print dialog and the export ask for a `user`-role ticket when the user
holds that role in the session domain (`AuthFacade.hasDomainUserRole`): a ticket with no role names
the user but grants none, so the backend would leave the non-public messages out of a printed or
exported list and refuse the PDF of a single draft with 403. A guest, or a signed-in user with no role
in the session domain, asks with no role and gets the public messages. Actions that must act on "the current selection" server-side first bundle the
ids into a short-lived temporary message tag and pass its id — the selection ids are never posted
directly. See [../backend-api-notes.md](../backend-api-notes.md) for the ticket and temp-tag endpoints.

**Print** has three flows, differing only in the URL: the current search → `report.pdf` with the filter
parameters appended; the selection → the same endpoint with `tag=<temporaryTagId>`; a single message →
`message/<id>.pdf` with no filter parameters. Report parameters are serialized with a `param:` prefix
and empty values are dropped. The print dialog offers report, orientation, paper size, map thumbnails
and the chosen report's free-text parameters:

- The **thumbnails checkbox appears only when the report leaves `mapThumbnails` undefined** — a report
  that sets it explicitly wins and hides the box.
- **Changing the report resets its parameters** to the new report's defaults, deliberately discarding
  what was typed: the keys are report-specific and carrying them across would apply a value to a
  differently meaning key.
- The first report is auto-selected only when none is set. The selector is hidden whenever there is no
  list to choose from — a caller-supplied fixed report, or a backend that returns none; a signed-in
  user can still print, on the backend's default. Parameter labels are backend keys rendered verbatim,
  not translated.
- A **guest** printing a list is offered the `public` report alone and gets it chosen, since the backend
  refuses any other list report to a caller it does not know, and the refusal would replace the
  application. A deployment without that report gives a guest no report, and _Print_ stays disabled. A
  guest's report chosen earlier that is not offered gives way to the first one offered.
- Only `pageSize`, `pageOrientation` and `mapThumbnails` persist (localStorage `niord.printSettings`).
  The report and its parameters reset every time the dialog opens.

**Mail** is bulk-only — there is no per-message mail. Recipients are picked from the suggestions of
`GET /rest/users/search-emails` (`EmailAddressField`): part of a name suggests the matching users of
the user directory, and a whole e-mail address typed is suggested as it is, so a reader who is no
user can be mailed; nothing typed is taken without being picked, and there is no CC/BCC. The subject
is mandatory, the body optional. The selection travels as a temporary
tag; mail needs no ticket. Subject, body and recipients travel in the body of
`POST /rest/message-mail/send`, so a long text fits and none of it reaches an access log. Send keeps
the dialog open while the mail is posted and closes it only when the backend has queued the mail; on
a failure the dialog stays open with what was typed, and the toast names the backend's reason when
it gives one (an address that is no mail address).

**Export** produces a ZIP: ticket, plus either the filter string or `tag=<temporaryTagId>` for the
selection path.

**Import** is a ZIP upload (admin-gated) posting a two-part `FormData` — the file plus a JSON `data`
part carrying the target series, an optional tag and four flags (assign new UIDs, preserve status,
create base data, assign default series). It starts an asynchronous batch job: the dialog shows the
backend's acknowledgement and a link into the Batch Jobs monitor and **does not refresh the list**.

## Bulk status update, compare, mailing-list reports

**Update status** (editor) lists the transitionable part of the selection — messages in a terminal
status are excluded because they have no valid target. Each row picks from its own valid transitions;
a "change all" control bulk-applies one distinct from→to transition across matching rows. Save confirms,
PUTs, toasts, patches the selection snapshots and refreshes the list. The wire payload keys on the
UUID. The list is one transaction on the backend: a refused item (409, e.g. a message another officer
changed since the dialog loaded) changes none of them, and the dialog toasts the backend's reason
(`messages.statusUpdate.toast.refused`) and stays open. The status endpoints apply the editor's own
Verify and Publish rule (MSG10): a Verify or Publish of a message with no title or with a mandatory
channel off, and a Publish before the message's publish-from date, are refused, and the 409's
`refusals` property names each refused message and its reasons. Before Save the dialog marks a row
whose list data already shows such a reason (no title; a Publish with a publish-from date still
ahead; the list carries no channels), but still sends it; after the 409 it marks the rows the backend
named with the backend's reasons, toasts how many were refused
(`messages.statusUpdate.toast.rowsRefused`) and stays open with the choices kept. Changing a marked
row's target drops the backend's mark.

**Compare** (exactly two selected, no login required) opens a two-message surface: two id pickers
(deleted messages selectable) feeding two independent loads, rendered side by side through the same
details renderer at a chosen content language, as an HTML word diff, or as a structured data diff.

**Mailing-list reports** (domain role) lists the public scheduled reports; Execute renders the report HTML
inside a sandboxed iframe — never `[innerHTML]` — and "open in new window" uses a `user`-role ticket.
The dialog is homed in this feature (with its own reports API service) so the admin → messages
dependency stays one-way.

## Area sort

Reordering messages within one area is reached from the messages views, not from the Areas admin
section. `SortAreaDialog` lists one area's messages in area-sort order and lets an editor drag rows;
each drop resolves the dropped row's new neighbours and calls `change-area-sort-order` with
after/before-id semantics, then reloads to show the authoritative order. Capped at 1000 rows.

Two entry points (`MessageAreaSortLauncher`):

- **From an area heading** — the contiguous run of message ids under that heading is collected into a
  short-lived temporary tag (30-minute TTL) and the dialog opens scoped to it, so a heading spanning
  mixed statuses sorts together. The pickers are hidden in this mode. The heading's sort button needs
  `editor` **and** the domain role (`AuthFacade.hasDomainUserRole`), since the temporary tag asks for
  the `user` role.
- **From the action menu** — interactive mode, with an area picker (one area at a time) and a status
  toggle over `PUBLISHED` / `VERIFIED` / `DRAFT`, seeded from the active list filter.

Closing the dialog refreshes the list either way.

## How a message is rendered

One renderer (`MessageDetailsRenderer`) has a wide blast radius: inside this area it serves the details
row (used by both the Details and Selected views), the details dialog and the comparison dialog; outside
it, the editor's live preview, edit form, status page, template-selection drawer and template-search
popover, plus the sysadmin category template result dialog. A rendering change lands in all of them.
`showPromulgation` is the opt-in those callers use. Its order is: above-attachments →
ID badge → title → References → Parts → Attachments → Charts → Publication → Promulgation (opt-in) →
below-attachments → Source.

- **ID badge** is colour-coded by status. For `NM` messages it appends `(T)` for a temporary notice and
  `(P)` for a preliminary one; `NW` messages never get a suffix. With no short id (a draft) the badge
  renders the translated type plus the main type, e.g. "Permanent NM" — never the title, which sits
  right next to it and would collapse the ID column into the title column.
- **Areas** render as a path built by climbing the parent chain, keeping the two broadest levels. The
  `-999999` sentinel renders as "General". The renderer body deliberately carries no message-level areas
  or dates row: areas live in the heading row and the table column, dates live per message part.
- **References** render the referenced id plus a translated suffix for `REPETITION`,
  `REPETITION_NEW_TIME`, `CANCELLATION` and `UPDATE`; plain `REFERENCE` gets none. Clicking one emits
  the id rather than navigating, so the surrounding surface decides whether to drill.
- **Attachments** carry a `display` placement: `ABOVE` renders inline above the body, `BELOW` inline
  below, and everything also appears in the collapsible thumbnail list (collapsed by default). A
  content type starting with `video` renders a `<video>`, everything else an `<img>`. Repository paths
  from the backend are **already URL-encoded and must be concatenated verbatim** — re-encoding
  double-encodes the percent signs and destroys the separators (`MessageUrlBuilderService`).
- **Source** line appends the publish date for messages in a public status, and appends it explicitly
  when the active label does not already interpolate it.
- **Promulgation** renders only for entries whose `promulgate` flag is set, and the heading is gated on
  the same predicate so a message with all channels off shows no orphaned heading. Channel renderers are
  a deployment extension point — see [../country-deployments.md](../country-deployments.md).
- Backend-authored HTML goes through the shared sanitiser (`safe-message-html.pipe.ts`, on
  `MessageHtmlSanitizer` in `shared-html/`), which keeps `text-align` and a capped `padding-left`
  and drops every other inline declaration; never call `bypassSecurityTrustHtml` in a component.
- A row whose follow-up date has passed gets the `.row-attention` red left rail plus a hover title
  (`RowAttentionDirective`) in the table, details and selected views — no row wash, the date text
  itself is deliberately not recoloured, and selection outranks the rail. Grid cards carry no such cue.

## Hold-E edit shortcut

`MessageEditShortcutService` tracks whether the plain **E** key is held — ignoring presses inside
inputs, textareas, selects and contenteditable, and ignoring modifier combos — and releases on window
blur so the shortcut cannot get stuck armed. While held, activating a message in the table, grid,
details or selected view routes straight to `/editor/edit/<id>` instead of opening the details dialog.
It is gated exactly like the menu's Edit: the `user` role plus a domain-editable series. A
non-editable message just opens normally.

**Known limitation:** on Windows precision trackpads, palm rejection suppresses taps while a key is
auto-repeating, so hold-E plus a trackpad tap does not register. A mouse click works. This is platform
behaviour, not a defect; no fix is planned. `editKeyHeld` is exposed as a signal if a tap-to-arm sticky
mode is ever wanted.

## Deliberate limits

- Selection is never persisted — a reload empties it.
- "Select all" means loaded, not the whole result set. UI wording must not imply otherwise.
- The drill stack is never in the URL; a shared link addresses one message.
- The map view's displayed order is the API's order, not the sort field.
- The date-column type is a per-browser preference, not part of a shared link.
- `aton` round-trips but has no UI.
- Server data is never cached beyond the view showing it; there is no client-side message cache.

## Tests

`ui-apps/admin/e2e/messages-shell.spec.ts`, `messages-flows.spec.ts`, `messages-pickers.spec.ts`,
`messages-row-links.spec.ts` and `messages-map-view.spec.ts` cover the shell, the action flows, the
picker fields, the row-link/new-tab behaviour and the map view. Unit specs sit beside their subjects —
`messages-filter-params.serializer.spec.ts` is the guard on the URL contract above. See
[../testing.md](../testing.md).
