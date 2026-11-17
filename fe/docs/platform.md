# Runtime platform

How the application starts, how it is configured, and how it authenticates, scopes itself to a
domain, translates itself, and gets deployed. Code structure and layer rules live in
[architecture.md](./architecture.md); Angular idioms in
[angular-conventions.md](./angular-conventions.md).

---

## Bootstrap

| Step | File                                  | What happens                                                                                                                                                      |
| ---- | ------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1    | `ui-apps/admin/src/index.html`        | A self-contained spinner (`.niord-splash`) inside `<app-root>` paints immediately. Angular renders over it.                                                       |
| 2    | `ui-apps/admin/src/main.ts`           | First import is `core/config/boot-resource-hints.install` — a side-effecting module that appends `<link rel="preconnect">` for the identity provider and the API. |
| 3    | `ui-apps/admin/src/main.ts`           | `enableWillReadFrequentlyHint()` patches `getContext('2d')` before OpenLayers mounts any canvas.                                                                  |
| 4    | `ui-apps/admin/src/app/app.config.ts` | `bootstrapApplication` runs the five app initializers, then the first render replaces the splash.                                                                 |

**The preconnect module must stay the first import in `main.ts`.** ES module bodies evaluate
depth-first in import order, so importing it first puts the TLS handshakes in flight for the whole
bundle-evaluation window. Imported anywhere else it fires a moment before the requests it is meant
to precede and buys nothing.

The `crossorigin` flag on each hint has to match the request that follows, or the browser opens a
second socket and the hint is wasted:

- identity provider — **no** `crossorigin`. Keycloak boots by loading iframe _documents_, which use
  the credentialed connection pool.
- API — `crossorigin="anonymous"`. `HttpClient` sends CORS XHR without credentials.

Each candidate is judged on its own: same-origin and unparseable entries are dropped, so a deployment
that serves the app from the Niord server itself emits no API hint — and no hints at all when the
identity provider is same-origin too.

The splash is deliberately independent of the app's CSS and theme — it has to paint while the
render-blocking initializers run. A branding change must keep it self-contained.

### App initializers

Registered in `app.config.ts` in this order:

| Order | Initializer                                             | Time budget | On failure / timeout                                               |
| ----- | ------------------------------------------------------- | ----------- | ------------------------------------------------------------------ |
| 1     | `AuthService.init()` — silent check-sso                 | 8 s         | Anonymous guest, `authFallback = 'unreachable'`                    |
| 2     | Transloco `load(selectedLanguage())`                    | 3 s         | Transloco loads the bundle lazily on the first translation instead |
| 3     | `webSettingsInitializer` — the runtime settings overlay | 5 s         | Environment values stand                                           |
| 4     | `DomainService.bootstrap(deepLinkDomainId)`             | 5 s         | Empty domain catalogue, no session domain                          |
| 5     | Eager `LastMessagesViewService` construction            | —           | —                                                                  |

**Angular calls every initializer in the same tick, in registration order, and boots when all the
returned promises settle (`Promise.all`).** Registration order therefore decides which request is
_issued_ first, never which one _completes_ first. Three consequences worth knowing:

- No initializer may read another one's result. In particular every bootstrap request goes out
  before an access token exists, so each targets an endpoint that is reachable without one:
  `/settings/web`, `/domains/all`, `assets/i18n/<lang>.json` and the two dictionary endpoints.
- All of them resolve before the first render, so a consumer reading `APP_CONFIG` in a constructor
  sees the merged configuration.
- Every budget above is deliberately at or below the 8 s auth cap, so no other leg can become the
  new long pole and extend the splash. Keep it that way when adding one.

A comment in `app.config.ts` above the first initializer claims the initializers are awaited in
order and that auth therefore completes before the domain bootstrap. That comment is stale; the
concurrent reading above is the correct one, and the code is written so nothing depends on the
ordering either way.

`provideRouter(routes)` relies on Angular's default `paramsInheritanceStrategy: 'always'`; the rule
not to re-pin `'emptyOnly'`, and what is written against that default, is in
[angular-conventions.md](./angular-conventions.md) → _Routing_.

---

## Configuration

### `AppConfig`

`ui-apps/admin/src/app/core/config/app-config.interface.ts` is the whole contract.

