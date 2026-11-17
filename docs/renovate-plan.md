# Renovate on the host's GitLab: a self-hosted runner for devops-shared and devops-niord

**Status: decided against on 2026-10-03, nothing was built.** The plan below was drawn up
the same day and is kept as the record: what a self-hosted Renovate would have done for the
two GitLab repos, what it would have cost, and why that was not worth it yet. Section 0 is
the decision. Everything from section 1 on is the plan as it stood, and is not being
carried out.
**The countries' bump (2026-11-03):** §0b.
**Scope:** the two private GitLab repos, `devops-shared` (platform pins under
`platform/test/`) and `devops-niord` (chart pins under `envs/staging/`). The GitHub side of
the propagation chain (bump pull requests into `niord-dk` and `niord-gl`, design §4) was
never part of this page, until §0b recorded how it was settled (2026-11-01 to 2026-11-03).
**Resolves:** the Renovate bullet of devops-shared's `docs/open-issues.md`, issue 4, as
"not pursued"; the other bullet of that issue (the prod cluster's deploy tokens) stays open.
**Repos:** this page lives here (`docs/renovate-plan.md`, next to the Gateway API plan);
the decision is recorded in `be/docs/cicd/implementation-plan.md` and `design.md` §4, and
goes to devops-shared's issue 4 and both `renovate.json5` headers in a follow-up merge
request there.

---

## 0. Decided against on 2026-10-03

**What Renovate would have done.** In devops-niord: after each `publish.yml` run, open and
auto-merge a merge request moving two lines, the staging pins for DK and GL, to the new
chart version. In devops-shared: notice that someone has mirrored a newer Traefik or
Reloader chart into Harbor and open the bump merge request.

**What it would not have done.** It cannot see upstream. A platform bump still starts with
a person mirroring chart and image by hand (`runbooks/bootstrap.md`, Mirror), and that
person has the pin one edit away; Renovate would have added a day of delay there, not
automation. Prod it never touches, by design; that is the promote job, whose
`PROMOTE_TOKEN` does not exist yet either.

**What it would have cost.** Two Maintainer-role bot tokens with expiry dates, two
schedules bound to a user's merge rights, a daily image pull, the automerge edge cases
(section 5), docs in three repos, and the loss of the one human look that catches a
mirrored chart without its image, which on Traefik takes the Argo CD UI down.

**Where the design wanted it.** Design §4 calls for Renovate because of the five-repo
target: generic `niord` publishes a version, Renovate bumps `niord-dk` and `niord-gl`, and
their full country suites gate the merge. That is real value, on the GitHub side, as the
hosted app, in a phase that has not started. The GitLab leg is two pins per repo.

**What happens instead.**

- Staging pins and platform pins move by hand, as they do today: a two-line merge request
  after a publish, the pin edit in the same session as a mirror.
- Open, decided separately: whether `publish.yml` should move the staging pins itself. It
  knows the version it just pushed to Harbor and could upsert the merge request in
  devops-niord through the GitLab API, the way the promote job does, with auto-merge on
  green. One token in this repo's GitHub secrets, no polling, no schedule, and staging would
  follow a publish within minutes. Not decided here.
- Revisit this page when the country repos exist and Renovate runs on GitHub for them. The
  job in step 1b, the token recipe in 1a and the verified facts in 2.3 are what to start
  from; the three reviewer findings (the schedule guard, the validator's file argument, the
  Dependency Dashboard) are already folded in.

---

## 0b. The countries' bump: Mend's app, self-hosted Renovate, then the Niord base bump app (2026-11-01 to 2026-11-03)

Recorded 2026-11-03. The GitHub side of the chain, the base bump pull requests into the
country repositories, was settled in three steps; the last one replaced Renovate there
altogether. The GitLab plan of this page is unchanged by it and stays decided against.

**2026-11-01 and 2026-11-02: Mend's hosted app (E1, E2).** The owner's choice for the
countries was Renovate as Mend's hosted app on the SIFA-DK organisation. The kit's
`templates/renovate.json` (RN-2) bumped the `<parent>` and the pack digests weekly, security
bumps at once, never automerged, without the Dependency Dashboard's approval (dropped by the
owner on 2026-11-01, decision 93). Its Maven lookup in GitHub Packages needed a credential
kept at Mend, `NIORD_PACKAGES_READ`: a machine user's `read:packages` token, the hostRules
user `niord-renovate` (decision 104). The template, the Base bump workflow and the guides
were built in the split plan's Renovate step and published in 4.0.111; the app was never
installed and the credential never made (tracker F1, F2).

