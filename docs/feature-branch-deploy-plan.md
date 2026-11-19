# Feature branches on staging and test environments

**Status:** decided by the owner on 2026-10-23 (the rulings R1–R5, §1); not built. It is built after the
country split (R5). Harbor and the GitLab repository `devops-niord` are the owner's side (R5; the GitHub
repository and organisation settings and the GitLab repository `devops-shared` likewise); nothing here is done to
them before he says so.
**Scope:** how a build of an unmerged branch of the base product, of a country repository (`SIFA-DK/niord-dk`,
`SIFA-DK/niord-gl`) or of both reaches staging or a `test-<name>` environment on the test cluster, and what keeps
it from production and from existing data: a preview workflow here with a Packages repository and a Harbor project
of its own, preview inputs in the country kit's image template, a deploy command and a promote change in
`devops-niord`.
**Does not change:** `publish.yml` and its `main`-only `publish` and `harbor` environments, so release publishing
stays `main`-only (the preview path of §3.1 is a new, separately fenced path beside it); the release version
`4.0.<run>` and its tag `v4.0.<run>`; the staging bump's pins and flow (`devops-niord`'s promote job with
`TARGET=staging`, `scripts/staging_bump.py` since 2026-11-19; it gains the checksum refusal of §3.3 and ignores
previews, §3.3); the two-pin deploy repo (`be/docs/cicd/design.md` §3); the roll-forward-only migration policy
(design §7); the rules of `docs/code-data-config-plan.md` (a repository change only adds inert keys; data arrives
by bootstrap or a sysadmin's import); the sample's safety rules; and the rule that production is touched only on
request (`AGENTS.md:52-55`). Prod never runs a branch.

---

## 1. The rulings (owner, 2026-10-23)

- **R1.** Staging is used for international collaboration and must be able to deploy feature branches. Every
  environment but prod may run a feature branch. Prod never does.
- **R2.** Existing data on any environment other than a developer's local one (test, staging, prod: messages,
  templates, settings values, users, anything a deployment already has) is never touched unless a human
  explicitly says so. What every deploy does by design is fine as is: Liquibase applying new schema changesets
  at start, and start-up adding missing inert default keys (settings, texts).
- **R3.** A branch with a schema changeset reaches staging only when its schema is final and expand-only (the
  roll-forward-only, one-version-window policy of `be/docs/cicd/design.md` §7), with an explicit human approval
  in the deploy; before that it runs in a `test-<name>` environment with its own database. Branches without a
  changeset deploy to staging freely.
- **R4.** Prod promotes a named main version (a release published from `main`) that has run on staging, not
  "whatever staging runs"; the promote refuses any pre-release version or preview image.
- **R5.** The plan is written now and built after the country split. Harbor and the GitLab repository
  `devops-niord` are the owner's side ("we do not touch harbor at this time").

---

## 2. Where things stand today

**Only `main` publishes a release.** `publish.yml` runs after a CI workflow completes for a push to `main`
(`.github/workflows/publish.yml:147-151`), and its gate skips any trigger that is not a push to `main` in this
repository (`:276-281`) and fails a commit `main` does not contain (`:286-296`). The break-glass dispatch is
refused from any other ref (`:265-271`); before the fix of F-X106 it was not (`docs/security-review-2026-09.md:160`).
What holds for every copy of the file, including a branch's edited one, is a repository setting: the `publish`
and `harbor` environments allow deployments from `main` only, so a branch's copy never gets their secrets. The
gate's refusal to publish while either environment is missing or allows another branch is code in the file, and
binds only a copy that still contains it (`publish.yml:101-109`, `require_main_only` at `:242-263`, called at
`:314-315` and `:379-380`). Until the split, the Packages token is a repository secret a branch's copy can name
(accepted: `docs/fe-country-split-plan.md` §4.11 rule 1, _Until the country split_). The jobs that hold credentials name those environments
(`:401`, `:783`). The security review records that publishing starts only from `main` and that the `harbor`
environment allows `main` only (`docs/security-review-2026-09.md:402-406`), and the country-split plan's rule 1
asks the same of every repository's `harbor` environment (`docs/fe-country-split-plan.md` §4.11 rule 1, the
`harbor` environments' deployment-branch policy; the countries' rule waits for the owner's go, the same bullet's
_Not yet_).

