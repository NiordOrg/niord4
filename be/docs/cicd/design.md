# Niord CI/CD & GitOps Deployment Design

**Status:** agreed design 2026-07-07; built in part (2026-10-26): Phase 1's split, the versioned publishing (§3), the source zip and the language packs, the countries' CI, Pack and Image workflows (Image disabled until the deploy step), DK and GL staging on the test cluster with pins moved by hand, each with the public site on a host of its own since 2026-11-10; the countries' bump by the Niord base bump app being built (2026-11-03); no Renovate installed; prod not started — see [`README.md`](README.md) · **Revised:** 2026-09-22, re-based on the monorepo (§2.1) and the deploy-repo rehearsal; 2026-09-24, one version pins chart and app image (§3), env values hold only overrides (§6), a complete stack and namespace per country (§6); 2026-09-26, namespaces named like the hostnames, `niord-<cc>-<env>` (§6); 2026-10, the deployable images get a reproducible dependency layer and the country pipelines call the image scripts rather than copy them (§2.1, `docs/image-split-plan.md`); 2026-10-03, the images are named `niord/niord-app-<country>` (§2); 2026-10-06, Phase 1 read against the tree: the frontend stays whole in `niord` until Phase 8 and `niord` publishes the administration app's country bundles beside `niord-base` (§2.1, §3), the country poms inherit `niord-parent` (§2.1), the organisation's GitHub plan makes `niord` public at the split (§5, §11); 2026-10-25, the country bumps are never automerged: weekly, security at once, the base after Dependency Dashboard approval, and a re-pin workflow on Renovate's branches (§4); 2026-10-26, the frontend left `niord` in the country split's P5–P7 as a source zip a country builds from (not an npm package), and the base product is country-unaware since its P7 (4.0.97) (§2.1, §3); 2026-11-01, the Dependency Dashboard approval dropped: weekly, security at once, never automerged, and a green bump PR leaves only review, merge and deploy (§4); 2026-11-03, the countries' bump is the Niord base bump app's pull request, opened or moved at every publish, never automerged: §2 principle 2, §4, §5, §8
**Scope:** CI, artifact flow, and Kubernetes deployment for the Spring-ported Niord stack serving Denmark (DK) and Greenland (GL), with room for ad-hoc test environments.

---

## 1. Goals and principles

The problem being solved: today a fix in the generic backend requires a chain of manual steps — release the Java library, bump the version downstream, rebuild the country application, release it, deploy it. The same chain exists for the frontend. With two countries this multiplies.

The design rests on five principles:

1. **Every merge is a release candidate.** No human "release" ceremony exists in the internal flow. Every merge to `main` in the generic repo publishes real, versioned, fully-tested artifacts.
2. **One propagation mechanism per leg.** *(Rewritten 2026-11-03; until then "one propagation mechanism everywhere": Renovate moving Maven jars, language packs, image tags and chart versions through the whole system.)* The countries' leg is the Niord base bump app's pull request, opened or moved by the base product's publish run minutes after each release, never automerged, with the pinned language packs moved in the same pull request (§4); the deploy repository's leg is as §4 records (Renovate decided against for GitLab on 2026-10-03; the staging pins move by hand).
3. **Git is the deployment interface.** Argo CD applies what is in the deploy repo. Deploying is merging; promoting is a PR; rolling back is `git revert`. The audit trail is git history.
4. **An environment is a directory.** Adding `envs/<name>/` to the deploy repo creates a full stack (app + its own Postgres/Keycloak) in a new namespace; deleting the directory tears it down.
5. **A version that exists passed everything.** Artifacts are published only after the full test suite (including integration tests) is green, so downstream automation never has to wonder whether a version is safe.

Net effect on the original pain:

| Scenario | Before | After |
|---|---|---|
| Fix bug in generic backend | release library → bump DK → build DK → release DK → deploy (×2 countries) | **1 merge**; bots do the rest; on staging in ~20–40 min |
| Fix bug in DK backend | build → release → deploy | **1 merge**; on DK staging in ~10–15 min |
| Deploy to production | manual per component | **1 button** per country (a PR whose merge is the deploy) |
| New test environment | manual setup | **1 PR** (copy a template directory) |

---

## 2. Repositories

| Repo | Visibility | Contents | Publishes |
|---|---|---|---|
| `niord` — `NiordOrg/niord4` at the move, public (decided 2026-11-17; `NiordOrg/niord` until then, 2026-10-06, §11), the earlier private repository until then | public (OSS) | `be/` Maven modules — the `niord-base` library and the generic English deployable `niord-app` — + `fe/` Angular 22 workspace with both applications, country-unaware | jars and the frontend source zip (Maven), Helm charts (OCI) — one shared version per merge |
| `niord-dk` — `SIFA-DK/niord-dk` | public¹ | `be/` + `fe/` — DK's frontend built from the source zip with the country kit | container images `niord-app-dk` (the deployable: backend + its build of the administration app) and, for the public site, `niord-dk-info` |
| `niord-gl` — `SIFA-DK/niord-gl` | public¹ | `be/` + `fe/` — GL's frontend built from the source zip with the country kit | container images `niord-app-gl` and `niord-gl-info` |
| `niord-deploy` | **private** | per-environment Helm values, sealed secrets, promote job, runbooks (§6) — exists as **`devops-niord`** on the host's GitLab | nothing (Argo watches it) |
| `platform-shared` | **private** | the Argo CD machinery — root app-of-apps, the AppProjects, both ApplicationSets, per cluster — plus the cluster-shared platform services (Traefik on test, monitoring later) and the bootstrap and sealing-key runbooks (§6) — exists as **`devops-shared`** on the same GitLab | nothing (Argo watches it) |

¹ Niord is an open source product used by several countries; `niord-dk` and `niord-gl` are SIFA's two installations of it, built on the published product the way any adopter's are. They are separate repositories for that reason alone (corrected 2026-10-06; until then this note said "political reasons"): the one repository exists because the Spring port and the frontend rewrite were easier in one tree, and it is private as a workshop, not as a policy. Making SIFA's installations public ("people can see how it is run") is intended and **pending a security audit** of their content — see §11. They always track the same generic version (enforced by automation, §4).

**Why a fifth repo.** The first draft of this design put the Argo CD machinery inside `niord-deploy`. The rehearsal split it out on 2026-08-13 (deploy-repo phase-0 answer A3): the AppProject that fences the deploy repo's generated Applications must not live in the repo it fences, and the platform services serve staging *and* prod so their changes need admin-owned review rather than the deploy repo's CODEOWNERS. The platform repo's niord ApplicationSet reads the deploy repo's `envs/`; adding or removing an environment there needs no change here. Everything in §6 that says "`argocd/` and `platform/` in `niord-deploy`" is now the platform repo.

**Names and host, as of 2026-09-22.** The two private repos live on the host's self-hosted GitLab, `git.sitcloud.dk`, group `cu3504-mssb-brs`, as **`devops-shared`** (platform) and **`devops-niord`** (deploy) since 2026-09-17; the GitHub sandboxes `platform-shared` and `niord-devops` are archived. The design's names `platform-shared` / `niord-deploy` are kept in this document as roles. The registry is Harbor, `reg.sitcloud.dk`, one project `cu3504-mssb-brs` shared by both clusters (images keep their upstream path, charts under `charts/`). There are **two clusters**, test (staging + every `test-<name>`) and prod (prod only), each with its own Argo CD and Sealed Secrets controller; the mapping is written into the ApplicationSets under `argocd/<cluster>/`, not into the deploy repo. The test cluster is bootstrapped; prod is not.

