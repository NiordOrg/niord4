# Frontend country split: a country-unaware base product, country repositories, language packs

**Status:** decided, 2026-10-19 to 2026-10-21. Built (§6, _Record_): P1 — 2026-10-21, #163 (P1a) and #165 (P1b);
P2a — 2026-10-21, #167; P2b — 2026-10-21, #169; P3a — 2026-10-21, #171; P3b1 — 2026-10-22, #173; P3b2 —
2026-10-22, #175; P3c1 — 2026-10-22, #177; P3c2 — 2026-10-22, #180, which completes P3; P4 — 2026-10-22, #182; the three reviews of PR A (#184) answered on 2026-10-22 in #186 (§6 P4,
_Review of PR #184_); the version that first carries each comes with PR A, and the first published `niord-fe-src` is PR A's. P5's and P6's backend parts are built — 2026-10-22, on 4.0.76, `SIFA-DK/niord-dk` #2 and `SIFA-DK/niord-gl` #2 (`docs/code-data-config-plan.md` §11 C6), with C6's base product half in #188 (the same-run rule, the base bootstrap English only). P5's kit half (P5k) — 2026-10-23, #194 into `feature/p5-kit`; #190, with its two reviews answered on 2026-10-23 (§6 P5, _P5k_, _Review of #190_); 4.0.87 first carries it. P5's and P6's frontend parts are built on the countries' feature branches — 2026-10-22/23, on 4.0.81: P5b, `SIFA-DK/niord-dk` #5 into `feature/p5-dk-moves`, and P6b, `SIFA-DK/niord-gl` #4 into `feature/p6-gl-moves`, each merged with a merge commit to keep the moved history (§6 P5, P6). DK's bump to 4.0.87 (P5e) — 2026-10-23, `niord-dk` #6; DK's PR into its `main`, `niord-dk` #7, was merged by the owner with a merge commit on 2026-10-24 (`d6fa491`), and its `Pack` run #1 published `niord-lang-da:1`, which the owner made public (§9); DK accepts the `zod` advisory GHSA-mrhx-c7xc-m3w5 temporarily (`niord-dk` #9, the owner, 2026-10-24) until its bump to a base whose lock resolves `zod` 4.6.6 or later, that is 4.0.91 or later (§6 P5, _DK's next pin bumps after P5e_). GL's bump to 4.0.87 (P6e) — 2026-10-24, `niord-gl` #7 into `feature/p6-gl-moves`, squashed as `0104daf`, with `da` pinned from GHCR and the same temporary `zod` acceptance until GL's bump to 4.0.91 or later; GL's PR into its `main`, `niord-gl` #8, was merged by the owner with a merge commit on 2026-10-24 (`e8662062`), its CI and the merge's `Pack` run green and nothing published (§6 P6). `kl` is GL's home pack only, never published (the owner, 2026-10-23; `niord-gl` #5). P9's base product part is built — 2026-10-23, #192, merged into `main` 2026-10-23 (`195d102d`, 4.0.89), with `docs/code-data-config-plan.md` C7: the sample, `./start-local.sh --sample`, the regeneration and the no-sample check; the reviews of #192 answered on 2026-10-23 (§6 P9). The country launcher is P8's (the owner's ruling of 2026-10-23): the kit's launcher template and DK's and GL's launchers. The integration suite's fixture is regenerated from the base product's bootstrap and the sample — 2026-10-23, `docs/code-data-config-plan.md` C4b (§11), on `c4b/it-fixture`, regenerated again on #192's sample after its merge, in PR #196 (`feature/c4b-it-fixture` into `main`): `./verify-local` passed on `f15c4de2` in 31m47s (all four parts), CI on #196 is green on `9bb87e5d` (all 12 checks), and its two reviews were answered on 2026-10-24 on `c4b/review-196-fixes`; staging has nothing of it. Audio promulgation joins the base product in P7 (the owner, 2026-10-24; §6 P7). P7 — 2026-10-24 to 2026-10-25, as the sub-PRs #203 (P7a), #205 (P7b), #206 (P7-be), #207 (P7c), #208 (P7d), #209 (P7e), #210 (P7-bs), #211 (P7f), #212 (P7g) and #213 (P7h) into `feature/p7-base-lets-go`, and PR #204 into `main` (§6 P7, _As built_); the owner merged #204 on 2026-10-25 as a squash (`c703d061`), published as 4.0.97 (the release `v4.0.97`, zip sha256 `d335d766…ecbca3e`); DK's bump onto it, `niord-dk` #10 (merged as `9f09d195`, its `Pack` run publishing `niord-lang-da:2`), and GL's, `niord-gl` #9 (merged as `555b7c73`, pinning `da:2`), both 2026-10-25 (§6 P5, P6); the owner's rulings of 2026-10-25 on Renovate (§4.12) and the JVM's zone (TZ1, `be/docs/potential-errors.md`). P8a — the sub-PRs #216-#223 and NG-13 into `feature/p8a-guides` (§6 P8, _As built (P8a)_) and its PR into `main`, #229, merged by the owner on 2026-11-01 as `5e1d3ec6`; P8's deploy parts are P8b, at the deploy step. The kit move, the step after P8a — KM-1 to KM-9 into `feature/kit-move` and #237 into `main`, merged on 2026-11-02 as `72697c7f` and published as 4.0.109, the first release with `niord-country-kit` beside `niord-fe-src` (§6 P8, _The kit move_). The Renovate step — RN-1 to RN-5 into `feature/renovate` and #249 into `main`, merged on 2026-11-02 as `a3c2f730` and published as 4.0.111 (§6 P8, _The Renovate step_). DK's and GL's single bump onto 4.0.111, DK-1 and GL-1 — `niord-dk` #11 (`b6a7120c`) and `niord-gl` #10 (`ffca4eb8`), both merged by the owner on 2026-11-02, DK's `Pack` run then publishing `niord-lang-da:3` (§6 P8, _MAIN, DK-1 and GL-1 as built_). NG-B — the backend's transitional root-bundle text layer and the kit move's tombstone and in-base layout removed, with the kit's report fixes — NG-B1 (#252), NG-B2 (#254), NG-B3 (#255) and NG-B4 (#256) into `feature/ng-b` and #253 into `main`, merged on 2026-11-02 as `2685be0e` and published as 4.0.114 (§6 P8, _NG-B as built_). N2, the plug-and-play country — `init` writes a complete, green, runnable country from a handful of answers, the backend included; the country guide is the short standard path beside a guide in depth and a set-up prompt — 2026-11-02 to 2026-11-03, N2-1 to N2-8 (#258, #263, #264, #262, #261, #260, #265, #266) and the route fixes N2-R (#268) into `feature/n2-plug-and-play`, its PR into `main` #259, merged by the owner on 2026-11-03 as `dc3b1d86` (§6 P8, _N2 as built_). The bump by push (F8, decision 133) — F8-1 to F8-5 (#272, #273, #270, #274, #276) into `feature/base-bump-app` and #271 into `main`, merged by the owner on 2026-11-03 as `9677f31f` and published as 4.0.120 to 4.0.122; the first app bump ran end to end the same day, `niord-dk` #15 and `niord-gl` #13 opened by the app and merged by the owner (§6 P8, _The bump by push_, _As built_). The decisions in §2 were taken by the owner
from 2026-10-19 on, after an analysis of the tree and of all six production builds (§10); the plan was then
checked against the tree and the installed Angular builder by three independent reviews (a fact check, a
new-country walkthrough, a design critique) and revised. On 2026-10-20 its backend parts were aligned with
`docs/code-data-config-plan.md` (code, data and configuration), which owns them; P5 and P6 then gained a
frontend part and a backend part. **The owner adopted that plan on 2026-10-21 (R14): this plan follows it, and §2.1 keeps the path without
it as the record of the alternative weighed.**
**Scope:** the country parts of both frontend applications — the administration app (`niord-app`) and
the public site (`niord-info`): their code seams, environments, assets, styles, translations and build
configurations; how a country repository builds its frontend from a published base product version; language
packs; the new GL theme and its font. This is Phase 8 of `be/docs/cicd/implementation-plan.md` (the
frontend chain), redrawn: its npm package becomes a Maven source zip.
**Repos:** `niord-ng` (the base product: the kit, the pack format, publishing, the clean-up), `SIFA-DK/niord-dk`,
`SIFA-DK/niord-gl` (their `fe/`, their pipelines). Not in scope: the backend's texts, settings, templates,
bootstrap and seed data — `docs/code-data-config-plan.md` owns them (R14); this plan names where the two meet (the
packs' backend half §4.5, the backend in §4.9–§4.11, the backend parts of P5 and P6, P9).

---

## 1. Summary

**Terms.** *Base product*: the country-unaware Niord in `niord-ng` that every country builds on. *Country*: a
country repository (`niord-dk`, `niord-gl`, …) and the deployments built from it.

**Goal.** The base product repository knows no country and no language but English. Each country repository
holds everything its deploy needs and builds its own administration bundle and public-site image from a
pinned base product version. No build carries a resource it does not need. Adding a country is one command,
a manifest and the files where the country differs — never a change to the base product.

```
 niord-ng (base product) ──── publishes per 4.0.<run> ──▶ org.niord.web:niord-fe-src:4.0.<run>:zip
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

| | Today | After |
|---|---|---|
| A country's frontend | in `niord-ng`: two `country-specific/<cc>/` folders, `public/assets/<cc>/`, `styles/tokens.<cc>.css`, 11 `angular.json` configurations (7 admin, 4 info) | the country repository's `fe/`, one manifest |
| Who builds a country bundle | `niord-ng`'s `publish.yml` (`niord-web-admin:<v>:zip:<cc>`) | the country pipeline, from `niord-fe-src:<v>` |
| Translations | `da.json`, `kl.json` in the base product; Danish written in the feature's PR | `en` in the base product; `da` in `niord-dk`, `kl` in `niord-gl` — packs; published packs, each with its own version |
| A key the base product adds | Danish written with it | the country build fails until every pack it uses has it, or the key is in its escape hatch (§4.6) |
| Language lists and names | fixed in shared code (`UI_BUNDLE_LANGUAGES`, `LANGUAGE_<XX>` keys) | data: the manifest and each pack's `pack.json` |
| Country wording, legal texts, logos | partly base product code and base product keys (the administration app's DEMA footer and dialogs) | the country's branding, in both applications, against a base-product-owned branding contract |
| Environments | the administration app's development/test files point at DK's test environment | neutral in the base product; a country's own are a seam |
| A build's contents | leaks (§10) | the base product core, the staged packs and one country — checked on every build |
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
| **R11** | **A one-command sample deployment, with Danish sample data, in the base product** (P9). Niord is a Danish project and its authors are Danish: the sample is DK's development base data, as the seed was (`be/niord/seed/README.md`, deleted with its folder in the code/data/configuration plan's C4b, its content in the sample), plus sample messages, layered on the base product's bootstrap (`docs/code-data-config-plan.md` §7–§8). It lives in the base product because it is captured from the entities and checked against the schema — it must be versioned with the schema. R1 is about the frontend; the sample is content. | A fictitious neutral region (no gain: everyone knows the project's origin); sample data in a separate repository (it would break on the first entity change). |
| **R12** | **A country may own extra npm dependencies, additively and checked** (§3.2). It lists them in its own npm manifest `fe/country/package.json` with a lockfile; `setup.mjs` merges them on top of the base product's lockfile. A country package that the base product already has at another version, or whose peer ranges reject the base product's framework versions, fails. The base product keeps control of the framework. | Base product dependencies only (a country integration waits for a base product release, and country-only packages land in the base product). |
| **R13** | **Each home publishes its own packs** — three separate businesses (owner, 2026-10-19; replaces a central workflow of the same day) (§4.5). A home's `pack.yml` publishes its pack with the repository's own workflow token as a **public** OCI artifact, `ghcr.io/<owner>/niord-lang-<lang>` under the home's own GitHub owner (`sifa-dk` for DK and GL); a consumer pulls it anonymously, pinned by that full reference and its content digest. The base product takes no part, and nobody holds anyone else's credential. A pack's name names its home's organisation, never its home repository: moving a pack to another repository of the same organisation later (e.g. a shared one) is a history move and a publish permission with no consumer change; a move to another organisation is a rename and a pin change in every consumer. | A central publish workflow in the base product (the base product becomes a party to every pack release; push needs a home credential that can start *any* base product workflow, `retract-version.yml` included — the first high finding of the plan's security review, §4.11; pull needs a read key into private repositories); the base product's token in every home. |
| **R9** | **No resource in a build that the build does not need, and one source per output file.** A country never "overrides" by writing an output path another source also writes: `ng build` copies assets concurrently and the winner is not defined (`@angular/build` 22.2 `utils/resolve-assets.js:22-41`, `tools/esbuild/utils.js:169-180`). Both are checked on every build. | — |
| **R14** | **Code, data and configuration (`docs/code-data-config-plan.md`), adopted (owner, 2026-10-21).** §2.1 records the path without it, weighed before adoption. A repository change never changes or deletes what a running deployment has in its database; it only adds inert keys a deployment never had (texts, settings), and a key an operator deleted stays deleted. Every template, script and report is data (one rule: what an operator can see and edit is data), reaching a deployment by the bootstrap of a fresh database or a sysadmin's import. Both Reload functions are deleted; there is no reset to default. A country's settings file replaces the base product's whole file (2026-10-20). That plan's base product part goes first: its C0 and C1 before P1, its C2–C5b before the backend parts of P5 and P6. | Read-only defaults with database overrides and a one-time transition of existing deployments (a tool judging, per production row, what is an edit). |

**Registries (follow from R3, R6 and R13, 2026-10-19).** The **source zip** is published to the GitHub
Packages Maven registry of the public `NiordOrg/niord`, as `niord-base` is. **Packs** are not: GitHub's Maven
registry is repository-scoped with no per-package read grant (`be/docs/cicd/design.md:95`), so a pack in a
private country repository's own Maven registry could be read by no other country — whereas a GHCR
package belongs to the organisation and can be made public while its repository is private, and a public
GHCR package is readable without any token (both checked in P5 before the first publish, §9; if either fails, packs wait for the repositories to go public and DK and GL build from in-tree copies until then). So each home publishes its packs there itself (R13).
**`NiordOrg/niord` is the delivery address of the base product for now (owner, 2026-10-19)** — the way the backend has published
since #133, because a private repository's packages are readable by no other repository. It is not
necessarily the base product's future home: the repository reshuffle before production may choose a new one, and
moving is three edits — `github.repository` in the base product's `be/pom.xml`, the machine user's tokens
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
listed, the upgrade report and the guidelines are the same on both paths. C1 of that plan (PR #160) removed
both Reload functions on the adopted path.

| Part | With R14 | Without R14 |
|---|---|---|
| A country backend's resources that replace `niord-base`'s (DK 46, GL 75; §4.9) | sorted into texts, a settings file and bootstrap data; only images stay guarded | **stay where they are, guarded**: listed in `be/overrides.json` with the `basedOn` fingerprint of the pinned `niord-base` resource; a check published in `niord-image-tools` fails the country build when the base product changes one |
| Backend texts (§4.5, §4.10) | the language packs' backend half, layered key by key | **as today**: DK and GL keep their own `*_<lang>.properties`; the packs are frontend only; a base product key in a file a country replaces still does not reach that country |
| Settings (§4.1, §4.11 rule 3, §5 step 8) | the country settings file, checked against the settings catalog; added only at start-up | **as today**: the country's `niord.json` replaces the base product's; no catalog check |
| Templates, scripts and reports | data: bootstrap or a sysadmin's import; no reload | **as today**: copied into the database on first use; "Reload from system" stays |
| The country repository (§4.2, §5 step 1) | `defaults/country/`, `be/bootstrap/` with `be/bootstrap/releases/<yyyy-mm-dd>-<topic>/` | the backend as DK and GL have it today |
| The admin app's dictionary merge (§4.10 layer 5) | rows the system added and nobody changed stop masking the app's texts | **as today**: those rows keep masking the newer texts |
| P5 and P6 (§6) | the backend parts wait for that plan's C2–C5b and only consume its C5b (the security test group, the start-up guard, the override check, `be/docs/country-api.md`) | **one piece each**, after a base product step that publishes the security test group, the start-up guard, the override check and the FreeMarker class resolver; the backend part adds `be/overrides.json` for the replaced resources |
| P9 (the sample) | layered on the base product's bootstrap, with its own DK rows in `be/niord/sample/` (domains, series, `Audio` until P7 and from P7 a restatement of the base product's type, its values equal to the base's and its domain link added, D7, the domain links, four scripts); nothing fetched from `niord-dk` | **its own dataset** in `be/niord/sample/`, loaded by the launcher into `niord_sample` only; P9's safety rules apply unchanged |
| The order of work (§6) | that plan's C0 and C1 before P1, C2–C5b before the backend parts of P5/P6 | that base product step before the backend parts of P5/P6 |

The plan was adopted before P1, so no country backend takes the "without" path.

---

## 3. The model

### 3.1 Three kinds of artifact

**The base product is one release made of several artifacts, all with the same version `4.0.<run>`:** the
backend's `niord-parent` and `niord-base`, the base product's bootstrap archive `niord-bootstrap` (zip),
`niord-image-tools` and, from P8a (NG-9), `niord-local-run` (zip: the files of a local run, the sample among
them; never an image input, §6 P9's safety rules), and, from P4, the frontend's `niord-fe-src`
(and the prebuilt `niord-web-admin` bundle: versions before P7's carried DK's and GL's too, and from P7's version, 4.0.97,
it is `en` only, §6 P7 D11). A country pins that one
version, its pom's `<parent>`; `setup.mjs` reads the same number, so a country's backend and frontend always
come from the same base product release. The table lists the frontend's artifact; the backend's are unchanged.

| Artifact | Home (source + workflow) | Version | Coordinates (registry: `NiordOrg/niord`) | Used by |
|---|---|---|---|---|
| **The base product frontend** — both applications' sources, the `en` pack, the key catalog, the country kit, the info container files | `niord-ng` `fe/` | `4.0.<run>`, the backend's | `org.niord.web:niord-fe-src:4.0.<run>:zip` | every country |
| **A language pack** — one language's base product text for both applications and the backend, its name, locale and flag | its home country (`en`: the base product, inside the zip) | the home's pack workflow run `<n>`, as an OCI tag, for a published pack (an unpublished home pack has none); consumers pin the digest | `ghcr.io/<owner>/niord-lang-<lang>:<n>`, `<owner>` the home's GitHub owner (`sifa-dk` for DK and GL; public GHCR, not the Maven registry) | every country naming it |
| **A country's deployables** — the administration bundle, the public-site image | the country | the country's | the image `niord/niord-app-<cc>` (bundle inside), `niord-<cc>-info` | its deploys |

A country's pom has the base product version once, as its `<parent>`. Each **foreign** pack is pinned in
`fe/niord-pins.lock.json` by its full reference, tag and **digest** (`ghcr.io/sifa-dk/niord-lang-da:12@sha256:…`); a digest names exact
content and cannot be re-pointed. The base bump moves the parent and the pinned packs, never automerged: the
Niord base bump app opens or moves each country's bump PR minutes after every publish (decision 133, the owner,
2026-11-03; §6 _The bump by push_), and a small workflow in each country, the Base bump workflow,
`.github/workflows/base-bump.yml` (built 2026-11-02 in §6 _The Renovate step_), re-pins the lock on the
`base-bump/` branch once release `v<version>`'s notes confirm the hashes and moves each pinned pack to its home's
newest pack in the same PR (§4.12); it is the one bump, and Dependabot opens security updates only (§4.2). The
cadence before it was Renovate's, weekly with security bumps at once (the owner, 2026-10-25; the Dashboard
approval dropped by the owner's ruling of 2026-11-01), superseded on 2026-11-03. Home packs are in the country's tree; `en` comes with the base product.

**The country kit's own package. Decided 2026-10-26 (the owner):** the country kit, its guides,
`API-CHANGES.md`, the backend contract `country-api.md` and the templates move out of `niord-fe-src` into a
published package of their own, for example `org.niord:niord-country-kit` of the same base product version. A
country's lock pins it by its own sha256, and it is unpacked at the country repository's root; `niord-fe-src`
keeps the frontend sources only. The move is its own step after #229 (its own feature branch, review and route
re-run) and before DK's and GL's bump, so DK and GL bump once, onto the version that has both #229 and the move
(§6 P8, _The kit's own package_). Until then the kit is inside `niord-fe-src`, as the table says.

**Reading.** The base product zip: GitHub's Maven registry needs a token even for a public package; the country
pipeline reads with its own workflow token (as for `niord-base` today), a developer with a `read:packages`
token in `~/.m2/settings.xml`, a country in another GitHub organisation the same. Since P4 the zip is also an
asset of the GitHub release on `v4.0.<run>` in `niord-ng` (§4.11 rule 2), readable with a workflow token's
`contents: read` in that repository, which Frontend CI's upgrade test uses (§4.8); a country reads the
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
   come from a checkout instead — `NIORD_BASE=../niord-ng/fe`, `NIORD_PACK_DA=../niord-dk/fe/packs/da` —
   copied in (the builder accepts asset inputs only inside the workspace root, `resolve-assets.js:25-27`;
   with `NIORD_BASE` the catalog is built from the checkout, with exactly the zip's include list).
   These overrides are **refused when `CI` is set**, and a production build or `image:info` refuses a
   workspace staged from one (`guard-build.mjs`'s refusal, which `image:info` runs too, besides refusing
   `NIORD_BASE` or a `NIORD_PACK_<LANG>` in its own environment while `CI` is set); every workspace records its provenance (§4.11).
   **As built in P5k** (P5's kit half, §6 P5): the stub fetches and verifies the zip and hands over to the kit;
   the packs are the kit's (`setup-kit.mjs`), after the stub's hash check, so the stub did not change. For each
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
   **In the base product's own tree**, multi-country until P7 deleted `da` and `kl` (P7f, #211: since then
   `languages/` holds `en` only, and the step stages `en` alone), the step is
   `fe/country-kit/stage-languages.mjs` (in `fe/scripts/` until P3a moved it into the kit, §6 P3): it stages every in-tree pack per
   application into `fe/.niord/build/<app>/` — `en` byte for byte; every other bundle the application has
   stripped to the catalog's keys (a key `en` lacks is dropped and listed), a value whose placeholders differ from
   the English text's or that is empty where English is not staged as the English value (listed), and the keys it
   lacks filled from `en`, so no staged bundle carries a broken value even in a build that runs no check (a
   complete bundle with nothing stripped or replaced is still a byte copy); and the flag of every pack in both
   applications (GL's administration app showed `kl`'s as a content language) — and each `angular.json`
   configuration's ignore list on that one staged input picks its languages, held to its language configuration
   by `contents:check`. It stages per application (`--app admin|info`, which every npm build and serve script
   passes), so two dev servers started together never write the same folder. The base product's `dk`/`gl`
   builds (`build:dk`, `build:gl`, `build:info:dk`, `build:info:gl`) ran step 3 first: the pack check of exactly
   their configuration's languages (`fe/country-kit/pack.mjs check`), so a missing Danish or Kalaallisut key failed
   them until P5; from P5k they passed `--frozen da`, which turned every finding of the frozen `da` copy into a
   warning (a key it lacks is staged from `en`), while a missing Kalaallisut key still failed `build:info:gl` until
   P6. P7d (#208) removed every caller of those builds, and P7f (#211) deleted them with the frozen `da`, `kl` and
   `pack.mjs --frozen` (D14). Both the stripping and the check came with P2b; "exactly its `uiLanguages`" is the country
   workspace's case, built in P3a (`country-kit/lib/stage-country.mjs`, which reuses the staging step's
   functions), and since P3a `contents:check` holds every base product configuration to exactly its UI
   bundles too, `en` no longer required among them.
5. **Generate the workspace** in `fe/` (every generated file gitignored), from the kit's
   `workspace-files.json` so no root file the base product's build reads can be forgotten: `angular.json`
   (one project per application, keeping the names `niord-app` and `niord-info`; one standalone configuration per
   deployable, the base product's composition rule, `fe/country-kit/README.md`, _Set-up_ step 6 (until P7e, #209,
   also `fe/docs/country-deployments.md`'s _Build configurations_, which D9 dropped) —
   the base product's own non-country configurations of each application, none invented: `production`,
   `development`, `local` and `test` for the administration app, `production` and `local` for the public site);
   `package.json` (the base product's dependencies and the country's, its `prepare` hook removed, the kit's scripts `start:admin`,
   `start:info`, `build` — since P3c1 `guard-build.mjs`, the audit, `ng build niord-app`,
   `ng build niord-info --stats-json` and then `check-build.mjs`, the checks on every country build (§4.8) — `test`,
   `lint`, `audit`, from P3c1 `bundle:info` (the public site's bundle report against the country's
   `info/bundle-baseline.json`, which `-- --update-baseline` records) and `image:info`, and from P3c2 `e2e`,
   `playwright test --pass-with-no-tests`, so a country without specs passes; every
   script constant text); `package-lock.json` (the base product's with the country's entries), `.npmrc`, `.nvmrc`,
   `.postcssrc.json`; `tsconfig.json` with the aliases of §4.3; the Vitest and ESLint configurations over
   the country's files; `playwright.config.ts` (P3c2, constant text: the base product's `playwright.config.ts`,
   which the zip carries, spread with `testDir: './e2e'` and `tsconfig: './.niord/tsconfig.e2e.json'`, a generated
   file mapping `@niord/admin/e2e` to the base product's login fixtures, `ui-apps/admin/e2e/auth/fixtures.ts`, so a
   base product change to the suite's options reaches the country with the bump); `proxy.conf.mjs`; the generated
   language configuration, style entries and `fileReplacements` (§4.3, §4.8).
6. Then `npm ci` and `npm run build` (the pipeline) or `npm run start:admin` (a developer); `npm test` and
   `npm run lint` run the country's own specs and lint with the base product's rules; `npm run e2e` runs its Playwright specs (`e2e/`) against a running `start:admin` with the base product's login fixtures, which a spec imports as `@niord/admin/e2e` (`import { expect, test } from '@niord/admin/e2e'`) — locally, as the base product's suite runs today (`.github/workflows/fe-ci.yml:44`), with the Keycloak the runner names in `KEYCLOAK_URL`: since P3c2 the suite and its fixtures name no environment and have no default.

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
reason in `audit-accepted.json`, printed like the escape hatch. Since P8a's C2, `npm audit signatures` exiting
3221226505 (a Windows process crash) with no report is run once more with a fresh npm cache of its own, removed
afterwards; a second crash fails, as any answer that is no report does.

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
  these; each needs only the pack's name and flag. The lists replace `siteLanguages` / `modelLanguages`
  of today's language configurations and `UI_BUNDLE_LANGUAGES` (admin
  `core/i18n/language-settings.service.ts:7`, info `core/config/language-config.ts:7`).
- **`en`** completes every bundle but ships only where offered: from P3 staging fills every bundle's missing keys
  from it (§3.2 step 4), and a build ships exactly its `uiLanguages` — `en` only when they name it (R9). Neither
  application keeps a runtime fallback language; from P1 until P3a `en` shipped in every build as that fallback (§4.6).
- **`packs`** — `pinned`: a foreign pack, its full reference, tag and digest in `fe/niord-pins.lock.json` (§3.1, §4.11), under the GHCR owner the entry's optional `owner` names (default `sifa-dk`); `home`: in `fe/packs/<lang>/`; `publish`: whether
  `pack.yml` publishes it: a pack goes to GHCR only when more than one country is known to use it, and a
  country that wants another's pack agrees on it with the pack's owners first (§4.5; the owner, 2026-10-23).
  Promoting a pack later is flipping the flag. The example's `kl` (`publish: false`) is GL's, `niord-gl` #5. As built in P5k the lock's `packs` maps each pinned language to that reference string,
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
    .gitattributes                 text stored LF, the binary types never converted; written by init (P5k)
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
                                   with the human actions, sets niord/upgrade-actions (the Renovate step,
                                   2026-11-02, and the bump by push; written by init)
  .github/CODEOWNERS               names the owners of the override, escape-hatch and dependency files
```

### 4.3 The seams

A seam is a base product file with a neutral default that a country may replace. The catalogue is
`country-kit/seams.json`; `setup.mjs` adds a `fileReplacements` entry (or the `index` option) for every
seam whose conventional path exists in the country tree. A non-spec file in `admin/app/` or `info/app/`
that is neither a seam nor imported by one fails the check, and so does a file in `admin/overrides/` or
`info/overrides/` that is neither named by an `overrides` entry nor used by one; `*.spec.ts` files are the country's tests.

| App | Seam (base product default) | Country file | Used today by |
|---|---|---|---|
| admin | `country-specific/default/country.providers.ts` (`provideCountry()`, no providers; until P3b1 `promulgation.providers.ts`, §4.9) | `admin/app/country.providers.ts` + its components (the audio promulgation is its first hook) | DK, GL (the audio editor and viewer until P7, whose P7c, #207, moved them into the base product; each country deletes its copy at its bump onto P7's version) |
| admin | the country routes and menu: `country-specific/default/country.routes.ts` and `country.menu.ts` (none; §4.9) | `admin/app/country.routes.ts`, `admin/app/country.menu.ts` | — (from P3b1; the example) |
| info | the country routes and providers: `country-specific/default/country.routes.ts` and `country.providers.ts` (none; §4.9) | `info/app/country.routes.ts`, `info/app/country.providers.ts` | — (from P3b1; the example) |
| admin | `environments/environment.{development,local,test,production}.ts` (neutral: `environment.ts`, development and local reach the local stack — `/rest` through the dev proxy, Keycloak at `http://localhost:8080/auth`, where the docker-compose realm's issuer is fixed, `be/docker-compose.yml:41-45`; test and production `/rest` and `/auth` on the deployment's own origin; no WMS, a world view) | `admin/environments/environment.<name>.ts` | DK (its test backend, Keycloak, WMS, map centre; since P1 in `country-specific/dk/environments/`, swapped in by `dk`, `local-dk` and `test-dk`); GL takes the neutral files until `niord-gl` brings its own (P6); in production none yet: all three production configurations use the base product's `environment.production.ts` |
| info | `country-specific/default/info.config.ts` | `info/app/info.config.ts` + the footer component | DK, GL |
| info | `src/index.html` | `info/app/index.html` | DK, GL |
| both | the language configuration | — generated by the kit (P3) from the manifest and `pack.json`: labels, locales, flag paths, the lists, the default; in P1 each application's hand-kept `country-specific/<cc>/language.config.ts` | — |
| both | `styles/tokens.active.css` | — generated as the `angular.json` `styles` array, data and no CSS file: the generated style-entry wrapper, then `styles/tokens.<cc>.css` (the base product's `tokens.active.css` when the country has none), then `styles/fonts.<cc>.css` when it exists, so no generated stylesheet names the country (`tokens.base.css` stays imported by each application's `styles.css`; P3a) | — |

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
  may use (today: what the footer and audio components need), plus a specs-only entry each,
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
  Since P8a's C2 a country's end-to-end specs, `e2e/**/*.ts`, are linted too, with `niord-app` and in their own
  program (`.niord/tsconfig.e2e.json`): they import only `@niord/admin/e2e` and `@playwright/test`, each exactly,
  `node:` built-ins and files of `e2e/` (`niord-country/e2e-imports`), and carry no inline configuration.
- **Compatibility policy.** The API is a contract kept across base product versions: a change that breaks it is
  named in the PR and recorded in `country-kit/API-CHANGES.md` with what a country must do; a removed export
  is first deprecated (kept, its contract comment marked with a line
  `Deprecated since <version>: <what to use instead>`, or, once the version it goes in is decided,
  `Deprecated since <version>, removed in <version>: <what to use instead>` (P3c2; a line of another form, or a
  removal not after its start, fails set-up) — never a JSDoc `@deprecated` tag, which the base
  product's lint would turn into an error at once — and every use reported by `setup.mjs` as a warning naming
  the removal version or that it is not scheduled, and in the upgrade report, §4.12) for at
  least one base product version that both DK and GL have taken. Base product code never imports the API, so it stays a one-way facade. The example country compiles against the API in the base product CI, and the upgrade test
  (§4.8) builds it from the previous published version, so a break cannot pass unnoticed.
- **Tailwind.** Each application's style entry is generated as a wrapper: it `@import`s the base product's
  `src/styles.css` and adds `@source` for the country's `admin/app` and `admin/overrides` (or the `info/` pair), and it is `styles[0]`
  in place of the base product's entry (never both). Today's entries scan only the base product tree
  (`admin src/styles.css:12-14`, `info src/styles.css:19-23`), so without this a utility only a country
  template uses — GL's footer has `sm:grid-cols-[1fr_auto_1fr]` — would silently be missing. The wrapper's
  text is constant per application and lives in `fe/.niord/generated/<app>/styles.css`; its relative `@source`
  reaches the country's folder, and the imported base entry's own relative `@source` lines still resolve,
  because Tailwind resolves an imported stylesheet's `@source` against the file it is written in (§9, settled
  in P3a).
- **Removed in P1** as their own mechanisms, folded into the language configuration:
  `InfoConfig.languageFlags`, `AppConfig.languages` / `environment.languages` (admin `config.token.ts:14`),
  the `LANGUAGE_<XX>` keys and `languageNameKey()` (info `shared/utils/language-name.ts:9-10`). In P1 that
  configuration is the hand-kept one swapped per `angular.json` configuration, today's
  `country-specific/<cc>/language.config.ts` in each application: the administration app's already gives each
  language its `label`, `locale` and `flagPath`; the public site's gains them. The kit's generator (P3) produces
  it for a country workspace from the manifest and `pack.json`, where a language's name is `pack.json` `label`.

### 4.4 Branding and legal texts

- **The contract** is `country-kit/branding.schema.json`, one section per application: every brandable key
  (placeholders, required or optional) and every brandable file with its output path (logos, the public
  site's `favicon` and `apple-touch-icon`, the legal documents). The administration app's favicon
  (`public/favicon.ico`, the Niord icon) is not brandable: no country brands it, and making it brandable would
  move a base product file (P3a). A file a base product page links
  (both icons, `ui-apps/info/src/index.html:10-11`) is required, and the base product's own build stages its
  default copies (today `ui-apps/info/public/assets/default/branding/`), so no build links a file it lacks. Every brandable path is base-product-referenced and never shipped by the
  base product's own assets; the Niord mark (`assets/img/niord_white.png` in the header, `assets/img/niord.png` on the home page) is
  fixed by the base product, never brandable (GL's identical copy goes in P1); the base product's build shows only the mark where no
  logo is branded, and renders nothing for an optional key no branding sets. `init` writes a skeleton
  of what the schema requires — the required keys in each application's `en.json` and the required files
  as the base product's default copies — and leaves every optional entry out: an empty or placeholder
  value is not neutral, because branding is merged over the bundle and a blank `FOOTER_COPYRIGHT` would
  blank the `en` pack's text (P3a). In P3a no key is required, so the skeleton is the public site's two
  icons.
- **Both applications** merge `assets/branding/<lang>.json` over the language bundle at runtime (the
  public site does today, `niord-transloco-loader.ts:65-66`; the administration app gains it) and render
  legal documents from `assets/legal/<doc>.<lang>.html` (the public site does today; the administration
  app's footer dialogs, today the disclaimer's fixed paragraphs `p1`–`p5` (`p3`–`p5` name DEMA) and the cookies policy in base product keys,
  `core/main-shell/footer/footer.html:52-103`, become one document dialog).
- **What leaves the base product in P1:** the DEMA / Beredskabsstyrelsen footer link, copyright and dialog
  texts (`en.json` `main.footer.*`, `footer.copyright` / `footer.link` at `footer.html:15-17`, and their Danish), the DEMA logos
  (`assets/img/company-logo*.png`, read by `footer.ts:7,37`). DK gets them as branding; GL gets its own
  copies (today byte-identical to DK's) until it supplies its own. No new base product text is written.

### 4.5 Language packs

```
packs/da/                                  (the same shape in the base product: fe/languages/en/, without backend/ —
                                            niord-base carries the English backend text itself)
  pack.json        { "lang": "da", "label": "Dansk", "locale": "da-DK", "flag": "img/flags/da.png" }
  i18n/admin/da.json     the administration app's text  ─┐ named by their output paths, so the
  i18n/info/da.json      the public site's text          │ staged copy keeps the names the loaders
  img/flags/da.png                                       ─┘ and the service worker know today
  img/flags/da.svg the flag's drawing, from which the PNG is rendered — never shipped
  backend/<bundle>.properties   the backend's text (web, message, pdf, mail, template); staged by setup.mjs,
                   copied into the country's jar by its Maven build (docs/code-data-config-plan.md §4.3);
                   other languages' lang.<code> keys optional (a draft PDF's language names); its own
                   lang.<code> missing is a pack-check warning, and the draft PDF then names the
                   language by its code in upper case (no pack.json label is added)
  ATTRIBUTION.txt  the flag's drawing, its sources and its render command — never shipped
  status/          never shipped:
    fingerprints.json  key → fingerprint of the English text it was translated from
    unaccepted.json    keys whose wording awaits acceptance (before P2 info's da-unaccepted.json and
                       kl-unverified.json, and the admin's inline `ai_` value prefix)
```

- **The status files' formats** (from P2). `status/unaccepted.json` is per application,
  `{ "<app>": { "unaccepted": [<flat keys>] } }`, keys sorted, with a `note` and, beside `unaccepted`, the
  reviewers' optional notes — `kl`'s `cldr` (values taken from CLDR) and `verbatim` (values deliberately the
  Danish text); go-live requires every `unaccepted` empty. The owner takes every Danish text in one pass at the
  very end of the work (ruling of 2026-10-22), not per pull request; until then each new value is listed here.
  `status/fingerprints.json` is
  `{ "<app>": { "<flat key>": "<fingerprint>" } }`, the fingerprint being the first 16 lower-case hex digits of
  the sha256 of the key's UTF-8 English value (one function, exported by the staging script, which the catalog
  and `pack accept` use too). P2a seeded it for every key the packs had, unaccepted ones included; the review of PR
  #184 dropped the 58 seeds of keys still unaccepted (`kl` 57, `da` 1), so a key is in exactly one of
  `unaccepted` and the fingerprints, and gets its fingerprint only when `pack accept` accepts it (§4.6).
  **The backend half** (since P5k; kit 4.0.81's format refused it, so DK's move listed its new Danish backend
  values in the file's `note`): `status/unaccepted.json` takes `"backend": { "<bundle>": { "unaccepted": [<keys>] } }`
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
  (R5): the `assets` half of the former `kl-unverified.json` is, in the base product,
  `fe/ui-apps/info/i18n-status/branding-unaccepted.json`, keyed by language. P5 and P6 copy each country's
  entries into its repository (DK's `da` list in `niord-dk` #5; GL's seven `kl` entries, with `da: []`, in
  `niord-gl` #4), and the base product kept GL's until P7 deleted the file (P7f, #211), since its own `gl` builds
  read it until then. In a country workspace the list is `fe/<app>/branding-unaccepted.json` (DK's move kept the
  base product's format at `fe/info/branding-unaccepted.json`, and P5k took that): a list per UI language of the
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
  first agrees on it with the pack's owners, as GL did with DK for `da`. `kl` is GL's home pack only (`niord-gl`
  #5; the owner, 2026-10-23): `publish: false`, staged at build and never published. A home's first publish is therefore the first push to its `main` that changes the pack folder — for DK, the merge of its bump to P5k's version (§6 P5). Two jobs, and a third for a withdrawal:
  - **validate** (no secret beyond the workflow token, so no personal access token; `permissions: contents: read` for the private home's checkout and `packages: read` for the base product's registry, from which `be/mvnw -f be/pom.xml dependency:copy` fetches the latest published `niord-fe-src` with that token, as `image.yml` reads `niord-base` today, its version the newest in the registry's `maven-metadata.xml`; the home's pinned kit reads only that zip's `catalog.json`, as data): the pack check against the latest base product
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

  **As built in P5k** (`templates/pack.yml`, `country-kit/lib/pack-archive.mjs`, `country-kit/lib/oci.mjs`; until
  then `validate` ended green with a notice and `publish` failed on a dispatch). The workflow has
  `permissions: contents: read` and one concurrency group, `pack`, never cancelled, since the withdrawn marker is
  read, changed and pushed whole, and queued (`queue: max`): GitHub's default keeps one pending run per group and
  cancels it when a third arrives, which could drop a queued withdrawal (#190's review). It runs on a push to `main` with the path filter `fe/packs/**` less
  `fe/packs/*/status/unaccepted.json` — the archive leaves the reviewers' notes out, so a change to them alone
  would publish the same bytes under a new tag (R190) — and by dispatch (`lang`, `action` publish or withdraw,
  `tag`, `reason`). The first publish is the first push to `main` that changes a pack folder: a home that adds
  `pack.yml` and its pack in one merge publishes at that merge, and dispatches `publish` only when no pack folder
  changed; the owner checks the organisation's package setting before it and makes the package public after it.
  - `validate` (`contents: read`, `packages: read`): _Which pack_ takes the dispatch's `lang`
    (`^[a-z]{2,3}$`, its folder present) or the one pack folder the push changed between `github.event.before` and
    the commit, `status/unaccepted.json` left out of the count as the push filter leaves it out (R190) — none is a
    notice and nothing to publish, and so is a push that removed the folder it changed (since the review of
    2026-10-23, §6 P5k), two or more fail asking for a dispatch each, an all-zero or malformed `before` fails asking
    for a dispatch, and so does a `before` no ref reaches (a force-push; R190); _Set up the workspace_ (`node
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
    no marker) the empty withdrawn marker pushed **before** the pack (R190), so a publish broken between the two
    pushes leaves only a marker, which no consumer pins, and a re-run of the failed job, with the same run number,
    finishes it; the archive pushed unchanged as `:<GITHUB_RUN_NUMBER>` under `GITHUB_REPOSITORY_OWNER` in lower
    case, a manifest `PUT` answered without `Docker-Content-Digest` confirmed by a `HEAD` of the digest (R190); and
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
    marker keeps the reason as given (R190). Each withdrawal leaves the previous `:withdrawn` manifest as an
    untagged version of the package: harmless, and housekeeping for the owner on the package's page.

  On the registry a pack is an OCI image manifest whose config, `application/vnd.niord.lang-pack.config.v1+json`,
  is `{"format":1,"lang":"<lang>"}` and whose one layer, `application/vnd.niord.lang-pack.v1+zip`, is the
  archive; the marker `:withdrawn` has the same config and one layer
  `application/vnd.niord.lang-pack.withdrawn.v1+json`, `{"format":1,"lang":"<lang>","withdrawn":[{digest, tag,
  reason, date}]}`, strict (a tag of digits, a reason of one line and at most 500 characters, at most 256 KiB) —
  the OCI 1.0 artifact convention with a custom config type, chosen over an empty config with `artifactType`;
  whether GHCR accepts it is proven by the first real publish. **The canonical archive**: exactly the layout
  above (`status/unaccepted.json` the one file left out silently; any other file, a link or a non-regular file
  fails, naming it, and so does a folder, empty or not, that holds no entry of the layout, R190), the text
  entries' CRLF and CR turned into LF, entries sorted and dated 1980-01-01 (the kit's `writeZip`), at most 32
  entries, 2 MiB an entry and 8 MiB in all inflated (`pack-archive.mjs`), the PNG flag at most 64 KB; a pulled
  archive's `pack.json` is held to `readPack`'s strict schema before anything is staged, the refusal naming
  `niord-lang-<lang>` (R190). The client's own cap, 16 MiB, bounds the layer's bytes on the wire and sits above
  the 8 MiB, since a stored archive of nearly 8 MiB of content is a little over 8 MiB; the two are never made
  equal. The same files give the same archive on the same Node build, whose zlib deflates it (P5k found the three
  packs' SHA-256s equal on Windows and in a `node:24` container, which nothing relies on); a publish never
  rebuilds: validate builds once, publish pushes those bytes, and a consumer verifies them against the pin. No run block holds an expression, every one,
  one-liners included, starts with `set -euo pipefail` (since P8a's C2), and the file names no npm. `pack-releases.md`, at the repository root so that
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
  checkout); `--base` needs published catalogs. They exist from P4's first publish (`catalog.json` at the
  zip's root); `--base`, which fetches each version's catalog, was not built in P4 (§6 P4's record) and came in
  P5k (`country-kit/lib/published-catalogs.mjs`): through the tree's Maven wrapper, Maven resolves the meta-version
  `LATEST` of `niord-fe-src` with `-U`, which fetches the registry's `maven-metadata.xml` into the local
  repository (a version range is not resolved by `dependency:copy` and fetches none); every `maven-metadata*.xml`
  in the head local repository, the one `-U` writes (`-Dmaven.repo.local` in `MAVEN_ARGS`, else Maven's
  `settings.localRepository`), is read (`maven-metadata-local.xml` is an install's) — a `-Dmaven.repo.local.tail`
  never, since Maven does not refresh a tail's metadata and a stale one could name a version the registry no longer
  lists (the review of 2026-10-23, §6 P5k) — its plain versions from `<version>` up each have their
  zip fetched into `fe/.niord/catalogs/` and only `catalog.json` read as data, held to the catalog format and its
  version. `<version>` must be published; `--base` takes no `--catalog`, and a failed Maven run stops it rather
  than reading stale metadata. Each version costs one Maven run and one zip of about 4 MB in the local
  repository, and is printed as it is read (R190), so `--base` is run rarely; a catalog published as its own
  small artifact would be the cheaper source later. Prune walks the bundles some catalog from `<version>` up
  still has: a backend bundle the base product dropped whole keeps its file, fingerprints and status list in the
  pack until the home removes them by hand, and the pack check names the bundle the catalog does not have until
  then. The same module gives `pack.mjs latest-catalog`. In the base product tree its
  `be/mvnw` names no repository for `niord-fe-src` (GitHub Packages is only its `distributionManagement`), so
  there it reads what the local repository holds unless a `settings.xml` profile names the registry; a country's
  pom names the registry, as for the zip.
- **Tools** (in the kit since P3a, `fe/country-kit/pack.mjs` and `fe/country-kit/catalog.mjs` beside
  the staging step, run in the base product tree as `npm run languages:check|migrate|prune|accept` and
  `npm run languages:catalog`; since the review of PR #184 `migrate`, `prune` and `accept` also run in a country
  workspace, from its `fe/` as `node ../.niord/kit/pack.mjs …` (`node .niord/base/country-kit/pack.mjs …`
  before the kit package, KM-6), where they edit the home packs under
  `packs/` — a pinned pack never — against the pinned zip's `.niord/base/catalog.json`, `--packs` and `--catalog`
  name another folder or catalog, `check` is refused because set-up is the pack check there, and every line names
  the pack folder it read, `packs/<lang>/`, `.niord/packs/<lang>/` or `languages/<lang>/`):
  `pack check` (since P5k `--frozen <lang>`, repeatable, turns every finding of that pack into a warning prefixed
  `frozen <lang>:`, §4.6), `pack migrate <lang>` (applies the catalog's `renamedFrom`, §4.6), `pack prune <lang>
  [--base <version>]`, `pack accept <lang> --app <app> <key>…` (removes each key from `unaccepted.json` and records
  its fingerprint; since P5k `--app backend --bundle <bundle>` for the backend half), `catalog`; and since P5k, in
  a country workspace only, the two steps of `pack.yml`: `pack latest-catalog --out <file>` (prints
  `latest-catalog: <version>`) and `pack validate <lang> [--catalog <file>] --out <dir>`. A command refuses an
  option it does not take.

### 4.6 The pack check

Run by `setup.mjs` for every pack the manifest names, against the base product's `catalog.json` (generated at
publish; in the base product tree built by `fe/country-kit/catalog.mjs`, which the check runs in memory and
`npm run languages:catalog` writes to `fe/.niord/catalog.json`: per application, every key with a
fingerprint of its English text (`fp`), its placeholders — `{{ name }}` with `name` matching `[\w.]+` — and,
where they apply, `markup` (the English value's tag names, attribute names and every `href`/`src`, when it has an
HTML element tag), `empty` (the English value is blank, so a translation may be too), `pattern` (a public-site
`*_DATE_FORMAT` key) and `list` (the entry count of a public-site `DATE_*` value written with `|`); and
`renamedFrom` for every key the base product renamed (new key → old key, from `fe/languages/en/renames.json`,
where the PR that renames a key names it, §7); a `backend` section does the same for niord-base's English
`*.properties`, whose placeholders are also MessageFormat's `{n}` (in `defaults/base/texts/en/` since
`docs/code-data-config-plan.md` C2, #179; the root-level `*_en.properties` are the legacy location, which
`catalog` still reads, refusing a bundle found in both, so the order of P2 and C2 did not matter), against which a pack's backend half
is checked; and `rules.datePatternTokens`, the tokens the public site's date renderer understands, read from
`fe/ui-apps/info/src/app/shared/utils/date-pattern-tokens.json`, so a country's packs are held to the renderer of
the version it builds against). `catalog` refuses English that breaks its own rules: an unknown date-pattern
token, an empty list entry, a `renames.json` entry whose new key is not in the catalog or whose old key still
is, a key named `__proto__`, `prototype` or `constructor`. The check runs with the branding and the escape hatch.

| Finding | Result |
|---|---|
| A catalog key missing from a pack, for an application whose `uiLanguages` name that pack; an empty translation where the English text is not empty counts as missing | **fails**, unless listed in the escape hatch |
| A `backend` catalog key missing from the backend half of a pack whose language is a **UI language** of either application | **fails**, unless listed in the escape hatch; the deployment's default language then serves (`DictionaryVo.java:72-82`). For a language that is only a **content language**, a missing backend key or a missing backend half is a **warning** and the deployment's default language serves in its PDFs and mails, so a new language can start with `pack.json` and a flag alone. The base product's own builds stage no pack's backend half until P5 (DK's Danish backend text is `niord-dk`'s), so its in-tree check has no subject for this row and runs the frontend rows only; the row is tested on fixtures |
| A translation whose placeholders are not exactly the English text's | **fails**, exactly like a missing key — and like one it may be listed in the escape hatch; a listed one is replaced by the English text at staging, so English shows |
| A catalog key with a `renamedFrom` whose old key the pack still has | **reported as a rename**, and missing under the first row until the pack's home runs `pack migrate`, which moves the translation and its fingerprint to the new key. A consumer of a foreign pack cannot migrate it: the key is missing (it fails, or goes in the escape hatch) until the home publishes the migrated pack (§4.12) |
| A `*_DATE_FORMAT` value with a token the renderer does not know (the catalog's `rules.datePatternTokens`; formerly `info i18n/date-pattern-guard.spec.ts`), or a `DATE_*` list with another number of entries than the English one or an empty one (formerly `info i18n/parity.spec.ts`, its `ARITIES`: 12 months, 7 days; both specs removed in P2b) | **fails** |
| A value whose markup differs from its reference's (tag names, attribute names, every `href`/`src`, each compared as a multiset), or markup in a key whose reference has none. For a pack or a text override the reference is the catalog's English value. For branding it is the country's own English branding (its default language's where `en` is not a UI language), and an `href` may differ from it only in path, on the same `https:` host (security review, §4.11) | **fails** |
| A `pack.json` outside its schema, a flag that is not a PNG inside the pack, a path outside the pack or through a link, a key named `__proto__`, `prototype` or `constructor` | **fails** |
| A `status/unaccepted.json` outside its shape (a `note`; per application `unaccepted` and the reviewers' optional `cldr` and `verbatim`; since P5k per backend bundle `unaccepted`), a listed key the pack's bundle lacks or holds empty, a key listed twice or in two lists, a `verbatim` value that is not the same key's value in another pack of the run, a pack key with no fingerprint in `status/fingerprints.json` that is not `unaccepted` (accepted without `pack accept`), or an `unaccepted` key that has one: a key is in exactly one of the two lists, so it is accepted only through `pack accept` (since the review of PR #184). In the backend half a key in neither list is one **warning** per bundle with the count (§4.5). For a **foreign** pack — a pinned one or a `NIORD_PACK_<LANG>` copy, staged from the canonical archive, which never carries `unaccepted.json` — the row does not apply: its status is its home's (P5k) | **fails** |
| A pack key the catalog does not have (a `lang.<code>` key in the backend half's `web` excepted, `docs/code-data-config-plan.md` §4.3) | **stripped** from the staged bundle; listed |
| A key whose English fingerprint differs from the one the pack translated from | **warning**, listed |
| A branding key not in `branding.schema.json`, or one whose placeholders differ from the schema's | **fails** (a typo, or a key the base product removed) |
| A required branding key or file missing for a UI language | **fails** |
| A brandable PNG whose pixel size is not the `width` or `height` its schema entry gives (the public site's icons 32×32 and 152×152 px, its footer logos 44 px high), four digits standing alone (a year) or an address written into a branding value whose key `forbids` it (`FOOTER_COPYRIGHT`, `MAP_ACCESSIBILITY`; the product fills `{{year}}` and `{{url}}`), or a public-site cookie statement that does not name a key the site stores (D23, since P8a) | **fails** |
| An escape-hatch entry whose key is now translated, or no longer in the catalog | **warning**, listed; `setup.mjs --prune-accepted` rewrites the file |
| An escape-hatch entry for a language or application the manifest does not use (`"app": "backend"` excepted: such an entry also names its `bundle`, and its language must still be one the manifest uses) | **fails** |
| The escape hatch not empty | printed in full on every build |

**In the base product tree since P2b** the check is `fe/country-kit/pack.mjs check` (`npm run languages:check`;
in `fe/scripts/` until P3a), with one negative test per row in `fe/country-kit/pack.test.mjs`, but for the
branding rows — the branding half of the markup row, an unknown branding key and a required branding key or
file missing. Those came with `branding.schema.json` in P3a, in the kit's `lib/branding.mjs` with one negative
test each in `branding.test.mjs`, together with §4.11 rule 4's content rules for brandable files; D23's rules
joined them in P8a (C3: the size, year, address and cookie-statement row, one negative test per rule; the
public site's page rule went to check-build); a country's
set-up runs them for each application, the base product's `dk`/`gl` builds did not (their overlays moved in P5
and P6; DK's and GL's overlays passed them as they stood). Each application's languages are read from its `angular.json`
language configurations (the administration app's `uiLanguages`, the public site's `languages`), per
configuration, so `build:info:dk` was not failed by a `kl` key; the escape hatch is `fe/missing-keys-accepted.json`,
which the base product does not have (absent counts as empty). **From P5k to P7** the base product's `da` copy
was frozen (§6 P5): `build:dk`, `build:gl`, `build:info:dk`, `build:info:gl` and `languages:check` passed
`--frozen da`, so every finding of that pack was a warning prefixed `frozen da:` — a key it lacked was staged from
`en` (`stage-languages.mjs` already did that) — while every other pack's stayed errors: a missing Kalaallisut key
failed `build:info:gl` until P6. P7f (#211) deleted the copy, those builds and the option (D14); `languages:check`
checks `en` alone.

```json
[
  { "lang": "da", "app": "admin", "key": "editor.foo.bar",
    "reason": "4.0.183 security fix; translation in niord-dk#212", "added": "2026-11-02" },
  { "lang": "da", "app": "backend", "bundle": "mail", "key": "mail.foo.subject",
    "reason": "4.0.183 security fix; translation in niord-dk#212", "added": "2026-11-02" }
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

**At runtime a missing key showed English** in both applications from P1 until P3a: Transloco's fallback language was `en`
(admin `app.config.ts:113`, `language.service.ts:35-38`; info `provide-niord-info.ts:12`,
`web-settings.service.ts:125`), and the administration loader merged `en.json`, not the default language, under each bundle
(`load()`'s `forkJoin` in `transloco-loader.ts`; P1). P2's staging step already completed the base product's own bundles, but the
merge stayed until the kit's `setup.mjs` staged countries' builds too. **Since P3a** staging fills every missing key
from `en` (§3.2 step 4), the loader fetches only the requested bundle, and the runtime fallback language is gone: a
build ships exactly its `uiLanguages` (R9), and a key still missing renders as the key. A bundle that fails to
load never reaches Transloco's fallback strategy, which throws when no fallback language is configured: the
administration loader retries it twice (after 1 s and 2 s, as the public site's loader does) and then completes
with the branding and the Dictionary's overrides alone, logging the failure; the public site keeps its stand-in,
another of its languages while the bundle is asked for again — availability, not a fallback language. The public
site's language configuration gained `brandingLanguages`, like the administration app's, and requests a branding
file only for those (none in the base product's own build). Before P1
both fell back to the country's default language, which in a Danish-default deploy is another bundle that
may lack the key.

### 4.7 Styles and fonts

- `tokens.<cc>.css` holds values only (`fe/docs/country-deployments.md`, _The token contract_, kept; restated
  for a country in `fe/country-kit/docs/designers-brief.md`, which the source zip ships, since P8a's NG-11).
- A text face is vendored in `styles/fonts/<face>/` with its licence and declared in `styles/fonts.<cc>.css`
  with relative `url()`s and `font-display: swap`. The builder emits only what a configuration's own style
  entries reference, hashed into `media/` (`@angular/build` `tools/esbuild/global-styles.js:22-28`; shown
  by experiment in the analysis: a font referenced only from `tokens.dk.css` is absent from the default
  build), so no other country's build can carry it.
- No `url(https://…)`, `fonts.googleapis.com` or `fonts.gstatic.com` anywhere in a build (checked, §4.8).
  `optimization.fonts`, set today, inlines Google CSS when it meets it; with the check it never does.
- The administration service worker has had `/assets/branding/*.json` and `/assets/legal/*.html` in its `i18n`
  group since P1a. A `/media/**` group (a country face cached offline) was not built then: `ngsw-config.json` is
  protected (§4.11 rule 3), so the group is a base product step, not on GL's P6 list (found by the review of PR
  #184). It was not made before P6's font: GL vendored Lato on base 4.0.81 (P6b, 2026-10-23), and the
  administration app showed the system face offline until the group existed (`font-display: swap`). **Built
  2026-10-25** (B4, on `post7/b4-records-small-fixes`): `ui-apps/admin/ngsw-config.json`'s `media` group, lazy
  install, prefetch update, `/media/**`, checked by `scripts/admin-service-worker.test.mjs` (run by
  `npm run test:guards`), which runs Angular's own generator over a build output with a face under `media/`
  (red on the old configuration, green on the new); the base build's `ngsw.json` has the group with no files,
  since the base build has no `media/`. GL's Lato shows offline after its first load from the first version
  that carries the group. Not yet run: a country build's `ngsw.json` listing its face under `media`, and GL's
  face offline in a deployed administration app (the deploy step).
- The generated budget allows for the face (P8a's C2): when `styles/fonts.<cc>.css` exists, each application's
  `styles` bundle budget, `maximumWarning` and `maximumError`, is raised by the sheet's size in bytes (written in
  kB as Angular counts them, 1000 bytes; `raiseStylesBudgets` in `country-kit/lib/workspace.mjs`), in the
  options and in any configuration with budgets of its own; a budget with a baseline or a size that is not
  absolute, and every other budget, stay the base product's. GL's 3586-byte `fonts.gl.css` puts its public
  site's styles budget at 56.586/58.586 kB against the base product's 53/55 kB, so the 54.02 kB warning of its
  P7 bump goes; the `initial` budget is not raised (its margin absorbs the sheet). Checked by
  `country-kit/workspace.test.mjs` (red before the change); not yet run: a GL build on a base version that
  carries it (GL's next bump).
- **GL's theme** from Claude Design (`tokens.gl.css`, 150 lines against 218 in the tree) is merged token by
  token, not copied over; its font line keeps `Lato` first; the Google / `/assets/fonts` loading its chat
  suggests is not used (R7).

### 4.8 The kit, its checks and the info image

`country-kit/` in the base product, at the repository root beside `fe/` (since the kit move's KM-2, §6), published
since KM-3 as its own package, `org.niord:niord-country-kit`, beside the source zip, which carries nothing of the kit
since NG-B:

- **Commands:** `init` (below), the steps of `setup.mjs` (§3.2), `pack …` (§4.5), `catalog`,
  `image:info --tag <registry>/<name>:<version>` (§ below). `init` writes the whole country from a handful of answers,
  the backend included (since N2-3, #264; before N2 a frontend skeleton after a hand-written `be/pom.xml`,
  _N2 as built_): `node .niord/kit/init.mjs [<cc>] [--answers <file>] (--sha256-file <file> | --zip-sha256
  <fe-sha256> --kit-sha256 <kit-sha256>) [--version <v>]` from the repository root (§5 step 2). It asks its sixteen
  answers (the other fifteen when the code is given) line by line (readline without a terminal, so a Git Bash pipe
  works), each standard default shown and taken with Enter, or reads them from `--answers`, both validated by `country-kit/init-answers.schema.json` and
  `country-kit/lib/answers.mjs`; the version is the kit marker's (`--version` only in niord-ng's tree, refused when it
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
  `docs/code-data-config-plan.md` §7.1; until P5k also `be/bootstrap/releases/README.md`, which the bootstrap archive
  refuses: it accepts under `releases/` only a release folder's `*.json`, `*.md` and `*.sql`, so DK's bootstrap folder
  test and bootstrap zip failed with it), `be/scripts/check-country-build-group.sh` (0755), the manifest
  `fe/niord-country.json`, `fe/info/app/info.config.ts` with the time zone, `.github/CODEOWNERS` with the owners,
  `image.yml` with `MODULE: 'niord-<cc>'` and `REGISTRY` and `HARBOR_PROJECT` empty (a kept `image.yml` gets its
  frontend and backend jobs printed), `ci.yml` (a kept one without `workflow_dispatch:` gets a notice) and the root
  `README.md`. Beside them: since P8a (NG-10) the launcher `start-local.sh` at the repository root,
  `templates/start-local.sh` byte for byte and executable (§5 step 9), and the root `.gitignore` with `/.local-run/`,
  the launcher's downloads and logs, a line `init` adds to an existing `.gitignore` that does not ignore that folder
  yet; `setup.mjs`, the branding skeleton from the schema — its required keys and files only, §4.4, and no branding
  text — `tokens.<cc>.css` with the default values, `pack.yml`, `base-bump.yml`, `fe/.gitignore`, `fe/.gitattributes`
  and, since NG-B2, the root `.gitattributes` from the same template (since P5k: the base product's
  `* text=auto eol=lf` with its binary list, without the `.husky` rule, held equal to `fe/.gitattributes` by
  `init.test.mjs`; a country's own is kept), `pack-releases.md` at the repository root (since P5k), `dependabot.yml`,
  `AGENTS.md` with a one-line `CLAUDE.md` importing it (KM-8), `.niord/.gitignore` and `fe/niord-pins.lock.json`
  with the base product's version and both zips' hashes: 41 files on an empty folder at N2-3's run by hand, 40
  once the bump by push retires `.github/renovate.json` (F8-4). Its closing lines name set-up (with
  `--update-pins` when languages were answered) and the launcher and, on Windows, the commit line that gives the
  three scripts their exec bits and the `NIORD_HOME` export before `be/mvnw verify` (`be/docs/potential-errors.md` PORT-1). An absent escape hatch, `audit-accepted.json` or
  `fe/country/package.json` with its lockfile, counts as empty.
- **The example country** `country-kit/example/` — a complete, fictitious country `xx`: every seam
  overridden, branding, a legal document, a vendored face, a country-only Tailwind utility, a home
  fixture pack `zz` (pseudo-localised from `en`). The base product CI sets it up and builds both applications
  and the info image on every frontend change, so the contract is tested where it is defined — a change
  confined to the public site on a pull request only: `fe-ci.yml`'s push paths exclude `fe/ui-apps/info/**`, so
  on `main` such a merge is re-checked by Info CI's build checks alone (D10, accepted). It is the
  reference to read; `init` does not copy it. In the base product CI and in `verify-local` the example and the
  acceptance tests below set up as a country does: against a zip built from the commit under test with the
  include list of §4.11 rule 6, installed into the job's local Maven repository and pinned by its hash — never
  through `NIORD_BASE`, which CI refuses (§3.2 step 2). Since P5k `zz` has `publish: true`, and the harness checks
  `.niord/packs/zz/` against the canonical archive, runs `pack latest-catalog` and, after the fixture upgrade,
  `pack prune zz --base 4.0.0-example`; with `--image` it publishes `zz` with the two commands `pack.yml`'s token
  jobs run (`pack.mjs validate`, then `oci.mjs publish`, later `withdraw`), anonymously, against an auth-less
  `registry:2.8.3` container on `127.0.0.1` — the Bearer flow, the owner's lower-casing and the workflow's own
  bash run only at a real publish (§9) — has the untouched `init` country pin it by digest (`--update-pins`), set
  up, pass `guard-build` and build from the pin, then withdraws the tag and sees that country's set-up fail with
  the reason. The container, `niord-example-registry-<pid>`, is removed at the end on every exit the harness
  reaches; one an interrupted run left, its pid no longer a running process, is removed by the next run (R190).
- **The new-country acceptance tests**, in the base product CI beside the example — since P3c2 steps of the
  same harness run after the example, against the same zip, so of Frontend CI's `Example country` job and
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
    skip exactly those probes by name (`--no-legal-doc`, `--no-branding-text`) rather than refusing. Since N2-3
    the harness makes `yy` as the Quick start makes a country (both zips and `SHA256SUMS` in `yy/.niord/`, only
    the kit unzipped, `init --answers .niord/init-answers.json --sha256-file .niord/SHA256SUMS`), and any kept file
    or a wrapper byte different from `be/`'s fails it; with `--backend` it builds the generated backend straight
    after init and set-up, before its frontend steps: niord-parent and niord-base with its test-jar installed from
    the tree as `4.0.0-example` into the scratch Maven repository, then `yy/be/mvnw verify` with Docker (the country
    build group, `CountryDefaultsChecksTest` over the generated settings, `CountryContractGuardTest` over the
    generated `application.yml`; on Windows a forward-slash `NIORD_HOME`), the group check and the override check;
    without Docker it refuses. Frontend CI's `Example country` job passes `--backend` when a pull request matches
    the scaffold rule and the example rule starts the job, and on a dispatch; Backend CI's `Generated country` job
    runs `--backend --skip-frontend` when the rule matches on a pull request, and on every run on `main` and every
    dispatch; `verify-local` passes it on a match (_N2 as built_);
  - **upgrade:** the example country pinned to the **previous published** base product version, then bumped to the
    one being built, must set up, check and build green — or fail only where `API-CHANGES.md` says a country
    must act. A base product change that breaks a country without saying so is caught before it is published.
    The harness takes the previous zip as `--upgrade-from <zip>`, sets that zip's own example up at its
    version, bumps it, applies the routine actions of every bump (the home pack translated again,
    `--accept-override` for overrides whose base files changed), then sets up and builds; a failure left
    passes only when a version section of `API-CHANGES.md` (_Unreleased_ or a version heading, never a policy
    section) new since that zip asks a country to act.
    Since P4 Frontend CI's `Example country` job takes the previous zip from the **latest release** of
    `niord-ng` (the first that is neither a draft nor a prerelease; its tag must be `v4.0.<n>`): it downloads
    the release's asset `niord-fe-src-<n>.zip` with `gh` and the workflow's own token, requires exactly one line
    `<sha256>  niord-fe-src-<n>.zip` in that release's notes and the asset's sha256 equal to it — a missing asset,
    a missing or repeated line or a mismatch fails the job, "refusing an unverified previous zip" — and passes the
    zip as `--upgrade-from`. With no release yet (PR A's own run: the first release is PR A's publish) the test is
    skipped and the job summary says so; `verify-local` takes `--upgrade-from <zip>` (a release's, `gh release
    download v4.0.<n> --repo SIFA-DK/niord-ng --pattern 'niord-fe-src-*.zip'`), and without it its row says the
    test is Frontend CI's. Why a release asset and not the registry (orchestrator's decision, 2026-10-22): the
    publishing token lives in the `main`-only `publish` environment, so a PR run cannot read the registry with it;
    a read-package secret for PR runs would be a new owner step; a release asset of the repository itself is
    readable with the workflow token's `contents: read`. The mechanism runs on every run on a **fixture pair**: the zip
    under test, `4.0.0-example`, and a zip derived from it in memory, `4.0.1-example` (one English text changed,
    the example's overridden template changed, `roleGuard` deprecated with its removal version and an
    `API-CHANGES.md` section); the example bumped to it must fail set-up on its override alone, write the
    upgrade report (§4.12), and set up and build green after the routine actions.
- **Checks on every country build** (and on the example in the base product). Since P3c1 they are the last
  step of the generated `npm run build`, `country-kit/check-build.mjs` (with `lib/build-checks.mjs`), over
  both applications' `dist`, so no pipeline, harness or developer build can skip them:
  - one source per output file: `resolveAssets` over each configuration, no duplicate destination — since
    P3c2 check-build's check 9, which reads the generated `angular.json` and resolves the assets of the options
    and of every configuration of both applications with the builder's own code, `country-kit/lib/asset-outputs.mjs`,
    which loads the workspace's installed `@angular/build` at run time (`createRequire` from the workspace root,
    so the kit itself still imports only Node built-ins); the base product's `contents:check` uses the same
    module, so there is one implementation;
  - the shipped bundles are exactly the manifest's `uiLanguages` (`en` only when they name it, R9), the flags exactly the
    manifest's languages (`<lang>.png` of the administration app's UI and content languages, of the public site's UI
    languages); nothing from `status/`, `ATTRIBUTION.txt`, a flag's drawing, `node_modules`, `.niord/` or another
    tree (`assets/default/` or a two-letter overlay folder under `assets/`);
  - no external font or style URL in `dist`, and no absolute URL outside the base product's allow-list
    (§4.11 rule 5: `scripts/build-urls-check.mjs --absolute` with `scripts/build-urls-allowed.json`) and, since
    P5k, the country's own `fe/build-urls-allowed.json` (§4.11 rule 5), whose entries every build prints;
  - the country-only utility of the example (base product CI) / the country's own: `setup.mjs` records the first
    Tailwind class with an arbitrary value of each country template in `.niord/build/country-utilities.json`
    (beside the staging folders, so no build ships it), and each one's selector, escaped as Tailwind writes it,
    must be in a stylesheet of its application's `dist`;
  - the secret scan of both `dist` (§4.11 rule 4);
  - since P8a (C3, D23), check-build's check 10: the public site's built `index.html` has the `lang` of the
    manifest's default language for the public site and that language's built `SITE_TITLE` as its title, the fix
    being the country's `info/app/index.html` (the index seam);
  - the public site's checks with parameters: `info-separation-check.mjs --root .niord/base/ --country <cc>`
    (before P3c1 it allowed only the base product workspace's prefixes; with `--country` it also allows the
    country's `info/`, `.niord/generated/info/`, its own `styles/tokens.<cc>.css`, `styles/fonts.<cc>.css` and
    `styles/fonts/`, and refuses its `admin/` and `.niord/generated/admin/`), and the bundle report against the
    country's own `info/bundle-baseline.json`, `info-bundle-report.mjs --root .niord/base/` (the baseline's entry
    points stay the base product's paths, matched under the prefix; the same command as the generated
    `bundle:info`; a country without a baseline yet gets a warning naming how to start one, so an untouched
    `init` still builds). `info-layout-check.mjs` gained `--nginx <dir>` (the folder holding
    `niord-security-headers.conf`) and the container test `--language <lang>` (the bundle and branding file it
    probes; default `en`), both used by `image:info` against the image; since P3c2 the container test also takes
    `--no-legal-doc` and `--no-branding-text`, which `image:info` passes, with a warning naming the file to add,
    for a country that has no legal document or no branding text for its default language yet (a fresh `init`):
    those probes become named skips counted in its last line, so an untouched country's image job is green. The
    nginx sandbox rules are tested by the example's image, in Frontend CI's `Example country` job (on pull
    requests, D10), and by hand on the base build with `image:info --package-only`, `layout:info` and
    `test:container:info` (D32, `fe/CLAUDE.md`); until P7 also by the base product's DK and GL images, which P7d
    (#208) removed from Info CI, so no DK or GL image is built in `niord-ng`. `info-layout-check.mjs`'s da/en
    table-width check runs only in an image job whose public site offers both `da` and `en` (`:884`) — DK's and
    GL's today, and the dry run's `qq` — never in the base build's or the example's image. Each country has its own bundle baseline (GL's
    includes Lato).
- **The info image** packages a built `dist`: the country pipeline builds with `npm run build`, then
  `image:info` copies the output into a fresh context, runs `precompress.mjs` there and adds the base product's
  nginx files and security headers (§4.11 rule 5). Until P3c1 the Dockerfile built inside the image from an
  allow-listed workspace, which was also "the first and only structural separation guarantee"; since P3c1 that
  guarantee is the separation check above, run on every country build and by `image:info` against the stats of
  the build the image packages, and lint. Until P7 the base product's own DK and GL images were made the same
  way, from the dist of `build:info:dk` and `build:info:gl` (`image:info --package-only`, in Info CI's one work
  job); since P7d (#208) Info CI builds no image, and `image:info --package-only` packages the base build by
  hand (D32). The
  image's registry and project are the `REGISTRY` and `HARBOR_PROJECT` variables of the
  `image.yml` that `init` writes (§5 step 2; until P7h's dry run §5 step 1 copied `niord-gl`'s), joined into `IMAGE_PREFIX` as in `niord-gl` today; `init` writes both into
  `image.yml`'s workflow-level `env:` empty (since the review of PR #184), so both jobs fail until the country
  sets them, and the push job logs in to that `REGISTRY` alone (§4.11 rule 1).
- **The base product's own guard:** `fe/` outside `country-kit/example/` holds no country tree, no pack but `en`,
  no configuration but the base product's, and none of an allow-listed set of country words (authority names
  and domains — `Beredskab`, `Søfart`, `brs.dk`, `dma.dk`, `t-dma` — `kl-GL`, `da-DK`, `Nuuk`, …), with a
  reason per allow-list entry. Since P4 the words and their allow-list are `country-kit/country-words.json`
  (read by `country-kit/lib/country-words.mjs`): the eight words above and the two authorities' English names,
  `Danish Emergency Management` and `Danish Maritime Authority`, each matched case-insensitively as a literal
  substring of every path and of the NFC text line by line (so `Beredskab` also catches the longer name, `Nuuk`
  the IANA zone); language names (`Dansk`, `Kalaallisut`) are not words — a language is a pack (R3), and the base
  product's specs use them as sample languages. Country and place names (`Denmark`, `Greenland`, `Copenhagen`,
  `Danmark`, `Grønland`, `Kalaallit`) and `niord.dk` are not on the list in P4 either. The zip names them today in
  several dozen files: specs' sample data and time zones, the public site's test zone `Europe/Copenhagen`, and
  doc-comment examples, with no logic among them but two test scripts' fixtures (checked 2026-10-22):
  `scripts/info-layout-check.mjs`, whose stub backend answers DK's and GL's root areas and, since the review of
  PR #184, any other a build names, so a third country's `rootAreas` pass it; and
  `scripts/info-container-test.mjs`, whose `dma-` message-series ids are inert strings. P7b (#205) made both
  neutral: the stub's roots are Anchorland/Ankerland (`urn:mrn:iho:country:xa`, 10 sub-areas) and
  Buoyland/Bøjeland (`:xb`, 3), handed out in turn to the root areas a build names, with no case of DK's or
  GL's, and the container test's series ids are `xx-nw`, `xx-nw-local` and `xx-nm`. It runs over the zip — by
  `source-zip.mjs build` (so `kit:zip`,
  the example harness, `verify-local`'s example row and Frontend CI's `Example country` job), by its `guard`
  command in `publish.yml`, and over the files the include list selects in `test:guards` (§4.11 rule 6); P7g
  (#212) widened it to the whole of `fe/`: a tree mode in `test:guards` over every file git lists in `fe/` but
  the list itself, where an unused allow entry fails too, while the zip mode reports hits only (D16); P7g also
  added `Soefart`, so the list has 11 words. The structural half — no country tree, overlay, token or font
  sheet, no pack but `en`, no configuration outside the kit generator's base set — is
  `scripts/base-product-tree.test.mjs`, in `test:guards` (D22).
  The backend half of that allow-list, niord-base's `main` with a reason per
  entry (the third-party `dk.dma.niord.s100` package, the legacy publication import, Javadoc and OpenAPI
  examples, Danish as a language, and the like), is in `docs/code-data-config-plan.md` §11 C5 since C5,
  which ran the word list over niord-base once and records its matching rule (a word boundary before each
  entry, none after, case-insensitive). Since P7-be (#206) niord-base's `CountryWordsTest`, a unit test outside
  the country build group, applies it with that rule and its own list,
  `be/niord/src/test/resources/build/country-words.json`: 17 words, 2 patterns (a Danish locale chosen in a
  template, its JSON-escaped form included, and the place names, D20), 1 strip (`dk.dma.niord.s100`) and 22
  allow entries, each used (D21; the counts in that plan's §11 C5).

### 4.9 Changing functionality (R10)

The owner expects all four kinds of variation: a country adding its own pages and features, changing how
an existing feature behaves, replacing a UI component, and needs not known yet. Two mechanisms cover them.

**Extension points — the normal way.** Each is a base product contract with a neutral default (nothing added,
the base product's behaviour), listed in `seams.json` with what it may use:

| Need | Extension point | Country file |
|---|---|---|
| Own pages and features | country routes, lazily loaded under the application's router | `admin/app/country.routes.ts`, `info/app/country.routes.ts` |
| Reaching them | menu entries (where, label key, icon, required role) | `admin/app/country.menu.ts` |
| Changed behaviour | behaviour hooks: injection tokens with a base product default (a strategy, a default value, a validation step), which the country provides its own implementation of | `admin/app/country.providers.ts` (the former `promulgation.providers.ts`, generalised in P3b1), `info/app/country.providers.ts` |
| A different component | named component slots in base product templates; the country registers its component for a slot with a lazy loader (`() => import(...)`), so a slot component never lands in the main chunk | in `country.providers.ts` |

- Hooks and slots are added where a country needs them — a base product change, deliberately: each is a
  promise the base product keeps across versions. Today's audio promulgation is the first hook and stays a
  contract: until P7 DK and GL registered their audio editor and viewer through it, and since P7c (#207) the base
  product registers its own (the owner, 2026-10-24: audio promulgation is the base product's, §6 P7), while a country
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
    "reason": "DK workflow step, niord-dk#140", "added": "2026-11-20" }
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
  (`@niord/admin/internal/*`, §4.3; until P3b2 every country file did, through P3a's interim aliases, and since
  P3b2 only override files do).
  `--add-override` writes the copy's relative imports as that alias, since the copy compiles at its own place.
- An override that is still needed after a version or two is a request for an extension point; the
  guidelines (P8) say so.

**The backend, the same two ways.** The country deployables have changed base product behaviour since they
existed, unguarded:
- **Extension points** are Spring's: a country provides its own bean for a base product interface or a
  `@Primary` replacement (never a protected bean, §4.11), and sets values in its settings file
  `defaults/country/settings.json`, which replaces the base product's whole file and is checked against the
  settings catalogs (`docs/code-data-config-plan.md` §5). This stays the normal way, and the base product names the
  interfaces meant for it in `be/docs/country-api.md` (written in `docs/code-data-config-plan.md` C5b) with the same compatibility policy as the
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
  run by the country's build) fails when one changes, as for the frontend. As built (`docs/code-data-config-plan.md`
  C5b): an entry mirrors the manifest's override entry without `with` — `{ "file", "basedOn": { "<file>":
  "sha256:…" }, "reason", "added" }`, the country's copy sitting at the same path in its jar — and the check is
  `check-overrides.sh` (with `CheckOverrides.java`), which the country's workflow runs after its package step
  over the executable jar, as it runs `split-jar.sh` (the step is C6's, in `ci.yml` and `image.yml`). It reads
  the country's `BOOT-INF/classes/` and the `META-INF/` that Spring Boot's repackaging keeps at the jar's root
  (a country's `META-INF/resources/` images are there, not under `BOOT-INF/classes/`), against the pinned
  `niord-base` jar inside it; `be/docs/country-api.md` has the format and the rules. The check enforces this
  paragraph's first sentence literally (since the review of `niord-ng` PR #179, 2026-10-22, which found a listed
  batch-job definition passing it): a shadow is permitted only when it is a static image under
  `META-INF/resources/img/` (`png`, `jpg`, `jpeg`, `gif`, `webp`, `ico`), and then only when listed; every other
  shadow, listed or not, fails. SVG is not among them, since a same-origin SVG is a document that can run
  script. `niord-dk` has 70 resource files, 46
  of them shadowing `niord-base` (counted 2026-10-19 and 2026-10-20 against `main`): under these rules its 42
  template shadows (`message-list-pdf-footer.ftl` included) and its two `_da` variants of the `aton` templates
  move to its bootstrap, `niord.json` becomes its settings file, `web_en.properties` and `mail_en.properties`
  become country texts (a rewording only the administration app shows becomes branding or a text override, `docs/code-data-config-plan.md` §4.4), and one shadow stays a guarded override: `META-INF/resources/img/company-logo.png`.
  `niord-gl` (131 resource files, 75 shadows) is classified the same way in `docs/code-data-config-plan.md` §9.
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
| 5 | the backend Dictionary | the deployment | texts edited at runtime in the database (Dictionaries page), per environment; a row still equal to the default the backend added (recorded in its ledger, including rows adopted unedited on existing deployments) is skipped, so the compiled text wins (`docs/code-data-config-plan.md` §4.5) | the backend (`dictionary-overrides.service.ts` skips the unchanged rows, `merge-dictionary-overrides.ts` applies the rest, with its placeholder rule since #158) |

- Layers 1–4 are merged at build time into the staged bundle where they are static (1–3: English, then the pack,
  then the text overrides, each key in its English place) and at runtime where
  they are per-deployment assets (4, as the public site merges branding today); layer 5 is runtime only.
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
  database row is what runs. Today's whole-file shadows (`web_en.properties`, `mail_en.properties` in DK; all
  five `_en` bundles in GL) become country texts holding only the differing keys a template or Java reads and the country's own keys; a
  rewording only the administration app shows is a text override or branding (layers 3 and 4).

### 4.11 Security rules

From the dedicated security review of the plan (2026-10-19: five areas, every finding adversarially
verified, a synthesis; 23 findings — 4 high, 9 medium, 10 low). **Its verdict:** the plan does not weaken a
running deployment's posture — the public site's CSP, proxy allow-list and header stripping, the backend's
`SecurityConfig` and the Keycloak flow stay base-product-owned and untouched — but it changes *who can put code
and text into a build, and with which credentials*, and the rules below close what that opens. They are
checked, not advised: each has a negative test in the example country (P3) or a step in a pipeline.

**Whom the guards defend against.** A *careless* country — the checks, protected lists and lint are for this.
A *malicious or compromised* country or home — only what the others control holds: digest pins, no shared
credential, a reviewed bump, the base-product-side checks of the zip. A *compromised base product* reaches every
country — hence split tokens, hash pins and, once public, attestations.

1. **Credentials and pipelines**
   - No repository holds another repository's credential (R13).
   - A country's frontend is built in an **unprivileged job** (`contents: read`, `packages: read`, no
     environment, no secret beyond the workflow token): `setup.mjs`, `npm ci --ignore-scripts` (a base product
     package that needs its install script is rebuilt alone from the kit's `scripts-allowed.json`), specs,
     lint, build, the info image and its container test. It hands `dist` and the image on as artifacts with
     their sha256. The **push job** (environment `harbor`, `refs/heads/main` only, no npm and no kit code)
     verifies the hashes, logs in and pushes. The base product's `publish.yml` already keeps npm away from the
     Harbor credential; the countries now do too.
   - Each repository's `harbor` environment has the deployment-branch policy `main` only, so a workflow run from
     another branch never gets its Harbor credential: `niord-ng`'s has it (`.github/workflows/publish.yml`'s header
     on the deployment branch rule, enforced by the gate's `require_main_only`);
     `niord-dk`'s and `niord-gl`'s get it in P5 and P6, each proven by a dispatch from another branch that the
     environment refuses (owner, 2026-10-21). Not yet: Harbor is not touched at this time (owner, 2026-10-22),
     so both countries' `harbor` environments still have no deployment-branch rule, and the rule and its proof
     wait for the owner's go (still so on 2026-10-24, `niord-ng`'s allowing `main` only; until the deploy step
     the owner keeps the countries' Image workflows disabled, `niord-dk`'s already and `niord-gl`'s from before
     its PR into `main` merges, so nothing pushes to Harbor). Write access only for named maintainers; the host
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
     app's private key and id live only in niord-ng's main-only `publish` environment, and the gate warns when
     the key is a repository secret (2026-11-03, §6 _The bump by push_).
     Realised in P1: the list is `.github/maintainers`, read from `main` whatever ref a run comes from, and
     checked against the run's triggering actor (a re-run by someone else is checked too). Until a rule on `main`
     requires review (rulesets need a public repository under GitHub Free), whoever may write `main` may also
     edit `.github/maintainers` or the two workflows in the same push, so who may retract or break-glass is in
     effect who may write `main`: write access only for named maintainers carries the rule (`.github/maintainers`
     says so; required review with `CODEOWNERS` on `.github/` is a go-live item, `fe/docs/backlog.md`).
     `NIORD_PUBLISH_TOKEN` (`write:packages` only; the machine user's login in the variable
     `NIORD_PUBLISH_USER`) is a secret of the environment `publish`, used by the build job and by the bundles'
     deploy job `web-publish`, which runs no npm — the bundles are built in a job with no environment and no
     secret; `NIORD_DELETE_TOKEN` (`delete:packages`, `read:packages`) is a secret of `retract`. Both
     environments allow `main` only, like `harbor`, and because GitHub creates an environment a job names
     without any rule, `publish.yml`'s gate and `retract-version.yml`'s first job fail a run while `publish`,
     `harbor` or `retract` is missing or allows another branch, before any job enters one.
   - **Until the country split (owner, 2026-10-22).** "When full repo is changed and everything is split up to
     each country, then we will rotate keys and setup proper accounts": until then the build and `web-publish`
     jobs deploy with the repository secrets `NIORDORG_PACKAGES_USER` / `NIORDORG_PACKAGES_TOKEN`, as `main` did
     before PR A, handed to the deploy steps under the names above. `publish` exists with its `main`-only rule
     and no secret, so the gate holds; `retract` does not exist yet, so `retract-version.yml` refuses every run
     and a bad version is deleted by hand in the meantime. Accepted: a repository secret can be named by a
     branch's copy of a workflow, which the environment's secret could not. At the split: the machine user and
     its two tokens in `publish` and `retract`, the deploy steps back to `secrets.NIORD_PUBLISH_TOKEN` and
     `vars.NIORD_PUBLISH_USER`, the repository secrets deleted and the old token revoked (`fe/docs/backlog.md`).
   - **As built after the review of PR #184.** `publish.yml` starts after Backend CI, Frontend CI and Info CI,
     and its gate requires each that ran for the commit to have succeeded (one its path filter skipped counts as
     not run, as before), because the zip carries the public site's sources: a merge confined to the public site
     now publishes a version. In the kit's `templates/image.yml` the push job logs in only to the workflow-level
     `REGISTRY` and pushes only `<REGISTRY>/<HARBOR_PROJECT>/<repository>-info:<version>`, refusing any other
     reference before any `docker` call: it trusts nothing from the frontend job but the bytes whose hashes it
     verifies. In each country workflow the set-up step alone (`Set up the workspace`; `Check the packs` in
     `pack.yml`) holds `GITHUB_TOKEN`, for the stub's Maven fetch, which GitHub Packages refuses without a
     token. Since P5k `pack.yml`'s validate job binds it on its two Maven steps, `Set up the workspace` and
     `Fetch the latest base product catalog`, and on no other; `publish` and `withdraw` hold the run token as
     `PACK_TOKEN` with `packages: write` only, run no checkout and no npm, and run only the kit's one-file OCI
     client after checking it against validate's SHA-256 (§4.5, _As built in P5k_). The gates and the maintainer checks of `publish.yml` and `retract-version.yml` are tested by running
     their own bash with a fake `gh` (`fe/scripts/release-workflows.test.mjs`, in `test:guards`).
2. **Integrity of what a country builds from**
   - `fe/niord-pins.lock.json` holds the sha256 of the pinned zip and the digest of every foreign pack;
     `setup.mjs` verifies each **before** unpacking or executing anything; `--update-pins` rewrites it (for each foreign pack the tag given on the command line, else the highest tag on GHCR that its withdrawn marker does not list, with that tag's digest), so a
     bump PR shows the change. As built in P5k: `node setup.mjs --update-pins [<lang>=<tag>]…` reads each pinned
     pack's marker first (a pack without one is unreadable: the command stops and writes no pin of any pack, and the
     home's next publish pushes the missing marker), refuses a given tag that is withdrawn or absent, accepts a
     candidate only once it is pulled and verified as a pack (one that is not is skipped with a line, and refused
     when named; R190), rewrites the lock with the base pin kept and the pack pins sorted, prints each old → new with a reminder
     to compare a first pin with the home's `pack-releases.md`, then runs the whole set-up; `NIORD_PACK_REGISTRY`
     may name a loopback registry instead of `ghcr.io`, for the example's harness, and any other host is refused. `publish.yml` records the zip's sha256 in the notes of a GitHub release on the tag `v4.0.<run>` (the tag stays lightweight, as the gate needs: the tag job's step _Tag v…_ in `.github/workflows/publish.yml`) and in the run
     summary — a record the registry token cannot rewrite. As built in P4: the build job's summary gives the line
     `<sha256>  niord-fe-src-4.0.<run>.zip` beside the deploy's upload lines; after the tag, the tag job — with its
     own `GITHUB_TOKEN`, no new secret — checks the zip it received from the build job against the build job's
     sha256 and creates the release with `gh release create --verify-tag` (never a new tag): its notes are one
     sentence naming the coordinates, a blank line and that line, in `sha256sum`'s form, on its own, and the zip is
     attached as a release asset (§3.1 _Reading_, §4.8's upgrade test). When GitHub refuses the tag (a workflow
     change on `main` since the run's commit), or `gh release create` fails, the run summary gives the by-hand
     commands for the tag and the release, from the run's one-day artifact `niord-fe-src`, which is deleted only
     once the release exists. A first pin is checked before it is committed — the zip's against these records, a foreign pack's against its home's `pack-releases.md` (§4.5) — by §5 steps 2 and 4; on a base bump the Base bump workflow (`.github/workflows/base-bump.yml`, §4.12) is its executor: it compares the two downloads with release `v<version>`'s notes and commits the pin only when they agree, and while niord-ng is private the country's own token cannot read the notes, so it pins nothing and the maintainer pins by hand from them (decisions 104 to 106; no interim token, 105); beyond that it is trust on first use, accepted until the attestations. Once the repositories are public, each artifact is attested (`actions/attest-build-provenance`) by the workflow
     that builds it — the zip by `publish.yml`, a pack by its home's `pack.yml`, an image by the country's push
     job — and verified by its consumer.
   - The kit uses Node built-ins only, is covered by the base product's lockfile and audit, and runs only after
     the hash check. `setup.mjs` is a stub that checks the kit's hash. What runs before that check, or holds a
     token without one, is accepted residual trust: Node, the Maven wrapper's distribution (no
     `distributionSha256Sum` in `be/.mvn/wrapper/maven-wrapper.properties`, here and in the country repositories'
     wrappers, so the distribution is pinned by TLS alone) and Maven's plugins. Pinning the wrapper's checksum is
     a go-live item (`fe/docs/backlog.md`).
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
     **Built so far** (P3a–P4, `country-kit/lib/build-report.mjs`): the frontend fields — base product version
     and hash, provenance, packs (since P5k a pinned pack's `reference`, the pin it was pulled by; additive, the
     format stays 1), escape hatch, overrides (a protected file never appears: set-up refuses it
     first), country dependencies, text overrides, the accepted audit findings counted — the staging into the
     administration bundle and the info image's OCI labels. **Not built:** the backend overrides from
     `be/overrides.json`, the bootstrap and release import files, the packs' backend half, the jar copy
     `META-INF/niord-build-report.json` configured in `niord-parent`, the start-up log and the sysadmin page's
     fetch. They come with the backend parts of P5 and P6, that is `docs/code-data-config-plan.md` C6 (found by
     the review of PR #184; §6 P4, _Review of PR #184_).
3. **Protected code — never overridden, a hook instead (owner, 2026-10-19)**
   - Frontend: `seams.json` carries a base-product-owned protected list `setup.mjs` refuses to override —
     `shared-html/**`, the safe-HTML and plain-text pipes, sandboxed HTML, the rich-text editor's allow-lists,
     `core/interceptors/**`, `core/auth/**`, both applications' `app.config.ts`, the administration app's
     `app.routes.ts` and `core/country/**` (the area role guards and the country-route rule, from P3b1),
     the administration app's `environments/environment.ts` (the public site has no `environments/`),
     the Transloco loaders, the administration app's `core/i18n/**` (the loader's inputs: the branding, the
     dictionary overrides and their merge), `core/config/web-settings.ts` and `core/config/app-config.token.ts`
     (the bearer token's host rule rests on the runtime overlay's excluded roots), `repo-url.ts`,
     `ngsw-config.json`, `eslint.config.js`, `scripts/**`, `country-kit/**`, `ui-apps/info/nginx/**`. The
     `core/i18n/**`, `web-settings.ts` and `app-config.token.ts` entries came with the review of PR #184, in place
     of the single `merge-dictionary-overrides.ts`: no `angular.json` file replacement and no DK, GL or example
     file overrides any of them (checked 2026-10-22), so protecting them costs no country anything.
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
     (`docs/code-data-config-plan.md` C5b, `CountryContractGuard`): protected properties are refused where a country
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
     country's only way to add an endpoint is an annotated `@RestController` (`docs/code-data-config-plan.md`
     §11 C5b). The protected classes also load only from `niord-base`. The published
     backend check fails on every shadow of a `niord-base` resource that is not a static image under
     `META-INF/resources/img/` (`png`, `jpg`, `jpeg`, `gif`, `webp`, `ico`), listed or not, and on such an image
     when it is not listed (§4.9); on any `templates/**` file in the country jar (templates belong in
     `be/bootstrap/`); on any file under `defaults/base/`; and on a committed `defaults/packs/` (generated).
   - The country settings file **replaces the base product's whole file (owner, 2026-10-20; reverses the values-only
     merge of 2026-10-19 — settings almost never equal between base product and country)**. It is a file under its own
     name (`defaults/country/settings.json`), never a shadow of the base product's, and sets values only: type, scope,
     web visibility and the `editable` flag come from the settings catalogs generated from the code, a password setting is never
     web-visible and is empty in the file (no real secret in a repository), and the Settings page shows the same
     settings as today (owner, 2026-10-20). The build fails on a key of the base product's settings file that the file lacks unless it is listed as using the base product default
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
     As built (`docs/code-data-config-plan.md` C5b): the group is the classes tagged `@CountryBuildGroup` (the JUnit
     tag `niord-country-build`) — `RestControllerSecurityAnnotationTest`, `RestHandlerSecurityITTest`,
     `SecurityConfigJwtTest`, `SecurityConfigFrameOptionsTest`, `JwtAuthorizedPartyITTest`,
     `MethodSecurityOrderingITTest`, `NiordDomainFilterTest`, `WebAppConfigTest`, `MailHtmlTest`,
     `FmTemplateClassResolverTest`, the guard's `CountryContractGuardTest` and `CountryContractStartupTest`, and
     `CountryDefaultsChecksTest` (the country's settings and text checks); `CountryBuildGroupTest` pins the set.
     `niord-parent` runs it with a Surefire and a Failsafe execution of its own over `niord-base`'s test-jar only,
     so it runs where a country declares that test-jar (DK does; GL from its C6), and a country's workflow
     checks the group's count (C6). The guard checks every handler method of Spring MVC's
     `RequestMappingHandlerMapping` beans, in any package, with the scanner the annotation test uses
     (`CountryContractGuard.JSR250`), and refuses a handler with `@CrossOrigin`; the HTTP surfaces outside it are
     listed above, with the protected properties (narrowed after the review of `niord-ng` PR #179).
   - The zip carries `country-kit/security.json` (the files each version changed for a security fix). An override
     whose changed `basedOn` file a fix names fails with a SECURITY message naming the version until the country
     has merged the fix into its copy and recorded the fixed fingerprints with `--accept-override`.
   - The generated lint applies every base product security rule to the country's files — the public site's
     anonymity bans, the `bypassSecurityTrust*` ban with no country exemption, `[innerHTML]` only through the
     base product's pipes (`safeMessageHtml`, `plainTextToHtml`) or a translation (`transloco`, whose markup the
     pack and branding checks hold to English's and Angular sanitises as a plain string), as the binding's last
     pipe — and forbids inline disabling there (`noInlineConfig`: an `eslint-disable` disables nothing and is
     itself reported). The base product's lint holds DK's and GL's folders to the same rules (P3b2).
   - Backend templates and script resources are code with full privileges. Data templates and scripts reach a
     deployment only by its bootstrap or a sysadmin's import, recorded with the user; the kit's backend check
     runs over every country bootstrap and release import file and refuses process, file, reflection, `eval`,
     `Java.type` and write APIs in scripts — a guard against carelessness, not a boundary (the boundary is
     SYSADMIN plus history); the base product sets a FreeMarker class resolver that admits only Niord's directives and keeps
     `?api` off (C1 of `docs/code-data-config-plan.md`, before P1); the `MailHtml` rule binds country templates.
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
     (`check-build.mjs`, §4.8) both `dist` for secret patterns (`country-kit/lib/secrets.mjs`, since P3c1): a PEM
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
   - **The administration app gets a Content-Security-Policy before P5, in the base product, not overridable (owner,
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
     Since P3c1: `scripts/build-urls-check.mjs --absolute` (an `http://`, `https://` or protocol-relative URL) with
     `scripts/build-urls-allowed.json`, measured over the base product's own builds and the example's — XML
     namespace names, OpenLayers' KML and projection identifiers, the tile templates and the map's attribution
     link, the documentation address and library error-message links — each entry with a reason and no wildcard
     host; `assets/legal/**` is not read, its documents being branding data held by rule 4. Every country build
     runs it (`check-build.mjs`); the base product's own six builds were measured, not gated: DK's and GL's public
     sites each carry three of DK's own addresses, which move with them in P5 and P6.
     **A country's own addresses (P5k)** go in its `fe/build-urls-allowed.json`, never in the base product's list,
     which carries no country's address (R1; its note says so). The file has the base list's format
     (`{ format: 1, note?, allow: [{ pattern, files?, app?, reason }] }`, an unknown field, a missing reason or
     another format failing), and each entry is stricter: one literal `https:` URL ending with `$` — `\.`, `\/`, `\?` and `\-`
     the only escapes, any other regular-expression syntax (`^` and an unescaped `.` included) refused — whose host
     is a dotted name of letters, digits and hyphens, with no IP address, port or credentials
     (`compileCountryUrlAllowList` in `scripts/build-urls-check.mjs`). An absent file is an empty list; a link or
     other non-regular file fails. Check-build reads both lists; a URL both admit counts for the base list; every
     build prints each country entry with its reason, and an entry that admits nothing in either application's
     build is a warning; the message for a URL neither admits names both files. `.github/CODEOWNERS` names the file.
     Since P8a's C2 an entry may name its application, `app`, `admin` or `info` (another value fails): a scoped
     entry admits its URL only in that application's output, is printed with its scope and warned about when it
     admits nothing in that application's build, and a URL that only an entry scoped to the other application
     would admit fails with a hint naming that entry; an entry without `app` holds in both, as before. A `files`
     glob such as `main-*.js` had matched both applications' main bundles. The base product's own list refuses
     `app`: it holds in both applications by design.
     DK's move chose this list for its three addresses (P4's _Found on the way_ settled).
   - The info image is built from a fresh context holding only `dist/niord-info/browser` (as `browser/`), the base
     product's five nginx files and its Dockerfile, pre-compressed there by `precompress.mjs`, which since P3c1 runs
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
     the kit needs, the base tokens, the `en` pack, the catalog, the kit and its own docs, and of `scripts/` only
     the public site's checks a country calls with parameters (R8) and the absolute-URL rule's allow-list —
     `scripts/{info-separation-check,info-layout-check,info-bundle-report,info-container-test,build-urls-check}.mjs`
     and `scripts/build-urls-allowed.json`, since P3c1, none of them importing another `scripts/` file — never `fe/docs/`,
     backlogs, e2e credentials or go-live material. The end-to-end suite, `ui-apps/admin/e2e/**` with its login
     fixtures (`ui-apps/admin/e2e/auth/`), and the root `playwright.config.ts` came in with the generated workspace's
     `e2e` script in P3c2, once the suite named no country's test environment: `keycloak-login.ts` requires
     `KEYCLOAK_URL` (it defaulted to DK's test Keycloak), the unreachable-Keycloak spec blocks any `/realms/` path,
     and `app-breadth.spec.ts` reads the served backend; `zip.test.mjs` fails any file of the suite naming one
     (`t-dma`, `dma.dk`, `brs.dk`, a `login.<host>` default). Its README stays out with every README under
     `ui-apps/`. `publish.yml` fails on any path outside it and runs the
     country-word guard over the zip from its first publish (P4). As built in P4: its build job builds the zip
     with the kit's own code (`source-zip.mjs build`, Node built-ins only, no npm) right after the checkout and
     before Maven, then `verify` (any path outside the list) and `guard` (the country words, §4.8) on the archive
     it deploys; a finding fails the run before anything is deployed (`web-publish`, `harbor` and `tag` wait for
     the build job). `build` itself also verifies and guards,
     and writes nothing on a finding; `verify` stays word-blind, so a set-up never fails a published zip on a
     word added later. A finding names the file, the line and the word, never the line's text. Each allow-list
     entry of `country-kit/country-words.json` is one word and one path glob with a reason and, where the need
     ends, the phase that ends it (`until`); an entry that permits nothing fails, so the list shrinks as P5–P7
     remove files. The list itself stays out of the zip (it names every word; a country never runs the guard).
     The specs and comments that name a word stayed in the zip, each allow-listed with its reason until P7 rewrote
     them (P7b, #205; P7g, #212), rather than being kept out: a base product spec beside a file a country overrides runs against the
     country's copy (rule 3), so leaving specs out would silently drop that check. Measured on the zip of P4,
     25 entries over 47 files: the licence's copyright holder (2, permanent), doc comments and a test helper (6),
     three globs over `ui-apps/**/*.spec.ts` for the locale, language and time-zone cases (3), six unit specs
     naming DK's test host, mail domain or authority (11) and sample source names (3), all but the licence's
     `until` P7; the e2e suite and the catalog have no hit. P7b (#205) removed the 23 `until: P7` entries, the
     specs and scripts they allowed rewritten to neutral values (D17, D18), and P7g (#212) added the tree mode's
     entries (D16, D19). Since P7 the list holds 11 words and 16 entries, none `until` P7: the licence's copyright
     holder in `LICENSE`, `README.md` and `docs/coding-standards.md` (6, permanent); `Nuuk` in the public site's
     `zoned-date.spec.ts` (permanent, D18) and in `docs/backlog.md`'s clock-change item (until the bug is fixed,
     D19); DK's go-live records and `DATA-MODEL.md`'s `$id` (3, until DK's go-live); and the integration plan, a
     dated record (5, `until` P8). Publishing these sources publicly while
     `niord-ng` is private is intended; **the list is loosened when the repositories go public (owner,
     2026-10-19)**.
   - **Pack and base product bumps are never automerged (owner, 2026-10-19):** the bump PR prints every changed value
     per application and flags values with markup or URLs. **Their cadence (the owner, 2026-10-25; the Dependency
     Dashboard approval dropped by the owner's ruling of 2026-11-01; the bump by push, decision 133,
     2026-11-03):** the Niord base bump app opens the bump PR at every publish, minutes after the release, and
     moves the one open bump PR to each newer version, never automerged, the review and the merge being the
     human gate; since the app's PR moves the pom but not the lock's base pin `{version, sha256}`, which set-up
     then refuses, a small workflow in each country, the Base bump workflow, re-pins the lock on the
     `base-bump/` branch (§4.12).
     **Green means no task (the owner, 2026-11-01):** the upgrade report is posted on the bump PR, and every
     action set-up cannot check (the `API-CHANGES.md` actions, a changed template to merge, a workflow file to
     copy by hand, a new bootstrap release folder) is a box under `## Human actions` in one sticky comment of
     the Base bump workflow, which holds the report too, with the commit status `niord/upgrade-actions` red
     while a box is unticked — not the PR description, which the app rewrites at every move (decision 103,
     the map's D1); a release folder's import stays a human decision. Built in the Renovate step (2026-11-02,
     `country-kit/templates/base-bump.yml`, §6 _The Renovate step_) and the bump by push (§6 _The bump by
     push_).
   - A version marked in `security.json` is raised to every country at once; its new keys may fall back to English
     in a printed SECURITY block limited to those keys; the time from publish to each country's rebuild is a
     tracked number. With the cadence above every version, a security version included, reaches every installed
     country as its bump PR within minutes of its publish; the Base bump workflow labels the pull request
     `security` when the report lists a new `security.json` fix (the Renovate step, 2026-11-02).
   - A home withdraws a bad pack with `pack.yml`'s withdraw dispatch, which lists its digest and the reason in the pack's withdrawn marker on GHCR (§4.5) — the channel every consumer reads anonymously; its `pack-releases.md` is the human record.
     `setup.mjs` reads the marker with every foreign pack it sets up and fails a pin listed there, printing the reason; a published pack without a marker fails as unreadable.
     As built in P5k: set-up reads the marker anonymously before anything else of the pack, and a listed pin is the
     first failed check, `pack <lang>: <reference> is withdrawn (<date>): <reason>; move the pin with node setup.mjs
     --update-pins`, its language left out of that run's pack check so that one line names it, and on a version change
     it is the upgrade report's first To-do item (§4.12). The pull itself is anonymous by construction: the client
     reads no token for a pull and sends credentials only to a token endpoint on the registry's host, and only when
     it publishes or withdraws; then it asks for `pull,push` on every request, reads included, its own scope taking
     precedence over the registry's challenge, and a write (`POST`, `PUT`) follows no redirect, failing on one
     (the review of 2026-10-23, §6 P5k). What a consumer verifies: the manifest's SHA-256 against the pin's digest (at most
     64 KiB), exactly one layer of the pack's media type and a config naming the pinned language, the layer's
     SHA-256 and size against its descriptor (a hard cap besides), then the archive's layout and caps
     (`readPackArchive`), and then the pack check against the consumer's own catalog; a redirect of a blob to another
     host carries no credentials. Hosts: `ghcr.io` over HTTPS, or a loopback registry over HTTP, which only the kit's
     tests and the harness write into a lock; set-up accepts a loopback pin wherever a lock holds one, since the
     digest guards the bytes and the lock's change shows in review (R190).
     The GHCR namespace: any repository's workflow token in the `SIFA-DK` organisation can create
     `ghcr.io/sifa-dk/niord-lang-<lang>` before the pack's home does (a package is created by the first repository
     that pushes to it, and linked to it); this closes at the home's first publish, and until then the pin review —
     a first pin compared with the home's `pack-releases.md` — is the guard.
   - `--accept-override` prints, per `basedOn` file, the recorded and the current fingerprint and the lines in which the
     country's copy differs from the base product's current file (P3b1); and, since P3c2, the base product's own diff
     of each changed file since the recorded fingerprint, from the previous version's file — the zip of the last
     set-up record's version that the stub's fetch left in `.niord/`, read only when its sha256 is the record's and
     the file's fingerprint the recorded one, else one line saying why it is not available — as the upgrade report
     (§4.12) shows it. The
     country CI's job summary lists every change to the override, escape-hatch, text-override and dependency
     files against the previous `main`; `.github/CODEOWNERS` names their owners — a gate only with the branch rule *Require review from Code Owners*, which GitHub Free lacks for a private repository, so until the repositories are public the file documents and this job summary is the gate; the go-live recheck reviews every
     override and escape-hatch entry of a release.
   - Before a filtered history is pushed (P5, P6) and before any repository goes public, a full-history
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
   §4.11; §6 _The bump by push_). Without the app a person pushes `base-bump/<version>` with the same one
   change and opens the PR; the app leaves such a PR alone.
2. The country's Base bump workflow (`.github/workflows/base-bump.yml`, the Renovate step, 2026-11-02) runs on
   it, since the app moves only the pom (the owner, 2026-10-25): it reads release `v<version>`'s notes first
   and compares them with the two downloads; only when they agree does it pin the lock's `base {version,
   sha256}` and `kit {sha256}`, run set-up, copy the stub and the launcher, move every pinned pack to its home's
   newest pack (`--update-pins`) in the same PR, named in the comment as a line, not a box (F8-D10, the
   recommendation, the owner's call), commit as `github-actions[bot]`, dispatch CI on that commit and post its
   comment and the status `niord/upgrade-actions`; a move posts the comment again, keeping the tick of a box
   while what it is about is unchanged: its text, and for a template's box the kit template's content, named
   by a hidden marker at the box's end (F8-D11, tightened by F8-5 after the review of #271 found a tick
   hiding a template changed again at a later version). Every other template the kit changed (`CODEOWNERS`,
   `dependabot.yml`, the `be/` init folder, one added or removed) is named in one line of the comment and of
   the upgrade report, with no box and no red check (F8-6, decision 147, 2026-11-04). The country's own token
   reads the notes of the public `NiordOrg/niord4`; notes that cannot be read fail the run, which pins nothing
   (2026-11-17; while niord-ng was private the comment's one item was the pin by hand, decisions 105 and 106).
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
| the base product changed the **set-up stub** (`templates/setup.mjs`) | since the Renovate step the Base bump workflow copies it; in a bump without the workflow the country copies `.niord/kit/templates/setup.mjs` (before the kit package, KM-6: `.niord/base/country-kit/templates/setup.mjs`) over `fe/setup.mjs`, as `API-CHANGES.md` says for that version: on a version change set-up reports the stub as a failed check whose copy command is the report's first To-do item, and stops before staging (without a version change it refuses the stub at once) | rare; a breaking change by `API-CHANGES.md`'s policy (since the review of PR #184) |
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
beside it, `niord-upgrade-report.json` (RN-1); the Base bump workflow posts the report whole in its comment on
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
  the versions crossed (in To do until the review of PR #184, which found the heading true only of the rest);
- **new for you:** extension points added since the previous version — and, for each override, whether a new
  hook now covers it, so the override can be dropped;
- the dependency changes: base product framework versions moved, country packages affected.

**Where and when (P3c2).** The report is `fe/.niord/niord-upgrade-report.md` (`country-kit/lib/upgrade-report.mjs`),
under the generated `.niord/` and so never committed; set-up prints its path. The previous version is the last
set-up record, `.niord/build/niord-build-report.json` (its `base.version` and `base.sha256`), which set-up writes
only when every check passed, so a red bump keeps comparing with the last good version; the report is written on
every version change after the checks, **also when they fail**, since a red bump is when it is needed, and not at
all without a record (a first set-up says so), unless `--previous <version>` names the previous version (RN-1),
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
`npm ci`). A withdrawn pin was among them until P5k, which fetches pinned packs: it is now a To-do item naming the
pack, its reference, the home's date and reason and `node setup.mjs --update-pins` (or `--update-pins <lang>=<tag>`). Since the Renovate step (2026-11-02) the
Base bump workflow makes the record a fresh CI workspace lacks by a set-up at the base commit and, when that
fails, runs set-up with `--previous <old>`; it posts the report on the PR, in its comment.

A country that keeps to extension points and translates its own packs gets a green bump almost every time;
the cost of an upgrade grows only with what it chose to override, and the report says so in numbers.

### 4.13 What the base product gives, and what a country owns

The division of labour between the core base product's maintainers and a country — the contract a new country
signs up to, repeated in the P8 guidelines.

**What a country gets for free, with every base product version:**
- all base product functionality, fixes and security fixes of both applications and the backend; from P7
  audio promulgation among them: its type in the base product's bootstrap and its editor and viewer in the
  administration app (the owner, 2026-10-24; §6 P7);
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
nothing changed in `niord-ng`.

Since N2 (§6 P8, _N2 as built_) the list is the plug-and-play path, and for most countries steps 1, 2 and 9 are all
of it: the release, the answers, `init` and set-up, the launcher and how to see it worked, which are the Quick start
of the short guide, `country-kit/docs/country-guide.md`. Steps 3 to 8 are its _Make it yours_, each only where the
country differs from what `init` wrote, beside the branding and the PDF logo every country sets (the reference
behind them is `country-kit/docs/country-guide-in-depth.md`); steps 10 and 11 are its _Deploy_, followed by its
definition of done and _Before go-live_. An AI agent can do steps 1, 2 and 9 from an empty folder by
`country-kit/docs/set-up-prompt.md`. The guides give the commands; this list gives the steps.

1. **The repository and the release.** An empty folder, `niord-<cc>`, with `git init`. The one machine file is
   written by hand: `~/.m2/settings.xml` with a classic `read:packages` token under the server id `github`, which
   set-up, the backend build and the launcher read (GitHub Packages refuses an unauthenticated read even of a
   public package, so the token stays needed once `niord-ng` is public). Release `v<version>`'s two assets, the
   source zip and the kit package, are downloaded into the root's `.niord/` (`gh release download`; while
   `niord-ng` is private a base product maintainer gives the files and the two lines), the two lines of the
   release's notes are written to `.niord/SHA256SUMS` and checked with `sha256sum -c` (§4.11 rule 2), and only the
   kit is unzipped, into `.niord/kit/`. No pom, wrapper or Maven is needed before `init`, which writes the backend
   (step 8; before N2 it was written by hand from the country guide's list, with the complete minimal `be/pom.xml`
   the review of #229 added, _N2 as built_). Another country's `be/` or `.github/` is not copied: `init` keeps a
   file that exists, so a copied `.github/` would keep the other country's workflows. A country may carry a file
   mirror of GRAD's SECOM library, `be/third-party-maven/`, listed first in its pom (DK and GL do); the owner's ruling
   of 2026-11-01 keeps it so for now, and where `secom-v2-core-jakarta` lives for good is a later ruling (F5, the
   mirrors' removal, waits for it; `be/docs/potential-errors.md` BUILD1). `init` writes no `be/third-party-maven/`,
   the in-depth guide gives the copy step, and whether the kit ships the mirror to a new country is the owner's open
   question (decision 126): until then a new country's first build on an empty Maven cache needs GRAD's repository
   up.
2. **The answers, `init` and set-up.** `node .niord/kit/init.mjs <cc> --sha256-file .niord/SHA256SUMS` from the
   repository root (or with `--answers <file>`; §4.8) checks both zips against the two lines, then asks the other
   fifteen answers (all sixteen without the code). Required are the ISO 3166-1 alpha-2 code in lower case, the name, the organisation, the time zone, the
   map centre's latitude and longitude and the `CODEOWNERS` owners; the rest have standard defaults: no language
   beside English, no producer code, zoom 6, and a first domain `<cc>-nw` of main type NW with its series
   `<CC>-NW-${number-3-digits}-${year-2-digits}`, YEARLY (`<cc>-nm` and Notices to Mariners with NM). A refusal
   names every problem and writes nothing; otherwise `init` writes the whole country (§4.8; 41 files on an empty
   folder) and pins both zips' hashes in `fe/niord-pins.lock.json`. Then `(cd fe && node setup.mjs)`, with
   `--update-pins` when languages were answered, fetches both packages through `be/mvnw`, checks them against the
   pins and generates the workspace; from then on `setup.mjs` keeps the base product current. The first commit
   gives the three scripts their exec bits (init's closing line). (Before N2 the pom and the wrapper were written by
   hand first, the source zip was fetched with `dependency:copy` and unpacked into `fe/.niord/base/`, and `init`
   took the two hashes as flags and wrote a frontend skeleton; before KM-6 the kit came inside the source zip, and
   since NG-B the source zip carries nothing of the kit; _KM-6 as built_, _NG-B as built_, _N2 as built_.)
3. **The manifest:** `init` wrote it from the answers: English and the answered languages as the UI and content
   languages of both applications, the default `en`. Languages per application, which packs are pinned and which
   are the country's own, and another default language are the in-depth guide's.
4. **Packs.** For each pinned pack, its name in the manifest (with its `owner` when not `sifa-dk`) and its full reference, tag and digest in `niord-pins.lock.json`
   (`node setup.mjs --update-pins`, built in P5k, §4.11 rule 2; before a first pin is committed, the digest it wrote is compared with the one the home's `pack-releases.md` records, §4.5 — while the home is private, as its maintainers give it); set-up stages every pack's backend half, home packs included, into `fe/.niord/packs/<lang>/backend/`, and the backend build reads it there: for every pack but `en` the pom copies the staged pack from `fe/.niord/packs/<lang>/` and its `require-staged-packs` check refuses a build that runs before set-up (P8a, NG-1, #217; the kit README's *The backend build*), so a pinned pack's backend half reaches the jar as a home pack's does. For a language
   no country has yet (Norway's `nb`): `fe/packs/nb/` with `pack.json`, the flag and its attribution;
   list it under `contentLanguages` until `pack check` says the text is complete. If it should be
   published, `pack.yml` (written by `init`) publishes it as `ghcr.io/<owner>/niord-lang-nb`, `<owner>` the country's GitHub owner, with the country's own workflow token (a consumer names that owner in its manifest), and
   the country sets the package public once — nothing is asked of the base product or of anyone else. An answered
   language is a published pack pinned from its home by set-up's `--update-pins` (when N2 was built, `da` from DK was
   the one published pack: N2-R's gap 5, _N2 as built_); the pom `init` writes carries, per language, the pack's two
   copies and the flag's `<available>` line only, so a pinned pack without a backend half builds.
5. **Branding:** `init` writes the skeleton the schema requires and no branding text (§4.4); the country fills it
   in — logos, favicon and touch icon, the legal documents — and once an application has a branding text, each of
   its UI languages needs its `branding/<lang>.json`, with `branding-unaccepted.json` listing what a reader of a
   language has not accepted yet. Since N2-5 the public site's default footer offers the logo, the accessibility
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
   key the base product's `${user.home}/.niord` wins over `NIORD_HOME`; since the E-fixes of 2026-11-03,
   SETTINGS-6, the owner's ruling, decision 138, `niord.home` follows `NIORD_HOME` whenever no `niord.home`
   property or variable is set, so the key is the countries' convention, which the settings check still
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
   from `sha256sum .niord/kit/backend/img/company-logo.png` (the kit package carries the image since N2-1).
9. **Run it:** `./start-local.sh` from the repository root (the kit README's *Running it locally*), the kit's
   `templates/start-local.sh`, which `init` writes since P8a (NG-10) and a country copies byte for byte;
   `--sample` runs the base product's sample instead. What it does, in order: it reads the country code and
   name from `fe/niord-country.json`, the base product version from `be/pom.xml`'s `<parent>` by the stub's
   exported `parentVersion`, and the deployable from the pom's own `<artifactId>`; checks the prerequisites (a
   JDK 25 with `jar`, Node with npm, curl, a `read:packages` token in `~/.m2/settings.xml` under the server id
   `github` or `NIORD_LOCAL_BASE_REPO`, a scratch Maven repository with a local build of the pinned base, and
   Docker running last); fetches `org.niord:niord-local-run:<version>:zip` (P8a, NG-9: `scripts/bootstrap.sh`,
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
   client (since N2-4: the five roles user, editor, admin, sysadmin and publication-curate, editor, admin and
   sysadmin composite, and the Sysadmin group's mapping; a client that exists is never changed, and nothing is
   deleted); on a database whose marker reads pending posts the bootstrap (`POST /rest/bootstrap` with a
   sysadmin's token, through `bootstrap.sh`, which waits for the marker): first the base product's archive, then
   the country's, `be/bootstrap/` zipped with that folder as the archive's root, in one run
   (`docs/code-data-config-plan.md` §7.2); and then starts `start:admin` and `start:info` on 4200 and 4400, whose dev
   proxy (`fe/proxy.conf.mjs`) sends `/rest` to the port `NIORD_BE_PORT` names. The backend has no CORS, so the dev
   server reaches a backend only through this proxy. `--sample` posts the base archive alone on `niord_sample_<cc>`
   and loads the sample, with the country's settings and texts (§6 P8's country launcher). It worked when the
   launcher's closing lines list the addresses, the logins and the three logs; `sysadmin` / `sysadmin` sees the
   Sysadmin menu with the domain chosen; `/rest/settings/web` gives the answered languages and `/rest/domains/all` the
   domain; and the public site shows times in the answered zone. The short guide's _Init, set-up and the launcher_ and
   _How to see it worked_ tell this step for a person, and the in-depth guide's _Running locally in detail_ the rest,
   with the rule that the backend's JVM runs in UTC however it is started.
10. **Push:** `init` writes `.github/workflows/ci.yml` (since N2-3: the backend's tests and the frontend's, green
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
    (`docs/code-data-config-plan.md` §7.2). Step 10's info image has nowhere to run until the base product's
    chart gives it a deployment and an ingress (`be/docs/cicd/implementation-plan.md`, Phase 8).

A Faroese deploy (`fo` and `da`) is the same list with `fo` as its home pack and `da` pinned from DK; it
follows DK's pace for Danish (a base product version with new keys needs DK's matching pack, or hatch entries).
`fe/docs/country-deployments.md` was trimmed in P7e (#209) to the base product's side, with pointers to the kit
README and this section (D9). The guides are the kit's, in `country-kit/docs/`: `country-guide.md`, the short
standard path (Quick start, Make it yours, Deploy, the definition of done and Before go-live);
`country-guide-in-depth.md`, the reference behind it; `set-up-prompt.md`, the prompt a person gives an AI agent to
do the Quick start (N2-7, N2-8); and `operators-guide.md` for the data an operator sets up in the running system
(P8a, NG-12).

**The dry run (P7h, 2026-10-25; D27).** Steps 1-9 were run from scratch by this list and the kit README alone
for a fictitious country `qq` (`en`, `da` pinned from GHCR, a new home pack `nb` as a content language), in an
empty folder outside the tree, against a `kit:zip` of P7's tree and a backend stripped from `niord-gl`'s `main`;
then `npm test`, `npm run lint`, `npm run build` and `npm run image:info`, all green in the end (§6 P7, _As built_). Its
gaps are corrected in steps 1, 4, 8 and 9 above and in the kit README. Steps 10-11 wait for the deploy step;
steps 1-2 and set-up and build are repeated against the published zip after the merge.

**The routes (N2-R, 2026-11-03).** Steps 1, 2 and 9 were run from an empty folder against a `4.0.999-n2` build of
the feature branch, by the short guide alone (the Person route, a fictitious country `qr`, with one change of _Make
it yours_ after them: the PDF logo override) and by the set-up prompt alone (the Agent route, `xr`, with `da`
pinned). Both reached their end, the Agent route with one confirmatory step outside the prompt; their eight gaps,
all in the documents, were fixed in the guides and the prompt (#268). The detail, and what stays unproven, is in
_N2 as built_ (§6 P8).

---

## 6. Implementation

**Shape.** Base product work on `feature/fe-country-split` in `niord-ng`, as self-merged sub-PRs after
`./verify-local --base origin/feature/fe-country-split`, merged to `main` as **PR A** (P1–P4); country work as
PRs in `niord-dk` (P5) and `niord-gl` (P6), each in a frontend part (after P4) and a backend part (after
`docs/code-data-config-plan.md` C2–C5b are published; that plan's C6); P9's base product part, with that plan's C7, went ahead in its own PR into `main` (#192, 2026-10-23), and the base product's clean-up went alone as **PR B** (P7, #204) from `feature/p7-base-lets-go`, with the guidelines (P8) after it. Staging keeps running
throughout: the base product published the `dk`/`gl` bundles until P7 (P7d, #208, removed them from `publish.yml`),
but no country image unpacks them since P5b and P6b (each country's `image.yml` builds its own); from P7's version, 4.0.97,
`niord-web-admin` carries `en` only (§6 P7, D11).
**From P5 the base product's DK copy was frozen** (from P6 GL's): a DK or GL change is made in its repository; the
base product deleted its copies in P7 (P7f, #211).
**Order with `docs/code-data-config-plan.md` (owner's question, 2026-10-20).** Its base product part goes first, before P1:
C0 (its rules written into the `CLAUDE.md` files) and C1, one small PR into `main` that stops the overwrites
(both Reload functions, which can overwrite production data today, and the `?path=` preload deleted, the seed
default off, the FreeMarker hardening, the importing user recorded in history; that plan's §11 lists it in full); C2–C5b on their own feature branch alongside P1–P4 (C5b is the
base product's half of the country contract: the security test group, the start-up guard, the override check,
`be/docs/country-api.md`), merged and
published before the backend parts of P5 and P6. P1–P4 and the frontend parts of P5 and P6 wait for nothing
there. The backend parts of P5 and P6 are its C6, and P9 carries its C7: a country's backend moves only once
the base product has decided what that backend carries.
**Record.** After each phase (each part of P5 and P6 on its own) the status line gets the date, the number of its
pull request (in the country repository for P5 and P6) and the version that first carries it.
**Staging at the end (owner, 2026-10-21).** Every staging step of this plan — "DK staging boots on them" in P5
and P6, P7's "reaches staging" — runs once after the last phase of both plans, in the step
`docs/code-data-config-plan.md` §11 names *Staging at the end (owner, 2026-10-21)*, because the GitLab
repositories are not reachable from the workstation the plans are carried out on. Until then each such item is
checked locally and recorded as *met locally; staging at the end*.

**P1 — contracts in the base product (still multi-country).**
- Languages: fallback to `en` in both applications and the administration loader's merge (§4.6); the language
  lists and names out of shared code (`UI_BUNDLE_LANGUAGES`, `LANGUAGE_<XX>`, `languageNameKey()`,
  `InfoConfig.languageFlags`, `environment.languages`) into each application's hand-kept
  `country-specific/<cc>/language.config.ts`, which the kit's generator replaces for a country in P3 (§4.3); a language whose flag the build
  does not ship renders its code as text (today the administration app invents
  `assets/img/flags/<code>.png`, `language-settings.service.ts:56-62`, and the base product build against the
  Danish demo content would show broken images; that content was the demo archive `start-local.sh --demo`
  applied from `docs/code-data-config-plan.md` C4.1, which deleted `niord-app`'s demo profile, until P9, whose
  sample replaced it).
- Branding (§4.4): the administration app's branding merge and legal-document dialog; the DEMA texts and
  logos move into DK and GL branding inside the current tree; the base product's build shows the Niord mark
  and no authority footer.
- Environments: the administration app's base product environments made neutral; DK's values move to its
  configurations' replacements.
- The leaks fixed in place: the administration GL collisions (four, byte-identical) end because the DEMA
  logos move into DK's and GL's branding (`ui-apps/admin/public/assets/<cc>/branding/`), so no base copy is
  left for the GL overlay to collide with and no ignore entry is needed, and GL's copy of the Niord mark is
  deleted (`niord_white.png`, not brandable, §4.4); the default and DK configurations ignore `assets/gl/**`;
  `flags/README.txt` stays beside the flags as their attribution and every administration asset input
  ignores it, so it leaves every build's output; info `kl-unverified.json` / `da-unaccepted.json` leave
  `public/` for `ui-apps/info/i18n-status/` (outside `src/` too, whose Tailwind scan reads every file); the
  unused `branding/logo.png` / `logo-da.png` (not `logo-gray*`) go from both info overlays.
- The checks of §4.8 that need no kit — one source per output, bundles equal languages, no external URL —
  in `fe-ci.yml` / `info-ci.yml`, and in `verify-local`, which runs them for the sub-PRs into
  `feature/fe-country-split` (Frontend CI and Info CI run only on pull requests into `main`).
- **The administration app's Content-Security-Policy** (§4.11 rule 5), sent by the backend, not overridable,
  with the e2e smoke run under it — a prerequisite of P5 (owner, 2026-10-19), met on PR A's merged tree on
  2026-10-22, 8 of 8 (§6 P4, _Review of PR #184_). The smoke run is `csp.spec.ts`
  and `auth-smoke.spec.ts` against a non-production build (`--configuration local`, because production builds
  refuse the e2e token stub; a country's set-up holds its production environment to `production: true` since the
  review of PR #184, `country-kit/lib/environment-check.mjs`, so that holds for a country's bundle too) that the
  backend serves, with a guard in the e2e fixtures that fails a test on
  any policy violation in the console; that the policy follows a settings change is
  `WebAppContentSecurityPolicyITTest`. The backend headers for
  `/assets/legal/**` and `/assets/branding/**` (`sandbox`, `nosniff`), and the same two in the public site's nginx template (`location ^~ /assets/legal/` and `/assets/branding/`).
- The base product's workflow hardening (§4.11 rule 1): the maintainer list on `retract-version.yml` and the
  break-glass path, the two-token split in `main`-only environments (`.github/maintainers`, the environments
  `publish` and `retract`). As built after PR A's merge (owner, 2026-10-22, §4.11 rule 1, _Until the country
  split_): `publish` exists with its `main`-only rule and the deploy steps use the old `NIORDORG_PACKAGES_*`
  secrets; the machine user, its two tokens, `retract` and deleting the old secrets come with the country split.
  PR A's own merge (40d55c94) published nothing: its run stopped at the gate, `publish` missing, as designed.
  The gates and the maintainer checks are tested since the
  review of PR #184 by `fe/scripts/release-workflows.test.mjs`, which runs the workflows' own bash with a fake
  `gh`. Their live reads — `gh api …/environments/<env>` and `…/deployment-branch-policies` with the workflow
  token (`actions: write`) — run for the first time in PR A's first publish run, and retract's in its first
  dispatch: watch the gate's log there. A 403 on `environments/<env>` means the token needs another permission;
  the gate then fails red and publishes nothing. Until the owner's steps are done, a missing environment, or one
  that allows another branch or protected branches, fails at the gate; an environment with the right rule but
  no `NIORD_PUBLISH_TOKEN` / `NIORD_PUBLISH_USER` passes the gate and fails at the first deploy, after the zip
  checks and the reactor build (about ten minutes). Nothing is published either way. The owner checks `harbor`
  with `publish` and `retract`: `require_main_only harbor` holds it to the same rule.
- Verification: the six production builds' file lists against §10 — every "ships, not needed" row gone; the
  checks fail on crafted configurations; a DK session's network log before and after the fallback change
  (the `en` bundle now loads beside `da`), taken on the local stack, never the test environment: `npm run
  start:local:dk` against the generic backend on an empty scratch database with `da` enabled in its web
  settings (a session on DK's data needs DK's backend from `niord-dk`, whose own bundles carry `da`; the
  generic backend refuses a database whose default language it has no bundle for, DICT1). Taken on 2026-10-22,
  after P3a, whose staging fills every missing key from `en` (§4.6, _At runtime a missing key showed English_): the
  log shows the `da` bundle alone, with no `en` beside it (P4's record).
- Done when: no build ships a resource it does not need, and CI says so.

**P2 — packs.** Carried out as two sub-PRs: **P2a** — the packs in place, the staging step for the base
product's own builds, the specs on `en` (the first and third items below, and the staging of the second);
**P2b** — the catalog, `pack check` / `migrate` / `prune` / `accept`, the language-specific specs as
pack-check rules, the pack check wired into the `dk`/`gl` builds, and the rules and docs of §7 (the rest of
the second item, and the fourth). P2a corrects only the paths and file names the documentation states.
- `fe/languages/{en,da,kl}/` in pack shape (§4.5), moved with `git mv` from `ui-apps/*/public/assets/i18n/`
  and the flags, with their attribution (GL's `kl.svg` rasterised to `img/flags/kl.png` at the 64×64 of the
  other two, letterboxed like them; a conversion, so both `kl.svg` copies are deleted, not moved, and the
  SVG's source, its old path and the render command are in the `kl` pack's `ATTRIBUTION.txt`); the `ai_` prefix converted to `status/unaccepted.json`;
  `fingerprints.json` seeded from today's English for every key a pack has; `transloco.config.ts` (the keys-manager's tooling file) gets `rootTranslationsPath: 'languages/en/i18n/admin/'`
  and `langs: ['en']`, never the staged copies in `fe/.niord/build/`; its comment says the other languages are
  packs held to the pack check and, from P3, names the manifest instead of `language.config.ts`.
- `catalog` (its `backend` section from niord-base's English bundles wherever they are, so it waits for no phase
  of `docs/code-data-config-plan.md`; `renamedFrom` from `fe/languages/en/renames.json`, §4.6) and
  `pack check` / `migrate` / `prune` / `accept` with tests — every row of §4.6 has a negative
  test; the staging step (§3.2 step 4) used by the base product's own builds too — the administration
  loader's `en` merge (P1) and `en` in every build stay until the kit's `setup.mjs` runs the step in P3.
- The 16 base product spec files that read `da`/`kl` today (12 admin, 3 info, the info `testing/test-config.ts`)
  move to `en` or the `zz` fixture; the language-specific ones (`date-pattern-guard`, `kl-unverified`, the
  endonyms of `country-config.spec.ts` and the parity spec's `DATE_*` arities, its `ARITIES`) become pack-check rules. Against the
  tree: of the 12 administration files, 11 drop their Danish halves and `i18n-key-parity.spec.ts` stays,
  re-pointed at the packs, as `da`'s completeness guard until the pack check replaces it in P2b; the 3
  public-site files (`date-pattern-guard`, `parity`, `kl-unverified`) are all language-specific and stay,
  re-pointed, until P2b; `test-config.ts` moves to `en` and an in-memory `zz` fixture (not P3's `zz` pack), and
  the public-site specs that switched to `da`/`kl` words through it follow. `da`/`kl` as a content-language code
  in message data or an HTTP path in a loader mock is data and stays.
- **Translations in base product PRs until the packs move.** Until P5 (for `kl` until P6) a PR that adds a key
  still writes its Danish in the in-tree `da` pack — and, for the public site, its Kalaallisut in `kl` — listed in
  that pack's `status/unaccepted.json` until the product owner accepts it (the successor of the `ai_` prefix and
  the public site's lists); the pack check fails the base product's `dk`/`gl` builds on a missing key, as the
  parity specs did. The CLAUDE.md files, `fe/docs/definition-of-done.md` and the `niord-review` skill say so
  since P2b (§7).
- **P2b as built.** `fe/scripts/catalog.mjs` and `fe/scripts/pack.mjs` sit beside `stage-languages.mjs`, whose
  `readPack`, `packFile` and `fingerprint` they use; P3 moves the three into `fe/country-kit/` together (§4.8),
  a folder P3 creates with its zip-include list (done in P3a: all three, with their tests, are in `fe/country-kit/`). They are plain Node: `checkPacks`, `buildCatalog` and the tools
  need built-ins only; `pack.mjs check` reads each configuration's languages through `build-contents-check.mjs`
  (TypeScript) and so runs from the workspace, never from the info image. Their `node:test` tests run under
  `test:guards`: `pack.test.mjs` has one negative test per pack row of §4.6 and one
  for the status-file row, a passing fixture, and tests of `migrate`, `prune` and `accept`; `catalog.test.mjs`
  reads the backend bundles from both locations and refuses one found in both. The branding rows move to P3.
  Staging strips to the catalog and stages a broken value as English (§3.2 step 4); `pack.json`'s `locale` and
  `label` rules (§4.11 rule 4) are checked by `readPack`, so staging refuses them too.
  - The specs that went: `i18n-key-parity.spec.ts` (the missing-key row; an extra key is stripped, not failed,
    R4); `date-pattern-guard.spec.ts` (the date-pattern row; its English half moved into `en-bundle.spec.ts`); the
    `kl` and `da` halves of `kl-unverified.spec.ts` (the status-file row, which also gives `kl` an accepted state:
    `pack accept`); the endonym test of `country-config.spec.ts` (`contents:check` rule 9: every language a
    configuration names, in both applications, has a pack whose `pack.json` label and locale it repeats). What
    stayed reads English or country branding only: `parity.spec.ts` as `en-bundle.spec.ts` (the English bundle
    has words and no empty value, 12 months and 7 days, its date patterns use the renderer's tokens, the gone-key
    source scan) and `kl-unverified.spec.ts` as `branding-unaccepted.spec.ts` (the branding list). The renderer's
    tokens are data, `ui-apps/info/src/app/shared/utils/date-pattern-tokens.json`, read by `zoned-date.ts` and the
    catalog alike.
  - The check runs first in `build:dk`, `build:gl`, `build:info:dk` and `build:info:gl`, for exactly the
    languages of that configuration, so Frontend CI, Info CI and `verify-local` run it with no step of their own
    (Info CI's and `verify-local`'s path rules name the two scripts); the start scripts and the English builds do
    not run it, and neither does the info image build, which Info CI's checks job precedes.
  - **The flags are drawn here (owner, 2026-10-21):** no third-party flag remains. `en` (the Union Flag) and `da`
    (the Dannebrog) are drawn as plain SVGs from their official construction, as `kl` was; each pack keeps its
    drawing at `img/flags/<lang>.svg` beside the PNG (never staged; `kl.svg` is back, in the pack this time), and
    all three PNGs are rendered with `kl.png`'s pinned command (64×64, the flag 60×40 at (2,12), the size and
    place the three share), every `ATTRIBUTION.txt` recording the drawing, its sources, where it departs from the
    official ratio and the command. The backend's `img/flags/en.png`, which `message-support.ftl` renders, is the
    same bytes as the `en` pack's, and its `README.txt` says so. The owner kept this geometry on 2026-10-22, every
    flag filling the shared 60×40 box, for now; the alternative, each flag's official ratio letterboxed in the
    box, is one wrapper change and one re-render.
- Verification: the six builds' output unchanged against P1 except where this phase says; both suites;
  `pack check` green for `da` and `kl`.
- Done when: every translation is a pack and the base product's specs need only `en`.

**P3 — the kit and the example country.** Carried out as five sub-PRs: **P3a** — the kit in place and a
country that builds from outside the base product tree: `fe/country-kit/` with the moved pack tools, the
manifest schema, `seams.json`, `branding.schema.json` and the branding rows, the zip build, `setup.mjs` and its
steps, `init`, the example country `xx` with pack `zz`, and the applications shipping exactly their
`uiLanguages` with no runtime fallback language; **P3b** — functionality and the country API, in two parts:
**P3b1** — country routes and menu entries, the component-slot registry and behaviour hooks
(`country.providers.ts` generalising `promulgation.providers.ts`), guarded overrides (`--add-override`,
`--accept-override`, `basedOn`, the protected-list refusal, `security.json`), the base product's specs of an
overridden file run against the country's copy and §4.3's orphan-file check; **P3b2** — the country API with its
generated mapping, its lint enforcement and the base product's security lint rules on country files,
`API-CHANGES.md`, the base product's DK/GL seam files switched to it, country dependencies (R12) and the
text-override layer (§4.10); **P3c** — the info image and the remaining checks: `image:info` packaging a built
`dist`, the separation and layout checks with parameters and each country's bundle baseline, the remaining
checks of §4.8 on every country build, the secret-pattern scan, the new-country acceptance tests (untouched
`init`, upgrade), the upgrade report (§4.12), and the generated workspace's `e2e` and `image:info` scripts and
Playwright configuration — carried out as **P3c1** (the info image from a built `dist` and `image:info`, the
public site's checks with parameters and each country's bundle baseline, the remaining checks of §4.8, the
secret-pattern scan) and **P3c2** (the acceptance tests, the upgrade report, the `e2e` script and its
Playwright configuration).
- **Priority inside the kit**, so it stays no bigger than it needs to be: first everything that guards
  correctness and security — hash verification, the pack check's failing rows, the protected lists, the
  base product's security tests in country builds, one source per output, the build/push split, the upgrade
  test; then the upgrade report (§4.12); the conveniences (redundant-override hints) last and as needs
  appear — rename migration, prune and accept exist since P2b and move in with the pack check. Every check has
  one negative test in the example country, so the kit's size is visible and each rule earns its place.
- `fe/country-kit/`: `catalog.mjs`, `pack.mjs` and `stage-languages.mjs` moved in from `fe/scripts/` with their
  tests, the branding rows of the pack check (§4.6), `init`, the `setup.mjs` steps, the manifest schema, `seams.json`, `branding.schema.json`,
  `workspace-files.json`, `zip-include.json` and the zip build P4's `publish.yml` reuses (§4.11 rule 6), the generators; the example country `xx` with pack `zz`, set up and built in the
  base product CI; the info image packaging a built `dist`, the separation and layout checks with parameters;
  the base product's DK/GL seam files switched to the country API (`@niord/admin/api` / `@niord/info/api`, §4.3).
- Languages: `setup.mjs` runs the staging step (§3.2 step 4) for countries; the administration loader fetches
  only the requested bundle, Transloco's runtime fallback language goes in both applications, and every build
  ships exactly its `uiLanguages`; a country workspace's language configuration is generated (§4.3).
- Functionality (§4.9): country routes, menu entries, the component-slot registry and the behaviour-hook
  pattern in both applications (`promulgation.providers.ts` becomes the first hook of `country.providers.ts`);
  guarded overrides in `setup.mjs`. The example country uses each once — a page, a menu entry, a hook, a
  slot, an override — and the base product CI fails an override whose `basedOn` no longer matches.
- The country API (`@niord/admin/api`, `@niord/info/api`), its lint enforcement and `API-CHANGES.md`;
  country dependencies (R12) with the example carrying one; the text-override layer (§4.10); the
  acceptance tests (untouched `init`, upgrade) in the base product CI.
- `verify-local` gains what this phase adds to the base product CI — the example's set-up and builds, the
  acceptance tests and the checks of §4.8 — so the sub-PRs verify them before PR A.
- Verification: the example builds both applications and its info image in the base product CI; its unique
  utility is in its CSS, its face only in its `media/`, its specs and lint run; the country specs of today
  (`branding-images`, `country-config`, `country-index`, `country.providers`, before P3b1 `promulgation.providers`) are kit checks or the
  example's specs.
- Done when: a country builds from a folder outside the base product tree, in the base product's own CI (through `verify-local` until PR A runs it in CI).
- **P3a as built.**
  - **The kit folder.** `catalog.mjs`, `pack.mjs` and `stage-languages.mjs` moved from `fe/scripts/` into
    `fe/country-kit/` with `git mv`, with their tests, and every caller follows: the npm scripts (names kept,
    `languages:*` included), `test:guards` (now also `country-kit/**/*.test.mjs`), the info Dockerfile and its
    `.dockerignore`, `verify-local`, Info CI's path rules, `build-contents-check.mjs`. `stage-languages.mjs`
    still imports Node built-ins only, so the info image copies it alone; `pack.mjs` reaches the base product's
    `scripts/` only in the base product tree's own `check`. New, every module plain Node with built-ins only and
    every check with a negative test under `test:guards`:
    - `niord-country.schema.json` (draft 2020-12, `additionalProperties: false` everywhere), `lib/schema.mjs` (a
      validator of exactly the keywords the schema uses, refusing a schema that uses another) and
      `lib/manifest.mjs` (§4.1's rules; `locales` and `overrides` optional; a non-empty `overrides` fails, since
      guarded overrides are P3b1's);
    - `seams.json` (format 1): the four administration environments, `info.config` and `info.index`; generated,
      with no country file, both language configurations and the brand tokens (§4.3); the protected list of
      §4.11 rule 3 as globs. `seams.test.mjs` holds the catalogue to the tree: every default and replaced path
      exists, every configuration named is real, ids are unique, country paths lie under `admin/` or `info/`,
      every protected glob matches a tracked file;
    - `branding.schema.json`: per application its keys (ten each, of the kinds text, html, url and file; the
      `country.*` namespace free), its files (the administration app's `company-logo-{lang}.png`; the public
      site's `favicon.png` and `apple-touch-icon.png`, both required, and `logo-gray(-{lang}).png`) and its legal
      documents (disclaimer and cookies, each naming its title key). `lib/branding.mjs` implements the three
      branding rows of §4.6 and §4.11 rule 4's content rules for the files staging copies (images by signature,
      the SVG bans, the legal documents' element and attribute allow-list, character references decoded before
      an `href`'s scheme is read); DK's and GL's four overlays pass it unchanged;
    - `zip-include.json` and the zip build: `lib/include.mjs` (globs `**`, `*`, `?`, `{a,b}`), `lib/zip.mjs` (a
      deterministic writer — entries sorted, files only, dated 1980-01-01, mode 0644, deflated or stored — and a
      reader that refuses links, non-regular entries, directory entries, absolute, drive, backslash and `..`
      paths, duplicates, ZIP64, several disks, a local name unlike its central one, and a wrong size or CRC),
      and `source-zip.mjs build|verify` (`npm run kit:zip`): the files git sees in `fe/`
      (`git ls-files -co --exclude-standard`, so no gitignored output can enter), relative to `fe/`, plus
      `catalog.json` at the root. `CLAUDE.md` is left out everywhere but under `country-kit/` (init's template),
      and so are `ui-apps/**/README.md`, the kit's tests and fixtures, and the end-to-end suite with its login
      fixtures (until P3c). Two builds of the working tree gave the same bytes, none of them a `dk`, `gl`, `da`,
      `kl`, `docs` or `scripts` path, and no `CLAUDE.md` but init's template;
    - `README.md`, the kit's own document, published in the zip.
  - **The stub and the kit.** `templates/setup.mjs` is the stub `init` writes byte for byte. It uses built-ins
    only and does steps 1–2 of §3.2 itself, because nothing from the zip may run before the hash check: it reads
    `../be/pom.xml`'s `<parent>` version and `niord-pins.lock.json`, `{ format: 1, base: { version, sha256 },
    packs: {} }` (the version beside the hash turns a pom bump without a new pin into a clear error rather than
    a bare mismatch); fetches `org.niord.web:niord-fe-src:<version>:zip` through `../be/mvnw dependency:copy` (on
    Windows through `cmd.exe` with a command line it builds and checks itself; `MAVEN_ARGS` passes through); hashes
    the zip before reading an entry; unpacks it through a fresh temporary folder into `.niord/base/`, refusing
    what the kit's reader refuses; and reuses an unpacked base outside CI when its marker `.niord/base.sha256`
    carries the pin. `NIORD_BASE` is refused when `CI` is set and recorded as `local: <path> @ <commit>`. The kit,
    `setup-kit.mjs` with `lib/{pins,maven,local-sources,stage-country,language-config,workspace,environment-check,settings-check,build-report}.mjs`,
    refuses a stub unlike its template (line endings aside); with `NIORD_BASE` copies exactly the include list
    and builds the catalog from the checkout; reads the manifest and its packs — a pinned pack only from
    `NIORD_PACK_<LANG>`, refused under CI, until P5 brings the OCI client; runs every check, reporting all
    findings before it stops: the pack check with the escape hatch, each application's branding, the
    production environment's `apiBaseUrl`, `keycloak.url` and `map.wmsUrl` (a lexical scan, since set-up runs
    before `npm ci`; a non-literal value fails), the settings file's languages (§4.1, read from its `values`);
    then stages exactly each application's `uiLanguages`, filled from `en`, the flags of the languages it
    names and the files the branding check lists, and writes `niord-build-report.json` (format, kit and base
    version, the zip's sha256, provenance, country, every pack with its source and applications, the escape
    hatch, `overrides: []`) into `.niord/build/` and the administration bundle. `guard-build.mjs`, which the
    generated `build` script runs first, refuses a workspace staged from a local source or without a report.
  - **The generated workspace**, from `workspace-files.json`, which lists every root file of the base product's
    `fe/` as made (copy, rewrite, generate) or not needed, with a reason; a test fails on a new tracked root file
    in neither list. `angular.json`: the projects `niord-app` and `niord-info`, the base product's own non-country
    configurations (§3.2 step 5), every path into `.niord/base/`, assets from the base public folder without
    `assets/{default,dk,gl}/` plus `.niord/build/<app>`, the `styles` array of §4.3, a `fileReplacements` entry for
    every seam whose country file exists and for the generated language configuration, `index` from
    `info/app/index.html`, and tsconfigs `.niord/tsconfig.<app>.{app,spec}.json`; each base test set-up file is
    copied into `.niord/generated/<app>/`, because Vitest follows the base `tsconfig.json`'s reference to
    `e2e/tsconfig.json`, which the zip does not carry. `package.json`: named `niord-<cc>-fe`, the base product's
    dependencies, no `prepare` or `lint-staged`, the scripts `start:admin`, `start:info`, `build`, and `test`
    and `lint` over the applications that have country files. The lockfile with its root name rewritten.
    `tsconfig.json` with `@niord/map/*`, `@niord/html/*` and, for every country file in P3a, the interim
    `@niord/admin/internal/*` and `@niord/info/internal/*` (P3b2 narrows them to the API and keeps `internal/*`
    for override files). An ESLint configuration applying the base product's rules to `admin/**` and `info/**`
    (a crafted error in a country file is reported); Vitest and proxy configurations re-exporting the base
    product's. The language configuration is JSON, `.niord/generated/<app>/language.config.json`, behind a
    constant TypeScript shim, and the Tailwind wrapper is constant text: no manifest or pack value is written
    into source text (§4.11 rule 4).
  - **`init <cc>`** with `templates/` (`pack.yml`, `image.yml` and `image-frontend-job.yml`, `dependabot.yml`,
    `renovate.json`, `CODEOWNERS`, a placeholder `CLAUDE.md` whose content is P8's, `be/bootstrap/README.md` (two bootstrap READMEs
    until P5k, §4.8), and since P8a (NG-10) the country launcher `templates/start-local.sh`, which `init` writes
    with the root `.gitignore`'s `/.local-run/`, §6 P8):
    it checks the fetched zip against `--zip-sha256`, writes the lock and never overwrites (§4.2, §4.4, §4.8).
    `pack.yml` is written as §4.5 has it, but its archive, publish and withdraw steps fail with "not available in
    this kit version" until P5 (since the review of PR #184 the archive step ends green with a notice and publish
    runs only on a dispatch, so a push to `main` is green; P5k built all three: §4.5, _As built in P5k_).
  - **The example country** `country-kit/example/`: `be/pom.xml` alone (parent `org.niord:niord-parent:4.0.0-example`;
    no second Maven wrapper in the tree — the harness copies the base product's in) and `fe/` with the stub, the
    manifest (administration UI `en` and `zz`, content the same, default `en`; public site `zz` only, so the
    branding reference there is the default language's; `locales` maps `zz` to `en-GB`), pack `zz`'s
    `pack.json`, flag, drawing and `ATTRIBUTION.txt`, the four administration environments,
    `info/app/info.config.ts` with its footer component and spec, `info/app/index.html`, branding and a legal
    document for both applications, `styles/tokens.xx.css`, and `styles/fonts.xx.css` with Lato 400 (latin,
    woff2, unmodified from `@fontsource/lato` 5.3.0) and its `OFL.txt`. The footer uses the country-only
    utility `sm:grid-cols-[3fr_2fr]`. `zz`'s bundles, backend half and fingerprints are not committed: the
    committed generator `pseudo-localise.mjs` writes them at run time and the example's `.gitignore` names them,
    so a base product PR that adds an English key needs no regenerating commit. The harness
    `example/run.mjs --work <dir>` builds the zip from the working tree as `4.0.0-example`, installs it and
    `niord-parent` into a scratch Maven repository `<dir>/m2` that reads through to the user's (so neither
    reaches `~/.m2` and no release-shaped version is looked up remotely), copies the example to `<dir>/xx` with
    the base product's wrapper, generates `zz`, pins the zip by its sha256 and runs `node setup.mjs`, `npm ci`
    (from npm's cache), `npm run build`, `npm test` and `npm run lint` — never through `NIORD_BASE`.
    `verify-local` runs it as its _example country_ row and Frontend CI's `Example country` job runs it on a
    change to what the zip or the harness reads — the include list widened to whole folders (the public
    site's folder included), the kit, the root workspace files, `be/pom.xml`, the Maven wrapper and the English
    backend bundles `catalog.json` is built from, one expression in both (`EXAMPLE_RULE`) — not on `fe/docs/`,
    `fe/scripts/` or another language's pack; the gate requires the job whenever it ran. The job restores
    Backend CI's Maven cache and never saves it, so a cold repository can never take Backend CI's key. The
    stub and the kit's Maven runner quote every argument that is not plain (paths with spaces, non-ASCII
    letters or parentheses, which `mvn.cmd` reads inside an `if (…)` block), and the harness refuses a
    `--work` or `--maven-repo` path that `MAVEN_ARGS` would split.
  - **Languages** (§4.6): the administration loader fetches only the requested bundle (one that does not
    arrive is retried twice, the load completes without it, and it is asked for again in the background
    after 30 s and up to three more times, each pause twice the one before, as the public site's loader
    does), Transloco's fallback language is gone from both applications, the public site's language configuration gained
    `brandingLanguages` (base `[]`, DK `da`, `en`, GL `kl`, `da`, `en`), and `contents:check` rule 2 is
    "exactly the UI languages" (no `en` required) and rule 4 reads `brandingLanguages`.
  - **Found on the way, and fixed.** The pack check counted a blank backend text as missing even where the
    English one is blank (`navwarn.date.time_interval_prefix` in `template_en.properties`), because the
    catalog gave backend keys no `empty` flag. The catalog's backend section now carries `empty` as the
    applications' does (§4.6), and the pack check lets a blank text stand where English is blank and fails it
    where English is not; the example needs no escape-hatch entry for it.
  - **Verification.** `test:guards` (the kit's tests, the moved ones and the new ones), the pack check, the
    catalog and `contents:check` (17 configurations, exactly the UI bundles) green; `build:dk` and `build:info:gl`
    with the moved pack check and staging; both full unit suites, both lints, `build` and `build:info` with
    `urls:check`; the info image built with the moved staging step. The example harness, run once locally:
    set-up against the pinned zip, `npm ci`, both builds, its specs and its lint green in 155 s; its staged and
    built bundles are exactly `en` and `zz` (administration) and `zz` (public site, no `en.json` in its `dist`),
    the flags the same; the Lato face only in the example's `media/`, none in the base product's `dist`; the
    country-only utility in the example's public-site CSS and in no base product CSS (§9); the build report
    records the pinned provenance and ships in the administration bundle. Refused by hand: `NIORD_BASE` with
    `CI` set, a wrong pin (nothing unpacked), an edited `setup.mjs`, a production build after a `NIORD_BASE`
    set-up. `verify-local` passed with its _example country_ row (set-up, both builds, the specs and the lint in
    2 min 21 s). Not yet run: Frontend CI's `Example country` job (first on PR A), and the network log in a
    browser (one bundle request per language, no branding request in the base product's public site), which was
    taken on 2026-10-22 (P4's record).
  - **What P3b keeps** (as P3a left it): country routes and menu entries, component slots, behaviour hooks,
    guarded overrides with `security.json` and the protected-list refusal, the country API with its generated
    mapping in place of the interim `internal/*` aliases, its lint enforcement with every base product security
    rule on country files and `API-CHANGES.md`, country dependencies (R12), the text-override layer (§4.10), the
    base product's specs of an overridden file run against the country's copy, and §4.3's orphan-file check.
    P3b was then carried out as P3b1 and P3b2 (below).
- **P3b1 as built.**
  - **The behaviour hooks and slots.** `ui-apps/admin/src/app/country-specific/{default,dk,gl}/promulgation.providers.ts`
    became `country.providers.ts` (`git mv`), each exporting one `provideCountry(): EnvironmentProviders`: empty in
    `default/`, the audio renderer (`PROMULGATION_RENDERERS`) and editor (`PROMULGATION_EDITORS`) in DK's and GL's —
    the first hook, swapped in by the same seven configurations, and the spec, now `country.providers.spec.ts`,
    holds each variant to exactly `provideCountry`, DK and GL to their own folder's components and the default to
    none. `app.config.ts` provides the core renderers and editors, then `provideCountry()` once at the root
    injector, so its multi providers add to the core's. The hooks are these two existing tokens; no new token was
    invented. The public site gained `country-specific/default/country.providers.ts`, empty. The component-slot
    registry is one per application, `shared/slots/component-slots.ts` (`SlotName`, `SlotLoader`,
    `SlotRegistration`, the multi token `COMPONENT_SLOTS`, `provideSlot(slot, () => import(...))`), with an
    `app-slot` outlet (`SlotOutlet`; on the public site `SlotOutletComponent`, by its naming) that fetches each
    registration once, renders them in registration order whichever chunk arrives first, renders nothing by
    default and nothing for a loader that fails, which goes to the `ErrorHandler`. A slot takes no inputs. One
    slot per application, since a registry no template uses would be an untested promise: `home.welcome` at the
    end of the front page's welcome panel (the outlet's host is `display: contents`, so an empty slot adds no gap
    to the panel's flex column) and `layout.beforeFooter` on every page of the public site, between `</main>` and
    the footer.
  - **Country routes.** Both applications' `default/` folders gained `country.routes.ts`, an empty default
    export. The administration app's `app.routes.ts` mounts it as one pathless lazy `loadChildren` just before
    `**`, through `guardedCountryRoutes` (`core/country/country-routes.ts`): a top-level entry stays when it is a
    redirect, has `data.public === true`, or has `roleGuard` in `canMatch` with a non-empty
    `data.rolesRequired` (an empty one lets everyone through); any other is left out and logged with
    `console.error`, so a forgotten guard closes the page and the wildcard still works. On the public site
    `app.routes.ts` is gone: `app.config.ts` calls `createNiordRoutes(() => import('./country-specific/default/country.routes'))`
    and composes the four country files (`info.config`, `language.config`, `country.routes`,
    `country.providers`), staying the one file that names a country folder; `createNiordRoutes` moved the teaser
    route above the layout, so no base path waits on the country's chunk, and adds the country entry as the
    layout's last child through a conditional spread, which the landing-route guard's walker never reaches. In
    both, an address no base route matches fetches the (empty) country chunk once before the wildcard.
  - **Menu entries.** `core/main-shell/country-menu.ts` holds `MenuAnchor` (`messages`, `editor`, `schedule`,
    `admin`, `sysadmin`), `CountryMenuEntry { after, labelKey: country.*, icon, routerLink, roles }` and the
    token `COUNTRY_MENU` (root default `[]`), which `app.config.ts` sets from `default/country.menu.ts`
    (`COUNTRY_MENU_ENTRIES`, empty). `MenuService` puts each entry after its anchor, where the anchor would be
    when it is hidden from the user, keeping the file's order, and shows it when `roles` is empty or the user
    has any one of them; the base items are unchanged. The icon is a registered `@ng-icons` name; a country
    registers its own glyphs with `provideIcons` in `country.providers.ts`.
  - **`paths:check` rule 4** now asks a configuration to swap every `default/` file the swapped-in country's
    folder also has; a default the folder lacks is the neutral default, as a country workspace's seams are, so
    DK and GL need no empty stubs of `country.routes.ts` and `country.menu.ts`. A test proves a DK copy of one
    would make its swap required again.
  - **The catalogue.** `seams.json` gained the seams `admin.country.providers`, `admin.country.routes`,
    `admin.country.menu`, `info.country.routes` and `info.country.providers` (every configuration), a `hooks`
    section (`admin.promulgation.renderers` and `admin.promulgation.editors`, each with its token and file) and a
    `slots` section (`home.welcome` and `layout.beforeFooter`, each with its template and registry), and the
    protected list gained the administration app's `app.routes.ts` and `core/country/**`. `seams.test.mjs` holds
    them to the tree: each hook's file exports its token, each slot is in its registry's `SlotName` and placed in
    its template, and every placed `<app-slot name>` and every `SlotName` member is catalogued.
  - **Guarded overrides** (`lib/overrides.mjs`; the manifest's schema and rules accept `overrides`).
    `node setup.mjs --add-override <base file> --reason <text>` and `--accept-override <file>` are read by the kit
    from the command line (the stub is unchanged, so committed stubs and `init` stay valid), run after the base is
    verified, rewrite `niord-country.json` and then run the whole set-up; any other argument is refused.
    `--add-override` refuses a path outside `ui-apps/{admin,info}/src/app/`, a file that is not TypeScript,
    JavaScript or JSON, a spec, a missing reason, a file the zip does not hold, a protected file, a seam's file
    (generated seams included), a file already overridden, an existing target, and a file with a relative import
    out of its application's `src/app/`; it copies the file to `<app>/overrides/<path below src/app/>` with its
    relative imports written as `@niord/<app>/internal/*` (a one-time scaffold from the base file's own text, not a
    manifest value in generated source), copies the template and styles the component names to their mirrored
    places, and records `basedOn`. Fingerprints are sha256 with CRLF read as LF. Every set-up fails an override
    whose `basedOn` file changed or went (one error naming every such file and `--accept-override`), whose file
    left the base product or joined the protected list, whose file is a seam's (generated seams included, so a
    hand-edited manifest cannot override a seam's file past its country file), or whose copy or one of its
    resources is missing; a changed file a fix in `country-kit/security.json` names fails first, with
    `SECURITY: <version>`. `security.json` (format 1, `fixes: []`, each fix `{ version, files, summary }`) ships in
    the zip with the kit; a missing or malformed one fails set-up. The pull request that fixes a security issue
    in a file under `ui-apps/<app>/src/app/` adds its fix to `security.json` with the version that first carries
    it, and an entry is never removed; `fe/CLAUDE.md` says so where the base product's work is checked. `--accept-override` prints per `basedOn` file the recorded and the
    current fingerprint and a line diff of the base product's current file against the copy (its imports written
    as the copy's), adds a resource the base component has gained, and refuses an override whose base file is
    gone. The generated `angular.json` puts each override into `fileReplacements` in the options and in every
    configuration, and lints the HTML under `<app>/overrides/`; set-up prints the overrides list as it prints the
    escape hatch, and the build report records each as `{ file, with, basedOn, reason, added, baseSpec }`.
  - **The base product's spec runs against the copy.** The spec beside an overridden file (`<stem>.spec.ts`) is
    copied to `.niord/generated/<app>/base-specs/` with its relative imports pointing back into `.niord/base/`,
    and the test target includes it; its import of the overridden file compiles to the country's copy through
    `fileReplacements`. Run in place it could not be: Vitest reads the nearest `tsconfig.json`,
    `.niord/base/tsconfig.json`, whose reference to `e2e/tsconfig.json` the zip does not carry (the same reason the
    test set-up files are copied, P3a).
  - **The orphan-file check** (`lib/orphans.mjs`) runs on every set-up: from each existing seam file under
    `admin/app/` or `info/app/` and each override's copy, it follows relative static, side-effect, re-export and
    dynamic imports, `templateUrl` / `styleUrl(s)` and a stylesheet's relative `@use`, `@forward` and `@import`, and
    fails every non-spec file of those folders and of `admin/overrides/` and `info/overrides/` it does not reach.
    Specs are neither checked nor followed.
  - **The example country** uses each extension point once: in the administration app a harbour page
    (`roleGuard`, the editor area's roles) with a menu entry after Messages (`lucideBook`, the same roles), a
    renderer for its own promulgation service `xx-bulletin` (since P7c, #207, an editor for it too, D5) and a welcome note in `home.welcome`; on the public
    site an about page and a notice in `layout.beforeFooter`; each text a `country.*` key of its branding, each
    with a spec, and its `country.routes.spec.ts` asserting that the base product's route rule drops nothing. Its
    override, made with `--add-override`, is `message-selection-toggle` (`.ts`, `.html`, `.scss`), whose copy
    changes only its template (a bookmark where the base product has a star) and carries the country's own spec.
    So a base product change to any of those three files turns the `Example country` job red until the
    example's fingerprints are accepted again — the failure the plan asks for. The harness gained a step after the
    build: the example's page and slot components (`app-harbour-page`, `app-welcome-note`, `app-about-page`,
    `app-site-notice`) must each be in some JavaScript file of the build and in none that `index.html` loads or
    preloads (`lazyChunkProblems`).
  - **Negative tests** under `test:guards`, one per new rule: the manifest's override rules (copy outside the
    overrides folders, not mirroring its file, the other application's folder or `basedOn`, a file outside
    `src/app/`, a template, a spec, `basedOn` without the file, a fingerprint that is not sha256, a `..` segment,
    two overrides of one file, no reason); each `--add-override` refusal; the set-up check (a changed, removed or
    now-protected base file, a missing copy, SECURITY first); `--accept-override` refusing a gone base file;
    `security.json`'s shape; the orphan check (an unimported file in `admin/app/`, an unused stylesheet and an
    unnamed copy in `admin/overrides/`); the hook and slot catalogue (a token its file does not export, a slot its
    template does not place, an uncatalogued slot); the lazy-chunk step (a selector in `main`, in a preloaded
    chunk, in none); and end to end in `setup.test.mjs`, the one the phase names — set-up from the example fails
    when an override's `basedOn` file changed, naming it and `--accept-override`, and passes after
    `--accept-override` — plus an unknown argument and `--add-override` of a protected file refused.
  - **Verification.** The administration app's affected specs (9 files, 96 tests), the public site's whole
    suite (91 files, 919 tests), `paths:check` (147 paths) with its test (13), `contents:check` (11 + 6
    configurations), both lints, the landing-route, teaser-frame and lint-boundary guards (58). The six builds
    with `urls:check`: `build:dk` and `build:gl` carry `app-audio-view` and `app-audio-edit`, `build` neither;
    `app-slot` is in a lazy chunk of the administration app (the front page's), and each application's country
    routes are a lazy chunk of their own, `index.html` loading only `main`. The public site's first visit grew by
    600 B (initial JS +705 B for the slot outlet and the empty providers), within `bundle:info`'s tolerance, so
    no re-baseline; `separation:info` green. The example harness, run once locally against a zip of the working
    tree: set-up printing the overrides list, `npm ci`, both builds, the lazy-chunk step, `npm test` (the
    administration app's 7 files and 17 tests, the base product's `message-selection-toggle.spec.ts` among them;
    the public site's 3 and 8) and the lint green in 166.7 s; the build report lists the override with its
    `basedOn` and `baseSpec`, and the built toggle uses the bookmark. By hand in its work copy: a broken
    `onToggle` in the copy fails the base product's spec (2 tests); an edit to the base template fails set-up,
    naming the file and `--accept-override`; `--add-override` of `core/interceptors/auth-token.interceptor.ts`, of
    an `.html` file, of a DK file outside the zip and of `default/country.menu.ts` is refused; `--accept-override`
    prints the fingerprints and the diff, and the next set-up passes. With the documents updated, `test:guards`
    green (407 tests, 406 passed, the symbolic-link case skipped as this Windows account cannot make links) and
    `docs:check` green (28 files). `verify-local` passed with the administration app's whole suite and its
    _example country_ row (2 min 50 s). Not yet run: Frontend CI's `Example country` job (first on PR A).
  - **Changed from the plan, and corrected above.** §4.3's table names the defaults in
    `country-specific/default/`. §4.9: an overridable file lies under `ui-apps/<app>/src/app/`, `with` mirrors
    its path below `src/app/`, specs and seam files are refused, and the base product's spec beside the file runs
    from a generated copy. §4.11 rule 3: the route rule binds the administration app's country routes at run
    time (left out and logged, failing closed), the public site's are public by construction, and the protected
    list gained `app.routes.ts` and `core/country/**`. §4.11 rule 6: `--accept-override` prints fingerprints and
    the diff of the copy against the base product's current file; the diff since the recorded fingerprint comes
    with the upgrade report. P4: `security.json` travels inside `country-kit/`, not at the zip's root.
  - **Found on the way.** The public site's country-folder lint (`INFO_COUNTRY` in `eslint.config.js`) is a
    `no-restricted-imports` pattern, so a dynamic `import('./country-specific/dk/…')` escapes it, unlike the
    separation rule's `ImportExpression` selectors; `app.config.ts` now uses a dynamic import of
    `default/country.routes`, so P3b2's lint work closes the gap. The generated Tailwind entry already scans the
    whole country folder of an application, overrides included, so a utility only an override's template uses is
    built.
  - **What P3b2 keeps:** the country API (`@niord/admin/api`, `@niord/info/api`: the tokens of every extension
    point and slot — `PROMULGATION_RENDERERS`, `PROMULGATION_EDITORS`, `provideSlot`, `COMPONENT_SLOTS`,
    `CountryMenuEntry`, `roleGuard` and the role lists, with their types — `InfoConfig`, and what the footer and
    audio components need, the legal dialog through a loader that keeps it lazy; and a specs-only `/testing`
    entry each for the test helpers DK's, GL's and the example's specs use) with its
    generated mapping replacing the interim `internal/*` aliases for every country file but an override; its lint
    enforcement (static and dynamic imports, relative paths out of the country folders, one application's API from
    the other's country code) with the dynamic-import gap above closed in the base product's own country-folder
    rule; the base product's security lint rules on country files with no inline disabling; `API-CHANGES.md`; the
    base product's DK/GL seam files switched to the API; country dependencies (R12: `fe/country/package.json`, the
    lockfile merge and its checks, `npm audit` with `audit-accepted.json`); the text-override layer (§4.10).
    **What P3c keeps:** `image:info` and the info image packaging a built `dist`; the separation and layout checks
    with parameters and each country's bundle baseline; the remaining checks of §4.8 on every country build
    (exactly the `uiLanguages` and flags shipped, no external font or style URL, the country-only utility in the
    built CSS — checked by hand in P3a); the secret-pattern scan; the new-country acceptance tests (untouched
    `init`, upgrade); the upgrade report (§4.12), with `--accept-override`'s diff of each `basedOn` file since the
    recorded fingerprint, which needs the previous version; the generated workspace's `e2e` and `image:info`
    scripts and Playwright configuration.
- **P3b2 as built.**
  - **The country API.** Each application has `src/public-api.ts`, mapped as `@niord/admin/api` and
    `@niord/info/api`, and a specs-only `src/testing-api.ts`, mapped as `@niord/admin/api/testing` and
    `@niord/info/api/testing`. They sit beside `main.ts`, outside `src/app/`, so `--add-override` refuses them
    and the zip's include list already carries them. `fe/tsconfig.json` maps the four; each application's
    `tsconfig.app.json` leaves `src/testing-api.ts` out and its `tsconfig.spec.json` takes it in, since test
    helpers belong in no build (the public site's are typed against Vitest's globals and load the English
    bundle; the administration app's import only Angular, Transloco and RxJS). The exports, exactly:
    - `@niord/admin/api`: the types `AppConfig` and `LanguageConfig`; `PROMULGATION_RENDERERS` with the types
      `PromulgationRenderer` and `BasePromulgation`; `PROMULGATION_EDITORS` with `PromulgationEditor` and
      `PromulgationEditorContext`; the type `MessageStatus` (the editor context carries it); `provideSlot` and
      `COMPONENT_SLOTS` with `SlotName`, `SlotLoader` and `SlotRegistration`; the types `CountryMenuEntry` and
      `MenuAnchor`; `roleGuard`; `EDITOR_AREA_ROLES`, `EDITOR_VIEW_ROLES`, `ADMIN_AREA_ROLES` and
      `SYSADMIN_AREA_ROLES` with the type `AuthRole`; `PlainTextToHtmlPipe`. Its testing entry:
      `provideTestTransloco` and `countryRouteProblems` (a country's `country.routes.spec.ts` asserts with it
      that the base product's route rule drops nothing).
    - `@niord/info/api`: the types `InfoConfig`, `RootAreaSpec` and `LanguageConfig`; `provideSlot` and
      `COMPONENT_SLOTS` with the three slot types; `AppConfigService`; `openInfoDialog`; `currentYearInZone`;
      `loadLegalDialog`. Its testing entry: `getTranslocoTestingModule` and `provideTestNiordConfig`.
    - Not exported: `COUNTRY_MENU` (`app.config.ts` wires it; a country fills it through `country.menu.ts`),
      `NiordInfoConfig`, `NIORD_INFO_CONFIG` and `LegalDialogComponent`. `loadLegalDialog()`, a new base file
      `shared/components/legal-dialog/load-legal-dialog.ts` with its spec, resolves to the dialog's class
      through a dynamic import, so the dialog stays a lazy chunk; a static re-export would have pulled it into
      `main` and changed DK's and GL's chunk lists.

    The form, which the kit parses: a header comment, then only `export { … } from` and `export type { … } from`
    statements, one per source module and kind, each under its own contract comment saying what it is for a
    country and which extension point it serves; no `export *`, no local declaration. A deprecation is a line
    `Deprecated since <version>: <what to use instead>` in an export's comment (P3c2 added the optional
    `, removed in <version>` before the colon), never a JSDoc `@deprecated`,
    which the base product's type-aware lint would turn into an error at the first notice, against §4.12's
    warning before an error. `country-kit/lib/api.mjs` reads the form (`readApi`, refusing any other statement,
    an export without its comment and a name exported twice), finds the four files from the base product's
    `tsconfig.json` (`apiFiles`), and names every use of a deprecated export in a country's `admin/` and `info/`
    (`deprecatedUses`: named imports and re-exports by name; a namespace import, an `export *` or a dynamic
    import counts every deprecated name of its entry). `api.test.mjs` keeps the API minimal: every export is
    imported by a DK or GL file, an example file or a generated shim, or is listed in `CONTRACT_ONLY` with its
    reason — `PromulgationRenderer`, `PromulgationEditor`, `SlotName`, `SlotLoader`, `SlotRegistration`,
    `MenuAnchor`, `AuthRole`, `EDITOR_VIEW_ROLES`, `ADMIN_AREA_ROLES` and `SYSADMIN_AREA_ROLES` of the
    administration app, `RootAreaSpec` and the three slot types of the public site — and the list fails when a
    name on it becomes used or is no longer exported; every name a country file imports exists; the testing
    helpers stay out of the main entries; every deprecated export is in `API-CHANGES.md`. `seams.test.mjs`
    holds the extension points to it: every hook's token, `COMPONENT_SLOTS` and `provideSlot` are exported by
    their application's API, and `fe/tsconfig.json` maps exactly the four entries, to files of the tree.
  - **DK and GL through the API.** Every file of `country-specific/{dk,gl}/` in both applications imports base
    product code only through its application's API (`import type` where only types are used, `/testing` in
    specs only); imports inside its own folder stay relative. The footers pass `loadLegalDialog` to
    `openInfoDialog` in place of their own dynamic import of the dialog. No template changed. `default/` is base
    product code and keeps its relative imports.
  - **The generated mapping** (`lib/workspace.mjs`). P3a's interim internal aliases are gone: the root
    `tsconfig.json` maps the base product's own `@niord/map/*` and `@niord/html/*` and the four API entries,
    re-based into `.niord/base/`. `@niord/<app>/internal/*` is mapped only in `.niord/tsconfig.<app>.{app,spec}.json`
    of an application the manifest overrides a file of, which then repeat the root's paths as well, since a
    child's `paths` replace its parent's. A country without overrides resolves no internal path at all. The
    generated language-configuration shims take their `LanguageConfig` type from the APIs.
  - **The country lint** (`country-kit/lib/country-lint.cjs`, CommonJS as both configurations that load it are,
    built-ins only): the ESLint plugin `niord-country` — one plugin object, registered by both configurations,
    since ESLint refuses a plugin redefined under its name — and the lists the base product's configuration now
    takes from it (`INFO_ANONYMOUS_PACKAGES`, `BYPASS_SECURITY_TRUST`, `INNER_HTML_PIPES`), so each has one
    source (since the kit move's KM-1: the base product's configuration takes the lists from its own
    `fe/eslint-shared.cjs`, an equal copy that `scripts/eslint-shared.test.mjs` holds to this file's, and only the
    configuration the kit generates in a country workspace loads `country-lint.cjs`). A custom rule rather than
    `no-restricted-imports` patterns: a pattern cannot tell how far a relative path climbs, and a later
    `no-restricted-imports` block replaces an earlier one.
    - `niord-country/imports` (options `app`, `roots`, `specRoots`, `internalRoots`, `banned`,
      `otherAppPackages`) checks import declarations, type imports included, re-exports, `import x = require()`,
      `import('…')` types and dynamic `import()`. It reports a specifier built at run time (a template literal
      included); a relative path resolving outside the roots (for a spec, outside the roots and spec roots); an
      absolute or drive path; a URL; `node:` outside specs; `@niord/<app>/api/testing` outside specs;
      `@niord/<app>/internal/*` outside the internal roots; every other `@niord/…` (the other application's API,
      `@niord/map/*`, `@niord/html/*`); a banned package; a country package of the other application. Each
      message names the API to use and §4.3.
    - `niord-country/inner-html` allows `[innerHTML]` and `[outerHTML]` only when the binding's last pipe is
      `safeMessageHtml`, `plainTextToHtml` or `transloco`, parentheses unwrapped; a safe pipe followed by
      another (`x | safeMessageHtml | uppercase`) fails, and so does an interpolated `innerHTML="{{ x }}"`.
    - `countryBlocks({ dir })`, which the generated `eslint.config.js` (constant text) ends with, after the base
      product's configuration: per application the imports rule with the roots `<app>/app`, `<app>/overrides`
      and `<app>/environments`, the spec root `<app>/assets` (the example's specs read their branding), the
      internal root `<app>/overrides`, the public site's anonymity bans and the other application's packages
      from `fe/country/package.json`; `no-restricted-properties` restating the five `bypassSecurityTrust*` calls
      with no exemption, because the base product's exemptions are paths a country file could imitate
      (`admin/app/shared-html/…`); `inner-html` on the templates, which inline templates reach through
      angular-eslint's processor; and `linterOptions` with `noInlineConfig` and
      `reportUnusedDisableDirectives: 'error'`, so an `eslint-disable` disables nothing and is itself reported,
      which the generated `ng lint … --max-warnings 0` fails.
  - **The base product's own lint** (`fe/eslint.config.js`): one block per `ui-apps/{admin,info}/src/app/country-specific/{dk,gl}/**/*.ts`
    with the imports rule rooted at that folder (the public site's with the anonymity bans) and the separation
    patterns restated; a block for their templates and inline templates with `inner-html`; `noInlineConfig` for
    their folders. Base product code outside DK's and GL's folders may not import `@niord/{admin,info}/api` or
    their subpaths (a static ban, added to the separation patterns every block repeats), so the API stays a
    one-way facade and cannot close an import cycle. The gap P3b1 found is closed: the dynamic form of the
    public site's country-folder rule holds every public-site file but `app.config.ts`, which has a block of its
    own allowing a dynamic import of `default/` only.
  - **`country-kit/API-CHANGES.md`**: the entry points, the compatibility policy of §4.3, how an entry is
    written, and the first entry, _Unreleased: first in the version PR A publishes_ — the API introduced, what a
    country must do (import through it, run `node setup.mjs` and `npm run lint`) and a table from each of P3a's
    internal paths to its API name, `loadLegalDialog` in place of the dynamic import of the dialog and the
    testing entries included. Set-up prints each use of a deprecated export as a `country API:` warning and
    passes.
  - **Country dependencies (R12)** (`lib/dependencies.mjs`, with `lib/semver.mjs` for ranges: exact, partial,
    `x`, comparators, caret, tilde, hyphen ranges, AND and `||`, npm's prerelease rule; anything else throws, so
    an unreadable peer range fails; it agreed with node-semver 7.8.5 on 515,160 version and range pairs wherever
    both read the range).
    - `fe/country/package.json`: `name`, `private: true`, an optional `description`,
      `dependencies: { <name>: <exact version> }` and
      `niord: { dependencies: { <name>: { app, reason } }, installScripts?: { <name>: <reason> } }`, `app` being
      `admin`, `info` or `both`. Any other field fails, as does a dependency without its `niord` entry or the reverse, and a name that is
      not an npm name or is `@niord/…`. `fe/country/package-lock.json`: `lockfileVersion` 3, `packages['']` with
      the name and the manifest's dependencies, then exactly the entries the merged tree adds to the base
      product's lockfile. Neither file means none; one without the other, a link or a file that is not JSON fails.
    - The checks, every finding reported before set-up stops: the shape; an inexact version; a base product
      package at another version (the same version passes and is listed); a lock entry at a path the base
      lockfile has with another version, resolved URL or integrity (npm's dev, peer and optional flags are not
      compared: they change when a country package depends on a base one); a resolved URL off
      `https://registry.npmjs.org/`, an integrity not sha512, a link; an install script without a reason (a
      reason for a package without one is a warning); a peer range of an added entry that the merged tree's
      version does not satisfy, or a non-optional peer it lacks, checked because the workspace's
      `legacy-peer-deps` would hide both; any added entry published less than 7 days ago, or with no known time.
      Beyond the plan: a lock entry that repeats a base entry unchanged, an added entry nothing in the manifest
      reaches, and a dependency the merged tree lacks or holds outside its range each fail.
    - Publish times: `publishTimes` fetches each added entry's registry document with Node's `fetch` and keeps an
      answer older than 7 days in `.niord/country-dependency-ages.json`, keyed `name@version` and integrity, so a
      repeated set-up needs no network for it; no answer fails set-up, naming the package.
    - `node setup.mjs --update-country-lock` (alone: no value, no other argument) runs after the base is
      verified: it writes `.niord/country-lock/` with the merged manifest, the base lockfile and `.npmrc`, runs
      `npm install --package-lock-only --ignore-scripts --no-audit --no-fund` there with constant arguments,
      refuses a result that changed a base entry, writes the country lock and then runs the whole set-up. A
      Dependabot security PR that rewrites the lock fails set-up until the country runs it again.
    - Set-up prints the country packages as it prints the escape hatch, each with its version, application and
      reason; the generated `package.json` and lockfile take the merged tree (`mergeDependencies`); the build
      report records them. `init`'s `dependabot.yml` has carried the `/fe/country` npm entry since P3a, and
      `init.test.mjs` now asserts it and that `init` writes no `country/`, `text-overrides/` or
      `audit-accepted.json`.
  - **npm audit** (`country-kit/audit.mjs`, run from a country's `fe/`): the generated `build` script runs it
    after `guard-build.mjs` and before both `ng build`s, and `npm run audit` runs it alone (P5 and P6 schedule it
    weekly). It runs `npm audit --omit=dev --audit-level=high --json` and `npm audit signatures --json` with
    constant arguments and reads both whatever npm's exit code. A finding is an advisory of severity high or
    critical, by its GHSA id and package, or an invalid or missing signature as `<name>@<version>`; an advisory
    whose URL carries no GHSA is named `npm advisory <source>` and cannot be accepted. `audit-accepted.json`
    entries are `{ advisory, package, reason, added }` or `{ signature, reason, added }`; an unknown field, an
    empty reason, a bad date or a repeat fails. The list is printed in full on every run, an entry that matches
    nothing is a warning, and a report that is not JSON (the registry did not answer) fails: no build without its
    audit. Set-up checks the file's shape, prints it when not empty and records their count in the build report. On
    Windows both the audit and the lock update start npm through the shell as one constant command line, which
    avoids Node's `DEP0190` warning.
  - **Text overrides (§4.10)** (`lib/text-overrides.mjs`). `readTextOverrides` refuses a link, any file but
    `<lang>.json`, a field other than `admin` and `info`, and a key named `__proto__`, `prototype` or
    `constructor`. `checkTextOverrides` holds each entry to §4.10: the language a UI language of the section's
    application; the key in the catalog (a renamed one named with its new key) and neither a branding-schema key
    nor `country.*`; exactly `text` and a 16-digit `fp`; empty only where English is; English's placeholders;
    the markup, date-pattern and word-list rows through `pack.mjs`'s exported `checkText`; a changed English
    fingerprint one warning per language listing the overrides and the fingerprints to record; an override
    equal to the pack's text (English's for `en`) a redundant warning, one differing from a text the pack has
    listed as a deliberate difference. The keys it supplies go to `checkPacks` as `covered`, which counts them
    present for the missing-key and placeholder rows only (the pack's own text still meets the markup,
    fingerprint and status rows); `pack check` prints, per language and application, how many keys the pack
    lacks come from text overrides and which, and an escape-hatch entry for such a key is a stale warning. Staging writes English, then the
    pack's fill, then the overrides with `pack.mjs`'s new `setText`, so a key keeps its place; a pack bundle no
    layer changed is still a byte copy, an overridden English bundle is written from JSON, and each
    application's result gains `overridden: { <lang>: n }`. Branding still wins at runtime. Set-up prints the
    count on every run, `text overrides: none` included.
  - **The build report** stays format 1 and gains `dependencies` (`[{ name, version, app, reason, installScript }]`),
    `textOverrides` (`{ <lang>: { admin, info } }`) and `auditAccepted` (a count), each empty or 0 when there is
    none. Only the count, because the report is served without sign-in (the administration bundle, later the
    info image's labels): the accepted advisories stay in `audit-accepted.json` and in the lines set-up and
    `audit.mjs` print on every run.
  - **The example country** imports through the API in every file, specs through `/testing`, its footer through
    `loadLegalDialog`; its override keeps its internal imports. Its dependency is `pretty-bytes` 7.1.0 (ESM, no
    dependencies, no install script, published 2025-09-21), for the administration app: the harbour page shows a
    constant notes size with `prettyBytes(n, { locale })` through a new branding key `country.harbour.size`
    (`en` and `zz`), with a spec. Its lock was made by `updateCountryLock` against the registry, and
    `--update-country-lock` in the harness's work copy reproduced it byte for byte. Its text override,
    `text-overrides/en.json`, words `messages.filter.domain.label` (the message filter's label) `Waters` where
    English says `Domain`. The harness gained a step after the lazy-chunk step, `textOverrideProblems`: each
    override's text must be its key's value in its language's built bundle of the application its section
    names. `EXAMPLE_RULE` already covers
    everything P3b2 touches, so neither Frontend CI nor `verify-local` changed.
  - **Negative tests** under `test:guards`, one per new rule:
    - `country-lint.test.mjs` (21, over a temporary workspace in `fe/.niord/`, deleted afterwards): a static,
      type, re-export, dynamic and template-literal escape out of `admin/app`; a run-time specifier; an absolute
      path, a drive path and a URL; `node:` outside a spec; `/api/testing` outside a spec; the internals outside
      `admin/overrides` (allowed inside); the other application's API and `@niord/map/*`; `keycloak-js` and
      signal forms on the public site, statically and dynamically; a country package of the other application; a
      bare `[innerHTML]`, one through another pipe and one in an inline template; `bypassSecurityTrustHtml` in
      `admin/app` and in a folder named like a base exemption; an `eslint-disable` above a violation, in a
      `.ts` file and in a template, still reporting the error and the directive. Allowed: `admin/app`,
      `admin/overrides`, `admin/environments`, a spec reaching `assets/`, the safe pipes and a translation.
    - `eslint-boundaries.test.mjs` (63) at DK's and GL's real paths: a relative import out of the folder,
      static and dynamic, in both applications; a deep relative import of base code; `@niord/info/api` from
      `admin/gl`; `/api/testing` outside a spec; base product code importing `@niord/admin/api`; a dynamic
      `import('./country-specific/dk/…')` in a public-site file and in `app.config.ts`, where `default/` is
      allowed.
    - `seams.test.mjs` (13): an API lacking a hook token or the slot registry. `api.test.mjs` (13): an
      `export *`, a local declaration, an export without its comment, another statement, a missing header, a name
      exported twice, a base `tsconfig.json` without an entry point, and a fixture country using a deprecated
      export.
    - `dependencies.test.mjs` (21) and `semver.test.mjs` (6): each check above — the manifest's and the lock's
      shape, an inexact version, a base package at another version, a changed base entry, a URL off the
      registry, a non-sha512 integrity, a link, an install script without a reason, a peer range rejecting the
      framework and an absent peer, the tree, a version under 7 days old — plus one file of the pair missing, a
      linked folder, the registry cache, no answer from the registry and a lock update that changes a base
      entry; no test touches the network.
    - `audit.test.mjs` (12): an unaccepted advisory and signature, `audit-accepted.json`'s shape (unknown field,
      empty reason, bad date, bad id, repeat), a stale entry, a report that is not JSON, a malformed file failing
      a clean audit.
    - `text-overrides.test.mjs` (17): each file refusal and each row above. `pack.test.mjs`: a covered missing
      key passes, an uncovered one still fails. `stage-country.test.mjs`: an override wins over the pack and over
      English, a forbidden key is refused, a second run writes nothing.
    - `workspace.test.mjs`: no internal alias without an override, internal paths only in the overridden
      application's tsconfigs, the testing entries in the spec program only, the ESLint text, the `build` and
      `audit` scripts, the merged dependencies. `setup.test.mjs` end to end from the example: the count printed
      and recorded with the dependencies; a lock entry breaking a rule stops set-up before staging;
      `--update-country-lock` alone, refused with a value or another argument; a deprecated export the country
      uses warns while set-up passes; no internal alias without an override; an accepted audit finding printed
      in full while both copies of the report count it and name neither advisory, package nor reason. `init.test.mjs`: the Dependabot
      entry. `example/run.test.mjs`: an override missing from the built bundle, and one of a language the build
      does not ship.
  - **Verification.** Before any edit the six builds were taken, each with `urls:check`, as the baseline; after
    the switch to the API, against it (hashes stripped from the names): `build` 210 files and `build:info` 39
    files, identical names and sizes (`main` 305,744 B and 345,200 B unchanged); `build:dk` 220 and `build:gl`
    221 files, the same names, `index.html` loading only `main` and the styles, `app-audio-view` and
    `app-audio-edit` still in `main`, `main` 307,546 → 307,583 B (+0.012 %), two lazy chunks +1 B and −34 B
    because their import list from `main` was renamed, and neither selector in `build`; `build:info:dk` (50
    files) and `build:info:gl` (55) the same names, the legal dialog still in a lazy chunk `index.html` does not
    load, `main` +20 B (348,392 → 348,412 B and 348,362 → 348,382 B, +0.006 %). `contents:check` (17
    configurations, 338 files), `paths:check` (147 paths), `separation:info` (88 modules) and `bundle:info`
    (within its baseline) green; `npm run lint` and `npm run lint:info` green; `languages:check` 0 errors, and
    `build:info:dk`'s pack-check and staging lines as before. The country specs: the administration app's
    `country-specific/` 5 files and 17 tests, the public site's `country-specific/` and legal dialog 7 files and
    68 tests. The packages' own test files: `country-lint` 21, `eslint-boundaries` 63 and `seams` 13, all passing; the
    six of the kit's checks 85 (one skipped: a file link needs a privilege here); the five of the kit's wiring
    85 (86 after the review's fix added a `setup.test.mjs` case); then `test:guards` whole, 532 tests,
    530 passed and 2 skipped (a symbolic link and a path with a space, which this account and path cannot
    make); after the review's fix of the report's `auditAccepted`, `setup.test.mjs` 29 of 29 and `test:guards`
    533 tests, 531 passed and the same 2 skipped. Against the registry on 2026-10-22: the base product's tree audited with 0 high or critical findings
    and 0 invalid or missing signatures; `pretty-bytes` 7.1.0's publish time read in 0.2 s; a scratch lock
    update in 1.8 s with one added entry, `node_modules/pretty-bytes`. The example harness, run locally against
    a zip of the working tree: green in 152.3 s — set-up 11.3 s, printing
    `text overrides: en 1 (admin 1, info 0)`, the dependency list and no deprecation; `npm ci` 50.5 s; the build 56.4 s with both audits
    passed; the lazy-chunk and text-override steps; `npm test` 12.0 s (the administration app's 7 files and 18
    tests, the public site's 3 and 8); the lint 15.4 s (an earlier run, with the override on a key no template
    uses, took 187.6 s). `pretty-bytes` is only in the harbour page's lazy chunk (2,866 B), in neither `main` nor
    the public site. By hand in its work copy: the generated `tsconfig.json` has no internal alias and the
    administration app's two tsconfigs have it, the public site's not; an internal import, a relative import
    into `.niord/base/`, a bare `[innerHTML]` and an `eslint-disable` over each fail
    `ng lint niord-app --max-warnings 0` (four errors and two ignored-directive warnings); with the override removed, an internal
    import fails `ng build niord-app` with TS2307; `pretty-bytes` bumped to 7.1.1 without
    `--update-country-lock` fails set-up with two errors; `--update-country-lock now` is refused.
    `verify-local` passed with both applications' whole suites and its _example country_ row (3 min 6 s). Not
    yet run: Frontend CI's `Example country` job (first on PR A).
  - **Changed from the plan, and corrected above.** §3.2: the lock holds exactly the entries the merged tree
    adds and `--update-country-lock` writes it; the age rule covers every entry the country adds, transitive
    ones included, with the answer cached; the audit runs inside `build` and as `npm run audit`; step 5's
    generated `package.json` and lockfile carry the country's packages and the `audit` script. §4.3: each API
    has a specs-only `/testing` entry; the generated root tsconfig also maps `@niord/map/*` and `@niord/html/*`,
    which lint bans in country files; relative paths may also reach `<app>/environments`, and a spec
    `<app>/assets`; `internal/*` is mapped in the tsconfigs of an application the country overrides, lint
    keeping every other file to the API; the deprecation marker; base product code never imports the API.
    §4.9: since P3b2 only override files reach internals. §4.10: the file's shape, the checks beyond §4.10's
    sentence (language, branding keys, redundant, deliberate difference, the stale hatch entry) and the staging
    order. §4.11 rule 2: the build report's new fields. §4.11 rule 3: `[innerHTML]` through the safe pipes or a
    translation, as the last pipe; DK's and GL's footers bind a translated copyright line. P3b1's _What P3b2
    keeps_: `COMPONENT_SLOTS`, the types, the legal dialog's loader and the testing entries. §7: the rules this
    phase wrote.
  - **Found on the way.**
    - With `noInlineConfig`, ESLint reports an ignored `eslint-disable` as a warning; the violation under it
      stays an error. Both of the base product's lints (`lint`, which gained `--max-warnings 0` after the
      review, and `lint:info`) and the generated lint run with `--max-warnings 0`, so a directive fails all
      three, and Frontend CI's `npm run lint` with them.
    - `ng test <project> --include` reads its glob relative to the workspace, so `src/app/country-specific/**`
      finds nothing; `--include='**/country-specific/**/*.spec.ts'` does.
    - `example/run.mjs` still starts npm on Windows with an argument array beside the shell, so the harness
      prints Node's `DEP0190` warning; the arguments are constants, so it is harmless.
  - **What P3c keeps:** `image:info` and the info image packaging a built `dist`; the separation and layout checks
    with parameters and each country's bundle baseline; the remaining checks of §4.8 on every country build
    (exactly the `uiLanguages` and flags shipped, no external font or style URL, the country-only utility in the
    built CSS — checked by hand in P3a); the secret-pattern scan; the new-country acceptance tests (untouched
    `init`, upgrade); the upgrade report (§4.12), with `--accept-override`'s diff of each `basedOn` file since the
    recorded fingerprint, which needs the previous version, and with its sections on the country API (the
    changes `API-CHANGES.md` records, deprecations with the version they end in) and on dependencies (the base
    product's framework versions moved, the country packages affected, advisories not accepted); the generated
    workspace's `e2e` and `image:info` scripts and Playwright configuration. The weekly `npm run audit` is
    scheduled by the country workflows of P5 and P6. P3c was then carried out as P3c1 (below) and P3c2.
- **P3c1 as built.**
  - **The info image from a built `dist`.** `ui-apps/info/Dockerfile` only packages: its `node:24-alpine` build
    stage — `npm ci`, the copies of the workspace, `ARG COUNTRY` with its refusal, the staging step, `ng build` and
    the pre-compression — is gone, and `Dockerfile.dockerignore` with it (`git rm`), since no workspace is a build
    context any more. The `alpine:3.22` stage is unchanged in behaviour (the packages, the log links, the five
    nginx files, `ENV WMS_SERVER_URL=` and `NIORD_TEASER_FRAME_ANCESTORS=*`, `EXPOSE 80`, the entrypoint and
    command) but copies `nginx/<file>` and `browser/` from a context `country-kit/image-info.mjs` assembles; its
    header names the separation check and lint where it said "the first and only structural separation
    guarantee". `ui-apps/info/nginx/precompress.mjs` exports `precompress(root)`, keeps its command line and exit
    code 2 behind the `invokedDirectly` guard, and runs over the context's copy, never over the `dist` the checks
    read; `.map` left its list of text formats, since a source map is refused before it runs.
    `country-kit/lib/info-image.mjs`, built-ins only: `parseTag` (an optional registry host and port, lower-case
    path components, a tag; no digest, no leading `-`, nothing past 255 characters), `readAllowList`,
    `outputProblems` (§4.11 rule 5's refusals: a `.map` file, a `sourceMappingURL` comment in a `.js` or `.css`,
    no `index.html`, an `index.html` script, stylesheet or `modulepreload` without `-<8 characters>.(js|css)`, a
    `.br` or `.gz` already present, a link — symbolic, or a hard link by its link count — or another non-regular
    file, and any file no glob of the allow-list matches), `assembleContext` (refuses a folder that is not empty;
    copies the Dockerfile, the five nginx files and every allowed file of the `dist`, each checked with `lstat`
    again while it is copied, then pre-compresses), `imageFilesProblems` (every `dist` file in the image byte for
    byte, every other file a `.br`/`.gz` twin that unpacks to its original), `dockerBuildArgs`
    (`build -f <context>/Dockerfile -t <tag>`, one `--label` per label, the context) and `defaultRun`
    (`spawnSync` with an argument array and `shell: false`). `country-kit/info-image-allowed.json` has nine globs,
    each with its reason, derived from the `dist` of `build:info`, `build:info:dk`, `build:info:gl` and the
    example: `index.html`; `teaser-frame.{html,js}` (unhashed by design: the pages that embed the teaser address
    them by name); `{main,chunk,styles}-*.{js,css}`; `media/*.woff2`; `assets/i18n/*.json`;
    `assets/img/flags/*.png`; `assets/branding/*.{json,png,webp,ico,svg}`; `assets/legal/*.html`;
    `assets/portrayal/*.svg`. None for other images under `assets/img/` or favicon files at the root, which no build
    ships; a glob with `**` or an entry without a reason makes the list unreadable.
  - **`image:info`** — `node <kit>/image-info.mjs --tag <ref> [--dist <dir>] [--package-only] [--no-browser]`,
    exit 0 built (and in a country checked), 1 refused or a check failed (every finding printed), 2 a usage
    error or Docker or Chromium missing. In a country workspace (the kit under `<cwd>/.niord/base/`) it refuses
    `--package-only`; `NIORD_BASE` or a `NIORD_PACK_<LANG>` while `CI` is set; a workspace with no build report or
    set up from a local source, through `guard-build.mjs`'s `refusal`; a build that staged no legal document (it
    asks the container test for the default language's disclaimer, else the first staged one; since P3c2 a
    warning and named skips instead, below); a `dist` without
    `browser-stats.json` (exit 2: `npm run build` passes `--stats-json`); and, unless `--no-browser`, a Chromium
    that does not start — launched through `@playwright/test` resolved from the workspace, since Playwright decides
    which browser build a launch takes and a path check would fail after `--only-shell` (exit 2, naming
    `npx playwright install --only-shell chromium`). Then the output refusals, all before Docker is asked. It
    assembles the context in a fresh temporary folder, builds it with the labels
    `org.niord.build-report=<the report as compact JSON>` and `org.opencontainers.image.version=<base version>`,
    each one argument, and removes the folder; copies the web root and the security headers out of the image
    (`docker create`, `docker cp`, `docker rm`) and compares them with the `dist`; and runs the separation check
    (`--stats <the build's stats> --root .niord/base/ --country <cc>`), the layout check (`--dist` and `--nginx` on
    the copies) and the container test (`--image`, `--legal-doc`, `--language <the public site's default>`,
    `--browser`), each a child process of the same Node with constant arguments. It never pushes: the tag is left
    for the country workflow. `--no-browser` leaves out the layout check and the container test's browser part,
    and says the image is not checked for a push. `--package-only` is the base product's own images: it stops
    after `docker build`, takes a tag without a version (`niord-dk-info`; a country's must name one), is the
    base product's `npm run image:info` (`node country-kit/image-info.mjs --package-only`), and is refused in a
    country, while outside one `image:info` without it exits 2. The only manifest or workspace values that reach
    a command are the country code and the default language (both `^[a-z]{2,3}$`) and a staged legal file's name.
  - **The public site's checks with parameters**, each with the output and verdict of before when the new option
    is absent: `info-separation-check.mjs --root <prefix>` (whole relative segments ending in `/`, no `.` or `..`)
    and `--country <cc>` (requires `--root`), which allow and refuse what §4.8 lists; `info-layout-check.mjs
--nginx <dir>`, whose missing header file, or one without a Content-Security-Policy, exits 2 before Chromium
    starts; `info-bundle-report.mjs --root <prefix>`, the baseline's entry points matched under it; the container
    test's `--language <lang>` (`^[a-z]{2,3}$`, default `en`), the bundle and the branding file it probes. The
    scripts resolve `--stats`, `--dist`, `--baseline`, `--json` and `--md` against the current folder (every base
    product npm script runs from `fe/`, so nothing moved there). The layout check's check 11 (a table that changes
    width on a language switch) is recorded as a named skip when the build does not offer both Danish and
    English, since its fixture changes width only between those two; the last line counts the skips.
  - **The checks on every country build** (`country-kit/check-build.mjs` with `lib/build-checks.mjs`), the last step
    of the generated `build`, over `dist/niord-app/browser` and `dist/niord-info/browser`: (1) the UI bundles
    exactly each application's `uiLanguages`; (2) the flags exactly `<lang>.png` of the languages each names; (3)
    nothing from `status/`, `node_modules/` or `.niord/`, no `ATTRIBUTION.txt`, no flag drawing, no
    `assets/default/` or two-letter overlay folder under `assets/`; (4) no external font or style URL
    (`checkDistUrls`) and no absolute URL outside `scripts/build-urls-allowed.json` (`checkAbsoluteUrls`); (5) each
    recorded utility in its application's CSS; (6) the secret scan of both; (7) the separation check with
    `--root .niord/base/ --country <cc>`; (8) the bundle report against `info/bundle-baseline.json` with
    `--root .niord/base/`, or, with no baseline yet, a warning naming how to start one. Exit 0, 1 on a finding
    (all printed), 2 when a `dist` or the stats are missing; the two child processes run through an injectable
    `run` with constant arguments.
  - **The absolute-URL rule** is `scripts/build-urls-check.mjs --absolute`, with an exported `checkAbsoluteUrls`
    and the base product's `scripts/build-urls-allowed.json`: 12 entries, each a regular expression matched
    against the start of the URL found (most whole matches ending in `$`), an optional `files` glob and a reason;
    an entry without a reason, with an unknown field or with a pattern that does not compile fails the check
    itself. A bare scheme such as `"https://"`, which code tests a value against, names no address and is not a
    URL. `assets/legal/**` is not read. Without `--absolute` the script does what it did. It takes its glob
    matcher from `../country-kit/lib/include.mjs`, as `build-contents-check.mjs` already takes the kit's (since
    the kit move's KM-1: from `scripts/glob.mjs`, the base product's own copy, which `scripts/glob.test.mjs` holds
    byte for byte to the kit's).
  - **The secret scan** (`country-kit/lib/secrets.mjs`, `country-kit/secret-scan.json`): fourteen pattern ids —
    `private-key`, `aws-access-key-id`, `github-token` (`ghp_`, `gho_`, `ghu_`, `ghs_`, `ghr_`),
    `github-fine-grained-token`, `gitlab-token`, `slack-token`, `google-api-key`, `stripe-live-key`, `npm-token`,
    `anthropic-key`, `openai-project-key`, `jwt`, `url-credentials` and `secret-assignment` (§4.11 rule 4). The
    assignment matches only a name ending in the secret-like word, optionally followed by `key`, an `s` or digits
    (`apiKey`, `CLIENT_SECRET`, `password2`; not `tokenData` or `passwordResetUrl`), and the bounded patterns
    cannot backtrack badly (the administration `dist` scans in about 150 ms). Set-up's `runChecks` scans the
    inputs (`secretScanInputs`) after the dependencies, each finding an error `secrets: <file>:<line>: <id>`
    reported with the others before set-up stops; a finding shows at most the value's first four characters and
    its length. The allow-list's entries are `{ pattern, files, scope, reason, country? }`: `scope` (`inputs` or
    `dist`) and `country` keep an entry to the scan it is for, so a glob matching no file of that scan can be the
    error it is.
  - **The utility record** (`country-kit/lib/utilities.mjs`): `countryUtilities` takes, per application, the first
    Tailwind class with an arbitrary value — a static `class` or a `[class.<name>]` binding, an arbitrary property
    included — of every `.html` under `<app>/app/` and `<app>/overrides/` and every inline `template:` of a
    non-spec `.ts` there; set-up writes them, sorted, to `.niord/build/country-utilities.json` (`{ format: 1,
admin, info }`) and prints them. `cssSelector` escapes a utility as Tailwind 4.2 writes it, held to the output
    of the installed `tailwindcss` in the test's fixture and, in the harness, to the example's real minified CSS.
  - **The zip and the workspace.** `zip-include.json` gained the five public-site checks and
    `scripts/build-urls-allowed.json` (§4.11 rule 6) and lost `Dockerfile.dockerignore`; `workspace-files.json`
    says the checks run from `.niord/base/scripts/`; a `zip.test.mjs` case holds every zipped script to imports the
    zip carries. The generated `package.json` (`BUILD_SCRIPTS` in `lib/workspace.mjs`, constant text): `build` is
    `guard-build.mjs && audit.mjs && ng build niord-app && ng build niord-info --stats-json && check-build.mjs`,
    each under `.niord/base/country-kit/`; `bundle:info` is `info-bundle-report.mjs --root .niord/base/` over
    `dist/niord-info/` with `--baseline info/bundle-baseline.json`, writing its report into `dist/`; `image:info`
    is `node .niord/base/country-kit/image-info.mjs`.
  - **The example country** has its own baseline, `example/fe/info/bundle-baseline.json`, seeded from the base
    product's (the same `landingRoute` and entry points, matched under `--root`), recorded with
    `npm run bundle:info -- --update-baseline` in the harness's work copy and copied back, its `source.build`
    naming the country build. The harness takes `--image` and, with it only, `--with-deps`: after the lint it runs
    `npx playwright install --only-shell chromium` (with `--with-deps` its system libraries) in the work copy and
    `npm run image:info -- --tag niord-xx-info:example`, and removes that tag, and only that one, whatever the
    outcome; on Windows `npx` starts through the shell as `npm` does, with constant arguments.
  - **The country template.** `templates/image-frontend-job.yml`: after the build, Chromium with its libraries,
    the image's name — `<REGISTRY>/<HARBOR_PROJECT>/<repository>-info:<base>.<run number>`, the repository's name
    lower-cased in the step's shell (`${name,,}` on `GITHUB_REPOSITORY`'s last segment), the base version read
    from the build report by a constant `node -e` that holds it to `^[0-9]+(\.[0-9]+)*$` before it enters
    `GITHUB_ENV`, and the step failing with a message when `REGISTRY` or `HARBOR_PROJECT` is unset — then
    `npm run image:info -- --tag "$IMAGE"`, `docker save` with the tar's SHA-256 uploaded beside `frontend-dist`,
    and the job output `info-image`; its timeout 45 minutes. `templates/image.yml`'s push job downloads both
    artifacts, checks both SHA-256 files, loads the image, logs in to Harbor with the robot (password on stdin,
    the registry taken from the reference, which reaches the shell through `env`; since the review of PR #184 the
    workflow-level `REGISTRY`, after the whole reference is checked, §4.11 rule 1) and pushes; the placeholder
    narrowed to the administration bundle's image stays its last step. The push job runs no npm and no kit code.
  - **Info CI** has one work job: the Images job is folded into Checks + builds, so its second checkout, `npm ci`,
    Chromium install and second pair of country builds are gone. Each country build's URL check is followed by
    `npm run separation:info` on that build's statistics (`Separation from the administration app, Danish
    artefact` and `…, Greenland artefact`: each build swaps other files in, so each has its own module graph).
    After the Danish build's URL check, separation check, markers and layout check comes
    `npm run image:info -- --tag niord-dk-info`, after the Greenland one's `niord-gl-info`, and last the two container-test steps with the commands, arguments and comment of before,
    each on its own image step and Chromium. The gate needs `changes` and `checks`. The path rules (push paths,
    `Detect`, `verify-local`'s `INFO_RULE`) gained what `image:info` imports or reads — `image-info.mjs`,
    `guard-build.mjs`, `info-image-allowed.json`, `lib/info-image.mjs`, `lib/include.mjs`, `lib/build-report.mjs`,
    `lib/manifest.mjs`, `lib/schema.mjs` and `niord-country.schema.json` — and the push paths
    `fe/scripts/build-*.json`, which `Detect`'s `fe/scripts/build-` already matched; the Prettier step gained the
    two new modules. The job timeout stays 30 minutes. **Frontend CI**'s `EXAMPLE_RULE` (and `verify-local`'s)
    gained the five scripts and the URL allow-list, the rest of `fe/scripts/` still left out; its Example country
    job runs the harness with `--image --with-deps`. **`verify-local`**'s example row passes `--image` (never
    `--with-deps`, which needs root) when `docker info` answers, and otherwise runs without it and says the image
    is Frontend CI's; its closing note says the base product's DK and GL images and their container tests are
    still Info CI's. Its public-site row runs `separation:info` after `build:info:dk` and after `build:info:gl`,
    each after that build's `urls:check`.
  - **Negative tests** under `test:guards`, one per new rule: `image-info.test.mjs` (Docker and the network
    replaced by an injected `run`): each malformed tag (upper case, none, a digest, `;`, a leading `-`, a space),
    the base product's tag without a version and a country's refused; a source map, a `sourceMappingURL`, an
    unhashed `index.html` bundle (the `--configuration local` shape), a missing `index.html`, a file outside the
    allow-list, a twin before the context, a symbolic and a hard link; a context folder that is not empty; an image
    with a file of other bytes, one missing, one extra and a twin of other bytes; in a country `--package-only`, no
    report, local provenance, `NIORD_BASE` and a `NIORD_PACK_*` under `CI`, no staged legal document, a
    non-production `dist` before Docker is asked, a missing Chromium, an image unlike the `dist`, a failing check;
    in the base product no `--package-only` and an unknown option — and the positive shapes: the argv constant with
    the tag and each label one element, the context only the Dockerfile, five nginx files and the pre-compressed
    `dist`, the `dist` itself unchanged. `info-separation-check.test.mjs` (27): administration code, `../`,
    `keycloak-js` and another country folder in the base product; the base product's, the country's and the
    generated administration code, another country's tokens; `--country` without `--root`, a bad code or prefix,
    a missing stats file. `info-layout-check.test.mjs` (4) and `info-container-test.test.mjs` (3): the new
    options' refusals, none launching Chromium or Docker. `info-bundle-report.test.mjs`: entry points under
    `.niord/base/` matched only with `--root`, relative paths against the current folder. `check-build.test.mjs`
    (12): an extra and a missing bundle, an extra and a missing flag, a file under `assets/i18n/` that is not a UI
    bundle and one under `assets/img/flags/` that is not a flag, a flag drawing, an `ATTRIBUTION.txt`, a
    `status/` file, a `.niord/` file, a `node_modules` path and an `assets/dk/` file, an external font URL and an unlisted absolute
    URL, a utility missing from the CSS, a secret in a built script (not printed), the separation check and the
    bundle report exiting 1, a missing baseline warning and passing, the exit codes. `secrets.test.mjs` (21): one
    crafted value per pattern id, built by concatenation so no test file carries a matching literal; the
    near misses (a sentence, a 15-character value, `${TOKEN}`, the identifier shapes); an allow entry suppressing
    exactly its pattern in its files; an entry without a reason, with an unknown id or scope, or matching no file;
    a binary file skipped; no finding in the kit itself. `utilities.test.mjs` (4); `build-urls-check.test.mjs`
    (11): an absolute URL in a `.js`, a `.css` and an `.html`, an allow-listed one, a legal document not read, an
    entry without a reason, the old behaviour without `--absolute`. `setup.test.mjs` (31 in all): a crafted token in the
    example's public-site branding and in its text override stops set-up before staging without printing the
    value; the utility record holds the footer's utility outside `.niord/build/info/`. `workspace.test.mjs`,
    `zip.test.mjs`, `init.test.mjs` (7 in all: the frontend job runs `image:info` and `docker save` and composes
    the image's name with the repository's name lower-cased in its shell, the push job checks
    `info-image.tar.sha256` and runs no npm) and `example/run.test.mjs` (`--image`, `--with-deps` alone,
    `--image` with a value; 10 in all).
  - **Verification.** Before any edit the DK and GL images were built from the unchanged tree (64 s together) and
    their files listed with their sizes: 136 and 149. After the change, from `build:info:dk` (7 s) and
    `build:info:gl` (9 s), `image:info --package-only` built each in 8–9 s and the images serve exactly the same
    files — 136 and 149 names and sizes, the bundle hashes included — with the nginx files' checksums, sizes and
    modes, the entrypoint, command, environment and exposed port the same; on that Windows Docker Desktop every
    packaged file is mode 0755 (below). The separation check counts 88 modules on `build:info` and 92 on each
    country build, the old and the new script alike; the layout check holds 47 of 47 (DK, 19 s) and 61 of 61
    (GL, 18 s); the container test with `--browser` passes its 165 assertions against each image (35 s each, GL with
    `disclaimer.kl.html`); `build:info` stays within `ui-apps/info/bundle-baseline.json`, which was not
    re-recorded, and `image:info` packaged it in 11 s. By hand: a `--configuration local` build is refused with
    78 problems (the maps, their comments, the unhashed `main.js` and `styles.css` outside the allow-list and named
    by `index.html`), a malformed tag exits 2, and no image is made. The six builds with `urls:check` green (39,
    60, 37, 11, 13 and 13 s) and `contents:check` (17 configurations, 338 files); the absolute-URL rule, measured by
    hand, finds nothing in `build`, `build:dk`, `build:gl`, `build:info` or the example's two builds, and three
    URLs in each of `build:info:dk` and `build:info:gl` — `https://www.brs.dk` twice and
    `https://www.was.digst.dk/nautiskinformation-soefartsstyrelsen-dk`, DK's own, from the configuration GL shares
    today — recorded, not gated. The secret scan finds nothing in the six `dist`, the example's inputs with its
    generated `zz`, DK's and GL's in-tree inputs and the kit. The example harness, run locally against a zip of the
    working tree: without a baseline green in 227 s, with check-build's warning; after the baseline was recorded,
    with `--image` green in 255 s — the build's checks passing (the bundle report within the new baseline, the
    utility `sm:grid-cols-[3fr_2fr]` found in the real CSS, separation 96 modules), then against the image the
    separation check, the layout check (32 checks, check 11 skipped) and the container test with `--browser` and
    `--language zz` (165 assertions), and the tag removed. By hand in its work copy: the footer's utility removed
    from the built CSS fails check-build, naming it; `ng build niord-info --configuration local --stats-json`
    then `image:info` is refused; after a `NIORD_BASE` set-up `image:info` and `npm run build` are refused, and
    `CI=1 NIORD_BASE=..` refuses `image:info`. `source-zip.mjs verify` passes on the harness's zip, whose `scripts/`
    entries are exactly the five checks and the URL allow-list. The Lato face and the utility are in the example's
    `dist` only. The packages' own test files green (90 tests in the five of the public site's checks and the
    image, one symbolic-link case skipped; the kit's files as counted above); `test:guards` whole 663 tests,
    660 passed and 3 skipped (the symbolic-link cases this Windows account cannot make); Prettier and ESLint clean
    on the changed files; `bash -n verify-local`; both workflows parsed (js-yaml 4.3.2), and the three
    `INFO_RULE` copies and the two `EXAMPLE_RULE` copies agree over every tracked file and the new ones, every
    module `image-info.mjs` reaches matched. After the review's fixes: `test:guards` whole 665 tests, 662 passed
    and the same 3 skipped; `check-build.test.mjs` with `init.test.mjs` 19 of 19, `info-separation-check.test.mjs`
    27 of 27, `info-bundle-report.test.mjs` 9 of 9; `build:info:dk` and `build:info:gl`, each followed by
    `separation:info`, 92 modules each. Three runs of the example harness with `--image` stopped at the same
    step, the npm audit of `audit.mjs` inside `npm run build`, on a network timeout at registry.npmjs.org (the
    same audit alone in the same work copy passed in 20–26 s); the zip, the scratch Maven install, the copy,
    set-up with the secret scan and `npm ci` passed. The second checker's whole run with `--image`, after the
    review's fixes, was green in 242 s, the npm audit included: `check-build` (admin bundles and flags `en`,`zz`,
    the public site's `zz`, 1 country utility, separation 96 modules, the bundle report within the example's
    baseline), the lazy chunks, the text overrides, `npm test` and lint, then the image holding exactly the
    dist, separation 96, layout 32 checks with the 1 named skip and the container test with `--browser` (165
    assertions); the baseline re-recorded in the work copy equals the committed one. `verify-local` passed with
    both applications' whole suites, each country artefact's URL and separation checks, and its _example
    country_ row with the image (4 min 17 s). Not yet run: Info CI's
    checks job with the two images and Frontend CI's `Example country` job with `--image` (first on PR A).
  - **Changed from the plan, and corrected above.** §3.2 step 2: `image:info`'s refusal of a locally staged
    workspace is `guard-build.mjs`'s, plus its own check of `CI` with `NIORD_BASE` or `NIORD_PACK_*`. Step 5: `build`
    ends with `--stats-json` and `check-build.mjs`; `bundle:info` is a generated script too, since a country records
    and updates its baseline with it; `e2e` is P3c2's. §4.2: `info/bundle-baseline.json`. §4.8: the checks run
    inside `npm run build`; the utility record is `.niord/build/country-utilities.json`; the bundle report gained
    `--root` (it did not take what a country needs), the container test `--language`, the layout check's option is
    `--nginx <dir>`; one source per output is P3c2's; the base product's own images are made the same way. §4.11
    rule 4: the patterns and the allow-list, whose entries also carry `scope` and an optional `country`. Rule 5:
    the absolute-URL rule's form and allow-list; the context also holds the Dockerfile, pre-compressed there;
    what "against the built image" means; the OCI labels' names (rule 2). Rule 6: the zip's `scripts/` files. `image:info --tag`:
    a version is required in a country, not for the base product's `--package-only` images, which are never
    pushed. §7: the public site's `CLAUDE.md` items done. R8 is unchanged: it already has the checks published in
    the zip and called with parameters.
  - **Found on the way.**
    - The layout check could not pass on a build with one language: its check 11 compares a table's width in
      Danish and English and failed a build without both as proving nothing, so it failed against the example's
      image; it is now a named skip there, and DK and GL assert as before.
    - A Windows Docker Desktop build gives every file of a context taken from a Windows folder the executable
      bit, so the locally built images hold every file at 0755, where the old image's build stage wrote 0644 for
      the built files (0755 for the 18 or 21 files copied from `public/`). nginx only reads them, and on Linux,
      where CI and the deployed images build, the context keeps the `dist`'s modes; no `COPY --chmod`, which would
      also set the folders' modes.
    - `info-bundle-report.mjs --update-baseline` cannot start a baseline from nothing (it refuses a missing file
      and needs `landingRoute` and `landingEntryPoints`): a country copies the base product's
      `.niord/base/ui-apps/info/bundle-baseline.json` to `info/bundle-baseline.json` and then runs
      `npm run bundle:info -- --update-baseline`, as check-build's warning says. The baseline's `_comment` was
      the report's fixed text, naming the base product's file in a country's baseline too; it now names the
      file `--baseline` gave, so a country's re-recorded baseline names its own `info/bundle-baseline.json`.
    - The measurement shaped the secret scan: the `da` pack's fingerprints, a Lezer `tokenData` table, the token
      names in `seams.json` and a base product spec's stand-in were each a false positive of the plain assignment
      rule; each shape is now never a secret, with its near miss tested.
  - **What P3c2 keeps:** the new-country acceptance tests (untouched `init` — including how `image:info` behaves
    for a country with no legal document and no bundle baseline, and the baseline's start — and the upgrade test);
    the upgrade report (§4.12), with `--accept-override`'s diff of each `basedOn` file since the recorded
    fingerprint, which needs the previous version, and with its sections on the country API and on dependencies;
    the generated workspace's `e2e` script and Playwright configuration, with the end-to-end suite and its login
    fixtures off DK's test Keycloak and into the zip; and one source per output file (`resolveAssets` over a
    country workspace's generated `angular.json`, §4.8), which the kit cannot reach with built-ins only. All of it
    was built in P3c2 (below).
- **P3c2 as built.** With it P3 is complete: its _Done when_ — a country builds from a folder outside the base
  product tree in the base product's own CI — holds locally through the harness and `verify-local`'s example row,
  and in CI first on PR A.
  - **One source per output (§4.8, R9).** The builder-backed asset resolution moved out of
    `scripts/build-contents-check.mjs` into a kit module, `country-kit/lib/asset-outputs.mjs` (`builderDir`,
    `builderModule`, `resolveOutputs`, `duplicateOutputs`), which loads `@angular/build` at run time from the
    workspace root through `createRequire`, so the kit still imports only Node built-ins and both checks share one
    implementation. `contents:check` calls it (re-exporting `builderModule`, its `(file, packageDir)` signature
    kept, and `resolveOutputs`), its rule 1 through `duplicateOutputs` with the same message; its output is
    unchanged, 17 configurations and 338 files. check-build gained **check 9**, `oneSourcePerOutput` in
    `lib/build-checks.mjs`: it reads the generated `angular.json` and resolves the asset list of the options and of
    each configuration of `niord-app` and `niord-info` (a configuration's own list, else the options'), each
    distinct list once and labelled with every configuration that uses it, and fails every output path two inputs
    write: `one source (niord-app:production, development, local, test): assets/img/niord.png is written by 2
    inputs (.niord/base/ui-apps/admin/public/assets/img/niord.png, .niord/build/admin/img/niord.png)`. A list that
    does not resolve is a finding; a missing `angular.json` exits 2 (run `node setup.mjs`); `check-build.mjs`'s
    `main` is async. Over the base product's own `angular.json` it resolves 13 distinct lists with no finding.
  - **`image:info` for a country with no legal document or branding text.** No longer a refusal: with no legal
    document staged it warns, naming `info/assets/legal/disclaimer.<lang>.html`, and passes the container test
    `--no-legal-doc`; with no `.niord/build/info/branding/<lang>.json` for the public site's default language it
    warns and passes `--no-branding-text`; otherwise its arguments are those of P3c1.
    `scripts/info-container-test.mjs` gained both options: `--no-legal-doc` turns the four legal-document probes —
    the file served, its sandbox headers, the percent-encoded path and, with `--browser`, its opaque origin — into
    named skips, `--no-branding-text` the branding text's sandbox probe, each listed and counted in the last line;
    `--no-legal-doc` with `--legal-doc` exits 2. Without them its assertions and default are those of P3c1.
  - **The generated workspace's `e2e` (§3.2 steps 5–6).** `lib/workspace.mjs` generates the script `e2e`,
    `playwright test --pass-with-no-tests`; a constant `playwright.config.ts` that spreads the base product's
    `./.niord/base/playwright.config` with `testDir: './e2e'` and `tsconfig: './.niord/tsconfig.e2e.json'`; and that
    `.niord/tsconfig.e2e.json`, mapping `@niord/admin/e2e` to `./base/ui-apps/admin/e2e/auth/fixtures.ts` (Playwright's
    `tsconfig` option with `paths` and no `baseUrl`, checked with the installed 1.59.1). No manifest value reaches
    either. `workspace-files.json` lists `playwright.config.ts` under the generated files, and the ignore list of
    `init` and of the example gained `/playwright.config.ts`, `/test-results/` and `/playwright-report/`.
    `API-CHANGES.md` records the alias under _Unreleased_ as an addition a country need not act on.
  - **The e2e suite names no environment, and is in the zip (§4.11 rule 6).** `keycloak-login.ts` requires
    `KEYCLOAK_URL`, its error naming the local stack's `http://localhost:8080/auth` and where the contract is:
    `e2e/auth/README.md` in the base product, the kit README's _End-to-end specs_ in a country workspace (realm `niord` and client `niord-web` stay the defaults); `auth-fallback.spec.ts` blocks every `**/realms/**`
    request instead of DK's login host; `app-breadth.spec.ts` reads a published short id from
    `/rest/public/v1/messages` through `baseURL` instead of DK's test site. `zip-include.json` takes in
    `ui-apps/admin/e2e/**` and the root `playwright.config.ts`; the suite's README stays out with every README
    under `ui-apps/`. The suite's README, `fe/docs/testing.md` and the backlog item say so; the base product's own
    suite runs with its environment given explicitly.
  - **The upgrade report (§4.12).** `lib/previous-base.mjs`: `readRecord` reads the last set-up record, the build
    report `.niord/build/niord-build-report.json`; `previousBase` reads that version's files from
    `.niord/niord-fe-src-<version>.zip` only when its sha256 is the record's, through `lib/zip.mjs`, and otherwise
    says why not (a record from `NIORD_BASE`, no zip, another hash, an unreadable zip); nothing is fetched.
    `lib/upgrade-report.mjs` (`upgradeReport`, with `changeSections` and `requiredActions` reading
    `API-CHANGES.md`) writes the five sections of §4.12 in its order from structured findings, never from the
    checks' message texts: `checkPacks` gained `missing`, `markup`, `changedEnglish` and `hatchStale`;
    `checkTextOverrides` `leftCatalog` (with a renamed key's successor), `stale`, `redundant`, `differing` and
    `markup`; the dependency check `peers` and `added`; `lib/api.mjs` `deprecatedUsesOf` with each use's `since` and
    `removedIn` — each additive, the printed errors and warnings unchanged. The branding items compare the current
    `branding.schema.json` with the country's branding files (required keys and files it lacks; keys and files it
    fills that the schema no longer names). Lists stop at 20 names and a diff at 40 lines, with the rest counted.
    `setup-kit.mjs` reads the record before the override options, passes the previous version to
    `--accept-override`, and after the checks, on a version change, writes `.niord/niord-upgrade-report.md` and prints
    its path — before it stops on a failed check; a first set-up prints that it has no record, so no report. The
    build report, the record, is still written only on success.
  - **`--accept-override`'s diff since the recorded fingerprint** (§4.11 rule 6). `lib/overrides.mjs`'s
    `diffSinceRecorded` gives the base product's own `lineDiff` of a changed `basedOn` file from the previous
    version's file to the current one when the previous file's fingerprint is the recorded one; otherwise
    `acceptOverride` prints one line saying why it is not available. The copy-against-current diff of P3b1 stays.
  - **Deprecations with their end (§4.3).** The marker takes an optional end, `Deprecated since <version>, removed
    in <version>: <what to use instead>`; a `Deprecated since` line of another form, or a removal not after its
    start, fails set-up, and the warning names the removal version or says it is not scheduled yet.
    `API-CHANGES.md`'s policy and _How an entry is written_ say so.
  - **The acceptance tests (§4.8)**, `country-kit/example/acceptance.mjs`, driven by `run.mjs` after the example's
    own steps, against the same zip: first `npx playwright test --list` in the example's work copy, which must list
    its new spec `e2e/harbour.spec.ts` (an editor opens the harbour page, through `@niord/admin/e2e`; only listed,
    never run against an environment). **The untouched init:** `yy`, made in `<work>/yy` by the zip's own `init`
    (a minimal `be/pom.xml` with `niord-parent` `4.0.0-example`, the base product's Maven wrapper, the zip copied to
    `fe/.niord/` and unpacked into `fe/.niord/base/` with `readZip`, `init yy --zip-sha256 <hash>`, never
    `NIORD_BASE`), then set-up, `npm ci`, `npm run build` with check-build's no-baseline warning asserted,
    `npm test`, `npm run lint`, `npm run e2e -- --list`, the baseline recipe of P3c1 (check-build then passes
    without the warning), and with `--image` `image:info --tag niord-yy-info:example` with both warnings asserted
    and the tag removed in `finally`. **The fixture upgrade:** `fixtureZip` derives `4.0.1-example` from the built
    zip in memory — `common.retry`'s English text changed with its catalog fingerprint recomputed, a comment line
    appended to `message-selection-toggle.html`, `roleGuard` marked `Deprecated since 4.0.1-example, removed in
    4.0.2-example` with a matching `API-CHANGES.md` section that asks nothing of a country — verified with
    `verifySourceZip` and installed with `niord-parent` at that version; the example's work copy, its pom and pins
    bumped, must fail set-up on its override alone and write the report, whose title, five headings in order,
    changed key, the override's `+` line and `roleGuard` with `removed in 4.0.2-example` are asserted; then `zz`
    pseudo-localised from the new English, `--accept-override` with its diff since the recorded fingerprint
    asserted, set-up green with the deprecation warning, `npm ci` only when the lockfile changed, and the build.
    **The upgrade test:** `--upgrade-from <zip>` (its version from the zip's `catalog.json`; refused without a
    value, for a missing file, one that is not a zip, a path `MAVEN_ARGS` would split and the harness's own two
    versions) sets that zip's own example up at its version, bumps it to the zip under test, applies the routine
    actions, sets up and builds; a failure left passes only when a version section of `API-CHANGES.md` new since
    that zip says a country must act — read with the upgrade report's own `changeSections` and `countryActions`,
    so the format has one reader and a policy section is never an action; a section whose every answer is
    "Nothing" asks for none. The classifier counts only an override whose base file changed as routine; a removed
    base file, a protected override, a security fix, a pack error or a set-up that failed before its checks are
    not. Without the option the harness prints `example: upgrade test: skipped — no niord-fe-src is published yet
    (P4); the mechanism ran on the fixture pair` (reworded in P4, which gives the option its zip) and appends it, with the fixture upgrade's line, to
    `$GITHUB_STEP_SUMMARY` when that is set, each line a paragraph of its own.
  - **CI and `verify-local`.** Frontend CI's `EXAMPLE_RULE` (and `verify-local`'s) gained `playwright\.config\.ts`
    among the root files; the Example country job's comments and step name describe the acceptance runs; its
    timeout stays 30 minutes and the gate is unchanged. Info CI's push paths and `Detect`, and `verify-local`'s
    `INFO_RULE`, gained `fe/country-kit/lib/asset-outputs.mjs`, which `contents:check` imports. `verify-local`'s
    example row names the untouched init, the fixture upgrade with its report, and repeats the harness's upgrade-test
    line from `example.log`.
  - **Negative tests** under `test:guards`, one per new rule (79 new tests; `test:guards` 665 → 744):
    `upgrade-report.test.mjs` (30, new: fixture trees with the previous version as a zip built and read in memory —
    the headings in order and the passed and failed notes; per To-do item a home and a foreign pack's new key, a
    markup mismatch, a changed overridden file with its diff since the recorded fingerprint, a now-protected
    override, a removed and a newly required branding entry, a text override that left the catalog with its new
    name, a peer rejection, an `API-CHANGES.md` action (a "Nothing" not listed), a security fix touching an override
    and the three not-checked lines; per To-look-at item changed English, redundant, differing and stale overrides,
    a deprecation with and without its removal version, a stale escape-hatch entry; a new hook an override is based
    on and a new API export; the dependency changes; every "not available" line; `readRecord` and `previousBase`
    with a missing zip, a local record, a hash mismatch and a malformed record); `acceptance.test.mjs` (22, new:
    the fixture's three changes, its recomputed fingerprint, its deterministic bytes and a `roleGuard` not where
    expected; the section reader and "Nothing"; a reworded policy section that is no new section and a failure it
    does not let pass; the classifier; the verdict; the report headings; the step summary;
    the pom bump); `setup.test.mjs` (35, +4: no record, no report; end to end from the example, a red bump that
    still writes the report and keeps the record, `--accept-override` printing the diff since the recorded
    fingerprint and advancing the record, no report for the same version); `overrides.test.mjs` (+2), `api.test.mjs`
    (+3: the end read, a malformed one and one not after its start refused, the warning), `pack.test.mjs`,
    `text-overrides.test.mjs` and `dependencies.test.mjs` (+1 each, the structured fields); `check-build.test.mjs`
    (15, +3: a staged file and a base product input writing one output, resolved by the real `@angular/build`,
    naming both; distinct outputs passing; a project without a build target and assets that do not resolve) with its
    exit-code test extended to a missing `angular.json`; `image-info.test.mjs` (35: no legal document and no
    branding text each giving its option and warning, both staged giving the arguments of P3c1, in place of the
    refusal); `info-container-test.test.mjs` (5, +2: both legal options together in either order, a value after
    `--no-branding-text`); `zip.test.mjs` (18, +2: the suite in the zip names no country's test environment, and a
    crafted file that does fails); `workspace.test.mjs` (+2: the script, the configuration's text and the alias;
    two countries alike); `init.test.mjs` (+1, the ignore lines); `build-contents-check.test.mjs` (+1,
    `duplicateOutputs`); `example/run.test.mjs` (12, +2: `--upgrade-from` refused without a zip, with a missing one
    and with one `MAVEN_ARGS` would split; the previous zip's version and the harness's own refused).
  - **Verification.** The packages' own test files: the one-source, image, workspace and zip files 152 tests,
    151 passed and 1 skipped (the symbolic-link case); the upgrade files 172 tests, 171 passed and 1 skipped
    (57 s); the harness's two 32 of 32. `test:guards` whole 742 tests, 739 passed and 3 skipped (the symbolic-link
    cases this Windows account cannot make), 84–97 s. `contents:check` 17 configurations, 338 files, unchanged by
    the move; `languages:check` 0 errors, 0 warnings, 0 keys stripped (14 s). The six builds (`build`, `build:dk`,
    `build:gl`, `build:info`, `build:info:dk`, `build:info:gl`), each with `urls:check`, `separation:info` after
    each public-site build and `bundle:info` after `build:info`, all green, every `ng build` output table (37, 37,
    37, 18, 18 and 18 rows) identical to P3c1's `verify-local` logs: no Angular build input changed, only
    `ui-apps/admin/e2e/**` under `ui-apps/`. The DK image from `build:info:dk` (11 s) packaged by `image:info` (10 s)
    passes the container test with `--browser`, 165 assertions as in P3c1 (58 s in all); with `--no-legal-doc
    --no-branding-text --language da`, 160 passed and 5 named skips. The base product's suite lists with
    `npx playwright test --list` (190 tests in 43 files), and `auth-smoke.spec.ts` with `KEYCLOAK_URL` unset fails
    at once in `requireEnv`, naming it, before any request. In a scratch country workspace outside `fe/` the
    generated configuration and alias listed a spec importing `@niord/admin/e2e`, and with no spec
    `playwright test --pass-with-no-tests` exited 0. The example harness with `--image`, locally against a zip of
    the working tree: green in 585 s (P3c1: 242–255 s) — the example's build 51 s and image 69 s, the e2e listing
    3 s; `yy`'s init 6 s, set-up 13 s, `npm ci` 69 s, build with the warning 75 s, test, lint and listing, the
    baseline 7 s, its image with both warnings 71 s; the fixture zip 0.6 s, the red set-up with the report 12 s,
    `--accept-override`, the green set-up with the deprecation warning, the build at `4.0.1-example` 62 s; the
    skipped line printed; no npm audit timeout. Again with `--upgrade-from` a copy of the harness's own zip
    relabelled `4.0.0-previous`, and `GITHUB_STEP_SUMMARY` set: green in 493 s, `upgrade test: 4.0.0-previous to
    4.0.0-example set up and built green`, both lines in the summary file — the mechanism end to end, not a real
    version difference. By hand in the example's work copy, a copy of a base public file at
    `.niord/build/admin/img/niord.png` fails check-build with the message above and passes once removed. A sample
    report from the real example bump (a 1,768-file zip of 3.9 MB) is one readable page. `bash -n verify-local`;
    both workflows parsed (js-yaml); the two `EXAMPLE_RULE` copies and `verify-local`'s `INFO_RULE` with Info CI's
    `Detect` agree, and Info CI's push paths match `Detect` over all 4,229 tracked files and the new ones; Prettier
    and ESLint clean on the changed files. After the review fixes (the harness's reader of `API-CHANGES.md`, the
    login fixtures' error): `acceptance.test.mjs` 22 of 22, `zip.test.mjs` 18 of 18, `test:guards` whole 744
    tests, 741 passed and 3 skipped; Prettier and ESLint clean on the changed files. `verify-local` passed with
    both applications' whole suites and its _example country_ row, the untouched `init`, the fixture upgrade and
    the upgrade test's skip line included (13 min 7 s). Not yet run: Frontend CI's `Example country` job and Info
    CI on PR A.
  - **Changed from the plan, and corrected above.** §3.2 step 5: the `e2e` script's text, the generated
    `playwright.config.ts` spreading the base product's, `.niord/tsconfig.e2e.json` and `@niord/admin/e2e`; step 6:
    the fixtures through the alias, `KEYCLOAK_URL` the runner's. §4.3: the deprecation marker's optional end. §4.8:
    one source per output is built, in check-build through the builder's own code resolved from the workspace,
    where P3c1 thought the kit could not reach it; `image:info` warns and the container test skips by name for a
    country with no legal document or branding text, where P3c1 refused; the acceptance tests are steps of the
    Example country job and `verify-local`'s row, with the untouched country `yy` and the upgrade test's mechanism
    proven on a fixture pair until P4. §4.11 rule 6: the e2e suite and `playwright.config.ts` are in the zip;
    `--accept-override`'s diff since the recorded fingerprint is built. §4.12: where the report is, the record it
    compares with, that it is written on a failed set-up, and what this kit version cannot fill. §8: the
    Actions-minutes row. P3b2's and P3c1's records carry a pointer each.
  - **Found on the way.**
    - The upgrade test's previous example takes its `zz` pack's backend half, and `niord-parent` at the previous
      version, from the working tree's `be/`, because the zip carries no backend bundles: right for the mechanism,
      but a backend key added between the two versions would show only as a pack warning at the previous version.
      P4 can refine it with a real previous zip. (P4 left it unchanged: the previous zip now comes from the latest
      release, its backend half and `niord-parent` still from the working tree.) Corrected by the review of PR
      #184: it was not only a warning. A backend key the previous catalog has and the working tree's `be/` lacks
      (removed or renamed) or holds with other placeholders fails the previous version's pack check, `zz` being a
      UI language, so the first such base product change would have turned the Example country job red. The
      previous example's backend half now comes from the previous zip's `catalog.json`
      (`example/pseudo-localise.mjs`): the current English pseudo-localised where its placeholders and markup
      still match the catalog's entry, a stand-in with the entry's placeholders where they do not, and a markup
      key with no matching text listed in the work copy's escape hatch; `niord-parent` still comes from the
      working tree.
    - The first cut of `--no-legal-doc` dropped the legal document's sandbox-header probe silently (160 passed
      and 4 skipped of 165); it is a named skip now, so passed and skipped always add up.
    - The step summary's lines followed the bundle report's table directly and Markdown folded them into it; each
      is a paragraph now.
    - The login fixtures' error named only `e2e/auth/README.md`, which the zip leaves out with every README under
      `ui-apps/`; it now also names `country-kit/README.md`, _End-to-end specs_, which the zip carries, where a
      country reads the contract. The README rule of §4.11 rule 6 is kept. `csp.spec.ts`'s own missing-variable
      error, and the specs' header comments, still name only `e2e/auth/README.md`: a base product spec a country
      does not run, so left as it is.
    - The upgrade test's verdict first read every `## ` section of `API-CHANGES.md`, so a reworded policy section
      (_How an entry is written_) counted as a new section asking a country to act and could let a failure pass;
      the harness now reads it with the upgrade report's `changeSections`, version sections only.
    - The zip now carries the base product's e2e specs, which still name DK and GL data and words (`Dansk`, live GL
      data), though no test environment; P4's country-word guard over the zip meets them. (P4: with the words it
      chose — language names are not words — the e2e suite has no hit.)

**P4 — publish the source zip; the dry run.**
- `publish.yml` deploys `niord-fe-src:4.0.<run>` — built from the **explicit include list** of §4.11 rule 6
  (never `fe/docs/`, backlogs or go-live material; the country-word guard over its contents from this first
  publish), plus `catalog.json` at its root (`security.json` travels inside `country-kit/`, which the list selects), its sha256 in the notes of the release on its tag (§4.11 rule 2) — with its existing
  `deploy:deploy-file` pattern; `design.md` §3's
  registry table gains the zip and the packs.
- Verification: in a scratch directory, the DK and GL trees as they will be moved, set up against the
  published zip, build output equal to the in-repository DK/GL builds of P3 (differences listed and
  explained).
- Done when: PR A is merged and a published version builds DK and GL outside `niord-ng`. Met locally by the
  dry run below; it holds when PR A is merged, the first published version being PR A's, and that version builds
  DK and GL outside `niord-ng` (P4 has no staging step).
- **P4 as built.** Nothing was published, tagged or released and no workflow was triggered: the first deploy,
  release and Frontend CI upgrade run come with PR A's merge, through the owner-gated `publish` and `retract`
  environments.
  - **`publish.yml`.** Its header and _Secrets_ say what is published and why the zip is also a release asset;
    no new secret. The build job, right after the checkout and before `setup-java` and the reactor build (so
    Maven has written nothing into the tree the zip reads, and a finding stops the run before anything is
    deployed: `web-publish` now needs `build` as `harbor` and `tag` already did, so a failed zip check or
    reactor build also leaves no bundles-only `4.0.<run>`; the `web` job still builds the bundles beside it,
    with no token), runs `actions/setup-node` from `fe/.nvmrc` (no cache, no npm) and the step _Build and check the
    source zip_ (`id: fe-src`, in `fe/`): `source-zip.mjs build --version "$VERSION"` into `$RUNNER_TEMP`, then
    `verify` and `guard` on that archive, its `sha256sum` required equal to the `.sha256` file, and `sha256=`
    written as the step output and the job output `fe_src_sha256`. `VERSION` reaches it only from the gate,
    through the job's env; no run block holds an expression. After `niord-image-tools`, _Deploy niord-fe-src_
    uses the same `deploy:deploy-file` shape, server `github` and env as that step
    (`org.niord.web:niord-fe-src:$VERSION`, packaging `zip`, `-DgeneratePom=true`); its summary block gives the
    upload lines and `<sha256>  niord-fe-src-$VERSION.zip`, the sha256 taken from the step output, not computed
    again. After the jars, the zip is handed to the tag job as the one-day artifact `niord-fe-src` (compression 0).
    The tag job's _Tag_ step is unchanged in what the gate needs (the tag lightweight, its push); it gains only the
    sha256 in its env, so that its by-hand summary also gives the release commands (`gh run download`,
    `sha256sum`, `gh release create --verify-tag --notes '<sha>  niord-fe-src-<v>.zip'`). Then _Fetch the source
    zip_, _Release v…_ (the zip's sha256 checked again against the build job's, `notes.md` written, `gh release
    create "v$VERSION" <zip> --verify-tag --title "v$VERSION" --notes-file notes.md`, by-hand commands in the
    summary if it fails) and _Delete niord-fe-src_ (continue-on-error and `set -uo pipefail`, as the existing
    artifact deletion; only after the release exists). Every other new run block has `set -euo pipefail`.
  - **`retract-version.yml`.** Its header, input and permissions comment name the release; the package loop
    gains `org.niord.web.niord-fe-src` (a missing one reported "not present", as the loop does); a new step
    _GitHub release_, before the checkout and the _Tag_ step, validates the version again and calls `gh api
    repos/$REPO/releases/tags/v$VERSION`: found, the release (and with it its asset) is deleted by id; HTTP 404,
    "not present"; any other failure, `::error` and exit 1. `contents: write` was already there.
  - **Frontend CI.** The `Example country` job gains the step `previous` before the harness (§4.8's upgrade
    test): the latest non-draft, non-prerelease release among the newest 20; none, the summary line
    `Upgrade test: skipped — no release of this repository carries a niord-fe-src yet (the first is PR A's
    publish, docs/fe-country-split-plan.md)` and an empty `zip=`; otherwise the tag must match
    `^v4\.0\.[0-9]+$`, the asset is downloaded with `gh release download`, the notes (CRs stripped) must hold
    exactly one line `^[0-9a-f]{64}  niord-fe-src-<v, dots escaped>\.zip$`, and its hash must be the asset's.
    The harness step passes `${UPGRADE_FROM:+--upgrade-from "$UPGRADE_FROM"}`. Permissions stay `contents:
    read`; the gate, `Detect`, `EXAMPLE_RULE` and the 30-minute timeout are unchanged. A latest release whose tag
    is not `v4.0.<n>` fails the job.
  - **`verify-local`** gains `--upgrade-from <zip>` (or `=`): exit 2 when the value is missing, holds a space or
    is not a file; the path is made absolute and passed through `native_path`; it turns the example row on
    whatever the branch changed (not with `--be-only`), and the row's label gains ", upgrade test". The header
    names `gh release download` for a release's zip and says that without the option the test is Frontend CI's.
    The root `README.md`'s publish row names `niord-image-tools` (missing before) and `niord-fe-src` and the
    release; its retract row "GitHub Packages, the release, Harbor and the tags".
  - **The kit.** `country-kit/country-words.json` (format 1, strict, 10 words, the allow-list of §4.11 rule 6)
    and `country-kit/lib/country-words.mjs` (Node built-ins only, the list read lazily, never at import:
    `lib/local-sources.mjs` imports `source-zip.mjs` in a country workspace, whose zip does not carry the list).
    `source-zip.mjs` gained `guardSourceZip`, `checkedSourceZip`, `writeSourceZip` and the command `guard
    <file>`; `build` verifies and guards before it writes and writes nothing on a finding; build, verify and guard
    exit 1 on a finding, one line per problem, and 2 when they cannot run (build's own verify failure exited 2
    before). `zip-include.json` excludes `country-kit/country-words.json`. The harness's step 1 is
    `writeSourceZip`, the same code as `kit:zip` and `publish.yml`'s `build`; its skip line reads `example:
    upgrade test: skipped — no previous niord-fe-src was given (--upgrade-from; Frontend CI takes the latest
    release's); the mechanism ran on the fixture pair`, the prefix `verify-local` repeats kept. No kit fix came
    out of the dry run: the release-shaped `4.0.90000` to the harness's `4.0.0-example` is not treated as a
    downgrade anywhere. `fe/country-kit/README.md` (the file table, the guard, a _Publishing_ section, the first
    pin against the release notes, the upgrade test from the latest release), `fe/CLAUDE.md` (the `kit:zip`,
    `run.mjs` and `test:guards` lines, a rule on country words) and `fe/README.md` (the `kit:zip` row, the
    upgrade test, the publishing paragraph, which was stale and now names the administration bundles and the zip)
    followed. `pack prune --base` was not built (§4.5).
  - **`be/docs/cicd/`** (text only): `design.md` §3 — the source zip and the Helm chart are stamped with the
    version; the GitHub Packages row names `org.niord.web:niord-fe-src` in place of the npm package; two new rows,
    the release on `v4.0.<run>` (the sha256 record and a copy of the zip, read by a first pin and Frontend CI's
    upgrade test) and the packs `niord-lang-<lang>` on public GHCR (published by the home's `pack.yml`, pinned by
    digest, read anonymously); `README.md`'s CI row; `implementation-plan.md`'s Phase 8 npm item checked as the
    source zip.
  - **Negative tests** under `test:guards` (20 new; 744 → 764): `country-words.test.mjs` (15: a planted word in
    an entry's text and in its name with file and line; case and NFC, with `å` as the decomposable letter, since
    `ø` and `æ` have no canonical decomposition; an allowed entry passing and the same word in another path
    failing; an unused entry; the validator refusing an unknown field, a word not in `words`, an empty reason, a
    bad glob; the real tree — every file the include list selects, the catalog left out for speed — passing with
    no entry unused) and `source-zip.test.mjs` (+5: a fixture build with a planted word failing and writing
    nothing; a clean `guardSourceZip`; the `guard` command's exit 1 and 0; `verify` word-blind). `run.test.mjs`
    gained none: step 1 is exactly `writeSourceZip`, whose refusal `source-zip.test.mjs` tests.
  - **The dry run** (scratch root `C:/Users/<user>/IdeaProjects/niord-ng-wt/.p4-dryrun`, version `4.0.90000`,
    release-shaped and above every real version, deleted afterwards with its image tags).
    - **The zip.** `publish.yml`'s three commands from `fe/`: 3.3 s, 1,769 entries, 3,906,900 bytes (11,779,868
      unpacked), sha256 `27520dca8d51a4bb1da585cb28973b0dff82a315b9dd673e33151a887a7a12f9`; a second build was
      byte-identical (the determinism of P3a, proven once).
    - **The scratch repository.** `niord-parent` `4.0.90000` installed as the harness does (18 s); the zip
      deployed with `publish.yml`'s `deploy-file` command to `file:///…/m2` with `-DrepositoryId=scratch` (17 s),
      never a remote. Set-up's `dependency:copy` found the deploy-file layout through `-Dmaven.repo.local` alone;
      no `settings.xml` was needed.
    - **DK and GL laid out as P5 and P6 will move them:** a minimal `be/pom.xml` (parent `niord-parent`, the base
      product's wrapper); `fe/niord-country.json` (DK: both applications `da`, `en`, default `da`, `da` a home pack
      published; GL: §4.1's example, `da` pinned, `kl` a home pack not published); `admin/app/` (the providers and
      `promulgation/`), DK's `admin/environments/`, `admin/assets/{branding,legal}/`, `info/app/` (`info.config.ts`,
      `index.html`, `footer/`), `info/assets/`, `styles/tokens.<cc>.css`, the pack (`packs/da` for DK, `packs/kl`
      for GL) with its backend half from the country repository's `main` (read only), and `e2e/` with the specs P5
      and P6 move. Each fetched the zip by `dependency:copy` (15–16 s), compared its sha256 with the record (the
      stand-in for the release notes), unpacked it with the kit's reader and ran `init <cc> --zip-sha256 <hash>`
      after the layout: init kept every moved file and wrote only 11 — `fe/setup.mjs`, `fe/.gitignore`,
      `fe/niord-pins.lock.json`, `.github/workflows/{pack,image}.yml`, `.github/{dependabot.yml,renovate.json,CODEOWNERS}`,
      `CLAUDE.md`, `be/bootstrap/README.md` and `be/bootstrap/releases/README.md`.
    - **Set-up, install, build.** DK: set-up 10 s with the fetch, then 1 s, after a two-key escape hatch and one key
      dropped from the scratch pack (its backend half, _Found on the way_); `npm ci` 44 s; `npm run build` 54 s.
      GL: set-up 11 s, then 1 s, with a 166-entry escape hatch (`da` 2, `kl` 164, the backend halves' gaps) and
      `NIORD_PACK_DA` naming DK's scratch pack; `npm ci` 52 s; `guard-build` refused the production build staged
      from a local pack, as it must, and the generated build script's steps after it ran by hand in 59 s (a P5
      dependency, not a kit fix). In both, check-build's one finding is the absolute-URL rule on DK's own three
      addresses in the public site (_Found on the way_). No Danish or Kalaallisut text was written.
    - **Bundle baselines** by init's recipe, first-visit transfer against the base product's 118,180 B: DK 14
      files, 119,398 B (+1,218); GL 14 files, 119,343 B (+1,163). With Lato (P6b), GL's baseline is 22 files,
      235,776 B by the report's rule, which counts all eight font files; a real first visit fetches two (46,620
      B), and without fonts it is 14 files, 119,823 B.
    - **Specs, lint, e2e.** `npm test`: DK 2 + 1 spec files, 6 + 6 tests; GL 2 + 1, 6 + 8. `npm run lint` green
      in both. `npm run e2e -- --list`: DK 2 tests in 1 file, GL 10 in 2 — after the move steps of P5 and P6
      below, made in the scratch copies.
    - **The four comparisons** with the in-repository `build:dk`, `build:info:dk`, `build:gl` and
      `build:info:gl` (hash-stripped file lists, sizes, bundles, flags, audio, legal dialog). The shipped language
      bundles, the flags, the branding and the legal files are byte-equal in all four pairs. The other
      differences:

      | Pair | Files (in-repo → dry run) | Bytes (in-repo → dry run) | Why |
      |---|---|---|---|
      | DK administration | 220 → 221 | 5,110,600 → 5,111,921 (+1,321) | `niord-build-report.json` (1,170 B), written by a country build only; chunks +151 B |
      | DK public site | 50 → 50 | 992,085 → 992,059 | main −28 B, chunks +2 B |
      | GL administration | 221 → 222 | 5,111,734 → 5,154,505 (+42,771) | the build report (42,814 B, carrying the 166 escape-hatch entries); chunks −43 B |
      | GL public site | 55 → 55 | 1,001,703 → 1,001,677 | main −28 B, chunks +2 B |

      The chunk and main differences are minified identifier names and the order of export aliases, which follow
      the module paths, other in a country workspace: in DK's administration bundle 111 of 195 JavaScript files
      are equal and 28 more equal but for identifier names. The generated administration language configuration
      also carries `siteLanguages` and `modelLanguages` (two more `da` and two more `en` literals). The audio
      components (`app-audio-view`, `app-audio-edit`) are in `main` in both builds of DK and of GL; the
      administration app's legal-document dialog is in `main` in both builds, the public site's legal dialog lazy
      in both.
    - **The harness** with `--image --upgrade-from` the dry run's `4.0.90000` zip: green in 720 s (P3c2, without
      `--upgrade-from`: 585 s), `upgrade test: 4.0.90000 to 4.0.0-example set up and built green`, its image tags
      removed.
  - **The publish step without publishing.** `publish.yml`'s zip step's run block, taken out and run from `fe/`
    with `VERSION=4.0.0-stepcheck` and scratch `RUNNER_TEMP`, `GITHUB_OUTPUT` and `GITHUB_STEP_SUMMARY`, 8 of 8
    cases: the real worktree passes in about 4 s (3,905,782 bytes, sha256
    `724dbb049495b0a6183f82cacb51eb1c7ea7e0c792f33344c10a5f065d10bff1` — another version stamped and a later
    tree than the dry run's; the guard reports 10 words and 25 entries); a scratch git repository holding the
    4,197 files git lists under `fe/` and `be/` gives the same sha256; there, a planted `ui-apps/admin/src/app/planted-country-word.ts` fails `build`
    with exit 1 naming file, line 2 and the word, writing nothing, and passes once deleted; the clean zip with an
    added `docs/planted.md` fails `verify` ("outside the include list"); a zip with a word in a `.ts` entry fails
    `guard` and passes `verify`; `guard` on the clean zip passes. The planted cases ran in the copy, not the
    worktree, because the kit's dry run and `test:guards` were building zips from the worktree at the time. The
    kit's own step-equivalent run in the worktree gave the same: the real tree passes, a planted word fails
    `build` with nothing written and passes once removed, `docs/planted.md` fails `verify`, an unused allow-list
    entry fails `guard`. The release-notes writer and Frontend CI's parser against each other with a fake `gh`,
    by hand then (since the review of PR #184 a committed test, `fe/scripts/release-workflows.test.mjs`, runs both
    steps' own bash), 13 of 13: the writer refuses a sha256 other than the build job's and an empty one; the round trip passes, with
    CRLF notes too; an empty release list gives the skip line and `zip=`; a mismatched hash, a missing line, two
    lines, an unescaped-dot look-alike, another version's line, a missing asset and a latest tag `v5.1.0` are
    refused; a failing `gh release create` exits 1 with the by-hand commands in the summary. (The review of PR
    #184 reported the dot escape of the parser's pattern as dead on bash 5.2 and later; run from a script file,
    Git Bash 5.3.15 expands the old `${version//./\\.}` to `4\.0\.12`, and the look-alike is refused on the old
    line too, there and under bash 5.2.15 on Linux (the `node:24` image, 2026-10-22), so this record stands. The pattern is `${version//./[.]}` since, which needs no backslash in any
    bash.) Retract's release step
    with a fake `gh`: present, deleted; absent, "not present"; HTTP 502, `::error` and exit 1; a bad version, exit
    1. Read-only `gh api` calls: no release in `SIFA-DK/niord-ng` today, so PR A's own run skips the upgrade test;
    a 404 reads `gh: Not Found (HTTP 404)`, which the retract step matches.
  - **Verification.** The five kit test files P4 changed or exercises, 79 tests of 79 (`country-words.test.mjs`
    15, `source-zip.test.mjs` 12, `zip.test.mjs` 18, `example/acceptance.test.mjs` 22, `example/run.test.mjs` 12); `test:guards` whole 764 tests, 761 passed and 3 skipped (the
    symbolic-link cases), 74 s; `languages:check` 0 errors, 0 warnings, 0 stripped; `contents:check` 17
    configurations, 338 files, unchanged. The six builds, each with `urls:check`, `separation:info` after each
    public-site build and `bundle:info` after `build:info`, green (35, 26, 25, 9, 10 and 8 s), their output tables
    37, 37, 37, 18, 18 and 18 rows as in P3c2: no Angular build input changed. The three workflows parse
    (js-yaml) with the steps in the intended order; `bash -n` on `verify-local` and on every run block;
    `verify-local --help` and its three refusals of `--upgrade-from`; `git diff --check`; `docs:check` 29 files,
    no errors; Prettier and ESLint clean on the changed files. `verify-local` passed at `c4c14cd8` in 26 min 50 s,
    every row (the backend's 10 known Windows-only failures aside; the _example country_ row 9 min 35 s, its upgrade
    test skipped without `--upgrade-from`). Not yet run: on PR A Frontend CI (its `Example country` job timed in
    CI, the upgrade test skipped) and the first publish.
  - **Changed from the plan, and corrected above.** §3.1 _Reading_: the zip is also a release asset, readable
    with `contents: read`. §4.5: `pack prune --base` was not built in P4. §4.8: the upgrade test takes the latest
    release's zip, verified against its notes; the guard's list is `country-kit/country-words.json`, with the two
    authorities' English names added and language names not words. §4.11 rule 2: the notes' form, the asset,
    the tag job's token, the by-hand path; rule 6: the guard as built and its measured allow-list. §5 step 2: the
    first pin compared with the release notes' line. §7, §8. P3c2's record carries two pointers.
  - **The browser network-log check of P1 and P3a**, taken on 2026-10-22 on the local stack: the generic backend on an
    empty scratch database, never a shared environment.
    - The base product's public site loads one UI bundle, `assets/i18n/en.json`, and no branding text. Its one
      request under `assets/branding/` is the favicon `index.html` links, the base product's own icon.
    - The base product's administration app loads one bundle, `en.json`, and no branding.
    - DK's administration variant (`npm run start:local:dk`, `siteLanguages` set to `en` and `da` on the scratch
      database):
      - English loads `en.json` and DK's `branding/en.json`;
      - switching to Danish loads only `da.json` and `branding/da.json`;
      - a reload in Danish loads `da.json` and `branding/da.json` (first recorded as `da.json` alone, which the
        loader cannot do for a language `brandingLanguages` names; corrected after the review of PR #184 from the
        re-run below).

      `en` is fetched beside no other bundle, as P3a's staging intends.
    - Seen on the way: the Danish screen kept English texts on the generic backend. Its `web` dictionary holds only
      English rows, and the administration app's dictionary merge chooses them for Danish when no Danish row
      exists (the requested language, else the default language, else any). C2b of
      `docs/code-data-config-plan.md` keeps that choice; an unchanged system-added row (`asAdded`) gives no
      override, so these rows no longer mask the Danish text, while an English-only row an operator edited still
      replaces it, by design (reworded after the review of PR #184, which found the first wording credited C2b
      with removing the fallback).
    - **Re-run on the merged tree** (2026-10-22, #179 merged in, the same set-up on a new scratch database
      bootstrapped by `start-local.sh`): DK's variant loads `en.json` and `branding/en.json` in English, and
      `da.json` and `branding/da.json` on the switch to Danish and on a reload; the Danish screen shows Danish;
      the base product's public site loads one bundle and no branding text, its administration app one bundle and
      no branding.
  - **Found on the way.**
    - **The absolute-URL rule fails DK's and GL's public sites as country builds** on DK's own three addresses
      (the authority's site twice in the footer, the accessibility statement on `was.digst.dk` in `info.config.ts`):
      the in-repository builds never run `--absolute` as a gate (§4.11 rule 5). Adding them to
      `scripts/build-urls-allowed.json`, as that file says a country's own URL is added, would put a country word
      into a file the zip carries, which the guard refuses without an allow-list entry, against R1. P5 chooses: the
      base product's allow-list with a guard entry until P7, the links made branding data (which check-build does
      not scan), or a country allow-list — possibly the owner's call. **Settled in P5:** a country allow-list,
      `fe/build-urls-allowed.json`, which DK's move chose and P5k built (§4.11 rule 5).
    - DK's `da` backend half (`niord-dk` `main`) lacks `mail.safetynet.cancel`, and `navwarn.date.html_day_suffix`
      is `{{suffix}}` where English has `<sup>{{suffix}}</sup>`: the markup row fails and cannot be escape-hatched
      (Danish has no superscript ordinal; P5 decides the value). GL's `kl` half lacks 163 catalog keys (mail 1,
      message 3, pdf 4, web 155) and has the same markup key; six GL-only keys (four `mail.*_gl`,
      `mail.no_active_nav_warnings`, `pdf.attachment.title`) are stripped as not in the catalog — country texts
      under `docs/code-data-config-plan.md`. The dry run settled them in scratch only.
    - The pack check's messages name the base product's layout, `languages/<lang>/…`, in a country workspace
      too, where the pack is `packs/<lang>/`: for P5's kit work or the backlog (done by the review of PR #184:
      each line names the folder it read).
    - Whether GitHub refuses the workflow token's `gh release create` on an existing tag whose commit carries
      workflow files that differ from `main` (the 4.0.13 case of the tag push) is unprobed; creating a release
      writes no ref. If it is refused, _Release_ fails red after everything else is published, with the by-hand
      commands in the summary.
    - `be/docs/cicd/README.md`'s status line still names only the library, the bundles and the image tools; it
      follows PR A's first publish.

- **Review of PR #184** (2026-10-22; #186 into `feature/fe-country-split`, from `split/review-184-fixes`).
  Three reviews of PR A at `82707d9c`: the first with two [P2] findings, its merge
  conditions; the second with 70 inline comments (4 major) and 15 items in its body, verdict "nothing blocks the
  merge"; the third confirming the first [P2]. Every item was checked against the tree and this plan, and each
  thread has its answer. What changed:
  - **The release workflows.** `publish.yml` starts after Info CI too, and its gate checks `info-ci.yml` beside
    `be-ci.yml` and `fe-ci.yml`, the path-filtered case unchanged (§4.11 rule 1). `fe-ci.yml`'s notes parser
    matches the version as `${version//./[.]}`. `retract-version.yml` reads every page of a package's versions,
    reports a package not present only on HTTP 404 and fails on any other error; its header and summary say that a
    country pinned to a retracted version keeps building and must bump. `.github/maintainers` says who the list
    binds until review is required. `fe/scripts/workflow-step.mjs` and `fe/scripts/release-workflows.test.mjs`
    (in `test:guards`) run the workflows' own bash blocks with fake commands first on `PATH`: the gate (Info CI
    green, failed, running and absent, the ancestor lookup, each environment's rule, the maintainer and the ref),
    the release step and the parser against each other with their refusals and a look-alike name, and retract's
    authorisation and package pages; the helper removes its run folder when the step is done, failed or not,
    and keeps it only when asked (`keep`, or `NIORD_KEEP_STEP_DIR`). A change to `publish.yml` or
    `retract-version.yml` runs that test (Frontend CI's push paths and `Detect`, `verify-local`'s `ADMIN_RULE`).
  - **The country templates.** `image.yml`'s workflow-level `REGISTRY` and `HARBOR_PROJECT` (empty, so both jobs
    fail closed until set) and its push job's checks (§4.11 rule 1, §5 step 10); `GITHUB_TOKEN` on the two set-up
    steps only; `pack.yml` green on `main` and publishing nothing in this kit version (§4.5); the placeholder,
    `dependabot.yml`'s country entry, the release-folder name and the stub's header comment (the minimal reader,
    the stub-change policy, the residual trust of rule 2) documented. `stub.test.mjs` gained 13 refusal cases;
    the stub's reader refused all of them as it was.
  - **The kit.** `pack.mjs` in a country workspace and the one-list status rule, the 58 seeded fingerprints of
    unaccepted keys dropped (§4.5, §4.6); staging matches a pack's keys by flat name, `migrate` writes English's
    shape, and a key given in both shapes (`"menu.close"` beside `menu: { close }`) is refused by staging and is
    an error of the pack check; `lib/zip.mjs`'s header says it is the full reader beside the stub's minimal one;
    the upgrade test's previous backend half from the previous catalog (P3c2's record, _Found on the
    way_); a stub change reported in the upgrade report's To do and the `API-CHANGES.md` actions moved to To look
    at (§4.12); a SECURITY stop only for a fix newer than the last set-up record's version; new set-up refusals:
    `production: true` in the production environment, orphans under `admin/environments/`, links in the code
    folders, a `packs.<lang>` for a language no application names, the footer branding's `imageSrc`/`imageAlt`
    and `url`/`ariaLabel` pairs and a footer label without its document or dialog title; the wider protected
    list (rule 3); the layout check's fixture answers any root area (§4.8); `build-urls-check` reads inline
    `<style>` blocks; the README corrected on every point raised.
  - **The applications.** A country route on a base product top-level path or `dev` is left out and logged; the
    public site's code-only language marker is `aria-hidden`; the legal-dialog spec reads whichever overlays
    exist and always checks an inline fixture; `csp.spec.ts`'s mail preview is a test of its own that skips by
    name; comments reworded.
  - **The backend**, wording and one warning: the CSP's and the frame rule's comments say what the code does; a
    tile range outside the first host label gets a warning naming that cause, and the two tile settings'
    descriptions state the rule (the settings catalog regenerated; a deployment's stored description is not
    changed, R-CDC1); `be/docs/cicd/`'s rows.
  - **Recorded, not built here:** the footer's English dictionary rows of DK and GL (`docs/code-data-config-plan.md`
    C6); the build report's backend half (rule 2, with C6); the `/media/**` service-worker group (§4.7, a base
    product step, not made before P6's font, which GL vendored in P6b; built 2026-10-25, B4); required review on `.github/`, the Maven wrapper's checksum and a lint block
    for a country's `e2e/` (`fe/docs/backlog.md`, go-live); DK's production environment and the footer
    legal-document tests (P5).
  - **Choices taken for the owner**, who asked for the recommended option on each and reviews them on his
    return: Info CI in the trigger and the gate (a merge confined to the public site publishes); the workflows
    tested by running their own bash, the parser kept in bash; the push host from the workflow's env with the
    whole reference checked; the token on the set-up steps only, `GITHUB_ACTOR` unbound; `pack.yml` green with a
    notice, publishing by dispatch only; the placeholder kept failing; the previous backend half from the catalog
    rather than a zip contract change; the layout fixture synthesised for any root area; a stub change reported
    rather than refused; SECURITY by version, no new manifest field; flat-name staging rather than a new error;
    the seeded fingerprints dropped; the branding pairs checked; `aria-hidden` rather than a label; base-path
    country routes left out rather than the comment narrowed; three files more on the protected list; the
    legal-dialog spec made overlay-independent rather than the dialog protected; the publish secrets not
    pre-checked (the failure modes stated instead); the CSP comment reworded rather than the guard extended to
    filters; a warning for an out-of-label tile range rather than expanding it; retract's paging fixed although it
    predates PR A; the wrapper checksum and the footer rows recorded for later.
  - **Changed from the plan, and corrected above.** §4.5 (the fingerprint seeds, `pack.yml` as built, the tools in
    a country workspace), §4.6 (the backend texts' location, the status row), §4.7 (the service worker), §4.8
    (`init`'s `env:`, the two scripts' fixtures), §4.9 (override files), §4.11 rules 1–3, §4.12 (the stub row,
    To do and To look at), §5 step 10, P1 (the stub's production guard, the gates' first live run), P4 (the push
    job's login), P3c2's and P4's records (the upgrade test, the fake `gh` run, the test count, the network-log
    check), P5, P6 and P7.
  - **Verification**, as the runs reported (all 2026-10-22): `release-workflows.test.mjs` 26 of 26 (on the
    unchanged workflows 7 failed as expected: the trigger, the four Info CI cases and two retract cases), then 29
    of 29 with the run-folder cases (two of them failing against the helper without its clean-up);
    `init.test.mjs` 17 of 17 (9 failed on the old templates); `stub.test.mjs` 34 of 34;
    `info-layout-check.test.mjs` 6 of 6 and `build-urls-check.test.mjs` 13 of 13 (the inline-style case failing
    on the old script); every `country-kit/**/*.test.mjs` (32 files) 599 tests, 596 passed and 3 skipped, each new
    case first seen failing on the old code but the stub-change bump case and the info `aria-hidden` assertions
    (read, not run, against the old code); `build:info:dk` with its URL check and the layout check's 47 checks;
    `build:dk` and `build:gl` with their pack checks, and the pack check whole, 0 errors after the seeds were
    dropped and with the two-shape refusal; the changed admin specs (3 files, 15 tests) and info specs (3 files,
    36 tests); `lint`, `lint:info`, `docs:check` and Prettier clean; `test:guards` whole 838 tests, 835 passed
    and 3 skipped; the backend's `WebAppContentSecurityPolicyTest` (the new range case red first),
    `SecurityConfigFrameOptionsTest`, `SettingsCatalogCurrentTest`, `MapTileSettingsTest` and the settings
    checks, 50 tests green. The Example country harness with the new kit (`node country-kit/example/run.mjs`,
    without `--image`): green in 6 min 8 s, the `xx` run with the country workspace's `pack.mjs prune zz` step,
    the untouched `init` country `yy` and the fixture upgrade 4.0.0 to 4.0.1-example, the upgrade test skipped
    for want of `--upgrade-from`. Then the upgrade test itself, with `--upgrade-from` a zip `4.0.0-prev` built
    from the tree before these fixes (`82707d9c`, sha256 `945e1048…`): green in 692 s. The previous example set
    up at its own version with a backend half made from the previous catalog; the bump refused the old stub as a
    check error and wrote the upgrade report; the routine action copied the new stub, and set-up, `npm ci` and
    the build were green (`upgrade test: 4.0.0-prev to 4.0.0-example set up and built green`). No key needed the
    escape hatch in that run, so the hatch with entries rests on `acceptance.test.mjs` and
    `pseudo-localise.test.mjs`. The checks jobs on Linux (`node:24`: bash 5.2.15, Node 24.22), on the committed
    tree: every step green, `test:guards` 838 of 838 with none skipped, after the one fix that run found (the
    Info CI trigger test wanted LF after `workflow_run:`, which a Windows checkout with `core.autocrlf` does not
    have; the workflow tests pass with the workflows in CRLF too); against the old workflows, with `jq` present
    as on GitHub's runners, 8 of 29 fail (the trigger, the four Info CI gate cases, and retract's paging,
    registry-error and not-present cases). `./verify-local` against the feature branch: PASS in all four parts.
    **The policy smoke run on the merged tree** (`csp.spec.ts auth-smoke.spec.ts` with `E2E_CSP_LOGIN=1`), on a
    new scratch database with the base product's bootstrap and the demo archive, served by the backend on slot
    6: 8 of 8, none skipped (the landing page and a deep link with tiles, a real Keycloak login, the sysadmin
    walk, a queued mail's preview frame, the editor, the guard's refused image). Two things stood in the way,
    now in `fe/docs/testing.md`: `start-local.sh` without `--info` sets `adminAppUrl` to its dev server, so the
    backend's root redirected there; and the demo archive's domains (`niord-client-*`) are not clients of the
    local realm (`niord-nw`, …), so the sysadmin held no role in them until they were renamed in that scratch
    database (`docs/code-data-config-plan.md` §8). A copy of `niord_test` is refused by the generic backend, by
    design: its default language is Danish, for which the base product has no texts. Not yet run: CI on PR A.

**P5 — DK moves (`niord-dk`).** A frontend part (after P4) and a backend part (after
`docs/code-data-config-plan.md` C2–C5b are published); the frontend part may go first.
- `git filter-repo` of DK's frontend paths with their history into `fe/`: both `country-specific/dk/`
  folders, `fe/ui-apps/info/public/assets/dk/`, DK's administration overlay `fe/ui-apps/admin/public/assets/dk/`
  (its branding and legal documents, placed there in P1), its entries of `fe/ui-apps/info/i18n-status/branding-unaccepted.json`
  (branding values and legal documents awaiting acceptance, §4.5; into `fe/info/branding-unaccepted.json`, its `da`
  list only; the base product's file kept GL's until P7 deleted it, P7f, #211), the e2e spec `language-switch.spec.ts` (it switches to
  Danish) and `app-breadth.spec.ts`, which keeps its path and history and is trimmed to its two footer
  legal-document tests, into `fe/e2e/`, `fe/styles/tokens.dk.css` (`tokens.active.dk.css` does not move: a country
  workspace generates it), before 2026-09-22 `fe/src/app/country-specific/dk/`; the `da` pack
  with the history of `fe/languages/da/` and its earlier `public/assets/i18n/da.json` paths; DK's five `be/src/main/resources/*_da.properties` copied into its `backend/`, so the first `niord-lang-da` carries its backend half (owner, 2026-10-21); the move deleted the root-level files, and DK's pom copies `fe/packs/da/backend/` in place until it reads the staged path, which comes with the kit version that adds a backend job to the templates, not with the bump to P5k's version (`docs/code-data-config-plan.md` §4.3). The public
  site's history before #39 is in the archived `niord-info` repository and does not move. Nor does the earlier
  history of DK's administration environments and logos: before niord-ng `40d55c94` they were base product files
  still alive at the tip, and GL's byte-identical logos; they bring their history from `40d55c94` on.
- **Translations in base product PRs from here** (for `kl` from P6): a PR adds English only; DK translates in
  its bump PR, or ahead of it in `niord-dk` (§4.6, §4.12). The base product's frozen `da` copy gains no keys, so
  until P7 deleted them (P7f, #211) its own `dk`/`gl` builds staged the missing keys from `en` and ran the pack
  check as a warning. `fe/ui-apps/admin/CLAUDE.md`, `fe/docs/definition-of-done.md` and the `niord-review` skill change
  with the frontend part (§7).
- The manifest, `setup.mjs`, `pack.yml` (the first publish of `niord-lang-da` to GHCR is the merge of DK's bump
  to P5k's version into its `main`, a push that changes `fe/packs/da/`; the owner checks the organisation's
  package setting before that merge — that SIFA-DK allows public packages and lets a workflow token create one,
  §9 — and makes the package public after it; if the merge's publish fails on the setting, the owner fixes it and
  DK dispatches `publish`), the
  frontend pipeline split as §4.11 requires — an unprivileged **build job** (set up — before the backend's Maven
  build once that reads the staged packs' backend half, with the kit version that adds a backend job to the
  templates; until then the pom copies `fe/packs/da/backend/` in place, `docs/code-data-config-plan.md` §4.3 —
  test, lint, build the
  bundle into `WEB_DIR` instead of unpacking `niord-web-admin:…:dk`, build the info image, run its container
  test) and a **push job** that only verifies and pushes — `renovate.json` for the pins as init writes it (never automerged), inert until Renovate is installed, which is deferred to the country split (owner, 2026-10-22, §9; its cadence and the re-pin workflow, the owner's rulings of 2026-10-25, come with the install; since the Renovate step (2026-11-02) the template carries the weekly window, `updateNotScheduled: false`, `rebaseWhen: conflicted`, the Dashboard without approval, `gitIgnoredAuthors`, `prBodyNotes` and `hostRules`, and `templates/base-bump.yml` is the re-pin workflow; DK and GL take both in their single bump (M2); the install is tracker F1),
  `dependabot.yml` (security updates only, no `maven` entry), `.github/CODEOWNERS` (a gate only with the branch rule *Require review from Code Owners*, unavailable on a private repository under GitHub Free: until the repositories are public it documents, and the job summary of §4.11 rule 6 is the gate).
- **Backend part** (`docs/code-data-config-plan.md` C6 for DK, on a base product version with its C5b, which is
  built): the backend security group (§4.11) inherited
  through `niord-parent`, the protected beans and properties asserted at start-up, and `niord.json` replaced by
  the country settings file with its checks (its `wmsPassword` placeholder emptied). The override check is a
  step C6 adds to DK's `image.yml` and `ci.yml`, after the package step.
- The backend's resources as `docs/code-data-config-plan.md` §9.1 classifies them: the 42 template
  shadows and 14 own templates to `be/bootstrap/`, `web_en`/`mail_en` to country texts (`web_en`'s admin-only rewordings to branding and a text override, that plan's §4.4), the `*_da` bundles
  replaced by the `da` pack's backend half in the frontend part (DK's move, `niord-dk` #5: the root-level copies deleted, the pom copying `fe/packs/da/backend/` in place; the Maven build switches to the staged pack with the kit version that adds a backend job to the templates, `docs/code-data-config-plan.md` §4.3); the base product publishes the override
  check in `niord-image-tools` and writes `be/docs/country-api.md` (both in that plan's C5b, before this part); `niord-dk` records its one remaining guarded
  override (an image, `META-INF/resources/img/company-logo.png`) in `be/overrides.json`; the footer's two English
  dictionary rows of DK's deployments, which mask the branding's footer, go as that plan's C6 says (the bump PR
  asks each deployment's sysadmin to delete them; nothing changes a deployment's rows by itself).
- **Backend part: built 2026-10-22** on 4.0.76 in `niord-dk` (#2, `b5d8b31`, on `feature/p5-dk-moves`);
  C6's State for DK (`docs/code-data-config-plan.md` §11) holds the record. The `*_da` bundles were not yet
  replaced by the `da` pack's backend half then; DK's frontend move (#5) has since deleted them, its pom copying
  the pack's half in place (`docs/code-data-config-plan.md` §4.3).
  DK's bootstrap domains: the owner ruled on 2026-10-22 that DK's bootstrap carries the IDs DK's deployments and
  the local realm use, `niord-nw`, `niord-nm`, `niord-fa`, `niord-almanac` and `niord-annex`, not the old dev
  data's `niord-client-*` that #2 carried (P5d renamed them in `niord-dk` #3, `1d29b97`, merged 2026-10-22;
  the P9 sample uses the same IDs; that plan's §8). DK's S-124 identity: by the owner's ruling of 2026-10-22
  DK publishes under the producer code `DK01` (was `DK00`) and its English `msg.responsible.agency` names the
  Danish Emergency Management Agency (`niord-dk` #4, `68b95cf`); the Danish text follows with P5b's pack.
- **What remains for the frontend part (P5b)**, from the backend part's record: `web_en`'s seven admin-only
  rewordings, deleted from the backend (`footer.copyright` and `footer.link` are DK's branding already;
  `msg.no_pos_messages` becomes a text override; `view_mode.table`, `promulgation.confirm.generate`,
  `promulgation.confirm.generate_all` and `template.markings.bearing` go); the five `*_da` bundles into the
  `da` pack's backend half with the Maven staging of `defaults/packs/`, each compared with the pack first and
  then deleted at the root (done by the move, #5, as an in-place copy of `fe/packs/da/backend/`; the staged
  path later, `docs/code-data-config-plan.md` §4.3); `DkWebBundlePublicationTypeTest`'s and `DkNavWarnDateFormatterTest`'s Danish side
  then read the pack, and `defaults/country/README.md` changes with them; `img/flags/da.png` later from the
  pack; `fe/README.md` (unchanged on 2026-10-22: the image takes `niord-web-admin:<base>:zip:dk` until P5b
  builds the bundle in `niord-dk`) rewritten. Done in P5b (below): the seven rewordings as listed
  (`msg.no_pos_messages` as DK's English text override), the `*_da` into the pack, the two tests and the README
  following it, the flag from the pack, `fe/README.md` rewritten.
- **P5b, the frontend part, as built** (2026-10-22; `niord-dk` #5 into `feature/p5-dk-moves`, merged with a
  merge commit as `bc9353a`; base 4.0.81, niord-ng `b74b3bfd`, to which DK's backend moved in the same PR).
  - **The move.** `git filter-repo` in Docker over a bundle of niord-ng's `origin/main` at `b74b3bfd`: 16 paths,
    13 renames, `(#N)` rewritten to `(SIFA-DK/niord-ng#N)`; 144 commits, DK's 47 files at the tip, each equal
    to its original. The secret scan (the kit's patterns; gitleaks as a second opinion) found nothing. One pure
    merge of the unrelated history, with `fe/.gitattributes` written first so the files check out LF; `git log
    --follow` reaches each file's niord-ng history (the administration `da.json` 123 commits).
  - **The choices.** Merged with a merge commit, never squashed, here and in DK's PR into `main`, or the moved
    history is lost. The `da` pack is DK's five root `*_da` made neutral: DK's own wording of the four keys its
    `texts/en/` words went to `defaults/country/texts/da/`, `footer.copyright` and `footer.link` are the base
    product's values (DK's branding carries DK's), `mail.safetynet.cancel` was added and
    `navwarn.date.html_day_suffix` passes the markup row (`docs/code-data-config-plan.md` §9.1); the root files
    and the committed `da.png` were deleted for the pack's. DK's pom copies `fe/packs/da/backend/` and the pack's
    flag in place, since kit 4.0.81 stages no home pack. DK's three public-site addresses went into a country
    URL list, `fe/build-urls-allowed.json`, which kit 4.0.81 did not read, so DK's build was red on them until
    P5e. `image.yml` in three jobs: an unprivileged `frontend` and `backend`, and a `push` job (`main` only,
    `harbor`, `contents: read`; no checkout, npm, Maven or kit code) that verifies the hashes and pushes;
    `ci.yml` gained a `frontend` job.
  - **Verification**, locally: the whole backend suite green (DK's Surefire 28, the group's 114 with 1 skipped,
    Failsafe 14 + 14) and the override check exit 0; `npm test`, lint and `e2e --list` green; the builds
    compared with niord-ng's `build:dk` and `build:info:dk` at `b74b3bfd` (the administration app 223 files
    against 222, differing only in `en.json`'s text override, the hashed names and the build report; the public
    site 50 against 50, equal once the hashed names are masked); `npm run image:info` exit 0 (165 container
    assertions); the push job rehearsed without pushing; the 4.0.81 jar on a scratch copy of `niord_test`
    added `mail.safetynet.cancel` and changed no row. Not yet run: CI on GitHub, the `harbor` environment's
    branch policy, the Harbor push, DK staging, `npm run e2e` against a running DK stack.
- **P5e, DK's bump to 4.0.87** (2026-10-23; `niord-dk` #6 into `feature/p5-dk-moves`, squashed as `e95ba3c`;
  base 4.0.87 = niord-ng `61869e64`, C6's base half and P5k's kit). The `<parent>` and the pin moved together;
  the kit's _What a country must do_ was carried out (`pack.yml` copied again, `pack-releases.md`, the eight
  Danish backend values moved from the status note into `backend.<bundle>.unaccepted`); C6's DK pin-bump items
  went in as additions, and `DkBootstrapITTest` asserts 42 of 42 shadows (`docs/code-data-config-plan.md` §11
  C6). DK's build is green: kit 4.0.87 reads the country URL list. `pack.yml`'s validate job by hand: _Which
  pack_ gives `da` for the merge into `main`, and `pack.mjs validate` the archive `c4cac342…`, the same in Docker
  on Linux. Not yet run: CI on GitHub, the first publish (made since, by the merge of #7, below), staging.
  - **DK's PR into `main`, `niord-dk` #7**, carries P5a, P5d, DK's S-124 identity, P5b and P5e; the owner
    merged it with a merge commit on 2026-10-24 (`d6fa491`). Before that it waited for
    the owner: the organisation's package setting before the merge (a workflow token may create a package, and
    packages may be public); a decision on the Image workflow, since the merge's green CI starts its push to
    Harbor (recommended: disabled until the deploy step); the merge with "Create a merge commit"; then the
    `Pack` run checked, which is `niord-lang-da`'s first publish, and the package made public; the run's line
    recorded in `pack-releases.md` at DK's next change; and the bump PR's note asking each DK deployment's
    sysadmin to delete the two English footer rows. It also waited for GRAD's Maven repository, which was down
    on 2026-10-23 and failed #7's backend CI (BUILD1, `be/docs/potential-errors.md`). The merge's `Pack` run #1
    published `ghcr.io/sifa-dk/niord-lang-da:1@sha256:ded8424ce29393e6ae0e3bcb27bae99db2eaf384361bf6718d1865eacab17efd`
    after the empty `:withdrawn` marker; the owner made the package public, and an anonymous pull answers 200
    for both tags (2026-10-24; §9). The owner disabled `niord-dk`'s Image workflow until the deploy step, since
    Harbor is not finished. Owed: the run's line in DK's `pack-releases.md`, at DK's next change (below).
- **The country launcher** is P8's (the owner's ruling of 2026-10-23), DK's included.
- **DK's next pin bump** (to a base version with C6's base half; that plan's §11 C6 lists it; done 2026-10-23
  in P5e, `niord-dk` #6, base 4.0.87, above): DK's 42
  shadows then apply on a fresh database; the 24 category `da` descriptions and the `Category_Domain` links in
  a new `categories.json` as additions; the 13 restated parameter types (`type`, name, `paramId` and `da`) and
  `NAVTEX`/`Twitter` as additions (optional: with a retry of more than one archive refused, a restatement and
  an addition give the same rows; retries removed 2026-10-24); `DkBootstrapITTest`'s equality test and shadow
  count updated; a failed bootstrap of a country's fresh deployment is recreated empty (D4); the base-only retry
  is for a base-only deployment (retries removed 2026-10-24: that plan's §7.2, *No retry*). Built as additions: `categories.json` (24 `da`, 10 links to
  `niord-nw`), the 13 types and `NAVTEX`/`Twitter` turned into additions; `DkBootstrapITTest` asserts 42 of 42
  shadows and the additions' rows, and its equality test is replaced by one that holds every addition to a
  pinned base entry.
- **DK's next pin bumps after P5e** (recorded 2026-10-24; nothing changed in `niord-dk` here; each done at DK's
  bump onto 4.0.97, `niord-dk` #10, `9f09d195`, below):
  - *At DK's next change* (a pull request, a bump or not). The line for its first publish in the Published
    table of `pack-releases.md`,
    `| da | 1 | sha256:ded8424ce29393e6ae0e3bcb27bae99db2eaf384361bf6718d1865eacab17efd | 2026-10-24 |`
    (archive sha256 `c4cac342c86ab8a97e5dcf4e6e78ac02f6e6356e9c0275e77d7e5348a97e4deb`, equal to P5e's
    `validate` archive), its exact form taken from the run summary of `niord-dk`'s `Pack` run #1. GL's pin (P6e)
    was compared with this digest as given, since DK's record had no line yet; GL's `fe/README.md` says so, and
    changes once the line is in. **Done** at DK's bump onto 4.0.97: the line is `pack-releases.md:15` on DK's
    `main`. The line for `da:2`, which #10's `Pack` run published at
    `sha256:2c0cc5eda59109807a10f0f6e04091fa0213f25d831ac1c03fc2000cd6118762`, goes into `pack-releases.md` at
    DK's next change.
  - *To a base version carrying #196 (C4b).* The test-jar's fixture then holds every base template row and the
    sample's (84 `ScriptResource` rows), and `DkBootstrapTemplates.addMissingTemplates` creates a row only when
    its path is absent, so none of DK's shadows reaches the IT database. At the bump `DkBootstrapTemplates`
    writes DK's 56 bootstrap templates over the fixture's rows (create or replace). `DkLightDestroyedTemplateITTest`
    needs it: `tmpl/light-destroyed.ftl` is one of DK's 42 shadows, and the fixture holds the base row.
    `DkDgpsTemplateITTest` needs nothing: its `tmpl/dgps.ftl` and `tmpl/dgps.js` are the sample's byte-equal
    copies of DK's today. The base half, the paths DK lacks, becomes unnecessary for the ITs
    (`docs/code-data-config-plan.md` §11 C4b; `be/docs/country-api.md`, the C4b row of _Changes_). **Done** at
    DK's bump onto 4.0.97 as `DkBootstrapTemplates.writeDksTemplates` (below, _C4b_).
  - *The `zod` advisory.* DK's `fe/audit-accepted.json` entry for GHSA-mrhx-c7xc-m3w5 (`niord-dk` #9, accepted
    by the owner on 2026-10-24) is temporary and is removed at DK's bump to the first base version whose lock
    resolves `zod` 4.6.6 or later. 4.0.91 already does (`main`'s #200, `68ba80a7`: `@angular/cli` 22.2.4, which
    declares `zod` 4.6.6). The entry's own reason names #196's `zod` override, which the review of #196 dropped
    once #200's lock had moved; what ends the entry is the lock's `zod` version. **Done** at DK's bump onto
    4.0.97: `fe/audit-accepted.json` is `[]`.
  - *To P7's version, 4.0.97*: DK's bump list, completed by P7h's scratch bump (2026-10-25, on DK's `main`
    `d6fa491` in a scratch folder; line numbers are of `d6fa491`), and **built 2026-10-25** in a clone of
    `niord-dk` on `p7/bump-4.0.97`, against 4.0.97 built from niord-ng `c703d061` into a scratch Maven repository
    (its `niord-fe-src-4.0.97.zip` byte-identical to the published one): `niord-dk` #10, CI green on the PR
    (`f3d35e37`) and on the merge, squash-merged by the owner on 2026-10-25 as `9f09d195`; the merge's `Pack` run
    (40209621267, validate and publish green) published `niord-lang-da:2` at
    `sha256:2c0cc5eda59109807a10f0f6e04091fa0213f25d831ac1c03fc2000cd6118762`. What was done and verified:
    - **The pins:** `<parent>` in `be/pom.xml` (`:22`) 4.0.87 → 4.0.97 and the lock's base `{4.0.97,
      d335d766…ca3e}`, equal to the notes of `SIFA-DK/niord-ng`'s release `v4.0.97` and to the zip's sha256;
      `packs` stays `{}`. The scratch bump's report (_To do: Nothing_) came before the review of #204 added a
      key. On 4.0.97 the first `node setup.mjs` exited 1 (12 s) with one _To do_ item, `packs/da/i18n/admin/da.json:
      translate 1 new key: admin.batch.toast.restartRefused`; once DK's `da` had it the second exited 0 (1 s,
      _To do: Nothing_). 0 escape-hatch entries. Its _To look at_: the changed hint, DK's text override
      `admin msg.no_pos_messages`, and three `API-CHANGES.md` actions (the audio deletion, the new key, and
      4.0.87's `pack.yml`/`pack-releases.md`, which P5e had done); its one dependency change `@angular/cli`
      ^22.2.2 → ^22.2.4.
    - **The new key** (from the review of #204, after the scratch bump): the batch page's
      `admin.batch.toast.restartRefused` ("The batch execution was not restarted: {{detail}}"). DK's `da`, its
      home pack, gives it "Batch-kørslen blev ikke genstartet: {{detail}}" (`da.json:1710`), listed in
      `fe/packs/da/status/unaccepted.json`. The changed English of
      `admin.publications.series.hint.messageReferenceFormat` ("NtM 27/2026") is a warning only; DK lists the key
      unaccepted too and removes its stale fingerprint, its Danish byte for byte as before, rather than
      `pack accept`: DK's `CLAUDE.md` forbids accepting or fingerprinting a Danish text, since the owner takes
      every Danish text in one pass at the very end (the ruling of 2026-10-22). No `pack accept` was run.
    - **The audio frontend:** DK deleted `fe/admin/app/promulgation/audio-edit/` (3 files), `audio-view/` (4
      files) and `fe/admin/app/country.providers.ts`, which held only their registration (set-up swaps a seam in
      only when its country file exists, so the base default applies); `fe/admin/` holds `assets/` and
      `environments/`. A copy left in place would silently replace the base product's: the scratch set-up passed
      with no warning and its `angular.json` swapped the stale file in, since the registries are Maps and
      `provideCountry()` comes after the core registrations (§6 P7). `audio-broadcast.ftl` stays DK's.
    - **The `zod` acceptance:** `fe/audit-accepted.json:2-7` (GHSA-mrhx-c7xc-m3w5) went before the first build,
      which passed with 0 accepted (no warning about the file was seen); the file stays as `[]`, since
      `CODEOWNERS` names it. 4.0.97's lock resolves `zod` 4.6.6, as every lock since 4.0.91.
    - **`pack-releases.md`:** the Published table gained da:1's line (*At DK's next change*), `:15` on DK's
      `main`; da:2's line is due at DK's next change (above).
    - **The bootstrap:** DK's `Audio` row in its `promulgation-types.json` stays byte for byte and restates the
      base product's `Audio`: within the first bootstrap its `da` replaces the base's English and its `niord-nw`
      link is added (D1 as overruled by the owner on 2026-10-24; that plan's §7.2, *Within one run*, built in
      P7-bs). Against 4.0.97's `niord-base`: `DkBootstrapFolderTest` 7 of 7 and `DkBootstrapITTest`'s bootstrap
      test green as written; `dksAdditionsNameThePinnedBaseProductsEntries` red at `:452` ("expected:
      <[typeId, domains]> but was: <[typeId, serviceId, name, priority, active, language, domains]>"), since
      `Audio` is in the base archive now. It was rewritten to expect `[navtex, audio, twitter]`, `audio` carrying
      only the fields DK's restatement gives and the base entry's `serviceId`, with its javadoc; the `paramIds`
      assertion's message reworded, the assertion kept. The bootstrap test also asserts `audio`'s priority 50
      (`:307-308`): it holds DK's restated value but does not by itself show a replacement, since the base
      product's `Audio` already has priority 50; the `da` at `:302-303` is what shows DK's values replaced the
      base's. No code change.
    - **C4b** (4.0.97 carries #196): `DkBootstrapTemplates.addMissingTemplates` became `writeDksTemplates`, which
      per DK template creates an absent row, replaces one whose content differs and leaves an equal one (no
      history row); the base product's half is dropped, and the two ITs' set-up methods are
      `writeDksBootstrapTemplates`. Without it `DkLightDestroyedTemplateITTest` was red at `:156`
      (`assertNotNull(daDetails)`, the fixture's English template left in place); green after, and nothing else
      was red.
    - **Documents:** every sentence of the two READMEs that described the same-run rule or a retry:
      `be/bootstrap/README.md:39-43` (the rule as add-only, "and nothing else"), `:54-57` ("an addition never
      changes a name, field or link"), `:58-60` (the retry) and `README.md:227-229` (the second retry sentence,
      now the No-retry rule); `be/bootstrap/README.md:29`, `:45` (`Audio` as DK's own, "whole"); `README.md:25`,
      `:30`, `:135` and `fe/README.md:18`, `:45` (the audio files and the seam file); and, beyond the list,
      `README.md:98-105` (the IT template rows from the fixture, C4b; `Audio` by `typeId`), `README.md:219-226`
      (the claim's second guard and the same-run rule as built), the first-publish sentences of `README.md:33`
      and `fe/README.md:62-64` (past tense), `be/bootstrap/README.md:9-15` (the claim's second guard), `:26`
      (the `categories.json` row's left-out `active` flag, now kept), `:93-102` (the checks) and
      `fe/README.md:16` (the pin's version). No DK javadoc named a retry: the javadocs changed are
      `DkBootstrapITTest`'s class and method javadocs, `DkBootstrapTemplates`' class, method and `templates()`
      javadocs, and the two ITs' set-up comments. The comments that said `niord-ng` keeps DK's bundle or frozen
      copy until its P7 were reworded: `.github/workflows/image.yml:21-23`, `README.md:206`, `:239` and
      `fe/e2e/app-breadth.spec.ts:9-10`.
    - **Checks:** `niord-ng`'s Info CI no longer builds, marker-checks, layout-checks, packages or
      container-tests DK's public-site artefacts (P7d), so DK's image job is the only check of its image; the
      country-marker assertions removed from `info-ci.yml` (`Europe/Copenhagen` and the like) went into DK's
      backlog as checks DK may carry. The image job runs the rewritten container test and layout check (P7b):
      DK's `dk` and `gl` root MRNs get the fixture roots Anchorland and Buoyland, and the da/en width check still
      runs. At the bump, on Windows: `npm ci`, `npm test` (the footer spec only, 1 file, 6 tests), `npm run lint`
      (both applications), `npm run e2e -- --list` (4 tests in 2 files), `npm run build` (audit 0 high, 0
      accepted; the administration app's initial 901.64 kB; check-build green) and `npm run image:info` with the
      browser (the labels read back equal, the separation check 93 modules, the layout check 47 of 47 with the
      width check, the container test 165 assertions); `pack.mjs validate da` (0 errors, its archive
      `275473e9…` differing from da:1's); the whole backend suite with `./test-it` (DK 28 unit and 14 IT; the
      group 114 unit, 1 skipped, and 14 IT: 4.0.87's counts), the group check and the override check (the logo
      only, against `niord-base-4.0.97.jar`). Not yet run: the Image workflow (disabled), DK staging on the
      images, `npm run e2e` against a running DK stack (P8's launcher).
    - **Nothing to do:** from 4.0.97 `niord-web-admin` is published under the classifier `en` only (D11;
      `image.yml` reads none since P5b; its comment at `:21-23` reworded); `paths:check` rules 4-5 and
      `pack.mjs --frozen` are gone (D13, D14; no country script used them); the generated `angular.json` lost two
      overlay ignore entries when set-up regenerated it (D15).
    - **Carried over to DK's own backlog** (removed from `fe/docs/backlog.md` in P7e, D29), `niord-dk`'s new
      `docs/backlog.md`, linked from its README: the deployed test site runs unflagged (the `environment.test.ts`
      behind test-dk sets `production: false`, so the `/dev/geo-editor` route and the pre-authenticated Keycloak
      stub stay live); the translation tracking lists (go-live waits for empty Danish lists); the brand and
      wording of the new authority (footer, logos, legal texts); an accessibility statement for each public host
      (its address in `accessibilityStatement` of `info.config.ts`); no parity spec for the language-config swap
      and no test-dk serve configuration; the DK status palette (18 tokens, all but two equal to the default,
      never tuned); 169 of 3,274 Danish values byte-identical to the English. And, as DK's own checks if it wants
      them, the country-marker assertions of `info-ci.yml`. The rules the deleted base specs applied to the
      frozen copies (D23) — icons of 32 and 152 px, the 44 px footer logo, `index.html`'s `lang` and `title`,
      cookie statements naming every `niord-info.*` key, no literal year in `FOOTER_COPYRIGHT` and no address in
      `MAP_ACCESSIBILITY` — were carried over to DK's backlog too; the kit checks them since P8a's C3 (NG-5,
      NG-6), and DK's `main` `9f09d19` passes every one of them (checked read-only on 2026-10-25).
  - *To the version B4's merge publishes* (recorded 2026-10-25; nothing changed in `niord-dk` here): the SECOM
    page's reconcile toast `admin.secom.toast.reconciled` gains the placeholder `{{failed}}` ("Reconciliation:
    {{created}} created, {{updated}} updated, {{cancelled}} cancelled, {{unchanged}} unchanged, {{failed}}
    failed"; `fe/country-kit/API-CHANGES.md`, `Unreleased`). DK's `da` value gains `{{failed}}`, since the pack
    check fails a text whose placeholders are not English's, so DK's set-up fails on that row until then; the
    key is listed in `status/unaccepted.json`. DK's next publish, `niord-lang-da:3`, carries it.
- **Move steps P4's dry run found** (made there in scratch copies only): the info footer spec reads
  `ui-apps/info/public/assets/dk` and imports `../language.config`, neither in a country tree — it reads
  `info/assets` and takes the languages from `niord-country.json`; `tokens.active.dk.css` and the hand-kept
  `language.config.ts` files do not move (a country workspace generates them from the manifest); the `da`
  pack's backend half gains `mail.safetynet.cancel` and a value for `navwarn.date.html_day_suffix` that passes
  the markup row (§6 P4, _Found on the way_); the public site's three DK addresses go in DK's
  `fe/build-urls-allowed.json` (the choice at §6 P4, _Found on the way_, settled; built in P5k); DK's `admin/environments/environment.production.ts`,
  which P5 writes (the in-tree DK folder holds development, local and test only), carries `production: true`,
  which set-up requires since the review of PR #184; `app-breadth.spec.ts`'s two footer legal-document tests,
  which pass only against a DK or GL build, move to `niord-dk`'s `e2e/`; the OCI client for pinned packs is built (P5k) and `niord-lang-da` published, on which GL's
  production build from a pinned `da` waits (P4 built it by hand after `guard-build`; P5k's harness builds one from
  a pinned pack on a local registry); `pack prune --base` is built (P5k, §4.5). No allow-list entry of `country-words.json` ends in P5 (all were
  `until` P7, and P7b, #205, removed them). The pack check's labels already name the resolved pack folder (`packs/<lang>/`,
  `.niord/packs/<lang>/` or `languages/<lang>/`), P4's hand-over item, done by the review of PR #184. The
  set-up refusals that review added — `production: true`, orphans under `admin/environments/`, links in the code
  folders, a pack entry for a language no application names, the footer branding's pairings and documents, the
  wider protected list — reach DK when it pins a zip that carries them.
- Verification: a dispatch from a branch other than `main` never reaches the `harbor` environment: the push
  job's `if: github.ref == 'refs/heads/main'` skips it, and the environment's main-only branch policy (the
  owner's setting, proven at the deploy step; Harbor is not touched at this time, owner 2026-10-22) is the
  second line (§4.11 rule 1);
  the image's bundle and the info image's files equal the last base-product-built ones; DK staging
  boots on them.
- Done when: DK's frontend is built only in `niord-dk`, and `niord-lang-da:<n>` is published with its backend half (frontend part);
  C6's done-when holds for DK (backend part). For DK the first half holds in `niord-dk` since P5b (niord-ng kept
  its frozen copy until P7 deleted it, P7f, #211), its build green since P5e; `niord-lang-da` was published by the merge of
  `niord-dk` #7 (2026-10-24, `d6fa491`: `Pack` run #1, `niord-lang-da:1`, made public and pulled anonymously,
  and pinned by GL in P6e), its backend half in the pack and checked by set-up, so the frontend part's
  done-when holds. The Verification's `harbor` branch policy and DK staging wait for the deploy step.
  On 4.0.97 (2026-10-25, `niord-dk` #10, `9f09d195`) both halves hold again, locally and on CI: set-up, the
  build with check-build and `image:info`; the whole suite, the group check and the override check; the
  merge's `Pack` run published `niord-lang-da:2`. Image and staging: not yet run.
- **P5k — the kit's half of the frontend part, as built** (2026-10-22 to 2026-10-23; #194, branch
  `p5k/packs-on-ghcr` into `feature/p5-kit`, merged 2026-10-23; #190, `feature/p5-kit` into `main`, with its two
  reviews answered on that branch, _Review of #190_ below; first published with the next base product version,
  4.0.87, to which DK bumped and whose merge published `niord-lang-da` (`niord-dk` #7, 2026-10-24), and which GL
  pinned once the package was public (P6e, 2026-10-24)). DK's move (`niord-dk`, branch `p5/fe-move`) ran
  beside it on kit 4.0.81 and named what the kit lacked; those items are built here too. Nothing was
  published: no push to `ghcr.io` or any registry but a local container, no Maven deploy.
  - **The canonical archive** (`country-kit/lib/pack-archive.mjs`: `ARCHIVE_LAYOUT`, `packEntries`,
    `buildPackArchive`, `readPackArchive`, `stagePack`) as §4.5 lists it: `status/unaccepted.json` left out, any
    other stray file (a `renames.json`), a link, a linked folder, a folder where a file belongs, a missing flag or a
    `pack.json` of another language refused, naming the file; text entries turned to LF; at most 32 entries, 2 MiB
    an entry, 8 MiB in all, 64 KB for the PNG flag (the SVG under the entry cap, §4.11 rule 4 naming the PNG).
  - **`pack.yml`, the OCI client and the record** (§4.5, _As built in P5k_; §4.11 rules 1 and 6):
    `country-kit/lib/oci.mjs` (Node built-ins and `fetch`, no other kit import), handed to the token jobs as one
    file checked by its SHA-256; `templates/pack-releases.md`; the stub unchanged, since the pull runs in the kit
    after the stub's hash check.
  - **The consumer** (`setup-kit.mjs`, `lib/pins.mjs`): every pack but `en` staged into `.niord/packs/<lang>/` in the
    archive's layout, home packs included (DK's item: its pom copies `fe/packs/da/backend/` in place until it reads
    the staged path, which comes with the kit version that adds a backend job to the templates, not with the bump
    to this one; `docs/code-data-config-plan.md` §4.3); a pinned pack's marker read first, then pulled by digest and verified; a withdrawn pin the first
    failed check and an upgrade-report To-do item; a missing marker unreadable; `NIORD_PACK_<LANG>` still the
    development path, staged in the archive's layout, refused under `CI`, and still refused by `guard-build`;
    `foreign` passed to the pack check for pinned and local packs; the secret scan reads the home packs and only the
    foreign packs' staged copies; `--update-pins [<lang>=<tag>]`; the lock's packs as full references, sorted; the
    build report's `reference`.
  - **The pack tools** (`pack.mjs`, `lib/published-catalogs.mjs`): `prune --base`, `latest-catalog`, `validate`,
    `check --frozen`, `accept --app backend --bundle`; prune and migrate handle the backend lists (prune only for
    bundles in the catalogs given); an option a command does not take is refused.
  - **The backend half's status** (§4.5; DK's item: kit 4.0.81 refused a `backend` section, so DK listed its 8 new
    Danish backend values in the note of `status/unaccepted.json` and seeded no backend fingerprints).
  - **The frozen `da` copy** (§4.6): `--frozen da` in `build:dk`, `build:gl`, `build:info:dk`, `build:info:gl` and
    `languages:check`; `stage-languages.mjs` unchanged, as it already staged a missing key from English.
  - **A country's URL allow-list** (§4.11 rule 5; DK's choice for its three addresses) and **its
    branding-unaccepted list** at `fe/<app>/branding-unaccepted.json` (§4.5; DK's location kept).
  - **`init`** (§4.8; DK's items): `fe/.gitattributes` from `templates/gitattributes`, `pack-releases.md`, and no
    `be/bootstrap/releases/README.md` (its text in `templates/bootstrap-README.md`; BootstrapArchive's layout
    unchanged, no backend change); `CODEOWNERS` gained the new paths. (Since KM-9 `init` writes the one template as
    both `fe/.gitattributes` and the root `.gitattributes`, and since NG-B2 the template keeps `*.cmd` CRLF:
    _NG-B as built_.)
  - **The Example country** (§4.8): `zz` `publish: true`; the staged layout, `latest-catalog` and `prune --base`
    on every run, the registry steps with `--image`; its scratch repository's `niord-fe-src` versions cleared at
    the start of each run.
  - **The rules and documents** of §7: `fe/ui-apps/admin/CLAUDE.md`, `fe/ui-apps/info/CLAUDE.md`, `fe/CLAUDE.md`,
    `fe/docs/definition-of-done.md`, `fe/docs/angular-conventions.md`, `fe/docs/platform.md`, `fe/README.md` and
    the `niord-review` skill (a base product PR adds English only; the `da` copy is frozen; Kalaallisut enforced
    until P6); the comments of `fe-ci.yml` and `info-ci.yml`; the kit's README (publishing, pinning, withdrawing,
    pruning, the archive, the backend status, the country lists, `init`, the harness); `API-CHANGES.md` (4.0.81
    named, and an _Unreleased_ section with what a country does at the bump).
  - **Choices taken for the owner**, the recommended option each time: the pull in the kit, not the stub; the
    client as one file handed over by artifact, so a withdrawal needs set-up green on `main` (by hand otherwise);
    the image-manifest shape with a custom config type; `publish` on a push changing exactly one pack, the tag the
    run number; the lock's packs as reference strings; loopback registries written into a lock only by the tests
    and the harness (set-up accepts a loopback pin wherever a lock holds one, R190);
    a withdrawn pin a check error rather than a throw, so the upgrade report is written; a `NIORD_PACK_<LANG>` copy
    taking precedence over a pin; the build report's `reference` additive; the catalog versions from Maven's
    metadata after resolving `LATEST` (a version range fetches none; `RELEASE` was not taken, GitHub Packages'
    metadata not being known to carry `<release>`), a failed Maven run stopping rather than reading stale metadata;
    validate's information-only check holding the pack to what it carries; the backend half's untracked keys a
    warning; the PNG flag alone under 64 KB; `--frozen` named on the command line; the country URL entries stricter
    than the base list's (no `^`, a dotted host, no port), a URL both lists admit counting for the base list; a
    `country.*` key allowed in the branding list; the branding list's warning counting valid lists only. After the
    review of 2026-10-23: a client with credentials asking for `pull,push` on every request, its own scope before
    the challenge's (GHCR's challenge to a read names `pull`, and a pull token may be refused for a package not
    published yet); a write
    following no redirect, refused with a message naming the host, method, path and status rather than `fetch`'s
    generic error; the catalog versions read from the head local repository only, a tail's metadata ignored rather
    than reported, since Maven still reads artefacts from it.
  - **Found on the way.**
    - DK's `fe/info/branding-unaccepted.json` at the history merge (`p5/fe-move`, `e375ce4`) was a copy of the
      base product's file and still held GL's `kl` list, which the new check refuses ("`kl` is not a UI language
      of info"). Checked once in a scratch copy read through `git archive` of that ref: as merged it fails on
      `kl`; without it, and with two `da` entries, it passes with the warning. DK's move commit (`niord-dk` #5,
      `d0c7825`) dropped GL's list, and its note names DK's own path, so DK's file passes as merged.
    - `prune --base` in the base product tree: `be/mvnw` there names no repository for `niord-fe-src`, so without
      a `settings.xml` profile naming the registry it reads only what the local repository holds; in a country
      workspace the country's pom names the registry.
    - Maven resolved `LATEST` from `deploy-file`'d metadata that carried `<release>` and `<versions>` and no
      `<latest>` (checked against a `file://` repository, 2026-10-23), so the one open point is what GitHub
      Packages' generated `maven-metadata.xml` holds, which DK's first `pack.yml` run proves.
    - Frontend CI's and `verify-local`'s example run with `--image` now pull `registry:2.8.3` from Docker Hub, and
      the run takes about 1.5 min more (the pinned country's production build). With `alpine:3.22` for
      `image:info` the job now pulls two images anonymously, under Docker Hub's rate limits on shared runners;
      `fe/docs/backlog.md` (_Quality gates and tooling_) holds what would close it if it bites.
    - `npm audit signatures` crashed once (exit 3221226505, a Windows process crash) in `verify-local`'s example
      row while other runs shared the npm cache, and once in P6b's checker, each passing when run again; fixed
      in P8a's C2 (NG-3): one retry on that exit with a fresh npm cache (§3.2).
  - **Changed from the plan, and corrected above.** §3.2 step 2 (the pull in the kit, the marker first, home packs
    staged) and step 4 (`--frozen da`); §4.1 (the lock's reference strings); §4.2 (the bootstrap READMEs,
    `fe/.gitattributes`, `fe/build-urls-allowed.json`, `fe/<app>/branding-unaccepted.json`, `pack-releases.md`);
    §4.5 (the backend status, the country branding list, _As built in P5k_, `prune --base`, the tools); §4.6 (the
    status row, `--frozen`); §4.8 (`init`'s files, the URL check, the example); §4.11 rules 1, 2, 5 and 6; §4.12
    (a withdrawn pin filled); §5 step 4; P3's and P4's records (pointers only); §7; §9.
    `docs/code-data-config-plan.md` §4.3 and §7.1 (the staged home packs; `init` writes `be/bootstrap/README.md`
    only).
  - **Verification**, as the runs reported (2026-10-22; `npm ci` in `fe/` first, exit 0):
    - The pack tools: `pack.test.mjs`, `pack-archive.test.mjs`, `published-catalogs.test.mjs`,
      `stage-languages.test.mjs` and `catalog.test.mjs`, 93 tests, 92 passed, 1 skipped (a file link needs a
      privilege on that Windows host; the junction case runs). A mutation pass removed 23 refusals one at a time:
      22 failed a test at once; the per-entry cap on a pulled archive did not, a test was added and fails without
      it. `languages:check`: 0 errors, 0 warnings, 0 stripped.
    - The frozen copy, with one key planted in `en`'s administration and public-site bundles and reverted after:
      `build:dk` (57 s), `build:gl` (53 s) and `build:info:dk` (29 s) exit 0, each with one `frozen da` warning and
      the key staged as English text in `.niord/build/<app>/i18n/da.json`; `build:info:gl` exit 1 (18 s) on
      `languages/kl/i18n/info/kl.json lacks 1 catalog key(s): P5K_PLANTED`. Reverted, the four exit 0 with 0 errors
      and 0 warnings (60, 65, 40 and 35 s).
    - The archive on two hosts: `languages/da`, `languages/kl` and the example's `zz` give the same SHA-256 on
      Windows and in `docker run --rm node:24` (`3ddebc93…`, `d545b32e…`, `263091d8…`); CRLF, CR and LF copies of a
      pack give one SHA-256 (unit test).
    - The OCI client: `oci.test.mjs` 31 of 31 against the in-process fake registry
      (`test-fixtures/fake-registry.mjs`), a fetch guard refusing any non-loopback URL; a mutation run took out 15
      checks one at a time (the manifest and blob digests, the body cap, the answered digest, the upload Location,
      the realm's host, the layer count and type, the config's language, the registry host, the reason, an existing
      tag, the marker's fields, the archive's SHA-256, a missing marker), each failing at least one test.
    - `pack.yml`: `pack-workflow.test.mjs` 24 of 24 (js-yaml parses it; _Which pack_'s own bash with a fake `git`;
      the tokens, permissions and `if:` conditions; the withdrawal's checks).
    - The consumer: `setup.test.mjs` 56 of 56 (first recorded as 55; the checker counted the file's 56 tests on
      2026-10-23, all passing); in-place mutations of the withdrawn lookup, the owner check, the
      highest-good-tag choice and the first-error placement each failed a test. With `stub`, `init`,
      `upgrade-report`, `manifest`, `image-info`, `workspace`, `zip`, `example/run` and `example/acceptance` tests,
      309 tests, 308 passed and 1 skipped; later `init` and `workspace` 51 of 51, `acceptance` and `run` 41 of 41,
      `upgrade-report` 32 of 32.
    - The country lists: `check-build.test.mjs`, `build-urls-check.test.mjs`, `branding.test.mjs` and
      `zip.test.mjs`, 89 tests, 88 passed, 1 skipped (the symbolic-link case); against the old
      `build-urls-check.mjs`, `build-checks.mjs` and `branding.mjs` the new tests failed (`build-urls-check.test.mjs`
      at its import, 5 of `check-build`'s, 6 of the 8 new branding tests — the absent list and a list inside
      `assets/` pass on the old code by design). `build:info` then `urls:check --absolute`: exit 0, 36 files, so the
      base list still admits the base product's own public site.
    - The Example country harness: without `--image`, green in 14 min 12 s (the staged layout, `latest-catalog`
      naming `4.0.0-example` then `4.0.1-example`, `prune zz --base 4.0.0-example` reading both with nothing
      removed; the registry step skipped with its line). With `--image --upgrade-from` the 4.0.81 release's zip
      (its SHA-256, `9d784b3a…`, equal to the release notes' line): the first attempt failed at `latest-catalog`
      because the reused work folder still held `4.0.1-example`, which the harness now clears; the rerun was green
      in 18 min 41 s — `zz` published to a `registry:2.8.3` container on `127.0.0.1`, pinned by `--update-pins`,
      set-up, `guard-build` and `npm run build` from the pin (the production build from a pinned pack that P4
      made by hand), the withdrawal stopping set-up with its reason, and the upgrade test from 4.0.81 to
      `4.0.0-example` green. `docker ps -a` and `docker images` showed no container or image of the harness after.
    - `test:guards` whole: 979 tests, 974 passed, 4 skipped, 1 failed — `scripts/release-workflows.test.mjs`'s
      retract paging case, on its 60 s bash timeout, which also fails alone on that workstation; neither
      `retract-version.yml` nor `workflow-step.mjs` changed. Prettier, ESLint and `git diff --check` clean on the
      changed files; `docs:check` 29 files, 0 errors.
    - The checker's run (2026-10-23): the metadata mechanism against a `file://` repository of two
      `deploy-file`'d versions — `4.0.0-example` and `4.0.1-example` read, `latest-catalog` naming `4.0.1-example`,
      `--base 4.0.0-example` giving both and `--base 4.0.1-example` one, an unpublished `4.0.2-example` refused;
      Maven resolved `LATEST` though the metadata carried `<release>` and `<versions>` and no `<latest>`. The
      upgrade test with this `API-CHANGES.md`: `run.mjs --image --upgrade-from` the 4.0.81 zip (`9d784b3a…`, equal
      to the release notes' line), exit 0 in 1870 s with the _Unreleased_ section present.
    - After the review's fixes (2026-10-23): `oci.test.mjs` 34 of 34 and `pack-workflow.test.mjs` 25 of 25 (the
      three new client tests — `pull,push` on every token request of a push, a first publish where a pull token
      may not read the package, an upload answered 307 failing with nothing sent elsewhere — failed with the
      fixes reverted, and the new _Which pack_ test for a removed folder without its line);
      `published-catalogs.test.mjs` 8 of 8 (the new tail test failed against a copy reading head and tails, getting
      `4.0.99`); the four files together, `setup.test.mjs` included, 123 of 123. A scratch script against a
      `registry:2` container pushed a pack and the empty marker, pulled the pack by digest, read the marker and
      listed the tags, green; the container and its image removed after. The Example country harness without
      `--image`: exit 0 in 949 s, `latest-catalog` naming `4.0.0-example` then `4.0.1-example` and `prune zz --base
      4.0.0-example` reading both with nothing removed. `test:guards` whole twice: 984 tests, 980 passed, 4 skipped
      (465 s); and 979 passed with one failure, `scripts/release-workflows.test.mjs`'s paging case on its 60 s bash
      timeout under load (29 of 29 alone, the case in 33.6 s).
    - **The checker's run after the review's fixes** (2026-10-23): the harness with `--image` and `--upgrade-from`
      the 4.0.81 zip (sha256 `9d784b3a…`), exit 0 in 1543 s: `zz` published to `registry:2.8.3` with `validate`'s
      archive, pinned by `yy` with `--update-pins`, a production build from the pin, the withdrawal stopping
      `yy`'s set-up with its reason, and the upgrade test green; `test:guards` whole: 984 tests, 980 passed, 4
      skipped.
    - **`./verify-local --base origin/feature/p5-kit`** on `44b4ea2f` (Windows, 2026-10-23): administration app
      PASS (10 min 37 s), public site PASS (1 min 54 s), the Example country FAIL once on the `npm audit
      signatures` crash (exit 3221226505; _Found on the way_), then the harness alone, as `verify-local` calls it
      (`--image`), PASS in 782 s on `404f600e`, a plan note after `44b4ea2f`. CI on #190 green at `ddce5140`
      (Frontend gate, Info gate, and the `Example country` job in 11 min).
    - **Not yet run:** GitHub Packages' generated metadata and GHCR's own behaviour (§9). P5's own Verification
      and Done-when are DK's: the first real publish of `niord-lang-da` from `niord-dk`, at the merge of its bump to
      a base version carrying P5k, GHCR's acceptance of the manifest shape, the package made public and pulled
      anonymously (§9), DK's `harbor` environment refusing another branch, and DK staging at the end. Since
      done (2026-10-24): the first real publish, by the merge of `niord-dk` #7 (`Pack` run #1, GHCR accepting
      the manifest), the package made public and pulled anonymously, and GL's first pin (P6e; §9); DK's
      `harbor` environment and DK staging are left, at the deploy step.
  - **Review of #190 (R190, 2026-10-23).** Two reviews of `ddce5140`: one inline finding (the shared `pack` group
    could drop a pending withdrawal) and 42 inline comments with six verdict items, six on the PR body, seven
    outside the diff and one on what DK's first publish proves. Each was checked against the code, the plans and
    the countries' move branches (read through git); all were fixed or recorded, one declined in code with a
    wording change (a rename-aside helper for `stagePack`). The six the verdict asked to settle before the
    countries bump, decided for the owner:
    1. **The pom's re-point** at `fe/.niord/packs/<lang>/backend/` is not a step of the bump to this version: it
       comes with the kit version that adds a backend job to the templates, with set-up before Maven and the Maven
       check; until then a country's pom copies its home pack's backend half in place, and GL keeps its root-level
       `*_da` (`docs/code-data-config-plan.md` §4.3; `API-CHANGES.md`).
    2. **DK's first publish** is the merge of its bump into its `main`, not a dispatch: the owner checks the
       organisation's package setting before that merge; DK dispatches only when a merge touched no pack folder,
       or after fixing the setting if the merge's publish failed on it. The push filter and _Which pack_'s count
       leave `status/unaccepted.json` out, and a `before` no ref reaches fails asking for a dispatch.
    3. **GL's pin** follows an order written in `API-CHANGES.md` and the kit README: DK's merge publishes
       `niord-lang-da`, the owner makes the package public, then GL runs `--update-pins`.
    4. **A first publish** pushes the empty marker before the pack, so a re-run of a broken one finishes it; "exists
       already" names the recovery (a new dispatch, a new run number); a manifest `PUT` without
       `Docker-Content-Digest` is confirmed by a `HEAD`.
    5. **The queue** (`queue: max`, `3b4c2971`), and the README's withdrawal section now says to check the run's
       summary line, dispatching again being safe.
    6. **A set-up that fails from staging the packs on** removes `fe/.niord/packs/` whole after writing the upgrade
       report, so no pack set-up did not pass is left where a build or Maven reads it (the reviewer's second option:
       the checks' labels and the secret scan's paths name `.niord/packs/<lang>/`, and every set-up restages every
       pack anyway); a failure before it (the pins, the zip's hash, the stub) leaves the last good set-up's folder.

    The other fixes: the client's tag list read whole or not at all (a 100-page cap and a later page's 404 throw),
    inherited names refused as commands, a loopback port held to 1–65535 (and a loopback registry on port 80
    reached at its first request), a `|` escaped in the summary rows; `--update-pins` accepting a candidate only
    once it is pulled and verified as a pack; a withdrawn pin no longer warned about as lacking its backend half;
    a secret in a pinned pack naming the pin and what to do; `.niord/oci/` keeping only the archives a run used,
    and a cached file of another size never read; a pulled `pack.json` held to the strict schema; an empty stray
    folder refused; `prune --base` printing each version as it reads it; `pack.yml`'s publish verify step, the
    validate outputs' sources and the step-level `if:`s tested; js-yaml declared as a devDependency at the lock's
    4.3.2 (the lock's root entry the only change); the harness's wording narrowed to the two commands, anonymously,
    against an auth-less registry, and a stale registry container of an interrupted run removed by the next; the
    country URL refusal saying another syntax is a base product change; Frontend CI's `Example country` comments
    and step name and `verify-local`'s example row naming the registry step, the row repeating the harness's
    publish or skip line. The documents: this plan (§4.5, §4.8, §4.11 rules 2 and 6, P5, P6, P7, §7, §8, §9),
    `docs/code-data-config-plan.md` §4.1, §4.3 and C6, the kit README, `API-CHANGES.md`, `fe/CLAUDE.md`,
    `fe/docs/platform.md` and `fe/docs/backlog.md`.

    The tests. Seen red against P5k's code and green with the fix (new behaviour): the client's first-publish
    re-run, manifest without a digest, tag page cap and later-page 404, port range, port-80 origin, "exists
    already" text, inherited command names and summary escape; set-up's removal of `.niord/packs/` (a pinned pack
    with a markup violation, and the checkout's failed check), the withdrawn pin's settings warning, the non-pack
    candidate, the pinned pack's secret line, the cache's size check and sweep, the pulled `pack.json` schema, the
    stray folder and `prune --base`'s progress; `pack.yml`'s push filter, the notes-only push and the force-push
    (each on a real temporary git history), the URL refusal's wording, and the harness's stale-container choice and
    summary line. Seen red only now, by removing or loosening the line each guards (P5k code that had no test that
    could fail): the GHCR token endpoint's three clauses, the blob size, the layer cap before its blob is fetched,
    the layer's digest and size, the config's media type, keys and format, seven marker rows (each now with its own
    regex), the streaming body cap (a `chunked` fake), the private-package and no-write messages, the upload
    `Location`'s query and the tag pages followed; set-up's five untested outcomes (an unreadable marker, a
    `NIORD_PACK_REGISTRY` naming `ghcr.io`, a pack without a marker writing no pin, a marker listing every tag, an
    unchanged pin); the publish job's five verify refusals, validate's output sources and the step-level `if:`s.
    `stagePack`'s guard test (a write failing half-way keeps the staged folder) passes on P5k's code too, as
    expected. The fake registry gained `failManifestPut`, `noManifestDigest`, `chunked`, `pageSize`, `selfLink` and
    `relativeLocation`, an absolute upload `Location` with `?_state=` by default, and the query in its log.

    Verification as the runs reported (Windows, 2026-10-23, from `fe/`): `oci.test.mjs` 57 of 57;
    `setup.test.mjs` 66 of 66 (254 s); `pack-workflow.test.mjs` 38 of 38 (125 s); `pack-archive`,
    `stage-languages`, `published-catalogs`, `pack` and `example/acceptance` tests 117, 116 passed and 1 skipped
    (the file-link case); `build-urls-check`, `check-build`, `example/acceptance` and `example/run` tests 85 of
    85; `dependencies`, `workspace` and `upgrade-report` 85 of 85; `init` 20 of 20; `git diff fe/package-lock.json`
    the one root devDependencies line; `bash -n verify-local` and its new grep over three scratch logs; Prettier
    and `git diff --check` clean on the changed files. ESLint applies no rule to the kit's `.mjs` files
    (`fe/docs/backlog.md`). The whole `npm run test:guards` was red once, on two of 1037: the kit's secret scan
    found the GHCR token-endpoint test's realm row with a user and password in its URL (now written with
    `<user>:<password>` placeholders, which the scan skips, and still refused), and
    `scripts/release-workflows.test.mjs`'s case with harbor allowing protected branches hit its 60 s bash
    timeout under load (alone, the file passed 29 of 29 in 223 s); run again after the fix, it passed: 1037
    tests, 1033 passed, 4 skipped, none failed, 303 s. `./verify-local --base origin/feature/p5-kit` on
    `e30a4b04` (Windows, 2026-10-23): PASS in 25m23s; the backend (the merge of `main` brought `be/` in) unit
    5115 and ITs 3359 with only the 10 known Windows-only failures, 7m41s; the administration app 6m41s; the
    public site 1m13s; the Example country 9m34s, `zz` published to a local `registry:2.8.3`, pinned, built and
    withdrawn. Not yet run: CI on #190 after the push.

**P6 — GL moves (`niord-gl`), with its theme.** A frontend part and a backend part, as P5.
- As P5 for GL (backend part: its 131 backend resources, 75 of them shadows, classified in
  `docs/code-data-config-plan.md` §9.2 and moved in its C6, which also ends GL's database-to-repository
  `build-resources.mjs` pipeline and has GL's `pom.xml` declare `niord-base`'s test-jar and the test dependencies, without which the
  security group runs no test there; the backend part went first, as DK's did (2026-10-22), so GL's five
  `*_kl` and five `*_da` bundles stayed at the root, read by the transitional layer; GL's frontend move
  (`niord-gl` #4) deleted the `*_kl`, its pom copying `fe/packs/kl/backend/` in place, and the `*_da` stay until
  GL's Maven build reads the pinned `niord-lang-da`'s backend half, which set-up stages since GL's bump to 4.0.87
  (P6e) and the build reads with the kit's backend job, `docs/code-data-config-plan.md` §4.3): both
  `country-specific/gl/` folders, `fe/ui-apps/admin/public/assets/gl/`,
  `fe/ui-apps/info/public/assets/gl/`, its entries of `fe/ui-apps/info/i18n-status/branding-unaccepted.json`
  (the seven Kalaallisut branding values and legal documents; GL's move, #4, has them in
  `fe/info/branding-unaccepted.json` with `da: []`, and the base product kept its copy until P7 deleted it, P7f, #211), `fe/styles/tokens.gl.css` (`tokens.active.gl.css` and the two `language.config.ts` do not move: a country workspace generates them), the e2e specs `gl-model-languages.spec.ts` and `admin-model-language.spec.ts` (every case needs GL's `kl`) into `fe/e2e/`; `kl` as a home pack with the history of `fe/languages/kl/` and its earlier `public/assets/i18n/kl.json` and `kl.svg` paths, GL's five `*_kl.properties` laid into its `backend/` without the six keys outside the catalog and with a passing `navwarn.date.html_day_suffix` (`kl` is a UI language of GL's public site, §4.6); `da` pinned from
  `niord-lang-da` (since P6e, 2026-10-24: `ghcr.io/sifa-dk/niord-lang-da:1` by its digest; P6b's local copy of
  DK's pack through `NIORD_PACK_DA`, which the production build and CI refuse, is gone).
- **Backend part: built 2026-10-22** on 4.0.76 in `niord-gl` (#2, `5b8f09b`, on `feature/p6-gl-moves`); C6's
  State for GL (`docs/code-data-config-plan.md` §11) holds the record. The five `*_kl` and five `*_da`
  bundles stayed at the root, read by the transitional layer; GL's frontend move (#4) has since deleted the
  `*_kl`, and the `*_da` stay until GL's Maven build reads the pinned `niord-lang-da`, which set-up stages since
  P6e; the build reads it with the kit version that adds a backend job (`docs/code-data-config-plan.md` §4.3).
- **What remains for the frontend part (P6b)**, from the backend part's record: `web_en`'s admin-only
  rewordings, deleted from the backend (`footer.copyright` and `footer.link` to GL's branding;
  `msg.no_pos_messages` and `editor.status.verify_short` to text overrides or gone, after P6b confirms an
  admin-app reader of `msg.no_pos_messages`; `promulgation.confirm.generate`,
  `promulgation.confirm.generate_all`, `publication_type.MESSAGE_REPORT`, `template.markings.bearing`,
  `template.sample_message`, `view_mode.table` and `editor.status.publish_short` gone); the five `*_kl`
  bundles into the `kl` pack's backend half with the Maven staging of `defaults/packs/`, compared with the pack
  first and then deleted at the root; the five `*_da` bundles compared with the pinned `niord-lang-da`'s
  backend half, the differences to `defaults/country/texts/da/` and the rest deleted; `GlTemplateTest`'s
  dictionary test then reads the packs, and `defaults/country/README.md` changes with it;
  `img/flags/{da,kl}.png` later from the packs; `info.config.ts`, the source of GL's settings map centre, moved
  into `niord-gl`, its README then naming GL's own path; `fe/README.md` rewritten. GL's launcher is P8's. Done in
  P6b (below), but for the `*_da` and `da.png`: `footer.*` in GL's administration branding already;
  `msg.no_pos_messages` has no reader in the administration app, so no override; `editor.status.verify_short`
  and the five others gone. The `*_kl` into the `kl` pack, compared first (only the suffix changed and
  `pdf.attachment.title` dropped), then deleted. The `*_da` compared with DK's pack (`niord-dk` `bc9353a`,
  `niord-lang-da` not being published): GL's own wording to `texts/da/`, the root `*_da` kept as the
  transitional layer until GL stages `niord-lang-da`. `kl.png` from the `kl` pack, `da.png` once GL stages
  `niord-lang-da`. `GlTemplateTest` reads the packs, `GlKalaallisutTextsTest` new; `info.config.ts` moved. The `*_da`
  and `da.png` were done in GL-1 (`niord-gl` #10, 2026-11-02: deleted, the pack's wording winning), and NG-B removed
  the transitional layer (#252; _MAIN, DK-1 and GL-1 as built_, _NG-B as built_).
- **P6b, the frontend part, as built** (2026-10-23; `niord-gl` #4 into `feature/p6-gl-moves`, merged with a
  merge commit as `7688862`; base 4.0.81, to which GL's backend moved in the same PR).
  - **The move**, as P5b's: `git filter-repo` in Docker over niord-ng `b74b3bfd`, 14 paths and 12 renames, a
    second pass leaving out the two `language.config.ts`; 4 commits (niord-ng touched GL's paths only in the
    squash merges #24, #39, #58 and #184), GL's 44 files at the tip, each equal to its original; the secret
    scan found nothing; one pure merge, `fe/.gitattributes` first.
  - **The theme and the face.** Claude Design's theme merged into `tokens.gl.css` token by token (95 tokens, 61
    values changed, values only, §4.7); Lato 400, 400 italic, 700 and 900 (latin, latin-ext) vendored from
    `@fontsource/lato` 5.3.0 with `OFL.txt` in `fe/styles/fonts/lato/`, declared by `fe/styles/fonts.gl.css`,
    before the base product's `/media/**` service-worker group existed (§4.7; built 2026-10-25, B4).
  - **The backend half of `kl`.** GL's five root `*_kl` without the six keys outside the catalog and with a
    passing suffix; the 163 catalog keys it lacks listed in `fe/missing-keys-accepted.json` (the escape hatch,
    §4.6: the default language serves until GL's translators supply them; no Kalaallisut was written); GL's
    five own mail keys to `texts/kl/`, `pdf.attachment.title`, read by nothing, dropped; the pom copies
    `fe/packs/kl/backend/` and the pack's flag in place. GL's five root `*_da` and `da.png` stay until GL stages
    `niord-lang-da` (`docs/code-data-config-plan.md` §4.1, §9.2).
  - **GL's two red CI steps**, until GL pins P5k's kit and `niord-lang-da`: set-up (kit 4.0.81 cannot fetch a
    pinned pack, and `NIORD_PACK_*` is refused under CI), then the absolute-URL rule (three findings on two
    addresses, in GL's `fe/build-urls-allowed.json`). `pack.yml`'s validate job is red at set-up for the same
    reason. Both were cleared in P6e (2026-10-24): set-up pulls the pinned `da`, and kit 4.0.87 reads the country
    URL list.
  - **Verification**, locally, with `da` from a copy of DK's pack (`NIORD_PACK_DA`): the whole backend suite
    green (GL's Surefire 27, the group's 114 with 1 skipped, Failsafe 3 + 14) and the override check exit 0;
    `npm test`, lint and `e2e --list` green; `npm run build` refused by `guard-build` (a local source), as
    designed, and its later steps run by hand, check-build red only on the absolute-URL rule; the builds
    compared with niord-ng's `build:gl` and `build:info:gl` (equal but for the theme, the face and the build
    report); the woff2 files only in `media/`; a first visit's network log all same-origin, Lato 400 and 700
    fetched; the public site at 1× and 2×; the info image in base-product mode (165 container assertions); the
    push job rehearsed without pushing; the 4.0.81 jar on a scratch copy of `niord_gl` added
    `mail.safetynet.cancel` (`en`) and changed no row. Not yet run: CI on GitHub, `pack.yml` on GitHub, the
    production build past `guard-build` and the country-mode `image:info` (both wait for `niord-lang-da`), the
    `harbor` environment's branch policy, the Harbor push, GL staging, `npm run e2e` against a running GL stack.
    Of what waited for `niord-lang-da` and P5k (CI's set-up, `pack.yml`'s validate, the production build past
    `guard-build`, the country-mode info image, `da`'s lock entry, and the root `*_da` bundles and committed
    `da.png` going), all but the last were done in P6e (2026-10-24), the root `*_da` and `da.png` waiting for
    the kit's backend job.
- **GL's bump to 4.0.87 (P6e): built 2026-10-24** in `niord-gl` (#7, `p6/bump-4.0.87` into
  `feature/p6-gl-moves`, squashed as `0104daf`), on base 4.0.87 = niord-ng `61869e64` (C6's base half, #188, and
  P5k's kit, #190), `niord-fe-src-4.0.87.zip` sha256
  `98708124fa8187ea02059ef34dce6c85eea6f5c0fe9de668e9664b441c7ca952`. GL's `<parent>` and pin move together from
  4.0.81. `da` is pinned from GHCR,
  `ghcr.io/sifa-dk/niord-lang-da:1@sha256:ded8424ce29393e6ae0e3bcb27bae99db2eaf384361bf6718d1865eacab17efd`
  (archive sha256 `c4cac342c86ab8a97e5dcf4e6e78ac02f6e6356e9c0275e77d7e5348a97e4deb`), pulled anonymously; the
  local copy P6b used (`NIORD_PACK_DA`) is gone, and the staged Danish is byte-equal before and after. The kit's
  _What a country must do_ is carried out (`pack.yml` copied again, `pack-releases.md` with empty tables, the
  `kl` pack's 115 backend values in its backend list). C6's GL pin-bump items are bootstrap additions
  (`categories.json` new with the `da` and `kl` of the base product's 13 categories and their 11 template
  parameters; the 13 types and `NAVTEX` turned into additions; the 111 `aton` `da` added), and on a fresh GL
  database the 46 shadows apply (46 of 46, asserted). GL's frontend build is a production build and green (the
  pinned `da`, the country URL list read), the country-mode info image built and checked. GL accepts the `zod`
  advisory GHSA-mrhx-c7xc-m3w5 temporarily (`fe/audit-accepted.json`, as DK's; _GL's next pin bumps after P6e_).
  `kl` is home-only, so GL's PR into `main` published nothing. CI on GitHub: green on GL's PR into `main` and
  on its merge (2026-10-24, below). Not yet run: Image (disabled until the deploy step), staging.
  - **GL's PR into `main`, `niord-gl` #8, merged 2026-10-24** (merge commit `e8662062`, both parents kept),
    carries P6a (the backend part, #2), GL's S-124 identity (#3), P6b (#4), #5, the GRAD mirror (#6) and P6e
    (#7); it carries P6b's moved history, so it was merged with "Create a merge commit", never squashed. Its
    CI was green on the PR (2 of 2: the unit and integration tests, and the frontend job) and on the merge's
    push to `main`; the merge's `Pack` run validated `kl` and published nothing (`kl` is `"publish": false`),
    so `pack-releases.md` stays empty and no package setting is needed. The owner disabled GL's Image workflow
    before the merge (Harbor is not finished, and the merge's green CI on `main` would have started
    `image.yml`'s push to Harbor); it is enabled again at the deploy step, and its first run then. Also at the
    deploy step, the bump PR's note asks each GL deployment's sysadmin to delete GL's English `web` rows
    `footer.copyright` and `footer.link` on the Dictionaries page and check the footer in English
    (`docs/code-data-config-plan.md` §11 C6).
- **`kl` is GL's home pack only** (the owner, 2026-10-23; `niord-gl` #5, squashed into `feature/p6-gl-moves` as
  `9d93abb`): `publish: false`, since GHCR carries a pack only when more than one country is known to use it
  (§4.5); it is staged at build from `fe/packs/kl/`.
- **GL's next pin bump** (to a base version with C6's base half; that plan's §11 C6 lists it). Done (P6e,
  2026-10-24; `niord-gl` #7, base 4.0.87; its status line above): GL's 46
  shadows and its 111 `kl` `aton` descriptions then apply on a fresh database; `da` added to its 111 `aton`
  additions (the base no longer carries them), the `da` and `kl` names of the base product's 13 categories and
  their 11 template parameters (24 of each) as additions, each with both `da` and `kl`; the 13 types as
  additions (with `paramId`, `da` and `kl`; optional, as DK's) and `NAVTEX` as `typeId` plus its domain (optional);
  `GlBootstrapITTest`'s equality test (red at the bump while GL's restatement keeps the `da` the base dropped)
  and shadow count updated; a failed bootstrap of a country's fresh deployment is recreated empty (D4); the
  base-only retry is for a base-only deployment (retries removed 2026-10-24: that plan's §7.2, *No retry*). At the
  bump to a version carrying
  P5k, GL's public-site branding list is its own `fe/info/branding-unaccepted.json` (GL's move, #4: `da: []` and
  the seven `kl` entries), which the kit reads; the base product's copy stayed until P7 for its own `gl` builds
  (P7f, #211, deleted both).
  GL pins `niord-lang-da` only after DK's bump merge has published it and the owner has made the package public
  (`API-CHANGES.md`); until it did (DK's `Pack` run #1, 2026-10-24), GL's set-up, CI and its own `pack.yml`
  validate failed on the missing pin, with `NIORD_PACK_DA` the local way round. As P5e found it: the
  `<parent>` and the pin to 4.0.87 (or the version then current) in one change; `node setup.mjs --update-pins`
  writes the `da` pin, compared with DK's `pack-releases.md` line before it is committed (in P6e with DK's
  digest itself, DK's record having no line yet); `pack.yml` copied from the kit and `pack-releases.md` added,
  although `kl` publishes nothing; the `kl` backend values of its status note into `backend.<bundle>.unaccepted`;
  the bootstrap additions above; the root `*_da` stay until GL's Maven build reads the staged `niord-lang-da`,
  with the kit version that adds the backend job. Built as additions: `categories.json` (24 `da`, 24 `kl`, no
  domain links), the 13 types and `NAVTEX` turned into additions, the 111 `aton` `da` added;
  `GlBootstrapITTest` asserts 46 of 46 shadows and the additions' rows, and its equality test is replaced by one
  that holds every addition to a pinned base entry. `niord_gl`'s 32 other categories and 23 other parameters
  are DK-derived data, not GL's bootstrap (owner question). On 2026-11-04 (the owner's ruling, decision 145) the
  32 were ruled the base product's: the base archive carries them in English since CAT45, and their `da` and `kl`
  names come with country PRs into niord-dk and niord-gl on the owner's accept.
- **GL's next pin bumps after P6e** (recorded 2026-10-24; nothing changed in `niord-gl` here; each done at GL's
  bump onto 4.0.97, `niord-gl` #9, `555b7c73`, below):
  - *To a base version carrying #196 (C4b).* GL's C6 branch (`feature/p6-gl-moves`) has two ITs:
    `GlBootstrapITTest`, on its own empty container and unaffected, and `GlFiringExercisesScriptITTest`, on the
    test-jar's `ITTest` with `GlBootstrapTemplates`, which, as DK's fixture, creates only missing rows. At the
    bump `GlBootstrapTemplates` writes GL's bootstrap templates over the fixture's rows (create or replace).
    The IT passes either way today (its `fetch-firing-exercises.js` is the sample's byte-equal copy of GL's),
    but GL's shadows of base templates would never reach the IT database. **Done** at GL's bump onto 4.0.97 as
    `GlBootstrapTemplates.writeTemplates` (below, _C4b_).
  - *The `zod` advisory.* GL's bump (P6e) added an `fe/audit-accepted.json` entry for GHSA-mrhx-c7xc-m3w5,
    since it pins 4.0.87 (the orchestrator's decision, following the owner's ruling for DK, `niord-dk` #9). It
    is temporary, as DK's, and is removed at GL's bump to 4.0.91 or later, the first base version whose lock
    resolves `zod` 4.6.6 (`main`'s #200, `68ba80a7`). The entry's removal trigger names niord-ng #196, whose
    review dropped its `zod` override; what ends the entry is the lock's `zod` version, as for DK. **Done** at
    GL's bump onto 4.0.97: `fe/audit-accepted.json` is `[]`.
  - *To P7's version, 4.0.97*: GL's bump list, completed by P7h's scratch bump (2026-10-25, on GL's `main`
    `e866206` in a scratch folder, bumped as DK's; line numbers are of `e866206`), and **built 2026-10-25** in a
    clone of `niord-gl` on `p7/bump-4.0.97`, against 4.0.97 built from niord-ng `c703d061` into a scratch Maven
    repository (the zip byte-identical to the published one): `niord-gl` #9, CI green on its first head
    (`bf022b98`), on the head after the `da` pin's move (`d0612043`) and on the merge, squash-merged by the owner
    on 2026-10-25 as `555b7c73`. The merge ran no `Pack` (`fe/packs/kl/` unchanged). What was done and verified:
    - **The pins:** `<parent>` in `be/pom.xml` (`:21`) 4.0.87 → 4.0.97 and the lock's base `{4.0.97,
      d335d766…ca3e}`. The scratch bump's "0 new escape-hatch entries" came before the review of #204 added a
      key: at the bump GL's set-up was red on exactly one error, the pinned `da`'s admin bundle lacking
      `admin.batch.toast.restartRefused`, so the first commit carried 1 hatch entry for the pinned `da:1`, and 0
      for `kl`, whose 163 entries all still match. The second commit (`d0612043`) moved the `da` pin to
      `ghcr.io/sifa-dk/niord-lang-da:2@sha256:2c0cc5eda59109807a10f0f6e04091fa0213f25d831ac1c03fc2000cd6118762`
      with `node setup.mjs --update-pins da=2` and dropped the entry: 0 hatch entries for `da`, the 163 for `kl`.
      The upgrade report (4.0.87 to 4.0.97): _To do: Nothing_; _To look at_ the pinned `da`'s changed English and
      the `API-CHANGES.md` actions, all done; its one dependency change `@angular/cli` ^22.2.2 → ^22.2.4.
    - **The new key** (from the review of #204, after the scratch bump): GL's `kl` gives
      `admin.batch.toast.restartRefused` no value. It is an administration key, GL's administration app has the
      UI languages `da` and `en` only (`fe/niord-country.json`), the `kl` pack has no administration bundle, and
      the kit's hatch refuses a `kl` admin entry ("no admin build has kl as a UI language", `pack.mjs`); so
      nothing went into `fe/packs/kl/status/unaccepted.json`. GL's one action was the `da` hatch entry for its
      pinned `niord-lang-da:1` (lang `da`, app `admin`, added 2026-10-25), which went when GL pinned `da:2`,
      whose Danish DK's #10 gave. The changed English of `admin.publications.series.hint.messageReferenceFormat`
      is a warning on GL's pinned `da` (an administration key, so not on `kl`), which GL cannot accept: DK, the
      pack's home, lists it unaccepted (P5 above), and the warning goes when GL pins a `da` carrying the
      accepted text.
    - **The audio frontend:** GL deleted its copies of the two components (`fe/admin/app/promulgation/audio-edit/`,
      3 files, `audio-view/`, 4 files) and `fe/admin/app/country.providers.ts` before the first set-up at 4.0.97,
      for the same reason as DK (the stale copy silently won in the scratch set-up too); `fe/admin/` holds only
      `assets/`; its three `audio-broadcast*.ftl` stay GL's (§6 P7).
    - **The `zod` acceptance:** removed from `fe/audit-accepted.json` (`[]`), as DK's; the build's audit 0 high,
      0 accepted. GL's READMEs' three lines are reworded to "empty today", as DK's `fe/README.md:24` words its
      empty file: `README.md:35`, `:168-169` and `fe/README.md:29` (the table row).
    - **The `da` pin:** `fe/README.md`'s sentence names the pin of tag 2 and says GL's pin of tag 2 is compared
      with the line DK's record adds for tag 2 at DK's next change; tag 1's digest
      (`sha256:ded8424c…7efd`), the pin until 4.0.97, equals the line `pack-releases.md:15` records.
    - **The bootstrap:** GL's `Audio` row stays byte for byte (`git diff -- be/bootstrap` empty) and restates the
      base product's `Audio`, with no domain link: within the first bootstrap its `kl`, priority 10 and
      `MANDATORY` replace or fill the base's `en`, 50 and empty requirement (D1 as overruled by the owner on
      2026-10-24; built in P7-bs); whether it links `niord-gl-nw` stays the open question below. Against
      4.0.97's `niord-base`: `GlBootstrapFolderTest` 7 of 7 (`:176-177` holds) and `GlBootstrapITTest`'s
      bootstrap test green with its `kl` at `:304-305`; the two assertions that prove the fill were added after
      it (`:309-312`: priority 10, "GL's priority 10 replaces the base product's 50", and `MANDATORY`, "GL's
      MANDATORY fills the base product's empty requirement"), green on a fresh database;
      `glsAdditionsNameThePinnedBaseProductsEntries` was red at `:447` only, as DK's, and was rewritten to expect
      `[navtex, audio]` (`:471`), `audio` allowed to carry values with its `serviceId` equal to the base entry's
      and no domain assertion for it, with its javadoc; the `paramIds` assertion's message reworded as DK's. No
      code change: test code only.
    - **C4b:** `GlBootstrapTemplates.addMissingTemplates` became `writeTemplates` (per GL path: none → create,
      content differs → replace, equal → nothing), the base product's half dropped; `BASE_BOOTSTRAP` stays, read
      by both ITs and written to the IT database by none. Measured with a temporary count: 0 of GL's 46 shadows
      held GL's content in the IT database with the old create-only fixture, 46 of 46 with `writeTemplates`. The
      count was replaced by a permanent assertion,
      `GlFiringExercisesScriptITTest.glsTemplatesHoldGlsContentInTheItDatabase` (`:103`), which requires each of
      GL's templates to hold GL's content in the IT database (red with a temporarily create-only
      `writeTemplates`, 48 paths, green with the real one); the firing-exercises test is green either way.
    - **Documents:** every sentence of the two READMEs that described the same-run rule or a retry:
      `be/bootstrap/README.md:37-41`, `:51-55`, `:55-57` and `README.md:259-261` (now the *No retry* rule);
      `be/bootstrap/README.md:27`, `:43` (`Audio`); `README.md:29`, `:35`, `:153` and `fe/README.md:18`, `:47`
      (the audio files and the seam file); the javadocs as DK's; the comments that said `niord-ng` keeps GL's
      bundle or frozen copy until its P7: `.github/workflows/image.yml:21-22`, `README.md:236-237`. And, beyond
      the list: `be/pom.xml:248-254` (the comment above the clean and unpack executions: the unpacked base
      bootstrap is read by both ITs and written to the IT database by none), `be/bootstrap/README.md:7-13`
      (Audio's values; the claim refusing a database that holds data) and `:97-106` (the checks), `README.md:35`
      and `fe/README.md:27`, `:114-119` (the hatch), `README.md:103-119` (the IT description), `README.md`'s
      *Moving to a newer base version* (a key the pinned `da` lacks; its changed English a warning GL cannot
      accept), `README.md:255-259`, `fe/README.md:56-57` (the Windows `image:info` limit, which P7a removed),
      `fe/README.md:16` (`4.0.97's`), and GL's `CLAUDE.md:42-47` (the hatch rule's exception for a key the pinned
      `niord-lang-da` lacks, §4.6 step 3).
    - **Checks:** as DK's: `niord-ng`'s Info CI checks no GL artefact any more, so GL's image job is the only
      check of its image, and the removed country-marker assertions are GL's to carry if it wants them. At the
      bump, on Windows: set-up (a fresh fetch through `../be/mvnw`, the zip verified), `npm ci`, `npm test` (the
      footer's spec, 8 of 8), `npm run lint` (`--max-warnings 0`), `npm run e2e -- --list` (10 tests in GL's two
      spec files), `npm run build` (audit 0 high, 0 accepted; initial 904.47 kB; check-build green; one Angular
      budget warning, niord-info's styles 54.02 kB against 53 kB, as in P7h's scratch build) and
      `npm run image:info` in a browser (the labels read back equal, the layout check 61 of 61 with the width
      check, the container test 165 of 165); after the `da:2` pin set-up, `npm test`, lint, build and
      `image:info`'s container test again. The whole backend suite (`clean verify`: GL 27 unit and 4 IT; the
      group 114 unit, 1 skipped, and 14 IT), the group check, the IT report check (`--min 10`) and the override
      check (the logo only, against `niord-base-4.0.97.jar`). Not yet run: the Image workflow (disabled) and
      staging.
    - **The public site's specs:** the Nuuk cases P7b moved to `America/Halifax` (message-source, the print view,
      the publications' day boundaries, `date-format.service`, current-year) and the administration app's
      desk-zone cases; GL may add its own Nuuk specs (an item in GL's backlog). `America/Nuuk` stays only in the
      public site's `zoned-date.spec.ts` among the specs (the two documents that name it are allow-listed,
      §4.11 rule 6; D18).
    - **Nothing to do:** as DK's (`niord-web-admin` `en` only, `image.yml:21-22`'s comment reworded and
      `README.md:190` staying true as a record; `paths:check` rules 4-5 and `pack.mjs --frozen` gone; the
      generated `angular.json`'s two ignore entries).
    - **Carried over to GL's own backlog** (removed in P7e, D29), `niord-gl`'s new `docs/backlog.md`, linked from
      its README: the translation tracking lists (the `kl` and `da` lists and the branding list; go-live waits
      for the professional `kl` translation); the brand and wording of the new authority (GL's own logos, links
      and host name, `FOOTER_LOGO` and `FOOTER_LOGO_ALT` per language, cookie statements that copy Denmark's and
      cite the Danish Cookie Order, no `backendHosts`); the zone of the Greenlandic domains (`niord-gl-nw` has
      zone UTC in the test data; `America/Nuuk` on every domain that owns a Greenlandic series); an
      accessibility statement for each public host; the Kalaallisut placeholders and their go-live check (the
      `kl` accept path, the branding list's non-empty check, the stale rows of the translator's workbook,
      `SOURCE_DATE_FORMAT` and the month names filed wrongly, the screen vocabulary differing from
      `message_kl.properties`); the pending professional Greenlandic translations; and the findings of
      "Greenland's colours and font are provisional" (`--niord-header-active` at 2.88:1, the selection wash and
      rail too close to the attention ones, ΔE 6.4, the recorded contrasts of `--niord-fg-subtle` at 4.23:1 and
      surface-400 at 2.66:1, the cancelled and expired badges at 4.04:1), to check against P6b's theme. And GL's
      own `America/Nuuk` specs. The D23 asset rules, carried over as DK's were, the kit checks since P8a's C3
      (NG-5, NG-6, #218), and GL's `main` `555b7c7` passes every one of them (checked read-only on
      2026-10-25).
  - *To the version B4's merge publishes* (recorded 2026-10-25; nothing changed in `niord-gl` here): the SECOM
    page's reconcile toast `admin.secom.toast.reconciled` gains the placeholder `{{failed}}`
    (`fe/country-kit/API-CHANGES.md`, `Unreleased`). An administration key, so `kl` gives it nothing; GL's pinned
    `niord-lang-da:2` lacks the placeholder, so GL lists the key in `fe/missing-keys-accepted.json` for that pin
    (staging then ships the English text) until it pins a `da` that carries it, DK's `niord-lang-da:3` (P5
    above).
- **GL's S-124 identity, answered** (owner, 2026-10-22): GL, as DK, publishes under the producer code `DK01`
  (was `DK00`) and its English `msg.responsible.agency` names the Danish Emergency Management Agency (was
  `Unknown Agency`; `niord-gl` #3, `6b80842`); the Danish and Greenlandic texts follow with P6b's packs.
- **Open owner questions** (that plan's §11 C6): GL staging's pre-C5 `S124Dataset` action, at the deploy
  step; the domains, series and reports a fresh GL deployment starts with, and whether GL's `Audio` is linked
  to `niord-gl-nw`; since P6e also `niord_gl`'s 32 other categories (DK-derived; GL's bootstrap carries the base
  product's 13 with their `da` and `kl`) and whether the 10 template categories link to `niord-gl-nw` (carried
  with no link, which offers them in every domain). On 2026-11-04 (the owner's ruling, decision 145) the 32 were
  ruled the base product's: the base archive carries them in English since CAT45, and their `da` and `kl` names
  come with country PRs into niord-dk and niord-gl on the owner's accept.
- The Claude Design theme merged into `tokens.gl.css` (§4.7); Lato 400, 400 italic, 700, 900 vendored with
  `OFL.txt`; GL's bundle baseline.
- **Move steps P4's dry run found:** as P5's for the footer spec, `tokens.active.gl.css` and `language.config.ts`;
  `gl-model-languages.spec.ts` and `admin-model-language.spec.ts` import their fixtures from `@niord/admin/e2e`
  instead of `./auth/fixtures`, and `admin-model-language.spec.ts`'s `ParamType`, imported from the base
  product's internals, becomes a local type (DK's `language-switch.spec.ts` needs no change); the `kl` pack's
  backend half gains a passing `navwarn.date.html_day_suffix`; the 163 catalog keys it lacks are listed in
  `fe/missing-keys-accepted.json` (the escape hatch, §4.6: the default language serves until GL's translators
  supply them; no Kalaallisut value is invented); of GL's six keys outside the catalog the five mail keys become
  country texts (`texts/kl/mail.properties`) and `pdf.attachment.title`, read by nothing, is dropped (§6 P4,
  _Found on the way_); GL's production build reads `da` from `niord-lang-da` through P5k's OCI client (done in
  P6e, 2026-10-24: `guard-build` passes, the build report's `packs.da` names the pin; until then a local copy,
  `NIORD_PACK_DA`, that `guard-build` refused); GL's public site fails the absolute-URL rule
  on the same addresses as DK's (P4's dry run), settled by P5's choice: GL's own `fe/build-urls-allowed.json`,
  read by P5k's kit. No allow-list entry of
  `country-words.json` ends in P6 (P7b, #205, removed the `until` P7 entries).
- GL's pipelines split as P5's: `image.yml`'s unprivileged `frontend` and `backend` jobs and a `push` job that
  only verifies and pushes (`main` only, `harbor`); `ci.yml`'s `frontend` job; `pack.yml`, `dependabot.yml` and
  `renovate.json` the kit's templates; `CODEOWNERS` as DK's. GL's CI was red one step earlier than DK's: at
  set-up, until P5k's OCI client was pinned and `niord-lang-da` published, then on the absolute-URL finding;
  `pack.yml`'s validate job was red at set-up for the same reason, on every pack push and publish dispatch.
  Both cleared in P6e (2026-10-24): CI green on GL's PR into `main`, `niord-gl` #8, and on its merge, and
  `pack.yml`'s validate job green by hand and in the merge's run (`pack.yml` 4.0.87 copied again).
- Verification: as P5; `woff2` files only in GL's `media/`; a first visit's network log shows only
  same-origin requests; the GL public site at 1× and 2×. Locally (2026-10-23) the info image ran in
  base-product mode only (the container test and the layout check over GL's dist); the country-mode
  `image:info`, its labels and separation run waited for `niord-lang-da`, since `image:info` refuses a workspace
  set up from a local pack. P6e (2026-10-24): the country-mode info image built and checked (on Windows through
  a wrapper for the build-report label's length: the image equals the dist, the separation check, the layout
  check 61 of 61, the container test with `--browser` 165 assertions); 16 `woff2` only under `media/`; the
  served files byte-equal to P6b's build but for the build report and `ngsw.json`'s timestamp, so P6b's network
  log and screenshots stand.
- Done when: GL's frontend is built only in `niord-gl` and wears its theme (frontend part); C6's done-when
  holds for GL (backend part). For GL on 2026-10-23 the first half holds in `niord-gl` on 4.0.81 (niord-ng kept
  its frozen copy until P7 deleted it, P7f, #211), with set-up from a local `da` copy and check-build red on three findings on two
  addresses (the authority's site twice, the accessibility statement) until GL pins P5k's kit and
  `niord-lang-da`; C6's done-when holds for GL on 4.0.81 (the whole suite, the group, the override check).
  The frontend part holds since P6e (on 4.0.87, `da` pinned, check-build green), with CI on GitHub green on
  GL's PR into `main`, `niord-gl` #8, and on its merge (2026-10-24, `e8662062`); C6's done-when holds for GL
  on 4.0.87 too (46 of 46 shadows asserted, the whole suite, the override check). Image and staging wait for
  the deploy step. On 4.0.97 (2026-10-25, `niord-gl` #9, `555b7c73`, CI green) the frontend part holds locally
  (set-up, `npm ci`, test, lint, the build with check-build green, `image:info`) and C6's done-when holds for GL
  (the whole suite 27 / 114, 1 skipped / 4 + 14, the group check, the override check, 46 of 46 shadows
  asserted); Image and staging: not yet run.

**P7 — the base product lets go (PR B).**
- Delete the DK/GL configurations, country folders (except the audio editor and viewer, which move into the
  base product, below), `tokens.dk/gl`, the `da`/`kl` packs, `ui-apps/info/i18n-status/` (its `branding-unaccepted.json` holding only GL's `kl` entries, which `niord-gl` has its own copy of since P6), the country build
  jobs in `fe-ci.yml` / `info-ci.yml`, the `dk`/`gl` bundle zips in `publish.yml`, `start-local.sh
  --dk/--gl`, `start-local-dk.sh`, `start-local-gl.sh`, the e2e specs P5 and P6 moved (with `app-breadth.spec.ts`'s
  two footer legal-document tests; the suite's DK defaults already became environment variables with no country
  default in P3c2); the `www.was.digst.dk` of `map-attribution.spec.ts` and the `dma-` message-series ids of
  `scripts/info-container-test.mjs` rewritten to neutral values; switch on the base product guard (§4.8) over the whole of
  `fe/`, the specs and comments its `until: P7` entries allow rewritten to neutral values (`example.org`) and
  those entries removed; the docs of §7.
  Beside its word list the guard takes over, as patterns with a reason each, what the word list cannot catch
  (found by the review of `niord-ng` PR #179): a Danish locale chosen in a template (`lang="da"`,
  `locale=da`; `bootstrap/templates/tmpl/object-adrift.ftl` had two, fixed there), and Danish and Faroese
  place names in base product data (`Skagen`, `Blåvand`, `Hammer Odde`, `Tórshavn`, the DGPS stations the
  base product's bootstrap carried until that review; `docs/code-data-config-plan.md` §11 C5). The frozen
  release folder `releases/2026-10-21-templates-from-database/` keeps its copy of `object-adrift.ftl` and is
  allow-listed as a release record.
  A country developer runs its frontend from its own repository — from P5 for DK, P6 for GL — with
  `node setup.mjs && npm ci && npm run start:admin` (§5 step 9), until the country launcher comes (P8; P9's
  *the same in a country repository*).
- **Audio promulgation joins the base product** (the owner, 2026-10-24: it was meant for the base product, and
  the split had left it with DK and GL). The backend is niord-base's already (`AudioPromulgationService`,
  `AudioMessagePromulgation`, `AudioMessagePromulgationVo`). P7 moves the administration app's audio editor and
  viewer (`country-specific/dk/promulgation/audio-edit/` and `audio-view/`, byte-identical to GL's copy) into
  the base product instead of deleting them, registered by the base product for the `serviceId` `audio`
  (`PROMULGATION_EDITORS`, `PROMULGATION_RENDERERS`, §4.9). Their English texts are in the base catalog already
  (`editor.promulgation.audio.*` in `languages/en/i18n/admin/en.json`), and nothing is translated: their Danish
  is in `niord-dk`'s `fe/packs/da/i18n/admin/da.json` already (published as `niord-lang-da:1`), no `kl` pack has
  an administration bundle, and GL's administration languages are `da` and `en`. The base product's bootstrap `promulgation-types.json` gains
  an `Audio` type (`typeId` and `serviceId` `audio`, English, no domain link) beside `NAVTEX` and `Twitter`:
  fresh databases only, no deployment's existing rows change (`docs/code-data-config-plan.md` §7.1). Since
  `PromulgationType.typeId` is unique and the sample's SQL refuses a base it was not made on, the sample's
  `Audio` restates the base product's values (`en`, 50) with its link to `niord-nw`, like its `NAVTEX` and
  `Twitter` (D7): within the first bootstrap a later archive's values replace the base's (the owner's ruling of
  2026-10-24, D1), and `regen-sample.sh` refuses a changed base row, so the sample's `Audio` stays English by
  equality. The sample is regenerated (`regen-sample.sh`) and the IT fixture after it
  (`regen-test-seed-postgres.sh`). DK's and GL's
  own `Audio` bootstrap rows restate the base product's since their bumps onto 4.0.97 (`niord-dk` #10, `niord-gl` #9), and on a fresh database
  their values apply: DK's `da`, with its `niord-nw` link, and GL's `kl`, priority 10 and `MANDATORY`. In
  that same bump each deletes its copies of the two components and their `country.providers.ts` registration,
  since a copy left in place silently replaces the base product's: the registries are Maps,
  `provideCountry()` comes after the core registrations, and nothing fails (§6 P5 and P6, the pin-bump items). DK's
  mailing-list template `audio-broadcast.ftl` stays DK's, and GL's `audio-broadcast*.ftl` GL's.
- Verification: the base product CI green with the guard on; §5 run from scratch for a fictitious country, in an
  empty directory against the published zip, by the guide alone.
  State (P7h, 2026-10-25): met locally; staging at the end. The guard is on in `test:guards` (tree mode 0
  findings and 0 unused entries; `base-product-tree.test.mjs` green) and in `CountryWordsTest`; §5 was run by the
  guide against a `kit:zip` of the branch (D27, _As built_). State (2026-10-25, after the merge): Backend,
  Frontend and Info CI green on #204 (on `0409350e` and on `a8db0b32`, 12 of 12 each) and on the merge
  `c703d061`, published as 4.0.97 (_As built_). Not yet run: §5's steps 1-2 and set-up and build against the
  published zip (they need a read-only Packages token on the workstation, which there is none of yet); steps
  10-11 and every staging item at the deploy step.
- Done when: `niord-ng` is country-unaware and Phase 8's own criterion holds
  (`be/docs/cicd/implementation-plan.md`: an FE-only base product merge reaches staging through the
  identical chain, and a BE+FE API change is demonstrated as **one PR** — still one, because the backend and the
  frontend ship from `niord-ng` as one release under one version, §3.1).
  State (P7h, 2026-10-25): met locally; staging at the end. Country-unaware: `base-product-tree.test.mjs` and the
  guard's tree mode over `fe/`, `CountryWordsTest` over the backend's shipped code, and P7g's grep over the tree
  for `country-specific/dk`, `country-specific/gl`, `build:dk`, `start:dk` and "DK's copy" (run again in P7h
  outside the two plans), which finds dated records (`docs/audio-promulgation-language-findings.md` (moved to niord-gl in P8a: deleted
  here in NG-13, copied by GL's P8a change into its `docs/` from niord-ng's history at `8bffad2a`),
  `docs/publications-port-inventory.md`, `fe/docs/niord-info-integration-plan.md`) and test fixtures only (the
  structural test's planted path, D31's synthetic `contents:check` configurations). Phase 8's criterion (D28): "one PR" is #204, whose diff spans `be/` (79 files: the bootstrap's
  `Audio`, the sample, the IT fixture, `CountryWordsTest`, P7-bs) and `fe/` (303 files) under one publish; the
  FE-only half is met locally (`kit:zip`, the example harness, `release-workflows.test.mjs`'s publish-step case),
  with #200 (FE-only, published as 4.0.91) as the publish-side precedent; the staging half waits for the deploy
  step. #204 is merged (`c703d061`) and published as 4.0.97 under one version, its backend and frontend
  together; DK and GL run on it since their bumps (`niord-dk` #10, `niord-gl` #9, P5 and P6 above).
- **Decisions** (2026-10-24, taken with the recommended options and recorded, the owner's instruction to decide
  and log; any can be overruled):
  - D1, the audio language: **overruled by the owner on 2026-10-24.** Taken first as: the base product's
    `Audio` is English, and a country's or the sample's `Audio` row adds only its domain links, so a fresh DK or GL
    database got an English `Audio` until an operator set its language (owner question 1). The owner ruled: "a
    first bootstrap needs to be seen as a single united first time deploy following the normal rule of country
    overwrite - so whatever the country has in settings, configurations, texts, bootstrap data, etc takes
    preceedence … BUT (and thats a big but) it must NEVER be possible to bootstrap a deployed database/system.
    There must be safeguards against this if not already" (`docs/code-data-config-plan.md` D4 as amended, §7.2,
    *Within one run*; built in P7-bs). So the base product's `Audio` stays English, and within the first
    bootstrap a country's `Audio` values replace it: a fresh DK database's `Audio` is `da` with its `niord-nw`
    link, a fresh GL database's `kl` with priority 10 and `MANDATORY`; the sample's stays English by equality (D7).
  - D2: the base `Audio` has priority 50 and is appended after `Twitter`, so the fixture's ids stay `navtex` 1,
    `twitter` 2, `audio` 3, and the order NAVTEX, Audio, Twitter the priority ITs assert holds.
  - D3: the viewer and the editor go beside the core siblings (`features/messages/promulgation/audio-view/`,
    `features/editor/promulgation/audio-edit/`), moved with `git mv` from DK's copy and GL's identical copy
    removed with `git rm`; relative imports, the keys unchanged, no admin plugin.
  - D4: DK's and GL's frozen `country.providers.ts` register nothing from the audio move on, until their folders go.
  - D5: an example-country promulgation editor keeps a consumer for the four admin exports that lose theirs
    (`PROMULGATION_EDITORS`, `PromulgationEditorContext`, `MessageStatus`, `PlainTextToHtmlPipe`).
  - D6: the audio frontend first, then the bootstrap and the sample in one sub-PR (two commits, in that order),
    then the IT fixture, so the committed sample never refuses a fresh base between sub-PRs.
  - D7: the sample archive's `Audio` restates the base values (`en`, 50) with its `niord-nw` link, as its
    `NAVTEX` and `Twitter` do. Since D1 was overruled (2026-10-24) the reason is another: within the first
    bootstrap a value the sample gives would replace the base's, and `regen-sample.sh` refuses a changed base row,
    so the sample restates the base values equal (a `da` there would now replace the base's English).
  - D8: the documents are brought to the end state in one sub-PR before the deletions, so `docs:check` stays
    green through them.
  - D9: `fe/docs/country-deployments.md` is trimmed to the base product's side with a pointer to the kit's
    README and §5; P8 writes the guide in `fe/country-kit/docs/`.
  - D10: Info CI loses its 14 country steps and *Install Chromium*. The Example country job's nginx, container
    and layout checks run on pull requests only (`fe-ci.yml`'s push paths exclude `fe/ui-apps/info/**`), so on
    `main` a merge confined to the public site is re-checked by Info CI's build checks alone; accepted, and
    recorded in §4.8 by P7's record.
  - D11: `publish.yml` keeps `niord-web-admin`, with the classifier `en` only.
  - D12: `./start-local.sh --dk` and `--gl` refuse with exit 2, naming the country repository's command.
  - D13: `paths:check` rules 4-5 (the country swaps) are deleted with their tests.
  - D14: `pack.mjs --frozen` is removed.
  - D15: `zip-include.json`'s dk/gl excludes are removed, and `workspace.mjs`'s `OVERLAYS` loses its
    `assets/dk/**` and `assets/gl/**` entries (it keeps `assets/default/**`, `workspace.mjs:48`, applied at
    `:142-143`), in the change that adds the structural guard.
  - D16: the guard gets a tree mode over every git-listed `fe/` file but the list itself; unused allow entries
    are checked in tree mode only, and the zip mode reports hits only.
  - D17, neutral values: `example.org` hosts (the login host another origin, `login.example.org`) and "Example
    Maritime Authority"; `da-DK` becomes `da`, `kl-GL` `kl`, and the kit's region-form fixtures use `de-DE`; the
    series ids `xx-nw`, `xx-nw-local`, `xx-nm`; fixture roots `urn:mrn:iho:country:xx`-style MRNs with
    fictitious sea names. Danish stays where it is a sample language (§4.8).
  - D18, time zones are test inputs, not country identity (like `Europe/Copenhagen`, §4.8): a case that depends
    on Greenland's rule (UTC−2 on the EU summer rule, a spring gap that ends a local day, which no zone outside
    Greenland reproduces) keeps `America/Nuuk` with a permanent allow entry, and `America/Godthab` would become
    `America/Nuuk` in such a case — as built (P7b) no `America/Godthab` case depended on the rule, so every one
    became `America/Halifax`, and `America/Nuuk` stays only in the public site's `zoned-date.spec.ts` among the specs (the two documents that name it are allow-listed, §4.11 rule 6); every other use moves (an incidental west-of-UTC zone to `America/Halifax`, its
    expectations recomputed with Intl; `Atlantic/Faeroe` to `Europe/Lisbon`; place names to fictitious ones).
    The comment that Greenland "abolished DST in 2023" is corrected: since 2024 UTC−2 with the EU summer rule.
  - D19, guard hits in documents outside the zip: `fe/docs/backlog.md`'s zone item (the inclusive day end in
    the spring gap) keeps its zone, allowed until the bug is fixed; `coding-standards.md`'s zone lines are
    rewritten per D18 and `testing.md:150` per D17; allowed with reasons: the licence holder (permanent), DK's go-live records and
    `DATA-MODEL`'s `$id` (until DK's go-live), the integration plan (until P8; made permanent in P8a (NG-13): a dated record, kept as written). GL's zone item goes as GL's.
  - D20: the patterns (a Danish locale, place names) apply to the backend half only, the JSON-escaped quote form
    included.
  - D21: the backend half is a niord-base unit test outside the country build group, with its own list
    (`docs/code-data-config-plan.md` §11 C5's words, rule and allow-list).
  - D22: §4.8's structural half is a test in `test:guards` (`scripts/base-product-tree.test.mjs`).
  - D23: the asset rules the deleted base specs applied to DK's and GL's frozen copies go to a kit backlog item.
    Done in P8a's C3 (NG-5, NG-6, #218). The rules are in checkBranding (`fe/country-kit/lib/branding.mjs`,
    rule 6), as data in `branding.schema.json` where it can carry them (`width` and `height`, `forbids`,
    `namesPreferences`), except the page rule, which is check 10 of check-build (`lib/build-checks.mjs`,
    `indexProblems`) over the built `dist/niord-info/browser/index.html`, because it compares the page with the
    built bundle after packs and text overrides. The keys the cookie statements name are one list,
    `ui-apps/info/src/app/core/services/preference-keys.json`, which the public site's preferences service
    imports, `@niord/info/api` exports (`PREFERENCE_KEYS`, `PREFERENCE_PREFIX`) and the kit reads. The example's
    page (`fe/country-kit/example/fe/info/app/index.html`) is `lang="zz"` with the generated `zz` `SITE_TITLE`,
    and its footer logo (`fe/country-kit/example/fe/info/assets/branding/logo-gray.png`) is redrawn at 220×44.
    DK's and GL's `main` pass every rule.
  - D24: `API-CHANGES.md`'s stale `Unreleased` is renamed to its 4.0.87 heading and a new `Unreleased` opened,
    which each later sub-PR writes into; the PR into `main` keeps `Unreleased`, and the next kit change names it.
  - D25, the long label: `image:info` writes the two labels as one `LABEL` instruction into the context's copy of
    the Dockerfile, continued over lines of at most 16 KiB (Docker refuses a line over 64 KiB), and reads them
    back with `docker inspect` after the build; a line never ends in an escaped backslash, which Docker would
    not read as a continuation; the §4.11 rule 2 contract is unchanged; not by digest.
  - D26: Info CI's change filter is kept, in its three copies.
  - D27: §5 is verified by a dry run by the guide against a `kit:zip` of the branch, with steps 1-2 and set-up
    and build repeated against the published zip after the merge.
  - D28, Phase 8's criterion: "one PR" is shown by P7's own PR into `main`; the FE-only half is *met locally;
    staging at the end*, with #200 as the publish-side precedent.
  - D29: the DK- and GL-only items of `fe/docs/backlog.md` are removed and listed in P7's record.
  - D30: the developer tools that assume DK's data (`be/scripts/seed-dev-database.mjs`,
    `capture-publication-fixtures.mjs` and `capture-legacy-publication-estate.mjs`;
    `fe/scripts/perf/publications-requests.mjs`) stay and are named in P7's record. So does
    `fe/docs/publications/go-live/`, DK's cutover material, until DK's go-live: the guard allows its words file
    by file (`GO-LIVE-PROCESS.md`, `PRODUCTION-CUTOVER-RUNBOOK.md` and `scripts/lib.sh`, each for `dma.dk` and
    `t-dma`), so a new file in the folder is reported, and the folder moves to `niord-dk` at DK's go-live (a
    follow-up; after the review of #204).
  - D31: `contents:check`'s cross-country rule stays a generic rule, with its synthetic dk/gl fixtures.
  - D32: `image:info --package-only`, `layout:info` and `test:container:info` stay as manual tools for the base
    build, documented in `fe/CLAUDE.md`.
  - D33: the audio move keeps its history in the branch: a pure-rename commit first, the edits after it. A merge
    commit was recommended for the PR into `main`; the repository allows only squash and rebase merges, and the
    owner chose a squash merge of #204 (2026-10-25). On `main` the moved files' history then follows only as far
    as git's rename detection finds them in the one squashed commit, and the sub-PRs' commits and their SHAs as
    recorded stay reachable through `refs/pull/204/head` and each sub-PR's `refs/pull/<n>/head`.
  - D34 (owner question 2 until 2026-10-24, now 1): `pdf.nm_almanac.title` "EfS A" and the 56/11 map centre stay unchanged in P7, since a
    changed repository default changes every deployment that never set it: first a country sets its own value,
    then the base default may change; a bootstrap text changes fresh databases only. Ruled on 2026-11-03 (the
    owner, decision 138): the 56/11 centre stays as the generic demo's; `EfS A` gets a neutral English value once
    DK and GL carry their own `en` value of the key, which neither does yet (niord-dk `d8a1046`, niord-gl
    `ffca4eb`: their `be/src/main/resources/defaults/country/texts/en/pdf.properties` set only
    `pdf.nm_almanac.desc`), so the base default is unchanged; `organisation` was already the neutral `Niord`.
    On 2026-11-03 (the owner, decision 141) the base product takes the neutral English `NtM Almanac`
    (`defaults/base/texts/en/pdf.properties:18`, `bootstrap/texts.json`; fresh databases only). DK and GL keep
    `EfS A` as their own English value, added by niord-dk #14 (`1a4d3be4`) and niord-gl #12 (`113ed0a4`), merged
    on 2026-11-03 before the base product's change, so their English almanac PDF keeps its title on a fresh
    database at their next base bump.
- **Open owner questions** (non-blocking; the audio language, question 1 until 2026-10-24, was answered by the
  owner's bootstrap ruling, D1):
  1. "EfS A" and the 56/11 map centre (D34): kept, or neutral values now (the bootstrap text for fresh databases
     only) (answered 2026-11-03, decisions 138 and 141: D34 above).
- **Sub-PRs**, all from `feature/p7-base-lets-go`, cut from `main` at `25f75068`; P7-be runs alongside P7a-P7c,
  its files disjoint from the frontend chain's, and P7-bs is stacked on P7-be:

  | Sub-PR | SPs | What |
  |---|---|---|
  | P7a | SP0 + SP1 | the decisions in the plan; the kit follow-ups: `image:info`'s labels as one `LABEL` instruction with line continuations in the context's Dockerfile copy (at most 16 KiB a line; Docker refuses 64 KiB), read back with `docker inspect`, measured with a report of 234 KiB (Docker 29.5.3: 17 lines, the longest 16 KiB, read back equal); a call that cannot start named by its code and message |
  | P7b | SP2–SP5 | the guard's `until: P7` test data and the published test scripts, to neutral values |
  | P7c | SP6 + SP7 | the example's promulgation editor; audio's editor and viewer join the base product |
  | P7d | SP10 + SP11 | no caller of a country build left (CI, publish, `verify-local`, the launcher); the e2e specs P5 and P6 moved |
  | P7e | SP12 | the documents to the end state |
  | P7f | SP13 + SP14 | the country trees, configurations, tokens and packs go |
  | P7g | SP15 | the base product guard over the whole of `fe/` |
  | P7h | SP17 | P7's record and the §5 dry run |
  | P7-be | SP8 + SP9 + SP16 | `Audio` in the base bootstrap, the sample and the IT fixture regenerated; the guard's backend half |
  | P7-bs | the owner's ruling of 2026-10-24 | branch `p7/be-first-bootstrap`: within the first bootstrap the country's values win; a bootstrap refuses a database that is not empty; no in-place retry and no takeover; a bootstrap job is never restarted, the one change on a running system's endpoint, limited to bootstrap jobs, for the owner's review (`docs/code-data-config-plan.md` D4 as amended, §7.2, §11 C6); kept by the owner's ruling of 2026-11-03 (decision 138) |

- **As built** (2026-10-24 to 2026-10-25). Each sub-PR was merged into `feature/p7-base-lets-go`, its commits
  kept, after `./verify-local --base origin/feature/p7-base-lets-go` passed on a Windows workstation (the times
  are the whole run's; a backend part shows only the 10 known Windows-only failures); PR #204 carries them into
  `main`, squashed, as the owner chose on 2026-10-25 (D33). Every SHA recorded below is the branch's (#204's
  head `0409350e` and its ancestors, or a sub-PR's head before its rebase where so named): on `main` the one
  squashed commit holds them all, and they stay reachable through `refs/pull/204/head` and each sub-PR's
  `refs/pull/<n>/head`. **Merged** by the owner on 2026-10-25 at 13:43Z as the squash `c703d061`. Backend,
  Frontend and Info CI on the merge are green (Backend CI's test jobs reused the PR's tested fingerprint, so
  they skipped), and `Publish 4.0.97 after Frontend CI` (run 40199635888) is green, tagging `v4.0.97` with
  `niord-fe-src-4.0.97.zip`, sha256 `d335d766fa2f67a12d70def78b0572acee32bb164f3d173722217dcf1ecbca3e`
  (the merge's Backend and Info CI each published a version before it, 4.0.95 and 4.0.96, with no release).
  DK's and GL's bumps onto 4.0.97 are done: `niord-dk` #10 (`9f09d195`) and `niord-gl` #9 (`555b7c73`), §6 P5
  and P6.
  - **P7a, #203** (SP0 + SP1): the decisions block, the owner questions and the sub-PR table above, and the plan
    corrections that are decisions (the audio language, nothing to translate, the silent replacement by a stale
    country copy, PR B as P7 alone); `image:info` names a call that cannot start by its code and message, and
    writes the two labels as one `LABEL` instruction read back with `docker inspect` (D25), so GL's
    36,601-character report no longer exceeds Windows' command line — Docker 29.5.3 built and read back a 234 KiB
    report in 17 lines and a 6 KiB label broken every 64 bytes in 113; `API-CHANGES.md`'s stale section named
    4.0.87 and a new `Unreleased` opened (D24). Red first: the spawn errors, a 40 KB report's 49,057-character
    argument, the round trip, a tampered read-back and the escaped-backslash line ending (`defaultRun`'s `ENOENT`
    case is a lock, not a red case). `verify-local` PASS in 20m04s on `a2927a00`.
  - **P7b, #205** (SP2–SP5): the 23 `until: P7` entries removed and what they allowed rewritten (D17, D18):
    `example.org` hosts and "Example Maritime Authority", `da` and `kl` for `da-DK` and `kl-GL` in 26 spec files
    and the test helper, `de-DE` for the kit's region-form fixtures, every `America/Godthab` to `America/Halifax`
    with its expectations recomputed with Intl (a deliberately broken field failed 8 of the cases), `Atlantic/Faeroe`
    to `Europe/Lisbon`, `America/Nuuk` kept for the public site's Greenland-rule cases; the published test
    scripts' series ids and fixture roots (§4.8). Red first: the guard over the old tree with the new list, 109
    hits in 45 files, and the rewritten layout-check case. Suites: 5,509 and 920 tests. `verify-local` PASS in
    20m16s on `8d4ab403`.
  - **P7-be, #206** (SP8, SP9, SP16), four commits: `Audio` in the base bootstrap (English, priority 50, active,
    no domain link, after `Twitter`, so the ids stay navtex 1, twitter 2, audio 3, D2); the sample regenerated,
    its `Audio` restating the base values with its `niord-nw` link (D7); the IT fixture regenerated (each dump's
    one change the audio row's language, `da` to `en`); `CountryWordsTest`, the guard's backend half (D20, D21:
    31 allowed and 55 stripped lines in 17 files, against SP16's estimate of 81-84 lines in 20). Both
    regenerations byte-identical in a second run; 108 targeted ITs. A developer's existing `niord_sample` needs
    `./start-local.sh --sample --reset`. `verify-local` PASS in 9m37s on `4a4ae502`: 5,181 unit tests and 3,361
    ITs.
  - **P7c, #207** (SP6, SP7): a rename-only commit (`10614d48`: DK's 7 audio files beside the core siblings,
    D3, D33; `git log --follow` reaches #184 and earlier), then the edits: relative imports, neutral comments,
    the registration in `core-renderers.providers.ts` and `core-editors.providers.ts`, GL's identical copies
    removed, DK's and GL's frozen `country.providers.ts` emptied (D4), the example's `xx-bulletin` editor (D5),
    and a `promulgation-list.spec.ts` case showing that a later registration for a base `serviceId` wins. Red
    first: the list's four core entries and the editor outlet's audio case. Main 308.39 kB raw (65.86 kB
    transfer), initial 901.52 kB. `verify-local` PASS in 21m12s on `b29cb28e`.
  - **P7d, #208** (SP10, SP11): Frontend CI's DK and GL builds and Info CI's 14 country steps and Chromium
    removed (D10, D26); `publish.yml` publishes one bundle, `niord-web-admin` under the classifier `en` (D11),
    with a `release-workflows.test.mjs` case that runs the zip-and-deploy step's bash with a stub `be/mvnw` (red on
    the old step); `retract-version.yml` names `niord-app-dk` and `-gl` as names of versions up to the split;
    `verify-local` without the country builds; `./start-local.sh --dk` and `--gl` exit 2 (D12),
    `start-local-dk.sh` and `start-local-gl.sh` deleted; D32's manual tools run once on the base build and
    documented in `fe/CLAUDE.md`; the three moved e2e specs and `app-breadth.spec.ts`'s footer legal dialogs
    gone, leaving 40 spec files and 177 tests (`playwright test --list`). `verify-local` PASS in 30m00s on
    `6ea158e6`, its backend part 5,181 unit tests and 3,361 ITs.
  - **P7e, #209** (SP12): the documents brought to the end state before the deletions (D8): the `fe/` guides, both
    applications' `CLAUDE.md` (the public site's release block now the base build's manual commands), the review
    skill, `fe/docs`, the root `CLAUDE.md`, `be/docs/cicd`, the feature-branch deploy plan and the image-split
    plan; `country-deployments.md` trimmed (D9; its recipes and overlay checklist are P8's); `backlog.md`'s 11
    DK- or GL-only items removed (D29, carried over in P5's and P6's bump lists above) and the base items they
    shared rewritten. The deletion simulation: 95 errors in 13 files before, none after; `docs:check` 0 errors.
    `be/docs/cicd/README.md:11`'s dated "Status (2026-10-06)" line was edited in place ("the administration
    bundle (en)") and is left as it is. `verify-local` PASS in 27m04s on `6c1ed9e5`.
  - **P7-bs, #210**, six commits: the owner's bootstrap ruling of 2026-10-24 (D1, overruled;
    `docs/code-data-config-plan.md` D4 as amended, §7.2, §11 C6): within the first bootstrap a later archive's
    values replace; the claim refuses a database that holds data; no retry and no takeover; the batch page's
    Restart refuses a bootstrap job, the one change on a running system's endpoint, limited to bootstrap jobs and
    left for the owner's review. The owner ruled on 2026-11-03 (decision 138) that the refusal stays.
    `verify-local` PASS in 10m24s on `49aaa74e`: 5,213 unit tests and 3,365 ITs. Its
    first run, at 02:04 on 2026-10-25 in the hour the clocks went back, failed 17 time-dependent ITs outside the
    change, each by exactly one hour (recorded as TZ1 in `be/docs/potential-errors.md`; the owner ruled on
    2026-10-25 that the JVM runs in UTC, set by the chart and the images at the deploy step and stated in the
    operators' guide, P8). `./start-local.sh
    --sample --reset` on its tree: the bootstrap through the strict claim, the sample loaded, both frontends up,
    404 s.
  - **P7f, #211** (SP13, SP14): the frozen copies deleted, 134 files (78 deleted, 56 edited), +281/−12,000
    (`git show --stat 8154d3d6`; SP13's share 36
    files, the two branding specs among them): both applications' `country-specific/dk/` and `gl/` and
    `public/assets/dk/` and `gl/`, `i18n-status/`, seven build and three serve configurations, `tokens.dk.css`,
    `tokens.gl.css` and their active swaps, `languages/da/` and `kl/`, nine `package.json` scripts,
    `pack.mjs --frozen` (D14), `paths:check` rules 4-5 (D13) and the lint blocks of the country folders;
    comments in shipped code neutral; the deleted specs' rules a kit backlog item (D23; the kit checks them since
    P8a's C3, the page rule in check-build, the others in checkBranding). Suites: the
    administration app 5,501 tests, the public site 863; main 308.39 kB. `verify-local` PASS in 17m51s on
    `9852dc1e`, the sub-PR's head before its rebase, whose parent is `6c1ed9e5` (P7e): that run preceded the
    rebase onto P7-bs (#210 merged just before #211), so it ran on a tree without P7-bs's 68 files (the two
    trees differ by exactly +3,596/−1,229). Merged
    as `8154d3d6` (on `49aaa74e`, the same 134 files); the merged tree was first verified whole by P7g's run on
    `ded2ca51` and by CI on the head.
  - **P7g, #212** (SP15): the guard's tree mode (D16) with `Soefart` added, the allow entries of D18 and D19 and
    every other hit made neutral; `base-product-tree.test.mjs` (D22); `zip-include.json`'s country excludes
    removed and `workspace.mjs`'s `OVERLAYS` reduced to `assets/default/**` (D15), the zip 1,785 entries before
    and after. Red first: the tree
    mode over the tree before the new entries, 36 hits on 22 lines in 8 files, none of them in the zip; the
    structural test, 91 problems on P7e's tree. `verify-local` PASS in 17m43s on `ded2ca51`.
  - **P7h, #213** (SP17): this record, §5's dry run and its gaps fixed in the kit README, and DK's and GL's
    scratch bumps. `verify-local`: PASS in 23m50s on `610c0473` (backend 8m30s, administration app 5m34s, example country),
    the sub-PR's head before its merge; merged as `0409350e`, on the same parent `ded2ca51` and one plan line
    apart.
  - **The §5 dry run** (D27, 2026-10-25). A fictitious country `qq` (`en`, `da` pinned from
    GHCR, a new home pack `nb` as a content language) in an empty folder outside the tree, by §5 and the kit README
    alone: `kit:zip` of `ded2ca51` as `0.0.0-p7h`, installed with P7's backend into a scratch Maven repository, its
    sha256 equal to `kit:zip`'s `.sha256`; `niord-gl`'s `main` (`e866206`) stripped for step 1. Every step ended green
    (steps 3 and 5 after the refusal the guide intends, step 9 after `niord.home` was set in the settings file and
    the database recreated from `template_postgis`): `init qq`, `--update-pins` pulling `niord-lang-da:1` anonymously (its digest compared with GL's lock, since
    DK's `pack-releases.md` has no line yet), 0 escape-hatch entries, branding, tokens and one seam, the settings
    file and a one-domain bootstrap; the backend on 8888, the bootstrap of both archives applied in 7 s
    (`niord-client-master` and `qq-nw`; `audio`, `navtex`, `twitter`), the dev server's index and an anonymous
    `/rest/settings/web` through the proxy 200 with `qq`'s languages; `npm test` (no country specs), `npm run
    lint`, `npm run build` (44 s, with check-build's no-baseline warning) and `npm run image:info` (57 s: the
    labels read back, the layout check's 47 checks, 161 container assertions and 4 skipped, `--no-legal-doc`).
    Departures: the `read:packages` token of steps 1-2 replaced by the scratch install (needs a token, not run),
    and `be/scripts/bootstrap.sh` run read-only from `niord-ng`'s tree. The gaps went into the kit README
    (_Starting a country_: `-DoutputDirectory="$PWD/fe/.niord"`, since a relative path lands in `be/fe/.niord/`,
    and `init` keeping a copied `.github/`; _Language packs_: a new language's `pack.json` and its minimal pack,
    the escape hatch's place and shape; _Branding_: a branding file for every UI language once one exists; _The
    seams_: what a seam file exports; _The upgrade report_: the report in a fresh clone, and a renamed
    `Unreleased` section listed again) and into §5 steps 1, 4, 8 and 9. Found and not fixed: no artefact carries
    `bootstrap.sh` to a country (P8's launcher; carried since P8a, NG-9: `niord-local-run`); a country settings file without `niord.home` lets the base
    product's `${user.home}/.niord` win over `NIORD_HOME`, and an index of an older Lucene format there blocked
    start-up (§5 step 8 now names the key; open items SETTINGS-6 and LUCENE-3 in `be/docs/potential-errors.md`). SETTINGS-6 was fixed on 2026-11-03
    (E-fixes, decision 138); LUCENE-3 waits for cut-over. The repeat against the published zip comes after the merge.
  - **DK's and GL's scratch bumps** (from SP5; P5's and P6's bump lists above): both `main`s set up on a
    `kit:zip` of `ded2ca51` as `4.0.999-p7h`, green through set-up, `npm ci`, `npm run build` and `npm run
    image:info` (DK 83 s, GL 59 s on Windows with no wrapper; P7a's label path and P7b's container test and layout
    check, the da/en width check included). `npm test` and `npm run lint`, which both countries' `ci.yml`
    (`:233`, `:238`) and `image.yml` (`:187`, `:191`) run before the build, were not run: they are run before
    the bump PRs are opened, since the bump changes the countries' app sets (P5's and P6's bump lists above;
    after the review of #204). Their bootstrap and folder ITs against P7's `niord-base` red only
    where a bump item says (the additions tests meeting `Audio`, DK's C4b template), the `da` and `kl` `Audio`
    assertions green as written. That `niord-base`'s install overlapped the dry run's in the shared `be/`;
    compared entry by entry with the dry run's solo rebuild of `ded2ca51`, every class is byte-identical and the
    four differing entries carry only the version string. The scratch copies were thrown away; the bumps are the countries' own PRs.
  - **Review of #204 (R204, 2026-10-25).** One review of `0409350e`: 31 inline comments (1 major, 13 minor, 17
    nit), five points on the PR body, eight outside the diff and three questions. Each was checked against the
    tree, the plans and DK's and GL's `main` (`d6fa491`, `e866206`, read through git); 45 were fixed and
    two recorded (the scratch bumps' `npm test` and lint, now run before the bump PRs; the cookie statements' key
    check, a kit item in `fe/docs/backlog.md`). The owner decided two on 2026-10-25:
    1. **The merge mode** (the major): the repository allows only squash and rebase merges, and the owner chose a
       squash merge of #204 (D33 and the opening of this record; the SHAs recorded are the branch's).
    2. **Explicit empty lists** (question 2): within the first bootstrap a list the later archive gives
       explicitly, even empty, replaces the earlier archive's; a field left out or null keeps it; an empty string
       or object counts as left out (`docs/code-data-config-plan.md` §7.2, *Within one run*).

    The other two questions and one comment were decided with the recommended options: the queued mails (`ScheduledMail`,
    `ScheduledMailRecipient`) are not data, so a scheduled alert queued before the bootstrap does not make the
    claim refuse; a default-text key a later archive restates is kept with a job-log line saying start-up
    created it; and the batch page shows a refused restart's reason (`admin.batch.toast.restartRefused`, the
    server's problem detail; any other failure keeps the generic toast). What else the review changed:
    `Given` and `BootstrapArchive` read with the typed read's Jackson-2 defaults; the claim's refusal has a
    `pending` arm for the race; a later `active: false` names the children it makes inactive in the job log;
    `CountryWordsTest` skips `Copyright` only on comment lines, lets an allow entry name its line's text, and
    fails a file in scope that does not decode unless it is binary; `start-local.sh`'s after-failure handling is
    `bootstrap_outcome`, with `pending` and `applied` arms and a test of its own; `BootstrapSameRunITTest`
    covers every replace branch with a differing value; `base-product-tree.test.mjs` refuses
    `ui-apps/<app>/i18n-status/` and `styles/fonts/`; the go-live allow entries name their files (D30); the
    shipped English names no country (the `messageReferenceFormat` hint's "NtM 27/2026", the geometry example's
    names, `ATTRIBUTION.txt`); and in the documents this record's SHAs and counts, D15, the bump lists, the
    stale retry sentences, the deploy plan's `publish.yml` lines and `be/k8s/README.md`'s step b. Every code
    change was seen red first: the backend's six changed unit classes, 80 tests, 10 failures and 1 error on the
    old code, then green, and the targeted unit run 139 of 139; the ITs `BootstrapITTest` and `BootstrapSameRunITTest` 3 failures
    on the old code, then 20 of 20, and 10 more IT classes 78 of 78; the batch page's spec red on the refused
    restart, then 13 of 13; the structural test red at 6 of 8 refused, then green; `test:guards` 1,061 pass and
    4 skipped of 1,065. The sample and the IT fixture, regenerated into a scratch folder, are equal to the
    committed dumps, and no committed dump changed. Committed as `a8db0b32` (sub-PR #214): `./verify-local`
    PASS in 25m30s on `a8db0b32`, and CI on #204's new head `a8db0b32` green, 12 of 12.
  - **Decisions:** D1-D34 as recorded above, D1 overruled by the owner's bootstrap ruling of 2026-10-24; the
    batch page's Restart refusal for the owner's review; D34's question open. Both closed on 2026-11-03 (the
    owner, decision 138): the Restart refusal stays, and D34 as ruled above. D30's developer tools, which
    assume DK's data, stay as they are, a follow-up: `be/scripts/seed-dev-database.mjs`,
    `capture-publication-fixtures.mjs` and `capture-legacy-publication-estate.mjs` (DK's test host) and
    `fe/scripts/perf/publications-requests.mjs`.
  - **Sentences corrected** (in this plan: §3.1, §3.2, §4.3, §4.5, §4.6, §4.8, §4.9, §4.11 rule 6, §5, the
    Shape, P5, P6, D18, §7 and §8, where they promised a P7 action; of the build plan's cut, as built): SP4's
    "Nuuk is the only IANA zone" (`America/Scoresbysund` shares its rule; D18 says "no zone outside Greenland");
    D18's "`America/Godthab` becomes `America/Nuuk`", which applied to no case; "`git grep 'until'` is empty" is
    `git grep '"until"'`, no entry carrying `until: P7`, while five carry `until: P8` (`country-words.json`, all
    for `docs/niord-info-integration-plan.md`), which the guard still reads (made permanent in P8a (NG-13): a dated record, kept as written); `npm run kit:zip` takes `-- --version <v> --out <file>`;
    SP10's "a stub `mvn`" is a stub `be/mvnw`, since the step runs `./mvnw`, and its red-first
    `./start-local.sh --dk` was not run (it would start a dev server), only the new refusal; SP13's "retarget …
    to production" was done on niord-info's `options.assets`, its production configuration having no assets
    array, and SP13 deleted 36 files, not 34; the `pack.mjs` frozen lines included the three `sinkOf` call
    sites; RACON, markings and `work_vessel` come from the sample's `categories.json`, not the bootstrap's.
  - **CI on #204:** green on `0409350e`, all 12 checks (Backend CI's unit 2m13s and integration 7m42s, Frontend
    CI, the example country 6m25s, Info CI), and green on `a8db0b32`, the fixes of #204's review (above), 12 of
    12 (unit 2m36s, integration 7m43s, the example country 7m16s); on the merge `c703d061` Backend, Frontend and
    Info CI green and `Publish 4.0.97 after Frontend CI` green (the opening of this record). §8's job timings
    are filled from these runs.
  - **DK's and GL's bumps onto 4.0.97:** done, their own PRs, `niord-dk` #10 and `niord-gl` #9 (§6 P5 and P6).
  - **Not yet run:** §5's steps 1-2 and set-up and build against the published zip (they need a read-only
    Packages token on the workstation); steps 10-11 and every staging item, at the deploy step.

**P8 — guidelines for adding a country variant.** Written once the mechanics exist, for four audiences,
and published in the source zip so a country reads the version it builds against (from the kit move, §3.1, the
owner's ruling of 2026-10-26, in `niord-country-kit` of the same version):
- **Every audience, first:** the division of responsibility of §4.13 — what a country gets for free from
  the base product's maintainers, what it owns and maintains itself, what it never does — and how an upgrade works
  (§4.12), so a country knows what it signs up to before it starts.
- **People:** the country guide (§5 in full) — repository, kit, manifest, packs, branding, seams,
  functionality, pipelines, the escape hatch and overrides, upgrading, staging.
- **Designers:** what a theme may change and how — the token files and their rules (values only, contrast
  requirements, the fixed base product scale), fonts (self-hosted `woff2` in the country's styles, the faces
  the UI uses, the licence, never a Google link), logos, favicon and touch-icon sizes from the branding schema — and a
  ready brief to hand a design tool such as Claude Design, with the base product's token file and the rules, so
  its output drops into `styles/` unchanged.
- **AI agents:** the `CLAUDE.md` that `init` writes into a new country repository — the contract, the
  commands, the checks and what each failure means, what never to do (edit generated files, add an npm
  dependency anywhere but `fe/country/package.json` (R12), fetch fonts, copy base product text into a pack) — so an agent can set up or change a country
  from the repository alone.
- **Operators** (the owner's request of 2026-10-21): the data a new country sets up in the running system,
  strictly from the user's and operator's side — the administration app and Keycloak, no code, no files, no
  repository. What a fresh deployment already holds from the base product's bootstrap
  (`docs/code-data-config-plan.md` §7.1: the master domain, the templates and scripts, the built-in reports, the
  generic categories with "Firing Exercises", the parameter types, NAVTEX, Twitter and, from P7, Audio, which
  comes with the base product, the AtoN dictionary), and
  what the country creates itself, in the order one depends on another: domains and their time zones, areas,
  charts, sources, categories, message series and their numbering, publications and their categories,
  promulgation types (its own, such as SafetyNET, and for the base product's NAVTEX, Twitter and Audio the
  links to its domains and its language) and their transmitters, mailing lists and their
  triggers, dictionaries and texts, the templates and reports it adds or changes (templates by import, that
  plan's §6.4; a print report has no preview: NG-12 found the Print Reports import a plain upload that replaces
  a report of the same id whole, so the guide says to export first),
  firing areas and their schedules, the AtoN data (NG-12 found no admin page and no batch job in niord-ng that
  loads AtoN records, so the guide sends the operator to the country's maintainers; how a new deployment gets
  them is an owner question), the settings on the Settings page, and the users, groups and
  roles in Keycloak. Each step says where in the app it is done and how to see that it worked, and builds on
  the user manuals in `guides/` (sysadmin, admin, editor) rather than repeating them. It also
  states the JVM's time zone: UTC (TZ1 in `be/docs/potential-errors.md`, the owner's ruling of 2026-10-25),
  which the chart and the images set at the deploy step.
- **The country launcher** (the owner's ruling of 2026-10-23: P8's, not P5's or P6's; P9's *the same in a
  country repository*, the open item `be/docs/cicd/implementation-plan.md:112`): the kit's launcher template,
  which `init` writes (§4.8), and DK's and GL's launchers from it. Each starts the base product's services, the
  country's backend and frontend variant, and the sample through `load-sample.sh`'s interface (P9, *State as
  built*, *Not built*), in one command, as `./start-local.sh --sample` does for the base product. DK's starts
  with Danish on (the owner's requirement of 2026-10-23), while the base product's `--sample` stays English
  only. Decision (c) (2026-10-25, confirmed by the owner; the plan's two earlier options, Danish switched on
  after the load or a sample regenerated on DK's own base, both failed: a country's backend writes its own
  default texts at its first start, before any bootstrap, so the base ids the C7 guard held moved): the base
  product's sample loads on a country's backend after the base archive, since P8a (NG-8) gives its own rows
  ids from 1,000,001 and names every base row by its natural key (`docs/code-data-config-plan.md` §8). DK's
  `--sample` is therefore Danish from its first start, through DK's own settings, and DK regenerates nothing.
  A country's `--sample` posts the base archive only, then the sample; the country's own archive is its plain
  `./start-local.sh` (the country's real bootstrap: the base archive and the country's in one run, no
  messages), since the sample's domains and series would collide with the country's, and the guard refuses a
  database that already holds them. Its databases are `niord_sample_<cc>` (`niord_sample_dk`,
  `niord_sample_gl`), which `load-sample.sh`, the SQL's guard and the launcher library's `sample_reset` accept
  beside `niord_sample`. What a country's launcher takes from the base product travels in
  `org.niord:niord-local-run:<v>:zip` of the version it pins (NG-9), beside `niord-bootstrap`, and is never
  an image input. The guides of this phase then point at it.
  - **As built (P8a, NG-10: the kit's template, 2026-10-26).** `fe/country-kit/templates/start-local.sh`, one
    bash launcher, LF, with no placeholder and no country word, the same in every country; the kit README's
    *Running it locally* describes it. In order: the arguments (`--sample`, `--reset`, `-h`/`--help`; an unknown
    argument, `--reset` without `--sample`, or `FE_PORT`/`INFO_PORT`/`BE_PORT` not integers from 1024 to 65535
    or not three different ones exit 2, before any docker call); one Node run over the country's files (cc and
    name from `fe/niord-country.json`; the base version by the stub `fe/setup.mjs`'s exported `parentVersion`,
    imported, so it does not run; the deployable as `be/pom.xml`'s own `<artifactId>`; the database; the
    `settings.xml` credential state and the native paths, in one process because a start costs about 0.3 s on
    the Windows workstations); `--sample` with a three-letter code exits 2, whitespace in the checkout or the
    home folder exits 1; the prerequisites, each with its own message and exit 1, Docker last (a JDK 25+ with
    `jar` from `JAVA_HOME`, else `~/.jdks/temurin-25`; curl; npm; `<server><id>github</id>` in Maven's
    `~/.m2/settings.xml`, `USERPROFILE`'s on Windows; then `docker info`); the fetch of `niord-local-run` and
    `niord-bootstrap` by `be/mvnw dependency:copy` into an absolute `.local-run/<version>/`, once per version,
    unpacked with `jar` into `local-run.tmp` and renamed, a failure naming the artifact, the credential and the
    minimum base version; the library sourced, `use_jdk25` and its contract (`BE_PID`, `BE_PORT`, `BE_URL`,
    `BE_LOG`, `start_backend`); ports in use refused, naming the override; set-up before any backend, then
    `npm ci` when `fe/node_modules/.package-lock.json` is missing or older than `fe/package-lock.json`; the
    traps (clean-up on EXIT, `cleanup; exit 130` on INT and TERM: `kill_tree` of the three jobs, up to 60 s
    for each port, on Windows `taskkill` of the LISTENING PID, the log followers stopped, the containers left
    running); `start_services` with the zip's compose file and realm, no compose call of its own; the database;
    the backend (`spring-boot:run -Dspring-boot.run.profiles=local` with `-Duser.timezone=UTC`,
    `-Dserver.port`, `-Dniord.home` and `NIORD_HOME`, the datasource, `-Dmode=development`, `-DadminAppUrl`,
    and `-Dniord.secom.enabled=false` under `--sample`; 900 s to answer); the bootstrap through
    `bootstrap_database`, the base zip then `be/bootstrap/` when it has a `batch-set.json`, in one run, or under
    `--sample` the base zip alone and then `sample_load`; the frontends (`start:admin -- --configuration local
    --port $FE_PORT`, `start:info -- --port $INFO_PORT`, `NIORD_BE_PORT` set); the closing lines with the
    local-only warning; the `[be]`/`[admin]`/`[info]` log followers and `watch_servers`, then exit 1. `init`
    writes the template byte for byte with mode 0755 and never overwrites it, and writes a root `.gitignore`
    with a comment and `/.local-run/` (`rootGitignoreText`) or appends both lines once, in the file's own line
    ending (CRLF when it has one), to an existing one that
    has none of `.local-run/`, `/.local-run/`, `.local-run`, `/.local-run`, reported as a third list,
    `changed`; it names `./start-local.sh` in its log and in the CLI's done line.
  - **Decisions of NG-10** (under the owner's "Go with your recommendations" of 2026-10-25):
    - The database is `niord_<cc>`, or the `database` of a strict root `start-local.json` (one key, a plain
      identifier of at most 63 characters; never `niord`, `postgres`, `template0`/`1`, `template_postgis`,
      `keycloak` — whose Niord home would be the realm's folder —, `niord_sample` or `niord_sample_*`). The
      template is the same in every country, so GL's `niord_gl_local` needs a committed per-country value, which
      GL-1 commits; an environment variable was rejected, because a run that forgets it would fall back to
      `niord_gl`. The launcher's datasource is its own, so neither DK's local profile (`niord`, niord-ng's) nor
      GL's (`niord_gl`, the owner's) is used.
    - The mark guard: every database the launcher creates gets `COMMENT ON DATABASE … IS 'created by
      start-local.sh, the country launcher'`, and an existing database without that exact mark is refused (exit
      1) before any backend starts, which protects `niord_gl`, `niord` and `niord_test` whatever is configured.
      The refusal names two remedies, another database in `start-local.json` or, for a database the developer
      made and no longer needs, `docker exec niord-db dropdb -U niord <database>`; marking a database by hand is
      not documented, so the guard stays. A database created but not marked stops the run (exit 1) before the
      backend, with the same `dropdb` as its remedy, so the next run never refuses its own database.
      `niord_sample_<cc>` is the launcher's by its name and has no guard; `--reset` is its remedy.
    - The Niord home is `~/.niord-local/<database>`, one per database: not `~/.niord`, where the legacy index
      fails start-up (LUCENE-3), nor `~/.niord-ng-dk` or `~/.niord-ng-gl`, which hold this machine's test-data
      imports. It is exported as `NIORD_HOME`, since the country settings set `niord.home` from it, and made only
      when the backend starts, so a refused database or a set-up refusal leaves no folder (found in the
      rehearsal below).
    - The ports are 4200, 4400 and 8888, overridden by `FE_PORT`, `INFO_PORT` and `BE_PORT` and refused when in
      use; there is no `--slot`. The second `--port` wins over `start:info`'s own 4400 (Angular CLI 22.2.4 keeps
      the last value of a repeated option, with a warning), proven in the rehearsal, so `lib/workspace.mjs` and
      its test are unchanged.
    - Set-up runs before the backend starts, not only before the frontends: the backend's build reads the packs'
      backend texts from `fe/.niord/packs/` (the kit README's *The backend build*, C1).
    - The frontends start after the backend is up and bootstrapped, in both modes, so the dev servers never
      show a database half bootstrapped and the command order is one to test; niord-ng's plain run starts them
      in parallel. The cost is the dev servers' build time after the backend's.
    - `NIORD_LOCAL_BASE_REPO` names a scratch Maven repository with a local build of the pinned base: the
      launcher skips the credential check and exports `MAVEN_ARGS` with `-Dmaven.repo.local` and a tail on
      `~/.m2/repository`, which the fetch, set-up and `spring-boot:run` read (the P7h method).
    - The fetch is cached per version in `.local-run/<version>/` (published versions never change; a rebuilt
      local version needs its folder deleted), and the cache key is both `scripts/local-run-lib.sh` and
      `niord-bootstrap-<v>.zip`. The country's archive is posted only when `be/bootstrap/batch-set.json`
      exists, else the base archive alone with a note: a new country from `init` has only `be/bootstrap/README.md`.
    - Exit codes: 2 for arguments, 1 for the country's files and the prerequisites, all before any docker call
      but `docker info`; the watch ends with exit 1, as niord-ng's does.
  - **Tests, red first.** `fe/country-kit/launcher.test.mjs` failed 20 of 20 (ENOENT) before the template
    existed and passes 20 of 20 since, in 1 min 14 s here: a temporary country repository with the real
    `be/scripts/local-run-lib.sh` and stand-ins for docker, mvnw, npm, curl, java and jar, `HOME` and
    `USERPROFILE` temporary (`stage_realm` would otherwise overwrite the realm copy the running
    `niord-keycloak` mounts). It covers `bash -n`; the argument refusals with a docker that fails if called, and
    a missing credential with exit 1 and no docker call; the plain run's order (both fetches with an absolute
    output folder, set-up, no compose while the containers run, `createdb -T template_postgis` and the mark,
    the backend's arguments and `NIORD_HOME`, the bootstrap with two archives base first, the frontends); the
    sample (`niord_sample_xx`, the base archive alone, the loader, a second backend start, `--reset` first);
    the refusal of an unmarked database and `start-local.json`. It runs the launcher with its own async spawn
    (240 s a run, 4 at a time) rather than `fe/scripts/workflow-step.mjs`'s `runBash`, whose fixed 60 s
    `spawnSync` timeout a run reached here under load (53 to 57 s). `init.test.mjs`'s new cases failed 11 of 39
    before the init change and pass 39 of 39 since. `npm run test:guards` passed: 1158 tests, 1154 passing, 4
    skipped for the symbolic-link privilege. After the review's fixes (below the rehearsal) `launcher.test.mjs`
    passes 21 of 21 (+1: the mark that fails, through a stand-in `markFails`; the unmarked database's refusal
    now also asserts the `dropdb` remedy) in 65 s, and `init.test.mjs` 41 of 41 (+2: a CRLF root `.gitignore`
    with and without its final CRLF keeps its text as the prefix, gains no bare LF and ends in `be/target/`,
    `.idea/`, `/.local-run/`). Every case that runs the launcher skips, with its reason, when the temporary
    folder's path holds whitespace (the launcher refuses one); `bash -n` skips only without bash.
  - **The rehearsal** (2026-10-26, local only, nothing pushed, one Maven invocation at a time). 4.0.999-p8a of
    this branch was built into the scratch repository `niord-ng-wt/.m2-p8a`, tail `~/.m2/repository`
    (`./mvnw -B -ntp install -DskipTests -pl :niord-parent,:niord-base,:niord-bootstrap -Drevision=4.0.999-p8a`,
    103 s; `npm run kit:zip -- --version 4.0.999-p8a` and `install-file` of `org.niord.web:niord-fe-src`;
    `be/scripts/local-run-zip.sh`, 12 entries, and `install-file` of `org.niord:niord-local-run`). SHA-256:
    - `niord-parent` pom `e8ae2e404634daf27996c3e97716c5cd0bf119d359556818d6fe3853f695d79f`;
    - `niord-base` jar `07281ab8777bdedbda300bdc4d6a280dac1d581eaa10847cc17ce7d69c4ea4df`;
    - `niord-bootstrap` zip `3df5533201218c54a45ea9973ead3c6e4afac9e1eb02c1c06274966a0092f04f`;
    - `niord-local-run` zip `5a60e662cd9ad4dd7eb757a4d40bdd447a5d7cbf700c1296a2168540f9e2740d`;
    - `niord-fe-src` zip, first `b0ce7c538d3454e8dfe4a673470d862aaf8e1479c028fe2110953217cf27f24a` (the
      template `8eca8a68a0cea3f7faa5f4d714b593d9190ecb525a5ec42e1bd4829ef1d8daa9`; DK's runs), then, rebuilt
      after one fix, `9fdd0e09391e9358d30debe786662f732035e9c128d2a9c8187c0704ec311e9f` (the final template
      `ea5e3b8bfc503905d44aaebcadfb838588e1aeae8c48c968284b17e98d50c31c`; GL's runs). The two templates differ
      only in where the Niord home's `mkdir` sits.
    - **The clones** of `niord-dk` and `niord-gl`, on a local branch `rehearsal/p8a-launcher` from `main`
      (`9f09d19`, `555b7c7`): `<parent>` and the lock's base pinned to 4.0.999-p8a and the zip's hash,
      `NIORD_LOCAL_BASE_REPO` on the scratch repository (no token on this machine). In both, the template copied
      from `fe/.niord/base/country-kit/templates/` was byte-identical to the tree's, so the zip carries it.
    - **DK plain:** up in about 5 min with the fetch, `npm ci` (1 min) and the first build. `niord_dk` created
      and marked, "Bootstrapping database niord_dk with 2 archives in one run", `GET /rest/bootstrap` applied
      with `baseVersion` 4.0.999-p8a. `/rest/settings/web` from the first start: `modelLanguages` [da, en],
      `defaultLanguage` da, `siteLanguages` and `infoSiteLanguages` [da, en], `infoDefaultLanguage` da, mode
      development. `niord_dk` held DK's five domains (`niord-almanac`, `niord-annex`, `niord-fa`, `niord-nm`,
      `niord-nw`) and the base product's `niord-client-master`, 94 script resources (69 under DK's template
      paths; the base alone has 84), 9 reports, 392 Danish dictionary texts and no message; 4200 and 4400
      answered 200. TERM: the launcher gone in 11 s, exit 130, nothing listening on 4200, 4400 or 8888. A second
      plain run (about 1.7 min) took the marked database with "niord_dk already holds data (applied): nothing
      is bootstrapped" and no `npm ci`; TERM, gone in 6 s.
    - **DK `--sample`**, with `INFO_PORT=4410` (about 3 min): `niord_sample_dk` got the base archive alone
      (applied), the sample loaded and the backend started again; 34 messages, 68 "SAMPLE —" texts in da and
      en, Danish settings as above. 4200 and 4410 answered 200, 4400 nothing, and only 4410 listened (Angular:
      "Option 'port' has been specified multiple times. The value '4410' will be used."). TERM: gone in 6 s,
      the ports free.
    - **GL without `start-local.json`** (176 s with the fetch and `npm ci`): exit 1, "The database niord_gl
      exists and holds data this launcher did not make (it has no mark of start-local.sh), so it is never
      touched. …", no backend started. Its one side effect, an empty `~/.niord-local/niord_gl`, moved the
      `mkdir` to the backend's start (with a test assertion) and was removed.
    - **GL with `start-local.json` `{"database":"niord_gl_local"}`** (2 min): `niord_gl_local` created and
      marked, 2 archives applied; `modelLanguages` [da, en, kl], `infoSiteLanguages` [kl, da, en],
      `infoDefaultLanguage` kl, `defaultLanguage` da, `siteLanguages` [da, en]; the domains
      `niord-client-master` and `niord-gl-nw`, 120 script resources, dictionary texts da 406, en 399, kl 235;
      the ports 200. TERM: gone in 7 s, the ports free. **GL `--sample`** (3 min): `niord_sample_gl` got the base
      archive alone, the sample loaded (34 messages), the same kl/da/en settings; TERM, gone in 9 s.
    - **Untouched:** `niord-db`, `niord-keycloak` and `niord-smtp` kept their Created and StartedAt times;
      `niord`, `niord_test` and `niord_gl` kept their `tup_inserted`, `tup_updated` and `tup_deleted` and their
      counts of Message, DictionaryEntry, Setting and Domain (`niord` 0/276/54/10, `niord_test` 51577/407/69/8,
      `niord_gl` 51600/409/71/8); only `xact_commit` grew (read-only transactions; no run connected to
      `niord_test` or `niord_gl`). `stage_realm` re-staged `~/.niord-local/keycloak`'s realm with the same
      content, LF from the zip.
    - **The bump fixes found, for DK-1 and GL-1** (made only on the throwaway branches): DK's home `da` gains
      `{{failed}}` in `admin.secom.toast.reconciled` (a local "…, {{failed}} mislykkedes", unaccepted), the key
      moves into `status/unaccepted.json` and its fingerprint `827fdeda5b2c6b76` leaves
      `status/fingerprints.json` (the pack check refuses a key in both); GL lists the key for its pinned
      `niord-lang-da:2` in `missing-keys-accepted.json`. No C3 branding finding in either; GL keeps its warning
      that 7 kl branding values await acceptance. Both also copy `start-local.sh` and add `/.local-run/` to the
      root `.gitignore`, and GL commits its `start-local.json`. Done in DK-1/GL-1 (`niord-dk` #11, `niord-gl` #10;
      _MAIN, DK-1 and GL-1 as built_).
    - **Clean-up:** both clones back on `p7/bump-4.0.97` with an empty `git status`, the rehearsal branch
      deleted and never pushed, set-up re-run at 4.0.97 (verified, exit 0); `niord_dk`, `niord_gl_local`,
      `niord_sample_dk` and `niord_sample_gl` dropped with their homes; no process left, nothing listening on
      4200, 4400, 4410 or 8888. `.m2-p8a` and the two zips are kept for NG-12 and NG-14.
    - **Found for a `be/` follow-up, not fixed here:** `stage_realm` compares bytes, so a Windows checkout's CRLF
      realm (niord-ng's own launcher) and the zip's LF realm (a country's) re-copy the realm whenever the two
      alternate; harmless, since the content is the same and the running `niord-keycloak` is never touched.
  - **The review's fixes, after the rehearsal.** The template is now
    `0f40800470cf512fc484aa196e9093473aaa89781325ef9e49ec3648788aa170`; against the rehearsed `ea5e3b8b…` (read
    from the scratch `niord-fe-src` zip) it differs in three places only: the unmarked database's refusal also
    names `docker exec niord-db dropdb -U niord <database>` for a database the developer made and no longer
    needs; a failed `comment on database` now stops the run (exit 1, before the backend) with the same `dropdb`
    as its remedy, where it went on before and the next run would have refused the database it had just made;
    and `--help`'s `sed` closes its block with `;p;}`, which macOS's `sed` also takes (the header names Linux
    and macOS). `init` appends the root `.gitignore`'s lines in the file's own line ending. These were not
    rehearsed again; the launcher test covers them, and Git Bash's `sed` prints the header.
  - **Not yet run:** the launcher test on Linux (Frontend CI's `test:guards` at the PR into `main`), the
    example country harness (`init` now writing `start-local.sh` and the root `.gitignore`) and
    `./verify-local --base origin/feature/p8a-guides`; the published `niord-local-run` fetched with a real
    `read:packages` token (after PR A's publish); an interactive Ctrl+C (TERM, which takes the same trap, was
    sent); a run on a clean machine; DK's and GL's own launchers (DK-1, GL-1). They arrived with `niord-dk` #11 and
    `niord-gl` #10, copied byte for byte from the template and made executable (_MAIN, DK-1 and GL-1 as built_), and
    have not been run on a country.
- **Every repository's documents** (the owner's requirement of 2026-10-23): beyond the guidelines, P8 brings
  the documents of `niord-ng`, `niord-dk` and `niord-gl` up to the split and to the code/data/configuration
  rules as built: the root README, every `CLAUDE.md` (trimmed where Claude Code's `/doctor` flags one as too
  large) and the other docs.
- Verification: a fictitious country set up from scratch by each route — a person following the guide, a
  design tool's output through the brief, an agent with only the generated `CLAUDE.md` — each to a green
  build; and an operator, by the operator guide alone, taking a fresh deployment of it from the bootstrap to a
  first message published in the country's own series and shown on its public site.
- Done when: all four routes reach their end without help from outside the guidelines.
- **As built (P8a)**, the sub-PRs into `feature/p8a-guides`, each verified with
  `./verify-local --base origin/feature/p8a-guides` (the details are the records below and in P9):
  - #216, NG-7 (`b93a9674`): the launcher library, and no launcher recreates a running shared container; PASS,
    backend 11m11s.
  - #217, NG-1 (`677a6aa9`): the kit's image template builds the backend after set-up (C1 step 1); PASS,
    administration 7m25s, public site 1m05s, example 13m13s, after a first run red only from another
    worktree's harness on the same image tags.
  - #218, C3, NG-5/NG-6 (`da4bb20b`): branding sizes, texts and cookie statements, the built page's language
    and title; PASS, administration 9m18s, public site 52s, example 13m09s.
  - #219, NG-2 (`208903ac`): strict run blocks, the jar-set hash the same on Git Bash; PASS, backend 9m38s,
    administration 9m14s, public site 54s, the example red on a native node crash (0xC0000409 in `oci.mjs`
    publish) and PASS on its re-run, 10m32s.
  - #220, NG-8 (`1dc9edac`): the sample loads on any country's backend after the base archive; PASS, backend
    13m20s.
  - #221, NG-3 (`0fd6a04f`): the URL allow-list per application, the font budget allowance, the e2e specs
    linted, one audit retry; PASS, administration 7m17s, public site 1m01s, example 13m43s.
  - #222, NG-11 (`b04490f6`): the guide index, responsibilities and upgrades, the designers' brief,
    `docs:check` rule 7; PASS, administration 9m40s, public site 1m06s, example 12m21s.
  - #223, NG-9 (`6245d44a`): `org.niord:niord-local-run`; PASS, backend 10m07s, administration 9m41s, public
    site 1m04s.
  - #224, NG-4 (`0af50fa4`): ESLint over the kit's and the workspace's scripts (`npm run lint:scripts`), and
    the Example country job's two anonymous pulls, `alpine:3.22` (through the Dockerfile's new `ALPINE_IMAGE`
    build argument, which `image:info` passes) and the harness's `registry:2.8.3`, moved from Docker Hub to
    the ECR Public mirror (`public.ecr.aws/docker/library/`); PASS, administration 8m37s, public site 1m37s,
    example 11m54s. The ECR move was reverted after #229's CI (2026-10-26): ECR Public answered the runner
    `429 Too Many Requests` (`toomanyrequests: Data limit exceeded`, run 40301130711), its anonymous limit
    being per source address as Docker Hub's is, on runners that share addresses, while Docker Hub's pulls
    had worked on every earlier run. Both images come from Docker Hub again; `ALPINE_IMAGE` and
    `image:info`'s passing of it stay, for a country's own mirror, and `fe/docs/backlog.md` (_Quality gates
    and tooling_) holds what would close the item (an authenticated pull, or another mirror, untested).
  - NG-13: niord-ng's documents brought up to the split and the rulings (the root and `be/` documents, the
    cicd plan and its intended use, the image-split plan's end note, the audio record moved to niord-gl, the
    five word-list entries made permanent, this plan's records); its `./verify-local` result is in its PR.
- **The kit follow-ups before the guides (C2: template and script hygiene), as built** (2026-10-25, NG-2,
  `p8a/c2-strict-hashes` into `feature/p8a-guides`).
  - **Strict run blocks.** Every run block of `image-frontend-job.yml`, `image.yml` (its push job and the
    placeholder step, which still exits 1) and `pack.yml` starts with `set -euo pipefail`, one-liners included:
    they are `run: |` blocks now, their commands, `env` and `if:` unchanged; the backend job's were strict from
    its first line. `pack-workflow.test.mjs`'s rule covers every block, and its four exact-text assertions read
    the run as js-yaml parses it (`stepRun` takes the comment above the next step into a `run: |` block);
    `init.test.mjs` holds every job of the generated `image.yml` (frontend, backend, push) to the same rule. Red
    first: with the test changes alone, 5 of the two files' 67 tests failed, on 12 of the templates' 30 run
    blocks (`pack.yml` 4, `image-frontend-job.yml` 7, `image.yml`'s placeholder 1); green after, 67 of 67, and
    `npm run test:guards` green (1080 tests, 1076 passing, 4 skipped for the symbolic-link privilege). The kit
    README states the rule where a country reads it, and `API-CHANGES.md`'s Unreleased says what a country does
    at its bump. DK's and GL's `pack.yml` are the template byte for byte on their `main` (`9f09d195`,
    `555b7c73`) and their `image.yml` already strict, so DK-1 and GL-1 copy `pack.yml` again and nothing else. Done in
    DK-1/GL-1 (`niord-dk` #11, `niord-gl` #10; _MAIN, DK-1 and GL-1 as built_).
  - **The hash quirk.** Git Bash's `sha256sum` marks binary mode with ` *` (`<hash> *a.jar`, `<hash> *-`), so
    `split-jar.sh --hash` named the same jar set by another hash than Linux. Both scripts' `sha256` helper, the
    same text in each, turns the marker into the two spaces, anchored on the 64-hex hash so that a name holding
    ` *` is untouched. `SplitJarHashTest` (Surefire; a stub `sha256sum` on the PATH printing the binary form, the
    Linux hash computed in Java) was red first, 3 of 3 failing: for the fixture `a.jar`, `B.jar` and `c-1.jar`
    the script gave `5f407051…044a5e` against the Linux `8dac972d…8e2e45`, with the stub and with this machine's
    own `sha256sum`; green after, 3 of 3. Nothing for DK or GL: the image tools come with the base version. No
    `be/docs/potential-errors.md` entry, since it was fixed with the change.
  - **`pack.yml`'s validate finding closed without a change.** P6b found validate red at set-up for GL's reason
    of the time: kit 4.0.81 fetched no pinned pack and `NIORD_PACK_*` is refused under CI, so the pinned `da`
    could not be staged until P5k's OCI client was pinned and `niord-lang-da` published. P5k's set-up stages
    every pinned pack from the lock, and P6e's runs were green by hand and in the merge's run (P6b's record and
    P6's pipelines above); a withdrawn or unpullable pin is a
    set-up failure the home must fix for its own build anyway. A set-up mode staging only the home's packs would
    be new surface with no failure behind it, so none was built (P8a's plan, 2026-10-25, the recommended option,
    under the owner's "Go with your recommendations").
  - **Not yet run:** actionlint and shellcheck (not installed here), and the new class on Linux (Backend CI on
    the PR into `main`). The example country harness and `./verify-local --base origin/feature/p8a-guides` ran
    in #219 (_As built (P8a)_ above).
- **The guides, part 1 (D1a), as built** (2026-10-26, NG-11, `p8a/guides-1` into `feature/p8a-guides`).
  - **`docs:check` learns the zip** (`fe/scripts/docs-check.mjs`, its rule 7). Three new behaviours:
    - In a document the source zip carries, chosen by `country-kit/zip-include.json` through `lib/include.mjs`
      on the document's own path (today the kit's README, `API-CHANGES.md` and `docs/`), a relative link and a
      backticked path into a workspace folder must name a file or folder of the zip, and so must a backticked
      `.niord/base/…` path, with or without `fe/`. The zip is git's file list as `checkoutFiles` takes it (the
      same selection as `kit:zip` and set-up's local copy) plus the generated `catalog.json`; a folder passes
      when the zip holds a file under it. A target the include list leaves out fails though it exists here.
    - In the kit's guides, `country-kit/docs/**`, an `npm run` names a script of a country workspace, read from
      `lib/workspace.mjs`'s `packageJson` for the example's `fe/`, so a new generated script is accepted with
      no copy. The set replaces the workspace's: a base-only script (`docs:check`, `kit:zip`) fails in a guide,
      and `start:admin` still fails everywhere else.
    - The agent template, `templates/CLAUDE.md`, is read for its `.niord/base/` paths only, and counted in the
      summary line; its other links, paths and scripts are a country repository's. `documentationFiles` still
      leaves `templates/` and `example/` out (the critic's point that the template's paths need a test, met by
      the same boundary rule).
      Red first: with the test changes alone, `node --test scripts/docs-check.test.mjs` gave 16 tests, 12 passing
      and the four new ones failing, each on its first assertion (a guide's `npm run start:admin` refused; a
      guide's link to `../../docs/backlog.md` and its backticked `country-kit/api.test.mjs` passing; the agent
      template's missing `.niord/base/` path passing). Green after: 15 of 16, the tree test red on exactly the two
      kit README lines below; with the guides written, 16 of 16. `country-kit/zip.test.mjs` 18 of 18 (its selection
      test now lists `country-kit/docs/README.md` and `country-kit/docs/responsibilities-and-upgrades.md`),
      `country-kit/country-words.test.mjs` 19 of 19, `npm run docs:check` 33 files, no errors, the 7 earlier
      warnings. `npm run kit:zip` (version `0.0.0-ng11`, offline, with its verify and the word guard in zip mode)
      passed, and its 1,790 entries hold the three `country-kit/docs/*.md`. `docs:check` reads no anchor, so a
      one-off script compared every `#anchor` link of the three guides and the kit README with GitHub's slugs of
      the target's headings: 68 links, none missing. (Since N2-6, 2026-11-02, `docs:check`'s rule 8 checks such
      anchors on every run: _N2 as built_.) `npm run test:guards` green: 1103 tests, 1099 passing, 4
      skipped for the symbolic-link privilege.
  - **The three kit lines the rule flagged**, each fixed by rewording, so the word list, a test file and an
    application README stay out of the zip as `zip-include.json`'s note intends: the kit README's folder list
    linked `country-words.json` (now named, unlinked, still "not in the zip"); its end-to-end paragraph named the
    suite's README by its `ui-apps/` path (now "whose README beside the login fixtures"); `API-CHANGES.md`'s
    compatibility policy named `country-kit/api.test.mjs` (now "the kit's API test, which the base product's CI
    runs"). No `Unreleased` entry: no country API and no country action changed.
  - **The guides**, in `fe/country-kit/docs/`, shipped in the zip with no change to its include list, so a
    country reads them at `fe/.niord/base/country-kit/docs/` for the version it builds on:
    - `README.md`, the index by audience in reading order: everyone (responsibilities and upgrades), people
      setting up a country (the kit README's _Starting a country_ and _Set-up_ until the country guide), designers
      (the brief), AI agents (the `CLAUDE.md` `init` writes) and operators. NG-12's `country-guide.md`,
      `operators-guide.md` and the agent template are named unlinked, marked "written in a later change" (the
      template "a placeholder, filled in a later change").
    - `responsibilities-and-upgrades.md`, for every reader: §4.13 restated with no country named (an export goes
      after "at least one base version that the countries building on it have taken"; the sample deployment of
      §4.13 left out until a country's launcher lands (NG-10); NG-12 adds it), `API-CHANGES.md`'s
      compatibility policy summarised and linked, the upgrade path, §4.12's red-reason table with the kit README
      section of each row, and the upgrade report's five sections.
    - `designers-brief.md`: what a theme changes and where, the token contract of `fe/docs/country-deployments.md`
      restated (that file is not shipped), the contrast floors with the default's measurements as the token files
      state them, fonts as §4.7 has them, logos and icons at the sizes C3's branding check reads from the PNG
      header, a paste-ready brief for a design tool that returns one `tokens.<cc>.css`, and how to see it worked.
      The kit README's folder list names `docs/`, and its introduction points at the guides as the way in.
  - **The upgrade path today is the hand-made bump PR:** one change sets the `<parent>` of `be/pom.xml` and the
    lock's base `{version, sha256}` from the release notes' `<sha256>  niord-fe-src-<version>.zip` line, then
    `node setup.mjs`, the report's To do, `--update-pins` for the foreign packs, the build; the guide says no kit
    command moves the base pin. **Renovate** is a section "From Renovate's install", fenced as decided, not built:
    the owner's rulings of 2026-10-25 (never automerged, weekly, security at once, the `<parent>` after Dependency
    Dashboard approval and a `security.json` version approved at once, a re-pin workflow in each country; the
    Dashboard approval dropped on 2026-11-01, §4.11), dated rather than numbered, naming no workflow or command;
    only `templates/renovate.json`, inert until the install.
    **GRAD** is one isolated paragraph, the SECOM library and the temporary file mirror a country may carry,
    ending "Open until the owner's ruling (E3); the mirrors then go."
  - **The descriptions of `docs:check`** in `fe/CLAUDE.md` (the command comment and the Guards list, which now
    names the agent template), `fe/docs/definition-of-done.md` and `fe/docs/coding-standards.md` say the same.
    `fe/docs/backlog.md` records the dangling `tokens.template.css` in `styles/tokens.default.css`'s status
    comment, which the zip ships and the brief hands to a design tool; `styles/` was left alone.
  - **Left to NG-12:** the country guide, the operators' guide, `templates/CLAUDE.md` with its `API-CHANGES.md`
    entry, and the index's three later files turned into links (with their anchors checked) and the marker
    removed. NG-3 needs a guide sentence in four places: the font budget (the brief's fonts), the URL
    allow-list's per-application scope (the brief's no-`https:` sentence and the country guide), the audit retry
    (the red-reason table's audit row) and the end-to-end lint (the country guide).
  - **Run since:** `./verify-local --base origin/feature/p8a-guides` PASS on #222 (_As built (P8a)_ above).
- **The guides, part 2 (D1b), as built** (2026-10-26, NG-12, `p8a/guides-2` into `feature/p8a-guides`).
  - **The three files**, shipped in the zip with no change to its include list:
    - `fe/country-kit/docs/country-guide.md` (707 lines, 37,609 characters), for the person who starts a
      country: §5 steps 1-9, each with what to do, the command and "How to see it worked", linking the kit
      README's sections rather than repeating them; step 1 lists the minimal backend file by file; step 7 restates
      the backend contract (the settings file with `"niord.home": "${NIORD_HOME}"`, the texts, `batch-set.json`
      in the runner's job order, `domains.json` with an active domain, the in-run precedence, the multipart
      limits, release folders, `expected-template-paths.txt`, static-image overrides); step 8 runs set-up before
      Maven, then `./mvnw verify` and the group's count; step 9 has `ci.yml` as the country's own (`init` writes
      none), `image.yml`, `pack.yml`, `dependabot.yml` and `renovate.json` as `init` writes them; *Run it
      locally* with the UTC rule; steps 10-11 with each part "verified at the deploy step"; the recipes *add a
      country* and *add a language* rewritten from the text P7e removed (`25f75068:fe/docs/country-deployments.md`)
      without its in-tree items; a definition of done for a country without the stale `build:<cc>` and "CI
      builds both" rows; and *Before go-live*, a checklist generalised from the 11 backlog items P7e removed,
      naming no country. Its examples use the made-up country `qq` and the language `zz`.
    - `fe/country-kit/docs/operators-guide.md` (362 lines, 21,131 characters), strictly from the administration
      app and Keycloak's console: finding your way; what a fresh deployment holds (`docs/code-data-config-plan.md`
      §7.1); the bootstrap, once on a new database, with its answers and no retry; the inactive master domain's
      workaround; 17 steps in dependency order, each with where, the buttons and fields as the templates and
      `en.json` name them, how to see it worked and the manual's page (the order of §6 P8's operators bullet, with
      publication categories before publications, since a series points at a category, and the Keycloak clients
      and the first sysadmin's role first, users, groups and roles last); the server's home folder; its clock;
      and cut-over by the missing-path report.
    - `fe/country-kit/templates/CLAUDE.md`, the agent contract (206 lines, 12,867 characters; 616 as the
      placeholder): what the repository is and what to read first, the layout, the commands, each check's failure
      and its fix with the kit README's section, the never-do list, the base bump (one pull request, the
      `<parent>` and the lock's base pin, the sha256 from the release notes' line), the backend rules restated
      inline and the version-matched guides as the authority. `init` copies it byte for byte; `init.mjs`'s header
      comment names it, and `init.test.mjs` gains a byte-for-byte case.
  - **Decisions:**
    - The minimal backend is listed from `niord-gl`'s `main` (555b7c7, read through `gh api`) and
      `be/docs/country-api.md`, generalised: the pom's `artemis.version` 2.54.0 and `testcontainers-keycloak`
      3.4.0, which `niord-parent` does not set; `be/.gitattributes` with the wrapper's line endings and GL's
      `bootstrap/** eol=lf`; a 14-line generic check that the country build group ran, tested against fake
      Failsafe and Surefire reports in five cases, in place of GL's `test-it` and `it-report-check.py`. No scaffold
      exists; the guide names the existing record (`be/docs/cicd/implementation-plan.md:214`) in words, not by a
      link a country cannot follow (rule 7), and no second record was added. That record's last sentence ("Until
      it exists, a new country strips a copy of niord-gl's be/ as that step says") contradicts the rewritten §5
      step 1, which copies no other country's `be/`; NG-12 leaves `be/` untouched, so the sentence is corrected in
      the next `be/` documents pass to "Until it exists, a new country writes its backend by hand from the country
      guide's list (`fe/country-kit/docs/country-guide.md`, step 1)".
    - `be/docs/country-api.md` is not in the zip; the guide and the template name
      `.local-run/<version>/local-run/docs/country-api.md` as plain text (the `niord-local-run` zip carries it,
      `be/scripts/local-run-zip.sh:17`).
    - The manuals are linked by the administration app's own Help anchors (`documentationUrl` and the help map in
      `fe/ui-apps/admin/src/environments/environment.ts`), after a three-page spot-check of
      `guides/sysadmin-manual/` against the admin pages and `en.json`: *Domains* matches field for field,
      but leaves out that *Create in Keycloak* shows only on a saved domain and the page's *Copy Domain* button and
      Keycloak link, which the guide restates; *Settings* differs a lot (its `niord.json`, environment-variable and
      code-settings sections and the AngularJS Web flag no longer describe niord-ng; no word of the country's
      settings file, nor that there is no reset and no delete), so the guide restates the step; *Message Series*
      matches, with niord-ng's *Restrict Types*, the next number's Compute popover and the new *Search Index*
      field, which the guide names. The published docs.niord.org is older than `guides` (the Settings
      "Editable" row). The app's Help anchor for publication categories is dead (`environment.ts:45` maps it to
      `#publication-categories`; the manual has `#publication-categories-tab`): the guide links `#publications`,
      and the dead anchor is a bug outside the task, so it goes to `fe/docs/backlog.md` as an entry of its own: map
      it to `#publication-categories-tab` (`guides/admin-manual/05-Publications.adoc:24`), or give the
      manual the anchor.
    - Two plan points did not hold, corrected in §6 P8's operators bullet: a print report has no preview (the
      Print Reports import is a plain upload that replaces a report of the same id whole), so the guide advises an
      export first; and niord-ng has no admin page and no batch job that loads AtoN records, so the guide sends the
      operator to the country's maintainers, an open question for the owner.
    - The workarounds for BOOT1 (an active domain from the country's bootstrap), SETTINGS-6 and LUCENE-3 are
      written "until fixed" with no defect id; LUCENE-3's remedy, emptying `<niord.home>/message-index` before the
      first start, is written "not verified". NG-14's operator route did not try it, so it moves to the deploy step
      (G8). The UTC rule is dated, not numbered, and no shipped guide carries a tracker id. Every deploy part
      (the bootstrap command, the home folder's volume, UTC in the chart and the images, the production realm,
      the public site's deployment, cut-over on a real volume) is "verified at the deploy step". The batch page's refusal to restart a bootstrap
      job is described as it works today (E5 open). On 2026-11-03 (the owner, decision 138) E5 was closed (the
      refusal stays, so that description holds) and BOOT1 and SETTINGS-6 were fixed (the E-fixes); the guides'
      "until fixed" sentences for those two are the guides' next pass.
    - NG-3's four sentences: the font budget and the URL allow-list's per-application scope (a font or stylesheet
      never one of its entries) in the brief's *Fonts*; the allow-list scope and the end-to-end lint's four
      imports in the country guide's step 6; the audit retry as its own row of the red-reason table in
      `responsibilities-and-upgrades.md`. That guide's *What you get* gains the launcher and the sample deployment,
      its upgrade step 5 the launcher's copy at every bump (the upgrade report does not name it), and its
      introduction a link to the country guide.
    - The links and records: the index links the three files; the kit README's `docs/` row names the four guides
      and its `init` paragraph the agent contract; `API-CHANGES.md` Unreleased says that `init` keeps an existing
      `CLAUDE.md`, so a country merges the template's sections by hand and drops a line that sends an agent to
      another repository's backend rules; `zip.test.mjs`'s selection test lists the five guides and the template;
      `fe/docs/country-deployments.md` points at the guides; §5 steps 1, 4 and 9 and its last paragraph point at
      them (step 4's transitional sentence was stale since NG-1, #217).
    - The review's fixes: the administration app grants a role only from the client of the domain chosen in its
      menu (`auth.facade.ts` `hasRole()` through `hasResourceRole`; nothing in the app reads
      `niord-client-master`), so the operators' guide step 1 has the first sysadmin hold sysadmin on
      `niord-client-master` and on the client of the bootstrap's first active domain, made by hand in Keycloak's
      console with the four roles and associated roles that `KeycloakIntegrationService.createKeycloakClient`
      builds (later clients from *Create in Keycloak*), and step 16 says a role on `niord-client-master` counts
      only for the backend's interface; the country guide's step 11 realm bullet and §5 step 11 say the same. The
      country guide's `application-local.yml` names `jakarta.persistence.validation.mode: none`, as the base
      product's local profile sets it, and `hibernate.default_batch_fetch_size: 16` as optional (long lists only).
      The local realm's domain clients also carry `publication-curate`, which *Create in Keycloak* does not make;
      the guide follows the code, and whether a created client needs that role is left open.
  - **Run:** `npm run docs:check` 35 files, no errors, the 7 earlier warnings; `node --test
scripts/docs-check.test.mjs` 16 of 16; `country-kit/zip.test.mjs` 18 of 18; `country-kit/country-words.test.mjs`
    19 of 19; `country-kit/init.test.mjs` 42 of 42 (the new byte-for-byte case passed on its first run: a guard,
    not a red-first test); `npm run lint:scripts` clean; prettier over the changed `fe/` files. `npm run kit:zip`
    (version `0.0.0-ng12`, offline) built the zip, and its `verify` and `guard` (11 words, 20 allow-list entries)
    passed; its 1,793 entries hold the five `country-kit/docs/*.md` and `country-kit/templates/CLAUDE.md`; the
    scratch zip was deleted. A one-off script compared the 120 `#anchor` links of the five guides, the kit README,
    `API-CHANGES.md`, the template and `fe/docs/country-deployments.md` with GitHub's slugs of the target's
    headings: none missing. After the review's fixes: `npm run docs:check` 35 files, no errors, the 7 earlier
    warnings; `country-kit/zip.test.mjs` 18 of 18; `country-kit/country-words.test.mjs` 19 of 19; prettier over
    the changed guides.
  - **Not yet run:** the four routes (NG-14: a person with the country guide, a design tool through the brief, an
    agent with only the generated `CLAUDE.md`, an operator from the bootstrap to a first published message), and
    `./verify-local --base origin/feature/p8a-guides`.
- **The four routes (D5), as run** (2026-10-26, NG-14, `p8a/routes` into `feature/p8a-guides`). Each route ran in a
  workflow agent from the base product built locally as `4.0.999-p8b` into `.m2-p8b`, its source zip's hash checked
  against the release-notes line the build step handed it (`58e9d8d2…6f5`). There is no GitHub Packages token on
  this machine, so every fetch of a base product package read the local build (`-Dmaven.repo.local=.m2-p8b` with
  `~/.m2/repository` as its tail; the launcher with `NIORD_LOCAL_BASE_REPO`): the environment, not a gap.
  `image:info`, the example harness, `pack.yml`, `image.yml` and `ci.yml` on GitHub and `--sample` were not run (the
  run's rules, or no token). The shared `niord-db`, `niord-keycloak` and `niord-smtp` kept their `StartedAt`
  throughout, and `niord`, `niord_test` and `niord_gl` were only read. Minutes are the routes' own step times.
  - **Person** (Quuxland `qq`, from an empty folder by `country-guide.md`; about 48 minutes): reached its end. UI
    English, the home content pack `qx` with a flag and a mail bundle; steps 1-9 in seven commits: one each for
    steps 1, 2 and 5, one for steps 3-4, one for steps 6, 7 and 9 (so step 9 was written before step 8 ran), one
    for step 8's fix (`mapDefaultLatitude` an integer setting) and one for `be/.gitignore`. Green:
    set-up, `npm ci`, build, test, lint and e2e; `./mvnw verify` with the group (114 unit tests, 14 ITs, none
    skipped) and the group check; the plain `./start-local.sh` (up in 2 min 42 s, `niord_qq` made and marked, both
    archives bootstrapped in one run, the `(country)` `niord.home` line, `/rest/settings/web` with its languages,
    TERM exit 130 in 8 s, the ports freed). Not on the guides alone: 10 gaps, 6 missing (no Maven in the
    prerequisites for the wrapper; no `be/.gitignore`; the exec bit lost on a Windows commit; no source named for
    the backend's English texts; no format for `batch-set.json` and `domains.json`; no client for the country's
    domain in the local realm, so the local sysadmin saw Messages only), 1 wrong (`country-api.md` readable only
    after a first launch, which spends the database's one bootstrap) and 3 unclear (the public site's default
    footer offers no legal document; whether a country's pom names GRAD's repository; where a setting's type is).
    Help from outside the guides: the bootstrap formats from the base archive and the admin app's domain and
    series models, the English mail texts from the niord-base jar, the Maven cached in `~/.m2/wrapper`, and
    `docs/country-api.md`, read from the `niord-local-run` zip in the local Maven repository before the first
    launch.
  - **Agent** (Qualand `qa`, an agent with the generated `CLAUDE.md` and what it points to; about 16 minutes):
    reached its end. Green: set-up, build (its one baseline warning gone once the baseline was started), test,
    lint; `./mvnw verify` with the group (114 unit tests with the documented `MailHtmlTest` skip, 14 ITs, none
    skipped) and the group check; `./start-local.sh` on 4300/4500/8988 with its bootstrap applied (domain `qa-nw`
    in `Atlantic/Reykjavik` with its series, 80 script resources, no `ERROR` line); INT exit 130 in 7 s;
    `niord_qa` and its home dropped. GRAD's repository refused connections during the run, and SECOM came from the
    warm cache, as the SECOM section warns. Not on the guides alone: 10 gaps, 4 missing (the README's *Starting a
    country* gave no way to make its precondition, the wrapper and a first pom; the bootstrap formats; no
    `be/.gitignore`; the local realm's missing domain client), 4 unclear (`CLAUDE.md` and `init` both lead to a
    first launch before the bootstrap exists; the `niord.smtp` keys unnamed; `country-api.md` cites documents a
    country never gets; how to reach the base product's `settings.json`), 1 outside the guide (the `niord.smtp`
    keys read from niord-base's `config/niord-core-defaults.yml`) and 1 product bug (the local realm ships GL's
    `niord-gl-nw`). The launcher's fetch was done by hand to read `country-api.md` before the one bootstrap.
  - **Design** (the brief's fenced text and the two token files only; about 9 minutes): reached its end, with no
    reading outside them. One file, `tokens.qq.css` (an indigo primary, a violet accent, 3/5/8 px radii), every
    property of `tokens.default.css` and none of `tokens.base.css`; the route's own checker, validated first by
    reproducing the default file's figures, passed 60 of 60 rules. 14 gaps in the brief and the files it attaches: 3
    missing (no slot for the country, its code and its brand direction; no floor for the white button label, the
    header's text or the brand bar, though the brief invites pointing the button at the accent, and white on the
    default teal is 4.07:1; the fenced text dropped the title colour's and the danger label's rules), 3 wrong
    (white on `#b52d20` written about 5.5:1, measured 6.25:1; `#667589` written 4.75:1, measured 4.70:1; a
    `tokens.template.css` that no tree holds) and 8 unclear (the 65 % wash; "clearly distinct" without a measure;
    `font-size-adjust`'s threshold and form; a face assumed; the ratio comment's form; "18 separate values"; subtle
    text on `--niord-bg`, where the default is 4.34:1; the prose list of the base tokens).
  - **Operator** (the design route's file dropped into `qq`, then `operators-guide.md` and the manuals it links,
    in the administration app and Keycloak's console only; about 19 minutes): reached its end, with no reading
    outside them. The designer's file dropped in byte for byte, and set-up, the build (styles +75 B, within the
    baseline) and the spec were green. On the bootstrapped `niord_qq`: step 1's client `qq-nw` with its four
    chained roles and the `Sysadmin` group's role, by hand; the bootstrap's lists checked; the domain (active,
    Publish, `Europe/London`, its series); areas Quuxland > Quux Sound added to the domain; the series' numbering;
    `adminAppUrl` set; a first NW (Coastal, `qq-nw`, Quux Sound, English and Quuxish) saved, verified and published
    as `QQ-NW-001-26`, the series' next number then 2; the message on the public site in the designer's theme. TERM
    exit 130 in about 9 s; `niord_qq` and its home dropped. 8 gaps: 1 wrong (Batch Jobs lists no execution after
    a restart, so the bootstrap check could not be made), 3 unclear (the bootstrap checks come before step 1 but
    need it; the new client's capability settings; `adminAppUrl` empty on the page while the launcher passes it), 1
    missing (step 17 left out the required Type and the Verify step, which the linked manual has) and 3 product
    bugs (the editor heading's raw `editor.header.untitled`; the `secomEnabled` 404 on every Sysadmin page,
    already in `fe/docs/backlog.md`; the local realm's DK and GL clients and groups and its Danish sign-in page).
    Left behind on purpose: the client `qq-nw`, its roles and the `Sysadmin` group's role in the shared local realm.
  - **Fixed in the guides** (this change; the routes' own repositories are left as they were):
    `country-guide.md` (no Maven needed: the wrapper fetched with curl from Maven Central's
    `maven-wrapper-distribution` 3.3.4
    `only-script`, whose `mvnw` and `mvnw.cmd` are the base product's byte for byte, `mvn wrapper:wrapper` the
    alternative; `be/.gitignore`; `application-local.yml` whole, with `niord.smtp`; `niord-parent` declaring GRAD's
    repository; the exec bit with `git update-index --chmod=+x` in steps 1 and 2; *The base product's backend
    files*, a new section that fetches the niord-base jar and `niord-local-run` into `.local-run/<version>/read/`
    for `country-api.md`, `settings.json`, `settings-catalog.json` and the English backend texts; the wait for step
    7 before the first launch, and its remedy; the public site's footer of the country's own for the legal
    documents; the settings' types; a minimal `batch-set.json` and `domains.json` with the domain and series
    fields and the short-format tokens; *Your sysadmin in your own domain* for the local Keycloak, with the sign-in
    page's language); the kit README (*Starting a country* gives the wrapper and the smallest pom inline, the
    first launch waits for the bootstrap, the exec bit, the backend texts' English); `responsibilities-and-upgrades.md`
    (GRAD's repository in `niord-parent`; the new version's backend files at a bump); `operators-guide.md` (the
    Sysadmin checks after step 1, the Batch Jobs list until a restart, the missing-client case, the client's
    capability pages and where the group is, start-up values over the Settings page's, step 17's Type and Verify,
    the heading's raw key until fixed); `designers-brief.md` (fill-in lines for the country, its code, its brand
    and its face; floors for the button label, the text on the primary, the danger label, the title, the header's
    text and its brand bar; the wash defined as `color-mix(in srgb, <-bg>, transparent 35%)`; CIE76 ΔE of at least
    39 from the attention rail, from `tokens.base.css`'s own figures; `font-size-adjust`'s band and form; literal
    statuses with equal values allowed; the ratio comment's form; subtle text on `--niord-bg`; the base tokens by
    name); `templates/CLAUDE.md` (the contract read before the backend is written, the first launch after the
    bootstrap, `be/.gitignore`, the exec bit, the local domain client). `styles/tokens.default.css` and the
    example's `tokens.xx.css` (comments only): 6.25:1 and 7.96:1 for the danger label, 4.70:1 for `fg-subtle`, no
    `tokens.template.css`, and each status `-fg` with its three ratios on its line; the backlog item for the
    dangling reference is removed with it.
  - **Recorded, not fixed:** `be/docs/potential-errors.md` BATCH5 (the in-memory job repository forgets the
    bootstrap's job logs at a restart) and REALM1 (the local realm's other deployments' clients and groups and its
    Danish default locale; the operator route's and the agent route's product bugs); `fe/docs/backlog.md`: the
    editor heading's raw key, the default `fg-subtle` below AA on `--niord-bg` and `--niord-surface-sunken`, the
    first launch that can spend a new country's database (`init`'s closing line, the launcher's base-only
    bootstrap), the launcher making the local domain client, the guides reaching a first maintainer only inside the
    zip, and `be/docs/country-api.md`'s references to documents a country never gets, for the next `be/` documents
    pass. Not done: a `be/bootstrap/` in the example country, which the agent route proposed, since only the
    example harness, which this run may not start, would exercise it.
  - **Run:** `npm run docs:check` 35 files, no errors, the 7 earlier warnings; `node --test
    country-kit/country-words.test.mjs country-kit/zip.test.mjs country-kit/init.test.mjs` 79 of 79; prettier over
    the changed `fe/` files. The new commands were tried in a scratch folder against `.m2-p8b`, offline: the curl
    wrapper fetch, the smallest pom's `validate` and `dependency:copy` of the source zip (`BUILD SUCCESS`), and the
    read section's fetch and unpack. No route ran again on the fixed guides.
  - **D5 against _Done when_:** not met by this run. All four routes reached their end, and the design and
    operator routes needed nothing outside the guidelines, but the person and agent routes did: the bootstrap
    formats, the English backend texts, the `niord.smtp` keys, a Maven install and the contract before the first
    launch. Each of those is now in the guides. D5 is met once the person and agent routes run again on the fixed
    guides without help from outside them; the operator route's deploy half is verified at the deploy step (G8).
- **The person and agent routes again, round 2 (D5), as run** (2026-10-26, NG-14, `p8a/routes`, on the guides of
  `100c8c7c`). Each route ran in a workflow agent from the base product built locally as `4.0.999-p8c` into
  `.m2-p8c`, its source zip's hash checked with `sha256sum -c` against the release-notes line the build step handed
  it (`88975e1f…b3b`); the local build stood in for GitHub Packages as in round 1, the environment, not a gap. Not
  run: `image:info` and the example harness (the run's rules), `ci.yml` and `image.yml` on GitHub (no pull
  request, no registry), steps 10 and 11. The shared `niord-db`, `niord-keycloak` and `niord-smtp` kept their
  `Created` and `StartedAt`, and `niord`, `niord_test` and `niord_gl` were only read. Each route dropped its
  database and its Niord home, stopped its processes and freed its ports, and left its domain client, the client's
  four roles and the `Sysadmin` group's role in the shared local realm, as the rules allow. Minutes are the routes'
  own step times.
  - **Person** (Quapland `qp`, UI English and the home content pack `qy` with its flag, from an empty git
    repository by `country-guide.md`, reading only the zip, the backend files the guide fetches into
    `.local-run/<version>/read/` and the repository itself; about 71 minutes): reached its end, with no help from
    outside the guides. Green: step 1's `validate` with the wrapper fetched by curl; `init` with every file
    `wrote` and none `kept`; set-up, `npm ci`, build, test, lint and e2e; the pack's flag copy and
    `require-staged-packs`, which refused with the guide's message while the staged packs were moved away;
    branding with the legal documents and the public site's cookie statement; a theme moved to the flag's teal
    (8.42:1 and 10.91:1, measured); the environments, a public-site footer of its own with its spec, an e2e spec
    and the bundle baseline, after which the build gave no warning; `./start-local.sh` with both archives in one
    run, the `(country)` `niord.home` line and the languages on `/rest/settings/web`; `npm run e2e` against it;
    the local sysadmin's client `qp-nw`, after which Editor, Admin and Sysadmin showed and Sysadmin > Domains
    listed `qp-nw`; TERM exit 130 with the three ports free; `./mvnw verify` with the group (114 unit tests, 1
    skipped; 14 ITs) and the group check. Step 9's `ci.yml` was written and committed, not run. 8 points: 1
    missing (the producer code's form: the first launch stopped at start-up on `QP`, after the launcher had made
    and marked `niord_qp` and Liquibase had given it its tables; the backend's own message gave the form, and
    `QP00` started), 3 unclear (that such a database still waits for its bootstrap, which the second run applied
    with no drop; where the `branding-unaccepted.json` lists live and their entries' form, which the route read
    from the kit README and the `CODEOWNERS` that `init` wrote; step 1's dependencies, half of them without a
    `groupId`) and 4 product observations: REALM1 (already recorded), the `secomEnabled` 404 (already in
    `fe/docs/backlog.md`), the public site's missing-translation warning for `SITE_TITLE` on every cold load in
    the development server (new), and on Windows an `ng serve` that outlived its killed npm and kept its port
    until `taskkill`. That last is Windows' process model when npm is ended by other than Ctrl+C, not a base
    product defect as far as this run shows: Ctrl+C in an interactive terminal is expected to stop both (not tried
    in this run), and the launcher already stops a listener that outlived its shell by its PID.
  - **Agent** (Qubland `qb`, English only; the orchestrator's helper made the precondition from the kit README's
    _Starting a country_ alone, then the agent read the generated `CLAUDE.md` and what it points to; about 27
    minutes): reached its end, with no help from outside the guides. Green: the wrapper by curl and the smallest
    pom's `validate`; the four commands, every file `wrote`; set-up, `npm ci`, build (the baseline started as its
    warning says, then no warning), test and lint; the backend from steps 1 and 7 (the domain `qb-nw` in
    `Europe/Oslo` with its series), its test-compile green at the first try; `./mvnw verify` with the group (114
    unit tests, 1 skipped; 14 ITs; `CountryDefaultsChecksTest` listing the 18 `useBaseDefault` keys) and the group
    check; the exec bits (100755), and a CRLF clone whose scripts still run; `./start-local.sh` on 4300/4500/8988
    with 2 archives in one run, the marker applied and `qb-nw` made; the local sysadmin's client and Sysadmin >
    Domains; the public site; SIGINT exit 130. GRAD's repository refused connections again, and the warm cache
    served SECOM as the guide says. Not run: `npm run e2e` and step 9. 4 points: 1 missing (step 3 named every
    manifest field but `name`, which `init` writes as the code in capitals and the launcher prints), 2 unclear
    (`testcontainers-keycloak`'s dependency must name its version, which nothing manages, so a pom written from
    the bullets fails `validate`; `JAVA_HOME`, which set-up's Maven needs, was named only in the kit README's
    _Set-up_, and the first set-up failed without it) and 1 product bug (the `secomEnabled` 404, already
    recorded).
  - **Fixed in the guides** (this change; the routes' own repositories are left as they were): `country-guide.md`
    (_What you need_: `JAVA_HOME` exported, which set-up's Maven reads and only the launcher replaces with
    `~/.jdks/temurin-25`; step 1: every dependency as `groupId:artifactId`, which ones take a version and why
    `testcontainers-keycloak` does, and `application.yml` whole, with the producer code's form, four upper-case
    letters or digits, `QQ00` as the example and a placeholder of that form until the authority has its own;
    step 3: the manifest's `name`; step 5: the `branding-unaccepted.json` lists beside the assets folders with
    their entries' form, and how to free a port a development server kept on Windows; _Run it locally_: a run
    whose backend did not start posted no bootstrap, so the next run bootstraps the same database with no
    `dropdb`, and the two console messages until fixed; _Before go-live_: the lists' paths); the kit README's
    _The databases_ (the same case); `templates/CLAUDE.md` (`JAVA_HOME` above the commands, and two red reasons:
    Maven's `JAVA_HOME` message, and the producer code's refusal, after which the launcher runs again with no
    `dropdb`).
  - **Recorded, not fixed:** `fe/docs/backlog.md`: the public site's `SITE_TITLE` warning (the title strategy's
    probe of the bundle; a production build logs nothing), and set-up finding no JDK where the launcher finds
    one (`~/.jdks/temurin-25`). Already recorded, and left: REALM1 and the `secomEnabled` 404. Not taken: a
    complete deployable pom for the made-up country in the kit, the person route's alternative, since the list
    now gives every coordinate and only the example harness, which this run may not start, would build it.
  - **Run:** `npm run docs:check` 35 files, no errors, the 7 earlier warnings; `node --test
    country-kit/country-words.test.mjs country-kit/zip.test.mjs country-kit/init.test.mjs` 79 of 79 (the
    template's byte-for-byte case among them); prettier over the changed `fe/` files. No route ran again on this
    round's fixes.
  - **D5 against _Done when_:** met for what runs locally. Every route has now reached its end without help from
    outside the guidelines: the design and operator routes in round 1, the person and agent routes in this round.
    The person route lost one launcher run to a gap of the guide, the producer code's form, and got past it from
    the backend's own message, inside what a country receives. This round's fixes are clarifications that no
    route has run again, and none was needed to reach the end. Outside this verdict: `ci.yml`, `image.yml` and
    `pack.yml` on GitHub, steps 10 and 11 and the operator route's deploy half, verified at the deploy step (G8);
    `image:info` and the example harness, which this run's rules kept out; `--sample`, which no route of this
    round ran.
- **The kit's own package (the owner's ruling of 2026-10-26), decided, and built as the kit move** (_The
  kit move, the step after P8a_, below; #237, published as 4.0.109). The country kit, its guides,
  `API-CHANGES.md`, the backend contract `be/docs/country-api.md` and the templates move out of `niord-fe-src`
  into a published package of their own, for example `org.niord:niord-country-kit` of the same base product
  version, pinned in a country's lock by its own sha256 and unpacked at the country repository's root;
  `niord-fe-src` keeps the frontend sources only (§3.1). Why: the guides and the backend contract are for a
  country's whole repository, its backend and its operators as much as its frontend, and today they reach a first
  maintainer only inside the frontend zip (round 1's _Recorded, not fixed_). At the move `country-api.md` is no
  longer read from `niord-local-run`, and the guides' paths `fe/.niord/base/country-kit/…` and the agent
  template's change with it. The order: #229's review fixed (the next bullet) and #229 merged, then the move as
  its own step (its own feature branch, review and route re-run), then DK-1 and GL-1, which bump once, onto the
  version that has both.
- **The review of #229 (2026-10-26), as fixed** (`p8a/review-229` into `feature/p8a-guides`). #229 was reviewed
  at `3542869f`; the review was pasted to the orchestrator, not posted on GitHub. Its verdict: ready after the two
  factual fixes; a useful local-development guide, with further work needed for a fully plug-and-play set-up.
  Each point was checked against the code before it was fixed. The guides are in `fe/country-kit/docs/`; the kit
  README and `templates/CLAUDE.md` in `fe/country-kit/`.
  - **1. The public site's time zone (P2).** The code: the public site formats every time it shows with
    `InfoConfig.timeZone` (`fe/ui-apps/info/src/app/niord-info.config.ts:13`), default `'UTC'`
    (`country-specific/default/info.config.ts:10`), used by `core/services/date-format.service.ts:54,107` and by
    the footer's year; it never reads a domain, and `init` writes no `info.config.ts`. The administration app takes
    the zone of the domain chosen in its menu, or the browser's own without one
    (`core/main-shell/services/locale-format.service.ts:115-122`). The backend's texts take the domain's zone
    (`FmTemplateService.java:173-176`), and so do its PDFs (`MessageReportRestService.reportTimeZone`, 563-589),
    which the public site offers through `/rest/message-reports` (`message-pdf-url.ts:27`). Changed:
    `operators-guide.md` step 2's _Time Zone_ bullet (the public site uses the zone its build names, UTC unless
    set, a maintainers' setting, with a link to the country guide), and the same claim in _The server's clock_;
    `country-guide.md` a new sub-step _The public site's time zone_ in step 6, with the whole `info.config.ts`,
    why it should match the domains' zone (so the page and its PDF agree) and how to see it worked, the seams
    bullet naming `timeZone`, the paragraph on the JVM in UTC corrected and a new _Before go-live_ item;
    `templates/CLAUDE.md`'s zone rule.
  - **2. A new language in an existing database (P2).** The code: start-up only adds the keys a database lacks
    and never changes a stored one (`SettingsService.addMissing`, `SettingsService.java:197-220`). The keys are
    `modelLanguages` and `defaultLanguage` (`NiordApp.java:75-94`) and `siteLanguages`, `infoSiteLanguages` and
    `infoDefaultLanguage` (`FrontendSettings.java:27-43`), all web and editable. The administration app's
    `resolveLanguages` (`core/i18n/language-settings.service.ts:38-75`) drops a UI code with no bundle and takes
    the first of `modelLanguages` when `defaultLanguage` is not among them; the public site's
    `resolveInfoLanguages` (`core/services/web-settings.service.ts:40-62`) keeps only shipped languages and takes
    the first when `infoDefaultLanguage` is not among them. Changed: `country-guide.md`'s _Recipe: add a language_
    has a new step 6 and a section _Turning it on in a running deployment_ (why, the five keys with their value
    forms and rules, Sysadmin > Settings, reload both applications, a value given at the server's start wins for
    the two text keys `defaultLanguage` and `infoDefaultLanguage` only, while the three lists are JSON and both
    applications read the stored value, and how to see it worked: `/rest/settings/web`, the administration app's
    header language menu, the editor's field and the public site's menu); `operators-guide.md` a new section _A
    new language in a running deployment_ after step 17, with a pointer from step 15, which also covers existing
    names and texts that have no text in the new language yet. The review of this fix found the start-up override
    holds only for a text setting: `SettingsRestService.getWebSettings` (104-108, 122-127) applies a system
    property or environment value only to a `String` setting, and the three lists are `json` in the catalogue;
    so both guides limit it to the two text keys, and the operators' guide's step 15 now says the same for every
    setting (a list or other JSON setting is changed only on the page).
  - **3. Node and npm versions.** The code: `fe/package.json:45-48` has `engines.node`
    `^22.22.3 || ^24.15.0 || ^26.0.0` and `packageManager` `npm@11.11.0`, which `lib/workspace.mjs:436` copies into
    the generated `package.json`; `fe/.nvmrc` is `24`, and the kit's workflow templates set up Node `24`; the
    launcher only checks that `node` and `npm` are on the PATH (`templates/start-local.sh:127,263`). Changed: the
    country guide's _What you need_ and the kit README's _Prerequisites_ state both ranges and recommend Node 24,
    24.15.0 or later, with npm 11 (this workstation: Node 24.15.0, npm 11.12.1), with the check commands and that
    npm only warns (`EBADENGINE`).
  - **4. The upgrade's manual actions.** The code: the upgrade report's _Versions_ names the new
    `API-CHANGES.md` sections (`lib/upgrade-report.mjs:445`), and their actions are listed, shortened, under _To
    look at_, which set-up does not check (488-493, 566-573) (the shortening went in NG-B2, _NG-B as built_). Changed: `responsibilities-and-upgrades.md`'s _How
    an upgrade works today_ (step 4 reads To do, then To look at; new step 6 _Do what API-CHANGES.md asks_, every
    new section's "What a country must do" in full wherever the report lists it; new step 7 _Merge the agent
    template_; the steps renumbered to 9; _It worked when_ includes both) and its _To look at_ bullet; the country
    guide's definition-of-done bump item; `templates/CLAUDE.md`'s base bump step 4.
  - **5. A complete minimal backend pom.** Decided: inline in `country-guide.md` step 1, not a template file
    (the reader follows one place; a template `init` does not write would need its own accounting in the kit
    README and the zip and init tests; the templates move with the kit anyway). It is
    `routes/qb/be/pom.xml` (round 2's agent route, built green, no packs) without `<name>`/`<description>`, with
    two placeholders only: `qq` for the code and `VERSION` for the base product version, as in the kit README's
    smallest pom; an unreplaced `VERSION` fails `validate` instead of building on a stale base. A short "why each
    part" list keeps every fact of the dependency-by-dependency prose; the packs' and the bootstrap unpack's
    additions and the SECOM paragraph stay. The kit README's _Starting a country_ says step 1 gives the whole pom.
    This reverses round 2's _Not taken: a complete deployable pom_ (above), which stays as written. Built in a
    scratch folder outside the repository, `routes/pom-check/be`: the pom extracted byte for byte from the edited
    guide by a one-off script (equal to qb's minus `<name>`/`<description>` with qb→qq and the version→`VERSION`),
    only `VERSION` replaced by `4.0.999-p8c`; the application class and both yml files from the guide's blocks;
    `defaults/country/` from qb's as `QQ`; `overrides.json` empty; qb's curl-fetched wrapper. With
    `JAVA_HOME=C:/Users/<user>/.jdks/temurin-25` and
    `./mvnw -B -Dmaven.repo.local=…/.m2-p8c -Dmaven.repo.local.tail=~/.m2/repository`: `validate` `BUILD
    SUCCESS` (17 s wall, Maven 0.374 s); `package` `BUILD SUCCESS` (43 s wall, Maven 25.355 s), 114 tests from the
    base test-jar in 10 classes, 0 failures, 0 errors, 1 skipped (`CountryContractGuardTest` 29,
    `CountryContractStartupTest` 6, `SecurityConfigFrameOptionsTest` 2, `SecurityConfigJwtTest` 7,
    `WebAppConfigTest` 7, `CountryDefaultsChecksTest` 1, `MailHtmlTest` 22 with the 1 skipped,
    `FmTemplateClassResolverTest` 10, `NiordDomainFilterTest` 25, `RestControllerSecurityAnnotationTest` 5), and
    `target/niord-qq-4.0.999-p8c.jar` (177,062,908 bytes). No `-o` was needed. The guide's pom with `VERSION` left
    in, offline, fails `validate` with "Non-resolvable parent POM … `org.niord:niord-parent:pom:VERSION`
    (absent)", exit 1. The local build stood in for GitHub Packages (no token on the workstation).
  - **6. "It adds no beans".** The code: the backend contract's _Extension points_ (`be/docs/country-api.md`
    36-54) allow a country beans of its own. Changed: `be/README.md` says a minimal country application needs no
    beans of its own beyond its application class, names the extension points where the contract allows a
    country's own bean, and that it never allows a protected one.
  - **7. The completion boundary.** Changed: a paragraph near the top of the guides' index (`README.md`) and three sentences in
    the country guide's introduction: local development is fully guided and has been followed end to end; the
    image push ends in a placeholder the country writes (steps 9 and 10); the deployment steps (10 and 11, and
    the operators' guide's parts on a deployment) are verified only at the deploy step.
  - **Left:** the root `CLAUDE.md` still says "Node 22+", where `engines` asks for 22.22.3, 24.15.0 or 26, for
    the next documents pass; no product code changed and no defect was recorded; `API-CHANGES.md` unchanged (its _Unreleased_ entry
    already tells a country to merge the template's sections).
  - **Run:** `npm run docs:check` 35 files, no errors, the 7 earlier warnings; `node --test
    country-kit/country-words.test.mjs country-kit/zip.test.mjs country-kit/init.test.mjs` 79 of 79 (the
    template's byte-for-byte case among them); prettier over the 6 changed `fe/` files, all unchanged
    (+339/−62 over those 6); a one-off anchor check, 134 links across the five guides, the kit README and
    `templates/CLAUDE.md` (a whole-text scan; a per-line scan counts 133, missing the one link whose text wraps
    over a line break in the kit README), every one resolving to a heading slug, the new `#the-public-sites-time-zone` and
    `#a-new-language-in-a-running-deployment` among them; the pom build above. After the review's fix of item 2
    (two guide files), prettier left both unchanged, `docs:check` again 35 files, no errors, 7 warnings, and the
    three kit test files again 79 of 79; that fix adds and removes no links.
  - **Not run:** a live check of a published message's times on the public site in a country's zone and of a
    language switched on in an existing database, at the deploy step or on the next route run; the image push and
    steps 10 and 11, at the deploy step; `./verify-local` on this change, not yet run.
  - **The owner's first guide comments (2026-10-26), applied** (`p8a/review-229-images`):
    `responsibilities-and-upgrades.md`'s _What you own and maintain_ split into _Every country_ and _Advanced, only
    if you choose them_ (the backend's static-image overrides among the advanced, as upgrade debt), the country
    guide's advanced passages marked **Advanced:** (steps 4, 6 and 7, with a legend line linking that heading), the
    compatibility promise's opening line (every country's API and the set-up stub; overrides outside), and the
    upgrade's pull request in the country's own repository (the opening line and step 9) with a flowchart; the
    Renovate pointer waits until Renovate is built; his review continues after the republish.
  - **The drawings (2026-10-26)**: 8 Mermaid diagrams where a guide describes a flow, an order or a layering: the
    index's big picture (`README.md`), the upgrade flow (`responsibilities-and-upgrades.md`), the style layers
    (`designers-brief.md`), the steps as a flow and the launcher's plain run (`country-guide.md`), the bootstrap's
    one-time answers, what depends on what in the set-up order and the first sysadmin's two roles
    (`operators-guide.md`); each checked against the code it shows, and the country guide's _Run it locally_ now
    names the launcher's fetch first, as the drawing and `templates/start-local.sh` do. Rendered with Mermaid
    10.9.1 in a scratch page: 8 of 8 SVGs, no syntax error, `#lt;cc#gt;` shown as `<cc>`; `docs:check` unchanged
    (35 files, no errors, the 7 earlier warnings); `country-words` and `zip` tests 37 of 37; prettier clean on the
    5 guides. Left out: who reads which guide (the index's table says it), set-up's pipeline (the kit README's
    _Set-up_ describes it, in the order pins, zip verified, stub and packs staged, checks, staging, workspace) and
    the backend build after set-up (two commands). `./verify-local` on this change, not yet run.
  - **Their review (2026-10-26)**: the style layers' arrows both read _then_ (a token of the base product's is
    never redeclared, as the brief's rule says); the compatibility promise's opening is two sentences, the second
    only that overrides sit outside it; the set-up order gains the arrow from the domains to the firing schedules
    (step 13 asks for a domain and a target domain). Rendered again: 8 of 8 SVGs, no syntax error; `docs:check`
    (35 files, no errors, the 7 earlier warnings), `country-words` and `zip` tests 37 of 37, prettier clean.
  - **The owner's GO (2026-11-01), applied** (`p8a/review-229-images`): the responsibilities guide's overview line
    (the country guide for setting up and building, the operators' guide for the running system, its own _How an
    upgrade works today_ for upgrading) and its links into the country and operators' guides; "for most countries
    it is all they need"; the Dashboard approval dropped (§4.11, §9 and the four files of `be/docs/cicd/`), with
    "green means no task" in _From Renovate's install_, and `templates/CLAUDE.md` checked and unchanged (it never
    named the approval); the country code as the ISO 3166-1 alpha-2 code in lower case; the producer code tagged
    for S-124 only, with what the backend does without it (it starts, generates no S-124 data and answers the
    public S-124 request for a message with an error; a malformed code stops it; with SECOM on it does not start
    without one); the operators' set-up drawing with all 17 steps, each arrow checked against the code (two arrows
    dropped: categories to firing schedules, areas to the first message; three added: domains to publications,
    areas to promulgation types, domains to AtoN data), the six steps that need no other step in an unstyled box.
    The upgrade's Renovate wording waits for Renovate (§9). Prettier clean on the three changed guides; a one-off
    anchor check over the five guides, `templates/CLAUDE.md` and the kit README, 167 fragment links, none
    unresolved; rendered with Mermaid 10.9.1 in the scratch page, 9 of 9 SVGs, no syntax error. `docs:check` 35
    files, no errors and the 7 earlier warnings, and the three kit test files 79 of 79, once the entry that the
    merge of `main` brought into `fe/docs/backlog.md` was reworded to name no removed path and no country word.
    `./verify-local` on this change: see the sub-PR.
- **The kit move, the step after P8a.** The kit leaves `niord-fe-src` for a package of its own (_The kit's own
  package_, above), on `feature/kit-move`, cut from `main` at `5e1d3ec6` once #229 had merged; its PR into
  `main` is the draft #237, opened when KM-1 (#236) had merged.
  - **The owner's rulings:**
    - 2026-10-26: the kit moves out of `fe/` into its own published package, which carries the kit, the guides,
      `API-CHANGES.md`, the backend contract `country-api.md` and the templates, is pinned by its own sha256 in
      the lock and is unpacked at the country repository's root; `niord-fe-src` keeps only the frontend sources.
      It is its own step after #229 (its own feature branch, review and route re-run), and DK and GL bump once,
      onto the version that has both.
    - 2026-11-01: agent-neutral names. In the move itself the kit's agent template becomes
      `templates/AGENTS.md`, with every mention of it, and the guides say "an AI agent"; in a separate commit of
      the same feature branch niord-ng's own `CLAUDE.md` files hold their content as `AGENTS.md`, each with a
      one-line `CLAUDE.md` importing it (`@AGENTS.md`), once it is checked whether Claude Code reads `AGENTS.md`
      natively; DK and GL rename theirs at their bump.
    - 2026-11-01: a country's code is its ISO 3166-1 alpha-2 code, two lower-case letters. The kit tightens to
      exactly two letters where it accepts two or three today: `init`, the manifest's `country` and the launcher.
      The manifest's language codes are a separate rule and keep two or three letters.
    - 2026-11-01, for GL's bump: the Danish pack's wording wins. Where GL's root `*_da` backend texts differ from
      `niord-lang-da`'s, GL takes the pack's wording and the root files are removed; GL's own `gl.` keys stay in
      its country layer, and GL-1 attaches the diff to its PR as the record.
  - **The design.** A read-only design over the tree at `96b2fb86` and DK and GL at their `main` (`9f09d19`,
    `555b7c7`), with an independent critique's corrections folded in.
    - _The package_ is `org.niord:niord-country-kit:4.0.<run>:zip`, the groupId of the whole-repository packages,
      published by the same `publish.yml` run at the same version as `niord-fe-src`: one release of several
      artifacts (§3.1). It carries the kit's tree without its tests, its fixtures, `country-words.json` and
      `source-zip.mjs` (niord-ng's publish tool; set-up's `NIORD_BASE` copy takes `gitFiles` from `lib/tree.mjs`):
      the guides, `API-CHANGES.md`, the README, the templates and the example country, `example/fe/` and
      `example/be/`, which the upgrade test reads (the harness, `example/{run,acceptance,pseudo-localise}.mjs`,
      runs only in niord-ng's tree beside `source-zip.mjs` and stays out; _KM-3 as built_), every data file
      (`seams.json`, `security.json`, both schemas, `info-image-allowed.json`, `secret-scan.json`,
      `workspace-files.json`) and
      `zip-include.json`, which describes the `niord-fe-src` of the same version and which set-up's override
      check and the `NIORD_BASE` copy read. The build adds `docs/country-api.md`, copied from
      `be/docs/country-api.md`, where the backend's contract tests and checks keep reading it, and the marker
      `kit.json`.
      `niord-local-run` stops carrying `country-api.md` (`be/scripts/local-run-zip.sh`,
      `be/scripts/local-run-README.md`), so the document has one published home. The package is defined by a new
      `kit-include.json` and built by a `source-zip.mjs --package kit` mode with the same build, verify and
      guard; the country-word guard and the import-closure check (no relative import leaves the package) run on
      it. `niord-fe-src` keeps the frontend sources and `catalog.json` at its root, so the published-catalog
      readers and `pack.yml`'s `latest-catalog` are unchanged.
    - _The tree move._ niord-ng's `fe/country-kit/` moves to a root `country-kit/`, so that `fe/` is exactly
      `niord-fe-src` and `country-kit/` exactly the kit package, and a crossing import shows as a `../` path. The
      CI filters are re-prefixed in the same sub-PR: `fe-ci`'s push paths gain `country-kit/**`, its frontend
      rule `^country-kit/`, and its example rule becomes `^country-kit/` plus `be/docs/country-api.md`;
      `info-ci`'s 16 kit entries (14 when the design was made, `lib/where.mjs` since KM-1 and `.prettierrc`
      since KM-2), kept three times,
      in its push paths, its Detect pattern and `verify-local`'s `INFO_RULE`; `verify-local`'s ADMIN, INFO and
      EXAMPLE rules and its Prettier list. A new guard test holds the three `info-ci` copies equal; today nothing
      does, and a missed copy skips Info CI silently. The run commands that name the kit's path move with it
      (`publish.yml`'s `source-zip.mjs` step, `fe-ci`'s and `verify-local`'s `example/run.mjs`), and so does the
      reach of `format`, `format:check` and the pre-commit hook's lint-staged, which run from `fe/`. The kit gets
      a `.gitattributes` with LF line ends (its templates are compared byte for byte), a `.prettierrc` held to
      `fe/.prettierrc` by a parity test, an ESLint configuration that loads its plugins through
      `createRequire(fe/package.json)` and one test helper giving its tests `fe/node_modules`; planted probes, a
      lint error and a failing test in `country-kit/`, must be reported, with ESLint run from `country-kit/`
      (the tree runs ESLint 10.2.1, which also lints a file outside its working folder by that file's own
      configuration; KM-2 kept the run from `country-kit/`). The fallback, if the probes cannot be made to pass: the
      source stays at `fe/country-kit/`, and then the resolver's source rule stays as KM-1 built it and
      `NIORD_BASE`'s kit is `<NIORD_BASE>/country-kit`; the package, the resolver and the closure check stand on
      their own.
    - _The country layout._ The kit unpacks into `<root>/.niord/kit/`, with the marker `<root>/.niord/kit.sha256`,
      and its zip is kept as `<root>/.niord/niord-country-kit-<v>.zip`, where the next upgrade report reads the
      previous kit. `niord-fe-src` still unpacks into `fe/.niord/base/`, and the packs and the build output stay
      in `fe/.niord/`, so every generated Angular, TypeScript, Vitest, proxy and Playwright path, the country poms'
      pack copies and the image jobs stay as they are; only the generated kit commands move to `../.niord/kit/…`
      (`lib/workspace.mjs`'s scripts and its country-lint require, `templates/pack.yml`, the launcher's message).
      `<root>/.niord/.gitignore`, holding `*`, ignores the folder itself: the stub writes it, and `init` too,
      since a first-time path unzips the kit and runs `init` before any stub run; no root `.gitignore` line is
      needed, and `init` still adds `/.local-run/`.
    - _One resolver_, `lib/where.mjs`, replaces every "the base product is the kit's parent" rule, and the marker
      `kit.json`, `{package, version}`, written into the package at build, tells the package layout; anything the
      resolver does not know is refused, where `pack.mjs`'s `packsLayout` fell back quietly to a wrong layout. KM-1
      built it with a third, transitional layout, the kit inside an unpacked `niord-fe-src` (_KM-1 as built_,
      below); the design's two layouts, the source tree and the package, are what is left when `niord-fe-src`
      stops carrying the kit, which is after the move version, whose zip still carries the tombstone: the in-base
      layout goes in NG-B (done in NG-B3, #255; _NG-B as built_). The kit loads base product code it needs from the
      base it resolves, and the base product keeps its own copies of the two things it took from the kit, held equal
      by tests.
    - _The lock_ stays format 1 with one key added, `"kit": {"sha256": "<kit zip>"}`; the kit shares
      `base.version`. The old stub's lock reader ignores extra keys, which lets a stub from before the move reach
      the tombstone; a format 2 would stop it at "not a lock file of format 1", with no report. `lockText` and
      `writeLock` carry `kit`, `--update-pins` keeps it, and the new stub refuses a lock without it, naming the
      release-notes line to pin. The build report's base record gains the kit's sha256.
    - _One stub_ still suffices, `fe/setup.mjs`, Node built-ins in one file: it reads the lock, fetches the kit by
      `mvn dependency:copy` into `<root>/.niord/`, checks its sha256 against `lock.kit`, unpacks it (a temporary
      folder, then a rename) into `<root>/.niord/kit` and checks that `kit.json`'s version is `base.version`; it
      then fetches `niord-fe-src` into `fe/.niord/base` as now, skips a fetch whose marker matches, hands over to
      `<root>/.niord/kit/setup-kit.mjs` with both hashes and exports `parentVersion` unchanged. A cold start is two
      Maven runs; the second's time on Windows is not measured.
    - _`NIORD_BASE`_ still names a checkout's `fe/`. The stub hands over to that checkout's kit
      (`<NIORD_BASE>/../country-kit`, or `<NIORD_BASE>/country-kit` under the fallback), which copies itself
      through `kit-include.json` into `<root>/.niord/kit` with `kit.json`, as `copyBase` copies the base product
      today, so the stub needs no glob matcher of its own and the country layout is the same in both modes.
      There is no `NIORD_KIT`. Both are refused under CI and recorded with `local:` provenance.
    - _The transition, a one-version tombstone._ DK and GL bump once, from 4.0.97, and their 4.0.97 stub hands
      over to `.niord/base/country-kit/setup-kit.mjs`. The move version's `niord-fe-src` therefore still carries
      `country-kit/setup-kit.mjs`, built from `fe/scripts/kit-move-tombstone.mjs` (mapped in by the include
      list's optional `add` field, _KM-6 as built_), and `country-kit/templates/setup.mjs`, the new stub. The
      tombstone accepts the old handover and fails with its
      own text, which names the stub's copy at `.niord/base/country-kit/templates/setup.mjs` (`.niord/kit` does
      not exist yet for an old stub), and, when a set-up record names another version, writes the upgrade
      report with the copy step in _To do_: a stub change stays a failed check with the copy in the report, and
      the Example upgrade test from a release before the move runs with no special case. The harness accepts both
      texts, copies the stub from whichever copy exists, and its version bump writes the kit pin as well. Without
      the tombstone, the old stub dies on a bare module-not-found. The upgrade report and the previous-base
      lookup read the previous kit zip, and for a version from before the move the previous `niord-fe-src`'s
      `country-kit/` entries. The tombstone goes in NG-B, with an `API-CHANGES.md` entry (done in NG-B3, #255, with
      its entry in the new `Unreleased`; _NG-B as built_).
    - _Release notes and retract._ The tag job writes two `sha256sum` lines, `<sha256>  niord-fe-src-4.0.N.zip`
      and `<sha256>  niord-country-kit-4.0.N.zip`, and attaches both zips; a country checks each line in its own
      folder, since the frontend zip lands in `fe/.niord` and the kit's in `<root>/.niord`. The packages sentence
      names the kit, the by-hand recovery texts give both lines, `fe-ci`'s reader stays anchored on each name, and
      the tag job's artifact glob and its clean-up of the `niord-fe-src` artifact take the kit's as well.
      `retract-version.yml` retracts both packages and names both assets. Read access to the new package is
      expected to follow `niord-fe-src`'s registry, not verified.
    - _docs-check rule 7_ has two file sets, from the two builders' include lists: a document the kit package
      carries links relatively only inside the kit, a backticked `.niord/kit/…` path must be in the kit set and a
      backticked `.niord/base/…` path in the `niord-fe-src` set (a link into the base product has no relative form
      across the two roots, so it is written as such a path); a document `niord-fe-src` carries links nowhere into
      the kit. `KIT_TEMPLATE_DOCUMENTS` becomes `['country-kit/templates/AGENTS.md']`, docs-check gets the second
      root, and the template's `AGENTS.md` is in its scope, as niord-ng's three in `fe/` (`fe/AGENTS.md` and both
      applications') are from KM-8; the root's and `be/`'s stay outside its reach, as their `CLAUDE.md` did, and a
      link script checks them (_KM-8 as built_).
    - _`AGENTS.md`._ Claude Code reads `AGENTS.md` natively only where no `CLAUDE.md` is in the folder or above,
      so niord-ng's five folders (the root, `be/`, `fe/` and both applications) each get their content as
      `AGENTS.md` and a bare one-line `CLAUDE.md`, `@AGENTS.md`, with the root's Claude Code-only bullet (the
      Read/Grep/Glob and `run_in_background` advice) below the import. The kit's `templates/CLAUDE.md` becomes
      `templates/AGENTS.md`, its `.niord/base/country-kit/` paths `.niord/kit/`; `init` writes `AGENTS.md` and the
      one-line `CLAUDE.md` when neither exists, and keeps a country's own `CLAUDE.md`, printing that it must import
      `AGENTS.md`.
    - _The two-letter code_ is `^[a-z]{2}$` in `init`, the manifest schema's `country`, the launcher template and,
      as one definition, `lib/workspace.mjs` and `lib/secrets.mjs`; the launcher's unreachable three-letter
      `--sample` guard goes. A shape check only, so the made-up route countries take user-assigned codes
      (`qm`-`qz`, `xa`-`xz`). Language codes are unchanged, and no global replace of `{2,3}` is made.
  - **The sub-PRs**, each squash-merged into `feature/kit-move` after `./verify-local --base
    origin/feature/kit-move`, with its summary in the PR:

    | Sub-PR | What | Depends on | State |
    |---|---|---|---|
    | KM-1 | one layout resolver; the kit and the base product stop importing each other by relative path; the import-closure check in the source zip's verify; set-up's and the build check's reads of the kit's data from the kit | #229 merged, `feature/kit-move` cut | built, on `km/1-resolver` into `feature/kit-move` |
    | KM-2 | `fe/country-kit` moves to a root `country-kit/`, with its CI filters, run commands and tooling, and the `info-ci` guard test | KM-1 | built, on `km/2-tree-move` into `feature/kit-move` |
    | KM-3 | `niord-country-kit` as its own package: `kit-include.json`, `source-zip --package kit`, `kit.json` written by `markerText`, the closure check over the kit zip, `publish.yml`'s two lines and assets, retract | KM-2 | built, on `km/3-package` into `feature/kit-move` |
    | KM-4 | set-up for both layouts: the lock's kit pin, the build report's kit hash, the upgrade report and the previous base across both layouts, the workspace generator's kit path | KM-2 | built, on `km/4-setup-lock` into `feature/kit-move` |
    | KM-5 | docs-check rule 7, `templates/AGENTS.md`, the guides' paths and first-time steps | KM-3 | built, on `km/5-guides` into `feature/kit-move` |
    | KM-6 | the switch: the stub, the kit pin, `init` (`--kit-sha256`, `.niord/.gitignore`), the generated paths, the tombstone, the example harness | KM-3, KM-4, KM-5 | built, on `km/6-switch` into `feature/kit-move` |
    | KM-7 | the two-letter country code | KM-2 | built, on `km/7-two-letter` into `feature/kit-move` |
    | KM-8 | niord-ng's `CLAUDE.md` content as `AGENTS.md`, with one-line importers (its own commit) | KM-2 | merged into `feature/kit-move` (#239) |
    | KM-9 | the fixes of one review of the whole branch and of the route re-run | KM-1 to KM-8 | built, on `km/9-review` into `feature/kit-move` |

    KM-3, KM-4, KM-7 and KM-8 run in parallel after KM-2, with KM-7 merged early, since it touches guide lines
    KM-5 and KM-6 touch too; one worktree at a time runs the example harness, whose image tags are fixed. After
    them: MAIN, the draft PR marked ready, CI once, the owner's merge, and 4.0.N published with both packages;
    DK-1, DK's single bump onto 4.0.N; GL-1, GL's, once DK-1's merge has run `Pack` and published `da:3`; NG-B,
    the transitional root bundle layer of the backend's default texts and the tombstone removed (with
    `zip-include.json`'s `add` and its `templates/setup.mjs` include, the 4.0.97 stub fixture and
    `lib/where.mjs`'s in-base layout with the tests that pin it), after GL-1. Done: MAIN (#237), DK-1 (`niord-dk`
    #11), GL-1 (`niord-gl` #10) and NG-B (#253: NG-B1, #252, the layer; NG-B2, #254, the upgrade report; NG-B3,
    #255, the tombstone and the in-base layout; _MAIN, DK-1 and GL-1 as built_, _NG-B as built_).
  - **KM-1 as built** (2026-11-01, `km/1-resolver` into `feature/kit-move`). No behaviour change: the kit stays at
    `fe/country-kit` and inside `niord-fe-src`.
    - _The resolver_, `country-kit/lib/where.mjs`, Node built-ins only and importing nothing of the kit. `where(kit)`
      gives `{kind, kit, base, workspace, root, be}`, and `version` for a package, worked out once for the kit
      itself; it works out paths only and never checks that the base product is there, so a first set-up can ask.
      Three layouts: `source`, niord-ng's tree (the kit `<repo>/fe/country-kit`, the base product and the
      workspace `<repo>/fe`, the backend `<repo>/be`), recognised by the kit's parent being a folder named `fe`
      not inside a `.niord` folder, in one small function that KM-2 changes; `in-base`, today's country layout,
      the kit inside the unpacked `niord-fe-src` at `<root>/fe/.niord/base/country-kit` (the workspace
      `<root>/fe`, the backend `<root>/be`), transitional, and removed with the tests that pin it once
      `niord-fe-src` no longer carries the kit; and `package`, the kit at `<root>/.niord/kit` with its marker, the
      base product `<root>/fe/.niord/base`. Anything else is refused, naming the kit and the three layouts.
      `baseLayout(base)` classifies a base product folder (`source` or `country`) and refuses any other with
      "`<path>` is not where the kit finds the base product". So the design's "without the marker, the source
      tree" holds with the in-base layout beside it until KM-6.
    - _The marker_ is `kit.json`, exactly `{"package":"niord-country-kit","version":"<v>"}` and a newline, valid
      only at `<root>/.niord/kit`; `where.mjs` exports `MARKER`, `KIT_PACKAGE` and `markerText(version)`, so
      KM-3's builder and the reader share one definition. A marker with other keys, another package, a version
      outside `/^[0-9A-Za-z][0-9A-Za-z.+_-]*$/`, invalid JSON or another place is refused, naming the file.
    - _The callers_: `init.mjs` (the base product from `where()`; `skeleton()` and `init()` take a `kit`
      parameter and read the templates from it, not through `base/country-kit`), `image-info.mjs` (the base
      product from `where(kit)`, an unknown layout stopping it with exit 2; a country workspace is any layout but
      `source` run from its resolved workspace), `pack.mjs` (`packsLayout` and `main` through `baseLayout`,
      `packsLayout` keeping its four fields and refusing an unknown tree where it fell back quietly before),
      `catalog.mjs`, `stage-languages.mjs`, `source-zip.mjs`, `lib/build-checks.mjs`, `lib/info-image.mjs`,
      `lib/branding.mjs` (`readPreferences`'s default) and the example harness (`run.mjs`, `acceptance.mjs`,
      `pseudo-localise.mjs`; its `.niord/base/country-kit/…` command paths are KM-6's). `catalog.mjs`'s backend
      in the in-base layout is now `<root>/be` instead of a meaningless `fe/.niord/be`; the catalog is built only
      in the base product's tree, so only the error of an unsupported run changes. A kit copy outside the three
      layouts, such as a `NIORD_BASE` checkout folder not named `fe`, is now refused when a kit module loads,
      where it took the kit's parent before; the guides and the stub already say `NIORD_BASE` names a
      checkout's `fe/`.
    - _The kit's data from the kit._ `setup-kit.mjs` reads `zip-include.json` and `security.json` from its `kit`
      parameter, and `checkBuild` gains `kit = where().kit` for `secret-scan.json`, instead of
      `base/country-kit/…`. The design had these reads in KM-4's _Kit data_ bullet; they moved into KM-1, and
      KM-4 is smaller by that bullet.
    - _The kit into the base product_: `lib/build-checks.mjs` loads `scripts/build-urls-check.mjs`, and
      `lib/info-image.mjs` the public site's `nginx/precompress.mjs`, by a top-level `await import()` from the
      kit's own resolved base, so `checkBuild`, `readCountryUrlAllow` and `assembleContext` stay synchronous;
      `pack.mjs check` loads `scripts/build-contents-check.mjs` and `scripts/workspace-paths-check.mjs` the same
      way, from `where().base` rather than its `fe` parameter, since its test runs it on a temporary tree without
      `scripts/`.
    - _The base product off the kit_: `fe/eslint-shared.cjs` (CommonJS, no requires) holds
      `INFO_ANONYMOUS_PACKAGES`, `INFO_ANONYMOUS_MESSAGE`, `BYPASS_SECURITY_TRUST` and `INNER_HTML_PIPES`, byte
      for byte the values and comments of `country-lint.cjs`, and `eslint.config.js` takes its three lists from
      it; `fe/scripts/glob.mjs` is the matcher block of `lib/include.mjs` (`expandBraces`, `escape`,
      `braceFreeToSource`, `globToRegExp`), byte-identical, which `build-urls-check.mjs` imports. Their parity
      tests, `scripts/eslint-shared.test.mjs` (exactly the four exports, each deep-equal to `country-lint.cjs`'s;
      `eslint.config.js` requires `./eslint-shared.cjs` and names no `country-kit/` specifier) and
      `scripts/glob.test.mjs` (the block byte-equal, the behaviour table of `zip.test.mjs` with its refusals
      through both modules, and `build-urls-check.mjs` importing `./glob.mjs` and nothing of the kit), were red
      first, with the two files missing. Both files join `zip-include.json`; `workspace-files.json`'s
      `notNeeded` and `seams.json`'s protected list gain `eslint-shared.cjs`; the headers of `country-lint.cjs`
      and `lib/include.mjs` name the equal copies. `npm run lint:scripts` names the root `eslint-shared.cjs`
      as well (the review found it was linted only by hand).
    - _The import-closure check_ is in `source-zip.mjs` (exported `relativeImports` and `importClosureProblems`),
      the publish tool KM-3 leaves out of the kit package, and `verify` appends its findings. A small lexer
      collects the `./` and `../` specifiers of static, side-effect and `export … from` imports and of literal
      `import()` and `require()`, and skips comments, the whole of template literals (generated text such as a
      configuration the kit writes), regular expressions, a shebang and computed specifiers. Rule (a): a
      `.mjs`, `.cjs`, `.js` or `.ts` file outside `country-kit/` whose specifier resolves into it, "the base
      product never imports the kit". Rule (b), ready for KM-3: a kit `.mjs`, `.cjs` or `.js` file, outside
      `templates/**`, `example/fe/**` and `example/be/**`, whose specifier resolves outside the kit, "the kit
      imports the base product only from its resolved base". Red first: `verifySourceZip` returned nothing on
      the three planted crossings (`eslint.config.js`'s require, `build-urls-check.mjs`'s `include.mjs` import,
      `build-checks.mjs`'s `../../scripts` import), and the new real-tree case named exactly the four kit
      crossings (`build-checks.mjs` to `build-urls-check.mjs`, `info-image.mjs` to `precompress.mjs`, `pack.mjs`
      to its two check scripts), with no false positive on the 4.1 MB zip; green after the edits. `kit:zip` on
      the real tree could not be shown red, since the base product's two files had changed before the check
      existed; a scratch zip with HEAD's `eslint.config.js`, `scripts/build-urls-check.mjs` and
      `lib/build-checks.mjs` put back makes `verify` exit 1, naming all three. Open for KM-3:
      `example/acceptance.mjs` and `example/run.mjs` import `../source-zip.mjs`, which the kit package leaves out,
      so its closure check would flag both.
    - _The CI rules_: `info-ci`'s push paths gain `fe/eslint-shared.cjs`, `fe/scripts/glob.mjs` and
      `fe/country-kit/lib/where.mjs` (37 entries to 40), its Detect pattern and `verify-local`'s `INFO_RULE` the
      same three; `fe-ci`'s example pattern and `verify-local`'s `EXAMPLE_RULE` gain `glob` and
      `eslint-shared.cjs`. Before, a change to one of the first two alone skipped Info and Example, and to
      `where.mjs` alone Info. The copies that must be equal still are, compared as strings.
    - _Run_: `node --test country-kit/where.test.mjs` 9 of 9, red first (the module missing); the new
      `pack.test.mjs` refusal case red against the quiet fallback; the 17 targeted kit test files 494 tests, 492
      passed, 2 skipped (symbolic links not allowed for the account, as on the base commit); the base product's
      eight files around the lint lists and the matcher 193 of 193; `npm run test:guards` 1207 tests, 1203
      passed, 0 failed, 4 skipped (symbolic links and environment); `scripts/release-workflows.test.mjs` 38 of 38;
      `npm run lint:scripts`, `npm run lint` and `npm run lint:info` clean; `npm run languages:check` and
      `stage-languages.mjs --app admin` exit 0; `npm run build` exit 0; `npm run languages:catalog` exit 0,
      `.niord/catalog.json` unchanged; `npm run urls:check -- --dist dist/niord-app/browser` exit 0 (208 text
      files); `npm run kit:zip` into the scratch folder, listing `eslint-shared.cjs` and
      `scripts/glob.mjs`, with `verify` and `guard` exit 0; `bash -n verify-local`, both workflows parsing, and a
      script matching the rules over the three new files and four paths that must not match; Prettier over the
      changed `fe/` files. Not run: the example harness and `image:info`, which this sub-PR's rules leave to
      `./verify-local`. `./verify-local`: see the sub-PR.
  - **KM-2 as built** (2026-11-01, `km/2-tree-move` into `feature/kit-move`). The kit is `country-kit/` at the
    repository root, beside `fe/`; the source zip's layout, the meaning of its include list and the countries are
    unchanged.
    - _The move_ is a commit of its own, `e8ddd804`: `git mv fe/country-kit country-kit`, 199 renames with no
      change of content, and a new `country-kit/.gitattributes`, `* text=auto eol=lf`, since the kit's templates
      are compared byte for byte. The path fixes below follow it.
    - _The source rule_, `lib/where.mjs`'s `isSourceKit(kit)`: niord-ng's kit is a folder named `country-kit`
      with no `.niord` in its path and a parent not named `fe`; its base product and workspace are
      `<parent>/fe`, its root `<parent>`, its backend `<root>/be`. It is recognised by shape, as before, since
      `where()` checks no existence. `repo/fe/country-kit`, the place before the move, is refused, so a stale
      copy is never taken for the tree. The in-base and package layouts and `baseLayout()` are unchanged, and the
      refusal names "niord-ng's tree, <repo>/country-kit beside <repo>/fe". `where.test.mjs` was red first, 4 of
      10 failing with "the kit at …\p9-sample\country-kit is in no layout it knows: niord-ng's tree,
      <repo>/fe/country-kit; …", and is green, 10 of 10, with a case for a temporary `repo/country-kit` and the
      refusals `somewhere/kit`, `repo/fe/country-kit`, `c/.niord/fe/country-kit` and `c/base/kit`.
    - _The zip from two roots, under the same names._ `source-zip.mjs` exports `treeFiles({ fe, kit })`: the
      files git sees in `fe/`, named relative to it, and those in the kit beside it, named `country-kit/…`; a
      name both roots give fails with "is given twice". `sourceEntries`, `lib/local-sources.mjs`'s
      `checkoutFiles` and `copyBase`, docs-check, the country-word guard's tree mode, `seams.test.mjs` and
      `base-product-tree.test.mjs` read the tree through it, so there is one definition of the tree as the zip
      names it. `NIORD_BASE` therefore still fills `.niord/base/country-kit/`, and a checkout from before the
      move still copies, its kit files coming from `fe/`'s own list. `zip-include.json` leaves out
      `country-kit/{.gitattributes,.prettierrc,eslint.config.mjs}`, niord-ng's tooling for the root folder,
      which the zip would otherwise gain; its include list is otherwise unchanged. The kit's dynamic imports of
      base product code still go through `where().base`, and the base product's own copies, `eslint-shared.cjs`
      and `scripts/glob.mjs`, stay.
    - _The kit's tooling at the root._ `country-kit/eslint.config.mjs`, a flat configuration that loads
      `@eslint/js` and `eslint/config` through `createRequire(<fe>/package.json)`, holds the blocks moved from
      `fe/eslint.config.js` with their comments: the `example/fe/**` and `example/be/**` ignores, the Node
      script block with its own `NODE_GLOBALS`, the CommonJS block and the two exemptions of
      `templates/setup.mjs`; `fe/eslint.config.js` keeps `dist/**` and `.angular/**` as its ignores.
      `country-kit/.prettierrc` is `fe/.prettierrc` byte for byte. `country-kit/tooling.test.mjs` holds the two
      `.prettierrc` equal, the `.gitattributes` line, the kit's Node globals deep-equal to those of
      `fe/eslint.config.js` and `js-yaml` resolving in `fe/node_modules`; red first, 2 of 4 failing (no
      `.prettierrc`, no `eslint.config.mjs`), green 4 of 4. The kit's tests take their npm packages through one
      helper, `test-fixtures/base-modules.mjs` (`requireFromBase = createRequire(<where().base>/package.json)`):
      `createRequire`, not `NODE_PATH`, which does not apply to ES module imports. The kit's runtime code imports
      no npm package, so a kit script runs as `node country-kit/x.mjs` from the root and as
      `node ../country-kit/x.mjs` from `fe/`: `catalog.mjs --version 0.0.0-km2` from both exits 0 with equal
      bytes. The kit's tests take the base product from `where().base` and `workflow-step.mjs` from
      `../fe/scripts/`. `example/run.test.mjs` needed a path fix too: its case for a `--work` inside `fe/` passed
      `country-kit/example/work`, no longer inside `fe/`, so its first run went past the refusal, built the
      example zip into that folder and called Maven once, which failed before writing anything (no image, no
      Docker; the folder was deleted). The case now passes `<base>/work` and tests `parseArgs` rather than
      `main`, so a regression of the refusal cannot start the harness.
    - _`fe/package.json`_: every `node country-kit/` is `node ../country-kit/` (15 scripts); `lint:scripts` is
      `eslint "scripts/**/*.{mjs,cjs,js}" eslint-shared.cjs --max-warnings 0 && npm run lint:kit`, and the new
      `lint:kit` is `cd ../country-kit && eslint "**/*.{mjs,cjs}" --max-warnings 0`; `format` and `format:check`
      are `prettier --write --cache . ../country-kit` and `prettier --check --cache . ../country-kit`;
      `test:guards` is `node --test "scripts/*.test.mjs" "../country-kit/**/*.test.mjs"`; lint-staged gains
      `../country-kit/**/*.{ts,html,css,scss,md,json,yml,yaml}` (Prettier) and `../country-kit/**/*.{mjs,cjs}`
      (ESLint) after its three entries.
    - _The guards' second root._ docs-check reads the kit beside `fe/` (`readWorkspace(root, kit)`, `fileOf`),
      names the kit's documents `country-kit/…` as the zip does, so rule 7 is unchanged, resolves a backticked
      `country-kit/…` against the kit, and fails a backticked `fe/country-kit/…` in every document ("the kit is
      at country-kit/, beside fe/"); `country-kit` stays in `WORKSPACE_FOLDERS` until KM-5. Its test was red
      first, 8 of 18 failing against the old `docs-check.mjs`. The word guard's tree mode reads both roots
      (`treeEntries(treeFiles())`), and an unused entry "matches nothing in fe/ or country-kit/". The kit's
      probes in `eslint-boundaries.test.mjs` run on a second ESLint instance whose `cwd` is the kit, so they load
      its configuration; they were moved, not run red first, since the kit's configuration already existed.
      `build-contents-check`, `glob.test.mjs` and `eslint-shared.test.mjs` import `../../country-kit/…`.
    - _The CI filters._ The new `fe/scripts/ci-filters.test.mjs` holds info-ci's Detect pattern equal to
      `verify-local`'s `INFO_RULE`, its push paths and the pattern to each other, compared both ways by
      generated instances, and fe-ci's frontend and example rules equal to `ADMIN_RULE` and `EXAMPLE_RULE`; it
      checks that no workflow and not `verify-local` names `fe/country-kit`, that the kit's files and
      `be/docs/country-api.md` match the filters they belong to (push paths as GitHub matches them), the three
      run commands, and that Info CI's and `verify-local`'s Prettier lists are equal. Red first on a scratch copy
      of `e8ddd804`'s workflows and `verify-local`, since the workflows in the worktree were already edited: 3 of
      6 failing, "fe-ci.yml names fe/country-kit", `publish.yml`'s `node country-kit/source-zip.mjs build …` not
      matching `/^node \.\.\/country-kit\/source-zip\.mjs build /m`, and "country-kit/stage-languages.mjs is a
      file from fe/"; the three equality cases passed there, both copies still saying `fe/country-kit`. Green, 6
      of 6. The filters: fe-ci's push paths gain `country-kit/**`; its frontend rule and `ADMIN_RULE` are
      `^(fe/|country-kit/|\.github/workflows/(fe-ci|publish|retract-version)\.yml$)`; its example rule and
      `EXAMPLE_RULE` take `country-kit/` and gain `be/docs/country-api\.md$`; info-ci has 16 kit entries (41
      push paths), `country-kit/.prettierrc` added because its Prettier step checks the kit's files by that
      configuration, in its push paths, its Detect pattern and `INFO_RULE`; both Prettier lists name
      `../country-kit/…`. The three run commands keep `working-directory: fe` and run `node ../country-kit/…`:
      `publish.yml`'s `source-zip.mjs build|verify|guard`, fe-ci's `example/run.mjs` and `verify-local`'s, after
      its `cd "$ROOT/fe"`; what they write is named by absolute paths or is unchanged.
    - _The probes_, each planted file deleted afterwards and its check run green again:
      - Lint: `country-kit/lib/km2-probe.mjs` holding `const a = 1;`, `a = 2;`, `debugger;`. `npm run
        lint:scripts` from `fe/` exited 1, reported through `lint:kit`: "country-kit\lib\km2-probe.mjs 2:1 error
        'a' is constant no-const-assign / 2:1 error 'a' is assigned a value but never used no-unused-vars / 3:1
        error Unexpected 'debugger' statement no-debugger / ✖ 3 problems (3 errors, 0 warnings)".
      - Test: `country-kit/lib/km2-probe.test.mjs`, `it('KM-2 probe', () => assert.fail('planted'))`. `npm run
        test:guards -- --test-name-pattern="KM-2 probe"` exited 1 naming "test at
        ..\country-kit\lib\km2-probe.test.mjs:4:1 ✖ KM-2 probe … AssertionError [ERR_ASSERTION]: planted"; npm
        appends the flag after the globs, where `node --test` takes it for a file pattern, so the whole suite
        ran (1181 tests, 13 failing: the other parts of KM-2, unfinished at that moment). With the flag before
        the globs, `node --test --test-name-pattern="KM-2 probe" "scripts/*.test.mjs"
        "../country-kit/**/*.test.mjs"` exited 1 with tests 59, pass 58, fail 1, the probe.
      - Prettier: `country-kit/lib/km2-probe.mjs` holding `export const x  =  "a"`. `npm run format:check`
        exited 1: "[warn] ../country-kit/lib/km2-probe.mjs / [warn] Code style issues found in the above file.
        Run Prettier with --write to fix."; after the deletion "All matched files use Prettier code style!".
      - lint-staged's reach, read-only, by `generateTasks` of lint-staged 16.4.0 with `package.json`'s
        configuration from `fe/`: `..\country-kit\README.md` went to the `../country-kit/**/*.{ts,…,yaml}`
        Prettier task and `..\country-kit\lib\where.mjs` to the `../country-kit/**/*.{mjs,cjs}` ESLint task, and
        the three old patterns took no kit file. `npx eslint ../country-kit/lib/where.mjs
        ../country-kit/templates/setup.mjs` from `fe/` is clean: ESLint 10.2.1 lints a file outside its working
        folder by that file's own configuration, the kit's, which exempts `setup.mjs`.
    - _The mentions._ A kit file named `country-kit/<file>` in prose, a comment or a message (`orphans.mjs`,
      `overrides.mjs`, `keycloak-login.ts`, the applications' comments) is its path from the root and its name
      in the zip, and stays. What changed: every live `fe/country-kit` (the CLAUDE.md files, the READMEs,
      `fe/docs/`, `be/docs/potential-errors.md` and `be/docs/cicd/`, `docs/feature-branch-deploy-plan.md`, this
      plan's overview and §4.8), a relative link from a `fe/` document (one more `../`) and a command run from
      `fe/` (`node ../country-kit/…`, the kit scripts' usage comments included). The root `CLAUDE.md` has a
      `country-kit/` row and its _Country-specific behaviour_ bullet names the kit at the root, the root
      README a `country-kit/` bullet, `fe/CLAUDE.md` says the kit sits beside `fe/`, and `fe/README.md`'s script
      table and `fe/docs/platform.md`'s CI descriptions follow the new scripts and filters. Records keep their
      wording: this plan's phase, review and as-built records and the P2b `catalog` record in §4.3 of
      `docs/code-data-config-plan.md`.
    - _Named by the design, no change needed_: `fe/.gitignore` and `fe/tsconfig.json` (comments naming the kit's
      files as `country-kit/<file>`), `fe/.husky/pre-commit` (`npx lint-staged`, which reads `package.json`),
      `seams.json` (its paths are the zip's), `scripts/release-workflows.test.mjs` and
      `scripts/info-container-test.mjs`.
    - _Left open._ The pre-commit hook is never installed: husky 9, run by `prepare` in `fe/`, stops at ".git
      can't be found", so `core.hooksPath` stays unset and lint-staged runs only by hand; a gap from before
      KM-2, logged in `fe/docs/backlog.md`. A 4.0.97 stub with `NIORD_BASE` naming a checkout after the move is
      refused ("no country-kit/setup-kit.mjs"), since that stub looks inside `fe/`; only the local developer
      mode on the feature branch is affected, until KM-6's stub. `fe/.prettierignore` no longer covers the kit;
      nothing it tracks is affected.
    - _Two gaps the review found, closed in this sub-PR._ `example/run.mjs` refused a `--work` inside `fe/`
      only, which before the move also covered the kit; with the kit beside `fe/` a `--work` under
      `country-kit/` was no longer refused, and what the harness wrote there was neither tracked nor ignored
      (`fe/.gitignore` no longer covers the kit and the root `.gitignore` had none of its patterns), so `git
      ls-files -co --exclude-standard` listed it, the next `kit:zip` would pack it under `country-kit/**`, the
      word guard's tree mode and `base-product-tree.test.mjs` would read it, and `test:guards` would run any
      test file in it. That happened once during KM-2, when `run.test.mjs`'s old case built the example zip
      into `country-kit/example/work` (above). Now `parseArgs` refuses a `--work` inside `fe/` or inside
      `country-kit/` (`run.test.mjs` covers both), and the root `.gitignore` carries the stray-file patterns
      of `fe/.gitignore` (`node_modules/`, `.niord/`, `coverage/`, `dist/`, `*.stackdump`, `Thumbs.db`,
      `*.iml`), which changes neither the zip nor its include list.
    - _Run_: `where.test.mjs` and `tooling.test.mjs` red first and green, as above; the kit's tests the path
      fixes touched, 478 tests, 476 passed, 2 skipped (symbolic links); the four guard test files of the kit 72
      of 72; the eight `fe/scripts` guard files, `ci-filters.test.mjs` and `release-workflows.test.mjs` among
      them, 155 of 155; `country-words.test.mjs` 20 of 20; `npm run test:guards` 1224 tests, 1220 passed, 0
      failed, 4 skipped; `npm run lint:scripts`, `npm run lint:kit` and `npm run format:check` exit 0;
      `require('./eslint.config.js')` from `fe/` and the kit's configuration from the root load (5 blocks);
      `npm run docs:check` 35 files, the kit's 7 read as `country-kit/…`, no errors, 7 warnings (the existing
      PrimeNG and legacy ones); `npm run contents:check` for `niord-app` (4 configurations, 52 files) and
      `niord-info` (2, 24) exit 0; `npm run kit:zip -- --version 4.0.0-km2` into the scratch folder, 4,118,266
      bytes, with `verify`, the import-closure check included, and `guard` exit 0; its entry names against
      `71fded92`'s `fe/` tree and include list: 1795 and 1795 besides `catalog.json`, none missing and none
      added, 14 differing in bytes (`package.json`, `eslint.config.js` and the kit's `catalog.mjs`, `pack.mjs`,
      `stage-languages.mjs`, `example/run.mjs`, `example/pseudo-localise.mjs`, `lib/where.mjs`,
      `lib/include.mjs`, `lib/local-sources.mjs`, `lib/country-words.mjs`, `source-zip.mjs`,
      `workspace-files.json` and `zip-include.json`), built before this README's edit; rebuilt after it, 4,118,369
      bytes, `verify` and `guard` exit 0 again, 15 entries differing in bytes, the kit's `README.md` added to the
      14 (the review's later comment fixes touched only `zip-include.json`, `lib/include.mjs` and
      `source-zip.mjs`, three of the 15); `bash -n verify-local`; the three workflows parse; Prettier over the
      changed files in `fe/` and `country-kit/` but the workflows (the changed `CLAUDE.md`, `README.md`,
      `be/docs/` files and the two plans were not Prettier-clean at `origin/km/1-resolver` either and are
      left so); `npm run build` and `npm run build:info` exit 0, `stage-languages` running through
      `../country-kit`; `npm run paths:check` 29 paths; the line ends, `git ls-files --eol country-kit`: 193
      files `i/lf w/lf` and 7 binary, and both stubs, `templates/setup.mjs` and `example/fe/setup.mjs`, sha256
      `3238c000faa6a01501227aba8d3d2953f3c47d28e3b227907f13137cb564bfb0`. The diff check, `git diff -M
      --name-status origin/km/1-resolver`: 198 renames, 168 of them `R100` and 30 with path fixes; one more,
      `zip-include.json`, shows as a deletion and an addition, its rewritten note dropping the similarity under
      50 %; the new `country-kit/.gitattributes`; and the five new files
      (`country-kit/{.prettierrc,eslint.config.mjs,tooling.test.mjs,test-fixtures/base-modules.mjs}`,
      `fe/scripts/ci-filters.test.mjs`). The example harness, `image:info` and `./verify-local`: see the sub-PR.
  - **KM-3 as built** (2026-11-01, `km/3-package` into `feature/kit-move`). The kit is published as a package of
    its own, `org.niord:niord-country-kit`, in addition to its copy under `country-kit/` in `niord-fe-src`, which
    stays until KM-6. Producer side only: no consumer reads the package yet, so set-up, the lock, the stub and the
    guides are unchanged, and there is no `API-CHANGES.md` entry (KM-6 carries the move's).
    - _The list_, `country-kit/kit-include.json`, in `zip-include.json`'s format and read by the same
      `readIncludeList`, its paths relative to the kit's folder (`country-kit/` in niord-ng's tree,
      `<root>/.niord/kit` in a country). It includes `{README.md,API-CHANGES.md}`, `*.{mjs,json}`, `docs/**`,
      `lib/**`, `templates/**` and `example/{fe,be}/**`, and excludes `**/*.test.mjs`,
      `**/{fixtures,test-fixtures}/**`, `country-words.json`, `source-zip.mjs`, `eslint.config.mjs` and
      `**/{node_modules,.niord,dist,.angular,coverage}/**`. On this tree it selects 132 of the kit's 206 git
      files. Left out: the tests and fixtures, `country-words.json`, `source-zip.mjs`, the example harness
      (`example/{acceptance,run,pseudo-localise}.mjs`) and niord-ng's tooling for the root folder
      (`.gitattributes` and `.prettierrc` match no include, `eslint.config.mjs` is excluded by name). The design
      kept all of `example/`; the harness imports `../source-zip.mjs`, which the package leaves out (KM-1's
      _Open for KM-3_), and the upgrade test reads only `example/fe/` and `example/be/`, so the design's bullet
      above is corrected. `kit-include.json` is in both zips, as `zip-include.json` is.
    - _The layout._ The package's 134 entries are the 132 and the two the build adds, named from the kit's top
      with no `country-kit/` prefix (`lib/where.mjs`, `docs/country-api.md`, `kit.json`): the zip unpacks at
      `<root>/.niord/kit` (_The country layout_, above) and `where()`'s package layout wants `kit.json` at the
      kit's top (_KM-1 as built_). `docs/country-api.md` is `be/docs/country-api.md` with CRLF read as LF, as
      `local-run-zip.sh` copied it, so a Windows working copy (`i/lf w/crlf`) gives CI's bytes; a missing file
      fails the build, naming the path. `kit.json` is `markerText(version)` from `lib/where.mjs`. A kit whose list
      selects either name is refused: "<name> is selected, but the build adds that name".
    - _The code._ `source-zip.mjs` exports `KIT_INCLUDE_FILE`, `KIT_ADDED` (`{contract: 'docs/country-api.md',
      marker: 'kit.json'}`, `contract` since the map's `country-api` is not a plain identifier), `kitEntries`,
      `buildKitZip`, `verifyKitZip`, `kitClosureProblems`, `guardKitZip`, `checkedKitZip` and `writeKitZip` beside
      the fe-src functions, the two write functions sharing one `written()` helper. The zip is
      `niord-country-kit-<version>.zip`, with `<sha256>  <basename>` in a `.sha256` file beside it, as for
      `niord-fe-src`. `build`, `verify` and `guard` take `--package <fe-src|kit>` anywhere after the command,
      defaulting to `fe-src`, so `publish.yml`'s fe-src step, the harness and `verify-local` are unchanged; another
      value exits 2 with "--package is fe-src or kit, not "<v>"". The success line names the package, "verified as
      a source zip, sha256 …" or "verified as the kit package, sha256 …", where it read "verified, sha256 …";
      nothing in the tree or the workflows matches the old text. `fe/package.json` has `kit:package`, `node
      ../country-kit/source-zip.mjs build --package kit`, after `kit:zip`, with its row in `fe/README.md` and its
      lines in `fe/AGENTS.md` after the `kit:zip` block (KM-8 moved `fe/CLAUDE.md`'s content there).
    - _`lib/tree.mjs`._ `gitFiles`, `KIT_FOLDER` and `CATALOG_ENTRY` move unchanged from `source-zip.mjs` into a
      new `country-kit/lib/tree.mjs`, Node built-ins only, which `source-zip.mjs` re-exports, so its importers
      keep their imports. `lib/local-sources.mjs`, the set-up module behind the `NIORD_BASE` copy, imports them
      from `./tree.mjs`; that import line is its only change, left in place so that KM-4's edits merge cleanly.
      It was the one shipped crossing a read-only probe of the tree found, and the kit package's verify was red
      on it (below). The header of `lib/country-words.mjs` drops a reason that no longer holds (set-up importing
      `source-zip.mjs` from a zip without it) and says the list is read only when a guard runs; that of
      `lib/include.mjs` names the second list, its matcher block untouched.
    - _The checks._ `verify --package kit` checks the list (every entry but the two added is one it selects); the
      marker, present and byte-equal to `markerText` of a version `VERSION` accepts, where key order, a missing
      newline, extra keys, another package, a bad version and invalid JSON all give "kit.json is not the kit's
      marker: it holds exactly {"package":"niord-country-kit","version":"<version>"} and a newline"; the contract,
      `docs/country-api.md` present; and the closure in its kit form, `kitClosureProblems`, stricter than KM-1's
      rule (b), which stays as it was for `niord-fe-src`. Every relative specifier of a shipped `.mjs`, `.cjs` or
      `.js` file outside `templates/`, `example/fe/` and `example/be/` (the same `COUNTRY_TREE` definition, tested
      against `country-kit/<entry>`) must resolve to an entry of the package: a target above the package's top
      gives "<name> imports '<spec>': it leaves the kit package; the kit loads the base product only from its
      resolved base", any other missing target "<name> imports '<spec>': no such file in the kit package
      (kit-include.json)". Computed specifiers, such as the kit's loads of base product code through
      `where().base`, and template literals are not read, as in KM-1's lexer. `guard --package kit` runs the
      country-word guard's zip mode over the entries named `country-kit/<entry>`, so the allow-list globs of
      `country-words.json` apply as in the tree, and reports a hit under the entry's own name; no allow-list entry
      names a kit path today, and the probe found no hit, `docs/country-api.md` included.
    - _Red first._ `zip.test.mjs` 19 tests, 1 failing, ENOENT on `kit-include.json`. `source-zip.test.mjs` failed
      to load: "SyntaxError: The requested module './source-zip.mjs' does not provide an export named
      'KIT_ADDED'". With the code in place and `lib/local-sources.mjs` not yet edited, the real tree was red in
      three cases, its own verification, the real-tree closure case and the CLI: `node ../country-kit/source-zip.mjs
      build --package kit --version 4.0.0-km3 --out …` exited 1 with "source-zip: the archive fails its checks;
      niord-country-kit-4.0.0-km3.zip is not written:" and "lib/local-sources.mjs imports '../source-zip.mjs': no
      such file in the kit package (kit-include.json)". The planted cases run on a fixture kit in a temporary git
      repository, with CRLF in its `be/docs/country-api.md` and every left-out kind planted: `lib/x.mjs` importing
      `../../fe/scripts/glob.mjs` is reported as leaving the package and `./missing.mjs` as no such file;
      `templates/` and `example/fe/` files importing `../../anything.mjs` are exempt; a computed import and a
      template literal are not read; the list's first word planted in a `lib/` entry fails the guard, naming the
      entry. The map's specifier `../fe/scripts/glob.mjs` cannot leave the package: from `lib/` it resolves to
      `fe/scripts/glob.mjs` under the package's top and is reported as no such file, so the test plants
      `../../fe/scripts/glob.mjs`, which from `country-kit/lib/` in niord-ng's tree reaches the base product.
    - _The publish run._ `publish.yml`'s build job runs a new step, "Build and check the kit package ${{
      env.VERSION }}" (`id: kit`, `working-directory: fe`, no `env`, so no token), after the fe-src step and before
      `setup-java`: `build`, `verify` and `guard --package kit` on `$RUNNER_TEMP/niord-country-kit-$VERSION.zip`,
      Node built-ins only, then the sha256 compared with the recorded one and written to the step's output, so a
      failure publishes nothing. The job gains the output `kit_sha256`. "Deploy niord-country-kit ${{ env.VERSION
      }} to GitHub Packages of NiordOrg/niord" runs `-N deploy:deploy-file` of
      `org.niord:niord-country-kit:$VERSION`, packaging `zip`, `-DgeneratePom=true`, on the fe-src pattern, its
      summary under "(country kit)" with the sha256 line; its comment says `niord-fe-src` still carries the kit
      until KM-6. "Hand the kit package to the tag job" uploads the run artifact `niord-country-kit`
      (`${{ runner.temp }}/niord-country-kit-*.zip`) beside the unchanged `niord-fe-src`. The tag job fetches it
      into `kit/` ("Fetch the kit package"), checks both zips against the build job's outputs in one loop ("<file>
      has sha256 …, but the build job built and deployed …"), writes the notes as an intro line naming both
      packages, a blank line, `<sha256>  niord-fe-src-<v>.zip`, `<sha256>  niord-country-kit-<v>.zip`, a blank line
      and the packages sentence, ending "…, org.niord.web:niord-fe-src (zip) and org.niord:niord-country-kit
      (zip).", and runs `gh release create "v$VERSION" "$zip" "$kit"`. The by-hand texts of the Tag and Release
      steps name both artifacts and both coordinates and give two `gh run download` lines (`-n niord-fe-src -D
      fe-src`, `-n niord-country-kit -D kit`), two `sha256sum` lines and a one-line `gh release create v$VERSION
      fe-src/niord-fe-src-$VERSION.zip kit/niord-country-kit-$VERSION.zip --notes "$(printf '%s  %s\n' …)"`, which
      gives two separate notes lines, since a country checks each in its own folder. The clean-up is "Delete
      niord-fe-src and niord-country-kit": one `jq` select on both names and the `while read -r id name` loop of
      the first delete step. The headers say all this, the local-run contents lose `country-api.md` (the header
      and the local-run deploy's comment, which names the kit package as the contract's one published home), and
      `web-publish`'s comment adds that a failed kit check stops the bundle too. `retract-version.yml` retracts
      `org.niord.niord-country-kit` after `niord-fe-src`; its header names the kit's 404 cases (before its first
      publish, and a version from before the kit move) and both assets. The root README's `publish.yml` and
      retract rows follow. `fe-ci.yml` is unchanged: its previous-zip reader is anchored on the `niord-fe-src`
      name.
    - _The workflow tests_, `release-workflows.test.mjs`, red first against the unchanged workflows: 46 tests, 13
      failing, every one a kit case. `release()` writes both zips and checks the notes layout (exactly two sha256
      lines, in order) and the two-file create call; a kit-mismatch refusal; the two by-hand texts run under bash
      with a fake `gh` that captures `--notes`, which must be exactly two lines; the clean-up against a fake
      listing `niord-fe-src`, `niord-country-kit` and `web-bundles`, deleting exactly the first two; the published
      packages (at least 5 deploys, `org.niord.niord-country-kit`, at least 4 upload paths), so the sentence, the
      retract list and the deploys change together; the kit step's commands, place, missing `env` and output; the
      kit deploy, on a `deployFile` helper the local-run case now shares; the hand-over and the fetch; the retract
      paging case deleting the kit's version. Green, 45 of 45. A kit "not present" retract case was dropped: it
      repeated the generic 404 case and the parity test, and kept running into the fixed 60 s limit of `runBash`
      in `fe/scripts/workflow-step.mjs` (EPERM on removing its temporary folder) while other sessions loaded the
      workstation. After the review a 46th case checks that the tag job's "Tag v" and "Release v" steps take
      `FE_SRC_SHA256` and `KIT_SHA256` from the build job's outputs (red on a copy with a mistyped `KIT_SHA256`);
      46 of 46. With an eighth package the retract `Packages` cases take about 18 s each on a quiet machine and
      up to 60 s under load: in one full `test:guards` run the paging case reached the limit (below). The
      cases' helper passes `timeout: 180_000` to `runBash`, which ignores it until `workflow-step.mjs` takes
      the option; that change is open. Linux CI is not affected.
    - _niord-local-run_ no longer carries `docs/country-api.md`. `be/scripts/local-run-zip.sh` drops the line and
      its layout comment (`usage()` prints lines 2-33), `be/scripts/local-run-README.md`, the zip's `README.md`,
      drops the row and says the contract is in `org.niord:niord-country-kit` of the same version, as
      `docs/country-api.md`, and `LocalRunZipTest` asserts its absence ("the backend contract is published in
      org.niord:niord-country-kit"). No script, Java class or workflow step in niord-ng read it from the local-run
      zip; the countries' launchers were not checked. The records above that read `country-api.md` from the
      `niord-local-run` zip, D1b's (NG-12) and the person route's (NG-14), hold for the versions before KM-3.
    - _The documents._ `country-kit/README.md`: its file-table row names `kit-include.json`, a new subsection
      _The kit package_ under _The source zip_ (producer side only), and _Publishing_ gives both deploys, both
      sha256 lines and both assets; its retract paragraph stays on the `niord-fe-src` pin, which countries use
      until KM-6. `be/docs/country-api.md`: the `niord-local-run` row drops "and this file", and a row for
      `org.niord:niord-country-kit:<v>:zip` says it carries the file as `docs/country-api.md`; the guard stays
      clean on it. `be/docs/cicd/design.md`: the published-packages cell drops `country-api.md` from the local-run
      list (versions before KM-3 still carry it) and adds the kit package, its build and checks and its sha256
      line, the retract sentence names it, and the release row gives the two lines and both assets. Info CI's
      filters need no new entry: no kit file it lists imports `source-zip.mjs` or `local-sources.mjs`, and
      `fe-ci`'s `country-kit/**` and example rule cover the two new files.
    - _`niord-fe-src`_ has 1798 entries, 1797 besides `catalog.json`: KM-2's 1795 and `country-kit/kit-include.json`
      and `country-kit/lib/tree.mjs`, the tree's only new files; its layout and include list are unchanged.
    - _Run_: `zip.test.mjs` and `source-zip.test.mjs` red first, as above, and green, 52 of 52 (19 and 33, 12 new
      kit cases); the tests of the moved names' importers (the kit's `country-words`, `setup`, `stub`, `seams`
      and `example/acceptance` test files, `fe/scripts/base-product-tree.test.mjs` and `docs-check.test.mjs`) 188
      of 188; `release-workflows.test.mjs` red first and green, as above, and `ci-filters.test.mjs` 6 of 6;
      `LocalRunZipTest` with `CountryApiDocTest` by Maven, red first with the test edit alone (18 run, 2 failed:
      `theEntriesAreExactlyTheLocalRunFilesSortedStoredAndDated` with the message above, `eachEntryIsItsSource`
      with "no source for docs/country-api.md"), then green, 10 of 10 and 8 of 8; `npm run kit:package -- --version
      4.0.0-km3` into the scratch folder, 134 entries, `verify` and `guard --package kit` exit 0 (11 words, 20
      allow-list entries), a second build the same sha256, `sha256sum -c` passing, and the entries
      inspected: `docs/country-api.md` equal to `be/docs/`'s with LF and no CR, `kit.json` equal to
      `markerText('4.0.0-km3')`, `lib/where.mjs`, `lib/tree.mjs`, `setup-kit.mjs`, `templates/setup.mjs`,
      `zip-include.json`, `kit-include.json` and `example/fe/setup.mjs` present, and no test, fixture,
      `country-words.json`, `source-zip.mjs`, harness, root tooling or `country-kit/` name; the publish step's own
      bash run against the tree with a temporary `RUNNER_TEMP`, exit 0, "verified as the kit package", the guard
      passing and the output equal to the recorded `.sha256`; the zip's size and sha256 are not recorded, since
      they change with every edit of a packaged file (the rebase onto KM-7 and KM-8 included); `npm run kit:zip
      -- --version 4.0.0-km3`, 1798 entries as above, `verify` and `guard` exit 0; `local-run-zip.sh` under `bash
      -n`, and run, 11 entries with no `country-api.md`, and with no arguments exit 2, its usage text ending at
      the exit-status line; `npm run lint:kit`, the `lint:scripts` ESLint and `npm run docs:check` (35 files, no
      errors, the 7 earlier warnings) exit 0; Prettier over the changed files in `country-kit/` and `fe/`; the
      three workflows parsing. `npm run test:guards` during the run, 1246 tests, 1238 passed, 4 failed, all four in
      `release-workflows.test.mjs` while it was still being edited, 4 skipped. On the finished tree, before the
      review's 46th workflow case: 1245 tests, 1240 passed, 1 failed, 4 skipped, the failure the retract paging
      case at `runBash`'s 60 s limit (it passes alone). After the review's fixes: 1246 tests, 1242 passed, 0
      failed, 4 skipped (the symbolic-link privilege), the paging case 17.8 s; `release-workflows.test.mjs` alone
      46 of 46, `zip.test.mjs` with `source-zip.test.mjs` 52 of 52, `LocalRunZipTest` 10 of 10, `npm run
      docs:check` as before and `npm run format:check` (`fe/` and `country-kit/`) exit 0.
    - _Not run_: no publish, deploy or release from the feature branch; the first real build, deploy and
      two-line, two-asset release of `niord-country-kit` is the main merge's publish run, where read access to
      the new package in NiordOrg/niord, expected to follow `niord-fe-src`'s registry, is still to be seen. The
      example harness, `image:info` and `./verify-local`: see the sub-PR.
    - _For KM-5_, the guide lines that still send a country to `niord-local-run` for `country-api.md`:
      `country-kit/docs/country-guide.md:500-516` (the `unzip … docs/country-api.md` of _The base product's
      backend files_) and `:911-916` (`.local-run/<version>/read/docs/country-api.md` and
      `.local-run/<version>/local-run/docs/country-api.md`), `country-kit/templates/CLAUDE.md:22-26` and
      `fe/docs/backlog.md:311-313` (a record). The kit README's first-time steps (around lines 1508-1526) fetch
      and pin `niord-fe-src` only, with one `sha256sum` line.
  - **KM-4 as built** (2026-11-01, `km/4-setup-lock` into `feature/kit-move`). No behaviour change: set-up is
    ready for both layouts while the kit is still inside `niord-fe-src`. A country on the 4.0.97 stub with a
    format-1 lock without a kit pin, the in-base layout and the example harness behave as before; the new
    behaviour appears only when a lock holds a kit pin, the stub hands over a kit hash, a record names one or
    set-up runs from a kit package.
    - _The lock_ stays format 1, with the optional `"kit": {"sha256": "<64 hex>"}` between base and packs (key
      order format, base, kit, packs), written only when a kit pin is known; it shares `base.version`. Without
      it the text is today's byte for byte. `lockText` and `writeLock` carry it, and refuse any other shape, a
      `null` included, with `"kit" is not { sha256 } of 64 lower-case hex digits`. The kit had no lock reader
      of its own (only the stub has one, which stays as it is); the new `readLock(text)` in `lib/pins.mjs`
      returns `{ format, base: { version, sha256 }, kit?, packs }` and refuses, each message starting with
      `niord-pins.lock.json` in the stub's wording, a text that is not JSON, not format 1, without a base
      `{ version, sha256 }`, with a malformed kit, or with packs that are not an object. It does not compare
      the version with the pom; the stub does that before the kit runs. It reads a lock without packs as one
      with none, where the stub's reader refuses it ("has no packs object"); since the kit reads a lock only
      after the stub has, the difference never comes into play, and it is left so. `--update-pins` reads
      through it and writes the kit pin back; a lock without one is written back without one. The 4.0.97
      stub's reader passes a kit pin through unchanged, still against the pom version: `pins.test.mjs`
      asserts it on `templates/setup.mjs`, imported read only.
    - _The build report._ `setup()` takes `kitSha256` (default null), the kit package zip's hash the stub
      verified, not the lock's pin: the record holds what was verified, as `base.sha256` does. The 4.0.97
      stub hands over none, so nothing changes. With it, the report's base is `{ version, sha256, kit: {
      sha256 } }`, the key absent otherwise, so today's report bytes are unchanged, and the provenance is
      "<the stub's provenance>; org.niord:niord-country-kit:<v>:zip sha256:<k>" (`KIT_ARTIFACT` in
      `lib/build-report.mjs`, built from `where.mjs`'s `KIT_PACKAGE`). `buildReport` refuses a given
      `kitSha256` that is not 64 lower-case hex digits, with `"<value>" is not a SHA-256 of 64 lower-case hex
      digits`, the shape the lock and `readRecord` accept, so a hash it records is never dropped at the next
      upgrade; the default null is not checked. The `NIORD_PACK_*` wrap applies over
      that, so `local:` stays first for a local source and `isLocal` and `guard-build` are unaffected. The
      format stays 1; the report's top-level `kit`, the kit's version, is unchanged. `readRecord` reads
      `base.kit` back, only a valid `{ sha256 }`, a malformed one dropped. For KM-6: the stub that fetches
      the kit package hands its hash over as `setup({ kitSha256 })` and does not name the kit in its own
      provenance, which `buildReport` already appends.
    - _The upgrade report and the previous base._ 'Now' is read from the kit folder set-up runs from:
      set-up passes `current: { version, base, kit: KIT }`, `KIT` the folder `where().kit` names, and
      `upgradeReport` defaults `current.kit` to `<base>/country-kit`. 'Before' comes in one of two shapes.
      When the record names a kit hash, the previous kit is `<root>/.niord/niord-country-kit-<v>.zip`
      (`kitZipOf`, `root` fe's parent by default, named from `fe/` in messages as
      `../.niord/niord-country-kit-<v>.zip`), read only when its sha256 is the record's, its entries named
      relative to the kit; the previous `niord-fe-src`'s own `country-kit/` entries are dropped, so the
      kit's files come from one source. When the record names none (a pre-move version), the previous
      `niord-fe-src`'s `country-kit/` entries are the kit's, and a kit zip lying beside it unhashed is not
      read. Either way the kit's files sit in the same map under `country-kit/<name>`, so the report's labels
      stay `country-kit/<file>` and every existing line is unchanged. The fallback is never quiet: a missing,
      mismatched or unreadable kit zip, or a pre-move zip without `country-kit/API-CHANGES.md`, sets
      `kitUnavailable`, which the report says as "(by version: <reason>)" in Versions and as the reason on
      New for you's Extension points line. The map had the label once for both Versions lines; it is decided
      per line, from whether that line's previous file is missing (the API-CHANGES line when
      `API-CHANGES.md` is, the security line when `security.json` is unreadable), so a line that did compare
      is never labelled by version; in the post-move failures both files are missing and both lines carry
      it. Likewise the Extension points line names the reason only when the previous `seams.json` is
      unreadable. `stubTemplate(layout)`, the layout from `where(kit).kind`, gives
      `../.niord/kit/templates/setup.mjs` in the package layout and `.niord/base/country-kit/templates/setup.mjs`
      in the in-base and source layouts (set-up from a checkout fills `.niord/base/country-kit`). Left as
      it was: `previousBase` still returns the whole as unavailable when the `niord-fe-src` zip is missing
      or mismatched, even beside a valid kit zip.
    - _The workspace generator_ takes `kitPath`, the kit's folder relative to `fe/`, by default `KIT_PATH`,
      `.niord/base/country-kit`, until KM-6 switches it; `setup-kit.mjs` calls `generateWorkspace` without
      it, so a generated workspace is byte-identical: the 24 files of `workspace.test.mjs`'s `generate('qq')`
      fixture hash the same before and after, by a scratch script outside the tree, with no `kitPath` and
      with `KIT_PATH`; with `../.niord/kit` only `package.json` and `eslint.config.js` differ.
      `buildScripts(kitPath)` builds the build, `image:info` and audit scripts from it (`bundle:info`, the
      base product's script, unchanged), and `eslintConfig(kitPath)` the require line, `./` before a path
      that does not start with `../`; `BUILD_SCRIPTS` and `ESLINT_CONFIG` stay, as `buildScripts()` and
      `eslintConfig()`. So the P3c1 record's "`BUILD_SCRIPTS` in `lib/workspace.mjs`, constant text" now
      holds at the default only; the record keeps its wording. The path goes into npm scripts and a require
      string and is checked segment by segment: any leading `..` segments, then at least one segment of
      `[A-Za-z0-9._-]` that is neither `.` nor `..`, else "is not a relative path of the kit". The map's
      single regular expression would have refused both `.niord/base/country-kit` and `../.niord/kit`,
      whose first segment starts with a dot, and was not used. The parameter is `kitPath`, not `kit`, which
      means an absolute kit folder elsewhere in the kit.
    - _Kit data_: nothing left. KM-1 had moved the reads: `setup-kit.mjs` reads `zip-include.json` and
      `security.json` from its `kit` parameter (543 and 556 in `changeOverrides`, 711-712 in `runChecks`)
      and `secret-scan.json` from it (748), and `checkBuild` reads `secret-scan.json` from `kit =
      where().kit` (`lib/build-checks.mjs`:247). A grep of `setup-kit.mjs`, `check-build.mjs` and `lib/*.mjs`
      found no other read of `base/country-kit`, only messages, comments, check-build's usage line and the
      defaults above (the in-base stub path, `current.kit`'s default and `KIT_PATH`).
    - _Not changed_: `templates/setup.mjs` (sha256 `3238c000…`, as `example/fe/setup.mjs`), `STUB_CHANGED`
      and `checkStub`, set-up's log line with the provenance, `zip-include.json`, `source-zip.mjs`,
      `publish.yml`, `workspace-files.json`, the guides, the kit's README and `API-CHANGES.md`.
    - _The task's names_: it named `pins.test.mjs` and `example/setup.test.mjs` as existing. The first is new,
      the lock tests having lived in `setup.test.mjs`'s "the pins of the lock", which stays; the second does
      not exist, and `example/acceptance.test.mjs` was run in its place. `build-report.test.mjs` is new too,
      since `buildReport` had only end-to-end cases in `setup.test.mjs`.
    - _Run_: `pins.test.mjs` and `build-report.test.mjs` red first at the base commit (`readLock` and
      `KIT_ARTIFACT` not exported), then 26 of 26; `setup.test.mjs`'s two new cases, "keeps the kit pin" and
      the kit hash the stub hands over reaching the report, its full provenance and the staged admin copy,
      red first (the kit dropped), then the file 68 of 68; `stub.test.mjs`, `init.test.mjs`,
      `example/acceptance.test.mjs` and the two new files, `lockText`'s other callers among them, 135 of
      135; `upgrade-report.test.mjs` red first with only the two new names exported, 7 of 41 failing for the
      expected reasons (the stale copy read, 'now' not from the kit, the in-base stub path, `readRecord`
      dropping the kit, no `kitUnavailable`), the pre-move shape and the unhashed kit zip passing, as the
      code before already handled them, then with `where.test.mjs` 51 of 51; `workspace.test.mjs` red first
      (`KIT_PATH` not exported, one failure at load), then 42 of 42, and the golden hashes above;
      `scripts/docs-check.test.mjs` 18 of 18; `npm run lint:kit` exit 0; Prettier over the changed `.mjs`
      files; `templates/setup.mjs` and `example/fe/setup.mjs` sha256
      `3238c000faa6a01501227aba8d3d2953f3c47d28e3b227907f13137cb564bfb0`. `npm run test:guards` 1267 tests,
      1263 pass, 0 fail, 4 skipped (the known symbolic-link and environment skips of KM-2); `npm run
      format:check`, `npm run docs:check` (35 files, no errors, the 7 existing warnings), `npm run
      paths:check` and `npm run lint:scripts` exit 0. After the review: `build-report.test.mjs`'s refusal
      case (an upper-case and a 63-digit hash) red first ("Missing expected exception"), then with
      `pins.test.mjs` and `setup.test.mjs` 95 of 95; `upgrade-report.test.mjs` 41 of 41 after a local
      `where` in `overrideItems` that shadowed the resolver was renamed `at`; `npm run lint:kit`, Prettier
      over the changed files and the stub's sha256 as above. Not run: the example harness, `image:info` and
      `./verify-local`: see the sub-PR.
  - **KM-7 as built** (2026-11-01, `km/7-two-letter` into `feature/kit-move`). A country's code is its ISO 3166-1
    alpha-2 code, exactly two lower-case letters; language codes are unchanged.
    - _The sites_: `init.mjs` (`CODE` and its refusal), `niord-country.schema.json` (`country` only, its
      description and its pattern), `templates/start-local.sh` (the check in its Node run, prefixed
      "fe/niord-country.json's country"), and, as one definition, `lib/workspace.mjs`'s exported
      `COUNTRY_CODE = /^[a-z]{2}$/`, which its `angularJson` and `packageJson` use and `lib/secrets.mjs`'s
      `compileAllow` imports (no import cycle: none of `workspace.mjs`'s imports imports `secrets.mjs`). Each
      refusal names the rule: `"<value>" is not a country code, its ISO 3166-1 alpha-2 code, two letters a-z`;
      the schema's reads `country "<value>" does not match ^[a-z]{2}$`. `init.mjs` keeps its own `CODE` and the
      schema its pattern, as the design's _two-letter code_ bullet lists them apart; the launcher keeps its own
      literal, since it runs at a country's root before any kit code is read.
    - _The dead code_: the launcher's `--sample` guard (its comment on `load-sample.sh` and `sample_reset`
      taking two letters, and the `if` with its exit 2) and the usage sentence "A two-letter country code only"
      went. They could not be reached: `--sample` only sets `SAMPLE=true`, the Node run calls `fail()` and exits 1
      on any code but `^[a-z]{2}$`, and line 228's `values="$(…)" || exit 1` stops before `CC` is read.
    - _The tests_, written red first, before any code change: a red run of the five files, 154 tests, 147 passed,
      7 failed, each for the expected reason: `init.test.mjs`'s "refuses a three-letter country code, writing
      nothing" (missing expected exception; it also checks that neither `fe/niord-country.json` nor
      `fe/setup.mjs` is written); `manifest.test.mjs`'s "rejects a country code that is not two letters a-z, and
      keeps three-letter language codes" (no problem `country "xxx" does not match ^[a-z]{2}$`; its control, a
      three-letter content language `fil` with its pack, passes in the green run; the red run stopped at the
      `xxx` assertion before reaching it, the base's language pattern being `{2,3}` unchanged);
      `workspace.test.mjs`'s `angularJson` "refuses a country code that is not one" (a second, `xxx` case) and `packageJson` "refuses a
      three-letter country code" (missing expected exception); `secrets.test.mjs`'s allow-entry case, now also
      "or for a code that is not a country code" (`xxx` gave no error; `gl` gives none); `launcher.test.mjs`'s
      "three-letter country in the manifest, exit 1, no calls", replacing "--sample for a three-letter country
      code, with exit 2" and run with no arguments and with `--sample`, whose no-argument run accepted `xxx` and
      reached the Docker check ("Docker is not running", exit 1), so its message failed the match, the
      `--sample` run not being reached as the loop stops at the first failing assertion; and the `--help` case,
      whose text still matched `/two-letter/`. Green, 154 of 154 in 75 s: KM-2's 151, three new cases, and the
      launcher's case replaced.
    - _The language-pattern check_: `git diff origin/km/2-tree-move -U0 -- country-kit | grep -nE
      '^[-+][^-+].*\{2,3\}'` lists five lines, all removed and all country patterns (`init.mjs`'s `CODE`,
      `secrets.mjs`'s check, `workspace.mjs`'s `CODE`, the schema's `country`, the launcher's check), and no added
      line. The schema keeps its seven language-pattern lines with `{2,3}` (34, 41, 45, 56, 60, 70 and 106, eight
      occurrences), unchanged, and no file holding a language-code rule (`pack.mjs`, `stage-languages.mjs`,
      `lib/pack-archive.mjs`, `lib/oci.mjs`, `lib/text-overrides.mjs`, `lib/branding.mjs`, `lib/build-checks.mjs`,
      `setup-kit.mjs`, `lib/upgrade-report.mjs`, `templates/pack.yml`, `templates/renovate.json`) is in the diff.
      `manifest.test.mjs`'s "rejects a language code that is not two or three a-z" is unchanged.
    - _Guides and API-CHANGES_: the kit's README says the manifest's `country` is "the country's ISO 3166-1
      alpha-2 code, two letters a–z", the launcher's _What it does, in order_ drops the three-letter `--sample`
      sentence from item 1 and has item 2 read "the country code (two letters a–z; any other exits 1)", and _The
      databases_' `--sample` bullet drops "for a two-letter code only"; its pack folders' rule (two or three
      letters, a language) stays. The country guide's `<cc>` is "two letters in lower case", and the settings
      file's `country` is "the same ISO 3166-1 alpha-2 code as `<cc>`, in upper case", as the base product's
      `defaults/base/settings.json` has `"XX"`. API-CHANGES' Unreleased gains "A country code is two letters",
      after the launcher entry it amends: what a country must do is nothing when its code is two letters, the
      launcher being copied again at the bump. `templates/setup.mjs` is not in the diff, sha256
      `3238c000faa6a01501227aba8d3d2953f3c47d28e3b227907f13137cb564bfb0`.
    - _Superseded wording kept as history_: P3c1 as built's "the country code and the default language (both
      `^[a-z]{2,3}$`)" and P8's "`--sample` with a three-letter code exits 2" are records and keep their words;
      the country code is two letters since KM-7.
    - _Left_: `fe/scripts/info-separation-check.mjs --country` still accepts two or three letters, a base
      product script the kit reaches (`check-build`, `image-info`) only with the manifest's `country`, which the
      schema now holds to two letters.
    - _Run_: the red and green runs above; `npm run test:guards` 1228 tests, 1224 passed, 0 failed, 4 skipped;
      `npm run lint:kit`, `npm run format:check` (before and after the guides' edits) exit 0; `npm run
      docs:check` 35 files, no errors, the 7 known warnings, after the guides' edits; `bash -n` of the launcher
      template, which stays LF only; Prettier over the changed kit files, the two guides included (this plan
      was not Prettier-clean at the base and is left so); a grep of the two guides finding no "two or three
      letters" for `country`, no "three-letter code" and no "for a two-letter code only". Not run: the example
      harness and `image:info`. `./verify-local`: see the sub-PR.
  - **KM-8 as built** (2026-11-01, `km/8-agents-md` into `feature/kit-move`). niord-ng's agent rules are in
    `AGENTS.md`, and Claude Code loads them through a one-line `CLAUDE.md`; no rule's content changed.
    - _The five files._ In the root, `be/`, `fe/`, `fe/ui-apps/admin/` and `fe/ui-apps/info/` the former
      `CLAUDE.md` content is `AGENTS.md`, its heading and the cross-links between the five saying `AGENTS.md`;
      each nested `CLAUDE.md` is the line `@AGENTS.md`. The root `CLAUDE.md` is `@AGENTS.md` and, under `## Claude
      Code only`, the process-start bullet word for word, the one that holds the Read/Grep/Glob and
      `run_in_background` advice; the _Windows sessions_ bullet (`JAVA_HOME`, `NIORD_HOME`, `py`) is
      agent-neutral and stays in `AGENTS.md`. `fe/AGENTS.md`'s `country-kit/templates/CLAUDE.md` is the kit's
      template and stays for KM-5. A scratchpad script wrote the files, each replacement having to match exactly
      once or nothing was written, with LF line ends, git's stored form (the root's and `be/`'s were CRLF in the
      Windows checkout, `core.autocrlf`).
    - _Why the importer_ (decision 100, from Claude Code's memory documentation): Claude Code reads `AGENTS.md`
      natively only when no `CLAUDE.md` is in the working folder or any folder above it, a nested `AGENTS.md`
      loads only when a file in its folder is read, and sessions before v2.1.281 may read `CLAUDE.md` only.
      `@AGENTS.md` expands the sibling file in full, relative to the importing file, so it works in the nested
      folders too.
    - _docs-check._ `AGENT_FILES = ['AGENTS.md', 'CLAUDE.md']`: a folder under `ui-apps/` is an application
      when it holds either file, and `documentationFiles` reads both for `fe/` and each application, `AGENTS.md`
      first, keeping those that exist, so a `CLAUDE.md` that gains content again stays under the guard. The
      reach is unchanged, `fe/` and the kit: the root's and `be/`'s `AGENTS.md` stay outside it, as their
      `CLAUDE.md` did (its vocabulary rules would flag `be/MIGRATION.md` and "legacy" there, a change of the
      rules' content), and a link script checks them. `KIT_TEMPLATE_DOCUMENTS` and the template's comments are
      KM-5's. `docs-check.test.mjs` gains "reads the AGENTS.md files and their CLAUDE.md importers", "finds an
      application by its AGENTS.md or its CLAUDE.md" (a temporary root with `ui-apps/a/AGENTS.md`,
      `ui-apps/b/CLAUDE.md` and `ui-apps/c/src/x.ts`) and the control "accepts a one-line importer CLAUDE.md";
      its existing case naming the public site's file names `ui-apps/info/AGENTS.md`.
    - _The kit._ `workspace-files.json`'s `notNeeded` lists `AGENTS.md`, the base product's agent instructions,
      and words `CLAUDE.md` as its one-line importer for Claude Code; both say a country's are at its repository
      root, from `init`. `workspace.test.mjs` gains "lists the agent files, AGENTS.md and its CLAUDE.md
      importer, as not needed". `zip-include.json`'s exclude names `AGENTS.md` beside every `CLAUDE.md`, as a
      flat brace list since `lib/include.mjs` nests none, and its note says "an AGENTS.md or a CLAUDE.md but
      init's template"; the zip is unchanged, no include glob matching these files, and `zip.test.mjs` gains
      three controls (`AGENTS.md` and both applications').
    - _References._ Repointed to `AGENTS.md`: the root, `fe/` and `be/` READMEs, `be/test-it`'s and
      `verify-local`'s comments, `fe-ci.yml`'s documentation-guard comment (rewrapped by hand) and
      `info-ci.yml`'s two, `country-kit/image-info.mjs`, `fe/scripts/info-container-test.mjs`, `fe/docs/`
      (`platform.md`, `testing.md`, `country-deployments.md`, `definition-of-done.md`, `backlog.md`, and lines 9
      and 202 of `niord-info-integration-plan.md`), the review skill's `SKILL.md`, the vendored skill's
      `VENDORED.md`, and `docs/feature-branch-deploy-plan.md`, whose `CLAUDE.md:48-51` is `AGENTS.md:52-55`, the
      production bullet's lines. Left as written: records and plans (this plan's earlier records,
      `docs/code-data-config-plan.md`, `repo-files-in-database-plan.md`, `image-split-plan.md`,
      `publications-port-inventory.md`, `be/docs/cicd/implementation-plan.md`,
      `be/docs/persistence-modernization-plan.md`, the layout, analysis and record lines of
      `niord-info-integration-plan.md`), GL's own `CLAUDE.md` in `be/docs/test-infrastructure.md`, the kit's
      template and its mentions (KM-5), and `source-zip.test.mjs`'s fixture. From KM-8 on, a plan's
      `X/CLAUDE.md` names the rules now in `X/AGENTS.md`.
    - _Sizes_ (LF, characters read by node / bytes, `wc -c`): the root 8,747 / 8,767, `be/` 17,642 / 17,676,
      `fe/` 27,440 / 27,540, `fe/ui-apps/admin/` 9,563 / 9,614 and `fe/ui-apps/info/` 35,607 / 35,775, 89.0% of
      the 40,000-character limit for an on-demand file and still the nearest; the nested importers 11 bytes, the
      root's `CLAUDE.md` 457 / 461 (§7).
    - _Run_: red first, with the `AGENTS.md` files written and `HEAD`'s `docs-check.mjs` and
      `workspace-files.json` in for one run, `node --test scripts/docs-check.test.mjs
      ../country-kit/workspace.test.mjs` from `fe/` gave 59 tests, 56 passed, 3 failed, exactly the two
      docs-check cases (`documentationFiles` listing `CLAUDE.md`, `ui-apps/admin/CLAUDE.md`, … and no
      `AGENTS.md`) and the workspace case, the importer control passing (a run before the `AGENTS.md` files
      existed failed a fourth, the case naming `ui-apps/info/AGENTS.md`); green, with `zip.test.mjs`, 77 of 77;
      the other readers of `zip-include.json` (`branding`, `country-words` and `source-zip` tests) 83 tests, 82
      passed, 1 skipped (the environment); `npm run docs:check` 38 files, the three `AGENTS.md` among them, no
      errors, 7 warnings, the base's (the PrimeNG one now at `AGENTS.md:51`); `npm run lint:scripts` exit 0;
      `ci-filters.test.mjs` 6 of 6; `bash -n verify-local` and `bash -n be/test-it`; both workflows parse
      (`js-yaml`); Prettier `--check` over the changed files in `fe/` and `country-kit/` but the workflows; the
      link script, 66 relative links in the five `AGENTS.md` and the root `CLAUDE.md`, none unresolved, and each
      importer's content as above; a line diff of each `AGENTS.md` against `HEAD`'s `CLAUDE.md`
      (`--ignore-cr-at-eol`), showing only the renamed names and, in the root, the moved bullet; a search for
      `CLAUDE.md` over the tree, finding only the sites left above; `npm run test:guards` 1229 tests, 1225
      passed, 4 skipped (the environment's symbolic-link privilege, as on the base); `npm run format:check` exit
      0, the three `fe/` `AGENTS.md` and their importers inside its reach as their `CLAUDE.md` were; `npm run
      paths:check` and `npm run lint` exit 0. Not yet run: the workspace test's `git ls-files` case after the
      commit (green before it, with `AGENTS.md` untracked) and `/memory`. `./verify-local`: see the sub-PR.
    - _How to verify_ that Claude Code loads the rules: `/memory` in a fresh Claude Code session in the worktree
      lists the root `CLAUDE.md` with its `AGENTS.md` at start, and each nested `CLAUDE.md` with its `AGENTS.md`
      once a file in its folder is read; not yet run.
  - **KM-5 as built** (2026-11-01, `km/5-guides` into `feature/kit-move`). The guides, the agent template and
    docs-check rule 7 follow the kit to `.niord/kit/`; no code a country runs changes before KM-6.
    - _Rule 7's two sets._ `readWorkspace` gives `kitPackage = { carries, files, folders }`, the kit set: the kit
      folder's files that `country-kit/kit-include.json` selects, plus `KIT_ADDED` (`docs/country-api.md` and
      `kit.json`, which the build adds), or null when the kit or its list is missing. Beside it stays `zip`, the
      fe-src set: what `zip-include.json` selects plus `catalog.json`, `country-kit/**` included until KM-6, so
      the set stays faithful to the list. One `foldersOf` helper serves both, and `inZip` became `inSet`. The kit set
      comes from git's list (`gitFiles`) or, when git does not see the folder, from `walkFiles`, skipping the
      folders `local-sources.mjs` skips (a local `UNWALKED`, so no kit file is edited to export them); both gave
      the same 135 files. Per document kind:
      - _A document the kit package carries_ (the kit's README, API-CHANGES and `docs/`): a relative link must
        name a file or folder of the kit, or fails with "link leaves the kit package (a base product file is
        written as a backticked fe/.niord/base/ path)"; a backticked `.niord/kit/…`, or `../.niord/kit/…` as a
        command run in `fe/` writes it (`KIT_PATH`), or a `country-kit/…` path must name a kit file ("not in the
        kit package"); `fe/.niord/kit/…` (`MISPLACED_KIT`) fails with "the kit unpacks at .niord/kit/ at the
        repository root, not in fe/"; a `.niord/base/…` path, or one into a workspace folder, must be in the
        source zip ("not in the source zip").
      - _A document the source zip carries outside the kit_: a link that resolves to `country-kit` or under it
        fails with "link into the kit package, which a country unpacks at .niord/kit/, outside the source zip",
        checked first because the fe-src set still holds the kit; any other link keeps "link leaves the source
        zip", and its `.niord/base/` paths keep their check.
      - _The agent template_: `KIT_TEMPLATE_DOCUMENTS = ['country-kit/templates/AGENTS.md']`, read for its
        `.niord/kit/` and `.niord/base/` paths only.
      - `country-kit` left `WORKSPACE_FOLDERS` (design §7); `checkedPath` handles `KIT_FOLDER` as the second
        root's prefix, so an `fe/` document still resolves `country-kit/…` against the kit folder. A
        `packagePath` helper serves both prefixes. `fe/AGENTS.md`, `fe/docs/coding-standards.md` and
        `fe/docs/definition-of-done.md` restate rule 7 with the two sets, `fe/AGENTS.md` names
        `../.niord/kit/pack.mjs` and `country-kit/templates/AGENTS.md`, and the backlog entry stays open with
        its facts corrected (the guides arrive in the kit package, only the niord-base jar is fetched, the
        contract is at `.niord/kit/docs/country-api.md`).
    - _The template._ `country-kit/templates/AGENTS.md` is written beside `templates/CLAUDE.md`, which stays
      unchanged on disk and is recorded as a rename at commit; until then the kit package and the source zip
      carry both. Against `templates/CLAUDE.md` it differs in 40 lines, each an edit or a re-wrap inside an
      edited paragraph: the heading; a sentence that `CLAUDE.md` beside it holds only `@AGENTS.md`; line 13's
      note, now that set-up unpacks the kit into `.niord/kit/` at the root and the frontend into
      `fe/.niord/base/`; every path to `.niord/kit/` (no `.niord/base/country-kit` is left); country-api.md read
      at `.niord/kit/docs/country-api.md`, the launcher-copy sentence dropped; `.niord/` at the root generated
      and never committed, and both `.niord/` and `fe/.niord/` in the Never list; the base bump naming both pins
      and both release-note lines, `<sha256>  niord-fe-src-<version>.zip` and
      `<sha256>  niord-country-kit-<version>.zip`; and step 4 merging from `.niord/kit/templates/AGENTS.md`.
      Step 2 still says "Move both pins", which reads as the two files.
    - _init._ It writes `AGENTS.md`, byte for byte `template('AGENTS.md')`, and `CLAUDE.md` as
      `CLAUDE_IMPORTER`, the exported line `@AGENTS.md`. A `CLAUDE.md` that exists is kept, and after the kept
      lines init prints "init: CLAUDE.md exists; add the line @AGENTS.md to it, so that Claude Code reads the
      agent contract in AGENTS.md" unless one of its lines, trimmed, already is `@AGENTS.md`; an existing
      `AGENTS.md` is kept like any file, the one-line `CLAUDE.md` written beside it. The header's row became two,
      `AGENTS.md` and `CLAUDE.md`. The template is read from `kit`, the kit's own folder (`where().kit`), so it
      works from `.niord/kit` unchanged.
    - _The guides._ The first-time block is the same text in the kit README's _Starting a country_ and the
      country guide's step 2 (both extracted and compared, identical):

      ```bash
      be/mvnw -B -f be/pom.xml dependency:copy \
        -Dartifact=org.niord.web:niord-fe-src:<version>:zip -DoutputDirectory="$PWD/fe/.niord"
      be/mvnw -B -f be/pom.xml dependency:copy \
        -Dartifact=org.niord:niord-country-kit:<version>:zip -DoutputDirectory="$PWD/.niord"
      (cd fe/.niord && printf '%s\n' '<fe-sha256>  niord-fe-src-<version>.zip' | sha256sum -c)
      (cd .niord && printf '%s\n' '<kit-sha256>  niord-country-kit-<version>.zip' | sha256sum -c)
      unzip -q fe/.niord/niord-fe-src-<version>.zip -d fe/.niord/base
      unzip -q .niord/niord-country-kit-<version>.zip -d .niord/kit
      node .niord/kit/init.mjs <cc> --zip-sha256 <fe-sha256> --kit-sha256 <kit-sha256>
      ```

      Each `sha256sum -c` runs in the folder its zip landed in, and nothing from either zip runs before both
      pass. Both texts say `.niord/` is never committed: `fe/.gitignore` names `fe/.niord/`, and the root's
      `.niord/` ignores itself through the `.gitignore` holding `*` that `init` and set-up write; nothing tells a
      country to commit it. country-api.md is read at `.niord/kit/docs/country-api.md`: _The base product's
      backend files_ fetches the niord-base jar only, for the two things neither zip carries, and the
      `niord-local-run` fetch and the `.local-run/…/docs/country-api.md` sentences (_For KM-5_ under _KM-3 as
      built_) are gone. The four relative links into the base product became backticked base paths
      (`country-guide.md`'s `fe/.niord/base/languages/en/ATTRIBUTION.txt` and
      `fe/.niord/base/styles/tokens.base.css`) or were dropped where a backticked path already named the file
      (`designers-brief.md`'s two), and the README's link to `source-zip.mjs`, which the package leaves out, is
      plain code with "(niord-ng's publish tool, which the kit package leaves out)". The README's commands name
      the kit as each runs: `node ../.niord/kit/pack.mjs`, `lib/oci.mjs`, `audit.mjs`, `check-build.mjs` and
      `image-info.mjs` from `fe/`, `.niord/kit/templates/…` from the root. The upgrade (the responsibilities
      guide's chart and steps 2, 3, 5 to 8) takes both release-note lines, sets `base.version`, `base.sha256`
      and `kit.sha256`, merges `.niord/kit/templates/AGENTS.md` into `AGENTS.md` and reads the new contract in
      the kit. `docs/README.md` names the lock's two checksums, the kit package as where the guides ship, and the
      row "An AI agent" for `templates/AGENTS.md`; the country guide's step 2 and the upgrade's step 7 say "an
      AI agent" too. The country guide's definition of done names nothing under `.niord/` or `fe/.niord/`, and
      its recipe says "the commands of" for the block's nine lines. `operators-guide.md` names none of these and
      is unchanged.
    - _API-CHANGES._ The policy line's copy path is `.niord/kit/templates/setup.mjs`. In the first Unreleased
      entry the template is `templates/AGENTS.md`, `init` writes `AGENTS.md` with a one-line `CLAUDE.md`, and the
      merge of a country that keeps its own agent contract comes from `.niord/kit/templates/AGENTS.md` into its
      `AGENTS.md`, never into `CLAUDE.md`; the Unreleased `start-local.sh`, `pack.yml` and
      `image.yml` paths are `.niord/kit/templates/…`. Two entries follow the first: "The agent contract is
      `AGENTS.md`." (a country renames its `CLAUDE.md` to `AGENTS.md`, commits a `CLAUDE.md` holding
      `@AGENTS.md`, and merges the template from `.niord/kit/templates/AGENTS.md`) and "The backend contract
      comes with the kit." (read it at `.niord/kit/docs/country-api.md`, drop any fetch from `niord-local-run`).
      The released sections keep their versions' paths (`.niord/base/country-kit/…` at lines 494-498, 4.0.87,
      and 579, 4.0.81).
    - _`zip-include.json`_ is unchanged: KM-8's exclude already names every `AGENTS.md` beside its `CLAUDE.md`,
      and `templates/` is deliberately not excluded, since init's template must ship. `zip.test.mjs` asserts
      `country-kit/templates/AGENTS.md` in the source zip's list and `templates/AGENTS.md` in the kit package's
      (`templates/**`).
    - _Red first and green_, from `fe/`. `node --test scripts/docs-check.test.mjs` with the new cases against
      `HEAD`'s `docs-check.mjs`: 23 tests, 6 failed, the five intended cases (the kit link-outs, the kit paths,
      the template, the source-zip link into the kit, the two sets) and the tree case, which saw the guides'
      `templates/AGENTS.md` link mid-edit; 11 of the 16 crafted assertions red. The other five passed already:
      `ui-apps/admin/e2e/auth/README.md`, `fe/.niord/base/docs/backlog.md`, `.niord/base/country-kit/missing.md`,
      the template's `fe/.niord/base/docs/missing.md` and "leaves the source zip" in a source-zip document; the
      `.niord/base/` half already held, so its cases are regression cases. Green, 23 of 23. `node --test
      ../country-kit/init.test.mjs` against `HEAD`'s `init.mjs`: 45 tests, 4 failed (the skeleton without
      `AGENTS.md`, the byte-for-byte case on a missing `AGENTS.md`, the given-kit case, and the new case "keeps
      a country's own CLAUDE.md, writes AGENTS.md beside it, and prints that CLAUDE.md must import it", with
      its two controls, a `CLAUDE.md` that already imports and an existing `AGENTS.md`); green, 45 of 45.
      `zip.test.mjs` 19 of 19.
    - _Run_ besides: `npm run docs:check` 38 files, `templates/AGENTS.md` among them, no errors, the 7 known
      warnings; the edited guides also under `HEAD`'s rule 7 (a scratchpad copy of the old script), the same
      result; `npm run lint:kit` and `npm run lint:scripts` exit 0; Prettier `--check` over each package's
      changed files; the stub template `templates/setup.mjs` and the example's copy both
      `3238c000faa6a01501227aba8d3d2953f3c47d28e3b227907f13137cb564bfb0`, unchanged; the greps finding no
      `.niord/base/country-kit` in `docs/*.md` or the template, none in API-CHANGES above `## 4.0.97`, and in the
      README only line 15, the resolver's layouts; no `local-run/docs/country-api.md` in the guides; no
      `templates/CLAUDE.md` in the guides or the README, and in API-CHANGES only the new entry's "it was
      `templates/CLAUDE.md`". `npm run test:guards`: 1299 tests, 1295 passed, 0 failed, 4 skipped (the
      symbolic-link privilege). `npm run format:check` exit 0. In a scratch copy without git (the walk
      fallback, 135 kit files) the four crafted cases each exit 1 with their message: "link leaves the kit
      package" for the country guide's link to `../../styles/tokens.base.css`, "not in the kit package" for
      `.niord/kit/missing.md` in `docs/README.md` and for `.niord/kit/docs/missing.md` in `templates/AGENTS.md`,
      and "not in the source zip" for `.niord/base/missing.md` in `README.md`; under the old rule case 4
      already failed and cases 1 and 2 passed. `npm run kit:package -- --version 4.0.0-km5` built 135 entries,
      `templates/AGENTS.md`, `docs/country-api.md` and `kit.json` among them (and `templates/CLAUDE.md` until
      the rename at commit), and `source-zip.mjs verify --package kit`, `guard --package kit` and `sha256sum
      -c` all exit 0.
    - _Stated plainly_: on the feature branch the guides and the template now describe KM-6's stub and init
      before they exist: `--kit-sha256`, the kit fetched into `.niord/` and unpacked at `.niord/kit/` by set-up,
      `.niord/.gitignore`, `kit.sha256` in the lock and the generated scripts at `../.niord/kit/`. Until KM-6,
      `init` refuses `--kit-sha256` as an unknown argument (exit 2), and the lock has no `kit.sha256`.
    - _Left for KM-6_:
      - Once `zip-include.json` drops `country-kit/**`, rule 7's fe-src set no longer holds
        `.niord/base/country-kit/…`, and the released API-CHANGES lines (494-498, 579) fail it; they then need
        an exemption for released sections or a ruling. `docs-check.test.mjs`'s template case accepts
        `fe/.niord/base/country-kit/README.md` and its guide case rejects `.niord/base/country-kit/missing.md`,
        both resting on that glob: the accepted path becomes a lasting source-zip file such as
        `fe/.niord/base/styles/tokens.base.css`.
      - init's usage (line 7, `node fe/.niord/base/country-kit/init.mjs`), its one-zip text (10-14), the
        `--kit-sha256` flag and the kit zip check, the `.niord/.gitignore` write, and the image.yml message
        (line 293) with `init.test.mjs`'s assertion of it (573); the country guide's list of what init writes
        then names `.niord/.gitignore`.
      - The kit README's text that still has the kit inside `fe/.niord/base/`: the resolver's layouts (13-17),
        set-up (71-146, 874), the harness (1808-1813), the source zip (1881-1883) and the retract paragraph
        (1991-1993); and `workspace-files.json`'s `country-kit` note ("read from .niord/base/country-kit/").
      - An Unreleased API-CHANGES entry for the kit move itself (`org.niord:niord-country-kit`, `.niord/kit`,
        the second sha256 line, `--kit-sha256`, `kit.sha256` in the lock), which the guides already describe,
        written with the code.
    - _Not run_: the example harness, `image:info` and `./verify-local` (see the sub-PR); no publish. The plan
      is not Prettier-clean and is left so.
  - **KM-6 as built** (2026-11-01, `km/6-switch` into `feature/kit-move`). The switch: a country takes the kit
    from its own package, `org.niord:niord-country-kit`, unpacked at `.niord/kit/` at the repository root, and
    `niord-fe-src` no longer carries the kit but for the two entries of the one-version tombstone.
    - _The stub_, `templates/setup.mjs` and its byte copy `example/fe/setup.mjs`, both sha256
      `6d74643114f8efa59b8f681315b17df0bf907d44008a78618fee657e4ec81acd` (no longer `3238c000…`), Node built-ins
      in one file as before. In order:
      - _The lock._ `readLock` requires `kit: { sha256 }` and otherwise stops with "niord-pins.lock.json has no
        kit { sha256 } (64 lower-case hex digits): pin the kit package by its line
        '<sha256>  niord-country-kit-<v>.zip' in the release notes", in both modes, before anything is
        written. A lock that is not JSON is rethrown with its cause. The kit's own `readLock`
        (`lib/pins.mjs`) keeps the kit optional, as KM-4 built it; its comment says so.
      - `NIORD_BASE` under CI is refused right after the lock, so such a run writes nothing. Then
        `<root>/.niord/.gitignore` is written as `*` and a newline (`NIORD_GITIGNORE`, exported) when it is
        absent or different, in both modes, before any fetch.
      - _The kit_, pinned mode, first: `mvn dependency:copy` of `org.niord:niord-country-kit:<v>:zip` into
        `<root>/.niord/` (`mavenCommand` takes `artifact`, by default `org.niord.web:niord-fe-src`, with the same
        path and argument checks); the zip's hash against `lock.kit.sha256` before any entry is read ("… has the
        SHA-256 X, not the pinned Y: nothing was unpacked"); `kit.json` must be `{package: 'niord-country-kit',
        version: <the pom's parent>}`, checked with the stub's own `KIT_MARKER` and `KIT_PACKAGE` since it
        imports only built-ins, or nothing is unpacked; the safe unpack (a temporary folder, then a rename) into
        `<root>/.niord/kit`; the marker `<root>/.niord/kit.sha256` holding the hash and a newline; and the zip
        kept, for the next upgrade report.
      - _The base product_ as before, its messages and logs byte for byte; the two fetches share `fetchZip` and
        `unpackedAlready`. Outside CI each fetch is skipped when its folder exists and its marker equals the
        pin; under CI both run. A cold start is two Maven runs, the kit first.
      - `prepare` returns `{ source, kit, version, sha256, kitSha256, provenance }`, the provenance unchanged
        (`pinned: org.niord.web:niord-fe-src:<v>:zip sha256:<h>`; KM-4's `buildReport` appends the kit).
      - _`NIORD_BASE`_: the kit is `<NIORD_BASE>/../country-kit`, refused without a `setup-kit.mjs` there
        ("NIORD_BASE=… is not a base product checkout's fe/ (no ../country-kit/setup-kit.mjs beside it)"); both
        markers are removed, the stub copies nothing, and `sha256` and `kitSha256` are null with the `local: …
        @ <commit>` provenance. The checkout's kit copies itself (below).
      - _The hand-over_, the new export `handOver({ fe, stub, handover, load })`, imports
        `<handover.kit>/setup-kit.mjs` and calls `setup({ fe, stub, source, version, sha256, kitSha256,
        provenance })`, exactly those keys; `kit` only finds the module. The script's main path uses it, and
        `parentVersion` is unchanged.
      - _The two lint findings_ the override had deferred to the next stub change are fixed: the rethrow keeps
        its cause, and the control-character check compares character codes instead of a regular expression.
        `eslint.config.mjs`'s override for the two rules moves to `test-fixtures/setup-4.0.97.mjs`.
    - _Set-up_ (`setup-kit.mjs`). `STUB_CHANGED` is "stub: fe/setup.mjs is not the kit's own; the base product
      changed it: in fe/, copy ../.niord/kit/templates/setup.mjs over setup.mjs"; `checkStub` refuses with
      "fe/setup.mjs is not the kit's own: it is never edited. In fe/, copy ../.niord/kit/templates/setup.mjs
      over setup.mjs". When the source is not `fe/.niord/base` and the kit is not already `<fe>/../.niord/kit`,
      set-up copies the kit there before the stub check, so the copy the message names exists, and logs
      "setup: <n> file(s) of the kit <KIT> copied into ../.niord/kit/, with its marker and the backend
      contract"; `copyBase` then runs as before. A pinned set-up never copies.
      - `lib/local-sources.mjs`'s new `copyKit({ kit, target, version, be })` copies the kit files
        `kit-include.json` selects (git's list, else the walk) through `copyInto`, refuses a selected
        `docs/country-api.md` or `kit.json` ("… is selected, but the copy adds that name"), writes
        `docs/country-api.md` from `<be>/docs/country-api.md` with CRLF read as LF (a missing contract throws,
        naming the path) and `kit.json` as `markerText(version)`, and returns the count of copied kit files
        without the two added. It imports nothing of `source-zip.mjs`, which the kit package leaves out: the
        selection rule is written again, and a `setup.test.mjs` case holds the copy equal to `kitEntries` by
        names and bytes. Two fixes the new unit case found: the contract's `docs/` folder is created when no
        selected file is under `docs/`, and `copyInto` creates its temporary folder up front, an empty
        selection included.
      - `stubTemplate` gives `.niord/base/country-kit/templates/setup.mjs` for `in-base` and
        `../.niord/kit/templates/setup.mjs` for `package` and `source`, since a checkout set-up now fills
        `.niord/kit`. `upgrade-report.test.mjs`'s To-do stub case (line 417) changed with it, its fixture kit
        resolving as the source layout.
    - _init_. The usage is `node .niord/kit/init.mjs <cc> --zip-sha256 <fe-sha256> --kit-sha256 <kit-sha256>   #
      from the repository root`, identical to `country-guide.md`:461 and the README's:1531; the header describes
      both packages and both `sha256sum -c` runs. Its checks, all before anything is written: the country code,
      the `--zip-sha256` shape, the `--kit-sha256` shape, the pom, `kit.json`'s version through `where(kit)`
      ("the kit at .niord/kit is <x>, but be/pom.xml's parent is <v>: unpack the kit package of <v>", so the
      marker's shape and place are checked too), the fe-src zip (its messages unchanged), and
      `.niord/niord-country-kit-<v>.zip` ("does not exist: fetch the release's kit package first", "has the
      SHA-256 X, not Y: nothing was written"). The lock carries `kit: { sha256 }`; `.niord/.gitignore` is
      written when absent and listed as kept when present (`NIORD_GITIGNORE` exported here too); the image.yml
      message names `.niord/kit/templates/image.yml`; an unknown argument prints the new usage, exit 2.
    - _The generated paths._ `KIT_PATH` is `../.niord/kit`, for a pinned and a checkout set-up alike, so the
      build, audit and `image:info` scripts and the require `require('../.niord/kit/lib/country-lint.cjs')`
      follow; `bundle:info` is unchanged, and no caller passes `kitPath`. `workspace-files.json`'s `country-kit`
      note and the `what` of `package.json` and `eslint.config.js` name `../.niord/kit/`. `templates/pack.yml`
      runs `node ../.niord/kit/pack.mjs latest-catalog …`, `node ../.niord/kit/pack.mjs validate …` and `cp
      ../.niord/kit/lib/oci.mjs …`, its comment naming the two pinned packages; the launcher's message is
      "fe/setup.mjs has no parentVersion: copy .niord/kit/templates/setup.mjs over it", without `../` since it
      runs at the root. The usage comments of `audit.mjs`, `check-build.mjs`, `guard-build.mjs` and `pack.mjs`
      say `node ../.niord/kit/<script>.mjs`. `workspace.test.mjs`'s explicit-path cases take the old
      `.niord/base/country-kit`, still a valid kit path, as the other path.
    - _The tombstone_, `fe/scripts/kit-move-tombstone.mjs`, Node built-ins only and importing nothing relative
      (the closure check's rule a), is `country-kit/setup-kit.mjs` in the zip. It exports `KIT_MOVED`: "stub:
      fe/setup.mjs is not the kit's own; the kit moved to its own package: in fe/, copy
      .niord/base/country-kit/templates/setup.mjs over setup.mjs, and pin the kit in niord-pins.lock.json as
      kit.sha256, from the release notes' niord-country-kit line". Its `setup()` takes the 4.0.97 hand-over, its
      other keys ignored, warns `setup: error: <KIT_MOVED>` and, when the set-up record names another base
      version, writes `fe/.niord/niord-upgrade-report.md` with the five headings in order: To do holds `cp
      .niord/base/country-kit/templates/setup.mjs setup.mjs` and the kit pin from the release notes'
      `niord-country-kit-<v>.zip` line, New for you and Dependency changes "Not compared: set-up stopped before
      its checks.". It then throws "1 check(s) failed; nothing was staged or generated for the applications"
      and writes nothing else, never the record. An unreadable record, or one without a plain version, counts
      as none (no report); the country falls back to "the country". `test-fixtures/setup-4.0.97.mjs` is the
      4.0.97 stub byte for byte (`git show 62b7b3e0:country-kit/templates/setup.mjs`, sha256 `3238c000…`):
      `tombstone.test.mjs` runs its `prepare()` with a fake Maven against a zip holding the tombstone and then
      does what its `main` does, and `pins.test.mjs`'s "passes a kit pin through" imports it.
      - _`zip-include.json`_ drops `country-kit/**` and the kit's excludes, includes
        `country-kit/templates/setup.mjs` (the new stub the tombstone points to), and gains the optional field
        `"add": { "country-kit/setup-kit.mjs": "scripts/kit-move-tombstone.mjs" }`, zip name to a path under
        `fe/`; its note is rewritten. The format becomes `{ format, note, include, exclude, add }`, with
        `INCLUDE_FORMAT` still 1, since `add` is optional. `lib/include.mjs` refuses an `add` that is not an
        object of plain relative paths (no glob character, backslash, leading `/`, drive, or empty, `.` or `..`
        segment); `source-zip.mjs`'s build packs each `add` entry from `fe/` and fails on a missing source, a
        link, a file that is not regular, and a name the globs select too; verify accepts the `add` names but
        does not require them; the kit-package code is unchanged. Set-up's local `NIORD_BASE` copy ignores
        `add`, so a local base carries no tombstone, and `copyBase` now copies of the kit only
        `templates/setup.mjs`.
      - `seams.json` drops the moot protected `country-kit/**`, and `seams.test.mjs` its
        `country-kit/stage-languages.mjs` line; `country-words.test.mjs` asserts `lib/country-words.mjs` in the
        kit package. `source-zip.test.mjs`'s two import-boundary cases call `importClosureProblems` over their
        crafted archive, whose `country-kit/lib/*` entries the list no longer selects.
    - _docs-check rule 7._ The fe-src set gains the `add` names. In `country-kit/API-CHANGES.md` the lines below
      the first heading after `## Unreleased` belong to released versions and skip rule 7's package checks of
      backticked paths; links, the never-valid `fe/country-kit/` and `fe/.niord/kit/` paths and the other rules
      still apply there, a heading inside a fence does not count, and a test shows another document with the
      same headings is still checked. Without it the 4.0.87 lines 494, 497 and 498 failed "not in the source
      zip", as KM-5's record foresaw. The template case's accepted base path is now
      `fe/.niord/base/styles/tokens.base.css`.
    - _The harness._
      - `acceptance.mjs`: `classifySetup` counts `STUB_CHANGED` or `KIT_MOVED` (imported from the tombstone) as
        a stub change; `fixtureZip` no longer touches API-CHANGES, and the new `fixtureKitZip(kitZip, { version,
        removedIn })` writes `kit.json` as `markerText(version)` and the fixture's API-CHANGES section above
        Unreleased; `untouchedInit` follows the guides' first-time path (the source zip into
        `yy/fe/.niord/niord-fe-src-<v>.zip` unpacked into `fe/.niord/base`, the kit zip into
        `yy/.niord/niord-country-kit-<v>.zip` unpacked into `yy/.niord/kit`, then `node .niord/kit/init.mjs yy
        --zip-sha256 <fe> --kit-sha256 <kit>` from `yy/`) (since N2-3, 2026-11-03, the Quick start's path instead:
        both zips and `SHA256SUMS` in `yy/.niord/`, only the kit unzipped,
        `init --answers .niord/init-answers.json --sha256-file .niord/SHA256SUMS`; _N2 as built_), checks
        `yy/.niord/.gitignore` and runs
        `../.niord/kit/check-build.mjs`; `publishAndPin` takes `oci.mjs`, `pack.mjs` and `guard-build.mjs` from
        the kit at `.niord/kit`; `bump(country, version, hash, kitHash)` is exported and writes the kit pin when
        given a hash. The stub copy left `routineActions`: `stubSource(country)` returns
        `.niord/kit/templates/setup.mjs`, else `fe/.niord/base/country-kit/templates/setup.mjs`, and throws
        naming both, and `copyStub(country, h)` copies it.
      - `upgradeTest` takes `from = { version, zip, sha256, kitZip?, kitSha256? }`: the example `be/` and `fe/`
        from the kit zip's `example/`, else from the zip's `country-kit/example/`; both bumps with both hashes;
        after a stub copy, set-up once more ("up: node setup.mjs with the new stub, past the stub check"),
        whose overrides the routine actions take. That second run stops the run as a step when it is still a
        stub change or exits non-zero without a `setup: error:` line; it is not a failure handed to
        `upgradeVerdict`, so the kit-move entry's "must act" can never excuse it. The previous API-CHANGES comes
        from the previous kit zip, else from the zip's `country-kit/API-CHANGES.md`, the current from the new kit
        zip.
      - `run.mjs`: `--upgrade-kit-from` needs a value, comes only with `--upgrade-from`, and is refused with a
        space, a cmd.exe character or a path that is not a file; `previousZip` also returns `layout`,
        `pre-move` when the zip has `country-kit/example/be/` entries, else `post-move`; the new
        `previousKitZip(file, version)` takes the version from `kit.json`, which must equal the zip's (an older
        kit is not run through this tree's `verifyKitZip`, since its include list may differ). Before anything
        is built or installed, `main` refuses a post-move `--upgrade-from` without `--upgrade-kit-from` ("give
        its kit package with --upgrade-kit-from") and a pre-move one with it. It clears both artifacts from the
        scratch repository, builds both packages with their publish checks, installs `niord-fe-src`, then the
        kit, then `niord-parent`, pins the kit in xx's lock, runs `../.niord/kit/pack.mjs`, derives and installs
        both fixture packages at `NEXT_VERSION`, and in the upgrade test installs the previous kit when given.
    - _fe-ci's previous step_ ("The previous published zip (the latest release's niord-fe-src, and its
      niord-country-kit when its notes name one)") greps the kit line with the same name-anchored pattern after
      the fe-src checks. No line is the pre-move layout: it says the release is from before the kit package
      and its kit is the zip's `country-kit/`, writes `kit=` and exits 0, the path the main PR's CI takes
      against 4.0.97. More than one line is an error; otherwise it downloads `niord-country-kit-<v>.zip` (no
      asset: "has no asset"), checks its sha256 ("refusing an unverified previous kit") and writes
      `kit=<path>`. `zip=` and `kit=` are written together at the end of each successful path, so a failed kit
      check leaves neither; the no-release branch writes `kit=` too. The harness step passes
      `${UPGRADE_KIT_FROM:+--upgrade-kit-from "$UPGRADE_KIT_FROM"}`. Detect's example rule and `verify-local`'s
      `EXAMPLE_RULE`, the same expression, gain `kit-move-tombstone`, since the zip carries it; `verify-local`
      takes `--upgrade-kit-from <zip>`, with the same space and file checks and only with `--upgrade-from`
      (`previous_zip`, shared by both options), and passes it to `run.mjs`; its usage is lines 3-16.
      `ci-filters.test.mjs` gains a case that both rules take the `fe/scripts/` files the zip carries, every
      `add` source among them, and leave out `docs-check.mjs` and `ci-test-shard.mjs`.
    - _API-CHANGES._ The first Unreleased entry, breaking, is "The kit is its own package,
      `org.niord:niord-country-kit`; the set-up stub changed.": what changed, why (the kit is a package of its
      own outside `fe/`, two packages of one version each pinned by its hash; no decision id, which docs-check
      warns on), what a 4.0.97 country sees at the bump (the literal `setup: error: <KIT_MOVED>` and the report),
      the steps (copy the stub, pin `kit.sha256` from the release notes, run `node setup.mjs` again, copy
      `pack.yml` and `start-local.sh` again), and nothing for `.niord/.gitignore`. `acceptance.test.mjs` checks
      that the entry holds the tombstone's text literally.
    - _The guides_: the README lines KM-5 left (the resolver's layouts, the stub's row, set-up steps 1 and 2,
      the `NIORD_BASE` row, the by-hand withdrawal, the harness paragraphs with `--upgrade-kit-from`, the source
      zip without the kit and its two tombstone entries, the retract paragraph), and its opening packaging
      sentence and the kit-package intro, which KM-5's list did not name but which KM-6 made wrong; no
      `fe/scripts/` path is backticked. The country guide's list of what init writes names `.niord/.gitignore`.
      The harness package compared every path and flag the harness and init use with the guides and found no
      deviation; the check agent's grep comparison found the same: init's usage line equals
      `country-guide.md`:461 and the README's:1531, the zip folders equal the `dependency:copy` lines, the lock
      key equals `responsibilities-and-upgrades.md`'s, and the stub copy is `.niord/kit/templates/setup.mjs`
      in the guides and `../.niord/kit/templates/setup.mjs` (from `fe/`) in `STUB_CHANGED`, `checkStub` and
      `stubTemplate`.
    - _Superseded wording_, corrected in the plan's body: §4.5's workspace command (`node ../.niord/kit/pack.mjs
      …`), §4.12's stub row, §5's step 2, the design's layouts bullet (the in-base layout goes in NG-B), its
      tombstone bullet (the `add` field) and NG-B's list. Records keep their words: _KM-1 as built_'s "removed
      … once `niord-fe-src` no longer carries the kit" and "until KM-6" (the in-base layout stays to NG-B, the
      move version's zip carrying `country-kit/setup-kit.mjs` at `fe/.niord/base`); _KM-4 as built_'s
      `stubTemplate` for the source layout and `KIT_PATH`'s default; P3c1's `.niord/base/country-kit/` scripts
      and `image:info`; _KM-5 as built_'s "Until KM-6". The design's §4, the stub copying the kit through
      `kit-include.json` under `NIORD_BASE`, and the map's scope saying so, are superseded by the design bullet
      above: the checkout's kit copies itself. `lib/include.mjs`'s "Three readers use it, so all three select
      the same files" now says the three select the same files by the globs and that the local copy ignores
      `add`. Left for KM-9: the comments of `publish.yml` (lines 8, 444, 655) and `fe-ci.yml` (198) describe the
      source zip as the files of `fe/` and of the kit beside it that `zip-include.json` selects, without `add`
      or the kit's leaving.
    - _Red first and green_, from `fe/`:
      - the stub: a namespace-import copy of the new `stub.test.mjs` against the old stub, 48 tests, 32 passed,
        16 failed, exactly the new or changed cases (the file itself failed to load, no `NIORD_GITIGNORE`
        export); green 48 of 48, with `pins.test.mjs` and `launcher.test.mjs` 91 of 91; `setup.test.mjs`'s stub
        check suite (parity, CRLF, edited stub) passing.
      - set-up and init: `init.test.mjs` and `upgrade-report.test.mjs` 93 tests, 11 failed for the expected
        reasons, then 93 of 93; `setup.test.mjs`'s red run was muddied by the stub package's edit in flight (the
        two copies differed), the new copy-before-the-stub-check case failing on the old `checkStub` text as
        expected; green 69 of 70 at first, the kit copy's byte comparison failing once on a README edited
        during the run, that describe 3 of 3 again; finally `init`, `upgrade-report`, `setup`, `pins` and
        `where` 195 of 195.
      - the generated paths: `workspace.test.mjs`, `pack-workflow.test.mjs` and `launcher.test.mjs` 104 tests,
        7 failed for the expected reason, then 104 of 104; `tooling.test.mjs` 4 of 4.
      - the tombstone and the zip: `tombstone.test.mjs` failed to load without the module, then 5 of 5;
        `zip.test.mjs` 3 failures, then 21 of 21; `source-zip.test.mjs` 6 failures, then all passing;
        `docs-check.test.mjs` 3 failures (the tree's API-CHANGES 494, 497 and 498, the fe-src set without
        `setup-kit.mjs`, the released-section case), then 24 of 24; with `seams`, `country-words`, `pins` and
        `branding` 182 tests, 181 passed, 1 not run (the environment).
      - the harness and CI: `example/acceptance.test.mjs` and `example/run.test.mjs` failed to load on the
        missing exports (`bump`, `previousKitZip`); `release-workflows.test.mjs` and `ci-filters.test.mjs` 58
        tests, 8 failed (7 release-workflows cases, the example rules' case); green, the four files 116 of 116.
    - _Run_ besides, by the packages: `npm run lint:kit` and `npm run lint:scripts` exit 0; Prettier `--check`
      over each package's changed files but the workflow; `npm run docs:check` no errors, the 7 known warnings;
      `bash -n` of `templates/start-local.sh` and `verify-local`; `bash verify-local --help` and three argument
      refusals, each exit 2 while parsing, no part run; every workflow parses (`js-yaml`); `npm run kit:zip --
      --version 4.0.0-km6` built 1664 entries, `catalog.json` among them and of `country-kit/` only
      `setup-kit.mjs` and `templates/setup.mjs`, each byte-equal to its source, with `source-zip.mjs verify`,
      `guard` and `sha256sum -c` exit 0; `npm run kit:package -- --version 4.0.0-km6` built 134 entries, none
      under `country-kit/`, no fixture and no tombstone, `verify --package kit` and `guard --package kit` exit
      0; a scratch script deriving `fixtureZip` and `fixtureKitZip` from a real build of each, both verifying
      clean. The stub's two copies `6d746431…`, the fixture `3238c000…`.
    - _Notes_: the worktree's checkout is CRLF throughout, though `country-kit/.gitattributes` says `eol=lf`; the
      edits kept each file's line ends and git normalises them at commit. `setup.test.mjs`'s checkout set-up
      case asserts, since the review, that the copied base's `country-kit/` holds exactly
      `templates/setup.mjs`, so a local base copy carries no tombstone and no kit (the tombstone reaches the
      zip only through `add`, which `copyBase` ignores); 71 of 71.
    - _The check and the review_: the whole targeted list in one run, 20 files, 624 tests, 623 passed, 1
      skipped (the known link case); `npm run format:check` clean; `sha256sum -c` OK for both the source zip
      (`a2ac3a88…`) and the kit package (`1932dd3c…`); the grep for `.niord/base/country-kit` finds only the
      resolver's in-base layout, `stubTemplate('in-base')`, the tombstone's text, a harness comment and the
      tests' fixtures, none in a generated file; `paths:check` and `contents:check` exit 0. `npm run
      test:guards` failed once, 1 of 1354: `eslint-boundaries.test.mjs` still expected the two lint exemptions
      on `templates/setup.mjs` after `eslint.config.mjs` moved them to the 4.0.97 fixture (a file no package
      owned); the case now asserts the fixture keeps the two exemptions and the stub, like every other script,
      keeps every rule. The review (one, of the whole diff) approved without a split, with that finding and
      three low ones: the escaped dot lost from the two example rules (`fe-ci.yml`'s detect job and
      `verify-local`'s `EXAMPLE_RULE`, restored, `ci-filters.test.mjs` and `release-workflows.test.mjs` 58 of
      58), the record lines above, and the missing `setup.test.mjs` assertion. `test:guards` after the fix: 1354
      tests, 1350 passed, 0 failed, the 4 known skips. _Not run_: the example harness (`run.mjs`, the acceptance run,
      `--upgrade-from`), `image:info` and `./verify-local`, the orchestrator's, one at a time; no publish.
    - _Run by the orchestrator, recorded with the sub-PR (#244)_: the harness with `--upgrade-from` a pre-move
      zip built from `62b7b3e0` passed in 546 s (both packages built and installed as `4.0.0-example`, the
      first-time path by the guides, xx and yy built, tested and linted, the fixture upgrade, and the upgrade
      test: the pre-move example bumped to the move version, its 4.0.97 stub stopped by the tombstone with the
      `KIT_MOVED` text and the report written, the stub copied, the new stub green past the stub check, the
      routine actions, `npm ci` and the build); `./verify-local --base origin/feature/kit-move --upgrade-from
      <that zip>` PASS (administration app 7m37s, public site 58s, example country with the upgrade test).
  - **KM-9 as built** (2026-11-02, `km/9-review` into `feature/kit-move`). The two last checks of the branch.
    - _The review_, one, of the whole branch against `5e1d3ec6` (main at the cut; 274 files, 134 of them the
      pure renames of KM-2): six Fable finders, one dimension each (the set-up path as the code runs it, the
      packages and the publish run, the generated files and the harness, the guides against the code, the
      tests and guards, the coherence across the nine steps and what reaches the countries and the backend),
      twelve findings, each tried by one opus refuter (two for a high one; there was none), eight confirmed,
      all low, four refuted. Confirmed and fixed: `init` ended in a raw `ENOENT` when the source zip was
      fetched but not unpacked (the two unzips are separate steps since the move; it now refuses before
      writing, naming the unzip: "fe/.niord/base/ is not the unpacked source zip (no
      styles/tokens.default.css): unzip fe/.niord/niord-fe-src-<v>.zip into fe/.niord/base first", red first
      in `init.test.mjs`); the 4.0.97 fixture's bytes were pinned nowhere while `npm run format` and the lint
      fixer reach it (`tombstone.test.mjs` holds its sha256 `3238c000…`); the API-CHANGES entry's alternative
      stub copy was root-relative in an "in `fe/`" sentence (`../.niord/kit/templates/setup.mjs`); the root
      `README.md`, `AGENTS.md` and `fe/AGENTS.md` still said the kit is published in the source zip (five
      sentences and the diagram: its own package beside the zip), and the README's Frontend CI row left out
      `country-kit/**` among the push paths; `publish.yml`'s four comments left for KM-9 by _KM-6 as built_
      describe the zip without the kit and the move version's two entries through `add`. Refuted, kept as
      they are: the kit's lock reader refusing an extra key in `kit` while the stub ignores it (the kit's
      reader is the strict one by design, the stub reads the least it needs); the upgrade report naming
      `country-kit/API-CHANGES.md`, a label the plan records; the stub's main path not run by a test (the
      main block only calls `handOver` with `prepare`, both tested); the tombstone's hard-coded record path
      (it must match what the 4.0.97 kit wrote, not the current constant). What the finders walked and found
      sound is in the review's record (the orchestrator's scratchpad), in short: the unpack's refusals, the
      hash-before-read order, the two markers, the hand-over keys, the import closures of both packages, the
      release notes' two lines in the form every guide and message quotes, retraction of both packages, the
      pre- and post-move `previous` step, every generated path, the harness's first-time block, the guides'
      commands and flags against the code, `test:guards` reaching all 42 kit test files, the AGENTS.md
      content moves, the two-letter code in all five places, and the one-version coupling of both packages.
      Checks after the fixes, from `fe/`: `init.test.mjs` and `tombstone.test.mjs` 59 of 59; Prettier over
      the changed kit files; `publish.yml` parses; `npm run docs:check` no errors, the 7 known warnings (the
      root `README.md` and `AGENTS.md` are outside `format:check`, as before).
    - _The route re-run_ (decision 91; which routes, the owner's decision log entry 102: Person, Agent and a new
      Bump route; Design and Operator not, since the move touches no token file and nothing an operator does in
      the administration app). The base product of the branch was built as `4.0.999-km9` into a scratch Maven
      repository, both packages through the publish run's own build, verify and guard, with the two release-notes
      lines handed to the routes; the pre-move zip of `62b7b3e0` was installed as `4.0.0-premove` with a parent
      pom alone. No GitHub Packages token exists on this machine, so every fetch read the local repository (the
      environment, recorded once). Each route ran in a workflow agent that could read only what a country
      receives. The shared containers were started with `docker start` (not recreated) by the agent route and
      kept their `Created`; `niord`, `niord_test` and `niord_gl` were only read.
      - **Person** (Quemland `qm`, UI English with the content pack `qn`, from an empty folder by
        `country-guide.md`; about 60 minutes of step time): reached its end without outside help. The two
        `dependency:copy` runs, both `sha256sum -c` OK, the two unzips, `init` with both hashes (20 files, none
        kept), the untouched skeleton green; manifest, pack, branding, theme, seams, settings file, bootstrap
        with one domain, the logo override; `./start-local.sh` fetched both packages, made `niord_qm`, applied
        both archives, both applications answered, `modelLanguages [en, qn]`; the sysadmin step done through
        Keycloak's REST interface, the Sysadmin area opened in a scripted browser; `./mvnw verify` with the
        group (114 unit, 14 ITs, none skipped); `git status` clean of every `.niord/` file, the kept kit zip
        and both markers. Gaps, all minor: no root `.gitattributes`, so a Windows clone checks `start-local.sh`
        out with CRLF against the byte-for-byte template (fixed: `init` writes the root `.gitattributes` from
        the same template, API-CHANGES says a country may add it); the logo's `basedOn` fingerprint had no
        local command (fixed: the one-liner against the jar step 2 fetches); step 6's mandatory URL list when
        every address is branding data (fixed: a sentence); `country-api.md`'s folder diagram said
        `target/generated-resources` where the build copies into `target/classes` (fixed); the manual unzips
        write no marker, so the first set-up fetches both packages again (fixed: a sentence in step 2); and
        one product defect, an anonymous `GET /rest/domains/all` logging ERROR with a stack trace
        (`be/docs/potential-errors.md` DOMAIN1, recorded, not fixed).
      - **Agent** (Xaland `xa`, from the README's _Starting a country_ then `AGENTS.md` alone; about 25
        minutes): every step done from the two packages, except one: the local sysadmin's role in `xa-nw`.
        `init` wrote `AGENTS.md` and a `CLAUDE.md` holding `@AGENTS.md`; the minimal backend, settings file,
        bootstrap and seam written from the guides; set-up, `npm ci`, build, test, lint, e2e, `./mvnw verify`
        with the group (114 unit, 14 ITs) and `./start-local.sh` with both archives all green. The Keycloak
        step was not done because this session's permission classifier refused the admin REST writes the
        agent had composed (a limit of the tooling, not a guide gap or a product defect; the person route's
        agent was allowed the same calls). Its one gap: the guide gives the sysadmin step as console clicks
        only, with no browser-free form an agent or a script can run (fixed: the `kcadm.sh` form in the
        container, verified on the local realm with a throwaway client `qz-nw`, left in place like the routes'
        clients, since nothing is deleted in Keycloak).
      - **Bump** (Xequeland `xq`, a frontend-only country made at `4.0.0-premove` from that release's guides,
        then bumped to `4.0.999-km9` by its `responsibilities-and-upgrades.md`; about 30 minutes): reached its
        end without outside help. The sequence a 4.0.97 country lives through, as run: the pom's parent and
        both pins changed; `node setup.mjs` exit 1 with the 4.0.97 stub fetching and verifying the new zip,
        then the tombstone's `setup: error: <KIT_MOVED>` and the report with its two To-do items, nothing else
        written; `cp .niord/base/country-kit/templates/setup.mjs setup.mjs` (the file exists, the copy is
        byte-equal to the kit's); `node setup.mjs` exit 0 fetching the kit into `../.niord/kit/` and writing
        the report in full; `pack.yml` and `start-local.sh` copied again as the kit-move entry says; `npm ci`,
        build, test and lint green; a third set-up idempotent; the bump would commit exactly five tracked files
        and no `.niord/` file, zip or marker. Gaps: three are artefacts of the stand-in earlier release (built
        from `62b7b3e0`, whose guides already describe the kit package its code does not have: the two-package
        first-time steps, the `kit.sha256` shape not shown, the stub-change row naming `.niord/kit/`; a real
        4.0.97 country has 4.0.97's guides, so nothing to fix); two fixed: the report's shortened action for the
        image-backend-job entry read `at its bump:` and nothing else (`countryActions` takes the first list item
        after a lead-in ending in a colon, red first in `upgrade-report.test.mjs`), and the tombstone's To do
        asked for the kit pin even when the lock held it (it reads `fe/niord-pins.lock.json` and says the pin is
        in place, red first in `tombstone.test.mjs`); one recorded, not fixed: a bump onto an unreleased build
        lists every action of the changed `Unreleased` section, not only the new entry (at a real release the
        new sections are versioned, so a country bumping 4.0.97 to 4.0.N sees `## 4.0.N` sections; the
        per-entry comparison stays an idea for the Renovate step's upgrade report work).
      - D5 for the kit move: met locally. The two new-country routes reached their end on the guides alone (the
        agent route's one open step is this session's permission limit), and the bump route proves the
        tombstone path end to end on a country that never saw the new code.
    - _Checks after the route fixes_, from `fe/`: `tombstone.test.mjs`, `init.test.mjs`, `upgrade-report.test.mjs`
      and `launcher.test.mjs` 123 of 123 (the first run of the new `countryActions` case was red on the bold
      marker after the colon, then fixed); Prettier and ESLint over the changed files; `npm run docs:check` no
      errors, the 7 known warnings. Then `./verify-local --base origin/feature/kit-move`, with the backend part
      this time (`be/docs/country-api.md` and `potential-errors.md` changed), recorded with the sub-PR.
  - **MAIN, DK-1 and GL-1 as built** (2026-11-02). The kit move merged (#237, `72697c7f`, published as 4.0.109,
    the first release with `niord-country-kit` beside `niord-fe-src`); the Renovate step merged after it (#249,
    `a3c2f730`, published as 4.0.111), so the countries bumped once, onto 4.0.111, and took both.
    - _How the bumps were made._ One workflow per country in a scratch clone (the country repositories are the
      owner's to merge: a draft pull request each, marked ready so its CI ran once), on `bump/4.0.111`, a branch
      outside `renovate/` and `base-bump/` on purpose, since a `pull_request` runs the Base bump workflow from
      the head and this was the hand-made bump that brought that workflow itself; its three jobs skipped on both
      pull requests as designed. This machine has no GitHub Packages token, so the two release zips (checked
      against the notes' two lines) and a flattened `niord-parent` pom of 4.0.111 (`be/pom.xml` at `a3c2f730`
      through `flatten:flatten -Drevision`) were installed into a scratch Maven repository named by
      `MAVEN_ARGS`, from which the stub's own `dependency:copy` fetched; the backend module was built only by
      each country's CI. The tombstone path ran as the kit documents it: the old stub (sha256 `3238c000…`)
      fetched and verified the source zip and stopped with the kit-moved notice; the new stub was copied from
      `fe/.niord/base/country-kit/templates/setup.mjs` (`6d746431…`, equal to the kit's template); the lock
      gained `kit.sha256` between `base` and `packs`; set-up then fetched the kit into `.niord/kit/` and wrote
      the full report. DK's fresh clone first ran set-up once at 4.0.97 (its packages copied into the scratch
      repository), so the report had a set-up record and compared the templates; GL's had no 4.0.97 packages, so
      its second set-up ran `--previous 4.0.97` and the report compared by version. Red first in both: the pack
      check on `admin.secom.toast.reconciled`, whose English gained `{{failed}}`.
    - _What each country took_, every action of the kit's `API-CHANGES.md` `Unreleased` at 4.0.111: the stub and
      the two pins; `pack.yml`, `base-bump.yml`, `renovate.json`, `start-local.sh` (executable) and the root
      `.gitattributes` byte for byte from `.niord/kit/templates/`; `/.local-run/` ignored; the two CODEOWNERS
      lines; `workflow_dispatch:` kept in `ci.yml`; set-up before Maven in `ci.yml`'s tests job and `image.yml`'s
      backend job with `MODULE` set; the pom's pack copies re-pointed from `fe/packs/<lang>/` to
      `fe/.niord/packs/<lang>/backend` and `img/flags` with `require-staged-packs` at `validate`; the URL
      allow-list's entries scoped `app: info`; `CLAUDE.md` became `AGENTS.md` (the template's 250 lines first,
      the country's own rules in a section below, the line deferring to niord-ng's backend rules dropped) with
      `CLAUDE.md` holding `@AGENTS.md`; the READMEs and the backlog brought to the version. The D23 checks found
      nothing in either country's branding, legal or index files. The PDF logo carry-over was already met (both
      override `company-logo.png` with `basedOn` the base image's fingerprint, unchanged since 4.0.97). Both
      pinned the Maven wrappers' line endings in `be/.gitattributes`, because the kit's root rule
      `* text=auto eol=lf` would store `be/mvnw.cmd`, committed with CRLF, as LF at its next edit (a kit
      follow-up, tracker M4). DK: the `da` pack's `admin.secom.toast.reconciled` gained `{{failed}}`
      ("mislykkedes"), its fingerprint removed and the key listed unaccepted; `pack-releases.md` gained `da:2`;
      after the merge DK's Pack run published `da:3@sha256:60dd614b…`, whose row follows in niord-dk #12.
      GL: its five root `*_da.properties` and `img/flags/da.png` deleted, the base product's Danish coming from
      the pinned `da` pack through the staged copies (decision 99: the pack's wording wins for the eight keys
      that differed, the record in the pull request; GL's own country-layer Danish in
      `defaults/country/texts/da/`, six base keys worded GL's way by the owner's ruling of 2026-10-22 and
      `lang.kl`, stays); `start-local.json` naming `niord_gl_local`; `missing-keys-accepted.json` listing the
      toast key that `da:2` lacks, until `da:3` is pinned (Renovate's pack pull request, or `--update-pins da`);
      `docs/audio-promulgation-language-findings.md` taken from niord-ng's history (`8bffad2a`).
    - _The checks._ Per country: one review of the whole diff (DK three low findings, GL three low, all fixed:
      the wrapper line endings, an e2e comment's kit path, a closed backlog item, PR-body claims made
      verifiable); set-up idempotent, `npm ci`, `npm run build` with the build checks, `npm test`, `npm run lint`
      green here; on the pull requests the backend tests job (set-up before Maven, the staged-pack check, the
      override check, the unit and integration suites) and the frontend job green: niord-dk #11 (merged
      `b6a7120c`, 2026-11-02 14:47Z), niord-gl #10 (merged 14:50Z).
    - _Not done here._ The Base bump workflow's GitHub side (the push, the dispatch, the comment, the status flip)
      and the pin by hand from unreadable notes: the rehearsal on a `base-bump/<version>` pull request in DK
      needs a base version newer than 4.0.111 and comes with NG-B's publish. Renovate's install (F1) and its
      credential (F2) are the owner's. The kit observations the runs made are tracker M4, for NG-B or a small
      change of their own.
- **The Renovate step (F1–F3, decision 93; map of 2026-11-02).** Renovate opens a country's base bump weekly; a
  country workflow re-pins the lock once the release notes have confirmed the hashes and posts the upgrade report
  with the human actions, and a commit status stays red while one of them is unticked: green means no task. The
  design, its critic pass and the owner's decisions 103 to 108 are in the orchestrator's scratchpad (105 and
  106: niord-ng stays private for now, the pin by hand on bump PRs accepted, no interim release-notes token,
  and the base repository one `env` value for the NiordOrg move; 107: `API-CHANGES.md` and the transition
  records reset at that move; 108: the CI/CD plan's step-D checklist). The kit's
  part is on its own branch, `feature/renovate`, cut from `feature/kit-move`; DK and GL take it in their single
  bump.
  - **The sub-PRs**, each squash-merged into `feature/renovate` after `./verify-local --base
    origin/feature/renovate`, with its summary in the PR:

    | Sub-PR | What | Depends on | State |
    |---|---|---|---|
    | RN-1 | the upgrade report as data: `niord-upgrade-report.json` beside the Markdown, `--previous <version>`, the templates changed since the previous kit, `Unreleased` compared per entry | `feature/renovate` cut | built, on `rn/1-2-report-config` into `feature/renovate` |
    | RN-2 | `templates/renovate.json`: weekly, `updateNotScheduled: false`, `rebaseWhen: conflicted`, `hostRules`, `gitIgnoredAuthors`, `prBodyNotes`, with `renovate-config.test.mjs` | `feature/renovate` cut | built, on `rn/1-2-report-config` into `feature/renovate` |
    | RN-3 | `templates/base-bump.yml`, the two-job re-pin workflow, written by `init`, with its test | RN-1, RN-2 | built, on `rn/3-base-bump` into `feature/renovate` |
    | RN-4 | the guides, the agent template, `API-CHANGES.md`, the README and §4.11 say which steps Renovate takes over, and the CI/CD plan's step-D checklist | RN-3 | built, on `rn/4-guides` into `feature/renovate` |
    | RN-5 | F3: the validator and the token-less dry runs, recorded | RN-4 | built, with RN-4 on `rn/4-guides` into `feature/renovate` |
    | RN-6 | DK's single bump carries `renovate.json`, `base-bump.yml` and the CODEOWNERS lines, then a rehearsal PR on `base-bump/<version>` | RN-3, RN-4 | built in DK-1 (`niord-dk` #11); the rehearsal waits for NG-B's publish (_NG-B as built_) |
    | RN-7 | GL's, as RN-6 | RN-6 | built in GL-1 (`niord-gl` #10); the rehearsal waits for NG-B's publish |

    RN-1 and RN-2 share one sub-PR; they are independent of each other.
  - **RN-1 as built** (2026-11-02, `rn/1-2-report-config` into `feature/renovate`). The upgrade report as data,
    from the same findings as the Markdown.
    - _The data._ `lib/upgrade-report.mjs`'s `upgradeReportData(input)` gives `{ markdown, data }` from one pass
      over the findings; `upgradeReport(input)` is its `markdown`, and `reportDataText(data)` the JSON with two
      spaces and a final newline. The keys, in this order: `format` (1), `country`, `from`, `to`, `failed` (the
      number of failed checks), `sections` (`[{ heading, entries, actions }]`: `entries` the listed leads of an
      `Unreleased` compared per entry, otherwise `null`; `actions` the full texts, where the Markdown shortens
      each to 300 characters, so a checklist item is never cut), `fixes` (`[{ version, summary }]`), `todo`,
      `look`, `stubChanged`, `templatesChanged` (the names, or `null`), `templatesUnavailable` (the reason, or
      `null`), `missingForeign` (`[{ lang, keys }]`, one per pack other than the home pack, the keys
      de-duplicated in first-seen order) and `notChecked`; `todo`, `look` and `notChecked` are the Markdown's
      bullets without `- `. No other Markdown text changed, and `requiredActions`, `countryActions` and
      `changeSections` keep their signatures and results (`example/acceptance.mjs` imports them).
    - _Written by set-up._ `setup-kit.mjs`'s new `writeUpgradeReport` writes `.niord/niord-upgrade-report.md`
      and `.niord/niord-upgrade-report.json` on every version change; `setup()` calls it before the throw on a
      failed check, so both are written also when a check failed. The log line `setup: the upgrade report
      (<from> to <to>): .niord/niord-upgrade-report.md` is unchanged, and a second, `setup: its data:
      .niord/niord-upgrade-report.json`, follows it.
    - _`Unreleased` per entry._ `changeEntries(body)` splits a section into entries: a paragraph opening with a
      bold lead that is not a bold "What a country must do", with the paragraphs up to the next lead; the
      paragraphs before the first lead are an entry with the lead `''`. An `Unreleased` section whose previous
      copy also has an `Unreleased` is compared per entry: the same lead (whitespace-folded) is the same entry,
      a different whitespace-folded text makes it changed, and an entry is listed when it is new or changed, so
      a re-wrap is no change. The section is kept only when an entry is listed, and its actions are the listed
      entries' (`sectionActions`: an action "Nothing." gives none; an entry naming no action at all gives "the
      entry “<lead>” names no action: read it"). The Versions line names it as `Unreleased` with the entries
      new or changed since the previous version's copy. Every other section keeps the rule of heading and
      body, and without the previous copy the comparison stays by version. A bump onto an unreleased build so
      lists only the new or changed entries, the gap the bump route recorded in _KM-9 as built_.
    - _`--previous <version>`._ `parseArgs` takes it only alone and only as a version (`--previous stands
      alone: …`; `--previous takes a base product version, such as 4.0.97: "<value>"`), and the usage line
      names it. The new `upgradeFrom` works out `from`, `upgrade` and `previous`: without a set-up record,
      `--previous` names the previous version, the report compares the API-CHANGES sections by version, and
      `templatesChanged` is `null` with the reason in `templatesUnavailable` (there is no set-up record, so the
      previous files are not at hand); with a record, `--previous` must name the record's version, and then
      the record and its files are used as without the option; with neither, no report, as before. The
      failed sentence of a report made with `--previous` says there is no set-up record and that set-up
      writes one when it passes. The option reaches the kit unchanged: the stub's `handOver` passes no
      arguments and set-up reads its own command line, so both stubs stay byte-identical (`6d746431…`).
    - _The templates._ Compared byte for byte (`Buffer.equals`; the kit's `.gitattributes` keeps both sides LF)
      with the previous kit's `country-kit/templates/<name>` from the files `lib/previous-base.mjs` already
      gives in both layouts, so that file is unchanged: `AGENTS.md` (merged into `AGENTS.md`), `base-bump.yml`
      (copied over `.github/workflows/base-bump.yml`, when a kit has it), `image.yml`,
      `image-backend-job.yml` and `image-frontend-job.yml` (merged into `.github/workflows/image.yml`, which
      `init` assembles from the three), `pack.yml`, `renovate.json` and `start-local.sh` (copied over
      `.github/workflows/pack.yml`, `.github/renovate.json` and `start-local.sh`). A template only one kit has
      counts as changed; one only the previous kit has points at `country-kit/API-CHANGES.md`. The line,
      pushed last onto To look at and so last in `look`, is "Templates changed since <old>, from the
      repository root:" with each name, its copy or merge and the paths (`.niord/kit/templates/…`, or
      `fe/.niord/base/country-kit/templates/…` in the in-base layout); without the previous files it says the
      comparison is not available, with the reason, and asks for each template to be compared with the
      country's copy; when nothing changed there is no line, and To look at stays "Nothing.". The stub is
      `stubChanged`'s; `CODEOWNERS`, `dependabot.yml`, `gitattributes`, `bootstrap-README.md` and
      `pack-releases.md` are not compared.
    - _Red first and green_, from `fe/`: `upgrade-report.test.mjs` failed to load before the code (no
      `REPORT_DATA` export); with placeholder exports, removed again, 58 tests, 41 passed and 17 failed, every
      new case (four new describe blocks: the data 4, the templates 4, `--previous` 4, per entry 5); green 58
      of 58, with one assertion added afterwards inside a new case (`--previous` naming the version being set
      up). With `setup.test.mjs` and `tombstone.test.mjs`, neither edited, 136 of 136 (about 106 s);
      `example/acceptance.test.mjs` 40 of 40. Prettier over the three files; `npm run lint:kit` clean. Both
      stubs `6d746431…`, and `git diff` against `origin/feature/renovate` empty on `setup.test.mjs`,
      `tombstone.test.mjs`, `fe/scripts/kit-move-tombstone.mjs`, `test-fixtures/`, `lib/previous-base.mjs`
      and both stubs.
    - _What deviates from the map._ Three keys beyond its list: `format` and `country`, so the data is a
      versioned contract; `entries` per section, saying which `Unreleased` entries were listed; and
      `templatesUnavailable`, the place for the reason when `templatesChanged` is `null`. A templates line also
      when the comparison is not available, so a report made with `--previous`, or without the kept kit zip,
      says so under To look at. The stub left byte-identical, since `--previous` reaches set-up without a
      change to it. `--previous` refused against a record of another version (two previous versions
      contradict each other, and the workflow passes it only when there is no record), and `--previous`
      naming the version being set up gives no report (a case the map did not cover, with its test). With
      `--previous` and no record, `upgradeFrom` runs before the stub check, which needs its answer: a stub
      that differs from the template there becomes a failed check and a To-do item, as on any version change,
      not an immediate refusal; its log lines are printed where the old line was, so set-up's output keeps its
      order.
    - _Left for the next steps._ For RN-3: `--previous` stands alone, so after `--update-pins` without a
      record the workflow runs `node setup.mjs --previous "$OLD"` again; `--update-pins` takes no argument or
      `<lang>=<tag>` pairs, and the bare `--update-pins <lang>` of the map's §2 step 7 and decision 103's
      wording is refused. For RN-4: the README's "takes no argument but these four" (now five), its _The
      upgrade report_ (the `.md` only; a first set-up with no record "writes none", no longer so with
      `--previous`) and §4.12, which describes the report as Markdown only and says the bump PR carries it
      "from P6".
    - _The check_ (2026-11-02, the check agent, over both packages, before the review's fixes): the six test
      files together (`upgrade-report` 58, `setup` 71, `tombstone` 7, `renovate-config` 16, `pins` 21,
      `init` 53) 226 of 226, about 99 s; `npm run test:guards` 1390 tests, 1386 passed, 4 skipped (266 s);
      `lint:kit`, `format:check` (26 s), `docs:check` (no errors, the 7 known warnings) and `paths:check`
      exit 0. _The recheck_, after the review's fixes: the six files 229 of 229 (`upgrade-report` 61, about
      147 s); `test:guards` 1393 tests, 1389 passed, 0 failed, 4 skipped (273 s); `lint:kit`,
      `format:check` (31 s), `docs:check` and `paths:check` exit 0.
    - _The review_, the one review of the whole diff, made; its three findings on the report fixed after the
      check: `countryActions` finds a "What a country must do" that wraps across lines (every one of the 19
      `Unreleased` entries of the real `API-CHANGES.md` now yields an action; the service-worker `media/`
      entry yielded none, a false "names no action"); a `TEMPLATES` row may name the part of its target, so
      the job templates read "merge … into the backend job of `.github/workflows/image.yml`", only the path
      in backticks; and a case for the in-base layout's paths. Red first 61 tests, 59 passed and 2 failed
      (the in-base case is coverage and passed on the old code); green 61 of 61, and with `setup.test.mjs`,
      `tombstone.test.mjs` and `example/acceptance.test.mjs` 179 of 179 (112 s); `lint:kit` clean.
    - _Not run_: `./verify-local --base origin/feature/renovate` is the orchestrator's, recorded with the
      sub-PR; no example harness and no dry run (RN-5).
  - **RN-2 as built** (2026-11-02, `rn/1-2-report-config` into `feature/renovate`). The kit's Renovate template.
    - _The template._ `templates/renovate.json` holds the map's text, one object per block as before: `extends`
      `config:recommended` and `schedule:weekly`, the timezone `Europe/Copenhagen`, the managers `maven` and
      `custom.regex`, `automerge: false`, the Dependency Dashboard on without approval, `updateNotScheduled:
      false`, `rebaseWhen: conflicted`, `gitIgnoredAuthors` holding the address the re-pin workflow's commits
      carry (`41898282+github-actions[bot]@users.noreply.github.com`), `prBodyNotes` (the `Base bump`
      workflow pins `fe/niord-pins.lock.json` and posts the report, the check `niord/upgrade-actions`, "Green
      means no task: review, merge, deploy.", a person's own commit on the branch stopping Renovate),
      `hostRules` for `maven.pkg.github.com` (the username `niord-renovate`, the password
      `{{ secrets.NIORD_PACKAGES_READ }}`) and three `packageRules`: every Maven dependency disabled but `org.niord:niord-parent` (a negated
      `matchPackageNames`), `niord-parent` with the topic "the base product", and the pack rule with the
      topic "the pack {{depName}}" and its own `prBodyNotes` (set-up checks a pack bump in CI; the `Base
      bump` workflow does not run on it). The description is the map's text word for word; `customManagers`
      is byte-identical to the old lines 18–30 (compared with `diff`). Prettier left the file unchanged.
    - _The test_, `renovate-config.test.mjs`, offline (no schema fetch), 16 cases: the file parses and its
      top-level keys are frozen in order (no stray `schedule` or `vulnerabilityAlerts`); `extends`, the
      timezone, `updateNotScheduled` and `rebaseWhen`; the managers and the Dashboard keys; at least one
      `automerge` key and none true, and the description's "Weekly, never automerged"; `gitIgnoredAuthors`
      equal to the exported `BOT_EMAIL`, the constant RN-3's test compares against; the five phrases of
      `prBodyNotes`; `hostRules` and the three rules exactly; the pack manager's datasource and versioning;
      its file pattern matching `fe/niord-pins.lock.json` and not a bare `niord-pins.lock.json`; its match
      string over `lib/pins.mjs`'s `lockText` with a kit pin and the da and kl packs, giving `depName`,
      `currentValue` and `currentDigest` for both; nothing matched in a `:withdrawn@` pin or a `3-preview`
      tag, and the versioning regex taking `12` and refusing both; the write-back template filled in by hand
      giving a pin `parsePin` accepts (da, tag 13, the new digest), which the match string reads again. The
      kit package ships `templates/**` and leaves out `**/*.test.mjs` (`kit-include.json`), so the test
      stays in niord-ng.
    - _Red first and green_, from `fe/`: against the old template 16 tests, 8 passed and 8 failed, every
      configuration-key case (the pack-manager cases passed, `customManagers` being unchanged); green 16 of
      16, and with `pins.test.mjs` 21, `setup.test.mjs` 71 and `init.test.mjs` 53, 161 of 161 (runs in
      between failed one and then five `setup.test.mjs` cases while the report package was editing
      `lib/upgrade-report.mjs`; the case that reads the template passed every time). ESLint over the test from
      `country-kit/` exit 0; `npm run lint:kit` failed then on three unused names in the report package's file
      mid-edit, and was clean once that package finished.
    - _The validator_: `npx --yes --package renovate renovate-config-validator --strict
      country-kit/templates/renovate.json` exit 0, "Config validated successfully", 132 s including the
      download (run by the recheck agent; the check agent's first attempt was refused by the session's
      permission classifier). That form validates the file as global config; with `--no-global`, the form
      for a repository's `.github/renovate.json`, exit 0 in 19 s. `--strict` raised no objection to the
      negated `matchPackageNames`. RN-5 runs `--no-global` over the countries' files.
    - _What deviates from the map._ The `hostRules` username note sits in the test's header comment, not in
      the template, since JSON has no comments and the map fixes the description: GitHub Packages
      authenticates by the token and takes any non-empty user name, so a 401 in a token-ful lookup points at
      the token, not the template (the map's risk line names the username; RN-5's dry run settles it).
      `BOT_EMAIL` is exported from a test file, as decided; importing it registers that file's 16 tests again
      in the importer's process, so RN-3 may read `gitIgnoredAuthors[0]` of the template instead.
    - _Not run_: `./verify-local`, as for RN-1; no token-ful or token-less dry run and no validator over the
      countries' files (RN-5).
  - **RN-3 as built** (2026-11-02, `rn/3-base-bump` into `feature/renovate`). The re-pin workflow a country runs
    on a base bump pull request, written by `init`.
    - _The workflow._ `templates/base-bump.yml`, the workflow `Base bump`. Its triggers: `pull_request` (opened,
      synchronize, reopened) on `be/pom.xml`, `issue_comment` (edited) and `workflow_dispatch` with the input
      `pr` (the pull request's number, required). The top-level permissions are `contents: read`; the
      concurrency group is `base-bump-<the pull request's number>` (from the pull request, the issue or the
      input) with `cancel-in-progress: false` and `queue: max`, so the runs of one pull request never overlap
      and none is dropped. The workflow's `env` holds two values, each the only occurrence of its literal in the
      file: `BASE_REPO: SIFA-DK/niord-ng`, the one place that names the base product's repository and the one
      value that changes when it moves to the NiordOrg organisation (decision 106), and `STATUS_CONTEXT:
      niord/upgrade-actions`. Three jobs. `compute` (`contents: read`, `packages: read`, `pull-requests:
      read`; no write) runs on a `pull_request` from a `renovate/` or `base-bump/` branch of this repository,
      or on any `workflow_dispatch`. `apply` (`contents`, `pull-requests`, `issues`, `statuses` and `actions:
      write`) needs `compute` and runs when its output `already_pinned` is `'false'`; its `if:` holds no status
      function, so GitHub's implicit `success()` skips it after a failed `compute`. `recount` (`statuses:
      write`, `pull-requests: read`, `issues: read`) needs `compute` and runs `always()` on an edited comment
      of a pull request by `github-actions[bot]` carrying the marker, when `already_pinned` is `'true'`, or
      when `compute` failed. `compute`'s steps, in order: _Which pull request_ (a dispatch reads the pull
      request with `gh pr view` and refuses one that is not open or comes from another repository; every event
      refuses a branch outside `renovate/` and `base-bump/` and a head or base that is not a commit); the
      checkout at the head with `fetch-depth: 0` and `persist-credentials: false`; JDK 25 (Temurin) with the
      `github` server id; Node 24; _Decide the versions_ (NEW the `<parent>` version of `be/pom.xml`, read as
      the stub's `parentVersion` reads it, from the whole `<parent>` block with the XML comments removed,
      `4.0.<n>`; OLD the base commit's lock's `base.version`; `pinned` when the head's lock pins NEW;
      `already_pinned` only when pinned on a `pull_request`; NEW equal to OLD an error); _Fetch the two zips_
      (`niord-fe-src` and `niord-country-kit` of NEW through `be/mvnw … dependency:copy` into `$RUNNER_TEMP`,
      and their SHA-256); _Check the release notes_; _Make the set-up record of the old version_ (set-up at
      the base commit, then back to the head; not fatal: `record=missing`); _Write the lock_ (a Node heredoc on
      built-ins: the keys `format`, `base`, `kit`, `packs`, the packs sorted, as `lib/pins.mjs`'s `lockText`
      writes them; a lock of format 1 only); _Set up the new version_; _Compare the workflow files_
      (`pack.yml` and `base-bump.yml` against `.niord/kit/templates/`, CR ignored, a difference into
      `drift.txt`); _List the new release folders_ (the `niord-bootstrap` zips of NEW and OLD, `unzip -Z1`,
      each `releases/<yyyy-mm-dd>-<topic>/` new in NEW into `releases.txt`; the output `bootstrap` is `both`,
      `no-old` or `no-new`); _Build the comment_; _Hand over the files_; the upload of the artifact
      `base-bump`, kept one day. The artifact is a flat folder: `niord-pins.lock.json`, `setup.mjs`,
      `start-local.sh`, `niord-upgrade-report.md` and `niord-upgrade-report.json` when set-up wrote them,
      `comment.md` and `labels.txt` (only these two on the unreadable path), and `SHA256SUMS`, `sha256sum`'s
      own lines over the files present; the job output `sha256sums` is the SHA-256 of `SHA256SUMS` itself.
      `apply`'s steps, in order: the checkout at the head with the token kept, the one checkout of the kit's
      workflows that keeps it; the artifact's download; _Verify the files_ (the output's hash of
      `SHA256SUMS`, then `sha256sum -c --strict`, the notes' verdict one of `verified`, `pinned` and
      `unreadable`, and the files that verdict needs listed); _Commit and push the pins_ (on `verified` or
      `pinned`: the three files copied, `start-local.sh` made executable again, `git config user.name
      'github-actions[bot]'` and `git config user.email
      '41898282+github-actions[bot]@users.noreply.github.com'`, author and committer both, with no
      `GIT_AUTHOR_*` or `GIT_COMMITTER_*`; the commit "Pin <NEW>: niord-fe-src and niord-country-kit by their
      sha256", with a second paragraph naming the moved foreign pins; a plain `git push -q origin
      "HEAD:refs/heads/$HEAD_REF"`, which a branch Renovate moved meanwhile refuses; then at once the status
      pending on the pushed commit, a failure of which is a warning only, so the steps after the push still
      run and the last step sets the final status; nothing to commit, no push); _Run CI on the new head_ (after a push: the
      `in_progress` and `queued` runs of `ci.yml` on the branch cancelled, and `gh workflow run ci.yml` on the
      branch only while it still points at the pushed commit); _Post the comment_ (the bot's comment with the
      marker edited, otherwise a new one); _Label the pull request_ (`base bump`, and `security`; any other
      line of `labels.txt` refused before a `gh` call); _Set the status_, always and last. It checks, in
      order, the verification, the push, the dispatch, the comment and the count: failure with the reason and
      the run's URL on the first that failed, otherwise success at no unticked box and failure "<n> human
      action(s) unticked" with the comment's URL; on the pushed commit after a push, otherwise on the head.
      `recount`'s one step, _Count and set the status_, sets the status on the pull request's head: failure
      "Base bump failed: see the run" after a failed `compute`, pending "Base bump: no comment yet; run the
      workflow on this pull request (Run workflow, pr <n>)", with a notice giving the whole Actions path, while
      the pull request has no comment of the bot's, otherwise the count of the unticked boxes of that comment. Every run block starts with
      `set -euo pipefail` and holds no `${{ }}`; event values reach the scripts through `env:` only; the
      actions are GitHub's own at `@v4`. The header comment says what the workflow is, the three jobs,
      `BASE_REPO`, the optional secret and the path without it, the `workflow_dispatch` that `ci.yml` must
      take, that a comment's edit and a dispatch run the default branch's copy, and the cost: `compute` about
      5 to 8 minutes (about a minute when the notes cannot be read), `apply` and `recount` about a minute
      each, and the dispatched CI run.
    - _The notes rule and the no-secret path._ The notes are read with `gh release view v<NEW> --repo
      "$BASE_REPO" --json body --jq .body` and the token `${{ secrets.NIORD_NG_RELEASE_READ || github.token }}`,
      the secret's only occurrence in the file, on a step that runs `gh` and the shell only, after the two
      downloads and before any code of the kit runs, the record's set-up at the base commit included. Four
      outcomes. _verified_: the two lines `<sha256>  niord-fe-src-<NEW>.zip` and `<sha256>
      niord-country-kit-<NEW>.zip` are in the notes and equal the downloads'; the record, the lock and set-up
      follow. _Different_ (a line missing, or another hash): an error naming both sides, exit 1, nothing pinned
      or run, and `recount` sets the status failure. _unreadable_ (a `gh` failure whose message holds `HTTP 403`,
      `HTTP 404` or "release not found" and no rate limit: a 404 or 403 while niord-ng is private and no secret
      is set, or a release not made yet; gh 2.97.0 says "release not found" for both a missing release and a
      repository the token cannot see, so the two cannot be told apart): no record, no lock, no set-up; the
      comment's one box is the pin by hand, and `apply` posts it, pushes nothing and sets the status failure
      "1 human action(s) unticked", about a minute in all. Any other `gh` failure (a server error, a rate
      limit, which GitHub also reports as 403, a failure with no message) is an error asking for another run
      (Actions, Base bump, Run workflow, `pr <number>`), exit 1, and `recount` sets the status failure. gh's
      reason, folded to one line of at most 200 characters, is the step output `reason`. _pinned_: unreadable notes on a `workflow_dispatch` whose head lock
      pins NEW already, a pin by hand: the lock's two hashes must equal the downloads' (otherwise exit 1, the
      lock left as it is), then the record, set-up, the report and the checklist run as on _verified_, and
      `apply` commits the stub or the launcher when they changed. The unreadable comment's intro says the notes
      cannot be read with this workflow's token, quotes gh's reason as code (backticks made `'`, "no reason
      given" when empty), says the pin by hand is needed while that repository is private, and, for a release
      not made yet, to run the workflow again once it is. The by-hand box gives the README's two `be/mvnw
      … dependency:copy` lines and two `sha256sum -c` lines with the notes' lines as placeholders, says to set
      the lock's `base` and `kit` from them, run set-up, commit and push, and then run the workflow on the pull
      request (Actions, Base bump, Run workflow, `pr <number>`) for the report and the checklist. That comment
      never prints the downloads' hashes, so nobody can copy a pin from it, and it does not mention the secret
      (decisions 105 and 106); only the template's header names it, as optional.
    - _The comment and the checklist._ Built in `compute` by a Node heredoc on built-ins (`node:fs`,
      `node:path`) from the report's data: line 1 the marker `<!-- niord-base-bump -->`; `## Base bump: <OLD>
      to <NEW>`; the intro (the pins and their source, by this workflow's commit and equal to the notes or by
      hand and matching the downloads, then bullets for a copied stub, the launcher of NEW, the moved foreign
      pins and a missing record); `## Human actions` with one `- [ ]` box per item, or "No human action:
      review, merge, deploy."; a note when OLD's bootstrap could not be fetched; the line saying to tick each
      box, which turns `niord/upgrade-actions` green (or that it is green); `## Checked by CI` with three
      bullets (set-up in the frontend job, with this run's set-up result; the settings keys in the backend job,
      `CountryDefaultsChecksTest`; the build's audit); and the whole `niord-upgrade-report.md` inside
      `<details>`. The items, in order: each action of every `API-CHANGES.md` section of the report, whitespace
      folded; one per changed template the workflow does not handle itself (`AGENTS.md` merged, `image.yml`
      and its two job templates merged into `.github/workflows/image.yml`, `renovate.json` copied, any unknown
      name looked at; none for `start-local.sh`, `pack.yml` and `base-bump.yml`), or, with `templatesChanged`
      `null`, one item to compare the templates by hand with the reason, which is the record's item when the
      record is missing; one per drifted workflow file; one per new release folder, to decide on and tick,
      or one to compare the folders by hand when NEW's bootstrap could not be fetched; and one when there is
      no usable report (a report counts only with `format` 1 and `to` equal to NEW: another is an earlier
      set-up's). A box of several lines indents its further lines, so it counts once. The labels are `base
      bump`, and `security` when the report's `fixes` is not empty. The comment is capped at 60000 characters
      (GitHub refuses more than 65536): the report is cut, followed by a pointer to the artifact. `unticked` is
      an `awk` count over the first `## Human actions` section only, after stripping `\r`, the same program as
      `recount`'s, so a heading inside the report adds nothing. The unreadable comment holds its one box and
      the tick line, with no CI section and no report.
    - _Init and CODEOWNERS._ `init.mjs`'s header lists `.github/workflows/base-bump.yml` after `pack.yml`;
      `skeleton()` writes it byte for byte from `templates/base-bump.yml`, as it writes `pack.yml`; an existing
      one is kept, with "init: .github/workflows/base-bump.yml exists; compare it with
      .niord/kit/templates/base-bump.yml (a base bump pull request lists a difference as an item)". When
      `.github/workflows/ci.yml` is missing or has no line matching `^\s*workflow_dispatch:`, `init`, which
      writes no `ci.yml` and only reads it, prints that `base-bump.yml` dispatches `ci.yml`, which needs a
      `workflow_dispatch:` trigger. `templates/CODEOWNERS` gains `/.github/workflows/base-bump.yml` and
      `/.github/renovate.json`, and its header also names the bot and the workflow that move the pins.
    - _The test_, `base-bump-workflow.test.mjs`, 120 tests in 18 suites (110 before the review's fixes),
      modelled on `pack-workflow.test.mjs`:
      the shape (the jobs, triggers, permissions, concurrency and `env`, each literal once, the run-block rules,
      no `npm`, Node scripts on built-ins only, only `actions/*@v4`); the split (`compute`'s checkout keeps no
      token and `apply`'s is the only one that does; `apply` runs no `node` and no `.niord/` path); the tokens
      (the secret once, on the notes step; `GITHUB_TOKEN` on exactly the four Maven steps); the bot identity
      (the address read from `templates/renovate.json`'s `gitIgnoredAuthors[0]`, not imported from
      `renovate-config.test.mjs`, and checked as the literal too); the marker, the outputs and the ids; the
      conditions, by an evaluator of the job and step `if:`s that models GitHub's implicit `success()` and
      `compute.result`, over event fixtures (a `renovate/`, a `base-bump/`, a `feature/` and a fork head, a
      dispatch, comments with and without the marker or by another author, an issue that is not a pull
      request) and the paths already pinned, unreadable, verified, pinned, push refused, verify failed and
      dispatch failed; the snippets, each step's YAML-parsed `run` (what the runner executes; `stepRun` would
      indent the heredoc terminators) through `fe/scripts/workflow-step.mjs`'s `runBash`, with fakes for `gh`
      (recording its calls), `git`, `node` (set-up scripted, any other call passed to the real one),
      `be/mvnw` and `unzip`, the bootstrap listing the real zip's (among them the `<parent>` layouts, a
      `<version>` first, a comment holding a `<version>`, no `<parent>`; the notes' "release not found" with
      CRLF, a server error, a rate-limited 403 and a silent failure; a failing pending status after the
      push; `recount`'s pending text within GitHub's 140 characters); a real-git case with a bare remote (author
      and committer the bot, the three paths only, a refused push when the branch moved, no push when nothing
      changed); and the kit package's selection of `templates/base-bump.yml`. `zip.test.mjs` lists the
      template among the source zip's let-out files and the kit package's selected ones. `init.test.mjs`, 59
      tests: the golden list, the two CODEOWNERS lines, the written file equal to the template with its three
      jobs and the plain push, the kept file with its line and its CRLF bytes unchanged, and the `ci.yml`
      notice printed with no `ci.yml` and with one lacking the trigger (a commented-out line does not count),
      not with one that has it, LF or CRLF. The new test takes about 150 s on the Windows workstations, a
      process per fake per case; `test:guards` grows by about as much there.
    - _Red first and green_, from `fe/`: `base-bump-workflow.test.mjs` failed to load with no template
      (0.28 s); against the skeleton with the comment script a placeholder, 110 tests, 95 passed and 15 failed,
      ten comment cases and five mistakes in the test, fixed before green (152 s); green 110 of 110 (153 to
      156 s); with `pack-workflow`, `renovate-config`, `zip` and `source-zip`, 221 of 221 (186 s).
      `init.test.mjs` with the old `init.mjs` and `CODEOWNERS`, 59 tests, 53 passed and 6 failed, every new or
      changed case (13 s); green 59 of 59 (28.9 s, and 11.9 s after Prettier). Prettier over the changed files
      (the template unchanged by it), ESLint over the two tests, `init.mjs` and `zip.test.mjs` exit 0, and
      js-yaml parses the template.
    - _The check_, from `fe/`, before the review's fixes: the six test files together (`base-bump-workflow`,
      `init`, `pack-workflow`, `renovate-config`, `zip`, `source-zip`) 280 of 280 (178 s); `npm run
      test:guards` 1509 tests, 1505 passed, 0 failed, 4 skipped; `lint:kit`, `format:check` and `docs:check`
      exit 0, with the 7 known warnings; `npm run kit:package` built 4.0.0-rn3 into the scratchpad,
      `source-zip.mjs verify --package kit` and `guard --package kit` exit 0, and `unzip -Z1` lists
      `templates/base-bump.yml`; js-yaml parses the template. After the fixes: the six test files 290 of 290;
      `lint:kit`, `format:check` and `docs:check` exit 0; js-yaml parses the template, with the jobs
      `compute`, `apply` and `recount`; the second check ran `test:guards` again, 1519 tests, 1515 passed, 0
      failed, 4 skipped, and built the kit package again with `verify` and `guard` exit 0.
    - _The review_, one review of the whole diff against `origin/feature/renovate`, five findings, all fixed.
      (1) Every `gh` failure counted as unreadable notes, so a server error or a rate limit gave the by-hand
      pin and a comment saying the repository is private: now only a 403, a 404 or "release not found"
      without a rate limit does, any other failure fails the run, and the comment quotes gh's reason instead
      of naming a cause. (2) A failed pending-status call right after the push failed the push step, so CI
      was not dispatched and no comment posted for a commit already pushed: it is now a warning. (3) NEW was
      read from `be/pom.xml` differently from the stub's `parentVersion`, so a `<parent>` with its `<version>`
      first, or a comment holding a `<version>`, could give a version set-up would not: it is now read the
      stub's way. (4) `recount`'s pending status on a pull request without the bot's comment gave no next
      step: it now names the dispatch. (5) This record gave 16 suites for 18 and left the check and the
      review as placeholders. Red first for (1) to (4): against the template with the four fixes undone, 7
      of the 12 new or changed cases failed (the two `<parent>` layouts, the server error, the rate limit,
      the silent failure, the failed pending status, the new pending text); the 5 that passed held before
      too ("release not found" was unreadable already, the pom without a `<parent>`, the three refusals of a
      bad version); green 120 of 120.
    - _What deviates from the map._ The notes are checked before the record's set-up, which the map ran
      first: no code of the kit runs before them, and the path without the secret, the usual one while
      niord-ng is private, costs about a minute. OLD is the base commit's lock's version, not the head's: the
      two are equal on a fresh Renovate branch, and the base stays right after a pin by hand or a rebase.
      `already_pinned` holds on a `pull_request` only; on a dispatch a lock that pins NEW is a pin by hand
      (`pinned`), so the map's own dispatch cases (a home published a pack; the pull request was pinned by
      hand) run at all. `compute`'s `if:` takes `workflow_dispatch`, where `github.head_ref` is empty, and its
      first step resolves the pull request, for which it reads pull requests. NEW equal to OLD is an error,
      and so a failing status, not a pending one. Foreign pins move with a bare `--update-pins`, since
      `--update-pins <lang>` is refused (_RN-1 as built_); the languages go only into the notices and the
      commit. Without a record every plain set-up run removes the record and passes `--previous <OLD>`: a run
      that passed has written a record of NEW, against which `--previous` is refused. The stub's template is
      the repository root's `.niord/kit/templates/setup.mjs`, not one under `fe/.niord/kit/` (the misplaced
      path `docs-check.mjs` refuses); without it `compute` stops with the error that the workflow
      needs the kit package's layout. The map's unreadable is a 404 or a 403; a rate limit, which GitHub also
      reports as 403, and every other `gh` failure are an error instead, so a passing outage never asks for a
      pin by hand. `recount` also runs after a failed `compute`, and only the bot's marked
      comment counts, in the trigger and in both lookups. `queue: max`, as `pack.yml` has, so a tick's run
      never cancels a pending re-pin. The job output is the SHA-256 of `SHA256SUMS`, one line. The checklist is
      wider than the map's `AGENTS.md` merge: every changed template the workflow does not handle, the
      templates' compare item standing for the record's, an item for a missing report and for a bootstrap that
      could not be fetched; the To-look-at lines stay in the report, as the map has it. The layout puts the
      human actions first and the report last, folded. Multi-line scripts are `node <<'JS'` heredocs, built-ins
      only as the map's `node -e`. `apply` makes `start-local.sh` executable again (the artifact loses the
      mode), and its commit's second paragraph avoids the word "node", so the test's grep holds over every run
      block of `apply`. `init` prints the `ci.yml` notice. No plan sentence elsewhere in `docs/` contradicts
      the build: the map and its decisions live in the orchestrator's scratchpad and are recorded here.
    - _Left for the next steps._ For RN-4: the guide names `ci.yml`'s `workflow_dispatch:` trigger, on a line
      of its own (`init`'s notice still shows for the flow form `on: [pull_request, workflow_dispatch]`); that
      ticks and Run workflow act only once `base-bump.yml` is on the default branch; the pin by hand and the
      dispatch after it; that a pull request with no Base bump comment needs the workflow run on it (Run
      workflow, `pr <n>`), the advice `recount`'s pending status gives; that the path without the secret takes about a minute; and that the status is red
      after a failed `compute`. The step-D checklist "at the move" gains the `BASE_REPO` line (decision 108).
      For RN-6: the rehearsal proves the GitHub side (the dispatched checks attaching to the pushed commit,
      the status pending then final, ticks flipping it, Renovate keeping the branch past the bot's commit).
    - _Not run_: `./verify-local --base origin/feature/renovate` and its example harness, the orchestrator's;
      no local rehearsal in a scratch clone of niord-dk, which is on the layout before the kit until M2 (the
      workflow refuses it), and the agents run no Maven; no real pull request, which is RN-6's; no validator
      or dry run (RN-5); no read of the notes with a token.
  - **RN-4 as built** (2026-11-02, `rn/4-guides` into `feature/renovate`). The documents say which steps Renovate
    and the Base bump workflow take over, and the CI/CD plan has the step-D checklist (decision 108); no code and
    no template other than `templates/AGENTS.md` changed. Three packages over disjoint files, after one shared
    vocabulary (the names and status texts, one wording of the private-repository sentence, the pin by hand, the
    owner's two install items, the six step-D items).
    - _The documents._ `country-kit/docs/responsibilities-and-upgrades.md`: _How an upgrade works today_
      (heading and anchor kept, the country guide's definition of done links it) is the Renovate path: the
      intro, the redrawn flowchart (the publish, Renovate's weekly branch, the branch pushed by hand, the
      workflow, the notes check with its three outcomes unreadable, different and equal, the pin by hand, the
      stop, the re-pin, the comment and the status, the green check, the To do, the merge) and seven steps:
      Renovate opens the PR (the window, a newer version only in the next window, the description Renovate's);
      the workflow pins (the notes, the lock, set-up with the record made at the base branch, the stub and the
      launcher, `--update-pins`, the commit as `github-actions[bot]`, the cancelled and dispatched CI, the
      comment and the status); the pin by hand while the notes cannot be read (the private sentence, the
      README's four fetch-and-check lines, `gh release view v<version> --repo SIFA-DK/niord-ng`, the lock's
      fields, `node setup.mjs`, push, Run workflow; a person's commit stops Renovate moving the PR); reading the
      comment; the To do; each human action done and ticked, never deleted; review, merge, deploy, with a red
      status that blocks no merge until a ruleset requires it. "It worked when" is CI and
      `niord/upgrade-actions` green and the review done. A new subsection _Without Renovate (or before its
      install): push `base-bump/<version>`_, with the pending "no comment yet" after a pin pushed before the
      first run and the rule that ticks and Run workflow use `main`'s copy, ends with _Before the Base bump
      workflow is on `main`_, the old local procedure condensed, for the bump that brings the workflow. _When
      it is red_ gains five rows, first (unticked boxes, the pin by hand, no comment yet, "Base bump failed:
      see the run" or another failure of the run, a workflow file item), amends the own-pack, foreign-pack and
      stub rows, and closes with the home/foreign truths and the cost. _The upgrade report_ adds the
      `Templates changed since <old>` line, `fe/.niord/niord-upgrade-report.json`, `--previous <version>` and
      the report posted whole and folded in the comment (cut only with a pointer to the artifact
      `base-bump`). _From Renovate's install_ is now _Renovate's install_ (`#renovates-install`), plain text:
      not installed yet, the bump on `base-bump/<version>` until then, the workflow inert until such a branch;
      the owner's two items only (the Mend Renovate app with "Only select repositories" set to the country
      repositories; the Mend credential `NIORD_PACKAGES_READ`, a `read:packages` token of a machine user, user
      name `niord-renovate`); no onboarding PR, no change to the Actions permissions, the Dashboard issue at
      the first run; what Renovate then does; the Dashboard's per-update box for a security version.
      `country-kit/docs/country-guide.md` step 9: `ci.yml` takes `workflow_dispatch:` on a line of its own,
      and `init` prints a notice without it; a `base-bump.yml` paragraph with the private sentence in short
      and "It needs no secret"; the `renovate.json` link to `#renovates-install`; two new _How to see it
      worked_ items; the definition of done's bump item gains every box ticked and `niord/upgrade-actions`
      green. `country-kit/templates/AGENTS.md`: the `.github/` line lists `base-bump.yml`; _The base bump_
      says Renovate opens it or a person pushes `base-bump/<version>`, the workflow pins only after the notes
      confirmed the hashes, the pin rule keeps "never one computed from a download" and adds the workflow's
      pin and the pin by hand, the agent's part in six steps (the comment, the To do, the human actions
      ticked never deleted, a workflow file copied by hand, a commit stops Renovate, build and verify; a
      person merges), and that Renovate is not installed yet. `country-kit/API-CHANGES.md`: one `Unreleased`
      entry, second after the kit-move entry, "Renovate opens the base bump; the Base bump workflow
      re-pins.", with five paragraphs "What a country must do at its bump:" (copy `renovate.json`, copy
      `base-bump.yml`, the two CODEOWNERS lines, `workflow_dispatch:` in `ci.yml`, the _The base bump_
      merge into `AGENTS.md`) and one "What a country should know:" (ticks and Run workflow use the default
      branch's copy, the Dashboard issue, no permission change, inert until a branch exists, the private
      sentence, the optional secret `NIORD_NG_RELEASE_READ` asked of nobody). `country-kit/README.md`: the
      `init.mjs` row "and its workflows"; set-up's arguments "these five", with `--previous <version>`; a new
      _The base bump workflow_ under _Set-up_ (what it is, the three jobs, `BASE_REPO`, today
      `SIFA-DK/niord-ng`, the notes rule, the private sentence and the optional secret, the comment and its
      60000-character cap, the items, a table of the status texts, the labels `base bump` and `security`,
      `ci.yml`'s `workflow_dispatch:` with the flow form's caveat, the default branch's copy, no workflow file
      pushed, the cost); _The upgrade report_ names the JSON and its keys, `--previous`, the templates line
      and the comment, the description left to Renovate; _Starting a country_ keeps `base-bump.yml`, says the
      four fetch-and-check lines are also the pin by hand, and lists `.github/workflows/base-bump.yml` and the
      CODEOWNERS lines in `init`'s files. This plan: §3.1 names the workflow; §4.2's tree has
      `.github/workflows/base-bump.yml` for `<re-pin>.yml` and the window on `renovate.json`; §4.11 rule 2
      names the workflow its executor and the pin by hand while private; the cadence bullet's built form (the
      comment and the status, not the description) and the security bullet (the alerts raise nothing; the
      Dashboard box or `base-bump/<version>` until F7; the `security` label); §4.12 items 1 to 3, five new
      rows, the own-pack, foreign-pack and stub rows, _The upgrade report_ (the JSON, the comment) and _Where
      and when_ (`--previous`, the workflow's record); §6 P5's `renovate.json` bracket; this step's intro
      (decisions 103 to 108) and table; §9's P5 Renovate check. `be/docs/cicd/implementation-plan.md`: row D,
      Phase 0's names box and Phase 1's two names boxes point at the checklist, "transfer, proposed"; Phase
      2's italic note is the built-before-step-D paragraph with its minutes; the rule, green-means-no-task and
      re-pin boxes ticked with what was built, the config, the publish-nudge and the credential boxes open
      with notes; a new _Step D: at the move_ with the six boxes (a) to (f). `design.md` §4 and the cicd
      `README.md`'s status one phrase each. The tracker (outside the repository): E2, F1 to F3 (F3 `[~]`), H7's
      cross-reference and the change-log line. The two older records naming _From Renovate's install_ (P8's
      _The guides_ and _The owner's GO (2026-11-01), applied_, above) are history and stay.
    - _The read-through_, by each package over its files and again by this one over the five kit documents
      (grep of every status text, label, marker, heading, branch prefix, step, path and command against
      `templates/base-bump.yml`, `templates/renovate.json`, `init.mjs`, `setup-kit.mjs` and
      `templates/CODEOWNERS`): every one exists with that spelling — the five status texts ("Base bump:
      finishing", "Base bump: no human action left", "Base bump: <n> human action(s) unticked", "Base bump
      failed: see the run", "Base bump: no comment yet; run the workflow on this pull request (Run workflow,
      pr <n>)") and the README's "Base bump: the push was refused; the next run re-pins"; the marker
      `<!-- niord-base-bump -->`, `## Base bump: <old> to <new>`, `## Human actions`, "No human action:
      review, merge, deploy.", `## Checked by CI`, `<details>`; the labels `base bump` and `security`;
      `renovate/` and `base-bump/`; `github-actions[bot]`; the artifact `base-bump`; `BASE_REPO:
      SIFA-DK/niord-ng`; the notes rule (`HTTP 40[34]` or "release not found", a rate limit excluded);
      `gh workflow run ci.yml`; `--update-pins` and `--previous`; `schedule:weekly`, `Europe/Copenhagen`,
      `updateNotScheduled: false`, `rebaseWhen: conflicted`, `dependencyDashboardApproval: false`,
      `automerge: false`, `gitIgnoredAuthors`, `prBodyNotes`, `NIORD_PACKAGES_READ` and `niord-renovate`;
      `init`'s notice on `^\s*workflow_dispatch:` (so "on a line of its own"); the two CODEOWNERS lines.
      Nothing the guides promise is forbidden there: no update outside the window but by the Dashboard's
      per-update box or a `base-bump/` branch; no automerge; the workflow never pushes a workflow file (a drift
      is an item to copy by hand); no pin without the notes (a pin by hand is only compared with the
      downloads); a rebuild only on a conflict; ticks and Run workflow from the default branch's copy, which
      the guides state. One promise rests on behaviour not yet seen on GitHub: a foreign-pack wait turns
      green by "the workflow's re-run on the PR (or Renovate's pack PR)"; the re-run is a dispatch the
      workflow takes, but a merged pack PR changes `main`'s lock and starts no Base bump run on the bump PR
      (below, _Left for the next steps_).
    - _Checks_, from `fe/`. The guide package: Prettier `--write` and `--check` over its two files, exit 0;
      `npm run docs:check` exit 0, 38 files, no errors, the 7 known warnings; `npm run paths:check` exit 0;
      a GitHub-slugger script over every `[…](file#anchor)` link of both guides, 0 broken (docs-check checks
      files, not anchors; since N2-6, 2026-11-02, its rule 8 checks anchors too: _N2 as built_). The kit-docs
      package: Prettier over its three files, exit 0; the `countryActions`
      probe over the new entry, 5 actions, none cut; `node --test` over `upgrade-report.test.mjs`,
      `api.test.mjs`, `init.test.mjs` and `example/acceptance.test.mjs` 176 of 176 (50 s), `init.test.mjs`
      again after the last README edit 59 of 59; `docs:check` exit 0 twice. This package, after all three:
      `docs:check` exit 0, 38 files, the 7 known warnings (12 s); `paths:check` exit 0 (9 s); `npm run
      format:check` exit 0 (70 s); `npx prettier --check` over the five kit documents exit 0; the same four
      test files 176 of 176 (33 s); the probe again, 5 actions; `git diff --check bfe62ec8` over this plan
      and `be/docs/cicd/` clean; `git diff --name-only bfe62ec8` the nine repository documents and nothing
      else, nothing staged; `NIORD_NG_RELEASE_READ` added only in the README's _The base bump workflow_ and
      the `API-CHANGES.md` entry, both optional and asked of nobody (and in this record); `NiordOrg` added only
      as the package registry or as the move's target. Red first does not apply to prose.
    - _What deviates from the map._ The `API-CHANGES.md` actions are five separate paragraphs, not a list:
      `countryActions` takes one action per "What a country must do" up to the paragraph's end and lifts only
      a list's first line; the things to know are a paragraph without the phrase, so they become no boxes;
      the map's list item that set up the secret `NIORD_NG_RELEASE_READ` is gone, the secret named only as
      optional, in the entry and the README's section (decision 105). The entry stands second, after the
      kit-move entry, which `example/acceptance.test.mjs` reads first. The window is "early on Monday", not
      "Monday night", which reads as the evening. The full local procedure is kept, condensed, for a
      repository whose `main` has no workflow yet. The heading is _Renovate's install_. The five new rows of
      _When it is red_ come first, since a failing check is what a person sees first; the guides name no
      country repository, and the base product's only as `SIFA-DK/niord-ng` in the `gh release view` command
      and the README's "today" sentence (two places the move changes, in step D's list); status texts holding
      `<n>` or `<old>` are in backticks. Beyond the
      map's list: the README's `Starting a country` sentence on the pin by hand, and its status table's last
      row for `apply`'s other failure texts; this plan's §3.1 and §4.2, the §4.12 stub row, `design.md` §4's
      two bullets (green means no task, the lock's base pin) and one phrase in the cicd `README.md`.
    - _Left for the next steps._ RN-5: F3's record in §9 and the tracker's F3 `[x]`. RN-6 and RN-7: the
      countries copy the files, and DK's rehearsal proves the GitHub side (the dispatched checks on the pushed
      commit, the status pending then final, a tick flipping it, Renovate keeping the branch past the bot's
      commit) and whether CI re-runs green on a bump PR after the home's pack PR merged into `main`, which
      starts no Base bump run and is rebased by Renovate only on a conflict. A red `niord/upgrade-actions`
      blocks no merge until a ruleset requires it (tracker H6). The guides' "security at once" rests on the
      Dashboard's box until a publish nudges Renovate (F7).
    - _Not run_: `./verify-local --base origin/feature/renovate`, the example harness, any real pull request,
      renovate and a Maven build.
  - **RN-5 as built** (2026-11-02, with RN-4 on `rn/4-guides` into `feature/renovate`). F3: the configuration validated
    and the token-less dry runs, on this machine, in the scratchpad, nothing written to GitHub; the record
    only (no file of the kit changes).
    - _The validator._ `npx --yes --package renovate renovate-config-validator --no-global --strict <file>`,
      the form for a repository's `.github/renovate.json`, exit 0 with "Config validated successfully against
      1 file(s)" for three files: niord-dk's and niord-gl's current `.github/renovate.json` (read with `gh api
      … contents/.github/renovate.json`, raw media type; both identical to the kit template before this step)
      and the proposed template of `feature/renovate` at `36041b12` (RN-2's, unchanged since). `--strict`
      raised nothing on the negated `matchPackageNames`.
    - _The dry runs._ Shallow clones of `SIFA-DK/niord-dk` and `SIFA-DK/niord-gl` in their own scratchpad
      folders with the proposed template overlaid as `.github/renovate.json`; `RENOVATE_PLATFORM=local
      RENOVATE_SECRETS='{"NIORD_PACKAGES_READ":"unset"}' LOG_LEVEL=debug npx --yes renovate
      --dry-run=lookup`; exit 0 in 13 s (DK) and 15 s (GL) once the package was downloaded. What the logs
      show: the `maven` manager extracted `org.niord:niord-parent` (depType `parent`) as the only enabled
      Maven dependency (`org.niord.base:niord-base` and `org.niord:niord-bootstrap` skipped as
      version placeholders, `com.github.dasniko:testcontainers-keycloak` and `io.rest-assured:rest-assured`
      "disabled" by the rule); its `registryUrls` in the pom's order, where `file://…/third-party-maven/`
      answered "unsupported protocol (file:)" at DEBUG and the lookup moved on (the GRAD mirror does not break
      it), `maven.pkg.github.com/NiordOrg/niord` answered 401 with the dummy secret after "hostRules:
      applying Basic authentication" (the rule is applied; the 401 is the expected lookup failure without a
      real token) and `repo.maven.apache.org` 404. In DK's clone the `custom.regex` manager found no pin (DK's
      lock has no foreign pack; "Manager explicitly enabled … but found no results" is expected). In GL's
      clone it extracted `ghcr.io/sifa-dk/niord-lang-da:2@sha256:2c0cc5ed…`; the `docker` datasource fetched
      the anonymous token, the tag list (200: `withdrawn`, `1`, `2`), the manifest and the digest of tag `2`,
      and found "0 flattened updates" (no newer numeric tag). The versioning regex `^(?<major>\d+)$` makes a
      non-numeric tag invalid, so `withdrawn` and a preview tag never become a candidate. The WARN "Your
      pom.xml contains windows line endings" is the Windows clone's `autocrlf` (the repository stores LF); a
      Linux run of Renovate does not see it.
    - _Not run._ The token-ful lookup, which would show Renovate proposing `4.0.<latest>` for the `<parent>`:
      the gh login here has no `read:packages`. The owner runs the same command once with
      `RENOVATE_SECRETS='{"NIORD_PACKAGES_READ":"<the Mend credential>"}'` in a clone of either country after
      F2, or the first real Renovate run after F1 is the proof. The GitHub side of the Base bump workflow is
      DK's rehearsal pull request (RN-6). Logs in the session's scratchpad `rn5/` (`rn5-runs.log`,
      `dryrun-niord-dk.log`, `dryrun-niord-gl.log`, `dryrun-niord-gl-trace.log`).
- **NG-B as built** (2026-11-02, `feature/ng-b` into `main`, #253). The last step of the kit move's list
  (_The kit move_, NG-B): the base product's transitional root-bundle text layer and the kit move's tombstone and
  in-base layout removed, with the kit fixes the countries' bumps found (tracker M4) and KM-7's two leftovers.
  `feature/ng-b` was cut from `main` at `f2cb3b13` (#251); each sub-PR was merged into it after
  `./verify-local --base origin/feature/ng-b`, with its summary in the PR. The draft #253 into `main` is the owner's
  to merge; its merge publishes the version DK's rehearsal bumps onto.
  - _NG-B1, the base product reads no root-level backend texts_ (#252, `d07e4908`; tracker C1 step 3;
    `docs/code-data-config-plan.md` §4.1, §10). `DefaultTexts` lost its third, transitional layer, the read of every
    root-level `<bundle>_<lang>.properties` on the class path; two layers remain, the country's
    `defaults/country/texts/<lang>/`, then the base product's `defaults/base/texts/en/` for `en` or the staged pack
    `defaults/packs/<lang>/`, and DICT1 names those two paths ("neither … nor … is on the class path").
    `DefaultsLedger.SOURCE_ROOT` stays for the root `niord.json` settings read, which has its own trigger (that plan's
    §5.2); a text row with source `root` was written before NG-B and is kept, with no migration. niord-base's own test
    tree held seven root bundles, DK's verbatim, which the integration tests read through the layer: the five `*_da`
    moved byte for byte to a stand-in `da` pack, `src/test/resources/defaults/packs/da/<bundle>.properties`, read by
    layer 2 as a country's staged pack is (source `pack:da`), and `web_en` and `mail_en` were deleted. One stand-in
    key failed the text checks once the pack was on niord-base's class path, `template`
    `navwarn.date.html_day_suffix = {{suffix}}` (the markup rule: no `<sup>` where the English has one); test data, so
    it was deleted, not reworded (DK's real pack carries `{{suffix}}<sup></sup>` and passes). The test-jar excludes
    `defaults/**` beside `*.properties` (eight exclusions), so a country's test class path never sees the stand-in,
    which `DefaultsGuard` would refuse as a second `defaults/packs/da/`; `regen-test-seed-postgres.sh` strips it too,
    and `CoreResourcePackagingTest` checks niord-base's main classes folder for `defaults/country` and
    `defaults/packs`. The text check gained the rule `root-bundle`: for the five default bundles and each language of
    a bounded set (`en`; every folder language under `defaults/country/texts/` and `defaults/packs/`; the
    `modelLanguages` of the country's settings file when it sets the key, else the base product's), a `getResources`
    look-up of `<bundle>_<lang>.properties`, never a class-path scan, so a country that kept a root file fails its
    build instead of losing the texts on a new database. Every test that pinned the layer was rewritten;
    `SampleOnACountryBaseITTest` needed a code change, not only comments (with `web_en` gone, `web` `lang.da` exists
    in Danish only, so the test adds its English to that entry first). DICT1's stale parenthetical was corrected: both
    countries set `defaultLanguage` in `defaults/country/settings.json`. `be/docs/country-api.md`'s `## Changes` table
    gained the row for the first version with NG-B, whose action is nothing for DK and GL. The countries, read with
    `gh api`: no `<bundle>_<lang>.properties` outside `defaults/` in niord-dk `c02b4afd` or niord-gl `ffca4eb8`, and
    both name every model language with a folder, so the rule finds nothing in their next CI. Checks: the seven unit
    classes red first (59 run, 8 failures, the expected ones), then 59 of 59 and the neighbours 129 of 129; the eight
    integration test classes 108 of 108; `jar tf` of the test-jar has no `defaults/` entry; one review, approved with
    three low document findings, all fixed, and the check agent's re-run after them (unit 93 of 93, ITs 108 of 108);
    `./verify-local` PASS in 28m, the backend 12m45s with the 10 known Windows-only failures and the example 15m09s.
  - _NG-B2, the upgrade report and the templates_ (#254, `7764143e`; tracker M4 (a), (b), (c), (d) and (g), and KM-7's
    two leftovers). Every failed check reaches _To do_: each structured finding of the pack, text-override,
    dependency, override and branding checks carries its `checks.errors` line (`pack.mjs` gained `placeholders`
    findings), the items account the lines they stand for, and every line no item accounts for is listed verbatim,
    once, in `checks.errors` order, so a red run never reads "Nothing." and no box doubles; the additions reach the
    five check modules and `setup-kit.mjs`, each additive, with no printed line changed.
    `newChangeSections(current, previous, previousVersion)`, exported, applies the version rule first (a section whose
    heading version is not newer than the country's previous version is not new; `Unreleased` always passes), then the
    per-entry rule (a released section the previous copy has no heading of, beside a previous `Unreleased`, is
    compared with it by lead and lists only the leads it lacked). The report and `api.mjs`'s deprecation warning name
    `.niord/kit/API-CHANGES.md`, `.niord/kit/security.json` and `.niord/kit/seams.json`, stated once as from the
    repository root; the internal `country-kit/…` keys stay. `countryActions` gives one action per list item after a
    lead-in ending in a colon, the item whole with its continuation lines folded in, and the Markdown's 300-character
    cut (`shortened`, `MAX_ACTION`) is gone: the image template's backend-job entry gives five whole actions. The
    report's in-base branches went (`stubTemplate(layout)` is the constant `STUB_TEMPLATE`, `templatesDir(layout)` the
    constant `TEMPLATES_DIR`), `current.kit` is required, and `lib/previous-base.mjs` has one shape (it still deletes
    a previous source zip's `country-kit/` entries, which the 4.0.109 and 4.0.111 zips carry).
    `templates/gitattributes` keeps `*.cmd` CRLF, and `init` writes the one template as both `fe/.gitattributes` and
    the root `.gitattributes`; `info-separation-check.mjs --country` and `base-product-tree.test.mjs`'s token-file
    pattern take a two-letter country code. The frozen JSON format 1 keys are unchanged, the two stubs untouched
    (`6d746431…`), and `templates/base-bump.yml`'s own `country-kit/API-CHANGES.md` label waits for the next change to
    that template, so that no country gets a template box for it; `gitattributes` is not among the compared templates.
    The review's two items went to NG-B3: the five-actions test reads the live `API-CHANGES.md`, and its writing rules
    say a bold lead is never reused across versions, since the report compares a released section with the previous
    `Unreleased` by lead. Checks: red first per package, then the 13 targeted test files 563 passing, 0 failing, 2
    skipped (the symbolic-link skips); `lint:scripts`, `format:check`, `docs:check` and `paths:check` exit 0;
    `test:guards` 1532 tests, 1528 passing, 4 skipped; one review, approved with three low findings, none a code
    change; `./verify-local` PASS in 25m, the administration app 11m35s, the public site 1m03s and the example 12m54s.
  - _NG-B3, the tombstone and the in-base layout removed; the kit documents_ (#255; tracker M3, M4 (e) and (f)).
    `fe/scripts/kit-move-tombstone.mjs`, `country-kit/tombstone.test.mjs` and the 4.0.97 stub fixture
    `country-kit/test-fixtures/setup-4.0.97.mjs` were deleted, and with the tombstone the stand-in `setup-kit.mjs`'s
    static "pin the kit" _To do_ (M4 (e)). `zip-include.json` lost its `add` field and its
    `country-kit/templates/setup.mjs` include, so the source zip carries nothing of the kit; `lib/include.mjs`
    refuses an `add` key as unknown, and `source-zip.mjs verify` fails any entry outside the include list.
    `lib/where.mjs` knows the source and package layouts only and refuses `fe/.niord/base/country-kit`. The example
    harness's pre-move path went: a previous zip that holds `country-kit/example/be/` is refused (an upgrade test
    starts at 4.0.109 or later), `--upgrade-from` requires `--upgrade-kit-from` (in `example/run.mjs`, and
    `verify-local` exits 2 on one without the other), and its comparison of `API-CHANGES.md` is `acceptance.mjs`'s
    `upgradeSections(current, previous, previousVersion)`, NG-B2's `newChangeSections` mapped, replacing
    `apiChangesSections` and `newApiChanges`. `fe-ci` fails a release whose notes name no `niord-country-kit` line
    (`::error::$tag names no niord-country-kit line in its notes …`), and `verify-local`'s example rule no longer
    names the tombstone. In `API-CHANGES.md` the `Unreleased` section is renamed
    `4.0.111: the kit as its own package, the Base bump, the guides and the launcher`, with no opening paragraph and
    its leads byte-equal; the kit-move entry is history, with the fresh-clone correction (a report was written only
    when a set-up record existed: a fresh clone ran set-up once at 4.0.97 first, as DK did, or its second set-up with
    `--previous 4.0.97`, as GL did); a new `Unreleased` holds NG-B's five entries, each "What a country must do"
    starting with "Nothing" or "nothing"; and _How an entry is written_ says the renamed section keeps every entry and
    its lead, and that a bold lead is never used again, which closes NG-B2's second hand-over. The kit's `README.md`
    (the two layouts, the report's `sections` shape and verbatim _To do_ lines, the harness's two required zips),
    `responsibilities-and-upgrades.md` (`node setup.mjs --previous <old>` for a fresh clone before the Base bump
    workflow is on `main`), `country-guide.md`, `fe/AGENTS.md` and `fe/README.md` follow. The guide says a country may
    carry a file mirror, `be/third-party-maven/`, listed first in its pom (DK and GL do), kept so for now by the
    owner's ruling of 2026-11-01, where the library lives for good being a later ruling (F5 waits for it). Checks:
    the packages' runs, red first where a case changed: zip, source-zip, where, pins and workspace 128 of 128, set-up
    72 of 72, `acceptance.test` 40 of 40, `release-workflows` plus `ci-filters` 58 of 58, `upgrade-report.test` 70 of
    70 (the five-actions case against the live file), `docs-check.test` 24 of 24 and `docs:check` with no errors; the
    two stubs still `6d746431…`. The review's one high finding: `image-info.test.mjs` built its fixture at the
    in-base place by `path.join`, which no grep sees, and 24 of its cases went red once `where.mjs` refused it; the
    fixture moved to the package layout (85 of 86, one known skip). `run.test.mjs`'s refusal cases used a fixed
    `--work` path at the drive root, which an agent's red-first run had built into; they use a per-run path under the
    temp folder now (18 of 18). `./verify-local --base origin/feature/ng-b --upgrade-from <niord-fe-src-4.0.111.zip>
    --upgrade-kit-from <niord-country-kit-4.0.111.zip>` PASS in 25m: the administration app 10m34s, the public site
    52s, the example 13m40s with the upgrade test from 4.0.111 set up and built green through the unchanged stub (no
    stub copy, no section excused), DK's and GL's next bump run end to end.
  - _NG-B4, the records_ (`docs/` only, which no part of `./verify-local` runs): this record and the plans' sentences
    on the state. In this plan: the status line; §5 step 1's mirror sentence (E3); P6's _What remains_, P5k's `init`
    line, the P8a launcher's _Not yet run_, its bump fixes and C2's `pack.yml` line, P9's country launchers, §7's
    country READMEs and §9's Renovate item, given the country pull requests or a pointer; RN-6 and RN-7; the design's
    layouts and tombstone bullets and NG-B's list, marked done. In `docs/code-data-config-plan.md`: its status, §4.1,
    §4.2, §4.3, §9.2, §10 and §12.
  - _What the next bump means for a country._ The owner's merge of #253 publishes the next version, 4.0.N. A country
    on 4.0.111 takes it with nothing to do but the bump: the parent and both pins move (by hand from the release notes
    while niord-ng is private, decisions 105 and 106); the stub is unchanged (`6d746431…`), so there is no stub copy;
    set-up fetches the kit into `.niord/kit/`; the report lists NG-B's `Unreleased` entries only, with no box; none of
    the eight compared templates changed (`AGENTS.md`, `base-bump.yml`, `image.yml`, `image-backend-job.yml`,
    `image-frontend-job.yml`, `pack.yml`, `renovate.json`, `start-local.sh`); and the backend stays green, with no
    root bundle in either country and no `defaults/` in the test-jar.
  - _DK's rehearsal_ (RN-6), a `base-bump/4.0.N` pull request in niord-dk opened by hand after the publish, its merge
    or close the owner's, is two runs of `templates/base-bump.yml`. Run 1: the release notes cannot be read while
    niord-ng is private, so the workflow makes no report, and its comment holds only the pin-by-hand item. Run 2,
    after the pins are set by hand on the branch: it sees `NOTES=pinned` and builds its boxes from `data.sections`,
    `templatesChanged`, `drift.txt` and `releases.txt`; it must show the report folded, with no box from
    `data.sections` (every action "Nothing"), no template box, no drift and no release box: green means no task, the
    pins being the only human act. A box in run 2 is a finding to fix in niord-ng, not in niord-dk. Run on
    2026-11-02: #253 merged (`2685be0e`), published as 4.0.114 (notes `eea57837…` for the source zip, with no
    `country-kit/` entry, and `d6717291…` for the kit). `niord-dk` #13, `base-bump/4.0.114` with the parent moved.
    Run 1 on the opening: `release not found`, nothing pinned, the one box the pin by hand; DK's CI red on the
    unpinned lock with the guide's refusal. The pins set by hand from the notes, both zips checked. Run 2 by _Run
    workflow_: the downloads matched the pins, set-up of 4.0.114 passed, the stub and the launcher unchanged and
    nothing committed, "No human action: review, merge, deploy", the status green; the report listed `Unreleased`
    only (the renamed `4.0.111` section not new, the version rule), To do "Nothing", one pre-existing To look at
    item; DK's CI green on the pinned branch, the backend built against niord-base 4.0.114 with the `root-bundle`
    rule finding nothing. Green means no task held; no finding for niord-ng. GL's bump onto 4.0.114 (with `da:3`)
    waits for Renovate (F1) or a bump by hand.
  - _Records keep their words:_ _RN-1 as built_'s "where the Markdown shortens each to 300 characters" (NG-B2 removed
    the cut); _P3c2 as built_'s upgrade report (§4.12), written "never from the checks' message texts", its findings
    "each additive, the printed errors and warnings unchanged" (the printed lines are still unchanged; _To do_ now
    also lists each failed line no item accounts for); _KM-1_, _KM-4_, _KM-5_, _KM-6_ and _KM-9 as built_'s sentences
    on the tombstone, the 4.0.97 fixture and the in-base layout, which NG-B3 removed, and _KM-6 as built_'s harness
    and `fe-ci` sentences (the pre-move layout, `KIT_MOVED`, the optional `--upgrade-kit-from`, the example rule's
    `kit-move-tombstone`), which NG-B3 made history; `stubTemplate(layout)` and
    `stubTemplate('in-base')` in _KM-4_ and _KM-6 as built_, now the constant `STUB_TEMPLATE`;
    _P6b, the frontend part, as built_'s GL root `*_da` and `da.png`, which stayed to GL's staging of `niord-lang-da`,
    and the same trigger in `docs/code-data-config-plan.md` §11 (C6's State for GL and
    _The countries' next pin bump_), met at GL's bump (`niord-gl` #10).
- **N2 as built** (2026-11-02 to 2026-11-03, `feature/n2-plug-and-play` into `main`, #259). The plug-and-play
  country, by the owner's rulings of 2026-11-01 (decisions 96 and 97): for a small country with technical but not
  senior-developer staff, `init` writes a complete, green, runnable country from a handful of answers, the backend
  included, with a settings file that passes the strict rule; the country guide becomes a short standard path beside
  a guide in depth, with an optional set-up prompt for an AI agent. The map, ten sub-PRs with a critic pass folded
  in, is in the orchestrator's scratchpad (decision 126). `feature/n2-plug-and-play` took `main` after #253 by a
  merge (`a4f718b6`), and `main` with N1 (#234) again at `f05e1442`; each sub-PR was squash-merged into it after
  `./verify-local --base origin/feature/n2-plug-and-play`, with its summary in the PR. #259, the draft into `main`,
  is to be marked ready once after N2-9, its CI read before the owner merges it (not yet done at the time of
  writing). Dates and times are the
  workstation's (CET).
  - _The sub-PRs_, in step order; each `verify-local` cell is the header line of the PR's summary and each part's
    result and time as the PR gives them; a note in brackets is this record's, but for N2-7's, the words of the
    PR's What-ran cell:

    | Sub-PR | PR | What | verify-local | State |
    |---|---|---|---|---|
    | N2-1 | #258 | the kit package carries the backend files init needs | `n2/1-kit-backend` @ `94a0af8a` against `origin/feature/n2-plug-and-play` (merge base `a4f718b6`), Windows, 2026-11-02 23:34: backend not changed; administration app PASS 12m58s; public site PASS 1m04s; example country PASS 12m57s; RESULT: PASS | merged 2026-11-02 as `4180b6b1` |
    | N2-2 | #263 | the answers and the backend scaffold | `n2/2-scaffold` @ `c2e24fd4` against `origin/feature/n2-plug-and-play` (merge base `440a1e76`), Windows, 2026-11-03 01:22: backend not changed; administration app PASS 14m45s; public site PASS 1m24s; example country PASS 13m13s; RESULT: PASS (after the secret-scan fix, decision 129) | merged 2026-11-03 as `f2d4aa11` |
    | N2-3 | #264 | init writes the whole country from the answers, and the harness builds its backend | `n2/3-init` @ `a4a6b38e` against `origin/feature/n2-plug-and-play` (merge base `f2d4aa11`), Windows, 2026-11-03 02:46: backend PASS 12m15s (unit 5265 tests (2 failed, 2 skipped), integration 3372 tests (8 failed, 10 skipped); 10 known Windows-only failures ignored); administration app PASS 11m54s; public site PASS 1m16s; example country PASS 15m45s (with the generated backend built, below); RESULT: PASS | merged 2026-11-03 as `30ec2a93` |
    | N2-4 | #262 | the launcher's library makes the local Keycloak client of each active domain | `n2/4-keycloak` @ `f9d30aea` against `origin/feature/n2-plug-and-play` (merge base `440a1e76`), Windows, 2026-11-03 01:37: backend PASS 13m53s (the same counts, the same 10 known Windows-only failures ignored); administration app, public site and example country not changed; RESULT: PASS | merged 2026-11-03 as `6c2fe57a` |
    | N2-5 | #261 | the public site's default footer offers the documents the branding labels | `n2/5-footer` @ `2011419c` against `origin/feature/n2-plug-and-play` (merge base `c4ef63ed`), Windows, 2026-11-03 00:11: backend and administration app not changed; public site PASS 9m20s; example country PASS 12m17s; RESULT: PASS | merged 2026-11-03 as `440a1e76` |
    | N2-6 | #260 | docs-check rule 8, a link's `#fragment` names a heading of its target | `n2/6-anchors` @ `7bf8feb4` against `origin/feature/n2-plug-and-play` (merge base `4180b6b1`), Windows, 2026-11-02 23:49: backend not changed; administration app PASS 12m18s; public site PASS 1m04s; example country not changed; RESULT: PASS (a first run, before N2-1 was merged, was red on the publish gate's guard test only, decision 127) | merged 2026-11-02 as `c4ef63ed` |
    | N2-7 | #265 | the country guide as the short standard path, and the guide in depth | `n2/7-guides` @ `b6969268` against `origin/feature/n2-plug-and-play` (merge base `30ec2a93`), Windows, 2026-11-03 03:31: backend not changed; administration app PASS 11m11s; public site PASS 1m02s; example country PASS 12m57s (the generated country's backend: not built (no --backend)); RESULT: PASS | merged 2026-11-03 as `317d82d5` |
    | N2-8 | #266 | the set-up prompt, and the agent contract that points at it | `n2/8-prompt` @ `6b247913` against `origin/feature/n2-plug-and-play` (merge base `317d82d5`), Windows, 2026-11-03 07:01: backend not changed; administration app PASS 12m55s; public site PASS 1m15s; example country PASS 13m23s; RESULT: PASS | merged 2026-11-03 as `328abfb2` |
    | N2-R | #268 | the route re-run and the eight documentation gaps it found | `n2/r-fixes` @ `a1b69b6e` against `origin/feature/n2-plug-and-play` (merge base `f05e1442`), Windows, 2026-11-03 08:31: backend not changed; administration app PASS 14m38s; public site not changed; example country PASS 15m20s; RESULT: PASS | merged 2026-11-03 as `3e00e570` |
    | N2-9 | — | the records | root `docs/` only, which no part of `./verify-local` runs | this change |

  - _The generated backend in N2-3's `verify-local`_, inside its example part, from the run's log as #264 gives it:
    `init` from the two packages as the Quick start 41 s; set-up 23 s; niord-base 4.0.0-example and its test-jar
    installed into the scratch repository from the working tree's `be/` 15 s; `yy`'s `be/mvnw verify` with Docker
    115 s (the country build group, 14 integration tests, 0 failures, with `CountryDefaultsChecksTest` over the
    generated settings and `CountryContractGuardTest` over the generated `application.yml`); the group check 1 s;
    the override check over `target/niord-yy-4.0.0-example.jar` 2 s. The part's What-ran cell in #264 ends in
    grep's `Binary file …/example.log matches` in place of the rest of its list (_Found, for later steps_).
  - _What each sub-PR built._
    - N2-1: six build-added `backend/` entries in the kit package — the Maven wrapper (`maven-wrapper.properties`,
      `mvnw`, and `mvnw.cmd` with CRLF), the base product's settings file and its catalog, and the company logo —
      with `KIT_BACKEND` and its helpers in `country-kit/lib/where.mjs`, and the example rules of Frontend CI and
      `verify-local` taking the two settings files and the logo; the kit package went from 135 entries (492 KB) to
      141 (538 KB), and the source zip, 1,662 entries, holds no `backend/` entry.
    - N2-2: the answers validator, `country-kit/init-answers.schema.json` and `country-kit/lib/answers.mjs`, and the
      backend scaffold, `country-kit/lib/scaffold.mjs` with `templates/be/` (`pom.xml.tmpl` among them) and
      `templates/ci.yml`, a template the upgrade report compares: 23 files from the answers, and no branding text;
      the kit package 154 entries.
    - N2-3: `init` as §4.8 gives it, with no frontend-only path left; the harness's `--backend [--skip-frontend]`
      (§4.8, the untouched `init`); the scaffold rule in three copies (Frontend CI, Backend CI, `verify-local`),
      held equal by `ci-filters.test.mjs`; Backend CI's `Generated country` job; `be/docs/country-api.md`'s
      sentence on the pom `init` writes, and the backend-scaffold box of `be/docs/cicd/implementation-plan.md`,
      ticked.
    - N2-4: `ensure_domain_clients` in `be/scripts/local-run-lib.sh`, run at the launcher's bootstrap step for the
      country's `be/bootstrap/`: thirteen `kcadm` calls through `docker exec niord-keycloak` for a missing client,
      two when it exists; `LocalRunLibraryTest` red 8 of 15, then green.
    - N2-5: the public site's default footer (§5 step 5); its spec red 10 of 15, then 15 of 15, and every bundle
      group at or below its baseline.
    - N2-6: `docs:check`'s rule 8, under which a link's `#fragment` into a document of the 38-file read set names a
      heading by GitHub's slug rule or an explicit anchor; `scripts/docs-check.test.mjs` at 35 cases; no broken anchor in the tree, so no link
      changed.
    - N2-7: the two guides, `country-guide.md` (419 lines at #265) and `country-guide-in-depth.md` (1,187), every
      section of the old guide placed by a heading map; the following documents re-pointed; `API-CHANGES.md`'s
      `Unreleased` entries 1 to 5 and 7.
    - N2-8: `country-kit/docs/set-up-prompt.md` (291 lines at #266); the agent template's "Read first" and "When a
      check fails"; `API-CHANGES.md` entry 6; `country-kit/set-up-prompt.test.mjs`, 8 of 8.
    - N2-R: both packages of `4.0.999-n2`, built from the feature branch at `328abfb2` as the publish run builds
      them (the kit package 157 entries); the two routes; the eight gaps' fixes in three documents. At N2-9's base
      the short guide is 439 lines, the guide in depth 1,190 and the prompt 315.
  - _The routes (N2-R)_, run at once by two workflow agents against a stand-in release (below), each allowed to read
    only what a country receives. **The Person route** (a technical maintainer, the fictitious country `qr`, by
    `country-guide.md` alone) reached its end with no outside help, in 38 minutes of steps: the release checked
    against both lines; the country code given on the command line and `init`'s other fifteen answers on standard
    input, 41 files written and none kept; set-up green in 17 s; the launcher to its closing block in about 9
    minutes, creating the client `qr-nw` and bootstrapping `niord_qr` from the two archives; `/rest/domains/all` and
    `/rest/settings/web` gave the answered domain and `en`; the sysadmin's five roles on `qr-nw` were seen in a token
    fetched by curl, not as the Sysadmin menu in a browser, which the other route was using; the commit 40 files,
    the three scripts at `100755`, nothing of `.niord/`; then one _Make it yours_ change, the PDF logo override with
    `basedOn` from the kit's `backend/img/` entry, its pixels decoded from an empty list's `report.pdf` after a
    restart; the definition of done green (the six frontend commands, `image:info` skipped by the route's rule;
    `be/mvnw verify` in 113 s with the group's 14 integration tests, the group check 0 skipped). CI's override check
    could not run on the route: the scratch repository has no `niord-image-tools` of `4.0.999-n2`. Its four gaps:
    the message-list PDF's address; the first build's bundle-baseline warning; the shared `NIORD_HOME` on Windows;
    the npm range.

    **The Agent route** (an agent given only `set-up-prompt.md`, the fictitious country `xr` with `da`) reached its
    end with one step outside the prompt: before answering the languages question it asked GHCR anonymously for
    `niord-lang-da`'s tags, a confirmatory probe of what set-up's `--update-pins` checks anyway, which its report
    counts as outside help. It took about 26 minutes and ran the prompt's steps 1 to 9 and 11; it skipped step 10,
    the optional builds, since the person had not asked for them, so no build and no `be/mvnw verify` ran on this
    route. `init` wrote 41 files, none kept; set-up with `--update-pins` pinned
    `ghcr.io/sifa-dk/niord-lang-da:3@sha256:60dd614b…` in 28 s; the launcher reached its closing block in about 8
    minutes on the ports 4300, 4500 and 8988, creating `xr-nw`; the three checks passed, the sysadmin signing in in
    a browser and seeing Messages, Editor, Admin and Sysadmin with the domain chosen; the commit `c586e14`. The
    first pin was committed without the comparison with its home's record (it equals the `da` row for tag 3 of
    DK's `pack-releases.md`). Its four gaps: which packs are published; the registry probe; the first pin's
    comparison; the check addresses on the launcher's ports.

    The eight gaps were fixed in #268, in the documents only (`country-guide.md`, `country-guide-in-depth.md`,
    `set-up-prompt.md`; no heading or table row changed): (1) _How to see it_ gives the public site's message-list
    PDF address and says its footer shows the logo with no message; (2) the definition of done calls the first
    build's bundle-baseline warning expected and links the two commands that record a baseline; (3) the Niord home
    folder is shared by every build on the machine, so two `verify` runs must not overlap; (4) npm 11.11.0 or a
    later 11.x; (5) a language is a pack another country publishes on GHCR, its home's `pack-releases.md` lists the
    tags, `da` from DK was the one published pack, and set-up's refusal texts for an unpublished one are quoted;
    (6) the prompt makes no registry call of its own; (7) the prompt shows each first pin's digest beside its home's
    record and waits for the person's yes before the commit, and the in-depth guide says compare before committing;
    (8) the prompt's checks use the closing block's ports. The environment, recorded once: no GitHub Packages token,
    and `niord-ng` private, so a stand-in release folder (the two zips and a `SHA256SUMS`, since the publish run
    attaches no `SHA256SUMS` asset and the notes carry the two lines) took the place of `gh release download`, and a
    scratch Maven repository, `.m2-n2`, with the base's parent, library, bootstrap and launcher package of
    `4.0.999-n2`, that of GitHub Packages, through `MAVEN_OPTS` or `MAVEN_ARGS` and the launcher's
    `NIORD_LOCAL_BASE_REPO`; the answers were piped. The Design route was not run: N2 does not change the designers'
    brief (tracker D5). Left on this machine: the two route repositories under `routes-n2/` (`qr` with two commits,
    `xr` with one, neither pushed), the clients `qr-nw` and `xr-nw` in the local realm beside decision 129's
    `yy-nw`, which the owner keeps (deleting in Keycloak is a person's act), and `.m2-n2`; the databases `niord_qr`
    and `niord_xr` were dropped, and the shared containers were never restarted.
  - _Deviations from the map, by sub-PR_ (the map is a scratchpad file, so its sentences are corrected here):
    - N2-1: five files outside the map's list (`country-kit/lib/local-sources.mjs`, `country-kit/setup-kit.mjs`,
      `country-kit/setup.test.mjs`, `country-kit/example/acceptance.test.mjs`, `fe/scripts/docs-check.test.mjs`);
      the kit copy a `NIORD_BASE` set-up makes carries `backend/` too, and its log line says so, so the map's "no
      behaviour change for set-up" reads "no change for a pinned set-up"; `verifyKitZip` requires the six entries,
      not only accepts them, and checks their line ends; `zip.test.mjs` asserts the six `backend/` names unselected,
      not all eight added names, since the kit's globs select `docs/country-api.md` and `kit.json`; `verify-local`
      ran the public site's part too (`lib/where.mjs` and the kit's tests are in its rule); the review moved the kit
      copy's added entries before the atomic rename, and `copyBase`'s catalog likewise.
    - N2-2: the pom's slots are `{{version}}`, `{{cc}}` and the generated `{{packPlugins}}` (`{{pkg}}` is the
      application class's); the class javadoc names the upper-case code, not the free-text name; the defaults follow
      the main type (NM: `<cc>-nm`, `<name> Notices to Mariners`, `<CC>-NM-…`); a series' short format may use the
      backend's twelve `FormatResolver` tokens; the time zone has a third check, no case-only variant of the name
      `Intl` resolves it to, with the residual that `Europe/KYIV` passes; `ci.yml` has one `{{cc}}` slot and no
      `MODULE` of its own, and drops GL's diagnostic step with `test-it` and Python; `country-kit/country-words.json`
      joined the files (the tests name `America/Nuuk`, a guarded word); `application.yml`'s comment says S-124 is on
      by the base product's rule; blank or untrimmed text is refused (the review); the kit's secret scan refused
      three secret-looking samples in `scaffold.test.mjs`, now joined at run time (`c2e24fd4`, decision 129); the
      local profile sets no `server.port`, since the launcher passes the port.
    - N2-3: the two sentences the map had under N2-4 (decision 128); `Generated country` runs on a pull request when
      the scaffold rule matches and on every run on `main` and every dispatch (decision 126 as built), while Backend
      CI's push trigger stays on `be/**`, so a merge that touches only the kit builds the generated backend on its
      pull request only (decision 130); the scaffold rule's string drops the logo and adds `be/niord-app/`, both
      bootstraps, the country build group, the override check, `lib/where.mjs`, the answers schema, the harness's two
      files and both workflows; Frontend CI passes `--backend` only when the example rule also starts the `Example
      country` job, and a pull request matching both rules builds the generated backend twice; `yy`'s answers are at
      `.niord/init-answers.json`, with no language; the source zip is read from `.niord/`, else `fe/.niord/`; only
      `init.test.mjs` and a check by hand run `init` in the source layout, not `docs:check`; the implementation
      plan's box says the producer code "when one was answered"; `be/docs/country-api.md`'s sentence is a paragraph
      of its own; the review moved the check of a kept manifest before any write and the release checks before the
      questions. A cost, stated in `be-ci.yml`'s header: `publish.yml` publishes a `main` commit only when Backend
      CI succeeds, so every backend merge's publish now waits the job's 8 to 10 minutes.
    - N2-4: run beside N2-2, before N2-3 (decision 128); the roles are user, editor, admin and sysadmin, the last
      three composite as `niord-client-master` has them, plus publication-curate; only the Sysadmin group is
      mapped; one `get clients` per start, not one look-up per domain; the red run 8 of 15, not 7; the test run with
      Surefire, not `be/test-it`, since a `*Test` is a unit test; an agent's shell proof reached the real Docker and
      made the client `yy-nw` (decision 129), so the test's drivers refuse to run unless `docker` is the stand-in.
    - N2-5: the accessibility link goes to the configured statement, `accessibilityStatement`, not to the legal
      dialog, whose public-site documents are the disclaimer and the cookie statement; the items follow DK's order.
    - N2-6: the slug keeps letters, marks, decimal digits, `_`, `-` and the space, not "punctuation stripped";
      three `fe/` documents gained the rule's line (`fe/AGENTS.md`, `fe/docs/coding-standards.md`,
      `fe/docs/definition-of-done.md`), the kit README having no paragraph on `docs:check`; no link changed; root
      `docs/` and `be/docs/` stay outside the read set, so rule 8 reads no anchor of this plan.
    - N2-7: the short guide is 419 lines, not about 300: about 400 with its tables and checklists (decision 131);
      text overrides stay an advanced part (the owner, 2026-10-26); entry 5's lead is "Two guides: the short
      standard path and the in-depth reference."; `fe/docs/backlog.md` (two entries N2-3 and N2-4 closed gone, a
      third reworded) and `country-kit/scaffold.test.mjs` joined the files; two sections of the old guide were
      dropped with their reason (the validate of a hand-written pom, and the wait for the bootstrap step, since
      `init` writes both); the GitHub CLI joined the prerequisites; the five Mermaid drawings were parsed as syntax,
      not rendered.
    - N2-8: entry 5 kept its lead and entry 6 announces the prompt; the never list states its `.niord/` exception
      (before `init` only the answers file, the two zips and `SHA256SUMS`, and the kit unzipped); step 4 also makes
      the repository; the third check, the Sysadmin menu, is made by the person or by an agent with a browser tool,
      with no token request; `country-kit/README.md` joined the files; the test also forbids `NIORD_BASE` and
      `maven.repo.local`.
    - N2-R: the map's RISKS line names `niord-ng-wt/routes/` and databases left behind, but the folder is
      `routes-n2` and both databases were dropped; its VERIFY line's "`be/mvnw verify` … in both" held for the
      Person route only, since the Agent route skipped step 10.
  - _Decided by recommendation_ (the orchestrator's decisions, in its scratchpad; the owner accepted 127 to 131 on
    2026-11-03):
    - decision 126: the map's cut and its nineteen decisions by the map's recommendation, with one change, the
      generated country on Backend CI path-filtered on pull requests and run on every run of `main` (above); two
      items left to the owner, the GRAD mirror for a new country and the acceptance without a token (below);
    - decision 127: one guard test, the publish gate's bash step in `release-workflows.test.mjs`, times out at
      `runBash`'s 60 s limit when three suites share the workstation; no code changed, since it passes alone (51 of
      51);
    - decision 128: N2-4 ran beside N2-2, and its two sentences on what the scaffold writes moved to N2-3;
    - decision 129: N2-2's secret-scan fix; the client `yy-nw` an agent's proof made in the local realm, left to the
      owner, who keeps it;
    - decision 130: Backend CI's push trigger stays on `be/**` (above), and the double build of a pull request that
      matches both rules is accepted for now;
    - decision 131: the short guide accepted at 419 lines, the owner's guide review (decision 97) being the place to
      cut further;
    - decision 132: the routes' account above.

    The Renovate step's hosted app (E1, E2; _The Renovate step_) and decision 132's self-hosted Renovate are
    superseded by decision 133 (the owner, 2026-11-03): the bump by push, through a GitHub App of SIFA-DK's own that
    the publish run uses to open the bump pull request in each country that installed it, built after N2 as a step
    of its own, whose record it is (§9's Renovate item until then).
  - _What the next bump means for a country._ Nothing of N2 is published until the owner merges #259. DK's and GL's
    first bump onto a version with N2 carries N2's `Unreleased` entries: entry 6 asks for the merge of the agent
    template's "Read first" and its `sysadmin` line, the merge the Base bump comment's agent-template box already
    asks for, so one merge ticks both; entry 7's `templates/ci.yml`, new to the kit and so counted as changed, comes
    as the generic _Look at_ box (compare once, nothing required) until `templates/base-bump.yml`'s `templateItems`
    names `ci.yml`. The launcher adds no client for DK or GL, whose bootstraps list only domains the realm already
    has clients for, and their hand-written backends are not compared with the scaffold.
  - _What stays unproven._ The real `read:packages` token path: `~/.m2/settings.xml`, and set-up's and the
    launcher's fetch from GitHub Packages; `gh release download` against the private repository; a cold Maven cache
    with GRAD down (BUILD1), on which a new country's first build depends while `init` writes no mirror, and the
    mirror for a new country itself, the owner's open question (decision 126); CI's override check run on a route
    (the harness ran it over `yy`'s jar); set-up's exact refusal of a pack that was never published, whose two texts
    are the code's and its loopback tests'; the Sysadmin menu on the Person route, seen in a token only, and a build
    and `be/mvnw verify` by the prompt alone, since the Agent route skipped step 10; the five Mermaid drawings
    rendered; the in-depth guide's start by hand and its `kcadm` fallback for the sysadmin, which no run made. Not
    run at the time of writing: #259's CI, the first run of `Generated country` and of the `Example country` job
    with `--backend` on GitHub's runners. A run on a clean machine with a token would prove the first three.
  - _Found, for later steps_ (not N2-9's files, which are root `docs/` only): the comments at
    `fe/scripts/docs-check.mjs` (:38 and :193) on the two entries the kit build adds, eight now; the comment at
    `publish.yml:473-480` on the same two entries (an edit runs Backend CI and publishes); `fe/README.md:166` and
    `:244-245`, which describe the untouched `init` without `--backend`; REALM1's parenthetical at
    `be/docs/potential-errors.md:1860`, since the launcher makes the client; `templates/ci.yml`'s header, "the
    Image workflow follows a green push run", while `templates/image.yml` runs on dispatch only; the agent
    template's exec-bit and bootstrap sentences against `init`'s commit line; `templates/base-bump.yml`'s
    `templateItems` without `ci.yml`; the public site's legal documents with no `labelKey` in
    `branding.schema.json`, so the branding check does not hold a public-site label to its title and document;
    `init`'s shared `NIORD_HOME` line, which could name a folder per repository; set-up's first-pin line, which
    could print the home's `pack-releases.md` address; the prompt's step 10, which still names the ports 4200 and
    4400, and its npm row; no step that signs in from the command line; `verify-local`'s summary taking grep's
    `Binary file … matches` into #264's example cell; PORT-1's fix in the base product. In this plan, §4.2's layout
    has no row for the `ci.yml` `init` writes, and §9's Renovate item still reads "Open: F1 and F2": both for the
    later steps.
  - _Sentences corrected, and records that keep their words._ In this plan, by N2-9: the status line (NG-B's clause,
    merged and published, and N2's); §4.8's opening, which said the kit is published in the source zip, its `init`
    bullet, written for `init` as N2-3 built it, and the untouched `init`'s sentence on N2-3's `yy` and
    `--backend`; §5, rewritten to the plug-and-play steps with its eleven numbers and their subjects kept, since §2,
    §4.8, §4.11, §11, the records, `be/docs/cicd/implementation-plan.md` and `be/docs/potential-errors.md` cite
    them by number, and the routes' paragraph after P7h's; and, insertion only, a dated pointer to this record in
    three records whose sentence N2 made history: _The guides, part 1 (D1a), as built_ (P8a), "`docs:check` reads no
    anchor"; _KM-6 as built_'s harness, the first-time path of `untouchedInit`; _RN-4 as built_'s checks,
    "docs-check checks files, not anchors". P7h's dry-run paragraph in §5 keeps its words: its "steps 1, 4, 8 and 9
    above" still names those steps' subjects, and a record that cites a §5 step by number reads against the same
    subject. The records of P7h, P8a, KM-9 and NG-B are otherwise unchanged. In `docs/code-data-config-plan.md`: its
    status paragraph, on NG-B and on the settings file `init` writes.
- **The bump by push (F8, decision 133; map of 2026-11-03).** The owner's decision of 2026-11-03 ~07:50, after a
  morning's discussion of the Renovate step's two ways in: Mend's hosted app needs a credential per organisation
  (a machine user's `read:packages` token in every country organisation); [GitHub, from knowledge] GitHub disables
  a scheduled workflow in a public repository after 60 days without activity, so a self-hosted Renovate (decision
  132) would stop once the repositories are public; [GitHub, from knowledge] Dependabot needs a registry token for
  GitHub Packages' Maven endpoint and pauses version updates after 90 days of ignored pull requests. In their place
  a GitHub App owned by SIFA-DK, "Niord base bump", used only by `publish.yml`'s `bump` job, opens
  `base-bump/<version>` from the default branch, or moves the one open app PR, in each country that installed it,
  minutes after each publish and never automerged; the pinned packs ride along in the same PR, moved by the Base
  bump workflow. The map, with its critique folded in, and its decisions F8-D1 to F8-D18 are in the orchestrator's
  scratchpad (decision 135; decision 134 gave the owner's clicks, and F8-D5 corrects their place to Settings >
  Environments > publish); the reasons are recorded in `docs/renovate-plan.md` §0b. `feature/base-bump-app` was
  cut from `main` at `dc3b1d86` after #259; each sub-PR is squash-merged into it after `./verify-local --base
  origin/feature/base-bump-app`, its summary in the PR; its PR into `main` is the owner's to merge.
  - **The sub-PRs**, in the order F8-1, F8-2, F8-3, F8-4 (F8-D18):

    | Sub-PR | What | Depends on | State |
    |---|---|---|---|
    | F8-1 | the bump script `fe/scripts/open-bump-pull-requests.mjs`, the fake GitHub `fe/scripts/github-api-stub.mjs` and the unit test | — | merged, #272 |
    | F8-2 | `publish.yml`'s `bump` job, the gate's key-location warning and their workflow test (`fe/scripts/release-workflows.test.mjs`) | F8-1 | merged, #273 |
    | F8-3 | the documents: the kit guides and README, this plan's live rules, `docs/renovate-plan.md` §0b, the preview, latency and CI/CD plans | — | merged, #270 |
    | F8-4 | the kit: the Base bump workflow's pin refresh and kept ticks, `templates/renovate.json` retired, `init`'s install line, `API-CHANGES.md` | F8-3 | merged, #274 |
    | F8-5 | the review of #271's finding: a template box keeps its tick across a move only while the kit template's content hash, carried in a hidden marker, is unchanged (F8-D11 tightened) | F8-4 | merged, #276 |
    | F8-6 | the Base bump comment and the upgrade report name every other changed kit template in one line, no box (decision 147) | F8-5 | merged, #282 |

  - _As built._ #271 (`feature/base-bump-app` into `main`) was merged by the owner on 2026-11-03 as `9677f31f`
    and published as 4.0.120 to 4.0.122 (one publish per CI workflow's completion, as before). The owner had
    registered the app on SIFA-DK, put its key and id on the `publish` environment and installed it on `niord-dk`
    and `niord-gl` on 2026-11-03 (decision 136 records that the move to the NiordOrg organisation repeats the
    registration there, since the fresh repositories are new installations). **The first live run proved what was
    stated from knowledge:** each publish's `Open the bump pull requests` job ran green as the app in about 20 s
    (4.0.122: 17:15:09 to 17:15:29 UTC) and opened `niord-dk` #15 and `niord-gl` #13, "Base bump to 4.0.122" by
    `niord-base-bump[bot]`, within 15 s of the tag, then moved them in place at the next publish; the app's commit
    and pull request started the countries' Base bump workflow and CI; the code owners got the review request.
    While niord-ng is private the pull requests landed in the pin-by-hand state (decisions 105 and 106): the
    workflow could not read the release notes with the country's token, its comment held the one box "Pin
    4.0.122 by hand", and CI was red on the unpinned lock. On the owner's accept the orchestrator pinned both from
    the notes and dispatched the workflow (DK 2 min 5 s, GL 1 min 45 s on the second dispatch; GL's first failed
    in its Maven fetch on a transient resolution error against the country's file mirror and passed unchanged),
    which left six boxes on each: the three "at its bump" actions of the Unreleased `API-CHANGES.md` entries (the
    agent template's "Read first" and sysadmin line, the `ci.yml` comparison, `niord.s124.enabled` written
    explicitly) and the three template items (merge `AGENTS.md`, look at `ci.yml`, copy the new `base-bump.yml`),
    the biggest set a country will see for a while, since this span delivered F8 itself and the S-124 opt-out
    (decision 143 explains to the owner why a simple bump shows "No human action"). The owner had those done too,
    in one commit per country (`niord-dk` `bdf0378`, `niord-gl` `1a53a83`): the Base bump workflow ran green on
    the push (the workflow file now the 4.0.122 template's), the ticks were recounted to `niord/upgrade-actions`
    green, CI passed in 4 min 20 s each, and the owner merged `niord-dk` #15 (`1dcf338c`) and `niord-gl` #13
    (`40279d90`) on 2026-11-03: the first app bump complete end to end, both countries on 4.0.122 with the new
    workflow on `main`. Left to the owner: the GitLab chart values' `s124.producerCode` check and the inert
    Renovate leftovers (`.github/renovate.json`, its CODEOWNERS line, the comment in `dependabot.yml`). Seen on
    the way, an open question (F8-6, the tracker): the comment names template changes only for the three files it
    tracks, while CODEOWNERS, `dependabot.yml` and `gitattributes` changed in the kit too, every one optional by
    its entry. Decided on 2026-11-04 (decision 147) and built as F8-6: the report's `otherTemplatesChanged`
    and one line in the comment above `## Human actions`, with no box, so the eight tracked templates keep
    their boxes and nothing turns red. Between the 4.0.114 and 4.0.122 kits the line names `CODEOWNERS`, the
    nine `be/` files, `dependabot.yml` and the removed `renovate.json`; the top-level `gitattributes` was
    unchanged, and the new one was `be/gitattributes`. **What stays unproven until niord-ng is public:** a move with the notes readable (the pin made by
    the workflow itself, green with no task), the kept ticks across a real move with boxes (the recount-only path
    on an already pinned lock rewrites no comment, so F8-5's markers appear first when a move writes one), the
    conflict case, the refusal of a workflow file on the app's branch, the real billed minutes of a full move, and
    GL's first pack refresh (`da:2` to `da:3`), which comes at its next bump now that its `main` carries the new
    `base-bump.yml`.

  - _The owner's steps_ (the map's §7): register the app on SIFA-DK (no webhook; Contents and Pull requests read
    and write, Metadata read, nothing else and no events; installable on any account), note its App ID and generate
    its private key; store the secret `NIORD_BUMP_APP_PRIVATE_KEY` (the whole `.pem`) and the variable
    `NIORD_BUMP_APP_ID` on niord-ng's `publish` environment (Settings > Environments > publish), not at repository
    level (the gate's new step warns on every run when it finds the key there); install the app from
    `https://github.com/apps/niord-base-bump` with Only select repositories, niord-dk and niord-gl, never niord-ng.
    When: after F8's PR into `main` is ready and before its merge, whose publish is the first live run; until then
    the `bump` job skips with a notice, the publish stays green, and the bump by hand stays. The key is rotated
    yearly with the other publish credentials ([GitHub, from knowledge] an app's private key does not expire).
  - _The cost_ (F8-D14). Measured on DK #13 (2026-11-02, wall time): the Base bump workflow on the PR 27 s to 1 min
    17 s, dispatched with `pr 13` 2 min 2 s; CI 35 s on the unpinned lock (set-up stops both jobs) and 4 min 50 s
    on the pinned branch. Estimated, the billed minutes rounding each job up: about 4 to 5 per country per move
    while niord-ng is private, about 12 more when a person pins by hand and runs the workflow, so about 380 a month
    for two countries at about 38 releases a month (15 from 2026-10-22 to 2026-11-03); about 14 per move, about
    1,060 a month, once niord-ng is public and every move pins and runs the full CI; both from SIFA-DK's shared
    2,000 a month. The live runs give the real figures.
  - _Open for the owner_, built by the recommendation until he says otherwise: F8-D10, the moved pin a line in the
    comment, not a box; F8-D13, no pins written by the app while niord-ng is private (the pin by hand stays,
    decisions 105 and 106); F8-D14, the PR moved at every publish, revisited when niord-ng goes public.
  - _What only the live runs prove_: GitHub accepting the JWT made from the real key, the real installation listing
    and the permission names on its tokens; the PRs opening in niord-dk and niord-gl at the version F8's merge
    publishes; [GitHub, from knowledge] that an app's PR and commit start the countries' Base bump workflow and CI;
    the review request to the code owner `CODEOWNERS` names on a private Free-plan repository; the move in place at
    the second publish, and the kept ticks; the conflict case (no `pull_request` run on a conflicting branch, then
    the replacement); the refusal of a workflow file on the app's branch; a job-level concurrency group with
    `cancel-in-progress: false` (the bump job uses no `queue: max`, which this tree has at workflow level only); the
    real billed minutes of a move; GL's first pack refresh, which came on 2026-11-04 at the bump to 4.0.135: the workflow moved `da` from 2 to 4 and committed the pin itself. That commit showed one more fact (F8-7, decision 149): a push made with the workflow's own token is the Actions bot's, and the pull request's CI and Base bump runs on it are created held for a maintainer's approval, while the app's own commits are not held; left as it is, one Approve and run per pack move, looked at again when the repositories go public.
  - _Records that keep their words_: RN-1 to RN-7 as built (_The Renovate step_), the 4.0.111 entry of
    `country-kit/API-CHANGES.md`, _MAIN, DK-1 and GL-1 as built_, and _N2 as built_'s note on decision 133; §9's
    Renovate item and §11's ruling 8 keep theirs, each with one dated sentence added.

**P9 — the sample deployment (R11).** One command starts a working Niord with content, for a developer
trying the base product and for a country starting out — the successor of the legacy repository's base-data zip,
automatic.
- The starting point: before P9, `./start-local.sh --demo` started the services (PostgreSQL/PostGIS, Keycloak
  with the local realm, MailHog), the generic backend and the frontends. From the code/data/configuration plan's
  C4 the backend started on a new database bootstrapped through the bootstrap command, with the base product's
  bootstrap and then the base-data archive (`be/niord/seed/niord-dev-basedata.zip`, from which
  `niord-base-data.sql` was captured; deleted in the code/data/configuration plan's C4b). What it lacked:
  - **sample messages** — the seed had the structure (areas, charts, categories, series, publications) but
    no messages: a few dozen navigational warnings and notices in Danish waters, in every status, with
    geometry, attachments and both languages, so lists, map, editor, publications and the public site show
    something. **A dataset of its own, layered on the bootstrap:** every fresh deployment gets the
    base product's bootstrap (`docs/code-data-config-plan.md` §7); the sample adds the DK rows it needs, carried
    in `be/niord/sample/` as Danish sample content (R11): DK's domains and series, `Audio` (from P7 a
    restatement of the base product's type, its values equal to the base's and its domain link added, §6 P7 D7), the domain links of
    `NAVTEX` and `Twitter`, the two scripts its mailing lists name (`fetch-firing-exercises.js`,
    `audio-broadcast.ftl`) and the DGPS pair its category Radio navigation/DGPS names (`tmpl/dgps.ftl`,
    `tmpl/dgps.js`; without it `missing-paths` is not `[]`). Nothing is fetched from `niord-dk`,
    so no credential is needed; drift from `niord-dk`'s bootstrap is accepted. Then come the demo archive's Danish
    base data (charts, areas, sources, publications, mailing lists, the categories beyond the bootstrap's) and the
    messages. The seed stopped being a
    deployment path with the code/data/configuration plan's C4 (the Helm switch went) and remains the integration suite's fixture, regenerated from the
    base product's bootstrap and the sample after P9 (`docs/code-data-config-plan.md` C4b, §7.4 and §11: a dump
    of a database bootstrapped with the base product's archive and loaded with the sample by `load-sample.sh`, in
    niord-base's test-jar, which is no deployable and goes into no image; the no-sample check passes it); tests read the whole message corpus (e.g. `MemberResolutionDifferentialITTest`). The
    sample is `be/niord/sample/` — versioned with the base product, built, captured and checked against the schema
    by one regeneration script (`be/niord/scripts/regen-sample.sh`, with a base-only baseline database; *State as
    built* below) — loaded by its own path only when the sample is asked for (after the
    bootstrap command, with `psql`, per the safety rules below);
  - **sample users** in the local realm, one per role (sysadmin, admin, editor, user), named in the README;
  - **one command with no knowledge needed** — `./start-local.sh --sample`, which replaces `--demo` (removed in P9): the services, an empty database
    created, the backend, the bootstrap command, the sample loaded with `psql`, the backend started again (its
    caches and message index then see the sample), both frontends, and at the end the URLs and the sample logins;
    re-running it on a seeded database starts it again, `--reset` starts from scratch;
  - **the same in a country repository** — its launcher (the open item `implementation-plan.md:112`) calls
    the base product's, with the country's backend and frontend variant (`setup.mjs`): the base product's
    archive, then the base product's sample, with no country seed (decision (c) of P8, built for the sample in
    P8a, NG-8: the sample loads on a country's backend after the base archive). Not built in P9's base product part (*State as built*): it
    is P8's, the kit's template and DK's and GL's launchers (the owner's ruling of 2026-10-23). By the owner's requirement of
    2026-10-23 DK's launcher starts with Danish on, while the base product's `--sample` stays English only.
- **Safety rules (security review, 2026-10-19):**
  - The sample is never on a deployable's classpath or in an image: it lives in `be/niord/sample/` outside
    `src/main/resources`, and the launcher loads it with `psql` into the loopback database after the backend
    has built the schema — not a Liquibase context, a Spring profile, an endpoint or a setting. A base product CI
    check fails when any jar or image contains it. The loader refuses unless the database host is loopback
    and the database is `niord_sample`, or `niord_sample_<cc>` for a country's launcher (a two-letter
    lower-case country code; since P8a, NG-8); `--reset` drops only that database. One package carries the
    sample, as allowed: since P8a (NG-9) `org.niord:niord-local-run:<v>:zip` (`be/scripts/local-run-zip.sh`),
    for a local run only. No deployable carries it (the jars, the bootstrap zip, the image), and the zip is
    never an image input: no Dockerfile or image step reads it, the no-sample check fails on it, and neither
    Backend CI nor `publish.yml` passes it to that check. `LocalRunZipTest` and `release-workflows.test.mjs`
    hold this.
  - **The messages are written for the sample, never exported** from any Niord environment or a local import
    of one; every title starts with "SAMPLE —". The sample has no user, contact, comment, history or
    recipient rows, its only e-mail domain is `example.org`, attachments are generated with no metadata; the
    regeneration script fails on anything else (e-mail outside `example.org`, a phone pattern, a table outside
    an allow-list). A seed made from `niord_test` or a production export is never committed as a country seed.
  - The sample users exist only in the local realm that `docker-compose` imports; the chart's realm gains none.
    Every port stays on `127.0.0.1`, and the launcher's closing lines and the README say: a local trial on this
    machine only — Keycloak in development mode, known passwords; never expose it; a country deploys with the
    chart. Mail goes to the loopback MailHog; SECOM's outbox dispatch is off.
- Verification: on a clean machine with only the documented prerequisites (JDK 25, Node, Docker), the
  command reaches a working administration app and public site with the sample messages, from the base product
  and from `niord-dk`; the P8 guidelines point to it as the first step.
- Done when: a newcomer has a running Niord with content after one command, without reading more than the
  README's first section.
- **Base product part: built 2026-10-23** on `feature/p9-sample` (branch `p9/sample`; PR #192 into `main`,
  merged 2026-10-23 as `195d102d`, 4.0.89), with `docs/code-data-config-plan.md` C7, whose §8 and §11 C7 hold the backend's record.
  - **The sample** (`be/niord/sample/`): a README with that plan's §7.1 first lines, naming `niord_sample`, the
    provenance, the safety rules and the sample users; `archive/`, a bootstrap archive; `messages/messages.json`,
    34 messages written for the sample; and the generated `niord-sample.sql` (1507 inserts) and `repo/` (4 PNG
    attachments). `archive/`'s `domains.json` (the 5 domains `niord-nw`, `niord-nm`, `niord-fa`,
    `niord-almanac`, `niord-annex` with the 6 series), `promulgation-types.json` (`NAVTEX` and `Twitter` with
    their domain links, `Audio`) and the four scripts are copied byte for byte from DK's merged bootstrap
    (`niord-dk` `feature/p5-dk-moves` @ `68b95cf`); the rest comes from the demo zip with `niord-client-*`
    renamed `niord-*` (37 category and 2 publication links) and the categories' 26 `messageId` removed, the
    counts kept (45 categories, 111 + 4 `aton` entries, 34 parameter types, 172 charts, 55 areas, 29 sources,
    4 publication categories, 8 publications, 5 mailing lists). The review of #192 mended the zip's own
    defects there (five sources re-paired, trailing spaces of two source names and three chart names, the
    mailing lists' typos, the six plain-http links of three publications left out; that plan's §8).
    The messages: 12 `dma-nw` and 2 `dma-nw-local`
    in `niord-nw`, 12 in `niord-nm` (each over DRAFT, VERIFIED, PUBLISHED, EXPIRED, CANCELLED and DELETED), 4
    in `niord-fa`, 2 each in `niord-almanac` and `niord-annex`; fixed uids, `en` and `da`, every title
    "SAMPLE —", geometry on 30, every position at sea where its vicinity says (nine moved there in the review
    of #192, judged from general geography), a PUBLISHED message with no end date; the attachments are drawn by a
    test-source
    main (`SampleTool`) as palette PNGs with no metadata chunk, the same bytes on every run (at most 99; past
    that it stops with an error naming the limit).
  - **Format and loader** (the P9 record; its ids and its guard were replaced, and the loader's one database
    name widened, in P8a: the next item): one `psql` script of the rows the sample adds on top of the base
    product's bootstrap: data-only column inserts with triggers disabled per table, in one transaction, a guard
    first and every touched sequence's `setval` last. The ids continued the bootstrap's own sequences, those of one
    bootstrap run on a fresh database with `modelLanguages` at its default ("unretried" until the owner's ruling
    of 2026-10-24, `docs/code-data-config-plan.md` §7.2, *No retry*, after which no bootstrap is retried;
    replaced in P8a). Until P8a the guard refused
    unless the database is `niord_sample`, its marker reads `applied`, its Liquibase changesets are the recorded
    list, no target table holds a row at or above the sample's first id there, and the base rows the sample's
    foreign keys point at, and the base parameter types, match a recorded fingerprint (`Category` by MRN and
    English name, `Dictionary`, `DictionaryEntry`, `PromulgationType`, `TemplateParam` by `paramId`, type and
    English name, and `ParamType` by name and kind over the 32 base ids, the last two since the review of
    #192). The changeset refusal names `regen-sample.sh`; the first-free-id and fingerprint refusals name
    `./start-local.sh --sample --reset`, and a regeneration only after a change to the base bootstrap, the
    schema or the entities. `be/niord/scripts/load-sample.sh [--host] [--db]
    --home <niord.home>` refuses (exit 1) unless the host is loopback, niord-db publishes its port on loopback
    only and the database is `niord_sample`; past those refusals it first copies `repo/` into the Niord home
    (exit 1 and no SQL run when the copy fails), then answers 3 without writing a row when the sample is
    already there, 0 when loaded, and 2 on a usage error.
  - **Changed in P8a (NG-8, 2026-10-25; decision (c), §6 P8's country launcher)**, so the sample loads on any
    country's backend after the base archive: the sample's own ids start at 1,000,001 in every table (34
    tables; the highest 1,000,172); every base row it names is a natural-key subselect (`Dictionary` by name,
    `DictionaryEntry` by dictionary and key, `PromulgationType` by `typeId`, `Category` by MRN or English name,
    `TemplateParam` by owner, position and `paramId`), in place of a base id; the guard checks the database
    name (`niord_sample` or `niord_sample_<cc>`), the schema and marker, the changesets, the offset range free
    in rows and sequences, the sample's domains and series free, and each natural key resolving to exactly one
    row, in place of the first free ids and the fingerprint; and `load-sample.sh --db` accepts `niord_sample` or
    `niord_sample_<cc>`, and the launcher library's `sample_reset` drops either. The interface is otherwise
    unchanged (exits 0, 1, 2, 3). `docs/code-data-config-plan.md` §8 holds the detail and the verification
    (the red-first `SampleOnACountryBaseITTest`, the regeneration and the fixture's).
  - **The regeneration** (`be/niord/scripts/regen-sample.sh`) builds two scratch databases through `run.sh`
    and the bootstrap command: `niord_sample_base` with the base product's archive only, the baseline, and
    `niord_sample` with the base archive and the sample's archive in one run, then the messages; it installs
    niord-base into a Maven repository of its own (`.local-run/sample-regen-m2`, the shared `~/.m2/repository`
    behind it), as a launcher slot does, and creates only a missing container of the three. It compares
    the two through `postgres_fdw` (every baseline row unchanged but for `version`, `created`, `updated` and the
    derived `treeSortOrder`, or it fails; the residue tables are not compared), captures the rows the baseline
    lacks for the allow-listed tables, normalised so that a rebuild gives the same bytes on any day (since the
    review of #192 also `nextScheduledExecution`), checks the link tables' rows against `messages.json`, runs
    `SampleSafetyCheck`, and loads the result with `load-sample.sh` into the baseline renamed `niord_sample`: the
    schema check, where a second load must answer 3. The messages go in through the message archive import run
    as a sysadmin batch set, a route taken while the import endpoint failed on every upload (MSGIO1, fixed on
    2026-11-13). `--check <sql> [<repo>]` runs only the safety check.
  - **The launcher** (`./start-local.sh --sample`): the database `niord_sample` (any other `NIORD_DB` refused),
    the files in `~/.niord-ng-sample`, both frontends unless `--admin` or `--info` is given (`--sample --info`
    serves `fe/docs/testing.md`'s CSP run), and `-Dniord.secom.enabled=false`; it refuses `--dk`, `--gl`,
    `--fe-only` and `--own-db`. Its order: the services, `niord_sample` created when missing, the backend, the
    bootstrap with the base product's archive only, the backend stopped, the sample loaded, the backend started
    again, then the frontends, and the closing lines (URLs, the four logins, the local-only warning). It calls
    the loader on every run: on a loaded `niord_sample` the loader copies the attachments again and answers 3,
    taken as success, and the launcher starts it again, saying that a changed `be/niord/sample` needs `--reset`;
    on one whose marker reads `existing` without the sample it stops, naming `--reset`. Until the owner's ruling
    of 2026-10-24 (that plan's §7.2, *No retry*) it remembered a retried bootstrap (the marker read `failed` or
    `in-progress` before its post): a retried run that did not finish, or a loader refusal after it, named
    `--reset`; any other loader refusal stops it, deferring to the guard's own remedy. Since P7-bs there is no
    retried bootstrap: it posts only on `pending`, and on `failed` or `in-progress` it prints the state, names
    `--reset` and posts nothing; when its own post fails it reads the marker again and says what happened (that
    plan's §7.2, *Who calls it*; since the review of #204 in `bootstrap_outcome`, with a `pending` arm, refused
    before it started, and an `applied` arm, finished after `bootstrap.sh` gave up waiting, which loads the
    sample). Since the review of #192 it also refuses any `NIORD_HOME_DIR` but `~/.niord-ng-sample` (exit 2),
    a `JAVA_HOME` without `bin/jar` (a JRE), and a checkout or Niord home path with whitespace when a backend
    starts; it reads `GET /rest/bootstrap` for up to 120 s while it answers 503. `--reset` refuses while a
    client session is connected (autovacuum and parallel workers not counted) and drops only `niord_sample`.
    `--demo` answers with exit 2 and a pointer to `--sample`. README's first section is now
    *Try it*, the one command.
  - **Sample users**: the local realm's four users `sysadmin`, `admin`, `editor` and `user` (the password is the
    name), with their addresses changed to `<name>@example.org`; the chart's realm gains none.
  - **Checks**: `SampleFolderTest` (the folder's README, the batch set, no `niord-client-`, the SQL passing
    `SampleSafetyCheck`, every `repo/` file named by an attachment row; since the review of #192 the guard's
    changesets against those `db.changelog-master.yaml` includes, every bookkeeping timestamp at the epoch, the
    guard's remedies and, until P8a, its fingerprints (since NG-8: the offset check of every id table, the
    natural-key check of each referenced table, every id at or above 1,000,001 and every non-literal value a
    `SampleReferences` shape, and `SAMPLE_FIRST_ID` equal to `SampleReferences.FIRST_ID`), the link tables' rows against `messages.json`, and the zip's defects
    staying out), `SampleSafetyCheckTest` (each rule red on its own snippet; since that review rule (a) closed
    over the statements the regeneration writes, the plain phone forms, a Unicode e-mail domain, any non-PNG
    file and exact PNG chunks, and parser cases with multi-line literals, quotes, dollar-quoting and comments),
    `SampleToolTest` (an absolute or escaping attachment path refused), `CheckNoSampleTest`,
    `LoadSampleScriptTest` (the loader's argument refusals, before any container, and its usage naming
    `./start-local.sh --sample --reset` from the repository root), `BootstrapRestServiceTest`
    (503 before the start-up flag) and `CoreResourcePackagingTest.theSampleDoesNotShipWithCore` (the whole
    classes folder), unit tests in every Backend CI run, which re-includes `be/niord/sample/**`. `be/k8s/scripts/check-no-sample.sh`
    (`CheckNoSample.java`, JDK built-ins) fails when an artifact holds a file of the sample, by its layout path,
    its name, its SHA-256, a sentinel line read from the SQL or, since the review of #192, the messages' uid
    prefix read from `messages.json`, also inside nested jars and zips and in a `docker export` tar: it reads
    jars, zips and `docker export` tars, and a `docker save` tar exits 2. It runs in `be-ci.yml` after the build (the jars and the bootstrap zip), in
    `publish.yml`'s build job before the first deploy (also over `dist`) and its harbor job over the image's
    inputs (`dist`, `web`) before the log-in, and in `./verify-local`'s backend part (since the review of #192
    only after a green build).
  - **Verification as run** (Windows, 2026-10-22 and 23): `SampleFolderTest`, `SampleSafetyCheckTest`,
    `CoreResourcePackagingTest`, `BootstrapArchiveTest` and `BootstrapFolderTest` in one run, 56 tests, 0
    failures. Two regenerations gave the same bytes (`niord-sample.sql` and `repo/`; 16m03s
    and 15m33s on a machine loaded by other sessions, 5m48s on a quieter one). `--check` red once for each rule
    (an `example.com` address, a phone number, an insert into `Contact`, a title without "SAMPLE —"). The
    loader's and the guard's refusals each seen: a non-loopback host, the database `niord`, the SQL on another
    database, a marker not `applied`, "already loaded" (3). `check-no-sample.sh` green over niord-base's jar
    and test-jar, `niord-app`'s jar, the bootstrap zip and an exported `niord-app` image, red (exit 1) on a jar
    copy carrying the SQL directly, nested and renamed, and on image inputs holding a sample file. A throwaway
    Keycloak on `127.0.0.1:18180` with the new realm file gave each of the four users a token with its
    `example.org` address and its role in the five domains; it was removed, and `niord-keycloak` was not
    touched by it. On slot 7 (administration 4107, public site 4407, backend 8895), `./start-local.sh --sample`
    from scratch, exactly as README's first section says: up in 3m45s with the backend already built (about
    12 min when the slot's Maven repository is built first); the marker `applied`, `missing-paths` `[]`; the
    34 messages per domain and status as `messages.json`, every `en` and `da` title "SAMPLE —", 30 with
    geometry, the 4 attachments answering 200 `image/png` (direct and through 4107); the public messages
    through 4407 (13 of the 18 PUBLISHED; the five in the fa, almanac and annex series are not public), 13
    with geometry, and the public site's list rendering the 13 titles. A re-run started it
    again without loading (about 4 min); `--reset` dropped and reloaded only `niord_sample` (about 4.5 min; the
    database list the same before and after). That first slot-7 run loaded an earlier generation of
    `niord-sample.sql`, which differs from the committed one only in normalisation (`Message.areaSortOrder`
    rounded, the publications' derived ids). The review's check (2026-10-23) ran it again on the committed
    one: `./start-local.sh --sample --reset` on slot 7 up in 5m35s including the backend build; the marker
    `applied`, `missing-paths` `[]`; the 34 messages per domain and status as `messages.json`, 30 with
    geometry; the 4 PNGs answering 200 direct and through 4107; the 13 public messages through 4407, rendered
    by the public site. A re-run started it again in 3m17s; `--reset` took 6m28s, the database list
    unchanged. On those real runs: the `existing`-marker refusal, `--reset` refused while a backend was
    connected, and the guard's "no Niord schema" and "marker not applied" refusals. With a stand-in loader
    only: a loader failure stopping the run, and Ctrl+C (SIGTERM) freeing the three ports.
  - **After the review's fixes** (2026-10-23): `SampleSafetyCheckTest` 9 (with every picture up to the limit
    of 99 drawn inside the frame), `SampleFolderTest` 6 and `CoreResourcePackagingTest` 9, 0 failures. A
    regeneration (468 s) rebuilt the committed `niord-sample.sql` and `repo/` byte for byte, installing
    niord-base into `.local-run/sample-regen-m2` (the shared `~/.m2` jars untouched); its schema-check load
    copied the attachments before the SQL, and its second load copied them and answered 3. By hand on a
    throwaway `niord_sample`: a `--home` that is a file, exit 1 with no SQL run; "already loaded" (3) with
    the 4 files copied into an empty home. The three scripts are executable in the index (100755). The
    checker then repeated the launcher's slot-7 run on the fixed loader with README's command: up in 182 s
    (the backend already built), a re-run in 101 s, and `--sample --reset` in 165 s, each with the marker
    applied, `missing-paths` `[]` and every domain and status count matching `messages.json`; the
    regeneration twice more, 546 s and 515 s, byte for byte. Backend CI on PR #192 (run 39907609719, on
    `7a525456`) ran `be-ci.yml`'s no-sample step green: no sample in 4 artifacts, 96,029 entries read.
  - **After the review of #192** (2026-10-23, on `p9/review-192-fixes`; the backend's tests, the two
    regenerations and the hand checks of the scripts are in `docs/code-data-config-plan.md` §11 C7): the
    targeted unit run, 89 tests, red first (24 failures with the new tests against the old rules and files) and
    0 failures after the code and the regeneration; the bootstrap ITs, 22 tests, 0 failures; the regeneration
    twice, 406 s and 309 s, byte-equal. The launcher has no test harness, so its functions were checked by
    hand, extracted from `start-local.sh` with a stand-in `docker` on the PATH and the real `load-sample.sh`,
    each red on the launcher before the fixes and green after: the already-loaded path copies the 4 PNGs into
    an empty Niord home and takes the loader's 3 as success (before: none copied); against a stand-in server,
    a `failed` or `in-progress` marker marks the run as retried (`pending` does not), a 503 for the whole
    deadline ends with "the backend is still starting", and under `--sample` an unfinished bootstrap names
    `--reset`; a loader refusal after a retry names `--reset` only, one without a retry defers to the
    guard's remedy, and an unbootstrapped `niord_sample` names `--reset` where it said "retry"; a
    `JAVA_HOME` holding `bin/java` but no `bin/jar` is refused (before: accepted). On the real script, each
    before any container or process: `NIORD_HOME_DIR=/c/tmp/other ./start-local.sh --sample`, `--demo` and
    `--reset` alone exit 2, and a `NIORD_HOME_DIR` or checkout path with a space exits 1. `verify-local`'s
    no-sample block, extracted with a stand-in check: after a failed build it now reports "not run (build
    failed)" instead of a second failure, and an exit 2 after a green build still fails. `bash -n` on
    `start-local.sh` and `verify-local`, and `npm run docs:check`, pass. A check of those fixes found two
    more, fixed with a test each seen red first (C7's record): the regeneration now stops when `SampleTool
    counts` fails, and `load-sample.sh`'s usage names the cure from the repository root.
  - **After the review of #192** (Windows, 2026-10-23): `./verify-local --base origin/feature/p9-sample` on
    `8f7369b2` (the review's fixes with `main` merged in): PASS in 28m12s, the backend's 5165 unit tests and 3359
    ITs with only the 10 known Windows-only failures, the administration app, the public site and the Example
    country. A clean-machine approximation: a fresh clone of the branch from GitHub, a scratch home with an empty
    Maven repository but for GRAD's two artifacts (GRAD was down, `be/docs/potential-errors.md` BUILD1), no
    Niord home and no `node_modules`, then README's `./start-local.sh --sample` on slot 8 against the shared
    containers (`niord-keycloak` recreated by the new path, with the owner's go; its realm kept): up in 526 s,
    with 13 public messages, both sites answering, the 4 attachments, the local realm's `sysadmin` login and the
    five sample domains beside the inactive master. Run again with one attachment removed: up in 83 s, the
    attachments copied again, nothing loaded, and the hint naming `--reset`.
  - **Not yet run:** a loader failure stopping the run and Ctrl+C freeing the three ports, on a real loader;
    the administration app's map view in a browser (the map's data was checked by REST; nor the nine
    positions the review of #192 moved); a mail reaching MailHog (the setting was read, not exercised);
    `publish.yml`'s two no-sample steps, at the first publish after the merge; the regeneration's path that
    creates a missing container (since NG-7 through `start_services`, `--no-recreate`: it removes nothing; still not run). After the review of #192: `bootstrap.sh`'s retry
    of a 503 `POST` against a backend still starting; the `--reset` session count against a live autovacuum
    worker; Backend CI on Linux for the new tests; a run on a second, clean machine (no warm caches, no Keycloak
    volume, GRAD reachable), before the P8 guidelines point to the command (owner).
    Verification's "from `niord-dk`" (with the country launcher, P8) and the P8 pointer.
  - **Not built:** the country launcher (*the same in a country repository*): DK's and GL's launchers, P8's
    (the owner's ruling of 2026-10-23); the kit's launcher template is built since P8a (NG-10, §6 P8's country
    launcher, *As built*). What it needed: `load-sample.sh`'s interface (`--host` loopback, `--db
    niord_sample` or, since P8a (NG-8), `niord_sample_<cc>`, `--home`, and since C4b `--container`, default
    `niord-db`; exits 0, 1, 2, 3), and a way to start the country's backend and frontend variant instead of
    `niord-app`. Until NG-8 it also needed a way past the guard, which loaded the sample only on the base
    product's own bootstrap at the version it was made on, after a first start with `modelLanguages` at its
    default (a country launcher would have run the base product's bootstrap path first and switched its
    languages on after the load, or regenerated the sample on its own base); since NG-8 the guard no longer ties
    the sample to a base-only first start, and the sample loads on a country's backend after the base archive
    (§6 P8's country launcher, decision (c)). The published carrier is built in P8a (NG-9):
    `org.niord:niord-local-run:<v>:zip`, made by `be/scripts/local-run-zip.sh` and deployed by `publish.yml`
    with `deploy-file` beside `niord-image-tools` (`retract-version.yml` retracts it, the release notes name
    it). It holds `scripts/` (`bootstrap.sh`, the launcher library `local-run-lib.sh` beside it, which runs
    `bootstrap.sh` and the loader with the running bash since the zip keeps no exec bits, and `load-sample.sh`),
    `sample/` (`niord-sample.sql` and `repo/`, where `load-sample.sh` finds them as `../sample`; the
    regeneration's inputs stay out), `docker-compose.yml`, `keycloak/niord-realm-local.json`,
    `docs/country-api.md` and a `README.md` (a local run only, the known passwords, never an image input);
    files only, sorted, stored and at a fixed date, so two builds give the same bytes (`LocalRunZipTest`). The
    kit's template, `templates/start-local.sh`, which `init` writes, is built in P8a (NG-10): it calls
    `sample_reset` and `load-sample.sh` (through `sample_load`) with `niord_sample_<cc>`, and its local
    rehearsal on 4.0.999-p8a loaded the sample on DK's real backend with Danish on from the first start. What
    remains of *the same in a country repository* is DK's and GL's launchers from it, which DK-1 and GL-1 brought in
    `niord-dk` #11 and `niord-gl` #10 (a copy of the template, the root `.gitignore`'s `/.local-run/`, and GL's
    `start-local.json` naming `niord_gl_local`; the countries' copies not yet run). The zip's first real deploy comes
    at the merge into `main`: not yet run. `be/k8s/README.md`'s step 4 bootstrapped a local cluster with the demo
    archive until C4b; since C4b it uses the base product's archive only, and the sample stays local-only
    (`./start-local.sh --sample` into the loopback `niord_sample`).
  - **Found on the way:** `start-local.sh`'s `docker compose up` recreates the shared `niord-keycloak` when run
    from another checkout, because the realm file's bind-mount path differs (as before P9; seen once during the
    slot-7 runs, about 30 s down); an existing realm is not imported again, so the `example.org` addresses reach
    a running `niord-keycloak` only after `docker compose -f be/docker-compose.yml rm -sf niord-keycloak &&
    docker volume rm niord-keycloak-data` and a new start (`down -v` would also delete `niord-db-data`, which
    holds `niord`, `niord_test` and `niord_gl`). Fixed in P8a (NG-7): `be/docker-compose.yml` names its project
    `be` and mounts the realm from `~/.niord-local/keycloak/niord-realm-local.json`, which every launcher copies
    there first, and the launchers' `start_services` (`be/scripts/local-run-lib.sh`) starts only a missing or
    stopped shared container (`--no-recreate`) and issues no compose command while all three run; the first
    compose up that reconciles `niord-keycloak` after it recreates it once, its volume kept. The regeneration
    and the launcher both use `niord_sample` and must not run at the same time. In a launcher-built `niord_sample` the base category
    Firing Exercises keeps `treeSortOrder` 13 from the base-only tree (cosmetic; the trees sort by
    `siblingSortOrder`).

---

## 7. What changes in the rules and docs

P2a changed only the paths and file names the files below state (the bundles, flags and status lists that
moved, the staging step and its two npm scripts); P2b made the translation rules (the P2 items below) and named
the pack check, the catalog and their npm scripts wherever the removed specs were named.

- **`fe/CLAUDE.md`:** the "Country variation" rule (country code lives in the country repositories); the
  rule that only shared folders sit beside `ui-apps/` now names `languages/` (the `en` pack) and
  `country-kit/` — `languages/` (with `da` and `kl` until they move) and the generated `.niord/` from P2a; the commands block and the `paths:check` description; the webfont wording (`:25` bans
  glyph webfonts — kept, with the exception of a country's vendored text face). In P2b: the `languages:*`
  scripts in the commands block, the specs reading only the `en` pack, `npm run languages:check` among the
  guards, and the `dk`/`gl` builds checking first; its line contrasting the public site's Danish list with the
  `ai_` prefix goes, since both applications now list unaccepted Danish in the `da` pack.
- **`fe/ui-apps/admin/CLAUDE.md`, `fe/docs/definition-of-done.md`, changed in P2 and P5 (§6):** in P2 (P2b) the
  `ai_` prefix gives way to the pack's `status/unaccepted.json`: until P5 every key in `en.json` and in the in-tree
  `da` pack, an authored Danish value listed in `languages/da/status/unaccepted.json` until the product owner
  accepts it with `npm run languages:accept`, a renamed key named in `languages/en/renames.json` and moved with
  `npm run languages:migrate`, and the definition of done's parity row is `npm run languages:check` and the
  `dk`/`gl` builds. From P5 every key in `en` only, and Danish is no longer written in the feature's PR — it is
  `niord-dk`'s pack, held to the pack check; a key rename is named as one in the PR. The `niord-review` skill's
  translation-ownership and Danish-copy checks change with them (P2b: a key without its Danish, or on the public
  site its Kalaallisut; an authored value not listed; a rename not in `renames.json`). **Done in P5k:** both
  `CLAUDE.md` files, `fe/CLAUDE.md`, the definition of done, `angular-conventions.md`, `platform.md` and
  `fe/README.md` say a base product PR adds English only and writes no Danish; the in-tree `da` pack is a frozen
  copy that gains no key, whose findings the `dk`/`gl` builds and `languages:check` report as warnings
  (`--frozen da`), until P7 deleted it (P7f, #211, with the option, D14); a renamed key is named in `languages/en/renames.json` and in the PR, with no
  `languages:migrate` of `da`; a new public-site key still carries its Kalaallisut, listed as unaccepted, until P6;
  `languages:accept` is for `kl` (and `--app backend --bundle`). The `niord-review` skill flags a key added to `en`
  with Danish in the `da` copy, a public-site key without its Kalaallisut (until P6), an authored Kalaallisut value
  not listed and a rename not in `renames.json`. The `branding-unaccepted.json` sentences kept the base product's
  list for GL's entries until P7 (P7e, #209, rewrote them for the tree P7f, #211, left, which has no list); DK's are in `niord-dk`'s `fe/info/branding-unaccepted.json`, and GL's in
  `niord-gl`'s from its move (#4).
  `fe/docs/angular-conventions.md` and `fe/docs/platform.md` follow (the parity spec and the `ai_`/`da_` prefixes
  give way to the pack check and the status file); `fe/docs/backlog.md`'s Kalaallisut item keeps only what the
  status-file row does not settle (the branding list's go-live check, the translator's workbook).
- **`fe/ui-apps/info/CLAUDE.md`:** in P2, `kl-unverified.json` and `da-unaccepted.json` give way to the packs'
  `status/unaccepted.json` and, for the branding values and legal documents, `i18n-status/branding-unaccepted.json`
  (P2a; `fe/CLAUDE.md`'s line on them with it); in P2b the bundles are held to `en` by the pack check of
  `build:info:dk`/`gl` rather than by a spec, a new key's Danish and Kalaallisut go in their packs, listed as
  unaccepted, until P5 (`kl` P6), and the endonyms are `contents:check`'s; then the three bundles, the country-variation and language-tuning passages,
  the resolved-default fallback, `docker build --build-arg COUNTRY`, "the first and only structural
  separation guarantee", the icon rule's "never a webfont" (as above). The image command and the separation
  guarantee were done in P3c1: the release block builds with `npm run build:info:dk && npm run image:info -- --tag
niord-dk-info`, and separation is the separation check on every build (and `image:info`'s against the image)
  and lint; `fe/CLAUDE.md`, `fe/README.md` and `fe/docs/definition-of-done.md` followed. Since P7e (#209) the
  release block is the base build's manual commands (`npm run build:info && npm run image:info -- --tag
  niord-info`, then `test:container:info`, D32), a country releasing its image from its own repository.
- **The country API in the rules (P3b2):** `fe/CLAUDE.md` and both applications' `CLAUDE.md` say that DK's and
  GL's files under `country-specific/<cc>/` (since P7e, #209: a country's files, in its repository) import base product code only through `@niord/<app>/api` (and
  `/api/testing` in specs), never relatively out of their folder, with lint enforcing it; that a new need is a
  deliberate, commented export recorded in `country-kit/API-CHANGES.md`, a removal deprecated first; and that
  base product code never imports the API. `fe/docs/architecture.md` names each application's country API as
  its one deliberate barrel, for country code only.
- **The e2e suite's environment and the acceptance tests (P3c2, done):** `fe/CLAUDE.md`, `fe/README.md`, the root
  `README.md`, `fe/docs/definition-of-done.md`, `fe/docs/testing.md` and `fe/ui-apps/admin/e2e/auth/README.md` say
  that every run of the suite names its Keycloak in `KEYCLOAK_URL`, which has no default, and how a country
  workspace runs its own specs through `@niord/admin/e2e`; `fe/CLAUDE.md` and `fe/README.md` name the harness's
  acceptance steps; `fe/country-kit/README.md` gained the upgrade report, the end-to-end specs, check 9, `image:info`
  for a country without a legal document, `--accept-override`'s diff since the recorded fingerprint, the harness's
  new steps with `--upgrade-from` and the zip's e2e suite; `country-kit/API-CHANGES.md` the deprecation marker's end.
- **The kit's documents in P5k (done):** `fe/country-kit/README.md` gained publishing, pinning, withdrawing (with
  the by-hand withdrawal) and pruning against published versions, the canonical archive, the backend status
  section, the packs staged into `.niord/packs/<lang>/`, the country URL allow-list, the branding-unaccepted list,
  `init`'s new and removed files and the harness's registry steps; `country-kit/API-CHANGES.md` named the
  published 4.0.81 and opened an _Unreleased_ section saying what a country does at the bump (`pack.yml` copied,
  `pack-releases.md` and `fe/.gitattributes` added, `be/bootstrap/releases/README.md` deleted, the Maven build
  pointed at `fe/.niord/packs/<lang>/backend/`, foreign packs pinned with `--update-pins`); the stub is unchanged.
- **`fe/docs/country-deployments.md`:** trimmed in P7e (#209) to the base product's side, with pointers to the
  kit README and §5 (D9), not rewritten as the guide of §5, which is P8's in `fe/country-kit/docs/`; its
  `production,<cc>` recommendation, wrong since the standalone-configuration rule, went with its recipes.
- **`fe/README.md`** (P2b: the `languages:*` scripts, the `dk`/`gl` builds checking first, the public site's
  bundles held to `en` by the pack check); `transloco.config.ts` in P2 (the `en` pack's root path,
  `langs: ['en']`, its comment).
- **`fe/docs/country-deployments.md`'s language recipe** (P2b): the flag drawn as `img/flags/<lang>.svg` and
  rendered with the pinned command, the pack check in place of the parity spec.
- **`be/docs/cicd/implementation-plan.md`** Phase 8 and **`design.md`** §2.1 and §3 (registry): point here;
  npm → source zip in Phase 8, step E of the order of work, the phase table and the npm-overlay risk row (the Phase 8 note names them); the packs in the registry table (Phase 8's item for a backend scaffold for new countries exists since 2026-10-21, §5 step 1).
  In P4 `design.md` §3's registry table gained the zip (in place of the npm package), its release record
  (the sha256 in the notes of the release on `v4.0.<run>`, the zip as its asset) and the packs on GHCR, and
  its mechanics paragraph the zip; `README.md`'s CI row names the zip and the release, and Phase 8's npm item
  is checked as the zip. The remaining npm wording (`design.md` §2, §4's diagram, §5, Phase 8's other rows)
  stayed for P7 and was done in P7e (#209). In P8a (NG-13): the implementation plan's stale lines (the done
  boxes ticked, GL in `SIFA-DK/niord-gl` with `DK01`, the test cluster's bootstrap, past tense), `design.md`'s
  and `README.md`'s status lines and `intended-use.md`'s Scenario 1 (never automerged, E1/E2, two human acts
  per country; one since the owner's ruling of 2026-11-01 (the merge; the Dashboard approval dropped)).
- **Root `CLAUDE.md`:** the country-specific bullet (no frontend country folder in `niord-ng`); the "Run
  everything" bullet loses `--dk`/`--gl`. Done in P7d (#208) and P7e (#209), and in P8a (NG-13): the docs row
  completed, the country launcher named, the split recorded as done, the UTC rule beside the documented
  backend command.
- **Root `README.md`:** the local-run block (`start-local-dk.sh`, `start-local-gl.sh`, `--dk --slot 2`) and the
  sentence that the launcher runs the country frontend variants (`--dk`, `--gl`); both point to the country
  repositories (§5 step 9). Done in P7d (#208), and in P8a (NG-13): the docs row completed, the country
  launcher named, the split recorded as done, the UTC rule beside the documented backend command.
- **Each country's `README.md`:** `setup.mjs`, the local overrides, the pack workflow, the escape hatch; in
  P8a: DK's and GL's P8a changes (`niord-dk` #11 and `niord-gl` #10).
- **The guidelines of P8** (people, designers, AI agents, operators), in `fe/country-kit/docs/`: part 1 built in
  P8a (NG-11, #222), part 2 built in P8a (NG-12: the country guide, the operators' guide and the agent template,
  §6 P8's *The guides, part 2 (D1b), as built*); their routes run in NG-14 and their deploy parts verified at the
  deploy step (P8b). The review of #229 fixed (§6 P8, _The review of #229 (2026-10-26), as fixed_), and the
  guides move with the kit to `niord-country-kit` (§3.1, the owner's ruling of 2026-10-26).
- **niord-ng's other documents (P8a, NG-13):** `be/README.md` (the dangling Danish clause, the UTC rule, E4),
  `be/docs/test-infrastructure.md` (GL's two ITs), `docs/image-split-plan.md`'s end note,
  `docs/audio-promulgation-language-findings.md` moved to niord-gl, the five `until: P8` word-list entries made
  permanent. The `CLAUDE.md` sizes, measured on 2026-10-26 after NG-13's edits (characters as checked out, read
  by node, the root's and `be/`'s with CRLF / bytes, `wc -c`): the root 8,523 / 8,547 (8,018 / 8,042 before
  NG-13's docs row and launcher line), `be/CLAUDE.md` 17,841 / 17,875, `fe/CLAUDE.md` 27,060 / 27,158,
  `fe/ui-apps/admin/CLAUDE.md` 9,563 / 9,614, `fe/ui-apps/info/CLAUDE.md` 35,601 / 35,769 (89% of the about
  40,000 that Claude Code's `/doctor` flags, the nearest), `fe/country-kit/templates/CLAUDE.md` 12,867 /
  12,909 after NG-12 (616 as the placeholder; about 7% over the aim of about 12,000 that NG-12 set it). None is
  over the mark and none within 10% of it, so none was trimmed; the public site's is
  231 bytes short of the 10% band. Measured again at KM-8 (2026-11-01), as `AGENTS.md`, in the LF form git
  stores (characters read by node / bytes, `wc -c`; Git Bash's `wc -m` counts bytes unless `LC_ALL=C.UTF-8`):
  the root 8,747 / 8,767 (the process-start bullet moved to the root `CLAUDE.md`; 69 characters more with CRLF,
  as a Windows checkout has it), `be/` 17,642 / 17,676 (17,841 with CRLF, the form measured above), `fe/`
  27,440 / 27,540, `fe/ui-apps/admin/` 9,563 / 9,614, `fe/ui-apps/info/` 35,607 / 35,775 (89.0% of 40,000,
  still the nearest); the `CLAUDE.md` importers are 11 bytes, the root's 457 / 461.
- **`docs/code-data-config-plan.md`** owns the backend half: texts, settings, templates, the bootstrap and the
  sample's data; its rules go into `be/CLAUDE.md`, and the P8 guidelines repeat what a country carries there.

---

## 8. Risks and fallbacks

| Risk | Impact | Fallback |
|---|---|---|
| The real applications do not build from an unpacked base product (TypeScript includes, the test builder, aliases, Tailwind, a root file the kit missed) | P3/P4 fail | P3's example country finds it first, in the base product CI, before anything moves; P1–P2 stand on their own (clean builds, packs). |
| A country-only Tailwind utility missing | silent styling loss | the utility check (§4.8). |
| DK has not translated a base product version's new keys | DK: its bump is red until it translates them in that PR (§4.12); GL and any other user of `da`: no complete Danish for that version until DK's pack is out | by design (R4): DK translates in its bump PR; GL waits for DK's pack, lists the keys in its escape hatch, or writes the Danish as text overrides (§4.6 step 3). |
| GHCR or the home's own `pack.yml` is unavailable | a home cannot publish its pack | the home keeps building against its in-tree pack (home packs never need the registry); DK's first publish in P5, at the merge of its bump, proves the path (proven on 2026-10-24, `niord-dk`'s `Pack` run #1). |
| Actions minutes (GitHub Free, 2,000 min) | base product CI gains three country builds on a change to what the zip carries — the example, the untouched `init` and the upgrade test (§4.8), each of both applications, the example and the untouched `init` also their info images — as steps of one `Example country` job (P3c2): one checkout, Maven restore and zip install. Measured locally (a Windows workstation) the job's harness takes 9 min 45 s with the images, against about 4 min in P3c1; a real upgrade test (from P4, once a release exists) adds one more set-up and build of the example — measured locally in P4 with `--upgrade-from` a release-shaped zip, 12 min (720 s), about 2 min 15 s more, plus the release download (about 4 MB); the job's timeout stays 30 min, and its first CI timing comes on PR A (with the upgrade test skipped: no release yet); countries build their frontends | the base product stopped building the 2×2 country variants in P7, which cost about the same: P7d (#208) removed Frontend CI's DK and GL builds and Info CI's 14 country steps and its Chromium install. Measured on CI: before P7 (#196, heads `0165c8c4` / `9bb87e5d`) Frontend CI's Checks + builds 4m02s / 3m52s, Admin unit tests 2m59s / 2m48s, Example country 9m00s / 7m15s, Info CI's Checks + builds 3m03s / 3m19s; after (#204's heads `0409350e` / `a8db0b32` and the merge `c703d061`) Checks + builds 2m49s / 2m43s / 2m42s, Admin unit tests 3m06s / 2m11s / 2m30s, Example country 6m25s / 7m16s / 9m03s, Info CI 1m33s / 1m41s / 1m27s; Backend CI's unit 2m13s / 2m36s and integration 7m42s / 7m43s on the two heads (skipped on the merge, its fingerprint tested). So P7d saved about 1 min on Frontend CI's checks job and about 1.5 min on Info CI per run; the example job varies with the runner. A country builds on a change or a pin bump, not on every base product publish. |
| A writer of a private country repository obtains its Harbor credential (the `harbor` environment's `main`-only rule missing, or lost at a change of owner or plan) | a pushed image not built from `main` | the rule: the `harbor` environments of `niord-dk` and `niord-gl` allow deployments from `main` only, set and proven by a refused dispatch from another branch, as `niord-ng`'s already does (owner, 2026-10-21; §4.11 rule 1). Not yet: `gh api` on 2026-10-26 returns `deployment_branch_policy` null for both countries' `harbor` environments (`niord-ng`'s has its branch policy); until the rule is set and proven at the deploy step, on the owner's Harbor go, the countries' Image workflows are disabled (`disabled_manually`), so nothing pushes to Harbor: that is today's mitigation. Left once the rule holds is the security review's caveat, a recheck after any change of owner or plan; named writers only, Harbor tag immutability, the build/push split (§4.11). |
| The sample reaches a deployment | fake warnings shown to mariners | the sample is never in a jar or image (P9), checked in CI: `check-no-sample.sh` over the jars in Backend CI, over the jars and the image's inputs in `publish.yml` before anything is deployed or pushed (built in P9). |
| A home publishes a bad or compromised pack | wrong text or a malicious link in every consumer | digest pins, no automerge with a printed diff, the markup rule, the withdrawn marker on the pack's GHCR package (§4.5, §4.11). |
| Between P1 and P3a the `en` bundle loaded beside the active one | one more request per language load: the administration app's `en.json` is 187 KB raw, 43 KB gzipped (its service worker caches it after the first load), the public site's 3.2 KB raw, 1.3 KB gzipped (measured on the P1 builds, 1 KB = 1,000 bytes, gzip -9) | measured in P1; since P3a every build is staged complete from `en` (§3.2 step 4), the loader fetches only the requested bundle, and the runtime fallback language is gone. |
| The administration app's policy blocks something a deployment loads (a tile host it cannot parse, an image linked from another site, a new third-party source) | a blank tile layer or image, console errors | the e2e run under the policy (P1); the policy follows the tile settings at runtime and logs a warning for a value it cannot admit; another source is a base product change with its reason (§4.11 rule 5). |
| The base product guard's word list too strict or too loose | false alarms or a miss | an allow-list with a reason per entry, reviewed like the escape hatch (since P4 `country-kit/country-words.json`, over the zip, an unused entry failing); its backend half was drawn up by C5's scan of niord-base (`docs/code-data-config-plan.md` §11 C5), which niord-base's `CountryWordsTest` took over in P7-be (#206); since P7g (#212) the frontend list is also checked over the whole of `fe/` in tree mode, an unused entry failing there only (D16). |
| The OFL's Reserved Font Name | — | fontsource's files used unmodified, with `OFL.txt`; no subsetting of ours. |

---

## 9. Open checks

Not decisions — facts the implementation must establish, each in the phase named:

- P3: `npm ci` accepts the base product's lockfile at the root of a country workspace whose package name
  differs (otherwise the generator rewrites the lockfile's root entry). **Settled in P3a:** it does, both
  with the root name rewritten to `niord-xx-fe` and without the rewrite (the example workspace with the
  lockfile's root name put back to `niord-app`: `npm ci` exit 0). The generator rewrites it anyway, so the
  lockfile names the workspace it belongs to.
- P3: Tailwind's `@source` in the generated wrapper, resolved against the wrapper, reaches the country
  templates (the example's utility check proves it). **Settled in P3a:** the wrapper in
  `.niord/generated/info/styles.css` reaches the country's templates — the example's
  `sm:grid-cols-[3fr_2fr]` is in its built public-site CSS and in no base product CSS — and the imported
  base entry's own relative `@source` lines still resolve, because `@tailwindcss/postcss` 4.2.4 resolves an
  imported stylesheet's `@source` against the file it is written in (checked in isolation too; the
  example's public-site CSS is 51,421 bytes against the base build's 51,324, every base utility kept).
- P5: Renovate, installed in P5 on the SIFA-DK organisation as the hosted app, offers updates for the `<parent>` of `be/pom.xml` (Maven, reading the `NiordOrg/niord` registry with a read-only token) and for the packs' GHCR digests in `fe/niord-pins.lock.json` (docker datasource through a custom manager).
  **Deferred to the country split (owner, 2026-10-22):** Renovate is installed once the repositories are split
  out per country and their keys rotated; until then the `.github/renovate.json` the kit's `init` writes stays inert (its pattern already matches
  the lock's reference strings, P5k), and a pin bump is a hand-made, reviewed PR: a pack's pin with
  `node setup.mjs --update-pins`, the base product's `<parent>` by hand, as `niord-dk` #2 and `niord-gl` #2 were.
  **The owner's rulings of 2026-10-25 for the install:** never automerged (unchanged), a weekly schedule,
  security bumps at once, and a small workflow in each country that re-pins the lock's base pin on Renovate's
  branches (§4.11, §4.12). **By the owner's ruling of 2026-11-01** the Dependency Dashboard approval is dropped,
  and "green means no task" is required: the upgrade report on the bump PR, every action set-up cannot check a
  failing check or an unticked list in its description (§4.11). All are built before the install, in the
  Renovate step (2026-11-02, §6): `templates/renovate.json` with the weekly window (RN-2), `templates/base-bump.yml`
  with `init` and `CODEOWNERS` (RN-3) and the guides (RN-4); "green means no task" is the Base bump comment's
  `## Human actions` and the status `niord/upgrade-actions`, not the description, which Renovate rewrites; while
  niord-ng is private the workflow pins nothing and leaves the pin by hand (decisions 105 and 106). Open: the
  install (tracker F1, after M2), the credential `NIORD_PACKAGES_READ` (F2), F3's record (RN-5), the countries'
  copies (RN-6, RN-7) and DK's rehearsal. Since then F3's record (_RN-5 as built_) and the countries' copies (RN-6,
  RN-7, in DK-1 and GL-1: `niord-dk` #11, `niord-gl` #10) are done, and DK's rehearsal ran on 4.0.114 (`niord-dk`
  #13, 2026-11-02: green means no task; _NG-B as built_). Open: F1 and F2, the owner's. **Closed 2026-11-03:**
  decision 133 replaced the install (F1) and its credential (F2) by the bump by push, the Niord base bump app (§6
  _The bump by push_); no Renovate is installed, and the kit's template retires with F8-4. **Proved live
  2026-11-03:** #271 merged, the app opened `niord-dk` #15 and `niord-gl` #13 at 4.0.122, both merged the same
  day (§6 _The bump by push_, _As built_).
- P5: the SIFA-DK organisation allows public packages on GHCR, and a public OCI artifact of a private
  repository is pulled anonymously (otherwise packs wait for the repositories to go public, and DK/GL keep
  building from in-tree copies until then). **Still open after P5k and R190:** the kit's client, `pack.yml` and
  set-up's pull are proven against an in-process registry and a local, auth-less `registry:2.8.3` only. DK's
  first publish of `niord-lang-da` — the merge of its bump to a base version carrying P5k, a push changing
  `fe/packs/da/`, before which the owner checks the organisation's package setting — then GL's first pin, prove
  the rest: GHCR's acceptance of the manifest shape (a custom config media type, no `artifactType`; §4.5, _As
  built in P5k_); whether a manifest `PUT` answers `Docker-Content-Digest` (no longer required: without it the
  client confirms the manifest by a `HEAD` of its digest); a 404 rather than a 403 for a push-scoped `HEAD` of a
  package not created yet (the client treats anything but 200 and 404 as a failure); the organisation's
  package-creation setting for a workflow token; the upload `Location`'s form; the form of the tags list's
  pagination `Link` header (paging is now tested against a paging fake, GHCR's own form is not); the Bearer flow
  with a real token and the owner lower-cased from `SIFA-DK`; on a real runner _Which pack_, `sha256sum -c`, the
  client's hand-over and the publish job's re-verification; the package made public and its anonymous pull; and
  what GitHub Packages' generated `maven-metadata.xml` holds for `LATEST`: against a `file://` repository of two
  `deploy-file`'d versions, whose metadata carried `<release>` and `<versions>` and no `<latest>`, Maven still
  resolved `LATEST` (2026-10-23). DK's bump to 4.0.87 is built (P5e, `niord-dk` #6, 2026-10-23): _Which
  pack_, set-up, `pack.mjs validate` (archive `c4cac342…`, the same on Linux) and the client's hand-over ran by
  hand; the GitHub half came with the merge of `niord-dk` #7 (2026-10-24, `d6fa491`). **The publish side is
  settled by DK's `Pack` run #1** (2026-10-24): its validate job (with `latest-catalog` against GitHub Packages)
  and its publish job ran on a real runner, and it published
  `ghcr.io/sifa-dk/niord-lang-da:1@sha256:ded8424ce29393e6ae0e3bcb27bae99db2eaf384361bf6718d1865eacab17efd`
  after the empty `:withdrawn` marker; the owner made the package public, and an anonymous pull answers 200 for
  both tags. GL's first pin is made (P6e, 2026-10-24): `node setup.mjs --update-pins da=1` pulled the public
  `ghcr.io/sifa-dk/niord-lang-da:1` anonymously and verified its manifest and archive by digest
  (`sha256:ded8424c…7efd`; archive `c4cac342…7e4deb`, equal to DK's `validate` archive of P5e); set-up reads the
  withdrawn marker before anything else of the pack (the log does not print it). The pull side, the package
  made public and its anonymous pull, is settled too.
- P5, P6: the `niord-dk` and `niord-gl` backends' resources re-counted against the pinned `niord-base` (on
  2026-10-20: 70 in DK, 46 of them shadows; 131 in GL, 75 shadows; classified in `docs/code-data-config-plan.md`
  §9). **Settled by C6 (2026-10-22, against 4.0.76):** DK moved its 56 templates, 42 shadows and 14 own, with
  `git mv`, unchanged; GL dropped 22 (19 equal to the base's, 3 lagging base fixes) and moved 86, 46 shadows
  and 40 own, unchanged but for `fetch-firing-exercises.js`'s line 19 (that plan's §9.1, §9.2, §11 C6).

---

## 10. Why: what the builds ship today

All six production builds on `main` @ `6a9c0923`, their output file lists read (analysis, 2026-10-19); the
last column is the same six builds after P1.

| Build | Ships, not needed (6a9c0923) | After P1 |
|---|---|---|
| admin default | all of `assets/gl/` (GL logos, the `kl` flag); `da.json` (197 KB) and the `da` flag; the DEMA logos and footer wording | gone: English only (`en.json`, the `en` flag), no `assets/branding/` or `assets/legal/`, no footer row; the header shows the Niord mark |
| admin DK | all of `assets/gl/` | gone: the base input ignores `assets/gl/**`; the DEMA logos, footer texts and legal documents come from DK's overlay (`assets/dk/` → `assets/branding/`, `assets/legal/`) |
| admin GL | the DEMA logos and the Niord mark twice (the GL overlay's four files are byte-identical to the base's: four output paths with two sources) | gone: one source per output; the logos only under `assets/branding/` from GL's overlay, one `niord_white.png` (the base product's). GL's branding and legal documents are byte-identical copies of DK's, GL's own until it supplies its own (§4.4) |
| info default | `da.json`, `kl.json`, the `da` flag | gone: English only |
| info DK | `kl.json`; the unused `branding/logo.png`, `logo-da.png` | gone |
| info GL | DK's branding and legal texts (12 of 12 files byte-identical); the unused `branding/logo.png`, `logo-da.png` | `logo.png` / `logo-da.png` gone. The branding and legal copies were needed after all: they render GL's footer and legal dialogs, and they stay as GL's own copies until it supplies its own (§4.4) |
| all | admin `flags/README.txt`; info `kl-unverified.json`, `da-unaccepted.json` (read only by specs) | gone: every administration asset input ignores `README.txt`, kept beside the flags as their attribution; the two lists live in `ui-apps/info/i18n-status/` |

Causes at 6a9c0923: the administration app's base `assets` had no `ignore` and `production` / `production-dk`
did not override it (`fe/angular.json:25-29`, `:135-161`, `:207-224`); the public site copied every language
file into every build; CI checked each country's expected markers and only the other country's time zone
(`info-ci.yml:252-284`), not the files a build must not carry. The code half — `fileReplacements` and the
style swap — was clean in every build. Since P1, `fe/scripts/build-contents-check.mjs` (`npm run contents:check`)
holds every build configuration of both applications to the files its language configuration and its own
country overlay name — one source per output, the bundles equal to the UI languages — in Frontend CI, Info CI
and `verify-local`, and `fe/scripts/build-urls-check.mjs` (`npm run urls:check`) holds each built `dist` to no
external font or style URL.

---

## 11. Review record

Every review of this plan, oldest first. Each point was checked against the tree before it was accepted or
rejected.

- **Three reviews before the PR (2026-10-19):** a fact check, a new-country walkthrough and a design critique,
  against the tree and the installed Angular builder; folded into the first version (fe06e31f).
- **The PR review (2026-10-19):** five inline findings and two general points, all accepted in 3445e63b: a
  public API per application, enforced by lint, with a compatibility policy (§4.3); country-owned npm
  dependencies (R12); overrides fingerprinting every file they were copied from (§4.9); pack publishing
  without the base product's credential; the sample as its own dataset (P9); the five translation layers and
  text overrides (§4.10); the new-country acceptance tests (§4.8). Its answer on pack publishing (a central
  workflow) was replaced the same day by R13. A second review of the same day asked for no change.
- **The security review (2026-10-19):** five areas, 23 findings (4 high, 9 medium, 10 low), each verified by
  an attempt to refute it; folded in as §4.11 in 9d04a791, with the owner's decisions. Its verdict: the plan
  does not weaken a running deployment.
- **The performance review (2026-10-20), of both plans:** no slowdown on any path that matters; wording
  added so an implementation cannot get slower by accident (eac8361a).
- **kaspernielsen, 2026-10-20 (10 points):** nine fixed in 20e9abbd; the tenth, R14 resting on an undecided
  plan with no fallback written down, in 5b886d2a with §2.1. Three of its answers were changed by the next
  round's rulings: R14 is decided (ruling 1), a content-only language falls back to the default language,
  not English (ruling 4), and the runtime `en` fallback goes in P3 instead of staying as an unused safety
  net (ruling 5).
- **kaspernielsen, 2026-10-20 evening (106 points on 5b886d2a):** across this plan,
  `docs/code-data-config-plan.md`, `be/docs/potential-errors.md`, `be/docs/cicd/implementation-plan.md` and
  the root `CLAUDE.md`; fixed 2026-10-21 in 2419e5c7. None rejected: 95 accepted, as suggested or with one of
  the options offered, 3 in part and 8 with another fix. The review also replied on four threads of
  2026-10-19 whose answers the plan had overtaken, and made two points on the PR's description and on the
  author's comment of 2026-10-20; those concern the PR, not this document.

**The owner's rulings of 2026-10-21**, which decided ten of the points:

1. `docs/code-data-config-plan.md` is adopted. R14 is decided and this plan follows the path with it; §2.1
   keeps the "without" column only as the record of the alternative weighed.
2. `adminIntegrationPageEnabled` is declared in niord-base as a real setting (`web`, default `false`): an
   ordinary operator key, editable on the Settings page.
3. An internal writer creates and updates `state` rows (the trees' last-update dates); the public set behind
   `PUT /setting/{key}` and the settings import refuse `state` keys and unknown keys.
4. A content-only language with no backend half yet is served by the deployment's default language, then
   any language, as the backend does today — not English. It cannot be the backend's `defaultLanguage` until
   its backend half exists. No backend code change.
5. From P3 a build ships exactly the manifest's `uiLanguages` (`en.json` only when `en` is offered) and the
   runtime `en` fallback goes; staging still fills every bundle's missing keys from `en`. R9 holds without
   exception.
6. The first `niord-lang-da` carries DK's five `*_da.properties` as its backend half from its first publish,
   copied in P5's frontend part; DK's Maven build reads its own copies until P5's backend part. No exception
   in the check.
7. A country's pom stays `be/pom.xml` with `be/mvnw`: no root pom, `image.yml` unchanged.
8. Renovate is the one bump bot, for the `<parent>` and the pack digests, installed on the SIFA-DK
   organisation as the hosted app in P5 and never automerged; Dependabot does security updates only.
   Deferred to the country split (owner, 2026-10-22; §9). Weekly, security bumps at once, and a re-pin workflow
   in each country (the owner, 2026-10-25; §4.11, §4.12); the Dashboard approval dropped (the owner, 2026-11-01;
   §4.11). Superseded by the bump by push (the owner, 2026-11-03, decision 133; §6 _The bump by push_): the
   Niord base bump app opens or moves each country's bump PR at every publish, never automerged; Dependabot does
   security updates only.
9. The `harbor` environments of `niord-dk` and `niord-gl` allow only `main`, set in P5 and P6 and proven by a
   refused dispatch from another branch; the accepted risk shrinks to the security review's recheck caveat.
   Not yet: Harbor is not touched at this time (owner, 2026-10-22; §4.11 rule 1).
10. P9's sample carries its own DK rows under `be/niord/sample/` as Danish sample content (R11); nothing is
    fetched from `niord-dk`, and drift from DK's bootstrap is accepted.

**Points on this plan not taken as suggested** (numbered in the review's posting order; those on the other
files are in the review record of `docs/code-data-config-plan.md`):

- Point 33, `--demo` between C4 and P9: not a local changelog that loads the unchanged seed, whose SQL is
  only in niord-base's test-jar from C4, beside stub beans that would replace real ones; `--demo` runs the
  bootstrap command with the base product's archive and then `be/niord/seed/niord-dev-basedata.zip`, until
  P9's `--sample` replaces it.
- Point 44, the catalog's backend section: no order between C2 and P2 is needed; `catalog` reads whichever
  English backend bundles the checkout has, so whichever of the two lands second changes one path.
- Point 48, the dates of the decisions: with the rulings of 2026-10-21 the decisions span 2026-10-19 to
  2026-10-21, and the status line, §2's heading and `CLAUDE.md` say so.
- Point 66, the validate job: its two scopes are named and it reads the latest catalog, the first option,
  because §4.6 step 2 needs the latest; but the zip is fetched with `dependency:copy`, not unpacked, and the
  home's pinned kit reads only that zip's `catalog.json`, as data, so no unpinned code runs.
- Point 78, the build report: written by `setup.mjs` at §3.2 step 4, not by a Maven step or the override
  check; the backend overrides come from `be/overrides.json`, which the override check holds complete.
- Point 88, the first pin: the sha256 is recorded in the notes of a GitHub release on the tag, not in the
  tag, which `publish.yml` keeps lightweight for its gate; beyond the comparisons of §5 steps 2 and 4 the
  first pin is trust on first use, accepted until attestations.
- Point 98, the first `niord-lang-da` without a backend half: the second option, settled by ruling 6, but
  the bundles are copied, not moved: DK's Maven build reads its own copies until P5's backend part; P6 does
  the same for `kl`.