**2026-11-03 morning: Renovate self-hosted (decision 132).** The owner reversed E1/E2:
Renovate as a scheduled workflow in each country's own Actions, written by `init`, reading
the registry with the country's built-in `GITHUB_TOKEN` and dispatching the Base bump
workflow with the pull request's number; no Mend app and no machine user, so F2 was dropped.
It was never built.

**2026-11-03 ~07:50: the bump by push (decision 133).** Both are superseded. A GitHub App of
SIFA-DK's own, "Niord base bump", is used only by niord-ng's publish run, its `bump` job, to
open the bump pull request on `base-bump/<version>`, or move the one open bump pull request
to the newer version, in each country repository that installed it. Its private key and id
live on niord-ng's main-only `publish` environment; a country holds no credential of it. The
reasons, from the discussion of that morning:

- Mend's credential is one per organisation: a machine user's token in every country
  organisation, kept at Mend.
- [GitHub, from knowledge] GitHub disables a scheduled workflow in a public repository after
  60 days without activity, so a self-hosted Renovate would stop without a word once the
  repositories are public.
- [GitHub, from knowledge] Dependabot, weighed beside them, needs a registry token for GitHub
  Packages' Maven endpoint and pauses version updates after 90 days of ignored pull requests.
- The push needs no schedule anywhere, so nothing pauses, and no credential in a country. It
  arrives minutes after a release, which absorbs the publish nudge (tracker F7), in private
  and public repositories alike; and [GitHub, from knowledge] an app's pull request, unlike
  one made with the built-in token, starts the country's workflows, so the Base bump
  workflow and CI run on it.

**What carries over.** The Base bump workflow and "green means no task" (decision 93); the
refresh of the pinned packs joins the bump pull request, the Base bump workflow moving them in
the same pull request; `templates/renovate.json` is retired from the kit in F8-4. The records
RN-1 to RN-7 and the 4.0.111 entry of `country-kit/API-CHANGES.md` keep their words. The bump
by push is built, and its sub-PRs recorded, in `docs/fe-country-split-plan.md` §6 _The bump by
push_.

---

## 1. Summary

- **End state:** a `renovate` job in each repo's own `.gitlab-ci.yml`, run by a pipeline
  schedule every weekday morning and on demand. It opens a merge request when Harbor holds a
  newer chart than the pin, and the merge request merges itself once the lint pipeline is
  green. Prod pins are never touched; that is already in both `renovate.json5` files.
- **What exists already:** both `renovate.json5` files, written for self-hosted use; and the
  read-only Harbor robots, one per repo, in the CI/CD variables `REGISTRY_ROBOT_USER` and
  `REGISTRY_ROBOT_PASSWORD` of **both** repos (issue 4 says devops-niord; devops-shared has
  had its own since 2026-09-26, bootstrap pipeline 8222). No new robot is needed.
- **What is missing:** the job, one GitLab token per repo that can open and merge merge
  requests, and the schedules. Neither project has any access token or schedule today.
- **What it does not change:** Traefik and Reloader still reach Harbor by hand (the Mirror
  section of `runbooks/bootstrap.md`). Renovate only sees Harbor. What it removes is the
  merge request and the merge after a mirror. The niord chart needs nothing: `publish.yml`
  already puts every version into Harbor.
- **Four steps**, each one merge request, each with a dry run before the real run. Step 2's
  first real run moves both stagings to the newest chart in Harbor, so it is pressed
  deliberately.

| # | Repo | What | Visible effect |
|---|---|---|---|
| 1 | devops-shared | the token, the job, a dry run, a real run | none until a new platform chart is mirrored (the Dependency Dashboard issue is switched off, decision 7) |
| 2 | devops-niord | the same | the stagings move to the newest chart in Harbor |
| 3 | both | the two schedules | a pipeline every weekday at 06:30 |
| 4 | devops-shared, this repo | close the record | none |

