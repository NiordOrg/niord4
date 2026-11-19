# From pull request to staging: the chain timed, and what was built to shorten it

**Status:** built and measured, 2026-10-08. A backend merge reaches Harbor in about 4.5
minutes (11.5 before), and a staging deploy is one command and one merge (three hand-offs
and about 18 minutes before). One step was decided against (the GitLab webhooks, §4) and
one follow-up is open (§4). The full proposal, its two reviews of 2026-10-07 and the
simplifying third look are in this file's history; this page is the
record that remains.
**Scope:** the path a change takes from a pull request into `main` to a running staging:
`be-ci.yml` and `publish.yml` here, `ci.yml` and `image.yml` in `SIFA-DK/niord-dk` and
`SIFA-DK/niord-gl`, Argo CD's git source in `devops-shared`, the staging pins in
`devops-niord`. Production is not part of it: the promote stays a hand step on request.
**Does not change:** what is tested before a version is published (every published version
has passed the unit and the integration suite on its exact backend content), the version
scheme, the image layout, the two-pin deploy repo, the decision that the country pipelines
run on demand while the repositories are private (revisited 2026-11-03, §5: each publish now
starts each installed country's Base bump workflow and CI), and the decision against a
self-hosted Renovate. No workflow token writes to any `main`; git is the interface.

---

## 1. Measured

The 4.0.61 deploy of 2026-10-06 against the first runs after the change:

| | 2026-10-06 | after 2026-10-08 |
|---|---|---|
| Merge into `main` → image and chart in Harbor | 11.5 min (a second full Backend CI run of 7.5 min, then the publish) | **4.5 min** (4.0.66: detect 9 s, both test jobs skipped, publish 4:14) |
| Merge → staging pins merged | 18 min, three hand-offs (pin commit, Image dispatch, merge request) | one command of ~9 min, one merge |
| Deploy-repo merge → Argo CD notices | up to 3 min, two pins up to 3 min apart | within 1 min, both pins |
| Integration-test job | 7.5 min | **4.5 min** (unit suite not repeated, images pulled during the compile) |
| A documentation-only backend merge | two full runs and a published version | nothing |
| Backend CI minutes a month, at 62 merges | ~870 | ~250 (the pull request runs; a merge whose run was green costs the detect job) |

Of 30 merges read on 2026-10-06, 6 were documentation only under `be/`, 9 touched only
paths no test reads (chart, image scripts, the workflow), 6 were backend code, 9 had no
backend path.

---

## 2. What was built

| Step | Where | Live since |
|---|---|---|
| 1. Documentation-only backend changes run no Backend CI and publish nothing | niord, `be-ci.yml` | 4.0.63 |
| 2. The run on `main` skips the tests a pull request run already ran on the same backend content | niord, two pull requests (the report, the skip), `be-ci.yml` | 4.0.65, 4.0.66 |
| 3. A country image follows a green pin bump on its `main` | niord-dk #1, niord-gl #1, `ci.yml` + `image.yml` | 2026-10-08 |
| 4a. Both Argo CD controllers poll GitLab every minute | devops-shared, `argocd/test/` | 2026-10-08, on the test cluster |
| 5. The integration job runs Failsafe only and pulls its container images while Maven compiles | niord, `be-ci.yml`, `be/test-it`, `be/scripts/it-images.sh` | 4.0.64 |
| 17. A staging deploy in one command (one button since 2026-11-19) | devops-niord, `scripts/staging-bump.py`; since 2026-11-19 the promote job, `TARGET=staging` | 2026-10-08 |

**Step 1.** `be-ci.yml`'s `push` filter excludes `be/docs/**` and Markdown under `be/`
(re-including `src/test/resources`, where one fixture lives) and includes `publish.yml`;
the `Detect` step applies the same rule as one shell function, `relevant_paths`. A
documentation merge starts no Backend CI, so no publish; a `publish.yml` change is
published by its own merge.

**Step 2.** A squash merge of head `H` onto `main` at `P` has the tree of
`merge-tree P H`, which is what the pull request run tested when `main` did not move.
What is compared is not the tree but a **fingerprint** of its backend-relevant part:
`git ls-tree -r` restricted to `relevant_paths`, hashed — so a documentation commit on
`main` in between changes nothing. The pull request run's `changes` job posts one commit
status on the head, `Backend CI / fingerprint`, `fp=… run=<run id>`, before the tests.
The push run finds the merged pull request, compares the fingerprint of the pushed commit
with the posted one, and asks the named run for its conclusion (this workflow's
`pull_request` run of `H`, `completed`, `success` — a run re-run red cannot be reused).
All three right → `tested=true` and both test jobs skip; two skipped jobs leave the
workflow green, which is what `publish.yml`'s gate reads. Every other answer runs
everything and writes its reason to the job summary (`Tested already:`), so a skip that
stops applying is visible and never red. A re-run and a dispatch never skip.

**Step 3.** `image.yml` gets a `workflow_run` trigger on CI completing green for a push
to `main`; it checks out the triggering commit and tags `<base>.<run>` from its own run
number as before, so `promote.py`'s tag rule and the dispatch for rebuilds are untouched.
The guard is `ci.yml`'s `push` path filter (`be/**`, `.github/workflows/**`, not
Markdown), evaluated over the whole push.

