# Publications — domain rules

The rules a publication obeys, stated as rules. [`DATA-MODEL.md`](DATA-MODEL.md) is the field-level
specification and [`API-CONTRACT.md`](API-CONTRACT.md) is the wire contract; both are authoritative for
shapes, names and status codes. This document is authoritative for _why the shapes are what they are_ —
the invariants a change must not break.

The frontend surfaces live under `ui-apps/admin/src/app/features/admin/sections/admin/publications/`, with the pure rule
modules (`series-rules.ts`, `issue-number.ts`, `issue-overrides.ts`, `publication-visibility.ts`,
`member-change.ts`, `publication-status.ts`) deliberately free of injector and HTTP so the rules can be
pinned by specs. The screens themselves are described in [`../features/admin.md`](../features/admin.md).

---

## Vocabulary

| Word                    | What it is                                                                                                                                                                                   |
| ----------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Series**              | A publication as a standing thing — "Notices to Mariners", "Firing practice areas". Holds the settings every one of its issues follows. Key: `seriesId`, an authored slug, frozen at create. |
| **Issue** (_udgave_)    | One released document of a series — "NtM week 27, 2026". Key: `publicId`, a UUID; it is what a citation points at, forever.                                                                  |
| **Member**              | A message that is in an issue. Keyed by message `uid`, never by short id.                                                                                                                    |
| **Override / curation** | An audited decision, behind the `publication-curate` permission, to include or exclude a message against the query's answer.                                                                 |
| **One-off**             | A publication that came out once. Stored as a series holding a single issue; `kind = ONE_OFF`.                                                                                               |
| **Category**            | What a series points at. Shared across series, and carries the flag that decides whether a whole group reaches the public site.                                                              |
| **Content period**      | The stretch of time whose messages an issue carries.                                                                                                                                         |
| **Cut-off** (_skæring_) | The instant the content period ends — the instant membership is decided at.                                                                                                                  |
| **Publication moment**  | When somebody pressed publish. A different fact from the cut-off.                                                                                                                            |
| **Public window**       | When the public site lists the document as current.                                                                                                                                          |
| **Edition**             | Which version of a period's document this is. A property of every issue.                                                                                                                     |

---

## What a series is

Four independent properties. Reading any one off another is the mistake this model exists to prevent.

**Kind** — `SCHEDULED | UNSCHEDULED | ONE_OFF`. A **stored fact, never derived**. It is what tells a
one-off apart from a series that simply has no calendar, and the issue count that produced it was spent
once, at import. A publication cannot change kind because somebody uploaded a second file.

**Cadence** — `NONE | DAILY | WEEKLY | MONTHLY | YEARLY`. How often it comes out. **`NONE` is not
"one-off"**: eleven NCAGS editions, eight ice-service notices and four editions of Dansk Fyrliste have no
cadence and are unmistakably series. Reading `NONE` as one-off is what once put an eleven-issue series in
the one-off list.

**Content mode** — `GENERATED_FROM_QUERY | UPLOADED_FILE | EXTERNAL_LINK | NONE`. `NONE` is real: roughly
48 imported publications have no membership of any kind. A content-mode picker that cannot show a series
its own value converts it on the next save.

**Time relation** — `PUBLISHED_IN_INTERVAL | IN_FORCE_AT_CUTOFF | COMPILED_FROM_SOURCE`, plus the
`aliveAtCutoff` flag. Only a query-backed series carries them, and the third value is the whole of the
**compilation** regime: the issue holds what another series' published issues printed, so it has no
criteria and no liveness flag of its own. Two of the three **tile** — an issue starting where the last was
cut off — and everything that reads as tiling (the interval, the chain rows, gap detection, the first
interval start) asks that question, not "is it the interval relation".

### Same cadence, opposite content rule

`weekly-ntm` and `weekly-ntm-p-t` are both WEEKLY and obey **opposite** content rules:

|                  | `weekly-ntm` (NtM / EfS)                      | `weekly-ntm-p-t` (P&T)                      |
| ---------------- | --------------------------------------------- | ------------------------------------------- |
| Time relation    | `PUBLISHED_IN_INTERVAL`                       | `IN_FORCE_AT_CUTOFF`                        |
| An issue carries | messages _published_ in the period            | messages _in force_ at the cut-off          |
| Issues           | tile — each starts where the last was cut off | overlap, for years                          |
| Content period   | `(previous cut-off, this cut-off]`            | a single instant; **no lower bound at all** |

Counting by publication, `IN_FORCE_AT_CUTOFF` is the _larger_ shape — 531 of 1,077 imported issues. The
same contrast holds for `firing-practice-areas` and `efs-a` against the accumulated yearly list. So:

- **"the period an issue covers" is a category error on an in-force series.** Its issues share almost all
  their members with their neighbours by construction — two consecutive firing-area editions shared 31 of
  32 — and asking whether they overlap is meaningless.
- Chain rows (`INTERVAL_CHAINED`, `OVERLAPPING_ISSUE`) are **not applicable** on an in-force series: they
  are about interval bounds, which such issues do not carry.
- A weekly _cadence_ says nothing about the content rule. Read `timeRelation`.

### Series lifecycle

`DRAFT → ACTIVE → RETIRED`, with reinstatement back to ACTIVE. **A series never returns to DRAFT**:
DRAFT means "not finished yet", and a publication with issues, citations and readers is not that.
Leaving ACTIVE requires a reason; entering or re-entering it is a confirm. Entering ACTIVE asks the
activation rules, and with them C-4 (every criteria operand names something that exists), as every save
does; the series import asks C-4 on every row and skips a row whose operand names nothing, with a sentence
in the job log.

Retiring a series retires **nothing it has already released** — published issues keep their documents and
their public windows.

### Which settings apply

`series-rules.ts` encodes applicability, and the form model keeps everything the admin typed while
`buildSeriesPayload` strips on the way out — so switching a series from weekly to yearly and back brings
the weekday home instead of erasing it.

