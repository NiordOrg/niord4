# AGENTS.md

`fe/` is the Angular workspace of Niord, the maritime safety information system for Navigational
Warnings and Notices to Mariners. It holds two applications that ship separately but share one
`package.json`, one lockfile and one set of tool configurations:

| Folder           | Angular project | What                                                                                                     |
| ---------------- | --------------- | -------------------------------------------------------------------------------------------------------- |
| `ui-apps/admin/` | `niord-app`     | the administration app — message editor, firing-area schedule, admin and sysadmin areas, behind Keycloak |
| `ui-apps/info/`  | `niord-info`    | the public site — anonymous message browser, map, print views and publications, no login                 |

`ui-apps/` holds the two applications and nothing else, so every folder beside it — `styles/`,
`shared-map/`, `shared-html/`, `languages/`, `docs/`, `scripts/` — and every file at the workspace
root belongs to both applications or to the workspace itself. `languages/` holds the base
product's one language pack, `en`; every other language is a pack in a country repository. A build
reads the pack only through the staged copies in `.niord/`, which is generated and gitignored, and
the pack check (`country-kit/pack.mjs`) holds every other pack to `en`.
The kit, `country-kit/`, sits beside `fe/` at the repository root. It is the kit a country
repository builds its frontend with, published as its own package, `org.niord:niord-country-kit`,
beside the base product's source zip, which carries nothing of it: the pack tools,
the set-up a country runs, `init`, the schemas of the country manifest and the branding, the seam
catalogue, the zip build, and the example country `xx` that sets up and builds from outside the
tree ([country-kit/README.md](../country-kit/README.md)).

Each application carries its own `AGENTS.md` with the rules that hold for it alone:
[ui-apps/admin/AGENTS.md](ui-apps/admin/AGENTS.md), [ui-apps/info/AGENTS.md](ui-apps/info/AGENTS.md). **Read the
public site's file before touching it**: it came in whole from a repository of its own and now runs
on the conventions below — zoneless and signal-first, every reactive GET an `httpResource`, every
date rendered in the deployment's configured zone — plus one requirement the administration app does
not carry, that the size of a first visit is a product requirement with a checked-in baseline and
four build budgets behind it. Its styling is the workspace's too: both applications load the shared
`styles/` folder — the brand tokens, the Tailwind `@theme` bridge, the Layer-3 classes they both
render, and the element rules for the HTML a message carries — each with its own entry stylesheet and
its own pinned utility scan, and neither carries a component library or a webfont of glyphs. The
OpenLayers map kit is extracted too: `shared-map/` holds the map component, its shell, the base
layers, the projection service and the S-124 portrayal a message is drawn with, behind the alias
`@niord/map`, and both applications draw their charts from there; `shared-html/` holds the sanitiser
that decides what the HTML a message carries is allowed to render, behind `@niord/html`, so a notice
reads the same in both. Rules that apply to the whole repository (CI, the backend it talks to, Windows
sessions) are in the [root AGENTS.md](../AGENTS.md). The documentation set is [docs/](docs/); the
administration file names the order to read it in. The public site's rules as they stand — its
separation guarantees, its bundle gate, its container — are in its own AGENTS.md, and
[docs/niord-info-integration-plan.md](docs/niord-info-integration-plan.md) is the record of how it
came in: the analysis, the measurements, and the decisions taken while bringing it onto these
conventions.

## Non-negotiables (both applications)

- **Zoneless and signal-first.** No `zone.js`, no `NgZone`, no `ChangeDetectionStrategy` annotation;
  state is signals, `computed()` derives, `effect()` only moves state out of the signal graph.
- **No component library.** Behaviour is composed from `@angular/cdk` and `@angular/aria` and wrapped
  once per application; never add a component library (no PrimeNG, no Material, no paid packs).
- **Every user-visible string is a Transloco key.** No string reaches a template unkeyed.
- **The time zone comes from the data, never the browser.** Lint fails on browser-local `Date` forms;
  a missing zone disables, it never guesses.
- **No server data is held beyond the view that shows it.** No client-side cache; component lifetime
  is data lifetime. Sibling views of one list share a read by taking the service from the
  `providers` of the component that hosts their outlet — not from a route's: Angular scopes a
  route's `providers` to the route **config**, so that injector outlives the component the route
  activated and hands the returning reader the data they left. Fix over-fetching structurally,
  never by holding.
