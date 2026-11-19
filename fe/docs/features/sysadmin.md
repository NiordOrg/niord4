# Sysadmin area (`/sysadmin`)

Fourteen sections that configure the system itself: how messages are numbered, which tenants exist,
which channels a message can be promulgated on, what the server renders and mails out, and what the
job engine is doing. The `/admin` area next door is for day-to-day content administration — see
[admin.md](admin.md).

**Access.** The whole area admits the `sysadmin` role only (`SYSADMIN_AREA_ROLES` in
`ui-apps/admin/src/app/core/auth/area-roles.ts`). A role counts when it is a Keycloak client role on
the currently selected domain's client or on the base client (`niord-client-master`), which holds at
every desk (a realm role counts for nothing, on the backend either), so a user whose role is on a
domain's client only loses the area on a switch to a domain where they do not hold it. Backend role gates are often looser than the UI (several reads are public,
several mutations only need `admin`) — the area gate is the effective one, and the backend re-checks
every mutation regardless.

**Shape.** The rail, the overview cards and the routes are all projected from `SYSADMIN_AREA` in
`ui-apps/admin/src/app/features/admin/section-descriptors.ts`; adding a section means adding one descriptor entry
plus a `loadComponent` override in `ui-apps/admin/src/app/features/admin/sysadmin.routes.ts`. Each descriptor also
carries the help-anchor key its screen resolves against the deployment's documentation base. Most
sections are the same recipe — client-filtered, client-paged list, a create/edit/copy drawer, a
confirm-delete, and a cog carrying import/export — and nothing here is cached: each facade is
provided at its page, and a drawer's entity is loaded when it opens and dropped when it closes. That
machinery, the `settingGate` visibility mechanism and the master-detail tree scaffold are described
in [../architecture.md](../architecture.md); this document covers what each section _means_.

---

## Message series (`/sysadmin/series`)

A message series owns the numbering and the short-ID format of the messages that belong to it. Its
configuration reaches into the message editor, so changing one is the highest-blast-radius edit in
this area.

| Field                      | Rule                                                                                                                                                                                                                                                                                                                      |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `seriesId`                 | Free-text string, the primary key, immutable after create. **Copy** clones a series with the id blanked so it becomes editable and required again.                                                                                                                                                                        |
| Main type                  | `NW` or `NM`.                                                                                                                                                                                                                                                                                                             |
| Restrict types             | Optional subset of the main type's sub-types (NW: `LOCAL_WARNING`, `COASTAL_WARNING`, `SUBAREA_WARNING`, `NAVAREA_WARNING`; NM: `TEMPORARY_NOTICE`, `PRELIMINARY_NOTICE`, `PERMANENT_NOTICE`, `MISCELLANEOUS_NOTICE`). An **empty list means unrestricted** and is sent as an empty array — it is not "no types allowed". |
| Number sequence            | `YEARLY`, `CONTINUOUS`, `MANUAL` or `NONE`.                                                                                                                                                                                                                                                                               |
| Short format               | Template string; present for every sequence type except `NONE`.                                                                                                                                                                                                                                                           |
| NAVTEX preamble            | Separate template, offered for NW series only.                                                                                                                                                                                                                                                                            |
| Additional editor fields   | Fields the deployment turns off by default that this series forces on.                                                                                                                                                                                                                                                    |
| Exclude from message index | Keeps the series' messages out of the free-text search index.                                                                                                                                                                                                                                                             |

Rules the code enforces and a refactor must not "simplify" away
(`ui-apps/admin/src/app/features/admin/sections/sysadmin/series/series-rules.ts`):

- Only the ticks belonging to the **currently selected main type** reach the payload. Ticks made
  while the other main type was selected stay in the open editor (switching NW → NM → NW restores
  them) but are silently dropped from the save.
- `YEARLY` and `CONTINUOUS` are the auto-numbered types: only they show a next number in the list
  and a next-number row in the drawer.
- When the sequence type is `NONE` the Short Format group hides and `shortFormat` is omitted from
  the payload — the text the user typed survives in the open editor, so an accidental flip to `NONE`
  and back does not destroy it.
- The NAVTEX preamble survives every sequence type including `NONE`, and an empty one is omitted
  rather than sent as `""`.

Short-ID tokens, insertable from the field's menu: `${year}`, `${year-2-digits}`, `${week}`,
`${week-2-digits}`, `${number}`, `${number-3-digits}`, `${id}`, `${legacy-id}`, plus `${t-or-p}`
offered for NM series only. NAVTEX tokens: `${publish-date}`, `${short-id}`.

