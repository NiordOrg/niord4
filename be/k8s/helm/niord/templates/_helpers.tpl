{{/*
Expand the name of the chart.
*/}}
{{- define "niord.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Fully qualified app name. Truncated to 63 chars (k8s DNS label limit, also a
StatefulSet/Service requirement). Mirrors the standard Helm convention.
*/}}
{{- define "niord.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "niord.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels.
*/}}
{{- define "niord.labels" -}}
helm.sh/chart: {{ include "niord.chart" . }}
{{ include "niord.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: niord
{{- end }}

{{- define "niord.selectorLabels" -}}
app.kubernetes.io/name: {{ include "niord.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Per-component helpers: name, selector labels and component labels keyed by a
component string ("app", "db", "keycloak", "smtp"). Usage:
  {{ include "niord.componentName" (list . "db") }}
  {{ include "niord.componentSelectorLabels" (list . "db") }}
*/}}
{{- define "niord.componentName" -}}
{{- $root := index . 0 -}}
{{- $component := index . 1 -}}
{{- printf "%s-%s" (include "niord.fullname" $root) $component | trunc 63 | trimSuffix "-" -}}
{{- end }}

{{- define "niord.componentSelectorLabels" -}}
{{- $root := index . 0 -}}
{{- $component := index . 1 -}}
{{ include "niord.selectorLabels" $root }}
app.kubernetes.io/component: {{ $component }}
{{- end }}

{{- define "niord.componentLabels" -}}
{{- $root := index . 0 -}}
{{- $component := index . 1 -}}
{{ include "niord.labels" $root }}
app.kubernetes.io/component: {{ $component }}
{{- end }}

{{/*
In-cluster hostnames of the app's dependencies. Each resolves to the Service this release
renders (short DNS name, resolvable inside the release namespace) unless the corresponding
host value points at another release's Service — the seam that lets an app-only release
wire to an infra-only release in the same namespace. A dependency this release needs (the
app's, or the proxy's Keycloak) that is neither rendered here nor named explicitly is a
values error, reported at render time.
*/}}
{{- define "niord.db.host" -}}
{{- if .Values.postgres.host -}}
{{- .Values.postgres.host -}}
{{- else if .Values.postgres.enabled -}}
{{- include "niord.componentName" (list . "db") -}}
{{- else -}}
{{- fail "postgres.host is required when postgres.enabled is false (name the infra release's Postgres Service)" -}}
{{- end -}}
{{- end }}

{{- define "niord.keycloak.host" -}}
{{- if .Values.keycloak.host -}}
{{- .Values.keycloak.host -}}
{{- else if .Values.keycloak.enabled -}}
{{- include "niord.componentName" (list . "keycloak") -}}
{{- else -}}
{{- fail "keycloak.host is required when keycloak.enabled is false (name the infra release's Keycloak Service)" -}}
{{- end -}}
{{- end }}

{{- define "niord.smtp.host" -}}
{{- if .Values.smtp.host -}}
{{- .Values.smtp.host -}}
{{- else if .Values.mailhog.enabled -}}
{{- include "niord.componentName" (list . "smtp") -}}
{{- else -}}
{{- fail "smtp.host is required when mailhog.enabled is false (name the infra release's MailHog Service or the mail relay)" -}}
{{- end -}}
{{- end }}

{{- define "niord.smtp.port" -}}
{{- if .Values.smtp.port -}}
{{- .Values.smtp.port -}}
{{- else if .Values.mailhog.enabled -}}
{{- .Values.mailhog.smtpPort -}}
{{- else -}}
{{- fail "smtp.port is required when mailhog.enabled is false (the infra release's MailHog port, e.g. 11025, or the mail relay's)" -}}
{{- end -}}
{{- end }}

{{- define "niord.app.host" -}}{{ include "niord.componentName" (list . "app") }}{{- end }}

{{/*
The proxy Ingress's host: the host of externalBaseUri. The Ingress only makes sense for an
https origin on the default port, because the ingress controller ends TLS on 443. Anything
else fails the render instead of producing an Ingress the browser would never match.
*/}}
{{- define "niord.proxy.ingressHost" -}}
{{- $u := urlParse .Values.externalBaseUri -}}
{{- if or (ne (index $u "scheme") "https") (not (index $u "host")) (contains ":" (index $u "host")) -}}
{{- fail (printf "proxy.ingress.enabled needs externalBaseUri as https://<host> with no port (got %q)" .Values.externalBaseUri) -}}
{{- end -}}
{{- index $u "host" -}}
{{- end }}

{{/*
In-cluster Keycloak base URL the BACKEND uses (admin client + JWKS fetch).
Browser-facing URL is .Values.externalKeycloakUrl.
*/}}
{{- define "niord.keycloak.internalUrl" -}}
http://{{ include "niord.keycloak.host" . }}:{{ .Values.keycloak.service.port }}/auth
{{- end }}

{{/*
The realm Keycloak imports (keycloak.mode, values.yaml). dev: files/niord-realm.json as it is.
production: the same realm without its users (unless keycloak.testUsers, which keeps the test
users: their passwords are in this public repository), without the niord-web-admin client and its service
account (unused: the app's admin calls carry the signed-in user's own token) and without any
client secret (the file's are masked placeholders, so Keycloak generates new ones), and with
niord-web accepting only redirects to externalBaseUri, from its origin, with PKCE, and no
password grant (which asks for neither a redirect nor PKCE).
*/}}
{{- define "niord.keycloak.realmJson" -}}
{{- /* production also renames the realm to keycloak.realm: the file's is "niord", and a realm for the shared
       Keycloak (niord-dk, niord-gl) is rendered from this same helper and uploaded by hand (README,
       "A shared Keycloak outside the release"). dev keeps the file as it is. */ -}}
{{- $file := .Files.Get "files/niord-realm.json" -}}
{{- if ne .Values.keycloak.mode "production" -}}
{{- $file -}}
{{- else -}}
{{- $realm := fromJson $file -}}
{{- if not $realm.clients -}}
{{- fail "files/niord-realm.json is not a realm export with clients" -}}
{{- end -}}
{{- $base := trimSuffix "/" .Values.externalBaseUri -}}
{{- $u := urlParse $base -}}
{{- if or (not (index $u "scheme")) (not (index $u "host")) -}}
{{- fail (printf "keycloak.mode production needs externalBaseUri as <scheme>://<host>[:port] (got %q)" .Values.externalBaseUri) -}}
{{- end -}}
{{- $origin := printf "%s://%s" (index $u "scheme") (index $u "host") -}}
{{- $clients := list -}}
{{- range $realm.clients -}}
{{- if ne .clientId "niord-web-admin" -}}
{{- $_ := unset . "secret" -}}
{{- if eq .clientId "niord-web" -}}
{{- $_ := set . "redirectUris" (list (printf "%s/*" $base)) -}}
{{- $_ := set . "webOrigins" (list $origin) -}}
{{- $_ := set . "attributes" (merge (dict "pkce.code.challenge.method" "S256") (default dict .attributes)) -}}
{{- $_ := set . "directAccessGrantsEnabled" false -}}
{{- end -}}
{{- $clients = append $clients . -}}
{{- end -}}
{{- end -}}
{{- $_ := set $realm "clients" $clients -}}
{{- $users := list -}}
{{- if .Values.keycloak.testUsers -}}
{{- range $realm.users -}}
{{- if ne (default "" .serviceAccountClientId) "niord-web-admin" -}}
{{- $users = append $users . -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- $_ := set $realm "users" $users -}}
{{- $_ := unset $realm.roles.client "niord-web-admin" -}}
{{- $_ := set $realm "sslRequired" "external" -}}
{{- $_ := set $realm "bruteForceProtected" true -}}
{{- /* The realm's name, and no ids. Every nested id (roles, clients, users, scopes, flows: about 260)
       and containerId is the test realm's, and Keycloak keeps ids unique across realms, so a second
       realm from this file would be refused with 409 "Duplicate resource". Without them Keycloak
       generates ids and links roles, groups and users by name. The built-in account and admin-console
       clients carry the realm's name in their paths (/realms/niord/account/*, /admin/niord/console/*),
       and the default role is named after the realm: both are renamed in the JSON text, where the three
       tokens occur nowhere else. */ -}}
{{- include "niord.keycloak.stripIds" $realm -}}
{{- $name := .Values.keycloak.realm -}}
{{- $_ := set $realm "realm" $name -}}
{{- $_ := set $realm "id" $name -}}
{{- toPrettyJson $realm
    | replace "/realms/niord/" (printf "/realms/%s/" $name)
    | replace "/admin/niord/" (printf "/admin/%s/" $name)
    | replace "default-roles-niord" (printf "default-roles-%s" $name) -}}
{{- end -}}
{{- end }}

{{/*
Removes every "id" and "containerId" below a value, in place: a map loses the two keys and its
values are walked, a list's items are walked, anything else is left. Renders nothing. Helm's
include may call itself, which is what walks the realm's nesting.
*/}}
{{- define "niord.keycloak.stripIds" -}}
{{- if kindIs "map" . -}}
{{- $_ := unset . "id" -}}
{{- $_ := unset . "containerId" -}}
{{- range $k, $v := . -}}{{- include "niord.keycloak.stripIds" $v -}}{{- end -}}
{{- else if kindIs "slice" . -}}
{{- range $v := . -}}{{- include "niord.keycloak.stripIds" $v -}}{{- end -}}
{{- end -}}
{{- end }}

{{/*
Whether the browser's Keycloak is outside this release's origin: externalKeycloakUrl does not
start with externalBaseUri. Then Keycloak is a shared one (keycloak.enabled false, keycloak.host
its in-cluster Service), the browser goes to it directly, and the proxy must not route /auth: a
route there would hand the shared Keycloak requests under this environment's name. Renders
"true" or nothing, for use in an if.
*/}}
{{- define "niord.keycloak.external" -}}
{{- $base := include "niord.keycloak.normalizedUrl" .Values.externalBaseUri -}}
{{- $kc := include "niord.keycloak.normalizedUrl" .Values.externalKeycloakUrl -}}
{{- if not (hasPrefix $base $kc) -}}true{{- end -}}
{{- end }}

{{/*
A URL as the browser compares origins: lower case, a default port dropped (https :443, http :80),
one trailing slash. So https://Host:443/auth and https://host/auth/ are the same place.
*/}}
{{- define "niord.keycloak.normalizedUrl" -}}
{{- $u := lower (trimSuffix "/" .) -}}
{{- $u = regexReplaceAll "^(https://[^/:]+):443(/|$)" $u "${1}${2}" -}}
{{- $u = regexReplaceAll "^(http://[^/:]+):80(/|$)" $u "${1}${2}" -}}
{{- printf "%s/" $u -}}
{{- end }}

{{/*
Image reference for a component: <registry>/<repository>:<tag>, where the registry is
global.imageRegistry when set, else the component's image.registry; no prefix when both
are empty (the locally built app image). An empty app tag is the chart's appVersion, so the
chart version pins the app image. An empty info tag is the app image's tag, whatever that
resolves to: a country builds the public site's image and its app image under one version, so the
two always run the same one. Every other component needs its tag spelled out.
Usage: {{ include "niord.image" (list . "postgres") }}
The old value forms (image as one "repo:tag" string, a sibling pullPolicy) fail with the
keys to use instead, rather than a template error or a silently ignored override.
*/}}
{{- define "niord.image" -}}
{{- $root := index . 0 -}}
{{- $component := index . 1 -}}
{{- $values := index $root.Values $component -}}
{{- $img := $values.image -}}
{{- if not (kindIs "map" $img) -}}
{{- fail (printf "%s.image is %q, the old one-string form; set %s.image.registry, .repository and .tag instead" $component (toString $img) $component) -}}
{{- end -}}
{{- if hasKey $values "pullPolicy" -}}
{{- fail (printf "%s.pullPolicy is the old sibling key; it is %s.image.pullPolicy" $component $component) -}}
{{- end -}}
{{- $tag := default "" $img.tag | toString -}}
{{- if and (not $tag) (eq $component "info") -}}
{{- $tag = default "" $root.Values.app.image.tag | toString -}}
{{- end -}}
{{- if and (not $tag) (has $component (list "app" "info")) -}}
{{- $tag = $root.Chart.AppVersion -}}
{{- end -}}
{{- if not $tag -}}
{{- fail (printf "%s.image.tag is empty; only the app image (and the public site's, which follows it) defaults to the chart's appVersion" $component) -}}
{{- end -}}
{{- $registry := default $img.registry $root.Values.global.imageRegistry -}}
{{- if $registry -}}
{{- printf "%s/%s:%s" $registry $img.repository $tag -}}
{{- else -}}
{{- printf "%s:%s" $img.repository $tag -}}
{{- end -}}
{{- end }}

{{/*
Pod Security `restricted`, which every namespace on the target clusters enforces. The pod runs as
the given non-root uid (and gid) under the runtime's default seccomp profile; every container,
init containers included, gets niord.containerSecurityContext. Components whose volume the process
writes to add `fsGroup` after the include. Usage:
  securityContext:
    {{- include "niord.podSecurityContext" 999 | nindent 8 }}
*/}}
{{- define "niord.podSecurityContext" -}}
runAsNonRoot: true
runAsUser: {{ . }}
runAsGroup: {{ . }}
seccompProfile:
  type: RuntimeDefault
{{- end }}

{{- define "niord.containerSecurityContext" -}}
allowPrivilegeEscalation: false
capabilities:
  drop: ["ALL"]
{{- end }}

{{/*
The Secrets the credentials come from: postgres.existingSecret / keycloak.existingSecret, or
the one the chart renders from the plain values when that is "" (postgres/secret.yaml,
keycloak/secret.yaml). Keys: `username`, `password`.
niord.postgres.secretName is the user the app connects as.
*/}}
{{- define "niord.postgres.secretName" -}}
{{- .Values.postgres.existingSecret | default (include "niord.componentName" (list . "db-credentials")) -}}
{{- end }}

{{/*
Whether the superuser of this release's Postgres has a Secret of its own (values.yaml,
postgres.admin), apart from the app's. True for a release that renders Postgres and either names
the superuser's Secret or names no Secret at all (the local default, two rendered Secrets). Not
true for a release that names only postgres.existingSecret: that one Secret is then the superuser's,
which validate.yaml allows only in a release without the app (an infra release), since an app in
it would connect as the superuser. Renders "true" or nothing, for use in an if.
*/}}
{{- define "niord.postgres.separateAdmin" -}}
{{- $admin := .Values.postgres.admin | default dict -}}
{{- if and .Values.postgres.enabled (or $admin.existingSecret (not .Values.postgres.existingSecret)) -}}true{{- end -}}
{{- end }}

{{/*
Whether the app (and a Keycloak in production mode) has a database user of its own that this
release makes: the superuser has its own Secret (niord.postgres.separateAdmin) and the database is
this release's Postgres. With postgres.host set the database is someone else's, whatever this
release renders beside it, and the user is given. Renders "true" or nothing.
*/}}
{{- define "niord.postgres.ownUser" -}}
{{- if and (include "niord.postgres.separateAdmin" .) (not .Values.postgres.host) -}}true{{- end -}}
{{- end }}

{{/*
The Secret of the superuser of this release's Postgres: postgres.admin.existingSecret, or the one
the chart renders from postgres.admin.username/password. Where it has none of its own
(niord.postgres.separateAdmin) it is the app's Secret, the one account there is.
*/}}
{{- define "niord.postgres.adminSecretName" -}}
{{- $admin := .Values.postgres.admin | default dict -}}
{{- if include "niord.postgres.separateAdmin" . -}}
{{- $admin.existingSecret | default (include "niord.componentName" (list . "db-admin")) -}}
{{- else -}}
{{- include "niord.postgres.secretName" . -}}
{{- end -}}
{{- end }}

{{/*
The Secret of the user Keycloak connects to its database as (production mode): keycloak.db's,
where this release makes the users (niord.postgres.ownUser); else the app's Secret, as before.
*/}}
{{- define "niord.keycloak.dbSecretName" -}}
{{- $db := .Values.keycloak.db | default dict -}}
{{- if include "niord.postgres.ownUser" . -}}
{{- $db.existingSecret | default (include "niord.componentName" (list . "keycloak-db")) -}}
{{- else -}}
{{- include "niord.postgres.secretName" . -}}
{{- end -}}
{{- end }}

{{/*
The script that gives a database to its user, run by an init container of the pod that uses the
database (the app's, Keycloak's), connected as the superuser of this release's Postgres. In the
environment: PGHOST, PGPORT, PGUSER and PGPASSWORD (the superuser), PGDATABASE (postgres),
DB_USER and DB_PASSWORD (the user), DATABASE and TEMPLATE (the template a missing database is
created from). It may run on every start:
  - the user is created when missing; its password is set to the Secret's every time, so a
    changed Secret takes effect at the next start, and it is held to a login that is no superuser
    and may create neither databases nor users;
  - the database is created when missing, owned by the user;
  - a database that exists and belongs to someone else — the one the image makes on a new volume,
    one from before the app had a user of its own, one the superuser recreated or restored — is
    handed over: the database, and in its schema public every table, sequence, view, function and
    type that no extension owns. Rows are not touched, and nothing is dropped. The extensions stay
    the superuser's: only a superuser may create PostGIS, and no statement of the app needs to
    own it.
The user's name and password reach SQL as psql variables, which psql quotes; the statement that
sets the password is kept out of the server's log.
Every session of the script has the search path pg_catalog, set by the client (PGOPTIONS), which a
search path set on the database does not replace. The hand-over works as the superuser inside a
database the user owns: with the default path, something the user created there could be what one
of its statements calls (SECREV-4, docs/security-review-2026-09.md section 5). The names it hands
over come out qualified with their schema. ChartDatabaseUserScriptITTest (niord-base) runs this
script in the chart's Postgres image.
*/}}
{{- define "niord.postgres.userScript" -}}
set -eu
export PGOPTIONS="-c search_path=pg_catalog"
until pg_isready -q; do echo "waiting for $PGHOST:$PGPORT ..."; sleep 2; done
if [ "$DB_USER" = "$PGUSER" ]; then
  echo "!! The user of database $DATABASE is $DB_USER, the superuser of this Postgres: its Secret must name a user of its own." >&2
  exit 1
fi
psql -X -q -v ON_ERROR_STOP=1 <<'SQL'
\getenv db_user DB_USER
\getenv db_password DB_PASSWORD
\getenv database DATABASE
\getenv template TEMPLATE
SET log_statement = 'none';
SET log_min_error_statement = 'panic';

SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'db_user') AS create_user \gset
\if :create_user
  CREATE ROLE :"db_user";
  \echo user :db_user created
\endif
ALTER ROLE :"db_user" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'db_password';

SELECT NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'database') AS create_database \gset
\if :create_database
  CREATE DATABASE :"database" TEMPLATE :"template" OWNER :"db_user";
  \echo database :database created, owned by :db_user
