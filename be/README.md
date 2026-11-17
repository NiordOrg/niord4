# Niord — Spring Boot backend

The **Spring Boot 4** backend of Niord (the NW + NM T&P editing and publishing system), as a
two-module Maven reactor: a country-neutral library and the generic English application built on it.
The Danish and Greenland applications are repositories of their own since 2026-10-06
(`SIFA-DK/niord-dk`, `SIFA-DK/niord-gl`), built on the published library of a pinned version.

```
pom.xml              Spring Boot 4 reactor (parent)
niord/               base library (org.niord.base:niord-base) — country-neutral, English
niord-app/           generic English application — the reference deployable
docker-compose.yml   local dev services (PostgreSQL/PostGIS + Keycloak + MailHog)
docs/                design notes, test/coverage notes, potential-errors catalogue
k8s/                 Helm chart and deploy scripts (OrbStack k8s)
MIGRATION.md         port roadmap, dependency map, framework-seam strategy
```

A country application is *the base library plus overlay resources* — its own settings file, logos
and producer-code configuration — and its bootstrap archive (`be/bootstrap/`: its templates, scripts,
series and texts, as data). A minimal country application needs no beans of its own beyond its application
class. The backend contract ([`docs/country-api.md`](docs/country-api.md), _Extension points_) allows a country
its own beans where it names an extension point: a promulgation service, a token validator, its own REST
endpoints, its own settings, or a bean for a base product interface not on the protected list. It never allows a
protected one. `niord-base` already ships the English side of everything it reads at start: the English backend
texts under `defaults/base/texts/en/` and the English-only settings file `defaults/base/settings.json`.
The FreeMarker templates and scripts (`templates/{tmpl,messages,aton,mailinglist,geometry}`) are data, not
code: they live in the base product's bootstrap, `niord/bootstrap/templates/`, and reach a database only
through the bootstrap of a new one or a sysadmin's import of a release file (`niord/bootstrap/releases/`;
`../docs/code-data-config-plan.md` §6, §7). No jar holds one; the bootstrap is published as
`org.niord:niord-bootstrap:<version>:zip` (the `niord-bootstrap` module). A country's
texts are read from `defaults/country/texts/<lang>/` and, for a language other than English, from the pack staged
at `defaults/packs/<lang>/`; a root-level `<bundle>_<lang>.properties` is no longer read (the transitional layer
went in NG-B; `../docs/code-data-config-plan.md` §4.1). A root-level `niord.json` is still read as the settings
file in place of the base product's until niord-base's test tree moves its own to `defaults/` (§5.2): the one
transitional layer left.
`niord-app` is what turns that into a running application — an `@SpringBootApplication` class plus the
deployment-tier dependencies (JDBC driver, Liquibase, JMS broker) a library deliberately does not ship.

## Prerequisites

- **JDK 25** (`.java-version` pins it; `.mvnw` uses whatever `JAVA_HOME` points at)
- **Docker** — for the local services and the integration-test suite
- Maven is **not** required: use the bundled `./mvnw`

```bash
./dev doctor
```

checks all of that in about a second — the JDK Maven will actually use, `./mvnw`, the Docker
daemon *and* the endpoint Testcontainers talks to (not the same thing, see below), and whether
anything already holds the ports the local services need. It is read-only; it starts nothing and
changes no configuration. Run it before the first `./run.sh` of the day, or whenever a startup or
a test run fails in a way that does not look like your code.

## Run locally

```bash
./run.sh
```

That is the whole command, from a fresh clone. It builds and installs `niord-base` + `niord-app`,
then starts `docker-compose.yml` (PostgreSQL/PostGIS, Keycloak, MailHog), waits until every service
accepts connections, builds the schema with Liquibase, and serves the REST API on
**http://localhost:8888/rest**. Log in as `sysadmin` / `sysadmin` through a frontend from `../fe/`
(`../start-local.sh` starts one with the backend, see [`../fe/README.md`](../fe/README.md)). The
containers are left running when the app stops — `docker compose down` when you are done with them.
On later starts run.sh skips its install step (~15 s) whenever nothing under the two modules or their
poms changed since the last one. It leaves the JVM's time zone to the machine; the rule is UTC (*The JVM's
time zone*, below).