**What a publish makes.** One version, `4.0.<run>` from this workflow's run number (`publish.yml:65-69`,
`:212`): `niord-parent`, `niord-base` and `niord-bootstrap` to the GitHub Packages registry of `NiordOrg/niord`
(`:519-530`), the image tools and the frontend source zip `niord-fe-src` (`:18-22`, `:578-588`), the
administration bundle `niord-web-admin` (classifier `en`, `:26-29`), the image `niord/niord-app-en` and the chart, both
`4.0.<run>`, into the Harbor project `cu3504-mssb-brs` (`:173-174`, `:843-869`), then the tag `v4.0.<run>` and a
GitHub release on it carrying the source zip and its sha256 (`:975`, `:1040`). Before anything is deployed the
jars, the bootstrap zip and the image inputs are checked to carry nothing of the sample (`:510-511`, `:824-825`).
(The line numbers are of `publish.yml` as of 2026-10-25.)

**A country image comes only from its `main`.** Since 2026-10-08 a country's `image.yml` builds after a green
CI run of a pin bump pushed to its `main`, tagged `<base>.<run>` (`docs/pipeline-latency-plan.md:71-75`); the
kit's template pushes only from `main` (`country-kit/templates/image.yml:41`) in the `harbor` environment
(`:44`) and only a tag of digits and dots (`:79`).

**Staging pins published builds.** A staging deploy is `devops-niord`'s promote job with `TARGET=staging`
(`scripts/staging_bump.py`, since 2026-11-19): it reads the country images in Harbor's release project, takes the
highest base that is a `v4.0.N` tag here and that both countries have an image of, and the newest image of each on
it, and opens a merge request with the four pins; the merge is by hand and Argo CD applies it
(`docs/pipeline-latency-plan.md`, item 17; `publish.yml`'s header, *Deployment is NOT here*). An environment pins
two numbers per country, the chart version (the base version) and `app.image.tag` (design §3,
`be/docs/cicd/design.md:89`).

**Test environments.** A `test-<name>` environment is one merge request that copies `envs/_template/`, pinned to
a specific build; it gets its own Postgres and Keycloak, starts with a usable schema (Liquibase is always on) and
no data, and is bootstrapped once with `be/scripts/bootstrap.sh` after the first sync; deleting the directory
tears it down (design §7, `be/docs/cicd/design.md:219`). Running an unmerged branch there is named only as
"optional later: country CI publishes `pr-123` images" (same line; `be/docs/cicd/implementation-plan.md:180`).
Ephemeral environments are step E of the order of work (`implementation-plan.md:16`), and no ephemeral
environment or PR image comes before step D, as none is free on a private repository (`:22`). R1 and R5 move
preview images and the use of `test-<name>` environments ahead of step D, to after the split (§5).

**The promote** reads what staging runs for a country and copies it into `envs/prod/<country>/`, as a merge
request whose merge is the deploy (design §7, `be/docs/cicd/design.md:221-226`). Its preconditions (staging
healthy, DK and GL on the same generic version) are designed (`:228`) and still to enforce
(`implementation-plan.md:155`); prod is parked (`implementation-plan.md:135`).

So nothing runs anywhere but on a developer's machine unless it has been merged into `main` — which R1 changes
for every environment but prod, by a preview path beside the release path, which stays as it is.

---

## 3. The design

### 3.1 Preview builds of the base product

**The workflow.** A new `.github/workflows/preview.yml`, `workflow_dispatch` only, runnable from any branch. It
must be on `main` before it can be dispatched (GitHub lists a dispatchable workflow from the default branch) and
then runs the dispatched branch's copy. Its gate refuses unless Backend CI, Frontend CI and Info CI each have a
completed, successful run for the branch head's exact commit that did its work. A run that skipped its work does
not count: a draft pull request's first run skips every job and still ends green (`be-ci.yml:196`,
`fe-ci.yml:94-98,148`, `info-ci.yml:148`), and pull requests here are opened as drafts (`AGENTS.md`), so the
gate refuses a draft's run on its first attempt and any run whose detection job (`changes`) was skipped. A
detection that ran and found nothing for its half (Frontend CI on a backend-only branch) counts as not applicable.
This is stricter than `publish.yml`'s gate, which takes a missing run as path-filtered and, for a commit without
a Backend CI run, reads the newest Backend CI run on `main` that the commit contains (`publish.yml:336-376`): a
branch has no such history, so a missing run refuses. A branch into `main` has the three runs from its pull
request once it is ready for review; a branch into a `feature/**` branch gets no CI (`AGENTS.md`, *CI runs only
on pull requests into `main`*), so its author dispatches the three on the branch (`be-ci.yml:161-166`,
`fe-ci.yml:124`, `info-ci.yml:122`). Each run costs Actions minutes, the reason the order of work kept PR images
back (`implementation-plan.md:22`); previews are made on request only. Whether the full integration suite must
run for a preview or a green pull-request run with the same backend fingerprint is enough
(`docs/pipeline-latency-plan.md:57-69`) is open (§6).