---

## 2. Background

### 2.1 How a version moves today

A platform bump (Traefik, Reloader): a person mirrors the chart and its image into Harbor
from a laptop, edits `platform/test/<component>/config.yaml`, opens a merge request, waits
for lint, merges, watches Argo CD. Then the same pin is copied to `platform/prod/` by hand.

A niord bump: `publish.yml` in this repo pushes the chart and images to Harbor. A person
edits `envs/staging/dk/config.yaml` and `envs/staging/gl/config.yaml`, opens a merge request,
waits for lint, merges. Prod is the promote job's business (not runnable yet either: its
`PROMOTE_TOKEN` does not exist, see section 6).

The design (`be/docs/cicd/design.md` §4) has Renovate doing the middle part of both: watch
the registry, open the merge request, merge on green. Both repos' `renovate.json5` encode
exactly that and say in their headers that they are inert until something runs them.

### 2.2 What GitLab looks like (read through the API on 2026-10-03)

| Setting | devops-shared (512) | devops-niord (513) |
|---|---|---|
| GitLab | 19.2.6-ee, instance shared runners (`shared-02 cluster`, Docker executor, no tags) | same |
| `main` | push: No one; merge: **Maintainers** | same |
| Merge checks | "Pipelines must succeed" on; squash always; delete source branch; no approval rules | same |
| Pipeline `workflow.rules` | `merge_request_event` and `main` only | same |
| CI/CD variables | `REGISTRY_ROBOT_USER` (visible), `REGISTRY_ROBOT_PASSWORD` (masked), both unprotected | same |
| Access tokens | none | none |
| Pipeline schedules | none | none |
| Job images | `public.ecr.aws/docker/library/*` (Docker Hub rate-limits the runners); helm from `get.helm.sh` | same |

