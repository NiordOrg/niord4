# Testing

Two suites. Unit tests run on Vitest through the Angular unit-test builder and cover pure logic,
services, facades and components. End-to-end tests run on Playwright against a live dev server and a
live backend, and cover journeys that only exist once auth, routing and the API are real.

| Command                                                               | Runs                                                                                                                                                                     |
| --------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `npm test`                                                            | The whole unit suite (`ng test niord-app`)                                                                                                                               |
| `ng test niord-app --include='**/edit-form.spec.ts'`                  | One spec file — still a full application compile                                                                                                                         |
| `ng test niord-app --include='../../../shared-map/portrayal.spec.ts'` | One spec of the map kit (`shared-map/`), named from the project's source root: a `**/` glob starts there and never reaches the kit. The same path works for `niord-info` |
| `ng test niord-app --coverage --coverage-reporters text-summary`      | The unit suite plus a coverage report                                                                                                                                    |
| `npx playwright test` (`npm run e2e`)                                 | The end-to-end suite (dev server, `KEYCLOAK_URL` and credentials required)                                                                                               |
| `npx playwright test ui-apps/admin/e2e/schedule.spec.ts`              | One end-to-end file                                                                                                                                                      |
| `npm run verify`                                                      | `paths:check` → `contents:check` → `format:check` → `lint` → `test --watch=false` → `build` → `urls:check`                                                               |
| `npm run test:info`                                                   | The public site's own unit suite (`ng test niord-info`)                                                                                                                  |

Every `ng` command names its project: an unpinned `ng test` runs both applications' suites one
after the other. The public site's gates are in [its AGENTS.md](../ui-apps/info/AGENTS.md).

## The unit harness

`npm test` is `ng test niord-app`, which is the `@angular/build:unit-test` builder configured on the
`test` target of that project in `angular.json`: runner config `vitest.config.ts`, setup file
[`ui-apps/admin/src/test-setup.ts`](../ui-apps/admin/src/test-setup.ts), and `ui-apps/admin/src/environments/environment.test.ts` excluded from
discovery. **The builder is what supplies jsdom, the application polyfills and TestBed
initialisation** — `vitest.config.ts` itself declares neither an environment nor a setup file.

Three consequences worth knowing before you reach for a shortcut:

- **Raw Vitest only works for pure functions.** `npx vitest run some.spec.ts` skips the builder, so a
  TestBed, component or service spec dies with `ReferenceError: window is not defined`. Scope a run
  with `ng test niord-app --include='<glob>'` instead.
- **A scoped `ng test` is also the typecheck.** It compiles the whole application bundle, so it
  checks templates and TypeScript across the repo, not just the files you touched.
- **`npx tsc -p tsconfig.json --noEmit` is not the application typecheck.** The root `tsconfig.json`
  has `"files": []` and only project references, so it passes on code `ng build` rejects. The honest
  gates are `ng test` and `npm run build`.

Watch mode defaults to on in a TTY and off otherwise — which is why `npm run verify` passes
`--watch=false` and CI does not have to.

### Single fork, and an off-centre clock

`vitest.config.ts` sets two things, both deliberate:

- `fileParallelism: false` — parallel forks hit a worker-startup crash on Windows with current Node.
  The suite is slower and stable. The cost is that the `TestBed` singleton and jsdom's storage are
  **shared across every spec file**, which is what the setup guards below exist for.
- `env: { TZ: 'America/St_Johns' }` — Newfoundland is UTC−3:30 with DST and a westward date boundary,
  so a half-hour offset and a different calendar day separate the runner's clock from every zone a
  spec names. Code that quietly reads the runner's zone fails here instead of passing locally and
  breaking in production. The `env` form is required: Node on Windows does not honour a shell `TZ=`
  prefix. Playwright runs the same `timezoneId`. The rules for writing zone-sensitive code and specs
  live in [coding-standards.md](./coding-standards.md).

### `ui-apps/admin/src/test-setup.ts` must stay

It runs before every test and does three things, each paying for a failure that has actually happened
here:

1. **`getTestBed().resetTestingModule()`** — some seventy spec files call
   `TestBed.inject(HttpTestingController).verify()` in an `afterEach`, which re-instantiates the
   module after teardown; the next file's `configureTestingModule()` then throws "Cannot configure the
   test module when the test module has already been instantiated".