\endif

SELECT pg_get_userbyid(datdba) <> :'db_user' AS hand_over FROM pg_database WHERE datname = :'database' \gset
\if :hand_over
  ALTER DATABASE :"database" OWNER TO :"db_user";
  \echo database :database handed over to :db_user
\endif

\connect :database
CREATE TEMP TABLE hand_over AS
SELECT o.step, o.name, format('ALTER %s %s OWNER TO %I', o.kind, o.name, :'db_user') AS statement
  FROM (
    SELECT CASE c.relkind WHEN 'S' THEN 'SEQUENCE' WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW' ELSE 'TABLE' END AS kind,
           c.oid::regclass::text AS name, 'pg_class'::regclass::oid AS catalog, c.oid, c.relowner AS owner, 1 AS step
      FROM pg_class c
     WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p', 'S', 'v', 'm')
       -- a sequence that belongs to a column follows its table
       AND NOT (c.relkind = 'S' AND EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_class'::regclass
                AND d.objid = c.oid AND d.refclassid = 'pg_class'::regclass AND d.deptype IN ('a', 'i')))
    UNION ALL
    SELECT 'ROUTINE', p.oid::regprocedure::text, 'pg_proc'::regclass::oid, p.oid, p.proowner, 2
      FROM pg_proc p
     WHERE p.pronamespace = 'public'::regnamespace
    UNION ALL
    -- enums, domains, ranges and free-standing composites; a table's row type follows its table
    SELECT 'TYPE', t.oid::regtype::text, 'pg_type'::regclass::oid, t.oid, t.typowner, 3
      FROM pg_type t
     WHERE t.typnamespace = 'public'::regnamespace
       AND (t.typtype IN ('e', 'd', 'r') OR (t.typtype = 'c' AND EXISTS (SELECT 1 FROM pg_class c
                WHERE c.oid = t.typrelid AND c.relkind = 'c')))
  ) o
 WHERE o.owner <> :'db_user'::regrole::oid
   AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = o.catalog AND d.objid = o.oid AND d.deptype = 'e');
SELECT count(*) > 0 AS any_to_hand_over, count(*) AS to_hand_over FROM hand_over \gset
\if :any_to_hand_over
  SELECT statement FROM hand_over ORDER BY step, name \gexec
  \echo :to_hand_over tables, sequences, views, functions and types of :database handed over to :db_user
\endif
SQL
{{- end }}

{{- define "niord.keycloak.secretName" -}}
{{- .Values.keycloak.existingSecret | default (include "niord.componentName" (list . "keycloak-admin")) -}}
{{- end }}
