# Niord on local Kubernetes (OrbStack)

Run the Spring Boot port of Niord (the generic `niord-app`; a country image from `niord-dk` or `niord-gl` by setting `app.image.repository`) and its full infrastructure on a
local Kubernetes cluster using a single Helm chart. This is the Kubernetes
equivalent of the repository's [`docker-compose.yml`](../docker-compose.yml) and is
tuned for [OrbStack's built-in Kubernetes](https://docs.orbstack.dev/kubernetes/).

The same chart serves the cluster environments described in
[`docs/cicd/design.md`](../docs/cicd/design.md) §6 in the same shape: one release per
country, in that country's own namespace, with every component. The defaults enable
everything but the public site in one release — the local bundle this page describes. Each of
the six components also sits behind an `enabled` flag, so a stack can be split into an app-only
release (`app` + `proxy`) and an infra-only release (`postgres` + `keycloak` +
`mailhog`) wired together through the `postgres.host`, `keycloak.host` and `smtp.host`
values.

> Local development only. The defaults are not hardened for production (plaintext
> passwords in values, Keycloak `start-dev` with the test realm, `csrf` disabled by the app,
> etc.). A cluster environment names its Secrets and runs Keycloak in production mode
> ([Keycloak in production mode](#keycloak-in-production-mode)).

Every pod meets the Pod Security `restricted` level, which the target clusters enforce in every
namespace: each runs as its image's non-root user (app and Keycloak 1000, MailHog 1000, nginx 101,
the public site 101, Postgres 999) under the runtime's default seccomp profile, with privilege escalation off and all
capabilities dropped (`niord.podSecurityContext` / `niord.containerSecurityContext` in
`templates/_helpers.tpl`). To check a change locally, label the namespace first:
`kubectl label ns niord pod-security.kubernetes.io/enforce=restricted`.

## What gets deployed

Everything is fronted by an nginx reverse proxy and served on a **single origin**
(`http://localhost:18888`), so there is one public entry point:

| Component | Image | In-cluster name | Service |
|-----------|-------|-----------------|---------|
| `proxy` (nginx) | `docker.io/library/nginx:1.30.6-alpine` | `niord-proxy` | LoadBalancer → host `:18888` |
| `app` (niord-app) | `niord/niord-app-en:4.0.0-SNAPSHOT` (built locally) | `niord-app` | ClusterIP (via proxy) |
| `db` | `docker.io/imresamu/postgis:18-3.6` | `niord-db` | ClusterIP |
| `keycloak` | `quay.io/keycloak/keycloak:26.8.2` | `niord-keycloak` | ClusterIP (via proxy `/auth`) |
| `smtp` | `docker.io/mailhog/mailhog:v1.0.1` | `niord-smtp` | LoadBalancer → host `:18025` (UI) |
| `info` (the public site; off by default) | a country's `niord-<cc>-info`, under the app image's tag | `niord-info` | ClusterIP, port 8080 (an Ingress for its own host in a cluster) |

The PostgreSQL database starts **empty**: on startup the app builds the schema with Liquibase (the bare
master changelog), and a new database holds no data, not even a domain, until it is bootstrapped once
with `be/scripts/bootstrap.sh` (step 4 of the Quick start; `docs/code-data-config-plan.md` §7). Keycloak
imports the `niord` realm, `helm/niord/files/niord-realm.json`, which is the integration tests' realm
(`niord/src/test/resources/keycloak/niord-realm.json`) byte for byte: `scripts/deploy.sh` stops while
the two differ and copies neither over the other. Its domain clients carry the domain ids of the base
product's sample (`niord-nw`, `niord-nm`, `niord-fa`, `niord-almanac`, `niord-annex`), since the backend
reads a domain's roles only from the client whose id is the domain's. The chart serves the administration app at `/`, the REST API and Keycloak
from one origin: the app image carries the deployable's build of the administration app and the
backend serves it (see [`be/README.md`](../README.md)). The realm's seeded users (e.g. **`sysadmin` /
`sysadmin`**) sign in through it. The public site, off by default, is described in
[The public site](#the-public-site); its development server runs from [`fe/`](../../fe/).

## Prerequisites

- **OrbStack** with Kubernetes enabled (Settings → Kubernetes), so `kubectl config
  current-context` is `orbstack`. OrbStack provides the container engine, a default
  `StorageClass`, LoadBalancer support, and shares built images with Kubernetes.
- **kubectl**, **helm** (`brew install helm`).
- **JDK 25** on the host (the image wraps a jar built with the reactor's Maven wrapper, and
  `build-image.sh` needs `java` to unpack it also when it reuses an existing jar).

## Quick start

```bash
cd k8s

# 1. Build the app image (mvn package and the administration app's production build on the
#    host, then the split of the jar and docker build, see "The image" below):
#    niord/niord-app-en:4.0.0-SNAPSHOT, the chart's default app image.
#    Needs `npm ci` run once in fe/; --no-web builds an image without the web app.
./scripts/build-image.sh
#   A country image (niord/niord-app-dk, niord/niord-app-gl) is built in that country's
#   repository since 2026-10-06; to run one here, pull it from Harbor (or build it there with
#   PUSH=0) and point the chart's app.image.repository at it.

# 2. Install/upgrade the Helm release and wait for rollout.
./scripts/deploy.sh

# 3. Check that the app is up.
curl -s http://localhost:18888/rest/actuator/health

# 4. Bootstrap the new database, once, with the base product's bootstrap.
#    A bootstrap runs once, on a new, empty database: the command refuses (409) a database that
#    holds data, naming the first table that holds a row, and one whose marker reads anything but
#    pending. The sample (Danish domains, series and messages) is not for a cluster: it is loaded
#    only into the loopback niord_sample by ./start-local.sh --sample, and its loader refuses any
#    other database. A job past its 10 minutes is asked to stop; the run reads failed once it has
#    ended. There is no retry: a failed run, and one whose backend stopped mid-run (a pod restart
#    leaves the marker in-progress), are refused for good, and a restart of a bootstrap job from
#    the batch page is refused too. On this cluster, or a staging or test one (production is not
#    covered here), the database is recreated instead:
#      a. a human drops the database (psql in the db pod: DROP DATABASE <name>, with the app scaled
#         to 0 so nothing holds a connection);
#      b. it is created empty again. A database listed in postgres.databases (the staging shape:
#         one infra release serving the app releases) is recreated by the databases Job at the next
#         sync, here at the next ./scripts/deploy.sh (helm/niord/templates/postgres/databases-job.yaml:
#         after every install and upgrade it creates each listed database that is missing, and it
#         never drops one; it renders only when the list is not empty). The Job's database is the
#         superuser's, and an app release on it (postgres.host) has no init container that hands it
#         over: before step c the superuser hands it to the app's user (psql in the db pod: ALTER
#         DATABASE <name> OWNER TO <the app's user>; "A database of someone else's", below). A
#         release whose app database is postgres.database on its own Postgres (this quick start's
#         niord) needs nothing here:
#         the app pod's database-user init container creates a missing postgres.database from
#         template_postgis, owned by the app's database user, before the backend starts in step c
#         ("The database users", below). A database of someone else's (postgres.host) is recreated
#         by whoever runs it. A database that is to hold a restored dump is the exception: create
#         and load it before the app starts, or the init container makes an empty one first and the
#         backend builds its schema in it;
#      c. the backend is started again (scaled back up), and its first start writes pending;
#      d. the same bootstrap command is run with the same archives.
#    A queued alert mail (a SECOM certificate near expiry, or a store that fails to load) does not
#    make the bootstrap refuse: the queued mails are not data. A refusal that names another table
#    no bootstrap wrote, on a database just created, means something wrote before the bootstrap:
#    a configuration fault. Fix the fault, then recreate the database.
(cd ../niord && "$JAVA_HOME/bin/jar" --create --no-manifest --file /tmp/niord-bootstrap.zip -C bootstrap .)
export NIORD_TOKEN=$(curl -s http://localhost:18888/auth/realms/niord/protocol/openid-connect/token \
  -d grant_type=password -d client_id=niord-web -d username=sysadmin -d password=sysadmin \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
../scripts/bootstrap.sh http://localhost:18888 /tmp/niord-bootstrap.zip
```

First rollout takes a few minutes: Liquibase builds the schema, Keycloak
imports the realm, and the Spring Boot app (GeoTools/Lucene/Artemis) starts behind a
generous startup probe. Watch it with:

```bash
kubectl -n niord get pods -w
```

| URL | What |
|-----|------|
| http://localhost:18888/rest | Niord REST API |
| http://localhost:18888/auth | Keycloak (admin console at `/auth/admin/`, `admin`/`admin`) |
| http://localhost:18888/rest/actuator/health | Health |
| http://localhost:18025 | MailHog (captured mail) |

Tear it down:

```bash
./scripts/teardown.sh          # remove the release, keep the data volumes
./scripts/teardown.sh --purge  # also delete PVCs + namespace (wipes the DB)
```

> The host-published ports (`18888` / `18025`) are in the 1xxxx range and do not clash
> with the repo's `docker compose` (8888/8080/8025), so both can run side by side.

## The image

`docker/Dockerfile` builds one image per deployable, with the parts that never change in layers
of their own, so that a registry stores them once ([`docs/image-split-plan.md`](../../docs/image-split-plan.md)):

| Layer | Holds | New digest |
|---|---|---|
| `eclipse-temurin:25-jre` | the Java runtime | when the base image is refreshed |
| dependencies | `/app/lib/` with the third-party jars, `/niord-home` | when a dependency version changes |
| `niord-base` | `/app/lib/niord-base-<version>.jar` | every build |
| the module | `/app/app.jar`, the thin jar | every build |
| web | `/app/web/`, the administration app | every build |

The executable jar is unpacked first (`scripts/split-jar.sh`, Spring Boot's own `extract`) into the
thin jar, `niord-base` and the third-party jars. `scripts/publish-images.sh` then lays the third-party
jars out as a directory tree with every entry at one fixed time, builds the `deps` stage twice without
cache to check that the layer comes out as the same bytes, builds the image from three named build
contexts, checks that it holds the right jars and, in CI, pushes it. `build-image.sh` runs the same
two scripts, so the local image is built by the code that builds the published one. The app starts
from the thin jar with a plain class path (`java -jar /app/app.jar`, a manifest `Class-Path` naming
`lib/*.jar`) instead of Spring Boot's nested-jar loader.

## How it works — single-origin reverse proxy

The one genuinely non-obvious part is the Keycloak URL.

Browsers reach Keycloak at the **same origin as the app, under `/auth`**, so a client reaches
the REST API and Keycloak through one public URL. That is exactly what the nginx proxy
provides:

```
browser ──▶ http://localhost:18888            (OrbStack LoadBalancer → nginx)
                       │
            ┌──────────┴───────────┐
   /auth/*  ▼                      ▼  everything else
     niord-keycloak:8080      niord-app:8888
```

- **Client → Keycloak** = `http://localhost:18888/auth` (same origin as `/rest`) — no
  CORS, no second host.
- **Token issuer.** Keycloak is started with `--hostname=http://localhost:18888/auth`,
  so it signs tokens with `iss = http://localhost:18888/auth/realms/niord`.
- **Token validation** (set in the app ConfigMap, standard Spring properties — no
  app-code change):
  - `issuer-uri` = `http://localhost:18888/auth/realms/niord` → validates the token `iss`.
  - `jwk-set-uri` = `http://niord-keycloak:8080/auth/realms/niord/protocol/openid-connect/certs`
    → fetches the signing keys over the **in-cluster** Service (the public
    `localhost:18888` URL is not reachable from inside a pod). Setting both makes Spring
    Boot build the decoder from `jwk-set-uri` and still add an issuer validator.

This works because OrbStack publishes the proxy's LoadBalancer port on the host's
`localhost`, but pods talk to each other (and to Keycloak's JWKS endpoint) over
cluster-internal Service DNS.

- **Backend → Keycloak admin API** (creating a per-domain client from the sysadmin UI,
  user and group administration) = the in-cluster Service as well: the chart sets the
  app's `authServerUrl` setting through an environment variable of the same name to
  `http://niord-keycloak:8080/auth` (`SettingsService` reads an environment variable named
  like a setting before the database). The admin client forwards the caller's token, so the
  URL comes from configuration only: a relative `authServerUrl` resolves against the
  `baseUri` setting, never against the `Host` of a request, and TLS is verified unless the
  `authServerTrustAllCertificates` setting says otherwise.

## The public site

The public site for mariners (`fe/ui-apps/info`) is the chart's sixth component, `info`, off by
default. Its image is a country's: each country's image pipeline builds `niord-<cc>-info` beside its
app image and under the same version, and niord-ng publishes none for the generic deployable. An
environment turns it on with `info.enabled` and `info.image.repository`; an empty `info.image.tag`
takes the app image's tag, so the site and the backend it reads are always the same version.

```
reader ──▶ https://<site host> ──▶ Ingress (info) ──▶ niord-info:8080   (nginx: the built files)
                                                             │  only the allow-listed, read-only /rest/ paths
                                                             ▼
                                                     niord-app:8888
```

The site has a host of its own (`info.ingress.host`) and an Ingress straight to its Service: the
chart's proxy sends everything on its host to the app, and both applications are served at `/`. The
container is the proxy for the site's host: it serves the built files, passes only the paths
[`fe/ui-apps/info/AGENTS.md`](../../fe/ui-apps/info/AGENTS.md) lists on to the app's Service over
plain http (`NIORD_SERVER_URL`, which the chart fills in), and empties every credential header on the
way. nginx resolves the app's Service name when it starts, so the site needs the app in the same
release; the render fails otherwise. It runs as the image's own unprivileged user, 101, and listens
on 8080. The chart offers no sea-chart server (`WMS_SERVER_URL`), so the site's `/wms/` answers empty.

To run it here, build a site image from the base build and tag it with the app image's tag (from
`be/k8s`, like the rest of this page; `npm ci` run once in `fe/`):

```bash
(cd ../../fe && npm run build:info && npm run image:info -- --tag niord-info:4.0.0-SNAPSHOT)
EXTRA_HELM_ARGS="--set info.enabled=true --set info.image.repository=niord-info" ./scripts/deploy.sh
kubectl -n niord port-forward svc/niord-info 18400:8080   # then http://localhost:18400
```

The local port is in the 1xxxx range, like the proxy's and MailHog's: `docker-compose.yml`'s Keycloak
holds 8080.

## Keycloak in production mode

`keycloak.mode: production` is for staging and production. The local bundle and the test
environments keep `dev`, with the test realm and its test users. The render refuses dev mode when
`externalBaseUri` or `externalKeycloakUrl` is not on `localhost`, `127.0.0.1` or `[::1]`, unless
`keycloak.devModeOffLocalhost` is true, which a `test-<name>` environment sets and staging and
production never do: a values file that forgets `keycloak.mode` fails instead of giving a cluster
the test realm and `admin`/`admin`.

- **`start` instead of `start-dev`**, on the database `keycloak.database` (`keycloak`) of the
  release's Postgres, connecting as a database user of its own, `keycloak.db` (as the user of
  `postgres.existingSecret` on a database of someone else's: "The database users", below). An
  init container creates the database when it is missing. Keycloak serves plain HTTP behind the
  proxy and takes the browser's scheme and address from its `X-Forwarded-*` headers. It runs one
  replica with a local cache. `start` builds Keycloak for these options on every start, some
  seconds before it listens.
- **The bootstrap admin comes from `keycloak.existingSecret`**: the render fails without it.
  Keycloak creates that admin in the realm `master` on the first start only. It stays the master
  realm's admin; the admin console calls it *temporary* in a banner. Replacing it is optional, and
  the Secret then no longer holds a working password.
- **The realm** is the test realm, derived by the template helper `niord.keycloak.realmJson`. It
  keeps the clients, roles and the `Sysadmin` group, and no user. With `keycloak.testUsers: true` it
  keeps the test users (`sysadmin`/`sysadmin`, and `testuser`, `testeditor`, `testadmin`,
  `testdomaineditor` and `testadminelsewhere` with the password `test`), whose
  passwords are in this public repository. It drops the
  `niord-web-admin` client with its service account and every client secret. The app's admin calls
  carry the signed-in user's own token, so nothing uses that client. Keycloak generates new secrets
  for the confidential clients. `niord-web` accepts redirects only to `<externalBaseUri>/*`, from
  the origin of `externalBaseUri`, requires PKCE (`S256`) and has the password grant (*Direct
  access grants*) switched off: a script that signs in with `grant_type=password`, as the
  publications go-live scripts do, needs it switched on in the admin console for the run. The
  realm turns on brute-force protection and requires https for addresses outside the private
  networks (`sslRequired: external`).
- **The first administrator** is made by hand, since the realm has no user: in the admin console
  (`<externalKeycloakUrl>/admin/`, realm `<keycloak.realm>`), Users, Add user, Credentials, Set
  password, then Groups, Join group, `Sysadmin`. That user then makes the others. The fresh-database
  bootstrap above signs in as that user instead of `sysadmin`, with the password grant switched on
  for the run as said above.
- **`niord-web` carries the client scope `kc-admin-api`**, in both modes. From Keycloak 26.8.2 the
  Admin REST API accepts only a token with that scope and its audience, and the app's admin calls
  (a domain's client, the users, the groups) carry the signed-in user's `niord-web` token.
  Keycloak 26.8.2 adds the scope to a realm it upgrades, but gives it only to its own `admin-cli`
  and `security-admin-console`, so a realm imported from an older chart answers those calls with
  401 until the scope is added to `niord-web` by hand: Clients, `niord-web`, Client scopes, Add
  client scope, `kc-admin-api`, Default.
- **Successful logins reach the log.** The realm's `jboss-logging` event listener writes failed
  events at `WARN` and successful ones at `DEBUG`, below the console level, so a successful
  login never showed. The deployment sets the listener's success level to `INFO`
  (`KC_SPI_EVENTS_LISTENER__JBOSS_LOGGING__SUCCESS_LEVEL`), in both modes: one line per
  `LOGIN`, `LOGOUT` and token exchange, with the user id, the client and the address, on stdout,
  where the cluster's log collector keeps it. Nothing is stored in Keycloak's database
  (`eventsEnabled` stays off).
- **Imported once.** Keycloak imports the realm only when its database does not hold it yet: on
  the first start. From then on the database is the realm's record, so a password changed or a
  user added in the admin console (`<externalKeycloakUrl>/admin/`) stays. A later chart change, or
  a new `externalBaseUri`, does not reach it: change `niord-web`'s redirect URIs and web origins in
  the admin console.
- **A realm imported before November 2026 names its domain clients `niord-client-nw`,
  `niord-client-nm`, `niord-client-fa`, `niord-client-almanac` and `niord-client-annex`.** No
  domain has those ids, so no role on them counted in any domain. The realm file now names them
  after the domains (`niord-nw` ...). A dev-mode Keycloak imports the new file at its next start,
  and the upgrade to this chart restarts it (the realm's checksum changes): it then has the new
  clients and the further test users, and the old clients are gone, as is everything made
  in its console since its last start. A production-mode realm, and a realm made by hand on a
  shared Keycloak, are not imported again and keep the old clients: in the admin console rename
  each (Clients, the client, Settings, *Client ID*, Save). Its roles and every user's and group's
  role mappings stay with it, and the next token carries them under the new id. Keycloak refuses
  the rename where a client with the domain's id exists already (made with the Domains page's
  *Create in Keycloak*, or by hand): keep that client, give each user and group with a role on the
  old `niord-client-*` client the same role on it (Users or Groups, the user or group, Role
  mapping), then delete the old client. A domain a restored
  database holds that has no client yet gets one from the Domains page of the administration app,
  or in the admin console. The realm file holds the sample's five domain clients and no other, so
  such a client lasts only on a production-mode realm or a shared Keycloak: a dev-mode Keycloak
  imports the file again at every restart and loses the client with every role given on it. There,
  give people their roles on `niord-client-master`, which count in every domain (K8S3 in
  [`be/docs/potential-errors.md`](../docs/potential-errors.md)).
- **A realm imported before November 2026 gives the base client `niord-client-master` no `user`
  role, and its `editor` holds none.** A role on that client counts in every domain, but there
  `editor`, `admin` and `sysadmin` did not make a person a user, so someone whose roles are on it
  alone was refused the actions that ask for `user` (comments, tags, the user search and others)
  in any domain where they hold no role of the domain's own client. The realm file now has the
  ladder every domain client has. A dev-mode Keycloak gets it at its next start, as above. A
  production-mode realm and a realm on a shared Keycloak keep the old one until it is added in the
  admin console: Clients, `niord-client-master`, Roles, Create role `user`; then the role
  `editor`, Associated roles, Assign role, the client role `user` of `niord-client-master`. The
  next token of each person with `editor`, `admin` or `sysadmin` there carries `user`.
- **A realm imported from a chart of before 21 November 2026 has a client role
  `publication-curate`** on each domain client and on `niord-client-master`, held through `admin`
  and `sysadmin` and given to some test users directly, and, where the test users were kept, the
  test user `testcurator` (password `test`, with `user` and that role on `niord-nw`). The realm
  file now has neither, and nothing in Niord asks for the role. The upgrade to the chart that
  drops them restarts Keycloak once (the realm's checksum changes): a dev-mode Keycloak then
  imports the new file, without the role and the user, and loses what was made in its console
  since its last start. A production-mode realm, and a realm on a shared Keycloak, are not
  imported again and keep both: in the admin console delete the role from each client that has it
  (Clients, the client, Roles, the role, Delete), which also takes it off the composite roles and
  every user's and group's role mappings, and delete the user `testcurator` (Users).

The production-mode realm holds no test user: their passwords are in this repository, which is
public. `keycloak.testUsers: true` keeps them, for a staging with no real data on a closed network
and never for production; the release's notes then print a warning. A realm made before keeps its
users, because a realm is imported once: they are removed in the admin console (F-X92 in
[`docs/security-review-2026-09.md`](../../docs/security-review-2026-09.md)).

To try it locally, create a Secret with the keys `username` and `password` for the bootstrap
admin and upgrade with `--set keycloak.mode=production --set keycloak.existingSecret=<secret>`
(and `--set keycloak.testUsers=true` to sign in with the test users). Production mode also takes
the database users from Secrets (the render fails without `postgres.existingSecret`), so a local
trial names three that hold the local defaults:

```bash
kubectl -n niord create secret generic local-db-app   --from-literal=username=niord_app --from-literal=password=niord_app
kubectl -n niord create secret generic local-db-admin --from-literal=username=niord     --from-literal=password=niord
kubectl -n niord create secret generic local-kc-db    --from-literal=username=keycloak  --from-literal=password=keycloak
# then, with the upgrade above:
#   --set postgres.existingSecret=local-db-app --set postgres.admin.existingSecret=local-db-admin \
#   --set keycloak.db.existingSecret=local-kc-db
```

A release that ran in dev mode moves over in place.

### A shared Keycloak outside the release

A cluster can run one Keycloak for every country (devops-shared, `platform/test/keycloak/`: the
test cluster's is `https://login.brs-test.e-navigation.dk/auth`), with a realm per deployment. A
release then renders no Keycloak of its own:

```yaml
externalKeycloakUrl: https://login.brs-test.e-navigation.dk/auth   # another origin than externalBaseUri
keycloak:
  enabled: false
  host: keycloak-http.keycloak.svc    # the shared Keycloak's Service, from any namespace
  service:
    port: 80
  realm: niord-dk                     # this deployment's realm on it
  mode: production                    # a staging or production release: see below
```

With `keycloak.enabled: false` the mode renders no Keycloak and triggers no Keycloak check. A
staging or production release sets it all the same: it holds the release to the database-Secret
guard ("The database users", below), which refuses `keycloak.mode: production` without
`postgres.existingSecret`. A release left in `dev` mode on a shared Keycloak is held to neither
guard, since the dev-mode guard concerns a Keycloak of the release's own.

What follows from `externalKeycloakUrl` being on another origin (`niord.keycloak.external`):

- **The proxy routes no `/auth`.** The browser goes to the shared Keycloak itself. The web app
  learns where from the backend at start, `GET /rest/settings/keycloak`: the Keycloak URL, the realm
  and its client id, derived from the token issuer the backend validates
  (`externalKeycloakUrl/realms/<realm>`), so its build's `/auth` and `niord` are replaced at boot.
  The Content-Security-Policy admits the issuer's origin already.
- **The backend's admin calls** (a new domain's client, users, groups) go to `keycloak.host` over
  plain HTTP, in the realm of the issuer, with the signed-in user's token as before. The user needs
  the same `realm-management` roles in that realm.
- **The realm is created by hand**, once, by an admin of the shared Keycloak, from a file this
  chart renders. The deployment's own values render no realm (its Keycloak is off), so render one
  with Keycloak on, in production mode, and the deployment's realm and site:

  ```bash
  helm template realm helm/niord --set keycloak.mode=production --set keycloak.existingSecret=unused \
    --set postgres.existingSecret=unused --set postgres.admin.existingSecret=unused-admin \
    --set keycloak.db.existingSecret=unused-kc --set keycloak.realm=niord-dk \
    --set externalBaseUri=https://niord-dk-staging.example \
    --set externalKeycloakUrl=https://login.example/auth --show-only templates/keycloak/configmap-realm.yaml
  ```

  Take `niord-realm.json` from the ConfigMap and create the realm from that file in the admin
  console. The rendered realm carries the deployment's name, redirect URIs and web origins, no ids
  (so a second country's file is accepted too), the built-in consoles' paths under the new name,
  and no user (add `--set keycloak.testUsers=true` only for a staging with no real data). Nothing
  imports it later: the shared Keycloak's
  database is its record.
- **No wait for Keycloak.** The app's wait-for-keycloak init container only waits for a Keycloak
  of the release. A shared Keycloak is stopped for its upgrades, and an app pod that restarts then
  still serves the site and the API; the signing keys are fetched when the first token arrives.

## The database users

The app connects to its database as a user that owns that database and is not a superuser. A
break-in through the app then reaches the app's own data and nothing else: not the Postgres
container's files or commands, and not Keycloak's database beside it
(`docs/security-review-2026-09.md`, F-X100; the plan is `docs/database-user-plan.md`).

A release that renders Postgres has two users, each in a Secret with the keys `username` and
`password`:

| | Value | Local default | Who connects as it |
|---|---|---|---|
| The superuser, which the Postgres image creates on a new volume | `postgres.admin.existingSecret` | `niord` / `niord` | the app pod's `database-user` init container, the databases Job, Keycloak's `create-database` init container. No application. |
| The app's user | `postgres.existingSecret` | `niord_app` / `niord_app` | the app |
| Keycloak's user (`keycloak.mode: production` only) | `keycloak.db.existingSecret` | `keycloak` / `keycloak` | Keycloak |

The `database-user` init container runs before every start of the app, as the superuser:

- It creates the app's user when it is missing, and sets its password to the Secret's every time.
  A changed Secret takes effect when the app pod is started again. It also holds the user to an
  ordinary login: no superuser, no right to create databases or users.
- It creates `postgres.database` when it is missing, from the image's `template_postgis`.
- It hands over a database that belongs to someone else: the one the image makes on a new volume,
  one from before the app had its own user, one the superuser recreated or restored. The
  database and every table, sequence, view, function and type in its schema `public` that no
  extension owns get the app's user as owner. No row is touched and nothing is dropped. The
  extensions stay the superuser's: only a superuser may create PostGIS, and the app needs no
  more than to use it.
- Every session it opens has the search path `pg_catalog`, set by the client, which a search path
  set on the database does not replace. It works as the superuser inside a database the app's
  user owns, so nothing that user creates there may be what its statements call
  (`docs/security-review-2026-09.md`, SECREV-4). `ChartDatabaseUserScriptITTest` in niord-base
  runs the script in the chart's Postgres image.

Keycloak's `create-database` init container does the same for `keycloak.database` and Keycloak's
user. The app's container holds the app's user only; the superuser's Secret is named in the init
container's environment and nowhere else in the pod.

```bash
kubectl -n niord logs deploy/niord-app -c database-user       # what it made or handed over; empty when there was nothing to do
```

**One account for both is refused.** A release that renders the app and its own Postgres and names
`postgres.existingSecret` without `postgres.admin.existingSecret` fails the render: that one
Secret would be the superuser's and the app's (and Keycloak's), and the app would connect as the
superuser of its database server. Until November 2026 every release ran that way. A release
without the app (an infra release) and a release on a database of someone else's are not
concerned.

**Values from before November 2026 that name one Secret** now fail the render; moving them is one
new Secret and two lines of values:

```yaml
postgres:
  existingSecret: niord-postgres-app      # new: any user name but the superuser's, e.g. niord_app
  admin:
    existingSecret: niord-postgres-admin  # the Secret the environment had in postgres.existingSecret
```

The Postgres pod keeps its Secret and is not restarted. The app pod is replaced, its init
container creates the user and hands over the database, and the app starts as the new user. An
environment with its own Keycloak in production mode names a third Secret,
`keycloak.db.existingSecret`, in the same change; the render fails without it. The chart refuses
values that name the superuser's Secret without the app's, the app's without the superuser's in a
release that renders the app and its own Postgres, the same Secret twice, or the superuser's
Secret in a release that renders no Postgres. It also refuses `keycloak.mode:
production` without `postgres.existingSecret`, so a release in production mode never runs on the
chart's default users; a staging or production release on a shared Keycloak sets that mode too
("A shared Keycloak outside the release", above).

**A database of someone else's** (`postgres.enabled: false` and `postgres.host`: another release's
Postgres, or a hosted database) comes with the user: `postgres.existingSecret` names it, and
nothing here creates or alters anything. The app needs a database that its user owns, with the
PostGIS extension already created in it by whoever may (a superuser), and no more: it builds its
schema itself. A Keycloak in production mode on such a database still connects as the app's user
and creates its own database, which a hosted database will not allow; its own host and user there
are step R3 of `docs/repo-files-in-database-plan.md`.

**In CloudNativePG mode** there is no superuser's Secret and no init container that makes users:
the operator makes the owner from `postgres.username` and the owner's Secret
([CloudNativePG](#cloudnativepg)).

**A local release from before** is handed over the same way at its next `./scripts/deploy.sh`: the
chart now renders two Secrets, the superuser keeps its name (`niord`), and the app's database
goes to `niord_app`. Values that set `postgres.username` or `postgres.password` now name the
app's user; the superuser's are `postgres.admin.username` and `postgres.admin.password`.

## CloudNativePG

The database can run as a `Cluster` of the [CloudNativePG](https://cloudnative-pg.io) operator (CNPG)
instead of the plain pod. The mode is off by default: local runs and the tests keep the pod and
`imresamu/postgis`. It is on when `postgres.cnpg.databases` has an entry, and the cluster must
already run the operator, 1.30 or newer. The chart renders only a database's own objects. The plan
behind it is devops-shared, `docs/database-plan.md`.

```yaml
postgres:
  username: niord_app         # the owner: the `username` of the Secret below, word for word
  existingSecret: niord-postgres-app
  cnpg:
    image: <registry>/cloudnative-pg/postgis:18.6-3.6.4-202610261018-standard-trixie
    databases:
      - number: 1
```

For each entry the chart renders:

- **A `Cluster`** named `<fullname>-db-<number>`: one instance, no disruption budget, the image of
  `postgres.cnpg.image`, the pull secrets of `global.imagePullSecrets`, a volume of
  `postgres.persistence.size` and the resources of `postgres.resources`. The operator makes the
  pod, the volume and one Service, `<fullname>-db-<number>-rw`. At the first start it makes
  `postgres.database`, owned by `postgres.username`, with the password of the owner's Secret
  (`postgres.existingSecret`) and the locale of `postgres.cnpg.locale`.
- **A `Database` object** of the same name that lists the `postgis` extension. The operator creates
  it as the superuser. A CNPG image has no `template_postgis`, and of the plain image's four
  extensions the app uses only this one.

What differs from the plain pod:

- **Nobody holds a superuser's password.** The app pod's `database-user` init container is not
  rendered. The render refuses `postgres.databases` and a Keycloak in production mode in the
  release: both need a superuser.
- **The image is one value, used as it is.** `global.imageRegistry` does not apply to it: CNPG's
  images reach a cluster through another registry project than the chart's other images. Pin a
  full tag.
- **The owner's name is a value that counts.** `postgres.username` must be the `username` in the
  owner's Secret. The operator does not refuse a Secret that names someone else: the database
  comes up healthy and its owner has no password. The app pod's `wait-for-postgis` init container
  stops with a message that names both.
- **The owner's Secret carries the label `cnpg.io/reload: "true"`.** Only then does a changed
  password reach the database. The Secret the chart renders has it; an environment's own Secret
  needs it too.
- **The app waits for PostGIS.** The operator creates the extension some seconds after the database
  answers (22 seconds in a local run), and the app's first migration fails on a database without
  it. So the app pod has an init container, `wait-for-postgis`, that connects as the owner and
  waits until the extension is there.

### The databases have numbers

- A number is used once and never again, also after its entry is gone. Backups are kept under the
  database's name, and a new database cannot use an old one's folder.
- The numbers rise down the list, and the app uses the last entry. `postgres.host` names another
  database for as long as the app is to stay there: an older number's Service, or the plain pod's.
- A restore, a new major PostgreSQL version or a second try is a new entry with the next number,
  next to the old one. Nothing is changed in place. An entry may name its own `image`.
- Removing an entry deletes that database and its volume. Nothing else does.
- `postgres.database`, `postgres.username` and `postgres.cnpg.locale` are read when a database is
  created. A change afterwards reaches no existing database.

### Checking a render, and a local run

```bash
# The render: one Cluster and one Database for each entry, and the app on the last one.
helm template niord helm/niord --set postgres.enabled=false \
  --set 'postgres.cnpg.databases[0].number=1' | grep -E '^kind: (Cluster|Database)|jdbc:|name: wait-for'

# A local run needs the operator in the cluster, once. It adds 11 CRDs, two webhook
# configurations and the namespace cnpg-system.
helm install cnpg oci://ghcr.io/cloudnative-pg/charts/cloudnative-pg --version 0.29.2 \
  -n cnpg-system --create-namespace --wait
EXTRA_HELM_ARGS="--set postgres.enabled=false --set postgres.cnpg.databases[0].number=1" ./scripts/deploy.sh

kubectl -n niord get cluster,database            # "Cluster in healthy state", and APPLIED true
kubectl -n niord logs deploy/niord-app -c wait-for-postgis
# Inside the pod only the postgres system user may use the socket; everyone else connects over
# the network with a password.
kubectl -n niord exec niord-db-1-1 -c postgres -- psql -d niord -c 'select postgis_full_version()'

# Removing the operator again: the release first, then the CRDs, which helm keeps.
helm uninstall cnpg -n cnpg-system
kubectl get crd -o name | grep '\.postgresql\.cnpg\.io$' | xargs kubectl delete
kubectl delete namespace cnpg-system
```

### Moving from the plain pod

The pod and a new database exist side by side in one release while the data moves. Each step is
one change of the values:

1. **Add the database and stop the app:** `postgres.cnpg.databases: [{number: 1}]` and
   `app.replicaCount: 0`. `postgres.enabled` stays `true`, and the environment still names
   `postgres.admin.existingSecret`: the pod keeps running, untouched.
2. **Dump the pod's database and restore it into the new one**, with the commands below. The new
   database must be empty: the app has not started on it.
3. **Start the app:** `app.replicaCount: 1`. It is on the new database, the last entry.
4. **Later, remove the pod:** `postgres.enabled: false` and no `postgres.admin.existingSecret`, in
   one change. The pod's volume and every database in it go with it.

Until step 3 the pod's database is complete, and going back is taking the entry out again. A second
try takes the next number.

A database made by the plain image holds four extensions, and the new one has only `postgis`. So the
dump's list is restored without what belongs to the extensions: the four extensions and their
comments, the schemas `tiger` and `topology`, and the entries for the rows of the extensions' own
settings tables, `spatial_ref_sys` among them. The restore runs as the owner, over the network, in
one transaction: one error undoes all of it. Tried on 2026-11-28 with a bootstrapped database, from
PostGIS 3.6.1 to 3.6.4: 19 of the dump's 706 entries left out, no error, and the same row count in
all 113 tables.

```bash
NS=niord; OLD=deploy/niord-db; NEW=niord-db-1; DB=niord
SECRET=niord-db-credentials          # the owner's Secret, postgres.existingSecret
IMAGE=ghcr.io/cloudnative-pg/postgis:18.6-3.6.4-202610261018-standard-trixie   # the new database's

# 1. Which database: the one in postgres.database. Dump it, as the pod's superuser.
kubectl -n $NS exec $OLD -- sh -c 'psql -U "$POSTGRES_USER" -d postgres -Atc "select datname, pg_size_pretty(pg_database_size(datname)) from pg_database where not datistemplate"'
kubectl -n $NS exec $OLD -- sh -c "pg_dump -U \"\$POSTGRES_USER\" -Fc $DB" > niord.dump

# 2. A client pod with the new database's image, connected as the owner. On a cluster that pulls
#    with a Secret, add imagePullSecrets.
kubectl -n $NS apply -f - <<POD
apiVersion: v1
kind: Pod
metadata: {name: pg-client}
spec:
  restartPolicy: Never
  securityContext: {runAsNonRoot: true, runAsUser: 26, runAsGroup: 26, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: pg
      image: $IMAGE
      command: ["sleep", "3600"]
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      env:
        - {name: PGHOST, value: $NEW-rw}
        - {name: PGDATABASE, value: $DB}
        - {name: PGUSER, valueFrom: {secretKeyRef: {name: $SECRET, key: username}}}
        - {name: PGPASSWORD, valueFrom: {secretKeyRef: {name: $SECRET, key: password}}}
      volumeMounts: [{name: work, mountPath: /work}]
  volumes: [{name: work, emptyDir: {}}]
POD
kubectl -n $NS wait --for=condition=Ready pod/pg-client --timeout=120s
kubectl -n $NS exec -i pg-client -- sh -c 'cat > /work/niord.dump' < niord.dump

# 3. The list, without what belongs to the extensions. Read what is left out: it is nothing but
#    extensions, tiger, topology and spatial_ref_sys. The two numbers after it must be 0: every
#    kept entry is in the schema public, and the old spatial_ref_sys held no row that PostGIS did
#    not bring itself.
kubectl -n $NS exec pg-client -- sh -c '
  cd /work && pg_restore -l niord.dump > all.list
  OUT="^[0-9]+; [0-9]+ [0-9]+ (EXTENSION - |COMMENT - EXTENSION |SCHEMA - (tiger|tiger_data|topology) |COMMENT - SCHEMA (tiger|tiger_data|topology) |[A-Z ]+ (tiger|tiger_data|topology) |TABLE DATA public spatial_ref_sys )"
  grep -E "$OUT" all.list; grep -vE "$OUT" all.list > niord.list
  echo "kept entries outside public: $(grep -E "^[0-9]+;" niord.list | grep -vcE "^[0-9]+; [0-9]+ [0-9]+ [A-Z ]+ public ")"
  echo "rows of spatial_ref_sys in the dump: $(pg_restore -a -n public -t spatial_ref_sys -f - niord.dump | grep -cE "^[0-9]+[[:space:]]")"'

# 4. The restore: zero errors, or nothing.
kubectl -n $NS exec pg-client -- sh -c 'cd /work && pg_restore -L niord.list --no-owner --no-acl \
  --single-transaction -d "$PGDATABASE" niord.dump && echo "restored with no error"'

# 5. The row count of every table that no extension owns, old against new: no difference.
cat > counts.sql <<'SQL'
select format('select %L || ''|'' || count(*) from %I.%I', n.nspname || '.' || c.relname, n.nspname, c.relname)
  from pg_class c join pg_namespace n on n.oid = c.relnamespace
 where c.relkind in ('r', 'p') and n.nspname not in ('pg_catalog', 'information_schema') and n.nspname !~ '^pg_'
   and not exists (select 1 from pg_depend d where d.classid = 'pg_class'::regclass and d.objid = c.oid and d.deptype = 'e')
 order by 1 \gexec
SQL
kubectl -n $NS exec -i $OLD -- sh -c "psql -X -At -U \"\$POSTGRES_USER\" -d $DB" < counts.sql > old-counts.txt
kubectl -n $NS exec -i pg-client -- psql -X -At < counts.sql > new-counts.txt
diff old-counts.txt new-counts.txt && echo "$(wc -l < new-counts.txt) tables, the same row counts"

kubectl -n $NS delete pod pg-client
```

The dump holds everything the database holds, the files of the repository included. Keep it
readable by you alone, and delete it when the pod is removed.

## Configuration

- **`k8s` Spring profile** (`SPRING_PROFILES_ACTIVE=k8s`) — no bean is gated on it today. The
  `k8s` profile (rather than `local`) is used so `application-local.yml`'s `localhost`
  datasource/issuer/mail hardcodes are NOT loaded.
- **`adminAppUrl` setting** — the app image serves the administration app on the release's
  own origin, so the setting stays empty and message links in mails and tweets use `baseUri`.
  Where the administration app runs on another origin, set `adminAppUrl` to its URL (Settings
  page): the links then point there, and a request for the backend's `/` is redirected there
  (see [`be/README.md`](../README.md)).
- **`baseUri` setting** — the mails and PDFs the backend renders load their style sheets and
  images (logos, repository files, map thumbnails) from the `baseUri` origin and from nowhere
  else (`SameOriginFetch`). Set it to the canonical public URL, one that serves those files
  itself. A redirect is followed only to the same origin, or to the same host with http and
  https switched while both are on their default ports: that covers the backend's own
  thumbnail redirects behind a proxy that ends TLS, but not a `Location` on another port
  (WEB10 in [`be/docs/potential-errors.md`](../docs/potential-errors.md)); such an image is
  then missing from the mail or PDF. Every template mail (a message mail, a comment mail, a
  mailing list's delivery) also fetches its stylesheet, `<baseUri>/css/templates/mail.css`, from
  the backend pod: when the pod cannot fetch it, the mail goes out without its styling and the
  log has a WARN "Mail sent without the stylesheet …". The chart does not derive it from
  `externalBaseUri`: a cluster environment pins it in its values, `app.settings.baseUri`, normally
  the value of `externalBaseUri`, and the pod must be able to fetch from that address. A
  database restored from another system carries that system's address. Locally the setting
  keeps the base product's `http://localhost:8888`, which the pod itself answers, so the mails'
  styling and images work and only their links name a port the browser does not use.
- **Pinned settings (`app.settings`)** — a setting of the Settings page whose value is one
  text, number or `true`/`false` can be pinned this way: the chart renders each key as an
  environment variable of that name, which the app reads before the stored row, so the pinned
  value wins and the Settings page still shows (and edits) the stored row without saying so. A
  list or object setting (type `json`: `modelLanguages`, `siteLanguages`, `infoSiteLanguages`,
  `editorFieldsBase`, `editorFieldsNw`, `editorFieldsNm`, `stdTemplateFields`) cannot be pinned:
  the app takes the variable as a text and does not parse it. Typical keys besides `baseUri`:
  `mode`, `mailValidRecipients`, `mailSender`. A value is a text, a number or `true`/`false`, and
  a whole number renders as an integer (`1000000`, not `1e+06`); anything else fails the render.
  An empty value is not rendered. The render refuses a key
  that names a password, secret or token, a key that does not start with a lower-case letter, a
  key with a dot or one starting `spring_`, `server_`, `management_`, `logging_` or `niord_`
  (Spring reads such a variable as a property and lets it outrank the chart's, and `niord.home`
  is the chart's `NIORD_HOME`), `authServerUrl` (the chart sets it) and a key also set in
  `secom.settings`. A `-D<settingKey>=<value>` in `app.javaOpts` pins a setting too, and wins
  over `app.settings` (the app reads a system property before an environment variable), but no
  render checks it, and `app.javaOpts` is one string that replaces the chart's default whole: a
  values file that sets it for a `-D` must repeat `-XX:MaxRAMPercentage=70.0 -XX:+UseG1GC`, or
  the JVM sizes its heap and chooses its collector itself. `app.settings` is the supported way. A
  setting whose value is a secret (`pdfEncryptionPassword`, `wmsPassword`) can be pinned neither
  way: the values are no place for a secret, and the chart has no value that passes a Secret's
  key to the app as a setting (K8S2 in [`be/docs/potential-errors.md`](../docs/potential-errors.md)),
  so such a setting is the stored row, and a restored database brings the other system's.
- **Map images and their tiles** — the backend draws a message's map image itself, on
  OpenStreetMap tiles it fetches from the host in the `osmUrl` setting (by default
  `https://tile.openstreetmap.org`), so the app pod needs egress to that host over https. Where
  the cluster reaches the internet through a proxy, pass `-Dhttps.proxyHost=<host>
  -Dhttps.proxyPort=<port>` and `-Dhttp.nonProxyHosts=<in-cluster hosts>` through
  `app.javaOpts`: the tile requests use the JVM's default proxy selector. Set the
  `mapTileContact` setting (Settings page) to the operator's e-mail address or URL, which the
  OpenStreetMap tile policy asks to find in the User-Agent; left empty, the backend names
  `baseUri` there. The tiles are cached in `tile-cache` under `NIORD_HOME`, on the app's volume,
  and a nightly sweep keeps that folder below 200 MB; nothing serves it. The schema update at
  start needs no egress: Liquibase's own usage analytics, on by default in the library and a call
  to `config.liquibase.com` at every start, are off in `config/niord-core-defaults.yml`
  (`spring.liquibase.analytics-enabled: false`).
- **Retired settings** — no external static-map service is called any more, and the
  `mapImageServer`, `mapImageIndent` and `osmSourceUrl` settings are gone: Liquibase deletes
  their rows. A `niord.json` of the deployment's own in `NIORD_HOME` that still sets one needs no
  change: the backend skips, with a warning, every key of that file that no settings catalog
  declares.
- **Spring properties** — a `ConfigMap` rendered as `/config/application.yaml` and loaded
  via `SPRING_CONFIG_ADDITIONAL_LOCATION` (higher precedence than the packaged
  `application.yml`). Sets the datasource (`niord-db`), the OAuth2 resource-server URLs and the
  mail host. The actuator probe
  groups the probes use ship in the image (`config/niord-core-defaults.yml`).
- **The mail relay** — locally and on the stagings the app sends to MailHog, which wants no
  login and no TLS. An environment on a relay that asks for them seals a Secret with the keys
  `username` and `password` beside its values and sets, with `mailhog.enabled: false`:
  `smtp.host`, `smtp.port` (587 for STARTTLS, 465 for implicit TLS), `smtp.existingSecret` and
  `smtp.tls` (`starttls` or `ssl`). With `starttls` a relay that stops offering STARTTLS fails
  the mail instead of receiving the login in clear text. With `smtp.tls: none`, the default, a
  login from `smtp.existingSecret` is sent in clear text; the render does not say so, and the
  app only logs a WARN at its start ("The mail login (niord.smtp.username) is sent to the relay
  without TLS …"): leave it `none` only for a relay reached over a trusted network. The backend
  reads `niord.smtp.username`, `.password` and `.tls` only from the base version whose mail
  session (`MailConfig`) carries the login and TLS; an older app image ignores them and sends
  with no login and in clear text, and nothing reports it. Set these values only with an app
  image of that version or later.
- Health probes use the Spring Boot liveness/readiness groups at
  `/rest/actuator/health/{liveness,readiness}` (the actuator base-path is `/rest/actuator`).
- **`enableServiceLinks: false`** on the app pod — otherwise Kubernetes injects legacy
  Docker-link env vars (e.g. `NIORD_SMTP_PORT=tcp://10.x.x.x:1025`) that Spring's
  relaxed binding maps onto `niord.smtp.port`, breaking the context start.

### Useful values (`helm/niord/values.yaml`)

| Key | Default | Notes |
|-----|---------|-------|
| `externalBaseUri` | `http://localhost:18888` | The single public origin (app). |
| `externalKeycloakUrl` | `http://localhost:18888/auth` | Same origin + `/auth`; also the token issuer. Change the proxy port → change both. |
| `proxy.service.port` | `18888` | The host port nginx is published on. |
| `proxy.ingress.enabled` | `false` | An Ingress for `externalBaseUri`'s host, to the proxy's plain HTTP port, for a cluster with an ingress controller. Needs `externalBaseUri` as `https://<host>` with no port (the render fails otherwise). nginx then passes on the controller's `X-Forwarded-Proto` and `X-Forwarded-Port`. Set `proxy.service.type: ClusterIP` with it, so those headers can only come from the controller. |
| `proxy.ingress.className` | `traefik` | The IngressClass that serves it; the test cluster's Traefik serves only `traefik`. |
| `app.image.tag` | `""` | `""` = the chart's appVersion: `4.0.0-SNAPSHOT` locally (the tag `build-image.sh` gives its image), `4.0.<run>` in a published chart — pinning the chart version pins the app image. |
| `global.imageRegistry` | `""` | Registry prefix for every image; `""` = each image's upstream registry. A cluster environment points it at the Harbor project, where the images are mirrored under their upstream paths. |
| `global.imagePullSecrets` | `[]` | `{name: …}` entries added to every pod (Harbor refuses anonymous pulls). |
| `<component>.image.{registry,repository,tag,pullPolicy}` | upstream | Same shape for `app`, `proxy`, `postgres`, `keycloak`, `mailhog`, `info`, `busybox`. The old forms (`image: repo:tag`, a sibling `pullPolicy`) fail the render with the key to use. |
| `app.enabled`, `proxy.enabled`, `postgres.enabled`, `keycloak.enabled`, `mailhog.enabled` | `true` | Per-component switches; a release renders only the enabled ones. |
| `info.enabled` | `false` | The public site (see [The public site](#the-public-site)). Needs `app.enabled` and `info.image.repository`, the country's image (`niord-<cc>-info`); the render fails otherwise. |
| `info.image.tag` | `""` | `""` = the app image's tag (`app.image.tag`, or the chart's appVersion when that is `""`). |
| `info.ingress.enabled`, `info.ingress.host`, `info.ingress.className` | `false`, `""`, `traefik` | An Ingress for the site's own host, a name alone, straight to its Service. On the test cluster the host is the namespace's name with `-info` appended, the one the cluster's Ingress rule allows. |
| `info.teaserFrameAncestors` | `*` | The pages allowed to embed the teaser (`NIORD_TEASER_FRAME_ANCESTORS`, a CSP `frame-ancestors` source list). |
| `postgres.host`, `keycloak.host`, `smtp.host` / `smtp.port` | `""` | Where the app finds a dependency another release renders. Services are named `<fullname>-db`, `-keycloak` and `-smtp`, where `<fullname>` is the release name plus `-niord` unless it already contains "niord" (release `staging-infra` → `staging-infra-niord-db`, `staging-infra-niord-keycloak`, `staging-infra-niord-smtp`). Empty = this release's own Service; required once the component is disabled in a release that still needs it — the app, or the proxy for Keycloak (the render fails otherwise; for MailHog both `smtp.host` and `smtp.port`). |
| `smtp.existingSecret` | `""` | A Secret with the keys `username` and `password`: the login the mail relay asks for, passed to the app as `NIORD_SMTP_USERNAME`/`NIORD_SMTP_PASSWORD` (`niord.smtp.username`/`.password`), never into the ConfigMap. `""` = no login. A changed Secret takes effect at the app pod's next start, as the database users' do. Needs `smtp.host`: with this release's MailHog the render fails. With `smtp.tls` `none` the login is sent in clear text: set `starttls` or `ssl` unless the relay is reached over a trusted network. Read only by an app image whose mail session carries the login (see "The mail relay"); an older one ignores it. |
| `smtp.tls` | `none` | TLS to the mail relay, rendered as `niord.smtp.tls` when not `none`: `starttls` (required before the login and the mail, usually port 587) or `ssl` (implicit TLS, usually port 465). The relay's certificate is checked against the JVM's trust store; a relay with a private CA needs a trust store named with `-Djavax.net.ssl.trustStore` in `app.javaOpts`. Anything else, or TLS with this release's MailHog, fails the render. Read only by an app image whose mail session carries TLS (see "The mail relay"); an older one ignores it and sends in clear text. |
| `smtp.requireRelay` | `false` | `true` = this environment's mail must reach people: the render refuses `mailhog.enabled` and a `smtp.host` named `<fullname>-smtp`. Production sets it at go-live, when it switches from MailHog to the relay; before that, and everywhere else, `false`. Anything but `true` or `false` fails the render. |
| `postgres.existingSecret` | `""` | A Secret with the keys `username` and `password`: the user the app connects as, the owner of its database and no superuser ("The database users"). `""` = the chart renders one from `postgres.username`/`postgres.password` (`niord_app`), the local default; required in `keycloak.mode: production`. Named alone in a release that renders the app and its own Postgres, the render fails: name `postgres.admin.existingSecret` too. No credential is rendered into a ConfigMap or a pod spec either way. |
| `postgres.admin.existingSecret` | `""` | A Secret with the keys `username` and `password`: the superuser of the Postgres this release renders, which the app pod's `database-user` init container, the databases Job and Keycloak's init container connect as. `""` = the chart renders one from `postgres.admin.username`/`postgres.admin.password` (`niord`), the local default; but where `postgres.existingSecret` is named alone, which the render allows only in a release without the app (an infra release) or whose app uses `postgres.host`, that Secret is the superuser's and none is rendered. |
| `keycloak.db.existingSecret` | `""` | Production mode: a Secret with the keys `username` and `password`, the user Keycloak connects to `keycloak.database` as. `""` = the chart renders one from `keycloak.db.username`/`keycloak.db.password`, the local default; required where `postgres.admin.existingSecret` is named. |
| `keycloak.existingSecret` | `""` | A Secret with the keys `username` and `password` for the Keycloak bootstrap admin. `""` = the chart renders one from `keycloak.adminUser`/`keycloak.adminPassword`, which production mode refuses. |
| `keycloak.mode` | `dev` | `dev`: `start-dev`, data kept in the container (gone on a restart), the test realm. `production`: `start` on `keycloak.database`, the test realm without its users and without `niord-web-admin`, and with `niord-web` limited to `externalBaseUri` — see [Keycloak in production mode](#keycloak-in-production-mode). |
| `keycloak.devModeOffLocalhost` | `false` | Dev mode with an `externalBaseUri` or `externalKeycloakUrl` that is not on `localhost`, `127.0.0.1` or `[::1]` fails the render unless this is `true`. A cluster test environment sets it; staging and production run `production` or a shared Keycloak. |
| `keycloak.testUsers` | `false` | Production mode only: `true` keeps the test realm's users, with the passwords in this repository, in the rendered realm; never for production. Dev mode always imports them. Anything but `true` or `false` fails the render. |
| `keycloak.database` | `keycloak` | Production mode: the database on this release's Postgres (or `postgres.host`) that Keycloak keeps its data in, created by an init container when missing. |
| `postgres.databases` | `[]` | Further databases this release's Postgres holds, next to `postgres.database`. An infra release serving several app releases lists one per app (`[niord_dk, niord_gl]`), and each app release names its own in `postgres.database`. A post-install/post-upgrade Job creates any that is missing (from `template_postgis`, owned by the superuser, `postgres.admin`) and never drops one. An app release on such a database needs it handed to its user ("A database of someone else's", above). |
| `postgres.cnpg.databases` | `[]` | CloudNativePG mode ([CloudNativePG](#cloudnativepg)): one `Cluster` of the operator's for each entry `{number: <n>}`, named `<fullname>-db-<number>`, next to or instead of the plain pod. The app uses the last entry. A number is used once; removing an entry deletes that database. An entry may name its own `image`. |
| `postgres.cnpg.image` | `ghcr.io/cloudnative-pg/postgis:18.6-3.6.4-202610261018-standard-trixie` | The database image of that mode: one full reference with its tag, used as it is. `global.imageRegistry` does not apply. |
| `postgres.cnpg.locale` | `en_US.utf8` | The locale a new database is created with, the plain image's. `""` = CNPG's default, `C`. |
| `app.persistence` / `postgres.persistence` | `enabled: true` | PVCs for NIORD_HOME and the DB. The repository files (attachments, publication files, the archive) are rows of the DB; NIORD_HOME is a cache (thumbnails and map images under `cache/`, the map tile cache of at most 200 MB, the AtoN tiles, the Lucene index) and the batch jobs' files, and nothing on it is backed up. |
| `app.javaOpts` | `-XX:MaxRAMPercentage=70.0 -XX:+UseG1GC` | Handed to the JVM as `JAVA_TOOL_OPTIONS`. One string that replaces the default whole, so a value that adds a flag repeats the two above. A `-D<settingKey>=<value>` here pins a setting ahead of `app.settings` and the stored row; pin settings with `app.settings` instead (see "Pinned settings"). Add the `-Dhttps.proxyHost`/`-Dhttps.proxyPort`/`-Dhttp.nonProxyHosts` flags when the tile host is reached through a proxy. The pod's JVM runs in UTC (`TZ=UTC`, set by the image and the chart, not by this value, so a value that replaces it keeps UTC); do not add `-Duser.timezone`. |
| `app.resources.limits.memory` | `2Gi` | Heap is `MaxRAMPercentage=70`. |
| `app.settings` | `{}` | Settings of the Settings page this environment pins, as environment variables named like the key (`baseUri`, `mode`, `mailValidRecipients`, `mailSender`); a pinned value wins over the stored row. An empty value is not rendered. Never a secret: the render refuses a key that names a password, secret or token. See "Pinned settings" above. |
| `app.oidc.allowedClients` | `""` | The Keycloak clients whose tokens the API accepts (the token's `azp` claim); any other client's token is answered 401. `""` = the application default, `niord-web` and the base client `niord-client-master`. A value replaces the default, so it names `niord-web` too, e.g. `niord-web,niord-client-master,<script-client>` for a script with a client of its own. Rendered as `niord.oidc.allowed-clients`. |
| `s124.enabled` | unset | Whether the deployment does S-124, rendered as `niord.s124.enabled` only when set. Unset = the image decides: a country image and the library default `true`, the generic `niord-app-en` `false`. `true` or `false` overrides the image; anything else fails the render, and so does `secom.enabled` with `s124.enabled` `false`. See [S-124 on or off](#s-124-on-or-off). |
| `s124.producerCode` | `""` | The deployment's IHO S-62 producer code, rendered as `niord.s124.producer-code` only when set. `""` = the image's own code (the Danish and Greenland images carry theirs). With S-124 on, a deployment without one cannot generate S-124, and SECOM refuses to start. |

Override per install through the script, which appends `EXTRA_HELM_ARGS` last, e.g.:

```bash
EXTRA_HELM_ARGS="--set app.resources.limits.memory=3Gi" ./scripts/deploy.sh
```

A plain `helm upgrade --install` of the same release with a `--set` resets every value it does not
name to the chart's default.

## SECOM / S-124 (optional)

Off by default (`secom.enabled=false` renders the chart exactly as before). When enabled, the
chart wires the app's embedded SECOM v2 service (`/api/secom`, see
[`docs/secom-design.md`](../docs/secom-design.md)) for MCP-PKI peers:

- **The proxy forwards `/api/secom` like everything else.** SECOM Edition 2 (IEC CDV
  63173-2, clause 4.3.3) takes a peer's identity from the certificate in its signed
  envelope, not from a TLS client certificate. The TLS layer is ordinary HTTPS with a
  certificate from a public CA, ended by whatever fronts the proxy: locally nothing (plain
  HTTP on 18888), in a cluster the ingress controller. nginx holds no certificate and
  strips any `X-Secom-Cert` a client sends.
  *Transitional:* the app's `MrnCertificateFilter` still reads identity from that header,
  and trusts it only when the deployment says so (`niord.secom.trust-cert-header`,
  default `false`; the chart never sets it). So until it reads the envelope instead
  ([`docs/s124-secom-spec-gap-analysis.md`](../docs/s124-secom-spec-gap-analysis.md) §7)
  every SECOM request except `GET /v2/ping` is refused in a deployed stack, whatever
  reaches the app and by whichever path — the nginx strip is belt and braces. Fail closed.
- **Two secrets, created outside Helm** (binary PKCS12 does not survive `.Files.Get`):
  `niord-secom-stores` (`keystore.p12`, `truststore.p12` — raw or base64, auto-detected;
  mounted into the app at `/run/secrets/secom` — plus their passwords `keystore-password` and
  `truststore-password`, which the chart passes to the app as environment variables) and,
  only with unattended renewal, `niord-secom-renewal` (below). For local testing,
  [`scripts/secom-test-secrets.sh`](scripts/secom-test-secrets.sh) derives them from the
  committed test PKI. NB: real truststores must be built with **keytool** (the JDK skips
  openssl's cert-only PKCS12 bags) and should contain the CA(s) that *issue* peer
  certificates, not just the MCP root.
- **Runtime settings** (`secom.settings.*` in values) are injected as env vars under their
  exact camelCase setting names (SettingsService precedence: sysprop > env > DB > default)
  — e.g. `secomServiceRegistryUrl`, `secomSignatureAlgorithm` (`SHA3_384_WITH_ECDSA` for
  legacy SHA3-only peers), `secomAlertEmails`. An environment variable wins over the Settings
  page and changes only with a new pod (a `helm upgrade` that changes it rolls the pods); a key
  left out of the values is set on the Settings page instead, where a save takes effect at once.
- **Rotation**: update the stores secret in place — the app polls the mounted files every
  minute and hot-reloads them automatically once the secret has propagated (a minute or
  two in total; confirmation mail to `secomAlertEmails`). For an immediate, synchronously
  verified pickup use the sysadmin SECOM page's certificate *Reload* button
  (`POST /rest/secom/security/reload` — no restart) or
  `kubectl -n niord rollout restart deploy/niord-app`. Nothing else holds certificate
  material, so nothing else needs a restart. `teardown.sh --purge` deletes the namespace
  *including the secrets* — re-run `secom-test-secrets.sh`.
  [`scripts/secom-truststore.sh`](scripts/secom-truststore.sh) rebuilds `truststore.p12`
  from a curated CA input (and `--apply` patches it into the stores secret).
- **Unattended renewal** (`secom.renewal.*` in values; identity plan T3b): with
  `secom.renewal.enabled=true` the chart additionally mounts the robot-credential secret
  `niord-secom-renewal` (`robot.p12` — the MIR robot user that authenticates renewals),
  renders the `niord.secom.renewal.*` config (`mirUrl` + `orgMrn` required), and gives the
  app pod a ServiceAccount bound to a Role with `get`/`patch` on exactly the two SECOM
  secrets — the daily in-app job then persists renewed keystores by PATCHing the secrets
  through the Kubernetes API (the mounts are read-only). Arm the job itself with the DB
  setting `secom.settings.secomRenewalEnabled="true"`. The running pod picks renewed
  material up via the kubelet sync + store watch (~2 min).
  [`scripts/secom-renewal-rehearsal.sh`](scripts/secom-renewal-rehearsal.sh) rehearses the
  whole loop offline against a stub MIR ([`scripts/secom-stub-mir.py`](scripts/secom-stub-mir.py))
  with a throwaway 30-day PKI and verifies every step — secret PATCHes, store-watch
  pickup, confirmation mails (~10 min, then `deploy.sh --secom` restores the standard
  test setup).

Quick start and verification (with the app image built):

```bash
./scripts/deploy.sh --secom            # = secom-test-secrets.sh + --set secom.enabled=true
                                       # (on niord-app-en add the S-124 flags: S-124 on or off, below)

# The one open endpoint
curl -s http://localhost:18888/api/secom/v2/ping

# Everything else needs a signed envelope; without one, 403. (Until the app reads the
# envelope, see above, this is also what a correctly signed request gets.)
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:18888/api/secom/v2/capability

# A forged identity header is ignored by the app (and dropped by the proxy): still 403
curl -s -o /dev/null -w '%{http_code}\n' -H "X-Secom-Cert: forged" \
     http://localhost:18888/api/secom/v2/capability

# Expose a message series over SECOM + backfill datasets (sysadmin token):
# PUT /rest/secom/series-config/{seriesId}, POST /rest/secom/datasets/reconcile — or use
# the sysadmin UI's SECOM page.
```

Pods stuck in `ContainerCreating` with `FailedMount` events mean the secrets are missing —
run `./scripts/secom-test-secrets.sh` (or create the real ones) and let the pods retry.

### S-124 on or off

`niord.s124.enabled` (default `true` in the library) says whether the deployment does S-124 at
all, and it is the parent of `niord.secom.enabled`: SECOM exchanges S-124 datasets. Off, the app
serves no `/rest/S-124` (messages and XSDs answer 404) and neither uses nor checks the producer
code, and it refuses to start with SECOM on, naming both keys. The automatic cancellation warning
stays whatever the switch says. It is a boot property: a restart applies it.

The chart renders `s124.enabled` and `s124.producerCode` only when the values set them; a key it
leaves out keeps the image's own value. So a country image runs on its own switch and producer
code, and the generic `niord-app-en` (the demo application, with no producer code) runs with
S-124 off. The render fails on an `s124.enabled` that is not `true` or `false`, and on
`secom.enabled` with `s124.enabled` `false`; with `s124.enabled` unset the image decides, and the
app refuses a SECOM start itself.

**Release note.** Until this change the chart always rendered `niord.s124.producer-code: DK00`,
which overrode the image's own code. A Helm deployment that relied on that default now runs on
the image's code (DK01 for the Danish and Greenland images). Check a cluster environment's values
(the GitLab devops repositories) before upgrading to it, and set `s124.producerCode` where the
deployment needs DK00.

**A local SECOM rehearsal on the default image.** `niord-app-en` switches S-124 off and has no
producer code, so `deploy.sh --secom` on it renders, and the app then refuses to start. Pass
both through `EXTRA_HELM_ARGS`:

```bash
EXTRA_HELM_ARGS="--set s124.enabled=true --set s124.producerCode=XX00" ./scripts/deploy.sh --secom
```

The same holds for `--secom-client`. `secom-renewal-rehearsal.sh` sets `EXTRA_HELM_ARGS` itself,
so it cannot be given the two flags; it puts both keys, with the rehearsal code `XX00`, in the
values file it generates.

### End-to-end subscriber demo (SECOM test client)

[`secom-test-client/`](secom-test-client/) is a minimal in-cluster SECOM consumer (a
standalone jar, **not** part of the Niord Maven reactor) that rehearses the full
producer round trip of the SECOM go-live (gate 6 in
[`docs/secom-go-live.md`](../docs/secom-go-live.md)):
it subscribes to the Niord SECOM endpoint with the committed test client identity
(`client-one`), doubles as its own MCP service registry so Niord can resolve its endpoint
without a real MSR, receives the pushed uploads, verifies the envelope + payload
signatures, dumps the payloads to `/data`, and answers each push with a **signed
DELIVERED acknowledgement** — driving Niord's outbox entry `PENDING → SENT → ACKED`.

**Waits for the envelope work.** The client still presents its identity as a TLS client
certificate, which the plain endpoint cannot see, so its subscribe is refused until both
it and the app's filter move to envelope-based identity
([`docs/s124-secom-spec-gap-analysis.md`](../docs/s124-secom-spec-gap-analysis.md) §7).
The commands stay as they are:

```bash
./scripts/deploy.sh --secom-client     # --secom + build/deploy the client + point
                                       # secomServiceRegistryUrl at it + set the test-only
                                       # secomAllowInsecureEndpoints=true (plain-http peer)

kubectl -n niord logs -f deploy/secom-test-client   # watch it subscribe + receive

# Then publish an NW in an exposed series through the REST API at http://localhost:18888/rest:
# the base product's bootstrap of Quick start step 4 creates only the master domain
# (niord-client-master), active, with no series, so add an NW series to it first, or create a
# domain with one (in the admin app or with the REST API), and expose the series on the
# sysadmin SECOM page
# — within a minute (the outbox dispatcher runs at second :37) the client logs the pushed
# exchange set, its signature verdicts and the acknowledgement. Cancelling the NW pushes
# the auto-created cancellation warning the same way. Check the producer-side bookkeeping:
TOKEN=... # sysadmin JWT
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:18888/rest/secom/outbox
```

Test-tool shortcuts to be aware of: Niord pushes to the client over plain in-cluster
HTTP — the committed test PKI cannot issue peer *server* certificates because its root
key is deliberately not checked in. By default `SecomOutboundClient` refuses exactly
that: it pushes only to `https://` peer endpoints whose host resolves to public addresses.
The `--secom-client` deploy therefore sets `secom.settings.secomAllowInsecureEndpoints=true`,
a test-environment switch that must stay off (the default) in real clusters; a later plain
`deploy.sh` or `--secom` resets it. Real outbound TLS against a peer therefore stays a
production-certificate concern (go-live gates 1/6). The client's env knobs (`SUBSCRIBE_WKT`, `SUBSCRIBE_BODY`,
`ACK_ALGORITHM`, `SEND_OPENED_ACK`, ...) are documented at the top of
[`SecomTestClient.java`](secom-test-client/src/main/java/org/niord/secom/testclient/SecomTestClient.java).

## Troubleshooting

```bash
kubectl -n niord get pods
kubectl -n niord logs deploy/niord-app -f
kubectl -n niord logs deploy/niord-proxy
kubectl -n niord logs deploy/niord-keycloak
```

- **App in CrashLoopBackOff at startup** — almost always the DB or a config issue.
  Check `kubectl -n niord logs deploy/niord-app`. The init-containers block the app
  until `niord-db:5432` and `niord-keycloak:8080` are reachable.
- **App pod stays in `Init`** — `kubectl -n niord logs deploy/niord-app -c database-user`. It
  waits for Postgres, then needs the superuser's Secret to match the superuser the volume was
  created with (the image creates it once, on a new volume, and ignores the Secret afterwards),
  and the app's Secret to name another user than that one.
- **App pod stays in `Init` in CloudNativePG mode** — `kubectl -n niord logs deploy/niord-app -c
  wait-for-postgis`. It names what it waits for: the extension (`kubectl -n niord get database`
  shows whether the operator applied it), a password the database does not take, or a Secret
  whose `username` is not `postgres.username`.
- **`localhost:18888` not responding** — confirm the proxy Service got a LoadBalancer
  (`kubectl -n niord get svc niord-proxy`), the proxy + app pods are `Ready`, and
  nothing else owns 18888. It is also at `niord-proxy.k8s.orb.local`.
- **401 on REST calls** — the token `iss` must equal
  `externalKeycloakUrl`. If you changed the proxy port, change `externalKeycloakUrl`
  (and rely on the chart re-rendering Keycloak's `--hostname`).
- **Start over with a new database** — `./scripts/teardown.sh --purge`, `./scripts/deploy.sh`, then
  Quick start step 4. The bootstrap runs once per database: on one that holds data it is refused.

## Layout

```
k8s/
├── README.md
├── docker/
│   ├── Dockerfile          # runtime image from the split jar, see "The image" (local and CI)
│   └── .dockerignore
├── helm/niord/
│   ├── Chart.yaml
│   ├── values.yaml
│   ├── files/              # the realm JSON, equal to the integration tests' (deploy.sh checks)
│   └── templates/
│       ├── nginx/          # reverse proxy (single public entry point)
│       ├── app/            # the Niord application (niord-app, or a country image)
│       ├── postgres/       # the plain pod, and the CloudNativePG objects (cnpg-*.yaml)
│       ├── keycloak/
│       └── mailhog/
└── scripts/
    ├── build-image.sh      # local: mvn package, web build, then the two scripts below
    ├── split-jar.sh        # unpacks an executable jar into thin jar, niord-base, third-party jars
    ├── publish-images.sh   # builds, checks and (in CI) pushes the images
    ├── check-overrides.sh  # the backend override check a country build runs over its jar
    │                       #   (with CheckOverrides.java; be/docs/country-api.md, Overrides)
    ├── deploy.sh
    └── teardown.sh
```
