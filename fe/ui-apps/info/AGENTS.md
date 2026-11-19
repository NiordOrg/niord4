# AGENTS.md

`ui-apps/info/` is the public site of Niord (Angular project `niord-info`): the anonymous browser for
published Navigational Warnings and Notices to Mariners — details, table, map and print views — plus
the publications list and the teaser embedded on other sites. Nobody logs in, nothing is edited,
every request is a read. Its readers are at sea on a metered satellite link, so **the size of the
first visit is a requirement of the product, not a result of the build**: `ui-apps/info/bundle-baseline.json`
records what that visit costs today, `npm run bundle:info` fails when the measured transfer passes it
by more than two percent (or 64 bytes, for a group too small for two percent to mean anything), and
four budgets in `angular.json` fail the build itself. The rules both applications of the workspace
share are in the [workspace AGENTS.md](../../AGENTS.md).

## Non-negotiables

- **Nothing from the administration application, ever.** ESLint fails such an import at the
  keyboard, whether it is spelled through `ui-apps/`, relative, or as a dynamic `import()`, and the
  separation check fails a build that has one anyway: `npm run separation:info` reads the build's
  module graph, Info CI runs it on every build, a country's `npm run build` runs it on every country
  build, and a country's `image:info` runs it against the stats of the build its image packages. The
  image itself holds the built files alone, so it guarantees nothing here. `keycloak-js` and
  `@angular/forms/signals` are banned for the same reason the site has no login: there is no identity
  here and no edit flow.
- **Zoneless and signal-first.** `zone.js` is not installed, and no `ChangeDetectionStrategy`
  annotation, `NgZone` injection or `setTimeout` debounce appears anywhere under `ui-apps/info/`. State
  is signals, `computed()` derives, and an `effect()` only moves state out of the signal graph —
  into OpenLayers, the URL, browser storage or a screen reader's live region; work that has to
  run after a render uses `afterNextRender` / `afterRenderEffect`. A plain mutable field that a
  template reads renders its construction-time value and never updates again, so everything a
  template reads is a signal, an input, or a constant fixed before the first render.
- **Every reactive GET is an `httpResource`,** keyed on signals and read behind `hasValue()` —
  `value()` throws in the error state. `rxResource` appears exactly once, for the root-area fan-out
  that needs `forkJoin` inside the resource. There is no `Subscription` field, no fetching `effect()`
  and no component injecting another component. Server data lives in the component that shows it:
  `MessagesStore` is listed in `MessagesComponent`'s own `providers`, never `providedIn: 'root'` and
  never in the route's — Angular scopes a route's `providers` to the route config, so that injector
  outlives the component and the reader who came back would find the list they left. The component
  hosts the outlet the three views render in, so the three renderings of one filtered list still
  share one fetch, and leaving `/messages` destroys it. A failed read
  is told apart from an empty one — the store carries the failure and the views say so, because "no
  warnings in this area" and "the list did not load" are not the same sentence to a mariner. Every
  surface that reads says so in its own words, in a live region that is there before the words
  are, and a read that fails empties what it replaces rather than leaving another request's rows
  under the new one (`shared/utils/settled-value.ts`). A read on its way is told apart too: the
  store says it is loading, and the views are `aria-busy` with a spinner that is decoration only.
  One root area that does not answer is left out and the others are offered.
- **The time zone is the deployment's, never the browser's.** It is a deployment value:
  `country-specific/default/info.config.ts` in the base product, a country's own `info.config.ts`
  in its repository, because an anonymous site has no domain to take one from.
  `DateFormatService` is the one renderer: it reads the layout from the active language's own
  `*_DATE_FORMAT` key and the month and weekday **words** from the four `DATE_*` keys, never from
  `Intl`'s locale data, because browsers ship reduced data and fall back to English without an error
  — which would put English months on a Kalaallisut page. The arithmetic behind it is
  `shared/utils/zoned-date.ts`, a copy of the administration application's helpers that stays a copy.
  There is no date library, and no inline lint disable: the four `no-restricted-syntax` selectors
  apply here in full.
