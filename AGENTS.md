# AGENTS.md

Niord is the maritime safety information system for Navigational Warnings and Notices to Mariners.
This repository holds the base product, as one repository with two halves that ship together:

| Path | What | Own conventions |
|---|---|---|
| `be/` | Spring Boot 4 backend: Maven reactor `niord/` (base library, `org.niord.core` + `org.niord.web` + `org.niord.model`) and `niord-app/` (generic English deployable). The Danish and Greenland deployables are the repositories `SIFA-DK/niord-dk` and `SIFA-DK/niord-gl` since 2026-10-06, built on the published library | [`be/AGENTS.md`](be/AGENTS.md) |
| `fe/` | Angular 22 workspace: `ui-apps/admin/` (the administration web app, Angular project `niord-app`) and `ui-apps/info/` (the public, anonymous site for mariners, Angular project `niord-info`, deployed in its own container) | [`fe/AGENTS.md`](fe/AGENTS.md), [`fe/ui-apps/admin/AGENTS.md`](fe/ui-apps/admin/AGENTS.md), [`fe/ui-apps/info/AGENTS.md`](fe/ui-apps/info/AGENTS.md) |
| `country-kit/` | the country kit: the set-up, `init`, the pack tools and checks a country repository builds its frontend with, its guides (`docs/`), `API-CHANGES.md`, the templates and the example country; beside `fe/`, published as its own package, `org.niord:niord-country-kit`, by the same publish run as the source zip and unpacked by a country at `<root>/.niord/kit/` | [`country-kit/README.md`](country-kit/README.md) |
| `guides/` | the user guides published at docs.niord.org, in AsciiDoc: the editor, admin and sysadmin manuals, the integration documents (`model/`, `public-api/`, `presentation/`) and the archived development guides; built by `guides/build.sh` (a pinned Asciidoctor in Docker) into `guides/_site/`, never committed, and checked by `guides/check-links.mjs`, which also holds every target of the admin app's help map (`help-urls.json`) to the built manuals; checked on pull requests by `guides.yml` | [`guides/README.md`](guides/README.md) |
| `docs/` | repository-level records: `security-review-2026-09.md` (the September 2026 security review: every finding's status, the deferred and accepted ones, and the recheck of the deferred ones against the chart and the stagings, 2026-11-12), `image-split-plan.md` (the deployable images: a reproducible dependency layer, then names without `-be`; its steps and their state), `gateway-api-plan.md` (the stopped Gateway API move and its rollback), `renovate-plan.md` (a self-hosted Renovate for the GitLab devops repos, decided against on 2026-10-03, kept as the record; and its §0b, the countries' bump: Mend's hosted app, then a self-hosted Renovate, both superseded by the Niord base bump app, 2026-11-01 to 2026-11-03), `pipeline-latency-plan.md` (the PR-to-staging chain timed, and what was built on 2026-10-08 to shorten it: a merge reaches Harbor in 4.5 minutes, a staging deploy is one command and one merge), `fe-country-split-plan.md` (the frontend country split, decided 2026-10-19 to 2026-10-21: a country-unaware base product, country repositories building from a published source zip, language packs with a home country each; the rules and the present state, in the section numbers the code cites), `code-data-config-plan.md` (adopted 2026-10-21: a repository change only adds inert keys a deployment never had and never changes or deletes its data; anything an operator can edit in admin — every template, script and report — is data that arrives by bootstrap or a sysadmin's import; no reload, no reset; the rules and the present state, in the section numbers the code cites), `feature-branch-deploy-plan.md` (decided 2026-10-23, built after the country split: preview builds of any branch on staging and `test-<name>` environments, never prod; a non-local environment's existing data is touched only on a human's explicit say-so; prod promotes a named main version that ran on staging), `repo-files-in-database-plan.md` (decided 2026-10-22 to 2026-10-27: every durable file the backend keeps on disk becomes a row in PostgreSQL, the application's volume becomes a cache nothing backs up, and the database is the one thing to back up, by the devops CronJob until a separate project moves it to the database hotel; R1, R2 and R4 done by 2026-11-10 (both stagings on the store, the design documents brought level), R3 not started, R5 replaced by the sea-chart tile cache plan; its decisions, phases and their state), `sea-chart-tile-cache-plan.md` (decided 2026-11-10 to 2026-11-12: the sea-chart (WMS) tiles cached by the backend's WMS proxy on the application's volume, for logged-in users only, off until a country switches it on, measured by an hourly log line and a Grafana dashboard; the cache built, the dashboard, the countries' settings and the staging checks open; its decisions, steps and their state), `public-site-deploy-plan.md` (decided and taken up 2026-11-10: the public site as a sixth component of the base chart, `info`, on a host and an Ingress of its own, its image made to fit a restricted pod (uid 101, port 8080) and the test cluster's Ingress rule allowing the host; staging and test environments only; its steps, decisions and execution record: built, and on both stagings since base 4.0.151), `database-user-plan.md` (decided and built 2026-11-12, for F-X100 of the security review: the application connects to its database as a user that owns it and is not a superuser; the base chart makes that user on its own Postgres and takes the one a hosted database comes with, and the first changeset builds as one; both stagings connect as their own non-superuser `niord_app` since 2026-11-12 (devops-niord !59, !58); the plan's steps are all done), `accessibility-findings.md` (the accessibility analysis of 2026-09-19: the legal scope, the current state and the recommendations; no page audited yet), `parallel-session-prompt.md` (a prompt for a Claude session working in a parallel worktree, applied only when a session is told to follow it), `specifications/` (external standards, and where to get the ones not kept here) | — |

