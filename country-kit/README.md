# The country kit

A country repository (`niord-dk`, `niord-gl`, …) builds its administration bundle and its public site
from a pinned version of the base product — the country-unaware Niord — with this kit. The kit is a
package of its own, `org.niord:niord-country-kit:<version>:zip`, published at the same version as the
base product's source zip, `org.niord.web:niord-fe-src:<version>:zip`, and a country runs it from where
set-up unpacks it, `.niord/kit/` at the repository root; it never copies or edits it. Set-up, the stub
and the pack tools are plain Node (ES modules, the Node built-ins only), so they run before `npm ci`
and need nothing the two packages do not carry. Two parts run after `npm ci` by design, because they are steps of the generated workspace:
the lint plugin, `lib/country-lint.cjs` (CommonJS, as ESLint loads it), and the build check, whose
asset resolution reads the workspace's installed `@angular/build` (`lib/asset-outputs.mjs`).

The kit finds the base product through one module, `lib/where.mjs`, never by taking its own parent
folder for granted. It tells the two layouts apart: in the base product's tree the kit is `country-kit/` at
the repository root, beside the base product `fe/`; a country runs the kit package, unpacked at
`<root>/.niord/kit` with its marker `kit.json` (`{"package":"niord-country-kit","version":"<version>"}`),
and the kit finds the base product at `<root>/fe/.niord/base`. Any other place is refused, with the
two layouts it knows. The base product code the kit runs — the URL check, the public
site's precompression — is loaded from the base product it finds, never by a relative path, and no
file of the base product imports the kit.

What a country owns is its manifest, the files at the seams' conventional paths and the files they
import, its guarded overrides, its branding, its text overrides, its brand tokens and font, its home
language packs, its own npm packages and its pins. Everything else — the applications, the
build options, the language list's shape, `angular.json`, `package.json` — is generated from the base
product on every set-up, so a base product change reaches the country with the version bump.

The guides in [docs/](docs/README.md) say who does what and how; this README is the reference they
link into.

## What is in this folder