| Key                | Meaning                                                                                                                |
| ------------------ | ---------------------------------------------------------------------------------------------------------------------- |
| `apiBaseUrl`       | Backend root. `/rest` (the page's own origin) in the production and local files; absolute in the others.               |
| `appName`          | Tab-title prefix.                                                                                                      |
| `documentationUrl` | Base of the documentation site; prepended to the help paths of `HELP_URLS`. Without it, no help link shows.            |
| `helpUrls`         | Deprecated, ignored: the help paths are code (`HELP_URLS`). Optional, so a country file still setting it compiles.     |
| `keycloak`         | `{ url, realm, clientId }`; a deployment may replace them at boot from `GET /rest/settings/keycloak` (Authentication). |
| `languages`        | Optional override of the country language file. Normally omitted.                                                      |
| `map`              | Base/overlay layer toggles and URLs, WMS protection, default lat/lon/zoom, highlight toggle.                           |
| `mode`             | Deployment label. Anything other than `'production'` shows the header badge, uppercased.                               |
| `production`       | **Build-time** flag. Gates the dev-only routes at module scope and the e2e Keycloak stub in the `KEYCLOAK` factory.    |

Field names deliberately mirror the backend's web-setting key names so the overlay below can match
them; front-end-only keys (`wmsUrl`, `highlightEnabled`) keep their own. The two tile addresses,
`map.osmUrl` and `map.openSeaMapUrl`, are backend web settings of the same names; they reach every
map's tile layers through the map kit's `MAP_TILE_SOURCES` token, which `app.config.ts` provides as
getters over `APP_CONFIG.map`, so a map reads the overlaid value
([architecture.md](./architecture.md) → _The map layer_).
`mode` and `production` answer different questions: `mode` is a runtime label, `production` is what
the bundle was built as, and the badge prefers `mode`.

### The environment files

The five files in `ui-apps/admin/src/environments/` are the base product's and name no deployment:
no host of a country, no WMS, a world map.

| File                         | Backend / identity provider                                                                 | `production` | Ships as                                               |
| ---------------------------- | ------------------------------------------------------------------------------------------- | ------------ | ------------------------------------------------------ |
| `environment.ts`             | the local stack: the `be/` backend through the dev server's `/rest` proxy, Keycloak on 8080 | `false`      | base file; the target of every `fileReplacements` swap |
| `environment.development.ts` | the local stack, as `environment.ts`                                                        | `false`      | `ng serve niord-app` (`npm start`)                     |
| `environment.local.ts`       | the local stack, as `environment.ts`                                                        | `false`      | the `local` build (`start:local`)                      |
| `environment.test.ts`        | the deployment's own origin: `/rest` and Keycloak at `/auth`                                | `false`      | the `test` build                                       |
| `environment.production.ts`  | the deployment's own origin: `/rest` and Keycloak at `/auth`                                | `true`       | `npm run build`; the generic image                     |

The local stack's Keycloak is addressed as `http://localhost:8080/auth`, not through the proxy, because
the docker-compose realm's issuer is fixed at that address and the backend's local profile checks
tokens against it.

A country's own environment files — its test backend and Keycloak, its WMS, its map centre — are
seams in its repository: `admin/environments/environment.development.ts` (and `.local`, `.test`,
`.production`), each replacing `environment.ts` in the configuration of that name
([country-kit/README.md](../../country-kit/README.md) → _The seams_). The kit's set-up swaps in the
ones that exist and holds them to the country API like every other country file; a configuration
the country has no file for takes the base product's.

Rules:

- An edit to `environment.ts` must be mirrored into `.development.ts`, `.local.ts` **and** `.test.ts`.
  `environment.production.ts` names no host at all: its API (`/rest`) and Keycloak (`/auth`) are
  origin-relative, because a deployment serves the app, the API and Keycloak from one origin. One
  production build per country therefore serves every environment of that country. keycloak-js
  resolves a relative `url` against the page.
- The production file is deliberately thin: no help anchors (they are code, `HELP_URLS`, see
  _Help anchors_), a single `map` leaf and no `mode`. Any key an environment file omits that the
  overlay should still be able to supply needs an `OPTIONAL_LEAVES` entry — which is exactly why
  that list names `mode`, `documentationUrl` and the `map.*` leaves. See below.
- The `production` configuration is the shipped build: `publish.yml` builds it (`npm run build`)
  into the bundle the generic image serves, and Frontend CI builds the same. The `test` build is the
  one that sets `production` to `false`, so never gate runtime behaviour on
  `environment.production` expecting every deployed build to match.

### `APP_CONFIG` is a runtime clone

`APP_CONFIG` is provided as `createRuntimeConfig(environment)` — a `structuredClone` — so the
settings overlay mutates a runtime copy and never the imported module object. **Inject `APP_CONFIG`;
never import `environment` directly.** Three module-scope readers are the sanctioned exceptions,
because they run before any injector exists: the dev-route gate in `app.routes.ts`, `LANGUAGES` in
`core/main-shell/config/config.token.ts`, and the preconnect install module.

### The web-settings overlay

At bootstrap the app GETs the anonymous flat map at `{apiBaseUrl}/settings/web` and overlays it onto
the `APP_CONFIG` clone **by leaf key name**: any config leaf whose name equals a payload key takes
the backend value. The database is the source of truth; the environment file is the fallback. A
setting added to the database later starts working against a same-named config leaf with no code
change.

Guardrails in `core/config/web-settings.ts`:

- **Excluded roots, never overridable:** `apiBaseUrl` and `keycloak` (bootstrap-circular — the
  overlay is itself fetched over `apiBaseUrl`), `production` (gates module-scope code no runtime
  value can reach), `languages` (feeds Transloco at module load), and the deprecated, ignored
  `helpUrls` a country's environment file may still carry (its leaf names are help section ids that
  could collide with unrelated setting keys — help is tuned through `documentationUrl` instead).
- **Optional leaves:** the merge walks the config object's own leaves, so a leaf a thin environment
  file omits entirely is invisible to it. Those are listed in `OPTIONAL_LEAVES` so the database can
  still supply them. One of them comes from the backend rather than the database: `s124Enabled`,
  which `/settings/web` computes from the running `niord.s124.enabled` and no environment file sets
  (absent means unknown and is read as on). The map's other computed value, `secomEnabled`, matches
  no leaf; the admin section gate reads it instead.
- **Scalars only, coerced to the leaf's type.** The backend emits some booleans as the strings
  `"true"` / `"false"` and consumers test `=== true`. A value that cannot represent the leaf's type
  is ignored and the environment value stands.
- **A blank tile address is no address.** An empty `osmUrl` or `openSeaMapUrl` is not applied, so
  clearing one in the Settings page returns the maps to the environment's address or, without one,
  to the map kit's default — never to a layer with an empty template and no tiles.
- **Unknown payload keys are dropped silently** — there is no log. A setting that matches no leaf
  simply does nothing.