2. **`localStorage.clear()` / `sessionStorage.clear()`** — language-switch specs persist the selected
   language, which leaked a `?lang=da` request into later language-scoped facade specs expecting
   `?lang=en`.
3. **A `Range.prototype.getClientRects` / `getBoundingClientRect` stub** — jsdom implements those on
   `Element` but not on `Range`. The rich-text editor reaches them through ProseMirror whenever a
   selection resolves inside a text node, and the resulting unhandled `TypeError` failed the _run_
   while every test still reported green. The stub returns no rects and a zero box, which ProseMirror
   reads as "unknown" and degrades from.

Delete any of them and the suite flakes in ways that look like component bugs and depend on file
order.

## Writing a unit spec

The application and its specs are zoneless — `zone.js` is not installed. `await fixture.whenStable()`
after setting a signal or an input is what flushes work; never call `fixture.detectChanges()` to flush
async work.

**Test-host state must be signals.** In a zoneless fixture, assigning a plain field triggers no change
detection, so the binding never updates and effect-driven behaviour (an overlay attaching, for
instance) never runs. It reads as a component bug and is not one. Use `open = signal(false)` and
`open.set(true)`.

### Specs over `httpResource`

`fixture.whenStable()` waits for pending resources, so the obvious "create → `whenStable()` → flush"
never completes once a component primes a resource-backed store. Settle the graph by hand first:

```ts
async function settle(): Promise<void> {
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
}
```

Twice, because a component created on the first pass issues its own reads on the second. Resources
resolve on a **microtask**, so a synchronous `flush()` followed by `detectChanges()` renders the
pre-answer state. Use `whenStable()` only after the flush. Roughly fifty spec files follow this shape.

Two related facts: `resource.reload()` is a no-op while the status is `loading`, so an invalidation
issued during the first load does nothing (fold a version counter into the request key when a re-read
must be guaranteed); and a component spec that renders a store-priming child needs that store stubbed,
or http testing providers, or it hangs.

### CDK overlay specs

Dialogs, drawers, confirms and popovers portal into a body-level `.cdk-overlay-container`, so:

- `fixture.autoDetectChanges()` in setup, then `await fixture.whenStable()` after each state change.
- Query the panel with `document.querySelector(...)`, not `fixture.nativeElement`.
- Clean up in `afterEach`, or panels leak across the shared fork:
  `document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());`
  Use `replaceChildren()`, not `innerHTML = ''`. About seventy spec files do this.
- Provide `provideTestTransloco()` and `provideNiordIcons()` — the overlay chrome needs both.
- jsdom has no Web Animations API, so entry animations are optional-chained in the components; never
  assert on them.

[`ui-apps/admin/src/app/shared/components/dialog/dialog.spec.ts`](../ui-apps/admin/src/app/shared/components/dialog/dialog.spec.ts)
is the worked reference.

### `TestBed.overrideComponent` caches the definition

`TestBed.overrideComponent(Cmp, { set: { providers: [...] } })` caches the compiled component
definition, and `resetTestingModule()` does not clear it. Build a fresh mock per test and every later
component stays bound to the **first** test's mock: its signals answer with stale data and its spies
never record. Early tests pass by coincidence and later ones fail on values their own setup clearly
set.

Hoist **one** module-level mock, reset its signals in `setup()`, and use `vi.clearAllMocks()` for the
spies. Component-level `providers: [SomeFacade]` is a common shape here, so this applies broadly.

### Shared test providers

`ui-apps/admin/src/app/shared/testing/` holds the helpers every spec should reach for instead of hand-rolling one.