| File                                                                                                                           | What                                                                                                                                                                                                                                                     |
| ------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [templates/setup.mjs](templates/setup.mjs)                                                                                     | the set-up stub a country commits as `fe/setup.mjs`, byte for byte; it verifies the kit package and the source zip by their hashes and hands over to the kit                                                                                             |
| [setup-kit.mjs](setup-kit.mjs) and `lib/`                                                                                      | the kit's half of set-up: the checks, staging, the build report, the generated workspace                                                                                                                                                                 |
| [guard-build.mjs](guard-build.mjs)                                                                                             | run first by the generated `build` script: refuses a workspace staged from a local source                                                                                                                                                                |
| [lib/where.mjs](lib/where.mjs)                                                                                                 | where the kit is, and so where it finds the base product, the workspace and the backend: the base product's tree, a country's unpacked zip or the kit package with its `kit.json`; any other place is refused                                            |
| [init.mjs](init.mjs) and `templates/`                                                                                          | starts a country repository from a handful of answers: the whole country, backend included (from `templates/be/`), its launcher, `start-local.sh` (below), and its workflows, `ci.yml` from `templates/ci.yml` among them                                |
| [init-answers.schema.json](init-answers.schema.json) and [lib/answers.mjs](lib/answers.mjs)                                    | init's answers: the schema and the reader with the rules a schema cannot say                                                                                                                                                                             |
| [lib/scaffold.mjs](lib/scaffold.mjs) and [lib/image-workflow.mjs](lib/image-workflow.mjs)                                      | the scaffold init writes from the answers (`be/` whole, the manifest, `info.config.ts`, `CODEOWNERS`, `ci.yml`, `image.yml`, the root README) and the image workflow's jobs                                                                              |
| [niord-country.schema.json](niord-country.schema.json)                                                                         | the manifest's schema                                                                                                                                                                                                                                    |
| [seams.json](seams.json)                                                                                                       | the seam catalogue, the extension points (hooks and slots) and the protected list                                                                                                                                                                        |
| [security.json](security.json)                                                                                                 | the base product files each version changed for a security fix, which an override of them must take                                                                                                                                                      |
| [branding.schema.json](branding.schema.json)                                                                                   | what a country may brand, per application                                                                                                                                                                                                                |
| [workspace-files.json](workspace-files.json)                                                                                   | every root file of the generated workspace and how it is made; what a country's `fe/.gitignore` names                                                                                                                                                    |
| [stage-languages.mjs](stage-languages.mjs)                                                                                     | staging of language packs, shared with the base product's own builds                                                                                                                                                                                     |
| [catalog.mjs](catalog.mjs), [pack.mjs](pack.mjs)                                                                               | the key catalog and the pack tools: `pack check`, `migrate`, `prune`, `accept`, `latest-catalog`, `validate`                                                                                                                                             |
| [lib/pack-archive.mjs](lib/pack-archive.mjs)                                                                                   | a pack's canonical archive: its layout, its build and its reader, and the staging into `.niord/packs/<lang>/`                                                                                                                                            |
| [lib/oci.mjs](lib/oci.mjs)                                                                                                     | the kit's OCI client: a pack and its withdrawn marker pushed and pulled; `pack.yml`'s publish and withdraw commands                                                                                                                                      |
| [lib/pins.mjs](lib/pins.mjs), [lib/published-catalogs.mjs](lib/published-catalogs.mjs)                                         | the pins of `niord-pins.lock.json`; the catalogs of the published base product versions, through Maven                                                                                                                                                   |
| [zip-include.json](zip-include.json), [kit-include.json](kit-include.json), `source-zip.mjs`                                   | what the source zip and the kit package hold; their builds, their verification and their guard command (the base product's publish tool, which the kit package leaves out)                                                                               |
| `country-words.json`, [lib/country-words.mjs](lib/country-words.mjs)                                                           | the country-word guard over fe/ and the zip: the words, their allow-list with reasons, and its check; not in the zip                                                                                                                                     |
| [docs/](docs/)                                                                                                                 | the guides: who does what and how an upgrade works, the country guide and its in-depth guide, the set-up prompt for an AI agent, the designers' brief, the operators' guide; read them at `.niord/kit/docs/` after set-up, the version you build against |
| [API-CHANGES.md](API-CHANGES.md)                                                                                               | the country API's compatibility policy and every change to it, with what a country must do                                                                                                                                                               |
| [lib/api.mjs](lib/api.mjs)                                                                                                     | reads the country API's files; finds a country's uses of a deprecated export                                                                                                                                                                             |
| [lib/country-lint.cjs](lib/country-lint.cjs)                                                                                   | the ESLint plugin `niord-country` and the blocks the generated lint applies to a country's files                                                                                                                                                         |
| [lib/dependencies.mjs](lib/dependencies.mjs), [lib/semver.mjs](lib/semver.mjs)                                                 | the country's own npm packages: their checks, the merge into the workspace, `--update-country-lock`; version ranges                                                                                                                                      |
| [audit.mjs](audit.mjs)                                                                                                         | `npm audit` and `npm audit signatures` of a country workspace, with `audit-accepted.json`                                                                                                                                                                |
| [lib/text-overrides.mjs](lib/text-overrides.mjs)                                                                               | the country's text overrides: their checks, and what staging lays over the packs                                                                                                                                                                         |
| [check-build.mjs](check-build.mjs), [lib/build-checks.mjs](lib/build-checks.mjs)                                               | the last step of the generated `build`: the checks on every country build                                                                                                                                                                                |
| [lib/asset-outputs.mjs](lib/asset-outputs.mjs)                                                                                 | the builder's own asset resolution, shared with the base product's `contents:check`: one source per output                                                                                                                                               |
| [lib/upgrade-report.mjs](lib/upgrade-report.mjs), [lib/previous-base.mjs](lib/previous-base.mjs)                               | the upgrade report of a version change, and the last set-up record and previous version it compares with                                                                                                                                                 |
| [lib/secrets.mjs](lib/secrets.mjs), [secret-scan.json](secret-scan.json)                                                       | the secret-pattern scan of the country's inputs and of both builds, and the base product's allow-list for it                                                                                                                                             |
| [lib/utilities.mjs](lib/utilities.mjs)                                                                                         | finds the country-only Tailwind utilities set-up records for the build check                                                                                                                                                                             |
| [image-info.mjs](image-info.mjs), [lib/info-image.mjs](lib/info-image.mjs), [info-image-allowed.json](info-image-allowed.json) | `image:info`: the public site's image packaged from the built output and checked; the files it may package                                                                                                                                               |
| [example/](example/)                                                                                                           | the example country `xx`, a complete country repository in miniature, with its harness and the new-country acceptance tests                                                                                                                              |

The `*.test.mjs` files beside them are the kit's own tests, run by the base product's `test:guards`;
they are not in the zip, and neither is `country-words.json`, which only the base product runs, nor
the base product's tooling for the folder, `eslint.config.mjs`, `.prettierrc` and `.gitattributes`.

## Set-up

From the country repository's `fe/`, with `JAVA_HOME` naming a JDK 25 (the zip comes through Maven):

```bash
node setup.mjs     # fetch, verify, check, stage, generate
npm ci             # the base product's dependencies and the country's, from the merged lockfile
npm run build      # the npm audit, both applications into dist/niord-app/ and dist/niord-info/, then the
                   # checks on every country build
npm test           # the country's own specs, and the base product's spec of each overridden file
npm run lint       # the country's own files, with the base product's rules and the country API's
npm run image:info -- --tag <registry>/<name>:<version>   # the public site's image, built and checked
npm run e2e        # the country's Playwright specs in e2e/, against a running start:admin
```

`start:admin` and `start:info` run the development servers (the administration app on 4200, the public
site on 4400, `/rest` proxied as in the base product), `audit` runs the npm audit alone, as the
country's weekly workflow does ([npm audit](#npm-audit)), and `bundle:info` the public site's bundle
report alone ([The checks on every country build](#the-checks-on-every-country-build)). Run `node setup.mjs` again after any change to
the manifest, a pack, the branding, a text override, a seam file, an override or the country's
packages, and after a version bump, which also writes the upgrade report
([The upgrade report](#the-upgrade-report)). From the repository root, `./start-local.sh` runs the set-up
and starts the whole local stack in one command: the shared containers, the country's backend and both
development servers ([Running it locally](#running-it-locally--start-localsh)).

`node setup.mjs` takes no argument but these five, each of which runs the whole set-up; any other
argument is refused. The first four change a committed file first. The first two change the
manifest's `overrides` ([Guarded overrides](#guarded-overrides)); the third, which stands alone and
takes no value, writes `country/package-lock.json` anew
([Country dependencies](#country-dependencies)); the fourth moves the pins of the foreign packs in
`niord-pins.lock.json` ([Pinning a pack](#pinning-a-pack)). The fifth, `--previous <version>`, which
stands alone, changes no file: without a set-up record it makes the upgrade report by comparing
with that version by version ([The upgrade report](#the-upgrade-report)), and beside a record of
another version it is refused:

```bash
node setup.mjs --add-override <base file> --reason <text>   # copy a base product file to override it
node setup.mjs --accept-override <file>                      # record the base product's current fingerprints
node setup.mjs --update-country-lock                         # make the country's lock from country/package.json
node setup.mjs --update-pins [<lang>=<tag>]...               # pin each foreign pack's newest good tag, or the one given
node setup.mjs --previous <version>                          # no set-up record: the upgrade report compares with <version> by version
```

What `node setup.mjs` does, in order; any failure stops it before anything is staged for the
applications or generated. From staging the packs (step 3) on, a failure also removes `.niord/packs/`,
where step 3 stages the packs as resolved, each verified by digest, after writing the upgrade report on
a version change once the checks ran; a failure before it (the pins, the zips' hashes, the stub)
leaves the folder of the last good set-up. So `.niord/packs/` is only ever one a set-up's checks
passed:

1. **The pins.** The base product version is the `<parent>` version of `../be/pom.xml`.
   `niord-pins.lock.json` pins that version's source zip by its SHA-256, the kit package of the same
   version by its own, and each foreign pack by its full reference, tag and digest:

   ```json
   {
     "format": 1,
     "base": { "version": "4.0.180", "sha256": "<64 hex digits>" },
     "kit": { "sha256": "<64 hex digits>" },
     "packs": { "da": "ghcr.io/sifa-dk/niord-lang-da:12@sha256:<64 hex digits>" }
   }
   ```

   A pom bump without a new pin fails with that message rather than a bare hash mismatch. A lock
   without the kit pin is refused, naming the line to pin it by, `<sha256>  niord-country-kit-<version>.zip`
   in the release notes. A pin for a pack the manifest does not pin, a pinned pack without a pin, a pin
   for another language than its key and one under another owner than the manifest's entry names fail.

2. **Fetch and verify.** The stub first writes the root's `.niord/.gitignore` holding `*`, so the kit
   and its zip ignore themselves. Then `../be/mvnw dependency:copy` fetches the kit package
   `org.niord:niord-country-kit:<version>:zip` into `../.niord/` (from `fe/`) — Maven's credentials,
   mirrors and cache serve the frontend as they serve the backend, and `MAVEN_ARGS` passes through. The
   zip is hashed and compared with `kit.sha256` **before** a single entry is read, its `kit.json` must
   name the base product version, and it is unpacked into `../.niord/kit/` through a fresh temporary
   folder, refusing links, anything but regular files, absolute, drive-letter, backslash or `..` paths,
   duplicate names and an oversized archive; the marker `../.niord/kit.sha256` records the hash, and the
   zip is kept, since the next upgrade report reads the previous kit from it. Then the same for the
   source zip: fetched into `.niord/`, compared with the `base` pin, unpacked into `.niord/base/` and
   marked by `.niord/base.sha256`. A cold start so runs Maven twice; a kit or base unpacked from the
   pinned hash before is used again, outside CI. The stub hands over to `../.niord/kit/setup-kit.mjs`
   with both hashes.

3. **The stub, the manifest and the packs.** The kit reads the last set-up record,
   `.niord/build/niord-build-report.json`, for the version the workspace was last set up at
   ([The upgrade report](#the-upgrade-report)). It refuses a `setup.mjs` that is not its own
   template (line endings aside): copy `.niord/kit/templates/setup.mjs` over it. On a
   version change a stub the base product has changed since is a failed check instead, so the
   upgrade report is written with the copy as one of its items before set-up stops
   ([API-CHANGES.md](API-CHANGES.md) records every such change). Then it reads `niord-country.json`
   and every pack the manifest names — `en` from the base product, a home pack from
   `packs/<lang>/` — and stages every pack but `en` into `.niord/packs/<lang>/`, in exactly the
   layout of the pack's canonical archive ([The canonical archive](#the-canonical-archive)), the path
   from which the country's Maven build reads every pack's backend half and flag,
   `.niord/packs/<lang>/backend/` and `.niord/packs/<lang>/img/flags/<lang>.png`, so set-up runs
   before any Maven build and the pom's check refuses a build without them
   ([The backend build](#the-backend-build)): a home pack from `packs/<lang>/`, which the checks
   still read, so its status files stay the home's; a pinned pack from GHCR, anonymously — its
   withdrawn marker first, then its manifest and archive by the pin's digest, each verified before
   a byte of the archive is unpacked, the archive kept in
   `.niord/oci/` and verified again when it is used again; or a local copy
   ([Local sources](#local-sources)). A pin the marker lists is a failed check, the first one
   printed: `pack <lang>: <reference> is withdrawn (<date>): <reason>; move the pin with node setup.mjs --update-pins`,
   and the upgrade report lists it. A pack without a marker fails as unreadable (a published pack
   always has one: the first publish pushes it before the pack), and so does a manifest or archive
   that does not hash to its digest. A staged folder of a language the manifest no longer names is
   removed. `.niord/oci/` keeps only the archives this run's pins used, and can be deleted at any
   time; a cached file of another size than the layer's is never read.

4. **The checks**, each listing every finding before set-up stops. First the publish time of every
   package the country's lock adds, from the registry or the cache
   ([Country dependencies](#country-dependencies)). Then the text overrides
   ([Text overrides](#text-overrides)), with their count per language printed on every run, and the
   pack check of every pack
   against the base product's catalog, per application for the languages it ships — a key a text
   override supplies counts as present — with the escape
   hatch `missing-keys-accepted.json` printed in full when it is not empty; each application's branding
   against `branding.schema.json`; the production environment's `production`, which must be `true`
   (with anything else a production bundle lets the e2e test's Keycloak token stub take Keycloak's
   place and compiles in the dev routes), and its `apiBaseUrl`, `keycloak.url` and `map.wmsUrl`,
   each a string literal starting with a single `/` (the same origin, so the
   Content-Security-Policy needs no other); and, when `../be/src/main/resources/defaults/country/settings.json`
   exists, its languages against the manifest (a language the manifest lacks, or a model language
   whose pack has no backend half, is a warning; a `defaultLanguage` that is a content-only language
   without a backend half fails); every guarded override against the base product it builds with
   and its `security.json` ([Guarded overrides](#guarded-overrides)), the list of overrides printed
   in full when it is not empty, as the escape hatch is; the orphan files and the links in the code
   folders ([The orphan-file check](#the-orphan-file-check)); the country's own npm packages, listed as the
   escape hatch is ([Country dependencies](#country-dependencies)); `audit-accepted.json`'s shape,
   the file printed in full when it is not empty ([npm audit](#npm-audit)); the secret scan of the
   country's inputs ([Secrets](#secrets)); and, as warnings that do
   not stop set-up, every use of a deprecated export of the country API
   ([The country API](#the-country-api)). On a version change set-up then writes
   `.niord/niord-upgrade-report.md` from these findings, also when a check failed
   ([The upgrade report](#the-upgrade-report)).

5. **Staging** into `.niord/build/<app>/`, the only asset input for languages and branding: exactly
   the application's `uiLanguages` — `en` only when they name it — each bundle stripped to the
   catalog's keys and filled from `en`, so no build fetches a second bundle at runtime, then the
   country's text overrides laid over it; the flags of
   every language the application names; the branding and legal files the schema names, and nothing
   else from the country's folders. Set-up writes `.niord/build/niord-build-report.json` — the base
   version, the zip's hash, where the base product and every pack came from (a pinned pack with
   its `reference`, the pin it was pulled by), the escape hatch,
   each override as `{ file, with, basedOn, reason, added, baseSpec }`, the country's packages as
   `{ name, version, app, reason, installScript }`, the text overrides counted per language and
   application, and how many findings `audit-accepted.json` accepts — and stages a copy into the
   administration bundle. It is also the set-up record the next version change compares with, so a
   failed set-up never writes it. Beside the staging folders, so no build ships it, it writes
   `.niord/build/country-utilities.json`: per application, the first Tailwind class with an
   arbitrary value (`sm:grid-cols-[3fr_2fr]`) of each country template, which the build check looks
   for in the built CSS, and prints them.

6. **The workspace**, generated in `fe/` from `workspace-files.json` and gitignored: `angular.json`
   with the projects `niord-app` and `niord-info` and the base product's own configurations
   (`production`, `development`, `local` and `test` for the administration app; `production` and
   `local` for the public site), every path into `.niord/base/`, a `fileReplacements` entry (or the
   `index` option) for every seam whose country file exists, and one for every override, in the
   options and in every configuration (a configuration's list replaces the options'); the test
   target runs the country's specs and a copy of the base product's spec of each overridden file
   (below); lint covers the country's TypeScript and the HTML under each application's `app/` and
   `overrides/` (never its legal documents), and the administration app's also the end-to-end specs
   in `e2e/`; the budgets are the base product's, each application's `styles` budget raised by the
   size of `styles/fonts.<cc>.css` when it exists ([The seams](#the-seams--seamsjson));
   `package.json` named `niord-<cc>-fe` with
   the base product's dependencies and the country's, and the scripts above, every one constant
   text (`build` runs `guard-build.mjs`, then `audit.mjs`, then both builds — the public site's with
   `--stats-json`, which its checks read — then `check-build.mjs`; `bundle:info` and `image:info` run
   the public site's bundle report and image from `.niord/base/`; `e2e` is
   `playwright test --pass-with-no-tests`); the base product's lockfile with the
   country's entries; `tsconfig.json` with the base product's aliases `@niord/map/*` and
   `@niord/html/*` and the four entry points of the country API, and, only for an application the
   country overrides a file of, `@niord/<app>/internal/*` in that application's tsconfigs
   ([The country API](#the-country-api)); an ESLint configuration that applies the base product's
   rules, to the end-to-end specs in their own program, `.niord/tsconfig.e2e.json`, and then the
   country blocks of `lib/country-lint.cjs`; Vitest and dev-server proxy
   configurations; a Playwright configuration over the country's `e2e/` with
   `.niord/tsconfig.e2e.json` ([End-to-end specs](#end-to-end-specs)); the language configuration per application, JSON made from the manifest and each
   pack's `pack.json` behind a fixed TypeScript file; and a Tailwind entry per application that imports
   the base product's and adds the country's folder, overrides included, to the utility scan. No
   manifest or pack value is ever written into source text.

### Local sources

For working on the base product and a country together, outside CI:

| Variable            | Effect                                                                                                                                                                                                                                                                                                                                                                                                                |
| ------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `NIORD_BASE`        | a base product checkout's `fe/` (for example `../../niord/fe`) in place of the two packages: the kit beside it, the checkout's `country-kit/`, runs set-up and copies itself into `../.niord/kit/` through `kit-include.json`, with `kit.json`, `docs/country-api.md` and the six `backend/` files as the package's build adds them; exactly the zip's files are copied in and the catalog is built from the checkout |
| `NIORD_PACK_<LANG>` | a home's pack folder in place of a pinned pack's pin, for example `NIORD_PACK_DA=../../niord-dk/fe/packs/da`: staged as its archive would be, the folder named for its language                                                                                                                                                                                                                                       |

A local pack takes the place of the pin, which it then needs none of, and is staged exactly as its
canonical archive would be: a file outside the layout fails, and its `status/unaccepted.json` is left
out, so its status rows are its home's, as a pinned pack's are. Both variables are **refused when
`CI` is set**, and both are recorded in the build report: `NIORD_BASE` as
`local: <path> @ <commit>` in `provenance`, a local pack as `local: <path>` under `packs`, with the
provenance then reading `local: packs from NIORD_PACK_* over pinned: …`. The generated `build` script
refuses such a workspace
(`guard-build.mjs`); a development server does not.

### The base bump workflow

`.github/workflows/base-bump.yml`, byte for byte [templates/base-bump.yml](templates/base-bump.yml)
as `init` writes it, re-pins a base bump pull request: the Niord base bump app's, or one a person
opens, both on a `base-bump/<version>` branch. It runs on a pull request from such a branch of
the country's own repository that changes `be/pom.xml`, on an edit of its own comment, and on Run
workflow with the pull request's number as `pr` (Actions, Base bump, Run workflow, `pr <n>`). Its
three jobs:

- `compute`, read-only, works out the versions (the `<parent>` of `be/pom.xml`, and the version the
  base branch's lock pins), fetches the two zips and compares them with release `v<version>`'s notes
  before any of the kit's code runs, makes the set-up record of the old version by a set-up at the
  base branch (when that fails, set-up runs with `--previous` and the report compares by version),
  writes the lock, runs set-up, copies a changed stub and the launcher, moves every pinned pack to
  its home's newest pack (`node setup.mjs --update-pins`), compares the workflow files with the
  kit's, lists the bootstrap's new release folders and the country's bootstrap templates whose base
  product template changed, and builds the comment.
- `apply`, with the write token and no code of the kit, verifies the artifact `base-bump` by the
  SHA-256 `compute` gave, commits the lock, `fe/setup.mjs` and `start-local.sh` as
  `github-actions[bot]` and pushes them, sets the status pending, cancels the branch's CI runs and
  dispatches `ci.yml` on the new commit, writes the comment and the labels, and sets the status
  last.
- `recount`, on a ticked or unticked box, on a push to a branch whose lock pins the version
  already, and after a failed `compute`, counts the unticked boxes and sets the status on the pull
  request's head.

`BASE_REPO` in its `env:` is the base product's repository, `NiordOrg/niord4`: the one
value in it that changes when that repository moves. A
country takes the new value with the template at its bump, and the comment lists a copy that differs
from the kit's as an item.

The release notes decide the pin. Their two lines, `<sha256>  niord-fe-src-<version>.zip` and
`<sha256>  niord-country-kit-<version>.zip`, equal to the downloads' hashes: the workflow pins
them. Different: the run fails, and nothing is pinned or run; tell the base product's maintainers.
Unreadable (`release not found` for a release not made yet, a rate limit, a server error): the run
fails and nothing is pinned; run it again (Actions, Base bump, Run workflow, `pr <n>`). The
workflow reads the notes with its own token, since the base product's repository is public, and
needs no secret: it reads GitHub Packages and writes to the pull request with its own token too.

The comment is one per pull request, found by its marker `<!-- niord-base-bump -->` and edited in
place: `## Base bump: <old> to <new>` with the pins and where they came from (each moved pack pin,
old and new, with the home's `pack-releases.md` to compare the new digest with), `## Human actions`
with a `- [ ]` box per item or "No human action: review, merge, deploy.", `## Checked by CI`, and
the whole upgrade report folded in `<details>`, cut at 60000 characters with a pointer to
`niord-upgrade-report.md` in the run's artifact `base-bump`. A move of the pull request to a newer
version posts the comment again, and a box keeps its tick while what it is about is unchanged: its
text, and for a box about a kit template the template's content, which a hidden marker at the box's
end names by its hash, so a template changed again at a later version is asked again. The items are
each action of a new `API-CHANGES.md` section, each template the report names as changed, to copy
or merge, a workflow file that differs from the kit's (`pack.yml`, `base-bump.yml`), and each new
bootstrap release folder `releases/<yyyy-mm-dd>-<topic>/` to decide on: a sysadmin imports it, the
workflow never does; and each file of the country's `be/bootstrap/templates/` that replaces a base
product template whose file differs between the two versions' bootstrap zips (changed, added or
removed), to compare with the new one: a file at the template's own path, which replaces it whole
in the bootstrap run, or a language variant of it, `x_<ll>.ftl` beside the base product's `x.ftl`,
which replaces it in its language. A base product correction reaches neither, so without the item a
stale copy would be found only by comparing databases. Without the old version's zip each such file
the new one holds is named, not compared; without the new version's, one item asks for the
comparison by hand. Every other template the kit changed since `<old>` (`CODEOWNERS`,
`dependabot.yml`, the `be/` files `init` writes, a template added or removed) is named in one line
above `## Human actions`, with no box: it asks nothing, and a country that keeps its own copy of one
compares it. Green means no task: review, merge, deploy. The status `niord/upgrade-actions`
says:

| State   | Description                                                                                                    | When                                                  |
| ------- | -------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------- |
| pending | `Base bump: finishing`                                                                                         | right after the push, until the run's last step       |
| success | `Base bump: no human action left`                                                                              | every box ticked, or none                             |
| failure | `Base bump: <n> human action(s) unticked`                                                                      | a box is unticked                                     |
| failure | `Base bump failed: see the run`                                                                                | `compute` failed                                      |
| pending | `Base bump: no comment yet; run the workflow on this pull request (Run workflow, pr <n>)`                      | a pull request pinned before the workflow's first run |
| failure | `Base bump: the push was refused; the next run re-pins`, or another `Base bump: …` naming the step that failed | a step of `apply` failed                              |

It labels the pull request `base bump`, and `security` when the report lists a new `security.json`
fix.

A push with the workflow's token starts no workflow, so `apply` dispatches `ci.yml`, which must
take `workflow_dispatch:` on a line of its own in its `on:` (the flow form
`on: [pull_request, workflow_dispatch]` works, but `init`'s notice still shows for it). A tick and
Run workflow run the default branch's copy of the workflow, so they do nothing until it is merged
there. The workflow never pushes a workflow file, which its token may not: a difference in
`pack.yml` or `base-bump.yml` is an item.

The cost, measured as wall time on a country's bump pull request on 2026-11-02: the workflow took
2 min 2 s when it pinned and ran set-up, and CI 4 min 50 s on the pinned commit. Each release moves
the pull request, so each release costs a move. Billed minutes round each job up, and this is an
estimate: about 14 per move, when every move pins and runs the full CI.
[How an upgrade works today](docs/responsibilities-and-upgrades.md#how-an-upgrade-works-today)
says what a person does with the comment.

## The manifest — `fe/niord-country.json`

What the country's builds can serve. Strict: an unknown field fails, with its path.

```json
{
  "country": "gl",
  "name": "Greenland",
  "apps": {
    "admin": {
      "uiLanguages": ["da", "en"],
      "contentLanguages": ["da", "en", "kl"],
      "defaultLanguage": "da"
    },
    "info": { "uiLanguages": ["kl", "da", "en"], "defaultLanguage": "kl" }
  },
  "packs": {
    "da": { "pinned": true },
    "kl": { "home": true, "publish": false }
  },
  "locales": {},
  "overrides": []
}
```

- `country` is the country's ISO 3166-1 alpha-2 code, two letters a–z; `name` plain text of at
  most 60 characters.
- `uiLanguages`: the application's interface is offered in these, each a complete bundle of its pack
  (filled from `en` where the escape hatch lists a key). `contentLanguages` (administration app):
  message content may be written in these; each needs only its pack's name and flag. Every UI language
  of the administration app is a content language too. `defaultLanguage` is one of the UI languages.
- `packs`: every named language but `en` has an entry, and no other language has one; `en` has none,
  since it comes with the base product. A pack is either `{ "home": true, "publish": <boolean> }` (in
  `fe/packs/<lang>/`; `publish` says whether the country's `pack.yml` publishes it,
  [Publishing a pack](#publishing-a-pack)) or `{ "pinned": true, "owner": "<GHCR owner>" }` (a pack
  another country is home to, pulled by its pin in `niord-pins.lock.json`; `owner` optional, by
  default `sifa-dk`).
- `locales`: a locale per language, `^[a-z]{2,3}(-[A-Z]{2})?$`, in place of the pack's own; only for a
  named language. The administration app formats by it; the public site does not read `locale` yet
  (its dates come from the bundle's `DATE_*` words), so there it is recorded and has no effect.
- `overrides`: the guarded overrides, normally `[]`; each entry is written by
  `node setup.mjs --add-override` and its `basedOn` by `--accept-override`
  ([Guarded overrides](#guarded-overrides)). An entry is
  `{ file, with, basedOn, reason, added }`: `file` a TypeScript, JavaScript or JSON file under
  `ui-apps/<app>/src/app/`, never a spec; `with` exactly `<app>/overrides/` and the path below
  `src/app/`; `basedOn` a `sha256:` fingerprint of `file` and of each other base product file of
  the same application the copy was made from; `reason` plain text of at most 200 characters;
  `added` a date. No two entries name one file, and no path has an empty, `.` or `..` segment or a
  backslash.

The deployment chooses among these languages at runtime, through the backend's web settings. What
it does with a setting that names a language the build lacks depends on the setting: the interface
language lists (`siteLanguages`, `infoSiteLanguages`) drop it; a `modelLanguages` entry stays, and
the editor gives it a field labelled with its code in upper case (under its pack's label when it
is a UI language of the administration app); the administration app keeps a `defaultLanguage`
while `modelLanguages` names it and otherwise takes the first model language; the public site
takes its first language in place of an `infoDefaultLanguage` it does not serve. Set-up warns on
each such setting and says which of these happens.

## The seams — `seams.json`

A seam is a base product file with a neutral default that a country replaces by a file at the seam's
conventional path, relative to the country's `fe/`. Set-up adds the replacement for every seam whose
file exists, in the configurations the catalogue names.

| Country file                                                                           | Replaces                                                                                            | Configurations       |
| -------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- | -------------------- |
| `admin/environments/environment.development.ts` (and `.local`, `.test`, `.production`) | the administration app's `environment.ts`                                                           | the one of that name |
| `info/app/info.config.ts`                                                              | the public site's default `info.config.ts`                                                          | every                |
| `info/app/index.html`                                                                  | the public site's `index.html` (the `index` option); check-build holds its `lang` and title         | every                |
| `admin/app/country.providers.ts`                                                       | the administration app's default `country.providers.ts`: hooks and slots, `provideCountry()`, empty | every                |
| `admin/app/country.routes.ts`                                                          | the administration app's default `country.routes.ts`: its own pages, `Routes`, empty                | every                |
| `admin/app/country.menu.ts`                                                            | the administration app's default `country.menu.ts`: `COUNTRY_MENU_ENTRIES`, empty                   | every                |
| `info/app/country.routes.ts`                                                           | the public site's default `country.routes.ts`: its own pages, `Routes`, empty                       | every                |
| `info/app/country.providers.ts`                                                        | the public site's default `country.providers.ts`: slots, `provideCountry()`, empty                  | every                |

A country file exports what the file it replaces exports, in the same form: `environment` from an
environment, `COUNTRY_INFO` from `info.config.ts`, a default export of `Routes` from a
`country.routes.ts`, and `provideCountry()` and `COUNTRY_MENU_ENTRIES` as the table names them.
Read the replaced file under `.niord/base/ui-apps/<app>/src/` for its exports and their types, or
the example's file of the same path ([The example country](#the-example-country)).

The language configuration of each application and the brand tokens are generated, with no country
file: the `styles` array names the generated Tailwind entry, then `styles/tokens.<cc>.css` (the base
product's default tokens when the country has none), then `styles/fonts.<cc>.css` when it exists.
When it does, each application's `styles` bundle budget, its `maximumWarning` and `maximumError`,
rises by the sheet's size (in kB as Angular counts them, 1000 bytes), so the face never warns and
the base product's margin still holds for the rest; every other budget is the base product's.
`protected` lists the base product files no country may ever replace — the sanitiser, the interceptors,
authentication, the loaders, `app.config.ts`, the administration app's route table and its
country-route rule, and the like.

## The extension points

The normal way to change what the applications do. Each is a base product contract with a neutral
default, kept across versions; `seams.json` names every one, and the base product's own test holds
the catalogue to its tree. A country's code imports the base product through the country API,
`@niord/admin/api` and `@niord/info/api` ([The country API](#the-country-api)), and its own files
relatively.

**Own pages** — `admin/app/country.routes.ts` and `info/app/country.routes.ts`, each a default
export `Routes`, loaded lazily after every base route, so the country's pages are a chunk of their
own. In the administration app every top-level entry has `canMatch: [roleGuard]` with a non-empty
`data.rolesRequired`, or `data: { public: true }`, or is a redirect, and its path's first segment is
none of the base product's top-level paths (`messages`, `editor`, `admin`, … and `dev`); the base
product leaves out and logs any other entry, so a forgotten guard closes the page and a base path is
never shadowed, also while a base route's guard turns a reader away.
The public site's routes are the layout's last child and public, as the whole site is. A page's
title is a `country.*` key.

**Menu entries** (administration app) — `admin/app/country.menu.ts` exports
`COUNTRY_MENU_ENTRIES`, each:

```ts
{ after: 'messages', labelKey: 'country.harbour.menu', icon: 'lucideBook', routerLink: 'harbour', roles: EDITOR_AREA_ROLES }
```

`after` is the base item it follows — `messages`, `editor`, `schedule`, `admin` or `sysadmin` — and
it keeps that place when the item is hidden from the user; entries after one item keep their order.
`labelKey` is a `country.*` key of the country's branding. `icon` is an icon the base product
registers, or one the country registers with `provideIcons` in its `country.providers.ts`. `roles`
are the roles that see it, any one of them; `[]` shows it to everyone, anonymous included. Give an
entry the roles of the page it opens.

**Hooks** — injection tokens a country adds its own implementations to, in `provideCountry()` of its
`country.providers.ts`, beside the base product's:

| Hook                           | Token                    | Default                                                                                                                       |
| ------------------------------ | ------------------------ | ----------------------------------------------------------------------------------------------------------------------------- |
| `admin.promulgation.renderers` | `PROMULGATION_RENDERERS` | the base product's NAVTEX, Twitter, SafetyNET and Audio; a country adds one `{ serviceId, component }` per service of its own |
| `admin.promulgation.editors`   | `PROMULGATION_EDITORS`   | the same four; a country adds the editor of each of its services                                                              |

**Slots** — named places in base product templates, `<app-slot name="…" />`, where a country shows a
component of its own. It registers one in `provideCountry()` with a lazy loader, so the component is
fetched only when its slot is shown and never lands in the first-visit chunks:

```ts
provideSlot('home.welcome', () => import('./welcome-note/welcome-note').then((m) => m.WelcomeNote));
```

| Slot                  | Application        | Where                                               |
| --------------------- | ------------------ | --------------------------------------------------- |
| `home.welcome`        | administration app | the end of the front page's welcome panel           |
| `layout.beforeFooter` | public site        | every page, between the main content and the footer |

A slot component takes no inputs. With nothing registered a slot renders nothing; a loader that
fails renders nothing and is reported to the application's error handler. Further hooks and slots
are added to the base product where a country needs one; ask for one rather than overriding a file.

## The country API

A country's files import base product code through one entry point per application and nothing
else:

| Entry point                | Imported by                                                                    |
| -------------------------- | ------------------------------------------------------------------------------ |
| `@niord/admin/api`         | the administration app's country files, `admin/app/` and `admin/environments/` |
| `@niord/admin/api/testing` | their specs only                                                               |
| `@niord/info/api`          | the public site's country files, `info/app/`                                   |
| `@niord/info/api/testing`  | their specs only                                                               |

Each is a base product file, `ui-apps/<app>/src/public-api.ts` or `testing-api.ts` under
`.niord/base/`; read it for what it exports, each export with a comment saying what it is for. The
administration app's API holds the hooks' tokens and types (`PROMULGATION_RENDERERS`,
`PROMULGATION_EDITORS`), the slot registry (`provideSlot`, `COMPONENT_SLOTS`), the menu entry's
type, `roleGuard` and the area roles for country routes, the environment's type `AppConfig`,
`LanguageConfig` and `PlainTextToHtmlPipe`. The public site's holds `InfoConfig`, `LanguageConfig`,
the slot registry, `AppConfigService`, `openInfoDialog`, `currentYearInZone` and `loadLegalDialog`
— `openInfoDialog(injector, loadLegalDialog, { documentId })` opens a legal document and keeps the
dialog a lazy chunk. The testing entries hold the test helpers (`provideTestTransloco` and
`countryRouteProblems`; `getTranslocoTestingModule` and `provideTestNiordConfig`): test helpers
that belong in no build — the public site's are typed against Vitest's globals and load the English
bundle — so only a spec may import the entry, which the app tsconfig leaves out and the spec
tsconfig takes in.

The API is a contract kept across base product versions. [API-CHANGES.md](API-CHANGES.md) holds its
compatibility policy and every change, with what a country must do. An export that is going is
deprecated first — its comment carries `Deprecated since <version>: <what to use instead>`, or,
once the version it goes in is decided, `Deprecated since <version>, removed in <version>: <what
to use instead>` — and set-up warns about every use of it, naming the file and the version it is
removed in, or that its removal is not scheduled yet; a `Deprecated since` line of another form,
or one whose removal version is not after its start, fails set-up. A need the API does not meet is a request for a new export, not a reason to reach
past it.

**What compiles.** The generated `tsconfig.json` maps the four entry points and the base product's
own `@niord/map/*` and `@niord/html/*`, which the base product's files compile through.
`@niord/<app>/internal/*` is mapped only in the tsconfigs of an application the country overrides a
file of, for the overrides' copies; a country without overrides resolves no internal path at all.

**What lint enforces** (`npm run lint`, through `lib/country-lint.cjs`) in the country's files:

- base product code only through the application's own API, in every form of import — static,
  `import type`, a re-export, `import x = require()`, an `import('…')` type and a dynamic
  `import()` — with `/testing` only in a `*.spec.ts`, `@niord/<app>/internal/*` only under
  `<app>/overrides/`, and never the other application's API, `@niord/map/*` or `@niord/html/*`;
- relative imports only within `<app>/app`, `<app>/overrides` and `<app>/environments` (a spec may
  also read `<app>/assets`); no absolute path, no URL, no `import()` whose path is built at run
  time, and `node:` only in a spec;
- on the public site, no `keycloak-js` and no signal forms, as in the base product; no country
  package of the other application (the `app` of `fe/country/package.json`);
- no `bypassSecurityTrust*` call anywhere;
- in templates, inline ones included, `[innerHTML]` only through `safeMessageHtml`, `plainTextToHtml`
  or `transloco`, as the binding's last pipe;
- no inline configuration: an `eslint-disable` comment disables nothing, is itself reported, and
  fails the lint.

A country's end-to-end specs, `e2e/**/*.ts`, are linted with `niord-app`, in their own program,
`.niord/tsconfig.e2e.json`, with the base product's rules. They import only `@niord/admin/e2e` and
`@playwright/test`, each exactly (no subpath), `node:` built-ins and files of `e2e/`
(`niord-country/e2e-imports`, which reads every form of import above), and no inline configuration
applies to them either ([End-to-end specs](#end-to-end-specs)).

Set-up adds one rule the lint cannot: the code folders (`admin/app/`, `admin/overrides/`,
`admin/environments/`, `info/app/`, `info/overrides/`) hold no link. Lint and the orphan-file check
read paths as they are written, so a link into `.niord/base/` would pass both and compile; set-up
fails every link there, as it does in the branding folders.

## Guarded overrides

Beyond the extension points, a country may replace a base product file with its own copy: a
TypeScript, JavaScript or JSON file of an application's `src/app/` — a component through its `.ts`,
whose template and styles come with it. Each is an entry of the manifest's `overrides`, and the build
fails whenever the base product changes a file the copy was made from, until the country has
compared its copy and recorded the change. An override that is still needed after a version or two
is a request for an extension point.

```bash
node setup.mjs --add-override ui-apps/admin/src/app/features/messages/components/message-selection-toggle/message-selection-toggle.ts \
  --reason "A selected message is marked with a bookmark"
```

`--add-override` refuses a path not under `ui-apps/admin/src/app/` or `ui-apps/info/src/app/` (shared
folders, `main.ts`, the stylesheets: ask for a hook); a file that is not TypeScript, JavaScript or
JSON (a template or stylesheet is overridden through its component); a spec; a missing reason; a file
the zip does not hold; a protected file; a seam's file, generated seams included (fill the seam
instead); a file already overridden; a copy that would overwrite an existing file; and a file whose
relative imports leave its application's `src/app/`. Otherwise it copies the file to
`<app>/overrides/<path below src/app/>`, with each relative import written as
`@niord/<app>/internal/<path>` (the copy compiles at its own place, where a relative path would reach
nothing; files under `<app>/overrides/`, the copies and their specs, are the only country files that
may import base product internals, and the workspace maps them only for an application the country
overrides), copies the template and styles its `templateUrl`, `styleUrl` and `styleUrls` name to their
mirrored places, records the entry with today's date and the fingerprint of every file it copied,
writes `niord-country.json` (two spaces, the overrides sorted by file) and runs the set-up. Change the
copy, and add the country's own spec of what it changes beside it.

A fingerprint is `sha256:` and the hash of the file with every CRLF read as LF, so a Windows checkout
and the zip agree. Every set-up fails an override:

- whose `basedOn` file the base product changed or removed, naming every such file and
  `node setup.mjs --accept-override <file>`;
- whose file left the base product, or joined the protected list (replace it with an extension
  point);
- whose file is a seam's, generated seams included (fill the seam instead: a hand-edited manifest
  cannot override a seam's file past the country's own);
- whose copy, or a template or stylesheet the copy names, is missing.

`--accept-override <file>`, with an entry's base file or its copy, records the current fingerprints
after the review and prints it: per `basedOn` file the recorded and the current fingerprint; for a
changed file the base product's own diff since the recorded fingerprint (`- before, + now`, from the
previous version's file to the current one); and the lines in which the country's copy differs from
the base product's current file (the import lines written as the copy's, so only the country's
changes show). The base product's diff needs the previous version's file: set-up takes it from the
zip the stub fetched for the version of the last set-up record, `.niord/niord-fe-src-<version>.zip`,
only when that zip's SHA-256 is the record's and the file's fingerprint is the recorded one;
otherwise the line says in one sentence why the diff is not available (no record, a record of the
same version or set up from a local checkout, no such zip, another hash, another file). A resource
the base component has gained since is added to `basedOn`. It refuses an override whose base file is
gone.

**`security.json`** lists, per base product version, the files that version changed for a security fix
(`{ version, files, summary }`, files relative to `fe/`); it travels in the zip with this kit, and a
missing or malformed one fails set-up. An override whose changed `basedOn` file a fix newer than the
last set-up record's version names fails first with `SECURITY: <version>`: merge the fix into the
copy, then `--accept-override` records the fixed fingerprints, printing the fix again. A fix the
country already set up with is not news: a later change to that file is the ordinary "changed since"
failure, as the upgrade report lists only the fixes new since the previous version. The base product adds a fix in the pull request that makes it, with
the version that first carries it, and never removes an entry.

**The base product's spec runs against the copy.** Where the overridden file has a spec beside it
(`<stem>.spec.ts`), the country's `npm test` runs a copy of it from
`.niord/generated/<app>/base-specs/`, its relative imports pointing back into `.niord/base/`; its
import of the overridden file compiles to the country's copy through `fileReplacements`. So a copy that
breaks what the base product tests fails the country's tests, and the country's own specs come on top.

Every set-up prints the overrides — each file, its copy, the reason, the date and the base spec run
against it — and the build report records them.

## The orphan-file check

Every file of a country's code folders is there for a reason set-up can see. A file in `admin/app/`,
`admin/environments/` or `info/app/` that is neither a seam's country file nor reached from one fails
set-up — an environment file spelt `environment.prodution.ts` would otherwise leave the production
build on the base product's neutral environment — and so does a file in `admin/overrides/` or
`info/overrides/` that is neither an override's copy nor reached from one.
Reached means through a relative import (static, side-effect, re-export or dynamic), a component's
`templateUrl`, `styleUrl` or `styleUrls`, or a stylesheet's relative `@use`, `@forward` or `@import`.
`*.spec.ts` files are the country's tests: they are neither checked nor followed, so a component only a
spec uses is an orphan.

## Branding — `branding.schema.json`

One section per application: every key a country may brand, with its kind (text, html, url, file),
its placeholders and whether it is required; every brandable file with its output path; the legal
documents (`assets/legal/<doc>.<lang>.html`), each naming its title key and the footer label that
offers it. Keys under `country.*` are
free. A country's branding lives in `admin/assets/` and `info/assets/` (`branding/<lang>.json`, the
files beside it, `legal/`). The check fails a key the schema does not name or whose placeholders
differ, a required key or file missing, a UI language of the application without its
`branding/<lang>.json` once any of them has one (or the schema requires a key: then every UI
language needs it), a key set without the key the schema says it `requires` (the footer's logo, `main.footer.links.center.imageSrc`, without its `imageAlt`; its link, `url`, without
its `ariaLabel`; the accessibility statement's label, `footer.accessibility`, without its address,
`footer.accessibilityLink`, and the reverse), a footer label (`footer.disclaimer`, `footer.cookies`) set without its dialog's
title or with its document shipped in no branding language, markup that differs from the country's English branding (or
its default language's where `en` is not a UI language) — an `href` may differ only in its path, on
the same `https:` host — and any file that is not data: images are PNG, WebP or ICO by their bytes, or
an SVG without script, `foreignObject`, event attributes or external references; legal documents use
only the dialog's elements and attributes, and no `javascript:` or `data:` address.

The public site's footer keys are held to the country guide with warnings, since a country may
replace its default footer with its own (the legal section's `labelCheck: "warning"`): set-up
prints each and the build stays green. It warns of a footer label (`FOOTER_DISCLAIMER`,
`FOOTER_COOKIES`) set without its dialog's title (`LEGAL_TITLE_DISCLAIMER`, `LEGAL_TITLE_COOKIES`)
or with its document shipped in no branding language, and of `FOOTER_LOGO` set without
`FOOTER_LOGO_ALT`, which it `recommends` (the default footer would draw the logo as decoration). In
either application a legal document shipped in a language whose branding lacks its footer label is
a warning too: the footer (on the public site, the default footer) never offers it there.

A file whose entry gives a `width` or `height` has that pixel size, read from the PNG's header chunk
(IHDR): the public site's `favicon.png` 32×32, `apple-touch-icon.png` 152×152 and every
`logo-gray.png` and `logo-gray-<lang>.png` 44 px high, twice the 22 px the footer draws, its width
free. A key whose entry `forbids` a rule holds nothing that rule finds: `FOOTER_COPYRIGHT` no four
digits standing alone (`year`: a year goes to `{{year}}`, which the product fills), `MAP_ACCESSIBILITY` no address written in and
`{{url}}` only as its link's href (`address`; the product fills `{{url}}` from `info.config.ts`).
Each public-site cookie statement, `info/assets/legal/cookies.<lang>.html` (`namesPreferences`),
names every key the site stores in a reader's browser, with its prefix (`niord-info.language`, …),
as `ui-apps/info/src/app/core/services/preference-keys.json` lists them: the public site's own list,
which `@niord/info/api` exports as `PREFERENCE_KEYS` and `PREFERENCE_PREFIX`. The administration
app's statements are not held to it.

The branding values and legal documents a reader of the language has not accepted yet are listed
beside the application's folders, in `admin/branding-unaccepted.json` and
`info/branding-unaccepted.json` (absent means none), in the base product's format, a list per UI
language of the application:

```json
{
  "note": "Awaiting acceptance by a reader of the language; go-live requires every list empty.",
  "da": ["assets/branding/da.json#FOOTER_COPYRIGHT", "assets/legal/disclaimer.da.html"]
}
```

The branding check fails a list that is not this shape, a language that is not a UI language of
the application, an entry that names neither a branding key nor a legal document, or one of another
language than its list's, a file the country does not ship, a key its branding file does not set or
the schema does not name (a `country.*` key may be listed), an entry listed twice, and a link or
other non-regular file; while any list holds an entry it warns, once per application, with the count
per language: go-live requires every list empty. The list is never staged.

## Language packs

A home pack in `fe/packs/<lang>/` has the shape of the base product's `languages/en/`, plus `backend/`
(the backend's text, `<bundle>.properties`) and `status/` (`fingerprints.json`, `unaccepted.json`),
and nothing else: a file outside the canonical archive's layout fails set-up
([The canonical archive](#the-canonical-archive)). Every key of an application's bundle is in exactly
one of `unaccepted.json` and `fingerprints.json`: a key the owner has not accepted is listed
unaccepted, an accepted one has the fingerprint of the English it was accepted against, and set-up's
pack check fails a key in both or in neither. The backend half has a section of each file:

```json
{
  "note": "…",
  "admin": { "unaccepted": [] },
  "backend": { "mail": { "unaccepted": ["mail.foo.subject"] } }
}
```

in `unaccepted.json`, and `"backend": { "<bundle>": { "<key>": "<fingerprint>" } }` in
`fingerprints.json`. A backend key listed twice, listed but missing or empty in its bundle, or
listed and fingerprinted fails, as a frontend key does; a backend key in neither list is not an
error but one warning per bundle with the count, since the backend half's status came after its
texts: recording a fingerprint for a text nobody read would accept it.

The backend half's `web.properties` names its own language, `lang.<lang>` (`lang.zz = Zedish`): a
draft print names each of a message's languages by that key, and where no pack has it, by the
language's code in upper case. A deployment whose message templates came before that fallback
fails the print instead, until a sysadmin imports the release folder that brings it. The pack check
warns of a backend half that lacks its own `lang.<lang>` or holds it empty, for a UI language as
for a content language; another language's `lang.<code>` is optional.

**A language no pack has yet** starts as a home pack of a content language (the manifest's
`contentLanguages`, a `packs` entry `{ "home": true, "publish": false }`), which needs only its name
and flag: `packs/<lang>/pack.json`, the flag `img/flags/<lang>.png` and, as every pack has them, the
flag's drawing `img/flags/<lang>.svg` and `ATTRIBUTION.txt` with the drawing's sources and its
render command. `pack.json` has exactly four fields, each a non-empty text:

```json
{ "lang": "zz", "label": "Zedish", "locale": "zz", "flag": "img/flags/zz.png" }
```

`lang` is the folder's name; `label`, shown beside the flag, plain text of at most 40 characters
(no `<`, `>`, `&` or control character); `locale` `ll` or `ll-CC`; `flag` exactly
`img/flags/<lang>.png`, a PNG of at most 64 KB. With no `i18n/`, `backend/` or `status/` yet, set-up
passes with one warning per backend bundle: the default language serves its keys until `backend/`
has them, and `web`'s warning names the missing `lang.<lang>` too. A backend bundle's keys are in
`.niord/base/catalog.json`, and their English is in the niord-base jar,
`defaults/base/texts/en/<bundle>.properties`, which the zip does not carry: the in-depth guide's [The base product's backend files](docs/country-guide-in-depth.md#the-base-products-backend-files)
says how to read it. To offer the language as a UI language, the pack adds `i18n/<app>/<lang>.json` with its
`status/` files, as above, and the manifest names it in `uiLanguages`.

**The escape hatch**, `fe/missing-keys-accepted.json` (absent means empty), lists the keys a pack may
lack for now: staging fills such a key of a UI bundle from `en`, the deployment's default language
serves a backend key, and the pack check passes it. It is a list of entries of exactly these fields,
with `bundle` only for `"app": "backend"`:

```json
[
  {
    "lang": "zz",
    "app": "backend",
    "bundle": "mail",
    "key": "mail.foo.subject",
    "reason": "no Zedish text yet",
    "added": "2026-11-02"
  }
]
```

`app` is `admin`, `info` or `backend`, `reason` not blank, `added` a date. An entry for a language or
application no build uses fails; one whose key is translated now, supplied by a text override or gone
from the catalog is a warning: remove it. Set-up prints the whole list when it is not empty.

The pack tools run from the country's `fe/`, from the base product the country is set up against;
they edit the home pack under `packs/` (a pinned pack is its home's, never edited here) against the
pinned zip's catalog, `.niord/base/catalog.json`, after `node setup.mjs` has unpacked it:

```bash
node ../.niord/kit/pack.mjs migrate <lang>                     # apply the base product's key renames
node ../.niord/kit/pack.mjs prune <lang>                       # delete keys the catalog lacks
node ../.niord/kit/pack.mjs prune <lang> --base <version>      # ... that no published catalog from <version> has
node ../.niord/kit/pack.mjs accept <lang> --app admin <key>... # the owner accepts a text
node ../.niord/kit/pack.mjs accept <lang> --app backend --bundle <bundle> <key>...
```

`accept` takes each key off `unaccepted.json` and records its fingerprint; it is the way a pack's
texts are accepted. A backend key must be in the catalog's bundle and have a text in
`backend/<bundle>.properties`; `--bundle` goes with `--app backend` only, and `--app backend` needs
it. `--packs <dir>` and `--catalog <file>` name another folder or catalog. Each line names the pack
by the folder it edited (`packs/<lang>/…`). The pack check itself is set-up's: run
`node setup.mjs`, which checks every pack the manifest names with the country's text overrides.

### Pruning against published versions

The source of a pack keeps the keys the base product dropped (the staged bundles are stripped), so a
key removed and later added again keeps its translation. Once every consumer of the pack is on a
version at or above `<version>`, `prune <lang> --base <version>` deletes the keys that no published
base product catalog from that version up has, the backend lists of every bundle some of those
catalogs still has included: a bundle the base product dropped whole keeps its file, fingerprints
and list until the home removes them by hand, and the pack check names the bundle the catalog does
not have until then. It reads the published
versions through the country's Maven wrapper, `../be/mvnw`, as set-up fetches the zip: Maven resolves
the newest `org.niord.web:niord-fe-src` with `-U`, which fetches the registry's
`maven-metadata.xml`, then every version in the head local repository's metadata files (the one `-U`
writes; a `-Dmaven.repo.local.tail` is never read, since Maven does not refresh its metadata) from `<version>` up
has its zip fetched into `.niord/catalogs/` and only its `catalog.json` read, as data, held to the
catalog format and to its version. `<version>` must be a published version; `--base` takes no
`--catalog`. Each version costs one Maven run and one zip of about 4 MB kept in the local repository,
and is printed as it is read, `pack prune: reading the catalog of <v> (<i> of <n>)`: run it rarely,
once every consumer has passed a version.

### The canonical archive

What a home publishes and a consumer pulls, and what set-up stages into `.niord/packs/<lang>/` for
every pack but `en` ([lib/pack-archive.mjs](lib/pack-archive.mjs)): a zip of exactly these files, for
the language `<lang>`:

| Entry                         | What                                                                         |
| ----------------------------- | ---------------------------------------------------------------------------- |
| `pack.json`                   | required, read with the strict schema; its `lang` the folder's               |
| `i18n/admin/<lang>.json`      | the administration app's text, when the pack has it                          |
| `i18n/info/<lang>.json`       | the public site's text, when the pack has it                                 |
| `backend/<bundle>.properties` | the backend half, each bundle named `^[a-z][a-z0-9]*$`                       |
| `img/flags/<lang>.png`        | required; a PNG of at most 64 KB                                             |
| `img/flags/<lang>.svg`        | the flag's drawing                                                           |
| `ATTRIBUTION.txt`             | the drawing's sources and its render command                                 |
| `status/fingerprints.json`    | the English each accepted text was translated from (the fingerprint warning) |

`status/unaccepted.json`, the reviewers' notes, is left out, the one file left out silently. Any
other file in the pack folder, a link, a folder where a file is expected, a folder, empty or not, that
holds no entry of the layout, or anything that is not a regular file fails, naming it. The text
entries (`.json`, `.properties`, `.txt`, `.svg`) have CRLF and CR line endings turned into LF, and
the zip's entries are sorted and dated 1980-01-01, so the same files give the same archive on the
same Node build, whose zlib deflates it, a Windows checkout with `core.autocrlf=true` included. A
publish never rebuilds: `validate` builds the archive once, `publish` pushes those bytes, and a
consumer verifies them against its pin. Caps: 32 entries, 2 MiB an entry, 8 MiB in all, inflated;
the OCI client's own cap, 16 MiB, bounds the layer's bytes on the wire and sits above, since a stored
archive of nearly 8 MiB of content is a little over 8 MiB. A pulled archive is held to the same
layout and caps, and its `pack.json` to the strict schema before anything is staged, a refusal
naming `niord-lang-<lang>`; a pinned or local pack, staged from the archive, has no
`unaccepted.json`, so the pack check leaves its status rows to its home and keeps only the warning
for a text translated from another English text.

### Publishing a pack

`.github/workflows/pack.yml` (`init` writes it; a version bump may ask for the kit's
`templates/pack.yml` to be copied over it, [API-CHANGES.md](API-CHANGES.md)) publishes a home pack the
manifest marks `publish: true` as `ghcr.io/<owner>/niord-lang-<lang>:<run>`, `<owner>` the repository's
GitHub owner in lower case and `<run>` the workflow's run number, with the repository's own workflow
token. It runs on a push to `main` that changes one pack folder under `fe/packs/` (a push that
changes two fails: dispatch `publish` for each; a push that removed the folder publishes nothing,
with a notice; a change to a pack's `status/unaccepted.json` alone, the reviewers' notes the archive
leaves out, starts no run, and the count of changed folders leaves it out too) and by dispatch, with
the inputs `lang`, `action` (`publish` or `withdraw`), `tag` and `reason`. A push whose previous
commit is not in the history (a force-push) fails, asking for a dispatch. Runs never overlap, and
none is dropped: the one concurrency group queues every pending run (`queue: max`), where GitHub's
default keeps one pending run and cancels it when a third arrives, which could lose a withdrawal.

**The first publish** is the first push to `main` that changes a pack folder: a home that adds
`pack.yml` and its pack in one merge — DK's bump to the version that brought publishing — publishes
at that merge. Before that merge the owner checks the organisation's package setting (that a
workflow token may create a package, and that a package may be public); if the merge's publish
fails on it, the owner fixes the setting and the home dispatches `publish`. A home dispatches
`publish` only when no pack folder changed. **The tag** is the workflow's run number, which GitHub
counts per workflow file: a renamed or recreated `pack.yml` starts again at 1, and `publish` refuses
"exists already" until the numbering passes the old tags; a tag is never overwritten. A tag that
exists is a completed publish; to publish again, dispatch `publish`, which gets a new run number.
Three jobs:

- **validate** (`contents: read`, `packages: read`): which pack — the dispatch's, whose `lang` must
  be two or three letters a–z with a folder under `packs/`, or the one a push changed; then
  `node setup.mjs`, which checks every pack, for the applications the manifest uses it in, against
  the pinned base product's catalog with the strict `pack.json` schema and the markup rule and fails
  as the home's own build does; `pack.mjs latest-catalog --out .niord/latest-catalog.json`, the
  newest published base product's catalog, through Maven; in this job these two steps alone hold the
  workflow token, which Maven needs for GitHub Packages. Then, with no token,
  `pack.mjs validate <lang> --catalog .niord/latest-catalog.json --out <dir>`: it refuses a language
  that is not one of the manifest's home packs, builds the canonical archive `niord-lang-<lang>.zip`
  with its `.sha256` beside it, runs the pack check of that one pack against the latest catalog as
  information only — printed and in the run summary, since consumers are on different versions — and
  says whether the manifest publishes the pack; it fails only when no archive can be built. The job
  checks the archive with `sha256sum -c` and hands it on with the kit's OCI client, `lib/oci.mjs` —
  one file, Node's built-ins and `fetch` only, importing nothing else of the kit — as the artifact
  `pack`, both SHA-256s as job outputs. Which pack runs on every run; once it named a pack, set-up,
  the client's hand-over and the upload run too, the catalog and the archive only on a push or a
  dispatch of `publish`. The information-only
  check runs without the home's escape hatch and text overrides, standing in for a consumer with
  neither: GL's run summary lists its 163 hatch-accepted backend keys as errors on every publish, by
  design. Because `validate` checks the one pack alone, that check also reports each
  `<app>.verbatim` entry as a line of its own (a verbatim text is compared with the other packs
  checked with it, and there are none) and the escape hatch's keys as "lacks" lines; it still
  exits 0, and set-up, which checks the pack with the manifest's other packs and the home's hatch,
  passes them. The archive
  holds every half the pack folder has; a half the home's manifest does not use
  is published with that check alone, and a consumer that uses it checks it fatally at its set-up.
- **publish**, on `main` only, for a pack with `publish: true`, on a push or a dispatch of `publish`:
  `packages: write` and nothing else, no checkout, no npm. It verifies the archive and the client
  against validate's SHA-256s, then `node oci.mjs publish --archive <file> --sha256 <hex>`, with every
  other value in its environment, re-hashes the archive, refuses a tag that exists, pushes the empty
  withdrawn marker when the pack has none yet (its first publish) **before** the pack, pushes the
  archive unchanged, and writes the reference with its digest into the run summary, with the line to
  add to `pack-releases.md`. A first publish that broke between the two pushes leaves only the
  marker, which no consumer pins; re-run the failed job (it keeps its run number) to finish it.
- **withdraw**, by dispatch of `withdraw` on `main` only, with the tag (digits) and a one-line reason:
  `packages: write` only; it verifies the client and runs `node oci.mjs withdraw`, which reads the
  marker, resolves the tag's digest, adds `{ digest, tag, reason, date }` unless it is listed already,
  and pushes the whole marker. The summary gives the line for `pack-releases.md`.

No run block holds an expression, and every one starts with `set -euo pipefail`; the language, the
tag and the reason reach the scripts through `env:` only. A translation commit to a pack with
`publish: false` is checked and publishes nothing. After the first publish the owner of the
repository makes the GHCR package public once, since consumers pull it anonymously; a consumer can
pin the pack only then.

**On the registry**, `<registry>/<owner>/niord-lang-<lang>`:

- `:<n>` is an OCI image manifest (`application/vnd.oci.image.manifest.v1+json`, with the annotations
  `org.opencontainers.image.source` and `org.opencontainers.image.version`) whose config,
  `application/vnd.niord.lang-pack.config.v1+json`, is `{"format":1,"lang":"<lang>"}` and whose one
  layer, `application/vnd.niord.lang-pack.v1+zip`, is the canonical archive;
- `:withdrawn` is the withdrawn marker: the same config and one layer
  `application/vnd.niord.lang-pack.withdrawn.v1+json`,
  `{"format":1,"lang":"<lang>","withdrawn":[{"digest":"sha256:…","tag":"<n>","reason":"…","date":"<yyyy-mm-dd>"}]}`.
  An unknown field fails; the tag is digits, the reason one line of at most 500 characters without
  control characters, the marker at most 256 KiB.

The client talks to `ghcr.io` over HTTPS only (a loopback registry over HTTP stands in for it in the
kit's tests and the example's harness); it answers a 401 through the registry's Bearer challenge, with
a token endpoint on the registry's host, anonymously unless it publishes or withdraws, when it asks
for `pull,push` on every request, reads included; a manifest must hash to its digest and is at most
64 KiB; a blob must hash to its descriptor's digest within its size; an upload's address stays on the
registry's host, and a write (`POST`, `PUT`) follows no redirect but fails on one, sending nothing
elsewhere; a pushed manifest's answered digest, when the registry gives one, must be the SHA-256 of
the bytes sent, and without one the manifest is confirmed by a `HEAD` of that digest; and a tag list
is read whole or not at all (more than 100 pages, or a later page missing, fails).

**`pack-releases.md`**, at the repository root (`init` writes it, outside `fe/packs/`, so editing it
runs no publish), is the home's human record: a table of every publish (pack, tag, digest, date) and
one of every withdrawal (with its reason). Add the run summary's line in the change that follows the
run. A consumer compares its first pin of a pack with the digest recorded there.

### Pinning a pack

A consumer pins a pack once its home has published it and the owner has made the package public:
until then a pull fails ("an anonymous pull needs the package to be public"), and so do the
consumer's set-up and CI on the missing pin, a `NIORD_PACK_<LANG>` copy being the local way round
([Local sources](#local-sources)). For `da` the order is: DK's bump merge publishes `niord-lang-da`,
the owner makes `ghcr.io/sifa-dk/niord-lang-da` public, then GL pins it. The consumer names the pack
`{ "pinned": true }` in its manifest, with `owner` when the pack's GHCR owner is not `sifa-dk`, and
writes its pin:

```bash
node setup.mjs --update-pins            # every pinned pack: the highest tag its withdrawn marker does not list
node setup.mjs --update-pins da=12      # da at tag 12, refused when that tag is withdrawn or absent
```

It reads each pack's withdrawn marker first: a pack without one is unreadable, and the command
stops and writes no pin of any pack (the home's next publish pushes the missing marker). It takes the
tag given or the highest numeric tag whose digest the marker does not list, pulls it and verifies it
as a pack, as set-up would (the archive cached for the set-up that follows) — a candidate that is not
a readable pack is skipped with a line, and refused when named — rewrites `niord-pins.lock.json` (the
base pin kept, the pack pins sorted), prints each pin old → new (`, unchanged` when it is), and then
runs the whole set-up. Before a **first** pin is committed, compare the digest it wrote with the one
the home's `pack-releases.md` records; after that, each bump of a pin is a reviewed pull request that
shows the new digest. `NIORD_PACK_REGISTRY` names a loopback registry (`http://127.0.0.1:<port>`) in
place of `ghcr.io`, for the kit's tests and the example's harness; any other host is refused. Only
they write a loopback pin, and set-up accepts one wherever a lock holds it: the digest guards the
bytes, and the lock's change shows in review. The build report records each pinned pack's
`reference`, the pin it was pulled by.

### Withdrawing a pack

A home withdraws a bad tag with `pack.yml`'s dispatch: `action` `withdraw`, `tag` and `reason`. Check
the run's summary line, `niord-lang-<lang>:<tag> withdrawn` or `… was withdrawn already`, before
recording the withdrawal; dispatching again is safe, since a digest listed already is a notice. Every
consumer's next set-up fails on a pin that names it, with the reason, until the consumer moves the pin
(`node setup.mjs --update-pins`, to the home's next pack or back to the last good one). A `|` in the
reason is written `\|` in the summary's row for `pack-releases.md`, and the row is fenced longer than
any run of backticks in it; the marker keeps the reason as given. Each withdrawal leaves the previous
`:withdrawn` manifest as an untagged version of the package: harmless, and the owner may delete it on
the package's page. The
withdrawal's validate job runs set-up to obtain the verified client, so it needs set-up green on
`main`; when `main` is red, withdraw by hand from a checkout of `main` in which `node setup.mjs` has
unpacked the pinned kit into `.niord/kit/` (the stub verifies both packages by their hashes before
any check runs, so a set-up that fails a check has done so), with a token that may write the package
(`write:packages`):

```bash
cd fe
PACK_LANG=<lang> PACK_TAG=<tag> PACK_REASON='<one line>' \
  PACK_TOKEN=<token> GITHUB_ACTOR=<user> \
  GITHUB_REPOSITORY_OWNER=<owner> GITHUB_REPOSITORY=<owner>/<repository> \
  node ../.niord/kit/lib/oci.mjs withdraw
```

and add the line it prints to `pack-releases.md`.

## Text overrides

A country's own wording of an ordinary base product key — a term it says differently from the other
countries that use the same pack — without changing the shared pack and without asking the base
product for a brandable key. Staging builds each bundle from English, then the language's pack, then
the text overrides, each key in its English place; the country's branding comes over them at
runtime, and the deployment's Dictionary over that. One file per language,
`fe/text-overrides/<lang>.json`, normally absent:

```json
{ "admin": { "messages.filter.domain.label": { "text": "Waters", "fp": "79fa33618d8eaa1f" } } }
```

A section per application (`admin`, `info`); per key the country's `text` and `fp`, the fingerprint
of the English text the override was written against — the key's `fp` in the base product's
catalog, `.niord/base/catalog.json`. An override is checked like a translation, and set-up fails:

- a language that is not a UI language of the section's application;
- a key not in the catalog (named with its new key when the base product renamed it), a key the
  branding schema names, and any `country.*` key — those are branding's;
- an entry with another field, or without a 16-digit `fp`; an empty text where English's is not;
- placeholders other than English's; markup, a date pattern or a word list that the pack check
  would fail in a pack.

It warns, once per language, about overrides written against another English text than today's,
listing each with the fingerprint to record once it has been looked at; and about an override equal
to the pack's text (English's, for `en`), which is redundant. An override of a key the pack has
with other words is listed as a deliberate difference. A key an override supplies counts as present
in the pack check, so an override can stand in for a key a foreign pack lacks (an escape-hatch entry
for that key is then stale). Every set-up prints the count per language —
`text overrides: en 1 (admin 1, info 0)`, or `text overrides: none` — and the build report records it.

## Country dependencies

A country's own npm packages never go into the generated `package.json`; they go in
`fe/country/package.json`, committed with its `package-lock.json`:

```json
{
  "name": "niord-xx-country",
  "private": true,
  "dependencies": { "pretty-bytes": "7.1.0" },
  "niord": {
    "dependencies": {
      "pretty-bytes": { "app": "admin", "reason": "The harbour page states the size of its notes" }
    }
  }
}
```

Every version is exact. Each package names the application it is for (`admin`, `info` or `both`)
and why the country needs it, and a package that runs an install script names why it may, under
`niord.installScripts`. Nothing else is allowed — no dev, optional, peer or bundled dependencies, no
overrides, scripts or workspaces — and `description` is optional. npm ignores the `niord` field, so
the file stays a real manifest for npm and Dependabot. Neither file means no packages; one without
the other fails.

After each change to `package.json`, make the lock with `node setup.mjs --update-country-lock`. It
copies the merged manifest, the base product's lockfile and `.npmrc` into `.niord/country-lock/`,
runs `npm install --package-lock-only --ignore-scripts --no-audit --no-fund` there (the registry is
needed) and writes `country/package-lock.json` with `packages[""]` and exactly the entries the
country adds to the base product's tree — a lock made by npm alone would hoist shared packages at its
own versions. Set-up merges both into the generated `package.json` and lockfile. Every set-up fails:

- either file outside its shape, an inexact version, a name that is not an npm name or is
  `@niord/…`;
- a country package the base product has at another version (the same version passes, and is
  listed);
- a lock entry that changes a base product entry — its version, resolved URL or integrity — or
  repeats one unchanged;
- a package the merged tree lacks or holds outside its range, or a lock entry nothing reaches: the
  lock is stale, so run `--update-country-lock`;
- a resolved URL outside `https://registry.npmjs.org/`, an integrity that is not sha512, a link;
- an install script without a reason;
- a peer range of an added package that the base product's installed version does not satisfy, or a
  required peer that is missing (the workspace's `.npmrc` lets npm install past peer conflicts, so
  npm would hide both);
- any package the lock adds, a transitive one included, published less than 7 days ago. Set-up asks
  the registry for each publish time and keeps every answer older than 7 days in
  `.niord/country-dependency-ages.json`, so a repeated set-up needs no network for it; no answer
  fails, naming the package.

The registry lookup is the kit's one network call, through Node's own `fetch`, which honours
`HTTPS_PROXY` only when `NODE_USE_ENV_PROXY=1` is set (Node 24) and never reads npm's `proxy` or
`https-proxy` settings. Behind a proxy, set both variables for `node setup.mjs`, or fill
`.niord/country-dependency-ages.json` by hand — `{ "format": 1, "published": { "<name>@<version>+<integrity>":
"<publish time, ISO 8601>" } }` for each entry the lock adds, the integrity as the lock has it and
the time from `npm view <name> time --json` — in each checkout, since the file is gitignored. A CI
runner needs neither.

Set-up lists the packages with their version, application and reason, as it lists the escape hatch,
and the build report records them; lint refuses a package in the other application's files.
`init`'s `.github/dependabot.yml` watches `/fe/country` for security updates only, and reads nothing
until the country adds the folder with its first package; a Dependabot pull request that rewrites the
lock fails set-up until `--update-country-lock` is run on its branch.

## npm audit

`npm run build` runs the audit before both builds, and the generated `audit` script runs it alone,
as the country's weekly workflow does: `node ../.niord/kit/audit.mjs`, which runs
`npm audit --omit=dev --audit-level=high` and `npm audit signatures` over the installed tree. An
advisory of severity high or critical, or a package whose registry signature is invalid or missing,
fails it unless `fe/audit-accepted.json` lists it with a reason:

```json
[
  { "advisory": "GHSA-xxxx-xxxx-xxxx", "package": "<name>", "reason": "…", "added": "2026-11-02" },
  { "signature": "<name>@<version>", "reason": "…", "added": "2026-11-02" }
]
```

An absent file is an empty list; an unknown field, an empty reason, a bad date or an entry listed
twice fails, and an advisory npm names without a GHSA id cannot be accepted. The list is printed in
full on every run, as the escape hatch is, and set-up prints it too and records it in the build
report; an entry that no longer matches a finding is a warning — remove it. When npm's answer is not
JSON (the registry did not answer), the audit fails: no build goes out without it. Two outside
faults are run again, and the audit says so each time:

- **An answer of the registry's that npm could not read**, which npm reports as
  `invalid json response body at <url>` (code `FETCH_ERROR` from `npm audit signatures`;
  `npm audit` gives no code): the registry has answered a request with the text `OK`. That command
  is run again, three attempts in all, after a pause of 2 s and then 6 s, 8 s at most beside npm's
  own runs, and each retry is printed,
  `npm audit signatures: attempt 1 of 3 failed (exit 1), the registry's answer could not be read: invalid json response body at …; trying again in 2 s`.
  The first report that comes decides, as without a retry: an invalid or missing signature is a
  finding, and a report is never run again. A third unreadable answer fails, its finding naming `after 3 attempts`.
  No other fault is run again: npm retries a connection that failed and a `5xx` answer itself before
  it reports them, and a `401`, `403` or `404`, another `FETCH_ERROR` (a timeout npm has retried),
  an answer that is no report, or the unreadable answer's error beside a report's own lists, fails
  at once.
- **`npm audit signatures` exiting 3221226505** (a Windows process crash) with no report: it is run
  once more with a fresh npm cache of its own, removed afterwards,
  `npm audit signatures: exit 3221226505 with no report, a Windows process crash; running it once more with a fresh npm cache`;
  a second crash fails, its finding naming `(exit 3221226505), after one retry with a fresh npm cache`.
  The crash's run again belongs to the attempt it crashed in and happens once per audit, so an
  unreadable answer after it is retried as above, and a crash in a later attempt fails.

A finding in a base product package is fixed by a later base product version; list it until then.

## The checks on every country build

`npm run build` ends with `node ../.niord/kit/check-build.mjs`, which holds what the two
builds wrote — `dist/niord-app/browser` and `dist/niord-info/browser` — to what the country may
ship. Every finding is printed before it fails (exit 1); a missing output or the public site's
missing stats file exits 2 (run `npm run build`), and so does a missing `angular.json` (run
`node setup.mjs`). It fails:

1. a UI bundle (`assets/i18n/<lang>.json`) that is not one of the application's `uiLanguages`, or one
   of them missing;
2. a flag that is not `<lang>.png` of a language the application names (the administration app's UI
   and content languages, the public site's UI languages), or one of them missing;
3. anything from a pack's `status/`, `node_modules/` or `.niord/`, an `ATTRIBUTION.txt`, a flag's
   drawing (`assets/img/flags/*.svg`), or another tree's assets (`assets/default/` or a two-letter
   folder under `assets/`);
4. an external font or style URL, and an absolute URL (`http://`, `https://` or `//host`) in a `.js`,
   `.css` or `.html` file that neither the base product's `scripts/build-urls-allowed.json` nor the
   country's `fe/build-urls-allowed.json` lists (below) — legal documents are not read, being branding
   data;
5. a country-only utility that set-up recorded in `.niord/build/country-utilities.json` and that is
   in no stylesheet of its application, looked for as Tailwind writes its selector
   (`.sm\:grid-cols-\[3fr_2fr\]`): a utility the generated Tailwind scan missed is silent styling
   loss otherwise;
6. a secret in either output ([Secrets](#secrets));
7. the public site's separation check: a first-party module of the public site's build from outside
   the base product's public site and shared folders, the country's `info/`, its generated
   `.niord/generated/info/` and its own styles (`styles/tokens.<cc>.css`, `styles/fonts.<cc>.css` and
   the face files beside it), or a package the public site may not load (`keycloak-js`) — the administration
   app's code, the base product's or the country's, never reaches the public site;
8. the public site's first visit past the country's own `info/bundle-baseline.json`, by more than its
   tolerance — the same command as `npm run bundle:info`;
9. an output path that two inputs write — one source per output file. `ng build` copies assets
   concurrently and which copy wins is not defined, so a country never "overrides" a base product
   file by staging its own where the base product's lands. The check reads the generated
   `angular.json` and resolves the asset list of the options and of every configuration of both
   applications with the builder's own code (`lib/asset-outputs.mjs`, which loads the workspace's
   installed `@angular/build` at run time, as the base product's `contents:check` does), each
   distinct list once, and names the configurations, the output path and both inputs:
   `one source (niord-app:production, development, local, test): assets/img/niord.png is written by 2 inputs (…)`;
10. the public site's built page, `dist/niord-info/browser/index.html`, whose html tag's `lang` is not
    the public site's default language in the manifest, or whose title is not that language's
    `SITE_TITLE` as the built bundle (`assets/i18n/<lang>.json`, after packs and text overrides) has
    it: `index (dist/niord-info/browser): index.html has lang "en", not <lang>, …`. The fix is the
    country's own page, `info/app/index.html` (the index seam), in its default language; a country
    whose public site defaults to English, with no text override of `SITE_TITLE`, may keep the base
    product's.

A country starts its baseline by copying the base product's and recording its own first visit over
it (the report takes its landing route and entry points from the file it updates), then commits
`info/bundle-baseline.json`; until it has one, check 8 is a warning saying so, so an untouched
skeleton still builds. Recording it again is a deliberate act, after a change explains the growth:

```bash
cp .niord/base/ui-apps/info/bundle-baseline.json info/bundle-baseline.json
npm run bundle:info -- --update-baseline
```

**The country's own addresses** — its authority's site in the footer, its accessibility statement —
go in `fe/build-urls-allowed.json`, never in the base product's list (R1). It has the base list's
format, each entry with a reason, and may scope an entry to one application with `app`:

```json
{
  "format": 1,
  "note": "The country's own addresses in its public site.",
  "allow": [
    {
      "pattern": "https://www\\.example\\.org/accessibility$",
      "app": "info",
      "reason": "The accessibility statement the footer links."
    }
  ]
}
```

and stricter entries: each pattern is one literal `https:` address ending with `$` (the check anchors
its start) — the escapes `\.`, `\/`, `\?` and `\-` and nothing else of a regular expression, so no
`^` and no unescaped dot — whose host is a dotted name of
letters, digits and hyphens, with no IP address, port or credentials. An address that needs other
syntax (a `+`, `{` or `}`, escaped or not) is not one literal address: it is a base product change,
as the refusal says. An unknown field, a missing
reason or a `format` other than 1 fails, and so does a link or other non-regular file in its place;
an absent file is an empty list. A URL both lists admit counts for the base product's.

`app`, `"info"` or `"admin"`, scopes the entry to that application's output: it admits its URL in
that application's build only, and another value fails. An entry without `app` admits its URL in
either. A `files` glob such as `main-*.js` matches both applications' main bundles, so an address
of one application is scoped with `app`, not with `files`. A URL that only an entry scoped to the
other application admits fails as any other, naming that entry:
`… (fe/build-urls-allowed.json allow[0] admits it in the public site's build only)`.

Every build prints each entry, `urls: the country's allow-list admits <url> — <reason>`, a scoped
one with its scope (`… admits <url> in the public site's build only — <reason>`), and warns about an
entry that admits nothing: a scoped one when it admits no URL of its application's build
(`… admits no URL of the public site's build, so it permits nothing: remove it`), one without `app`
when it admits none in either application's build. Remove it. `.github/CODEOWNERS` names the file.

The secret scan's allow-list is the base product's, like a CSP source: a false positive a country
needs is a base product change, with its reason.

## The backend build

`node fe/setup.mjs` runs before any Maven build of the country's backend: locally before
`be/test-it`, `./mvnw package` or a launcher that starts the backend (the country's `start-local.sh`
runs it first: [Running it locally](#running-it-locally--start-localsh)), and in every CI job that runs
Maven. Set-up stages every pack but `en` — home, pinned or local — into `fe/.niord/packs/<lang>/`, in
the layout of the pack's canonical archive ([The canonical archive](#the-canonical-archive)), and
the country's `be/pom.xml` reads each pack's backend half and flag there, never `fe/packs/<lang>/`
in place: a pinned pack is nowhere else, and a home pack is read as its checks passed it.

The pom copies each staged pack with two executions of the inherited `maven-resources-plugin` at
`process-resources`: the backend half into `defaults/packs/<lang>/`, where the base product's text
layering reads a language pack, and the flag into `META-INF/resources/img/flags/`, the flag a draft
PDF shows. For the example language `zz`:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-resources-plugin</artifactId>
  <executions>
    <execution>
      <id>copy-zz-pack-backend</id>
      <phase>process-resources</phase>
      <goals>
        <goal>copy-resources</goal>
      </goals>
      <configuration>
        <outputDirectory>${project.build.outputDirectory}/defaults/packs/zz</outputDirectory>
        <resources>
          <resource>
            <directory>${project.basedir}/../fe/.niord/packs/zz/backend</directory>
            <filtering>false</filtering>
            <includes>
              <include>*.properties</include>
            </includes>
          </resource>
        </resources>
      </configuration>
    </execution>
    <execution>
      <id>copy-zz-pack-flag</id>
      <phase>process-resources</phase>
      <goals>
        <goal>copy-resources</goal>
      </goals>
      <configuration>
        <outputDirectory>${project.build.outputDirectory}/META-INF/resources/img/flags</outputDirectory>
        <resources>
          <resource>
            <directory>${project.basedir}/../fe/.niord/packs/zz/img/flags</directory>
            <filtering>false</filtering>
            <includes>
              <include>zz.png</include>
            </includes>
          </resource>
        </resources>
      </configuration>
    </execution>
  </executions>
</plugin>
```

They are executions of the inherited plugin rather than a `<resources>` section, which would replace
the inherited resources and their filtering. The copies land in `target/classes`, never under
`src/main/resources`: the image tools' override check refuses a committed `defaults/packs/`.

A copy from a folder that does not exist copies nothing and passes, so a build before set-up would
make a jar without the pack's texts. The pom's check refuses it, with one pair of `<available>`
lines per pack the pom copies, naming the two paths its copies read:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-antrun-plugin</artifactId>
  <executions>
    <execution>
      <id>require-staged-packs</id>
      <phase>validate</phase>
      <goals>
        <goal>run</goal>
      </goals>
      <configuration>
        <target>
          <fail message="fe/.niord/packs/ lacks a pack this build copies: run node fe/setup.mjs first">
            <condition>
              <not>
                <and>
                  <available file="${project.basedir}/../fe/.niord/packs/zz/backend" type="dir"/>
                  <available file="${project.basedir}/../fe/.niord/packs/zz/img/flags/zz.png" type="file"/>
                </and>
              </not>
            </condition>
          </fail>
        </target>
      </configuration>
    </execution>
  </executions>
</plugin>
```

- It has no `<version>`: Spring Boot's parent manages `maven-antrun-plugin` (3.2.0 at Spring Boot
  4.0.7), and the pom inherits that through `niord-parent`.
- It is not the enforcer plugin's `requireFilesExist`: that rule refuses every path with `..` in
  it, staged or not (it compares the path with its canonical form), and the pom reaches `fe/` only
  through `${project.basedir}/../fe`.
- It runs at `validate`, the first phase, so it guards every lifecycle build: `./test-it`, the image
  job's `./mvnw package -DskipTests` and a launcher's build alike.
- A goal called directly — set-up's `../be/mvnw dependency:copy` of the zip, the image job's
  `-N …:unpack` of the image tools — runs no lifecycle phase, so the check never blocks set-up
  itself.

A country that adds a pack to its manifest adds its two copies and its pair of `<available>` lines
in the same change. A pack without a backend half (a content-only language, which can start with
`pack.json` and a flag alone) gets the flag's copy and the flag's `<available>` line only: the check
names exactly the paths the pom copies, and a `backend` line for such a pack fails even after
set-up. An English-only country stages no pack and needs neither the copies nor the
check, until its first pack.

**In the image workflow** the backend job `init` writes
([Starting a country](#starting-a-country--init)) runs, after the checkout and in this order: the
JDK (`setup-java`, with GitHub Packages' credentials), the Maven repository cache,
`Read the base version` (which refuses an empty `MODULE` and a `<parent>` that is not `4.0.<n>`,
and makes the run's version `<base>.<run number>`), Node (`setup-node`, without an npm cache: the
job runs no npm), `Set up the workspace` in `fe/`, `Build the jar`
(`./mvnw $MVN_FLAGS package -DskipTests`), `Fetch the image tools`, `Override check`,
`Split the jar`, `Pack the split jar and the image tools with their SHA-256`, and the upload of
`backend-dist.tar` with its `.sha256` as the artifact `backend-dist`. The workflow's token is bound
on `Set up the workspace`, `Build the jar` and `Fetch the image tools`, the three steps that run
Maven, and nowhere else in the job. Every run block starts `set -euo pipefail`. The push job needs
the frontend job and this one, verifies `backend-dist.tar.sha256` beside the frontend's two artifacts,
and runs `Check the version` — the backend job's version is `4.0.<base>.<run>` and the public site's
image carries that tag — before it pushes anything
([image:info](#the-public-site-image--imageinfo)).

**In the country's CI** `init` writes `ci.yml` from `templates/ci.yml` when none exists, with Node
and set-up before Maven; a country that keeps a `ci.yml` of its own adds them itself, after its JDK
set-up and before its Maven build:

```yaml
- uses: actions/setup-node@v4
  with:
    node-version: '24'
- name: Set up the workspace
  working-directory: fe
  env:
    GITHUB_TOKEN: ${{ github.token }}
  run: |
    set -euo pipefail
    node setup.mjs
```

## Secrets

Every seam, branding, legal and text-override file a country writes ships to anonymous browsers,
so a secret never goes in one; a credential a feature needs stays on the backend. Set-up scans the
country's inputs — `admin/`, `info/`, `styles/`, `text-overrides/`, its home packs and the pinned and
local packs it staged, the manifest, `country/package.json` and its lock, the escape hatch,
`audit-accepted.json` and the pins — and the build check scans both builds' output, with
`lib/secrets.mjs`:

- a PEM private key;
- a token by its issuer's prefix: AWS access key ids, GitHub (`ghp_`, `gho_`, `ghu_`, `ghs_`, `ghr_`,
  `github_pat_`), GitLab, Slack, Google API keys, Stripe live keys, npm, Anthropic and OpenAI project keys;
- a JWT, and a user and password written into a URL;
- a name that ends in a secret-like word — password, secret, token, API key, client secret, private
  key, access key (`apiKey`, `CLIENT_SECRET`, `password2`, not `tokenData`) — given a quoted value of 16
  or more characters without whitespace and with at least 3.5 bits of entropy per character. A
  placeholder (`${…}`, `<…>`), a dotted key path, a constant's name, a hyphenated slug and fewer than 32
  hex digits never count.

A finding names the file, the line and the pattern, and never the value — at most its first four
characters and its length — and fails set-up, with every other finding, before anything is staged
for the applications. A finding in a pinned pack, which the country cannot edit, names the pin and
what to do: `secrets: pinned pack <lang> (<reference>): <file>:<line>: … — pin another tag (node
setup.mjs --update-pins <lang>=<tag>) and tell its home`. Binary files (images, fonts, PDFs, anything with a NUL byte) are not read. A false positive is
listed in the base product's `secret-scan.json` (`{ pattern, files, scope, reason, country? }`,
`scope` `inputs` or `dist`), never in a country file.

## The public-site image — image:info

The public site is released as one container image per country, packaged from the built output:

```bash
npm run build
npx playwright install --only-shell chromium     # once; --with-deps on a fresh Linux runner
npm run image:info -- --tag <registry>/<name>:<version>
```

`image:info` (`node ../.niord/kit/image-info.mjs`) refuses, before Docker builds anything:

- a reference that is not `[host[:port]/]name[/name…]:tag`, the host and names in lower case — no
  digest, no leading `-`, never without its tag; it is one argument of Docker's, never part of a
  command line;
- `NIORD_BASE` or a `NIORD_PACK_<LANG>` while `CI` is set, and a workspace set up from a local source
  or with no build report (as `npm run build` does): what is pushed is built from the pins alone;
- a build without its stats file;
- an output that is not a production build or holds what the image may not ship: a source map or a
  `sourceMappingURL` comment, an `index.html` script or stylesheet without a content hash, a `.br` or
  `.gz` file already there, a link or other non-regular file, and any file outside the base product's
  `info-image-allowed.json`.

It then copies the output into a fresh temporary folder with the base product's Dockerfile and its
five nginx files — never the workspace — compresses its text files there once (brotli and gzip, at
their highest settings, so nginx serves them pre-compressed), builds the image with the build report as its labels
`org.niord.build-report` and `org.opencontainers.image.version`, and removes the folder. The image's
base is `alpine:3.22` from Docker Hub, or the reference the environment variable `ALPINE_IMAGE`
names, such as a mirror of it the country has access to (`registry.example.org/library/alpine:3.22`),
which `image:info` then logs. The labels
are written as a LABEL instruction into the context's copy of the Dockerfile, never on the command
line (a long report exceeds Windows' 32,767-character command line and Linux's 128 KiB argument
cap), in lines of at most 16 KiB that Docker joins (it refuses a line of 64 KiB), and read back
with `docker inspect` after the build; a difference fails the run. A Docker or Node call
that cannot start is named with its error's code and message (e.g. `ENAMETOOLONG`). Then it
checks the image it built, not the output beside it: it copies the image's web root and security
headers out, which must hold exactly the output, byte for byte, beside the compressed twins; runs the
separation check on that build's stats, the layout check in Chromium on the copied files under the
copied policy, and the container test against the image — the proxy allow-list, the security
headers, the start-up checks, and in Chromium the frame policy — in the site's default language.
Exit 0 is an image checked for a push; 1 a refusal or a failed check, every finding printed; 2 a
usage error, or Docker or Chromium missing. `--no-browser` leaves out the layout check and the
container test's browser part and says the image is not checked for a push; `--dist <dir>` takes
another output folder.

The container test asks for the default language's disclaimer, else the first legal document
staged, and for the default language's branding text. A country that has neither yet — one fresh
from `init` — is not refused: `image:info` warns, naming the file to add
(`info/assets/legal/disclaimer.<lang>.html`, `info/assets/branding/<lang>.json`), and passes the
container test `--no-legal-doc` or `--no-branding-text`, which skip exactly those probes by name and
count the skips in its last line. So an untouched country's frontend job (below) is green from its
first push, and the probes come back with the files.

`image:info` never pushes, and leaves the tag for the push. The frontend job `init` writes into
`image.yml` builds and checks the image, names it
`<REGISTRY>/<HARBOR_PROJECT>/<repository>-info:<base version>.<run number>` (the repository's name
in lower case, as a Docker reference needs) and hands it on as a `docker save` archive with its
SHA-256. `REGISTRY` and `HARBOR_PROJECT` are the workflow's own `env`, empty in the template until
the country sets them, which fails the frontend job and the push job. The push job, in the `harbor`
environment on `main` only, trusts nothing from the build jobs but the bytes it verifies: it checks
the three artifacts' hashes, checks that the backend job's version is `4.0.<base>.<run>` and that
the image carries it as its tag ([The backend build](#the-backend-build)), refuses an image name
other than `<REGISTRY>/<HARBOR_PROJECT>/<repository>-info:` with a numeric tag before it loads
anything, loads the image, logs in to the workflow's `REGISTRY` with the repository's Harbor
robot and pushes only that reference, and runs no npm, no Maven and no kit code. Its last step is a
placeholder that fails until the country replaces it with the step that builds and pushes its
administration bundle's image (niord-dk's `image.yml` is the model); the public site's image is
already pushed when it runs. Every run block of the three jobs starts `set -euo pipefail`. The CSP,
the other security headers, the proxy allow-list and the query maps are the base product's and
never parameters: a feature that needs another backend path or CSP source is a base product change.

## Paused until production

Until the first production deployment, or the first country that the base product's own developers
do not maintain, whichever comes first, part of the upgrade contract is paused. The base product and
the countries built on it are developed together by the same people, so for now:

- a country's base bump is merged when its CI is green;
- the boxes of the Base bump comment are information: an unticked box, and the red
  `niord/upgrade-actions` status it sets, hold no merge back;
- `API-CHANGES.md` gets an entry only when the base product's upgrade test demands one
  ([The example country](#the-example-country), _The upgrade test_);
- the Changes table of the backend contract, `docs/country-api.md`, gets no row.

Not paused:

- the base product's upgrade test itself. It runs in Frontend CI's Example country job, so it is
  part of the required `Frontend gate`, and it is what says that a change breaks a country at its
  next bump;
- a value for every new English text in a country's pack: a draft, listed unaccepted
  ([Language packs](#language-packs)). An accepted translation is never rewritten in a bump.

The rest of this README, `API-CHANGES.md` and the guides describe the contract in full, as it
holds again from the day it wakes. One deliberate pull request, decided by the base product's
owner, wakes it, and that pull request deletes this section.

## The upgrade report

A version bump — a new `<parent>` in `../be/pom.xml` with its pin — makes `node setup.mjs` write
`.niord/niord-upgrade-report.md` and print its path: one page saying what the new version means for
this country. Beside it, set-up writes its data, `.niord/niord-upgrade-report.json`: the same
findings as JSON, under the keys `format`, `country`, `from`, `to`, `failed`, `sections`, `fixes`,
`todo`, `look`, `stubChanged`, `templatesChanged`, `templatesUnavailable`, `otherTemplatesChanged`,
`missingForeign` and `notChecked`, which the Base bump workflow builds its checklist from. Each of `sections` is
`{ heading, entries, actions }`: `entries` the leads of the entries listed, for an `Unreleased`
compared with the previous copy's `Unreleased` per entry or a released section compared with it by
lead, and null for a section listed whole; `actions` what it asks, each in full, one per list item
after a lead-in ending in a colon, in the JSON as in the Markdown. The report compares with the
last set-up record, `.niord/build/niord-build-report.json`, which set-up writes only when every
check passed, so a red bump keeps comparing with the last good version; the report itself is
written after the checks also when one failed, since a red bump is when it is needed. A set-up of
the version already recorded writes none, and so does a first set-up, with no record, which says
so, unless `--previous <version>` names the previous version: the report then compares with that
version by version, without its files, so the parts that need them, the template comparison among
them, say they are not available. The record and the previous version's zip are in `.niord/`, which
is never committed: in a fresh clone, or on a runner, run `node setup.mjs` once at the old version,
before the pom and the pin change, to get the whole report of the bump.

Its sections, in this order, each item naming the file and the action:

- **Versions** — from which version to which, and the `API-CHANGES.md` sections and `security.json`
  fixes new since the previous version. The zip carries no release notes, and the report says so.
  It names the kit's files as `.niord/kit/API-CHANGES.md`, `.niord/kit/security.json` and
  `.niord/kit/seams.json`, from the repository root.
- **To do** — what set-up or the build fails on until it is done, and nothing else: a stub the base
  product changed, with the command that copies the kit's over it; per pack and application the new
  keys to translate (in a foreign pack: wait for its home's pack, accept English in the escape hatch
  or word it in a text override) and the translations or text overrides whose markup no longer
  matches English's; each override whose base product file changed, with the base product's diff of
  it since the recorded fingerprint, whose base file was removed, which joined the protected list,
  or whose base file a new security fix changed; the branding keys and files the schema requires and
  the country lacks, or no longer names and the country fills; text-override keys that left the
  catalog, with a renamed key's new name; a country package whose peers reject the base product's
  new versions; a pinned pack whose home withdrew the pinned digest, with the reason and
  `node setup.mjs --update-pins`. Two things set-up does not check are listed as such: keys added to
  the base product's settings file and settings keys that left the catalog (the zip carries no
  backend settings list yet), and advisories (the audit of `npm run build`, after `npm ci`). Then,
  as set-up printed it, each failed check's line that no item above stands for, so a red set-up
  never reads "Nothing.".
- **To look at** — the warnings: English changed under a translation, a text override written
  against another English text, redundant or differing from the pack, each use of a deprecated
  export with the version it is removed in (or "removal not scheduled"), escape-hatch entries
  that can go, and a line "Templates changed since `<old>`" naming each kit template that changed
  since the previous kit, with its copy or merge (or that the comparison is not available, and
  why); after the warnings, a line "Other templates changed since `<old>`" naming every other
  template of `.niord/kit/templates/` that changed, was added or was removed, with no action; then,
  under their own line saying that set-up does not check them, each new
  `API-CHANGES.md` section whose _What a country must do_ is not "Nothing".
- **New for you** — the hooks, slots, seams and API exports added since the previous version, and per
  override whether a new hook's file or slot's template is among its `basedOn` files, so the override
  may give way to it.
- **Dependency changes** — the base product's dependency versions that moved, the framework
  (`@angular/*`) first, and the country packages whose peers name a moved one.

The previous version's files — for the diffs, the new sections and the new extension points — come
only from the zip of the recorded version that the stub's fetch left in `.niord/`, read when its
SHA-256 is the record's ([Guarded overrides](#guarded-overrides) says when the diffs are given).
Without it, as after a set-up from a local checkout, each section that needs them says once why
they are not available, and the new sections are found by their version. With it, a section is new
only when its version is newer than the previous version (`Unreleased` always is); beside the
previous copy's `Unreleased`, an `Unreleased` lists the entries that copy lacks or whose text
changed, and a released section the previous copy has no heading of lists only the leads that
copy's `Unreleased` lacked (a section none of whose entries is listed is left out); any other
section is new when the previous copy has no section of its heading, or one with other text. A
bold lead is therefore never used again in a later version
([How an entry is written](API-CHANGES.md#how-an-entry-is-written)). Nothing is fetched for the
report. The Base bump workflow posts the report whole in
its comment on the bump pull request, folded below the human actions
([The base bump workflow](#the-base-bump-workflow)); the pull request's description is the app's,
or the person's who opened it.

## End-to-end specs

A country's Playwright specs live in `fe/e2e/` and run with `npm run e2e`
(`playwright test --pass-with-no-tests`, so a country without specs passes) against its running
`start:admin`. The generated `playwright.config.ts` is constant text: the base product's
`.niord/base/playwright.config.ts` spread, with `testDir: './e2e'` and
`tsconfig: './.niord/tsconfig.e2e.json'`, so a base product change to the suite's options reaches
the country with the bump. That generated tsconfig maps `@niord/admin/e2e` to the base product's
login fixtures, `.niord/base/ui-apps/admin/e2e/auth/fixtures.ts`:

```ts
import { expect, test } from '@niord/admin/e2e';
```

The fixtures name no environment: `KEYCLOAK_URL` is always the runner's to give (the local stack's
is `http://localhost:8080/auth`), with `KEYCLOAK_TEST_USERNAME` and `KEYCLOAK_TEST_PASSWORD`, and a
test that needs a login fails at once without it, naming it and pointing here — as in the base
product's own suite, whose README beside the login fixtures has the whole contract (the zip leaves
it out, as every README under the applications). `KEYCLOAK_REALM` and `KEYCLOAK_TEST_CLIENT_ID` default to `niord` and
`niord-web`. A country's suite runs against its own local stack or test environment, never another
country's.

`npm run lint` lints the specs, `e2e/**/*.ts`, with `niord-app`: `.niord/tsconfig.e2e.json` is
Playwright's program for them and the lint's (it extends the generated `tsconfig.json` and takes no
ambient types), and the base product's rules apply. For TypeScript and the lint it maps no alias but
`@niord/admin/e2e`, since its `paths` replace the root's. Playwright's loader also follows the root
`tsconfig.json`'s references, whose programs map the base product's other aliases (the country
API's entry points, `@niord/map/*`, `@niord/html/*`, and `@niord/<app>/internal/*` where the country
overrides a file), so `npm run e2e` alone would resolve them in a spec. It is the lint that keeps a
spec to four imports (`niord-country/e2e-imports`, in every form of import the country API's rule
reads):

- `@niord/admin/e2e`, exactly, the login fixtures;
- `@playwright/test`, exactly, including an `import('@playwright/test')` type;
- a `node:` built-in, written so (`node:path`, never `path`);
- a relative path that stays inside `e2e/`, such as `./helpers/login`.

As in the country's other files, an `eslint-disable` comment disables nothing and is itself
reported. A country with specs and no administration code of its own still lints `niord-app`.

## Starting a country — `init`

`init` starts a country repository from a handful of answers: the whole standard country, backend
and frontend, which sets up, builds and starts locally at once. The country guide's
[Quick start](docs/country-guide.md#quick-start), which the kit package carries, gives the steps in
order, and the in-depth guide's
[What init wrote, file by file](docs/country-guide-in-depth.md#what-init-wrote-file-by-file) says
why each file is there.

Before it, the repository needs:

- locally, a token with `read:packages` in `~/.m2/settings.xml` under the server id `github`
  (GitHub Packages refuses an unauthenticated read even of a public package); set-up, the backend's
  build and the launcher read it;
- the release in `.niord/` at the repository root: both zips and `SHA256SUMS`, checked with
  `sha256sum -c`, and only the kit package unzipped, into `.niord/kit/`, from where `init` runs.
  `init` reads the source zip in memory, from `.niord/`, else from `fe/.niord/`;
- on GitHub, "Automatically delete head branches" on (Settings, General, or
  `gh api -X PATCH repos/<owner>/niord-<cc> -F delete_branch_on_merge=true`), so that a merged pull
  request's branch, each base bump's included, goes with it.

No `be/pom.xml` and no Maven wrapper come first: `init` writes both. A `be/pom.xml` that exists is
kept, and its `<parent>` must name the kit's version. The repository should hold none of the other
files `init` writes (below), the root `.gitignore` aside: `init` keeps a file that exists, so a
`.github/` copied from another country's repository keeps that country's `ci.yml`, `pack.yml`,
`base-bump.yml`, `image.yml`, `dependabot.yml` and `CODEOWNERS`. Of such a copy only the workflows
`init` does not write belong in a new repository.

From the repository's root, as the country guide's [The release](docs/country-guide.md#the-release)
gives them:

```bash
mkdir .niord
gh release download v<version> --repo NiordOrg/niord4 --pattern 'niord-fe-src-<version>.zip' --pattern 'niord-country-kit-<version>.zip' --dir .niord
printf '%s\n' '<fe-sha256>  niord-fe-src-<version>.zip' '<kit-sha256>  niord-country-kit-<version>.zip' > .niord/SHA256SUMS
(cd .niord && sha256sum -c SHA256SUMS)
unzip -q .niord/niord-country-kit-<version>.zip -d .niord/kit
node .niord/kit/init.mjs <cc> --sha256-file .niord/SHA256SUMS
```

The `sha256sum -c` line is the first pins' check, and it refuses: `SHA256SUMS` holds the two lines
exactly as the notes of the base product's GitHub release `v<version>` give them,
`<sha256>  niord-fe-src-<version>.zip` and `<sha256>  niord-country-kit-<version>.zip`
(`gh release view v<version> --repo NiordOrg/niord4`; release notes exist from 4.0.182), and
`sha256sum -c` fails unless each downloaded zip has its line's hash. Run nothing from either zip
before both say OK: `unzip` and
`init` come after. The publish workflow writes both lines when it publishes the zips, and the
registry's token cannot change them ([Publishing](#publishing)). `init`'s own hash checks run from
the unpacked kit, so they pin the hashes they are given and catch a wrong version or a broken
download; they do not authenticate the zips. Nothing of `.niord/` is committed: it ignores itself
through the `.gitignore`, holding `*`, that `init` and set-up write into it, and `fe/.gitignore`
names `fe/.niord/`. The kit's unzip writes no marker, so the first `node setup.mjs` fetches both
packages once more through `be/mvnw` and marks them.

The command line, from the repository root:

```text
node .niord/kit/init.mjs [<cc>] [--answers <file>] \
  (--sha256-file <file> | --zip-sha256 <fe-sha256> --kit-sha256 <kit-sha256>) [--version <v>]
```

- `<cc>`, given first, presets the country: its question is skipped
  (`init: country: <cc>, from the command line`); with `--answers` it fills a missing `country` and
  refuses a different one.
- `--sha256-file <file>` takes the release notes' two lines exactly: `<sha256>  <name>`, 64
  lower-case hex digits and two spaces, the names `niord-fe-src-<version>.zip` and
  `niord-country-kit-<version>.zip` once each; a CR at a line's end is stripped and a blank line
  ignored. `sha256sum`'s binary form (` *<name>`), upper case, a third name, a name twice, another
  version's names and a missing line are each refused.
- `--zip-sha256 <fe-sha256> --kit-sha256 <kit-sha256>` is the same input as two flags. Give one
  form: both, or neither, is refused.
- `--version <v>` only in the base product's tree, whose kit names no version. In the kit package the
  version is its `kit.json`'s, and a `--version` that differs is refused.
- `--answers <file>` reads the answers from a file (below).

**The answers.** Without `--answers`, `init` asks them one by one, line by line, after the line
`init: answer each question and press Enter; a value in [brackets] is the standard default, taken with Enter alone`.
It reads lines, not keys, so it works in Git Bash on Windows and on a pipe without a terminal; a
blank line takes the standard default (where a question has none, the answers are refused), and input that ends before the last question writes nothing.
With `--answers <file>` it reads them from a JSON file, UTF-8 with any BOM stripped. The 16
questions, what each means and its standard default are the country guide's
[The answers](docs/country-guide.md#the-answers); [init-answers.schema.json](init-answers.schema.json)
is their schema and [lib/answers.mjs](lib/answers.mjs) the reader, with the rules a schema cannot
say. `init` checks the answers once and refuses with every problem named, writing nothing; it never
asks again. After the last asked answer it prints them under
`init: the answers, as --answers takes them:` as the JSON `--answers` takes, so a refused run is
repeated from a file: save them as `.niord/init-answers.json`, which is never committed, correct them
and run `init` again with `--answers .niord/init-answers.json`.

**Checked before any write.** The version, the answers, the hashes, the release (a kept
`be/pom.xml`'s parent, both zips with their lines' hashes, the source zip read) and a kept
`fe/niord-country.json`, which must be a manifest the kit reads and name the answers' country. On
the interactive route the release is checked before the first question, so a wrong download is
refused before anything is typed. Every file is computed in memory before the first is written.

**What it writes.** `init` pins both zips in `fe/niord-pins.lock.json` (the base's version and
sha256, and the kit's sha256) and writes, each as `init: wrote <file>` and never over an existing
file, which it lists as `init: kept <file>, which exists`:

- **the scaffold**, 23 files from the answers ([lib/scaffold.mjs](lib/scaffold.mjs)): `be/pom.xml`
  from `templates/be/pom.xml.tmpl` (on the base product's parent, with the country build group's test
  dependencies and each answered language's pack copies), `be/mvnw`, `be/mvnw.cmd` and
  `be/.mvn/wrapper/maven-wrapper.properties` (the base product's wrapper, from the kit's `backend/`),
  `be/.gitattributes`, `be/.gitignore`, `be/.java-version`, the application class in
  `be/src/main/java/org/niord/<cc>/`, `be/src/main/resources/application.yml` (with
  `niord.s124.enabled: true`, and the producer code when one was answered) and
  `application-local.yml`, `be/src/main/resources/defaults/country/settings.json` (from the kit's
  `backend/defaults/base/settings.json` and its catalog) and its `README.md`,
  `be/bootstrap/README.md` (which also says what each release folder
  `releases/<yyyy-mm-dd>-<topic>/` holds), `be/bootstrap/batch-set.json` and
  `be/bootstrap/domains.json` (the first domain, with its time zone, map centre and message series),
  `be/overrides.json` (no override), `be/scripts/check-country-build-group.sh`, the manifest
  `fe/niord-country.json` (English, and every answered language pinned), the public site's
  `fe/info/app/info.config.ts` (with the answered time zone), `.github/CODEOWNERS` with the answered
  owners (for the override, escape-hatch, dependency and pin files, the country's URL allow-list and
  branding-unaccepted lists, `fe/packs/`, `pack-releases.md`, `pack.yml` and the Base bump
  workflow), `.github/workflows/image.yml` with the frontend job, the backend job
  ([The backend build](#the-backend-build)) and the push job, whose last step is the placeholder for
  the administration bundle's image above, and `MODULE` filled (when the repository has an
  `image.yml`, it prints both build jobs to add, and what the push job of
  `.niord/kit/templates/image.yml` adds: both jobs as its needs, the `backend-dist` download and its
  SHA-256, and `Check the version`), `.github/workflows/ci.yml` from `templates/ci.yml`, and the root
  `README.md`;
- **the skeleton**: `fe/setup.mjs`, the branding the schema requires (each required key in the
  application's `en.json`, each required file as the base product's default copy; optional entries
  are left out, because an empty value would blank the bundle's text), `fe/styles/tokens.<cc>.css`
  with the default values, `fe/.gitignore`, `fe/.gitattributes` (the base product's line-ending
  rule: text stored LF, Windows command scripts (`*.cmd`) CRLF, the binary types never converted, so
  a Windows checkout with `core.autocrlf=true` keeps the bytes the kit hashes and compares; a
  country's own `.gitattributes` is kept) and the same rule as `.gitattributes` at the repository
  root (so `start-local.sh`, the workflows and `AGENTS.md` keep LF too),
  `.github/workflows/pack.yml` ([Publishing a pack](#publishing-a-pack)),
  `.github/workflows/base-bump.yml` ([The base bump workflow](#the-base-bump-workflow); an existing
  one is kept, and `init` prints to compare it with `.niord/kit/templates/base-bump.yml`),
  `pack-releases.md` at the repository root, `.github/dependabot.yml` (security updates for
  `/fe/country` and the workflows' actions), the agent contract `AGENTS.md`
  (byte for byte [templates/AGENTS.md](templates/AGENTS.md)) with a `CLAUDE.md` holding the one line
  `@AGENTS.md`, which loads it in Claude Code (a `CLAUDE.md` that exists is kept, and `init` prints
  that it must import `AGENTS.md`), `.niord/.gitignore` holding `*`, and the launcher
  `start-local.sh` at the repository root, byte for byte
  [templates/start-local.sh](templates/start-local.sh)
  ([Running it locally](#running-it-locally--start-localsh)).

A kept `ci.yml` without a `workflow_dispatch:` trigger gets a notice that `base-bump.yml` dispatches
it. `start-local.sh`, `be/mvnw` and `be/scripts/check-country-build-group.sh` get the mode 0755.
`init` writes no README into `be/bootstrap/releases/` itself: the bootstrap archive accepts under
`releases/` only a release folder's `*.json`, `*.md` and `*.sql`. The root `.gitignore` is the one
existing file `init` changes: it appends the line `/.local-run/` (the launcher's downloads and logs)
under a comment, in the file's own line ending (CRLF when the file has one), unless a line already
reads `.local-run/`, `/.local-run/`, `.local-run` or `/.local-run`, and lists the file as changed; a
repository without one gets a root `.gitignore` with that line. A language added later brings its
pack's copies and check lines into `be/pom.xml` by hand ([The backend build](#the-backend-build)).

`init` ends with these lines, the last two on Windows only, and
`(cd fe && node setup.mjs --update-pins)` in place of the set-up line when languages were answered:

```text
init: next, from the repository root:
init:   (cd fe && node setup.mjs)
init:   ./start-local.sh
init: ./start-local.sh --sample starts the base product's sample instead (the kit's README, Running it locally)
init: on GitHub, install the Niord base bump app on this repository once, so each base version arrives as a pull request: https://github.com/apps/niord-base-bump (the country guide, Upgrades)
init: on Windows, give the scripts their exec bits in the first commit: git add -A && git add --chmod=+x start-local.sh be/mvnw be/scripts/check-country-build-group.sh
init: on Windows, before be/mvnw verify: export NIORD_HOME=$(cygpath -m "$TEMP")/niord
```

Then `(cd fe && node setup.mjs)` and `./start-local.sh`: the plain run bootstraps the country's
database once, with the base product's archive and then the domain `init` wrote
([The databases](#the-databases)). In `fe/`, `npm ci` and `npm run build` — the untouched country
builds working English applications, with a warning until it records its bundle baseline — and
`npm test`, `npm run lint` and `npm run e2e` pass with none of its own specs yet; `image:info` builds
its image, warning that it has no legal document and no public-site branding text yet
([image:info](#the-public-site-image--imageinfo)). The base product's CI holds it to that on every
change to what the zip carries, and builds its backend with `be/mvnw verify` when the scaffold's
inputs change (the harness's untouched init, below). `init` writes no `fe/country/`,
`fe/text-overrides/` or `fe/audit-accepted.json`: each counts as empty while absent.

For the workflows `init` writes, the country then sets `REGISTRY` and `HARBOR_PROJECT` in
`image.yml`'s `env:` (`init` filled `MODULE`, the `artifactId` of its deployable, the jar's name) —
the frontend job and the push job fail while `REGISTRY` or `HARBOR_PROJECT` is empty, the backend
job while `MODULE` is — and creates the `harbor` environment, restricted to `main`, with the secrets
`HARBOR_ROBOT_USER` and `HARBOR_ROBOT_PASSWORD`. In CI the build jobs read GitHub Packages with the
workflow's own token, bound to the frontend job's `Set up the workspace` and to the backend job's
`Set up the workspace`, `Build the jar` and `Fetch the image tools`, and nowhere else. `ci.yml`
takes `workflow_dispatch:` in its `on:`, on a line of its own, since the Base bump workflow
dispatches it ([The base bump workflow](#the-base-bump-workflow)); that workflow needs no secret.

**A pin by hand.** A bump made without the Base bump workflow, before that workflow is on `main`,
pins on the bump branch. There `be/pom.xml`'s `<parent>` already names the new version, so the
branch's own `be/mvnw` fetches both zips, and each is checked against its line of the notes, from
the repository root:

```bash
be/mvnw -B -f be/pom.xml dependency:copy \
  -Dartifact=org.niord.web:niord-fe-src:<version>:zip -DoutputDirectory="$PWD/fe/.niord"
be/mvnw -B -f be/pom.xml dependency:copy \
  -Dartifact=org.niord:niord-country-kit:<version>:zip -DoutputDirectory="$PWD/.niord"
(cd fe/.niord && printf '%s\n' '<fe-sha256>  niord-fe-src-<version>.zip' | sha256sum -c)
(cd .niord && printf '%s\n' '<kit-sha256>  niord-country-kit-<version>.zip' | sha256sum -c)
```

The two output folders are absolute because Maven resolves a relative `-DoutputDirectory` against
the project's folder, `be/`, so `fe/.niord` would land in `be/fe/.niord/`. Then
`fe/niord-pins.lock.json`'s `base` takes the version and the first line's sha256, its `kit` the
second line's, and `node setup.mjs` runs in `fe/`
([The base bump workflow](#the-base-bump-workflow)).

## Running it locally — `start-local.sh`

`start-local.sh` at the country repository's root starts the country's whole local stack in one
command: the shared containers, the country's backend, the administration app and the public site.
It is [templates/start-local.sh](templates/start-local.sh) byte for byte, the same in every country,
with no placeholder: it reads the country's values from its files. `init` writes it into a new
country; a country copies `.niord/kit/templates/start-local.sh` over it again at a base
product bump and never edits it, so its CI may compare the two after set-up. Its downloads and logs
go into `.local-run/` at the root, which the root `.gitignore` names (`/.local-run/`).

From the repository root, in Git Bash on Windows or a shell on Linux or macOS:

```bash
./start-local.sh                    # the country's own bootstrap, on the database niord_<cc>
./start-local.sh --sample           # the base product's sample, on the database niord_sample_<cc>
./start-local.sh --sample --reset   # the same, after dropping niord_sample_<cc> (and nothing else)
./start-local.sh --help             # the script's header: what it does and what it reads
```

`--reset` goes with `--sample` only: after a base product bump that changed the sample, or a sample
bootstrap that failed. On Windows, run it from Git Bash. Git on Windows records a new file without
its exec bit, so the first commit gives `start-local.sh`, `be/mvnw` and
`be/scripts/check-country-build-group.sh` theirs with `init`'s closing line,
`git add -A && git add --chmod=+x start-local.sh be/mvnw be/scripts/check-country-build-group.sh`;
a checkout that lost one gets it back with `git update-index --chmod=+x <file>`, committed.

### Prerequisites

Each is checked with its own message before anything starts, and Docker last, so a refusal of the
others makes no docker call:

- a JDK 25 or later with `bin/jar`: `JAVA_HOME`, else `~/.jdks/temurin-25`; `java` on the PATH is not
  read, and a JRE has no `jar`, with which the launcher unpacks its library;
- Node and npm, and curl. The launcher checks only that `node` and `npm` are on the PATH. The
  versions are those of the base product's `package.json`, which set-up copies into the
  `fe/package.json` it generates: Node `^22.22.3 || ^24.15.0 || ^26.0.0` (`engines`) and npm 11.11.0
  (`packageManager`). Use Node 24, 24.15.0 or later, with the npm 11 it brings: the line the base
  product's `.nvmrc` names and the kit's workflows set up. npm only warns (`EBADENGINE`) for a Node
  outside the range;
- a GitHub Packages token with `read:packages` in `~/.m2/settings.xml` under the server id `github`
  (on Windows the `settings.xml` of `USERPROFILE`, as Maven reads it), which the backend's build needs
  too — or `NIORD_LOCAL_BASE_REPO` (below), for a local build of the base product;
- Docker running (`docker info`; on Windows, Docker Desktop started);
- a checkout and a home folder whose paths hold no whitespace: the JVM's and Maven's arguments split
  at a space.

The launcher does not check it, but a first maintainer needs one more before it: the GitHub CLI
`gh`, signed in, for the release's download ([The release](docs/country-guide.md#the-release)).

The environment it reads:

| Variable                | Default | What                                                                             |
| ----------------------- | ------- | -------------------------------------------------------------------------------- |
| `FE_PORT`               | 4200    | the administration app's port                                                    |
| `INFO_PORT`             | 4400    | the public site's port                                                           |
| `BE_PORT`               | 8888    | the backend's port                                                               |
| `NIORD_LOCAL_BASE_REPO` | none    | a scratch Maven repository with a local build of the pinned base product version |

`NIORD_LOCAL_BASE_REPO` names an existing folder. The launcher then exports `MAVEN_ARGS` with it as
`maven.repo.local` and `~/.m2/repository` as its tail, which the fetch, the set-up and the backend
all read, and does not check the credential.

The ports are numbers from 1024 to 65535, three different ones; a port in use is refused, naming the
variable that picks another. A bad argument or port exits 2, a missing or invalid country file or
prerequisite exits 1, both before any docker call but `docker info`.

### What it does, in order

1. **The arguments**, as above.
2. **The country's files**, read with one Node run: the country code (two letters a–z; any other
   exits 1) and name from `fe/niord-country.json`; the base product version from `be/pom.xml`'s
   `<parent>`, by the set-up stub's own rule (`fe/setup.mjs` exports `parentVersion`, and does
   nothing when it is imported); the deployable from `be/pom.xml`'s own `<artifactId>`, the child of
   `<project>`, comments left out; and the database ([The databases](#the-databases)).
3. **The prerequisites**, as above.
4. **The fetch**, once per version: `be/mvnw dependency:copy` of `org.niord:niord-local-run:<version>:zip`
   and `org.niord:niord-bootstrap:<version>:zip` into `.local-run/<version>/`, given as an absolute
   folder, since Maven resolves a relative one against `be/`. `niord-local-run` holds the launcher
   library the script sources (`.local-run/<version>/local-run/scripts/local-run-lib.sh`), the
   bootstrap command, the sample and its loader, the compose file and the local realm; it is unpacked
   with the JDK's `jar` into a folder of its own first, so an unpack that broke off is never taken for
   a whole one. `niord-bootstrap` is the base product's bootstrap archive. A published version never
   changes, so it is not fetched again; a local build of the same version again (`NIORD_LOCAL_BASE_REPO`)
   needs `.local-run/<version>/` deleted. A failed fetch names the artifact and the credential; a base
   product version older than the launcher's first carries no `niord-local-run`, and the remedy is a
   bump of `be/pom.xml`'s `<parent>`.
5. **The ports**, each refused when in use.
6. **The set-up**: `node setup.mjs` in `fe/`, before the backend's build, which reads the packs'
   backend texts from `fe/.niord/packs/` ([The backend build](#the-backend-build)). A refusal stops the
   run here, before any container or backend is touched. Then `npm ci`, when
   `fe/node_modules/.package-lock.json` is missing or older than `fe/package-lock.json`.
7. **The shared containers** `niord-db` (PostgreSQL with PostGIS), `niord-keycloak` (with the local
   realm) and `niord-smtp` (MailHog), shared by every checkout and every country's launcher, through the
   library with the zip's compose file and realm: a running one is never stopped, restarted or
   recreated, only a missing or stopped one is started, and while all three run no compose command is
   issued at all. Their data lives in named volumes.
8. **The database**, created in `niord-db` from `template_postgis` when it is missing
   ([The databases](#the-databases)); `--sample --reset` drops `niord_sample_<cc>` first.
9. **The backend**: `be/mvnw spring-boot:run` with the `local` profile and the JVM arguments
   `-Duser.timezone=UTC`, `-Dserver.port=<BE_PORT>`, `-Dniord.home=<the Niord home>`, the datasource
   `jdbc:postgresql://localhost:5432/<database>` (the launcher's own, whatever the country's
   `application-local.yml` names), `-Dmode=development` (both frontends show it with a ribbon) and
   `-DadminAppUrl=http://localhost:<FE_PORT>`, so its message links point at the administration app
   of this run; under `--sample` also `-Dniord.secom.enabled=false`, so the sample sends nothing out.
   `NIORD_HOME` is exported with the same folder, since a country's settings set `niord.home` from
   `${NIORD_HOME}`. The first run also builds the backend, and the launcher waits up to 15 minutes
   for it to answer.
10. **The bootstrap.** First, at every start and whatever the database holds, the library creates
    in the shared local realm the missing client of each domain of `be/bootstrap/domains.json` with
    `"active": true`, with the roles `user`, `editor`, `admin` and `sysadmin`, and gives `sysadmin`
    to the group `Sysadmin`; a client that exists is
    left as it is, nothing in Keycloak is updated or deleted, and the base product's archive and a
    `--sample` run are never read
    ([Your sysadmin in your own domain](docs/country-guide-in-depth.md#your-sysadmin-in-your-own-domain)).
    Then the bootstrap is posted through the library only on a database whose marker reads pending: a
    database that holds data is never bootstrapped, and one whose bootstrap failed or broke off is not
    bootstrapped again (the message names the remedy, the database recreated empty). The plain run
    posts the base product's archive and then the country's `be/bootstrap/`, zipped, in one run; while
    `be/bootstrap/` has no `batch-set.json`, the base product's archive alone, with a note. `--sample`
    posts the base product's archive alone, then stops the backend, loads the sample with its loader
    and starts the backend again; on a sample loaded before, it copies the sample's attachments again
    and loads nothing.
11. **The frontends**, once the database is bootstrapped, so neither shows a database half made: the
    generated `start:admin` with `-- --configuration local --port <FE_PORT>` and `start:info` with
    `-- --port <INFO_PORT>`, in `fe/`, with `NIORD_BE_PORT` naming the backend's port for the dev
    server's proxy. The last `--port` given wins over the script's own 4400. The launcher waits up to 5
    minutes for each.
12. **The closing lines**: the administration app (log in as `sysadmin` / `sysadmin`), the public
    site, the backend's `/rest`, Keycloak on 8080 (its admin console `admin` / `admin`), MailHog on
    8025, the database with its `psql` command, the Niord home and the three logs,
    `.local-run/backend.log`, `admin.log` and `info.log`; under `--sample` also the sample's logins
    (`sysadmin`, `admin`, `editor` and `user`, each with its name as password); and the local-only
    warning. Then it follows the three logs, each line marked `[be]`, `[admin]` or `[info]`.
13. **The watch and the clean-up**: it watches the three ports, not the processes, and ends (exit 1)
    once a server no longer answers. Then, as on Ctrl+C or a TERM (exit 130), it stops the backend and
    both frontends, waits up to 60 seconds for each of the three ports to be free (on Windows it stops
    a listener that outlived its shell by its PID), and leaves the shared containers running.

### The databases

- **The plain run**: `niord_<cc>`, unless a file `start-local.json` at the repository root names
  another, `{ "database": "<name>" }`: an object with that one key, the name a plain lower-case
  identifier of at most 63 characters, never `niord`, `postgres`, `template0`, `template1`,
  `template_postgis`, `keycloak`, `niord_sample` or `niord_sample_<…>`.
- **The mark.** The launcher marks every database it creates with the comment
  `created by start-local.sh, the country launcher`, and refuses an existing database without that
  mark before any backend starts (exit 1): a database it did not make is never touched, whatever a
  configuration names. The refusal names both remedies: another database in `start-local.json`, or,
  for a database you made yourself and no longer need, `docker exec niord-db dropdb -U niord <database>`,
  after which the next run creates and marks it. A database is never marked by hand. A database the
  launcher created but could not mark stops the run (exit 1) before any backend starts, with the same
  `dropdb` as its remedy. A country whose `niord_<cc>` already holds local data of another kind, on its
  developers' machines, commits a `start-local.json` naming another database: GL's names
  `niord_gl_local`, because `niord_gl` is an existing local database. A marked database that holds
  data is used as it is, and nothing is bootstrapped; to start afresh, stop the launcher, run
  `docker exec niord-db dropdb -U niord <database>`, and start it again. A marked database whose
  backend did not start, such as on a configuration value it refused, was posted no bootstrap and
  still waits for one, its tables made or not: the next run bootstraps it, with no `dropdb`.
- **`--sample`**: `niord_sample_<cc>`. It is the launcher's by its name, without a mark, and
  `--sample --reset` is its remedy.
- **The Niord home** of each database is `~/.niord-local/<database>`: the cache it holds (the
  message index, the issues' previews, the thumbnails, the map images and the map tiles) and the
  batch jobs' files belong to that database; the repository's files are rows of the database. It
  is made when the backend starts, so a refused database gets none, and it is never `~/.niord`,
  where an older backend's message index makes this one fail at start-up. A backend started
  without a home is refused: its log says `niord.home cannot be resolved`, with the cure.

The plain run is the country's real bootstrap: the base product's archive and the country's own, no
messages. `--sample` is the base product's archive and the base product's sample, with the country's
settings and texts — a country whose settings name Danish is Danish from its first start — and never
the country's own archive, whose domains and series would collide with the sample's.

A local trial on this machine only: everything listens on loopback, and Keycloak runs in development
mode with known passwords. Never expose it. A country deploys with its image and the chart.

## The example country

`example/` is a fictitious country `xx` laid out as a country repository: `be/pom.xml` and `fe/` with
the stub, a manifest (administration app in `en` and the home pack `zz`, the public site in `zz` only),
every seam above filled, branding and a legal document for both applications, brand tokens, a vendored
Lato face with its licence, and a footer that uses a utility no base product template has. It uses
each extension point once: in the administration app a harbour page for the editor area's roles with
a menu entry after Messages, a renderer and an editor for its own promulgation service `xx-bulletin`
and a note in the `home.welcome` slot; on the public site an about page and a notice in the
`layout.beforeFooter` slot. It has one guarded override, made with `--add-override`:
`message-selection-toggle`, whose copy shows a bookmark where the base product shows a star, with
the country's own spec beside it. A base product change to that component's `.ts`, `.html` or
`.scss` therefore fails the example until its fingerprints are accepted again — in a scratch copy,
`node setup.mjs --accept-override <file>`, then the manifest copied back. Every other file imports
the base product through the country API, its specs through the `/testing` entries. It has one
country package, `pretty-bytes`, for the administration app (the harbour page states a size with
it), whose lock was made with `--update-country-lock`, and one text override,
`text-overrides/en.json`, which words the message filter's `Domain` as `Waters`. It has its own
bundle baseline, `info/bundle-baseline.json`, recorded from its build as above; a base product
change that moves its first visit past the tolerance records it again, as it records the base
product's own. It has one end-to-end spec, `e2e/harbour.spec.ts`, which opens the harbour page as an
editor through `@niord/admin/e2e`; the harness only lists it, never runs it against an environment.
Its pack `zz`, a home pack with `publish: true`, is pseudo-localised from `en` by
`example/pseudo-localise.mjs` when the harness runs; only its `pack.json`, flag and attribution are
committed. Its public page, `info/app/index.html`, is in `zz` (`lang="zz"`) and titled with `zz`'s
generated `SITE_TITLE`, which the harness's copy step and the routine actions of a bump write into
it again (`pseudoLocalise`'s `index`), so check 10 of the checks on every country build holds; the
pseudo-localiser's test fails while the committed title is not the generated one. It is the
reference to read; `init` does not copy it.

The harness sets it up and builds it the way a country does, from a folder outside the base product
tree. From the base product's `fe/`, with `JAVA_HOME` naming a JDK 25:

```bash
node ../country-kit/example/run.mjs --work ../.local-run/example [--maven-repo <dir>] [--image [--with-deps]] \
  [--backend [--skip-frontend]] \
  [--upgrade-from <previous niord-fe-src zip> --upgrade-kit-from <its niord-country-kit zip>]   # from fe/
```

It builds the source zip and the kit package from the working tree as version `4.0.0-example`,
installs both and the base product's `niord-parent` into a scratch Maven repository in the work folder (reading through to `--maven-repo`,
by default `~/.m2/repository`, for everything else), copies the example to `<work>/xx` with the base
product's Maven wrapper, generates `zz` (and its `SITE_TITLE` into the copy's public page), pins both
packages by their hashes and runs `node setup.mjs`, then
checks that `.niord/packs/zz/` holds exactly `zz`'s canonical archive and that
`pack.mjs latest-catalog` names `4.0.0-example`, the newest version in the scratch repository (whose
`niord-fe-src` and `niord-country-kit` versions are cleared at the start of each run), then `npm ci`,
`npm run build` (the npm audit included, so the registry is needed, as for `npm ci`, and the checks on
every country build), `npm test` and
`npm run lint` there (its spec `e2e/harbour.spec.ts` included) — never through `NIORD_BASE`. After
the build it checks that the example's pages and slot components are lazy chunks: each component's selector is in some JavaScript file of the
build and in none that `index.html` loads or preloads; and that each text override's text is its
key's value in the built bundle of its language and application. With `--image`, which needs Docker,
it then installs Chromium in the work copy (`--with-deps` adds its system libraries, as a fresh CI
runner needs; it asks for root) and runs `npm run image:info -- --tag niord-xx-info:example`, and
removes that tag at the end, pass or fail. Then `npx playwright test --list` in the work copy proves
that the generated Playwright configuration, the `@niord/admin/e2e` alias and the example's spec
compile, as the lint proved that the spec imports only what a spec may.

Then come the new-country acceptance tests, against the same two packages (`example/acceptance.mjs`):

- **The untouched init.** A country `yy` is made by the kit package's own `init` in `<work>/yy`, as
  [Starting a country](#starting-a-country--init) says — an empty `yy/`, both zips and their
  `SHA256SUMS` in `yy/.niord/`, only the kit package unzipped, into `.niord/kit/`, the answers in
  `.niord/init-answers.json` (`yy`, `Europe/London`, a map centre, the producer code `YY00`, the
  owner `@example`, no languages, the domain's and the series' standard defaults), and
  `node .niord/kit/init.mjs --answers .niord/init-answers.json --sha256-file .niord/SHA256SUMS`
  from `yy/` — and never edited. `init` must print no `kept` line, and the Maven wrapper it wrote
  must equal the base product's `be/` wrapper byte for byte (off Windows, with its exec bit). It sets
  up pinned by those hashes, never through `NIORD_BASE`. With `--backend`, before the frontend steps,
  the harness installs `niord-parent` and `niord-base` with its test-jar from the working tree as
  `4.0.0-example` into the scratch Maven repository, runs `yy`'s `be/mvnw verify` with Docker (on
  Windows with `NIORD_HOME` written with forward slashes), `be/scripts/check-country-build-group.sh`,
  which must count more than 0 integration tests, and the override check over
  `target/niord-yy-4.0.0-example.jar`; without Docker it refuses, and never skips them.
  `--skip-frontend`, only with `--backend`, leaves out the example `xx`, `yy`'s frontend steps and the
  upgrade test. Then, in `yy`'s `fe/`: `npm ci`;
  `npm run build` must print check-build's warning that it has no bundle baseline yet; `npm test`,
  `npm run lint` and `npm run e2e -- --list` pass; the baseline is started as that warning says,
  after which check-build passes without it; and with `--image` its image is built and checked as
  `niord-yy-info:example`, with `image:info`'s warnings of no legal document and no branding text,
  and that tag removed at the end. Frontend CI's Example job, when it runs, passes `--backend` on a
  pull request that changes the scaffold's inputs; Backend CI's Generated country job runs
  `--backend --skip-frontend` when a pull request changes them, and on every push to `main`.
- **Publishing and pinning**, with `--image` (Docker): a `registry:2.8.3` container from Docker Hub,
  `niord-example-registry-<pid>`, on `127.0.0.1` and a free port; `zz` published from `xx` with the
  two commands `pack.yml`'s token jobs run, anonymously, against that auth-less registry —
  `pack.mjs validate zz` against the latest catalog, then the kit's `lib/oci.mjs publish` with the
  run's values in its environment and `PACK_REGISTRY` naming the container; `yy` given `zz` as a
  pinned pack under the owner `example`, its pin written by `node setup.mjs --update-pins` with
  `NIORD_PACK_REGISTRY`, then set up from the pin, `guard-build` accepting the workspace, and
  `npm run build` from the pinned pack; then `zz:1` withdrawn with `oci.mjs withdraw`, after which
  `yy`'s set-up fails naming the reason. The step proves the commands and the pin, not the Bearer
  flow, the owner's lower-casing or `pack.yml`'s own bash, which a real publish runs. The container is
  removed at the end on every exit the harness reaches, and the registry image too when the run
  pulled it; one an interrupted run (Ctrl-C) left is removed by the next run, before its own
  `docker run`, when its pid is no longer a running process, or by hand with
  `docker rm -f niord-example-registry-<pid>`. Without `--image` the step is skipped with one line:
  the client, `pack.yml` and set-up's pull are tested against an in-process registry in their own
  tests (`oci.test.mjs`, `pack-workflow.test.mjs`, `setup.test.mjs`).
- **The fixture upgrade.** A source zip `4.0.1-example` is derived from the first in memory — one
  English text of the administration app changed with its catalog fingerprint, a line appended to the
  template the example overrides, and `roleGuard` deprecated
  `since 4.0.1-example, removed in 4.0.2-example` — and a kit package `4.0.1-example` from the first
  kit package, its marker naming that version and its `API-CHANGES.md` holding a matching section that
  asks nothing of a country; both are verified and installed. The example's work copy, bumped to
  it, must fail set-up on its override alone and write `.niord/niord-upgrade-report.md`, whose title,
  five sections in order, changed key, override diff and deprecation are checked; then the routine
  actions of every bump — `zz` translated again from the new English, its `SITE_TITLE` written into
  the public page when the country has one, `--accept-override`, whose
  diff since the recorded fingerprint is checked — and set-up passes with the deprecation warning,
  `npm ci` runs when the lockfile changed, and the build passes. Then `pack.mjs latest-catalog`
  names `4.0.1-example`, and `pack.mjs prune zz --base 4.0.0-example` reads the catalogs of both
  example versions and has nothing to remove.
- **The upgrade test.** With `--upgrade-from <zip>` and `--upgrade-kit-from <zip>`, given together,
  a previous published release's two packages: its `niord-fe-src` (its version read from its
  `catalog.json`; a missing file, one that is not a zip, a path `MAVEN_ARGS` would split and the
  harness's own versions are refused) and its `niord-country-kit` (its `kit.json` naming the same
  version). Either zip alone is refused, and so is a source zip from before the kit package, one
  holding `country-kit/example/be/`: an upgrade test starts at 4.0.109 or later. That version's own
  example, from its kit package's `example/`, is set up at its version and bumped to the packages of
  this tree. When set-up stops on the stub, the kit's `.niord/kit/templates/setup.mjs` is copied
  over it and set-up runs once more; then the routine actions, a set-up and the build. A failure left
  passes only when a version section of `API-CHANGES.md` (_Unreleased_ or a version, never the
  policy) new since that version says a country must act; the new sections are found as the upgrade
  report finds them, against the previous kit package's `API-CHANGES.md`: the version rule, then each
  entry against the previous copy's `Unreleased`, so a renamed section excuses nothing by its
  heading, and an entry or a section that names no action excuses nothing either. Frontend CI's
  `Example country` job passes the packages of the latest release of the base product: it downloads
  the release's `niord-fe-src` and `niord-country-kit` assets with the workflow's own token and
  refuses each unless the release notes hold exactly one line naming its hash,
  `<sha256>  niord-fe-src-<version>.zip` and `<sha256>  niord-country-kit-<version>.zip`; notes
  without the kit's line fail the job, since every release since 4.0.109 publishes the kit package.
  With no release yet it skips the test and says so in the job summary. `verify-local` takes the
  two zips with its own `--upgrade-from <zip>` and `--upgrade-kit-from <zip>`, and exits 2 on one
  without the other. Without the options the run says the test is skipped and that the
  mechanism ran on the fixture pair; when `GITHUB_STEP_SUMMARY` is set, that line and the fixture
  upgrade's go to the job summary.

Each step prints its duration; the first failure stops the run. With `--image` and `--upgrade-from`
the whole run took 18 minutes 41 seconds on a Windows workstation (2026-10-22, with other runs
beside it), without `--image` 14 minutes 12 seconds; with `--image` alone, as `verify-local` runs it,
782 seconds (2026-10-23), and Frontend CI's `Example country` job 11 minutes.

## The source zip

The zip holds exactly what `zip-include.json` selects of the base product's `fe/` — the applications'
sources and public assets, the shared folders, the workspace files the kit generates from, the base
tokens, the `en` pack, and of the base product's `scripts/` only the public site's checks
a country runs with its own paths — `info-separation-check.mjs`, `info-layout-check.mjs`,
`info-bundle-report.mjs`, `info-container-test.mjs` and `build-urls-check.mjs` — with
`build-urls-allowed.json` and `glob.mjs`, the base product's own copy of the kit's glob matcher, which
`build-urls-check.mjs` imports — and of its root files `eslint-shared.cjs`, the lint lists
`eslint.config.js` takes, plus `catalog.json` at its root. It also holds the administration
app's end-to-end suite, `ui-apps/admin/e2e/` with its login fixtures, and `playwright.config.ts`,
which a country's generated `e2e` script builds on ([End-to-end specs](#end-to-end-specs)); its
README stays out with every README under `ui-apps/`, and the kit's `zip.test.mjs` fails any file of
the suite that names a country's test environment. Paths are relative to `fe/`, so an unpacked zip is
the base product's `fe/`, as a country's `fe/.niord/base/` holds it. No entry of the zip is under
`country-kit/`: the kit, its stub included, is a package of its own
([The kit package](#the-kit-package)).

```bash
node ../country-kit/source-zip.mjs build --version <version> --out <file>   # from fe/; npm run kit:zip -- …
node ../country-kit/source-zip.mjs verify <file>
node ../country-kit/source-zip.mjs guard <file>
```

`build` takes the files git sees in `fe/` and in the kit beside it (tracked and untracked, never
ignored ones), keeps those the list
selects, adds the catalog, and writes an archive whose entries are sorted, dated 1980-01-01 and mode
0644, so the same tree gives the same bytes and the same SHA-256 on the same Node build (the entries
are deflated by its zlib; a release is built once and verified by its SHA-256, never rebuilt), which it prints and writes beside the
archive as `<file>.sha256` (the form `sha256sum -c` reads). A selected link or non-regular file fails
it. `verify` reads the archive as set-up does and fails on any entry outside the list, on a missing
`catalog.json`, and on a relative import that crosses between the kit and the rest of the zip: a file
outside `country-kit/` that imports into it, or a kit file (the templates and the example country's
own `fe/` and `be/` aside) that imports outside it. `guard` runs the base product's country-word
guard over every entry of the archive, the catalog included, and fails on a word. `build` runs both checks on the archive it made before
it writes anything, so `kit:zip`, the example harness and the publish workflow fail on either; each
command exits 1 on a finding, printing one line per problem, and 2 when it cannot run.

**The country-word guard.** The base product names no country, and the zip is what a country
receives of it. The words — authorities' names and hosts, country locales and
places — are listed in `country-words.json`, which only the base product reads: it names every word,
so it is kept out of the zip, and a country never runs the guard. A word is matched as a literal
substring of each file's path and of its text, ignoring case, in Unicode NFC form; a finding names
the file and line and the word, never the line. A file that must keep a word for now is allowed it by
an entry of the list's `allowed`: one word, one path glob of the include list's kind, a reason, and,
where the need ends, the phase that ends it (`until`).

The guard runs in two modes. `test:guards` runs it over every file git lists in the base product's
`fe/` and in this kit (tracked and untracked, never ignored ones) but the list itself, and reports the words and
every entry that allows nothing, so a new word fails there first and the list shrinks as the files
lose their words. `build`, `guard`, the example harness and the publish workflow run it over the
zip's entries and report the words only, because an entry for a file outside the zip permits
nothing there. A finding in a file the zip carries is settled by an entry with its reason or by
keeping the file out of the zip, and anywhere else by an entry or by removing the word; a document
the zip carries, this one included, names no word itself and points to the list instead. `verify`
knows no word, so a zip published before a word was added still verifies.

Installing it into a Maven repository, where a country's set-up finds it — run in the base product's
`be/` (`-N` keeps Maven to the parent project; add `-Dmaven.repo.local=<dir>` for a scratch repository):

```bash
./mvnw -B -N install:install-file -Dfile=<file> \
  -DgroupId=org.niord.web -DartifactId=niord-fe-src -Dversion=<version> -Dpackaging=zip
```

### The kit package

The kit is published as a package of its own, `org.niord:niord-country-kit`, at the same version as
the source zip. It holds
what `kit-include.json` selects of this folder: everything the kit runs or reads — its scripts and
`lib/`, every data file, both include lists, the guides, `API-CHANGES.md`, this README, the templates
and the example country's `example/fe/` and `example/be/` — and never the kit's tests and fixtures,
`country-words.json`, `source-zip.mjs` (the base product's publish tool), the example harness
(`example/*.mjs`, which runs only in the base product's tree) or the tooling of the kit's folder there
(`.gitattributes`, `.prettierrc`, `eslint.config.mjs`). Its paths are relative to this folder, with
no `country-kit/` before them, since a country unpacks it into `<root>/.niord/kit`, and the build adds
two entries at the top, `docs/country-api.md`, the backend contract, copied from the base product's
`be/docs/country-api.md` with LF line ends, and `kit.json`, the marker that tells the kit it is the
package (`lib/where.mjs`), and six under `backend/` that `init` copies: `backend/mvnw` (LF; the
package holds every entry as 0644, and `init` writes it as `be/mvnw` with 0755), `backend/mvnw.cmd`
(CRLF), `backend/maven-wrapper.properties`,
`backend/defaults/base/settings.json`, `backend/defaults/base/settings-catalog.json` and
`backend/img/company-logo.png`, from `be/`.

```bash
node ../country-kit/source-zip.mjs build --package kit --version <version> --out <file>   # from fe/; npm run kit:package -- …
node ../country-kit/source-zip.mjs verify --package kit <file>
node ../country-kit/source-zip.mjs guard --package kit <file>
```

`build` writes it as it writes the source zip, with its `<file>.sha256`, and a selected file named
like either added entry fails it. `verify` fails on an entry outside the list, on a missing marker or
one not exactly `{"package":"niord-country-kit","version":"<version>"}`, on a missing
`docs/country-api.md`, on a missing backend file or one whose line ends are not its own (the logo's
bytes are not read), and on a relative import that names no file of the package: no import may
leave it (the kit loads the base product's code only from the base it resolves, by an import the
check does not read) or reach a file the list leaves out; the templates and the example country's
`fe/` and `be/`, which run in a country's own tree, are aside. `guard` runs the country-word guard
over every entry, matched as `country-kit/<entry>`, the name the allow-list gives a kit file. Its
coordinates for `install:install-file` are `-DgroupId=org.niord -DartifactId=niord-country-kit`.

### Publishing

The base product's publish workflow (`.github/workflows/publish.yml`) builds the source zip and the
kit package in its build job with `build`, then runs `verify` and `guard` on each file written, before
the build job builds the reactor or deploys anything of its own (the library, the image tools and the
two zips), so a path outside a list, an import leaving the kit package or a country word fails that
job before it deploys anything. It then deploys both zips with the same coordinates to the registry,
as it deploys the administration bundles:

```bash
./mvnw -B -e -N deploy:deploy-file \
  -Durl="https://maven.pkg.github.com/NiordOrg/niord" -DrepositoryId=github \
  -DgroupId=org.niord.web -DartifactId=niord-fe-src -Dversion=<version> \
  -Dpackaging=zip -Dfile=<file> -DgeneratePom=true
./mvnw -B -e -N deploy:deploy-file \
  -Durl="https://maven.pkg.github.com/NiordOrg/niord" -DrepositoryId=github \
  -DgroupId=org.niord -DartifactId=niord-country-kit -Dversion=<version> \
  -Dpackaging=zip -Dfile=<kit file> -DgeneratePom=true
```

After it has tagged the commit `v<version>`, it creates the GitHub release of that tag with the
workflow's own token. Its notes hold two lines in the form `sha256sum` prints, one per zip,
`<sha256>  niord-fe-src-<version>.zip` and `<sha256>  niord-country-kit-<version>.zip` — the record a
first pin is checked against ([Starting a country](#starting-a-country--init)), which the registry's
token cannot rewrite — and it carries both zips as assets; the base product's own Frontend CI reads
the source zip for the upgrade test with no token beyond its own. The run summary shows the same
lines.

A version the base product retracts (`.github/workflows/retract-version.yml`: the packages, the
release and the tag) keeps building where it is pinned: a country's Maven cache and its unpacked
`.niord/base/` and the root's `.niord/kit/` still hold the two packages, and their hashes still match
the pins, as the country's backend keeps
building against the same version from its cache. Retracting stops new fetches only, so a country
pinned to a retracted version bumps to the next one rather than waiting for a failure.