- **Read once per page load, never polled.** An edit in the sysadmin Settings page applies on the
  next load.
- **Never blocks boot:** 5 s cap, every error swallowed, and an empty completion handled explicitly,
  so the app runs on pure environment config with no backend at all.

#### What the overlay cannot reach

These backend web settings match no config leaf and therefore have no effect on the front end today.
Treat the list as the known gap, not as a bug list:

| Setting                                                  | Front-end behaviour today                                                                                                                                                             |
| -------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `mapImageSize`                                           | Thumbnail capture is fixed at `THUMBNAIL_SIZE = 256` (`features/editor/util/map-thumbnail.util.ts`).                                                                                  |
| `stdTemplateFields`                                      | The category editor ships its own `STD_TEMPLATE_FIELDS` list.                                                                                                                         |
| `editorFieldsBase` / `editorFieldsNw` / `editorFieldsNm` | The effective field map is computed server-side and arrives on the message; the admin pick-lists use a hard-coded candidate list (`features/admin/utils/editor-field-candidates.ts`). |
| `wmsProvider`                                            | No consumer.                                                                                                                                                                          |

The language settings take a path of their own rather than the overlay. `siteLanguages`,
`modelLanguages` and `defaultLanguage` are resolved from the same payload at boot by
`LanguageSettingsService.configure()` (`core/i18n/language-settings.service.ts`), with the country's
`language.config.ts` as the fallback when the call fails or a value is missing. A Settings edit
therefore changes the language menu on the next page load, without a rebuild; a UI language is
offered only when a bundle ships for it. The rules are in
[country-deployments.md](./country-deployments.md) → _Languages_. The public site has its own
`infoSiteLanguages` / `infoDefaultLanguage`, which this application ignores.

The admin section gates read the same map a second time, outside the overlay: when an area with a
gated section mounts, `SectionGatesService` fetches `{apiBaseUrl}/settings/web` once and resolves each
section's `settingGate` key from it, so an edit of a gate setting shows the next time the area
opens. See [architecture.md](./architecture.md) → _Section gates are visibility, not protection_.

---

## Authentication

The Keycloak client is built once, by the `KEYCLOAK` injection token's factory, from
`config.keycloak`. Those three values are the environment file's, unless the deployment replaced them:
before bootstrap, `main.ts` fetches `GET {apiBaseUrl}/settings/keycloak` (the backend derives it from
the token issuer it validates) and `createRuntimeConfig` applies every non-empty value from the
answer (`web-settings.ts`, `setRuntimeKeycloakConfig`). This is the one path into `keycloak`: the
settings overlay never touches it (`EXCLUDED_ROOTS`), because the client is built before the overlay
runs. **Nothing constructs `Keycloak` itself** — that token is also how specs and the e2e fixture
substitute a stub. When the
bundle is not a production build and an e2e fixture has seeded a token bundle on `window`, the
factory returns a pre-authenticated stub instead of a real client; the `production` guard makes that
path unreachable in a production build. The guard is only as good as the environment's flag: the base
product's production environment sets `production: true`, and a country's set-up refuses a production
environment that does not (`country-kit/lib/environment-check.mjs`), since that bundle would otherwise
let the stub replace Keycloak and compile in the dev routes.

`AuthService.init()` runs `keycloak.init({ onLoad: 'check-sso', silentCheckSsoRedirectUri:
origin + '/silent-check-sso.html', checkLoginIframe: false })` raced against an 8 s timeout — the
third-party-cookie probe alone can block for ten seconds and the silent-check iframe has no built-in
timeout of its own.

On failure or timeout the app drops to an anonymous guest and carries on, and a late-settling
`init()` is left unawaited so it cannot clobber the guest session. **It never answers an auth
failure with a reload or a logout** — the rule, why both loop, and the guest banner are in
[features/shell-and-auth.md](./features/shell-and-auth.md) → _Degraded authentication_.

`ui-apps/admin/public/silent-check-sso.html` loads `ui-apps/admin/public/silent-check-sso.js`, whose single line posts the
redirect URL back to the opener. It is an external file, not an inline script, **so the
Content-Security-Policy can stay at `script-src 'self'`**. Inlining it would mean loosening the CSP.

### Session snapshot

`captureSession()` stores an immutable snapshot, not the live token: narrowed identity claims,
`realmRoles: Set<string>` from `realm_access`, and `resourceRoles: Map<resource, Set<role>>` from
`resource_access`. Per-domain authorization is encoded as Keycloak **client** roles where the client
id _is_ the domain id. Role checks read `resourceRoles` only: the backend grants nothing from realm
roles, so neither does the app.

Because checks read the snapshot, it is re-captured after every successful token refresh — otherwise
a role change granted mid-session stays invisible until a full reload.

### Token refresh and 401

`authTokenInterceptor` short-circuits any non-Niord URL and any anonymous session, then awaits
`ensureFreshToken()` (`updateToken` with a 60 s leeway) before attaching `Authorization: Bearer`.

A failed refresh, or a 401 from the backend, calls `sessionExpired()`: the session is cleared
**locally** and `authFallback` becomes `'expired'` — never a logout, per the rule above. Signals
coalesce equal values, so a burst of 401s settling here is harmless.

### Interceptor scoping

Both interceptors gate on `isNiordBackendRequest(url)`, which matches the URL against `apiBaseUrl`
and `map.wmsUrl` through `urlMatchesPrefix`. A raw `startsWith` would be suffix-confusable — a
bare-origin prefix would also match `https://api.example.invalid.evil.com/…` and leak the bearer
token — so the character after the prefix must be `/`, `?` or `#`, unless the prefix already ends in
`/`. Anything else is third-party and passes through untouched.