The nested files own everything specific to a half. This file holds only what is true across both.

## Facts documents keep getting wrong

- The backend is **Spring MVC** (`@RestController`), **PostgreSQL 18 + PostGIS**, **Liquibase**, **Hibernate ORM 7**,
  **Jackson 3** (`tools.jackson.*`; `com.fasterxml.jackson.annotation.*` remains), **Java 25**. A text
  that says otherwise on the web layer, the transaction API or the test database is stale — trust the tree.
- The legacy Quarkus backend is **not** in this repository; nothing here should reference a `quarkus/` folder.
- Tests: `*Test` runs under Surefire, `*ITTest` under Failsafe on Testcontainers (Docker Desktop). A class named
  wrongly never runs; a suite that skips reports `Tests run: 0` with BUILD SUCCESS — use `be/test-it`.
- The frontend talks to the backend at `/rest` (proxied by the dev server, see `fe/proxy.conf.mjs`); CORS is not
  configured anywhere and must not be added.

## Working here

- **Run everything:** `./start-local.sh` (`--sample` for the sample deployment in `niord_sample`; `--info` for the public site on
  4400) from Git Bash; the parts separately are in [README.md](README.md). A country runs from its own repository
  with its own `./start-local.sh`, which the kit's `init` writes from `country-kit/templates/start-local.sh` (built
  on the launcher library and `org.niord:niord-local-run`; DK's and GL's arrive with their P8a changes); here
  `--dk`/`--gl` refuse and name it. Prerequisites: JDK 25,
  Node 22+, Docker Desktop.
- **CI reads workflows from `.github/workflows/` at the root only** (`be-ci.yml`, `fe-ci.yml`,
  `info-ci.yml`, `guides.yml`); copies under `be/` or `fe/` are inert. A ruleset on `main` requires three checks:
  the backend's `Unit tests`, the `Frontend gate` for the administration app and the `Info gate` for
  the public site (see README).
- **CI runs on every pull request** and on `main`, drafts and sub-PRs into a `feature/**` branch
  included: the repository is public, so Actions minutes cost nothing. A documentation-only backend change (`be/docs/`, Markdown under `be/`) runs no Backend CI and
  publishes nothing; a change to `publish.yml` runs Backend CI and is published by its own merge.
- **Verify in proportion, and push once.** While iterating, run only the tests of what changed (one
  class: `be/test-it --its-only -pl niord -Dit.test=<Class>`,
  which skips the unit suite; one spec file in `fe/`); the whole suite runs once, as CI on the
  pull request, where a full local run beforehand (`./verify-local`) only repeats what CI does
  (backend ~7 min locally, ~10 min on CI). Commit as often as useful but push when the work is done:
  every push to a PR restarts its CI and cancels the run in flight.
- **Production is touched only on request.** Do not start work on the production environments, prepare
  it, or suggest starting it — the prod values, secrets, cluster, promote and the host questions that
  gate them (`be/docs/cicd/`, step C of the order of work) — unless the owner asks for exactly that.
  Staging and everything before it are ordinary work; prod is the owner's call, every time.
- **Suspected production bugs found outside the task** go to `be/docs/potential-errors.md` (open items only,
  one `## CODE. title` entry each) or `fe/docs/backlog.md`; do not fix unrelated code in passing.
- **Country-specific behaviour** lives in the repositories `SIFA-DK/niord-dk` and `SIFA-DK/niord-gl`, backend and
  frontend, never in the shared code paths: this repository holds no country's folder, only the base product's neutral
  defaults at the seams (each frontend application's `country-specific/default/`). The backend contract a country
  builds on (its extension points, the protected list, the override check) is
  [`be/docs/country-api.md`](be/docs/country-api.md); the frontend's (the seams, the country API, the set-up from
  the kit package and the source zip) is [`country-kit/README.md`](country-kit/README.md), the kit at the repository root beside `fe/`.
- **`fe/shared-map/portrayal/`** (the portrayal definition and its symbols) is also read by the backend's map-image
  renderer: Maven copies it into niord-base. A change there runs Backend CI and is measured in both applications and
  in the server-made map images.
- **Windows sessions:** Maven wants `JAVA_HOME` and `-Dmaven.repo.local` spelled out, and integration tests need
  `NIORD_HOME` exported with forward slashes (`be/docs/potential-errors.md`, PORT-1). `python3` may resolve to the
  Microsoft Store stub; `py` is the real interpreter.