| Helper                                                                | What it gives you                                                                                                                                                                                                                   |
| --------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `provideTestTransloco()`                                              | Transloco with **two** languages and a stub loader                                                                                                                                                                                  |
| `provideFakeAuthFacade({ editor, admin, sysadmin, isAuthenticated })` | Signal-backed role flags; roles are fixed at construction                                                                                                                                                                           |
| `provideFakeDomainFacade({ canEditSeries })`                          | A fixed answer for series edit rights                                                                                                                                                                                               |
| `provideTestSessionZone(zone)` + `wallClockIn(zone, instant)`         | A session domain carrying only a time zone (pass none for the "no domain yet" state), and a readable wall-clock assertion                                                                                                           |
| `fakeRowLinkRouter()`                                                 | The `createUrlTree` / `serializeUrl` / `events` a row link needs to render an `href`                                                                                                                                                |
| `provideTestLanguages(config?)`                                       | An English-and-Danish language configuration on the `LANGUAGE_CONFIG` token (`TEST_LANGUAGES` by default). The base product's own configuration is English only, so a spec that switches to Danish or formats in `da` provides this |

Two languages, not one, is load-bearing: a language Transloco has not been told about is read as a
_scope_, so switching to it silently turns every lookup into `da.some.key` and every label assertion
fails for an unrelated reason. Translations come from the stub loader, so keys resolve to themselves
unless a spec seeds them with `setTranslation` and loads the language before asserting.

### Stubs a component with a help button forces on its spec

`<app-help-anchor>` injects `HelpUrlService`, which injects `APP_CONFIG` **and** `Router`, and eagerly
reads `router.routerState.snapshot.root` plus `router.events`. A bare `{ navigate, url }` router stub
is not enough — any spec rendering a surface that hosts a help button needs:

```ts
{ provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord' } },
{ provide: Router, useValue: {
    navigate, url: '/editor/edit/m-1', events: of(),
    routerState: { snapshot: { root: { data: {}, firstChild: null } } },
  } },
```

Put the section under `data.help.section` on the route snapshot to exercise the route-driven mode.
The section must be a key of `HELP_URLS` (`core/main-shell/help/help-urls.json`), and the anchor
renders only with `documentationUrl` on the stubbed config (e.g. `'https://docs.example'`, giving
`https://docs.example/editor-manual/manual.html#editor`) — without a base, `urlForSection()` returns
`undefined` and the anchor stays hidden behind its `@if`, so the spec fails for a reason that looks
like the stub above being wrong. `helpUrls` on the stub is deprecated and ignored.

### What gets a spec

"Covered" means the logic-bearing and high-risk code has specs that exercise its real **branches and
error paths** — not that a sibling `.spec.ts` file exists. A spec that renders a component and asserts
it exists buys nothing.

These deliberately carry no spec, and adding one is noise: pure type/model/token/route files, thin
re-export shells, and pure OpenLayers style configuration.

### Coverage

```bash
ng test niord-app --coverage --coverage-reporters text-summary --coverage-reporters json-summary
```

Per-file JSON lands at `coverage/niord-app/` and is ignored by git. **No thresholds are configured**,
so coverage is information, not a gate — nothing fails a build on it.

## The frontend against the backend's endpoint table