The WMS prefix counts only while it is on the same origin as `apiBaseUrl`. The runtime web-settings
overlay excludes `apiBaseUrl` but not `map.wmsUrl`, so a setting could move the WMS prefix; on any
other origin its tiles are requested without the token or the domain header, and a protected WMS
there fails to load rather than receive every signed-in user's token.

The interceptors only attach headers; they never rewrite URLs. Services build absolute backend URLs
themselves from `apiBaseUrl`, which is why the relative order of the two is immaterial beyond having
the token on the request when the domain header is added.

### One-time tickets for downloads

Downloads that leave the app (PDF renders, ZIP/JSON exports, anything opened in a new tab) cannot
carry a bearer header, so they go through a short-lived single-use ticket: `AuthTicketService`
fetches one per download (never cached) and `TicketedDownloadService` is the one home for the
pattern, with `openPublic(url)` as the explicit no-ticket mode. The ticket semantics — what `?role=`
does and what its absence means — and the root-relative file addresses that must go through
`ApiUrlResolver` are in [backend-api-notes.md](./backend-api-notes.md) → _One-time tickets_.

---

## Roles

The four role lists live in `core/auth/area-roles.ts` and nowhere else, and the route guard and the
nav menu both read them, so a screen's protection and its visibility share one source. There is **no
implicit hierarchy** — every accepted role is spelled out in every list that admits it. Who may enter
which area, the helper semantics on `AuthFacade`, the guard's two denial paths, and the places where
a URL reaches a screen the nav does not offer are in
[features/shell-and-auth.md](./features/shell-and-auth.md).

---

## Domain

A domain is the desk the user sits at: it scopes results, permissions and defaults on the backend,
and supplies the clock, the map defaults and the message-series set on the client.

**The initial session domain** is chosen by `DomainService.bootstrap` — a `?domain=` deep link, then
the persisted id, then the first catalogue entry; the order and how it degrades are in
[features/shell-and-auth.md](./features/shell-and-auth.md) → _Session domain_. The deep-link id is
parsed out of `Location.path()` through the router before any navigation happens.

**Every request to the backend carries `NiordDomain: <sessionDomainId>`** when a session domain
exists — including anonymous requests. This is the multi-tenancy channel.

**Switching the domain reloads the browser**
(`window.location.href = window.location.pathname + window.location.search`), deliberately, so every domain-scoped slice starts clean. It lands on the address the reader is on unless
the deepest activated route declares another with `data: { onDeskChange: '<address>' }` — how a page
addressed inside one desk sends a desk change somewhere that still means something. The rule, that
contract, and the one-shot
`sessionStorage` mark a screen can read afterwards, are in
[features/shell-and-auth.md](./features/shell-and-auth.md) → _Session domain_. That mark is a plain
boolean, not a signal: it describes how the load began and never changes.

Features never read `Domain` objects directly; they read `DomainFacade` computeds — `capabilities`
(`atons`, `templates`, `publish`, `firingSchedule`), `searchDomainEffective`, `editableDomains`,
`mapDefaults`, `domainMessageSeriesIds`, `defaultSortBy` / `defaultSortOrder`, and the memoized
`supportsMainType` / `supportsType` / `supportsNw` / `supportsNm` / `canEditMessageSeries` factories
(the `supportsNw` / `supportsNm` pair takes an `exclusively` flag). Note the asymmetry: support
questions read the **search** domain, edit questions read the **session** domain. Two of them carry
rules worth knowing:

- `searchPublicMessages` is true when the viewer lacks the `user` client role in the session domain
  (`AuthFacade.hasDomainUserRole`: on the session domain's client or on the base client; signed in
  alone is not enough) **or** the search domain differs from the session domain. It is what
  decides whether a search asks the backend for public messages only.
- `defaultSortBy` / `defaultSortOrder` are parsed out of the domain's single `messageSortOrder`
  string (`parseMessageSortOrder` in `core/domain/models/domain.model.ts`), which defaults to
  `AREA` / `ASC` when absent or unrecognized.

**The domain owns the clock.** Every rendered date and time uses `Domain.timeZone`, reached through
`LocaleFormatService.sessionTimeZone()`. There is no browser-zone fallback and no date library. The
full rule, including the lint rule that enforces it, is in
[coding-standards.md](./coding-standards.md).

Domain names are translated server-side, so `DomainService` re-fetches the catalogue on a language
change — inside `untracked()`, for the reason given in
[angular-conventions.md](./angular-conventions.md) → _Effects_.

---

## Internationalization

**The language list, and its order, is deployment configuration.** `LANGUAGES` is
`COUNTRY_LANGUAGES`, from the build's `language.config.ts` — `country-specific/default/` in the base
product, generated from a country's manifest by the kit's set-up; no environment file overrides it.
The base product's build is English only, and a country's build names its own list and default.
`availableLanguages` names every language the build can show as
content, each entry with `label`, `value`, `locale` (which drives all `Intl` formatting) and an
optional `flagPath` — a language with none renders as its code, never as an invented flag path.
`uiLanguages` names exactly the UI bundles the build ships (`en` only when named; every bundle is
staged complete, its missing keys filled from `en`), and `brandingLanguages` the languages with a
branding file (none in the base product, which then requests none). Transloco's `availableLangs` is
`availableLanguages` with `uiLanguages` — the configuration's languages alone — and `defaultLang` the
configuration's default; there is no fallback language. **The list order is canonical**
— every per-language field group, preview row and padding pass renders in exactly that order, so
"current language first" is deliberately not a thing, and everything that iterates languages must
scale to N. See [country-deployments.md](./country-deployments.md).