- **Backend HTML is sanitised, and only the shared sanitiser says what is safe.**
  `shared/pipes/safe-message-html.pipe.ts` hands message content to `MessageHtmlSanitizer`
  (`@niord/html/message-html-sanitizer`), which runs Angular's sanitiser over it and then puts back
  the two inline declarations a message is allowed to keep — `text-align` and a capped
  `padding-left`, rebuilt from validated values; it also narrows every `class` to the vocabulary the
  shared message-HTML stylesheet styles and gives every link with a `target` the relation
  `noopener noreferrer`. That is why the pipe's result is marked safe: a plain string is sanitised
  again at the binding, which would take both declarations out again. Nothing under
  `ui-apps/info/` calls a `bypassSecurityTrust*` method — ESLint fails one anywhere outside
  `shared-html/` — a translation string that carries markup is rendered with a plain
  `[innerHTML]`, one that reaches the page some other way (the map's credits, which OpenLayers
  writes itself) goes through `DomSanitizer.sanitize` first, and markup is never built as a
  string — a badge or a label is a component with real elements.
- **Three folders are shared, and the build reads all three**: `styles/` for the tokens and the
  classes both applications render, `shared-map/` for the OpenLayers map kit, imported as
  `@niord/map/<file>`, and `shared-html/` for the message-HTML sanitiser, imported as
  `@niord/html/<file>`. This site's chart is a thin component on `<app-ol-map>`: the map, its shell,
  the base layers, the layer switcher, the hit detection and the hover timing are the kit's. What
  belongs to this site sits beside it: the message features layer — which the chart, a message's
  dialog map and the teaser all draw with — in `shared/components/message-features-layer/`, and
  the labels and the tooltip in a `map-layers/` folder under the messages feature.
  **How a message looks is not this site's to decide**: the marks come from
  `@niord/map/map-styles` — `styleForMessageFeature()` for a warning or a notice and its affected
  area, `coordinateIndexStyle()` / `coordinateNameStyle()` for the numbered pins on a details map —
  which is the IHO S-124 portrayal the administration application draws, every colour, symbol and
  size of it read from the one definition `shared-map/portrayal/portrayal.json`, so a mariner reads
  the same chart in both. A layer here sets `mainType` on each feature and passes the view
  resolution in; it never builds a `Style` of its own. A behaviour, portrayal or performance change
  to the map is made in the kit, with the administration app's behaviour as the default, and
  measured for both applications, because a map tuning that helps one costs the other. The rules
  are [docs/map-portrayal.md](../../docs/map-portrayal.md).
- **Dialogs are the site's own `<app-dialog-frame>`, and they open lazily.** `openInfoDialog` fetches
  `@angular/cdk/dialog` and the dialog component together when the reader clicks; a static
  `inject(Dialog)` in an eagerly loaded file puts the overlay, the portal and the focus trap into
  what every first visit transfers, for a surface nobody has opened yet. A dialog component renders
  the frame and nothing around it: the scrim, the focus trap, `aria-modal`, Escape and returning
  focus belong to the CDK, and what the opener has to say arrives as `DIALOG_DATA`. The fetch takes
  as long as the link does, so one dialog is fetched at a time and a second click meanwhile is
  dropped, an opener that is gone by the time the fetch lands opens nothing, and a fetch that fails
  is reported and fetched again on the next click. Tab sets are `<app-tabs>` on `@angular/aria`.
- **The stylesheet is `src/styles.css`** — Tailwind with the scan pinned to this application, the
  CDK's overlay chrome, then the shared tokens, the `@theme` bridge and the two theme values only
  this site uses, the shared Layer-3 classes and the shared message-HTML rules, then this site's
  own two files under `src/styles/`. A repeated look is a shared class or a utility on the
  template; a new rule in `src/styles/` is for what neither can express — the documents an asset
  file carries, what a printer does. Country colours are tokens, never classes. The scan
  deliberately excludes spec files: a spec quoting a class name out of a message's HTML would
  otherwise emit a utility of that name into the shipped stylesheet, where it outranks the
  message-HTML rule it was named after.
