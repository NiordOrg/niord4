# niord-app

Niord is the maritime safety information system used to author, publish and distribute
Navigational Warnings (NW) and Notices to Mariners (NM). This folder is its web frontend: one
Angular workspace, one `package.json`, one lockfile, one set of tool configurations — and two
applications that ship separately.

| Folder           | Angular project | Deployed as                                                                                                                                                       |
| ---------------- | --------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ui-apps/admin/` | `niord-app`     | the administration app — the editor for warnings and notices, the firing-area schedule, the admin and sysadmin areas, behind Keycloak                             |
| `ui-apps/info/`  | `niord-info`    | the public site — anonymous browsing of published messages as details, table, map and print, plus publications and the teaser; no login, read-only, size-budgeted |

Both talk to the same Niord REST API (a separate application with its own deployment) over
same-origin `/rest`. The administration app authenticates through Keycloak; the public site never
does, and anonymous browsing is a supported mode of both, not a degraded one.

Neither application imports the other. Three folders beside them belong to both: `styles/` carries
the brand tokens, the Tailwind `@theme` bridge, the named classes they both render and the element
rules for the HTML a message carries, `shared-map/` carries the OpenLayers kit behind the alias
`@niord/map`, and `shared-html/` carries the sanitiser for the HTML a message carries behind
`@niord/html`. Each application has its own entry stylesheet and its own pinned utility scan, so a
class only one of them uses never reaches the other's bundle. What lives where, and the cascade order
both entry stylesheets declare, is in [docs/styling-reference.md](docs/styling-reference.md).

## Stack

| Concern          | As installed                                                                                                  |
| ---------------- | ------------------------------------------------------------------------------------------------------------- |
| Framework        | Angular 22 — standalone components, zoneless, signal-first, Signal Forms                                      |
| Language         | TypeScript 6, `strict` plus the extra flags in `tsconfig.json`                                                |
| UI foundation    | `@angular/cdk` + `@angular/aria` for behaviour, `@ng-icons/lucide` for icons, Tailwind CSS 4                  |
| Translation      | Transloco 8; the base product ships the `en` pack, and every other language is a pack in a country repository |
| Maps             | OpenLayers 10                                                                                                 |
| Rich text / code | TipTap 3 / CodeMirror 6, both loaded lazily                                                                   |
| Authentication   | `keycloak-js` 26                                                                                              |
| Unit tests       | Vitest 4 through the Angular `unit-test` builder (`ng test`)                                                  |
| End-to-end tests | Playwright                                                                                                    |
| Quality          | ESLint 10 + angular-eslint, Prettier 3, husky + lint-staged pre-commit hooks                                  |

No component library is installed, and none is to be added — see
[docs/ui-standards.md](docs/ui-standards.md).

## Requirements

- Node `^22.22.3 || ^24.15.0 || ^26.0.0` (`package.json` → `engines`); `.nvmrc` pins 24, which is
  also what CI uses.
- npm 11 (`packageManager` in `package.json`). `.npmrc` sets `legacy-peer-deps=true` because the
  `@tiptap` packages are at two versions, whose peer ranges strict resolution refuses (the file
  says which) — `npm ci` needs no extra flags.
- npm overrides (`overrides` in `package.json`). A patched version of a transitive package goes
  into the lock with `npm update <package>` when every dependant's range allows it; an override is
  only for a package some dependant pins below the fix, and each one is listed here with the
  advisory, why the lock cannot move, and when it goes. The `zod` and `undici` advisories of October
  2026 (GHSA-mrhx-c7xc-m3w5; GHSA-4qv8-2p9j-2685, GHSA-f72v-6w45-q472, GHSA-xjj6-f33q-929c) need
  none: `@angular/cli` 22.2.4 declares `zod` 4.6.6, and `jsdom`'s range allows `undici` 7.30.1, so
  both are lock moves. Nor does the `minimatch` advisory of November 2026 (GHSA-g3hv-75wg-m249,
  fixed in 11.0.1), which only the lint tools bring in: ESLint 10.15 declares `minimatch` ^11.0.5
  and typescript-eslint 8.73 ^11.0.1, so ESLint was a lock move and typescript-eslint's pin moved
  from 8.56.1. Today there is one override:
  - `vite` 8.3.4: `@angular/build` 22.2.x declares `vite` 8.3.0 exactly, which carries the
    launch-editor advisories GHSA-587h-7wmj-h5pm (high, Windows), GHSA-r825-w8mm-5pxw and
    GHSA-698v-4r3x-3vcv and Vite's own GHSA-9jrq-w75r-8gcw, GHSA-rq7h-c2jc-7f22 and
    GHSA-vfpm-58rq-9qcg, all fixed in 8.3.3. Remove it once `@angular/build` declares `vite` 8.3.3
    or later: drop the entry, run `npm install`, and check `npm ls vite`.
- A backend. `npm start`, every `start:local*` script and every public-site dev server proxy
  same-origin `/rest` to the `be/` backend on `localhost:8888` (`proxy.conf.mjs`), which
  `./start-local.sh` or `be/run.sh` starts, and the administration app signs in at the local
  stack's Keycloak on `localhost:8080`. Nothing in this repository configures CORS. See
  [docs/platform.md](docs/platform.md).

## Getting started

```bash
npm ci
npm start              # http://localhost:4200 — the administration app, the base product, local stack
npm run start:info     # http://localhost:4400 — the public site, against the be/ backend
```

`languages/` holds the base product's one language pack, `en`. Every start, build and watch script
first stages it into `.niord/build/<app>/` (generated, gitignored), which is all a build reads of
it. After a pack edit while a dev server runs, `npm run languages:stage` (or
`npm run languages:watch` in a second terminal) re-stages it and the server picks the staged file
up; a bare `ng serve` or `ng build` needs `npm run languages:stage` first. `npm run languages:check`
holds every other pack to `en`: this tree has none, and a country's build runs the check over its
own packs ([country-kit/README.md](../country-kit/README.md#language-packs)).

`./start-local.sh` from the repository root brings up the whole stack — database, Keycloak,
backend and dev server — `--info` runs the public site in place of the administration app, and
`--admin --info` runs both dev servers against the one backend.

## npm scripts

Every `ng` command names its project. In a two-project workspace a bare `ng build` or `ng serve`
stops with "Cannot determine project", and a bare `ng test` or `ng lint` neither chooses nor fails —
it runs both projects' targets one after the other — so no script here leaves the project out.
Every start, build and watch script below runs `node ../country-kit/stage-languages.mjs --app admin` (or
`--app info`) before its `ng` command, so the tables name only the `ng` part.

The administration app (`niord-app`):

| Script                      | What it does                                                                                                                                                                                                                                                                                  |
| --------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `npm start`                 | `ng serve niord-app` — dev server on port 4200 with the `development` configuration, local stack                                                                                                                                                                                              |
| `npm run build`             | `ng build niord-app` — the `production` configuration (optimised, hashed, service worker)                                                                                                                                                                                                     |
| `npm run watch`             | `ng build niord-app --watch` with the `development` configuration                                                                                                                                                                                                                             |
| `npm test`                  | `ng test niord-app` — the Vitest unit suite; watch mode in a TTY, single run otherwise                                                                                                                                                                                                        |
| `npm run lint`              | `ng lint niord-app --max-warnings 0` — ESLint over `ui-apps/admin/src/**/*.ts` and `ui-apps/admin/src/**/*.html`                                                                                                                                                                              |
| `npm run lint:scripts`      | `eslint "scripts/**/*.{mjs,cjs,js}" eslint-shared.cjs --max-warnings 0 && npm run lint:kit` — ESLint's recommended rules with Node's globals over the workspace's scripts and the shared lint lists, then the kit's                                                                           |
| `npm run lint:kit`          | `cd ../country-kit && eslint "**/*.{mjs,cjs}" --max-warnings 0` — ESLint over the kit's scripts, run from `country-kit/` with its own configuration, `eslint.config.mjs`, which loads the base product's plugins; the example country's own files excepted (their lint runs in its workspace) |
| `npm run verify`            | `paths:check` → `contents:check` → `format:check` → `lint` → `lint:scripts` → `test --watch=false` → `build` → `urls:check`                                                                                                                                                                   |
| `npm run e2e`               | `playwright test` — needs a dev server on port 4200, `KEYCLOAK_URL` and Keycloak credentials (below)                                                                                                                                                                                          |
| `npm run perf:publications` | Drives the publications screens against a running dev server and fails on a request budget                                                                                                                                                                                                    |

The public site (`niord-info`):

| Script                        | What it does                                                                                                                                                                                                                                                                                                                                                   |
| ----------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `npm run start:info`          | `ng serve niord-info --port 4400` — `/rest` proxied to the `be/` backend on 8888                                                                                                                                                                                                                                                                               |
| `npm run build:info`          | `ng build niord-info --stats-json` — the `production` configuration; the stats file feeds the two checks below                                                                                                                                                                                                                                                 |
| `npm run test:info`           | `ng test niord-info` — its own Vitest suite, discovered separately from the administration one                                                                                                                                                                                                                                                                 |
| `npm run lint:info`           | `ng lint niord-info --max-warnings 0` — over `ui-apps/info/src/**/*.ts` and `ui-apps/info/src/**/*.html`, with no inline disable                                                                                                                                                                                                                               |
| `npm run separation:info`     | `node scripts/info-separation-check.mjs` — every first-party module of the build belongs to the public site                                                                                                                                                                                                                                                    |
| `npm run bundle:info`         | `node scripts/info-bundle-report.mjs` — first-visit transfer against `ui-apps/info/bundle-baseline.json`, 2 % or 64 B tolerance                                                                                                                                                                                                                                |
| `npm run layout:info`         | `node scripts/info-layout-check.mjs` — the bars, message cards and filter chips of the last build in Chromium, `/rest` from a fixture                                                                                                                                                                                                                          |
| `npm run test:container:info` | `node scripts/info-container-test.mjs` — the proxy, the security headers and the start-up checks of a built image, from outside                                                                                                                                                                                                                                |
| `npm run image:info`          | `node ../country-kit/image-info.mjs --package-only` — `-- --tag niord-info` after `build:info`: the base build's image (a manual tool; a country runs it in its own repository), packaged from the built `dist` in a fresh context with the nginx files, refusing a non-production build, a source map or a file outside `country-kit/info-image-allowed.json` |
| `npm run verify:info`         | `paths:check` → `contents:check` → `format:check` → `lint:info` → `test:info --watch=false` → `build:info` → `urls:check` → `separation:info` → `bundle:info`                                                                                                                                                                                                  |

Workspace-wide:

| Script                       | What it does                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| ---------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `npm run paths:check`        | `node scripts/workspace-paths-check.mjs` — paths exist, start nowhere at the workspace root, and stay in their project's folder or a shared one                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `npm run contents:check`     | `node scripts/build-contents-check.mjs` — what every build configuration ships, from `angular.json` without a build: one source per output, exactly the UI bundles and the flags its language configuration names (`en` only when named), only from the staged packs, no other country's overlay, no README or status list; `-- --project <name>` for one application, whose languages it stages first                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `npm run languages:stage`    | `node ../country-kit/stage-languages.mjs` — stages the packs of `languages/` into `.niord/build/<app>/`: `en` as it is, every other bundle stripped to the keys `en` has, a value with other placeholders or an empty one replaced by the English text, the keys it lacks filled from `en`, every pack's flag; `-- --app admin` or `info` for one application. Rerun after a pack edit while a dev server runs                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `npm run languages:watch`    | `node ../country-kit/stage-languages.mjs --watch` — the same, again on every change under `languages/`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `npm run languages:check`    | `node ../country-kit/pack.mjs check` — the pack check: every other pack against the catalog of `en` (missing or empty keys, placeholders, markup, date patterns, the packs' `status/` lists); `-- --project <name> --configuration <name>` for one build's languages. In this tree the only pack is `en`, so it runs over the catalog alone                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `npm run languages:catalog`  | `node ../country-kit/catalog.mjs` — writes the catalog the check reads, `.niord/catalog.json`: every English key of both applications and of the backend's English bundles, with its fingerprint, placeholders and renames                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `npm run languages:migrate`  | `node ../country-kit/pack.mjs migrate` — `-- <lang>`: moves a pack's values to the new keys `languages/en/renames.json` names                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `npm run languages:prune`    | `node ../country-kit/pack.mjs prune` — `-- <lang>`: deletes the keys of a pack the catalog no longer has                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `npm run languages:accept`   | `node ../country-kit/pack.mjs accept` — `-- <lang> --app admin\|info <key>...`: the product owner accepted these values; takes them off the pack's `status/unaccepted.json` and records their English fingerprint. It is for a home pack in a country's own repository; `en` has no status list                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `npm run urls:check`         | `node scripts/build-urls-check.mjs` — with `-- --dist <dir>`, a built `dist` carries no external font, style or `@import` URL                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `npm run test:guards`        | `node --test "scripts/*.test.mjs" "../country-kit/**/*.test.mjs"` — crafted input that lint, paths:check, contents:check, urls:check, bundle:info, docs:check and the public site's nginx query maps must reject, the site's print addresses against those maps, both entry stylesheets on the shared theme, the country-word guard over every file git lists in `fe/` and `country-kit/` (no word the list does not allow, no allow-list entry that permits nothing), the base product's tree (no country folder, no pack but `en`, no country token sheet, no configuration outside the generator's base set), the CI filters held to each other (the workflows' path filters and `verify-local`'s rules, and the run commands that name the kit), and the kit's own tests: the pack tools, and a crafted manifest, branding, seam catalogue, zip, set-up stub, pin, environment and settings file for each rule a country's set-up enforces |
| `npm run kit:zip`            | `node ../country-kit/source-zip.mjs build` — `-- --version <v> --out <file>`: the source zip a country builds from, `org.niord.web:niord-fe-src`, built from what git sees of this working tree through `country-kit/zip-include.json`, with `catalog.json`, written only when the include list and the country-word guard pass; the same tree gives the same bytes, and the sha256 is written beside it ([country-kit/README.md](../country-kit/README.md#the-source-zip))                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `npm run kit:package`        | `node ../country-kit/source-zip.mjs build --package kit` — `-- --version <v> --out <file>`: the country kit's package, `org.niord:niord-country-kit`, what `country-kit/kit-include.json` selects of `country-kit/` (no test, fixture, country-word list, `source-zip.mjs` or example harness), named from the kit's top, plus `docs/country-api.md` (from `be/docs/`) and the marker `kit.json`; written only when its verify (the list, the marker, no import leaving the package) and the country-word guard pass; the same tree gives the same bytes, and the sha256 is written beside it ([country-kit/README.md](../country-kit/README.md#the-kit-package))                                                                                                                                                                                                                                                                              |
| `npm run format`             | `prettier --write --cache . ../country-kit` — **rewrites every file** of the workspace and the kit; format only what you changed instead                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `npm run format:check`       | `prettier --check --cache . ../country-kit` — what CI runs, over both applications and the kit                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `npm run docs:check`         | `node scripts/docs-check.mjs` — README, the AGENTS.md files, `docs/`, the kit's own documents and the review skill: links, paths, npm scripts, configurations, pinned `ng` commands and vocabulary                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `npm run portrayal:fixtures` | `node scripts/portrayal-fixtures.mjs` — rewrites `shared-map/portrayal/fixtures/expected.json` from what OpenLayers draws of `cases.json`; run it after changing a case, the screen sizes, `map-styles.ts` or OpenLayers (see [map-portrayal.md](docs/map-portrayal.md#golden-fixtures))                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `npm run prepare`            | Installs the husky hooks; runs automatically after `npm ci`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |

The pre-commit hook formats staged `ts`/`html`/`css`/`md`/`json`/`yml` files and runs `eslint --fix`
on staged `ts`/`html`/`mjs`/`cjs` files and the CommonJS `scripts/**/*.js`, in either application and in the
scripts; it formats and lints the kit's staged files, under `../country-kit/`, the same way.

The example country (`country-kit/example/`, in the kit beside `fe/`, country `xx` with its pack `zz`) is set up and built
the way a country repository is, from a folder outside this tree:

```bash
node ../country-kit/example/run.mjs --work ../.local-run/example   # JAVA_HOME naming a JDK 25
```

It builds the source zip and the kit package from the working tree, installs both and
`niord-parent` as version `4.0.0-example` into a scratch Maven repository under the work folder,
copies the example there, pins both by their sha256 and runs the example's own `node setup.mjs`, `npm ci`, `npm run build`
(both applications, ending in the checks on every country build), `npm test` and `npm run lint`.
With `--image` (Docker running) it also packages the example's public-site image and runs the
container test, the layout check and the separation check against it. Then come the new-country
acceptance tests against the same zip: a country made by `init` and never edited sets up, builds,
tests, lints and starts its bundle baseline, and the example is bumped to a zip derived from the
first, where set-up must fail on its override alone and write the upgrade report, then pass after
the routine actions. The upgrade test from the previous published release
(`--upgrade-from <zip> --upgrade-kit-from <kit zip>`, its `niord-fe-src` and `niord-country-kit`
together) runs in Frontend CI from the latest release's two zips, each verified against the sha256
in that release's notes, and in `verify-local` from the two its own options name; without them the
run says it is skipped. With `--image` the whole run
takes about ten minutes on a workstation. `verify-local` runs it as its `example country` part, with `--image` when Docker answers,
and Frontend CI as its `Example country` job, always with `--image --with-deps`;
[country-kit/README.md](../country-kit/README.md) has the rest.

## Build configurations

From `angular.json`, for `niord-app`. All four configurations are standalone.

| Configuration | Environment file             | Swaps                                                 | Service worker | Used by                                   |
| ------------- | ---------------------------- | ----------------------------------------------------- | -------------- | ----------------------------------------- |
| `production`  | `environment.production.ts`  | —                                                     | yes            | `npm run build` (default)                 |
| `development` | `environment.development.ts` | — (no optimisation, no budgets, source maps)          | no             | `npm start`, `npm run watch`              |
| `local`       | `environment.local.ts`       | — (unoptimised), `/rest` proxied to the `be/` backend | no             | `npm run start:local`                     |
| `test`        | `environment.test.ts`        | — (optimised, `production: true`, the TEST badge)     | no             | `ng build niord-app --configuration test` |

The files in `ui-apps/admin/src/environments/` are the base product's and name no deployment:
`environment.production.ts` and `environment.test.ts` take the API at `/rest` and Keycloak at `/auth`
on the page's own origin, the single origin a deployment serves the app, the API and Keycloak from,
and both set `production: true`, so neither build registers the dev routes or lets the e2e token
stub stand in for Keycloak;
the development and local files take `/rest` through the dev proxy and the local stack's Keycloak on
`localhost:8080`, with a world map and no WMS. A country's environments and configurations come
from its own repository, generated in its workspace by the kit
([country-kit/README.md](../country-kit/README.md#the-seams--seamsjson)); the seams and their neutral
defaults are documented in [docs/country-deployments.md](docs/country-deployments.md), the
environment files and the runtime settings overlay in [docs/platform.md](docs/platform.md).

`niord-info` has two, `production` and `local`, and both are **standalone**: Angular merges
comma-composed configurations shallowly, so a `production,<other>` would drop production's own keys
the moment the other declared the same one. The public site has no environment files at all.

| Configuration | Swaps                       | Assets overlaid         | Used by              |
| ------------- | --------------------------- | ----------------------- | -------------------- |
| `production`  | —                           | `public/assets/default` | `npm run build:info` |
| `local`       | — (unoptimised, no budgets) | `public/assets/default` | `npm run start:info` |

What a deployment may still tune without a rebuild is the language list, the default language, the
deployment ribbon and the base map's tile address: the site reads `infoSiteLanguages`,
`infoDefaultLanguage`, `mode` and `osmUrl` (an `https:` template, or it is ignored) from the
anonymous `GET /rest/settings/web` at boot and falls back to the build's language configuration, or
to OpenLayers' own tile address, when that call fails.
A language is offered only when a translation bundle ships for it — a build ships the bundles its
`language.config.ts` names (the base build, `en`), each staged into the build from the language
packs. A country's other packs, its branding overlay (authority name, footer links, dialog titles)
and its acceptance lists — each pack's `status/unaccepted.json` and its
`fe/info/branding-unaccepted.json` for the branding values and legal documents awaiting acceptance —
live in its repository, and every list is empty at its go-live; no build ships them.

## CI

Two GitHub Actions workflows, one per application, both at the **repository root** (GitHub reads
workflows nowhere else): [`.github/workflows/fe-ci.yml`](../.github/workflows/fe-ci.yml) for the
administration app and [`.github/workflows/info-ci.yml`](../.github/workflows/info-ci.yml) for the
public site. Each runs on every pull request and on pushes to `main` that touch its inputs;
`./verify-local` at the repository root runs the same steps locally (the example country's image
only when Docker runs; see the root `AGENTS.md`). Each work
job runs after its own `npm ci` on the Node version in `.nvmrc` — Frontend CI's side by side, Info CI's one job; within a job every step runs even when an earlier one failed,
so one red check never hides another. Every job pays its own `npm ci`, which is why there are a
few and not one per check.

Frontend CI, its administration jobs pinned to `niord-app`:

- **Checks + builds** — `npm run format:check`, `npm run docs:check`, `npm run paths:check` (all
  three cover the whole workspace), `npm run contents:check -- --project niord-app`, `npm run lint`,
  `npm run lint:scripts`, `npm run test:guards`, then `npm run build`, followed by `npm run urls:check` on its output.
- **Admin unit tests** — the whole suite in one job, `node scripts/ci-test-shard.mjs 1 1`. Not
  plain `Unit tests`: that name is the backend's required check, and a required check matches a
  job of its name in any workflow.
- **Example country** — `node ../country-kit/example/run.mjs --image --with-deps`: the example country set
  up against a source zip of the commit, `npm ci`, both builds with the checks on every country build,
  its specs and its lint, then its public-site image packaged from that build with the container test
  (`--browser`), the layout check and the separation check against the image; then the untouched
  `init` and the fixture upgrade, with the skipped upgrade test named in the job summary. It runs on a change to
  what the zip or the harness reads — the zip's include list (`ui-apps/` whole, the public site
  included, and the public site's checks of `scripts/` with the URL allow-list), the kit, the root
  workspace files, `be/pom.xml`, the Maven wrapper and the English backend bundles — and not on
  `docs/`, the other `scripts/` or another language's pack. Its Maven repository is restored from
  Backend CI's cache and never saved.
- **Frontend gate** — green only when every job detection asked for is.

Info CI, every job pinned to `niord-info`:

- **Checks + builds** — Prettier over `ui-apps/info/` and the info scripts, `npm run docs:check`,
  `npm run lint:info`, then `npm run test:guards`, which lints crafted imports into the
  administration app, across a layer and into a country folder, and crafted `bypassSecurityTrust*`
  calls outside `shared-html/`, and fails unless each is rejected — a pattern that stops matching
  fails nothing by itself. Then `npm run test:info`, `npm run paths:check`,
  `npm run contents:check -- --project niord-info` and `npm run build:info`, followed on its output
  by `npm run urls:check`, `npm run separation:info` and `npm run bundle:info`, the first-visit
  bundle report against the checked-in baseline. The base build's image, its container test and
  the layout check are manual tools ([AGENTS.md](AGENTS.md)); Frontend CI's Example country job
  checks the example's image on pull requests.
- **Info gate** — green only when it is.

The two gates are checks the ruleset on `main` requires, beside the backend's `Unit tests`: a red
gate blocks the merge.

A change confined to `fe/ui-apps/info/` runs Info CI and, of Frontend CI, only the example country
and the gate: Frontend CI drops that folder before it decides whether the administration app was
touched, and its administration jobs are then skipped. A change that reaches neither skips the gate
too — a skipped gate counts as passing. Everything else under `fe/` or `country-kit/` runs Frontend CI, and Info
CI as well when it reaches something the public site's jobs read — the shared folders, the
workspace configuration, `.nvmrc`, the Prettier configuration, the info scripts, the paths guard,
the guard tests and the kit files `image-info.mjs` imports or reads. Both workflows list a moved file under its old and its new path, so a move
between the applications runs both.

The end-to-end suite is deliberately excluded (it needs a live backend and Keycloak). Details are in
[docs/platform.md](docs/platform.md).

The repository's publish workflow (`.github/workflows/publish.yml`) follows Frontend CI and
Backend CI on `main` and releases the backend's artefacts to GitHub Packages and Harbor, with the
administration app's bundle (`niord-web-admin`, classifier `en`), and
the base product's frontend source zip, `org.niord.web:niord-fe-src`, whose sha256 it records, with
the zip, in the GitHub release of the tag `v4.0.<run>`
([country-kit/README.md](../country-kit/README.md#publishing)). A country builds, tests and pushes its
own public-site image from its repository (`npm run image:info` there,
[country-kit/README.md](../country-kit/README.md#the-public-site-image--imageinfo)), and the base
chart has no deployment for it yet — see [docs/backlog.md](docs/backlog.md).

## End-to-end tests

```bash
npx playwright install                 # once, on a fresh clone
npm start                              # in another terminal — the suite has no webServer
KEYCLOAK_URL=http://localhost:8080/auth KEYCLOAK_TEST_USERNAME=… KEYCLOAK_TEST_PASSWORD=… npx playwright test
npx playwright test ui-apps/admin/e2e/schedule.spec.ts   # one file, with the same variables
```

The suite never drives the Keycloak login page: it exchanges the credentials for a token bundle and
seeds it before the app boots. It names no environment of its own: `KEYCLOAK_URL` is given on every
run (above, the local stack's), and a test that needs a login fails at once without it, naming it.
It runs on port 4200: the specs that expect a URL name `localhost:4200`
([docs/backlog.md](docs/backlog.md), _Quality gates and tooling_). The base account must hold every role;
optional per-role pairs, the realm and the client are listed in
[ui-apps/admin/e2e/auth/README.md](ui-apps/admin/e2e/auth/README.md). Never commit credentials. A
country's generated workspace runs its own specs with `npm run e2e`, importing these fixtures as
`@niord/admin/e2e` ([country-kit/README.md](../country-kit/README.md#end-to-end-specs)). Everything else
about both suites is in [docs/testing.md](docs/testing.md).

## Documentation

Agent entry point: [AGENTS.md](AGENTS.md) for the workspace,
[ui-apps/admin/AGENTS.md](ui-apps/admin/AGENTS.md) for the administration app and
[ui-apps/info/AGENTS.md](ui-apps/info/AGENTS.md) for the public site. The `docs/` set below describes the
administration app unless it says otherwise. The public site's rules as they stand, its gates and
its container are in its AGENTS.md; how it came into this workspace, and the decisions taken on the
way, are recorded in [docs/niord-info-integration-plan.md](docs/niord-info-integration-plan.md).
Everything else lives under `docs/`:

| Document                                                                                     | What it covers                                                                                                                                                                                                      |
| -------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [docs/architecture.md](docs/architecture.md)                                                 | The four layers, lint-enforced import boundaries, data flow, state, extension points, the admin shell, the map layer, shared primitives                                                                             |
| [docs/platform.md](docs/platform.md)                                                         | Bootstrap order, configuration and the settings overlay, authentication, domains, i18n loading, service worker, deployment                                                                                          |
| [docs/angular-conventions.md](docs/angular-conventions.md)                                   | Signals, effects, the three data-loading mechanisms, Signal Forms, templates, routing, Transloco                                                                                                                    |
| [docs/typescript-conventions.md](docs/typescript-conventions.md)                             | Compiler flags, what lint enforces, naming, closed sets, wire vs model types                                                                                                                                        |
| [docs/coding-standards.md](docs/coding-standards.md)                                         | Error handling, dates and time zones, the no-cache rule, comments, dependencies, commit gates                                                                                                                       |
| [docs/ui-standards.md](docs/ui-standards.md)                                                 | The shared component catalogue, overlay surfaces, form controls, accessibility, responsive and styling rules                                                                                                        |
| [docs/styling-reference.md](docs/styling-reference.md)                                       | Every design token and Layer-3 class, and where each lives                                                                                                                                                          |
| [docs/testing.md](docs/testing.md)                                                           | The unit harness and its traps, spec idioms, the e2e fixtures and locator rules                                                                                                                                     |
| [docs/definition-of-done.md](docs/definition-of-done.md)                                     | The checklist a change has to clear, the gates of each application, and what enforces each gate                                                                                                                     |
| [docs/country-deployments.md](docs/country-deployments.md)                                   | The base product's seams and their neutral defaults, the token contract, the behaviour seam, where a difference lives, languages; adding a country is the kit's README                                              |
| [docs/backend-api-notes.md](docs/backend-api-notes.md)                                       | Wire contracts that are not obvious from a response body, per endpoint family                                                                                                                                       |
| [docs/map-portrayal.md](docs/map-portrayal.md)                                               | The IHO S-124 symbology drawn on every message map                                                                                                                                                                  |
| [docs/niord-info-integration-plan.md](docs/niord-info-integration-plan.md)                   | The record of how the public site came in: the analysis, the bundle measurements, countries and languages, deployment, the decisions taken while bringing it onto the workspace conventions, and what is still open |
| [docs/backlog.md](docs/backlog.md)                                                           | Before go-live, the maintenance backlog, and what is deliberately not built                                                                                                                                         |
| [docs/docs-niord-org-map.md](docs/docs-niord-org-map.md)                                     | Which help-anchor id points at which manual chapter on docs.niord.org, and what is stale                                                                                                                            |
| [docs/features/shell-and-auth.md](docs/features/shell-and-auth.md)                           | Header, footer, landing page, roles and areas, degraded auth, deep links, page titles                                                                                                                               |
| [docs/features/messages.md](docs/features/messages.md)                                       | The Messages area: views, filter model and URL contract, selection, tags, actions, rendering                                                                                                                        |
| [docs/features/editor.md](docs/features/editor.md)                                           | The Editor area: working copy, edit form, status workflow, geometry, promulgation, templates                                                                                                                        |
| [docs/features/schedule.md](docs/features/schedule.md)                                       | The Firing Area Schedule area                                                                                                                                                                                       |
| [docs/features/admin.md](docs/features/admin.md)                                             | The `/admin` area, section by section                                                                                                                                                                               |
| [docs/features/sysadmin.md](docs/features/sysadmin.md)                                       | The `/sysadmin` area, section by section                                                                                                                                                                            |
| [docs/publications/domain-rules.md](docs/publications/domain-rules.md)                       | The publications domain as rules — read before touching publications                                                                                                                                                |
| [docs/publications/DATA-MODEL.md](docs/publications/DATA-MODEL.md)                           | Publications specification: entities, fields, invariants (guarded by `scripts/publications/`)                                                                                                                       |
| [docs/publications/API-CONTRACT.md](docs/publications/API-CONTRACT.md)                       | Publications specification: endpoints, payloads, error codes                                                                                                                                                        |
| [docs/publications/COMPILATION-SERIES-PLAN.md](docs/publications/COMPILATION-SERIES-PLAN.md) | A planned, not started, publication kind                                                                                                                                                                            |

Generic Angular API questions are answered by the vendored
[`.claude/skills/angular-developer/`](.claude/skills/angular-developer/SKILL.md) skill; the
`docs/` set states only what is particular to this application.

## Licence

Apache License 2.0 — see [LICENSE](LICENSE). Copyright Danish Emergency Management Agency
(Beredskabsstyrelsen). Source files carry no per-file headers; the licence is stated here and in
`LICENSE` only.