**Next number.** For an auto-numbered series the drawer shows the next number for the current year
with a pencil opening an anchored popover. It fetches the stored number per `{seriesId, year}`,
**Compute** asks the backend to derive it (for a `YEARLY` series from the year's existing messages,
for a `CONTINUOUS` one from the series' highest number in any year), **Update** persists it
and closes the popover only — the drawer stays open, and its display plus the list refresh behind
it. Dismissing without Update persists nothing. Year input appears for `YEARLY` series; a
`CONTINUOUS` series still reads the current year. Years are bounded 1900–2099 and Update requires an
integer ≥ 0. The backend refuses (400) a number below the one Compute gives, a number a message of
the series already has; the popover then stays open and its toast
(`admin.series.nextNumber.toast.belowUsed`) says so and points to Compute. Any other failure shows
the generic `updateError` toast. The pencil is disabled while the form has unsaved changes, because
the popover edits the persisted series, not the working copy.

"Current year" is read in the **session domain's** time zone, not the browser's — for the hours
either side of New Year two zones disagree about which year a number belongs to, and the domain
issuing the numbers is the one that decides.

**Cross-feature effect.** Whether a message's short ID and message number are hand-editable is
derived from the session domain's series configuration; a `MANUAL` series makes them editable. See
[editor.md](editor.md).

The section deliberately has **no cog**: the backend exposes no series upload endpoint, so there is
neither import nor export. The list filter matches a `seriesId` substring only — not type, sequence
or format.

---

## Domains (`/sysadmin/domains`)

A domain is the application-level tenant. Its `domainId` **is** the Keycloak client id and is
immutable; **Copy** clones a domain with the id blanked.

A domain record carries: active flag, sort order, name (a single plain string — domain names are not
localized), an IANA time zone, a map centre (latitude/longitude plus a zoom level constrained to
3–15), a default message sort order, an HTML colour, the bound areas / categories / message series,
and three capability flags — **Publish** (promulgate by default), **AtoNs** and **Templates**
(message template execution).

- The **name is required**, as the domain id is on create: Save stays disabled while it is blank.
  The backend refuses a domain without an id or a name with 400 ("A domain needs a domain id" /
  "A domain needs a name") before it asks Keycloak for the domain's client, and an update that blanks
  the name the same way. Any other row the database refuses (a name, colour or sort order longer than
  255 characters; the form sets no limit) is refused before Keycloak is asked too, with a 500 and no
  sentence, so no client is left behind for a domain that was not made.
- The **time zone is the domain's clock** for the whole application: message dates, mail-queue day
  boundaries and the series next-number year all read it. It is mandatory — Save stays disabled
  until a zone is chosen, a persisted blank one included. See
  [../coding-standards.md](../coding-standards.md).
- The message sort order is one of six fixed strings (`AREA ASC`, `AREA DESC`, `ID ASC`, `ID DESC`,
  `DATE ASC`, `DATE DESC`) sent verbatim. Blank persists as null; a stored value outside the six
  renders blank rather than being forced to a default.
- The time-zone options come from `Intl.supportedValuesOf('timeZone')`, with a persisted-but-missing
  zone (an alias or a deprecated id) prepended so it still displays and survives an untouched save.
- `inKeycloak` and `firingSchedule` are backend-derived and read-only — the form never sends them. A
  firing schedule is attached from the Firing Schedules section, not here.
- Save reduces the pickers to minimal references — `{id}` for areas and categories, `{seriesId}` for
  series — so stale localized name payloads never round-trip.

**Keycloak coupling.** Creating a domain silently auto-provisions the matching Keycloak client,
best-effort: if provisioning fails the domain is still created and the failure is not surfaced.
`inKeycloak` is tri-state — `true` means the client exists, `false` means it is missing, `null`
means the backend could not reach Keycloak at all; false and null are treated alike. The edit
drawer's Keycloak status row (edit mode only) is the retry path: its **Create in Keycloak** button
posts a void provisioning call, so the new state can only be learned by refetching the list — which
is what a successful provision does, updating the row in place while the drawer stays open. The
toolbar's Keycloak link opens the admin console's clients view, composed from the deployment's
configured Keycloak URL and realm — the realm is never hardcoded.

**The admin list is not the session catalogue.** It is fetched with `?inactive=true` and shows every
domain, including inactive ones and ones the signed-in user holds no role for; it must never be
filtered by per-user editability, and the application's bootstrap (active-only) domain fetch must
never be reused for it. Export downloads the complete catalogue including inactive domains, so a
backup round-trips through Import.

After a successful create, update or delete the application-wide domain catalogue — the header
switcher and the capability gates — refreshes live, without a page reload.

---

## Promulgation types (`/sysadmin/promulgation`)

A promulgation type is a configured message-editor channel: each active type materializes its own
section in the message editor and drives that channel's generate call. Its `serviceId` decides which
backend promulgation service renders and sends it.

- The service list is **discovered at runtime** from the backend registry and labelled with the
  backend's own `serviceName`. Those labels are backend display data, never translation keys, and
  the service list is never hardcoded — a deployment's backend can add its own service.
- `typeId` is a free-text string primary key chosen once at create and immutable afterwards. It keys
  every nested per-service endpoint, the editor's generate call and stored promulgation records.
  `serviceId` is fixed by the Add menu entry and is never editable.
- Fields: active, requirement (`OPTIONAL` / `DEFAULT` / `MANDATORY`), name, priority, language
  (blank = any, omitted from the payload), domains, restrict types, script resource paths.

**Restrict types.** A main type's checkbox group is offered when the type has **no** domains
selected (both groups offered) or when at least one selected domain owns a message series of that
main type. Checks whose group is currently hidden stay latent in the working set — regaining domain
support in the same session restores them — and pruning happens only at save: deselecting the last
supporting domain silently drops those sub-types from the saved set. The working domains are
re-resolved against the session domain catalogue by `domainId` before the support check, because the
copies stored on a type can be stale.

**Per-service configuration** is dispatched through the `PROMULGATION_ADMIN_PLUGINS` multi-provider
token — one of the three promulgation registries, whose rules (root-injector registration only,
dispatch by `serviceId`, an unregistered id renders nothing) are in
[../architecture.md](../architecture.md) → _Promulgation registries_. The plugin section mounts for
persisted types only (every nested endpoint needs an existing `typeId`); in add mode the drawer says
to save the type first.

| Service     | Configuration                                                                                                                                                                                                                                                                                                              |
| ----------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `navtex`    | Transmitters. `name` is the primary key within the type; active flag; a multi-select of areas.                                                                                                                                                                                                                             |
| `safetynet` | Distribution areas in four shapes — **CIRCULAR** (center `Lat Lon` + radius in NM), **RECTANGULAR** (SW corner + northings + eastings), **COASTAL** (NAVAREA + coastal area A–Z), **NAVAREA** (I–XXI) — each with name, active, sort order (default 100), an optional message-area prefix and an optional repetition code. |
| `twitter`   | A single OAuth settings record per type: format, include-thumbnail, API key/secret, access token/secret.                                                                                                                                                                                                                   |

SafetyNET repetition codes: `REPETITION_01` transmit once, `REPETITION_11` two transmissions six
minutes apart, `REPETITION_16` every 12 hours, `REPETITION_42` every four hours. A blank choice must
be **omitted** from the payload, never sent as an empty string.

Twitter settings are **create-on-demand**: the first open of a type without settings legitimately
returns a null body (or an error), and the dialog starts an empty record with include-thumbnail on.
Stored secrets round-trip in clear text from the backend into password inputs. Saving Twitter
settings closes the dialog; saving a NAVTEX transmitter or a SafetyNET area returns to the in-dialog
list. Preserve that asymmetry.

Every nested body carries a `promulgationType: {typeId}` parent reference, and a PUT body's own key
(type id, transmitter name, area name) must equal the path segment — the backend rejects a mismatch.
Because sub-entities are looked up by name, **renaming a name-keyed sub-entity is not supported**.
Deleting a promulgation type does not cascade to its per-service data: the backend refuses a type
that still has transmitters, areas or Twitter settings, and the page shows its delete-error toast.

Changing this section changes the message editor: an active type must still materialize its channel
section, and its restriction must still gate which sub-types offer that channel. See
[editor.md](editor.md).

---

## Firing schedules (`/sysadmin/schedules`)

A firing schedule configures the backend's automatic firing-exercise message generator. It reads:
_for firing areas belonging to source domain X, generate firing-exercise messages into target domain
Y's message series Z, looking `scheduleDays` ahead, but only while `active` is set._

This is **not** the day-to-day firing-area period editor at `/schedule` (see
[schedule.md](schedule.md)), and it is not the firing-area message generation offered from the Areas
admin page.

- Both domain pickers list only the **editable** domains for the signed-in user — those whose
  Keycloak client grants them a role — not every domain in the system.
- The target message series options are domain-scoped and dependent: they come from the selected
  target domain's own `messageSeries`, so no message-series endpoint is called. Changing the target
  domain clears and repopulates them, and a target domain with exactly one series auto-selects it.
- Requiredness is deliberately asymmetric: source domain, target domain and target series are
  required; **scheduled days is not** (an empty horizon is a valid schedule); active defaults off.
- The Target Series column shows the raw series id with no name lookup, unlike the domain columns,
  which show the domain's name — the contrast is name-vs-raw-id, not localized-vs-raw.
- The cog's **Update firing exercises…** fires a bodyless call that runs the backend's regeneration
  of the exercise messages. The call answers when the run has finished, with the number of schedules,
  areas and cancellations it could not do: a clean run toasts success, any failure a warning with
  their sum. The row is disabled while the request runs.

---

## Dictionaries (`/sysadmin/dictionaries`)

A dictionary is a named store of entries; each entry has a `key`, one description per language
carrying `value` and an optional `longValue`, plus an optional non-localized `atonFilter`. The page
enumerates whatever names the database holds: `web`, `message`, `pdf`, `mail` and `template` are the
stock set and a deployment may add others, but only `web` and `message` ever reach the UI. `pdf` and
`mail` back server-side rendering (PDF reports, e-mails); `template` is the value pool a LIST
parameter type draws from, read by this application.

**`web` and `message` are the runtime UI-text override.** They are fetched anonymously at bootstrap
and merged over the compiled translation bundle by key, so an administrator can re-word any label
without a rebuild, and a Dictionary edit pushes into the running app without a page reload. Three
rules make that safe — an override that drops a `{{token}}` the compiled value carries is refused,
a blank value never overrides, and a row the backend added itself and nobody has changed since
(`asAdded`) does not mask the compiled text; all three are described in
[../platform.md](../platform.md) → _The loader merges two sources_. Editing such a row makes it an
override like any other.

**Delete names the key's users.** Before the delete confirm opens, the page asks
`key-users` for the key (see [../backend-api-notes.md](../backend-api-notes.md) → _Dictionaries_)
and lists under the message first the parameter types whose LIST holds the entry, saying that the
delete is refused while they list it, then the script resources whose content names the key — or,
for a key under a runtime-built prefix such as `msg.status.`, names the prefix — at most ten and a
count of the rest, then what each part of the backend that reads the key in code does without it.
Where the web settings report S-124 off (`s124Enabled` false, computed from `niord.s124.enabled`),
the S-124 agency reader is left out, since only SECOM's exchange sets read it and SECOM cannot run
then; the cancellation-message reader stays, because the cancellation warning is issued whatever the
switch says. A key nobody uses says so. The confirm always ends by saying that a deleted key stays deleted after a
restart and that the dictionary import restores it. If the check fails (an older backend answers 404)
or has not answered within five seconds, the confirm still opens and says the users could not be
checked: a delete never depends on the check. A second Delete click while a check runs drops the
first check, so only the last click opens a confirm. The backend refuses to delete an entry a
parameter type lists (409, writing nothing); the error toast then carries the backend's reason, which
names the types.

**Import previews before it writes.** The cog's Import takes one `.json` file in the export format
and first asks the backend what importing it would do (`import-preview`, which writes nothing; see
[../backend-api-notes.md](../backend-api-notes.md) → _Dictionaries_). The dialog then lists the keys
the file would add, grouped by dictionary and ticked — except a key an operator deleted here, which
is marked as such and offered unticked, so restoring one key does not bring back every key an
operator removed; the keys it would overwrite, unticked, each as a table of what the overwrite
writes beside the deployment's values; how many keys are unchanged; and the entries it refuses (an
entry with no translation, a value too long, …), each with the backend's reason, shown as the
backend sends it. A ticked overwrite writes each language the file carries whole, value and long
value, keeps the languages the file does not carry and replaces the AtoN filter. The table therefore
has a line for each language the file carries, and a long-value line wherever the long value
changes — a long value the file omits or leaves empty is cleared, and the line says _(cleared)_ —
and an AtoN-filter line when the filter differs. Import sends the file with the ticked keys only, an
overwrite with the hash of the values its table showed: an unticked key is neither added nor
overwritten, and a key changed since the preview is kept, which the job log says. The job runs in the
background; the dialog shows the backend's answer and links to the `dictionary-import` job. Both
import dialogs behave alike from here on (see _Script resources_ below): every answer belongs to the
file it was asked for, a failure shows the backend's reason for a 4xx, and a failed import leaves the
ticks open.