| Setting                              | Applies when                                                     |
| ------------------------------------ | ---------------------------------------------------------------- |
| Time relation                        | content mode is `GENERATED_FROM_QUERY`                           |
| Criteria and liveness flag           | query-backed **and not** a compilation                           |
| Source series                        | the time relation is `COMPILED_FROM_SOURCE` — and only then      |
| Weekday                              | cadence is `WEEKLY`                                              |
| Day of month                         | cadence is `MONTHLY` or `YEARLY`; month, only `YEARLY`           |
| Nominal cut-off time                 | any cadence but `NONE` (a wall clock in the owner domain's zone) |
| Period-boundary cut-off default      | cadence is `YEARLY`                                              |
| First interval start                 | query-backed **and** a relation that tiles                       |
| Page size / orientation / thumbnails | a report is configured                                           |
| Next-issue creation                  | **derived from the cadence**, never chosen                       |

These predicates _shape_ the payload; they do not judge it. The authority on validity is the server's own
`/validate`, deliberately not re-implemented on the client — a second validator is a form that says yes
and a save that says no. A refusal carries `fieldErrors[{rule, field, message}]`; the rule ids are
catalogued in [`DATA-MODEL.md`](DATA-MODEL.md).

**A cadence opens its own next issue.** Having a frequency _is_ the statement that the successor opens
itself when the current issue is published, whatever the time relation — an in-force successor is created
with no period start and its end one cadence period after the stamp. Having no cadence is the statement
that each issue is made by hand. The editor states the answer rather than offering a control the server
would refuse, and a stored value that contradicts the cadence heals on the next save.

---

## The cut-off model — four facts, not one

An issue has **four distinct time facts**. Conflating any two of them is the single most expensive mistake
available in this domain.

| Fact                   | Field(s)                      | Answers                           | Weekly                           | Annual in-force                              | Accumulated annual         |
| ---------------------- | ----------------------------- | --------------------------------- | -------------------------------- | -------------------------------------------- | -------------------------- |
| **Content period**     | `intervalFrom` → `intervalTo` | which messages belong             | `(prev cut-off, this cut-off]`   | a single instant                             | the calendar year          |
| **Cut-off**            | `cutoffStampedAt`             | the instant content is decided at | ≈ the release, stamped precisely | the validity year's start / the edition seam | 31 Dec of the content year |
| **Publication moment** | `publishedAt`, `publishedBy`  | when a person pressed publish     | minutes after the cut-off        | days or weeks into the new year              | the following year         |
| **Public window**      | `publicFrom` → `publicTo`     | when the site lists it as current | release to next release          | the year it names                            | the year it names          |

The cut-off is a fact about the **content**; the publication moment is a fact about the **process**. They
coincide for a weekly notice because the weekly release _is_ the act of closing the period; they differ by
up to a year for an annual, whose period is closed by the calendar and whose document goes out when it is
ready. **The membership rule reads the cut-off and the content period, never the publication moment.**

`publishedAt` is always the instant the release action ran, never the chosen cut-off — a back-dated
release that reported itself published at its cut-off would hide exactly the lateness the pair exists to
show.

### Default, and choosing

`cutoffDefault ∈ {RELEASE_MOMENT, PERIOD_START, PERIOD_END}` per series, seeded by the shape of the
publication and editable in series settings. It is a **default, never a rule**: the admin may name any
**past** instant at publish time; a future instant is refused (`CUTOFF_IN_FUTURE`), because a period
cannot end in the future without claiming to contain messages nobody has published.

Next issue starts exactly at the previous stamped cut-off — chaining, on tiling series.

### Nominal vs stamped, and two provenance vocabularies

Only some cut-offs were observed. The rest were computed, recovered or typed, and a reader deciding
whether to trust a period has to see which. **Two vocabularies, deliberately not interchangeable:**

- `intervalFromSource` / `intervalToSource` ∈ `STAMPED | NOMINAL | RECOVERED | MANUAL` — where a content
  period's **edges** came from.
- `cutoffSource` ∈ `STAMPED_AT_PUBLISH | STAMPED_MANUAL_TIME | RECOVERED_FROM_UPDATED |
RECOVERED_FROM_NEXT_TAG | RECOVERED_FROM_COVER | NOMINAL_CLOSE | PUBLIC_WINDOW | MANUAL | NOT_RELEASED`
  — how the **freeze instant** was arrived at.

Only `STAMPED` / `STAMPED_AT_PUBLISH` go unmarked on screen. `STAMPED_MANUAL_TIME` **is** marked: the
stamp is real, but the instant was chosen. `NOT_RELEASED` is not a recovery failure — nobody published the
issue, so there is no instant to find.

Cut-offs are read in the **owner domain's** timezone. There is no per-series zone; a second one could
disagree with the one that counts. See [`../coding-standards.md`](../coding-standards.md) → Dates and time
zones.

### One instant drives the open-issue screen

An open issue has exactly **two** dates: a period start and a planned cut-off — and "now" as the one
alternative when it is later than the plan. The screen has a view instant (`?at=`), and the member table,
the **release rail** (the publish checklist, specified under Publish below), the **omissions panel** (what
the criteria left out) and the headline count are all computed at that **one** instant. A
switch between the two appears only when now is past the planned cut-off and messages the criteria take
have been published since; those late messages are the first, collapsible section of the member list and
are not repeated below it. The **preview** follows the same instant: the screen sends it (the planned
cut-off in the planned view, now in the as-of-now view), and the preview is resolved and named at it.

While a period is being edited, the period **as typed** drives the list, its counts and the criteria
editor, live and before saving (`?from=`, echoed back as `viewedFrom`). The **workbench** — the one call,
and the one resolution behind it, that answers the whole open-issue screen (see Performance constraints) —
echoes `viewedAt`, `viewedFrom` and `lateAfter` so the screen can name the moment it is answering for
instead of assuming it.

The publish dialog pre-selects the viewed instant and offers **two options, no third**: the planned
cut-off (unavailable while it lies in the future) and now. There is no free "another time" — changing the
cut-off from the dialog edits the _plan itself_, through the issue's own edit path, so a cut-off changed
here and one changed in the edit panel are the same act with the same numbers.

---

## Membership

### The canonical rule

```
members(issue) = messages WHERE series-criteria match
                 AND publishDateFrom ∈ (previousIssue.cutoff, thisIssue.cutoff]
                 AND NOT (publishDateTo < thisIssue.cutoff)   -- "alive at cut-off": the date half
                 AND NOT (withdrawnAt   < thisIssue.cutoff)   -- "alive at cut-off": the status half
                 ± per-issue overrides (include / exclude, audited)
```

- The interval bound is **open below, closed above**, deliberately.
- A message with no publish date is dropped, and reported as `NO_PUBLISH_DATE` rather than silently.
- **Current message status is irrelevant.** A member that later expires or is cancelled stays in its
  historical issue; the document listed what it listed. **One exception, on the public surface only:** a
  query naming an issue widens its status set to `PUBLISHED`, `CANCELLED` and `EXPIRED` — not `DELETED` —
  so a member deleted after release drops out of that issue's **public** message list while the admin
  record is untouched. The member row stays, drifted as `exists`, and its status column reads _Deleted_.

### Alive at cut-off — both halves

`aliveAtCutoff` is a **per-series flag**, not a constant: the two content regimes in the archive differ by
exactly this one clause, and forcing it on breaks the older one. In-force membership _is_ the liveness
test, so the flag is forced true there.

When the flag is on, liveness takes **both** halves:

- **Date half** — `publishDateTo IS NULL OR publishDateTo >= cutoff` (null-safe; the unsafe form collapsed
  one P&T issue from 165 members to 47).
- **Status half** — `withdrawnAt`, the earliest message-history row with status CANCELLED or EXPIRED. A
  cancel never moves an editor-set future validity end, so a notice cancelled _before_ the cut-off with a
  validity end _after_ it is date-alive and status-dead. It is **out**.

Cancelled _after_ the cut-off, or with no history row to date the cancel, the message stays a member and
the acknowledgeable `CANCELLED_BUT_DATE_ALIVE` warning names it. Nothing about `withdrawnAt` is frozen:
history rows are immutable, so the fact is re-derivable.

_Diagnostic habit:_ before calling a cancelled-but-included member a bug, compare the message's cancel
instant to the issue's cut-off.

### Criteria

A criteria document is **structured, resolvable end to end, and picked rather than typed**. Kinds are the
server's camel-case wire names: `messageSeries`, `messageMainType`, `messageType`, `domain`, `area`,
`category`, `chart`.

Four constraints shape the editor, and each removes a control a reader might expect:

- **`match` has exactly one value, `ALL`.** Nodes are ANDed; the header says so in words.
- **`NOT_IN` is reserved and refused.** Every node is `IN`, so there is no operator control.
- **A kind appears at most once.** Two same-kind `IN` nodes under `ALL` are redundant or empty.
- **At most eight nodes**, and an `area` / `category` operand is an MRN, never a surrogate id.

Every edit re-probes `/resolve-preview`, which resolves against real messages and persists nothing. The
preview returns the real `total` and `candidates` plus a **capped sample** — a wide document over a live
corpus drops thousands of candidates, and serializing them while somebody is typing answers a question
nobody asked.

**There is no advanced-expression hatch, and it was never built.** The whole imported estate's filters are
covered by the structured vocabulary; an unknown one is detected rather than guessed at. Reopen the
question only against a concrete series the vocabulary cannot express.

### Curation overrides

Audited, and gated on a permission of their own. Every override carries author, timestamp and reason;
every member always shows why it is included.

**Curation is a tier, not a role.** The curation writes and the standing-overrides read are gated on
`publication-curate`, a named permission sitting between edit rights and admin. It is granted to admins
today, which is why curation looks admin-only — but the tier is real: those calls **403 where the rest of
the issue screen loads**, and the author-and-reason half of a why-line is absent below it. Widening
curation to another audience is a role change in the identity provider that touches no stored data, because
every override records its author regardless of who is allowed to write one.

- **Reason mandatory, 3–512 characters**, on include, exclude and clear alike — the same bounds
  server-side.
- **All-or-nothing over a selection.** A curator excluding eleven messages is taking one decision about a
  list; applying nine and refusing two leaves a state nobody asked for.
- **Include picks a message by short id or uid**, never by browsing.
- **Include takes only a message in a public status** (published, expired or cancelled — RI-1, the rule
  every other route into a member set applies). An include is printed into the public document at
  publish, so a draft, verified or deleted message is refused (`OVERRIDE_MESSAGE_NOT_PUBLIC`, naming every
  offender, nothing written). Exclude is not checked: excluding puts nothing into a document.
- **Standing exclusions come from `GET /issue/{id}/overrides`**, never derived from the audit trail: an
  exclude followed by a withdrawal leaves two history entries and no decision, and reading the history as
  a state brings a withdrawn exclusion back to life.
- An EXCLUDE is **not a member source** — an excluded message is not a member. `MEMBER_SOURCES` are
  `CRITERIA | OVERRIDE_INCLUDE | IMPORTED`.
- Overrides constitute membership on **any** content mode. The query gate governs whether the query runs,
  not whether an issue can have members.

### Per-issue criteria override

An issue may select something other than its series selects (`criteriaOverride`). One function computes the
effective document, and it is used by member resolution, the frozen snapshot, the publish checklist and
the message-side lookup. An override **equal** to the series' criteria is stored as no override.

Two predicates, two different questions — keying on the wrong one silently skipped the entire imported
estate once:

| Predicate            | Asks                                                                                                 |
| -------------------- | ---------------------------------------------------------------------------------------------------- |
| `criteriaOverridden` | Does this select something other than the series says? (a published issue answers from its snapshot) |
| _has own criteria_   | Did somebody **deliberately tailor** this?                                                           |

An imported issue answers **yes** to the first and **no** to the second: the importer recorded what each
release actually selected, and one series spans two content regimes.

This removes the reason to clone a series. An edition that must differ — a two-week issue over the week
52/1 turnover, a supplement carrying one extra message series — used to require cloning the whole template
into a throwaway series; six of those are in the archive, fragmenting the series they were cloned from.

### Compilations — a year of another series' issues

A series whose time relation is `COMPILED_FROM_SOURCE` selects no messages of its own. Each of its issues
holds **the union of what the source series' published issues printed** inside its period:

```
members(compiled issue) = frozen member rows of sourceSeries issues
                          WHERE status = PUBLISHED
                            AND effective cut-off ∈ (previousIssue.cutoff, thisIssue.cutoff]
                          deduplicated by message, the earliest issue owning a repeat
                          ± per-issue overrides (include / exclude, audited)
```

- **A source issue belongs to the period its stamped cut-off falls in.** Its own week and year numbering is
  not consulted, so a notice printed in week 1 of two different years lands in the annual whose period the
  earlier cut-off falls in, once.
- **Retired source issues are never read.** Retiring an issue is the statement that what went out for its
  period should not stand; its replacement, once published, is what the compilation carries.
- **Open source issues contribute nothing** and are reported rather than ignored — see the rail row below.
- **The order is the source's order.** Rows come out by source cut-off, then by the position the source
  issue itself recorded, so the document reads as the source documents one after another. Manual includes
  follow, in the series' ordinary sort, and are printed in a section of their own.
- **Nothing is re-judged.** There is no criteria document and no liveness flag: whether a notice was in
  force was decided at each source issue's own cut-off, and asking again at the compilation's would drop
  what those issues rightly carried. A tailored criteria document is refused for the same reason.
- **Curation works exactly as anywhere else.** An exclude drops a row; an include adds a message no source
  printed. An include naming a message a source issue already printed is refused as already a member —
  the compiled derivation is an automatic membership, like a criteria match.
- **No chains.** A source must be another query-backed series and may not itself be a compilation, or one
  issue's contents would depend on a derivation two levels away that nothing wrote down.

**On the screens.** The series editor offers the third regime with its own help line and replaces the
criteria probe with a **source-series picker** — active, query-backed series that are not compilations and
not this series, the owner desk's first; the server stays the authority and renders its refusal on the
field. On an issue, the member table is **grouped by source issue**: a heading row per source week, naming
it, linking to it, and carrying its content period (the stretch its notices were published in; the cut-off
alone for a source with no period start) and count, with the members beneath in the order that issue
printed them and the manual includes in a final _Added by hand_ group. The band reads "compiled from N
issues of …", naming the source series the way a reader knows it.

**The headings are the record of what the year covered, and the survey is what holds that record.** A
source week that printed nothing contributes no member row, so a table grouped from the rows alone would
drop that week silently and a year would quietly list fifty-one of its fifty-two weeks. Every published
source issue is a heading, in the frozen cut-off order, and one that printed nothing opens onto the same
"no messages" sentence an empty issue shows. Only a published source issue is a heading: an open week and
an uncovered stretch contributed nothing and are not part of what the issue holds.

While the issue is **open**, a **Sources** card lists every source period in range, marking the ones still
open and the ones with no issue at all. That card is the open issue's view of what is still outstanding,
so it is gone once the issue is released: on a released compilation every source issue — including one that
printed nothing — is a heading of the grouped table, and a card repeating those weeks beside it would draw
the same list twice. The view switch, the late section, the left-out panel and the criteria panel are all
absent: a compilation has no candidate set to reject and no predicate to preview.

**On the release rail**, a fifteenth row asks whether every source period inside this issue's period has a
published issue. It is a warning, not a block, and it is acknowledgeable — releasing the year in early
January before the last week is out is a real decision, not a mistake — and it names the open weeks and the
uncovered stretches.

**The estate.** The accumulated annual is converted to this regime **by the import itself**, keeping its
identifier, its category and the issues already carried in with their uploaded documents. Its earlier years
were made by hand in the previous system and stay exactly as they were imported; every year since then
shows on the strip as a missing period, which is where an admin starts each of them. See
[`../backlog.md`](../backlog.md) for the retroactive years and the source-drift banner, both deliberately
after go-live.

### The frozen snapshot

Publishing freezes the member list. The `frozen*` fields are what each message looked like at release and
are **never healed** from the live message — healing them would erase the divergence somebody has to see
before deciding whether to amend.

A message published **into a released issue's period after the release** is the other divergence, and it
is shown where both readers look: on the message, as a `MISSED` row of its publications panel, and on the
issue page, as the list of messages published since the release. An amend is still the only way to take
such a message in.

- Every frozen member is `PUBLISHED` by construction: nothing withdrawn before the cut-off is a member.
- The **title is deliberately live**, in the language the list was asked for. A frozen title would sit
  under a renamed message and say the wrong thing; the `frozen*` fields beside it already carry the record.
- The **status column is always present**, on open and published issues alike, and shows the status the
  message has **now**, in the standard colours — _Deleted_ where the message is gone.
- `drift[]` (`exists`, `type`, `status`) plus `current` say where the record and the live message
  disagree. A drifted row renders an **icon with a tooltip**, never a line of text and never a count above
  the table: on an issue of any age most rows have moved on, so text would be the norm and a permanent
  warning warns about nothing.
- The reference instant is the **cut-off**. Anything before it is inside the decision of what went out.
  The end of the publication window is frozen but never compared — it acquires a value as a side effect of
  every cancel and expiry, so it says nothing the status does not.

`membershipProvenance ∈ EXACT | EXPLAINED_DIFF | UNION_SNAPSHOT | NO_MEMBERSHIP | IMPORTED | COMPILED`
grades how much the stored list is trusted. **`COMPILED`** says the rows were derived from another series'
published issues rather than from a query, and the issue's header then names the source series and every
source issue it covered — the one record of that, since a source retired or published later moves the
union. **`IMPORTED` is the one every screen branches on**: an issue carried in with
the archive was never resolved here, so re-deciding its content is not an action that exists.

`NO_MEMBERSHIP` means _no membership semantics_ — nothing derives the contents. It is **not** a claim that
the row count is zero: six annex publications carry exactly one hand-named member row each, which is the
only surviving record of what that annex contained.

**An imported issue reads exactly like a native one.** Amend is the only thing they differ in, and that is
a button, not a table.

---

## Issue lifecycle

`OPEN → PUBLISHED → RETIRED`, with reactivate back to PUBLISHED. Publishing is one-way.

### Create

From a **reviewed draft**: the draft endpoint proposes the period, chains it off a neighbour, counts what
the criteria would select over it and warns; the create request carries the bounds that came back
approved. Both bounds travel together because a period is one decision — creating the issue and then
correcting its end is two writes with an issue standing between them whose period nobody agreed to.

A missing period in the archive is clickable: retro-create prefills from the neighbouring cut-offs with a
live preview, and marks the recovered bound's provenance as such.

### Publish

Freezes membership at the chosen cut-off, renders the document, stamps `publishedAt`, opens the successor
where the cadence says so, and caps the predecessor's public window at the new stamp.

The release rail is the **publish checklist**. Each row carries:

| Field                         | Meaning                                                                                                                                                                                                                             |
| ----------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `severity`                    | `OK \| WARN \| BLOCK`. A failing WARN may be acknowledged; a BLOCK never can.                                                                                                                                                       |
| `applicable`                  | `false` = the question does not apply to this issue and the row is dropped from the rail. A rule that does not apply passes for a reason that says nothing.                                                                         |
| `detailCode` + `detailParams` | The measured answer as a translatable sentence. Instants are epoch ms, never server-formatted text.                                                                                                                                 |
| `acknowledgeCode`             | **The code the gate compares, where it differs from the row's own.** The rail names the _question_; the gate compares the resolver's warning vocabulary. A ticked box must be sent back under `acknowledgeCode` where there is one. |

**`canPublish` is the server's answer and the only one that counts.** The UI must never re-derive it from
the rows, or the button and the backend disagree about the same issue. Submitting is deliberately _not_
gated on ticking everything: the server re-resolves at publish time, a warning can appear in between, and
a refusal names exactly what is still owed.

The rail follows the **chosen** cut-off, not the screen's: membership is resolved as of it, and a rail
taken at the other instant describes a release the admin has just declined to make.

Unacknowledged warnings refuse a publish under the manual gate. Over 1,000 members is a hard refusal
(`MEMBER_LIMIT_EXCEEDED`) — a product limit with roughly four times headroom over the largest set ever
observed.

The PUBLISHED audit entry records **the whole checklist it was published against** — every applicable row
with its code, severity, pass and acknowledgement — not only the warnings nobody ticked.

### Amend

Replace-in-place at the same public link, for the same issue. The interval and the stamped cut-off are
**unchanged**, and there is no cut-off question: re-taking the instant the content was decided would
quietly turn a correction into a different publication. Only one public version ever exists.

- Reason mandatory (3–512): an amend rewrites something people have read.
- The outgoing per-language file is **archived before the regenerate overwrites it**, recorded on the
  audit entry.
- **An imported issue is never amended** (`ISSUE_IMPORTED`) — its document is what people cited, and
  re-resolving against today's query is a rewrite of the record, not a correction.

### Work in flight belongs to the issue

A release and an amend each render the document again per language and take tens of seconds on a compiled
annual, and the request outlives the dialog it was pressed from. **That the work is running is a fact about
the issue, not about the browser that started it**: the server records it on the issue and the workbench
reports it, so a refreshed page, a second tab and another admin all see the same release in progress rather
than an idle issue with a button offering to render it a second time. A second publish or amend against an
issue that is already rendering is refused (`ISSUE_BUSY`), before anything is frozen or archived — two
renders write the same per-language files at once and the loser overwrites the winner's. The record is the
server's to clear: it goes when the request ends, and one older than the server's ceiling is treated as
stale, because a state that can be entered and never left would leave an issue nobody could ever publish.
A client that did not start the work re-reads the issue while the record stands and stops on the first
answer that carries none; the one that did start it waits for its own response instead, which is the only
thing that knows the transaction finished.

### New edition

A successor edition **links at create and hands over the window at publish**: creating it sets the
supersede link and audits it; the predecessor's window is closed by the publish that takes over. Capping
at create would leave the download site with no current edition while the replacement is still open.

**One open successor at a time**: while an edition superseding the issue is OPEN, New edition answers with
that edition rather than making another.

Hidden on a series that opens its own successor — the successor already exists, and a second document for
the same period competes with the amend flow — and on a one-off, which holds exactly one issue and has no
next issue to supersede.

### Retire and reactivate

Retiring an issue requires a reason and a destructive confirm. **The file and the public window stay**, so
citations keep resolving; what leaves is the public list. The confirm says exactly that, because there is
no verb anywhere that makes a released file unreachable.

Retiring also means **the period is no longer covered**: the timeline shows it as missing with the
retro-create offer, and the overlap rule ignores retired neighbours. So a wrong issue — typically an
imported one, which is never amended — is corrected by withdrawing it and creating, curating and
publishing a replacement for the same period. The withdrawn row stays in the archive beside it.

Reactivate is a plain confirm and asks for no reason: it restores the state the issue was already
published in, re-resolving, re-freezing and re-capping nothing.

### Delete

| Status      | Deletable                                                                                                                                                                                                                                             |
| ----------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `OPEN`      | Yes, by an admin of the owner domain — **unless a message cites it**. The picker offers published issues only, but an imported issue keeps its legacy id and sits at `OPEN` until first published, so the messages that cited the legacy row cite it. |
| `PUBLISHED` | **Never.** Retire first; publishing stays one-way.                                                                                                                                                                                                    |
| `RETIRED`   | Only when no message cites it. A refusal names the citing messages (`ISSUE_CITED`).                                                                                                                                                                   |

A soft-deleted message counts as citing — it can be restored. No reason is asked for. The issue's own
audit trail goes with it and the series keeps **one** `DELETED` entry naming who deleted which issue and
what it was. **There is no tombstone status**: a hidden "deleted" state every query must remember to
exclude is the same class of silent trap as a client-side cache.

### Copy

A series can be copied from the server's own template endpoint: it comes back unsaved, blank-id and
DRAFT, with provenance and issue counters cleared and patterns, criteria, report config and descs
carried. Deciding client-side which fields to drop would put a second answer beside the one the save
is validated against.

---

**A tag row published long after its issue is not imported.** The import from the previous system copies a publication's message tag, and a tag can hold a notice published months after the publication went out. Such a row cannot have been in the printed document and would file the notice under the wrong week in every compilation, so the importer leaves out a row published more than fourteen days after the day the issue closed on, in the series' cut-off zone, and names what it left out in its report. The fortnight covers reconstructed cut-offs that fall a few days before the real sitting. The import check applies the same rule, so a fresh estate reads zero and the check only catches what got through. Nothing in the previous system's tables is changed.

## Numbering and naming

Every issue carries `week`, `weekTo`, `year` and `edition` **derived from its cut-off**, whether or not the
publication is numbered by any of them — an annual edition closes in some ISO week too, and that number
names nothing about the publication and must never reach a screen.

**The series decides which number means anything**, and the issue only supplies it. The numbering scheme
wins over the cadence; a scheme of `NONE` on a weekly series means the series does not number its issues,
and reading the cadence anyway would overrule a deliberate choice. A caller that branches on
`week != null` gets it wrong in both directions.

Formatting rules: a double week reads `52+1`, not `52-1` — the pair is one issue covering both weeks, not
a range. An issue of three weeks or more reads as a range of its first and its last week, `36-38`, because
`36+38` would read as weeks 36 and 38; across a year's end it reads `52-2` beside the later year, the
cut-off's week-year, and the earlier year's 52 or 53 weeks are counted (the owner decided on 2026-11-20). A
pattern writes the pair as `${week}+${weekTo}`, and that `+`, and only that one, prints as a hyphen for an
issue of three weeks or more, in the name, the file name and the citation alike (`IssueNaming.expand`, and
the admin app's `issueNumberText` and `expandIssuePattern`; pinned by `IssueNamingTest` and
`issue-number.spec.ts`). A released or imported issue keeps the name and file name it has. A year prints its
edition only when the edition is not the first; `(1)` beside a yearly invites the reader to look for a second
edition that does not exist.

**A week released late is still one week:** a window shorter than 1¾ periods (under 13 whole days) is named
for its cut-off week alone, so a week released up to about five days late (cut on a Wednesday, released the
Monday after next) is not a double week, though the week between the two cut-offs is then named by no issue
unless the editor types the week label. The owner decided on 2026-11-20 to keep this tolerance, and
`IssueNamingTest.aSingleWeekReleasedLateIsNotADoubleWeek` pins it.

**Neither a name nor a period is unique.** `publicId` is the only unique key an issue carries: nothing
refuses a second issue of the same series with the same name over the same period, and the archive holds
such rows. Anything written on the assumption of one issue per period per series — a reconciliation, a
report, a lookup by name — is wrong against it.

### Derived values versus printed labels

`weekLabel` and `yearLabel` are **free-text labels** that carry no number check — a week may read `36+37`
and a year may read `Two thousand`. This holds for **every kind of series**, not only the weekly ones: a
yearly edition's year label is as free as a weekly's week label, and an admin who wants a roman numeral
types one.

**A label is offered only where the series prints it.** The tailoring drawer draws a week box when the
series numbers by week (a weekly cadence, or an `ISO_WEEK_YEAR` scheme), a year box when the series numbers
its issues at all, and an edition box always — every publication carries an edition. The scheme wins over
the cadence here as everywhere: a scheme of `NONE` draws neither a week nor a year box, whatever the cadence. A box for a value the
document never prints would collect text nobody ever sees, so a yearly compilation gets no week box even
though its cut-off derived one. The same rule decides the rest: a label with no box is never marked as
tailored, is not reset by "follow the cut-off", and is never written by a save from that drawer — which
leaves a stale label from an import or an earlier numbering exactly where it stood. A screen whose series
has not arrived yet offers all three, as everywhere else that has nothing to compare against.

Everything that **prints** a number reads the label first and the derived value only where there is none.
Everything that **computes** — ordering, the timeline strip, gap detection, the archive, and a report
template working out a volume from a year — reads the derived numeric values, so nothing anybody types can
move an ordering or a timeline, and no label can be handed to arithmetic that would fail on it. There is
no second-week label: a span written any other way is written into the week, so an issue says its span
once.

### What an edition may say differently from its series

One drawer holds all of it: the printed numbering, the name, the file name, the report and that report's
parameters. **A field left alone follows the series; a field touched is marked and stops following; an
empty box hands it back.** Names and file names are per-language and are judged by expanding the series'
pattern and comparing, because that is the only reading that is true while somebody is typing. A pattern
carrying a token the client cannot resolve is **not expanded at all** — saying nothing about that field
beats marking every issue as tailored.

**The name is tailored, never edited elsewhere.** There is no name box on the issue's edit panel and none
in the publish dialog: a name changed without the document that carries it is a name that disagrees with
the file. The edit panel keeps the edition, the period and the criteria; the publish dialog keeps the
instant and the warnings.

`edition` belongs to **every** publication, and **nothing derives it**: an issue that records no edition
records none, and the server expands `${edition}` from the stored value, so an empty one expands to
nothing. The `1` is a **display default only** — what a form shows where the box is empty. A screen that
quietly supplied that `1` as a value would compute a name the server would never write and then mark the
issue tailored for differing from it. An admin may change the edition on an open issue, and every report
prints it when it is not the first.

### Reserved report parameters

Seven names are **injected from the issue** at render time and are refused as parameter keys, case- and
whitespace-insensitively, on both sides: `week`, `weekTo`, `year` and `edition`, which carry what the
issue **prints**, `yearNumber` and `weekNumber`, which carry what the numbering **derived**, and `weeks`,
the issue's weeks as its name writes them. A row repeating one of them would be a second source for the
same value that can disagree with the first. Issue-level parameters are merged **over** the series' map key
by key; the numbering is injected last.
`weeks` is what the weekly reports print in their heading and page header. It is the week part of the series' name pattern in the report's
language, the stretch from its first week token to its last (`${week}`, `${weekTo}` and their two-digit
variants, with the text between them, ending at the first token of another kind), expanded for the issue by
the code that expands the name, so the heading and the name say the weeks in the same characters: `36` under
`Uge ${week}, ${year}` (for an issue of weeks 36 to 38 too, as its name says), `36 og 37` under
`Uge ${week} og ${weekTo}, ${year}`, `36+37` or `36-38` under `Uge ${week}+${weekTo}, ${year}`. A pattern
that writes no week (or none at all) gives that last form, the pair form, so a document is headed by all its
weeks where its name cannot say them; a week label is printed whole. The accumulated report's sections
carry the same as `group.weeks`, each by its source issue's own series pattern and label.

A series or an issue that stored one of the seven before it was refused is met as follows: a save of the
series is refused (S-23 is a hard rule) until the row is removed, and so is its activation; an issue edit is
refused only where it adds or changes the key, so a row sent back unchanged passes, and the issue drawer
neither shows nor sends a reserved key, so its next save drops it; a release reads no rule and puts the
injected value over the stored one; the series import skips a file row that carries one, and the import from the
previous system drops the key.

The series editor **suggests** the parameter names the chosen report declares and the series does not carry
yet, each as a control that appends an empty row: a hint and never a check, because a template may read a
key the report never declared and a report may declare one its template ignores. Only the report's KEYS are
read: its values are the message print dialog's defaults, written for a different caller.

The two numbers are there for the one thing a template computes from a year rather than prints — the
volume line of an accumulated report — and they are absent where the issue has no such number. A template
that does arithmetic reads the derived number; the label beside it is free text and cannot be subtracted
from.

---

## Owner and availability

Two properties, answering two questions. Being null used to be how the second one got answered.

**Owner** (`domainId`, **required for every kind**, one-offs included) — the one domain a publication is
listed in, administered from, and whose timezone its cut-offs are read in. Null is not a value; an
ordinary save cannot adopt an ownerless row, and the transfer endpoint is the one way to give one an owner.

**Availability** (`OWNER_ONLY | SELECTED_DOMAINS | ALL_DOMAINS` + `availableDomainIds`) — where else it may
be _used and referenced_, read-only, and **never listed in that domain's admin area**.

> Visible from domain X ⟺ `owner == X ∨ availability == ALL_DOMAINS ∨ X ∈ availableDomainIds`.
> Inactive domains in the list are ignored by the predicate and shown greyed in the editor.

| Surface                                                    | Which question it asks                                                                                                                       |
| ---------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| Admin series and one-off lists                             | **owner**. Always the session domain; there is no "show all domains" toggle. Omitting the filter returns the estate — for scripts only.      |
| Admin detail screens (series, one-off, issue)              | **owner**. Another desk's publication is refused in place of the page, naming the desk that owns it; an ownerless one opens from every desk. |
| Series / one-off editor                                    | owner is read-only (session domain on create, stored owner on edit). No domain picker.                                                       |
| Message-editor citation picker                             | session domain, **visible-from**                                                                                                             |
| Messages-list publication filter chips                     | the search domain; resolving a chosen publication stays unscoped                                                                             |
| Deep links, by-id lookups, member lists, filter resolution | **unscoped on the wire** — a citation that exists must always resolve                                                                        |
| Public site                                                | not a domain concern at all. Category-driven and domain-blind.                                                                               |

**Writes** are scoped to the owner: a series that names a domain is that domain's to write, and the
strongest role does not lift it — the question is which desk the caller is sitting at, not how much they
are trusted.

**Reads are never scoped on the wire**: every admin endpoint answers for every domain, a citation that
exists resolves from anywhere, and nothing there may hide a series.

**The admin detail screens are the one surface that refuses what the read answers**, and that is a view
rule rather than a scoping of any read. `ownerVerdict` is the single answer — the loaded publication's
owner against the active desk — and all three detail screens consume it: a series, a one-off or an issue
whose owner is not the active desk renders one refusal panel and nothing else, with no form, no cards, no
actions and no further reads for it. An issue is judged by its series, which is where the accountability
is recorded. The refusal **names the owning desk and offers the one step that changes the answer** —
switching the session domain, after which the same address renders — and a desk the reader holds no role
in is named without the offer, because the switch resolves the desk out of the catalogue the picker uses.
The refusal is **for arrivals** — a link followed from elsewhere, an address typed. Changing desk while a
detail page is open leaves it for the publications front page instead: the reader asked for a desk, not
for this record, and re-mounting one desk's record under another answers them with a refusal they did
nothing to earn. The one switch that keeps the address is the refusal's own offer, which exists to reach
this very page. `seriesWritable` is the same verdict read one notch looser, for what may be OFFERED on
a page that does render: a screen declines an action rather than offering a button that always fails.

An **ownerless** publication is owned by every desk in both readings. There is no desk to switch to in
order to reach it, and the save that would give it an owner lives behind the very screen a refusal would
withhold.

**Defaults**: a query-generated series starts `OWNER_ONLY` — it is a desk's own product. Everything else —
one-offs, links, uploaded files — starts `ALL_DOMAINS`, because those exist to be cited. The owner is never
in `availableDomainIds`, and the list is sent **empty rather than omitted** when the mode changes away from
`SELECTED_DOMAINS`: an omitted collection reads as "unchanged" and would leave a stale share standing.
`SELECTED_DOMAINS` with nothing selected is refused on both sides.

**Transfer** is an audited action with a mandatory reason, allowed to an admin who is admin in **both**
domains. It re-zones future cut-offs only; nothing stamped changes. A transfer that empties a selected list
collapses it to `OWNER_ONLY`.

---

## Public visibility — three independent facts

**The public site serves this model unconditionally**: a published issue is served, and the series' own
status is not consulted. There is no fourth fact deciding which system answers for a series.

Never a single boolean. "Active" folded a status, a period and a category flag into one word, and each is
invisible from the other two. `publicationVisibility` derives **one** answer for every screen, and the
branch order _is_ the model:

1. **A category the public site does not list** comes first — no status and no period can put a publication
   onto a site that never lists its category. `categoryPublish` is **tri-state**: `undefined` means the
   server did not report it and must not be read as `false`.
2. **Withdrawn** — gone from the site whatever its period says, history included; the file is kept for
   citations.
3. **Not released yet** — a draft, possibly with a period already prepared for it.
4. **The period**: scheduled (a start still to come), bounded, since, historic, public. The two ends are
   read a millisecond apart, deliberately: the stored `publicTo` is the **last instant the server still
   lists the issue** — its overlap test is closed at both ends — which is why the chain caps a window at
   the successor's stamped cut-off **minus one millisecond**, an exactly-equal boundary making two issues
   current for one instant. The screens read the named instant as already past.

**An open issue has no public window, only a projection.** The window is what publish stamps, so an OPEN
issue stores none; `expectedPublicFrom` / `expectedPublicTo` are display-only, computed per request, never
stored, and marked as expected. A published issue whose successor is open shows that successor's planned
cut-off as its expected end — what the site _will_ do, rather than what the record currently says.

The public catalogue is ordered by three keys, in this order: **category priority** ascending, the **start
of the public window** (`publicFrom`, not the publication moment) descending, then `publicId` ascending. The
last exists to give the list a **total** order — without it a series and its twin sort non-deterministically
against each other. The sections of the public page are built from that order. The series' **sort order**
is stored and editable, and no list reads it.

---

## One-offs

A one-off is a series of kind `ONE_OFF` holding exactly one issue. It has its own route
(`/admin/publications/one-off/:seriesId`), its own API service and its own screen, and is excluded from
every dashboard group so it is never listed twice.

- **Status is the issue's status, relabelled**: `OPEN → Draft`, `PUBLISHED → Published`,
  `RETIRED → Withdrawn`.
- **The public period has both ends hand-set** and editable on the one-off. A future start is allowed, so a
  one-off can be prepared and go public on a set day; publish never overwrites a prepared window.
- A one-off can carry any content mode, criteria and a report — three of the one-offs in the archive carry
  content mode `NONE`, and nothing stops one being generated from a query. What it cannot have is a
  cadence, a cut-off schedule, a numbering scheme, a first interval or an automatic successor: those are
  questions about the _next_ issue, and there is no next issue.
- A query-backed one-off takes its interval from its single issue and needs no first-issue start.
- The word "active" does not appear on one-off screens: the three facts above produce one derived line.

**The save endpoint's publish flag is a transition, not a field.** It compares what is asked for against
where the publication actually stands and acts on the difference — so a form that sends the whole
publication back must send the value that changes nothing, computed as _"an ACTIVE series holding a
PUBLISHED issue whose period has begun and has not ended"_. Deliberately **not** the derived visibility
line the screens show, which counts a run-out period as published; sending that would turn a rename into a
withdrawal.

---

## Categories

A category is **what a series points at**, shared across all series — not part of any one of them. It lives
in its own admin section beside Publications rather than inside the dashboard.

Load-bearing constraints:

- **`publish` decides whether a whole group of publications reaches the public site**, and it is
  domain-blind. A publication in an unpublished category is reached from citing messages and from nowhere
  else. There is no other flag: a category carries an id, a priority, `publish` and its per-language texts,
  and nothing on it decides whether a message may cite a publication.
- **`priority` is the primary sort key of every publication list, the public catalogue included.** The
  admin form seeds a new category at 100 — the back of a list whose live values run 10, 20, 50 — but the
  **entity default is 0**, so a category created through the API or an interchange import sorts to the very
  **front** of every list until somebody sets a priority. Aligning the two is an open decision.
- **Deleting a category is refused while anything points at it** (`CATEGORY_IN_USE`), counting both the
  current publications and the rows still served by the previous system.
- Name and description are per-language.

---

## Where publications reach the rest of the application

Three surfaces outside this section speak this domain. Their screens are described in
[`../features/messages.md`](../features/messages.md) and [`../features/editor.md`](../features/editor.md);
the rules they must obey are here.

- **Citations.** Whether a series may be cited at all is one setting: `messagePublication ∈ NONE |
INTERNAL | EXTERNAL`. `NONE` — the default — is never offered; `EXTERNAL` writes the reference into the
  message's public publication text and `INTERNAL` into its separate internal one, which is why the value
  cannot be flipped once an issue has published: the existing citations live in the other field. The picker
  offers `PUBLISHED` issues only: a citation names something a reader is meant to go and look up. The editor
  never cites an `OPEN` issue; one is cited through an import, where an imported issue keeps its legacy id and sits at `OPEN` until
  first published, which is why the delete guard checks an open issue for citations too. A retired
  publication is still resolved, because a message citing it must keep rendering what it cited, and is
  never offered for a new citation. The reference text is written _into stored message HTML_ at citation
  time, and no endpoint can clear a citation, so a wrong-language reference is publicly visible and
  removable only by hand. That is why reference formats are per-language and are required in every
  configured language on a series that is citable at all.
- **Filtering by an issue.** The messages list carries a publication filter that round-trips through the
  URL and saved filters; print, export, the public REST API and the SOAP API all reach an issue's messages
  through the same filter string. A `PUBLISHED` or `RETIRED` issue resolves to its **frozen snapshot**; an
  `OPEN` one is re-queried live. An unresolvable id **fails loudly** — it must never widen to "everything".
- **The membership panel in the editor.** A row is one **series**, not one issue: an in-force series
  re-lists a message in every edition, so a notice in force for two years belongs to a hundred-odd issues
  of it, and `issueCount` says how many. `FROZEN` (recorded when the issue was published) and `LIVE`
  (resolved when the panel asked, and still changeable) must never render the same way.

---

## Concurrency

Both series and issues carry a `version`, **filled by every read and compared by every write that sends
it, never assigned from a body**. A save sends the whole object, so a form loaded before somebody else's
edit carries the old value of every field it is not about; the check is what turns that into a refusal
instead of a silent revert.

- It travels in the **body** where there is one, and as **`?version=`** where the body is spoken for (the
  status change, the withdrawals, the file calls).
- Creates, dry runs and previews name no single row and carry none.
- **The domain guard runs first**: a caller sitting at the wrong desk is refused 403 before the version is
  ever compared, so a 409 always means the row moved, never that the caller was not entitled to it.
- An **absent** version is the caller declining the check, so the parameter is omitted entirely rather than
  sent blank.
- A stale write is refused with **`STALE_VERSION`** (409).
- **An issue's version also moves when its child rows change** — a curation forces it — so two curators on
  one open issue collide instead of one silently replacing the other's decision in a list neither re-reads.

Worth sending above everywhere else: the publish request. A release cannot be taken back, and pressing it
against a rail drawn before somebody else curated the member set freezes a different publication from the
one that was reviewed.

---

## Audit trail and archived documents

Every lifecycle event is its own entry with its own action — there is no generic "updated", because a
history line saying "something changed" answers nothing on the screen somebody opened to ask what happened.
The vocabulary is closed server-side and open client-side: an older build renders the bare code rather than
a key path, and a coverage spec walks the declared list against the translation bundles.

- `actorKind` `IMPORT` is its own kind, never a nameless `USER`: an entry carried in with an issue was not
  an action anybody took here.
- An entry may carry an **archive** naming a superseded document per language. **There is no address**: the
  archive sits outside the served repository root, and the pair {entry, language} _is_ the address, resolved
  by the stream endpoint. Retention is indefinite — the documents are not reproducible from data.
- Human-typed reasons are never translated. Backend text on the wire is **codes plus parameters**; the
  client renders them from its own bundles. Server-side translation exists only for rendered documents and
  mail.
- Imported issues carry one honest entry saying they were imported. No overrides and no audit trail are
  fabricated for them.

---

## The report pipeline

Facts that surprise people, most of them server-side:

- **The front matter is a separate per-language script resource** included by the report — masthead, legend,
  the `(P)`/`(T)` explanation, logo, address — resolved by localized lookup. Editing that one script
  resource changes every future issue with no publication, series or report change at all, and the two
  languages may differ structurally, not merely in wording.
- **Every fixed string on a cover comes from the runtime-editable per-language dictionary**, not from the
  publication. A sysadmin can reword or rebrand every future issue from the Dictionaries page.
- **The volume on the cover is computed in the template** from the generation year, so regenerating a
  historical issue prints a different volume than the original. Storing it as a report parameter has no
  effect unless the template changes too.
- **An annex report takes its cover title from its first member message.** An annex is structurally a
  one-message publication, which makes member order load-bearing there.
- **A report property silently wins over the publication's print settings** — the data model is built
  defaults → query string → report properties → print parameters, in that order.
- **`debug=true` on the report endpoint returns the raw HTML instead of a PDF.** It is the pipeline's only
  diagnostic tool, and it answers only a caller the backend knows — signed in, or presenting a ticket issued
  to a signed-in user; an anonymous request gets the PDF, and its `param:` values are ignored.
- A publish refuses without a document: a query-backed series without a report is invalid and
  unpublishable.
- **A preview is fetched; a superseded document is opened.** A preview is an on-demand render pulled into
  the page as bytes with the caller's own bearer — its address answers nothing without one, so following it
  as a link fails. An archived superseded document is _opened in a new tab_ behind a **one-time, single-use
  ticket that lives for a minute**: a top-level navigation carries no bearer, and the archive endpoint
  checks the admin role in code precisely so a ticket satisfies it. The reward is the browser's own viewer,
  and an address nobody can pass on.
- **Nothing asks whether a preview is fresh.** Publishing always renders afresh, so no document can go out
  stale and the release rail carries no freshness row; a preview is a look at the document without
  releasing it, nothing more.

### The report catalogue

A series names a report by `reportId`, picked from the reports the current domain can see. Four rules bite:

- **A report naming no domain is a public report** — offered in every domain's picker. Naming domains
  narrows it to those.
- **A report with no sort order sinks to the bottom** of the picker; the order is sort order ascending,
  the unset ones last.
- **An unknown or blank `reportId` falls back to the built-in `standard` report, silently.** A series
  pointing at a report somebody deleted still prints — with the wrong layout and no error anywhere. This is
  the explanation for "the PDF came out looking wrong and nothing complained".
- **A series names a report that must already exist in the target environment.** Reports carry their own
  export and import, separate from the publications interchange, so moving a series without its report
  lands on the fallback above.

The accumulated annual prints through its own report and template, which lay the year out as its source
weeks one after another: a cover, a table of contents, then a heading per source issue with that issue's
messages beneath it in the order it printed them, and the manually added ones in a section at the end. The
report row is seeded for development; **in production it is created once, by hand, in the Sysadmin report
editor**, exactly as the rule above requires — a series naming a report that does not exist there prints
silently through the fallback.

Report templates and script resources are edited in the Sysadmin area — see
[`../features/sysadmin.md`](../features/sysadmin.md).

---

## Gap detection and the timeline

The issue list endpoint returns an **envelope, not an array**. Rows interleave real issues with
**synthesized** `MISSING` and `UPCOMING` periods, discriminated by `pseudo` being **present** — absent on a
real row. Never narrow on a missing `publicId`.

**Read `gapDetection` before `gapCount`.** A closed gate omits the count entirely, because "nobody looked"
and "looked, found none" must not render the same way — and every imported series is DRAFT, which closes
the gate, so that is the common case. Reasons: `CADENCED_SERIES` (it ran), `NO_CADENCE`,
`SERIES_NOT_ACTIVE`, `SERIES_DORMANT`, `CADENCE_PERIOD_UNKNOWN`.

**A missing period is a calendar question, not a content question.** The gate keys on the calendar alone —
a cadence, an ACTIVE status, not dormant — so every cadenced series gets the forward pass: missing periods
after the newest cut-off, the single upcoming one, and nothing at all while an issue is open.

The time relation decides only how coverage _between_ two releases is read:

- **Tiling series** — the interval relation and a compilation alike — coverage is read off the intervals. A period is MISSING only where no issue's
  interval covers it; on the imported weekly archive that yields **zero** historical gaps, because the
  holiday double-week issues cover both of their weeks. A current period that is overdue shows as _late_ on
  the UPCOMING row, not as a hole in history.
- **In-force series (and a series with no relation)** — two consecutive releases cover the stretch between
  them by definition. A fortnight between cut-offs is a double week, not a hole. A period between them is
  missing only where a release was cut off in it and later retired.

Nothing keys on the cadence _value_, so yearly in-force editions behave the same way: nothing missing
between 2026 and 2027, the 2028 edition upcoming once 2027 is cut off.

Dormancy suppresses everything: a series that has not cut off for N cadence periods reads as dormant rather
than listing missing periods. It is derived, never stored — "we deliberately stopped" is `RETIRED` — and it
changes nothing else. **N is a product threshold that is still open**; the specification carries the
proposal, and nothing else should state a number.

---

## Performance constraints

Two measured facts shape every screen in this section:

- **A request that does almost nothing costs 0.13–0.18 s** on the deployed path (TLS, proxy, JAX-RS, auth).
- **The budget is 500 ms, cold, for everything a screen asks for together.** A screen therefore gets about
  **three calls**, and expensive work must happen once.

So the open-issue screen makes **two** requests — the series, and one workbench call that returns the
issue, its members, its history, its curation decisions, the release checklist and the omissions **from a
single resolution**. Nothing on that screen can answer for a different instant than anything else on it,
and the screen costs one round trip instead of six.

`npm run perf:publications` ([`../../scripts/perf/publications-requests.mjs`](../../scripts/perf/publications-requests.mjs))
drives each screen through a browser, counts its calls, sums their time and exits non-zero when a screen
is over budget. The four app-bootstrap calls belong to the shell and are listed but not counted.

**No client-side caching, and none is wanted.** The property to hold is _"each page reads exactly what it
shows, once per view"_ — not _"hold data so it is not fetched again"_. The rule, its reasoning and the
drawer-mount mechanism that implements it are in [`../architecture.md`](../architecture.md) → State.

The one deliberate exception: a series' archive arrives in one unpaged call (capped at 2,000 rows,
~500 for the busiest series) and is filtered, sorted and paged client-side. The synthesized rows exist only
in that answer, so splitting it would mean re-merging two lists by sort key on the client.

