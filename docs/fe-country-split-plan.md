# Frontend country split: a country-unaware base product, country repositories, language packs

**What this document is:** the rules and the present state of the frontend country split: the decisions (§2), the
model (§3), the contracts (§4), how a country is added (§5), the risks (§8) and the open checks (§9). It is not a
record of how the split was built. Its section numbers are the ones the code and the other documents cite, so
they do not change, and the numbers 6, 7, 10 and 11 are not used.
**Scope:** the country parts of both frontend applications — the administration app (`niord-app`) and
the public site (`niord-info`): their code seams, environments, assets, styles, translations and build
configurations; how a country repository builds its frontend from a published base product version; language
packs; a country's theme and its font.
**Repositories:** the base product's (the kit, the pack format, publishing), `SIFA-DK/niord-dk` and
`SIFA-DK/niord-gl` (their `fe/`, their pipelines). Not in scope: the backend's texts, settings, templates,
bootstrap and seed data — `docs/code-data-config-plan.md` owns them (R14); this document names where the two meet
(the packs' backend half §4.5, the backend in §4.9–§4.11, the sample in §3.4).

---

## 1. Summary

**Terms.** *Base product*: the country-unaware Niord that every country builds on. *Country*: a
country repository (`niord-dk`, `niord-gl`, …) and the deployments built from it.

**Goal.** The base product repository knows no country and no language but English. Each country repository
holds everything its deploy needs and builds its own administration bundle and public-site image from a
pinned base product version. No build carries a resource it does not need. Adding a country is one command,
a manifest and the files where the country differs — never a change to the base product.

```
 niord (base product) ─────── publishes per 4.0.<run> ──▶ org.niord.web:niord-fe-src:4.0.<run>:zip
   fe/ui-apps/{admin,info}                            (base product sources, en pack, key catalog,
   fe/languages/en      (the source pack)              country kit, info container files)
   country-kit/         (assemble, init, checks,
                         the example country)                    │
   (no country, no da/kl)                                        │ the zip in the Maven registry of
                                                                 │ the public NiordOrg/niord
 niord-dk                                 niord-gl               ▼
   fe/niord-country.json  (manifest)        fe/niord-country.json
   fe/admin, fe/info      (seams, assets)   fe/admin, fe/info
   fe/styles              (tokens)          fe/styles   (tokens, Lato)
   fe/packs/da ─ DK publishes it ────┐      fe/packs/kl (home pack)
                 itself (public GHCR)  │
   pom: parent niord 4.0.<run>        └───▶ fe/niord-pins.lock.json: niord-lang-da:<n>@sha256
        │                                   pom: parent niord 4.0.<run>
        ▼                                        ▼
   admin bundle in niord-app-dk,            admin bundle in niord-app-gl,
   image niord-dk-info                      image niord-gl-info
```

**Every country builds the same way** (§3.2): `node setup.mjs` unpacks the pinned base product and packs,
checks every pack against the base product's key catalog, stages what the build will ship and generates the
Angular workspace; then `npm ci` and `npm run build`.

| | Before the split | Since the split |
|---|---|---|
| A country's frontend | in the base product's repository: two `country-specific/<cc>/` folders, `public/assets/<cc>/`, `styles/tokens.<cc>.css`, 11 `angular.json` configurations (7 admin, 4 info) | the country repository's `fe/`, one manifest |
| Who builds a country bundle | the base product's `publish.yml` (`niord-web-admin:<v>:zip:<cc>`) | the country pipeline, from `niord-fe-src:<v>` |
| Translations | `da.json`, `kl.json` in the base product; Danish written in the feature's PR | `en` in the base product; `da` in `niord-dk`, `kl` in `niord-gl` — packs; published packs, each with its own version |
| A key the base product adds | Danish written with it | the country build fails until every pack it uses has it, or the key is in its escape hatch (§4.6) |
| Language lists and names | fixed in shared code (`UI_BUNDLE_LANGUAGES`, `LANGUAGE_<XX>` keys) | data: the manifest and each pack's `pack.json` |
| Country wording, legal texts, logos | partly base product code and base product keys (the administration app's DEMA footer and dialogs) | the country's branding, in both applications, against a base-product-owned branding contract |
| Environments | the administration app's development/test files point at DK's test environment | neutral in the base product; a country's own are a seam |
| A build's contents | leaks: resources of other countries and languages | the base product core, the staged packs and one country — checked on every build |
| Fonts | system stacks only | a country may vendor a text face (GL: Lato) |

---

## 2. Decisions (owner, 2026-10-19 to 2026-10-21)

| | Decision | Rejected, and why |
|---|---|---|
| **R1** | **The base product frontend is country-unaware.** English only: no country folder, configuration or environment, no `da`/`kl`, no authority wording. Its own build shows the Niord mark and no authority footer. | Keeping the DEMA branding in the default build (DK branding in a non-DK build). |
| **R2** | **A country repository holds everything its deploy needs** and takes the rest only as pinned, published artifacts: the base product and foreign packs. | The base product building country variants from overlays fetched from the country repositories (credentials into private repositories; nothing moves). |
| **R3** | **Language packs, home-country model.** Every language has exactly one home, which owns its text and its publishing: `en` the base product (every key is born there), `da` `niord-dk`, `kl` `niord-gl`. Other countries use the published pack. Each published pack is its own artifact with its own version, and a change to one pack publishes that pack only; an unpublished home pack (`publish: false`, §4.1) is a folder in its home's tree, staged at build. A pack also carries its language's backend text (`backend/*.properties`, `docs/code-data-config-plan.md` §4.3); the base product's English backend text stays in niord-base. | One shared `niord-languages` repository (nobody naturally owns it); a full copy of `da.json` per country (3,425 keys drifting per country, invisible to the base product's checks). |
| **R4** | **The pack check fails the country build** when the pinned base product has a key that a used pack lacks. A translation whose placeholders no longer match the English text counts as missing. Keys the base product no longer has are stripped, never shipped. A changed English text is a warning. **An escape hatch**, `missing-keys-accepted.json`, lists — reviewed, with reasons — the keys that may fall back to English for now; every build prints it. | A warning only (untranslated text reaches production unnoticed); failing on extra keys (DK and GL, sharing one `da` pack, would have to upgrade in lockstep). |
| **R5** | **Packs hold base product text only.** Authority names, footer, legal texts and a country's own wording are its branding, merged over the pack at runtime in both applications. | Country words inside packs (`da` could not serve DK and GL unchanged). |
| **R6** | **A Maven source zip.** The base product publishes `org.niord.web:niord-fe-src:4.0.<run>:zip`; a country unpacks it the way its pipeline already unpacks the backend's artifacts and installs from the base product's lockfile. One version pin per country — its `<parent>`. | An npm package (a scope tied to the GitHub owner, a token even for public reads, a second pin to keep equal to the pom); Angular libraries (every seam rewritten as dependency injection); runtime configuration (every country's assets in every build). |
| **R7** | **GL's text face is Lato, vendored.** Fontsource's Lato 1.104 `woff2` files, unmodified, latin and latin-ext under `unicode-range`, for the theme's faces — 400, 400 italic (message text has emphasis), 700, 900 — with `OFL.txt`, in `niord-gl`'s styles, from GL's own font sheet by relative `url()`. Never under `public/`, never fetched from Google. | `@fontsource/lato` as a dependency (its CSS ships unused `.woff` copies; a shared npm dependency); files under `assets/` (copied unfiltered into builds that do not need them; no content hash); a Google Fonts link. |
| **R8** | **The base product owns the public site's container and checks** — Dockerfile, nginx configuration, security headers, the separation / layout / bundle / container checks — published in the source zip and called by country pipelines with parameters. | A Dockerfile per country (security headers drift per country). |
| **R10** | **A country may change functionality, two ways** (§4.9). The normal way is the base product's extension points: country routes and pages, menu entries, named component slots, behaviour hooks. Beyond them, a country may replace any base product TypeScript, JavaScript or JSON source file (a template or stylesheet through its component's `.ts`) — a **guarded override**: each one is listed in the manifest with the fingerprint of the base product file it was based on, the build fails when that base product file changes until the country has reviewed its copy, and every build prints the list. An override that stays needed is a request for a seam. **The backend follows the same rule** (§4.9): Spring beans as its extension points, a resource that shadows `niord-base`'s as a guarded override — under `docs/code-data-config-plan.md` only a static image; templates are data (bootstrap and import files), and texts are layered per key, never shadowed. | Extension points only (every unforeseen need waits for a base product release); free file overrides (a country's copy goes stale silently when the base product changes). |
| **R11** | **A one-command sample deployment, with Danish sample data, in the base product** (§3.4, P9). Niord is a Danish project and its authors are Danish: the sample is DK's development base data, as the legacy seed was (its content is in the sample), plus sample messages, layered on the base product's bootstrap (`docs/code-data-config-plan.md` §7–§8). It lives in the base product because it is captured from the entities and checked against the schema — it must be versioned with the schema. R1 is about the frontend; the sample is content. | A fictitious neutral region (no gain: everyone knows the project's origin); sample data in a separate repository (it would break on the first entity change). |
| **R12** | **A country may own extra npm dependencies, additively and checked** (§3.2). It lists them in its own npm manifest `fe/country/package.json` with a lockfile; `setup.mjs` merges them on top of the base product's lockfile. A country package that the base product already has at another version, or whose peer ranges reject the base product's framework versions, fails. The base product keeps control of the framework. | Base product dependencies only (a country integration waits for a base product release, and country-only packages land in the base product). |
| **R13** | **Each home publishes its own packs** — three separate businesses (owner, 2026-10-19; replaces a central workflow of the same day) (§4.5). A home's `pack.yml` publishes its pack with the repository's own workflow token as a **public** OCI artifact, `ghcr.io/<owner>/niord-lang-<lang>` under the home's own GitHub owner (`sifa-dk` for DK and GL); a consumer pulls it anonymously, pinned by that full reference and its content digest. The base product takes no part, and nobody holds anyone else's credential. A pack's name names its home's organisation, never its home repository: moving a pack to another repository of the same organisation later (e.g. a shared one) is a history move and a publish permission with no consumer change; a move to another organisation is a rename and a pin change in every consumer. | A central publish workflow in the base product (the base product becomes a party to every pack release; push needs a home credential that can start *any* base product workflow, `retract-version.yml` included — the first high finding of the plan's security review, §4.11; pull needs a read key into private repositories); the base product's token in every home. |
| **R9** | **No resource in a build that the build does not need, and one source per output file.** A country never "overrides" by writing an output path another source also writes: `ng build` copies assets concurrently and the winner is not defined (`@angular/build` 22.2 `utils/resolve-assets.js:22-41`, `tools/esbuild/utils.js:169-180`). Both are checked on every build. | — |
| **R14** | **Code, data and configuration (`docs/code-data-config-plan.md`), adopted (owner, 2026-10-21).** §2.1 records the path without it, weighed before adoption. A repository change never changes or deletes what a running deployment has in its database; it only adds inert keys a deployment never had (texts, settings), and a key an operator deleted stays deleted. Every template, script and report is data (one rule: what an operator can see and edit is data), reaching a deployment by the bootstrap of a fresh database or a sysadmin's import. Both Reload functions are deleted; there is no reset to default. A country's settings file replaces the base product's whole file (2026-10-20). | Read-only defaults with database overrides and a one-time transition of existing deployments (a tool judging, per production row, what is an edit). |

**Registries (follow from R3, R6 and R13, 2026-10-19).** The **source zip** is published to the GitHub
Packages Maven registry of the public `NiordOrg/niord`, as `niord-base` is. **Packs** are not: GitHub's Maven
registry is repository-scoped with no per-package read grant (`be/docs/cicd/design.md`), so a pack in a
private country repository's own Maven registry could be read by no other country — whereas a GHCR
package belongs to the organisation and can be made public while its repository is private, and a public
GHCR package is readable without any token (both hold: §9). So each home publishes its packs there itself (R13).
**The registry of `NiordOrg/niord` is the delivery address of the base product's Maven packages (owner,
2026-10-19)**, the backend's included. Moving it to another address
is three edits — `github.repository` in the base product's `be/pom.xml`, the machine user's tokens
(`NIORD_PUBLISH_TOKEN` and `NIORD_DELETE_TOKEN`, §4.11 rule 1) in the base product, the registry URL in each country pom.
New versions then go to the new address; old ones stay readable at the old one, which is retired only when no
environment pins a version that lives only there. Reading the base product needs a token even though it is
public — a limit of GitHub's Maven registry that only Maven Central would remove (not in scope); reading a
pack needs none. A published pack is public from its first publish, also while its home repository is
private (base product UI text only, R5).

### 2.1 With or without the code/data/configuration plan (R14)

The plan follows the path **with** `docs/code-data-config-plan.md`, adopted on 2026-10-21: every place that cites it or
R14. The table keeps the path **without** it as the record of the alternative weighed before adoption: these
parts would have changed and nothing else — the
frontend parts, the language packs' frontend half, the extension points, the security rules other than those
listed, the upgrade report and the guidelines are the same on both paths. Both Reload functions are removed on
the adopted path.

| Part | With R14 | Without R14 |
|---|---|---|
| A country backend's resources that replace `niord-base`'s (DK 46, GL 75; §4.9) | sorted into texts, a settings file and bootstrap data; only images stay guarded | **stay where they are, guarded**: listed in `be/overrides.json` with the `basedOn` fingerprint of the pinned `niord-base` resource; a check published in `niord-image-tools` fails the country build when the base product changes one |
| Backend texts (§4.5, §4.10) | the language packs' backend half, layered key by key | **as before**: DK and GL keep their own `*_<lang>.properties`; the packs are frontend only; a base product key in a file a country replaces still does not reach that country |
| Settings (§4.1, §4.11 rule 3, §5 step 8) | the country settings file, checked against the settings catalog; added only at start-up | **as before**: the country's `niord.json` replaces the base product's; no catalog check |
| Templates, scripts and reports | data: bootstrap or a sysadmin's import; no reload | **as before**: copied into the database on first use; "Reload from system" stays |
| The country repository (§4.2, §5 step 1) | `defaults/country/`, `be/bootstrap/` with `be/bootstrap/releases/<yyyy-mm-dd>-<topic>/` | the backend as DK and GL had it |
| The admin app's dictionary merge (§4.10 layer 5) | rows the system added and nobody changed stop masking the app's texts | **as before**: those rows keep masking the newer texts |
| The country backends | consume what the base product publishes for them: the security test group, the start-up guard, the override check and `be/docs/country-api.md` | **one piece each**, after a base product step that publishes the security test group, the start-up guard, the override check and the FreeMarker class resolver; the backend adds `be/overrides.json` for the replaced resources |
| The sample (§3.4, P9) | layered on the base product's bootstrap, with its own DK rows in `be/niord/sample/` (domains, series, `Audio` as a restatement of the base product's type, its values equal to the base's and its domain link added, the domain links, four scripts); nothing fetched from `niord-dk` | **its own dataset** in `be/niord/sample/`, loaded by the launcher into `niord_sample` only; the sample's safety rules (§3.4) apply unchanged |

The plan was adopted before any country moved, so no country backend takes the "without" path.

---

## 3. The model

### 3.1 Three kinds of artifact

**The base product is one release made of several artifacts, all with the same version `4.0.<run>`:** the
backend's `niord-parent` and `niord-base`, the base product's bootstrap archive `niord-bootstrap` (zip),
`niord-image-tools`, `niord-local-run` (zip: the files of a local run, the sample among
them; never an image input, §3.4), the country kit `niord-country-kit` (zip), and the frontend's `niord-fe-src`
(and the prebuilt `niord-web-admin` bundle, which is `en` only since 4.0.97; earlier versions carry DK's and GL's
too). A country pins that one
version, its pom's `<parent>`; `setup.mjs` reads the same number, so a country's backend and frontend always
come from the same base product release. The table lists the frontend's artifacts.

| Artifact | Home (source + workflow) | Version | Coordinates (registry: `NiordOrg/niord`) | Used by |
|---|---|---|---|---|
| **The base product frontend** — both applications' sources, the `en` pack, the key catalog, the info container files | the base product's `fe/` | `4.0.<run>`, the backend's | `org.niord.web:niord-fe-src:4.0.<run>:zip` | every country |
| **The country kit** — the set-up, `init`, the pack tools and checks, the guides, `API-CHANGES.md`, the backend contract `country-api.md`, the templates and the example country | the base product's `country-kit/` | the same | `org.niord:niord-country-kit:4.0.<run>:zip` | every country |
| **A language pack** — one language's base product text for both applications and the backend, its name, locale and flag | its home country (`en`: the base product, inside the zip) | the home's pack workflow run `<n>`, as an OCI tag, for a published pack (an unpublished home pack has none); consumers pin the digest | `ghcr.io/<owner>/niord-lang-<lang>:<n>`, `<owner>` the home's GitHub owner (`sifa-dk` for DK and GL; public GHCR, not the Maven registry) | every country naming it |
| **A country's deployables** — the administration bundle, the public-site image | the country | the country's | the image `niord/niord-app-<cc>` (bundle inside), `niord-<cc>-info` | its deploys |

A country's pom has the base product version once, as its `<parent>`. Each **foreign** pack is pinned in
`fe/niord-pins.lock.json` by its full reference, tag and **digest** (`ghcr.io/sifa-dk/niord-lang-da:12@sha256:…`); a digest names exact
content and cannot be re-pointed. The base bump moves the parent and the pinned packs, never automerged: the
Niord base bump app opens or moves each country's bump PR minutes after every publish (the owner,
2026-11-03), and a small workflow in each country, the Base bump workflow,
`.github/workflows/base-bump.yml`, re-pins the lock on the
`base-bump/` branch once release `v<version>`'s notes confirm the hashes and moves each pinned pack to its home's
newest pack in the same PR (§4.12); it is the one bump, and Dependabot opens security updates only (§4.2).
Home packs are in the country's tree; `en` comes with the base product.

**The country kit is its own package (the owner, 2026-10-26; since 4.0.109):** the country kit, its guides,
`API-CHANGES.md`, the backend contract `country-api.md` and the templates are
`org.niord:niord-country-kit` of the same base product version, not a part of `niord-fe-src`. A
country's lock pins it by its own sha256, and it is unpacked at the country repository's root, in `.niord/kit/`;
`niord-fe-src` holds the frontend sources only.