Three consequences. The `main` rule already admits a schedule and a "Run pipeline" on
`main` (devops-niord's pipeline header says so for the promote job), so a Renovate pipeline
needs no new source rule, only a job with a rule of its own, and a guard so a schedule
without the variable does not pass as a green lint run. Because only Maintainers may merge
`main`, the bot that merges must be a Maintainer: GitLab performs "merge when pipeline
succeeds" as the user who set it. And a protected variable is only handed to pipelines on
`main`, which for a scheduled pipeline means the schedule's owner must be allowed to merge
to `main`.

### 2.3 Renovate facts that shape the job

- Self-hosted Renovate reads the repo's `renovate.json5` and resolves `{{ secrets.X }}` from
  the environment variable `RENOVATE_SECRETS`, a JSON object. That is how the robot reaches
  the config without being in git.
- On GitLab it needs a token with the `api` scope (`read_api` is enough for a dry run) and
  at least the Developer role; Maintainer here, for the reason above. It pushes branches
  over Git-over-HTTP with the same token, and for a **project** access token the `api`
  scope does not cover Git-over-HTTP (it does for a personal token). So `write_repository`
  is needed as well, and must not be trimmed later. The promote job asks for the same pair.
- Auto-merge on GitLab is "merge when pipeline succeeds" (`platformAutomerge`, the default).
  After pushing, Renovate gives GitLab one second to create the merge request's pipeline
  (`RENOVATE_X_GITLAB_BRANCH_STATUS_DELAY`, 1000 ms) and checks mergeability five times
  (`RENOVATE_X_GITLAB_AUTO_MERGEABLE_CHECK_ATTEMPS`, Renovate's own spelling) before it
  gives up for this run. A same-day merge is the expectation; when the pipeline is slow to
  appear, the next run sets the flag, so a bump lands the next morning at the latest. The
  two knobs are where to turn if the miss turns out not to be rare.
- Both configs use `managerFilePatterns`, so Renovate 39 or newer. The newest release on
  2026-10-03 is 44.132.2 (published 2026-10-02; `44.132.2` and `44.132.2-full` both exist on
  ghcr.io). The default image is the slim one; the enabled managers (`helm-values`,
  `custom.regex`) need no language tooling, so `-full` is not needed.
- Both configs extend `config:recommended`, which brings three defaults this page has to
  take a position on. `:dependencyDashboard`: the first real run opens an issue titled
  "Dependency Dashboard" in the project and keeps it updated (decision 7 switches it off).
  `semanticCommits` is `auto`: Renovate looks at the repo's recent commits and uses
  `chore(deps): …` titles only where it finds that style, so here the merge requests read
  "Update …", in the repos' own voice. `separateMajorMinor`: a Traefik mirror two majors
  ahead yields a minor and a major merge request that auto-merge in sequence; irrelevant
  for niord 4.0.x.
- `renovate-config-validator` ships in the image and checks the config without a token.
  Run with **no file argument**: given a file name it treats the file as the global
  self-hosted config, not as a repository config, and would pass a repo-level mistake.
  Without one it finds `renovate.json5` in the working directory and validates it as the
  repo config Renovate is about to load. `--strict` also fails a config that needs
  migration, which is wanted with an exactly pinned image.
- The docker datasource reads `/v2/<name>/tags/list` and the new tag's manifest. Harbor
  serves both to the pull robot (helm-template pulls the same charts with it), so no
  further permission.
- `baseDir`, where Renovate clones, defaults to `/tmp/renovate`. The image runs as an
  unprivileged user, and the runner's checkout under `$CI_PROJECT_DIR` may be root-owned,
  so the default is the right place; the official renovate-runner template moves it under
  `$CI_PROJECT_DIR` only to cache the clone between runs, worth nothing for a repo of two
  pins.

---

## 3. Decisions

1. **The job lives in each repo's own `.gitlab-ci.yml`.** Not a third "renovate-runner"
   project. The repos' rule is "nothing here reaches outside this repo"; a token scoped to one
   project cannot be turned against the other; and the promote job already has this shape.
   The price is the job text twice, as the helm-template job already is.
2. **One project access token per repo: name `renovate`, role Maintainer, scopes `api` and
   `write_repository`.** Maintainer because of the merge rule (2.2); both scopes because of
   2.3. The alternative, naming the bot user on the protected branch's allowed-to-merge list,
   is a GitLab Premium feature and not needed. GitLab forces an expiry; take the longest it
   offers, write the date into `runbooks/bootstrap.md`, and keep the secret in Key Vault
   `sfs-niord-v3` as `gitlab-renovate-devops-shared` and `gitlab-renovate-devops-niord`. In
   the repo it is the CI/CD variable `RENOVATE_TOKEN`: masked and hidden, **protected** (the
   job runs on `main` only, and nothing else should read it).
3. **The Harbor robots are the existing ones.** Reused as `RENOVATE_SECRETS`, built in the
   job from the two variables. Read-only, one per repo, already proved.
4. **Mirroring stays by hand.** Renovate cannot watch ghcr.io or Docker Hub for us: every pin
   points at Harbor, and it is Harbor it reads. A platform bump still starts with the Mirror
   section. Pull-through (the host's public `ghcr` and `docker_hub` proxy projects) would
   change that, and is the host question recorded in `platform/test/reloader/config.yaml`;
   not decided here.
5. **A pipeline schedule per repo, weekdays 06:30 Europe/Copenhagen, plus an on-demand run.**
   Both carry the variable `RENOVATE=run`. A schedule without it creates no pipeline at all
   (a `workflow` guard, step 1b), so a misconfigured schedule shows as "never ran" rather
   than as a green lint pipeline that checked nothing. A "Run pipeline" on `main` without it
   runs the ordinary lint pipeline, as it does today. A schedule belongs to a user, runs
   with that user's permissions, and is deactivated if the user leaves the project; the
   owner must be allowed to merge to `main`, or the protected token is withheld and the job
   fails on its first line. The owner is written down in the runbook.
6. **The image is pinned exactly**, `ghcr.io/renovatebot/renovate:44.132.2`, like helm in the
   lint job. Renovate does not bump its own image: the `gitlabci` manager stays off, because
   the configs' rule is that Renovate writes the pins and nothing else. Bump it by hand, with
   the helm pin, a few times a year.
7. **No Dependency Dashboard.** `"dependencyDashboard": false` goes into both configs. These
   repos keep their decisions in `docs/open-issues.md`, a file, and each has one or two
   dependencies: the merge requests are the whole picture, and a bot-maintained issue would
   be the first issue in devops-niord's tracker and the third in devops-shared's, for nothing.
8. **A dry run first, every time something new is wired.** `RENOVATE_DRY_RUN=full` proves
   token, robot, image and config and writes nothing.

---

## 4. Steps

Each step is one merge request. Verify, report, then the next. The hand steps are written
for a zsh on the laptop with the GitLab personal access token in the keychain
(`security find-internet-password -s git.sitcloud.dk -a <your GitLab login> -w`); the UI path is given
next to each. Tokens and variables need the Maintainer or Owner role on the project.

### Step 1 — devops-shared: the token, the job, a dry run, a real run

**1a. The token** (Settings → Access tokens → Add new token, or the API):

```
TOK=$(security find-internet-password -s git.sitcloud.dk -a <your GitLab login> -w)
curl -s -H "PRIVATE-TOKEN: $TOK" -H 'Content-Type: application/json' \
  -X POST https://git.sitcloud.dk/api/v4/projects/512/access_tokens \
  -d '{"name":"renovate","scopes":["api","write_repository"],"access_level":40,"expires_at":"2027-10-01"}' \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("token") or d)'
```

Expected: one line, the token (`glpat-…`). If GitLab answers that the expiry is beyond the
instance's maximum, use the date in the message. Paste the token into Key Vault
`sfs-niord-v3` as `gitlab-renovate-devops-shared`, with the expiry date in its tags, then:

```
read -rs RENOVATE_TOKEN
curl -s -H "PRIVATE-TOKEN: $TOK" -X POST https://git.sitcloud.dk/api/v4/projects/512/variables \
  --data-urlencode "key=RENOVATE_TOKEN" --data-urlencode "value=$RENOVATE_TOKEN" \
  -d masked_and_hidden=true -d protected=true -d raw=true \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["key"], "masked" if d["masked"] else "VISIBLE", "protected" if d["protected"] else "UNPROTECTED")'
unset RENOVATE_TOKEN
```

Expected: `RENOVATE_TOKEN masked protected`. (UI: Settings → CI/CD → Variables → Add:
Masked and hidden, Protected, Expand variable reference off.)

**1b. The merge request.** Branch `kaspernielsen/renovate-job`.

`.gitlab-ci.yml`:

- `workflow.rules` get one guard. The existing `main` rule already admits schedules and
  "Run pipeline"; the guard keeps a schedule that lacks the variable from producing a
  pipeline at all (decision 5):

  ```yaml
  workflow:
    rules:
      - if: $CI_PIPELINE_SOURCE == "merge_request_event"
      # A schedule without RENOVATE=run would run the lint jobs and skip
      # Renovate: green, and checked nothing. So it creates no pipeline.
      - if: $CI_PIPELINE_SOURCE == "schedule" && $RENOVATE != "run"
        when: never
      - if: $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH
  ```

- The lint jobs stay out of a Renovate pipeline. On `.python` and on `helm-template`:

  ```yaml
  rules:
    - if: $RENOVATE == "run"
      when: never
    - when: on_success
  ```

- A new stage `renovate` after `lint`, and the job:

  ```yaml
  # Renovate: watches Harbor for newer charts than the pins under platform/test/
  # and opens a merge request per bump, which merges itself once the lint jobs
  # above pass (renovate.json5 says which files it may write). It runs from a
  # pipeline schedule every weekday morning and from Build → Pipelines → Run
  # pipeline on main with the variable RENOVATE=run; add RENOVATE_DRY_RUN=full
  # for a run that only logs what it would do. The token and the schedule are
  # in runbooks/bootstrap.md, "Renovate". It reads Harbor with the same robot
  # as helm-template. The image is pinned exactly, like helm; bump it by hand.
  # The validator takes no file name on purpose: with one it would check the
  # file as a self-hosted config, not as this repo's config.
  renovate:
    stage: renovate
    image: ghcr.io/renovatebot/renovate:44.132.2
    rules:
      - if: $RENOVATE == "run" && $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH
    resource_group: renovate
    variables:
      RENOVATE_PLATFORM: gitlab
      RENOVATE_ENDPOINT: $CI_API_V4_URL
      RENOVATE_REPOSITORIES: $CI_PROJECT_PATH
      RENOVATE_AUTODISCOVER: "false"
      RENOVATE_ONBOARDING: "false"
      RENOVATE_REQUIRE_CONFIG: required
      LOG_LEVEL: info
    script:
      - ': "${RENOVATE_TOKEN:?set the CI/CD variable RENOVATE_TOKEN (project access token renovate, Maintainer, api + write_repository; runbooks/bootstrap.md, Renovate)}"'
      - ': "${REGISTRY_ROBOT_USER:?set the CI/CD variables REGISTRY_ROBOT_USER and REGISTRY_ROBOT_PASSWORD (see the helm-template job)}"'
      - ': "${REGISTRY_ROBOT_PASSWORD:?set the CI/CD variables REGISTRY_ROBOT_USER and REGISTRY_ROBOT_PASSWORD (see the helm-template job)}"'
      - renovate-config-validator --strict
      - export RENOVATE_SECRETS="$(node -e 'console.log(JSON.stringify({REGISTRY_ROBOT_USER:process.env.REGISTRY_ROBOT_USER,REGISTRY_ROBOT_PASSWORD:process.env.REGISTRY_ROBOT_PASSWORD}))')"
      - renovate
  ```

  Why these: `resource_group` so a schedule and a button press never run together; the
  secrets JSON built by `node` so a password with a quote or backslash survives; the
  validator first, without a file name (2.3), so a broken config fails before any API
  call; no `baseDir`, because the default `/tmp/renovate` is already where it should be
  (2.3). `RENOVATE_DRY_RUN` is not set in the job: it comes from the run variables when
  wanted.

In the same merge request:

- `renovate.json5`: the header's "INERT UNTIL A RUNNER EXISTS" paragraph becomes how it runs
  (the job, the schedule, the dry run, where the token is), and `"dependencyDashboard": false`
  joins the config with a line saying why (decision 7). The rules stay as they are.
- `docs/manual.md`: section 12's "Renovate is inert today" bullet, and section 13 gets the
  second pipeline shape (a Renovate pipeline runs one job, no lint; a schedule without the
  variable runs nothing).
- `runbooks/bootstrap.md`: a "Renovate" heading after "The CI pull robots" with the token
  (name, role, scopes, expiry date, "expires at midnight UTC", Key Vault name, variable
  settings), the schedule (cron, timezone, owner, and that the owner must keep the right to
  merge to `main`) and the two ways to run it; and a row in the "What was installed" table.
- `docs/open-issues.md`: nothing yet (step 4).

**1c. Proof.** After the merge, Build → Pipelines → Run pipeline on `main`, or:

```
TOK=$(security find-internet-password -s git.sitcloud.dk -a <your GitLab login> -w)
curl -s -H "PRIVATE-TOKEN: $TOK" -H 'Content-Type: application/json' \
  -X POST https://git.sitcloud.dk/api/v4/projects/512/pipeline \
  -d '{"ref":"main","variables":[{"key":"RENOVATE","value":"run"},{"key":"RENOVATE_DRY_RUN","value":"full"}]}' \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["id"], d["web_url"])'
```

Read the job log. It must show: the config validated; the repository cloned; two
dependencies extracted, `reg.sitcloud.dk/cu3504-mssb-brs/charts/traefik 41.6.0` and
`…/charts/reloader 2.2.14`; the lookups answered by Harbor (no `401`, no `unauthorized`);
and either "no updates" or `DRY-RUN: Would create` lines. Only the `renovate` job ran.

Then the same without `RENOVATE_DRY_RUN`. Nothing newer is mirrored, so no merge request
appears, no issue appears (decision 7), and the log says so. To see the whole chain once,
mirror the next Reloader chart and image (the Mirror section) and run again: a merge
request from `renovate` appears, its pipeline runs the four lint jobs, and it merges on
green (if "merge when pipeline succeeds" is not set on it, the next run sets it, section
2.3). Argo CD then shows the test cluster's Reloader on the new version.

Record in this page: date, pipeline ids, what the real run did.

### Step 2 — devops-niord: the same

**2a.** The token and the variable as in 1a, project 513, Key Vault name
`gitlab-renovate-devops-niord`.

**2b.** The merge request: the same `workflow` guard, the same `rules` on the lint jobs
(`.python`, `helm-template`, `tools-check`; the promote job keeps its own rule), the same
`renovate` job with its comment pointing at the niord chart and `envs/staging/`. Docs:
`renovate.json5`'s header and `"dependencyDashboard": false`, `README.md` lines 84 and 94
(now true, no longer a promise), the bootstrap runbook's token table if that repo has one,
and `docs/open-issues.md`'s entry that names the robot pair.

**2c.** The dry run as in 1c, project 513. It must extract two dependencies, both
`…/charts/niord 4.0.38`, and the lookup must list the newer tags in Harbor.

**2d. The first real run, pressed deliberately.** It opens one merge request that bumps
`envs/staging/dk/config.yaml` and `envs/staging/gl/config.yaml` to the newest chart tag in
Harbor, and the merge request merges itself. Before pressing it:

- List what Harbor holds (`crane ls reg.sitcloud.dk/cu3504-mssb-brs/charts/niord` with the
  pull robot, from the runbook) and decide that the newest tag is one the stagings should
  run. If a tag should not be a candidate (4.0.46 was the one version that rendered an
  HTTPRoute, `docs/gateway-api-plan.md`), retract it first.
- Nobody is in the middle of a staging check.

After the merge: both staging Applications in Argo CD go Synced and Healthy on the new
version, and both staging sites load. Record the version and the date here.

### Step 3 — the two schedules

Build → Pipeline schedules → New schedule, in each repo, or:

```
TOK=$(security find-internet-password -s git.sitcloud.dk -a <your GitLab login> -w)
for P in 512 513; do
  SID=$(curl -s -H "PRIVATE-TOKEN: $TOK" -X POST "https://git.sitcloud.dk/api/v4/projects/$P/pipeline_schedules" \
    -d description=Renovate -d ref=main -d 'cron=30 6 * * 1-5' -d cron_timezone=Europe/Copenhagen -d active=true \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
  curl -s -H "PRIVATE-TOKEN: $TOK" -X POST "https://git.sitcloud.dk/api/v4/projects/$P/pipeline_schedules/$SID/variables" \
    -d key=RENOVATE -d value=run | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["key"], d["value"])'
  echo "project $P schedule $SID"
done
```

Expected, twice: `RENOVATE run` and the schedule id. The owner is the user whose token
created it, and the schedule runs with that user's permissions: they must keep the right
to merge to `main`, or the protected `RENOVATE_TOKEN` is withheld and the job fails on its
first line. "Take ownership" by a Developer would break it silently. Write cron, timezone,
owner and ids into `runbooks/bootstrap.md` (the Renovate heading from step 1). The next
weekday morning, both projects show a scheduled pipeline, green, with "no updates" or a
merge request; the check is that the `renovate` job ran, not only that the pipeline is
green.

### Step 4 — close the record

- devops-shared `docs/open-issues.md`, issue 4: delete the Renovate bullet and the sentence
  "Until it runs, nothing bumps versions"; the prod deploy-token bullet and the status line
  stay. Issue 2's auto-merge bullet: now exercised; its "if a required approval is ever
  added" caveat stays.
- This repo, `be/docs/cicd/implementation-plan.md`: Phase 2 (lines 92 and 95: the config is
  self-hosted on GitLab, the registry credential is the Harbor robot) and Phase 3 line 109
  (the deploy-repo rules are live), with the date. Copy `design.md`, `implementation-plan.md`
  and `intended-use.md` to `devops-niord/docs/` uncommitted, as the convention is.
- This page: status line to done, with the dates from each step.

---

## 5. Risks and open checks

- **A mirrored chart without its image.** Lint proves the chart is in Harbor; nothing proves
  the image is. Today a person notices before merging. With auto-merge the merge is
  immediate, and a missing image shows as `ImagePullBackOff` on the test cluster, for
  Traefik that takes the Argo CD UI down too (`platform/test/traefik/config.yaml`, "A CHART
  BUMP"). Mitigation now: the Mirror section mirrors chart and image in one session. The fix
  is a small lint job that checks the image at the chart's `appVersion` exists in Harbor
  (`crane manifest` with the pull robot); section 6.
- **ghcr.io from the runner.** The runners reach `get.helm.sh` and `public.ecr.aws`, so
  ghcr.io should pull. If it does not, the host's public `ghcr` proxy project in Harbor
  serves the image to the repo's robot through `DOCKER_AUTH_CONFIG`; that is the fallback,
  not the first choice (a credential more in the pipeline).
- **The merge-when-pipeline-succeeds miss** (2.3). Rare by design, harmless with a daily
  run; a same-day merge is one more button press, and the two knobs are named there.
- **Token expiry.** GitLab mails the project's Maintainers and Owners 60, 30 and 7 days
  before a project access token expires, and the token stops at midnight UTC on its date;
  after that the job fails with `401` on the first scheduled morning, a red pipeline. The
  date in the runbook and in Key Vault stays the primary record; the mails are the reminder
  the Harbor robots never get.
- **A Maintainer-role bot.** The token can change project settings. It is masked, hidden,
  protected, and only the job on `main` reads it; the pipeline file that uses it only changes
  through a reviewed merge request. Keep "Maintainer and Owner lists short" in mind: this
  adds one.
- **Docker-tag versioning.** Harbor's chart tags are plain semver (`4.0.38`, `41.6.0`), which
  the docker datasource orders correctly. A branch-proof tag that is not semver would be
  ignored, not chosen.
- **The first devops-niord run moves staging** (2d). Decided per run, not by the schedule.

---

## 6. Not in this plan

- **The image-presence lint job** (section 5, first bullet). Worth doing before the first
  platform bump, as its own merge request in devops-shared.
- **`PROMOTE_TOKEN`** for devops-niord's promote job: same recipe as 1a, role Developer, and
  the job becomes runnable. Separate, because it changes what the prod pins can do.
- **Release notes in the merge request body.** Without a GitHub token (`RENOVATE_GITHUB_COM_TOKEN`,
  read-only) Renovate cannot fetch the Traefik or Reloader changelog and logs a rate-limit
  warning. Cosmetic.
- **Pull-through instead of mirroring** for the platform components (decision 4).
- **Renovate bumping its own image** or the lint images (the `gitlabci` manager, decision 6).
- **The GitHub side**: bump pull requests into `niord-dk` and `niord-gl` (design §4) belong
  to the repo split, not to this page.

---

## 7. References

- devops-shared: `renovate.json5`, `.gitlab-ci.yml`, `docs/open-issues.md` (issues 2 and 4),
  `docs/manual.md` (sections 12 and 13), `runbooks/bootstrap.md` (step 0, "The CI pull
  robots", "Mirror"), `platform/test/traefik/config.yaml` ("A CHART BUMP").
- devops-niord: `renovate.json5`, `.gitlab-ci.yml` (the promote job's comment on
  `PROMOTE_TOKEN`), `README.md`.
- This repo: `be/docs/cicd/design.md` §4, `be/docs/cicd/implementation-plan.md` Phases 2 and 3,
  `docs/gateway-api-plan.md` (4.0.46).
- Renovate: docs.renovatebot.com, "Self-hosted configuration" (`secrets`, `dryRun`,
  `onboarding`, `requireConfig`, `platformAutomerge`, `baseDir`), "Self-hosted experimental"
  (the three `RENOVATE_X_GITLAB_*` knobs), "Platform: GitLab" (token scopes and role, merge
  when pipeline succeeds), "Config validation" (a file argument means global config;
  `--strict`), and the `config:recommended` preset (`:dependencyDashboard`,
  `:semanticPrefixFixDepsChoreOthers`).
- GitLab: "Access token scopes" (`api` on a project token has no Git-over-HTTP clause;
  `write_repository`), "Project access tokens" (Maintainer or Owner creates; expiry mails
  60/30/7 days; midnight UTC), "Pipeline schedules" (run with the owner's permissions), the
  API for access tokens, CI/CD variables (`masked_and_hidden`), schedules and
  `POST /projects/:id/pipeline`.
