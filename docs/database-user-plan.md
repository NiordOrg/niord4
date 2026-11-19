# The application's own database user

**Status: done on 2026-11-12 (the owner: "lets do an extra account"). The base product's part is
published as base 4.0.162: the chart makes the user, and the application builds and
bootstraps a new database as that user. Both stagings connect as their own user, `niord_app`,
since the same day (two merge requests in devops-niord), and the owner checked them (section 8).**
**Scope:** the base Helm chart, the first changeset, the tests of the new-deployment path, and DK's
and GL's staging. Production is not part of this plan.
**Repos:** niord (the chart, the changeset, the tests), devops-niord (the stagings' Secrets and
values), niord-dk and niord-gl (a bump, no code change).

This is the plan for F-X100 of [`security-review-2026-09.md`](security-review-2026-09.md): "the
application connects to its database as a user that owns that database and is not a superuser"
(the owner's ruling of 2026-09-26). The recheck of 2026-11-12 found it not met and asked for a plan
first (section 5 there, item 5).

---

## 1. Where things stood (2026-11-12)

- **One account did everything.** The Postgres image creates one user on a new volume, a
  superuser. The chart gave that user's Secret (`postgres.existingSecret`) to the Postgres pod, to
  the databases Job, to the application and to a Keycloak in production mode. Both stagings ran
  that way (`niord-postgres-admin`).
- **What that costs.** A break-in through the application (an SQL injection, a stolen login)
  reaches the application's data with any database user. With the superuser it also runs
  commands in the Postgres container, reads and writes its files, creates superusers and reads
  every other database on the server. The finding is low: it limits how far a break-in reaches.
- **The image's superuser cannot be made ordinary.** Tried on the chart's image (PostgreSQL 18,
  PostGIS 3.6): `ALTER ROLE ... NOSUPERUSER` is refused for the user the image creates ("The
  bootstrap superuser must have the SUPERUSER attribute"), and `REASSIGN OWNED` is refused for it
  too. So a second user is the only road, and a database that exists is handed over table by table.
- **One statement stopped a new database.** A user that owns its database built the whole schema
  except `COMMENT ON EXTENSION postgis` in the first changeset: only the extension's owner may
  write it, and an extension belongs to whoever created it. The owner of a database cannot create
  PostGIS either ("Must be superuser to create this extension"), so the extension is always a
  superuser's.
- **The database hotel gives the same shape.** A hosted database comes with an ordinary user, so
  the application must run as one there too, and the same statement was in the way
  ([`repo-files-in-database-plan.md`](repo-files-in-database-plan.md) §4.9).

## 2. The decision: one contract, two providers

The application always gets the same three things: a database it owns, a user that is not a
superuser, and PostGIS already created in that database. Who provides them differs:

| | The chart's own Postgres (`postgres.enabled: true`) | A database of someone else's (`postgres.enabled: false`, `postgres.host`) |
|---|---|---|
| The user, the database, PostGIS | the chart, as the image's superuser | the host (the hotel), or whoever runs the other release |
| `postgres.existingSecret` | the application's user | the application's user |
| `postgres.admin.existingSecret` | the superuser, used by the chart's own containers only | none; the chart refuses it |

So the application's side of the chart is the same in both, and a staging on the chart's Postgres
runs as the hotel will make it run. Waiting for the hotel was the alternative. It was dropped: the
hotel needs the changeset fixed and the application running as an ordinary user anyway, the
hotel's date is not known, and F-X100 is then met on the stagings without the host.

## 3. The shape

**Values** (`be/k8s/helm/niord/values.yaml`; the chart's README, "The database users"):

- `postgres.existingSecret`, `postgres.username`, `postgres.password`: the application's user.
  The local default is `niord_app`.
- `postgres.admin.existingSecret`, `.username`, `.password`: the superuser of the Postgres the
  release renders. The local default keeps the name it had, `niord`.
- `keycloak.db.existingSecret`, `.username`, `.password`: the user of a Keycloak in production
  mode. The local default is `keycloak`.

**Three cases**, told apart by the values alone:

| Values | What runs |
|---|---|
| no Secret named (a local run, a `test-<name>` environment) | two users from two rendered Secrets |
| `postgres.existingSecret` and `postgres.admin.existingSecret` | two users from the environment's Secrets |
| `postgres.existingSecret` alone | one account, as before: the chart renders the same objects as it did, and adds no init container. **Refused since 2026-11-18** in a release that renders the application and its own Postgres (section 11); an infra release without the application and a database of someone else's are not concerned |

The third case was why a new chart version changed nothing by itself. Both stagings' values were
rendered with the chart before and after: the objects are the same, only comments differ. An
environment moves when its values name both Secrets.

**The init container** (`database-user` in the application's pod; `niord.postgres.userScript` in
`_helpers.tpl`). It connects as the superuser before every start of the application:

1. It creates the application's user when missing and sets its password to the Secret's every
   time, so a changed Secret takes effect at the next start. It holds the user to an ordinary
   login: no superuser, no right to create databases or users.
2. It creates the database when missing, from the image's `template_postgis`.
3. It hands over a database that belongs to someone else: the database, and every table,
   sequence, view, function and type in the schema `public` that no extension owns. Only the
   owner changes. No row is touched and nothing is dropped. The extensions stay the superuser's.

Step 3 covers three things with one rule: the empty database the image makes on a new volume, a
database from before this plan, and a database the superuser recreated or restored
(`runbooks/fresh-database/fresh-db.sh` in devops-niord keeps working as it is). An init container
and not the databases Job: Argo CD runs that hook after the sync, once every resource is healthy,
and the application cannot be healthy without its user.

The application's container holds its own user only. The superuser's Secret is in the init
container's environment and nowhere else in the pod. The statement that sets the password is kept
out of the server's log.

**Keycloak** in production mode gets the same from its `create-database` init container: a user
of its own that owns `keycloak.database`. Both stagings use the shared Keycloak, which has its own
Postgres in devops-shared, so this is for an environment with a Keycloak of its own.

**The chart refuses** the superuser's Secret without the application's, one Secret under both
names, the application's Secret alone in a release that renders the application and its own
Postgres (since 2026-11-18), the superuser's Secret in a release that renders no Postgres, a
production-mode Keycloak without its own Secret where the others come from Secrets, and values
from before these keys (`helm upgrade --reuse-values`).

## 4. The first changeset

`V001__baseline_schema.sql` lost `COMMENT ON EXTENSION postgis`. The statement changed nothing in
the schema. An applied changeset is immutable, so this is the deliberate exception
`ChangelogChecksumPinTest` describes: the file names the old checksum in a `--validCheckSum` line,
and every database built before the change stays valid. The test pins the new checksum and holds
the file to accepting the old one; it fails without the line.

`CREATE EXTENSION IF NOT EXISTS postgis` stays. It finds the extension where a superuser created
it, which is every deployment: the Postgres image creates it in the database it makes and in
`template_postgis`, and a hosted database is ordered with it.

## 5. What was tried (2026-11-12, on OrbStack)

- **The checksum.** `ChangelogChecksumPinTest`: 2 tests pass; the new one fails when the
  `--validCheckSum` line is taken out.
- **A new deployment as the ordinary user.** `LiquibaseBootstrapITTest` and `BootstrapITTest` now
  connect as `ApplicationDatabaseUser`, a user that owns the database and is not a superuser: the
  first start and the whole bootstrap pass (13 tests, none skipped).
- **The init container's script**, against a container of the chart's image: a new volume, a
  database with all ten changesets built by the superuser (191 objects handed over, a function,
  an enum, a domain and a composite type among them), a missing database, a Secret that names the
  superuser (refused), a changed password with quotes and a backslash (the new one accepted, the
  old one refused), a user someone made a superuser (made ordinary again), and a second run of
  each (nothing to do). The server's log held no password.
- **The chart on the local cluster**, five runs:
  - a new install with the default values: the application started as `niord_app`, all ten
    sessions on the database were that user, and a changed Secret took effect at the next start;
  - an environment as the stagings are (main's chart, an image with nine changesets, one Secret),
    upgraded to this chart and image with the values unchanged: the Postgres pod was not
    restarted, the application started on the old checksum and applied the tenth changeset;
  - the same environment with a second Secret and both named: the user was created, the database
    and 182 objects handed over, the application started as the new user, and the row count of
    every one of the 112 tables was the same before and after;
  - an environment with its own Keycloak in production mode moved to three Secrets: Keycloak's
    database and its 90 tables handed over, its 5 users, 20 clients and 2 realms still there; and
    a new install with such a Keycloak, whose user built Keycloak's schema in a database made for it;
  - a local release from before (main's chart, its default values) upgraded with the default
    values, as `./scripts/deploy.sh` does: the database went to `niord_app` and the application
    started as that user.
- **Not tried:** the running stagings (the test cluster is not reachable from a workstation), and
  the integration suite as a whole as the ordinary user: the seeded suite loads its fixture with
  the foreign-key triggers disabled, which only a superuser may do, and no deployment loads a
  fixture.

## 6. The steps

| # | Step | Repo | State |
|---|---|---|---|
| 1 | The first changeset without the comment statement, its old checksum valid; the new-deployment tests as the ordinary user | niord | done: in base 4.0.162 |
| 2 | The chart: the two users, the init container, Keycloak's user, the checks; the README | niord | done: in base 4.0.162 |
| 3 | The merge publishes a version. DK and GL are bumped to it and both stagings take it with their values unchanged: nothing about their database changes | niord, niord-dk, niord-gl, devops-niord | done on 2026-11-12: published as 4.0.162, DK and GL bumped, both stagings on it (devops-niord) |
| 4 | Each staging moves: a sealed Secret `niord-postgres-app` beside its values, and the two lines of section 3 in `envs/staging/<cc>/values.yaml`. Only after that staging runs base 4.0.162 or later (step 3): an earlier chart does not know `postgres.admin` and would start Postgres' clients as a user that does not exist | devops-niord | done on 2026-11-12: the sealed Secrets in one merge request, the values in another, both merged on the owner's go and checked (section 8) |
| 5 | The records after the move: F-X100's row in the security review, `be/docs/cicd/design.md` §8 (the secrets an environment holds) with its copy in devops-niord, and `envs/_template` there | niord, devops-niord | done on 2026-11-12: `envs/_template` in the values' merge request; the security review, design §8 and this plan in this pull request; the copy in devops-niord after it |

A database is handed over by step 4's merge, not before. That merge is the owner's explicit
say-so for the staging's existing data
([`feature-branch-deploy-plan.md`](feature-branch-deploy-plan.md)).

## 7. Decisions

| # | Decision | Taken |
|---|---|---|
| 1 | Build the application's user into the chart now, or leave it to the database hotel | **The owner, 2026-11-12: build it now.** |
| 2 | Local runs and `test-<name>` environments get the ordinary user too, as the chart's default | Taken on the recommendation, 2026-11-12; not ruled on by the owner. A statement that needs a superuser then fails on a workstation first. The other choice is one line of values (`postgres.existingSecret`). |
| 3 | How a database that exists is handed over | Taken on the recommendation, 2026-11-12: in place, by the init container, ownership only. Dump and restore was the alternative; the hotel project rehearses that anyway. The stagings' own move still waits for the owner (step 4). |
| 4 | Which value keeps its name | `postgres.existingSecret` stays the application's user, which it already was for a release on someone else's database; the superuser gets the new name. |
| 5 | Keycloak's own user in the same change | Yes: the script is shared, and without it a Keycloak in production mode would be the one client left on the superuser. |

## 8. What to check on a staging after its move (step 4)

1. `kubectl logs deploy/staging-<cc>-niord-app -c database-user` names the user created, the
   database handed over and the number of objects.
2. The application is ready, and a sysadmin opens a message with an attachment and saves a change.
3. In the db pod, as the superuser: every session on the application's database is the new
   user's, that user is no superuser, and it owns the database.
4. The Postgres pod was not restarted by the move.

Checked on both stagings on 2026-11-12, about five minutes after the values' merge. The same on DK
and GL:

1. The log: `user niord_app created`, `database niord handed over to niord_app`, and 182 objects
   handed over, the number the staging-shaped run of section 5 handed over.
2. The owner opened a message's attachment and saved a draft twice, on each staging.
3. All 10 sessions on `niord` were `niord_app`'s; `niord_app` is not a superuser and owns `niord`.
4. Both Postgres pods were 14 days old with no restart.

## 9. Records updated with steps 1 and 2

- [`security-review-2026-09.md`](security-review-2026-09.md) §5: F-X100's decision, and what the
  recheck leaves.
- [`repo-files-in-database-plan.md`](repo-files-in-database-plan.md) §4.9 and R3: what a restore
  by an ordinary user reports (fifteen errors with the chart's image, not one), and the parts of
  R3 this plan already built.
- `be/k8s/README.md` ("The database users"), `be/docs/test-infrastructure.md` (the
  empty-database path) and the root `AGENTS.md` (this document).

## 10. Not in this plan

- **The database hotel.** Its seams stay R3 of the repository-files plan: URL parameters such as
  `sslmode`, a CA, and a Keycloak on a host and a database it did not create. What this plan gives
  that project: an application that runs as an ordinary user, a changeset that builds as one, and
  `keycloak.db.existingSecret`.
- **Production.** Its values are written when it is taken up; they name the Secrets of section 3.
- **The developer's loop.** `./start-local.sh` runs the backend against the `niord-db` container
  as that container's superuser. F-X100 is about deployments. The two tests of section 5 are what
  catches a statement that needs a superuser.
- **Closing the other databases to the application's user.** It may connect to them, as every
  Postgres user may by default, and reads nothing there: tried against a Keycloak database, it was
  refused the tables and the schema.

## 11. Execution record

- 2026-11-12: the question ("can the chart support both its own database and a database hotel"),
  the probes of section 1, and the owner's decision 1.
- 2026-11-12: steps 1 and 2 built and tried as section 5 says, in one pull request. Its
  branch was rebased onto main (Keycloak 26.8.2, nginx 1.30.6) before the push; the stagings'
  values were rendered again against that main, with the same result, and the new install with a
  Keycloak in production mode was run again on 26.8.2.
- 2026-11-12: the pull request merged on the owner's "merge it", its checks green. Backend CI on
  main and the publish run were green: base 4.0.162 (`v4.0.162`). No environment runs it yet.
- 2026-11-12: both stagings took 4.0.162 with their values unchanged (devops-niord), which
  ends step 3.
- 2026-11-12: step 4 prepared as a draft merge request in devops-niord. It names both Secrets
  in DK's and GL's values and updates the note in `envs/_template`. Both stagings were rendered
  with the 4.0.162 chart before and after. Only the app Deployment changes: it gets the
  `database-user` init container, and the app's container reads `niord-postgres-app`. The Postgres
  pod and its Secret do not change, and DK and GL get the same diff. The sealed Secrets are not on
  the branch: sealing needs the test cluster's certificate, which the workstation did not have yet.
  The app Deployment uses `Recreate`, so the merge request must not be merged without them.
- 2026-11-12: the owner printed the test cluster's sealing certificate on the jump server
  (`kubeseal --fetch-cert`, a key made on 2026-10-29). `niord-postgres-app` was sealed with it on a
  workstation for each staging: the user `niord_app` and a random password that nobody holds. The
  Secrets went in a merge request of their own in devops-niord, so their unsealing could be
  checked before the app depended on them: merged on the owner's go, and both Secrets were there a
  minute later.
- 2026-11-12: the values' merge request merged on the owner's go, its pipeline green. Both app pods
  were replaced and ready within three minutes, and the checks of section 8 passed on both stagings.
  Step 5's records followed: the security review (section 5, item 5 and the decision on F-X100),
  design §8, this plan and the root `AGENTS.md`, then the copy of design §8 in devops-niord.
- 2026-11-12: the second recheck of the security review found a hole in the init container's
  script: it ran the hand-over as the superuser, with the default search path, inside the database
  the application's user owns (SECREV-4). Fixed on the owner's go: the script gives every
  session it opens the search path `pg_catalog`, and `ChartDatabaseUserScriptITTest` runs it in the
  chart's Postgres image. The stagings take it with their next base version.
- 2026-11-18: the chart refuses the third case of section 3 where it mattered: a release that
  renders the application and its own Postgres and names `postgres.existingSecret` without
  `postgres.admin.existingSecret` fails the render, so values in that shape can no longer start
  the application as the superuser without anyone noticing. Every environment had moved; the
  README's realm render for a shared Keycloak names the two further Secrets it now needs.