Backend rules the editor mirrors:

- `value` **anchors** a description: a description with a blank value is stripped on both create and
  update, taking any filled long value with it — a long value alone never survives a save. The
  editor warns (non-blocking) when that is about to happen.
- Create fails when every description is stripped, so the editor requires at least one non-blank
  value to create. Update has no such floor — an edit can legitimately leave an entry with zero
  descriptions, which the editor also warns about.
- A key is immutable once created; the key input is disabled in edit mode. **Copy** re-enables it and
  strips the internal id so the save creates a new entry.
- The extended columns (long values + AtoN filter) auto-appear whenever any loaded description
  _defines_ a long value — an empty string counts as defined. The guess is recomputed on every
  reload, including after a save or delete, so it can override a manual toggle.

The list filter matches key, any language's value, and long values while the extended columns show.
Dictionary names and entry keys are URL-encoded everywhere — keys routinely contain dots and spaces.

---

## Parameter types (`/sysadmin/param-types`)

A parameter type is the schema for one field on a message-template execution form. Three kinds:

- **STANDARD** — backend-seeded built-ins (text, boolean, date, number, position types, …). They are
  **read-only**: no create, edit, copy or delete, and their rows carry no action icons. They are
  listed so they can be referenced. The New menu offers only List and Composite.
- **LIST** — an ordered subset of one dictionary's entries. The editor is a two-pane transfer:
  Available shows the chosen dictionary's entries minus the selected ones; adding appends to the end
  of Selected; removing returns the entry to Available; Clear All empties the selection. Membership
  is deduplicated on the entry **key**. Order within Selected is set by the per-row move-up /
  move-down buttons and is persisted — it is the option order the user sees at execution time.