**The version.** `4.0.<last>-alpha.<slug>.<run>`: `<last>` the newest `v4.0.N` tag the branch head contains
(the release the branch started from or last merged), `<slug>` the branch name in lower case with every
character outside `[a-z0-9-]` replaced by `-`, cut to 30 characters and required to hold a letter, `<run>`
`preview.yml`'s run number. Example: `4.0.80-alpha.feature-nw-import.12`. The run number names the run, and the
run its commit.

- *It sorts below the next release.* Every later release is `4.0.<M>` with `M > last`, since releases take
  `publish.yml`'s growing run number. Under SemVer (Helm's chart versions) a pre-release sorts below its own
  release, so the preview sorts below `4.0.<last>` and every later one. Under Maven's ordering the first
  qualifier `alpha` is a known pre-release qualifier and sorts below the plain release too.
- *It is refused by the bump and unstable to Maven.* Previews are published where no country's release build
  looks (§3.2), and no preview runs `publish.yml`, whose `bump` job alone opens the countries' bump PRs (below, §3.6);
  the version is a second wall: the bump script refuses any `--version` that is not `4.0.<n>` before any call
  (its unit test, case 14). `alpha` is one of Maven's pre-release qualifiers. The fixed `alpha` is there because
  a bare qualifier (`4.0.80-nw-import.12`) is an unknown qualifier, which Maven sorts *above* `4.0.80`. Step 3 of
  §5 checks the refusal.
- *It is never a release tag.* No `v4.0.*` tag is made for a preview: `publish.yml`'s gate takes any
  `v4.0.`-prefixed tag on a commit as "already published" and would skip that commit's publish when it is merged
  (`publish.yml:304-310`), the staging bump counts only a base that is a `v4.0.N` tag, and `retract-version.yml` removes
  only `v4.0.<run>` (`retract-version.yml:172`).

**Where it publishes.** Nowhere a release lives. A GitHub environment of its own, `preview`, with no
deployment-branch rule (any branch) and its own two secrets: the classic token (`write:packages`) of a preview
machine user whose only write access is a separate repository that holds nothing but packages, e.g.
`NiordOrg/niord-preview`, so the token cannot write `NiordOrg/niord`'s registry; and a robot of a separate Harbor
project, e.g. `niord-preview`, that can push there and nowhere else. Both keep a preview for a period (§4).
`publish`, `harbor` and their secrets are not touched, so `publish.yml`'s gate and every release stay exactly as
they are. A private repository keeps environment secrets only on a paid plan
(`docs/security-review-2026-09.md:405-406`): `preview` lives under the same condition as `publish` and `harbor`.

**What it publishes**, at the preview version, is the set `publish.yml` publishes at the time it is built, made
by the same scripts (`country-kit/source-zip.mjs`, `be/k8s/scripts/split-jar.sh`, `publish-images.sh`,
`check-no-sample.sh`):

- to the GitHub Packages of the preview repository: `niord-parent`, `niord-base` with its test-jar,
  `niord-bootstrap`, `niord-image-tools`, `niord-fe-src` and the `niord-web-admin` bundle (classifier `en`, what
  the generic image carries); a country build reads the others (design §3, `be/docs/cicd/design.md:95`);
- to the preview Harbor project: the generic image `niord/niord-app-en` and the chart, both at the preview
  version (`IMAGE_PREFIX` names the registry and project, `be/k8s/scripts/publish-images.sh:13`), with the
  commit in the image labels (`:18`);
- to the run summary: the version, the commit, the source zip's sha256 line in `publish.yml`'s form and the
  changesets the build carries (§3.3).

The preview jobs keep the release jobs' split: npm and the base product's frontend build run only in a job with
no environment and no secret, as `publish.yml`'s `web` job does (`publish.yml:23-25`); the jobs in `preview`
verify the hashes of what they are handed and push only to the preview repository's Packages and to
`<REGISTRY>/<preview project>/…`. The no-sample checks run as in `publish.yml`, before anything leaves the runner.