**Reading.** The base product zip: GitHub's Maven registry needs a token even for a public package; the country
pipeline reads with its own workflow token (as for `niord-base`), a developer with a `read:packages`
token in `~/.m2/settings.xml`, a country in another GitHub organisation the same. The zip is also an
asset of the GitHub release on `v4.0.<run>` in the base product's repository (§4.11 rule 2), which Frontend CI's
upgrade test reads (§4.8); a country reads the
registry. Packs: anonymously, over
HTTPS from GHCR (the kit's own OCI client, Node built-ins only). Only the base product holds the base product's
publishing token (`NIORD_PUBLISH_TOKEN`, §4.11 rule 1); a home publishes its packs with its own workflow token.

### 3.2 How a country builds

One committed entry point, `fe/setup.mjs` — a stub written by `init` that verifies the kit inside the
pinned zip by its hash and runs it; the kit refuses a modified stub:

1. **Read the pins:** the `<parent>` version from the country's `be/pom.xml` (`setup.mjs` runs in `fe/` and reads `../be/pom.xml`), the hashes of the zip and the
   foreign packs from `fe/niord-pins.lock.json` (§4.11).
2. **Fetch and verify** `niord-fe-src` (through `be/mvnw -f be/pom.xml dependency:copy`, which only downloads the zip into `fe/.niord/`, so Maven's credentials and cache
   serve the frontend as they serve the backend) and each foreign pack (from GHCR by digest); check each
   against its pinned hash **before** unpacking or executing anything; unpack into `fe/.niord/base/` and
   `fe/.niord/packs/<lang>/`, refusing symlinks, links and any path outside the target. Locally either may
   come from a checkout instead — `NIORD_BASE=../niord/fe`, `NIORD_PACK_DA=../niord-dk/fe/packs/da` —
   copied in (the builder accepts asset inputs only inside the workspace root, `resolve-assets.js:25-27`;
   with `NIORD_BASE` the catalog is built from the checkout, with exactly the zip's include list).
   These overrides are **refused when `CI` is set**, and a production build or `image:info` refuses a
   workspace staged from one (`guard-build.mjs`'s refusal, which `image:info` runs too, besides refusing
   `NIORD_BASE` or a `NIORD_PACK_<LANG>` in its own environment while `CI` is set); every workspace records its provenance (§4.11).
   **As built:** the stub fetches and verifies the kit package and the zip and hands over to the kit;
   the packs are the kit's (`setup-kit.mjs`), after the stub's hash check. For each
   pinned pack the kit reads the pack's withdrawn marker first, anonymously (§4.11 rule 6), then the manifest and
   the archive by the pin's digest (`country-kit/lib/oci.mjs`), each checked against its digest before the
   archive is read; the archive is kept in `fe/.niord/oci/<sha256>.zip` and checked again on reuse. It stages
   every pack but `en` into `fe/.niord/packs/<lang>/` in exactly the canonical archive's layout (§4.5) — a
   pinned pack from its archive, a `NIORD_PACK_<LANG>` copy and a **home pack** from its folder the same way —
   so the country's Maven build reads every pack's backend half from that one path; the checks still read a
   home pack from `fe/packs/<lang>/`, so its status files stay the home's.
3. **Check** every pack, the branding and the escape hatch (§4.6). The build stops here on a failure.
4. **Stage** what the build ships into `fe/.niord/build/<app>/`: per application, exactly the bundles of its `uiLanguages` (`en` only when they name it, R9), stripped to the catalog's keys, with every key a bundle lacks (escape-hatch
   keys included) filled from `en` — so neither application fetches `en` at runtime: the admin loader fetches only
   the requested bundle, and neither application keeps a runtime fallback language (performance
   review, 2026-10-20; owner, 2026-10-21). The generated language configuration lists which languages have branding, and the loaders
   request branding only for those (no 404 in the base product's own build); the flags of
   every language it names; the country's branding and legal files. This staging directory is the only
   asset input for packs and branding, so status files, licences and other countries can never be copied. It also writes the build report (§4.11 rule 2) into `fe/.niord/build/`.
   **In the base product's own tree**, whose `languages/` holds `en` only, the step is
   `country-kit/stage-languages.mjs`: it stages every in-tree pack per
   application into `fe/.niord/build/<app>/` — `en` byte for byte; every other bundle the application has
   stripped to the catalog's keys (a key `en` lacks is dropped and listed), a value whose placeholders differ from
   the English text's or that is empty where English is not staged as the English value (listed), and the keys it
   lacks filled from `en`, so no staged bundle carries a broken value even in a build that runs no check (a
   complete bundle with nothing stripped or replaced is still a byte copy); and the flag of every pack in both
   applications — and each `angular.json`
   configuration's ignore list on that one staged input picks its languages, held to its language configuration
   by `contents:check`. It stages per application (`--app admin|info`, which every npm build and serve script
   passes), so two dev servers started together never write the same folder. "Exactly its `uiLanguages`" is the
   country workspace's case (`country-kit/lib/stage-country.mjs`, which reuses the staging step's
   functions), and `contents:check` holds every base product configuration to exactly its UI
   bundles too, `en` not required among them.
5. **Generate the workspace** in `fe/` (every generated file gitignored), from the kit's
   `workspace-files.json` so no root file the base product's build reads can be forgotten: `angular.json`
   (one project per application, keeping the names `niord-app` and `niord-info`; one standalone configuration per
   deployable, the base product's composition rule, `country-kit/README.md`, _Set-up_ step 6 —
   the base product's own non-country configurations of each application, none invented: `production`,
   `development`, `local` and `test` for the administration app, `production` and `local` for the public site);
   `package.json` (the base product's dependencies and the country's, its `prepare` hook removed, the kit's scripts `start:admin`,
   `start:info`, `build` — `guard-build.mjs`, the audit, `ng build niord-app`,
   `ng build niord-info --stats-json` and then `check-build.mjs`, the checks on every country build (§4.8) — `test`,
   `lint`, `audit`, `bundle:info` (the public site's bundle report against the country's
   `info/bundle-baseline.json`, which `-- --update-baseline` records) and `image:info`, and `e2e`,
   `playwright test --pass-with-no-tests`, so a country without specs passes; every
   script constant text); `package-lock.json` (the base product's with the country's entries), `.npmrc`, `.nvmrc`,
   `.postcssrc.json`; `tsconfig.json` with the aliases of §4.3; the Vitest and ESLint configurations over
   the country's files; `playwright.config.ts` (constant text: the base product's `playwright.config.ts`,
   which the zip carries, spread with `testDir: './e2e'` and `tsconfig: './.niord/tsconfig.e2e.json'`, a generated
   file mapping `@niord/admin/e2e` to the base product's login fixtures, `ui-apps/admin/e2e/auth/fixtures.ts`, so a
   base product change to the suite's options reaches the country with the bump); `proxy.conf.mjs`; the generated
   language configuration, style entries and `fileReplacements` (§4.3, §4.8).
6. Then `npm ci` and `npm run build` (the pipeline) or `npm run start:admin` (a developer); `npm test` and
   `npm run lint` run the country's own specs and lint with the base product's rules; `npm run e2e` runs its Playwright specs (`e2e/`) against a running `start:admin` with the base product's login fixtures, which a spec imports as `@niord/admin/e2e` (`import { expect, test } from '@niord/admin/e2e'`) — locally, as the base product's suite runs, with the Keycloak the runner names in `KEYCLOAK_URL`: the suite and its fixtures name no environment and have no default.

The country never writes `angular.json`, `package.json` or a language list, so a base product change to build
options, budgets, the service worker or a seam's default reaches every country with the version bump.
**Country dependencies (R12).** A country's own npm packages are a real npm manifest, `fe/country/package.json`
with its `package-lock.json` (exact versions; per package a reason and the application it is for, under the
manifest's own `niord` field, which npm ignores), which `setup.mjs` merges on top of the
base product's lockfile; the country commits both, so its builds are reproducible and Dependabot (an npm entry for
`/fe/country`, security updates only) can watch them. The lock holds exactly the entries the merged tree adds to
the base product's, and `node setup.mjs --update-country-lock` writes it (a standalone npm lock would hoist shared
packages at its own versions and collide with the base tree). It fails when a country package is a base product package at
another version; when its peer ranges reject the base product's installed framework versions (checked explicitly:
the workspace's `legacy-peer-deps` would hide it); when any **base product** entry of the merged tree differs from
the pinned base product's lockfile (version, resolved URL, integrity); when a resolved URL is not
`https://registry.npmjs.org/` or an integrity is not sha512; when a country package has an install script and
no reason for one; when any entry the country's lock adds, a transitive one included, was published under 7 days
ago (the registry's answer is cached in `.niord/` once it is older, so a repeated set-up needs no network for it). A country package names the application it is
for and loads no remote code (§4.11). `npm audit --omit=dev --audit-level=high` and `npm audit signatures` run
on every build (the generated `build` script runs them first) and weekly (`npm run audit`, scheduled by the
country's workflow); a high or critical advisory, or an invalid or missing signature, fails unless listed with a
reason in `audit-accepted.json`, printed like the escape hatch. `npm audit signatures` exiting
3221226505 (a Windows process crash) with no report is run once more with a fresh npm cache of its own, removed
afterwards; a second crash fails, as any answer that is no report does. An answer of the registry's that npm
could not read (`invalid json response body at <url>`) is run again, three attempts in all with a pause of 2 s,
then 6 s, and fails when it stays unreadable; a report decides as without a retry (2026-11-24).

### 3.3 Who owns what

| Concern | Base product | Country |
|---|---|---|
| Application code, shared folders, build options, budgets, service worker | ✓ | — |
| The seam catalogue and a neutral default for every seam | ✓ | a file at a seam's conventional path |
| English text, the key set, the key catalog | ✓ (`fe/languages/en`) | — |
| Other languages | — | its home packs (`da` in DK, `kl` in GL); pins for the others |
| The branding contract: which keys and files may be branded | ✓ (`country-kit/branding.schema.json`) | fills it in |
| Brand tokens and fonts | `tokens.base.css`, `tokens.default.css` | `tokens.<cc>.css`, its font sheet and files |
| Info container, nginx, security headers, checks | ✓ | calls them |
| Which languages a deployment offers at runtime | — | the backend settings: the defaults in its settings file, each deployment's own values in its database (§4.1; `docs/code-data-config-plan.md` §5) |
| Pipelines | publishes the source zip | builds its bundle and image, publishes its home packs |

### 3.4 The sample deployment and its safety rules (P9)

One command starts a working Niord with content, for a developer trying the base product and for a country
starting out (R11): `./start-local.sh --sample` in the base product's repository, and the same flag of a
country's launcher. This subsection is what the code and the other documents cite as "P9" and as "P9's safety
rules". `docs/code-data-config-plan.md` §8 holds the backend's detail: the format, the guard, the loader and
the regeneration.

**What it is.** The sample is `be/niord/sample/`: Danish sample content layered on the base product's bootstrap
(`docs/code-data-config-plan.md` §7). It carries the DK rows it needs (DK's domains and series, `Audio` as a
restatement of the base product's type with its domain link added, the domain links of `NAVTEX` and `Twitter`,
the two scripts its mailing lists name and the DGPS pair its category Radio navigation/DGPS names), Danish base
data (charts, areas, sources, publications, mailing lists, the categories beyond the bootstrap's) and the sample
messages: navigational warnings and notices in Danish waters, in every status, with geometry, attachments and
both languages, so lists, map, editor, publications and the public site show something. Nothing is fetched from
`niord-dk`, so no credential is needed; drift from `niord-dk`'s bootstrap is accepted. The sample is versioned
with the base product and is built, captured and checked against the schema by one regeneration script,
`be/niord/scripts/regen-sample.sh`. The local realm has one sample user per role (`sysadmin`, `admin`, `editor`,
`user`), named in the README. The integration suite's fixture is a database bootstrapped with the base product's
archive and loaded with the sample; it is in niord-base's test-jar, which is no deployable and goes into no
image.

**The launcher.** `./start-local.sh --sample` starts the services, creates the database `niord_sample` when it
is missing (any other `NIORD_DB` is refused), starts the backend, posts the bootstrap with the base product's
archive only, stops the backend, loads the sample with `psql`, starts the backend again (its caches and message
index then see the sample), starts both frontends, and ends with the URLs, the sample logins and the local-only
warning. Run again on a loaded database it starts it again; `--reset` starts from scratch and drops only that
database. It refuses any `NIORD_HOME_DIR` but `~/.niord-local/niord_sample`, and it refuses `--fe-only` and `--own-db`.
A country's launcher (the kit's `templates/start-local.sh`, §5 step 9) does the same on `niord_sample_<cc>`
with the country's backend, settings and texts: the base product's archive alone, then the sample, with no
country bootstrap. The sample loads on any country's backend after the base archive: its own ids start at
1,000,001, and every base row it names is found by a natural key (`SampleOnACountryBaseITTest`). The base
product's `--sample` stays English only, while DK's launcher starts with Danish on (the owner's requirement of
2026-10-23).

**The safety rules (security review, 2026-10-19).** They hold for every change to the sample, the launchers,
the loader, the builds and the publish:

1. **Never in a deployable.** The sample is never on a deployable's classpath or in an image: it lives in
   `be/niord/sample/` outside `src/main/resources`, and the launcher loads it with `psql` into the loopback
   database after the backend has built the schema — not a Liquibase context, a Spring profile, an endpoint or a
   setting. A base product CI check fails when any jar or image contains it: `be/k8s/scripts/check-no-sample.sh`
   (`CheckNoSample.java`) finds a file of the sample by its layout path, its name, its SHA-256, a sentinel line
   read from the SQL or the messages' uid prefix, also inside nested jars and zips and in a `docker export` tar.
   It runs in Backend CI after the build (the jars and the bootstrap zip), in `publish.yml` before the first
   deploy and over the image's inputs before the registry log-in, and in `./verify-local`'s backend part.
2. **Loopback only, one database.** The loader, `be/niord/scripts/load-sample.sh`, refuses unless the database
   host is loopback, the database container publishes its port on loopback only, and the database is
   `niord_sample`, or `niord_sample_<cc>` for a country's launcher (a two-letter lower-case country code);
   `--reset` drops only that database.
3. **One package carries it, for a local run only:** `org.niord:niord-local-run:<v>:zip`
   (`be/scripts/local-run-zip.sh`). No deployable carries the sample (the jars, the bootstrap zip, the image),
   and the zip is never an image input: no Dockerfile or image step reads it, the no-sample check fails on it,
   and neither Backend CI nor `publish.yml` passes it to that check. `LocalRunZipTest` and
   `release-workflows.test.mjs` hold this.
4. **Written for the sample, never exported.** The messages are written for the sample, never exported from any
   Niord environment or a local import of one; every title starts with "SAMPLE —". The sample has no user,
   contact, comment, history or recipient rows, its only e-mail domain is `example.org`, and its attachments are
   generated with no metadata; the regeneration script fails on anything else (an e-mail outside `example.org`,
   a phone pattern, a table outside an allow-list; `SampleSafetyCheck`). A seed made from a copy of a real
   environment's data or from a production export is never committed as a country seed.
5. **A local trial on this machine only.** The sample users exist only in the local realm that `docker-compose`
   imports; the chart's realm gains none. Every port stays on `127.0.0.1`, and the launcher's closing lines and
   the README say: a local trial on this machine only — Keycloak in development mode, known passwords; never
   expose it; a country deploys with the chart. Mail goes to the loopback MailHog; SECOM's outbox dispatch is
   off.

---

## 4. The contracts

### 4.1 The country manifest — `fe/niord-country.json`

What a country's build can serve. GL as the example:

```json
{
  "country": "gl",
  "name": "Greenland",
  "apps": {
    "admin": { "uiLanguages": ["da", "en"], "contentLanguages": ["da", "en", "kl"], "defaultLanguage": "da" },
    "info":  { "uiLanguages": ["kl", "da", "en"], "defaultLanguage": "kl" }
  },
  "packs": {
    "da": { "pinned": true },
    "kl": { "home": true, "publish": false }
  },
  "locales": {},
  "overrides": []
}
```

- **`uiLanguages`** — the application's UI is offered in these; each needs that application's text in its
  pack, complete (§4.6). **`contentLanguages`** (administration app) — message content may be written in
  these; each needs only the pack's name and flag. No language list is fixed in shared code.
- **`en`** completes every bundle but ships only where offered: staging fills every bundle's missing keys
  from it (§3.2 step 4), and a build ships exactly its `uiLanguages` — `en` only when they name it (R9). Neither
  application keeps a runtime fallback language (§4.6).
- **`packs`** — `pinned`: a foreign pack, its full reference, tag and digest in `fe/niord-pins.lock.json` (§3.1, §4.11), under the GHCR owner the entry's optional `owner` names (default `sifa-dk`); `home`: in `fe/packs/<lang>/`; `publish`: whether
  `pack.yml` publishes it: a pack goes to GHCR only when more than one country is known to use it, and a
  country that wants another's pack agrees on it with the pack's owners first (§4.5; the owner, 2026-10-23).
  Promoting a pack later is flipping the flag. The example's `kl` (`publish: false`) is GL's. The lock's `packs` maps each pinned language to that reference string,
  `ghcr.io/<owner>/niord-lang-<lang>:<tag>@sha256:<64 hex>`, which `setup.mjs --update-pins` writes; set-up
  fails a pin for a pack the manifest does not pin, a pinned pack without a pin (unless a `NIORD_PACK_<LANG>`
  names it), a pin for another language than its key and a pin under another owner than the entry's.
- **`locales`** — optional per-country locale for a language, e.g. `{ "da": "da-FO" }` for a Faroese
  deploy; otherwise `pack.json`'s.
- **`overrides`** — the guarded overrides (§4.9), each with its `basedOn` fingerprints; normally `[]`, written
  by `setup.mjs --add-override` and `--accept-override`.
- **The deployment chooses among them at runtime.** Both applications take the offered languages from the
  backend's web settings at boot (`siteLanguages`, `modelLanguages`, `defaultLanguage`,
  `infoSiteLanguages`, `infoDefaultLanguage`; admin `language-settings.service.ts:36-55`). The manifest
  says what the build *can* serve; a setting naming a language the build lacks is dropped (both
  applications filter against the shipped list). `setup.mjs` warns when the country's settings file
  (`be/src/main/resources/defaults/country/settings.json`) names a language the manifest lacks, and warns when
  its `modelLanguages` names a language whose pack has no backend half yet (the deployment's default language serves until it has one; §4.6). A content-only language cannot be the
  backend's `defaultLanguage` until its backend half exists — a missing default-language file stays fatal
  (`docs/code-data-config-plan.md` §4.2) — so `setup.mjs` fails when the settings file's `defaultLanguage` names
  such a language.
- The kit's schema rejects an unknown field.

### 4.2 The country repository

```
niord-<cc>/
  be/
    pom.xml  mvnw                  <parent> niord 4.0.<run>; reads the packs' backend half that setup.mjs
                                   staged in fe/.niord/packs/ (packs are on GHCR, R13, not Maven)
    src/main/resources/            defaults/country/ (settings, texts) and application.yml, in the jar
    overrides.json                 the guarded backend overrides, images only (§4.9)
    bootstrap/                     outside the jar: the bootstrap data with its README.md, releases/<yyyy-mm-dd>-<topic>/
                                   the release import files, each release folder with its own README.md;
                                   releases/ itself holds folders only (docs/code-data-config-plan.md §7.1, §9)
  fe/
    setup.mjs                      the entry point (§3.2); written by init
    niord-country.json             the manifest (§4.1)
    .gitattributes                 text stored LF, the binary types never converted; written by init
    build-urls-allowed.json        the country's own absolute URLs, each with a reason (§4.11 rule 5); normally absent
    admin/
      app/                         seam files and the components they provide (§4.3), with their specs
      environments/                environment.{development,local,test,production}.ts — the country's backends
      assets/branding/             files named in branding.schema.json: logo(s), <lang>.json
      assets/legal/                <doc>.<lang>.html (the footer's dialogs)
      overrides/                   guarded overrides (§4.9): a .ts copy, its template and styles beside it
      branding-unaccepted.json     branding values and legal documents awaiting acceptance (§4.5); normally absent
    info/
      app/                         info.config.ts, index.html, the footer component, with specs
      assets/branding/  assets/legal/  overrides/  branding-unaccepted.json
      bundle-baseline.json         the first visit's transfer, which npm run build holds the build to (§4.8)
    styles/
      tokens.<cc>.css              brand values only
      fonts.<cc>.css  fonts/<face>/   optional: @font-face with relative url(), the woff2 files and the
                                   licence (GL: fonts.gl.css, fonts/lato/ with OFL.txt)
    e2e/                           the country's Playwright specs (npm run e2e, §3.2)
    packs/<lang>/                  home packs (§4.5)
    missing-keys-accepted.json     the escape hatch (§4.6); normally []
    text-overrides/<lang>.json     the country's own wording of base product keys (§4.10); normally absent
    country/package.json           the country's own npm packages (R12), with its package-lock.json
    niord-pins.lock.json           the hashes of the pinned base product zip and foreign packs (§4.11)
    audit-accepted.json            npm audit findings accepted, each with a reason (§3.2); normally absent
    .niord/ angular.json package*.json … node_modules/     generated by setup.mjs, gitignored
  .github/workflows/
    image.yml                      gains the frontend job (init writes image.yml with it only when the
                                   repository has none, and otherwise prints the job to add by hand)
    pack.yml                       publishes a home pack as ghcr.io/<owner>/niord-lang-<lang> with the
                                   repository's own token (path filter fe/packs/**, one pack per push; dispatch
                                   for a publish or a withdrawal, §4.5)
  pack-releases.md                 the home's record of every pack published and withdrawn (§4.5); written by init
  .github/dependabot.yml           security updates only: npm for /fe/country and github-actions, no maven
                                   (the base bump moves the <parent>; the base product's npm alerts are the
                                   base product's; a country takes a fix with the version)
  .github/workflows/base-bump.yml  the Base bump workflow: on a base-bump/ branch moving the <parent> (the
                                   Niord base bump app's PR, or one a person opens), checks release
                                   v<version>'s notes, pins the lock, moves the pinned packs, posts the report
                                   with the human actions, sets niord/upgrade-actions (written by init)
  .github/CODEOWNERS               names the owners of the override, escape-hatch and dependency files
```

### 4.3 The seams

A seam is a base product file with a neutral default that a country may replace. The catalogue is
`country-kit/seams.json`; `setup.mjs` adds a `fileReplacements` entry (or the `index` option) for every
seam whose conventional path exists in the country tree. A non-spec file in `admin/app/` or `info/app/`
that is neither a seam nor imported by one fails the check, and so does a file in `admin/overrides/` or
`info/overrides/` that is neither named by an `overrides` entry nor used by one; `*.spec.ts` files are the country's tests.

| App | Seam (base product default) | Country file | Used by |
|---|---|---|---|
| admin | `country-specific/default/country.providers.ts` (`provideCountry()`, no providers; §4.9) | `admin/app/country.providers.ts` + its components | DK, GL |
| admin | the country routes and menu: `country-specific/default/country.routes.ts` and `country.menu.ts` (none; §4.9) | `admin/app/country.routes.ts`, `admin/app/country.menu.ts` | the example |
| info | the country routes and providers: `country-specific/default/country.routes.ts` and `country.providers.ts` (none; §4.9) | `info/app/country.routes.ts`, `info/app/country.providers.ts` | the example |
| admin | `environments/environment.{development,local,test,production}.ts` (neutral: `environment.ts`, development and local reach the local stack — `/rest` through the dev proxy, Keycloak at `http://localhost:8080/auth`, where the docker-compose realm's issuer is fixed, `be/docker-compose.yml:41-45`; test and production `/rest` and `/auth` on the deployment's own origin; no WMS, a world view) | `admin/environments/environment.<name>.ts` | DK (its test backend, Keycloak, WMS, map centre) |
| info | `country-specific/default/info.config.ts` | `info/app/info.config.ts` + the footer component | DK, GL |
| info | `src/index.html` | `info/app/index.html` | DK, GL |
| both | the language configuration | — generated by the kit from the manifest and `pack.json`: labels, locales, flag paths, the lists, the default | — |
| both | `styles/tokens.active.css` | — generated as the `angular.json` `styles` array, data and no CSS file: the generated style-entry wrapper, then `styles/tokens.<cc>.css` (the base product's `tokens.active.css` when the country has none), then `styles/fonts.<cc>.css` when it exists, so no generated stylesheet names the country (`tokens.base.css` stays imported by each application's `styles.css`) | — |

- **The production environment.** Every production configuration puts `environment.production.ts` in place
  of `environment.ts`; it holds what the runtime overlay never sets (admin `core/config/web-settings.ts:52-58`
  excludes `keycloak` and `helpUrls`) and `map.wmsUrl`, a frontend-only field without which no WMS layer
  renders (`core/config/app-config.interface.ts:25-27`, `shared/components/ol-map/base-map-layers.ts:46`). A
  country's copy may set `appName`, `helpUrls`, `keycloak.realm` / `clientId` and the `map` leaves;
  `apiBaseUrl` stays `/rest`, `keycloak.url` `/auth`, and `map.wmsUrl`, if set, is the backend's same-origin
  WMS proxy `/wms/` (`be/niord/src/main/java/org/niord/web/wms/WmsProxyServlet.java:55`). `setup.mjs` fails
  when any of the three is not origin-relative, so the CSP needs no WMS origin (§4.11 rule 5). The
  development files are the exception to `/auth`: on the local stack Keycloak answers at
  `http://localhost:8080/auth`, the issuer the realm and the backend's local profile name
  (`be/docker-compose.yml:41-45`, `be/niord-app/src/main/resources/application-local.yml:71`), so a proxied
  `/auth` would not match it; `/rest` goes through the dev proxy as everywhere.
- **The country API — an enforced maintenance boundary, not a security boundary.** Country code, overrides
  and country packages run in the same browser origin with the signed-in user's full privileges; they are
  reviewed as base product code, and the security rules of §4.11 bound them. Each application exports a small public API: `@niord/admin/api`
  and `@niord/info/api` (one `ui-apps/<app>/src/public-api.ts` each, next to `main.ts` and so outside what
  `--add-override` accepts; aliases beside the existing `@niord/map` and `@niord/html`): the
  tokens of every extension point and slot, `InfoConfig`, and the services, pipes and components a country
  may use (what a footer and a promulgation component need), plus a specs-only entry each,
  `@niord/admin/api/testing` and `@niord/info/api/testing` (`src/testing-api.ts`: the test helpers DK's, GL's
  and the example's specs use, test helpers that belong in no build — the public site's are typed against
  Vitest's globals and load the English bundle — so only a spec may import the entry, which the app tsconfig
  leaves out and the spec tsconfig takes in). The generated
  root TypeScript configuration maps these four entry points and the base product's own `@niord/map/*` and
  `@niord/html/*`, which the base product's files compile through and lint bans in country files; nothing else of
  the base product resolves. Lint enforces it as well — static
  and dynamic imports, no relative path out of `admin/app`, `admin/overrides`, `admin/environments`, `info/app` or
  `info/overrides` (a spec may also reach its application's `assets/`), no import of one application's API
  from the other's country code. Internals are reachable only from a file listed as a guarded override
  (§4.9), through `@niord/admin/internal/*` / `@niord/info/internal/*`, which the generator maps only in the
  application tsconfigs of an application the country overrides a file of (TypeScript's paths are per program,
  not per file); lint keeps every other file of that application to the API. Within the country tree a file imports relatively.
  A country's end-to-end specs, `e2e/**/*.ts`, are linted too, with `niord-app` and in their own
  program (`.niord/tsconfig.e2e.json`): they import only `@niord/admin/e2e` and `@playwright/test`, each exactly,
  `node:` built-ins and files of `e2e/` (`niord-country/e2e-imports`), and carry no inline configuration.
- **Compatibility policy.** The API is a contract kept across base product versions: a change that breaks it is
  named in the PR and recorded in `country-kit/API-CHANGES.md` with what a country must do; a removed export
  is first deprecated (kept, its contract comment marked with a line
  `Deprecated since <version>: <what to use instead>`, or, once the version it goes in is decided,
  `Deprecated since <version>, removed in <version>: <what to use instead>` (a line of another form, or a
  removal not after its start, fails set-up) — never a JSDoc `@deprecated` tag, which the base
  product's lint would turn into an error at once — and every use reported by `setup.mjs` as a warning naming
  the removal version or that it is not scheduled, and in the upgrade report, §4.12) for at
  least one base product version that both DK and GL have taken. Base product code never imports the API, so it stays a one-way facade. The example country compiles against the API in the base product CI, and the upgrade test
  (§4.8) builds it from the previous published version, so a break cannot pass unnoticed.
- **Tailwind.** Each application's style entry is generated as a wrapper: it `@import`s the base product's
  `src/styles.css` and adds `@source` for the country's `admin/app` and `admin/overrides` (or the `info/` pair), and it is `styles[0]`
  in place of the base product's entry (never both). The base product's entries scan only the base product
  tree, so without this a utility only a country
  template uses — GL's footer has `sm:grid-cols-[1fr_auto_1fr]` — would silently be missing. The wrapper's
  text is constant per application and lives in `fe/.niord/generated/<app>/styles.css`; its relative `@source`
  reaches the country's folder, and the imported base entry's own relative `@source` lines still resolve,
  because Tailwind resolves an imported stylesheet's `@source` against the file it is written in (§9).
- **The language configuration is the one mechanism** for a language's name, locale and flag: there is no
  `InfoConfig.languageFlags`, no `AppConfig.languages` or `environment.languages`, no `LANGUAGE_<XX>` key and no
  `languageNameKey()`. Each language has its `label`, `locale` and `flagPath`; the kit's generator produces the
  configuration for a country workspace from the manifest and `pack.json`, where a language's name is
  `pack.json` `label`.

### 4.4 Branding and legal texts

- **The contract** is `country-kit/branding.schema.json`, one section per application: every brandable key
  (placeholders, required or optional) and every brandable file with its output path (logos, the public
  site's `favicon` and `apple-touch-icon`, the legal documents). The administration app's favicon
  (`public/favicon.ico`, the Niord icon) is not brandable: no country brands it, and making it brandable would
  move a base product file. A file a base product page links
  (both icons, `ui-apps/info/src/index.html:10-11`) is required, and the base product's own build stages its
  default copies (`ui-apps/info/public/assets/default/branding/`), so no build links a file it lacks. Every brandable path is base-product-referenced and never shipped by the
  base product's own assets; the Niord mark (`assets/img/niord_white.png` in the header, `assets/img/niord.png` on the home page) is
  fixed by the base product, never brandable; the base product's build shows only the mark where no
  logo is branded, and renders nothing for an optional key no branding sets. `init` writes a skeleton
  of what the schema requires — the required keys in each application's `en.json` and the required files
  as the base product's default copies — and leaves every optional entry out: an empty or placeholder
  value is not neutral, because branding is merged over the bundle and a blank `FOOTER_COPYRIGHT` would
  blank the `en` pack's text. While no key is required, the skeleton is the public site's two
  icons.
- **Both applications** merge `assets/branding/<lang>.json` over the language bundle at runtime and render
  legal documents from `assets/legal/<doc>.<lang>.html` (in the administration app, the footer's one document
  dialog).
- **Not in the base product:** an authority's footer link, copyright and dialog texts, and its logos. They are a
  country's branding, in both applications.

### 4.5 Language packs

```
packs/da/                                  (the same shape in the base product: fe/languages/en/, without backend/ —
                                            niord-base carries the English backend text itself)
  pack.json        { "lang": "da", "label": "Dansk", "locale": "da-DK", "flag": "img/flags/da.png" }
  i18n/admin/da.json     the administration app's text  ─┐ named by their output paths, so the
  i18n/info/da.json      the public site's text          │ staged copy keeps the names the loaders
  img/flags/da.png                                       ─┘ and the service worker know
  img/flags/da.svg the flag's drawing, from which the PNG is rendered — never shipped
  backend/<bundle>.properties   the backend's text (web, message, pdf, mail, template); staged by setup.mjs,
                   copied into the country's jar by its Maven build (docs/code-data-config-plan.md §4.3);
                   other languages' lang.<code> keys optional (a draft PDF's language names); its own
                   lang.<code> missing is a pack-check warning, and the draft PDF then names the
                   language by its code in upper case (no pack.json label is added)
  ATTRIBUTION.txt  the flag's drawing, its sources and its render command — never shipped
  status/          never shipped:
    fingerprints.json  key → fingerprint of the English text it was translated from
    unaccepted.json    keys whose wording awaits acceptance
```

- **The status files' formats.** `status/unaccepted.json` is per application,
  `{ "<app>": { "unaccepted": [<flat keys>] } }`, keys sorted, with a `note` and, beside `unaccepted`, the
  reviewers' optional notes — `kl`'s `cldr` (values taken from CLDR) and `verbatim` (values deliberately the
  Danish text); go-live requires every `unaccepted` empty. The owner takes every Danish text in one pass at the
  very end of the work (ruling of 2026-10-22), not per pull request; until then each new value is listed here.
  `status/fingerprints.json` is
  `{ "<app>": { "<flat key>": "<fingerprint>" } }`, the fingerprint being the first 16 lower-case hex digits of
  the sha256 of the key's UTF-8 English value (one function, exported by the staging script, which the catalog
  and `pack accept` use too). A key is in exactly one of
  `unaccepted` and the fingerprints, and gets its fingerprint only when `pack accept` accepts it (§4.6).
  **The backend half:** `status/unaccepted.json` takes `"backend": { "<bundle>": { "unaccepted": [<keys>] } }`
  (bundles and keys sorted, no `cldr` or `verbatim`) and `status/fingerprints.json`
  `"backend": { "<bundle>": { "<key>": "<fingerprint>" } }`; `pack accept <lang> --app backend --bundle <bundle>
  <key>…` accepts a backend text. A backend key listed twice, listed but missing or empty in its bundle, or listed
  and fingerprinted fails, as a frontend key does; a backend key in neither list is not an error but one warning
  per bundle with the count, so a pack's backend texts written before their status need no fingerprint seeded —
  seeding one would accept a text nobody read.
- **The `en` pack** holds `pack.json`, both bundles, its flag and the flag's drawing, `ATTRIBUTION.txt` and
  `renames.json` — no `status/`: English is the reference, nothing in it awaits acceptance, and its fingerprints
  are the catalog's. `renames.json` is `{ "<app>": { "<new key>": "<old key>" }, "backend": { "<bundle>": {
  "<new key>": "<old key>" } } }`; a section without renames may be left out, so `{}` names none.
- **A country's branding values and legal documents awaiting acceptance are the country's, not a pack's**
  (R5). In a country workspace the list is `fe/<app>/branding-unaccepted.json`: a list per UI language of the
  application, each entry `assets/branding/<lang>.json#<KEY>` or `assets/legal/<doc>.<lang>.html`. The kit's
  branding check (`lib/branding.mjs`, rule 5) fails a bad shape, a language that is not a UI language of the
  application, an entry of another language than its list's, a file the country does not ship, a key its
  branding file does not set or the schema does not name, and an entry listed twice; while any list holds an
  entry it warns once per application, with the count per language, since go-live requires every list empty.
  The list is never staged.