The three containers `niord-db`, `niord-keycloak` and `niord-smtp` belong to the compose project `be`, which
the file's top-level `name: be` fixes from any folder, and are shared by every checkout and every country's
launcher. A launcher (`../start-local.sh`, `niord/scripts/regen-sample.sh`, a country's `./start-local.sh`) never
stops, restarts or recreates a running one: its `start_services` (`scripts/local-run-lib.sh`) issues no compose
command while all three run, and starts a missing or stopped one with `docker compose up --no-recreate`.
Keycloak's realm is mounted from `~/.niord-local/keycloak/niord-realm-local.json`, which `./run.sh` and every
launcher copy there from `keycloak/niord-realm-local.json` first; a hand `docker compose up` needs that copy, and
fails without it rather than creating a folder in its place. The first compose up that creates or reconciles
`niord-keycloak` after this change (a hand `docker compose -f docker-compose.yml up -d`, or Spring Boot's when
no service runs) recreates `niord-keycloak` once, because its mount changed; its volume `niord-keycloak-data`,
and with it the realm and its users, stays. Launchers never do that recreation. An edited realm still needs
`docker compose -f docker-compose.yml rm -sf niord-keycloak && docker volume rm niord-keycloak-data` and a new
start (Keycloak imports the file only into a new realm), and an edited compose file (an image bump) reaches a
running container only by a hand `docker compose -f docker-compose.yml up -d <service>`. The bump to Keycloak
26.8.2 needs both on a realm an older image imported: from 26.8.2 the Admin REST API accepts only a token with
the client scope `kc-admin-api`, which the realm file now gives `niord-web`, and without it the app's Keycloak
admin calls (the Domains page's clients, the Users page) answer 401. Remove the volume as for an edited realm, or
keep it and add the scope by hand: `http://localhost:8080/auth/admin/`, realm `niord`, Clients, `niord-web`,
Client scopes, Add client scope, `kc-admin-api`, Default. The same hand command
is the way out when a stopped container's old configuration no longer starts (a bind source that is gone, such as
the realm of a removed worktree, after a Docker Desktop restart or a reboot): it recreates the container and keeps
its volume, and the launcher's error names it.

While the app is running, iterate **without** a stop/start:

```bash
./reload.sh            # second terminal, after editing code under niord/ or niord-app/
```

It recompiles what changed, and `spring-boot-devtools` restarts the application on the warm JVM in
~3 seconds — no Maven install, no JVM cold start, no docker-compose wait, database untouched. This
covers code and resources in **both** modules, because the run classpath swaps the installed
`niord-base` jar for `niord/target/classes` (see the `default-cli` note in `niord-app/pom.xml`).
Two things still need a stop and a fresh `./run.sh`: dependency/pom changes, and anything only read
at start-up (Liquibase changelogs, the settings start-up adds when the database lacks them).

The database starts **empty**: start-up adds the English texts and the settings, and nothing else, not
even a domain. A new database is bootstrapped once, with the base product's bootstrap
(`niord/bootstrap/`: the active master domain `niord-client-master`, the templates and scripts, the categories
`Light`, `Light buoy` and `Firing Exercises`, the parameter types, the built-in reports, `NAVTEX`,
`Twitter` and `Audio`, the `aton` dictionary). `../start-local.sh` does it by itself when the backend says the database
is new; by hand, while `./run.sh` is up:

```bash
"$JAVA_HOME/bin/jar" --create --no-manifest --file /tmp/niord-bootstrap.zip -C niord/bootstrap .
export NIORD_TOKEN=$(curl -s http://localhost:8080/auth/realms/niord/protocol/openid-connect/token \
  -d grant_type=password -d client_id=niord-web -d username=sysadmin -d password=sysadmin \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
scripts/bootstrap.sh http://127.0.0.1:8888 /tmp/niord-bootstrap.zip
```

A bootstrap runs once, on a new, empty database. The command refuses (409) a database that held a domain, an
area or a message at its first start (`existing`), a database bootstrapped before (`applied`), and one that holds
data by the time it is posted, naming the first table that holds a row. `GET /rest/bootstrap` reads the marker:
`pending`, `existing`, `in-progress`, `applied` or `failed`. A job still running after 10 minutes is asked to
stop, and the run reads `failed` only once that job has ended. There is no retry: a `failed` run, and a run whose
backend stopped mid-run (it leaves `in-progress`), are refused for good, whatever the archives. Recreate the
database empty (stop the backend, then `docker exec niord-db dropdb -U niord niord`; the next `../start-local.sh`
creates it empty and bootstraps it), or create it by hand from `template_postgis`, start the backend and run the
same command with the same archives. When its own post fails, `../start-local.sh` reads the marker again and
names that remedy only on `failed`; on `in-progress` it says to wait until `GET /rest/bootstrap` shows the outcome.

<details>
<summary>Running Maven directly instead of <code>run.sh</code></summary>

The bare `spring-boot:run` needs a one-time bootstrap, because `-pl niord-app` selects only that
module while it depends on the reactor-local `org.niord.base:niord-base:${revision}` (`4.0.0-SNAPSHOT` by default):

```bash
./mvnw -pl niord-app -am install -DskipTests -DskipITs   # once, and after any change under niord/
./mvnw -pl niord-app spring-boot:run -Dspring-boot.run.jvmArguments=-Duser.timezone=UTC
```

Two things to know. The install is the bootstrap for *compilation*: `-pl niord-app` resolves
`niord-base` from `~/.m2` when compiling, but the **run** classpath swaps that jar for
`niord/target/classes` (see `niord-app/pom.xml`) — so after edits under `niord/`, a plain
`./mvnw -pl niord compile` is all a running app needs (devtools restarts it), and a stale local
repository can no longer silently serve old `niord/` code to the run. And
`-pl niord-app -am spring-boot:run` does *not* collapse install-plus-run into one command: with
`-am` the goal runs against every selected project, hitting the parent pom first and failing with
*"Unable to find a suitable main class"*.

</details>

For content, `../start-local.sh --sample` bootstraps the database `niord_sample` with the base product's
bootstrap and loads the sample (`niord/sample/`): 5 domains, 6 message series, 55 areas, 172 charts,
45 categories, 8 publications and 34 sample messages titled "SAMPLE —". That dataset is **Denmark-centric**,
with English and Danish texts; the base product's settings offer English only, so it reads as English until a
sysadmin adds `da` to `modelLanguages` on the Settings page and reloads the page (the administration app reads the languages when it loads). Only the place names are Danish.

**The JVM's time zone.** The JVM runs in UTC (TZ1 in [`docs/potential-errors.md`](docs/potential-errors.md),
the owner's ruling of 2026-10-25): timestamps are stored without a zone, in the JVM's zone, so a JVM in a zone with
summer time stores the repeated autumn hour one hour off. `../start-local.sh` and the regeneration scripts set
`-Duser.timezone=UTC`; `./run.sh` and `spring-boot:run` set nothing, so pass
`-Dspring-boot.run.jvmArguments=-Duser.timezone=UTC`. The pom sets no `jvmArguments`, so nothing is replaced, and
`mode=development` is an environment variable and stays (below).

Neither command needs `NIORD_HOME`; the repository root defaults to `~/.niord`, and follows `NIORD_HOME` when it is
exported and no `-Dniord.home` is given. To run against services you started yourself:

```bash
./run.sh -Dspring-boot.run.jvmArguments="-Duser.timezone=UTC -Dspring.docker.compose.enabled=false \
  -Dspring.datasource.url=jdbc:postgresql://localhost:5432/mydb"
```

The `mode=development` override of a local run is applied as an *environment variable* in
`niord-app/pom.xml`, not as a `-D` in `jvmArguments`, so passing your own `jvmArguments` (which
replaces that parameter wholesale) does not drop it. `authServerUrl` needs no override: start-up adds
the settings file's absolute `http://localhost:8080/auth` to an empty database.

### The country applications

The Danish and Greenland applications left this reactor on 2026-10-06 for `SIFA-DK/niord-dk` and
`SIFA-DK/niord-gl`. Each is this library plus overlay resources, built on the published
`org.niord:niord-parent` and `niord-base` of a pinned version from the GitHub Packages registry of
`NiordOrg/niord`; their READMEs say how to build and run them against the same `docker-compose.yml`
services as here. A country's launcher uses the same services and the same project `be`, through the same
`start_services`, so it never touches a running shared container either. What a local run of a country application needs beyond `niord-app` — `NIORD_HOME`,
because its settings file sets `niord.home` to the token `${NIORD_HOME}` — is documented
there. A country's local command runs its JVM in UTC too (*The JVM's time zone*, above).

