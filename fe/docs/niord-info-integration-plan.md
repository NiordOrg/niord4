# niord-info in niord-ng — analysis and integration plan

niord-info is the public, anonymous, lightweight web UI of Niord: the mariner's view of active
Navigational Warnings and Notices to Mariners (map, table, details, print, publications, teaser).
This document analyses the current code and sets out how it becomes part of this repository.
It doubles as the execution record: tick the tasks in [Tasks](#tasks) as they land.

The site's rules as they stand — its separation guarantees, its proxy, its gates — are stated in
[`ui-apps/info/AGENTS.md`](../ui-apps/info/AGENTS.md), and where this document and that file
disagree, the file is right. The separation guarantees, the map and the deployment below were
brought up to date after the review of the merged work (2026-09-24); the analysis of the upstream
code, the measurements, the task lists and the decision log keep what was true when each was
written.

**Sources analysed (2026-09-21):**
`NiordOrg/niord-info` @ `419ddd7` (the library, `src/lib/**`) and `kaspernielsen/niord-info` @ `3b5033b`
(the DK shell: app config, footer, legal dialogs, Dockerfile, nginx). 41 source files, 3 251 lines of
TypeScript with inline templates, 35 spec files (2 249 lines), 802 lines of SCSS.

## Requirements

| #   | Requirement                                                                                   | How the design meets it                                                                                                                                                                                                                                                                                                                                                    |
| --- | --------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Standalone deployment in its own container, no possible spill-over from the administration UI | Own application project, own build output, own image. The image is built from a Docker context that **does not contain `fe/ui-apps/admin/`**, so an import of administration code fails the image build. Lint bans and a bundle check back that up.                                                                                                                        |
| 2   | Styling and design tokens maintained in one place                                             | Tokens, the Tailwind theme bridge, a core slice of the component classes and the message-HTML stylesheet move to a neutral `fe/styles/` that both applications list. The shared code is the OpenLayers map kit in `fe/shared-map/` — see [The map](#the-map) — and the message-HTML sanitiser in `fe/shared-html/`. None of the three folders can import application code. |
| 3   | Follows the repository's Angular version                                                      | Same workspace, same `package.json`, same lockfile. There is nothing to keep in step.                                                                                                                                                                                                                                                                                      |
| 4   | Own site-language setting, read from the settings endpoint                                    | New web settings `infoSiteLanguages` and `infoDefaultLanguage`, read from the anonymous `GET /rest/settings/web` exactly as the administration app reads `siteLanguages`.                                                                                                                                                                                                  |
| 5   | Own `en` / `da` / `kl` bundles                                                                | `fe/ui-apps/info/public/assets/i18n/{en,da,kl}.json`, with a key-parity spec.                                                                                                                                                                                                                                                                                              |
| 6   | The repository's current country split                                                        | `src/app/country-specific/{default,dk,gl}/` swapped by `fileReplacements`, brand tokens swapped through `styles[]`, country assets through the `assets` overlay — the three swap points of [country-deployments.md](country-deployments.md).                                                                                                                               |
| 7   | A DK and a GL version                                                                         | Build configurations `dk` and `gl` (plus `local-*`), one image per country.                                                                                                                                                                                                                                                                                                |
| 8   | Folder structure below `src/lib` kept                                                         | `core/ features/ shared/ i18n/ routes/ testing/` move as they are; the level above them is `src/app/`, there is no folder called `lib`. Folders are **not** reorganised to look like the administration app. A component moves only when the moved part is genuinely generic or a boundary requires it, and every such move is named in this plan — today that is the map. |
| 9   | **Small bundle — the site is used on ships with low bandwidth**                               | A core requirement with a measured baseline, budgets that fail the build, and a first-visit transfer check in CI. See [Bundle size](#bundle-size).                                                                                                                                                                                                                         |
| 10  | No new texts                                                                                  | The existing keys and wording are kept. New keys only where a text is required and missing today (screen-reader labels, two error messages that are hard-coded English).                                                                                                                                                                                                   |

## Repository layout

```
fe/                                   the Angular workspace (one package.json, one lockfile)
  angular.json                        projects: niord-app (folder ui-apps/admin), niord-info (folder ui-apps/info)
  package.json  package-lock.json  .npmrc  .postcssrc.json  tsconfig.json
  eslint.config.js                    ONE config; every glob carries its full <app>/ prefix
  vitest.config.ts  proxy.conf.mjs  playwright.config.ts  transloco.config.ts
  CLAUDE.md  README.md                workspace rules only, pointing at the two application files
  docs/  scripts/  .claude/           stay at workspace level
  styles/                             SHARED, CSS only
    tokens.base.css                   country-locked tokens (S-124 hues, status, severity)
    tokens.default.css  tokens.dk.css brand tokens
    tokens.active.css  tokens.active.dk.css  tokens.active.gl.css
    theme.css                         the Tailwind @theme bridge (today styles.css lines 38-148)
    components-core.css               the Layer-3 families both apps use
    message-html.css                  .message-details-html — the contract for backend-rendered HTML
  shared-map/                         SHARED, the OpenLayers map kit: the map, its layers, the S-124 portrayal
    ol-map.ts  map-shell.service.ts  tile-load-gate.ts  map-projection.service.ts
    layers/  ol-layer-switcher.ts  map-styles.ts  assets/img/s124/
  shared-html/                        SHARED, the message-HTML sanitiser (alias @niord/html)
  ui-apps/admin/                     the administration app (Angular project name stays niord-app)
    CLAUDE.md                         the administration non-negotiables
    src/  public/  e2e/               moved as they are, siblings preserved
    tsconfig.app.json  tsconfig.spec.json  ngsw-config.json
  ui-apps/info/                      niord-info (Angular project name niord-info)
    CLAUDE.md
    src/
      main.ts  index.html  styles.css
      app/
        app.ts  app.config.ts  app.routes.ts
        core/        interceptors/ models/ services/          (as today under lib/)
        features/    messages/ publications/ teaser/
        shared/      components/ pipes/
        i18n/  routes/  testing/
        country-specific/
          default/   info.config.ts  language.config.ts
          dk/        info.config.ts  language.config.ts  index.html
          gl/        info.config.ts  language.config.ts  index.html
    public/assets/
      i18n/          en.json  da.json  kl.json
      img/
      default/       branding/ the neutral Niord icons
      dk/            branding/{da,en}.json  legal/{disclaimer,cookies}.{da,en}.html  logos, icons
      gl/            branding/{kl,da,en}.json  legal/…  logos, icons
    Dockerfile  Dockerfile.dockerignore (an allow-list)
    nginx/default.conf.template
    tsconfig.app.json  tsconfig.spec.json  bundle-baseline.json
```

### Two application folders side by side

The two applications live under `fe/ui-apps/` — `ui-apps/admin` (project `niord-app`) and
`ui-apps/info` (project `niord-info`). They were first placed directly under `fe/` as
`admin-app/` and `info-app/`; the owner then asked for one folder that names what they are, and
`ui-apps` was chosen over `apps` so that a reader does not expect an Nx workspace. The folders
beside it — `styles/`, `shared-map/`, `shared-html/`, `docs/`, `scripts/` — are shared or
workspace-level. The task lists and the decision log below keep the names that were current when
each entry was written.

The administration app moved out of the workspace root, and niord-info sits beside it — no
`projects/` layer in between. Both layouts meet every hard
requirement, and the move does **not** make the separation stronger; it is made for clarity, and
it is made first, as its own move-only change, because it is never cheaper than before the second
application exists:

- **The administration app gets a name.** With root `""` it is "the workspace minus some
  folders", and formally contains `styles/`, `shared-map/` and the other application.
- **Unpinned commands fail loudly.** From `fe/`, a bare `ng build` or `ng serve` stops with
  "Cannot determine project" instead of silently building the administration app.
- **One token for every rule.** `no-restricted-imports` matches the import text as written, so a
  ban on `**/src/app/**` would also flag niord-info importing its own `../src/app/…` from an e2e
  spec. `**/ui-apps/admin/**` and `**/ui-apps/info/**` are unambiguous at any depth, and the lint
  blocks, format scopes and CI filters of the two applications become mirror images.
- **Rules for coding agents are scoped correctly.** `fe/CLAUDE.md` today states administration
  rules — Signal Forms for every edit flow, Keycloak, the time zone from the domain, `en` + `da`
  with the `ai_` prefix, "compose the shared wrappers" — that are wrong for an anonymous public
  site. `ui-apps/admin/CLAUDE.md` covers that application's `src/`, `public/assets/i18n` and `e2e/`
  together, which a file inside `src/` could not.

What moves is application code only: `src/`, `public/`, `e2e/`, `tsconfig.app.json`,
`tsconfig.spec.json`, `ngsw-config.json`. `docs/`, `scripts/`, `.claude/` and every tool
configuration stay at `fe/`, so the documentation guard, the specification check, the CI
workflow, `start-local.sh` and `be/` are untouched. The Angular project keeps the name
`niord-app`, so `dist/niord-app` and the build targets do not change — the same pairing as
folder `ui-apps/info` / project `niord-info`. Fixes that are still brought over from the source
repository of the administration app follow the rename when replayed with
`-c merge.directoryRenames=true`.

The library/shell split ends. It existed so that several authorities could build their own shell
around an npm package; here the country split does that job. `provideNiordInfo()`, the
`NIORD_INFO_CONFIG` token and `createNiordRoutes()` stay as the seam between `app/` and the
country folder, so the code below `core/ features/ shared/` moves without being rewritten.
ng-packagr, the git submodule, the nested npm workspace, the `paths` alias and the
`"prebundle": false` workaround all go.

### Why a second project in `fe/` and not a folder beside it

|                     | Second project in `fe/` (chosen)                              | Top-level folder, own lockfile                                       | Top-level folder, root npm workspace                                       |
| ------------------- | ------------------------------------------------------------- | -------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| Angular version     | one lockfile — cannot drift                                   | drifts (the source is already a major behind); needs a CI comparison | cannot drift                                                               |
| Tokens in one place | by path                                                       | relative path across folders                                         | a package                                                                  |
| Separation          | build context without `fe/ui-apps/admin/`, lint, bundle check | physical                                                             | as chosen                                                                  |
| Cost                | angular.json, lint, one workflow                              | a second toolchain to maintain                                       | `fe/`'s lockfile moves; every npm path in CI and the local scripts changes |

`@angular/build:application` emits an independent output per project; nothing is shared at run
time. The one thing the two projects share at build time is `fe/styles/` and the dependency set.

## Separation guarantees

Five layers, the first of which is structural:

1. **The image build cannot see the administration app.** `ui-apps/info/Dockerfile` builds from
   context `fe/` with an **allow-list** `Dockerfile.dockerignore` (`*` first, then `!` re-includes)
   — a deny-list would let every new file at the workspace root into the context. Re-included and
   copied: `package.json`, the lockfile, `.npmrc` (the peer-resolution setting `npm ci` needs),
   `.postcssrc.json` (without it Tailwind is not processed and the CSS comes out wrong),
   `angular.json`, `tsconfig.json`, `styles/`, `shared-map/`, `shared-html/` and `ui-apps/info/`.
   `angular.json` may
   keep declaring the administration project: building one project does not need the other's
   files on disk. CI builds the image from a clean checkout for both countries, so a missing
   input fails there and not at release. Any import that reaches `ui-apps/admin/` does not resolve,
   and the build fails.
2. **Compiler scope — tidiness, not a boundary.** `ui-apps/info/tsconfig.app.json` includes
   `ui-apps/info/src/**` and the two shared source folders, `shared-map/` and `shared-html/`,
   which it reaches through the two aliases the root `tsconfig.json` declares; there are no
   barrels. `include` names the entry files; TypeScript still follows any relative import out of
   the folder, so this layer stops nothing on its own. Layers 1, 3 and 4 are the boundary.
3. **Lint.** One `fe/eslint.config.js`, every glob written with its full application prefix — a
   per-application config that spreads the root one would re-base the administration globs onto
   niord-info's identically named folders. The `ui-apps/info/**` block bans `**/ui-apps/admin/**`,
   its relative spelling (`../../../../admin/…`) and `keycloak-js`, in a static import and in a
   dynamic `import()` alike, and carries its own layer rules (`shared` and `core` never import
   `features`; only `app.config.ts` imports a country folder, and only `default/`). The mirror rule
   bans the public site from `ui-apps/admin/**` in the same spellings. A stale glob stops applying
   without an error, so CI lints a crafted import against every layer and expects each to be
   rejected (`npm run test:guards`).
4. **Bundle check in CI.** Module paths in the build's `stats.json` are workspace-relative, so the
   check is exact: every first-party module must start with `ui-apps/info/`, `styles/`,
   `shared-map/` or `shared-html/`; anything starting with `ui-apps/admin/` or `../` fails, and so
   do `keycloak-js` and `@angular/forms/signals`.
5. **Tailwind scan scope.** Tailwind v4 detects sources from the working directory, which here is
   the whole workspace. Both entry stylesheets must pin the scan
   (`@import 'tailwindcss' source('./app')` or the equivalent `@source` form) so that neither
   application emits utilities for classes that only the other one uses. This is a separation
   rule and a bundle-size rule at once.

At run time the container's nginx is an allow-list on **method and path**, not path alone — the
backend serves an anonymous `GET` and an editor-only `DELETE` on the same `/rest/repo/file/…`
path, and `/rest/repo/` also carries upload and listing endpoints:

- only `GET` and `HEAD` are proxied (`limit_except GET HEAD { deny all; }`); anything else is refused;
- only `/rest/public/v1/`, `/rest/settings/web`, `/rest/repo/file/`,
  `/rest/message-reports/report.pdf`, `/rest/message-reports/message/<id>.pdf` and `/wms/` are
  proxied — the two report paths named one by one, each taking only the query the site's print
  addresses carry; any other path under `/rest/` is 404, including `/rest/repo/list/`,
  `/rest/repo/upload/` and the rest of `/rest/message-reports/`, and so is `/auth/`;
- `Authorization`, `Proxy-Authorization`, `Cookie` and `NiordDomain` request headers are emptied
  before proxying and `Set-Cookie` is hidden on the way back, so a credential that reaches the
  public host is never forwarded.

The backend's own role checks remain the real protection; the proxy makes the public host unable
to carry an administration request at all. A container test in CI proves it against the built
image with a stub upstream: `DELETE /rest/repo/file/x`, `POST /rest/repo/upload/x`,
`GET /rest/messages/search` and `GET /rest/repo/list/x` are refused without reaching the stub, and
a permitted `GET` sent with an `Authorization` header arrives at the stub without it. The same
test holds the security headers, the report locations' query allow-lists and the entrypoint's
start-up checks; the whole contract is in [`ui-apps/info/AGENTS.md`](../ui-apps/info/AGENTS.md).

## The map

One OpenLayers component for both applications, so that a performance fix is made once.

The administration app's map was tuned well beyond a stock OpenLayers setup: a tile-load gate
that holds tile fetches while a zoom or pan is in flight (roughly a tenth of the tile requests on
a fast gesture — the saving that matters most on a ship's link), debounced resize and move-end
handling, view-change tolerance so programmatic and user moves do not echo, and layers as
content-projected components sharing one map shell. niord-info has none of this: its map is one
627-line component that builds the OpenLayers map itself, debounces with `setTimeout`, uses
`NgZone`, and also renders the message id badge, builds tooltip HTML and opens the details dialog.

**What is shared — `fe/shared-map/`** (about 850 lines plus their specs when it was extracted, all
free of application imports): `ol-map.ts`, `map-shell.service.ts`, `tile-load-gate.ts`,
`map-projection.service.ts`, `layers/` (tile, WMS, OpenSeaMap) and `ol-layer-switcher.ts`. The
S-124 portrayal (`map-styles.ts`, its token reader and the symbol files) and the pointer handling
(`hit-detection.ts`, `map-hover.ts`) joined it later, so a message is drawn and hit the same way in
both applications; [architecture.md](architecture.md) → _The map layer_ lists the kit as it is.

**What stays in the administration app:** `base-map-layers.ts` and `wms-layer-visibility.ts`
(they read `AuthFacade` and `APP_CONFIG`), `map-view-defaults.ts` (domain defaults), the
coordinate readout, the geometry editor and every message layer.
**What stays in niord-info:** the message features layer the chart, the message dialog's map and
the teaser draw with (`shared/components/message-features-layer/`), and the labels, the tooltip,
the no-position list and the click that opens the details dialog, under `features/messages/`.

Rules that keep this from becoming a back door:

- `fe/shared-map/` is a source folder, not a packaged library. Both applications reach it through
  a `paths` alias, `@niord/map`, because a relative import across project roots would be
  unreadable; the message-HTML sanitiser in `fe/shared-html/` came later with the second and last
  alias, `@niord/html`.
- Its ESLint block allows `@angular/*`, `ol/*` and `rxjs` (and `vitest` in its specs) and nothing
  from `ui-apps/admin/` or `ui-apps/info/`. It may not inject anything an application defines;
  what differs between the two is an input. It has its own specs and runs in both CI workflows.
- The WMS layer fetches tiles through `HttpClient` so that the administration app's credentials
  attach, and has a plain image-loading mode, which the anonymous site uses — its WMS goes through
  the container's `/wms/` proxy.
- The kit cannot translate, so the layer switcher's heading, a layer's name and the tile layer's
  credits arrive as text each application has translated — the one sanctioned exception to
  translation ownership ([angular-conventions.md](angular-conventions.md) → _Translation
  ownership_).
- OpenLayers is imported by module path in the kit, never from the `ol` barrel, and niord-info
  imports only the kit files it uses. The bundle report decides: the kit must not make the map
  chunk larger than today's 85 kB transfer.

- **Tuning is not finished, and later rounds must reach both applications.** A change to map
  behaviour or performance — tile loading, rendering, gestures, resize, view handling, layer
  plumbing — is made in `fe/shared-map/` and nowhere else. Neither application carries its own
  copy or a local workaround of something the kit does; if a tweak only suits one of them, it
  becomes an input on the kit with the other application's behaviour as the default. A change
  under `fe/shared-map/` runs both CI gates, and the tile-request count for a scripted zoom and
  pan is measured for both applications, so a tweak that helps one and hurts the other is seen.

Order: niord-info's map comes in unchanged (group A) with the teaser's import of it as one named
lint exception. The kit is extracted from the administration app as a pure move (group C). Then
niord-info's map is rebuilt as a thin `message-map` on `<app-ol-map>` (group D), which is also
where its `NgZone`, `setTimeout` and `innerHTML` tooltip go. The teaser then uses the kit with a
small feature layer, and the lint exception is removed.

## Country variants

Follows [country-deployments.md](country-deployments.md) point for point.

| Swap point | Administration app today                                                                     | niord-info                                                                                         |
| ---------- | -------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| TypeScript | `fileReplacements` on `country-specific/default/{language.config,promulgation.providers}.ts` | `fileReplacements` on `country-specific/default/{info.config,language.config}.ts`                  |
| Brand      | `styles: ["src/styles.css", "src/styles/tokens.active.<cc>.css"]`                            | `styles: ["ui-apps/info/src/styles.css", "styles/tokens.active.<cc>.css"]` — the same token files  |
| Assets     | `public/assets/gl` overlaid on `assets` for GL                                               | `public/assets/<cc>` overlaid on `assets` for DK and GL: branding JSON, legal HTML, logos, favicon |

`info.config.ts` carries the country's part of what `provideNiordInfo({...})` takes, the
`InfoConfig` fields: `timeZone`, `executionMode`, `wmsLayer`, `rootAreas`, `footerComponent`,
`languageFlags`, `accessibilityStatement` and `backendHosts`. `language.config.ts` carries the
fallback language list used when the settings endpoint cannot be reached, typed like the
administration app's `LanguageConfig`.

|                    | default               | dk                                                      | gl                       |
| ------------------ | --------------------- | ------------------------------------------------------- | ------------------------ |
| Fallback languages | `en`                  | `da`, `en`                                              | `kl`, `da`, `en`         |
| Fallback default   | `en`                  | `da`                                                    | `kl`                     |
| Root areas         | none (whole data set) | `urn:mrn:iho:country:dk` (+ `gl` while DMA serves both) | `urn:mrn:iho:country:gl` |
| Time zone          | from the country file | `Europe/Copenhagen`                                     | `America/Nuuk`           |
| Brand tokens       | `tokens.default.css`  | `tokens.dk.css`                                         | `tokens.gl.css`          |

Because comma-composed configurations merge shallowly, the project gets standalone
configurations — `production`, `dk`, `gl`, `local`, `local-dk`, `local-gl` — the same shape and
the same trap as documented for the administration app.

**The four legal dialogs.** Today they are four components, one per document and language, picked
by `language() === 'da' ? Da : En`. That cannot serve a third language and puts country text in
code. They become one dialog component that loads
`assets/legal/<document>.<lang>.html` — country content in the country's asset overlay, any number
of languages, no text changed. Greenland uses the Danish legal texts, translated, until its owner supplies its own; the wording
is reviewed before go-live because the texts name the Danish maritime area.

## Site language

The administration app reads `siteLanguages`, `modelLanguages` and `defaultLanguage` from
`GET /rest/settings/web` (`@PermitAll`, flat map of every setting with `web: true`), and falls
back to the country file. niord-info does the same with its own keys:

| Setting               | Type      | DK            | GL                 |
| --------------------- | --------- | ------------- | ------------------ |
| `infoSiteLanguages`   | json list | `["da","en"]` | `["kl","da","en"]` |
| `infoDefaultLanguage` | string    | `da`          | `kl`               |

Backend work: one entry per key, `web: true, editable: true`, in `be/niord`, `be/niord-dk` and
`be/niord-gl` `niord.json`. No Liquibase changeset — `SettingsService.init()` persists keys that
are absent from the database at boot. Two things to know: a `niord.json` value never updates an
existing row (later changes go through the sysadmin Settings page), and
`be/niord-gl/scripts/seed-local.mjs` upserts a fixed key list that the two new keys join.
`siteLanguages` is read by no backend code, and neither will these be.

Frontend: a copy of `resolveLanguages()` in `app/core/services/` reading the two `info*` keys,
run from `provideAppInitializer` with the same 5 s timeout and swallow-and-fall-back behaviour.
A language is offered only when it is in the setting **and** has a shipped bundle
(`en`, `da`, `kl`), so a setting cannot switch the site to a language it has no texts for.
A visit starts in the language its address names, then the one the reader chose on the switcher,
then the resolved default, each only if the site offers it; only a choice made on the switcher is
remembered, so a later change of `infoDefaultLanguage` reaches every reader who never chose.

The same request also delivers `mode`, which replaces `runtime-config.json` and the
`EXECUTION_MODE` environment variable: one source for the DEV/TEST ribbon instead of two, and
the `jq` entrypoint script goes.

## Translations

- The library's English defaults live in TypeScript (`i18n/default-translations.ts`, 64 keys);
  Danish exists only in the DK shell (`assets/i18n/da.json`); the shell's `en.json` is `{}`.
  They become three complete JSON bundles with a parity spec. **Keys stay as they are**
  (`MENU_NW`, `PART_TYPE_DETAILS`, …): several are composed from backend enums, and renaming 69
  keys would churn every text and the translation workbook for no user benefit.
- The loader keeps its two-step merge: `i18n/<lang>.json`, then the country's
  `branding/<lang>.json` (the authority's name, the footer's words and logo, the map's credits and
  the legal dialogs' titles).
- **Month and weekday names live in the bundles**, in all three languages, under four keys
  (`DATE_MONTHS_WIDE`, `DATE_MONTHS_ABBREVIATED`, `DATE_DAYS_WIDE`, `DATE_DAYS_SHORT`, each a
  `|`-separated list). They are the words the site shows today, not new texts. This is their one
  home from the first task group to the last: `date-fns` has no `kl` locale, and `Intl` cannot be
  relied on for it — Node's full ICU knows `kl`, but browsers ship reduced locale data and fall
  back silently. While `date-fns` remains, a small adapter builds its `Locale` from the four
  keys; when it is removed, the zoned-date helper reads the same keys and uses `Intl` only for
  zone arithmetic and numeric parts. The translator's values land in `kl.json` and survive the
  date-library change; the workbook's apply recipe says so.
- Hard-coded strings to move behind keys — all required, none invented: five screen-reader labels
  in the details dialog, three in the main menu, one in the detail card, the `back` button, two
  print-view load errors, the static `<title>`. `Message … is not available` is replaced by the
  existing, unused key `MSG_NOT_FOUND`.
- Removed: six keys nothing renders (`MENU_PUBLICATIONS`, `GENERAL_MSGS`, `ACTIVE_DATE`,
  `DATE_FROM`, `DATE_TO`, `REMOVE_PUBLICATION`).

### Export for the Greenlandic translation

Delivered outside the repository: `niord-info-tekster-til-oversaettelse-kl-2026-09-21.xlsx`, in
the layout of the database-text workbook (sheets Vejledning / Tekststrenge / Anvendelse /
Oversigt, the same 14 columns, yellow `Oversættelse` column, machine key and apply recipe per row).

| Category                               | Rows    | Danish words | English words |
| -------------------------------------- | ------- | ------------ | ------------- |
| Brugerflade (`info-ui:`)               | 56      | 94           | 97            |
| Branding (`info-brand:`)               | 7       | 11           | 17            |
| Hard-coded, to be keyed (`info-code:`) | 12      | 0            | 28            |
| Page title (`info-html:`)              | 1       | 0            | 6             |
| Legal dialogs (`info-legal:`)          | 28      | 453          | 487           |
| Date names (`info-ui:DATE_*`)          | 4       | —            | —             |
| **Total**                              | **108** | **562**      | **639**       |

A fifth sheet, Udeladt, lists the 23 rows that are not translated (unused keys, `(T)` / `(P)` /
`INT` / DEV / TEST markers, format codes, the developer panel, the OpenStreetMap attribution),
each with its reason. Message content, area names and publication titles come from the backend
and are covered by the database-text workbook. The inventory and the two generator scripts are
kept beside the workbook so it can be regenerated.

## Bundle size

Measured on the current DK shell (production build, Angular 21.2.6, 2026-09-21):

|                                                                       | Raw        | Transfer        |
| --------------------------------------------------------------------- | ---------- | --------------- |
| Initial JavaScript (10 chunks)                                        | 349 kB     | 99 kB           |
| Initial CSS — all of Bootstrap, bootstrap-icons, OpenLayers, app SCSS | 327 kB     | 37 kB           |
| **Initial total**                                                     | **676 kB** | **136 kB**      |
| Map chunk (OpenLayers) — loaded by the map view                       | 347 kB     | 85 kB           |
| Publications chunk (date-range picker, dayjs)                         | 135 kB     | 29 kB           |
| bootstrap-icons webfont (`woff2`) — for 14 icons                      | 134 kB     | 134 kB          |
| All JavaScript, 26 files                                              | 893 kB     | 234 kB (brotli) |

**A first visit to the message map costs about 360 kB**, of which the icon font is more than a
third. That is the number to defend.

| Change                                                                   | Expected effect on a first visit |
| ------------------------------------------------------------------------ | -------------------------------- |
| bootstrap-icons font → 14 inline Lucide icons                            | −130 kB                          |
| Bootstrap CSS → tokens + scanned Tailwind utilities + core classes       | −20 to −25 kB                    |
| ng-bootstrap (modal, nav) → `@angular/cdk` dialog + `@angular/aria` tabs | about even                       |
| date-range picker + dayjs → two native date inputs                       | −25 kB on Publications           |
| date-fns + date-fns-tz → `Intl` with the configured zone                 | −10 to −15 kB                    |
| Angular 21 → 22                                                          | small, unmeasured                |

Measured after each task group (first visit of the message map, transfer):

| After                                                                          | First visit   | Initial     | Note                                                                                                     |
| ------------------------------------------------------------------------------ | ------------- | ----------- | -------------------------------------------------------------------------------------------------------- |
| baseline (upstream build, Angular 21)                                          | 360 583 B     | 135.6 kB    | the number to defend                                                                                     |
| group A (imported, Angular 22.1)                                               | 359 582 B     | 134.4 kB    | the Angular 22 runtime is about 6 kB raw heavier, compression evens it out                               |
| group B (countries, settings, bundles, container)                              | 352 595 B     | 128.8 kB    | country-neutral build; the Danish artefact is 354 437 B, and the container puts exactly that on the wire |
| group C (shared styles, map kit, off Bootstrap)                                | **187 771 B** | **96.4 kB** | −48 %: icon font gone (−134 kB), Bootstrap gone, dialog kept out of the first visit; both targets met    |
| group D (conventions: zoneless, zoned dates, resources, map on the kit, types) | 200 235 B     | 100.6 kB    | +12.5 kB, every byte measured — see the decision below; still −44.5 % and both targets met               |

Every row above measures the message map. The site's root and every unknown path redirect to the
message list, `/messages/details`, and that list draws no map, so the checked-in baseline was
re-pointed at the route a first visit really lands on: **115 636 B**, that is 103.1 kB initial
plus 12.6 kB of the list's own lazy chunks. The map view is a second visit; the `anyScript` budget
in `angular.json` still caps its OpenLayers chunk.

Target: **first visit ≤ 230 kB, initial ≤ 110 kB transfer**; never above the baseline at any
step. Enforcement:

- `budgets` on the project in `angular.json` (initial, any lazy chunk, component style) set from
  the measured values after each step — the build fails, not warns, above them.
- `scripts/info-bundle-report.mjs`: brotli size of the first-visit set (index, initial chunks,
  CSS, the lazy chunks of the route the root lands on, fonts) written to the CI summary and
  compared with a checked-in `ui-apps/info/bundle-baseline.json`; a rise above 2 % of a group, or
  above 64 bytes where that is more, fails the job.
- Measured and recorded in this document after every task group below.
- Outside the measured set: the tab icon every page names and a country build's footer logo,
  which a first visit fetches as well. They are held to their drawn size instead — the icon at
  32 px, each logo at twice the 22 px the footer draws it at — by
  `country-specific/branding-images.spec.ts`; the 152 px home-screen icon is fetched only by a
  phone that saves the site.
- Rules that protect it: no component library, no icon font, no webfont, no second date library,
  OpenLayers imported by module path only, the core class file sliced so that no
  administration-only family (`.ac-*`, `.sel-*`, `.tree-*`, `.picker-*`, drawer, toast) ships,
  Tailwind scan pinned to the project.
- nginx serves pre-compressed brotli and gzip, `immutable` on hashed files, `no-cache` on
  `index.html`. A service worker is not added now; whether ships benefit from offline reading of
  the last fetched notices is a product question, not a port task.

## Angular 22 and conventions — where the code stands

Already in the target shape: signal `input()` / `output()` / `viewChild()`, `inject()` everywhere,
no decorators, no `any`, native control flow, lazy routes, `app-` selectors, tsconfig flags equal
to the administration app's, no zone.js in the application.

| Finding                                                                                                                                                                           | Evidence                                                                                                      | Kind                                            | Size                     |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- | ----------------------------------------------- | ------------------------ |
| Angular 21.2.6 → 22.1, TypeScript 5.9 → 6.0                                                                                                                                       | both `package.json`                                                                                           | required                                        | M                        |
| `ChangeDetectionStrategy.OnPush` annotation                                                                                                                                       | every component (22 after the four legal dialogs became one)                                                  | convention                                      | S                        |
| `NgZone` injected and used                                                                                                                                                        | `message-map.component.ts:123,185,349,432,496`                                                                | required for zoneless                           | M                        |
| **Dates rendered in the browser's zone**                                                                                                                                          | `message-source.component.ts:41-42`, `format-date.pipe.ts:11-12`                                              | **live defect**                                 | M                        |
| Browser-local `Date` reads                                                                                                                                                        | `publications.component.ts:135`, `default-footer.component.ts:15`                                             | lint failure                                    | S                        |
| Three date libraries                                                                                                                                                              | date-fns, date-fns-tz, dayjs                                                                                  | bundle + convention                             | M                        |
| No `httpResource`: six reactive GETs on `HttpClient` + `subscribe`                                                                                                                | `message.service.ts:16,20`, `publication.service.ts:11`, `messages.component.ts:80`, `teaser.component.ts:56` | convention                                      | L                        |
| `effect()` used to fetch, one with a mutable skip flag                                                                                                                            | `messages.component.ts:42-50`, `publications.component.ts:146-161`                                            | convention                                      | M                        |
| Server data held on a route parent that three child views inject                                                                                                                  | `messages.component.ts:53,60,61`; `inject(MessagesComponent)` ×3                                              | convention (no data beyond the view)            | L                        |
| Duplicate teardown (`Subscription` fields beside `takeUntilDestroyed`)                                                                                                            | `messages.component.ts:38,162`, `publications.component.ts:106,184,201`                                       | convention                                      | S                        |
| `[(ngModel)]` on a plain field, `$any($event)`                                                                                                                                    | `publications.component.ts:70-71,114`                                                                         | convention                                      | S (goes with the picker) |
| Wire types (`…Vo`) used as models, mutable, UI state written onto them                                                                                                            | `message.model.ts:23`, `publications.component.ts:227`                                                        | convention                                      | M                        |
| 15 non-null assertions, `\| string`-widened unions, 2 `Array<>`, one barrel                                                                                                       | `message-map.component.ts` mostly                                                                             | convention                                      | S                        |
| Cross-feature import teaser → messages map                                                                                                                                        | `teaser.component.ts:7`                                                                                       | lint — named exception until the shared map kit | S                        |
| `SafeHtmlPipe` (`bypassSecurityTrustHtml`) at 9 sites, one `innerHTML =` string                                                                                                   | `message-detail-card…:102`, `message-map…:361`                                                                | security review                                 | M                        |
| Message id badge built as an HTML string                                                                                                                                          | `message.service.ts:42`                                                                                       | convention — becomes markup on `.status-badge`  | S                        |
| `getPublication()` calls a path no backend serves; nothing calls it                                                                                                               | `publication.service.ts:16` vs `ApiRestService` `/publication/{id}`                                           | dead code — remove                              | S                        |
| `publications.routes.ts` unused; docs name symbols that do not exist                                                                                                              | —                                                                                                             | cosmetic                                        | S                        |
| No ESLint, no format check upstream                                                                                                                                               | —                                                                                                             | the workspace's config applies                  | S                        |
| Specs: zone setup, analog plugin, 27 `detectChanges()` flushes                                                                                                                    | `test-setup.ts:1`                                                                                             | required for the suite                          | L                        |
| Accessibility: 8 `<span role="button">` without Space, state by colour only, focus ring removed (`.btn:focus { outline: none !important }`), layout tables, `alt` = language code | `_app.scss:149` and templates                                                                                 | WCAG AA                                         | M                        |

Angular-22-compatible releases of `@ng-bootstrap/ng-bootstrap` (21.0.0) and
`ngx-daterangepicker-bootstrap` (22.5.0) exist, which allows the version upgrade to land **before**
the restyle with the application working at every step.

One deliberate difference from the administration app: the time zone stays a country value
(`info.config.ts`). The administration app takes it from the signed-in user's domain; an anonymous
site has no domain. The rule that survives is "never the browser's zone", and the zoned-date
helpers are copied, not imported.

## From Bootstrap to the niord-ng design

The Bootstrap surface is small — 96 distinct classes in 180 places in the library, 20 in 59 in
the shell, three ng-bootstrap APIs, 14 icons, one date-range picker — but the shell imports the
**whole** framework for it.

| Element                                                                 | Becomes                                                                                                                          | Shared or own                 | Size                                                                                |
| ----------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- | ----------------------------- | ----------------------------------------------------------------------------------- |
| Tokens, theme bridge                                                    | `fe/styles/`                                                                                                                     | shared                        | M (extraction, touches the administration app's `styles[]` and docs)                |
| Rendered message HTML (`.message-description`, `_message.scss:120-192`) | `.message-details-html` in `message-html.css` — near-identical today                                                             | shared                        | S, but snapshot real messages first                                                 |
| Grid, utilities (`row`, `col-*`, `p-0`, `float-end`)                    | Tailwind utilities                                                                                                               | own                           | M                                                                                   |
| Buttons (44 sites)                                                      | `.btn-primary`, `.link-button`, `.icon-button`                                                                                   | shared classes                | S                                                                                   |
| Icons (14 `bi-*`)                                                       | `@ng-icons/lucide`, per-icon import                                                                                              | own                           | S                                                                                   |
| Tabs (`ngbNav`, 1 site)                                                 | own small tabs on `@angular/aria` + `.tabs*` classes                                                                             | own component, shared classes | S                                                                                   |
| Modals (`NgbModal`, 6 dialogs)                                          | own dialog on `@angular/cdk/dialog` + `.dialog-*` classes                                                                        | own component, shared classes | M                                                                                   |
| Date-range picker                                                       | two native `<input type="date">` with the existing labels                                                                        | own                           | S–M                                                                                 |
| Navbar, language switcher, DEV/TEST ribbon                              | `--color-chrome*` tokens                                                                                                         | own                           | M                                                                                   |
| Filter bar                                                              | real `<button aria-pressed>` on `.chip` / `.seg`                                                                                 | shared classes                | S                                                                                   |
| Message id badge                                                        | markup on `.status-badge` + `.is-*`                                                                                              | shared classes                | S                                                                                   |
| Tables, cards                                                           | `.niord-table`, `.message-table`, `.row-*`, card classes                                                                         | shared classes                | M                                                                                   |
| Footer, teaser                                                          | tokens                                                                                                                           | own                           | S                                                                                   |
| Map chrome (layer switcher, tooltip, general-messages panel)            | the shared map kit; tokens through a copied `readCssVar()`; S-124 hues from `tokens.base.css` so both apps draw the same markers | own                           | M                                                                                   |
| Print view                                                              | stays a client-rendered route with its own print CSS                                                                             | own                           | L — 80 lines of `!important` overrides were written against Bootstrap's print sheet |
| Attachments                                                             | `.attach-link`                                                                                                                   | shared class                  | S                                                                                   |

`components.css` (2 475 lines) is split: the families above go to `components-core.css`, the
overlay, form, tree, picker, drawer and toast families stay with the administration app.
Risks to test, not assume: print parity with the current site, position tables in message text
(units and border colour differ slightly between the two stylesheets), the three fixed header
offsets on mobile, and the touch-target floor that arrives with the shared classes.

## Deployment

```
browser ──> niord-info container (nginx, static files)
              /                      index.html, hashed chunks, assets   (brotli, immutable)
              /rest/public/v1/   ──> NIORD_SERVER_URL                    (allow-list)
              /rest/settings/web ──> NIORD_SERVER_URL
              /rest/repo/file/   ──> NIORD_SERVER_URL                    (attachments, images in message HTML)
              /rest/message-reports/report.pdf         ──> NIORD_SERVER_URL   (the printed list)
              /rest/message-reports/message/<id>.pdf   ──> NIORD_SERVER_URL   (one printed message)
              /wms/              ──> WMS_SERVER_URL                      (credentials stay server-side)
              everything else under /rest/ → 404
```

- The `/api` prefix and `apiBaseUrlInterceptor` go; the application calls `/rest/…` like the rest
  of the repository. Same origin, so no CORS anywhere.
- **Attachments and embedded images need an explicit URL contract.** The public API defaults to
  `externalize=true`, which rewrites every link in a message to an absolute URL on the backend's
  configured base URI — another origin than the public host, and one a ship may not reach.
  niord-info requests `externalize=false` on `/messages` and `/message/{id}` and gets
  root-relative `/rest/repo/file/…` paths, which the container proxies (the current nginx does
  not, so they fall through to `index.html`). One normalising function in `core/services` is the
  only place that touches these URLs: it leaves root-relative paths alone and reduces an absolute
  URL whose path starts with `/rest/repo/file/` to that path, because stored HTML can contain
  absolute links from earlier saves. It does so for the site's own host and the backend's public
  hosts the country configuration lists; another deployment's repository link is left as written.
  The publications endpoint takes the same parameter and is
  asked the same way; its `link` values go through the same function, and attachments — which
  the backend returns as bare repository paths — get the prefix from it. Covered by specs on the function and by the
  parity check: an image inside message HTML, an attachment and a publication link must all load
  from the public host's own origin.
- The permanent redirects for the previous site's URLs (`/teaser.html`, `/details.html`) and the
  hash-URL redirect stay.
- The current template's three-minute `proxy_cache_valid` on the public API is carried over as it
  is. It names no cache zone, so it is most likely inert; revisit it after go-live.
- One image per country: build argument `COUNTRY=dk|gl` selects the configuration. Image names
  follow the CI/CD design (`niord-dk-info`, `niord-gl-info`), one Helm deployment and ingress host
  each. The public host is a separate origin from the administration app, so `localStorage` is
  not shared; the keys are namespaced (`niord-info.language`, …) regardless.
- CI: `.github/workflows/info-ci.yml`, built like `fe-ci.yml`: `pull_request` is **not**
  path-filtered; a `changes` job decides, work jobs are skipped with a job-level `if:`, and an
  always-running **Info gate** is the second check for branch protection to require (it is
  switched on before production go-live; until then both gates are advisory). The change filter
  covers `fe/ui-apps/info/**`, the three shared folders, `fe/package*.json`, `fe/.npmrc`,
  `fe/.nvmrc`, `fe/.postcssrc.json`, the Prettier configuration and `fe/.editorconfig`,
  `fe/angular.json`, `fe/tsconfig*.json`, `fe/eslint.config.js`, `fe/vitest.config.ts`, the
  `info-*` scripts, the paths guard and the guard tests under `fe/scripts/`, and the workflow file
  itself; a moved file counts under both its paths. Jobs: format, lint with the guard tests, unit
  tests, build `dk` and build `gl` each with the layout check, paths check, bundle report,
  separation check, image build for both countries, container proxy test.
- **Every command names its project.** With one project, `ng test`, `ng lint` and `ng build`
  need no name; with two, a bare `ng test` runs every test target, which would put niord-info's
  specs into the administration shards and their `--list-tests` discovery. In the same change
  that adds the project, the scripts in `fe/package.json` and `scripts/ci-test-shard.mjs` are
  pinned to `niord-app`, the new `*:info` scripts to `niord-info`, and `npm run verify` gets a
  `verify:info` sibling. `fe-ci.yml` keeps filtering on `fe/`, so a change to `fe/styles/` reruns
  the administration checks too.
- Local run: `npm run start:info`, `start:info:dk`, `start:info:gl` on port `4400 + slot`;
  `start-local.sh --info` and `new-worktree.sh` learn the port.

## Tasks

Ordered so the application builds and runs after every group, and the bundle is measured at the
end of each.

**0. Move the administration app to `fe/admin-app/` — move-only, before anything else**

- [x] Baseline on the untouched tree: `ng test niord-app --list-tests` count, `playwright test --list`, the file listings of the three production builds (default, `dk`, `gl`) with `ngsw.json`, and `eslint --print-config` for one file per layer block
- [x] Commit 1: a pure `git mv` of `src/`, `public/`, `e2e/`, `tsconfig.app.json`, `tsconfig.spec.json`, `ngsw-config.json` — zero content edits, every entry a rename
- [x] Commit 2: configuration — `angular.json` (`root`, `sourceRoot` and every path string), `tsconfig.json` references, the moved tsconfigs' `extends` / `outDir`, the `ngsw-config.json` schema path, the ESLint globs, `playwright.config.ts`, `transloco.config.ts`; `newProjectRoot` set to the workspace root
- [x] Commit 3: documents and agent rules — an anchored replacement of path tokens in `fe/docs`, `fe/README.md`, the root `CLAUDE.md` (never REST `/public/` URLs, the go-live set, `DATA-MODEL.md` or backend paths; `API-CONTRACT.md` by hand, line count kept); `fe/CLAUDE.md` split into workspace rules and `admin-app/CLAUDE.md`; the documentation guard covers the new file
- [x] Guards against the paths that build green when stale: no string in `angular.json` may start with `src/`, `public` or `tsconfig.`; `eslint --print-config` per layer equals the baseline; the `dk` and `gl` bundles contain their country language marker
- [x] Verification by hand, beyond the CI gate: `npm run verify`, the three builds with listings equal to the baseline, `docs:check`, the publications specification check, the shard dry run, equal test and e2e counts, one local start

**A. Bring it in, unchanged in behaviour**

- [x] First, the experiment everything else rests on: a throw-away niord-info project built in a Docker context without `admin-app/` (real `npm ci`, `tsconfig.json` still referencing the absent project)
- [x] `format:check` and `lint` scoped per application; no generated per-application ESLint config

- [x] Record the two source commits; copy library and shell into `fe/info-app/` in the layout above (no `lib` folder), keeping history notes in this document
- [x] Add the `niord-info` project to `angular.json` with its own tsconfig files; remove ng-packagr, `public-api.ts`, the barrel, the `paths` alias, the submodule workflow
- [x] Angular 22.1 / TypeScript 6.0 with `@ng-bootstrap` 21 and the date-range picker 22.5 as temporary dependencies; fix compile and deprecation errors
- [x] Specs onto the workspace's unit-test builder: drop the analog plugin and zone setup, `detectChanges()` → `whenStable()`, keep the canvas stub
- [x] Pin every existing npm script and `ci-test-shard.mjs` to `niord-app`; add the `*:info` scripts
- [x] ESLint block with the separation and layer rules. The map component comes in where it is, in `features/messages/`; the teaser's import of it is the one named exception in the cross-feature rule until the shared map kit replaces it
- [x] `/api/rest/…` → `/rest/…`, delete the interceptor and the dead `getPublication()`; dev proxy for `/rest` and `/wms`
- [x] Bundle report script, `bundle-baseline.json` from the measured baseline, budgets

**B. Platform: countries, languages, container**

- [x] `country-specific/{default,dk,gl}` with `info.config.ts` + `language.config.ts`; configurations `production`, `dk`, `gl`, `local*`; asset overlay per country
- [x] Backend: `infoSiteLanguages`, `infoDefaultLanguage` in the three `niord.json` files and the GL seed script
- [x] Settings initializer (languages + `mode`); remove `runtime-config.json` and the entrypoint script
- [x] `en.json` from the TypeScript defaults, `da.json` from the shell, `kl.json` with temporary machine-made values and `kl-unverified.json` listing every such key; parity spec and a spec that the list matches the bundle; remove the six unused keys
- [x] Hard-coded strings behind keys (the twelve required ones; `MSG_NOT_FOUND` reused); language-aware `<title>`
- [x] One legal dialog reading `assets/legal/<document>.<lang>.html`; delete the four per-language components
- [x] Dockerfile + dockerignore (with `.npmrc` and `.postcssrc.json`) + nginx template (method and path allow-list, credential headers emptied, brotli, redirects); clean image build for both countries
- [x] Container proxy test: administration requests and forwarded credentials are refused
- [x] `externalize=false` and the URL-normalising function, with specs for message images, attachments and publication links
- [x] `info-ci.yml` with unfiltered `pull_request`, the `changes` job and the Info gate; local scripts and ports
- [x] Date names as four bundle keys in `en`, `da`, `kl`; adapter that builds the `date-fns` locale from them
- [x] GL variant: root area, zone, flags, brand assets
- [x] Language acceptance specs: with the UI in `kl` and a message that has only `da` / `en` content, the content is shown in the fallback language and its element carries that language's `lang` attribute while `<html lang>` stays `kl`; the same for area names and publication titles

**C. Shared styling, then off Bootstrap**

- [x] Extract `fe/styles/` (tokens, theme bridge, `components-core.css`, `message-html.css`); repoint the administration app; pin both Tailwind scans; update [styling-reference.md](styling-reference.md) and [country-deployments.md](country-deployments.md)
- [x] Message HTML onto the shared stylesheet, with before/after snapshots of real messages
- [x] Grid, buttons, utilities, icons; remove Bootstrap SCSS and the icon font
- [x] Own dialog and tabs; remove `@ng-bootstrap`
- [x] Native date inputs; remove the date-range picker, dayjs, `FormsModule`. The input's own chrome (field order, calendar popup) follows the **browser** language, not the site language — accepted, the labels stay ours; checked with a Danish browser on the `kl` and `en` site
- [x] Navbar, filter bar, badge, tables, cards, footer, teaser, map chrome
- [x] Print view parity against the current site; mobile offsets; accessibility pass (buttons, `aria-pressed`, focus ring, table roles, alt texts)

- [x] Extract `fe/shared-map/` from the administration app as a pure move: the `@niord/map` alias, its ESLint block, specs running in both workflows, Docker copy list and separation check updated, module-path OpenLayers imports, plain-image mode on the WMS layer; [architecture.md](architecture.md) updated

**D. Conventions**

- [x] niord-info's map rebuilt as a thin `message-map` on the shared `<app-ol-map>` (tile-load gate, shared shell, layer components); teaser on the kit; lint exception removed; map chunk measured against the 85 kB baseline

- [x] Remove the `OnPush` annotations (22 remain) and `NgZone`
- [x] Zoned-date helpers reading the four date-name keys; fix the two browser-zone renderings; remove date-fns, date-fns-tz and the locale adapter
- [x] Six GETs onto `httpResource`; remove fetching effects, `Subscription` fields and the parent-component injection
- [x] Wire types private beside their services, `readonly` models, UI state off the wire objects, assertions and unions
- [x] `SafeHtmlPipe` review: sanitised rendering for backend HTML, no string-built `innerHTML`
- [x] Route `title` keys from existing menu keys; split `MessageService`

**E. Greenlandic and go-live**

- [ ] Professional translations from the workbook applied to `kl.json`, `branding/kl.json`, legal HTML and the date locale; `kl-unverified.json` emptied — go-live requires it empty or signed off
- [ ] Greenland owner reviews the translated Danish legal texts
- [ ] Images published, Helm deployment and ingress per country, test environment
- [x] Parity check against the current public site (map, table, details, print, publications, teaser, redirects)
- [ ] Cut-over; upstream repositories archived. Until then fixes made upstream are ported by hand

## Decisions taken

- **Layout:** two application folders side by side in the `fe/` workspace — `admin-app/` and `info-app/`, no `projects/` layer, no `lib` folder; the administration app moves first, as a move-only change; one shared `package.json`. Dependencies listed there cost nothing in a bundle that does not import them — only the modules reachable from the project's `main.ts` ship.
- **Sharing:** tokens and CSS, plus exactly one piece of code — the OpenLayers map kit in `fe/shared-map/`, so that map performance work is done once for both applications. Everything else niord-info owns. (The follow-ups after the hand-over review added a second, the message-HTML sanitiser in `fe/shared-html/`.)
- **Folders:** not reorganised to resemble the administration app; a component moves only for a stated reason.
- **Upstream:** imported once; the two source repositories keep serving production until this version is deployed, then they are archived. Fixes made there in the meantime are ported by hand.
- **Greenland legal texts:** the Danish ones, translated, for now.
- **Greenland defaults:** `kl` from day one, `America/Nuuk`. Until the professional translations arrive, `kl` values are machine-made, plain on screen, and tracked in `kl-unverified.json`.
- **DK root areas:** Denmark and Greenland, as today.
- **API response cache:** left as it is for now.
- **Names:** images `niord-dk-info` and `niord-gl-info`; host names are deploy-time values (ingress host, `NIORD_SERVER_URL`, `WMS_SERVER_URL`).
- **Authority:** the responsible authority is now the Danish Emergency Management Agency (Beredskabsstyrelsen), not the Danish Maritime Authority. Code metadata follows this repository. Footer, logos and legal wording are country content and move verbatim; where they still name the previous authority, the replacement wording and logos are supplied by the owner, not authored here.

## Decisions taken during execution

The plan is executed without the owner at the keyboard; every decision taken along the way is
recorded here, newest last, for review at the end.

- Pull requests go group by group into the integration branch `feature/niord-info-integration`,
  never into `main`; both CI workflows now also run for pull requests into `feature/**`.
  (Withdrawn 2026-09-25 to save Actions minutes: pull requests into `feature/**` run no CI and are
  verified with `./verify-local`; see the root `CLAUDE.md`.)
- Merge mode is rebase-merge (the repository allows no merge commits), so the pure-rename commit
  of the move stays a commit of its own.
- The Angular project name of the administration app stays `niord-app`; only its folder changes.
- `fe/docs` stays a workspace-level folder although most of it documents the administration app;
  moving it would re-root the documentation guard, the format ignore list and the generator scripts.
- **The move (group 0) is done and proven equal.** 1 441 files, every one a 100 % rename in a
  commit of its own. Before and after: 476 spec files (5 152 tests pass), 184 e2e tests listed, the
  three production builds emit the same 221 / 221 / 217 files with every asset byte-identical
  except `index.html` and `ngsw.json` (they embed chunk hashes, and chunk hashes cover module
  paths), the same service-worker groups, the same country language markers, and the same
  restricted-import rules on one file per lint layer.
- The owner's local stack from an earlier day was still running and held handles on the three
  folders, so they could not be renamed as directories; the files were moved one by one instead
  (same result in git). The stack was **not** stopped — it is the owner's process — and its dev
  server now serves a stale build: **restart `start-local.sh`**.
- New guard `npm run paths:check` (first step of `verify`): every path in `angular.json` must
  exist and name an application folder. Three `public/assets/gl` asset inputs were exactly the
  kind of path a prefix replacement misses and a build does not notice.
- The documentation replacement covers bare `src/`, `public/` and `e2e/` tokens as well as the
  longer paths; backend paths, REST `/public/` URLs, the go-live set and the historical port
  inventory are untouched. `API-CONTRACT.md` was edited by hand with its line count kept.
  (m) => m
- **Group A is done: niord-info builds, lints and passes its specs inside the workspace, unchanged
  in behaviour.** Imported from `NiordOrg/niord-info` @ `419ddd7` and `kaspernielsen/niord-info` @
  `3b5033b`. Compared file by file after identical formatting, the library sources differ from
  upstream in 44 lines: the `/rest` prefix, the removed interceptor option and dead
  `getPublication()`, eight unused imports, eight `Array<>` annotations, two lint exemptions.
  34 spec files, 177 tests pass; the administration suite is unchanged at 476 files, 5 152 tests.
- The footer and the four legal dialogs landed in `info-app/src/app/country-specific/dk/` — the
  workspace's own name for country content — until group B turns them into country assets.
- `@angular/localize` was **not** added although ng-bootstrap names it as a peer: nothing in the
  compiled packages calls it, and a run through every route showed no `$localize` error. One
  dependency and one polyfill chunk less.
- The specs keep their `detectChanges()` calls; only what the change of test builder required was
  touched (two type errors the old runner never checked, one fixture instant that crossed midnight
  under the runner's pinned clock, one deprecated `TestBed` call). niord-info has its own small
  `vitest.config.ts` on top of the workspace one, because the date-range picker needs inlining and
  that package name has no place in the administration suite's configuration.
- A bare `ng test` or `ng lint` runs **both** applications' targets one after the other. Every npm
  script and the CI shard runner is pinned to its project; without that the administration CI
  would have linted and tested the public site.
- The accessibility lint rules were **not** downgraded for niord-info: a control run showed they
  fire on its inline templates and find nothing, the spans already carry role, tabindex and a key
  handler. The real accessibility work (buttons, pressed state, focus ring) stays in group C.
- The bundle gate ships with the upstream baseline, not a re-measured one, because it holds:
  first visit 359 582 B against 360 583 B.
- The documentation guard checks that backticked application paths exist; this plan names files
  that later groups write, so that one check is lifted for this one document.
- **Found while running the site against a live backend** (filed in [backlog.md](backlog.md)):
  the "historical" publications tab does not render — the date-range picker's locale token has no
  provider — and the upstream production build fails identically, so the port did not cause it.
  It is not patched: the fix would put English calendar words on a Danish page, and group C
  replaces the picker with native date inputs. Old `/teaser.html` links fall through to the message
  list unless the server redirects them (group B's nginx does). The base map is requested
  protocol-relatively, which fails on a plain-HTTP dev server only.
- Still to close in group B, now facts and not assumptions: the execution mode is hard-coded to
  development, which ships the map debug panel; publication download links carry the backend's
  absolute host.

- **Group B is done: two countries, languages from the backend, three bundles, one legal dialog,
  a container with a proven allow-list, and the Info gate.** 40 spec files / 283 tests for the
  public site, the administration suite unchanged; all six build configurations carry their
  country markers; both images (32 MB each) pass the 32-assertion proxy test.
- **The container serves pre-compressed brotli, gzip as the fallback.** The stock nginx image has
  no brotli module and gzip alone would have cost about 50 kB on a first visit, so the runtime
  stage is Alpine with its nginx and brotli packages and a small entrypoint that renders the
  template. Files are compressed at build time by a Node script with the settings the bundle
  report measures with, from 256 bytes up — measured: the container puts exactly the report's
  number on the wire. Responses from the backend are gzipped on the fly, never double-compressed.
- The proxy test sends every request with raw `node:http` (fetch would strip the very headers
  under test) and has a control assertion proving the credentials were really sent before it
  asserts that none arrived. The stub upstream is reached through a named host-gateway entry:
  on Docker Desktop `host.docker.internal` also resolves to an unroutable IPv6 address.
- `/assets/` is never answered with `index.html`: a missing legal document would otherwise render
  the whole application inside the dialog. The dialog guards against it as well.
- The cache rule for hashed files had to be widened: this builder emits mixed-case hashes with
  `_` and `-`, which the pattern carried over from the old site never matched.
- **Texts.** The three bundles hold 74 keys. `MENU_PUBLICATIONS`, `DATE_FROM` and `DATE_TO` stay
  because the restyle gives them consumers. Nine Danish strings have no Danish source (eight
  screen-reader labels and error lines, and `tilbage`); they ship plain and are listed in
  `da-unaccepted.json` for the owner. **Every Greenlandic value is machine-made and unread by a
  speaker** — 54 keys, 5 branding values and both legal documents, all listed in
  `kl-unverified.json`; month and weekday names are the CLDR ones. Specs keep both lists honest.
- Legal documents were sliced out of the four old templates by script, so the wording is provably
  unchanged; titles moved to the country branding files. The Greenland footer is a copy of the
  Danish one and shows Danish placeholder logos until Greenland supplies its own.
- A first visit starts in the deployment's default language, not the first of the list. A site
  with no root areas loads the whole public data set (checked against a live backend: 220
  messages, the two countries' sum) instead of rendering empty.
- Link contract, settled by probing a live backend: with `externalize=false` attachments come as
  bare repository paths, message HTML and publication links as root-relative ones — the
  publications endpoint **does** take the parameter, contrary to what this plan said. One
  function normalises all three shapes; an attachment was fetched from the site's own origin.
- The Info gate also runs the documentation guard, because a change confined to `fe/info-app/`
  now skips the administration workflow that used to run it. Its format step needs
  `--ignore-unknown` (images, the Dockerfile and the nginx files have no Prettier parser).
- Backend: on this Windows machine three unit tests fail before and after the change
  (`SecomImportBoundaryTest`, `KubernetesSecretKeystoreWriterTest`,
  `MessageMapImagePipelineTest` — path separators and a decimal comma). They were proven
  pre-existing on an untouched checkout and excluded from the local run; CI runs on Linux.

- **Group C is done: one place for tokens and CSS, one shared map kit, and the public site on
  the workspace's design without Bootstrap.** First visit 187 771 B and initial 96.4 kB — both
  targets met, with the whole of group D still to come. The administration app is provably
  untouched in behaviour: its CSS came out byte-identical after the extraction, every one of its
  476 spec files still passes, and its three build inventories are unchanged apart from one chunk
  that OpenLayers now splits differently.
- The stylesheet split is guarded by a tool, not by reading: `scripts/css-neutrality.mjs` compares
  the emitted CSS of two builds byte for byte, or under reordering as the set of (condition,
  selector, declarations) triples. `.message-table` stays with the administration app; the shared
  core holds 104 selectors, the administration remainder 175, and the two sets are disjoint.
- Pinning the administration app's Tailwind scan to its own folders lost exactly sixteen selectors,
  all of them from prose in `docs/`, the vendored skill and the public site's old markup — none
  from a file the administration app ships.
- The map kit moved as sixteen files (eight sources, eight specs), reached through the one alias
  `@niord/map/<file>`, no barrel. Its specs run in **both** applications' test targets, both lint
  targets cover the folder, and a lint block allows it only `@angular/*`, `ol/*` and `rxjs`. The
  layer switcher takes its heading as an input, so the kit has no translation dependency; the WMS
  layer's HTTP client is optional, so an anonymous site can let the browser load tiles. The CI
  shard runner had to resolve include paths absolutely — a relative pattern outside the
  application's folder made `ng test` exit with "no tests found" — which would otherwise have
  silently dropped the kit's specs.
- The public site keeps its own small dialog frame on `@angular/cdk/dialog` and its own tab set
  on `@angular/aria`, both on the shared `.dialog-*` and `.tabs*` classes. The dialog is opened
  through a lazy opener: a static injection of the CDK dialog put 37 kB of overlay machinery into
  the chunk the landing route fetches (+10 kB on a first visit, measured), so the message dialog
  is fetched on demand from all three views. No default-config provider for the CDK dialog — one
  replaces the CDK's own defaults rather than extending them.
- The date-range picker, dayjs, ng-bootstrap, Popper and Bootstrap are gone. Two native date inputs
  with the existing labels send the same epochs as before for the same range (the spec pins a full
  year and a leap-day pair against both time zones), and the "historical" publications tab
  renders again — the upstream defect ends with the picker.
- Fourteen icons come from `@ng-icons/lucide` by per-icon import; the id badge is markup on the
  shared `.status-badge` classes, so NW and NM now wear the same neutral pill and the type is read
  from the identifier. The site's base type is the token scale, about a third larger than the old
  12 px — the single biggest visible change, and intended.
- The map's developer overlay is deleted outright: ten hard-coded English strings and two map
  listeners that a public site should not ship.
- Message HTML renders through the shared `message-html.css`, checked by a computed-style diff of
  1 341 elements over forty real messages plus one synthetic fixture for the table shapes the test
  data lacks; every difference matched a row of the predicted table. One visible change accepted:
  a link inside a message part now underlines on hover. A later review found that too little on
  a touch screen, where nothing hovers: a link a message carries is now drawn as one at rest (the
  primary and an underline), as is an attachment's file name, and only a publication reference
  keeps the colour of its text, as the site this one replaces drew it.
- Print parity is argued from the print contract, not measured against the old site (a pre-restyle
  build would have needed a second checkout); the print-media rendering is identical across the
  last change and the page count unchanged. The phone width is fixed structurally: the fixed
  filter bar measures itself and publishes its height as a custom property, and the detail card's
  field grid can shrink — `/messages/details` no longer scrolls 316 px sideways on a phone. The
  fix cost 106 bytes of first visit, the only increase in the group.
- Accessibility: every former `role="button"` span is a real button, the focus ring the old
  stylesheet removed is back, icons are `aria-hidden`. A flag says in its alternative text which
  language the text beside it is in, named in that language ("Dansk flag"), and the text itself
  carries that language's `lang`; each language button is named the same way, marked with its
  own `lang`, and pressed while it is the page's language, so `ARIA_SWITCH_LANGUAGE` has no
  consumer any more and waits, with `TERM_APPLY` and `TERM_CANCEL`, for the removal the
  [backlog](backlog.md) records under the public site's code and translations. The
  page has a main landmark and a skip link to it, and the message dialog is named before its
  message has arrived and keeps focus in place when a step, a reference or Back takes the pressed
  control away. That work raised the first-visit baseline by 962 B (+0.8 %): 662 B on the landing
  route (the language-marking directive's own chunk and its bindings), 224 B in the entry script
  (the skip link, the landmark, the language buttons) and 75 B of utilities. Keeping the list of
  shipped languages out of the settings service is what keeps the entry script one file: a view
  that imported that service moved the whole shared framework into a chunk of its own, +1.9 kB.
  The same review's visual findings closed the rest: a pressed filter chip keeps its mark in a
  forced-colours theme, a filter chip is a 40 px target below 1024 px, the name of a field is
  never cut to an ellipsis (on a phone it stands above its value, so a Kalaallisut name reads
  whole), an attachment image keeps its ratio on a phone and is named by its file name where it
  has no caption, the tab focus ring, the form-field border and the default and Danish
  cancelled and expired badges meet their contrast floors, and the map kit honours reduced
  motion. The 40 px chips make the pinned filter bar taller on a phone — at 390 px the Danish
  one grows from 140 to 230 px, the Greenlandic from 88 to 142 px. The first visit grows by
  298 B (styles +24, entry script +55, the landing route's chunks +221), inside the gate's
  tolerance, and the baseline is left where it was. Rebased onto the loading-state work below,
  it measures +184 B against the baseline that work raised (styles +89, the landing route's
  chunks +206, entry script −112, index +1), still inside the tolerance. The two together put
  the global stylesheet at 51 324 B raw, past its 51 kB warning budget on every build, so the
  budget pair was re-set from that measurement to 53 / 55 kB.
  Two findings need a word nobody has written and went to [backlog.md](backlog.md): the filter
  bar has no group name and no route has an `<h1>`.
- Budgets are set from the measured artefacts: initial 383 / 402 kB, styles 51 / 53 kB, any
  script 357 / 374 kB, component style 2 / 4 kB; `lint:info` runs with `--max-warnings 0`.
  `TERM_APPLY` and `TERM_CANCEL` have no consumer any more; their removal is a backlog item.

- **Group D is done: the public site follows the workspace's conventions.** Zoneless (22
  annotations and the `NgZone` gone), dates rendered in the country's zone through the site's own
  helper, six reads on `httpResource` behind a route-scoped store, the map and the teaser drawing
  through the shared kit, readonly models, backend HTML sanitised, preferences namespaced, route
  titles from existing keys. No new key anywhere. 54 spec files / 404 tests; the administration
  suite unchanged. Both country builds and both images pass every gate; a 34-scenario smoke of each
  country against the live backend and one anonymous check of the administration map are green.
- **The bundle grew by 12.5 kB in this group, and that growth is accepted.** First visit 200 235 B
  against the 187 771 B group C reached — still −44.5 % against the upstream site, and inside both
  targets (first visit ≤ 235 520 B, initial ≤ 112 640 B) with 35 kB and 9.6 kB to spare. Every
  byte is measured in its commit body: Angular's resource primitive hoisted into the eager chunk
  (+4.5 kB), the map on the shared kit (+4.3 kB, the kit's seven files against the old component;
  no new OpenLayers module), the teaser's own chunk (+1.5 kB), esbuild re-splitting the eager chunk
  into vendor and entry (+3.6 kB, identical modules), the rest under a kilobyte. The levers were
  tried and measured: deferring the WMS layer costs more than it saves (the deferred-block runtime
  lands in every visit); the ones left each delete a capability (the kit's layer switcher, the WMS
  layer). Not worth 3 kB. The checked-in baseline was raised at each step that could not be given
  back and never for packaging drift; the budgets are set from the measured artefacts and now fail,
  not warn.
- The two browser-zone renderings were a proven defect: a spec against the pre-fix code rendered
  "31 December 2023" for an instant that is "1 January 2024" in Copenhagen. The site's date
  helper was checked against date-fns-tz over 210 pattern combinations with zero mismatches and
  pins fourteen daylight-saving day boundaries for both zones; two of the plan's own test literals
  were wrong and are corrected to the measured values.
- Map tiles, measured for both applications: a scripted zoom-and-pan on the public map went from
  77 tile requests to 48 once the kit's tile-load gate applied (−38 %); the administration map
  makes 52 for the same gesture. Marker colours stay at today's values behind tokens; the map's
  debug overlay is gone. The messages map draws OpenStreetMap only — the ArcGIS base map was only
  ever the teaser's, which now owns it.
- Two kit inputs (`attributions`, `interactive`) with defaults that keep the administration app as
  it was; its builds differ by 110–398 bytes because the inputs are code, and by nothing else.
- Facts corrected by reading the wire rather than the plan: area ids are numbers (the model said
  string, and the remembered-root-area comparison silently never matched — fixed), the badge draws
  only the two statuses the bundles name, `publishDateFrom` is normalised at the service boundary.
- Owner input received during the group: all nine Danish strings accepted (`da-unaccepted.json` is
  empty); the English disclaimer names the Danish Emergency Management Agency in both country
  copies. Two findings of an external review of the earlier groups fixed: the four workspace scripts
  resolve their own location with `fileURLToPath` (a checkout path with a space crashed the first
  step of `verify`), and the `externalize` contract is recorded as the backend really behaves.
- Found and filed, not fixed: the running backend now offers `kl` to the Danish site, whose asset
  overlay ships no `kl` flag or branding (two 404s, a broken flag) — a country-overlay decision;
  the centre pins are computed when messages are drawn, not when the reader zooms (the review
  follow-up removed the pins, #51).

- **Parity check against the current public site (group E).** 103 features compared between the
  old site at nautiskinformation.soefartsstyrelsen.dk (production data) and the new site (test data)
  at 1280 and 390 px: 40 the same, 24 equivalent, 37 different, 2 missing. Of the 37 differences,
  16 are accessibility gains (real links and buttons, a keyboard order, `aria-modal`, focus return,
  `<html lang>`, a language-aware title, alt texts, 239 focusable elements against 5), 6 are the
  port's stated intentions (the list markup, the neutral id pill, same-origin downloads, native
  date inputs, browser print), and the rest are deployment configuration. The old site scrolls
  sideways by 547 px on a phone; the new one does not. No `/rest` call fails on any route.
- **Three findings from the check.** (1) The teaser's accessible name carried the banner key's
  `<br>` tags — fixed: the link is now named by the rendered banner element. (2) Inline `style`
  attributes in message HTML are stripped by the sanitiser the site now uses; on the test data 13
  message parts lose an indent, a justified paragraph or a link colour. That is the safety policy
  chosen in group D, and its visible cost is the owner's to weigh — either keep it, or allow-list
  `style` on a few elements. (3) The print banner reads "Efterretninger for søfarende og
  Navigationsadvarsler den …" in all cases, exactly as the upstream site's key has it; the old
  proxy capitalised "Søfarende", adapted the banner to the chosen families, and printed a shorter
  banner for a single message. Matching it would be new text, which the owner ruled out — left as
  is, for the owner.
- **The one capability the new site does not reproduce:** the old print buttons open
  `/details.pdf`, a server-generated PDF (the plan and the upstream code both assumed
  `details.html`). The new site prints the page from the browser; a PDF needs either a backend
  endpoint or the decision that browser print is enough.
- The Greenland brand is now its own file, `tokens.gl.css` (the owner's), replacing the Danish
  tokens the GL configurations imported until now: the full token set, both applications' GL
  builds green, the new colours in the emitted CSS. Three text pairs sit a fraction under the
  4.5 : 1 contrast ratio — in the same places as in the Danish and default files, so a workspace
  choice and not a Greenland one.

- **Follow-ups after the owner's review of the hand-over (all four done).** (1) One S-124
  portrayal for both maps: the administration app's `map-styles.ts`, its token reader and the six
  S-124 symbols moved into the map kit as pure moves, the public site's layers draw with it and its
  old darkmagenta module and tokens are gone; both maps now show the S-124 magenta symbols, the
  administration map is unchanged pixel for pixel, its builds gain one shared chunk. First visit
  +905 B, the portrayal module being larger than the 56-line one it replaces. (2) One sanitiser
  for both applications in a new `fe/shared-html/` (alias `@niord/html`, the map kit's shape):
  it keeps exactly `padding-left` (a validated length, 160 px cap) and `text-align` (four
  keywords) out of a message's inline styles and strips the rest — in the test data the other
  five properties are pasted-from-Word residue (Verdana 8.5 pt, cm margins) that would override
  the site's type scale. The kept declarations are rebuilt from validated values, never copied,
  and marked by a class token a message cannot forge. The pipes return `SafeHtml` because
  `[innerHTML]` re-sanitises a plain string and would strip what was kept; the public site's rule
  is amended to say so. The owner's premise that the console warnings were about `style` was
  mostly wrong: 70 of them are the `publication` attribute on links, dev-mode noise only.
  (3) The print buttons open the backend's anonymous PDFs (`/rest/message-reports/report.pdf`,
  `…/message/{id}.pdf`) with the filter mapped onto the report endpoint's own parameters and the
  message series added, verified as exact set membership through the endpoint's debug HTML; the
  container proxies the two paths with a 180 s read timeout; the client-rendered print route stays
  as the fallback. Found on the way: the "active now" chip filters nothing (the public endpoint has
  no such parameter — filed), and the PDF downloads rather than opens, as the administration app's
  does. (4) The applications moved under `fe/ui-apps/`: 1 583 files as 100 % renames, every
  build inventory identical, both images and container tests green.
- The bundle baseline was re-measured once against the rule, in the portrayal commit: the gate
  failed on the `index` group alone (+12 bytes on a 465-byte group, brotli entropy from changed
  chunk hashes, raw size unchanged). A 2 % tolerance on a group that small trips on any hash
  change; the gate's tolerance should gain an absolute floor of a few dozen bytes. It has one
  now: a group may grow by 2 % or by 64 bytes, whichever is more.

- **External review of the three implementation groups (B, C, D): six findings, all confirmed
  against the tree and all fixed, each with the regression spec the reviewer named and each
  spec proven failing on the pre-fix tree.** (1) The messages store was provided on the route,
  and Angular keeps a route injector alive after the routed view is destroyed, so leaving
  `/messages` and coming back reused the old store and its fetched list — the no-cache rule the
  store exists to satisfy was broken, in the very place group D claimed it. The provider moved
  to the messages component; a router spec drives the real routes through `RouterTestingHarness`
  and fails if the provider goes back on the route. (2) The date formatter returned early on an
  unloaded pattern before reading anything reactive, so a message rendered before its bundle
  arrived kept an empty date; the pattern key is now a memoised `translateSignal`. (3) The
  runtime language settings set Transloco's default but not its fallback — worse than the
  review said: with `kl,en` made available, the leftover `da` fallback was no longer a known
  language and a missing key echoed its name. The fallback now moves with the resolved default,
  proven with a real Transloco. (4) A repository link lost its fragment (`#page=7`); appended.
  (5) The four navigation links were unnamed below 640 px (label hidden, icon `aria-hidden`);
  the label is `sr-only sm:not-sr-only` and keeps the existing key as the name. (6) The dialog
  spec gains the unconditional overlay cleanup the testing document prescribes — the leak it
  guards against does not reproduce on this tree, but the rule stands. First visit +6 B.
- Two earlier reviewer findings (`fileURLToPath` in the scripts, the `externalize` contract)
  were fixed in group D.
- **Loading and failure states, a bundle that does not arrive, the language a reader did not
  choose, the deep link's steps and the scroll position: the first visit grew by 2 218 B
  (+1.9 %), and the baseline was raised by it.** Measured against a build of the tree before the
  change: 689 B of the entry script is the router's own scroll restoration, which puts a page the
  reader moves on to at its top and Back where they were; 674 B more is the bundle loader's
  retries and stand-in language, the language resolution, the dialog opener's guards and the
  spinner glyph; 738 B is the landing route's loading state, live regions, per-area recovery,
  main-type floor and the deep link's close; 115 B is the spinner's animation and utilities.
  Nothing of it can be deferred past the first render it serves.
- **The review's hygiene findings on the public site: comments, literals, branding assets, legal
  texts.** Decisions taken: (1) a wall clock in a clock change resolves as Java's
  `ZonedDateTime.of` resolves it — the offset before the change is kept, so a gap moves forward
  and an overlap takes its first occurrence — and the site and the backend agree; the
  administration app's copy behaves the old way and is filed in the backlog, not changed. (2) The
  accessibility statement's address is one country value, `accessibilityStatement`, which the
  footer links and the branding files' map credit words through `{{url}}`; which logo a language
  shows is the branding value `FOOTER_LOGO`; the copyright year is `{{year}}`, from the
  deployment's zone. (3) The country-neutral overlay's icon is a neutral Niord mark — the needle
  of the administration app's `niord.png` wordmark, on white, at 32 and 152 px — and the Danish
  crest stays in the two country overlays until the owner replaces it. The tab icon is 32 px and
  each footer logo 44 px tall, twice its drawn height, so a first visit to a country build
  fetches 22–31 kB less image data, outside the measured set. (4) The cookie statements were
  written anew with the owner's leave and describe the site's four storage entries, the tile
  host and the Cookie Order; the Danish one is listed in `da-unaccepted.json`, which gained an
  `assets` list, together with the Danish site title, which had never been through the list; the
  Kalaallisut one is machine-made and stays in `kl-unverified.json`. (5) A legal document missing
  in the reader's language falls back to the site's default language and then to the other site
  languages, not to English — the fallback a missing translation takes as well. (6) The values
  only this site draws with (`bg-map-sea`, `text-message-title`) are theme values of its own
  entry stylesheet, the teaser's banner is the chrome pair at 60 %, and the deployment ribbon is
  utilities. (7) The initial-bundle budget pair was re-set from the country builds' largest
  measure, 398 400 B: warning 410 kB, error 430 kB. The first visit the gate measures grew by
  293 B.

- **Review of the merged work, 2026-09-23 to 2026-09-24.** Once the site was on `main` (#39) it
  was reviewed as a whole — the public site, the shared folders, the administration app where the
  work touched it, the backend's report paths, the gates and the documents — and every finding
  was checked against the tree before anything was changed. The owner settled the open questions
  in advance, and the fixes went into the integration branch `feature/pr39-review-fixes` as one
  pull request per theme, each through its own gates: the query allow-list of the two report
  locations (#43); the backend's anonymous report hardening, the execution mode that defaults to
  `production`, the public report and the report's time zone (#46); the guard gaps in CI, lint,
  the paths check and the bundle gate (#45); the administration app's message indents and
  stale-deploy recovery (#44); the language on every list read and Browse by publication (#47);
  bars that never cover the language switcher, focus or content (#48); security headers, proxy
  hardening and HTML and URL safety (#49); the map kit's hit tolerance, hover timing and
  attribution, no centre pins, and the teaser on OpenStreetMap (#51); accessible names,
  languages, focus and landmarks (#52); link affordance, forced colours, field names, contrast and
  reduced motion (#53); loading and failure states (#54); comments, literals, branding assets and
  legal texts (#55). The last pass brought the documents level with all of it: the Definition of
  Done and the review skill name the gates of each application and mark what holds for the
  administration app alone; the map kit's exception to translation ownership is written where the
  rule lives; the analysis sections of this plan, the reference documents and the CI/CD design
  name the current layout; and the documentation guard now also fails a missing path into a
  shared folder or the scripts, an npm script or a build configuration that does not exist, and an
  `ng` recipe that does not name its project. The sanitiser's inline-style policy and the
  server-rendered PDF left the list below, settled by the hand-over follow-ups (2) and (3). What
  was deferred rather than fixed is in [backlog.md](backlog.md), each item with the owner's reason
  (#42): keyboard operation of the map itself, a first-visit baseline per country, an image that
  fits a hardened pod, the dormant `/wms/` proxy, a tile host a deployment can set, the end-to-end
  suite against the local stack, the shared stylesheets' hygiene, the public site's component
  names and its pre-translated dialog titles and badge labels, the Kalaallisut go-live mechanics,
  the Greenland colour and font follow-up, and three keys nothing renders.
- **Owner rulings, 2026-09-24.** The Danish values `da-unaccepted.json` listed — the three
  language names, the skip link, the two load-failure lines and the site's Danish name — and the
  Danish cookie statement are accepted, as the nine earlier ones were, so the file is empty
  again; `kl-unverified.json` is untouched, the professional translation still to come. The
  previous site's print address `/details.pdf` gets no redirect: a bookmark to it opens the
  message list, like any path the site does not know, and no decision about it waits for the
  cut-over.

## Still open

Everything below needs the owner or a deployment; nothing in the repository waits on it. The items that
must be done before the site's first release are owned by [`backlog.md`](backlog.md), under "Before the
public site's first release".

- **The client-rendered print view's banner.** `/messages/print`, which only the previous site's `/details.html` links reach, reads "Efterretninger for søfarende og Navigationsadvarsler den …" whatever was printed, where the old site adapted it to the chosen families and to a single message. Matching it would be new text.
- **Branch protection on `main`**, requiring "Frontend gate", "Info gate" and the backend's "Unit tests", switched on before production go-live (the repository becomes public then). Until it is, every gate is advisory and a red one blocks no merge.
- **Greenlandic.** The translator's workbook of 2026-09-22 (`niord-info-tekster-til-oversaettelse-kl-v2`) carries every row the site ships; when it returns, its values replace the machine-made ones and `kl-unverified.json` is emptied. Go-live for Greenland requires that.
- **Brand and wording for the new authority.** The DK footer, logos and legal texts still carry the previous authority's name and images where the owner has not yet replaced them (the English disclaimer's liability paragraph is done). Greenland needs its own logos, footer links and host name; today it shows the Danish placeholders, and the Kalaallisut visitor sees the English-language logo.
- **Whether the Danish site should ever offer `kl`.** The Danish deployable declares `infoSiteLanguages` as `["da","en"]` (`be/niord-dk/src/main/resources/niord.json`), so it does not. A deployment that adds `kl` on the Settings page gets a broken flag with two 404s, because the DK asset overlay has no `kl` flag or branding, and a `kl` reader's footer shows the bundle's product line ("Niord © year") inside the authority's link where the Danish branding line belongs — the backlog's maintenance item. Either the overlay gains the assets, or `kl` stays out of Denmark's setting.
- **The DEV / TEST ribbon** follows the backend's `mode` web setting, which the administration app reads as well. Every deployable's `niord.json` declares it as `production` and a local run starts the backend as `development`, but a database that already carries a `mode` row keeps it — the go-live item in [`backlog.md`](backlog.md) says what to check. The ribbon shows the mode upper-cased (DEVELOPMENT, TEST), where the site this one replaces said DEV.
- **Deployment.** Nothing publishes the two images to a registry yet, and no Helm deployment or ingress exists for them; the image does not start without `NIORD_SERVER_URL`, with one that is not `http(s)://host[:port]`, or when it cannot be resolved, and an https backend must present a certificate the image's CA bundle trusts. The three-minute API cache directive in the nginx template is carried over inert (no cache zone); the backlog owns the choice to drop it or make it real.
- **Cut-over**, and the two upstream repositories archived; until then a fix made upstream is ported by hand.
- The five administration map surfaces other than the messages map view have not been opened by hand since the layer switcher's heading became an input; their specs and build inventories are unchanged.

## Not verified

- The public site has only run against the local backend's test data and, for the parity check, been compared with the old production site by feature; no deployment of the new site has served production data.
- The professional Greenlandic translation and its rendering (line lengths, the date names in context) — the machine-made values are placeholders.
- Print output on paper. The two print buttons open the backend's PDFs, whose message sets were checked through the report endpoint's debug HTML, not on a printed page and not against the old site's `/details.pdf`; the client-rendered `/messages/print`, which only the previous site's `/details.html` links reach, is held by its print-media rendering and page count, not by paper.
- The tile-request counts were taken against dev servers, not a released image; the gesture is identical, so the comparison holds, but the absolute numbers on a released image may differ.