### The loader merges three layers

`TranslocoHttpLoader.getTranslation(lang)` forkJoins one compiled bundle, `assets/i18n/<lang>.json`
(for a content-only language, the build's default UI bundle), the branding file
`assets/branding/<lang>.json` when the language is one of `brandingLanguages`, and the overrides an
administrator has entered in the sysadmin Dictionary, and merges them **before** Transloco stores the
bundle, so there is never a base-then-override flash. The layers, each over the one before: the
bundle ← branding ← the Dictionary. No English bundle lies underneath: every bundle the build ships
is staged complete from `en` ([fe-country-split-plan.md](../../docs/fe-country-split-plan.md) §3.2
step 4), and a key still missing renders as the key. A bundle that does not arrive is asked for again
twice (after 1 s, then 2 s); the load then completes with branding and the overrides alone, and the
failure is logged with `console.error`. Transloco keeps that load for the session, so the loader asks
for the bundle again in the background — after 30 s, then up to three more times, each pause twice
the one before — and, when it arrives, fetches the two layers above it again and replaces the
translation on screen. The branding file is best-effort: a 404 or a timeout leaves it out. `BrandingService` records which keys the branding
set, so the footer shows only what the country branded — never a Dictionary row that happens to
carry a footer key.

- Only the **`web` and `message`** dictionaries reach the UI. They are fetched from the anonymous
  `dictionaries/dictionary/{name}/entries` endpoints, so they resolve before login and before a
  domain exists.
- Only a **non-empty** value in the requested language overrides; a blank must never blank out a
  compiled label.
- **An unchanged system-added row does not override.** The backend marks a translation `asAdded`
  while its value still equals what its start-up added or adopted (code/data/configuration plan
  §4.5). Such a row carries the backend's wording, not an administrator's, so
  `DictionaryOverridesService` drops the translation it would have used and the compiled text
  shows. There is no fall-through to another language: an edited English row never replaces the
  app's own Danish. A missing flag counts as false, so against an older backend every non-empty row
  overrides as before; a row an administrator edited loses the flag and wins.
- The override fetch is capped at 5 s and every failure degrades to the compiled bundle alone. i18n
  can never break or hang the app.

### The interpolation-token guard

`mergeDictionaryOverrides` **refuses any override whose `{{tokens}}` are not exactly those of the
compiled value**, and keeps the compiled value instead. Dictionary entries predate this front end and
many use its keys with other parameters. An entry reading `Published` over a compiled
`Published {{date}}` would leave the app's `translate(key, params)` call nowhere to put the value, and
the date would silently vanish; an entry reading `Remove from "{{tag}}"` over the compiled submenu
heading `Remove from…` — the old application named one tag with that key — asks for a value the app
never passes, and rendered `Remove from ""`. An override may re-word freely; it cannot drop a
placeholder or add one. A key the compiled bundle does not carry is taken as it is. Refused keys are
console-warned in dev mode.

### Key conventions

- The compiled bundles mix nested groups with **flat, dot-containing top-level keys**, because the
  Dictionary keyspace is flat. Override resolution tries the flat key first, then walks the nested
  path. A key that an administrator must be able to override has to match the Dictionary key exactly.
- There are **no Transloco lazy scopes** anywhere — one bundle per language.
- The pack check (`npm run languages:check`) holds every pack to `en.json`. In this tree that is
  `en` alone; in a country's build it is that country's packs, and a key a pack lacks fails there.
  A key `en.json` no longer has is stripped from the staged bundle. The staging step fills a
  missing key from English (there is no runtime fallback language), so a missing key renders its
  English text rather than its name.
- A text that names an authority — the footer's link and copyright, its logo, the disclaimer and the
  cookie statement — is never a key of the bundles: it is the country's branding file and legal
  documents, in its repository (`admin/assets/branding/`, `admin/assets/legal/`, and the same under
  `info/assets/`; [country-kit/README.md](../../country-kit/README.md) → _Branding_).
- **No language but English is written here.** A country translates in its own pack, where its
  product owner accepts each value — the rule is in
  [angular-conventions.md](./angular-conventions.md) → _Internationalization_.

### Switching and refreshing

`LanguageService` applies the persisted `localStorage['niord.language']` to Transloco **before**
subscribing to `langChanges$` (a BehaviorSubject that emits synchronously — subscribing first would
lock the signal to the bootstrap default). `selectLanguage()` **loads the bundle before flipping the
active language**, then persists; a failed load leaves the language unchanged and is logged, never
swallowed. An effect keeps `<html lang>` in sync.

Route `title` values are translation keys, not literal titles: `I18nTitleStrategy` (registered as the
router's `TitleStrategy` in `app.config.ts`) composes them and re-applies the last key list on every
translation load, so a cold load, a language switch and a dictionary refresh all rewrite the tab
title. See [features/shell-and-auth.md](./features/shell-and-auth.md) → _Page titles_.

`TranslationRefreshService.refreshActiveLanguage()` re-runs the loader and **replaces** the live
table (`setTranslation(…, { merge: false })`). The full replace is deliberate: a merge can only add
or overwrite, so a _deleted_ override would never revert to the compiled value. The sysadmin
Dictionary page calls it so an edit shows without a reload.

---

## Help anchors

`HELP_URLS`, read from `core/main-shell/help/help-urls.json`, maps section ids onto anchors in the
documentation manuals. It is code, not configuration: the same in every build, production and every
country included, and the contract the guides' anchor check reads. `documentationUrl` is the
configurable half — the environment file's value, which the backend's web setting overrides at
runtime — and is how one deployment points at its own copy of the manuals while the anchor ids stay
fixed. An id added for a new screen goes into `help-urls.json`; `npm run test:guards` fails an id the
code uses that the map lacks, and a map key no code uses. `AppConfig.helpUrls`, where the map used to
live, is deprecated and ignored.

How a section id resolves to a URL, which manual each key points at, and which anchors are stale is
the contract in [docs-niord-org-map.md](./docs-niord-org-map.md); where the help buttons sit in the
shell is in [features/shell-and-auth.md](./features/shell-and-auth.md).

---

## Service worker and update detection

`provideServiceWorker('ngsw-worker.js')` is **last** in the providers array, with
`registrationStrategy: 'registerWhenStable:30000'` and `updateViaCache: 'none'`. It is enabled only
when `!isDevMode()` **and** the hostname is not `localhost` / `127.0.0.1` / `[::1]`.

Both halves of that gate are load-bearing:

- `isDevMode()` alone is not enough — the `test` configuration builds optimized, so
  `ng serve niord-app -c test` would register a real worker against `http://localhost:4200`, cache
  a dev shell, and outlive the session.
- **Never gate on `environment.production`** — it says which environment file was built, not where
  the app runs: the shipped `production` build sets it `true`, while the optimized `test` build and
  the local ones set it `false`. The worker's gate asks where the app runs.

`ui-apps/admin/ngsw-config.json` invariants:

- **No `dataGroups`, ever.** API, identity provider, WMS and tile traffic must always hit the network.
- `navigationRequestStrategy: "freshness"`, with `/rest/**`, `/auth/**` and `/wms/**` excluded from
  `navigationUrls`.
- Asset groups cover only hashed build output, the `media/` files the styles reference (a
  country's vendored face, cached at its first load so it shows offline), `assets/i18n/*.json` with
  the branding and legal files, and images. The two silent-check-sso files are in the app-shell
  group. `scripts/admin-service-worker.test.mjs` (in `npm run test:guards`) runs Angular's generator
  over the config and holds these invariants.

`BuildVersionService` detects the skew that matters for a separately-deployed single-page app: the
deployed `index.html` referencing a **different hashed `main-*.js`** than the one the running document
loaded. Comparing a baked-in web stamp against the backend's would be meaningless, since the two
deploy independently; the backend's stamp is surfaced separately as a detail line.

Two things the probe must keep: it resolves `index.html` against `document.baseURI` (a deep link must
still reach the app root), and it sends **both** `Cache-Control: no-cache` and the `ngsw-bypass`
**header**. `Cache-Control` governs the HTTP cache but not Cache Storage, so a controlling worker
would otherwise answer from the cached shell and the check would always report "up to date". The
header form, not the query form — the query form changes the request URL.

The probe runs at start-up and again whenever the tab comes back into view, because a tab left open
across a deploy is the case it exists for and nothing else re-reads `index.html` while the tab sits in
the background. Two more signals feed the same flag:

- **A lazily-loaded route that can no longer be fetched.** The deploy removed the old build's chunks,
  so the first click into a view the tab has not opened yet fails with the browser's
  dynamic-import error. `provideRouter` carries `withNavigationErrorHandler`, which recognises that
  error and reports it; the navigation stays where it was.
- **The service worker.** `SwUpdate.versionUpdates` reporting `VERSION_READY`, and
  `SwUpdate.unrecoverable` — a version whose files are gone from the server. With the worker off,
  neither stream emits.

The banner it feeds sits in the shell on every page and is the only sanctioned update path —
**nothing auto-reloads** ([features/shell-and-auth.md](./features/shell-and-auth.md)).

---

## Deployment

### Build configurations

| Configuration | Environment file             | Service worker | Used for                                                                         |
| ------------- | ---------------------------- | -------------- | -------------------------------------------------------------------------------- |
| `production`  | `environment.production.ts`  | yes            | default of `ng build niord-app`; the shipped bundle (`publish.yml`); Frontend CI |
| `development` | `environment.development.ts` | no             | `ng serve niord-app` (budgets emptied — unoptimized output always exceeds them)  |
| `local`       | `environment.local.ts`       | no             | `npm run start:local`, against the `be/` backend                                 |
| `test`        | `environment.test.ts`        | no             | optimized build for a deployment's own origin                                    |

A country's configurations are generated in its workspace by the kit's set-up
([country-kit/README.md](../../country-kit/README.md)), each standalone rather than composed onto
another with the comma syntax, since Angular merges composed configurations shallowly. What a
country's files change is in [country-deployments.md](./country-deployments.md).

Bundle budgets are a build gate, not advice: initial 950 kB warn / 1.05 MB error, `main` 850 kB /
950 kB, `styles` 115 kB / 135 kB, any single component style 4 kB / 8 kB. The initial threshold moves
in step with `main`'s, since initial is `main` + styles. Pulling a heavy dependency into the initial
chunk fails the build in CI — lazy-load it instead.

### Hosting

No file in the bundle configures a host. The backend is the host: each deployable image carries its
country's production build and serves it at the root of its origin, beside `/rest` and `/auth`.
It provides the three things the bundle needs from its host:

- `Cache-Control: no-cache` on `/index.html`, `/ngsw.json`, `/ngsw-worker.js` and the other
  unhashed files — without it a cached shell or manifest defeats the update path above. The
  content-hashed files are cached for a year as immutable.
- A navigation fallback to `/index.html` that excludes `/rest`, `/assets/*` and real file
  extensions, so deep links resolve to the app but missing files 404 as files.
- `X-Frame-Options: DENY`, except `SAMEORIGIN` on `/silent-check-sso.html`, the page keycloak-js
  loads in a hidden iframe.

The backend also sends the app's Content-Security-Policy, on its HTML documents only: `index.html`,
every navigation the fallback answers with it, and `silent-check-sso.html`. The API, the scripts,
the styles and the other files carry none. The policy is the base product's and no country
changes it. It reads, directive by directive:

| Directive                                     | Why                                                                                                                                                      |
| --------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `default-src 'self'`                          | anything not named below comes from the app's own origin                                                                                                 |
| `script-src 'self'`                           | the build's bundles and the service worker; no page carries an inline script, which is why the silent-check-sso callback is its own file                 |
| `style-src 'self' 'unsafe-inline'`            | the start-up splash in `index.html`, Angular's component styles and the inline styles of the editors                                                     |
| `img-src 'self' data: blob:` + the tile hosts | the data URIs of the stylesheets and the rich-text editor, the protected WMS tiles (`blob:`), and the hosts of the `osmUrl` and `openSeaMapUrl` settings |
| `font-src 'self'`                             | the build's own fonts                                                                                                                                    |
| `connect-src 'self'` + Keycloak               | the REST API, and keycloak-js's token exchange                                                                                                           |
| `frame-src 'self'` + Keycloak                 | the hidden frame of keycloak-js's silent check-sso                                                                                                       |
| `object-src 'none'`                           | no plug-in content                                                                                                                                       |
| `base-uri 'self'`                             | an injected `<base>` cannot move the relative addresses elsewhere                                                                                        |
| `form-action 'self'`                          | no form posts elsewhere; keycloak-js logs in and out by navigation                                                                                       |
| `frame-ancestors 'self'`                      | only the app's own origin may frame a page of it, as the silent check-sso does                                                                           |

The backend builds it at run time from the current settings: the tile hosts are those of the tile
settings as `/rest/settings/web` serves them (the map kit's defaults where they are blank), and the
Keycloak origin is that of the token issuer the backend trusts. A sysadmin's change to a tile
setting therefore reaches the policy with the next page load, on every pod. The WMS is not listed:
the browser reaches it through the same-origin `/wms/` proxy. An image a message links from another
site is refused here by design. Files under `assets/legal/` and `assets/branding/` are a country's
data, so they carry `Content-Security-Policy: sandbox` and `nosniff` in place of the app's policy.

The policy is checked by `ui-apps/admin/e2e/csp.spec.ts`, which runs with `E2E_CSP` set against a
non-production build the backend serves; the guard in the e2e fixtures fails every test built on the
fixtures' `test` during which the browser refuses something (a spec that imports `test` from
`@playwright/test` runs unguarded) ([testing.md](./testing.md), _End-to-end tests_).

### Workflows

Two GitHub Actions workflows, one per application, both at the **repository root** — GitHub reads
workflows nowhere else, so every job that runs npm sets `working-directory: fe`. Each runs on pull
requests into `main` once they are ready for review, and on pushes to `main` that touch its inputs.
A draft runs nothing (its jobs skip themselves), and a pull request into a `feature/**` branch runs
no CI: `./verify-local` at the repository root runs the same steps locally (the example country's
image when Docker runs; the root `AGENTS.md` has the rule). A newer
push to a pull request cancels the run in flight; runs on `main` are never cancelled.

GitHub bills every job rounded up to a whole minute, and every job pays its own checkout and
`npm ci`, so each workflow keeps its work in two jobs. Within a job every step runs even when an
earlier one failed, so one red check never hides another.

**`.github/workflows/fe-ci.yml`**, the administration app:

- **Detect frontend changes** decides whether the pull request touches `fe/` outside
  `fe/ui-apps/info/`, or the kit, `country-kit/`. A change confined to that folder is the public
  site's alone; everything else under `fe/` — the shared folders, the workspace configuration,
  `docs/`, `scripts/` — counts, and so does every file of the kit.
- Three work jobs run side by side, each after its own `npm ci` on the Node version in `.nvmrc`:
  **Checks + builds** (`format:check`, `docs:check`, `paths:check`, `contents:check` for
  `niord-app`, `lint`, `test:guards`, then `npm run build`, followed by `urls:check` on its output),
  **Admin unit tests** (the whole suite; not plain
  `Unit tests`, the name of the backend's required check) and **Example country**
  (`node ../country-kit/example/run.mjs --image --with-deps`: the example country set up from a source
  zip of the commit, both builds with the checks on every country build, its specs and its lint,
  then its public-site image packaged from that build with the container test, the layout check and
  the separation check against the image; the `npm ci` is the harness's, in its work copy).
- **Frontend gate** is green only when every work job detection asked for succeeded. It runs whenever there is something
  to judge — also when a work job or the detection failed — and is skipped on a draft and when
  the administration app is untouched; a skipped check counts as passing.

**`.github/workflows/info-ci.yml`**, the public site:

- **Detect public-site changes** decides whether the pull request touches anything the public
  site's jobs read: `ui-apps/info/`, the three shared folders, the workspace configuration
  (`package*.json`, `.npmrc`, `.nvmrc`, `.postcssrc.json`, the Prettier configuration,
  `.editorconfig`, `angular.json`, `tsconfig*.json`, `eslint.config.js`, `vitest.config.ts`), the
  `info-*` and `build-*` scripts, the paths guard, the guard tests, the kit files the public site's
  jobs read (`country-kit/`'s pack tools, its tests, `image-info.mjs` with what it imports or reads,
  and its Prettier configuration) and the workflow itself.
- One work job, **Checks + builds** (Prettier over `ui-apps/info/` and the info scripts,
  `docs:check`, `lint:info`, `test:guards`, `test:info`, `paths:check`, `contents:check` for
  `niord-info`, `build:info` with `urls:check`, `separation:info` and `bundle:info`). It builds no
  image: the base build's image (`npm run image:info`), its container test and the layout check
  are manual tools ([fe/AGENTS.md](../AGENTS.md)), and Frontend CI's Example country job checks the
  example's image on pull requests. What keeps the administration app out of the public site is
  the separation check and lint, not the image.
- **Info gate** runs, and is skipped, on the same terms as the Frontend gate.

Neither trigger is path-filtered on `pull_request`: a check that never reports would block a merge
forever once branch protection requires it, so the work jobs are skipped instead. Both **Detect**
steps list a moved file under its old and its new path (`git diff --no-renames`), so a move between
the applications runs both workflows. The two gates are the checks branch protection will require;
it is switched on before production go-live, and until then both are advisory — a red gate blocks
no merge. On `push`, Frontend CI leaves out `fe/ui-apps/info/` like its Detect step does, so a merge
confined to the public site runs Info CI alone, and Info CI starts the publish run
(`.github/workflows/publish.yml` follows Backend CI, Frontend CI and Info CI on `main`: the source zip it
publishes carries the public site's sources, so such a merge publishes a version).

The unit-test suite runs with file parallelism off. `scripts/ci-test-shard.mjs` can split it: it
takes the builder's own `ng test niord-app --list-tests` discovery and assigns file _i_ to shard
_i_ mod _n_, so every spec lands in exactly one shard and a new spec needs no registration. CI runs
one shard (`1 1`): every shard first compiles the whole test bundle, so four shards cost twice the
runner minutes for a minute or so less of waiting. Run a shard locally with
`node scripts/ci-test-shard.mjs 1 4`; add `--dry-run` to list its files.

Playwright is deliberately excluded: it needs a live backend and a real identity provider
([testing.md](./testing.md)).

`npm ci` relies on the relaxed peer resolution configured in `.npmrc` (the ESLint TypeScript plugin
has not yet widened its peer range to the TypeScript version in use). Drop that file once it does.

### Dev servers: cross-origin or proxied

Which serve configuration runs decides where the administration app's `/rest` calls go.

**Proxied** — every serve configuration whose environment file names `/rest`: the default
`npm start` (the `development` configuration), `npm run start:local`, and what `./start-local.sh`
runs. The serve target's options set
`proxyConfig` to `proxy.conf.mjs`, which sends `/rest` to the `be/` backend on `localhost` (port 8888,
or `NIORD_BE_PORT`) and `/wms` to `NIORD_WMS_URL` when that is set. The application and its API share
the dev server's origin, as they share one in production, so nothing is cross-origin; Keycloak is
the local stack's on `localhost:8080`. The proxy carries those two paths only: an image the backend
answers with a redirect to its own `/img/…` does not load there ([backlog.md](./backlog.md)). The
`production` and `test` serve configurations reach `/rest` the same way, but their Keycloak is `/auth`
on the dev server's origin, which the proxy does not carry, so they do not sign in.

**Cross-origin** — no base configuration is. A country's development or test environment, from its
own repository, may name its test deployment and Keycloak as absolute URLs on another host; then the
proxy is never asked, and:

- that deployment must accept the dev origin cross-origin, and its identity provider must list the
  origin's redirect URIs. This repository's backend configures no CORS, so these configurations do
  not work against a local `be/` backend: use a `local*` one;
- file addresses the API reports root-relative need `ApiUrlResolver`;
- the WMS layer is same-origin only in a production-style deployment, which is why it is configured
  `wmsProtected: true` — tiles are fetched through `HttpClient` so they pick up the auth and domain
  headers instead of going out through the raw tile loader.

The public site's dev server always proxies: its serve target sets `proxyConfig` for every
configuration, and every backend and chart address the site calls is on its own origin.

---

## Browser storage

**The rule: guard every access with `isPlatformBrowser` and wrap it in `try` / `catch`**, so private
mode or blocked site data degrades silently instead of breaking boot. Core storage access follows it
and new keys must. **Nothing that matters is kept here** — these are per-browser conveniences, never
server data ([architecture.md](./architecture.md)).

| Key                                             | Store   | Owner                                                                                                             |
| ----------------------------------------------- | ------- | ----------------------------------------------------------------------------------------------------------------- |
| `niord.sessionDomainId`                         | local   | `core/domain/services/domain.service.ts` (shell — see [features/shell-and-auth.md](./features/shell-and-auth.md)) |
| `niord.language`                                | local   | `core/main-shell/services/language.service.ts`                                                                    |
| `niord.lastTagSelection`, `niord.lastTagDomain` | session | messages — last tag shortcut                                                                                      |
| `niord.printSettings`                           | local   | messages — print dialog                                                                                           |
| `niord.mailingList.testMessage`                 | local   | admin — mailing-list trigger test                                                                                 |
| `dateColumnType`                                | local   | messages — table date column                                                                                      |
| `formatLocationSettings`, `formatTimeSettings`  | local   | editor — insert-location / insert-time dialogs                                                                    |

New keys use the `niord.` prefix and both guards. Two gaps to close rather than copy:
`niord.mailingList.testMessage` is read and written with neither guard, and `dateColumnType`,
`formatLocationSettings` and `formatTimeSettings` have the `try` / `catch` but no platform check.
Those last three also predate the prefix convention; rename them only together with a read of the old
name, or users silently lose the preference.