- **The menu bar and the filter bar are pinned by the stylesheet, and only where there is room.**
  They are sticky (`.menu-bar` and `.filter-bar` in `src/styles/chrome.css`), never fixed: each
  takes its own room in the flow, so nothing below them clears them with a margin, and on a
  viewport under 30rem tall — 400 % zoom, a phone held sideways — neither is pinned at all. Both
  wrap to more rows in a long language or on a narrow screen, so neither has a fixed height: each
  publishes its measured height (`shared/utils/publish-height.ts`), and what is sized or scrolled
  against them — the map view's height, the page's `scroll-padding-top` — reads
  `--niord-pinned-bars-height`, which is zero wherever nothing is pinned. `npm run layout:info`
  holds all of it in a browser.
- **The menu bar wears the administration header's colours, not its layout.** Its items are the
  chrome's foreground, the active one carries the header's light rounded highlight and weight
  rather than a colour of its own, and the bar ends in the header's 3 px brand stripe
  (`--color-chrome-active`), all from the shared chrome tokens. Its own shape stays: the centred
  items, no logo, the four views as a row of icons on a phone rather than a burger, so each is one
  tap away, and each language as its own pressed-or-not button rather than a dropdown (the owner,
  2026-11-02).
- **The rich text a message carries is `.message-details-html`**, from the shared
  `styles/message-html.css`. Every place a message's part details or its publication reference are
  bound with `[innerHTML]` wears that class: the rules inside it are element-level, which nothing
  else in the workspace is allowed to be, and they only reach markup under the wrapper. They draw
  every link as a link at rest; the publication field's wrapper adds `.message-publication`, under
  which a link reads as the citation it is part of.
- **Icons are inline SVG, from one registry.** Every glyph is `<app-icon name="lucide…">`, and the
  name must be an entry in `shared/icons/info-icons.ts` — a glyph with no entry renders nothing.
  A new glyph is an import plus an entry there; never a webfont, never a raw `<svg>` in a template.
- **One service owns browser storage.** `core/services/preferences.service.ts` is the only file that
  reads or writes `localStorage`, every key it uses carries the `niord-info.` prefix, and a reader
  whose choice was written under the older bare name keeps it — the service carries that name over
  once and then removes it. Storage can throw or come back empty, so every read degrades to the
  default rather than failing a render.
- **Same-origin `/rest`.** Every call is a relative `/rest/public/v1/…`; there is no base-URL prefix,
  no interceptor rewriting one, and no CORS anywhere in the stack. The one function that touches a
  repository URL is `core/services/repo-url.ts`: it encodes an attachment's path segment by segment
  (`@niord/html/repo-path`, which the administration app uses too), and it reduces an absolute
  repository URL to its path only on the site's own host or on a public host of the backend, which
  the country configuration lists (`backendHosts`) — one on any other host may be another
  deployment's repository, and is left as it was written. The dev server proxies `/rest` and
  `/wms` — see `proxy.conf.mjs`.
- **Printing is the backend's PDF.** Both print buttons open a `/rest/message-reports/…` address
  in a tab of its own: `report.pdf` for the filtered list and `message/<id>.pdf` for one message,
  both anonymous, both composed in `features/messages/message-pdf-url.ts`. The list names the
  backend's `public` report, the standard layout without the front page the editors' prints open
  with, and the backend dates both in the zone of the domain the messages come from. That endpoint
  reads the administration search names — `area`, not the public API's `areaId`; the language is
  `lang` in both — and applies none of the public API's own narrowing, so the list address also
  names the message series the rows on screen came from; without that the sheet carries notices
  from domains this site does not serve.
  An absent series is read as **unrestricted**, so the list sheet is offered at all only once the
  read keyed on the current filter has settled with a non-empty series set — the print chip is
  inert while a list is loading, empty or failed, and rows left over from the previous filter do
  not open it.
  The client-rendered `/messages/print` route stays: it is what `/details.html` redirects to.
