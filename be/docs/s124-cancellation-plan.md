# S-124 Cancellation Warnings — design (MessageKind)

When a published navigational warning is editorially cancelled, Niord automatically
issues the **cancellation NW** that S-124
(Ed. 2.0.0 §9.3 method 2, Table 8-1) and S-124R002 require — a real warning with its own
series number, retrievable by "name of series, sequence number, and year", carrying a
`References` element with `referenceCategory = 1 (Warning Cancellation)` pointing at the
cancelled warning.

This document describes that design: the `MessageKind` data model, the minting flow, the
per-series SECOM configuration, and the client/visibility rules. Spec-conformance context
lives in [s124-secom-spec-gap-analysis.md](s124-secom-spec-gap-analysis.md).

## Supporting mechanics (elsewhere in the tree)

Behaviors the cancellation flow relies on, owned by other layers:

| Item | Where |
|---|---|
| `referenceCategory` emitted on every `References` (CANCELLATION/UPDATE/REPETITION_NEW_TIME → 1, REFERENCE/REPETITION → 2) | `S124StandardMappings.toReferenceCategory` |
| `noMessageOnHand = false` always (true is reserved for empty in-force bulletins) | `S124StandardMappings.noMessageOnHand` |
| Bulletin codelists: `IN_FORCE` category 3, `NavwarnTypeGeneralLabel.IN_FORCE_BULLETIN` (8), 4× `{scope} In-Force Bulletin` + 4× `{scope} No Warning` `warningTypeLabel` values | bindings + `S124StandardMappings` |
| Mapper renders a zero-parts message as preamble + references only — exactly the Table 8-1 "cancellation-only" shape | `S124Mapper` (parts loop no-ops) |
| Cancellation pushes reach the original subscribers: dataset vicinity = own geometry ∪ referenced messages' geometries | `S124DatasetService.computeVicinityGeometry` |
| Public per-message S-124 endpoint, status-gated (`Status.isPublic()`, non-public → 404) | `GET /rest/S-124/messages/{uid-or-shortId}?lang=xx` (`S124RestService`, `S124Service.generateGML`) |
| Source-side self-cancellation marker: cancel/expire stamps `publishDateTo`, rendered as preamble `cancellationDate` in the re-pushed final GML | `MessageService.updateStatus` + `S124Mapper` |

## S-124 over the normal REST API

Satisfied by construction. The auto-created cancellation message is an ordinary
published, numbered `Message`, so `GET /rest/S-124/messages/{uid-or-shortId}` serves its
GML the same way it serves any other warning (and the *source*, being `CANCELLED` =
public, also stays retrievable there with its in-band `cancellationDate`).
`resolveMessage` accepts UID or shortId; shortId is the practical stand-in for the
spec's "series + number + year" lookup. Where `niord.s124.enabled` is `false` (the S-124
opt-out, the owner 2026-11-01; `secom-go-live.md` § S-124 off) `/rest/S-124/**` is unmapped
and answers 404, the cancellation message's GML included; the message itself is minted as
everywhere else (decision 2).

## Data model: `MessageKind`

Enum in `org.niord.core.message` (internal — deliberately **not** in the public
`org.niord.model` API):

```java
public enum MessageKind {
    /** An ordinary editorial message (default for all existing rows) */
    WARNING,
    /** Auto-created cancellation warning (S-124 §9.3 method 2); read-only, terminal */
    CANCELLATION,
    /** Reserved for in-force bulletins — not implemented yet */
    BULLETIN;
}
```

Why a kind column and not a `Type.CANCELLATION_WARNING` enum value:

- `Type` encodes the *scope* axis (LOCAL/COASTAL/SUBAREA/NAVAREA) and the S-124 mapper
  derives `warningTypeLabel` from it (`S124Mapper.toComplexTypeWarningTypeType`). A
  cancellation keeps the source's type, so the mapper needs **zero changes**; the XSD has
  no "cancellation" warningType — cancellation-ness is entirely `References`-borne, which
  matches this model exactly.