## What the backend serves, and message links

The backend serves the REST API under `/rest`, the WMS proxy under `/wms`, and the files its own
output links to: the PDF and mail stylesheets under `/css/templates` and the images under `/img`
(logos, flags, the map-image placeholder, file-type icons). The administration app and the public site are separate applications in `../fe/`.

A deployable image also serves the administration app, as the old Niord served its UI: the image
carries the deployable's production build of it (niord-app's image the generic build; a country's
image its own build, made in its repository) in `/app/web`, and `niord.web-app.dir` (`NIORD_WEB_APP_DIR`, set by the Dockerfile)
makes the backend serve it at the root of its origin (`WebAppConfig`). A browser's navigation to one
of the app's own addresses, such as `/messages` or `/editor/…`, gets its `index.html`; the
content-hashed bundles are cached for a year, the rest revalidated on every use. Without the property —
`./run.sh`, the integration tests — or when its directory holds no `index.html` (an image built with
`build-image.sh --no-web`), the backend serves no web app and `GET /` answers 404 unless `adminAppUrl`
redirects it. Only the app's `/silent-check-sso.html` may be framed, by its own origin, for Keycloak's
silent login check; every other response of the application carries `X-Frame-Options: DENY`. On the
app's HTML documents, though, browsers apply the Content-Security-Policy's `frame-ancestors 'self'`
(`WebAppHeadersFilter`) instead, so the app's own origin may frame them, which is what
`silent-check-sso.html` needs, and other origins stay refused. Every HTML the backend serves from user
or country data is sandboxed (`Content-Security-Policy: sandbox`): the repository's files, the report
and mail previews, and the files under `/assets/legal/` and `/assets/branding/`; a PDF is served from
the repository without it, for the browser's viewer. None of it can abuse the framing; the app's own
pages, Keycloak and a country's build of the app are trusted. The app's HTML documents (`index.html`,
every navigation, `/silent-check-sso.html`) carry a Content-Security-Policy the backend builds from the
current tile settings (`osmUrl`, `openSeaMapUrl`) and the token issuer. niord-base's `WebAppConfig`
builds it, and no country can replace that class or its bean (`CountryContractGuard`); a country filter
that rewrites the header is refused by review (`../docs/fe-country-split-plan.md` §4.11 rule 3). The
files under `/assets/legal/` and `/assets/branding/` also carry `X-Content-Type-Options: nosniff`.