- **No new user-visible text.** Every string is a Transloco key and the existing keys stay as they
  are, spelling included — the current site's wording is the contract with its readers. A genuinely
  new key is for something that has no text today, a screen-reader label most often. The bundle is
  the `en` pack's `languages/en/i18n/info/en.json`. A build reads the bundles from the copies staged
  into `.niord/build/info/`, each complete (a key a pack lacks is filled from `en`), and ships
  exactly the bundles its `language.config.ts` names. The country's own words — authority name,
  footer links and logo, dialog titles — come from `assets/branding/<lang>.json`, which overrides
  the bundle, never from a key of the bundles; the loader asks for a branding file only for the
  configuration's `brandingLanguages` (none in the base product's own build, so it requests none).
  A new key carries English only. Every other language is a country's pack, held to `en` by the
  pack check of that country's build — every key, its placeholders, its markup, the date patterns
  and the month and weekday lists. A country's branding values and legal documents awaiting
  acceptance are listed in its repository (`fe/info/branding-unaccepted.json`, read by the kit's
  branding check), its packs' awaiting values in each pack's `status/unaccepted.json`; no build
  ships either list, and every list is empty at its go-live.
- **Text from the backend says which language it is in.** A message, an area, a publication or a
  legal document with no text in the reader's language arrives in one it has — on a Kalaallisut
  page of a deployment whose warnings are written in Danish, nearly every one arrives in Danish.
  The element that renders such text binds `[appContentLang]` (`shared/directives/content-lang.ts`)
  to the language the text came in, which sets `lang` wherever that is not the page's, and the flag
  beside a title or a subject
  names the language in its alternative text (`ARIA_LANGUAGE_FLAG`); a language the build names no
  flag for shows its code as text instead. A language is named in itself — Dansk, English,
  Kalaallisut — by its label in the build's `language.config.ts` (`AppConfigService.languageName`),
  which `npm run contents:check` holds to the label of the language's pack, so it is the same in
  every configuration; the language buttons of the menu are named that way and say which one is
  active with `aria-pressed`.
- **The folder layout is the one that came in**: `core/` (models and services), `features/`
  (`messages`, `publications`, `teaser`), `shared/`, `i18n/`, `routes/`, with `app.ts` and the
  configuration files `app.config.ts`, `niord-info.config.ts` and `provide-niord-info.ts` beside
  them in `src/app/`. `core/` and `shared/` are below `features/` and
  never import from it; sibling features never import each other. ESLint enforces both.