**Step 4a.** `requeueAfterSeconds: 60` on the two ApplicationSet git generators (the
chart version pin) and `timeout.reconciliation: 60s` in a new `argocd-cm.yaml` (the image
tag pin), applied by root like the rest. The install manifest ships `argocd-cm` with nine
default keys; the file names one more and the default client-side apply removes none (a
key count of ten confirmed it). The application controller and the repo server read the
value at start and were restarted once; `runbooks/bootstrap.md` says so for upgrades.

**Step 5.** `./test-it --its-only` appends `-Dtest=_none_ -Dsurefire.failIfNoSpecifiedTests=false`
after the report floor is read (stays 50), so the integration job runs no unit test; the
`Unit tests` job runs them in parallel as before. The four Testcontainers images, read
from `NiordTestContainers.java` by `be/scripts/it-images.sh`, are pulled in a background
subshell of the same step before `./test-it`; the first IT class then starts its
containers from local images. One-class run: `./test-it --its-only -pl niord -Dit.test=<Class>`.

**Item 17.** `scripts/staging-bump.py` in devops-niord, run from a workstation with the
person's own `gh` and `glab` logins: takes a base (default the base product's newest `v4.0.N`
tag), commits the pin in each country repository under the person's identity, waits for
CI and the Image run, reads the tags, writes the four pins into a branch with
`promote.py`'s own substitution, validates, opens the merge request. The merge stays by
hand. `--dry-run`, `--country`, `--no-wait`, `--timeout`; an already-pinned country reuses
its newest green image or dispatches `image.yml`; a red CI stops it before the deploy repo
is touched. First run: both countries 4.0.61 → 4.0.66, images `4.0.66.6`, nine minutes.

*Since 2026-11-19 the script is gone.* devops-niord's promote job does the staging bump with
`TARGET=staging` (`scripts/staging_bump.py`), from the country images in Harbor: the highest
base that is a `v4.0.N` tag here and that both countries have an image of, and the newest
image of each on it. It opens the merge request on `staging/bump`, and a person merges it.
Image runs again after every green CI run on a country's `main`, so there is nothing to
dispatch or wait for. First run: both countries 4.0.182 → 4.0.214.

---

## 3. When step 2 tests again, and that is correct

`main` moved by a backend change between the pull request's last run and its merge (the
fingerprints differ; "Update branch" re-tests and restores the skip); the pull request was
merged before its run finished; the run was re-run and went red; a `workflow_dispatch`;
a re-run on `main`; a status that could not be posted (a warning, not a failure).
Break-glass is unchanged.