**What it never does.** No tag `v4.0.*` (above). No GitHub release, not even one marked pre-release: Frontend
CI's upgrade test takes the newest non-draft, non-pre-release release among the 20 newest
(`fe-ci.yml:421-422`), and a run of previews would push the real releases out of that page and the test would
skip. Nothing into `NiordOrg/niord`'s registry or the Harbor project `cu3504-mssb-brs`.

### 3.2 Country preview images

The kit's `templates/image.yml` (`country-kit/templates/image.yml`, the model for each country's own) gains
dispatch inputs: `base`, a release `4.0.N` or a preview version, and nothing else. It then runs from any branch:

| Base product | Country | Example tag |
|---|---|---|
| a preview | `main` | `4.0.80-alpha.feature-nw-import.12.7` |
| a release | a branch | `4.0.80.7-dk-fix` (the country branch's slug) |
| a preview | a branch | `4.0.80-alpha.feature-nw-import.12.7-dk-fix` |

The country's `<run>` is appended as today (`<base>.<run>`, design §3); a country branch adds `-<slug>`. **Every
preview tag holds a letter; every release tag is digits and dots.** The base version is set in the job's
workspace only (the `<parent>` of `be/pom.xml` and set-up's pins), never committed. A release base is read from
`NiordOrg/niord`'s registry only; a preview base from the preview repository, which the workspace adds as a
Maven repository for that run only. Set-up records the base it built against in `niord-build-report.json` (base
product version and hash, provenance: `docs/fe-country-split-plan.md` §4.11 rule 2, the build report), which the
info image carries as labels, and the backend image's jar once its copy is built (the same bullet's _Not built_).

A preview has no GitHub release, so its source zip has no release notes to be checked against (integrity rule 2,
`docs/fe-country-split-plan.md` §4.11 rule 2, the zip's sha256 record). Its record is the preview run's summary line (§3.1), which the
preview token cannot rewrite; the country's preview job prints the sha256 of the zip it pinned in its own
summary, and the reviewer of the deploy compares the two lines (§4). Beyond that a preview's pin is trust on
first use, as a release's is after its first check (the same rule's first-pin sentence), accepted until the attestations.

A preview push job of its own, in the country's own `preview` environment with a robot for the preview project,
pushes preview tags only. It keeps the release jobs' split (`docs/fe-country-split-plan.md` §4.11 rule 1: the
unprivileged job and the push job, and _As built_): npm and the country's code run only in the unprivileged frontend job, with no environment and no
secret; the push job runs no npm and no kit code, verifies the hashes, logs in only to `REGISTRY` and pushes only
`<REGISTRY>/<preview project>/…`, refusing any other reference. The existing push job stays on `main` and the
`harbor` environment and refuses a pre-release base: the template's tag check already refuses anything but digits
and dots for the public site's image (`country-kit/templates/image.yml:79`); the backend image step each
country writes into it (`:86-94`, `niord-dk`'s `image.yml` the model, as the country-split plan's §5 step 10 says; not in this repository) gets the same check,
and the country's `ci.yml` on `main` fails a `<parent>` version that is not `4.0.N`.

### 3.3 Deploying

**The command.** `devops-niord` gets `scripts/preview-deploy.py` beside `scripts/staging_bump.py`, run from a
workstation with the person's own `gh` and `glab` logins. (The staging bump ran that way until 2026-11-19; it is a
target of the promote job since then. Whether the preview deploy becomes one too is open.)

```
preview-deploy.py --env staging|test-<name> --country dk|gl --base <version> --image <tag> [--schema-final]
```

It writes the chart version, the chart's registry, `app.image.registry` and `app.image.tag` of
`envs/<env>/<country>/` on a branch (each image has `image.{registry,repository,tag}`,
`implementation-plan.md:139`), and `info.image.registry` beside them where the environment runs the public site:
the site's tag follows `app.image.tag` in the chart, so the preview's site image must be in the same preview
project, pushed by the same run, validates as the repository's merge-request check does, and opens a merge request.
Merging deploys (Argo CD). Whether a `config.yaml` can name a chart registry other than the release project, or
the ApplicationSet fixes it, is a fact of `devops-shared`/`devops-niord` this repository does not hold (design §6
describes the AppProject as "the fence: sources, namespaces, cluster resources", `be/docs/cicd/design.md:182`);
step 5 of §5 settles it.