---

## The import, and going live

The import is **one-time and enforced**: it refuses an estate it has already imported rather than skipping,
merging or updating, all three of which quietly reconcile a re-run against an archive somebody may have
edited since. Re-importing means clearing the imported rows first — the intended friction — and the undo
endpoint refuses once any imported series has left DRAFT.

Once deployed, **the public site serves this model and there is no way back through the application.**
There is no per-series switch, no estate-wide switch and no readiness gate; undoing a deployment is a
full database restore, which is a decision taken outside these screens.

**Until go-live, the go-live process documents are binding**: the ruled process, the mechanics underneath
it, the re-run rules and the outstanding task list, all kept outside this repository. Production is
**one window**, not a passive period: import, verify and activate happen together, rehearsed identically
on test from a fresh production restore, with the restore rehearsed and the image frozen after the final
rehearsal.

---

## What the model deliberately does not do

Each of these is a considered decision, not an oversight. Re-proposing one needs a new argument.

| Not done                                                 | Why                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| -------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **No message tag is minted per issue**                   | A tag is a writable, unaudited, colliding side channel into an official record — three publications once pointed at one tag, so filtering by any of them returned the others' contents. Membership is resolved through the issue; rendering uses a temp tag that is discarded inside the publish transaction, and nothing ever reads membership from it. **The consequence, which is the operative half:** most of the public tags in the archive were minted by a publication, and from this model on none are — so printing, exporting, mailing or filtering a **new** issue by a tag name stops working, and a saved filter keyed on a tag name a future issue would have carried silently matches nothing. Every existing tag survives untouched as history, and tags keep all their other uses. |
| **Curation is behind its own permission**                | Curation needs `publication-curate`, held by admins today, so nobody below that tier adds or removes members — losing bulk add/remove of publication membership from the messages list, the same actions from the message editor's action menu, and the one-click last-used-tag shortcut. Measured exercise of that right across nine years was roughly one event; the editor's real lever over membership — _when they publish the notice_ — is untouched. Pointing a publication at a hand-made tag is dropped with it.                                                                                                                                                                                                                                                                            |
| **Mail cannot act on an issue**                          | Print, export, the message list, the public API and the SOAP API all reach an issue's messages through the publication filter, because they consume the same opaque filter string. Mail is the one surface that only ever took a tag, so it still mails a **selection** via a temp tag. Closing it is small and additive; nothing depends on it.                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| **Delete is narrower than before**                       | A hard-deleted published issue takes its `publicId` with it and dangles every stored citation, and there is no join table to find them. Accepted as a real reduction.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| **No advanced-expression criteria**                      | Nothing in the archive needs one, and arbitrary expressions are unverifiable — which is why the filters they would replace were a lookup table in the first place.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| **No automatic release**                                 | Release mode is stored and shown read-only: the manual gate is the only mode, because an unattended release has nobody to acknowledge its warnings and no abort path. The setting exists for when the scheduler and its abort notification do. **A stored automatic mode is displayed and preserved and performs nothing** — a series imported or created through the API carrying it reads as automatic, saves back unchanged, and still waits for somebody to press publish.                                                                                                                                                                                                                                                                                                                       |
| **No server-side paging of a series archive**            | See Performance constraints above.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| **A compilation is not re-judged, re-sorted or chained** | It reproduces what its source issues printed, in the order they printed it, and it may not compile another compilation. Re-running the source criteria at the annual's cut-off, re-sorting the union by today's area tree, or compiling a compilation would each replace a recorded answer with a derived one. See _Compilations_ above; the regime itself is built.                                                                                                                                                                                                                                                                                                                                                                                                                                 |

---

## Keeping the specifications honest

Run all three after any edit to the two specifications.

- [`spec-check.js`](../../scripts/publications/spec-check.js) — checks the two documents against each other
  for the defect classes that have actually bitten: one identifier described two ways, an error code with
  two HTTP statuses, two endpoints sharing a path template, a section reference pointing at nothing. Biased
  towards precision — it will miss things, but what it reports is real, and a clean run is not proof of
  correctness.
- [`gen-rule-ids.js`](../../scripts/publications/gen-rule-ids.js) — exports the invariant rule ids for the
  backend's manifest test, carrying a hash of the slice they came from, so a rule added without
  regenerating is reported where the edit happened rather than at the next backend build.
- [`gen-field-manifest.js`](../../scripts/publications/gen-field-manifest.js) — the same for the entity
  field manifest the backend's reflection test walks.

Both exports write into this repository's backend module, `be/niord/src/test/resources/`. Where the
matching backend branch lives in a second checkout, name it — `--out <file>` on either script, or
`NIORD_REPO=<repository root>` for both and for `spec-check` itself — or the freshness check compares this
specification against an unrelated tree and the write lands in it.