- **Country variation is a country repository's files at the seams.**
  `ui-apps/info/src/app/country-specific/default/` holds the four country files with their neutral
  defaults: `info.config.ts` (time zone, root areas, footer component, the address of the
  accessibility statement), `language.config.ts` (every language the build ships a bundle for, with
  its label, locale and flag; the site languages; the default; the languages it ships branding
  for), `country.routes.ts` (the country's own pages, every one public as the site is, empty here)
  and `country.providers.ts` (`provideCountry()`: the country's component slots, nothing here; one
  slot exists, `layout.beforeFooter`, between the main content and the footer, which renders
  nothing until a country registers a component for it with a lazy loader). The build overlays
  `public/assets/default/` onto `assets`. `app.config.ts` composes the four files and is the only
  file that imports the folder; ESLint fails every other spelling, static or dynamic. A country
  replaces them from its repository (`info/app/` there, the seams of
  [country-kit/README.md](../../../country-kit/README.md#the-seams--seamsjson)). The route table is
  `routes/create-niord-routes.ts`: the teaser route sits above the layout route, so no base address
  waits on the country's chunk, and the country's routes are the layout's last child, loaded lazily
  after every base route; an address no base route matches fetches that chunk once before the
  wildcard. A slot is a base product contract kept across versions, added deliberately where a
  country needs one and catalogued in `country-kit/seams.json`, and exported by the country API.
  A country's files import the base product only through `@niord/info/api` (`src/public-api.ts`;
  in specs also `@niord/info/api/testing`, `src/testing-api.ts`), never by a relative path into the
  base product; the country lint fails every other import, static or dynamic, `keycloak-js` and
  signal forms, a bare `[innerHTML]` and an `eslint-disable` there. A footer opens a legal document
  with `openInfoDialog(injector, loadLegalDialog, …)`, so the dialog stays a lazy chunk. Nothing
  else in the site imports the API. A country need it does not meet is a new export, added
  deliberately under its own contract comment in the file's form and recorded in
  `country-kit/API-CHANGES.md`; an export is removed only after a deprecation.
  A country may carry the page its build serves (its `info/app/index.html`, the `index` seam) in
  place of the base product's English `src/index.html`: on a slow link it is what a screen reader
  and the browser tab have for seconds before the application starts, so it is in the country's
  default language and carries the site's name as that language's bundle words it; the kit's
  check-build fails a built page whose `lang` or title is not the default language's. A country's
  footer is its own component, named by its `info.config.ts`; the disclaimer and the cookie
  statement are `assets/legal/<document>.<lang>.html` in its branding, rendered by the one shared
  dialog, so a further language or document is a file. The cookie statement names every key of
  `core/services/preference-keys.json` with its prefix, which a country's set-up checks, so a new
  stored key is added there and recorded in `country-kit/API-CHANGES.md`. Two build
  configurations: `production` and the unoptimised `local`, each **standalone** — Angular merges
  composed configurations shallowly, so a comma-composed `production,<other>` silently drops
  production's own keys.
- **A deployment tunes the languages and the tile address, the build decides what is possible.**
  `infoSiteLanguages` and
  `infoDefaultLanguage` are read once at boot from the anonymous `GET /rest/settings/web`, and the
  build's `language.config.ts` is what the site runs on when that call fails; boot never blocks on
  it. A language is offered only when the setting asks for it **and** a bundle ships for it, so no
  setting can switch the site to a language it has no words for. The resolved default is applied
  as Transloco's default language and the available list. There is no fallback language: every
  shipped bundle is staged complete, and a key still missing at runtime renders as the key. A visit
  starts in the
  language its address names, then the one the reader chose on the switcher, then the resolved
  default — each only if the site offers it — and only a choice made on the switcher is remembered,
  so a deployment that moves its default reaches every reader who never chose. A translation bundle
  that does not arrive is asked for again, and meanwhile the site shows another of its languages
  rather than key names (`i18n/niord-transloco-loader.ts`). The deployment ribbon comes from the
  same payload's `mode`, and a backend that did not answer shows none — a public site must not
  display a DEV badge because a request failed. The base map's tile address is the same payload's
  `osmUrl`, taken only as an `https:` template carrying `{z}`, `{x}` and `{y}` (the policy below
  takes images over https alone) and handed to the map kit through its `MAP_TILE_SOURCES` token
  in `app.config.ts`; anything else, or no answer, leaves OpenLayers' own
  `tile.openstreetmap.org`. The site draws no OpenSeaMap, so `openSeaMapUrl` means nothing here.
  A deployment that moves its tiles to another host updates its cookie statement, which names
  `tile.openstreetmap.org` as the host a map is drawn from.
- **Every `ng` command names the project.** In this workspace a bare `ng build` or `ng serve` stops
  with "Cannot determine project", and a bare `ng test` or `ng lint` runs _both_ applications'
  targets one after the other; it does not choose and it does not fail.

## Commands

```bash
npm run start:info       # dev server http://localhost:4400, /rest proxied to the be/ backend
npm run build:info       # production build with --stats-json (bundle:info reads the stats)
npm run test:info        # the Vitest unit suite
npm run lint:info        # ESLint with --max-warnings 0: separation, layer boundaries, the time-zone
                         # rule, a11y templates — the site carries no inline disable of any of them
npm run bundle:info      # first-visit transfer against bundle-baseline.json — run after build:info
npm run separation:info  # nothing of the administration app reached the build — run after build:info
npm run layout:info      # the layout in Chromium, against the last build: every menu control on top, no
                         # pinned bars at 400 % zoom, focus never under a bar, whole field names, 40 px
                         # filter chips, forced colours, link styles, printed link addresses, scroll
                         # regions that follow their content; every page under the image's CSP over
                         # plain HTTP, map tiles included — run after build:info (a manual check on the
                         # base build; the Example country job runs it in CI)
npm run verify:info      # paths:check → contents:check → format:check → lint:info → test:info → build:info
                         # → urls:check → separation:info → bundle:info
npm run contents:check -- --project niord-info   # what each configuration ships, from angular.json, no build
npm run urls:check -- --dist dist/niord-info/browser   # no external font or style URL — after each build
```

The whole local stack with the public site instead of the administration app — a backend, the
database and Keycloak included — is `./start-local.sh --info` from the repository root (`--admin
--info` for both frontends at once, `--fe-only` against a backend that already runs). A country's
public site runs from its own repository (`npm run start:info` there, after
`node setup.mjs && npm ci`). It serves on 4400 + slot.

A country releases the site as its own container image from its repository (`npm run image:info`
there); in the base product the same command packages the base build, as a manual tool.
`npm run image:info` (`country-kit/image-info.mjs`) copies `dist/niord-info/browser` into a fresh
context with the Dockerfile and the five files of `nginx/`, never the workspace, pre-compresses it
there and builds the image, and refuses a build that is not a production one, any source map and
any file outside `country-kit/info-image-allowed.json`:

```bash
npm run build:info && npm run image:info -- --tag niord-info
npm run test:container:info -- --image niord-info --no-legal-doc --no-branding-text   # the proxy, the headers
                         # and the start-up checks; the base build ships neither file
npm run test:container:info -- --image niord-info --no-legal-doc --no-branding-text --browser
                         # and the frame policy in Chromium
```

What keeps the administration app out is the separation check and lint (above), not the image. A
country runs the same `image:info` from its own build, and there it also runs the container test with
`--browser`, the layout check and the separation check against the image it built before the image may
be pushed ([country-kit/README.md](../../../country-kit/README.md#the-public-site-image--imageinfo)).

The image serves the site and nothing else: only GET and HEAD reach the backend, only
`/rest/public/v1/`, `/rest/settings/web`, `/rest/repo/file/`, `/rest/message-reports/report.pdf`,
`/rest/message-reports/message/<id>.pdf` and `/wms/` exist on it, `Authorization`,
`Proxy-Authorization`, `Cookie` and `NiordDomain` are emptied on the way out, and a query that
carries a one-time `ticket`, in any spelling the backend decodes to that name, is a 403. The backend
is told the host the reader asked for, the scheme a TLS front reports in `X-Forwarded-Proto` (`http`
or `https`, nothing else) and the address chain with this hop appended; every other forwarding header
a client writes — `Forwarded`, `X-Forwarded-Host`, `-Port`, `-Prefix`, `-Ssl` — is replaced or
removed. The two report paths are named one by one rather than allowed as a
prefix, because the rest of that family — the report catalogue and the create, update and delete of
a report definition — is an administration surface; rendering a list can take seconds, so those two
raise the proxy read timeout. They also take no query but the parameters the site's print addresses
carry, checked as one whole string: the list takes exactly one `report=public`, anywhere in the
query, and around it the filter `message-pdf-url.ts` composes — `lang`, `mainType`, `area`,
`messageSeries` — plus `publication`; one message takes `lang`; and the value of every parameter
but `report` is letters, digits, `.`, `_` and `-`. The report is required and pinned: for a caller
it knows, the endpoint renders any report definition named, whatever its domain, and it renders a
list that names no report in the standard one, which opens with the editors' front page. The
backend holds an anonymous caller to the `public` report on every host it answers on — a list
without a report is rendered in it, one naming any other is a 403 — so a second report meant for
the public is allowed by name, deliberately, in the container and in the backend alike. Anything
else — a list without the report, the report twice, another report, the endpoint's `debug`
switch, its page layout, its free-form `param:` values, another spelling of an allowed name — is a
403 that never reaches the backend, so a parameter a print address gains is added to the two maps
at the top of `nginx/default.conf.template` in the same change; `scripts/info-print-query.test.mjs` (in
`test:guards`) fails while an address `message-pdf-url.ts` composes does not match them. The two
locations that take a message id — `/rest/public/v1/message/<id>` and the one-message PDF — pass
the request line on as it arrived, so the backend receives a short id that holds a slash
(`B%2F1%202026`) whole, and the raw line is held to the shape `encodeURIComponent` gives the id,
one path segment: an encoded slash, in either case, is let through there, and an encoded dot,
backslash, percent, semicolon or NUL, an encoded or literal doubled slash, a further segment or a
`..` is a 403. `/rest/repo/file/` passes the request line on as it arrived as well, so a file name's
`;`, which every link writes as `%3B`, reaches the backend escaped rather than decoded into a path
parameter the backend refuses; its raw line is held to the paths the backend's firewall serves for
a repository file: `/rest/repo/file/` spelt so, then non-empty segments of RFC 3986 path characters
(letters, digits, `-._~`, `!$&'()*+,=:@`) and `%XX` escapes, `%25` and `%3B` among them. Any other
raw character (a `;`, a backslash, brackets, `^`, a letter outside ASCII), an empty segment (a
doubled or trailing slash), a `.` or `..` segment, and an encoded dot, slash, backslash or NUL or any
of them encoded twice (`%252F`) is a 403; a name that merely holds two dots (`Rapport..pdf`) is
not. Its query is passed on unchecked but for the ticket, and its headers are its own: the sandbox
for every type but a PDF, nosniff and the referrer policy.

The container runs as the unprivileged user `nginx`, uid and gid 101, never as root, and listens on
port 8080. It writes under `/tmp` alone — the server block the entrypoint renders (`/etc/nginx/http.d`
is a link to `/tmp/niord-http.d`), the pid file and nginx's temporary files — so it runs in a pod held
to the restricted Pod Security standard, and on a read-only root filesystem when `/tmp` is writable.
The container test runs it the way such a pod does, and stricter: as the image's own user, which it
checks is 101, with every capability dropped, no privilege escalation and a read-only root filesystem.
The base Helm chart deploys it as its `info` component, with a country's image
([be/k8s/README.md](../../../be/k8s/README.md#the-public-site)).

Three settings come from the deployment, and the entrypoint checks the first and the last before
nginx starts — a value of the wrong shape stops the container with a message that names it:

- `NIORD_SERVER_URL` — the backend, as `http(s)://host[:port]`; required, since the image names no
  backend of its own. One trailing slash is dropped; a path, a query or credentials are refused. The
  name must resolve when the container starts. An `https` backend's certificate is verified against
  the image's CA bundle for that name, which is also sent as SNI; a backend reached over the
  cluster's own network is given by its `http` Service address instead.
- `WMS_SERVER_URL` — the chart server; empty means no chart layer.
- `NIORD_TEASER_FRAME_ANCESTORS` — the pages allowed to embed the teaser, as a CSP `frame-ancestors`
  source list; the image's `*` keeps every page that embeds it working. Every other route may be
  framed by the site alone. The teaser is `/teaser` and `/teaser/`, with any query; a path below
  it is not, because the router moves it on to the message list, and the application never moves
  an address on the teaser to another page by its fragment (`routes/hash-url.ts`). A page that
  embeds the teaser by the hash-routed site's address, `/#/teaser?lang=da`, asks for the root,
  since the fragment never reaches the server; a frame's request for the root is therefore given
  the teaser frame document (`public/teaser-frame.html`) instead of the application. It shows
  nothing and takes the teaser's policy, and its script moves the frame on to `/teaser` when the
  fragment names the teaser. Its script carries a copy of `hashUrlTarget`, which
  `scripts/info-teaser-frame.test.mjs` (in `test:guards`) holds to the application's answers. A
  browser marks a frame's request with `Sec-Fetch-Dest`, which it sends to an https origin and to
  its own machine only, so over plain HTTP, and in a browser that sends no such header, that one
  address stays refused in another site's frame.

Every response carries the security headers of `nginx/niord-security-headers.conf` — a
Content-Security-Policy that allows the site's own script files and no inline script, and the
`nosniff` and `strict-origin-when-cross-origin` referrer policy of
`nginx/niord-common-headers.conf`, which it includes — and every location that sets a header of its
own includes that file again, because nginx drops a server's `add_header` in such a location. The
backend's own `nosniff` is hidden, so the header is never sent twice. Behind a TLS front that
reports https in `X-Forwarded-Proto`, every response also carries
`Strict-Transport-Security: max-age=31536000` (this host only, from the same file), and an
upstream's copy is hidden; over plain HTTP it carries none. So neither `index.html` nor
the teaser frame document carries an inline script; what has to run before the router starts goes
at the top of `main.ts`, where it must not be able to throw. The policy takes images over https
and never over plain http, and the container itself serves plain HTTP behind a TLS front, so every
tile address names `https:` itself — one without a scheme asks for http images wherever the site
is opened over http — and `npm run layout:info` fails a map page that breaks the policy. A
repository file gets `Content-Security-Policy: sandbox` instead of the
site's policy, PDFs excepted. A legal document and a branding file (`/assets/legal/`,
`/assets/branding/`) are a country's data and get `Content-Security-Policy: sandbox` too, with the
`nosniff` and referrer policy of `nginx/niord-common-headers.conf` and not the site's policy, which
the browser would enforce beside the sandbox. Redirects are relative, so a TLS front's scheme
survives them. A hashed build file is cached for a year and a file under `assets/` for five minutes, and a missing one of
either is a `no-store` 404. Every text file is compressed once, in the
image's build context, and served pre-compressed — brotli to a client that asks for it, gzip to one that does
not, the original to neither.

The live defects you will meet before you have changed anything are in
[docs/backlog.md](../../docs/backlog.md) under "The public site" — read them before reporting one.

## Guards

- `npm run verify:info` before handing anything over. It is most of the Info gate, not all of it:
  the gate also runs `npm run docs:check` and `npm run test:guards`, and Frontend CI's Example
  country job, on pull requests, lays out the example's build and container-tests its image. Run
  `npm run layout:info` and `npm run test:container:info` by hand on the base build when a change
  can reach them.
- `npm run layout:info` after `npm run build:info` whenever a change can move the menu bar, the
  filter bar or what sits under them, or reshape a message card — their templates, `src/styles/`,
  the shared `styles/`, a translation that grows a label, a language or an area added to a
  country. The layout is only visible in a browser, so this is the one check that sees it.
- **A first visit lands on `/messages/details`**: the root and every unknown path redirect there,
  and that is the route `bundle-baseline.json` names and `bundle:info` measures. A change to that
  redirect moves the gate with it — `landingRoute` and `landingEntryPoints` change in the same
  commit. The routes spec fails while a redirect and `landingRoute` disagree, and
  `npm run test:guards` fails while `landingEntryPoints` are not the files that route loads.
- `npm run bundle:info` after any dependency, any import that is not lazy, and any change to a route
  that the first visit reaches. An improvement is reported, never accepted on its own:
  `--update-baseline` is a deliberate act.
- A change under `fe/shared-map/`, `fe/shared-html/` or `fe/styles/` runs **both** applications' gates.
- `npm run paths:check` after any edit to `angular.json`.
- `npm run docs:check` after any change to this file, the workspace README or `docs/`.
- `npm run test:container:info` against a freshly built image after any change to the Dockerfile,
  the entrypoint, the files under `nginx/`, `country-kit/image-info.mjs` or its allow-list, the proxy allow-list, the parameters the two print
  addresses carry, `index.html` or the teaser frame document; `npm run test:guards` holds the print
  addresses to the query maps and the teaser frame document's script to the application's hash
  rules without an image, so it runs first. Add `--browser` after a change to the frame policy,
  the teaser frame document, `routes/hash-url.ts` or the teaser: Chromium then frames the site
  from another origin, as the Example country job does for the example's image.
- Review with the `niord-review` skill against the public site's gate section of
  [docs/definition-of-done.md](../../docs/definition-of-done.md). The rest of that checklist is
  written for the administration app; where it and this file differ — Signal Forms, new visible
  text, the domain's time zone, the shared wrappers — this file is the rule here.
- The gate for this application is the **Info gate** of `.github/workflows/info-ci.yml`: format and
  documentation, lint and the guard tests, the unit suite, the paths and contents checks, and the
  base build with its URL, separation and bundle checks — one work job after one `npm ci`. It is
  a check the ruleset on `main` requires: a red gate blocks the merge. A pull request confined to
  `ui-apps/info/` runs that workflow and, of Frontend CI, only the `Example country` job and the
  `Frontend gate` (the example builds the public site too); the administration jobs are skipped.
  A push to `main` confined to `ui-apps/info/` runs Info CI alone, and Info CI then starts the
  publish run (the source zip carries the public site).