- **The base product is country-unaware; a country's differences live in its own repository.** Its
  seams have neutral defaults, catalogued in `country-kit/seams.json`: each application's
  `country-specific/default/` (`language.config.ts`, `country.providers.ts`, `country.routes.ts`,
  the administration app's `country.menu.ts`, the public site's `info.config.ts`), the brand tokens
  `styles/tokens.default.css` that `styles/tokens.active.css` imports, the public site's asset
  overlay `ui-apps/info/public/assets/default/` and the administration app's neutral
  `src/environments/`. A country fills the seams from its own repository, built from the published
  source zip and the kit package, unpacked at `fe/.niord/base/` and `.niord/kit/`
  ([country-kit/README.md](../country-kit/README.md)); this
  tree holds no country's folder, configuration, tokens, assets or pack. A build's
  `language.config.ts` names its languages — their labels, locales and flags, which UI bundles ship
  — and a language with no flag there renders as its code; the flags come from the language packs.
  Every staged bundle is complete, its missing keys filled from `en`, so neither application has a
  runtime fallback language: a build ships exactly the UI bundles its configuration names, and a
  key missing at runtime renders as the key. Behaviour differs through `country.providers.ts`
  (`provideCountry()`: hook registrations, such as a renderer and an editor for a promulgation
  service of the country's own, and component slots), and a country's own pages and menu entries
  through `country.routes.ts` and `country.menu.ts`, each empty in `default/`; a hook or slot is a
  base product contract kept across versions, added deliberately where a country needs one and
  catalogued in `country-kit/seams.json`. A country's files import base product code only through
  their application's country API, `@niord/admin/api` or `@niord/info/api` (and its `/testing`
  entry in specs), never by a relative path into the base product; the country lint fails anything
  else, dynamic imports included (the kit's generated lint configuration applies it in the country's
  workspace). Base product code never imports the API (`eslint.config.js`). A need it does not meet
  is a new export of `ui-apps/<app>/src/public-api.ts` (or `testing-api.ts`) — deliberate, under its
  own contract comment, in the file's minimal form (`export { … } from` statements only) — recorded
  in `country-kit/API-CHANGES.md`; an export is removed only after it has been deprecated for at
  least one base product version that DK and GL have both taken. A pull request that changes a file
  under `ui-apps/<app>/src/app/` for a security fix adds `{ version, files, summary }` to
  `country-kit/security.json` in the same pull request, with the version that first carries the
  fix, so a country's override of that file fails set-up until the fix is merged into it; an entry
  is never removed. A text that names an authority (the footer, a logo, a legal document) is
  branding, in the country's overlay, never a key of the base bundles. The administration app's
  `src/environments/` files are neutral (the local stack in development, the deployment's own
  origin when deployed); a country's test backend, Keycloak and map are its own environment files.
  Never a country branch in shared code. What a deployment may still tune — the site languages, the
  default language, the deployment ribbon, the base map's tile addresses — arrives from the
  backend's web settings at boot, and the build's own value (the language configuration, the
  environment file, the map kit's default) is the fallback when it does not.
- **No function copies repository content into the database.** A deployment's texts, settings,
  templates, scripts and reports belong to its operators. Neither application offers a reload from
  the server's files or a reset to default, and a deep link only looks a row up. Data reaches a
  deployment only by its bootstrap or a sysadmin's import
  ([code-data-config-plan.md](../docs/code-data-config-plan.md)). A render reads the database
  only; a template or script the database lacks resolves to nothing.
- **Comments are self-contained**: no tracking ids, phase labels, dates or pointers at documents.
- **Format only the files you changed** (`npx prettier --write <paths>`); never `npm run format`.
- **Every `ng` command names its project** — `ng build niord-app`, `ng test niord-info`. A bare
  `ng build` or `ng serve` stops with "Cannot determine project". A bare `ng test` or `ng lint` does
  not choose and does not fail: it runs both projects' targets one after the other, so it mixes the
  two suites or reports the other application's lint errors.
- **Neither application imports the other.** What both need lives in the shared folders above, and
  ESLint rejects a path into the other application whether it is spelled through `ui-apps/`,
  relative from inside the application, or as a dynamic `import()`; only a specifier assembled at
  run time escapes it.
- **A map change is made in `shared-map/` and measured for both applications.** The kit is one of
  the two pieces of code they share, it may import only `@angular/*`, `ol/*` and `rxjs`, and
  anything an application owns reaches it as an input — with one exception: the deployment's tile
  addresses, the same for every map an application draws, reach the tile layers through the kit's
  `MAP_TILE_SOURCES` token (`shared-map/tile-sources.ts`), which each application provides once in
  its `app.config.ts`. Nothing else joins it. Both projects run its specs and lint it, so
  a tuning that helps one application and costs the other is visible before it ships. How a message
  itself is drawn is the kit's too — one portrayal definition, `shared-map/portrayal/portrayal.json`
  with its symbol SVGs, the one source of every portrayal colour, size, anchor and pen (the
  backend's map images read it too), and one module, `map-styles`, that draws with it, so the marks
  are the same in both.
- **What the HTML a message carries may render is decided in `shared-html/`.** `MessageHtmlSanitizer`
  runs Angular's sanitiser over the markup and then puts back the two inline declarations a message
  is allowed to keep — `text-align` and a capped `padding-left`, rebuilt from validated values and
  never copied from the input — narrows every `class` to the vocabulary the shared message-HTML
  stylesheet styles, and gives every link with a `target` `rel="noopener noreferrer"`. In development,
  before the sanitiser sees the markup, it takes out what earlier editors left there and the sanitiser
  would drop anyway (comments, `<o:p>`, `data-*`, `id` and the like), so the console's "sanitizing
  HTML stripped some content" — which Angular prints only in development — is left for markup nobody
  expected; a production build leaves that pass out. It is the
  one place in the workspace that may mark a string of message HTML safe — ESLint fails a
  `bypassSecurityTrust*` call anywhere else, bar the administration app's plain-text pipe and
  sandboxed-HTML component — and it may import only `@angular/*`. Both projects run its specs and
  lint it. The rules for the two declarations and the class vocabulary are modules of their own
  there, which the administration app's rich-text editor reads as well, so an edit keeps exactly
  what the sanitiser renders and the editing surface carries no class the rendered message would
  not; so is `repo-path.ts`, which both applications encode an attachment's path with.

## Commands

The administration app:

```bash
npm start                # dev server http://localhost:4200, the base product against the local stack
                         # (/rest proxied to the be/ backend, Keycloak on localhost:8080)
npm run start:local      # against the be/ backend on localhost:8888 (proxied /rest)
npm run build            # production build (bundle budgets are a gate)
npm run lint             # ESLint (layer boundaries, time-zone rule, a11y templates)
npm run lint:scripts     # ESLint over scripts/ and eslint-shared.cjs, then the kit's (npm run lint:kit, from country-kit/; the example country's own files excepted)
npm test                 # Vitest unit suite; ng test niord-app --include='**/x.spec.ts' for one file
                         # (a map-kit spec: --include='../../../shared-map/x.spec.ts', see docs/testing.md)
npm run verify           # paths:check → contents:check → format:check → lint → lint:scripts → test → build → urls:check
KEYCLOAK_URL=http://localhost:8080/auth npx playwright test   # e2e — the Keycloak always given (the suite
                         # has no default; this is the local stack's), KEYCLOAK_TEST_USERNAME/PASSWORD set,
                         # a dev server on 4200 (npm start; the specs name localhost:4200)
E2E_CSP=1 E2E_BASE_URL=<backend> KEYCLOAK_URL=http://localhost:8080/auth npx playwright test csp.spec.ts   # the smoke run under the
                         # backend's Content-Security-Policy (docs/testing.md; the local realm's KEYCLOAK_TEST_USERNAME/PASSWORD for the authenticated tests)
E2E_SCENARIOS=1 ... npx playwright test ui-apps/admin/e2e/scenarios --workers=1   # the duty-officer scenarios: they WRITE
                         # data, so only against the sample deployment, by hand before a release, never in CI
                         # (the variables, the stack and the restart command: ui-apps/admin/e2e/scenarios/README.md)
E2E_A11Y=1 ... npx playwright test a11y-routes.spec.ts   # axe-core over every route against the WCAG 2.1 A
                         # and AA rules, held to e2e/a11y/known-violations.json; read-only, only against the
                         # sample deployment, by hand, never in CI (docs/testing.md; the keyboard walk is a scenario)
```

The public site, each command the same job pinned to the other project:

```bash
npm run start:info       # dev server http://localhost:4400, /rest proxied to the be/ backend
npm run build:info       # production build with --stats-json
npm run test:info        # Vitest unit suite
npm run lint:info        # ESLint
npm run bundle:info      # first-visit transfer against ui-apps/info/bundle-baseline.json
npm run separation:info  # every first-party module of the build belongs to the public site
npm run layout:info      # the bars, the message cards and the filter chips of the last build, in Chromium;
                         # a manual check on the base build (the Example country job runs it in CI)
npm run a11y:info -- --base <url> --out <dir>   # axe-core over every page and dialog of a running site,
                         # WCAG 2.1 A and AA and best practice, at 1280 and 320 px in each language;
                         # a manual audit tool, in no CI job (docs/testing.md)
npm run verify:info      # paths:check → contents:check → format:check → lint:info → test:info → build:info
                         # → urls:check → separation:info → bundle:info
npm run image:info -- --tag niord-info   # the base build's image, packaged from the last build:info (manual)
npm run test:container:info -- --image niord-info --no-legal-doc --no-branding-text
                         # the proxy, headers and start-up checks of a built image; the base build ships
                         # neither file (manual; add --browser for the frame policy in Chromium)
```

Every `npm start`/`start:*`/`build*`/`watch` script stages its application's language packs into
`.niord/build/<app>/` before it calls `ng`; a bare `ng serve` or `ng build` needs
`npm run languages:stage` first. Unit tests stage nothing: the specs read the `en` pack directly, and
no spec reads another pack — the pack check holds those to `en`.

A country releases the public site as its own container image (`npm run image:info` in its
workspace), packaging a built `dist`; in the base product,
`npm run build:info && npm run image:info -- --tag niord-info` packages the base build the same
way, as a manual tool. The image holds the built files alone, and what keeps the administration
app out of it is the separation check and lint.
`ui-apps/info/AGENTS.md` has the commands and what the image allows.

Workspace-wide, covering both:

```bash
npm run paths:check      # every path in angular.json exists, none starts at the workspace root, and each lies
                         # in its project's folder or a shared one
npm run contents:check   # what every build configuration ships, read from angular.json without a build: one
                         # source per output, exactly the UI bundles and the flags its language
                         # configuration names, each only from the staged copies, no other country's overlay, no
                         # README, status list or attribution, each language's label and locale those of
                         # its pack (-- --project niord-app or niord-info for one application); it stages
                         # the languages of the projects it checks first
npm run languages:stage  # stage the packs of languages/ into .niord/build/<app>/ (-- --app admin|info for one);
                         # rerun after a pack edit while a dev server runs, which picks the staged file up
npm run languages:watch  # the same, again on every change under languages/, in a second terminal
npm run languages:check  # the pack check: every other pack against the catalog of en. A missing or empty key,
                         # other placeholders, other markup, a date pattern the renderer cannot read and a
                         # new value not listed in the pack's status/unaccepted.json fail; a key en lacks
                         # is listed as stripped, a changed English text as a warning (-- --project
                         # niord-app|niord-info --configuration <c> for one build's languages, as a
                         # country's build runs it). In this tree en is the only pack, so it checks the
                         # catalog alone
npm run languages:catalog  # the catalog the check reads: every English key of both applications and of the
                         # backend's English bundles, with its fingerprint, placeholders and renames;
                         # written to .niord/catalog.json
npm run languages:migrate -- <lang>   # move a pack's values to the new keys languages/en/renames.json names
npm run languages:prune -- <lang>     # delete the keys of a pack the catalog no longer has
npm run languages:accept -- <lang> --app admin|info <key>...   # the product owner accepted these values:
                         # off the pack's status/unaccepted.json, their English fingerprint recorded. It
                         # runs on a home pack in a country's own repository (node
                         # ../.niord/kit/pack.mjs there), with --app backend --bundle <bundle>
                         # for the pack's backend half
npm run urls:check -- --dist dist/niord-app/browser   # a built dist: no external font, style or @import URL
npm run kit:zip -- --version <v> --out <file>   # the source zip a country builds from, with its .sha256;
                         # written only when every entry is on the include list, no relative import
                         # crosses between country-kit/ and the rest of the zip, and the country-word
                         # guard passes (country-kit/country-words.json)
npm run kit:package -- --version <v> --out <file>   # the kit package, org.niord:niord-country-kit, with its .sha256;
                         # what country-kit/kit-include.json selects, plus docs/country-api.md, kit.json
                         # and the six backend/ files,
                         # written only when the list, the marker, the closure check (no import leaves the
                         # package) and the country-word guard pass
node ../country-kit/example/run.mjs --work <dir>   # the example country set up and built from <dir>, outside
                         # the tree, against a zip of this working tree, then the new-country acceptance
                         # tests: an untouched init and the fixture upgrade with its upgrade report
                         # (--upgrade-from <zip> --upgrade-kit-from <kit zip>, a release's two zips
                         # together, for the upgrade test, which Frontend CI takes from the latest
                         # release; needs JAVA_HOME; verify-local runs it); [--backend [--skip-frontend]]:
                         # the untouched init's backend built with be/mvnw verify on Docker, the group
                         # and override checks
npm run format:check     # Prettier over the whole workspace and the kit
npm run docs:check       # README, the AGENTS.md files, docs/, the kit's own documents, the review skill:
                         # links, paths, npm scripts, configurations, ng commands that name their project,
                         # and vocabulary; in a document the kit package carries, links that stay inside
                         # the kit and `.niord/kit/` and `.niord/base/` paths that name a file of the kit
                         # package or the source zip; in one the source zip carries, links that stay
                         # inside it and never into the kit; and in the kit's guides only a country
                         # workspace's scripts; and every link's #fragment into a document it reads
                         # names a heading there (GitHub's slug) or an <a id>
npm run test:guards      # the guards' own negative tests: crafted imports the lint boundaries must reject,
                         # a bypassSecurityTrust* call outside shared-html/ that lint must reject,
                         # a broken angular.json paths:check must reject, crafted asset sets contents:check
                         # must reject (two sources for one output, a bundle or flag no language names, a
                         # README or status list shipped, another country's overlay, a language label that
                         # is not its pack's), a crafted pack for each finding of the pack check, a dist
                         # with an external font or style URL urls:check must reject, a bundle past bundle:info's tolerance,
                         # a bundle baseline naming files the public site's landing route does not load,
                         # a print address of the public site that its container's query maps refuse,
                         # an entry stylesheet that binds the dark variant itself instead of taking the shared one,
                         # a document naming a path, a script or a configuration docs:check must reject,
                         # a country word anywhere in fe/ or country-kit/ or an allow-list entry that permits nothing,
                         # a country folder, a pack but en, a country token sheet or a configuration
                         # outside the generator's base set in the base product's tree,
                         # and the kit's: a crafted manifest,
                         # branding, seam catalogue, zip, stub, pin,
                         # environment and settings file for each rule the country set-up enforces, a hook
                         # or slot the catalogue and the tree disagree on, each refused --add-override, an
                         # override whose base file changed, went or became protected (SECURITY first when
                         # security.json names it) or is a seam's file, an orphan file in a country's app/ or overrides/, and a
                         # country page or slot component in the example's first-visit chunks
```

**Framework idioms:** the vendored [`.claude/skills/angular-developer/`](.claude/skills/angular-developer/SKILL.md)
skill (pinned to an upstream commit in its `VENDORED.md`; update by copying, never by editing) and the
`angular-cli` MCP own generic Angular APIs (verify version-specific facts via the MCP and check its
`searchedVersion`); the `docs/` set owns everything specific to these applications and wins where the
two meet.

## Guards

- `npm run paths:check` after any edit to `angular.json`: a path left behind at the workspace root
  builds green and ships the wrong file. `npm run contents:check` after an edit to an `assets` entry,
  an asset overlay such as `ui-apps/info/public/assets/default/`, a `language.config.ts` or the pack
  under `languages/`: a file one build does not need builds green and ships.
- `npm run languages:check` after an edit to an `en` bundle or to `languages/en/renames.json`: no
  build here runs the pack check, and a key a country's pack lacks fails that country's build at its
  bump, so a new or renamed key is named in the PR (a rename also in `languages/en/renames.json`).
- `npm run test:guards` after any edit to `eslint.config.js`, a guard script, a folder the lint
  boundaries name, the public site's route table, its print addresses or its nginx query maps: a
  pattern or a baseline that stops matching fails nothing by itself.
- `npm run docs:check` after any change to README.md, an `AGENTS.md`, `docs/`, the kit's documents,
  its agent template (`country-kit/templates/AGENTS.md`) or the review skill.
- `node ../country-kit/example/run.mjs --work <dir>` (or `verify-local`) after a change to the kit, its
  example, the zip's include list or a root file of the workspace: a country builds from what the
  zip carries, and a file the kit leaves out builds green here and fails there.
- A country word of `country-kit/country-words.json` in any file git lists under `fe/` or
  `country-kit/` (the list itself excepted) fails `test:guards`, and in a file the source zip carries also `kit:zip`, the
  example harness and the publish. Remove the word, or allow it in that file with a reason and,
  where the need ends, the phase that ends it (an entry that permits nothing fails `test:guards`);
  a file the zip carries can also be kept out of it (`country-kit/zip-include.json`). A file the
  zip carries, a document included, never names a word to explain one — it points to the list.
- `test:guards` (`scripts/endpoint-roles.test.mjs`) holds every HTTP call and every role-gated item
  to the backend's endpoint table (`../be/niord/src/test/resources/endpoints/`): run it after a new
  call, a new query parameter, a new gated item or a change to the table; what to change when it
  fails is in [docs/testing.md](docs/testing.md#the-frontend-against-the-backends-endpoint-table).
  A change to the table alone runs Frontend CI's administration jobs (its folder is in the
  workflow's filter and in `verify-local`'s `ADMIN_RULE`).
- `test:guards` (`scripts/base-product-tree.test.mjs`) fails a country folder, a pack but `en`, a
  country token sheet or a configuration outside `CONFIGURATIONS` of `country-kit/lib/workspace.mjs`
  in the base product's tree: a country lives in its own repository.
- `npm run build:info && npm run bundle:info` after anything that can reach the public site's first
  visit: its readers are at sea on a metered link and the baseline is checked in.
- `node scripts/publications/spec-check.js` after any edit to the two publications specifications.
- **Two gates, one per application**: `Frontend gate` in `.github/workflows/fe-ci.yml` and
  `Info gate` in `.github/workflows/info-ci.yml`, checks the ruleset on `main` requires: a red gate
  blocks the merge. A change confined to `ui-apps/info/` runs the public site's workflow and, of Frontend CI,
  only the `Example country` job and the gate, because the example builds the public site too. The
  example job runs on a change to what its zip or harness reads — the zip's include list, the kit,
  `be/pom.xml`, the Maven wrapper and the English backend bundles — and not on `docs/`, the other
  `scripts/` files or another language's pack (`verify-local`'s `EXAMPLE_RULE` is the same expression). Everything else
  under `fe/` runs Frontend CI, and Info CI as well when it reaches something the public site's jobs
  read — the shared folders, the workspace configuration, the Node and Prettier settings, the info
  scripts, the paths guard and the guard tests. Each gate job is the only name the ruleset
  needs, so jobs can be added, merged or re-sharded without touching it — but never name one
  `Unit tests`, the backend's required check, which matches a job of that name in any workflow. A
  gate is skipped (which counts as passing) when its application is untouched.
- Review with the `niord-review` skill; the checklist is [docs/definition-of-done.md](docs/definition-of-done.md),
  which names the gates of each application. The rest of it is written for the administration app:
  for a change to the public site, [ui-apps/info/AGENTS.md](ui-apps/info/AGENTS.md) wins wherever the
  two differ — it has no edit flow and no Signal Forms, it adds no visible text but required
  accessibility labels, and its time zone is the country's.