The `adminAppUrl` setting (empty by default) names the URL of the administration app:

- Message links in mails and tweets take the form `<adminAppUrl>/messages?messageId=<id>`, or
  `<baseUri>/messages?messageId=<id>` while `adminAppUrl` is empty. A template stored in a database
  may still render the form `<baseUri>/#/message/<id>`; the backend rewrites such links before it
  stores a mail or sends a tweet.
- With `adminAppUrl` set, `GET /` on the backend answers `302 Found` with `Location: <adminAppUrl>/`
  and the original query string. Older mails and tweets carry `<baseUri>/#/message/<id>` links: the
  browser keeps the fragment across the redirect, and the administration app forwards it to
  `/messages?messageId=<id>`. There is no redirect when `adminAppUrl` has the backend's own origin.

A same-origin deployment — the image serves the administration app at `/`, and nginx routes
everything but `/auth` to the backend — leaves `adminAppUrl` empty, and `baseUri` is that origin.
`../start-local.sh` passes `-DadminAppUrl=http://localhost:<port>` for the administration dev server
it starts; like every setting, a system property or environment variable of the same name wins over
the stored value.

The `mode` setting — `development`, `test` or `production` — is the deployment label both frontends
show as a ribbon when it is anything but `production`. Every settings file (`defaults/base/settings.json`,
a country's) sets `production`, which is also the backend's fallback. A local run overrides it: `niord-app/pom.xml`
sets `mode=development` as an environment variable of `spring-boot:run` (so `./run.sh` gets it),
and `../start-local.sh` passes `-Dmode=development`.
`GET /rest/settings/web` reports a String-typed web setting with such an override in place of the
stored value, so the ribbon follows the mode the backend runs in; a stored row nobody overrides
stays what it is, whatever the settings file says: start-up adds a setting only to a database that
lacks it.

## Build & test

```bash
./mvnw clean test          # unit tests (Surefire, *Test) — no Docker
./test-it                  # + integration tests (Failsafe, *ITTest) — Testcontainers, needs Docker
./mvnw clean verify -DskipITs
```

A green unit run prints only Maven's `Running …` / `Tests run: …` lines — everything the tests
themselves write to stdout/stderr goes to `<module>/target/surefire-reports/<Class>-output.txt`
instead, so a real failure is not buried in the several thousand lines of stack traces and XML the
suite emits on the happy path. **Failures are untouched**: they still print in full, twice. Pass
`-Dniord.test.redirectOutput=false` for the old behaviour, and see
[`docs/test-infrastructure.md`](docs/test-infrastructure.md#where-test-output-goes) for the details.
Integration tests are unaffected — they still stream to the console.

**Use `./test-it` rather than a bare `./mvnw verify` for the integration suite.** `ITTest` guards
every integration test with a JUnit *assumption*, so when Testcontainers cannot reach Docker all
~200 `*ITTest` classes report "Tests run: 0" and the build still ends in BUILD SUCCESS. A green
`mvn verify` therefore means either "the integration tests passed" or "the integration tests never
ran", and nothing in the Maven output distinguishes the two. `./test-it` runs the suite and then
reads the Failsafe XML reports, which do record the difference, and fails loudly on a run that
skipped. It clears stale report directories first, so an incremental run cannot be scored against
the previous run's results.

It passes any extra arguments through to Maven, and relaxes the "at least 50 executed" floor to
"at least one" when you narrow the run:

```bash
./test-it -pl niord
./test-it -pl niord -Dit.test=KeycloakITTest
```

The integration suite starts its own containers (PostGIS, Keycloak, Artemis, MailHog) via
Testcontainers, on dynamic ports — it does not use the `docker-compose.yml` stack. If a suite does
skip, `./dev doctor` tells you which Docker endpoint is missing: `docker ps` is *not* a valid
readiness check when more than one engine is installed, because the docker CLI follows its own
context while Testcontainers follows `DOCKER_HOST`/`~/.testcontainers.properties`, and the two can
be different daemons.

A healthy full run reports no skipped test (until 2026-10-06 one, a one-time Liquibase relocation
gate that a dedicated script activated). `test-it` lists skips that are not about Docker but does not
fail on them; it fails on skips that name Docker or Testcontainers, and on a run where nothing
executed at all.

### CI

[`.github/workflows/be-ci.yml`](../.github/workflows/be-ci.yml) (at the **repository root** —
GitHub does not read workflows from subdirectories) runs automatically on every pull request, drafts
and pull requests into a `feature/**` branch included, and on every push to `main` that touches `be/**`:

| Job | Runs | Typical |
|---|---|---|
| Detect backend changes | on every such PR and push | ~10 s |
| Unit tests | `./mvnw verify -DskipITs` | ~2 min |
| Integration tests | `./test-it` | ~7 min |

The test jobs skip themselves when the PR does not touch `be/**`; a skipped
`Unit tests` reports "skipped", which the ruleset on `main` counts as passing, so a frontend-only PR
costs the detection job alone. When detection fails, `Unit tests` runs anyway. The ruleset on `main`
requires `Unit tests` (beside the frontend's `Frontend gate` and `Info gate`) before a pull request
merges; `Integration tests` is not required for the merge, but a red run of it on `main` stops the
publish of that commit. `./verify-local` at the repository root runs the same parts locally:
`./mvnw clean verify` over this reactor and the same integration-test report check (see the root
`AGENTS.md`). CI runs `./mvnw`, not the runner's preinstalled Maven, so it uses the same pinned
Maven version you do, and it runs the *same* `./test-it` you run locally, so the "did the ITs
really run" check cannot drift between the two.

See **[MIGRATION.md](MIGRATION.md)** for the staged port roadmap and current state, and
[`docs/cicd/design.md`](docs/cicd/design.md) for the repository/CI layout — one shared version
per merge, and the per-country repositories, which hold a country's backend since 2026-10-06 and its
frontend since the country split.
