# Coding standards

Cross-cutting rules that hold everywhere in `ui-apps/admin/src/`, plus the reasoning behind the ones that look
arbitrary. Neighbouring documents own the rest:

| Topic                                             | Document                                                 |
| ------------------------------------------------- | -------------------------------------------------------- |
| Layers, import boundaries, data flow, state       | [architecture.md](./architecture.md)                     |
| Angular idioms, data loading, Signal Forms, i18n  | [angular-conventions.md](./angular-conventions.md)       |
| Types, naming, DTO/model separation               | [typescript-conventions.md](./typescript-conventions.md) |
| Shared components, accessibility, Tailwind layers | [ui-standards.md](./ui-standards.md)                     |
| Test harness, spec idioms, e2e fixtures           | [testing.md](./testing.md)                               |

Generic Angular and TypeScript API questions are answered by the vendored skill in
[`.claude/skills/angular-developer/`](../.claude/skills/angular-developer/SKILL.md) and the
`angular-cli` MCP. What follows is only what is particular to this codebase.

## Principles

- Small units with one responsibility; separated concerns; no hidden side effects.
- Explicit data flow over implicit behaviour — a reader should be able to follow a value from the
  API service to the pixel without guessing.
- Optimise for the developer who arrives in a year, not for the shortest diff today.

## Services and orchestration

- One responsibility per service. Keep data access, mapping and UI logic apart.
- Transport DTOs never leak into UI models, and no component binds a DTO when a model exists.
- Expose UI-consumable state as signals at the UI boundary (the facade pattern); keep RxJS for
  genuine event streams.
- Which loading mechanism a GET uses — `httpResource`, `rxResource` or plain `HttpClient` — is
  decided by [angular-conventions.md](./angular-conventions.md); the error half of that contract is
  below.

## Error handling

| Failure           | What the user sees                                                                                                                    | What the code does                                                                                                                                             |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Read / query      | Inline error state in the region the data would have filled — usually `<app-error-banner>`, with a retry where retrying is meaningful | Error state on the owning facade or resource (an error signal, a `loadStatus`, or the `httpResource` `hasValue()` guard — `value()` throws in the error state) |
| Mutation          | A toast                                                                                                                               | `NotificationService.error(key)` **and** resolve to a typed result (the value, or `undefined`)                                                                 |
| Validation        | Inline field errors                                                                                                                   | Signal Forms field state (`required` / `disabled` schema rules)                                                                                                |
| Anything uncaught | Console                                                                                                                               | `provideBrowserGlobalErrorListeners()`; there is no custom `ErrorHandler`                                                                                      |

Three consequences worth spelling out:

- **Whether a read toasts is decided by the region rule, not by it being a read.** A read that
  owns a region on screen fails **inline and does not toast** — the empty region carries the
  message (`sources-page.html` renders `<app-error-banner>` with a retry for the list read). An
  imperative fetch triggered by an action **toasts**, because nothing on screen represents it:
  opening the editor for one row, loading a comparison, or a re-read of content already on screen
  (a user refresh, or the automatic re-read after a status change — `EditorFacade.reload()`),
  where the failure would otherwise leave stale content looking fresh. The two are not exclusive:
  the editor's initial load does both (`EditorFacade.failLoad()` sets `loadStatus = 'error'` for
  the inline panel _and_ toasts) because the panel says where the failure is and the toast says it
  just happened.
- **A failed mutation never rejects silently.** The caller gets a value back so it can revert an
  optimistic update, keep a drawer open, or leave the row selected.
  `MessageTagActionsService` is the reference shape: `tap` the success toast, `catchError` into the
  error toast plus `of(undefined)`.
- **Authentication failures never reload or log out.** A backend `401` (or a failed token refresh)
  drops the session to the anonymous guest in the auth interceptor and raises the guest banner; the
  API is public, so guest browsing is a supported mode, and a redirect to the identity provider from
  an on-load request loops.

## Dates and time zones

**A time zone comes from the domain's settings. A missing time zone is as wrong as a hardcoded
one — never fall back to the browser's zone silently.** Niord's users are not in the zone the
warnings are issued in, and a stored instant read back through the wrong clock is a wrong date,
not a cosmetic one. Where no session domain exists yet (the bootstrap window, a sysadmin screen
opened without a domain), a date control **disables itself** and a computation **refuses** — it
does not guess. The zone lives on the domain: `DomainService.sessionDomain()?.timeZone`, reached
most conveniently as `LocaleFormatService.sessionTimeZone()`. A publication series reads its
**owner** domain's zone instead, which is deliberately not the session's.