- A pack may omit an application's file if no country uses the language as that application's UI
  language (`kl` has no administration text; it is a content language there).
- **Publishing (R13): the home's own business.** `pack.yml` in the home repository runs on a push to its
  default branch that changes `fe/packs/<lang>/`, and by dispatch for a pack no push changed, or a withdrawal.
  GHCR carries a pack only when more than one country is known to use it; a country that wants another's pack
  first agrees on it with the pack's owners, as GL did with DK for `da`. `kl` is GL's home pack only
  (the owner, 2026-10-23): `publish: false`, staged at build and never published. A home's first publish is therefore the first push to its `main` that changes the pack folder. Two jobs, and a third for a withdrawal:
  - **validate** (no secret beyond the workflow token, so no personal access token; `permissions: contents: read` for the private home's checkout and `packages: read` for the base product's registry, from which `be/mvnw -f be/pom.xml dependency:copy` fetches the latest published `niord-fe-src` with that token, as `image.yml` reads `niord-base`, its version the newest in the registry's `maven-metadata.xml`; the home's pinned kit reads only that zip's `catalog.json`, as data): the pack check against the latest base product
    catalog (informational — consumers are on different versions), the strict `pack.json` schema and the
    markup rule (§4.6), and a **canonical archive** of the pack — regular files only, exactly the pack
    layout (`pack.json`, `i18n/{admin,info}/<lang>.json`, `backend/<bundle>.properties`, `img/flags/<lang>.{png,svg}`, `ATTRIBUTION.txt`,
    `status/fingerprints.json`; never `status/unaccepted.json`, which holds reviewers' notes), fixed order and
    timestamps, size caps — with its sha256.
  - **publish** (needs validate; `permissions: packages: write` only; runs only on the default branch):
    pushes that archive unchanged as `ghcr.io/<owner>/niord-lang-<lang>:<run>`, `<owner>` the home repository's GitHub owner in lower case (the only namespace its workflow token can push to; `pack.yml` reads it from the run, so `init` needs no owner), and writes the digest into
    the run summary; the home records each publish (its tag and digest) and withdrawal in its `pack-releases.md`, the human record.
  - **withdraw** (dispatch only, with the tag and a reason, both through `env:` only; `permissions: packages: write` only; default branch only): adds the tag's digest, the tag and the reason to the pack's **withdrawn marker** and pushes the whole marker as `ghcr.io/<owner>/niord-lang-<lang>:withdrawn`, a small OCI artifact; the first publish pushes it empty. Consumers read it anonymously, like the pack (§4.11 rule 6).

  The package is set to public once. One pack per change; nothing else is rebuilt; every consumer's next base
  bump moves its pin to the new pack (`node setup.mjs --update-pins` in the Base bump workflow), **never
  automerged** (§4.12). The language code reaches every script through
  `env:` only and must match `^[a-z]{2,3}$`, the folder and `pack.json`.

  **As built** (`templates/pack.yml`, `country-kit/lib/pack-archive.mjs`, `country-kit/lib/oci.mjs`). The workflow has
  `permissions: contents: read` and one concurrency group, `pack`, never cancelled, since the withdrawn marker is
  read, changed and pushed whole, and queued (`queue: max`): GitHub's default keeps one pending run per group and
  cancels it when a third arrives, which could drop a queued withdrawal. It runs on a push to `main` with the path filter `fe/packs/**` less
  `fe/packs/*/status/unaccepted.json` — the archive leaves the reviewers' notes out, so a change to them alone
  would publish the same bytes under a new tag — and by dispatch (`lang`, `action` publish or withdraw,
  `tag`, `reason`). The first publish is the first push to `main` that changes a pack folder: a home that adds
  `pack.yml` and its pack in one merge publishes at that merge, and dispatches `publish` only when no pack folder
  changed; the owner checks the organisation's package setting before it and makes the package public after it.
  - `validate` (`contents: read`, `packages: read`): _Which pack_ takes the dispatch's `lang`
    (`^[a-z]{2,3}$`, its folder present) or the one pack folder the push changed between `github.event.before` and
    the commit, `status/unaccepted.json` left out of the count as the push filter leaves it out — none is a
    notice and nothing to publish, and so is a push that removed the folder it changed,
    two or more fail asking for a dispatch each, an all-zero or malformed `before` fails asking
    for a dispatch, and so does a `before` no ref reaches (a force-push); _Set up the workspace_ (`node
    setup.mjs`: every pack, for the applications the manifest uses it in, against the pinned catalog, with the
    strict `pack.json` schema and the markup rule, fatal, as the home's own build); _Fetch the latest base product
    catalog_ (`pack.mjs latest-catalog`, Maven only); these two steps alone hold `GITHUB_TOKEN`. Then, with no
    token, `pack.mjs validate <lang> --catalog .niord/latest-catalog.json --out <dir>`: it refuses a language that
    is not a home pack of the manifest, builds the canonical archive with its `.sha256`, runs the pack check of that
    pack against the latest catalog as information only (printed and in the run summary; consumers are on
    different versions) and writes `lang`, `publish` and `sha256` to the step's outputs; it fails only when no
    archive can be built. `sha256sum -c` checks the archive; the kit's OCI client, `lib/oci.mjs` — one file, Node's
    built-ins and `fetch`, importing no other kit file — is copied beside it, and both go on as the artifact `pack`
    (one day), their SHA-256s as job outputs. _Which pack_ runs on every run; once it named a pack, set-up, the
    client's hand-over and the upload run too, the catalog fetch and the archive only on a push or a dispatch of
    `publish`, so a withdraw dispatch leaves
    `publish` and `sha256` empty. The archive holds every half the pack folder has: a half the home's manifest
    does not use (an `i18n/<app>/` of an application it does not use the pack in) is published with the
    information-only check alone, and a consumer that uses it checks it fatally at its own set-up. That check runs
    without the home's escape hatch and text overrides, standing in for a consumer with neither, so GL's run
    summary lists its 163 hatch-accepted backend keys as errors on every publish, by design.
  - `publish` (`needs: validate`; `packages: write` only; `main` only; on a push, or a dispatch of `publish`, for
    a pack with `publish: true`): no checkout and no npm; it checks both files against validate's SHA-256s, then
    `node oci.mjs publish --archive … --sha256 …` with the run token as `PACK_TOKEN` and every other value from
    the run's environment: the archive re-hashed; a tag that exists refused — a tag that exists is a completed
    publish, and publishing again is a new dispatch, which gets a new run number; on a first publish (the pack has
    no marker) the empty withdrawn marker pushed **before** the pack, so a publish broken between the two
    pushes leaves only a marker, which no consumer pins, and a re-run of the failed job, with the same run number,
    finishes it; the archive pushed unchanged as `:<GITHUB_RUN_NUMBER>` under `GITHUB_REPOSITORY_OWNER` in lower
    case, a manifest `PUT` answered without `Docker-Content-Digest` confirmed by a `HEAD` of the digest; and
    the reference with its digest written into the summary with the line for `pack-releases.md`. GitHub counts
    `GITHUB_RUN_NUMBER` per workflow file: a renamed or recreated `pack.yml` starts again at 1, and publish refuses
    "exists already" until the numbering passes the old tags; a tag is never overwritten.
  - `withdraw` (`needs: validate`; dispatch of `withdraw` on `main` only; `packages: write` only): checks the
    client's SHA-256, the tag (digits) and the reason (not blank), then `node oci.mjs withdraw` reads the marker
    (none: publish first), resolves the tag's digest, adds `{ digest, tag, reason, date }` unless listed already,
    and pushes the whole marker. It needs set-up green on `main`, since validate runs it for the verified client;
    with `main` red a home withdraws by hand with the kit README's commands. The run summary says `withdrawn` or
    `withdrawn already`; the home checks it before recording the withdrawal, and dispatching again is safe. A `|`
    in the reason is written `\|` in the summary's row, fenced longer than any run of backticks in it, while the
    marker keeps the reason as given. Each withdrawal leaves the previous `:withdrawn` manifest as an
    untagged version of the package: harmless, and housekeeping for the owner on the package's page.

  On the registry a pack is an OCI image manifest whose config, `application/vnd.niord.lang-pack.config.v1+json`,
  is `{"format":1,"lang":"<lang>"}` and whose one layer, `application/vnd.niord.lang-pack.v1+zip`, is the
  archive; the marker `:withdrawn` has the same config and one layer
  `application/vnd.niord.lang-pack.withdrawn.v1+json`, `{"format":1,"lang":"<lang>","withdrawn":[{digest, tag,
  reason, date}]}`, strict (a tag of digits, a reason of one line and at most 500 characters, at most 256 KiB) —
  the OCI 1.0 artifact convention with a custom config type, chosen over an empty config with `artifactType`;
  GHCR accepts it. **The canonical archive**: exactly the layout
  above (`status/unaccepted.json` the one file left out silently; any other file, a link or a non-regular file
  fails, naming it, and so does a folder, empty or not, that holds no entry of the layout), the text
  entries' CRLF and CR turned into LF, entries sorted and dated 1980-01-01 (the kit's `writeZip`), at most 32
  entries, 2 MiB an entry and 8 MiB in all inflated (`pack-archive.mjs`), the PNG flag at most 64 KB; a pulled
  archive's `pack.json` is held to `readPack`'s strict schema before anything is staged, the refusal naming
  `niord-lang-<lang>`. The client's own cap, 16 MiB, bounds the layer's bytes on the wire and sits above
  the 8 MiB, since a stored archive of nearly 8 MiB of content is a little over 8 MiB; the two are never made
  equal. The same files give the same archive on the same Node build, whose zlib deflates it; a publish never
  rebuilds: validate builds once, publish pushes those bytes, and a consumer verifies them against the pin. No run block holds an expression, every one,
  one-liners included, starts with `set -euo pipefail`, and the file names no npm. `pack-releases.md`, at the repository root so that
  editing it runs no publish, is a table of publishes (pack, tag, digest, date) and one of withdrawals (with the
  reason).
- **Moving a pack later** (to another home, or to a shared repository): `git filter-repo` of
  `fe/packs/<lang>` with its history into the new repository, its `pack.yml`, and the GHCR package's
  publish permission granted to that repository; the old home's manifest changes the pack from `home` to
  `pinned`. Within one organisation consumers change nothing — the name names the organisation, not the home repository. A move to another organisation is a rename: the pack is published under the new owner, the old package stays readable for the pins that name it, and every consumer changes its manifest's `owner` and its pin.
- **The pack's source keeps what the base product dropped** (stripping is output-only), so a key removed and
  later re-added keeps its translation. `pack prune --base <version>` deletes keys no catalog at or
  above that version has; the home runs it once every consumer has passed the version. Without `--base`,
  `pack prune` deletes the keys none of the catalogs it is given has (by default the one built from the
  checkout); `--base` needs published catalogs (`catalog.json` at the
  zip's root) and fetches each version's catalog
  (`country-kit/lib/published-catalogs.mjs`): through the tree's Maven wrapper, Maven resolves the meta-version
  `LATEST` of `niord-fe-src` with `-U`, which fetches the registry's `maven-metadata.xml` into the local
  repository (a version range is not resolved by `dependency:copy` and fetches none); every `maven-metadata*.xml`
  in the head local repository, the one `-U` writes (`-Dmaven.repo.local` in `MAVEN_ARGS`, else Maven's
  `settings.localRepository`), is read (`maven-metadata-local.xml` is an install's) — a `-Dmaven.repo.local.tail`
  never, since Maven does not refresh a tail's metadata and a stale one could name a version the registry no longer
  lists — its plain versions from `<version>` up each have their
  zip fetched into `fe/.niord/catalogs/` and only `catalog.json` read as data, held to the catalog format and its
  version. `<version>` must be published; `--base` takes no `--catalog`, and a failed Maven run stops it rather
  than reading stale metadata. Each version costs one Maven run and one zip of about 4 MB in the local
  repository, and is printed as it is read, so `--base` is run rarely; a catalog published as its own
  small artifact would be the cheaper source later. Prune walks the bundles some catalog from `<version>` up
  still has: a backend bundle the base product dropped whole keeps its file, fingerprints and status list in the
  pack until the home removes them by hand, and the pack check names the bundle the catalog does not have until
  then. The same module gives `pack.mjs latest-catalog`. In the base product tree its
  `be/mvnw` names no repository for `niord-fe-src` (GitHub Packages is only its `distributionManagement`), so
  there it reads what the local repository holds unless a `settings.xml` profile names the registry; a country's
  pom names the registry, as for the zip.
- **Tools** (in the kit, `country-kit/pack.mjs` and `country-kit/catalog.mjs` beside
  the staging step, run in the base product tree as `npm run languages:check|migrate|prune|accept` and
  `npm run languages:catalog`; `migrate`, `prune` and `accept` also run in a country
  workspace, from its `fe/` as `node ../.niord/kit/pack.mjs …`, where they edit the home packs under
  `packs/` — a pinned pack never — against the pinned zip's `.niord/base/catalog.json`, `--packs` and `--catalog`
  name another folder or catalog, `check` is refused because set-up is the pack check there, and every line names
  the pack folder it read, `packs/<lang>/`, `.niord/packs/<lang>/` or `languages/<lang>/`):
  `pack check`, `pack migrate <lang>` (applies the catalog's `renamedFrom`, §4.6), `pack prune <lang>
  [--base <version>]`, `pack accept <lang> --app <app> <key>…` (removes each key from `unaccepted.json` and records
  its fingerprint; `--app backend --bundle <bundle>` for the backend half), `catalog`; and, in
  a country workspace only, the two steps of `pack.yml`: `pack latest-catalog --out <file>` (prints
  `latest-catalog: <version>`) and `pack validate <lang> [--catalog <file>] --out <dir>`. A command refuses an
  option it does not take.

### 4.6 The pack check

Run by `setup.mjs` for every pack the manifest names, against the base product's `catalog.json` (generated at
publish; in the base product tree built by `country-kit/catalog.mjs`, which the check runs in memory and
`npm run languages:catalog` writes to `fe/.niord/catalog.json`: per application, every key with a
fingerprint of its English text (`fp`), its placeholders — `{{ name }}` with `name` matching `[\w.]+` — and,
where they apply, `markup` (the English value's tag names, attribute names and every `href`/`src`, when it has an
HTML element tag), `empty` (the English value is blank, so a translation may be too), `pattern` (a public-site
`*_DATE_FORMAT` key) and `list` (the entry count of a public-site `DATE_*` value written with `|`); and
`renamedFrom` for every key the base product renamed (new key → old key, from `fe/languages/en/renames.json`,
where the PR that renames a key names it); a `backend` section does the same for niord-base's English
`*.properties`, whose placeholders are also MessageFormat's `{n}` (in `defaults/base/texts/en/`,
`docs/code-data-config-plan.md` §3.3; the root-level `*_en.properties` are the legacy location, which
`catalog` still reads, refusing a bundle found in both), against which a pack's backend half
is checked; and `rules.datePatternTokens`, the tokens the public site's date renderer understands, read from
`fe/ui-apps/info/src/app/shared/utils/date-pattern-tokens.json`, so a country's packs are held to the renderer of
the version it builds against). `catalog` refuses English that breaks its own rules: an unknown date-pattern
token, an empty list entry, a `renames.json` entry whose new key is not in the catalog or whose old key still
is, a key named `__proto__`, `prototype` or `constructor`. The check runs with the branding and the escape hatch.

| Finding | Result |
|---|---|
| A catalog key missing from a pack, for an application whose `uiLanguages` name that pack; an empty translation where the English text is not empty counts as missing | **fails**, unless listed in the escape hatch |
| A `backend` catalog key missing from the backend half of a pack whose language is a **UI language** of either application | **fails**, unless listed in the escape hatch; the deployment's default language then serves (`DictionaryVo.java:72-82`). For a language that is only a **content language**, a missing backend key or a missing backend half is a **warning** and the deployment's default language serves in its PDFs and mails, so a new language can start with `pack.json` and a flag alone. The base product's own builds stage no pack's backend half, so its in-tree check has no subject for this row and runs the frontend rows only; the row is tested on fixtures |
| A translation whose placeholders are not exactly the English text's | **fails**, exactly like a missing key — and like one it may be listed in the escape hatch; a listed one is replaced by the English text at staging, so English shows |
| A catalog key with a `renamedFrom` whose old key the pack still has | **reported as a rename**, and missing under the first row until the pack's home runs `pack migrate`, which moves the translation and its fingerprint to the new key. A consumer of a foreign pack cannot migrate it: the key is missing (it fails, or goes in the escape hatch) until the home publishes the migrated pack (§4.12) |
| A `*_DATE_FORMAT` value with a token the renderer does not know (the catalog's `rules.datePatternTokens`), or a `DATE_*` list with another number of entries than the English one (12 months, 7 days) or an empty one | **fails** |
| A value whose markup differs from its reference's (tag names, attribute names, every `href`/`src`, each compared as a multiset), or markup in a key whose reference has none. For a pack or a text override the reference is the catalog's English value. For branding it is the country's own English branding (its default language's where `en` is not a UI language), and an `href` may differ from it only in path, on the same `https:` host (security review, §4.11) | **fails** |
| A `pack.json` outside its schema, a flag that is not a PNG inside the pack, a path outside the pack or through a link, a key named `__proto__`, `prototype` or `constructor` | **fails** |
| A `status/unaccepted.json` outside its shape (a `note`; per application `unaccepted` and the reviewers' optional `cldr` and `verbatim`; per backend bundle `unaccepted`), a listed key the pack's bundle lacks or holds empty, a key listed twice or in two lists, a `verbatim` value that is not the same key's value in another pack of the run, a pack key with no fingerprint in `status/fingerprints.json` that is not `unaccepted` (accepted without `pack accept`), or an `unaccepted` key that has one: a key is in exactly one of the two lists, so it is accepted only through `pack accept`. In the backend half a key in neither list is one **warning** per bundle with the count (§4.5). For a **foreign** pack — a pinned one or a `NIORD_PACK_<LANG>` copy, staged from the canonical archive, which never carries `unaccepted.json` — the row does not apply: its status is its home's | **fails** |
| A pack key the catalog does not have (a `lang.<code>` key in the backend half's `web` excepted, `docs/code-data-config-plan.md` §4.3) | **stripped** from the staged bundle; listed |
| A key whose English fingerprint differs from the one the pack translated from | **warning**, listed |
| A branding key not in `branding.schema.json`, or one whose placeholders differ from the schema's | **fails** (a typo, or a key the base product removed) |
| A required branding key or file missing for a UI language | **fails** |
| A brandable PNG whose pixel size is not the `width` or `height` its schema entry gives (the public site's icons 32×32 and 152×152 px, its footer logos 44 px high), four digits standing alone (a year) or an address written into a branding value whose key `forbids` it (`FOOTER_COPYRIGHT`, `MAP_ACCESSIBILITY`; the product fills `{{year}}` and `{{url}}`), or a public-site cookie statement that does not name a key the site stores | **fails** |
| An escape-hatch entry whose key is now translated, or no longer in the catalog | **warning**, listed; `setup.mjs --prune-accepted` rewrites the file |
| An escape-hatch entry for a language or application the manifest does not use (`"app": "backend"` excepted: such an entry also names its `bundle`, and its language must still be one the manifest uses) | **fails** |
| The escape hatch not empty | printed in full on every build |

**In the base product tree** the check is `country-kit/pack.mjs check` (`npm run languages:check`), with one
negative test per row in `country-kit/pack.test.mjs`, but for the
branding rows — the branding half of the markup row, an unknown branding key, a required branding key or
file missing, and the size, year, address and cookie-statement row. Those are in the kit's `lib/branding.mjs`
with one negative test each in `branding.test.mjs`, together with §4.11 rule 4's content rules for brandable
files (the public site's page rule is check-build's); a country's
set-up runs them for each application. Each application's languages are read from its `angular.json`
language configurations (the administration app's `uiLanguages`, the public site's `languages`), per
configuration; the escape hatch is `fe/missing-keys-accepted.json`,
which the base product does not have (absent counts as empty). The base product's tree holds `en` only, so
`languages:check` checks `en` alone.

```json
[
  { "lang": "da", "app": "admin", "key": "editor.foo.bar",
    "reason": "4.0.183 security fix; translation follows in niord-dk", "added": "2026-11-02" },
  { "lang": "da", "app": "backend", "bundle": "mail", "key": "mail.foo.subject",
    "reason": "4.0.183 security fix; translation follows in niord-dk", "added": "2026-11-02" }
]
```

**When the base product adds a key — the workflow for a country that uses a foreign pack** (GL: base product `en`,
DK's `da`, its own `kl`):
1. The base product publishes 4.0.171 with new keys; its catalog lists them. Nobody is blocked: every country
   stays on its pin.
2. **DK need not wait for its own bump to translate.** Its `pack.yml` checks `da` against the *latest*
   catalog, so the new keys show up there at once; DK can publish `da` n+1 with them while DK itself stays
   on 4.0.170 (keys an older base product lacks are stripped, never shipped).
3. GL bumps to 4.0.171 (its bump PR, never automerged). Its build fails on its own `kl` — GL translates those
   keys in the bump PR — and on `da` unless DK's n+1 is out. For `da`, GL chooses one of three:
   - **wait** for DK's n+1 and take both bumps together (the usual case once DK translates promptly);
   - **accept English for now:** list the keys in its escape hatch, with a reason — Danish users of GL see
     English for those few texts until DK's pack arrives;
   - **write the Danish itself** as GL text overrides (§4.10) — GL's own wording, checked like any
     translation. When DK's pack later carries the key, `setup.mjs` lists the override as redundant (or as
     a deliberate difference, if the wording differs), so GL removes it or keeps it knowingly.
4. `setup.mjs` reports which of these each missing `da` key went through; the escape-hatch and override lists
   shrink back as DK's pack catches up.

**A new language** starts as a content language only (its pack needs `pack.json` and a flag) and joins
`uiLanguages` when `pack check` reports it complete — the UI is English until then. No escape-hatch
entries are needed to start.

**At runtime there is no fallback language.** Staging fills every missing key
from `en` (§3.2 step 4) and the loader fetches only the requested bundle: a
build ships exactly its `uiLanguages` (R9), and a key still missing renders as the key. A bundle that fails to
load never reaches Transloco's fallback strategy, which throws when no fallback language is configured: the
administration loader retries it twice (after 1 s and 2 s, as the public site's loader does) and then completes
with the branding and the Dictionary's overrides alone, logging the failure; the public site keeps its stand-in,
another of its languages while the bundle is asked for again — availability, not a fallback language. The public
site's language configuration has `brandingLanguages`, like the administration app's, and requests a branding
file only for those (none in the base product's own build).

### 4.7 Styles and fonts

- `tokens.<cc>.css` holds values only (`fe/docs/country-deployments.md`, _The token contract_; restated
  for a country in `country-kit/docs/designers-brief.md`, which the kit package ships).
- A text face is vendored in `styles/fonts/<face>/` with its licence and declared in `styles/fonts.<cc>.css`
  with relative `url()`s and `font-display: swap`. The builder emits only what a configuration's own style
  entries reference, hashed into `media/` (`@angular/build` `tools/esbuild/global-styles.js:22-28`: a font
  referenced only from one country's sheet is absent from every other build), so no other country's build can
  carry it.
- No `url(https://…)`, `fonts.googleapis.com` or `fonts.gstatic.com` anywhere in a build (checked, §4.8).
  `optimization.fonts`, which is set, inlines Google CSS when it meets it; with the check it never does.
- The administration service worker has `/assets/branding/*.json` and `/assets/legal/*.html` in its `i18n`
  group, and a `media` group for a country face cached offline (lazy install, prefetch update, `/media/**`).
  `ngsw-config.json` is protected (§4.11 rule 3), so the group is the base product's. It is checked by
  `scripts/admin-service-worker.test.mjs` (run by
  `npm run test:guards`), which runs Angular's own generator over a build output with a face under `media/`;
  the base build's `ngsw.json` has the group with no files,
  since the base build has no `media/`. Not yet run: a country build's `ngsw.json` listing its face under
  `media`, and a country's face offline in a deployed administration app.
- The generated budget allows for the face: when `styles/fonts.<cc>.css` exists, each application's
  `styles` bundle budget, `maximumWarning` and `maximumError`, is raised by the sheet's size in bytes (written in
  kB as Angular counts them, 1000 bytes; `raiseStylesBudgets` in `country-kit/lib/workspace.mjs`), in the
  options and in any configuration with budgets of its own; a budget with a baseline or a size that is not
  absolute, and every other budget, stay the base product's. GL's 3586-byte `fonts.gl.css` puts its public
  site's styles budget at 56.586/58.586 kB against the base product's 53/55 kB; the `initial` budget is not
  raised (its margin absorbs the sheet). Checked by `country-kit/workspace.test.mjs`.
- **GL's theme** keeps `Lato` first in its font line; no Google or `/assets/fonts` loading is used (R7).

### 4.8 The kit, its checks and the info image

`country-kit/` in the base product, at the repository root beside `fe/`, published
as its own package, `org.niord:niord-country-kit`, beside the source zip, which carries nothing of the kit:

- **Commands:** `init` (below), the steps of `setup.mjs` (§3.2), `pack …` (§4.5), `catalog`,
  `image:info --tag <registry>/<name>:<version>` (§ below). `init` writes the whole country from a handful of answers,
  the backend included: `node .niord/kit/init.mjs [<cc>] [--answers <file>] (--sha256-file <file> | --zip-sha256
  <fe-sha256> --kit-sha256 <kit-sha256>) [--version <v>]` from the repository root (§5 step 2). It asks its sixteen
  answers (the other fifteen when the code is given) line by line (readline without a terminal, so a Git Bash pipe
  works), each standard default shown and taken with Enter, or reads them from `--answers`, both validated by `country-kit/init-answers.schema.json` and
  `country-kit/lib/answers.mjs`; the version is the kit marker's (`--version` only in the base product's tree, refused when it
  disagrees). No `be/pom.xml` is needed first; one that exists is kept and must name the kit's version as its
  parent's. The release — both zips against the two lines, a kept pom's parent — is checked, and the source zip read
  in memory, before the first question or write; every file is computed and the kept/written split decided before the
  first write, so a refusal writes nothing. `init` never overwrites a file: one that exists is kept and listed, and
  the root `.gitignore` is the one existing file it changes. It writes the 23 files of `country-kit/lib/scaffold.mjs`,
  from `templates/be/` and `templates/ci.yml`: `be/pom.xml` from `templates/be/pom.xml.tmpl` (the parent with an empty
  `relativePath`, the `github` repository, niord-base and its test-jar and the country build group's dependencies; per
  answered language the pack's two copies and the flag's `<available>` line only), the Maven wrapper from the kit
  package's `backend/` entries (`be/mvnw` 0755, `be/mvnw.cmd` CRLF), `be/.gitattributes`, `be/.gitignore`,
  `be/.java-version`, `be/overrides.json`, the application class (`org.niord.<cc>`; a code that is a Java keyword
  takes `_`, so `do` gives `do_`), `application.yml` (`niord.s124.enabled: true`, the producer code only when
  answered), `application-local.yml`, `defaults/country/settings.json` (the 11 answered keys in `values`, the other 15
  keys of the base file in `useBaseDefault`, each with a reason; §5 step 8) and its README, `be/bootstrap/`
  (`batch-set.json` naming `domain-import`, `domains.json` with one domain, its time zone and its series, and
  `be/bootstrap/README.md`, which also says what each `releases/<yyyy-mm-dd>-<topic>/` holds:
  `docs/code-data-config-plan.md` §7.1; no `be/bootstrap/releases/README.md`, which the bootstrap archive
  refuses: it accepts under `releases/` only a release folder's `*.json`, `*.md` and `*.sql`),
  `be/scripts/check-country-build-group.sh` (0755), the manifest
  `fe/niord-country.json`, `fe/info/app/info.config.ts` with the time zone, `.github/CODEOWNERS` with the owners,
  `image.yml` with `MODULE: 'niord-<cc>'` and `REGISTRY` and `HARBOR_PROJECT` empty (a kept `image.yml` gets its
  frontend and backend jobs printed), `ci.yml` (a kept one without `workflow_dispatch:` gets a notice) and the root
  `README.md`. Beside them: the launcher `start-local.sh` at the repository root,
  `templates/start-local.sh` byte for byte and executable (§5 step 9), and the root `.gitignore` with `/.local-run/`,
  the launcher's downloads and logs, a line `init` adds to an existing `.gitignore` that does not ignore that folder
  yet; `setup.mjs`, the branding skeleton from the schema — its required keys and files only, §4.4, and no branding
  text — `tokens.<cc>.css` with the default values, `pack.yml`, `base-bump.yml`, `fe/.gitignore`, `fe/.gitattributes`
  and the root `.gitattributes` from the same template (the base product's
  `* text=auto eol=lf` with its binary list, without the `.husky` rule, held equal to `fe/.gitattributes` by
  `init.test.mjs`; a country's own is kept), `pack-releases.md` at the repository root, `dependabot.yml`,
  `AGENTS.md` with a one-line `CLAUDE.md` importing it, `.niord/.gitignore` and `fe/niord-pins.lock.json`
  with the base product's version and both zips' hashes. Its closing lines name set-up (with
  `--update-pins` when languages were answered) and the launcher and, on Windows, the commit line that gives the
  three scripts their exec bits and the `NIORD_HOME` export before `be/mvnw verify`. An absent escape hatch, `audit-accepted.json` or
  `fe/country/package.json` with its lockfile, counts as empty.
- **The example country** `country-kit/example/` — a complete, fictitious country `xx`: every seam
  overridden, branding, a legal document, a vendored face, a country-only Tailwind utility, a home
  fixture pack `zz` (pseudo-localised from `en`). The base product CI sets it up and builds both applications
  and the info image on every frontend change, so the contract is tested where it is defined — a change
  confined to the public site on a pull request only: `fe-ci.yml`'s push paths exclude `fe/ui-apps/info/**`, so
  on `main` such a merge is re-checked by Info CI's build checks alone (accepted). It is the
  reference to read; `init` does not copy it. In the base product CI and in `verify-local` the example and the
  acceptance tests below set up as a country does: against a zip built from the commit under test with the
  include list of §4.11 rule 6, installed into the job's local Maven repository and pinned by its hash — never
  through `NIORD_BASE`, which CI refuses (§3.2 step 2). `zz` has `publish: true`, and the harness checks
  `.niord/packs/zz/` against the canonical archive, runs `pack latest-catalog` and, after the fixture upgrade,
  `pack prune zz --base 4.0.0-example`; with `--image` it publishes `zz` with the two commands `pack.yml`'s token
  jobs run (`pack.mjs validate`, then `oci.mjs publish`, later `withdraw`), anonymously, against an auth-less
  `registry:2.8.3` container on `127.0.0.1` — the Bearer flow, the owner's lower-casing and the workflow's own
  bash run only at a real publish (§9) — has the untouched `init` country pin it by digest (`--update-pins`), set
  up, pass `guard-build` and build from the pin, then withdraws the tag and sees that country's set-up fail with
  the reason. The container, `niord-example-registry-<pid>`, is removed at the end on every exit the harness
  reaches; one an interrupted run left, its pid no longer a running process, is removed by the next run.
- **The new-country acceptance tests**, in the base product CI beside the example — steps of the
  same harness, run after the example against the same zip, so of Frontend CI's `Example country` job and
  `verify-local`'s example row (`country-kit/example/acceptance.mjs`, driven by `run.mjs`; one checkout, one
  Maven restore, one zip install):
  - **untouched `init`:** a country made by `init` and not edited must set up and build into working
    English applications at once — branding, another language and own functionality are each an
    increment on a green start. The harness makes `yy` with the zip's own `init`, pinned by the zip's hash
    (never `NIORD_BASE`), and runs set-up, `npm ci`, `npm run build` (asserting check-build's warning that it
    has no bundle baseline yet), `npm test`, `npm run lint` and `npm run e2e -- --list`; then the baseline's
    start (copy the base product's, `npm run bundle:info -- --update-baseline`), after which check-build
    passes without the warning; with `--image`, `image:info`, which for a country with no legal document or
    no branding text for its default language warns, naming the file to add, and has the container test
    skip exactly those probes by name (`--no-legal-doc`, `--no-branding-text`) rather than refusing.
    The harness makes `yy` as the Quick start makes a country (both zips and `SHA256SUMS` in `yy/.niord/`, only
    the kit unzipped, `init --answers .niord/init-answers.json --sha256-file .niord/SHA256SUMS`), and any kept file
    or a wrapper byte different from `be/`'s fails it; with `--backend` it builds the generated backend straight
    after init and set-up, before its frontend steps: niord-parent and niord-base with its test-jar installed from
    the tree as `4.0.0-example` into the scratch Maven repository, then `yy/be/mvnw verify` with Docker (the country
    build group, `CountryDefaultsChecksTest` over the generated settings, `CountryContractGuardTest` over the
    generated `application.yml`; on Windows a forward-slash `NIORD_HOME`), the group check and the override check;
    without Docker it refuses. Frontend CI's `Example country` job passes `--backend` when a pull request matches
    the scaffold rule and the example rule starts the job, and on a dispatch; Backend CI's `Generated country` job
    runs `--backend --skip-frontend` when the rule matches on a pull request, and on every run on `main` and every
    dispatch; `verify-local` passes it on a match;
  - **upgrade:** the example country pinned to the **previous published** base product version, then bumped to the
    one being built, must set up, check and build green — or fail only where `API-CHANGES.md` says a country
    must act. A base product change that breaks a country without saying so is caught before it is published.
    The harness takes the previous zip as `--upgrade-from <zip>`, sets that zip's own example up at its
    version, bumps it, applies the routine actions of every bump (the home pack translated again,
    `--accept-override` for overrides whose base files changed), then sets up and builds; a failure left
    passes only when a version section of `API-CHANGES.md` (_Unreleased_ or a version heading, never a policy
    section) new since that zip asks a country to act.
    Frontend CI's `Example country` job takes the previous zip from the **latest release** of
    the base product's repository (the first that is neither a draft nor a prerelease; its tag must be
    `v4.0.<n>`): it downloads
    the release's asset `niord-fe-src-<n>.zip` with `gh` and the workflow's own token, requires exactly one line
    `<sha256>  niord-fe-src-<n>.zip` in that release's notes and the asset's sha256 equal to it — a missing asset,
    a missing or repeated line or a mismatch fails the job, "refusing an unverified previous zip" — and passes the
    zip as `--upgrade-from`. With no release yet the test is
    skipped and the job summary says so; `verify-local` takes `--upgrade-from <zip>` (a release's, `gh release
    download v4.0.<n> --repo NiordOrg/niord4 --pattern 'niord-fe-src-*.zip'`), and without it its row says the
    test is Frontend CI's. Why a release asset and not the registry (decided 2026-10-22): the
    publishing token lives in the `main`-only `publish` environment, so a PR run cannot read the registry with it;
    a read-package secret for PR runs would be a new owner step; a release asset of the repository itself is
    readable with the workflow token's `contents: read`. The mechanism runs on every run on a **fixture pair**: the zip
    under test, `4.0.0-example`, and a zip derived from it in memory, `4.0.1-example` (one English text changed,
    the example's overridden template changed, `roleGuard` deprecated with its removal version and an
    `API-CHANGES.md` section); the example bumped to it must fail set-up on its override alone, write the
    upgrade report (§4.12), and set up and build green after the routine actions.
- **Checks on every country build** (and on the example in the base product). They are the last
  step of the generated `npm run build`, `country-kit/check-build.mjs` (with `lib/build-checks.mjs`), over
  both applications' `dist`, so no pipeline, harness or developer build can skip them:
  - one source per output file: `resolveAssets` over each configuration, no duplicate destination —
    check-build's check 9, which reads the generated `angular.json` and resolves the assets of the options
    and of every configuration of both applications with the builder's own code, `country-kit/lib/asset-outputs.mjs`,
    which loads the workspace's installed `@angular/build` at run time (`createRequire` from the workspace root,
    so the kit itself still imports only Node built-ins); the base product's `contents:check` uses the same
    module, so there is one implementation;
  - the shipped bundles are exactly the manifest's `uiLanguages` (`en` only when they name it, R9), the flags exactly the
    manifest's languages (`<lang>.png` of the administration app's UI and content languages, of the public site's UI
    languages); nothing from `status/`, `ATTRIBUTION.txt`, a flag's drawing, `node_modules`, `.niord/` or another
    tree (`assets/default/` or a two-letter overlay folder under `assets/`);
  - no external font or style URL in `dist`, and no absolute URL outside the base product's allow-list
    (§4.11 rule 5: `scripts/build-urls-check.mjs --absolute` with `scripts/build-urls-allowed.json`) and
    the country's own `fe/build-urls-allowed.json` (§4.11 rule 5), whose entries every build prints;
  - the country-only utility of the example (base product CI) / the country's own: `setup.mjs` records the first
    Tailwind class with an arbitrary value of each country template in `.niord/build/country-utilities.json`
    (beside the staging folders, so no build ships it), and each one's selector, escaped as Tailwind writes it,
    must be in a stylesheet of its application's `dist`;
  - the secret scan of both `dist` (§4.11 rule 4);
  - check-build's check 10: the public site's built `index.html` has the `lang` of the
    manifest's default language for the public site and that language's built `SITE_TITLE` as its title, the fix
    being the country's `info/app/index.html` (the index seam);
  - the public site's checks with parameters: `info-separation-check.mjs --root .niord/base/ --country <cc>`
    (with `--country` it also allows the
    country's `info/`, `.niord/generated/info/`, its own `styles/tokens.<cc>.css`, `styles/fonts.<cc>.css` and
    `styles/fonts/`, and refuses its `admin/` and `.niord/generated/admin/`), and the bundle report against the
    country's own `info/bundle-baseline.json`, `info-bundle-report.mjs --root .niord/base/` (the baseline's entry
    points stay the base product's paths, matched under the prefix; the same command as the generated
    `bundle:info`; a country without a baseline yet gets a warning naming how to start one, so an untouched
    `init` still builds). `info-layout-check.mjs` takes `--nginx <dir>` (the folder holding
    `niord-security-headers.conf`) and the container test `--language <lang>` (the bundle and branding file it
    probes; default `en`), both used by `image:info` against the image; the container test also takes
    `--no-legal-doc` and `--no-branding-text`, which `image:info` passes, with a warning naming the file to add,
    for a country that has no legal document or no branding text for its default language yet (a fresh `init`):
    those probes become named skips counted in its last line, so an untouched country's image job is green. The
    nginx sandbox rules are tested by the example's image, in Frontend CI's `Example country` job, and by hand
    on the base build with `image:info --package-only`, `layout:info` and
    `test:container:info` (`fe/AGENTS.md`); no country's image is built in the base product's repository.
    `info-layout-check.mjs`'s da/en
    table-width check runs only in an image job whose public site offers both `da` and `en` — DK's and
    GL's — never in the base build's or the example's image. Each country has its own bundle baseline (GL's
    includes Lato).
- **The info image** packages a built `dist`: the country pipeline builds with `npm run build`, then
  `image:info` copies the output into a fresh context, runs `precompress.mjs` there and adds the base product's
  nginx files and security headers (§4.11 rule 5). The structural separation
  guarantee is the separation check above, run on every country build and by `image:info` against the stats of
  the build the image packages, and lint. `image:info --package-only` packages the base build. The
  image's registry and project are the `REGISTRY` and `HARBOR_PROJECT` variables of the
  `image.yml` that `init` writes (§5 step 2), joined into `IMAGE_PREFIX`; `init` writes both into
  `image.yml`'s workflow-level `env:` empty, so both jobs fail until the country
  sets them, and the push job logs in to that `REGISTRY` alone (§4.11 rule 1).
- **The base product's own guard:** `fe/` outside `country-kit/example/` holds no country tree, no pack but `en`,
  no configuration but the base product's, and none of an allow-listed set of country words (authority names
  and domains — `Beredskab`, `Søfart`, `brs.dk`, `dma.dk` — `kl-GL`, `da-DK`, `Nuuk`, …), with a
  reason per allow-list entry. The words and their allow-list are `country-kit/country-words.json`
  (read by `country-kit/lib/country-words.mjs`): the seven words above, `Soefart` and the two authorities'
  English names, `Danish Emergency Management` and `Danish Maritime Authority`, 10 words, each matched
  case-insensitively as a literal
  substring of every path and of the NFC text line by line (so `Beredskab` also catches the longer name, `Nuuk`
  the IANA zone); language names (`Dansk`, `Kalaallisut`) are not words — a language is a pack (R3), and the base
  product's specs use them as sample languages. Country and place names (`Denmark`, `Greenland`, `Copenhagen`,
  `Danmark`, `Grønland`, `Kalaallit`) and `niord.dk` are not on the list: the tree names them in
  specs' sample data and time zones, the public site's test zone `Europe/Copenhagen`, and
  doc-comment examples, with no logic among them. The two test scripts' fixtures are neutral:
  `scripts/info-layout-check.mjs`'s stub backend answers the root areas Anchorland/Ankerland
  (`urn:mrn:iho:country:xa`, 10 sub-areas) and
  Buoyland/Bøjeland (`:xb`, 3), handed out in turn to the root areas a build names, so any country's
  `rootAreas` pass it, and the container test's series ids are `xx-nw`, `xx-nw-local` and `xx-nm`. The guard
  runs over the zip, reporting the words — by
  `source-zip.mjs build` (so `kit:zip`,
  the example harness, `verify-local`'s example row and Frontend CI's `Example country` job) and by its `guard`
  command in `publish.yml` — and as a tree mode in `test:guards` over every file git lists in `fe/` and
  `country-kit/` but
  the list itself, where an entry of the allow-list that permits nothing fails too (§4.11 rule 6). The
  structural half — no country tree, overlay, token or font
  sheet, no pack but `en`, no configuration outside the kit generator's base set — is
  `scripts/base-product-tree.test.mjs`, in `test:guards`.
  The backend half is niord-base's `CountryWordsTest`, a unit test outside
  the country build group, with its own list,
  `be/niord/src/test/resources/build/country-words.json`, and its own matching rule (a word boundary before
  each entry, none after, case-insensitive): words, patterns (a Danish locale chosen in a
  template, its JSON-escaped form included, and the place names), a strip (`dk.dma.niord.s100`) and
  allow entries, each used and each with its reason (the third-party `dk.dma.niord.s100` package, the legacy
  publication import, Javadoc and OpenAPI examples, Danish as a language, and the like); the list's own note
  states the rule and its scope in full.

### 4.9 Changing functionality (R10)

The owner expects all four kinds of variation: a country adding its own pages and features, changing how
an existing feature behaves, replacing a UI component, and needs not known yet. Two mechanisms cover them.

**Extension points — the normal way.** Each is a base product contract with a neutral default (nothing added,
the base product's behaviour), listed in `seams.json` with what it may use:

| Need | Extension point | Country file |
|---|---|---|
| Own pages and features | country routes, lazily loaded under the application's router | `admin/app/country.routes.ts`, `info/app/country.routes.ts` |
| Reaching them | menu entries (where, label key, icon, required role) | `admin/app/country.menu.ts` |
| Changed behaviour | behaviour hooks: injection tokens with a base product default (a strategy, a default value, a validation step), which the country provides its own implementation of | `admin/app/country.providers.ts`, `info/app/country.providers.ts` |
| A different component | named component slots in base product templates; the country registers its component for a slot with a lazy loader (`() => import(...)`), so a slot component never lands in the main chunk | in `country.providers.ts` |

- Hooks and slots are added where a country needs them — a base product change, deliberately: each is a
  promise the base product keeps across versions. The promulgation hook is the first and stays a
  contract: the base product registers its own audio editor and viewer through it (the owner, 2026-10-24: audio
  promulgation is the base product's), and a country
  registers through it the promulgation services it adds.
- A country's own text (its pages, its menu labels) lives under `country.*` keys in its branding files;
  the branding schema allows that namespace freely and the pack check does not require it in packs.

**Guarded overrides — beyond the extension points.** A country may replace any TypeScript, JavaScript or JSON file of an application's code, under `ui-apps/<app>/src/app/` (a template or stylesheet through its component's `.ts`; `index.html` is a seam, §4.3),
**except the protected ones** (§4.11 rule 3: the sanitiser, interceptors, auth, the loaders and the like —
for those a country asks for a hook), a spec, and a seam's file (the country fills the seam instead). Its copy lies at the same path below
`<app>/overrides/` (`with`). Files outside `src/app/` — the shared folders, `main.ts`, the stylesheets — are not overridden: a country
asks for a hook. Each override is an entry of the manifest's `overrides` (§4.1):

```json
"overrides": [
  { "file": "ui-apps/admin/src/app/features/editor/foo/foo.ts",
    "with": "admin/overrides/features/editor/foo/foo.ts",
    "basedOn": {
      "ui-apps/admin/src/app/features/editor/foo/foo.ts":   "sha256:3f1c…",
      "ui-apps/admin/src/app/features/editor/foo/foo.html": "sha256:9a02…",
      "ui-apps/admin/src/app/features/editor/foo/foo.css":  "sha256:51be…"
    },
    "reason": "DK's own workflow step", "added": "2026-11-20" }
]
```

- `basedOn` lists **every base product file the copy was made from** — the replaced file and, for a component,
  its template and styles, which `setup.mjs --add-override` finds from the component's `templateUrl` /
  `styleUrl` and records itself. When the base product changes or removes any of them, the build **fails** until
  the country has compared its copy with the base product's new version and recorded the new fingerprints
  (`setup.mjs --accept-override <file>`). Every build prints the list, as it prints the escape hatch.
- The fingerprints trigger a review; they cannot prove the copy still fits the base product around it. Each
  override therefore carries the country's own specs for the behaviour it changes, and they run on every
  country build; so does the base product's spec beside the overridden file (`<stem>.spec.ts`), against the
  country's copy, from a generated copy of the spec whose relative imports point back into the base product.
- Only TypeScript, JavaScript and JSON files can be replaced (`fileReplacements`), so a component is
  overridden at its `.ts` file, which names its own template and styles beside it in the country tree.
- Override files follow the country rules and the base product's lint; files under `<app>/overrides/` — the
  copies and their specs — alone may reach base product internals
  (`@niord/admin/internal/*`, §4.3).
  `--add-override` writes the copy's relative imports as that alias, since the copy compiles at its own place.
- An override that is still needed after a version or two is a request for an extension point; the
  country guides say so.

**The backend, the same two ways.**
- **Extension points** are Spring's: a country provides its own bean for a base product interface or a
  `@Primary` replacement (never a protected bean, §4.11), and sets values in its settings file
  `defaults/country/settings.json`, which replaces the base product's whole file and is checked against the
  settings catalogs (`docs/code-data-config-plan.md` §5). This stays the normal way, and the base product names the
  interfaces meant for it in `be/docs/country-api.md` with the same compatibility policy as the
  frontend's API.
- **Data, never an override:** every template and script (message templates, mail bodies, report layouts, and
  the templates the code names) is deployment data (`docs/code-data-config-plan.md` §6). A country ships its versions in its `be/bootstrap/` for a fresh
  deployment and in `be/bootstrap/releases/<yyyy-mm-dd>-<topic>/` import files for running ones — never as a resource in its jar.
- **Texts, key by key:** a country's backend text is its packs' backend half plus its own country texts
  (`defaults/country/texts/`), layered per key over the base product's English (§4.10); a second copy of a file
  under `defaults/` stops start-up.
- **Guarded overrides** are what is left: a static image at a base product path. Spring Boot puts the country's
  own classes first, so the country's copy wins deterministically — but silently: a base product fix to that
  image never reaches the country. Each is listed in `be/overrides.json` with `basedOn` fingerprints of the
  `niord-base` resource of the pinned version, and a check the base product publishes (in `niord-image-tools`,
  run by the country's build) fails when one changes, as for the frontend. As built:
  an entry mirrors the manifest's override entry without `with` — `{ "file", "basedOn": { "<file>":
  "sha256:…" }, "reason", "added" }`, the country's copy sitting at the same path in its jar — and the check is
  `check-overrides.sh` (with `CheckOverrides.java`), which the country's workflow runs after its package step
  over the executable jar, as it runs `split-jar.sh` (in `ci.yml` and `image.yml`). It reads
  the country's `BOOT-INF/classes/` and the `META-INF/` that Spring Boot's repackaging keeps at the jar's root
  (a country's `META-INF/resources/` images are there, not under `BOOT-INF/classes/`), against the pinned
  `niord-base` jar inside it; `be/docs/country-api.md` has the format and the rules. The check enforces this
  paragraph's first sentence literally: a shadow is permitted only when it is a static image under
  `META-INF/resources/img/` (`png`, `jpg`, `jpeg`, `gif`, `webp`, `ico`), and then only when listed; every other
  shadow, listed or not, fails. SVG is not among them, since a same-origin SVG is a document that can run
  script. Under these rules a country's template shadows are its bootstrap data, its `niord.json` is its
  settings file, and its `web_en.properties` and `mail_en.properties` are country texts (a rewording only the
  administration app shows is branding or a text override, `docs/code-data-config-plan.md` §4.4); of DK's
  shadows one stays a guarded override, `META-INF/resources/img/company-logo.png`.
  `docs/code-data-config-plan.md` §9 classifies DK's and GL's resources.
- **A base product Java class is never replaced** by a class of the same fully qualified name (a split package
  whose winner depends on the classpath): a country that needs different behaviour gets a bean or a hook.

### 4.10 Translation layers — which text wins

Five layers, each a different owner and concern; a higher one wins for the keys it sets:

| # | Layer | Owner | What it is for | Where |
|---|---|---|---|---|
| 1 | the `en` pack | base product | every key, the fallback for all others | `fe/languages/en/` |
| 2 | the language pack | its home country | base product text in that language, shared by every country using it | `packs/<lang>/` |
| 3 | **text overrides** | the country | its own wording of an *ordinary* base product key — a term DK and GL say differently, without changing the shared pack and without asking the base product for a brandable key | `fe/text-overrides/<lang>.json` |
| 4 | branding | the country | the keys the branding schema names (authority, footer, legal titles) and its own `country.*` keys | `assets/branding/<lang>.json` |
| 5 | the backend Dictionary | the deployment | texts edited at runtime in the database (Dictionaries page), per environment; a row still equal to the default the backend added (recorded in its ledger, including rows adopted unedited on existing deployments) is skipped, so the compiled text wins (`docs/code-data-config-plan.md` §4.5) | the backend (`dictionary-overrides.service.ts` skips the unchanged rows, `merge-dictionary-overrides.ts` applies the rest, with its placeholder rule) |

- Layers 1–4 are merged at build time into the staged bundle where they are static (1–3: English, then the pack,
  then the text overrides, each key in its English place) and at runtime where
  they are per-deployment assets (4); layer 5 is runtime only.
- **The file** `fe/text-overrides/<lang>.json` has a section per application, each key with its text and the
  fingerprint (`fp`, the catalog's) of the English text it was written against:
  `{ "admin": { "<key>": { "text": "…", "fp": "<16 hex digits>" } }, "info": { … } }`. `en` may be overridden
  too; a key the branding schema names and every `country.*` key are branding's, never an override's.
- **Text overrides are checked like translations:** the language must be a UI language of the section's
  application; every key must be in the catalog (a key that left it fails, named with its new key when the base
  product renamed it), placeholders must
  match the English text, and so must the markup and a date pattern's or word list's rules (§4.6; each fails otherwise); a changed English text is a warning listing the
  overrides to look at, each with the fingerprint to record. An override equal to the pack's text (to English's for
  `en`) is a warning, redundant; one that differs from a text the pack has is listed as a deliberate difference. A
  key an override supplies counts as present in the pack check's missing-key and placeholder rows (§4.6, step 3 of the foreign-pack
  workflow), and an
  escape-hatch entry for it is a stale warning. Every build
  prints how many keys a country overrides per language (`text overrides: <lang> <n> (admin <a>, info <i>)`, or
  `none`) and records the counts in the build report.
- The backend has its own layers (`docs/code-data-config-plan.md` §4): the base product's English, the language
  pack's backend half, the country's texts — merged per key, never a whole-file shadow — and the deployment's
  Dictionary in the database. A key the merged result has and the database lacks is added at start-up and never
  changed afterwards; a key an operator deleted is not added again (a ledger records what was added). The
  database row is what runs. A country's texts hold only the differing keys a template or Java reads and the
  country's own keys, never a whole file; a
  rewording only the administration app shows is a text override or branding (layers 3 and 4).

### 4.11 Security rules

From the dedicated security review of the plan (2026-10-19: five areas, every finding adversarially
verified, a synthesis; 23 findings — 4 high, 9 medium, 10 low). **Its verdict:** the plan does not weaken a
running deployment's posture — the public site's CSP, proxy allow-list and header stripping, the backend's
`SecurityConfig` and the Keycloak flow stay base-product-owned and untouched — but it changes *who can put code
and text into a build, and with which credentials*, and the rules below close what that opens. They are
checked, not advised: each has a negative test in the example country or a step in a pipeline.

**Whom the guards defend against.** A *careless* country — the checks, protected lists and lint are for this.
A *malicious or compromised* country or home — only what the others control holds: digest pins, no shared
credential, a reviewed bump, the base-product-side checks of the zip. A *compromised base product* reaches every
country — hence split tokens, hash pins and, later, attestations.

1. **Credentials and pipelines**
   - No repository holds another repository's credential (R13).
   - A country's frontend is built in an **unprivileged job** (`contents: read`, `packages: read`, no
     environment, no secret beyond the workflow token): `setup.mjs`, `npm ci --ignore-scripts` (a base product
     package that needs its install script is rebuilt alone from the kit's `scripts-allowed.json`), specs,
     lint, build, the info image and its container test. It hands `dist` and the image on as artifacts with
     their sha256. The **push job** (environment `harbor`, `refs/heads/main` only, no npm and no kit code)
     verifies the hashes, logs in and pushes. The base product's `publish.yml` keeps npm away from the
     Harbor credential in the same way.
   - Each repository's `harbor` environment has the deployment-branch policy `main` only, so a workflow run from
     another branch never gets its Harbor credential: the base product's has it (`.github/workflows/publish.yml`'s
     header on the deployment branch rule, enforced by the gate's `require_main_only`); a country's is proven by
     a dispatch from another branch that the
     environment refuses (owner, 2026-10-21). As of 2026-10-24 `niord-dk`'s and `niord-gl`'s `harbor`
     environments have no deployment-branch rule yet: the rule and its proof
     wait for the owner's go, and until the deploy step
     the owner keeps the countries' Image workflows disabled, so nothing pushes to Harbor. Write access only for
     named maintainers; the host
     is asked for tag immutability on `niord/niord-app-*` and `niord-*-info`. What stays accepted is the
     security review's caveat: the rule was checked on GitHub Free, so it is rechecked after any change of
     owner or plan. Main-only holds for
     Harbor and publishing, not for staging: staging may run feature branches, only through the separate
     preview path of [`docs/feature-branch-deploy-plan.md`](feature-branch-deploy-plan.md) (its R1-R3; the
     `harbor` environments and publishing stay main-only; §3.1 there), and existing data on any environment
     other than a developer's local one is touched only on a human's say-so (its R2; the owner, 2026-10-23).
   - The base product: `retract-version.yml` and the break-glass path of `publish.yml` refuse a run whose
     triggering actor is not on a maintainer list; publishing and deleting use two machine-user tokens
     (publish-only, delete-only) in environments restricted to `main`, rotated at the repository move and
     yearly; Dependabot reads with a read-only token, never a publishing or personal one; the Niord base bump
     app's private key and id live only in the base product's main-only `publish` environment, and the gate warns
     when the key is a repository secret.
     The list is `.github/maintainers`, read from `main` whatever ref a run comes from, and
     checked against the run's triggering actor (a "Re-run all jobs" by someone else is checked too; a
     "Re-run failed jobs" does not repeat the check, so `retract-version.yml` refuses one started by anyone
     but the login its `authorise` job checked, since 2026-11-21, while in `publish.yml` it completes the jobs
     a maintainer's break-glass run had started). Until a rule on `main`
     requires review, whoever may write `main` may also
     edit `.github/maintainers` or the two workflows in the same push, so who may retract or break-glass is in
     effect who may write `main`: write access only for named maintainers carries the rule (required review
     with `CODEOWNERS` on `.github/` is a go-live item, `fe/docs/backlog.md`).
     `NIORD_PUBLISH_TOKEN` (`write:packages` only; the machine user's login in the variable
     `NIORD_PUBLISH_USER`) is a secret of the environment `publish`, used by the build job and by the bundles'
     deploy job `web-publish`, which runs no npm — the bundles are built in a job with no environment and no
     secret; `NIORD_DELETE_TOKEN` (`delete:packages`, `read:packages`) is a secret of `retract`. Both
     environments allow `main` only, like `harbor`, and because GitHub creates an environment a job names
     without any rule, `publish.yml`'s gate and `retract-version.yml`'s first job fail a run while `publish`,
     `harbor` or `retract` is missing or allows another branch, before any job enters one; and
     `retract-version.yml` refuses a run whole, before it deletes anything, while `retract` holds no
     `NIORD_DELETE_TOKEN`.
   - **Until the country split (owner, 2026-10-22).** "When full repo is changed and everything is split up to
     each country, then we will rotate keys and setup proper accounts": until then the build and `web-publish`
     jobs deploy with the repository secrets `NIORDORG_PACKAGES_USER` / `NIORDORG_PACKAGES_TOKEN`,
     handed to the deploy steps under the names above. `publish` exists with its `main`-only rule
     and no secret, so the gate holds; `retract` does not exist yet, so `retract-version.yml` refuses every run
     and a bad version is deleted by hand in the meantime. Accepted: a repository secret can be named by a
     branch's copy of a workflow, which the environment's secret could not. At the split: the machine user and
     its two tokens in `publish` and `retract`, the deploy steps back to `secrets.NIORD_PUBLISH_TOKEN` and
     `vars.NIORD_PUBLISH_USER`, the repository secrets deleted and the old token revoked (`fe/docs/backlog.md`).
     Present state (read 2026-11-21): `NIORDORG_PACKAGES_USER` / `NIORDORG_PACKAGES_TOKEN` have since moved,
     under the same names, from the repository to the `publish` environment, so a branch's copy can no longer
     name them; `retract` exists, main only, without `NIORD_DELETE_TOKEN`, so a run gets past `authorise` and
     the `retract` job's first step refuses it whole, before anything is deleted (since 2026-11-21; until then
     its Packages step failed for want of a token and the release and the tag still went); the machine user,
     its two tokens and the steps' names are still to come.
   - **As built.** `publish.yml` starts after Backend CI, Frontend CI and Info CI,
     and its gate requires each that ran for the commit to have succeeded (one its path filter skipped counts as
     not run), because the zip carries the public site's sources: a merge confined to the public site
     publishes a version. In the kit's `templates/image.yml` the push job logs in only to the workflow-level
     `REGISTRY` and pushes only `<REGISTRY>/<HARBOR_PROJECT>/<repository>-info:<version>`, refusing any other
     reference before any `docker` call: it trusts nothing from the frontend job but the bytes whose hashes it
     verifies. In each country workflow the set-up step alone (`Set up the workspace`; `Check the packs` in
     `pack.yml`) holds `GITHUB_TOKEN`, for the stub's Maven fetch, which GitHub Packages refuses without a
     token. `pack.yml`'s validate job binds it on its two Maven steps, `Set up the workspace` and
     `Fetch the latest base product catalog`, and on no other; `publish` and `withdraw` hold the run token as
     `PACK_TOKEN` with `packages: write` only, run no checkout and no npm, and run only the kit's one-file OCI
     client after checking it against validate's SHA-256 (§4.5, _As built_). The gates and the maintainer checks of `publish.yml` and `retract-version.yml` are tested by running
     their own bash with a fake `gh` (`fe/scripts/release-workflows.test.mjs`, in `test:guards`).
2. **Integrity of what a country builds from**
   - `fe/niord-pins.lock.json` holds the sha256 of the pinned zip and the digest of every foreign pack;
     `setup.mjs` verifies each **before** unpacking or executing anything; `--update-pins` rewrites it (for each foreign pack the tag given on the command line, else the highest tag on GHCR that its withdrawn marker does not list, with that tag's digest), so a
     bump PR shows the change. As built: `node setup.mjs --update-pins [<lang>=<tag>]…` reads each pinned
     pack's marker first (a pack without one is unreadable: the command stops and writes no pin of any pack, and the
     home's next publish pushes the missing marker), refuses a given tag that is withdrawn or absent, accepts a
     candidate only once it is pulled and verified as a pack (one that is not is skipped with a line, and refused
     when named), rewrites the lock with the base pin kept and the pack pins sorted, prints each old → new with a reminder
     to compare a first pin with the home's `pack-releases.md`, then runs the whole set-up; `NIORD_PACK_REGISTRY`
     may name a loopback registry instead of `ghcr.io`, for the example's harness, and any other host is refused. `publish.yml` records the zip's sha256 in the notes of a GitHub release on the tag `v4.0.<run>` (the tag stays lightweight, as the gate needs: the tag job's step _Tag v…_ in `.github/workflows/publish.yml`) and in the run
     summary — a record the registry token cannot rewrite. As built: the build job's summary gives the line
     `<sha256>  niord-fe-src-4.0.<run>.zip` beside the deploy's upload lines; after the tag, the tag job — with its
     own `GITHUB_TOKEN`, no new secret — checks the zip it received from the build job against the build job's
     sha256 and creates the release with `gh release create --verify-tag` (never a new tag): its notes are one
     sentence naming the coordinates, a blank line and that line, in `sha256sum`'s form, on its own, and the zip is
     attached as a release asset (§3.1 _Reading_, §4.8's upgrade test). When GitHub refuses the tag (a workflow
     change on `main` since the run's commit), or `gh release create` fails, the run summary gives the by-hand
     commands for the tag and the release, from the run's one-day artifact `niord-fe-src`, which is deleted only
     once the release exists. A first pin is checked before it is committed — the zip's against these records, a foreign pack's against its home's `pack-releases.md` (§4.5) — by §5 steps 2 and 4; on a base bump the Base bump workflow (`.github/workflows/base-bump.yml`, §4.12) is its executor: it compares the two downloads with release `v<version>`'s notes, which are public, and commits the pin only when they agree; beyond that it is trust on first use, accepted until the attestations. They are not built yet: each artifact is to be attested (`actions/attest-build-provenance`) by the workflow
     that builds it — the zip by `publish.yml`, a pack by its home's `pack.yml`, an image by the country's push
     job — and verified by its consumer.
   - The kit uses Node built-ins only, is covered by the base product's lockfile and audit, and runs only after
     the hash check. `setup.mjs` is a stub that checks the kit's hash. What runs before that check, or holds a
     token without one, is accepted residual trust: Node, the Maven wrapper's distribution and Maven's plugins.
     This repository's wrapper pins its distribution by `distributionSha256Sum`
     (`be/.mvn/wrapper/maven-wrapper.properties`, since 2026-11-04); the country repositories' wrappers had no
     checksum when last read, so theirs is pinned by TLS alone, and pinning it there is a go-live item
     (`fe/docs/backlog.md`).
   - Every build writes `niord-build-report.json` — base product version, packs, escape hatch, overrides (protected
     ones flagged), backend overrides, the bootstrap and release import files, the packs' backend half, country dependencies, the text overrides counted per language, the accepted audit findings counted (the entries stay in `audit-accepted.json` and in set-up's output: the report is served without sign-in), provenance (pinned, or
     `local: <path> @ <sha>`). Its one writer is `setup.mjs`, at step 4 (§3.2), into `fe/.niord/build/`: by then
     it has read every item the report lists, and the backend overrides are the entries of `be/overrides.json`, which
     the override check (§4.9) proves complete or the build fails. The report then ships unchanged: staging puts
     it into the administration bundle, the info image carries it as OCI labels, and the country's Maven build,
     which runs after set-up, puts it into the jar as `META-INF/niord-build-report.json` (configured in
     `niord-parent`); the backend logs it
     at start-up. A base product owner, the host or an auditor can see what a running deployment replaced. The sysadmin page fetches the administration bundle's copy
     (it holds no secret, rule 4), never at boot.
     **Built** (`country-kit/lib/build-report.mjs`): the frontend fields — base product version
     and hash, provenance, packs (a pinned pack with its `reference`, the pin it was pulled by),
     escape hatch, overrides (a protected file never appears: set-up refuses it
     first), country dependencies, text overrides, the accepted audit findings counted — the staging into the
     administration bundle and the info image's OCI labels. **Not built:** the backend overrides from
     `be/overrides.json`, the bootstrap and release import files, the packs' backend half, the jar copy
     `META-INF/niord-build-report.json` configured in `niord-parent`, the start-up log and the sysadmin page's
     fetch.
3. **Protected code — never overridden, a hook instead (owner, 2026-10-19)**
   - Frontend: `seams.json` carries a base-product-owned protected list `setup.mjs` refuses to override —
     `shared-html/**`, the safe-HTML and plain-text pipes, sandboxed HTML, the rich-text editor's allow-lists,
     `core/interceptors/**`, `core/auth/**`, both applications' `app.config.ts`, the administration app's
     `app.routes.ts` and `core/country/**` (the area role guards and the country-route rule),
     the administration app's `environments/environment.ts` (the public site has no `environments/`),
     the Transloco loaders, the administration app's `core/i18n/**` (the loader's inputs: the branding, the
     dictionary overrides and their merge), `core/config/web-settings.ts` and `core/config/app-config.token.ts`
     (the bearer token's host rule rests on the runtime overlay's excluded roots), `repo-url.ts`,
     `ngsw-config.json`, `eslint.config.js`, `scripts/**`, `country-kit/**`, `ui-apps/info/nginx/**`. No DK, GL
     or example file overrides any of them, so protecting them costs no country anything.
   - Backend: protected beans a country may neither replace nor duplicate — `SecurityFilterChain`,
     `JwtAuthenticationConverter`, `JwtDecoder` (Spring Boot's auto-configured bean; `niord-base` declares none,
     `SecurityConfig.java:163-165`, and a country decoder would switch that configuration off and drop the `azp`
     validator, so start-up fails on any `JwtDecoder` bean definition from outside Spring Boot's
     `OAuth2ResourceServerJwtConfiguration`), `GrantedAuthorityDefaults`, the method-security configuration,
     `NiordDomainFilter`, `DomainService`, `UserService`, `TicketService`, `SettingsService`,
     `ScriptResourceService`, `FmTemplateService`, `JsResourceService`, `MessageMailService`,
     `MailingListExecutionService`, `WebAppConfig`, any CORS configuration (token-validator beans may be added:
     they only narrow). Protected properties: `management.server.port`, `management.endpoints.web.exposure.*`
     beyond health, `management.endpoint.*.enabled`, `spring.security.*`, `niord.oidc.base-client`
     (`niord.oidc.allowed-clients` only with a reason). `niord-base` asserts these at start-up. As built
     (`CountryContractGuard`): protected properties are refused where a country
     packages them, in configuration read from the class path (a jar's `application*.yml` or `.properties`); a
     deployment's own configuration (environment, system properties, command line, a `file:` location such as
     Helm's `/config/application.yaml`) is not refused, since the base product's own deployment sets the issuer and
     key set there; `spring.security.oauth2.resourceserver.jwt.issuer-uri` is allowed, for the local profiles; the
     reason for `niord.oidc.allowed-clients` is a non-blank `niord.oidc.allowed-clients-reason` beside it;
     `management.endpoint.*.enabled` covers Spring Boot 4's `.access`, `management.endpoints.access.*` and
     `enabled-by-default` too. *The method-security configuration* is `@EnableMethodSecurity` or
     `@EnableGlobalMethodSecurity` on a class outside `niord-base`, and a `RoleHierarchy` or
     `MethodSecurityExpressionHandler` bean declared outside `niord-base` and Spring Security; *any CORS
     configuration* is a `CorsConfigurationSource` or `CorsFilter` bean declared outside the Spring framework, a
     `WebMvcConfigurer` outside `niord-base` overriding `addCorsMappings`, a handler with `@CrossOrigin`, and
     `management.endpoints.web.cors.*`. Also refused: a `@PropertySource` on a class outside `niord-base` and
     Spring, since its file would reach the environment after the property check. Outside the guard, and
     refused by review: HTTP surfaces outside Spring MVC's handler mappings (servlets, filters,
     `RouterFunction`s, SECOM's RESTEasy endpoints), `Filter`, `SecurityContextHolderStrategy` and
     `BearerTokenResolver` beans, and a property source a jar's own `EnvironmentPostProcessor` adds; a
     country's only way to add an endpoint is an annotated `@RestController` (`be/docs/country-api.md`).
     The protected classes also load only from `niord-base`. The published
     backend check fails on every shadow of a `niord-base` resource that is not a static image under
     `META-INF/resources/img/` (`png`, `jpg`, `jpeg`, `gif`, `webp`, `ico`), listed or not, and on such an image
     when it is not listed (§4.9); on any `templates/**` file in the country jar (templates belong in
     `be/bootstrap/`); on any file under `defaults/base/`; and on a committed `defaults/packs/` (generated).
   - The country settings file **replaces the base product's whole file (owner, 2026-10-20; reverses the values-only
     merge of 2026-10-19 — settings almost never equal between base product and country)**. It is a file under its own
     name (`defaults/country/settings.json`), never a shadow of the base product's, and sets values only: type, scope,
     web visibility and the `editable` flag come from the settings catalogs generated from the code, a password setting is never
     web-visible and is empty in the file (no real secret in a repository), and the Settings page shows the same
     settings as before (owner, 2026-10-20). The build fails on a key of the base product's settings file that the file lacks unless it is listed as using the base product default
     (the pinned base product's settings-file value, else the code default) with a reason, on an undeclared key and on
     a wrong type (`docs/code-data-config-plan.md` §5).
   - **The base product's security tests run in every country build:** on the frontend, the base product's own spec
     beside every overridden file (`<stem>.spec.ts`) runs against the country's copy, from a generated copy whose
     relative imports point back into the base product (the country's specs come on top); on the backend,
     `niord-parent` scans a published security test group of `niord-base`'s test-jar —
     `RestControllerSecurityAnnotationTest`, `RestHandlerSecurityITTest`, the `SecurityConfig` tests, the
     sanitiser and `MailHtml` tests, the start-up guard — which a country inherits with the parent. The annotation
     test scans `org.niord` only (`RestControllerSecurityAnnotationTest.java:118`); what binds every controller,
     a country's outside `org.niord` included, is the start-up guard:
     `niord-base` refuses to start if a registered handler method resolves no JSR-250 annotation on the method
     or its declaring class, as `Jsr250AuthorizationManager` resolves it (`RestControllerSecurityAnnotationTest.java:160`), so a country
     controller cannot fail open; every top-level entry of the administration app's `country.routes.ts` has
     `canMatch: [roleGuard]` with a non-empty `data.rolesRequired`, or `data: { public: true }`, or is a redirect —
     the base product leaves out and logs any other at run time (`core/country/country-routes.ts`), so a
     forgotten guard fails closed. The public site's country routes are public by construction: the site has no sign-in.
     As built: the group is the classes tagged `@CountryBuildGroup` (the JUnit
     tag `niord-country-build`) — `RestControllerSecurityAnnotationTest`, `RestHandlerSecurityITTest`,
     `SecurityConfigJwtTest`, `SecurityConfigFrameOptionsTest`, `JwtAuthorizedPartyITTest`,
     `MethodSecurityOrderingITTest`, `NiordDomainFilterTest`, `WebAppConfigTest`, `MailHtmlTest`,
     `FmTemplateClassResolverTest`, the guard's `CountryContractGuardTest` and `CountryContractStartupTest`, and
     `CountryDefaultsChecksTest` (the country's settings and text checks); `CountryBuildGroupTest` pins the set.
     `niord-parent` runs it with a Surefire and a Failsafe execution of its own over `niord-base`'s test-jar only,
     so it runs where a country declares that test-jar (DK and GL do), and a country's workflow
     checks the group's count. The guard checks every handler method of Spring MVC's
     `RequestMappingHandlerMapping` beans, in any package, with the scanner the annotation test uses
     (`CountryContractGuard.JSR250`), and refuses a handler with `@CrossOrigin`; the HTTP surfaces outside it are
     listed above, with the protected properties.
   - The kit package carries `security.json` (the files each version changed for a security fix). An override
     whose changed `basedOn` file a fix names fails with a SECURITY message naming the version until the country
     has merged the fix into its copy and recorded the fixed fingerprints with `--accept-override`.
   - The generated lint applies every base product security rule to the country's files — the public site's
     anonymity bans, the `bypassSecurityTrust*` ban with no country exemption, `[innerHTML]` only through the
     base product's pipes (`safeMessageHtml`, `plainTextToHtml`) or a translation (`transloco`, whose markup the
     pack and branding checks hold to English's and Angular sanitises as a plain string), as the binding's last
     pipe — and forbids inline disabling there (`noInlineConfig`: an `eslint-disable` disables nothing and is
     itself reported).
   - Backend templates and script resources are code with full privileges. Data templates and scripts reach a
     deployment only by its bootstrap or a sysadmin's import, recorded with the user; the kit's backend check
     runs over every country bootstrap and release import file and refuses process, file, reflection, `eval`,
     `Java.type` and write APIs in scripts — a guard against carelessness, not a boundary (the boundary is
     SYSADMIN plus history); the base product sets a FreeMarker class resolver that admits only Niord's directives and keeps
     `?api` off (`docs/code-data-config-plan.md` §6.5); the `MailHtml` rule binds country templates.
     A deployment runs the database row of every template by design (R14); a Java change that a template depends
     on stays compatible or ships the template in a release import file (that plan's §6.2).
4. **Text, markup and files**
   - The markup rule (§4.6) applies to packs, text overrides and branding: a value's tags, attributes and
     every `href`/`src` must equal its reference's: the catalog's English value for a pack or a text override, and the
     country's own English branding (its default language's where `en` is not a UI language) for branding, where an
     `href` may differ only in path, on the same `https:` host. A key with no markup in its reference may carry none. The
     catalog marks every key whose English text has markup — an HTML element tag, so a literal `<none>` is text —
     which are the keys the applications render as HTML.
   - `pack.json` has a strict schema (unknown fields fail): `lang` `^[a-z]{2,3}$` equal to the folder and the
     package name; `locale` `^[a-z]{2,3}(-[A-Z]{2})?$`; `label` plain text, at most 40 characters; `flag`
     exactly `img/flags/<lang>.png`, a regular file inside the pack with a PNG signature, at most 64 KB. The
     generators never interpolate a pack or manifest value into source text — they emit JSON the applications
     import as data. Staging fails on any source path outside the pack or country directory, and on a key named
     `__proto__`, `prototype` or `constructor`.
   - Brandable files are data, never active content: legal documents pass the element and attribute allow-list
     the dialog renders (no script, style, iframe, form, svg, event or style attributes, `javascript:` or
     `data:` URLs); images are PNG, WebP or ICO, or an SVG with no script, `foreignObject`, event attributes or
     external references. The administration app's backend and the public site's nginx (`location ^~ /assets/legal/` and `/assets/branding/` in the base-product-owned template) serve `/assets/legal/**` and `/assets/branding/**` with
     `Content-Security-Policy: sandbox` and `nosniff`, not overridable. nginx keeps `nosniff` and the
     referrer policy there (`nginx/niord-common-headers.conf`, which the site's header file includes too) and
     replaces only the policy: an `add_header` in a location drops every inherited one, and two policies would
     both be enforced.
   - Every seam, branding and text-override file ships to anonymous browsers: a secret never goes in one; a
     credential a feature needs stays on the backend. `setup.mjs` scans the country's `fe/` inputs (its
     application folders, styles, text overrides, home packs and the pinned packs it copied, the manifest, its
     npm manifest and lock, the escape hatch, `audit-accepted.json` and the pins) and the build check
     (`check-build.mjs`, §4.8) both `dist` for secret patterns (`country-kit/lib/secrets.mjs`): a PEM
     private key; token formats by their issuer's prefix — AWS access key ids, GitHub (`gh?_`, `github_pat_`),
     GitLab, Slack, Google API, Stripe live, npm, Anthropic and OpenAI project keys; a JWT; credentials in a URL;
     and a name ending in a secret-like word (password, secret, token, API key, client secret, private key,
     access key) assigned a quoted value of 16 or more characters, no whitespace and at least 3.5 bits of
     entropy per character — never a placeholder, a dotted key path, a constant's name, a hyphenated slug or
     hex shorter than 32 digits. A finding names the file, the line and the pattern, never the value. The
     allow-list is the base product's, `country-kit/secret-scan.json` (each entry a pattern, a glob, the scan it
     is for — `inputs` or `dist` — an optional country and a reason), so a country cannot silence the scan by
     itself; it is empty, the measurement over the example's, DK's and GL's inputs and the six builds having found
     nothing.
5. **Runtime**
   - **The administration app has a Content-Security-Policy, in the base product, not overridable (owner,
     2026-10-19):** `default-src 'self'`, `script-src 'self'`, `style-src 'self' 'unsafe-inline'` (Angular's component styles, the inline splash), `object-src 'none'`, `base-uri 'self'`, `form-action 'self'` (as the public site; keycloak-js logs in and out by navigation, not by a form post), `frame-ancestors 'self'`,
     `font-src 'self'`, `connect-src 'self'` plus the Keycloak origin, `frame-src 'self'` plus the Keycloak origin (the silent `check-sso` iframe), `img-src 'self' data: blob:` (the protected WMS tiles arrive as `blob:` URLs) plus the tile origins of the current settings, built at runtime because an operator may change them (`docs/code-data-config-plan.md` D3; no `https:` wildcard); the WMS provider is not listed, because the browser reaches it only through the same-origin `/wms/` proxy (`WmsProxyServlet.java:55`).
     The Keycloak origin is that of the token issuer (`spring.security.oauth2.resourceserver.jwt.issuer-uri`),
     the browser-facing Keycloak — not the `authServerUrl` setting, which a deployment points at the in-cluster
     Service (`be/k8s/helm/niord/templates/app/deployment.yaml:109`); a deployed bundle reaches Keycloak at
     the same-origin `/auth`, so there it adds nothing beyond `'self'`. The tile settings (`osmUrl`,
     `openSeaMapUrl`) are read uncached on each HTML response, as `/rest/settings/web` serves them, and the
     memo is keyed by their values, so the policy follows a change on the next page load on every pod; a
     source is taken from the value as the browser reads it, a tile host with an OpenLayers range
     (`{a-c}`, `{1-4}`) gets `*.`, and a value the policy cannot admit adds no source and is logged. The policy
     is sent on every navigation the SPA fallback serves and on every top-level `.html` of the build
     (`index.html`, `silent-check-sso.html`), never on `/rest/**` or other assets. An image a message links
     from another site is refused here by design (none in DK's or GL's data on 2026-10-21, every message
     image is under `/rest/repo/file`; the public site keeps `https:`). The policy-follows-settings test
     is `WebAppContentSecurityPolicyITTest`; the e2e smoke run executes under it.
   - No absolute external URL in any `js`, `css` or `html` of `dist` except the base product's allow-list (the tile
     templates; the WMS layer is the same-origin `/wms/`), in both applications. A country package loads no remote code.
     The check is `scripts/build-urls-check.mjs --absolute` (an `http://`, `https://` or protocol-relative URL) with
     `scripts/build-urls-allowed.json`, measured over the base product's own builds and the example's — XML
     namespace names, OpenLayers' KML and projection identifiers, the tile templates and the map's attribution
     link, the documentation address and library error-message links — each entry with a reason and no wildcard
     host; `assets/legal/**` is not read, its documents being branding data held by rule 4. Every country build
     runs it (`check-build.mjs`).
     **A country's own addresses** go in its `fe/build-urls-allowed.json`, never in the base product's list,
     which carries no country's address (R1; its note says so). The file has the base list's format
     (`{ format: 1, note?, allow: [{ pattern, files?, app?, reason }] }`, an unknown field, a missing reason or
     another format failing), and each entry is stricter: one literal `https:` URL ending with `$` — `\.`, `\/`, `\?` and `\-`
     the only escapes, any other regular-expression syntax (`^` and an unescaped `.` included) refused — whose host
     is a dotted name of letters, digits and hyphens, with no IP address, port or credentials
     (`compileCountryUrlAllowList` in `scripts/build-urls-check.mjs`). An absent file is an empty list; a link or
     other non-regular file fails. Check-build reads both lists; a URL both admit counts for the base list; every
     build prints each country entry with its reason, and an entry that admits nothing in either application's
     build is a warning; the message for a URL neither admits names both files. `.github/CODEOWNERS` names the file.
     An entry may name its application, `app`, `admin` or `info` (another value fails): a scoped
     entry admits its URL only in that application's output, is printed with its scope and warned about when it
     admits nothing in that application's build, and a URL that only an entry scoped to the other application
     would admit fails with a hint naming that entry; an entry without `app` holds in both (a `files`
     glob such as `main-*.js` matches both applications' main bundles). The base product's own list refuses
     `app`: it holds in both applications by design.
   - The info image is built from a fresh context holding only `dist/niord-info/browser` (as `browser/`), the base
     product's five nginx files and its Dockerfile, pre-compressed there by `precompress.mjs`, which runs
     outside the image; it refuses a non-production build (a source map, a `sourceMappingURL` comment, an
     `index.html` script or stylesheet without a content hash), a pre-compressed twin, a link or other
     non-regular file, or a file outside the output allow-list `country-kit/info-image-allowed.json`. Before
     a country pushes it, the container test (`--browser`), the layout check and the separation check run
     against the built image: the image's web root and `niord-security-headers.conf` are copied out
     (`docker create`, `docker cp`), every file of the `dist` must be there byte for byte and every other file be
     the `.br`/`.gz` twin of one, which binds the image to the build whose stats the separation check reads; the
     layout check runs on that copy under that policy, and the container test against the image. A country's image
     carries the build report as the OCI labels `org.niord.build-report` and `org.opencontainers.image.version`
     (rule 2). The CSP, the other security headers, the proxy allow-list and the query maps
     are never parameters: a country feature that needs another backend path or CSP source is a base product change.
6. **Publishing, bumps and the repositories**
   - The source zip is built from an **explicit include list** (`country-kit/zip-include.json`): the
     applications' sources and public assets minus country overlays, the shared folders, the workspace files
     the kit needs, the base tokens, the `en` pack, the catalog, and of `scripts/` only
     the public site's checks a country calls with parameters (R8) and the absolute-URL rule's allow-list —
     `scripts/{info-separation-check,info-layout-check,info-bundle-report,info-container-test,build-urls-check}.mjs`
     and `scripts/build-urls-allowed.json`, with `glob.mjs`, the one `scripts/` file one of them imports — never
     `fe/docs/`, backlogs, e2e credentials or go-live material, and nothing of the kit, which is its own package
     (`country-kit/kit-include.json`). The end-to-end suite, `ui-apps/admin/e2e/**` with its login
     fixtures (`ui-apps/admin/e2e/auth/`), and the root `playwright.config.ts` are in it for the generated
     workspace's `e2e` script, and the suite names no country's test environment: `keycloak-login.ts` requires
     `KEYCLOAK_URL`, the unreachable-Keycloak spec blocks any `/realms/` path,
     and `app-breadth.spec.ts` reads the served backend; `zip.test.mjs` fails any file of the suite naming one
     (`dma.dk`, `brs.dk`, a `login.<host>` default). Its README stays out with every README under
     `ui-apps/`. `publish.yml` fails on any path outside it and runs the
     country-word guard over the zip. As built: its build job builds the zip
     with the kit's own code (`source-zip.mjs build`, Node built-ins only, no npm) right after the checkout and
     before Maven, then `verify` (any path outside the list) and `guard` (the country words, §4.8) on the archive
     it deploys; a finding fails the run before anything is deployed (`web-publish`, `harbor` and `tag` wait for
     the build job). `build` itself also verifies and guards,
     and writes nothing on a finding; `verify` stays word-blind, so a set-up never fails a published zip on a
     word added later. A finding names the file, the line and the word, never the line's text. Each allow-list
     entry of `country-kit/country-words.json` is one word and one path glob with a reason and, where the need
     ends, what ends it (`until`); an entry that permits nothing fails, so the list shrinks with the files. The
     list itself stays out of the zip (it names every word; a country never runs the guard).
     A spec or comment that names a word is allow-listed with its reason rather than kept out of the zip: a base
     product spec beside a file a country overrides runs against the
     country's copy (rule 3), so leaving specs out would silently drop that check. **The list is loosened when
     the repositories go public (owner, 2026-10-19).** The base product's repository has been public since
     2026-11-17, and the country repositories `niord-dk` and `niord-gl` are not. The list stays unchanged until
     the owner says whether the condition is met.
   - **Pack and base product bumps are never automerged (owner, 2026-10-19):** the bump PR prints every changed value
     per application and flags values with markup or URLs. **Their cadence (the owner, 2026-10-25, 2026-11-01
     and 2026-11-03):** the Niord base bump app opens the bump PR at every publish, minutes after the release, and
     moves the one open bump PR to each newer version, never automerged, the review and the merge being the
     human gate; since the app's PR moves the pom but not the lock's base pin `{version, sha256}`, which set-up
     then refuses, a small workflow in each country, the Base bump workflow, re-pins the lock on the
     `base-bump/` branch (§4.12).
     **Green means no task (the owner, 2026-11-01):** the upgrade report is posted on the bump PR, and every
     action set-up cannot check (the `API-CHANGES.md` actions, a changed template to merge, a workflow file to
     copy by hand, a new bootstrap release folder) is a box under `## Human actions` in one sticky comment of
     the Base bump workflow, which holds the report too, with the commit status `niord/upgrade-actions` red
     while a box is unticked — not the PR description, which the app rewrites at every move; a release folder's
     import stays a human decision. The workflow is `country-kit/templates/base-bump.yml`.
   - A version marked in `security.json` is raised to every country at once; its new keys may fall back to English
     in a printed SECURITY block limited to those keys; the time from publish to each country's rebuild is a
     tracked number. With the cadence above every version, a security version included, reaches every installed
     country as its bump PR within minutes of its publish; the Base bump workflow labels the pull request
     `security` when the report lists a new `security.json` fix.
   - A home withdraws a bad pack with `pack.yml`'s withdraw dispatch, which lists its digest and the reason in the pack's withdrawn marker on GHCR (§4.5) — the channel every consumer reads anonymously; its `pack-releases.md` is the human record.
     `setup.mjs` reads the marker with every foreign pack it sets up and fails a pin listed there, printing the reason; a published pack without a marker fails as unreadable.
     As built: set-up reads the marker anonymously before anything else of the pack, and a listed pin is the
     first failed check, `pack <lang>: <reference> is withdrawn (<date>): <reason>; move the pin with node setup.mjs
     --update-pins`, its language left out of that run's pack check so that one line names it, and on a version change
     it is the upgrade report's first To-do item (§4.12). The pull itself is anonymous by construction: the client
     reads no token for a pull and sends credentials only to a token endpoint on the registry's host, and only when
     it publishes or withdraws; then it asks for `pull,push` on every request, reads included, its own scope taking
     precedence over the registry's challenge, and a write (`POST`, `PUT`) follows no redirect, failing on one.
     What a consumer verifies: the manifest's SHA-256 against the pin's digest (at most
     64 KiB), exactly one layer of the pack's media type and a config naming the pinned language, the layer's
     SHA-256 and size against its descriptor (a hard cap besides), then the archive's layout and caps
     (`readPackArchive`), and then the pack check against the consumer's own catalog; a redirect of a blob to another
     host carries no credentials. Hosts: `ghcr.io` over HTTPS, or a loopback registry over HTTP, which only the kit's
     tests and the harness write into a lock; set-up accepts a loopback pin wherever a lock holds one, since the
     digest guards the bytes and the lock's change shows in review.
     The GHCR namespace: any repository's workflow token in the `SIFA-DK` organisation can create
     `ghcr.io/sifa-dk/niord-lang-<lang>` before the pack's home does (a package is created by the first repository
     that pushes to it, and linked to it); this closes at the home's first publish, and until then the pin review —
     a first pin compared with the home's `pack-releases.md` — is the guard.
   - `--accept-override` prints, per `basedOn` file, the recorded and the current fingerprint and the lines in which the
     country's copy differs from the base product's current file; and the base product's own diff
     of each changed file since the recorded fingerprint, from the previous version's file — the zip of the last
     set-up record's version that the stub's fetch left in `.niord/`, read only when its sha256 is the record's and
     the file's fingerprint the recorded one, else one line saying why it is not available — as the upgrade report
     (§4.12) shows it. The
     country CI's job summary lists every change to the override, escape-hatch, text-override and dependency
     files against the previous `main`; `.github/CODEOWNERS` names their owners — a gate only with the branch rule *Require review from Code Owners*, which GitHub Free lacks for a private repository, so until the repositories are public the file documents and this job summary is the gate; the go-live recheck reviews every
     override and escape-hatch entry of a release.
   - Before a filtered history is pushed and before any repository goes public, a full-history
     secret scan runs and its result goes into the PR; secret scanning and push protection are switched on with
     the visibility flip; the transition reviews every workflow for fork-PR secret exposure, pins third-party
     actions by commit SHA and adds a `SECURITY.md`.

### 4.12 Upgrading a country to a new base product version

The maintenance a country does most often, so it must stay boring. **One pull request:**

1. The Niord base bump app opens a PR from a `base-bump/<version>` branch made from `main` that moves the pinned
   base product version, the `<parent>` of `be/pom.xml`, minutes after each publish, asking the code owners of
   `be/pom.xml` for review; a newer release moves the open PR on its branch (people's commits kept); a branch in
   conflict with `main` is replaced by a fresh PR and the old one closed with a comment; an app PR left behind
   when a bump by hand brought `main` to the published version or past it is closed with a comment; never
   automerged (the owner, 2026-10-25, 2026-11-01 and 2026-11-03;
   §4.11). Without the app a person pushes `base-bump/<version>` with the same one
   change and opens the PR; the app leaves such a PR alone.
2. The country's Base bump workflow (`.github/workflows/base-bump.yml`) runs on
   it, since the app moves only the pom (the owner, 2026-10-25): it reads release `v<version>`'s notes first
   and compares them with the two downloads; only when they agree does it pin the lock's `base {version,
   sha256}` and `kit {sha256}`, run set-up, copy the stub and the launcher, move every pinned pack to its home's
   newest pack (`--update-pins`) in the same PR, named in the comment as a line, not a box,
   commit as `github-actions[bot]`, dispatch CI on that commit and post its
   comment and the status `niord/upgrade-actions`; a move posts the comment again, keeping the tick of a box
   while what it is about is unchanged: its text, and for a template's box the kit template's content, named
   by a hidden marker at the box's end, so a tick never hides a template changed again at a later
   version. Every other template the kit changed (`CODEOWNERS`,
   `dependabot.yml`, the `be/` init folder, one added or removed) is named in one line of the comment and of
   the upgrade report, with no box and no red check (2026-11-04). The country's own token
   reads the release notes of the base product's public repository; notes that cannot be read fail the run,
   which pins nothing.
   The country's pipeline then
   runs `setup.mjs` on the pinned commit: fetch and verify, check packs, overrides, the API, the
   dependencies; generate; test; build.
3. **Green: no task.** Green is CI and `niord/upgrade-actions` both green. The country reviews, merges and
   deploys; the upgrade report and the list of human actions are in the Base bump comment, every needed
   action a box there, the status red while one is unticked (the owner, 2026-11-01). The new base product
   functionality, build options, budgets, service worker, seam defaults and language lists come with the
   version — the country never copied any of them.
4. **Red: each reason has its action:**

| Stops because | The country | How often |
|---|---|---|
| the status **`niord/upgrade-actions`** is red, `Base bump: <n> human action(s) unticked` | does each item of the comment's `## Human actions` and ticks its box, never deleting one | at every bump that asks something set-up cannot check |
| a bump PR has **no Base bump comment** (the status pending, `Base bump: no comment yet; …`) | runs the workflow on it (Actions, Base bump, Run workflow, `pr <n>`) | after a pin pushed before the first run |
| the status says **"Base bump failed: see the run"** | opens the run: a notes read that failed (runs it again), notes that disagree with the registry's zips (nothing pinned; tells the base product's maintainers), a repository without the kit package's layout | rare |
| a **workflow file** item (`pack.yml`, `base-bump.yml`, an image template to merge into `image.yml`) | copies or merges it by hand: the workflow's token may not push a workflow file | when the kit changes one |
| new English keys missing in **its own** pack | translates them in the same PR (the report lists them); that push stays on the branch when a newer release moves the PR; `pack.yml` publishes the pack on the merge | most versions with new UI text |
| new keys missing in a **foreign** pack | the Base bump workflow moves the pin with `--update-pins` at every bump; until the home has published a pack with the keys it waits, then re-runs the workflow on the PR or takes the next release's move, or accepts English via the escape hatch, or writes its own wording (§4.6) | when a home lags |
| the base product changed a file the country **overrode** | compares with the base product's diff, adjusts its copy, re-accepts | only with overrides |
| the base product changed the **country API** | does what `API-CHANGES.md` says for that version | rare; deprecated first, so a warning before an error |
| the base product changed the **set-up stub** (`templates/setup.mjs`) | the Base bump workflow copies it; in a bump without the workflow the country copies `.niord/kit/templates/setup.mjs` over `fe/setup.mjs`, as `API-CHANGES.md` says for that version: on a version change set-up reports the stub as a failed check whose copy command is the report's first To-do item, and stops before staging (without a version change it refuses the stub at once) | rare; a breaking change by `API-CHANGES.md`'s policy |
| a **security fix** touched an overridden file | merges the fix into its copy | rare, never skipped |
| a **country npm package** rejects the new framework version | updates that package | at framework major upgrades |
| a key the base product **added to its settings file** is missing from the country's | sets a value, or lists the key as using the base product default (the pinned base product's settings-file value) with a reason; the report lists the new keys. A key the base product declares only in code takes its code default and needs nothing (`docs/code-data-config-plan.md` §5.5) | when the base product adds a key to its settings file |
| a **new or changed data template** in the base product's `be/niord/bootstrap/releases/<yyyy-mm-dd>-<topic>/` import file | decides whether its own bootstrap and each of its deployments take it (a sysadmin imports it; nothing arrives by itself) | when the release notes name one |
| the **branding schema** requires a key or file the country lacks, or removed a key it fills | adds the key or file for every UI language, or deletes the removed key (§4.6) | when the base product changes the branding contract |
| a file the country **overrode** joined the **protected list** | replaces the override with an extension point, or asks the base product for one (§4.11 rule 3) | rare |
| a **base product dependency** has a high advisory (`npm audit`) | lists it with a reason in `audit-accepted.json` until a base product version fixes it, then removes the entry (§3.2) | when an advisory comes before the base product's fix |
| a pinned foreign pack is **withdrawn** | moves its pin to the home's next pack, or back to the last good one (§4.11 rule 6) | rare |
| a **setting key** in its settings file, or a **text-override** key, **left the catalog** | deletes the entry, or moves it to the key the release notes name as its successor (§4.10; `docs/code-data-config-plan.md` §5.5) | when the base product removes or renames one |
| a **changed English value** has markup a translation or text override no longer matches | fixes the markup in its own pack or text override; for a foreign pack, waits for the home's fixed pack | when English markup changes |
| a key the base product **renamed**, in a **foreign** pack its home has not migrated | as for a foreign pack's new keys: the key is missing until the home publishes a migrated pack | when a home lags after a rename |

**The upgrade report.** `setup.mjs` writes `niord-upgrade-report.md` for every version change, and its data
beside it, `niord-upgrade-report.json`; the Base bump workflow posts the report whole in its comment on
the bump PR, not in the description, which is the app's, rewritten at each move — one page saying what the new version means *for
this country*:

- the base product versions crossed, with their release notes' headlines;
- **to do** (set-up or the build fails until done; only what they enforce): the stub to copy when the base
  product changed it, per pack the new keys to translate and the translations whose markup
  no longer matches a changed English value, the overrides whose base product files changed (with the diff) or
  that are now protected, the branding keys and files the schema added or removed, the settings and
  text-override keys that left the catalog, the keys the base product added to its settings file, a withdrawn pin, the dependency advisories not accepted, a country package that rejects the new framework versions, the security fixes touching an override;
- **to look at** (warnings): English texts changed under existing translations, text overrides now equal to
  the pack (redundant) or differing from it, deprecations with the version they end in, escape-hatch entries
  that can go, and — under a line saying set-up does not check them — the actions `API-CHANGES.md` names for
  the versions crossed;
- **new for you:** extension points added since the previous version — and, for each override, whether a new
  hook now covers it, so the override can be dropped;
- the dependency changes: base product framework versions moved, country packages affected.

**Where and when.** The report is `fe/.niord/niord-upgrade-report.md` (`country-kit/lib/upgrade-report.mjs`),
under the generated `.niord/` and so never committed; set-up prints its path. The previous version is the last
set-up record, `.niord/build/niord-build-report.json` (its `base.version` and `base.sha256`), which set-up writes
only when every check passed, so a red bump keeps comparing with the last good version; the report is written on
every version change after the checks, **also when they fail**, since a red bump is when it is needed, and not at
all without a record (a first set-up says so), unless `--previous <version>` names the previous version,
or without a version change. Its headings are `# Upgrade report: <cc>
from <previous> to <version>`, then `## Versions`, `## To do`, `## To look at`, `## New for you` and
`## Dependency changes`, every item naming the file and the action; a To-do item that set-up does not check is
listed under _Not checked by set-up_. The previous version's files — for the overrides' diffs, the
`API-CHANGES.md` sections and `security.json` fixes new since then, the extension points and API exports added,
the dependency versions moved — come only from that version's zip, `.niord/niord-fe-src-<version>.zip`, which the
stub's fetch left there, read when its sha256 is the record's; nothing is fetched for the report. Without it (a
record from a local checkout, no such zip, another hash) each section that needs it says once why, and the new
sections are found by version. **What this kit version cannot fill**, said plainly in the report: the release
notes' headlines (the zip carries none; the new `API-CHANGES.md` and `security.json` sections stand in); the keys
the base product added to its settings file and the settings keys that left the catalog (the zip carries no
backend settings list; `docs/code-data-config-plan.md`); and the advisories (`npm run build`'s audit, after
`npm ci`). A withdrawn pin is a To-do item naming the
pack, its reference, the home's date and reason and `node setup.mjs --update-pins` (or `--update-pins <lang>=<tag>`). The
Base bump workflow makes the record a fresh CI workspace lacks by a set-up at the base commit and, when that
fails, runs set-up with `--previous <old>`; it posts the report on the PR, in its comment.

A country that keeps to extension points and translates its own packs gets a green bump almost every time;
the cost of an upgrade grows only with what it chose to override, and the report says so in numbers.

### 4.13 What the base product gives, and what a country owns

The division of labour between the core base product's maintainers and a country — the contract a new country
signs up to, repeated in the country guides (`country-kit/docs/`).

**What a country gets for free, with every base product version:**
- all base product functionality, fixes and security fixes of both applications and the backend,
  audio promulgation among them: its type in the base product's bootstrap and its editor and viewer in the
  administration app (the owner, 2026-10-24);
- the build itself: build options, budgets, the service worker, the generated workspace, the kit and its
  checks, the info container with its nginx configuration and security headers, the image tools;
- English text for every key, and the key catalog that tells each pack what is new;
- the country API with a compatibility promise: breaking changes named in `API-CHANGES.md`, removals
  deprecated first for at least one version both DK and GL have taken;
- an upgrade report for its bump (§4.12), and an upgrade test in the base product's CI that builds an example
  country from the previous version, so a break is caught before the base product publishes it;
- new extension points when a country's need is general — a hook instead of an override;
- the sample deployment, the guidelines, `init`, and the base product's dependency security alerts (a fix arrives
  with the version).

**What the base product maintainers are responsible for:**
- keeping the country API stable, documenting and deprecating every change to it;
- keeping the example country and the acceptance and upgrade tests green — they are the base product's promise;
- marking security fixes (`security.json`) and raising them to every country with priority;
- reviewing requests for extension points, and adding them when the need is general;
- never shipping country-specific code, text or data in the base product (R1), and publishing English only.

**What a country owns and maintains:**
- its manifest, seams, branding, legal texts, styles and fonts;
- **its home packs**, for itself and every country that uses them: translating new keys when the base product
  publishes them — a home's pack is a service to others, so it should not lag far behind;
- its overrides: each is its own maintenance debt, reviewed on every base product change to the files it copies;
- its own functionality (pages, hooks, slots), its country npm packages and their security alerts;
- its escape hatch and text overrides — kept short;
- its pipeline, its images and its deployments, including taking base product versions promptly — above all those
  marked security;
- its backend country module, by the same rules (§4.9), with its settings file, country texts, bootstrap and
  release import files (`docs/code-data-config-plan.md` §9).

**What a country does not do:** edit generated files, change base product code except through a guarded override,
override protected code, put secrets in frontend files, or add country text to a base product pack.

---

## 5. Adding a country

The test of this plan is that this list is complete for a new country — say Norway, `nb` and `en` — with
nothing changed in the base product.

The list is the plug-and-play path, and for most countries steps 1, 2 and 9 are all
of it: the release, the answers, `init` and set-up, the launcher and how to see it worked, which are the Quick start
of the short guide, `country-kit/docs/country-guide.md`. Steps 3 to 8 are its _Make it yours_, each only where the
country differs from what `init` wrote, beside the branding and the PDF logo every country sets (the reference
behind them is `country-kit/docs/country-guide-in-depth.md`); steps 10 and 11 are its _Deploy_, followed by its
definition of done and _Before go-live_. An AI agent can do steps 1, 2 and 9 from an empty folder by
`country-kit/docs/set-up-prompt.md`. The guides give the commands; this list gives the steps.

1. **The repository and the release.** An empty folder, `niord-<cc>`, with `git init`. The one machine file is
   written by hand: `~/.m2/settings.xml` with a classic `read:packages` token under the server id `github`, which
   set-up, the backend build and the launcher read (GitHub Packages refuses an unauthenticated read even of a
   public package, so the token is needed although the base product's repository is public). Release
   `v<version>`'s two assets, the
   source zip and the kit package, are downloaded into the root's `.niord/` (`gh release download`), the two lines of the
   release's notes are written to `.niord/SHA256SUMS` and checked with `sha256sum -c` (§4.11 rule 2), and only the
   kit is unzipped, into `.niord/kit/`. No pom, wrapper or Maven is needed before `init`, which writes the backend
   (step 8). Another country's `be/` or `.github/` is not copied: `init` keeps a
   file that exists, so a copied `.github/` would keep the other country's workflows. A country may carry a file
   mirror of GRAD's SECOM library, `be/third-party-maven/`, listed first in its pom (DK and GL do); the owner's ruling
   of 2026-11-01 keeps it so for now, and where `secom-v2-core-jakarta` lives for good is a later ruling (the
   mirrors' removal waits for it; `be/docs/potential-errors.md` BUILD1). `init` writes no `be/third-party-maven/`,
   the in-depth guide gives the copy step, and whether the kit ships the mirror to a new country is the owner's open
   question: until then a new country's first build on an empty Maven cache needs GRAD's repository
   up.
2. **The answers, `init` and set-up.** `node .niord/kit/init.mjs <cc> --sha256-file .niord/SHA256SUMS` from the
   repository root (or with `--answers <file>`; §4.8) checks both zips against the two lines, then asks the other
   fifteen answers (all sixteen without the code). Required are the ISO 3166-1 alpha-2 code in lower case, the name, the organisation, the time zone, the
   map centre's latitude and longitude and the `CODEOWNERS` owners; the rest have standard defaults: no language
   beside English, no producer code, zoom 6, and a first domain `<cc>-nw` of main type NW with its series
   `<CC>-NW-${number-3-digits}-${year-2-digits}`, YEARLY (`<cc>-nm` and Notices to Mariners with NM). A refusal
   names every problem and writes nothing; otherwise `init` writes the whole country (§4.8; on an empty
   folder) and pins both zips' hashes in `fe/niord-pins.lock.json`. Then `(cd fe && node setup.mjs)`, with
   `--update-pins` when languages were answered, fetches both packages through `be/mvnw`, checks them against the
   pins and generates the workspace; from then on `setup.mjs` keeps the base product current. The first commit
   gives the three scripts their exec bits (init's closing line).
3. **The manifest:** `init` wrote it from the answers: English and the answered languages as the UI and content
   languages of both applications, the default `en`. Languages per application, which packs are pinned and which
   are the country's own, and another default language are the in-depth guide's.
4. **Packs.** For each pinned pack, its name in the manifest (with its `owner` when not `sifa-dk`) and its full reference, tag and digest in `niord-pins.lock.json`
   (`node setup.mjs --update-pins`, §4.11 rule 2; before a first pin is committed, the digest it wrote is compared with the one the home's `pack-releases.md` records, §4.5 — while the home is private, as its maintainers give it); set-up stages every pack's backend half, home packs included, into `fe/.niord/packs/<lang>/backend/`, and the backend build reads it there: for every pack but `en` the pom copies the staged pack from `fe/.niord/packs/<lang>/` and its `require-staged-packs` check refuses a build that runs before set-up (the kit README's *The backend build*), so a pinned pack's backend half reaches the jar as a home pack's does. For a language
   no country has yet (Norway's `nb`): `fe/packs/nb/` with `pack.json`, the flag and its attribution;
   list it under `contentLanguages` until `pack check` says the text is complete. If it should be
   published, `pack.yml` (written by `init`) publishes it as `ghcr.io/<owner>/niord-lang-nb`, `<owner>` the country's GitHub owner, with the country's own workflow token (a consumer names that owner in its manifest), and
   the country sets the package public once — nothing is asked of the base product or of anyone else. An answered
   language is a published pack pinned from its home by set-up's `--update-pins` (on 2026-11-03 `da` from DK was
   the one published pack); the pom `init` writes carries, per language, the pack's two
   copies and the flag's `<available>` line only, so a pinned pack without a backend half builds.
5. **Branding:** `init` writes the skeleton the schema requires and no branding text (§4.4); the country fills it
   in — logos, favicon and touch icon, the legal documents — and once an application has a branding text, each of
   its UI languages needs its `branding/<lang>.json`, with `branding-unaccepted.json` listing what a reader of a
   language has not accepted yet. The public site's default footer offers the logo, the accessibility
   statement (a link to `info.config.ts`'s `accessibilityStatement`) and the disclaimer and cookie statement (in the
   legal dialog), each when its branding label is set; a footer of the country's own is only for more.
6. **Styles:** `tokens.<cc>.css`; optionally a vendored face.
7. **Seams and functionality**, only where the country differs: environments (its test backend and
   Keycloak), `info.config.ts` (which `init` writes with the answered time zone), `index.html`; its own pages, menu
   entries, behaviour hooks and component slots (§4.9); a guarded override only where no extension point fits (the
   in-depth guide's _Seams and own code_).
8. **The backend**, which `init` wrote (§4.8), changed only where the country differs: its settings file
   `be/src/main/resources/defaults/country/settings.json`, which replaces the base product's file
   (`docs/code-data-config-plan.md` §5.2), complete from the first build — the 11 keys the answers set in `values`,
   the languages (`siteLanguages`, `modelLanguages`, `defaultLanguage`, `infoSiteLanguages`, `infoDefaultLanguage`),
   its `country` and `organisation`, the three `mapDefault…` keys and `"niord.home": "${NIORD_HOME}"` (without that
   key `niord.home` still follows `NIORD_HOME` whenever no `niord.home` property or variable is set, so the key
   is the countries' convention, which the settings check
   requires), and the other 15 keys of the base file in
   `useBaseDefault`, each with a reason, so the strict rule passes (that plan's §5.5); its configuration in
   `application.yml` — above all `niord.s124.enabled`, written explicitly whatever its value (the S-124 opt-out, the
   owner 2026-11-01: on by default in the library, `true` for DK and GL, `false` in niord-app; `niord.secom.enabled`
   needs it), which `init` writes `true`, and with S-124 on `niord.s124.producer-code`, its own IHO S-62 producer
   code, which the base product leaves unset (`be/niord/src/main/resources/config/niord-core-defaults.yml:106-120`)
   and `init` writes only when answered (never `DK01`, which DK and GL use by the owner's ruling of 2026-10-22, nor
   `DK00`, theirs before it, nor the guides' `QQ00`: `init` refuses all three; without a code the short guide's
   _Before go-live_ says set one or turn S-124 off) — and the chart's values, which render `niord.s124.enabled` and
   the producer code only when they set them (the chart has no default code since the opt-out, `be/k8s/README.md`
   § S-124 on or off), and its `be/bootstrap/` for a fresh deployment, which `init` writes with one domain, its time
   zone and its series: its import files and a `batch-set.json` that names each job and its file in the runner's job
   order, as the base product's `be/niord/bootstrap/` has them (`be/docs/country-api.md` § Bootstrap and release
   folders; `docs/code-data-config-plan.md` §5, §7); more bootstrap data, release folders and the country's own
   texts are the in-depth guide's. Every country replaces the PDF footer's logo: its PNG at
   `be/src/main/resources/META-INF/resources/img/company-logo.png`, listed in `be/overrides.json` with `basedOn`
   from `sha256sum .niord/kit/backend/img/company-logo.png` (the kit package carries the image).
9. **Run it:** `./start-local.sh` from the repository root (the kit README's *Running it locally*), the kit's
   `templates/start-local.sh`, which `init` writes and a country keeps byte for byte;
   `--sample` runs the base product's sample instead. What it does, in order: it reads the country code and
   name from `fe/niord-country.json`, the base product version from `be/pom.xml`'s `<parent>` by the stub's
   exported `parentVersion`, and the deployable from the pom's own `<artifactId>`; checks the prerequisites (a
   JDK 25 with `jar`, Node with npm, curl, a `read:packages` token in `~/.m2/settings.xml` under the server id
   `github` or `NIORD_LOCAL_BASE_REPO`, a scratch Maven repository with a local build of the pinned base, and
   Docker running last); fetches `org.niord:niord-local-run:<version>:zip` (`scripts/bootstrap.sh`,
   the launcher library `scripts/local-run-lib.sh`, the sample and `load-sample.sh`, the compose file and the
   local realm; `be/scripts/local-run-zip.sh`) and `org.niord:niord-bootstrap:<version>:zip` with
   `dependency:copy` into an absolute `.local-run/<version>/`; runs set-up (`node setup.mjs`, then `npm ci`
   when the lock is newer than the install) before the backend's build, which reads the packs' backend texts
   from `fe/.niord/packs/` (§4.8, the kit README's *The backend build*); starts the base product's services
   (PostgreSQL/PostGIS, Keycloak with the local realm, MailHog) through the library's `start_services`, which
   issues no compose command while the three run; creates the database in `niord-db` from `template_postgis`
   when it is missing (a plain `createdb` has no PostGIS) — `niord_<cc>`, or the one a committed
   `start-local.json` names, never the name the country's `application-local.yml` gives, since the launcher
   passes its own datasource and refuses an existing database without its mark; starts the backend with
   `spring-boot:run`, the `local` profile, UTC, port 8888, the development mode, `adminAppUrl`, and the Niord
   home `~/.niord-local/<database>` as `niord.home` and `NIORD_HOME`; at its bootstrap step, on every start, gives
   each active domain of the country's `be/bootstrap/domains.json` that has no client in the shared local realm its
   client (the four roles user, editor, admin and sysadmin, editor, admin and sysadmin composite, and
   the Sysadmin group's mapping; a client that exists is never changed, and nothing is
   deleted); on a database whose marker reads pending posts the bootstrap (`POST /rest/bootstrap` with a
   sysadmin's token, through `bootstrap.sh`, which waits for the marker): first the base product's archive, then
   the country's, `be/bootstrap/` zipped with that folder as the archive's root, in one run
   (`docs/code-data-config-plan.md` §7.2); and then starts `start:admin` and `start:info` on 4200 and 4400, whose dev
   proxy (`fe/proxy.conf.mjs`) sends `/rest` to the port `NIORD_BE_PORT` names. The backend has no CORS, so the dev
   server reaches a backend only through this proxy. `--sample` posts the base archive alone on `niord_sample_<cc>`
   and loads the sample, with the country's settings and texts (§3.4). It worked when the
   launcher's closing lines list the addresses, the logins and the three logs; `sysadmin` / `sysadmin` sees the
   Sysadmin menu with the domain chosen; `/rest/settings/web` gives the answered languages and `/rest/domains/all` the
   domain; and the public site shows times in the answered zone. The short guide's _Init, set-up and the launcher_ and
   _How to see it worked_ tell this step for a person, and the in-depth guide's _Running locally in detail_ the rest,
   with the rule that the backend's JVM runs in UTC however it is started.
10. **Push:** `init` writes `.github/workflows/ci.yml` (the backend's tests and the frontend's, green
    on the first pull request) and `image.yml` with `MODULE` filled. The pipeline runs `setup.mjs`, the checks,
    the country's specs and lint, builds the bundle into the country image and the info image, and its push job
    (§4.11 rule 1) pushes both to Harbor: the info image as `init` writes the job, and the administration bundle's
    image by a step the country writes in place of the job's last step, a placeholder that fails until then
    (`niord-dk`'s `image.yml` is the model: a `backend` job that packages, runs the override check and splits the jar,
    and a push step that runs `publish-images.sh` over the verified artifacts). Before the first push: the
    repository's own push robot under the Harbor project (`devops-shared` `runbooks/bootstrap.md`) as
    `HARBOR_ROBOT_USER` / `HARBOR_ROBOT_PASSWORD` on its `harbor` environment, restricted to `main`; the registry and
    project as `image.yml`'s `REGISTRY` and `HARBOR_PROJECT` (§4.8); and the image repositories `niord/niord-app-<cc>`
    and `niord-<cc>-info`, with tag immutability asked of the host (§4.11 rule 1). Until `REGISTRY` and
    `HARBOR_PROJECT` are set the frontend job fails with a message; with them and without the robot the frontend job
    is green and the push fails at the Harbor login; and the push job stays red at its placeholder until the country
    has written its administration-bundle step.
11. **Deploy** (`be/docs/cicd/design.md` §6, §8): in `devops-niord`, the namespace `niord-<cc>-staging` (one per
    environment and country) with its values — `global.imageRegistry`, `app.image.repository` set to
    `niord/niord-app-<cc>` and `app.image.tag`, `keycloak.mode: production` with `keycloak.existingSecret`
    (`be/k8s/README.md`, *Keycloak in production mode*) — and its sealed secrets. The realm the chart derives
    has the `niord-client-master` client with the sysadmin role, the bootstrap command's precondition, and the
    client of the bootstrap's first active domain with its sysadmin role, since the administration app grants a
    role only from the client of the domain chosen in its menu (without it the first sysadmin never reaches the
    Sysadmin area, where *Create in Keycloak* makes the later clients); an operator runs the command once after
    install with the base product's archive and the country's
    (`docs/code-data-config-plan.md` §7.2). Step 10's info image runs as the base chart's `info` component, on a
    host and an Ingress of its own (`be/k8s/README.md`).

A Faroese deploy (`fo` and `da`) is the same list with `fo` as its home pack and `da` pinned from DK; it
follows DK's pace for Danish (a base product version with new keys needs DK's matching pack, or hatch entries).
`fe/docs/country-deployments.md` holds the base product's side, with pointers to the kit
README and this section. The guides are the kit's, in `country-kit/docs/`: `country-guide.md`, the short
standard path (Quick start, Make it yours, Deploy, the definition of done and Before go-live);
`country-guide-in-depth.md`, the reference behind it; `set-up-prompt.md`, the prompt a person gives an AI agent to
do the Quick start; and `operators-guide.md` for the data an operator sets up in the running system.

**Proven by runs from an empty folder.** Steps 1-9 were run by this list and the kit README alone for a
fictitious country (`en`, `da` pinned from GHCR, a new home pack as a content language; 2026-10-25), and steps
1, 2 and 9 by the short guide alone and by the set-up prompt alone (2026-11-03). The gaps they found are
corrected in the steps above, the kit README, the guides and the prompt. Steps 10-11 wait for the deploy step.

---

## 8. Risks and fallbacks

| Risk | Impact | Fallback |
|---|---|---|
| The real applications do not build from an unpacked base product (TypeScript includes, the test builder, aliases, Tailwind, a root file the kit missed) | a country cannot build | the example country finds it first, in the base product CI, before a version is published. |
| A country-only Tailwind utility missing | silent styling loss | the utility check (§4.8). |
| DK has not translated a base product version's new keys | DK: its bump is red until it translates them in that PR (§4.12); GL and any other user of `da`: no complete Danish for that version until DK's pack is out | by design (R4): DK translates in its bump PR; GL waits for DK's pack, lists the keys in its escape hatch, or writes the Danish as text overrides (§4.6 step 3). |
| GHCR or the home's own `pack.yml` is unavailable | a home cannot publish its pack | the home keeps building against its in-tree pack (home packs never need the registry); the path is proven by DK's first publish (2026-10-24). |
| Actions minutes | the base product CI runs three country builds on a change to what the zip carries — the example, the untouched `init` and the upgrade test (§4.8), each of both applications, the example and the untouched `init` also their info images — as steps of one `Example country` job: one checkout, Maven restore and zip install; its timeout is 45 min, which leaves room for the generated country's backend (`--backend`, about ten minutes, §4.8). Before the job built that backend it took about 6 to 9 min on CI (measured 2026-10-25) | the base product builds no country variant; a country builds on a change or a pin bump, not on every base product publish. |
| A writer of a private country repository obtains its Harbor credential (the `harbor` environment's `main`-only rule missing, or lost at a change of owner or plan) | a pushed image not built from `main` | the rule: the `harbor` environments of `niord-dk` and `niord-gl` allow deployments from `main` only, set and proven by a refused dispatch from another branch, as the base product's does (owner, 2026-10-21; §4.11 rule 1). Not yet: on 2026-10-26 neither country's `harbor` environment had a deployment-branch policy; until the rule is set and proven at the deploy step, on the owner's Harbor go, the countries' Image workflows are disabled, so nothing pushes to Harbor. Left once the rule holds is the security review's caveat, a recheck after any change of owner or plan; named writers only, Harbor tag immutability, the build/push split (§4.11). |
| The sample reaches a deployment | fake warnings shown to mariners | the sample is never in a jar or image (§3.4, P9), checked in CI: `check-no-sample.sh` over the jars in Backend CI, over the jars and the image's inputs in `publish.yml` before anything is deployed or pushed. |
| A home publishes a bad or compromised pack | wrong text or a malicious link in every consumer | digest pins, no automerge with a printed diff, the markup rule, the withdrawn marker on the pack's GHCR package (§4.5, §4.11). |
| The administration app's policy blocks something a deployment loads (a tile host it cannot parse, an image linked from another site, a new third-party source) | a blank tile layer or image, console errors | the e2e run under the policy; the policy follows the tile settings at runtime and logs a warning for a value it cannot admit; another source is a base product change with its reason (§4.11 rule 5). |
| The base product guard's word list too strict or too loose | false alarms or a miss | an allow-list with a reason per entry, reviewed like the escape hatch (`country-kit/country-words.json`, over the zip and, in tree mode, over the whole of `fe/` and `country-kit/`, where an unused entry fails); its backend half is niord-base's `CountryWordsTest` with its own list (§4.8). |
| The OFL's Reserved Font Name | — | fontsource's files used unmodified, with `OFL.txt`; no subsetting of ours. |

---

## 9. Open checks

Facts the implementation had to establish. They are settled; what is not proven is named.

- **The lockfile.** `npm ci` accepts the base product's lockfile at the root of a country workspace whose package
  name differs, both with the root name rewritten to `niord-xx-fe` and without the rewrite. The generator
  rewrites it anyway, so the
  lockfile names the workspace it belongs to.
- **Tailwind's `@source`** in the generated wrapper, resolved against the wrapper, reaches the country
  templates: the wrapper in
  `.niord/generated/info/styles.css` reaches them — the example's
  `sm:grid-cols-[3fr_2fr]` is in its built public-site CSS and in no base product CSS — and the imported
  base entry's own relative `@source` lines still resolve, because `@tailwindcss/postcss` 4.2.4 resolves an
  imported stylesheet's `@source` against the file it is written in. The example's utility check proves it on
  every run (§4.8).
- **The bump bot.** No Renovate is installed: the bump is the Niord base bump app's pull request, opened or
  moved at every publish, with each country's Base bump workflow (§4.11 rule 6, §4.12; the owner, 2026-11-03).
  The first app bumps ran end to end on 2026-11-03 at 4.0.122, in both countries.
- **Public packs on GHCR.** The SIFA-DK organisation allows public packages on GHCR, and a public OCI artifact
  of a private repository is pulled anonymously. The publish side is
  settled by DK's first `Pack` run (2026-10-24): its validate job (with `latest-catalog` against GitHub Packages)
  and its publish job ran on a real runner, and it published
  `ghcr.io/sifa-dk/niord-lang-da:1@sha256:ded8424ce29393e6ae0e3bcb27bae99db2eaf384361bf6718d1865eacab17efd`
  after the empty `:withdrawn` marker; the owner made the package public, and an anonymous pull answers 200 for
  both tags. The pull side is settled by GL's first pin (2026-10-24): `node setup.mjs --update-pins da=1` pulled
  the public pack anonymously and verified its manifest and archive by digest. GHCR accepts the manifest shape
  (a custom config media type, no `artifactType`; §4.5). Not exercised against GHCR itself: the tags list's
  pagination (tested against a paging fake).
- **The country backends' resources.** The `niord-dk` and `niord-gl` backends' resources against `niord-base`
  (2026-10-20: 70 in DK, 46 of them shadows; 131 in GL, 75 shadows) are classified in
  `docs/code-data-config-plan.md` §9, and both countries moved theirs on 2026-10-22: DK its 56 templates, 42
  shadows and 14 own, unchanged; GL dropped 22 (19 equal to the base's, 3 lagging base fixes) and moved 86, 46
  shadows and 40 own, unchanged but for one line of `fetch-firing-exercises.js` (that plan's §9.1, §9.2).

---

## The step names the code and the documents use

Comments, tests and documents name the steps by which the split was built ("since P7", "from P8a"). The record
of those steps is not in this document. This table says what each name stands for, and where its rules are now.

| Name | When | What it stands for | Its rules are in |
|---|---|---|---|
| P1 (P1a, P1b) | 2026-10-21 | the contracts in the base product, while it still held the countries: branding and legal texts in both applications, the language configuration, the administration app's Content-Security-Policy, the maintainer list and the release environments | §4.4, §4.11 |
| P2 (P2a, P2b) | 2026-10-21 | language packs: the pack layout and the status files, the staging step, the pack check | §4.5, §4.6 |
| P3 (P3a, P3b1, P3b2, P3c1, P3c2) | 2026-10-21 and 22 | the country kit and the example country: set-up and the generated workspace (P3a), the extension points and the country API (P3b1, P3b2), the checks on every country build and the info image (P3c1), the acceptance and upgrade tests, a country's end-to-end specs and the upgrade report (P3c2) | §3.2, §4.3, §4.8, §4.9, §4.12 |
| P4 | 2026-10-22 | the source zip `niord-fe-src`, published with every version, and the release that records its hash | §3.1, §4.11 rules 2 and 6 |
| P5 (P5b, P5d, P5e, P5k) | 2026-10-22 to 24 | DK moved into `niord-dk`: its frontend (P5b), its bootstrap's domain ids (P5d), its bump onto 4.0.87 (P5e). P5k is the kit's half, in 4.0.87: published packs, their pins and the withdrawn marker, a country's own URL allow-list | §4.1, §4.2, §4.5, §4.11 |
| P6 (P6b, P6e) | 2026-10-22 to 24 | GL moved into `niord-gl`, with its theme and its font: its frontend (P6b), its bump onto 4.0.87 (P6e) | §4.7 |
| P7 (P7a to P7h, P7-be, P7-bs) | 2026-10-24 and 25, 4.0.97 | the base product let go of the countries: no country folder, pack or configuration in it, the word guards over the whole tree, audio promulgation in the base product (P7c, P7-be), the first bootstrap's rule (P7-bs, `docs/code-data-config-plan.md` §7.2), the run of §5 from an empty folder (P7h). D7 to D34 are decisions taken in it | §4.8, §4.11 rule 6, §5 |
| P8, P8a, P8b | P8a: 2026-10-25 to 2026-11-01 | the guidelines for adding a country. P8a: the guides in `country-kit/docs/`, the country launcher and `niord-local-run`, the sample on any country's backend; NG-1 to NG-14 are its steps, and D23 is its decision on the content rules of branding. P8b is its deploy part, not started | §3.4, §4.6, §5 |
| P9 | 2026-10-23, 4.0.89 | the sample deployment | §3.4 |
| the kit move, KM-1 to KM-9 | 2026-11-02, 4.0.109 | the country kit as a package of its own, `niord-country-kit`, at the repository root | §3.1, §4.8 |
| the Renovate step, RN-1 to RN-7 | 2026-11-02, 4.0.111 | the Base bump workflow and "green means no task". The Renovate configuration it also built was never installed | §4.11 rule 6, §4.12 |
| MAIN, DK-1, GL-1 | 2026-11-02 | the countries' single bump onto 4.0.111 | §4.12 |
| NG-B (NG-B1 to NG-B4) | 2026-11-02, 4.0.114 | the backend's transitional root-bundle text layer removed, and the last of the kit's layout inside `fe/` | `docs/code-data-config-plan.md` §4.1 |
| N2 (N2-1 to N2-8, N2-R) | 2026-11-03 | the plug-and-play country: `init` writes a complete country, the backend included; the short guide, the guide in depth and the set-up prompt | §4.8, §5 |
| the bump by push, F8 (F8-1 to F8-6) | 2026-11-03 and 04, 4.0.120 to 4.0.122 | the Niord base bump app, which opens or moves each country's bump pull request at every publish | §4.11 rule 6, §4.12 |
| R1 to R14 | 2026-10-19 to 21 | the owner's decisions | §2 |