- **COMPOSITE** — bundles nested sub-parameters. Each has a required `paramId` (the execution
  form-field identifier), a per-language name, a reference to another parameter type **by name**, and
  `mandatory` / `positionList` flags. The nested type list excludes COMPOSITE, so **nesting is
  bounded to one level**. A `paramId` is unique within its list: the parameter dialog refuses an id
  another row holds (compared exactly, as the backend compares it), because the execute form keys
  its values by `paramId`.

Three identifiers are easy to confuse: the type's numeric `id` (the PUT/DELETE path and the detail
fetch), a nested parameter's `paramId` (a string, scoped to the composite), and a nested parameter's
`type` (another parameter type referenced by _name_).

**Copy** clears both the id and the name, so the clone must be renamed and saves as a new record.
**Edit** re-fetches the full detail — the list payload may omit values and nested parameters. The
list is fetched all-language. Its **Model language** picker selects the displayed LIST values and
COMPOSITE parameter names client-side, with default-language fallback for missing text. Type names
remain plain identifiers, and the filter still matches those names. List-value and nested-parameter
tables share the page's selection without modifying the edited record. The nested parameter editor
is shared with TEMPLATE categories, whose tree also offers the picker and which allow all three
kinds in the nested type list where composites allow only STANDARD and LIST.

---

## Script resources (`/sysadmin/script-resources`)

A script resource is a Freemarker template (`FM`) or a JavaScript file (`JS`) stored in the database,
keyed by a numeric `id` for PUT/DELETE/history but by its human-readable `path` (e.g.
`resources/foo.ftl`) everywhere a user or another entity references it. Script resources back print
reports, mails and message templates. The catalogue is global — no domain scoping, no per-language
descriptions — so neither a domain switch nor a language switch affects the page.

- **The database is the source of truth.** A deployed database commonly carries resources that are
  _newer_ than any copy checked into a source tree — branding, contact details and wording change in
  the database. Before overwriting a stored resource, compare the stored version, the file you hold
  and the change you intend; byte-identity with a clean checkout is the tell that you are holding
  the older side.
- The type is chosen at create and then fixes three things at once: the editor's syntax mode, the
  path field's label, and the required file extension.
- **Copy** keeps the content and the directory, blanks the filename and drops the id, so saving
  creates a new resource rather than overwriting the source.
- **History** is unavailable until the resource has been saved once. Selecting one revision shows its
  source read-only; selecting two shows a side-by-side diff oldest → newest; selection is capped at
  two. A revision with no recorded author shows as System. The Source column says whether the
  revision came from an import (the page's or a fresh deployment's bootstrap) or from this page's
  editor; a revision written before the source was recorded leaves it empty.