There is no date library. The sanctioned helpers are:

| Where                                                                     | What it owns                                                                                                                                                                                                                                                                                                                                                 |
| ------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `ui-apps/admin/src/app/shared/utils/zoned-date.ts`                        | Arithmetic. `getZonedDateParts` (read a wall clock in a zone), `zonedDateToUtc` / `wallClockToInstant` (build an instant from one), `startOfDayInZone` / `endOfDayInZone` (`.999` ms), `pickedDayToZone` (a picker's calendar day as that day in a zone), `dayInZoneToLocalDate`, `shiftDaysInZone`, `noonOfLocalDay`, and the bare-clock carrier set below. |
| `ui-apps/admin/src/app/core/main-shell/services/locale-format.service.ts` | Rendering. Applies the active locale + the session zone; `formatWith` is the low-level Intl passthrough for structural widget labels only.                                                                                                                                                                                                                   |
| `ui-apps/admin/src/app/core/main-shell/services/date-time-format.ts`      | The one date/time layout config every renderer routes through.                                                                                                                                                                                                                                                                                               |

**The bare `HH:mm` carrier.** A cut-off time or a scheduled send time is a clock with no date, so
no zone can resolve it and it is stored and sent as an `HH:mm` string. Such a value rides on a
browser-local `Date` parked on a fixed carrier day, because a control's value type is `Date`.
`parseWallClockTime` / `formatWallClockTime` convert the string form; `wallClockOf` reads the
clock back off a carrier; `zonedWallClockAsLocalDate(instant, zone)` produces one from a real
instant so a zone-less `HH:mm` control can display it (the inverse of `wallClockToInstant`). Going
through these is what distinguishes "deliberately zone-less" from "forgot the zone".

### Display format

Every rendered date and time goes through one configuration, `DATE_TIME_FORMAT` in
`ui-apps/admin/src/app/core/main-shell/services/date-time-format.ts`, and one service, `LocaleFormatService`
(which defaults the zone to the session domain). Change a layout there, never at a call site. The
policy it encodes is deliberate, so do not "correct" it:

- English is `en-GB` in the language configuration: dates read day-month-year ("4 Jul 2026") and
  weeks start on Monday, because that matches the Danish order ("4. jul. 2026") across both
  languages.
- English times are 12-hour with an uppercase AM/PM marker ("9:35 AM"), an intentional deviation
  from the 24-hour default of `en-GB`. Danish times are 24-hour with the locale's own separator
  ("09.35"). Other locales use their native form.
- A combined date-time is the date, a comma and a space, then the time.
- Modern ICU puts a narrow no-break space before AM/PM; `normalize()` in the format module replaces
  it (and U+00A0) with a plain space so copied and inserted text stays plain. A spec that asserts a
  formatted time must therefore expect a plain space.
- The calendar draws its own month and weekday labels from the same locale instead of relying on
  the browser's native date input, whose popup follows the browser locale rather than the app
  language.

### Picker zone semantics

`<app-date-picker>` and `<app-date-time-field>` are zone-aware:

- `[timeZone]` — the IANA zone the shown and typed wall clock belongs to. Omitted, it defaults to
  the session domain's. Bind it explicitly where the governing zone is **not** the session's (a
  publication series uses its owner domain's).
- `[local]="true"` — opt out entirely and carry the browser's own wall clock unconverted, for a
  consumer that owns the conversion itself or that is really carrying a bare clock.
- **Neither available** (no `[local]`, no zone) — the control renders **disabled** with
  `aria-disabled` and the `common.dateTime.noTimeZone` title. A date-time with a guessed zone is
  not a date-time.
- The value in and out is always an **instant**; `defaultToCurrentTime`, the midnight default and
  the calendar's "today" all read the effective zone, so the two halves never disagree.
- `<app-time-field>` has no zone and deliberately takes none — it is the bare-clock carrier above.
- Where a zone `<select>` must show "nothing chosen" for an empty persisted value, use
  `<app-select [emptyIsNoSelection]="true">` (off by default, since several lists carry a
  deliberate labelled empty option that IS a selection).

### The lint rule

`no-restricted-syntax` in `eslint.config.js` fails the build on four selectors, each naming the
helper to reach for instead:

1. the `Date` mutators (`setHours` … `setFullYear`) — they write in the browser's zone;
2. the local `Date` readers (`getHours` … `getTimezoneOffset`) — they read in the browser's zone;
3. `new Date(y, m, …)` with two or more arguments — it builds a browser-zone instant
   (`new Date(iso)` and `new Date(epoch)` are fine and are not flagged);
4. `new Intl.DateTimeFormat(...)` whose options carry no `timeZone` — it renders in the browser's
   zone. A shorthand `{ timeZone }` satisfies it.

Exempt: the three helper modules above, `shared/forms/date-picker/**`, `shared/forms/time-field/**`,
`shared/testing/zone-testing.ts` and `**/*.spec.ts`. Those files ARE the conversion layer, and
specs construct local dates freely because they assert against an explicit zone instead.

The same rule also carries the ban on a dynamic `import()` into the other application. The
exemption lifts only the four time-zone selectors, so the conversion layer and the specs keep that
ban — but an inline disable of `no-restricted-syntax` lifts both on its line.

**A disable is legitimate only for the bare `HH:mm` carrier class** — and even there, prefer the
carrier helpers, which absorb every such site the codebase currently has. Anything else is a fix.
Spell the reason out:

```ts
// eslint-disable-next-line no-restricted-syntax -- <why no zone can apply here>
```

### Time-zone specs

- **Mock a zone that is not the runner's**, via `provideTestSessionZone(zone)` from
  `shared/testing/zone-testing.ts`; pass no zone to exercise the disabled "no domain yet" state.
- **Assert an exact ISO string or epoch value**, or the `wallClockIn(zone, instant)` reading from
  the same file. A spec that reads `getHours()` back off a result proves only that the runner and
  the code agree, which they always do.
- Pick a zone that discriminates: `Asia/Kolkata` is UTC+5:30 year-round, so even the minutes rule
  out a whole-hour runner; `Asia/Tokyo` is UTC+9 with no DST; `America/Halifax` is UTC-4/-3 on the
  North American rule, half an hour west of the runner; `Europe/Copenhagen` and `America/New_York`
  have stable DST rules for transition cases. Greenland's zone (UTC-2 on the EU rule) is kept only
  where a case needs its spring gap, which ends a local day (23:00 becomes 00:00), and the
  country-word guard allows it in that spec with the reason.
- **The unit suite runs in `America/St_Johns`** (`vitest.config.ts` `test.env.TZ`) — Newfoundland,
  UTC-3:30 with DST, so a half-hour offset and a westward date boundary separate the browser's
  clock from every zone a spec names. Playwright runs the same `timezoneId`. Vitest's `env` sets
  `process.env.TZ` inside the fork, which Node honours on Windows; a shell `TZ=` before the
  command does not.

### Why the enforcement is doubled

The lint rule and the off-centre runner zone look redundant. They are complementary, and the app
needs both because there is deliberately **no global default zone**:

- A global default cannot be attached to anything. Native `Date` has no chokepoint every operation
  passes through, so a "default zone" could only ever be a convention — and a convention is what
  fails silently. It also could not render two zones at once, which this app does: a publication
  series is edited in its owner domain's zone (`series-editor.html` binds `[timeZone]`) while the
  surrounding shell shows the session domain's.
- The **lint rule catches the syntax** on every path, including code no spec ever runs: a
  `setHours` on a stored value, a multi-argument `new Date`, a zone-less `Intl.DateTimeFormat`.
- The **runner zone catches the meaning** on tested paths — the right helper called with the wrong
  argument, which no selector can see. A half-hour offset and a westward date boundary mean the
  browser's clock and any domain's cannot coincide, so a computation that quietly means "the
  browser's clock" fails a spec instead of passing in one domain's zone and breaking in another's.
- Neither catches new code that uses the sanctioned helpers, computes the wrong thing, and ships
  without a spec. That residue is covered by the ordinary discipline: every behaviour change gets a
  spec.

Two traps inside the helper set itself, both of which have bitten:

- **`startOfDayInZone` is for a stored instant, never for a picker value.** It decomposes an instant
  by the zone's calendar, which re-buckets it — off by a day whenever the zone lies west of the
  browser's. A date-only picker emits the browser-local calendar day the user _saw_; there the move
  is `pickedDayToZone` / `wallClockToInstant`, which keep the seen year/month/day and reinterpret
  only the clock.
- **One rule, one function.** All-day bounds are snapped by `forceAllDayBounds` in
  `ui-apps/admin/src/app/features/editor/util/event-date-bounds.util.ts`, and _both_ surfaces that can mark a row
  all-day call it — the editor facade and template execution — so a row created while a template
  runs spans exactly the same instants as the same row edited by hand. `endOfDayInZone` ends at
  `.999` because the bound the user asked for is inclusive.

### The accepted disables

The tree carries exactly one inline `no-restricted-syntax` disable, and it is not an `HH:mm`
carrier: `currentYearIn` in `ui-apps/admin/src/app/features/admin/sections/sysadmin/series/series-rules.ts` reads
`new Date().getFullYear()` when there is no session domain. That is the second sanctioned class —
**a value that is a label or an editable seed, never a stored instant, on a screen where no domain
clock exists**. Everything outside those two classes is a fix, not a disable, and the reason goes on
the line.

Lint is a separate gate from the build: `ng build` runs no ESLint, so these selectors are reached by
`npm run lint` (which `npm run verify` runs before `build`).

Rendering has one performance rule that is also a correctness rule: `date-time-format.ts` caches
`Intl.DateTimeFormat` instances keyed by `locale|options`, and the options carry the `timeZone` — so
a cached formatter can never render another domain's clock. Constructing an `Intl` formatter is the
expensive part and the date pipes are impure (they re-run every change-detection pass), so the cache
matters; keying it on anything less than the full options would be a silent zone bug.

## Server data is never held beyond the view that shows it

The rule, its reasoning and the drawer-mount mechanism are owned by
[architecture.md](./architecture.md) → _State_: no cache, no store, no TTL, no `invalidate()`;
component lifetime is data lifetime. What belongs here is the one construct that looks like a cache
and is not.

Allowed, and not a cache:

- a facade keeping its **own** rows on screen while its **own** re-read of the same thing is in
  flight — `latchWhileSettled(settled, live)` in `ui-apps/admin/src/app/shared/utils/`. The caller composes
  `settled`, and the two shapes it takes have **opposite error contracts**: `!loading` adopts
  whatever the stream then emits, so an error clears the rows (right for a search stream that
  zeroes to `[]` while pending — `schedule.facade.ts`), while
  `!loading && !error && hasValue()` replaces the rows only on a successful fetch, keeping the last
  good ones through both a reload **and** an error. A resource-backed list wants the second: an
  `httpResource` in the error state has no value to read, so adopting it would paint "no rows" for
  what is really "the fetch failed". Needing the inverse of the shared latch means hand-rolling it,
  with the reason written down (`message-details-dialog.ts` retains only across the loading window,
  because its `message()` must resolve to null on error for the not-found surface to appear);
- a router-state seed that the GET replaces.

When a screen fetches too much, fix **where** the fetch lives (a component mounted while invisible →
mount it on demand) or **what** the server returns (one composed endpoint, gated reads). Never fix
it by holding. See [architecture.md](./architecture.md) for how this shapes facades.

## Domain-scoped state

Niord is multi-tenant by domain, and much of what a screen holds is valid only inside the active
session domain: filter slices referencing domain-scoped ids, selection, paged results, per-domain
catalogues and the lookups that resolve ids to labels, in-progress edits, and role-derived access
(roles are per-domain client roles).

Switching the session domain therefore performs a **full browser reload** onto the same address,
query string included, so nothing domain-scoped can survive; the switch is described
in [features/shell-and-auth.md](./features/shell-and-auth.md) → _Session domain_. The one
in-place refresh — after a create, update or delete in the domains admin — and its known limit are
in [features/sysadmin.md](./features/sysadmin.md) → _Domains_.

If the reload is ever replaced by a reactive in-place switch, every feature must then reset exactly
the categories above on the switch, and the domain header (`NiordDomain`) must be re-applied to
in-flight work. Until then, do not write code that depends on surviving a domain switch.

## Comments

A comment explains what the code cannot: the invariant, the reason, the consequence of changing it.

- **Self-contained.** No tracking ids, phase or wave labels, dated notes, "this change", or
  pointers at documents. A future reader has only the repository.
- **Timeless.** Write as if the code was always this way. Not "now that X is gone" — state the
  current contract and its reason.
- **Mark deliberate oddities as deliberate.** Anything that looks like a bug and is not gets
  "deliberately …" or "intentional: …" plus the reason, so the next reader does not "fix" it.
- Narrow exception: artefacts that exist in the world and are the reason the code exists may be
  described as such — `legacyHashRedirectGuard` forwards the `/#/message/{id}` link, "the form
  older mails and tweets carry", which is a production reality, not bookkeeping.

## Dependencies

- **No component library.** The foundation is `@angular/cdk` + `@angular/aria` for behaviour and
  `@ng-icons/lucide` for icons, wrapped by the components in `ui-apps/admin/src/app/shared/` and styled with
  Tailwind tokens and the `@layer components` classes. Do not add PrimeNG, Angular Material, or any
  other component library; extend the shared wrapper instead.
- **One engine per job**, never a second: TipTap for rich text, CodeMirror for code, OpenLayers for
  maps, and no date library at all (see above).
- Use DI-based extension points where variability is genuine — the promulgation registries and the
  tokens that let a shared control reach feature data are the worked examples
  ([architecture.md](./architecture.md)).
- Prefer configuration and composition over hardcoded behaviour; a country deployment must be able
  to vary something without a core change ([country-deployments.md](./country-deployments.md)).

## Code organisation

- Prefer a shared abstraction over duplication, but keep feature code close to its feature boundary.
- Layer and feature boundaries are **lint-enforced**, not conventional —
  [architecture.md](./architecture.md) states them.
- Do not introduce broad refactors that the task does not require.

## Tests

The harness, the spec idioms and the e2e fixtures are in [testing.md](./testing.md). One rule is a
coding standard rather than a testing detail, because it constrains what a locator may assume:

**Never pin text the backend owns.** Most user-visible strings are backend data: the runtime
Dictionary overrides the compiled i18n bundle key-for-key, so a string read out of `en.json` may be
nothing like what the app paints, and those rows are editable by an admin on a shared backend.
Locate by component or role when the name adds nothing
(`page.locator('app-messages-action-menu').getByRole('button')`), or accept either spelling with an
anchored regex when the name genuinely is what you are testing (`{ name: /^new (blank )?nw$/i }`).
Never "fix" a failing spec by pasting in whatever string the app renders today.

The two locator traps that follow from the same rule — accessible names match as substrings, and
roles are exact rather than inherited — are in [testing.md](./testing.md) → _Locators_.

## Formatting and commit gates

- Prettier and ESLint run on **staged files** through husky + lint-staged, so a commit formats what
  it touches and nothing else.
- Format only the files you changed: `npx prettier --write <paths>`. **Never run the repo-wide
  `npm run format`** — it rewrites every file in the tree, churning timestamps and unrelated files
  into the diff.
- `npm run verify` is the administration app's full gate: `paths:check` → `format:check` → `lint` →
  `test` → `build`. Run it before handing work over; the public site's is `npm run verify:info`, and
  what each application's gate adds is in [definition-of-done.md](./definition-of-done.md) →
  _Green gates_.
- `npm run docs:check` validates this documentation set: every relative link must resolve, and a
  `#fragment` into a document the check reads must name one of its headings (by GitHub's slug) or an
  explicit `<a id>`; every path into an application or a shared folder, every `npm run` script and
  every build configuration a document names must exist; an `ng` command written out names its
  project; and the documentation vocabulary is enforced — no rewrite-process wording, no sub-feature
  tracking ids, no links to documents that were removed. In a document the kit package carries, a
  link must also stay inside the kit, and a `.niord/kit/` or `.niord/base/` path must name a file of
  the kit package or the source zip; in one the source zip carries, a link stays inside it and never
  leads into the kit. The kit's guides may name only a country workspace's npm scripts, and the
  agent template is checked for its `.niord/kit/` and `.niord/base/` paths. The publications
  specifications and the go-live runbooks are excluded, because `scripts/publications/` guards those
  instead.

## Copyright

Copyright is stated **once per artefact, never per file**: the repository is covered by the
Apache-2.0 `LICENSE` at its root (the owner is named in its appendix and in `README.md`); the application states it to
users in the footer (`footer.copyright`). Source files carry no licence headers — do not add them.
The owner is the Danish Emergency Management Agency (Beredskabsstyrelsen); that is the name to use
wherever a build, a document or a deployment has to name one.