The backend keeps a table of every REST handler, with its guard, the parameters it reads and the
answer it gives each test identity (`../be/niord/src/test/resources/endpoints/*.json`; the
backend's side, the endpoint sweep that calls every handler as the table says, is "The endpoint
sweep" in `be/docs/test-infrastructure.md`). Two checks hold the administration app and the public
site to it:

- **The guard**, `scripts/endpoint-roles.test.mjs` (in `npm run test:guards`, about two seconds).
  `scripts/endpoint-roles/extract-calls.mjs` reads every HTTP call from the sources' syntax tree
  (`node scripts/endpoint-roles/extract-calls.mjs` prints them): `http.<verb>`, `httpResource`,
  the ticketed and public downloads, `window.open`, the global `fetch`, with the address followed
  through helpers, fields and injected services. Each call must reach one entry by verb and path (a
  trailing slash is a path of its own), send only parameters the handler reads, and ask a ticket
  role the entry's ticket cells accept (a cell on a named row counts for its role); every
  public-site call, and every download the browser opens without a ticket, reaches its handler as
  anonymous, so its entry must be `PermitAll` and answer anonymous. A site whose address cannot be
  followed goes into `scripts/endpoint-roles/manual-calls.json`, a parameter sent on purpose or
  recorded as a fault into `accepted-unread.json` with its backlog line; an entry of either that
  matches nothing fails, so the lists only shrink.
- **The spec**, `ui-apps/admin/src/app/core/auth/endpoint-roles/endpoint-roles.spec.ts` (in
  `npm test`). It renders the gated components (the main navigation, the messages Action menu, the
  message cog menu, the Tag filter's picker, the User filter's field) once per identity of the
  backend's table with the real `AuthService` and `AuthFacade` over that identity's token claims
  (`fake-auth-service.ts`), and the items each
  identity is shown must equal `role-offers.json` (a difference fails with one sentence per item).
  The guard then holds each identity an item is shown to against every endpoint the item calls (an
  offer the backend refuses that is already recorded is listed under the item's `knownFaults`
  with its backlog line, and fails once the refusal is gone), fails an item whose endpoint
  no call of the frontend reaches any more, holds the claims to the backend's test realm, and
  fails a file that gates on a role (`hasRole`, `isEditor`, `hasDomainUserRole`, `adminIn`,
  `canManageUsers`, `isAuthenticated`, `rolesRequired`, …) unless the spec renders it or
  `role-offers.json`'s `notRendered` says why not.

A new action shown to anyone fails the spec until its item is in `role-offers.json` with the
endpoints it calls; a new call fails the guard until the backend's table has a decided entry for it
(`reviewed: true`; the generator's skeleton of a new handler fails here as it fails the backend's
format test). When the guard fails, each sentence names the call site and the entry:

- **A call that reaches no entry**: the backend has no such handler (fix the address), or its
  table has not been regenerated (the backend's `EndpointTableGenerator`).
- **A parameter the handler does not read**: stop sending it; if it is sent on purpose, or the
  fault is recorded in `docs/backlog.md`, list it in `accepted-unread.json` with
  `"ref": "design: <why>"` or `"backlog: <a phrase of the entry>"`.
- **A ticket role, an anonymous call or an offer the entry refuses**: ask the role the cells
  accept, or gate the item as the backend does; if the backend is wrong, record it, and list the
  item's identity under `knownFaults` with its backlog line until the fix.
- **A stale list entry** (a manual call, an accepted parameter, a known fault that no longer
  shows): remove it.

The spec renders only the components listed above; the other gated files are in
`role-offers.json`'s `notRendered`, so their offers are not checked against the table. The folder `core/auth/endpoint-roles/` stays out of the countries' source zip
(`country-kit/zip-include.json`): it reads the backend's table and names the base product's
actions.

## End-to-end tests

`playwright.config.ts` is deliberately minimal: `testDir: ./ui-apps/admin/e2e`, `fullyParallel`, `baseURL
http://localhost:4200`, `reporter: 'list'`, and the same `timezoneId: 'America/St_Johns'` as the unit
suite. Two absences matter:

- **No `webServer`.** A dev server must already be running, and it talks to a real backend. The
  suite names no environment of its own: `KEYCLOAK_URL` is given by the runner every time (the
  local stack's is `http://localhost:8080/auth`), and a test that needs a login fails at once
  without it, naming it. The specs that expect a URL name `localhost:4200`
  ([backlog.md](backlog.md), _Quality gates and tooling_), so run a dev server on the default
  port: `npm start` serves the base product against the local stack. The npm script stages the
  language files first; a bare `ng serve` ships none.
- **No `locale`.** The suite runs on the bundled browser's en-US default.

The suite and its login fixtures also travel in the base product's source zip. A country's
generated workspace runs its own specs in `fe/e2e/` with `npm run e2e` (`playwright test
--pass-with-no-tests`): its generated `playwright.config.ts` spreads this one with `testDir:
'./e2e'`, and its specs import the fixtures as `@niord/admin/e2e`, which the generated
`.niord/tsconfig.e2e.json` maps to `.niord/base/ui-apps/admin/e2e/auth/fixtures.ts`
([the kit's README](../../country-kit/README.md)). The same variables apply there.

On a fresh clone, install the browsers once with `npx playwright install`.

The fixtures in `ui-apps/admin/e2e/auth/fixtures.ts` carry a guard that fails any test during which
the browser reports a Content-Security-Policy violation, in the page or in any of its frames. The
dev server sends no policy, so the guard bites only on a bundle the backend serves, with the
policy it sends ([platform.md](platform.md), _Hosting_). That run is a deliberate step: build a
non-production bundle (the token stub the authenticated tests use is refused by a production
build) with `npm run build -- --configuration local --output-path dist/<dir>`, which stages the
language files first, start the
backend with `niord.web-app.dir` pointing at `dist/<dir>/browser`, then run
`E2E_CSP=1 E2E_BASE_URL=<the backend> KEYCLOAK_URL=http://localhost:8080/auth npx playwright test csp.spec.ts auth-smoke.spec.ts`
with the local realm's credentials. `csp.spec.ts` checks the policy itself — one header on the HTML
documents, none on the API or the scripts, the tile and Keycloak sources — walks the maps, the
editor, the settings and the mail queue as a sysadmin, and proves that the guard sees a refused
image; `E2E_CSP_LOGIN=1` adds a real login through the Keycloak form. Without `E2E_CSP` it skips.
A full run needs two things in the database the backend serves: a queued mail (else the mail
preview, the one frame that inherits the policy, skips as "no mail in the queue") and a domain in
which the sysadmin can create a message (else the editor case skips as "the session domain offers no
message to create"). A skip is named in the report; a run that is to stand for the policy has none.
On a local stack, start the backend with `./start-local.sh --sample --info` and `NIORD_WEB_APP_DIR` set
to the bundle's absolute path: without `--info` the script sets `adminAppUrl` to its own dev server, and
the backend's root then redirects there. The sample's domains are clients of the local realm, so the
sysadmin can create a message in `niord-nw`.

Against a shared development backend the suite does not write: the **mutating editor workflow**
(edit → save → verify → publish → cancel, and the revision table) would write irreversibly to data
other people are using. The specs that stop at that boundary say so, as
[`ui-apps/admin/e2e/editor-shell.spec.ts`](../ui-apps/admin/e2e/editor-shell.spec.ts) does: it
asserts the routes and guards and leaves the state machine alone. The workflow is covered by the
duty-officer scenarios below, which run only against the sample deployment and only when asked to.

### Duty-officer scenarios

[`ui-apps/admin/e2e/scenarios/`](../ui-apps/admin/e2e/scenarios/) holds whole working days of a
duty officer, through the screens: a warning from a template to its cancellation, a notice to
mariners with parts and attachments, the firing schedule and its exercises, a weekly publication,
and a warning written, saved, verified and published with the keyboard alone (the accessibility
check below).
They write data, so they are opt-in: without `E2E_SCENARIOS=1` every one of them skips by name, and
a plain `npm run e2e` stays as harmless as before. With it, a guard refuses to write unless the run
has one worker and the backend holds the sample deployment in a mode other than production. They
are hand-run, like the rest of the suite, and never in CI. Run them before a release, and after a
change to a journey they take.

- **The stack.** The sample deployment (`./start-local.sh --sample` makes and loads its database
  once), with its backend run apart from `start-local.sh` so a command can restart it, the
  administration app's and the public site's dev servers, the local Keycloak and MailHog. The
  backend runs with `-DmailValidRecipients=ALL`, so its mails reach MailHog. The README of the
  folder has the commands. A whole run takes four to eight minutes.
- **What a step asserts.** After every step that saves, the scenario compares what the screen shows
  with what the admin API holds and, where the step is public, the public API, the public site, the
  PDF and the mail; it states what must be true, never only that nothing failed. A list is compared
  in order.
- **The restart.** Once per scenario the backend is restarted by the runner's command
  (`E2E_SCENARIOS_RESTART`) and everything compared before is read again: faults that show only
  after a restart are real. The command stops the backend and starts it again in the background;
  the suite then waits until it is ready (at `E2E_SCENARIOS_BACKEND_URL` when given, else through
  the dev server). Without the command the step is skipped, and the report says so.
- **What it writes.** Messages, a contact, mailing lists, firing periods, a firing schedule and
  publication series, all in the sample's database. Each file takes a run marker and puts it in
  every name it gives; assertions name the run's own rows, never totals, so a second run on the
  same database passes. What the sample lacks, a scenario makes through the API in its set-up; what
  would act on a later run (a mailing list, the firing schedule and its periods, a series) it
  switches off or clears at the end, and the next run's set-up tidies what a crashed run left on.
  The messages stay as the run's record.
- **Offline.** The pages reach no host but this machine: a tile gets a one-pixel image, every other
  request off the machine is aborted.
- **Known faults.** A product fault is never worked around. The step marks it with
  `knownFault(code, title, check)`: the check asserts the right behaviour, the step passes while the
  fault shows and annotates the report, and the test fails once the fault is gone, so the mark
  leaves with the fix. The code is the record's in `be/docs/potential-errors.md` or the entry's in
  [backlog.md](backlog.md); a fault with no record yet is `pending: <title>` until it is filed. A
  fault that leaves nothing after it to test is a `test.fixme` naming the same record.
- **Adding a step.** A step is a `test.step` inside the scenario's serial `describeScenario`. It
  works through the screen as an officer would; it then reads the same thing through `AdminApi`
  (and `PublicApi` where the step is public) and compares the two with `support/compare.ts`, and
  it asserts the values the step typed, so a fault in the storage shows too. A new name carries the
  run marker. What the step reads, the restart test reads again. Before it is committed, the step is
  shown red once with a fault planted in the answer the browser gets (a `page.route` that changes
  it, never committed), and green without.

The folder's README has the variables, the way to run the sample's backend apart from
`start-local.sh` so it can be restarted, and what each scenario leaves in the database.

### Authentication

The suite never drives the Keycloak login UI. `ui-apps/admin/e2e/auth/keycloak-login.ts` exchanges test credentials
for a token bundle over the password grant, `inject-auth.ts` seeds it onto a window key with
`page.addInitScript()` before Angular bootstraps, and the `KEYCLOAK` provider in `ui-apps/admin/src/app/app.config.ts`
returns a pre-hydrated stub instead of the real SSO client. The factory checks `config.production` at
**runtime** before it even looks for the window key, so a production configuration never reaches the
stub and setting the key by hand buys nothing. It is a guard, not a build-time elimination — the branch
still ships.

Required: `KEYCLOAK_URL` (no default: the suite names no environment), `KEYCLOAK_TEST_USERNAME`
and `KEYCLOAK_TEST_PASSWORD`. Optional: `KEYCLOAK_REALM` (`niord`), `KEYCLOAK_TEST_CLIENT_ID`
(`niord-web`), and role-suffixed pairs (`…_SYSADMIN`, `…_ADMIN`, `…_EDITOR`, `…_USER`). [`ui-apps/admin/e2e/auth/README.md`](../ui-apps/admin/e2e/auth/README.md) carries the full contract and the
bundle flow; never commit credentials.

Server-side prerequisite: the chosen Keycloak client needs **Direct Access Grants** enabled. A 401
from the grant call almost always means that flag is off.

### Fixtures, and which account to use

`ui-apps/admin/e2e/auth/fixtures.ts` exposes `authenticatedPage` (the base pair) plus `sysadminPage`, `adminPage`,
`editorPage` and `userPage`, each falling back to the base pair when its role-suffixed variables are
absent. One bundle is fetched per role per worker and reused.

- **The base pair must be an account holding every role.** Nine spec files drive the two admin areas —
  `/admin` and `/sysadmin` — through `authenticatedPage`, which always uses the base pair, and the
  route guards bounce a plain user home. The `/sysadmin` ones (dictionaries, mails, batch,
  integration, script resources) are the binding constraint: `SYSADMIN_AREA_ROLES` is `['sysadmin']`
  alone, so a base account short of that role fails them while every `/admin` spec still passes.
- **The deny assertions only mean something with genuinely scoped role accounts.** `role-split.spec.ts`
  pins that `/admin` admits `admin` and `sysadmin`, `/sysadmin` admits only `sysadmin`, and the editor
  shell admits a read-only `user`. An authenticated-but-unauthorised visit resolves to the home page
  with an access-denied toast — _not_ to the Keycloak login, which is the anonymous path covered by
  `route-guards.spec.ts`. If an over-privileged `adminPage` account fails the `/sysadmin` deny test,
  that is the account talking, not the code.

### Locators

Most visible strings are backend data, not application text: the runtime dictionary overrides the
compiled bundle key for key, and those rows are editable from a shared backend. Locate by role or by
component, accept either spelling with a regex when the name is what you are testing, and never paste
in whatever string the application renders today. That rule is also stated as a coding standard in
[coding-standards.md](./coding-standards.md), because it constrains what a locator may assume.

Two traps in the role API itself have each cost real debugging time here:

- **Accessible names match as substrings.** `getByRole('button', { name: 'OK' })` also matches
  "Cookies", so a passing assertion can be pointing at the footer, and `{ name: 'Actions' }` matches
  every per-row "Message actions" cog and trips strict mode. Anchor the regex (`{ name: /^ok$/i }`)
  or pass `exact: true`, or scope the locator to the region that holds the one you mean.
- **Roles are exact, and an explicit role replaces the implicit one.** `getByRole('dialog')` does not
  match the confirm, which is an `alertdialog` — which is why a drawer locator stays pinned while a
  confirm is open over it; the template search input is a `combobox`, so `getByRole('textbox')`
  finds nothing. Read the role off the component, not off the element type.

`data-testid` exists on about a dozen templates — the publications series and issue screens, and the
geometry-editor sandbox — for the handful of regions where nothing else is stable (a rendered JSON
payload, the panels of the publication issue detail). Reach for it last.

### The geometry-editor sandbox

`/dev/geo-editor` is registered only when the build is not production and needs no authentication. It
hosts the shared geometry editor standalone, independent of any consuming feature, and
`ui-apps/admin/e2e/geometry-editor-sandbox.spec.ts` drives it — seed a shape, edit it, commit, and read the emitted
GeoJSON back off the page. Keeping that route alive is what makes the editor testable at all.

The spec records what is deliberately _not_ automated: vertex modify, shift-delete, ctrl box-select,
translate and file drop need canvas-pixel precision that is flaky headless, so they are manual, with
the delete logic covered deterministically by the `remove-interaction` unit spec.

## What CI runs, and what it does not

The two workflows and their steps are listed in [platform.md](./platform.md) — one owner per fact.
What belongs here is the omission: **the end-to-end suite is excluded on purpose**, because it needs
a live backend and a Keycloak realm, neither of which exists in CI. Run it locally against a dev server
before shipping anything that touches a journey it covers.

Also unautomated today, and worth knowing before you assume a gate exists:

- No accessibility scanner in CI. The public site has one as a manual tool,
  `npm run a11y:info -- --base <url> --out <dir>` (`scripts/info-a11y-scan.mjs`): axe-core, a dev
  dependency, over every page and dialog of a running site at 1280 and 320 px in each language the
  switcher offers, the WCAG 2.1 A and AA rules and the best-practice ones apart, written to a JSON
  and a Markdown report that hold no page content. It exits 1 while a rule fails (3 when a page
  could not be opened at all), so it can become a gate, but no workflow runs it; the audit record it served is
  [the public site's audit](../../docs/accessibility-audit-public-site.md). The public site's layout
  check below asserts reflow and visible focus around its two bars, and nothing else.
- The administration app's accessibility check is two specs of the end-to-end suite, by hand before
  a release like the rest of it, and only against the sample deployment:
  - [`a11y-routes.spec.ts`](../ui-apps/admin/e2e/a11y-routes.spec.ts) runs axe-core
    ([`a11y/axe.ts`](../ui-apps/admin/e2e/a11y/axe.ts)) over every route of the application, as an
    anonymous reader and as the sysadmin, and over the messages' Actions menu, the Print dialog and the
    header's domain and language menus, against the WCAG 2.1 A and AA rules, light mode and English
    only. It is read-only, runs only with `E2E_A11Y=1` and checks that the backend holds the sample
    first; without the switch it skips by name.
  - The scenario [`scenarios/keyboard-publish.spec.ts`](../ui-apps/admin/e2e/scenarios/keyboard-publish.spec.ts)
    writes, saves, verifies and publishes a warning with the keyboard alone, asserting where the focus
    is and that it shows after every step, and scans each overlay it opens. It runs with the other
    scenarios (`E2E_SCENARIOS=1`, one worker).

  Each scan is held to [`a11y/known-violations.json`](../ui-apps/admin/e2e/a11y/known-violations.json),
  a list of the violations already known, each with the backlog entry it is filed under: a violation
  not on the list fails the test, and so does an entry that no longer shows, so the list shrinks with
  each fix. A fault the keyboard walk meets is a `knownFault` of the scenario rules above. Every
  scan's result is attached to the test as JSON (rule, impact, selectors; no markup). The first pass
  with them is recorded in [accessibility-findings.md](../../docs/accessibility-findings.md#first-pass-administration-app).

  ```bash
  E2E_A11Y=1 E2E_BASE_URL=http://localhost:<admin port> KEYCLOAK_URL=http://localhost:8080/auth \
  KEYCLOAK_TEST_USERNAME=sysadmin KEYCLOAK_TEST_PASSWORD=... npx playwright test a11y-routes.spec.ts
  ```

- No coverage thresholds.

Five repo-level checks sit outside both suites:

- `npm run docs:check` (`node scripts/docs-check.mjs`) — verifies that relative links in the
  documentation resolve and that forward-looking documents stay free of retired vocabulary. See
  [`scripts/`](../scripts/).
- `npm run test:guards` (`node --test "scripts/*.test.mjs"`) — the negative tests of the
  workspace's own guards, each a crafted input the guard must reject: an import into the other
  application in each spelling the lint rules cover, across a layer or into a country folder,
  linted at a real place in the tree; an `angular.json` edit that `paths:check` must name; a build
  grown past `bundle:info`'s tolerance; a bundle baseline whose entry points are not the files the
  public site's landing route loads; a print address the public site composes that its container's
  query maps would refuse, and a crafted query those maps must refuse; a copy of the hash-address
  rules in the public site's teaser frame document that answers an address differently from the
  application's own. Both workflows run it after lint.
- `npm run layout:info` (`node scripts/info-layout-check.mjs`) — opens the last public-site build in
  Chromium with every `/rest` call answered from a fixture, and asserts what only a browser can
  see: every menu control on top at four widths in each of the build's languages, no bar pinned
  at 400 % zoom, focus never landing under a bar, nothing scrolling sideways at 320 px, the focused
  skip link drawn above a test site's corner ribbon, every field name of a message card whole in
  each language (the bundle's longest among them), a 40 px filter chip on a phone, a pressed chip
  told apart in a forced-colours theme, a link in a message drawn as one at rest, on a printed
  page a link's address in its own line and only where a reader can type it, and a message part
  that becomes a named tab stop when a language switch widens its table and stops being one when
  the switch back narrows it. Every page carries the Content-Security-Policy of the image's nginx
  configuration and is served over plain HTTP, as the container serves it: the teaser and the map
  view must ask for their base-map tiles within that policy, and no page may break it. Frontend
  CI's Example country job runs it on the example's build on pull requests; on the base build it
  is run by hand. Text widths follow the machine's fonts: the Linux runner sets `system-ui` in DejaVu Sans,
  which is wider than Segoe UI on Windows, so a word that fits locally can still overflow in CI.
- `node scripts/publications/spec-check.js` — a mechanical consistency checker across
  [`docs/publications/DATA-MODEL.md`](./publications/DATA-MODEL.md) and
  [`API-CONTRACT.md`](./publications/API-CONTRACT.md): the same identifier described two ways, an
  error code with two HTTP statuses, a section reference pointing at nothing. Exits 1 on a blocker.
  Biased to precision — a clean run is not proof of correctness, only that the two documents do not
  contradict each other.
- `npm run perf:publications` — drives the running dev server and fails when a publications screen
  exceeds its request-count and request-time budget. It needs `NIORD_USER` and `NIORD_PASS` in the
  environment or it exits 2 immediately; `APP_URL` (default `http://localhost:4200`), `REPORT_ONLY=1`
  (measure without failing) and `OUT` are optional.

## Framework guidance

For generic Angular testing APIs, the vendored skill's
[testing-fundamentals.md](../.claude/skills/angular-developer/references/testing-fundamentals.md) is
the reference; this document states only what is specific to this repository. The skill's
`references/e2e-testing.md` is setup guidance for a workspace with no end-to-end framework yet and
does not apply here: end-to-end testing is Playwright, configured as described below.