All three code repos were one repository, this one, until the split: the backends left on 2026-10-06 (step B) and the frontends in the country split's P5 and P6 (2026-10-22/23); §2.1 is the mapping as it was applied.

### 2.1 From the monorepo to the target repos

What in the monorepo went where. The country layers existed as directories, so DK and GL split at the same time — the original plan's "GL as Phase 5, a copy of the DK shape" is superseded.

| In the monorepo until the split | Target repo | Note |
|---|---|---|
| `be/niord/` — `org.niord.base:niord-base` (core, web, model, shared IT harness) | `niord` `be/` | the published jar every country pins |
| `be/niord-app/` — `org.niord.app:niord-app`, the generic English deployable | `niord` `be/` | **decided 2026-09-22:** `niord` publishes it as a generic image, `niord/niord-app-be:4.0.<run>` (renamed 2026-10-03 to `niord/niord-app-en`, see `docs/image-split-plan.md` step 2) in Harbor (`publish.yml`), for demo and ephemeral environments and outside adopters; no jar in GitHub Packages |
| `be/pom.xml` — reactor parent `org.niord:niord-parent`, version `${revision}` (default `4.0.0-SNAPSHOT`, flatten-maven-plugin for the installed poms) — *done 2026-09-22* | `niord` `be/` | **decided 2026-10-06 (reversing "instead of inheriting the parent"):** a country pom names `org.niord:niord-parent:4.0.<run>` as its parent (empty `relativePath`, resolved from GitHub Packages) and `niord-base` at `${project.parent.version}`, so one literal moves per bump. Only that resolves the dependency set `niord-base` was tested with — the Jackson overrides, the retained library versions, the repositories (`docs/image-split-plan.md` §7.2.2); the `<modules>` list of a parent read from a repository is ignored. `maven.deploy.skip` stays on the deployables. `github.repository` in the parent is `NiordOrg/niord` since 2026-10-06 and does not change at the move |
| `be/niord-dk/` — `org.niord.dk:niord-dk` | `niord-dk` `be/` | moved 2026-10-06 (step B) |
| `be/niord-gl/` — `org.niord.gl:niord-gl` (was under the DK group id until 2026-09-22) | `niord-gl` `be/` | moved 2026-10-06 (step B) |
| `fe/`, the base product (country-unaware since the country split's P7) | `niord` `fe/` | published as the source zip `niord-fe-src`; `fe/` is one Angular workspace with two applications, the administration app (`fe/ui-apps/admin/`) and the public site (`fe/ui-apps/info/`), and the shared `fe/styles/`, `fe/shared-map/` and `fe/shared-html/` |
| *History:* DK's frontend — its folders under both applications' `country-specific/`, its build configurations in `fe/angular.json`, `fe/styles/tokens.dk.css` and the public site's DK asset overlay | **`niord-dk` `fe/`**: moved in the country split's P5 (2026-10-22, [`docs/fe-country-split-plan.md`](../../../docs/fe-country-split-plan.md)) and deleted from the base product's repository in its P7 (2026-10-06 had settled `niord` `fe/` in Phase 1 and the country's in Phase 8) | `niord-dk` builds its frontend from the source zip `niord-fe-src` with the country kit. The administration app's bundles reached the country images through GitHub Packages beside `niord-base` — **built 2026-10-06:** `org.niord.web:niord-web-admin:4.0.<run>`, a pom and a zip per deployable under its classifier, each with `index.html` at the archive root, the classifier `en` only since P7 and `en`/`dk`/`gl` in versions before it; a country pipeline unpacked its bundle at the base version it pinned (`maven-dependency-plugin`) and handed the directory to `publish-images.sh` as `WEB_DIR`, until it built its own bundle from the source zip. The `tag` job's prune to the newest 20 versions, for the organisation's 500 MB of private Packages, went with the change of registry (§5); `retract-version.yml` removes one version by hand |
| *History:* GL's frontend — the same set for GL, with `fe/styles/tokens.gl.css`, the administration app's GL asset overlay and the public site's | **`niord-gl` `fe/`**: moved in the country split's P6 (2026-10-23) and deleted from the base product's repository in its P7 | the same |
| `fe/ui-apps/info/Dockerfile`, its allow-list `Dockerfile.dockerignore` and `nginx/` — the public site's container, packaging a built `dist`, one image per country (`niord-<cc>-info`), built, tested and pushed by each country's image pipeline from its own build (`npm run image:info`, the country kit's `templates/image.yml`) | `niord` keeps them, and they travel in the source zip; the base product's repository packages the base build by hand only (`npm run image:info -- --tag niord-info`) and publishes no image of the public site. The base chart's `info` component runs a country's image under its app image's tag (since 4.0.151; [`docs/public-site-deploy-plan.md`](../../../docs/public-site-deploy-plan.md)) | the public site's images are the one frontend image that exists; the FE image story (§11) is about the administration app |
| `be/niord/scripts/regen-test-seed-postgres.sh` — the IT fixture (`niord-base-data.sql`, in the test-jar) | `niord` | since C4b (`docs/code-data-config-plan.md` §7.4) it builds the fixture from `be/niord/bootstrap/` and `be/niord/sample/`: the suite's own `NiordTestApplication` applies the base product's archive through the bootstrap runner, `load-sample.sh` loads the sample after it, and no deployable is involved (until 2026-10-06 the script booted the DK deployable on its base-data archive). `be/niord/seed/` (the demo archive and its `niord.json`) was deleted in C4b. The sample holds areas, charts, categories, domains, series, publications, sources, mailing-list *definitions* and messages written for it, with `example.org` addresses only |
| `be/k8s/helm/niord/` — one chart, six components behind enable flags (the sixth, `info`, the public site, off by default), Harbor-ready images, no subcharts, versioned with the release | `niord` (chart source, published as OCI) | one chart rendered as app-only and infra-only releases, not separate app and infra charts (decided 2026-09-22); the remaining seams from the deploy repo's open issue 1 land in Phase 3 |
| `be/k8s/docker/Dockerfile`, `scripts/split-jar.sh`, `scripts/publish-images.sh`, `scripts/build-image.sh` — parameterised on the module (`--module niord-gl`), *done 2026-09-22*; the third-party jars in a reproducible layer of their own, *done 2026-10* (`docs/image-split-plan.md`) | `niord` keeps them (they build any deployable). A country pipeline **calls** `split-jar.sh` and `publish-images.sh` of the base version it pins and does not copy them: the dependency layer's bytes come from the tree the script makes and the Dockerfile's `COPY`, so a drifted copy stores a 158 MB layer of its own with no failing step. **Since 2026-10-06 the four files travel as the package `org.niord:niord-image-tools:4.0.<run>` (zip) in `NiordOrg/niord`'s registry**, published by the build job beside `niord-base`; the country pipeline unpacks it, as it unpacks its bundle. Since the code/data/config plan's C5b the zip also carries the backend override check (`scripts/check-overrides.sh` with `scripts/CheckOverrides.java`), which a country pipeline runs over its jar after its package step ([`country-api.md`](../country-api.md), Overrides). (Until then the plan was a checkout of `be/k8s` at the tag — which a country repository cannot make while the product repository is private without a credential of it.) `build-image.sh` is the monorepo's local build and gets a country variant then. | the same jars need the same dependency management: only a country pom that inherits `niord-parent` at the pinned base version resolves `niord-base`'s dependency set (image plan §7.2) |
| `be/k8s/README.md`, `be/k8s/scripts/secom-*`, `secom-test-client/` | `niord` (operational reference; the deploy repo vendors a snapshot under `reference/`) | |
| `.github/workflows/be-ci.yml`, `fe-ci.yml`, `info-ci.yml`, `publish.yml`, `retract-version.yml` | `niord` (the publish leg of §5 is `publish.yml`); the country repos get the country pipeline | `info-ci.yml` is the public site's CI, beside `fe-ci.yml` for the administration app |
| `be/docker-compose.yml`, `start-local*.sh`, `be/dev`, `be/test-it` | `niord`; the country repos need the cross-repo variant of the local loop (§3) | |
| `be/docs/cicd/` | `niord` — the owner of these documents (README) | |

**Rules that make public country repos safe (public code, private environment):**

- Country repos contain code and *sample* config only — zero real hostnames, IPs, credentials, or environment values.
- Everything environment-specific lives in private `niord-deploy`: Helm values, domains, sizing, secret material (sealed).
- GitHub secret scanning + push protection enabled on all public repos; CI logs are treated as public output.
- Known and accepted: a security fix is publicly visible on merge, before prod runs it. This is already true of the generic OSS repo today; fast staging→prod promotion keeps the window small.

**`niord-info`** is no longer a repository of its own: it is the public site in `fe/ui-apps/info/` (since 2026-09-22); its country parts are the country repositories' since the country split's P5 and P6, like the administration app's (the rows above), and its two upstream repositories are archived at its cut-over. **`niord-proxy`** continues as-is (a generic repo); if its country-specific parts are pure config, it needs no country code at all — a generic image + per-country values in `niord-deploy`. If code, it becomes a directory in `niord-dk`/`niord-gl` — never a new repo.

**Host constraints (verified):** the K8S host mandates a private repo (expected to be, or to constrain, `niord-deploy` — platform and bot-write access still to be confirmed, §11) and requires all images to be pulled through the **host's registry**. Namespace creation is self-service. Argo CD is installed and owned by us.

---

## 3. Versioning and artifacts

**Scheme:** `4.0.<ci-run-number>` — monotonic, sortable, bot-friendly, traceable (every publish tags the commit `v4.0.<run>`). In this repository the run number plus 181, so its run 1 is 4.0.182, after 4.0.181, the last version the earlier private repository published (decided 2026-11-17; implementation plan, step D).

**Mechanics in `niord`:** Maven CI-friendly versioning — root pom declares `<version>${revision}</version>` with default `<revision>4.0.0-SNAPSHOT</revision>`, so local development is unchanged; CI publishes with `-Drevision=4.0.<run>`. In place since 2026-09-22 (`be/pom.xml`, with flatten-maven-plugin `resolveCiFriendliesOnly` so installed poms carry the literal version; `run.sh`/`start-local.sh` read the default through `NIORD_VERSION`). The frontend source zip (`niord-fe-src`) and the Helm chart are stamped with the same version in the same pipeline run, so **one version number identifies a compatible BE + FE + chart set**. The chart's appVersion is that same number and is the generic image's default tag. **Since the repository split (2026-10-06) an environment pins two numbers per country:** the chart version — the product's base version — and `app.image.tag`, the image the country repository built on that base, tagged `<base>.<run>` (e.g. `4.0.59.1`; the chart version alone names no country image any more). The deploy repo's promote copies both and refuses a tag whose first three components differ from the chart version (`scripts/promote.py`). The alternative kept in view: a small chart per country that wraps this one and carries its image tag as appVersion, which would return the deploy repo to one pin. A BE-only change still bumps the shared version (country FE images rebuild harmlessly); this lockstep is deliberate — it makes FE/BE skew structurally impossible.

**Where artifacts go:**

| Artifact | Registry | Consumers |
|---|---|---|
| jars, the administration app's bundle (`niord-web-admin`, zip, classifier `en`; 2026-10-06; versions before the country split's P7 also carry `dk` and `gl`), the image tools (`niord-image-tools`, zip; 2026-10-06), the local-run files `org.niord:niord-local-run` (zip, every publish from P8a, NG-9: the launcher library, `bootstrap.sh`, `load-sample.sh` with the sample, the compose file and the local realm, built by `be/scripts/local-run-zip.sh`; a country's local run fetches it; never an image input; versions before the kit move's KM-3 also carry `country-api.md`), the base product's frontend source zip `org.niord.web:niord-fe-src` (zip, every publish from P4; country set-ups fetch it by `dependency:copy`, [`docs/fe-country-split-plan.md`](../../../docs/fe-country-split-plan.md) §3.1–§3.2), the country kit `org.niord:niord-country-kit` (zip, every publish from the kit move's KM-3: the kit's set-up, guides, templates, data files and example country as `country-kit/kit-include.json` selects them, the backend contract `docs/country-api.md` copied from `be/docs/country-api.md`, its one published home, and the marker `kit.json`; built and checked by `country-kit/source-zip.mjs --package kit`, with its include list, the import-closure check and the country-word guard; attached to the release with its own sha256 line) | GitHub Packages | country repo CI. **Published to the registry of `NiordOrg/niord` since 2026-10-06, not to this repository's.** The Maven registry is repository-scoped (checked against GitHub's documentation after the first country run saw the parent as absent): a package inherits the permissions of the repository that published it, there is no per-package access grant for Maven (unlike the container and npm registries), so packages of a private repository are readable by no other repository's workflow token, and a classic PAT in every consumer was the alternative. Packages of the public `NiordOrg/niord` are readable with any token — the country repositories' own `GITHUB_TOKEN` included — **confirmed 2026-10-06: `niord-dk` and `niord-gl` resolved `niord-parent` and `niord-base` 4.0.56 from it with their own tokens and ran their suites green** — and cost no storage; developers still need a one-time token with `read:packages` in `settings.xml`. The publish writes with a machine user's publish-only token, `NIORD_PUBLISH_TOKEN` (the user in the variable `NIORD_PUBLISH_USER`), of the `publish` environment, and `retract-version.yml` deletes every one of these packages, `niord-local-run` and `niord-country-kit` included, with its delete-only `NIORD_DELETE_TOKEN` of the `retract` environment; both environments, like `harbor`, allow deployments from `main` only, and both tokens are rotated at the repository move and yearly. Versions up to 4.0.54 are in this repository's registry |
| the source zip's and the kit's sha256 records and a copy of each zip | the GitHub release on `v4.0.<run>` in the base product's repository (notes line `<sha256>  niord-fe-src-<version>.zip`, from KM-3 a second line `<sha256>  niord-country-kit-<version>.zip`, each checked in its own folder, then from P8a a sentence naming the version's Maven packages, not in that shape, which a release made by hand from a failure summary leaves out, as no reader needs it; both zips as assets; made by the tag job with the workflow token after the tag) | a country's first pin (§5 step 2 of [`docs/fe-country-split-plan.md`](../../../docs/fe-country-split-plan.md)) and Frontend CI's upgrade test, which reads the asset with its own `contents: read` token |
| language packs `niord-lang-<lang>` | public GHCR, `ghcr.io/<owner>/niord-lang-<lang>:<n>` (`<owner>` the home country's GitHub owner, `sifa-dk` for DK and GL), each published by its home country's `pack.yml` with that repository's own token (R13; the first, `niord-lang-da`, in P5) | every country naming the pack, pinned by digest, read anonymously |
| container images, OCI Helm chart | **host registry** (mandated) | the cluster / Argo CD; Renovate watches it for staging bumps |
| curated OSS releases (occasional, e.g. `4.1.0`) | Maven Central | outside Niord adopters — trivial to cut, since every build is already release-grade; a "release" is a curated tag |
| chart (optional mirror) | public ghcr.io | the community — supports the "see how it's run" goal |

**Local cross-repo development:** `mvn install -Drevision=local` in `niord`, point the country build at `local`; the frontend side is a country's set-up against a locally built source zip (`country-kit/README.md`, *Local sources*). The one ergonomic tax of splitting the current monorepo.

---

## 4. Propagation: bump pull requests

*Since 2026-11-03 the countries' leg is the Niord base bump app: the `bump` job of the base product's `publish.yml` opens `base-bump/<version>` or moves the one open app PR in each country that installed the app (being built as F8, [`docs/fe-country-split-plan.md`](../../../docs/fe-country-split-plan.md) §4.11 rule 6 and §4.12; the reasons in [`docs/renovate-plan.md`](../../../docs/renovate-plan.md) §0b). The deploy repository's leg is as recorded below (Renovate decided against for GitLab, 2026-10-03). The json5 sketch at the end is the 2026-07-07 design; its first rule no longer applies. Until 2026-11-03 this section was "Renovate everywhere".*

Two legs move a version:

```
niord main merge ──publish──▶ GH Packages 4.0.126 + chart/images → host registry
        │
        ├─▶ the Niord base bump app's PR in niord-dk "Base bump to 4.0.126" ─ never automerged
        │        (minutes after the publish; moved to each newer one)
        ├─▶ the Niord base bump app's PR in niord-gl (same)                ─ never automerged
        │        (minutes after the publish; moved to each newer one)
        │        └─▶ the country's Base bump workflow, then country CI builds images → host registry
        │
        └─▶ Renovate PR in niord-deploy envs/staging/*  (chart pins) ─ auto-merge
                 (as designed; by hand since 2026-10-03)
                 └─▶ Argo CD syncs staging
```

- **Bump-PRs, not direct triggers:** each country repo carries an exact, auditable record of which generic version it runs, and a generic version that breaks a country's tests **never lands** — that country stays one version behind with a red PR explaining why.
- **Never automerged, at every publish** (the owner, 2026-10-19, 2026-10-25, 2026-11-01 and 2026-11-03): a person merges a country's bump PR. The app opens it at every publish and moves the open one to each newer version, so a country's Base bump workflow and CI run after every publish (the cost, measured on DK's bump PR as wall time and estimated in billed minutes: §5); the review and the merge are the human gate. Until 2026-11-03 the cadence was weekly, security bumps at once. **Green means no task:** the upgrade report is posted on the PR, every needed action is a failing check or an unticked box in the Base bump workflow's comment, with the status `niord/upgrade-actions` red while one is open (the app rewrites the description), and a release folder's import stays a human decision (the Base bump workflow built 2026-11-02; `docs/fe-country-split-plan.md` §4.11).
- **The lock's base pin** (the owner, 2026-10-25): the app's `<parent>` PR moves the pom but not the lock's base pin `{version, sha256}` in `fe/niord-pins.lock.json`, which set-up then refuses, so each country's Base bump workflow commits the pin on the `base-bump/` branch before set-up runs, once release v<version>'s notes confirm the hashes, and moves the pinned language packs to each home's newest pack in the same commit (built 2026-11-02; the pack refresh with F8; `docs/fe-country-split-plan.md` §4.11, §4.12).
- **The "DK == GL same generic version" invariant** is checked at promote time (§7), not kept by simultaneous bumps: with a person's review and merge in each country, the two may run different versions for a while.
- Renovate is configured to **never touch `envs/prod/**`** — the promote workflow is the only writer to prod values.
- Renovate handles all four ecosystems here (Maven via GH Packages, the frontend source zip included; the language packs on GHCR, as OCI; docker tags in Helm values against the host registry; OCI chart versions) with the same package-rules file. Run it as the hosted GitHub App if `niord-deploy` is on GitHub, or self-hosted (cron, every ~5 min) if the host repo is on another platform. **Decided 2026-10-03: not self-hosted against the GitLab repos for now.** With two pins per repo, platform bumps that start with a hand mirror anyway, and the country repos not yet split, the GitLab leg of the chain is a two-line merge request by hand; the plan, its costs and the alternative (`publish.yml` moving the staging pins itself) are in [`docs/renovate-plan.md`](../../../docs/renovate-plan.md) at the repository root. The countries' leg above is the app's since 2026-11-03.

Illustrative package rules (sketch):

```json5
{
  "packageRules": [
    { "matchRepositories": ["niord-dk", "niord-gl"],
      "matchPackagePatterns": ["^org\\.niord"],
      "automerge": false, "schedule": ["on monday"] },   // weekly; security bumps at once
    { "matchRepositories": ["niord-deploy"],
      "matchFileNames": ["envs/staging/**"], "automerge": true },
    { "matchRepositories": ["niord-deploy"],
      "matchFileNames": ["envs/prod/**"], "enabled": false },
    { "matchRepositories": ["niord-deploy"],          // platform serves staging AND prod:
      "matchFileNames": ["platform/**"], "automerge": false }  // PRs yes, auto-merge never
  ]
}
```

---

## 5. CI pipelines per repo

| Repo | On pull request | On merge to `main` |
|---|---|---|
| `niord` | units (minutes) | **full suite incl. integration tests, sharded 6–8 ways** (Testcontainers Postgres/PostGIS + Keycloak; GitHub-hosted runners run Docker fine) → publish jars + the source zip + chart + tag. Test-then-publish: a published version has passed everything. |
| `niord-dk` / `niord-gl` | country units + **full country IT suite** — this same check must be green before a person merges the base bump pull request; the app's PRs mean nobody waits on it | build + push images to host registry |
| `niord-deploy` | lint values, `helm template` dry-run, sealed-secret schema check | (Argo CD picks up the merge) |

- **Public repos ⇒ free GitHub Actions minutes**, which is why sharding the IT suite and running full country suites on every bump-PR costs nothing. *Checked 2026-10-06:* the SIFA-DK organisation is on GitHub Free — 2,000 Actions minutes a month, 0.5 GB of Actions storage and 500 MB of Packages storage, all shared by its private repositories and all free for public ones (secret scanning and push protection are public-only there too). The base product's repository is private, which is why its CI is as thrifty as the README describes. **Decided 2026-10-06: the three code repositories stay private until the split is complete**, and go public together afterwards (the audit, §11, comes before that step). Until then the design's cost assumption does not hold: `main` took 62 merges in the 30 days to 2026-10-06 (28 in one week), each already costing Backend CI plus a publish, and a private country repository that runs its ITs and its image build on every bump adds ~7 minutes per country per merge — ~15 minutes a merge, ~900 a month at that rate, on top of what is spent today. So, while private: the country pipelines run **on demand, not per publish** (decided 2026-10-06 — a dispatch with the base version; staging's pins move by hand until the repositories are public, implementation plan, *The order of work*), the packages go to the public `NiordOrg/niord` registry, where they cost nothing (§3; the prune went with the change of registry), and the alternative of GitHub Team (3,000 minutes, 2 GB of Packages, environments on private repositories, a few dollars a seat) is kept in view. *Revisited 2026-11-03: each publish now opens or moves each country's bump pull request, which starts its Base bump workflow and CI; while the base product's repository is private that is about 4 to 5 billed minutes per country per move (estimated from the wall times of DK's bump PR, 2026-11-02: the Base bump workflow 27 s to 1 min 17 s, CI 35 s on the unpinned lock), about 380 a month for two countries at about 38 releases a month, and about 14 per move once it is public (estimated; the full CI, 4 min 50 s wall, at every move); the image still waits for the merge.*
- **Break-glass path:** a manual `workflow_dispatch` in `niord` publishes after units only (clearly labeled, ITs still run and report async), from `main` only. For the "prod is down" day; not for routine use.
- **A publish that fails in the GitHub Packages upload** (seen 2026-11-04, during a GitHub incident: 4.0.129, 4.0.131, 4.0.133 and 4.0.134 stopped on a 409 or a 500 on the first file whose acknowledgement was lost, the file itself stored) leaves a partial version in Packages and no release, tag, images or bump; nothing downstream sees it. A retry under the same number meets its own leftover file and 409s for real, so the recovery is a fresh number: the next merge's publish, or the break-glass dispatch once GitHub's status page reads clear (4.0.135 that day, which carried the four failed merges' content). The partial versions stay as harmless leftovers.
- **Expected latency, machine time only** (without the merge of §4): merge in `niord` → both country stagings updated in **~20–40 min** (PR feedback itself stays at minutes — units). Country-only fix → its staging in ~10–15 min. If measured latency grates in practice, the fallback is publish-after-units with the IT result gating *promotion* instead — a workflow-order change, not an architecture change.

---

## 6. Cluster, Argo CD, and the deploy repo

**Topology:** two clusters (test: staging and every `test-<name>`; prod: prod only); **one namespace per environment and country** (`niord-dk-staging`, `niord-gl-staging`, `niord-dk-prod`, `niord-gl-prod`, `niord-test-<name>`), named like the environment's hostname — country first, then environment (`niord-dk-staging` serves `niord-dk-staging.<test domain>`; decided 2026-09-26). **Each country runs a complete stack of its own** — app, nginx proxy, Postgres, Keycloak with its own realm, and MailHog outside prod — as one release of the chart (decided 2026-09-24). In-cluster Postgres per country for now. The move to the host's managed PostgreSQL, the "database hotel", is a separate project ([`../../../docs/repo-files-in-database-plan.md`](../../../docs/repo-files-in-database-plan.md), D11). It is more than a values change: it needs that plan's chart seams (§4.9 there: an external database and secret for Keycloak, JDBC URL parameters such as `sslmode`, a mounted CA), a role of its own for each application, and a dump and restore of each database. Since 4.0.146 the database holds every durable byte, the repository files included, so the hotel's backup will be the whole backup. Rejected: **shared infrastructure per environment** — one Postgres with a database per country and one Keycloak and realm for both, which the chart was taught first and still supports. It needs an infra release, host settings between releases, a Keycloak that derives its public address per request to serve two origins, one login page, password policy and mail sender for both countries, and one database superuser for both apps (a user per country was thought blocked, because the base-data seed disabled triggers, which needs a superuser; the seed runs only in local and test environments since the code/data/config plan's C1, so that premise no longer holds). Also rejected: a shared Postgres with a Keycloak per country (keeps most of that for little saving); a schema per country (the Liquibase changelogs and the seed name the `public` schema throughout). Revisited 2026-11-03: **one Keycloak per cluster, shared by both countries, with a realm per country** (`niord-dk`, `niord-gl`; devops-shared `platform/test/keycloak/`, the deploy repo's `runbooks/keycloak-realm.md`). Postgres stays per country. The realm per country keeps what the per-environment Keycloak gave: separate users, one site per `niord-web` client; the chart's `/auth` route goes when Keycloak is on another origin, and the app learns its Keycloak from the backend at start (`GET /rest/settings/keycloak`). The cost accepted: two of everything per environment — instances, backups, upgrades, sealed secrets, realms — which GitOps turns into twice the files rather than twice the work. Argo CD installed and owned by us; prod and staging both auto-sync — **git is the gate, not the Argo UI**.

**Namespace inventory.** Environment namespaces plus three platform namespaces; exactly two components in the cluster are ever installed by hand — the **bootstrap pair**:

| Namespace | Runs | Managed by |
|---|---|---|
| `argocd` | Argo CD | **hand-installed at bootstrap** (with the bootstrap secrets, §8) |
| `sealed-secrets` | Sealed Secrets controller | **hand-installed at bootstrap** — deliberately *not* Argo-managed, see below |
| `monitoring` | shared observability stack — kube-prometheus-stack (Prometheus + Grafana + Alertmanager; §11) — one instance scraping every `niord-*` namespace | Argo, from `platform/` |
| `niord-<cc>-staging`, `niord-<cc>-prod` | permanent environments, one namespace per country (`dk`, `gl`): that country's complete stack (app, proxy, Postgres, Keycloak; MailHog outside prod) | Argo, from `envs/staging/<cc>/`, `envs/prod/<cc>/` |
| `niord-test-<name>` | ephemeral environments | Argo, from `envs/test-<name>/` — created on merge, pruned on directory delete |

Names are upstream defaults, no `niord-` prefix: the cluster is operationally ours (Argo's CRDs are cluster-scoped anyway) and defaults keep tooling frictionless — with one known trap: `kubeseal` assumes the controller lives in `kube-system`, so seal with `--controller-namespace sealed-secrets`. Cluster machinery we do **not** own — storage classes, the registry — is the host's, lives in host-managed namespaces, and has no entry in `niord-deploy`. The ingress controller turned out to be ours on the test cluster: Traefik, a `platform/` entry in `devops-shared` since 2026-09-26, serving only Ingresses of class `traefik` (the chart's `proxy.ingress`). TLS issuance on test is ours too, so cert-manager becomes one more `platform/` entry, same pattern. Prod will sit behind the host's HTTPS offloader (§11).

**Repo layout.** As first drafted (one deploy repo); the rehearsal moved `argocd/` and `platform/` into the platform repo (§2), added `platform/reloader/` — one cluster-shared stakater Reloader instead of the per-release subchart the chart bundled until 0.4.0 (removed again on 2026-10-14: SECOM Edition 2 has no TLS listener on the proxy, so nothing used it) — and, with two clusters, one directory per cluster. `envs/` is unchanged; this is what exists in `devops-shared` and `devops-niord` today (staging/prod parked under `reference/pending/` until the chart can deploy them):

```
devops-shared/                                    (the design's platform-shared; was: the top of niord-deploy)
  argocd/<cluster>/        # test/ and prod/ — each cluster's Argo CD runs only its own directory
    root.yaml              # app-of-apps, applied once by hand at bootstrap
    niord-project.yaml     # AppProject — the fence: sources, namespaces, cluster resources
    niord-appset.yaml      # ApplicationSet — git *files* generator over devops-niord's envs/*/*/config.yaml,
                           #   globbed per cluster (staging + test-* here, prod there)
    platform-project.yaml, platform-appset.yaml   # the same pair for platform/<cluster>/
    argocd-cmd-params-cm.yaml, and on test argocd-cm.yaml and argocd-server-ingress.yaml
  platform/<cluster>/      # cluster-shared services we own — Argo-managed like any env component
        traefik/         config.yaml  values.yaml  *.sealed.yaml   (test cluster only)

  (monitoring is not in the repo yet: it is added under platform/<cluster>/ when its chart is chosen; cert-manager later, if TLS is ours — §11)
devops-niord/                                     (the design's niord-deploy)
  envs/
    _template/             # copy-me for new environments (tokens for name/host)
    staging/
      dk/     config.yaml  values.yaml  *.sealed.yaml   # DK's whole stack in niord-dk-staging; chart pin
                                                         #   (config.yaml) — Renovate auto-bumps; the app image follows it
      gl/     config.yaml  values.yaml  *.sealed.yaml   # GL's whole stack in niord-gl-staging
    prod/
      dk/     config.yaml  values.yaml  *.sealed.yaml   # pins written ONLY by the promote workflow
      gl/     config.yaml  values.yaml  *.sealed.yaml
    test-<name>/ ...                        # ad-hoc, short-lived
```

- **ApplicationSet mechanics:** a git **files** generator over `envs/*/*/config.yaml` and `platform/*/config.yaml` (two path patterns, still one generator) yields one Argo `Application` per environment × country (`staging-dk`, `staging-gl`, `prod-dk`, …) plus one per platform service. Each tiny `config.yaml` declares the component, chart + pinned chart version, target namespace, and sync policy; `CreateNamespace=true` gives spin-up-by-PR. One generator, no matrix gymnastics.
- **Apps are multi-source:** chart from the host registry (OCI), values from `niord-deploy`. The chart itself is public; only values are private.
- **Values files hold only overrides.** Argo CD renders the pinned chart plus the values from scratch on every sync — there is no `helm upgrade --reuse-values` to defeat — so a values file states what differs from the chart defaults: hostnames, the image repository, enable flags and host seams, sizing, secret references. Two kinds of value are spelled out anyway: switches that must never follow a changed default (`secom.enabled`) and each country's Postgres/Keycloak tags, pinned so a database or identity-server upgrade is a deliberate, promoted change.
- **Per-country apps** give independent health/sync status per country. A version bump moves only the app image; Postgres and Keycloak restart only when their pinned tags or their chart templates change, and those changes show in the promote diff.
- Staging: auto-sync + self-heal + prune. Prod: auto-sync too — protection lives in git (branch protection on `envs/prod/**`).
- **Platform services ride the same rails:** the monitoring stack is just another Application synced from `platform/` — its registry pull secret ships as a SealedSecret in the same directory, and its chart-version bumps arrive as Renovate PRs like everything else — but `platform/**` never auto-merges: one shared instance serves staging *and* prod, so platform changes get prod-grade review (§4).
- **The Sealed Secrets controller is deliberately *not* Argo-managed.** Everything git delivers — env and platform alike — may contain SealedSecrets, so the controller must precede git twice over: its own registry pull secret could not be sealed before it runs, and apps could sync `SealedSecret` CRs before the CRD exists (Argo fails dry-run on an unknown kind unless `SkipDryRunOnMissingResource` is set — bootstrap ordering beats sync-option archaeology). Hence the bootstrap pair, in order: install Argo CD → install the controller (CRD included) → apply the bootstrap secrets (§8) → apply `root.yaml`; from there, git owns everything. The host-registry mandate covers these platform images too (Argo, the controller, Grafana/Prometheus) — mirror or pull-through, verify which (§11).
- **What `monitoring` watches:** the failure classes CI can't — Argo apps Degraded/OutOfSync, crash-looping pods, PVC fill (the Postgres volumes hold every durable byte since 4.0.146; the app's volume is a cache that can fill too), a failed or missing backup CronJob (§9) — with alerts routed into the same channel as the two routine deploy signals (staging synced / bump failed), so "normally, nothing" stays true. App-level metrics are a follow-on, not a blocker: the BE already serves Spring Actuator under `/rest/actuator`, and a Prometheus scrape endpoint is one dependency away (`micrometer-registry-prometheus`).

**Host-repo contingency:** if bots can write to the host-mandated repo, `niord-deploy` lives there (Renovate self-hosted supports GitLab/ADO/etc.). If not, source of truth moves to GitHub and a one-way mirror job pushes to the host repo; Argo watches whichever end the host prefers. Verification item, §11. *Status 2026-09-22:* both repos are on the host's GitLab and the test cluster's Argo CD already reads them there, so the mirror fallback now means moving Argo's source of truth again — it is a last resort. The open part is narrow: whether a bot account may hold Maintainer on a protected branch (H1).

---

## 7. Environments: permanent, ephemeral, and the promote button

**Ephemeral environments — spin-up is one PR:** copy `envs/_template/` to `envs/test-nw-import/`, pin the chart version (a specific build — the app image follows it; test envs are **pinned by default** — you're usually testing a specific thing; opting into Renovate tracking is a flag), merge. The stack comes up in namespace `niord-test-nw-import` with its own Postgres and Keycloak, and boots with a usable schema (Liquibase is always on) and no data: a new database is bootstrapped once with `be/scripts/bootstrap.sh` after the first sync ([`../../../docs/code-data-config-plan.md`](../../../docs/code-data-config-plan.md) §7.4; a Helm hook is a later option). The chart no longer has a seed switch since that plan's C4.1; a values file that still sets `postgres.seed.enabled` sets a value nothing reads (the GitLab values files are read when that plan comes to staging, at its end). Ingress via wildcard DNS: `*.brs-test.e-navigation.dk` points at Traefik, and the env's host is `niord-test-nw-import.brs-test.e-navigation.dk` — one level under the domain, because a wildcard covers one level only; each env's own Keycloak avoids redirect-URI juggling. Tear-down = delete the directory. Optional later: country CI publishes `pr-123` images so an env can run an unmerged branch. Feature-branch builds on staging and test environments, and a promote of a named main version, are planned in [`docs/feature-branch-deploy-plan.md`](../../../docs/feature-branch-deploy-plan.md) (owner, 2026-10-23).

**The promote button (per country, whole stack):** a `workflow_dispatch` in `niord-deploy` with input `country: dk | gl`:

1. Reads what staging currently runs for that country — the chart version under `envs/staging/<country>/`, the country's app image tag (a build on that chart's base version, §3) and the country's Postgres and Keycloak tags.
2. Copies them into `envs/prod/<country>/`.
3. Opens a PR — *"Promote DK to prod: 4.0.125 → 4.0.131"* — whose diff **is** the release note, with compare-links into the `niord`/`niord-dk` commit ranges appended.
4. **Merging the PR is the deploy.** Argo syncs prod within seconds. Who may merge = branch protection on `envs/prod/**`.

Preconditions the workflow enforces (refuse or loudly warn): staging apps Healthy/Synced for that country; DK and GL staging on the same generic version; *(later, slot reserved)* the staging smoke test green for exactly these tags.

Whole-stack promotion means prod only ever runs a BE+FE+proxy combination that has soaked together on staging.

**Rollback = `git revert` of the promote PR.** This works because of the roll-forward-only migration policy — which imposes one discipline on the team: **schema changes must stay backward-compatible for one version window** (expand/contract migrations), so version N−1 code runs against version N schema during rollout and after a revert.

**Staging smoke test (deferred, slot reserved):** ~10 HTTP probes per country against the *deployed* staging (login page renders, `/rest/public/v1/messages` returns 200 JSON, a search returns results) after each sync. Catches the failure class no test suite can: broken values, missing secret, bad migration against real data volume, Keycloak misconfig. Until it exists, the gate is "a human looked at staging". Prerequisite to check: staging reachable from GitHub runners (§11).

---

## 8. Secrets

Three buckets:

1. **CI/bot tokens** (registry robot account, the bump app's private key and id on the `publish` environment of the base product's repository, promote-workflow token) — GitHub Actions secrets on the GitHub side. Never in-cluster.
2. **Bootstrap secrets** (Argo CD's credential for `niord-deploy`; registry pull credentials for the bootstrap pair's own images — Argo CD and the Sealed Secrets controller, §6) — applied by hand once at bootstrap; runbook'd, not automated.
3. **In-cluster app/env secrets** — per environment and country: Postgres superuser, the app's own database user (the owner of its database and not a superuser, on both stagings since 2026-11-12; `docs/database-user-plan.md`), SMTP credentials, image pull secret (~4 items × env × country). The Keycloak admin is the shared Keycloak's, in devops-shared, since 2026-11. The realm is no sealed secret: the chart renders it and a platform admin uploads it once (2026-11-03).

**Decision: Sealed Secrets** (no existing secret store in the org/at the host). The controller holds a cluster keypair; secrets are encrypted locally with `kubeseal` and the ciphertext is committed under `envs/<env>/`; the controller decrypts to native `Secret`s in-cluster.

- Shared **test-tier credentials** sealed with the `cluster-wide` scope annotation and baked into `envs/_template/`, so ephemeral envs stay one-PR with zero secret ceremony. (Native namespace-bound scoping stays the rule for staging/prod.)
- **Runbook, non-negotiable:** back up the sealing key offline — a cluster rebuild without it loses every secret. Rotation = re-seal + commit.
- **Upgrade path:** External Secrets Operator the moment the host/org provides a real store (likely alongside the "database hotel") — swap SealedSecret CRs for ExternalSecret CRs in `niord-deploy`; nothing else moves. (SOPS was ruled out: it's the Flux-native option and perpetually fiddly under Argo.)
- Ask the host whether **etcd encryption at rest** is enabled (should be, regardless of the choice above).
- Sidenote for whenever the in-cluster Postgres setup is next touched: an operator like CloudNativePG auto-generates per-instance DB credentials as native Secrets, removing DB passwords from this problem entirely.

Kubernetes' built-in `Secret` is delivery, not management — base64 is encoding, not encryption, and plain Secrets in git or hand-created ones outside git would break the GitOps model (and ephemeral envs) outright.

---

## 9. Prod snapshots & backups *(designed — closure deferred, see §11)*

Key move: **the backup job and the snapshot feature are the same thing.**

- **Producer:** a CronJob in each country's prod namespace (`niord-dk-prod`, `niord-gl-prod`) runs nightly `pg_dump -Fc` → object storage, retention N days. This is also the DR story. Since 4.0.146 the dump is the whole backup: the repository files are rows of the database (`RepoFile`), and the app's volume is a cache that nobody backs up ([`../../../docs/repo-files-in-database-plan.md`](../../../docs/repo-files-in-database-plan.md)). The CronJob is the interim backup until the separate hotel project moves the database to the host's managed PostgreSQL; the host's backup then replaces it, and the consumer's dump comes from the host restoring into a second database, or from a dump job (that plan's §4.9).
- **Consumer:** a per-env values flag —

  ```yaml
  db:
    seed: snapshot      # default "base" = the synthetic Liquibase seed
    snapshot: latest    # or a dated key
  ```

  The env's init Job restores the dump before the app starts. Because Liquibase is always-on and roll-forward-only, **a snapshot from older prod code restores fine — the app boots and Liquibase upgrades the schema to match the code under test.** That is exactly "test this migration against real data before promoting", for free.
- **Mandatory scrub on every non-prod restore.** Niord keeps real credentials *inside* the database (the settings `wmsPassword` and `pdfEncryptionPassword`, and the Twitter API keys and tokens of the `TwitterSettings` rows; SMTP is chart configuration, `smtp.host`/`smtp.port`, not database data) and mailing lists contain personal email addresses. `scrub.sql` nulls credentials, anonymizes emails, and points mail at the env's MailHog. Hard rails: the restore Job **refuses to run in the prod env**, and **fails if the scrub script is missing** rather than restoring unscrubbed.
- **Attachments are DB rows since 4.0.146** (the `RepoFile` table), so a snapshot carries them: a snapshot env serves the prod attachments, publication files and archived editions, and draws its thumbnails and map images again on first view. The file mirror this bullet once offered (`files: mirror`) is not needed.
- **Staging refresh** (staging DB is long-lived): the same restore Job, on demand only. The stop and the start are merge requests (`app.replicaCount: 0`, then the line removed), because staging syncs with self-heal and undoes a `kubectl scale`; the deploy repo's `runbooks/repo-files-cutover.md` stops and starts staging the same way.
- Search indexes rebuild from the DB at startup; no special handling.

---

## 10. End-to-end walkthroughs

*A plain-language, stakeholder-oriented version of these scenarios lives in `intended-use.md`.*

**Generic BE bugfix → production.** PR to `niord` (units, minutes) → merge → sharded full suite → `4.0.126` published (~10–15 min) → the Niord base bump app's PRs in `niord-dk` + `niord-gl`, minutes after the publish, merged by a person after their IT suites (~10–15 min; §4) → images pushed → staging values bumped → Argo syncs both stagings (~5 min). *~20–40 min total, without the waits for the merges.* Verify on staging → press promote per country → review the diff-as-release-note → merge → prod. Rollback if needed: revert the promote PR.

**DK-only fix.** Merge in `niord-dk` → image → DK staging in ~10–15 min. One manual step total.

**Spin up an env to test a migration against prod-like data.** Copy `_template/` → set `db.seed: snapshot` + the chart version to test → PR → merge → full stack up with scrubbed prod data, schema rolled forward by Liquibase. Delete the directory when done.

**Prod is on fire.** Break-glass publish in `niord` after units (~5 min) → bumps/images (~10 min) → verify on staging → promote. ITs report async; the promote diff shows exactly what moves.

---

## 11. Open items & host verification checklist

**To verify with the K8S host** (blocking for build-out sequencing, not for the design):

- [ ] Which platform hosts the mandated private repo, and can a bot/service account open **and merge** PRs/MRs there? (Fallback if not: GitHub source-of-truth + one-way mirror.)
- [x] Can GitHub Actions push to the host registry from outside (robot account)? Yes — verified 2026-09-22 (`publish.yml`, images `4.0.1`).
- [ ] Is etcd encryption at rest enabled?
- [ ] Is S3-compatible object storage available (for backups/snapshots — else MinIO in-cluster)? What is the prod backup story today?
- [ ] Will staging be reachable from GitHub-hosted runners (needed for the future smoke test), or is an in-cluster runner / allowlist entry needed?
- [ ] Who terminates TLS / issues certificates for our hostnames — host ingress, or us? *(Ours → cert-manager joins `platform/`, §6.)* *(Test cluster answered 2026-09-26: us — Traefik ends TLS, cert-manager to follow. Prod still open: the host's HTTPS offloader.)*
- [ ] Can the host registry proxy/cache upstream images (pull-through), or must we push mirrors? Applies to the platform images too: Argo CD, Sealed Secrets controller, Traefik, Prometheus/Grafana.
- [ ] Does the cluster CNI enforce `NetworkPolicy`, and is a host-imposed default-deny already in place between namespaces? *(Added 2026-08-13 by the deploy repo, H8: without enforcement the NetworkPolicy the split chart ships is silently inert; with a default-deny, monitoring scrapes and host ingress need allowlisting.)*
- [ ] Does the host provide an outbound SMTP relay (sender domain, SPF/DKIM), or do we bring our own? *(Added 2026-08-13, H9: prod only — staging and test envs sink mail into MailHog; the relay needs chart values the local chart does not have, and the production realm's `smtpServer` block.)* *(Since 2026-11-18 the chart can name a login and TLS for the relay, `smtp.existingSecret` and `smtp.tls`, inert when unset; whether production needs them is still the host's answer.)*

The same list, with answers as they arrive, is `devops-niord/docs/phase-0-answers.md` (H1–H9, D1–D4, A1–A3). On 2026-09-22 each of H1–H9 got a *Known internally* line from an audit of the two repos and the test-cluster bootstrap, and a narrowed *Still to ask*; the host has answered none of them yet. Two findings from that audit change this design's premises: the test cluster had **no ingress or Gateway controller** (Tanzu gives `type=LoadBalancer` Services an address instead — the infra chart's Ingress objects and the SECOM cutover argument both assume an ingress controller, H6; since 2026-09-26 the test cluster runs our own Traefik; SECOM Edition 2 takes a peer's identity from the signed envelope, so a SECOM request is ordinary HTTPS through it), and the deploy repos' CI is **GitLab shared runners**, not GitHub Actions (the smoke-test reachability question is now about those runners, H5).

**Internal open decisions:**

- [x] **One owner for these three documents** — decided 2026-09-22: this repo (`be/docs/cicd/`) owns them; `devops-niord/docs/` carries copies and says so in its README.
- [x] **Repo names and host** — settled 2026-09-17: `devops-shared` and `devops-niord` on `git.sitcloud.dk/cu3504-mssb-brs`, GitLab CI in both (§2). D1's wildcard test-env DNS domain is settled too (2026-09-26): `*.brs-test.e-navigation.dk`, in our own Cloudflare zone.
- [x] **`niord-app`:** `niord` publishes a generic image from it (§2.1, decided 2026-09-22); no jar.
- [x] **FE image story — decided 2026-09-26: the FE bundle served by the BE image**, as the old Niord served its UI. Each deployable image carries its country's production build of the administration app (`niord-app-en` the base build from `publish.yml`'s `web` job, a country's image its own build from its pipeline) and the backend serves it at `/` (`WebAppConfig`); Keycloak stays at `/auth` on the same origin. Rejected: one nginx image per country (`niord-dk-fe`, `niord-gl-fe`) — one more image and chart component per country for no gain. What follows is the text of the open item: The local chart already fronts everything with nginx on one origin; the `/auth` single-origin constraint (deploy-repo values comments) holds either way for the backend's relative `authServerUrl` default (`KeycloakIntegrationService.resolveAuthServerUrl`), unless the deployment sets an absolute one. The backend's `adminAppUrl` setting stays empty when the administration app is served at `/` of the backend's origin, and names the app otherwise (message links, the redirect of `/`; `be/README.md`).
- [ ] Security audit of the country content before it is public — **before the three repositories go public together, after the split** (2026-10-06: they stay private until then). In scope: the backend country modules *and* the frontend country folders, in the country repositories since the country split's P5 and P6 and in the earlier private repository's history, and the history `niord` keeps if it is that repository renamed. A content scan on 2026-10-06 (hostnames, addresses, credential words, the base-data archive) found public agency addresses and sites in the PDF and mail templates (`efs@dma.dk`, `brs.dk`, …), the local-profile password `niord`, and nothing else — no environment hostnames, no credentials, no personal data; the audit is small.
- [ ] Snapshot closers: confirm scrub scope (anything sensitive beyond credentials-in-DB and mailing-list emails? The attachments come along as rows since 4.0.146, so the question covers them too). The old "attachments skip vs. mirror" choice is gone (§9).
- [ ] Staging smoke test: build when the stack stabilizes (slot already reserved in the promote workflow).
- [x] **GitHub organisation and names of the three code repositories — decided 2026-10-06:** the product goes to **`NiordOrg/niord`**, the open source project's existing home (today the legacy Niord, last pushed 2026-09-24), and the two installations stay in SIFA-DK as **`SIFA-DK/niord-dk`** and **`SIFA-DK/niord-gl`**. The split starts with the earlier private repository retained as the product repository; the move to `NiordOrg` is a later step that is not scheduled. *(2026-11-03: the move is a fresh repository with no history, not a transfer; the SIFA-DK repositories stay private for the history, and the country repositories start fresh too, their new homes to decide; the set-up to redo is the implementation plan's step D (b).)* *(2026-11-17, the owner: the new repository is **`NiordOrg/niord4`**, public from its first commit, and not `NiordOrg/niord`, which stays the legacy Niord and the packages' registry; the versions go on from 4.0.182. The country repositories stay where they are for now. Implementation plan, step D.)* The packages go to `NiordOrg/niord`'s registry already (§3), so `github.repository` in `be/pom.xml` and the URL the country poms read are final; what changes at the move is the image source labels and the deploy repo's provenance lines. Still to settle, before the move: *how* the product repository becomes `NiordOrg/niord` — a repository transfer (keeps issues, pull requests, settings; GitHub redirects the old name; whether repository-scoped packages and the `harbor` environment with its secrets survive a transfer across organisations is the check to make) or a push of the history into the existing repository with the legacy content archived first. The country directories leave the generic repository in an ordinary commit; only the two country repositories are made with `git filter-repo`.
- [ ] Names/tokens: `_template` token conventions. *(Wildcard DNS domain settled 2026-09-26: `*.brs-test.e-navigation.dk`.)*
- [x] Observability: the `monitoring` stack is **kube-prometheus-stack**, decided 2026-10-20 and running on the test cluster with the first alert set since 2026-10-22 (`devops-shared/docs/monitoring-plan.md`, phase-0-answers D4). The mail receiver went in on 2026-11-10, through the host's SMTP relay (H9). Still open: the prod copy (once the prod cluster is bootstrapped), and `micrometer-registry-prometheus` in the BE when app-level metrics are wanted.

**Build-out order** — re-based 2026-10-06 and kept in one place, [`implementation-plan.md`](implementation-plan.md), *The order of work*: **A** the last pre-split item (the cross-repository Packages read; the bundles and the seed are done), **B** the split (the two country repositories with on-demand pipelines; the monorepo is the product repository in place), **C** the environments (DK and GL staging through Argo CD, prod, the promote, the rollback drill), **D** going public (the audit, the move to `NiordOrg/niord`, then the countries' full CI at every bump and the sharded suite, free on public repositories; the bump PRs themselves arrive at every publish since 2026-11-03), **E** the rest (ephemeral environments, backups and snapshots, the smoke test, the public site's deployment; the frontend chain was built as the country split instead, [`docs/fe-country-split-plan.md`](../../../docs/fe-country-split-plan.md)). Two facts set it: the split is the product's natural shape (footnote ¹ of §2), and staging may be down for as long as the split takes — it runs around the clock from step C on, its pins moved by hand until step D — so nothing that exists to keep staging fresh is a prerequisite for the split. The host checklist above stays a standing item, unsent; nothing in A–C waits for it.