- **Import previews before it writes.** The cog's Import takes one `.json` file in the export format
  and first asks the backend what importing it would do (`import-preview`, which writes nothing; see
  [../backend-api-notes.md](../backend-api-notes.md) → _Script resources_). The dialog then lists the
  paths the file would add, ticked, each marked when the missing-path report lists it, with an
  action that ticks only those (what a release's README advises); the paths it would overwrite,
  unticked, each with a side-by-side diff of the deployment's row (left) against the file (right);
  how many paths are unchanged; and the entries it refuses — a script or template the backend's
  script check refuses, or a path it does not accept — each with the backend's reason, shown as the
  backend sends it. Paths are compared case-insensitively, as the template loader finds rows, so a
  file path that differs from a row only in case is that row's overwrite (or unchanged), never an
  add. The two import dialogs are the one exception to the rule that a server refusal renders as a
  sentence ([../ui-standards.md](../ui-standards.md#a-server-refusal-renders-as-a-sentence-never-as-wire-text)):
  each reason names a token, a class, a path or a limit the sysadmin must see verbatim to fix the
  file. Import sends the file with the ticked paths only, an overwrite with the hash of the row its
  diff showed: an unticked path is neither added nor overwritten, and a row changed since the preview
  is kept, which the job log says. The job runs in the background, records the importing user and
  the source Import in each written row's history, and the dialog shows the backend's answer and
  links to the `script-resource-import` job.
- **Both import dialogs bind every answer to its file.** Picking another file, reopening the dialog
  and every close cancel the pending preview and import, and an answer that still arrives is
  dropped, so a late preview is never shown — nor ticked and imported — against another file. A
  failed preview or import shows the import error; for a 4xx it adds the backend's reason under it as
  the backend sends it (a problem document's `detail`, or a plain-text body), which is the same
  exception to the refusal rule; a 5xx, a network failure or an HTML error page shows the sentence
  alone. A failed import leaves the ticks and Import open, so the sysadmin presses Import again
  without picking the file again; only an answer that starts a job (`Started batch job '…'`) locks the
  ticks and shows the job link and the background note. An answer that started no job (`Error
executing batch job …`) is shown as an error, with the answer as its reason and no job link.
- A deep link `?path=<encoded>` looks the path up among the stored resources and edits the one whose
  path matches exactly. When none is stored, a notice says so and nothing opens; the link never
  writes a row. Exactly one leading `/` is stripped. It is read once on entry and the URL self-clears without re-navigating, so
  the open drawer survives. It is a **query** parameter, not a path segment, because resource paths
  contain slashes and a `%2F`-encoded segment is rejected by the SPA-fallback server.

The reusable path field has two modes: single (one path with a picker and an edit link) and multiple
(a reorderable list that never goes empty, each row with its own picker). By default the picker
accepts both types, and any caller may narrow it — Print Reports, the one single-mode consumer,
passes a Freemarker-only pattern so only `.ftl` is offered. The field holds path **strings** — one in
single mode, an ordered array in multiple mode — never a resource record.

The list filter matches the `path` only — not the type and not the content.

---

## Categories (`/sysadmin/categories`)

Categories form a global hierarchy used to classify messages. The tree takes no domain parameter —
every domain sees the same categories. It runs on the shared master-detail tree scaffold (drag to
re-parent, toolbar move up/down, an inline editor with an unsaved-changes guard) described in
[../architecture.md](../architecture.md).

A category has a type, `CATEGORY` or `TEMPLATE`, and the radio stays editable after creation.
**A template is a category** — there is no separate template entity. TEMPLATE nodes carry a tree
badge, and **Add is disabled while the open editor is a TEMPLATE** (you cannot start a new category
while authoring a template).

When the type is `TEMPLATE` a five-part authoring block appears:

| Part                     | Meaning                                                                                                                                                             |
| ------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Domains                  | In which domains the template is offered. This is the only domain scoping in the section.                                                                           |
| Standard template fields | Which standard message fields the execute form shows: `type`, `promulgation`, `id`, `publish_date`, `areas`, `positions`, `event_dates`, `work_vessel`, `markings`. |
| Template parameters      | Named, typed, optionally mandatory parameters, including position-list parameters.                                                                                  |
| Script resources         | The ordered `.ftl` / `.js` resource paths the template executes.                                                                                                    |
| Example message          | A message id the **Test…** button runs the template against.                                                                                                        |

**Test…** executes the template against the example message and opens a result dialog rendering the
produced message, with one button per configured language; it is disabled until an example message id
is set. When the run is refused, the error toast carries the backend's reason (for example, an example
message whose series the current domain does not hold). Template execution in the editor renders exactly what the template declares — see
[editor.md](editor.md).

Besides the template block a category carries an active flag, per-language names, an MRN, an
`atonFilter` and the same additional-editor-fields selection series and areas have. They have no geometry and no message-sorting inheritance — those belong to areas.
Opening a category seeds one blank script-resource row so there is always a row to type into;
creating a category places it under the selected node, or at the root when nothing is selected. The
cog carries a **Re-index categories** action beside import/export: it asks the backend to recompute
the whole tree's sort order in one call, rewriting every node's position, not just the subtree in
view.

Both candidate pools — additional editor fields and standard template fields — are **pinned lists in
the frontend** (`ui-apps/admin/src/app/features/admin/utils/editor-field-candidates.ts` and the constant in
`category-editor.ts`) mirroring the deployment's backend configuration. A deployment with different
message fields has to revise them; nothing checks that the two sides still agree. See
[../country-deployments.md](../country-deployments.md).

---

## Print reports (`/sysadmin/reports`)

A print-report definition is what the message print dialog offers and what a report-backed
publication points at.

| Field          | Rule                                                                        |
| -------------- | --------------------------------------------------------------------------- |
| `reportId`     | String primary key, required, immutable once created. Never a message id.   |
| `name`         | Required.                                                                   |
| `sortOrder`    | Defaults to 10.                                                             |
| `templatePath` | A path into the Freemarker script-resource catalogue, restricted to `.ftl`. |
| `domains`      | A domain list where **empty means all domains** — not "no domains".         |
| `properties`   | Free-form JSON object.                                                      |
| `params`       | Free-form JSON object of default report parameters.                         |

`properties.mapThumbnails`, when present at all (true _or_ false), hides the Include-thumbnails
checkbox from the print dialog and bakes the value in. `params` seeds the default values of the
report parameters the print dialog offers.

Malformed JSON in either editor shows an inline parse error and **blocks Save**. **Test print** is by
contrast always enabled: the message tag is optional (it only narrows which messages the test PDF
renders) and in-progress malformed JSON is tolerated, so the action can never throw. The tag is a
message-tag id, and locked tags are excluded from the picker. Only the report list expands a
report's `${year}`, `${week}` and their two-digit forms, so the test print first sends the in-edit
report to `POST /message-reports/expand-params` (SYSADMIN; it stores nothing) and opens the print
dialog with the answer's parameters; when that call fails, a toast says so and no dialog opens. The
dialog's header names the report and claims no message count (without a tag it prints the current
domain's list), and it shows no _Report_ select, which could not show an unsaved report or one of
another domain.

The report list is a global catalogue; `domains` scopes a report's applicability, not who may edit
it. Only the domain option list offered in the editor is session-aware. The list filters on both the
report id and the name.

---

## Mail queue (`/sysadmin/mails`)

Read-only but for one action, Send again: no create, edit, delete or cancel. Mails are produced
elsewhere — mailing-list triggers, the messages mail action, and promulgation — and this page is
where delivery is verified, failures diagnosed and a held or failed mail sent again. The lifecycle (`PENDING` → `SENT`, `HELD` or `ERROR`, with
server-side retry back-off) is entirely backend-driven. A mail is `HELD` when, as the sender job
handled it, the mail filter (`mailValidRecipients`) kept it from reaching all of its recipients: at
`LOG` nothing left the server; under a list of addresses a recipient not on the list was redirected
to the first listed address (a mail is `SENT` under a list only when every recipient was on it).
The status filter offers _Held_ between _Sent_ and _Error_, and its pill is pending's amber with a dashed
border.

- The search is **server-paged**, ordered newest-created first. Filters are recipient / sender /
  subject (substring), status (equality) and a from/to range. An empty filter value is **omitted from
  the query string entirely** — empty is not the same as absent to this backend, and the status
  filter's _All_ means "omit the parameter", not an enum value.
- The date range constrains the **created** timestamp, not the send date, and the picked day is
  snapped to start/end of day in the **session domain's** time zone before being sent as epoch
  milliseconds.
- The list response never carries the mail body; it arrives only from the per-mail details fetch,
  which is issued first — the dialog opens only on success, and a failure shows an error toast and no
  dialog.
- In the details dialog the date row shows the mail's `sendDate`, the time it is or was due,
  labelled by status: _Due_ for a pending mail (after a failed attempt, the time of the next
  attempt) and for a held one, _Sent_ for a sent one, _Last attempt_ for one that failed for good;
  it is left out when the mail has no send date. Error text and attempt count show only when an
  error was recorded. A _Sent again_ row gives the time and the user name of the last Send again
  (`sentAgainAt`, `sentAgainBy`) when there was one. Recipients are partitioned into TO / CC / BCC
  client-side and empty groups are hidden.
- Mail bodies are full, style-laden HTML documents. They render in the sandboxed-iframe primitive
  (`<app-sandboxed-html>`, empty `sandbox` attribute, an injected stylesheet because linked
  stylesheets cannot resolve inside an opaque origin). The body is never bound to live DOM: the
  primitive's sanitizer bypass exists only so the document reaches the empty-`sandbox` iframe's
  `srcdoc` intact, and that pattern must not be reused anywhere else.
- The retention note above the list is read from the `mailDeleteAfterDays` setting. A non-positive
  value means mails are never deleted, so the note is hidden; so is a failed or absent read.
- The toolbar has a manual refresh; there is no polling. Filter, page and dialog state are page-local
  and never reflected in the URL.
- **Send again** puts a mail back to `PENDING` exactly as it was made (subject, HTML text and
  recipients); the sender job sends it within a minute or two under the mail filter in force then.
  A row offers it (the paper-plane button before the envelope) for a `HELD`, `SENT` or `ERROR` mail
  with attempts; a `PENDING` mail is on its way, and an `ERROR` mail with no attempt is a mailing-list
  trigger's failure record, never a mail (`canSendAgain` in `send-again.ts`). The toolbar's _Send all
  matching again…_ shows only while the status filter is _Held_ or _Error_: it reads the search
  again with the committed filter as one page of 500 (`SEND_AGAIN_MAX`), sends nothing and says so
  when more match, leaves the records (and any sent mail: those go one at a time) out, and says so
  when none is left. The confirmation is the page's own (`ConfirmService` provided by the page,
  `<app-confirm width="44rem">`): a message for one mail, one sent mail or many, and one line per
  group of mails with the same subject, status and creation minute, oldest first, with the count,
  the subject, the creation time in the domain's zone, the status and the recipients — every one for
  a single mail, the first 10 distinct addresses and how many more for a group.
- Accept posts `POST /rest/scheduled-mails/send-again` (sysadmin only) with
  `{"mails":[{"id","status","sentAgainAt"}]}`, the status and `sentAgainAt` (epoch millis or null)
  the page showed; the backend locks each row and refuses it as `CHANGED` when either differs, as
  `GONE` when the row was deleted and as `RECORD` for a failure record, and queues the rest:
  `{"queued":[ids],"refused":[{"id","reason"}]}`. A refusal leaves the others queued, and the toast
  says how many were left. A 409 (the mail filter is `LOG` or empty) and a 400 (an empty list, more
  than 500, a duplicate id or a status it cannot send again from) change nothing and carry a problem
  document whose `detail` the toast shows; any other failure shows a fixed text, never the raw
  error. The page reads the list again after a success, and blocks a second request while one runs.
  No role gate is in the component: the sysadmin route and the endpoint's guard keep it to a system
  administrator, so `role-offers.json` has no item for it.

---

## Niord integration (`/sysadmin/niord-integration`)

A record describing a scheduled server-to-server message **pull**: from the remote instance at `url`,
pull messages and, for each named source message series on that remote instance, import them into
this instance's mapped target series.

This section is **visibility-gated** by the backend `adminIntegrationPageEnabled` setting: the rail
entry and overview card appear only when it resolves truthy, while the route is always registered so
a deep link resolves. The gate is not route protection.

- Three flags with their add-form defaults: `active` = **true**, `assignNewUids` = **true** (each
  imported message gets a fresh underlying UID instead of keeping its source UID), `createBaseData` =
  **false** (when set, the import creates missing base data such as areas and charts). Two of the
  three default on.
- The **source** series id is free text with no validation, deliberately: it names a series on the
  remote instance, which this application cannot enumerate. It must never become a dropdown or be
  validated against the local series list, and an empty source is allowed.
- The **target** series is a required select over the full, cross-domain list of local message series,
  with the raw `seriesId` as both value and label — no name lookup, no domain scoping. (Contrast
  Firing Schedules, whose series options are scoped to one domain.)
- The mapping table always shows at least one row: a blank row is seeded on add, seeded when editing
  an integration whose stored list is empty, and re-seeded when the last row is deleted. Deleting the
  only row therefore clears its two fields rather than removing it. That invariant — not a minimum-row
  validator — is what prevents saving an integration with zero mappings.
- The rows are saved **verbatim** — nothing is pruned or trimmed — but every row needs a target
  series, so a row with an empty target makes the form invalid and blocks Save. Only the source may
  be blank: a row with an empty source and a chosen target is a legitimate payload row.
- The URL field is required but has no URL-shape validation.
- The per-row **execute** action fires a bodyless call that triggers the pull server-side. There is no
  result payload, so the toast confirms only that the pull _started_.

The feature is entirely domain-agnostic, and the list shows only the URL and raw series ids, so a
language switch must not refetch it.

---

## SECOM (`/sysadmin/secom`)

Operates this node's SECOM / S-124 exchange with other maritime nodes: the MCP identity it presents,
which message series are published as S-124 datasets, the dataset instances generated from them, the
peers subscribing to them, and the outbound delivery queue. Nothing in the section is localized — no
request carries `?lang=`, so a language switch must not refetch it — and nothing is domain-scoped.

**Two independent switches decide what the page shows, and it tells them apart.** `secomEnabled` is a
value the backend computes from the running `niord.secom.enabled` and ships in `/settings/web`, true
exactly where the subsystem runs (no setting row decides it); like Niord integration it gates the rail
entry and the overview card only, never the route. `niord.secom.enabled` is deployment configuration:
without it the whole `/secom` controller is absent and **every** endpoint answers 404. The page reads that off the **status probe alone** — a 404 there replaces the tabs with a
note that the subsystem is off on this node, enabling it being a deployment change plus a restart
rather than a UI toggle. Any other failure stays an ordinary per-section error, so a 500 or a dropped
connection never tells the operator the subsystem was switched off.

Five page-local tabs with no URL state — a reload lands on Overview and a deep link cannot name a tab.
All six loads fire together on mount and each carries its own loading, error and retry, so a failing
datasets fetch does not blank the certificate and outbox panels; a mutation refreshes only the slices
it touched. There is no polling, and no cog: the backend exposes no SECOM upload endpoint, so the
section has neither import nor export.

| Tab          | Holds                                                                                                                                    | Backend                                                                                                                           |
| ------------ | ---------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| Overview     | Certificate age and expiry, when unattended renewal next fires, the exposed series, and the dataset / subscription / outbox volumes.     | `GET /secom/status`                                                                                                               |
| Certificates | The service certificate the node presents to peers, the root it chains to, and the trusted CAs inbound peer certificates must anchor in. | `POST /secom/security/reload`, `POST /secom/security/renew`                                                                       |
| Datasets     | The per-series exposure table and the generated dataset instances.                                                                       | `GET` / `PUT /secom/series-config`, `GET /secom/datasets`, `…/{uuid}/gml`, `…/{uuid}/republish`, `POST /secom/datasets/reconcile` |
| Subscribers  | Live subscriptions, and every peer node that has ever interacted with this one.                                                          | `GET /secom/subscriptions`, `DELETE /secom/subscriptions/{uuid}`, `GET /secom/nodes`                                              |
| Outbox       | One row per upload or subscription notification, with its attempt count, backoff schedule and last failure.                              | `GET /secom/outbox`, `POST /secom/outbox/{id}/retry`, `DELETE /secom/outbox/{id}`                                                 |

**Certificates.** Expiry is toned from the server-computed `daysToExpiry` — danger at 14 days or less
(an already-lapsed certificate is negative and also carries an explicit EXPIRED marker), warning up to
60 days, plain body text beyond — and both certificate surfaces read the thresholds from one place, so
the overview and the tab cannot disagree. **Reload** re-reads the configured keystore and truststore
and swaps them in without a restart; **Renew Now** is confirm-gated and forces a renewal against the
MCP Identity Registry ignoring the expiry window. Both leave the current key material active when they
fail, and both are blocked while their request is in flight, so an impatient double-click cannot fire
two MIR renewals. A run that renews nothing answers 400 and a missing renewal configuration 409, so a
successful response always renewed something — but it may still carry per-credential failures, which
raise a **warning** naming both sides rather than a success. Unattended renewal is armed only when it
is both enabled _and_ fully configured; enabled-but-unconfigured is called out in its own words,
because the operator believes it is on. The MIR Portal link appears only when the `secomMirPortalUrl`
setting is set.

**Datasets.** The exposure table is the **NW** message series joined with the stored config rows,
modelled on the series list rather than the config list, so a series whose row was deleted still
appears, unticked; only NW series can be exposed at all. A tick PUTs the whole row — exposure flag and
language restriction together — one save per series at a time, and the persisted answer is layered over
the loaded list so the ticks stay put without re-entering the list's loading state. The language
checkboxes narrow which model languages the series' datasets carry; **none ticked means no
restriction**, and the backend stores them in its own canonical order and echoes that back. The
checkbox list is the deployment's model languages while the backend validates against its own setting —
the two describe the same deployment, and a mismatch is a configuration error it reports as a 400.
**Reconcile Now** diffs eligible messages against the dataset store, and doubles as the backfill when
SECOM is first switched on. Its toast reports the sweep's created, updated, cancelled, unchanged and
failed counts; failed are messages whose S-124 GML could not be generated, for which no dataset is
written and which the next sweep tries again. Viewing a dataset's GML opens a read-only dialog fed by an authenticated
fetch, because the endpoint is `sysadmin`-gated and a plain new-tab link would reach it without a
bearer token. **Republish is not idempotent** — the backend enqueues one upload per matching
subscription on every call — so it is guarded per dataset while its POST is in flight.

**Subscribers and outbox.** Removing a subscription also notifies the subscriber, so it is confirmed as
a destructive action. In the outbox the status filter is part of the request key and refetches
server-side rather than filtering the loaded page, and the row actions are offered only where the
backend accepts them: **Retry** on `PENDING` and `FAILED`, **Cancel** on `PENDING` alone. Long WKT
geometries and failure messages are clipped in the cell, with the untruncated value on the row title.

Statuses render as translated `.status-badge` pills through an explicit, exhaustive enum → key map, so
a value this build does not know falls back to a readable label and the neutral badge default instead
of painting a missing-key placeholder. The four tables page **client-side** at the shared admin page
size over a full fetch — the backend returns the whole list, except the outbox: its endpoint answers
the newest 100 entries (its default limit), and when the list holds 100 the tab says it shows the
newest 100. The overview's outbox counts cover every entry.

---

## Settings (`/sysadmin/settings`)

Edits only the settings the backend flags as editable and the code declares (a settings catalog
entry). There is no create and no delete — which settings are editable is server configuration.
Settings are system-wide, not per-domain. A row of an earlier Niord version that no code declares (DK's
and GL's eight, `wmsLogin` and `faMrnPrefix` among them) stays in the database, but the backend's
`GET /rest/settings/editable-settings` leaves it out, so the page neither lists nor exports it
(owner, 2026-10-21; `docs/code-data-config-plan.md` §5.6).

The value control is chosen from the setting's declared `type` by **exact, case-sensitive** matching
(`ui-apps/admin/src/app/features/admin/sections/sysadmin/settings/setting-editor.ts`):

| Declared type       | Control                                                     |
| ------------------- | ----------------------------------------------------------- |
| `Boolean`           | Checkbox                                                    |
| `Long`, `Integer`   | Integer number input                                        |
| `Float`, `Double`   | Number input allowing decimals                              |
| `Password`          | Password input with browser autofill/autocorrect suppressed |
| `String`, or absent | Plain text input                                            |
| `json` (lowercase)  | Code editor behind a JSON-syntax validator                  |
| anything else       | Plain text input; the backend re-validates on save          |

- Password settings are masked as asterisks in the list but the edit control binds the **real**
  secret, so editing reveals nothing on screen while still round-tripping the value. The list filter
  spans key and value but matches a Password row against the **mask**, so a search cannot leak a
  credential.
- Key and description are display-only; only the value is editable. The save path key must equal the
  body key, and keys commonly contain dots, so they are URL-encoded.
- Saving a `json` setting is **blocked** while the text does not parse, with an inline error.
- A successful save is deliberately silent — no toast, unlike other sections. A failed save shows an
  error and keeps the drawer open. Every close path, Cancel included, refetches the list.
- The drawer edits a **deep copy** of the row, so the list is never mutated before a save lands.

**Which settings the web client actually reads.** That is a different, separately flagged set: at
bootstrap the application overlays the backend's web-visible settings onto its runtime configuration
by leaf key name, database winning, applied on the next page load. Several keys are therefore
_inert_ from this page's point of view — the excluded roots (`apiBaseUrl`, `keycloak.*`,
`production`, `languages`, the deprecated `helpUrls`) and the backend-only keys that match no
configuration leaf. The mechanism and the full list are in
[../platform.md](../platform.md) → _The web-settings overlay_; the page shows every setting it
lists identically, with no marker for the inert ones ([../backlog.md](../backlog.md)).

Some setting _values_ differ per deployment (`adminIntegrationPageEnabled`, `documentationUrl`, …).
That is data edited here, not a per-deployment code override.

---

## Batch jobs (`/sysadmin/batch`)

Live visibility and control over the backend job system: which job types exist and how many are
running, a type's recent executions, live progress, stop / restart / abandon, the data file a job
processed, its log files, bulk job start via an archive, and an escape hatch that schedules arbitrary
JavaScript server-side. The rail entry carries a running-count badge, polled every 10 s while the
user is anywhere in `/sysadmin`.

**The backend role split is not uniform and the page depends on it.** The status endpoint is public
but returns an _empty_ status object to a caller without the `admin` role (deliberately, so a poll
does not flood the server log); everything else — instances, logs, the data-file download, the
stop/restart/abandon actions, the archive upload and execute-JavaScript — requires `sysadmin`, because
job instances are not scoped to a domain while `admin` is granted per domain. The consequence is
intended behaviour: a user holding `sysadmin` but not `admin` sees an empty job-type list, yet can
still upload an archive and execute JavaScript. Do not "fix" that in the frontend.

- The per-execution action buttons render from **server-computed flags** (stoppable / restartable /
  abandonable) and must never be re-derived from the status: the server applies two instance-level
  rules the UI cannot see — only the _latest_ execution of an instance is restartable, and if any
  execution of the instance is stoppable then _no_ execution of it is abandonable.
- End time and progress are deliberately asymmetric: an end time shows when the execution's status is
  not `STARTED`; a progress bar shows only when the execution is `STARTED` **and** its _instance_
  reports a progress value. A started execution with no progress shows an empty cell — neither. Note
  the level mixing: progress lives on the instance, the started check on the execution.
- The data-file download link and its server thumbnail appear only when the instance has a file name;
  jobs with no data file show only the logs link. The download uses a one-time `sysadmin`-role ticket:
  the backend answers the instance, log, download and stop/restart/abandon endpoints for sysadmins only.
- The log viewer lists the instance's file names and loads a whole file as read-only text on
  selection. Re-clicking the selected file does not deselect it — once a file is picked, exactly one
  is always selected. A content-fetch failure renders the error body inline rather than as a toast.
- **Execute JavaScript runs nothing in the browser.** It posts the code to the backend, which
  _schedules_ a script-executor job; the confirmation means "scheduled", not "completed", and any
  output appears only in that job's logs. It is the most dangerous surface in either admin area, so a
  confirmation step and an explicit server-side warning precede it.
- The archive upload accepts `.zip`; the backend extracts it and starts the jobs it contains,
  returning accumulated status text the dialog shows raw and stays open on. Files with another
  extension are dropped silently by the picker. A batch set may name only the backend's allow-list
  of import jobs: one that names the script-resource, dictionary or settings import, or
  `script-executor`, is refused whole and starts nothing, and the answer says why. Those imports run
  from their own pages (the first two with their preview), and JavaScript runs through Execute
  JavaScript.
- Failure feedback is split by intent: the ambient 3 s page poll and the instances fetch fail
  **silently** and keep the last render, so a flaky backend does not spam the operator; user-initiated
  actions raise an error notification.
- The executions table is server-paged (5 per page); switching job type resets to the first page and
  clears the table immediately.
- An optional `/sysadmin/batch/{jobName}` segment pre-selects a job type on entry. It is read once and
  never written back, so clicking other type cards does not change the URL. The import dialogs'
  "view batch job" link targets this form.

The page's 3 s foreground poll pauses while the browser tab is hidden and is intentionally separate
from — and uncoordinated with — the 10 s rail-badge poll.

---

## Import and export

Every section that has a backend upload endpoint shares one import dialog: a single file,
auto-uploaded on pick or drop, the backend's raw response shown in a read-only result area, and the
dialog stays open so the operator can read it. Script resources and Dictionaries have their own
dialog with the same picker, which previews first and uploads only when the operator presses Import
with what they ticked (see their sections above). **A file whose extension is not accepted is
dropped silently.** Imports are queued as backend jobs, so the list is eventually consistent —
imported rows may not be visible when the dialog closes, even though every consumer refetches on
close.

Exports are browser downloads, which carry no bearer token, so protected endpoints get a fresh
one-time ticket per click (never cached). The required role differs by section and is not guessable:

| Section            | Export                                  | Import         |
| ------------------ | --------------------------------------- | -------------- |
| Message series     | — (no backend endpoint)                 | —              |
| Domains            | public URL                              | JSON           |
| Promulgation types | ticket, role `sysadmin`                 | JSON           |
| Firing schedules   | —                                       | —              |
| Dictionaries       | public URL                              | JSON           |
| Parameter types    | public URL                              | JSON           |
| Script resources   | ticket, role `admin`                    | JSON           |
| Categories         | public URL                              | JSON           |
| Print reports      | ticket, role `sysadmin`                 | JSON           |
| Mail queue         | —                                       | —              |
| Niord integration  | —                                       | —              |
| SECOM              | —                                       | —              |
| Settings           | ticket, role `sysadmin`                 | JSON           |
| Batch jobs         | instance's file, ticket role `sysadmin` | `.zip` archive |

---

## Known limits

- **Ghost session domain.** Deleting or deactivating the domain the current session is using leaves
  the header switcher and the capability gates on a dead domain until the page is reloaded.
- **Stale embedded series.** Editing a message series does not refresh the copies embedded in the
  domain records the session holds, so the promulgation restrict-type support check can lag until a
  reload.
- **No marker for inert settings.** The Settings page cannot tell an administrator which keys the web
  client never reads.
- **Pinned candidate pools.** The additional-editor-field and standard-template-field lists are
  frontend constants; nothing verifies they still match the deployment's backend configuration.
- **Plain-text code editors in two places.** The Settings JSON value and the execute-JavaScript dialog
  use the code control without a syntax mode, so they render the plain textarea fallback; the
  syntax-highlighting editor is already wired for script resources and their history diff.
- **No automated coverage for live channel data.** Creating a Keycloak client and mutating
  promulgation per-service data (NAVTEX transmitters, SafetyNET areas, Twitter settings) are
  deliberately left out of unattended end-to-end tests, because the shared backend's realm and
  promulgation types are live operational configuration. Those flows are covered by component and API
  specs and verified by hand; the exclusions are stated in the headers of
  [`ui-apps/admin/e2e/sysadmin-domains.spec.ts`](../../ui-apps/admin/e2e/sysadmin-domains.spec.ts) and
  [`ui-apps/admin/e2e/sysadmin-promulgation.spec.ts`](../../ui-apps/admin/e2e/sysadmin-promulgation.spec.ts).