---

## 4. Not built

- **Step 4b, GitLab push webhooks to both Argo CD controllers** — deferred by the owner on
  2026-10-08 ("polls for now"), and decided against on 2026-11-10: in devops-shared the
  draft that built it is closed unmerged. It would have saved at most a minute per
  staging deploy, since both controllers poll every minute (step 4a). For that it needed
  a second internal Ingress for the ApplicationSet controller's webhook port, a Traefik
  `allowedHosts` line, a hand-applied secret, four GitLab webhooks, and two answers from
  the host (can `git.sitcloud.dk` reach the test cluster's internal Ingresses; are
  webhooks to the local network allowed).
- **Step 5b in the harness** — `Startables.deepStart` of PostGIS, Keycloak and MailHog in
  `NiordTestContainers`, so the three start side by side: about half a minute more off
  the integration job, and the same for the country repositories' tests. The pre-pull
  was built first because it was measured; this is the open follow-up.
- **A lane for changes no test reads** (chart, Dockerfile, image scripts: ~7 of 30 merges
  still run the Java suite once, on the pull request). Needs a guard against publishing a
  red `main`; decide after a few weeks of the above.

Considered and left out: publishing on `push` (a wasted build on every red run for ~2.5
minutes), reusing Frontend CI's bundles (artifact storage), sharding the integration suite
before the repositories are public (three times the minutes), a merge queue (Enterprise
for a private repository), Renovate and per-publish country pipelines (decided
2026-10-03 and 2026-10-06; per-publish country runs came back with the bump by push,
2026-11-03, §5), a faster Keycloak (a dependency decision), production.

---

## 5. Record

- 2026-10-07 — plan written from the 2026-10-06 measurements; reviewed twice the same day
  (the merge split corrected from 4:17:9 to 6:9:6:9, the Surefire skip, the promote tag
  rule, the two Argo CD controllers, the depth-1 checkout); a third look simplified step
  2 to one fingerprint status, step 3 to a path filter, step 5 to a `test-it` flag.
- 2026-10-08 — built in parallel, reworked once to the third look, merged in order with
  each publish tagged before the next workflow change (4.0.63–4.0.66); step 4a verified on
  the cluster by the owner; webhooks deferred; item 17 built and run; this page rewritten
  as the record on 2026-10-13.
- 2026-11-03 — the bump by push (built as F8 in
  `docs/fe-country-split-plan.md` §4.11 rule 6 and §4.12): each publish opens or moves the
  bump PR in every country that installed the Niord base bump app, which starts its Base
  bump workflow and CI. Measured on DK #13 (2026-11-02, wall time): Base bump 27 s to 1 min
  17 s, dispatched 2 min 2 s, CI 35 s on the unpinned lock and 4 min 50 s pinned.
  Estimated: about 4 to 5 billed minutes per country per move while this repository is private
  (about 380 a month for two countries), about 14 (about 1,060 a month) once it is public;
  the cadence, every publish, is revisited when this repository goes public. The image
  still follows the bump PR's merge.

## 6. References

- `.github/workflows/be-ci.yml` (its header explains steps 1, 2 and 5), `publish.yml`.
- `SIFA-DK/niord-dk`, `SIFA-DK/niord-gl`: `.github/workflows/ci.yml`, `image.yml`.
- `devops-shared/argocd/test/` (`argocd-cm.yaml`, the two ApplicationSets), `runbooks/bootstrap.md`; the closed draft for the webhooks that were not built.
- `devops-niord`: the promote job in `.gitlab-ci.yml` and `scripts/staging_bump.py` (`scripts/staging-bump.py` until 2026-11-19), `tests/test_staging_bump.py`, README "Moving staging to a new base: one button".
- `be/docs/cicd/design.md` §3–§7, `implementation-plan.md` *The order of work*; `docs/renovate-plan.md`.