- `Type` is a public-API enum; a new value breaks strict Jackson clients (unknown enum
  values are un-ignorable) and touches the hardcoded type lists in the UI. `kind` stays
  out of the public `MessageVo` entirely — anonymous/v1 consumers see a normal scoped
  warning with a CANCELLATION reference, which is also what S-124 consumers see.
- Bulletins later compose cleanly: `type=COASTAL_WARNING + kind=BULLETIN` →
  "Coastal In-Force Bulletin"; with a Type-based model the bulletin's scope would be
  underdetermined (a series' `types` is a set and can mix scopes).

The pieces:

- `Message`: `@NotNull @Enumerated(EnumType.STRING) MessageKind kind = MessageKind.WARNING;`
  (same idiom as `MessageSeries.numberSequenceType`). Copied in the `Message(MessageVo)`
  constructor / `toVo` for `SystemMessageVo` only — the editor/admin API carries `kind`;
  the public `MessageVo` never does.
- Schema: the `Message.kind` column ships in the SECOM Liquibase changeset
  (`niord/src/main/resources/db/changelog/changes/V003__secom.sql`), alongside the
  SECOM tables. `kind` is `varchar(32) NOT NULL DEFAULT 'WARNING'`, no index (low
  cardinality, always combined with indexed filters).
- Validation on save: `kind == CANCELLATION` requires `mainType == NW`. (Deeper shape
  rules — has a CANCELLATION reference, no parts — are guaranteed by the auto-creation
  code, not validated on every save.)
- **Read-only guard**: `MessageRestService.updateMessage` rejects edits when
  `kind != WARNING` (400). Status changes and comments stay allowed; DELETE is
  unreachable anyway (PUBLISHED never transitions to DELETED).
- **Creation guard**: system-managed kinds are created by Niord itself, never by clients —
  `MessageRestService.createMessage` rejects `kind != WARNING` bodies (400). The *service*
  `createMessage` accepts them (the auto-creation flow constructs them internally), as do
  the admin-gated batch archive importers (archive fidelity: exported cancellation
  messages re-import with their kind).

## The minting flow (auto-creating the cancellation message)

What happens, in plain terms: when an editor cancels a published warning, Niord
automatically creates **one new, separate message** whose entire content is "warning X is hereby cancelled" — a real `Message` row
in the same series, taking the next warning number, published immediately, with a single
CANCELLATION reference pointing at the cancelled warning and `kind = CANCELLATION`.
That new message is what the S-124 spec calls the *cancellation NW*.

Issuing the cancellation warning is **not optional**: there is no per-cancellation editor
choice and no per-series switch. Every editorial cancel of a published NW warning issues
it — no REST parameter, no cancel-dialog checkbox, no sysadmin toggle. The only thing that
decides whether one is minted is whether the message *can* carry one: it must be a
`WARNING`-kind NW message in an auto-numbered series, because the cancellation warning is
an NW message that takes the next number in that series.

The entry point is a `@Transactional` method **inside `MessageService`** (it must reuse
the private `sendStatusUpdate`): `cancelMessage(String uid)` — performs the normal
`updateStatus(uid, CANCELLED)` logic and then builds and publishes the cancellation
message in the **same transaction** (if its creation fails, the cancel rolls back too;
the source stays PUBLISHED).

Field shape of the cancellation message:

| Field | Value |
|---|---|
| `kind` | `CANCELLATION` |
| `type` / `mainType` | copied from source (keeps scope for `warningTypeLabel`) |
| `status` | `PUBLISHED` (born published; never passes through the editorial workflow) |
| `messageSeries` | source's series |
| `number` / `shortId` | next in series via `messageSeriesService.updateMessageIdsFromMessageSeries(cancellation, true)` after `publishDateFrom` is set (mirrors the publish branch of `updateStatus`) |
| `publishDateFrom` | now |
| `publishDateTo` | `null` — stays in force until manually retired (decision 1 below) |
| `areas` / `areaSortOrder` / `categories` / `charts` | copied from source (`createMessage` re-persists them; categories also drive `navwarnTypeGeneral`) |
| `parts` / geometry | none — the `References` payload carries the meaning |
| `references` | one `Reference{messageId = source.getUid(), type = CANCELLATION}` |
| `descs` | explicit per-app-language titles, `autoTitle = false` (see Title below) |
| `originalInformation` | `true` |
| `createdBy` | current user (set by `createMessage`; creation is always editor-triggered, so a current user exists) |

Steps: build the cancellation message → `createMessage(...)` (validates series/type, persists areas/
categories/charts, fires `promulgationManager.onCreateMessage`, assigns uid/repoPath via
`@PrePersist`) → publish steps mirrored from `updateStatus`'s PUBLISHED branch
(`checkUpdateYear`, number/shortId assignment, `publicationService.updateRecordingPublications`,
`promulgationManager.onUpdateMessageStatus`) → `saveMessage` → `sendStatusUpdate(cancellation, VERIFIED)`.

The JMS status broadcast is what makes everything downstream work unchanged: the SECOM
listener re-reads the new message, finds it PUBLISHED + eligible (numbered, NW, series
exposed per `S124SeriesConfig`) and creates + pushes a new ACTIVE dataset;
mailing-list/Twitter listeners fire their normal PUBLISHED triggers (that *is* the
operational broadcast — promulgation `onUpdateMessageStatus` is a no-op for the source's
CANCELLED transition, so there is no double-broadcast problem to suppress).

### Trigger: unconditional, never per message or per series

Auto-creation is not configurable; clients cannot enable *or disable* it:

- `PUT /rest/messages/message/{id}/status` and the bulk
  `PUT /rest/messages/update-statuses` route **every** `CANCELLED` transition through
  `messageService.cancelMessage(uid)`, which applies the rule itself. No REST
  parameters.
- Clients: the computed, editor-only `SystemMessageVo.autoCancellationWarning` field
  tells a client whether cancelling the message issues a cancellation warning, so its
  cancel dialog can announce "a cancellation warning will be issued automatically"
  (backed by the pure `MessageService.willIssueCancellationWarning` — the same predicate
  that gates minting, so the note can never disagree with the behaviour). The editor
  cannot opt out. The administration app's cancel dialog shows the note when the field is
  true, and its bulk status dialog states the rule when an NW row is set to Cancelled.
- Sysadmin: nothing to configure — the series editor carries no cancellation-warning
  toggle.

Every other `updateStatus(..., CANCELLED)` caller never auto-creates a cancellation
message **by construction** (only the editorial REST cancel paths mint) — each one is a
verified must-not path:

| Caller | Why it must not auto-create |
|---|---|
| `MessageRestService.updateMessageStatus` `cancelReferencedMessages` cascade (`:605`) | The message being *published* is itself the method-3 "new dataset with cancellation"; auto-creating here would issue a duplicate cancellation NW per cancelled reference |
| `NiordIntegrationExecutionService` (`:160`, `:175`) | Mirrored messages — the upstream authority issues its own cancellations |
| `FiringExerciseService` (`:193`) | High-volume schedule churn; not an editorial cancellation |
| `ScheduledMessageService` (`:79`) | Sets EXPIRED, not CANCELLED — that is spec method 1 (self-cancellation via the in-band `cancellationDate`); creating one on expiry would be wrong |

### Guards

- **Series shape:** only NW series with automatic numbering (YEARLY/CONTINUOUS) mint —
  the cancellation warning is an NW message that must take the next number in the series.
  This is a property of the series, not a setting: there is nothing to validate at config
  time, and cancelling in a series that cannot number one simply mints nothing.
- **Terminal rule:** `cancelMessage` never creates a cancellation message when
  `source.kind != WARNING` — never a cancellation-of-a-cancellation (the gap doc's
  "SECOM-store rule"; enforced at the editorial layer, where numbers are assigned).
  Cancelling/expiring a CANCELLATION message itself remains allowed (flows through the
  store as a normal final push) — it just never triggers another auto-created one. This
  is a silent skip, not an error: there is no per-message request to refuse.
- **Skip, never fail:** when the message cannot carry a cancellation warning (terminal
  rule, or a series that is not auto-numbered NW), `cancelMessage` logs the reason and
  skips minting rather than failing the cancellation.
- The read-only/creation guards on `kind` (above) fence the rows themselves.

### Title

`autoTitle` composes titles from area lineage + part subjects; the cancellation message
has no parts, so at creation time `autoTitle = false` is set and explicit `MessageDesc`
titles are written for each app language: *"Cancellation of {source.shortId}"* /
*"Annullering af {source.shortId}"*. The phrase is resolved from the `message`
dictionary (key `msg.cancellation.title`, seeded for en/da) with an English fallback,
so deployments can re-word without code changes.

### Mapper

No cancellation-specific branches (`warningTypeLabel` from the copied type; references
and zero-parts handling already correct). Two cheap hardenings are asserted in tests:

- A `kind=CANCELLATION` fixture round-trips: exactly one `References` with category 1 /
  `noMessageOnHand=false` / `messageSeriesIdentifier` of the source, no `NavwarnPart`,
  and XSD-valid output.
- `navwarnTypeGeneral` falls back to Other Hazards when categories are empty — copying
  the source's categories keeps its classification aligned with the source's.

## Per-series SECOM configuration (`S124SeriesConfig`)

`S124SeriesConfig` lives in `org.niord.secom.model`, owned by `SecomConfigService`, with
its own Liquibase changeset (keeps the optional SECOM subsystem out of the core schema —
the same line the codebase draws elsewhere):

```java
@OneToOne  MessageSeries messageSeries;   // unique — one config row per series
boolean exposeOverSecom;
List<String> languages;                   // model-language codes, one comma-separated column (JpaStringListAttributeConverter); empty = no restriction
// future: bulletinEnabled, bulletinCadence, ...
```

- No row (or `exposeOverSecom = false`) = the series is never published over SECOM —
  closed by default (e.g. a coastal/local series that must not be published: simply never
  enable it). `S124DatasetService.isEligible`/`scopedSeriesIds` read the config rows.
- One SECOM endpoint serves **multiple series** by design: SECOM's filter vocabulary is
  product/geometry/UNLOCODE/time only (no series concept — `SecomV2Resource`); each
  dataset self-describes its series via `messageSeriesIdentifier` in the GML. Consumers
  can't opt out of a series except geographically, so producer-side per-series exposure is
  the control point. Footnote: dataset MRNs use the message shortId
  (`urn:mrn:iho:nw:<country>:<shortId>`, the country token from the `country` setting), so exposed series should have distinct short-ID formats
  (DK's do; UID is the fallback).
- Layering: `niord.secom.enabled` (boot property) remains the deployment-wide kill switch
  for the subsystem; this entity is the runtime per-series dial beneath it.
- `languages` is a *restriction* on the model languages the series' datasets may carry
  (e.g. a NAVAREA series in English only), not an ordering: an S-124 dataset carries the
  message texts in every model language the message has content in, and the restriction
  narrows that set. Empty = no restriction. `SecomConfigService.normalizeLanguages` stores
  it canonically (model-language order, lower-case, deduplicated) and rejects a code that
  is not a model language rather than dropping it. `S124DatasetService` resolves the row
  at generation time (`S124Service.languagesFor`); changing a restriction therefore changes
  the bytes of the series' active datasets, which the next reconcile sweep re-issues as new
  versions and pushes — deliberate and bounded to the series.
- Admin CRUD: `GET /rest/secom/series-config` +
  `PUT/DELETE /rest/secom/series-config/{seriesId}` (sysadmin-gated; the Datasets tab of
  the administration app's SECOM section sits on these). The PUT body is
  `{seriesId, exposeOverSecom, languages}` and replaces the row as sent - an absent or
  empty `languages` clears the restriction.

Scalar SECOM knobs are **ordinary `@Setting`s** — the generic Settings system is the
standard, no special-case settings storage: `secomSignatureAlgorithm`, `secomRevocationCheck`, `secomValidateClientChain`,
`secomServiceRegistryUrl`, `secomServiceRegistryInsecure`, `secomCallbackEndpoint`
(joining the `secomOutbox*` family) — editable on the generic sysadmin Settings page,
applied on restart, the same semantics `@Value` properties would have. The three crypto
knobs are read programmatically in `SecomConfiguration`'s `@Bean` methods, because that
configuration class is instantiated at web-server init, before the settings layer is
usable. Boot-only properties stay `@Value` and get **no UI** (nothing else in the app
displays deployment properties): `enabled`, the keystore/truststore family, the
`@Scheduled` crons and `producer-code` (deployment identity). Certificate-renewal
*state* is real entity data and can introduce a purpose-built entity when needed.

## SECOM section + kind visibility

The SECOM section of the administration app
(`fe/ui-apps/admin/src/app/features/admin/sections/sysadmin/secom/`) carries all the ops
views across five tabs: Overview (cert created/expiry, renewal projection, exposed series,
counts), Certificates (service/root certificate details, truststore entries,
reload/renew-now/MIR-portal actions), Datasets (the S-124 series exposure table —
`S124SeriesConfig` toggles vs all NW series — plus the dataset list: view stored GML,
republish, reconcile-now), Subscribers (subscriptions incl. delete, known peer nodes) and
the Outbox (status filter, retry, cancel). Its rail entry and overview card ride the
`secomEnabled` value of `GET /rest/settings/web`, which the backend computes from the
running `niord.secom.enabled` at each request (`SettingsRestService`, since the S-124
opt-out of 2026-11-01) — so it is true exactly on SECOM-enabled nodes. The
`secomEnabled` setting the SECOM subsystem auto-registers stays declared (add-only), but
`/web` puts the computed value over a stored row, and the administration app reads it from
that one anonymous map. The route itself is always registered; on a
node without the subsystem the section shows an explanatory note instead of raw 404s.

Message search has a `kind` parameter; *query-driven* searches (everything parsed
through `MessageSearchParams.instantiate()`: message lists, reports, exports,
mail-a-search, mailing-list trigger queries) default to `kind=WARNING`, so cancellation
warnings only appear when explicitly requested. Programmatic searches
(`new MessageSearchParams()`) stay unfiltered — the expiry sweeper keeps retiring
CANCELLATION messages, and the firing-exercise/mirroring/area-sort flows are untouched;
the SECOM reconcile sweep uses its own JPQL. The frozen v1 public API pins
`kinds=WARNING` (no new parameter). Direct addressing by ID
(`/rest/messages/message/{id}`, the S-124 endpoint) remains kind-independent, and so do
`messageId` reference lookups — the search ANDs the resolved reference-ID set with every
predicate, so the WARNING default is skipped there (a cancellation warning must appear
in the reference tree of the message it cancels); an explicit `kind` still applies. A
client lists one kind at a time — no `kind` for warnings, `kind=CANCELLATION` for
cancellation warnings — so per-row badging is pointless; an editor view instead shows a
read-only banner when the message's `kind` (on `SystemMessageVo`) is `CANCELLATION`. In the
administration app the Type filter's _Cancellation warnings_ chip sends `kind=CANCELLATION`,
and the editor shows a read-only banner on such a message and offers no edit and no Copy;
a copy made elsewhere (the list's row menu) comes back from `copy-message-template` as a
`WARNING`, since a copy is always an ordinary editorial message.

## Design decisions

1. **Lifetime of the cancellation message: `publishDateTo = null`** (stays PUBLISHED
   until manually retired). The product spec (PS Ed 2.0.0 §9.2.1, the IHO's;
   `docs/specifications/README.md`) treats a dataset with an empty `cancellationDate` as
   "valid until cancelled by a new dataset", with absence from the latest in-force
   bulletin as the failsafe — so consumers keep the cancellation dataset as valid until
   an editor manually expires/cancels the message in Niord (status changes on
   CANCELLATION messages remain allowed; only content edits are locked), or until
   in-force bulletin support lands and provides the spec's failsafe. Revisit if that
   becomes an operational burden; a self-cancelling TTL (§9.3 method 1, Table 8-1
   "new dataset with cancellation self-cancelling") remains the spec-blessed alternative
   and is a one-line change at creation time. Note for the exchange-set layer: with
   `cancellationDate` unset, the discovery metadata `temporalExtent` must be omitted
   (§12.2.2 — "only used when a NAVWARN have a known expiry date").
2. **Unconditional, no per-message and no per-series choice.** Every editorial cancel of
   a published NW warning issues the cancellation warning. It must **not** be possible to
   disable — not per message and not per series — so there is no REST parameter, no
   cancel-dialog checkbox and no sysadmin toggle. (An earlier revision made this opt-in
   per series via a `MessageSeries.issueCancellationWarnings` flag; the flag and its
   column were dropped, since S-124 §9.3 method 2 is what a conformant deployment owes
   its consumers, not a local preference.) The only series that do not mint are those
   that *cannot*: non-NW, or without automatic numbering. The S-124 opt-out
   (`niord.s124.enabled`, the owner 2026-11-01) leaves this decision unchanged:
   cancellation warnings are minted whatever the switch says (`MessageService` depends on
   nothing in `org.niord.s124`; `S124DisabledITTest` mints one with S-124 off).
3. **NAVTEX/SafetyNET: stays as-is.** No auto-generated NAVTEX text for cancellation
   messages; `onCreateMessage` attaches whatever promulgation shells the domain
   configures, but no text is authored and nothing is transmitted beyond the normal
   behaviour. Revisit only if operations asks for a generated "CANCEL {shortId}" text.
4. **Client visibility: opt-in lists, always addressable.** Searches default to
   `kind=WARNING`, and a client asks for `kind=CANCELLATION` explicitly — cancellation
   warnings are opt-in in every list, but always directly addressable by ID. No per-row
   badge; editor banner + cancel-dialog announcement instead (for the administration app,
   see [`fe/docs/backlog.md`](../../fe/docs/backlog.md#messages)).
5. **Kind creation is system-only.** Clients can never create CANCELLATION or BULLETIN
   messages through the API; the REST create endpoint rejects them (400). Only Niord's
   own flows (cancellation auto-creation, a future bulletin job, batch archive import)
   produce non-WARNING rows.

## Out of scope (tracked elsewhere)

- **Fileless dataset cancellation + 365-day cancellation catalogue entries / retention**
  (S-124R010/R011): implemented in the exchange-set layer — see
  [s124-secom-spec-gap-analysis.md](s124-secom-spec-gap-analysis.md) §3.
- **In-force bulletins / S-128**: parked (gap doc §2 — de facto the no-S128 spec
  variant, fully satisfied now that cancellation information ships). The `BULLETIN` kind
  value stays reserved and the codelist plumbing exists; the eventual shape is a
  scheduled per-series job producing `References` category 3 with `noMessageOnHand=true`
  for an empty series; its config lands in `S124SeriesConfig`.
- **Multi-target cancellations** (one NW cancelling several): the model supports it via
  multiple references; the auto-creation flow stays 1:1, multi-target remains
  manual-compose (method 3).
- **Retroactive migration** of historical CANCELLED messages.
- **MCP certificate lifecycle**: its own subsystem —
  `docs/mcp-identity-certificates-plan.md`.

## Tests

- **Units:** kind defaults/persistence round-trip; kind validation (CANCELLATION
  requires NW); field shape of the composed cancellation message
  (kind/type/series/reference/descs/titles); the gating incl. the terminal rule and the
  skip for series that cannot number a cancellation warning; mapper fixture for the
  cancellation-only dataset incl. XSD validation (`MessageCancellationWarningTest`,
  `S124Test`).
- **ITs:** REST cancel → source CANCELLED + exactly one new PUBLISHED
  cancellation-message row with expected shape (numbered from the
  series sequence), `GET /rest/S-124/messages/{cancellationShortId}` returns XSD-valid GML
  with category-1 reference to the source; cancel in a non-auto-numbered NW series → no
  cancellation message; publish-with-`cancelReferencedMessages` cascade → **no** auto-created
  cancellation messages; expiry sweeper → none either; SECOM store:
  the cancellation message's dataset ACTIVE + pushed, source dataset CANCELLED + final push +
  pinned-subscription cleanup; read-only guard → 400; anonymous GET of the cancellation
  message's S-124 works, anonymous cancel is rejected
  (`MessageCancellationWarningITTest`, `MessageKindITTest`,
  `S124CancellationWarningITTest`, `SecomConfigITTest`).
- **Config ITs:** eligibility honours config rows (no row ⇒ not exposed); admin CRUD
  authorization (sysadmin only).