**The schema (R3).** The merge request lists what the build does to the environment's schema: the changesets
the build has that the environment's current build does not, read from the master changelog
(`be/niord/src/main/resources/db/changelog/db.changelog-master.yaml`) of the two base product commits. The commit
comes from the base version, not from a country image's labels, which name the country repository's commit
(`publish-images.sh:18`): the tag `v4.0.N` for a release, the preview run named by the version's `<run>` for a
preview. For `--env staging` it refuses a build with any new changeset unless `--schema-final` is passed, and
then writes into the merge request that the person states the schema is final and expand-only. Merging that
merge request is the human approval R3 asks for. "Final" is concrete: Liquibase identifies a changeset by its id,
its author and its changelog's path (Liquibase's documentation on changesets) and records its checksum; the pins
key it `author:id` (`be/niord/src/test/java/org/niord/core/ChangelogChecksumPinTest.java:33-53`). Once staging has
applied it, the merged version must carry it byte for byte under the same id, author and path, or Liquibase
refuses to start, as 4.0.59 did to both stagings for two hours (`implementation-plan.md:135`). "Expand-only"
here means a changeset that adds (a table, a nullable or defaulted column, an index) and neither drops, renames
nor reshapes. A changeset that reshapes existing values, which D2 allows in `main`
(`docs/code-data-config-plan.md` §13 D2; §7.4's Liquibase bullet), is not expand-only, so under R3 it reaches staging from `main` only.
For `--env test-<name>` the list is shown and nothing is refused.

**Going back.** Back to a release is the staging bump, as today. It, and `preview-deploy.py`, refuse a build
whose changeset pins (`ChangelogChecksumPinTest`'s map) give another checksum for an id the environment has
already run, reading the pins of every build the environment has had in the deploy repository's history.
The staging bump lists only the release project's image repositories that the environment's values name, and
accepts only a tag `<base>.<run>` of digits on a base that is a `v4.0.N` tag, so a preview image is never its
newest image. Going back also has to set the chart's registry and `app.image.registry` (and `info.image.registry`)
back to the release project, which `preview-deploy.py` changed: the staging bump writes only the chart version and
`app.image.tag` today, so step 5 teaches it that.

### 3.4 Data (R2)

- No tool of this plan bootstraps, loads, imports into or edits an existing environment's data. Release folders
  stay a sysadmin's import (`docs/code-data-config-plan.md` §1, _The rule_; §2 R-CDC3). A deploy does what every
  deploy does: Liquibase's new changesets and start-up's missing inert keys.
- What a branch adds on staging stays there. Its new default texts and setting keys are added once and never
  changed afterwards (`docs/code-data-config-plan.md` §2 R-CDC1, R-CDC5; `docs/fe-country-split-plan.md` §4.10, the backend's layers): a key the branch later renames or
  rewords before it reaches `main` stays on staging in the branch's form, a renamed one beside the new name, and
  no tool corrects it (R2); a human may, when asked. A changeset staging has applied stays applied when its
  branch is abandoned: its table stays, and its id is never used again, since Liquibase would refuse the other
  content under it (§3.3).
- The product already refuses the one mistake within reach: the bootstrap command answers 409 when the
  database's marker reads `existing` or `applied` (`be/scripts/bootstrap.sh:17-25`), which every database with
  any domain, message or area does (`docs/code-data-config-plan.md` §12, the risk "A seeded or populated database
read as fresh"; §7.2).
- A `test-<name>` environment gets its own Postgres (design §7), created empty and bootstrapped at creation by
  the person who creates it, with `bootstrap.sh` and the base product's archive, then the country's, at the
  versions the environment runs. Or, when a human asks for it, with a copy of staging's data made by a human;
  whether that is allowed, and with which scrub (design §9's `scrub.sql`, `be/docs/cicd/design.md:272`), is open
  (§6).
- Never the P9 sample: it reaches no jar, image or deployed environment (`be/niord/sample/README.md:1-10`, its
  loader refuses any but a loopback database `niord_sample`, `:78`; the checks in
  `docs/code-data-config-plan.md` §8).
- Deleting `envs/test-<name>/` deletes the environment and its database. The prune of the namespace and its
  volumes is still to verify (`implementation-plan.md:179`), and is verified before the first preview runs there.
- Staging's data is touched by no step here. The staging refresh of design §9 (`be/docs/cicd/design.md:274`)
  stays on demand by a human.
- A branch must itself keep the code/data rule (no start-up writer that changes existing rows); the reviewer
  of a preview-deploy merge request checks it (§4).

### 3.5 Promote (R4)

`devops-niord`'s promote takes a named main version per country — the chart version `4.0.N` and the country's
image tag `4.0.N.<run>`, by default the newest such tag staging has pinned — instead of reading what staging
runs now (design §7, `be/docs/cicd/design.md:223`). It refuses:

- a chart version that is not `4.0.N` or an image tag that is not `4.0.N.<run>` (a pre-release), and any
  registry or chart source but the release project;
- a version that staging's `main` never pinned (both pins in one commit under `envs/staging/<country>/`), read
  from the deploy repository's git history.

A pin in that history is taken as "has run on staging": Argo CD keeps no record of past health. When the version
is the one staging runs now, the designed precondition that staging is Healthy/Synced (`:228`) checks it as well.
The existing tag rule (`<base>.<run>` with the chart's base, design §3, `:89`) and the designed DK-equals-GL
check (`:228`) stay. As a second wall the prod cluster gets no pull credential for the preview project and its
AppProject no preview chart source, so a preview pin in `envs/prod/` would fail to sync. The promote is
production: it is changed only when the owner asks for it (`AGENTS.md:52-55`).

### 3.6 The bump app and the countries

- The countries' bump is the Niord base bump app's PR, opened only by `publish.yml`'s `bump` job: after a
  release, on `main`, in the main-only `publish` environment, and never automerged (`docs/fe-country-split-plan.md`
  §4.11, rule 1 and the never-automerged ruling; §4.12 there, step 1). A preview build makes no release and runs
  no `publish.yml`, and the script refuses any version that is not `4.0.<n>` (§3.1), so no preview reaches a
  country.
- A pin to a preview never reaches a country's `main`: the base is set in the image job's workspace only, and
  the country's `ci.yml` fails a pre-release `<parent>` on `main` (§3.2).
- If Renovate is ever run against the deploy repository (decided against on 2026-10-03; the rules wait in
  `devops-niord/renovate.json5`, `implementation-plan.md:143`), a rule disables every package in the preview
  project, so an environment that pins a preview is never moved to another preview, past R3, by a bot.

---

## 4. Guards and housekeeping

- **Retention.** The preview Harbor project keeps a tag for a period (open, §6), and `preview-deploy.py` warns
  when it pins a tag older than the period less a week. Preview package versions in the preview repository go
  by a scheduled `preview-prune.yml` on `main`, deleting only versions of the preview pattern older than the
  period; its delete token reaches the preview repository only, and `retract-version.yml`, which removes only
  `4.0.<run>` (`retract-version.yml:172`), is not used for previews. Which environment holds that token follows
  the split's machine users (`docs/fe-country-split-plan.md` §4.11 rule 1, _Until the country split_).
- **Who may dispatch.** Anyone with write access to the repository (GitHub's rule for `workflow_dispatch`). A
  fork's branch cannot be dispatched here and its runs get no environment secret; a contributor without write
  access has a maintainer push the branch first.
- **The preview token's scope.** A classic token's `write:packages` cannot be narrowed to version patterns, and
  a branch's copy of `preview.yml` can be edited, so a writer could publish any version with it, but only into
  the preview repository, the one repository its machine user may write. A release-shaped version there is read
  by no country's release build and never offered by the bump (§3.6), and a country's preview job takes a release base from
  `NiordOrg/niord` only (§3.2). `preview.yml` also refuses a version off the preview pattern before its deploy
  step. The Harbor robot pushes to the preview project only, and the workflow token has `contents: read` and
  pushes no tag. This is a new credential any branch can reach, fenced to its own repository and project; until
  the split the release Packages token can be named by a branch's copy too (§2), after it the preview token is
  the only such credential.
- **A reviewer of a preview-deploy merge request checks:** the environment and country; that the image and chart
  are in the preview project and name the commit the person meant; that the source zip's sha256 in the country
  run's summary matches the preview run's line (§3.2); the changeset list, and for staging the `--schema-final`
  statement against the changesets themselves (adds only; the same id, author and path it will merge with); that
  the branch's new default keys and changeset ids are final too, since staging keeps them (§3.4); that the branch
  adds no start-up writer of existing data; for a `test-<name>` environment that its database is its own. For
  staging it is announced to the international collaborators who share it.

---

## 5. Steps to build, in order

After the country split (R5), so after step B and ahead of step D, where the order of work had ephemeral
environments and PR images (`implementation-plan.md:16`, `:22`). **Owner** marks the owner's side; **repo** this
repository's.

| Step | Owner | What | Verified on the test cluster by |
|---|---|---|---|
| 1 | owner | The Harbor project for previews, a push robot each for `niord`, `niord-dk` and `niord-gl`, a pull credential sealed for the test cluster only, a retention rule; the preview Packages repository and its machine user, with write access to that repository only | the preview robot pushes a scratch tag there and is refused by `cu3504-mssb-brs`; the release robot is refused by the preview project; a `test-<name>` namespace pulls the tag; the preview token is refused by `NiordOrg/niord`'s registry |
| 2 | owner | The `preview` environment in the base product's repository (no branch rule) with the preview Packages token and robot; the same in each country repository, with the countries' read access to the preview repository | a branch's dispatch reaches the secrets; the next `main` publish passes its gate with `publish` and `harbor` unchanged |
| 3 | repo | `preview.yml` and `preview-prune.yml` on `main`; the docs (design §7, Phase 6's `pr-<n>` item and the order of work in `implementation-plan.md`, `:16` and `:22`, a line on the preview path in `docs/security-review-2026-09.md` §7, the README) | a dispatch from a branch publishes the preview set and no tag or release; a dispatch on a draft's skipped runs is refused; the next release is `4.0.N`, the staging bump still picks it, Frontend CI's upgrade test still finds it; the bump script refuses a preview version (its unit test); the prune deletes a scratch preview and nothing else |
| 4 | repo | The kit's `templates/image.yml` inputs, the preview push job and the `main` job's pre-release refusal; then each country's `image.yml` and `ci.yml` | a country preview of each row of §3.2's table; the `main` job and `ci.yml` shown red once on a preview base |
| 5 | owner | `preview-deploy.py` with its tests, the changeset list and the checksum refusal in it and in the staging bump (`scripts/staging_bump.py`), and the staging bump's return of the registries to the release project (§3.3, *Going back*); the preview chart source and pull credential in the test cluster's AppProject | a `test-<name>` environment from a preview with a changeset, bootstrapped, used, deleted with its volumes; a staging merge request refused without `--schema-final` and opened with it; back to a release with the staging bump, which passes over a newer preview image |
| 6 | owner, on request | The promote's named-version input, its refusals and the staging-history check; prod's AppProject and pull credential without the preview project | the promote's own tests and a dry run; nothing is done to prod for this plan |

---

## 6. Open questions for the owner

1. **The version scheme**: `4.0.<last>-alpha.<slug>.<run>` as proposed, or another fixed pre-release word.
2. **The tests behind a preview**: the full integration suite on the dispatch, or a green pull-request run with
   the same backend fingerprint reused (`docs/pipeline-latency-plan.md:57-69`), as the run on `main` does.
3. **The retention periods** for preview images and package versions (30 days is the proposal).
4. **Staging data in a test environment**: whether a `test-<name>` environment may take a copy of staging's data
   made by a human, and with which scrub.
5. **The Harbor project and the preview Packages repository**: whether the host creates the project or we may,
   and both names; whether one pull credential may cover both projects on the test cluster.
6. **The chart source per environment**: whether `config.yaml` can name the preview project's chart registry, or
   the ApplicationSet needs a second source (§3.3).
7. **A test environment's name and country**: the design names the namespace `niord-test-<name>`
   (`be/docs/cicd/design.md:219`) while the ApplicationSet reads `envs/*/*/config.yaml` (`:204`), one directory
   per country; the `_template` conventions are an open item already (`:319`).

---

## 7. Record

- 2026-10-23: the rulings R1–R5 given by the owner; this plan written; a link from `be/docs/cicd/design.md` §7.
- 2026-10-23: a fact-check applied: previews publish to a Packages repository of their own; the gate refuses a
  draft's skipped runs; release publishing stays `main`-only beside the preview path; `staging-bump.py` ignores
  previews; the consequences on staging of a branch's keys and changesets; the promote's "has run" read from
  staging's pin history.
- 2026-10-24 (a review): the references into the two plans cite sections and rules instead of
  line numbers, which move with every change to them; four workflow and README line numbers corrected; the
  country backend image's model named as `niord-dk`'s `image.yml`, as the country-split plan's §5 step 10 does.
