#!/usr/bin/env bash
#
# The launcher library: the country-neutral functions of a local run, sourced by niord-ng's ../../start-local.sh
# and by a country's launcher, which takes it from org.niord:niord-local-run (be/scripts/local-run-zip.sh): there it
# is in scripts/ beside bootstrap.sh, which it finds in its own folder, and load-sample.sh. The zip keeps no exec
# bits, so the library runs both scripts with bash. It only defines things: it never runs set -e or changes another
# shell option, and runs no command when sourced but the uname check behind WINDOWS.
#
#   source be/scripts/local-run-lib.sh
#
# The caller's contract:
#   - set -euo pipefail, as every function here is written for it;
#   - JAVA_HOME a JDK 25 (use_jdk25 sets it), whose bin/jar zips a bootstrap folder;
#   - the globals BE_PID (the backend's background job, set by start_backend), BE_PORT (its port), BE_URL (a URL
#     that answers 200 once it is up) and BE_LOG (its log);
#   - a function start_backend, which starts the backend in the background and sets BE_PID; sample_load calls it
#     to start the backend again after the load;
#   - on the PATH: docker, curl, node (json_field and the domain clients' JSON: active_domains, json_find) and cmp;
#     on Windows netstat, taskkill and cygpath, elsewhere
#     pgrep (kill_tree) and, when there is one, lsof (port_in_use).
# The values of the caller's own (ports, database names, paths, the deployable) stay in the launcher.

if [[ -z "${WINDOWS:-}" ]]; then
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*) WINDOWS=true ;;
        *) WINDOWS=false ;;
    esac
fi

# The local realm's token endpoint, a fixed value of the shared stack and no override: bootstrap_database posts the
# local sysadmin's password grant (niord-web) there, so it is the loopback niord-keycloak and nothing else.
KEYCLOAK_TOKEN_URL="http://localhost:8080/auth/realms/niord/protocol/openid-connect/token"
# niord-keycloak's own loopback, where its kcadm.sh signs in (ensure_domain_clients): a fixed value of the shared stack
# and no override.
KEYCLOAK_ADMIN_SERVER="http://localhost:8080/auth"

# The marker once bootstrap_database is done: applied or existing, empty when the database is not bootstrapped.
# Both start empty whatever the environment holds, so a start acts only on what it found itself.
BOOTSTRAP_STATE=""
# Whether ensure_database created the database
DATABASE_CREATED=false

# The shared containers of be/docker-compose.yml
SHARED_CONTAINERS=(niord-db niord-keycloak niord-smtp)

die() { echo "!! $*" >&2; exit 1; }

# The library's own folder, where bootstrap.sh is: inside a function, BASH_SOURCE[0] is the file that defined it
# ('.' when that path has no folder part).
library_dir() {
    local dir="${BASH_SOURCE[0]%/*}"
    [[ "$dir" == "${BASH_SOURCE[0]}" ]] && dir=.
    printf '%s' "$dir"
}

# A path the JVM understands (C:/Users/... on Windows, unchanged elsewhere).
native_path() { if $WINDOWS; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

# --- prerequisites -----------------------------------------------------------------------------------

java_major() {
    local v
    v=$("$1/bin/java" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')
    echo "${v:-0}"
}
# A JDK 25+, not a JRE: bootstrap_database zips with bin/jar, which a JRE does not have.
jdk_25() { [[ -x "$1/bin/java" && -x "$1/bin/jar" && "$(java_major "$1")" -ge 25 ]]; }

# JAVA_HOME as it is when it is a JDK 25+, otherwise ~/.jdks/temurin-25, exported; java on the PATH is not read.
use_jdk25() {
    if [[ -n "${JAVA_HOME:-}" ]] && jdk_25 "$JAVA_HOME"; then
        return 0
    fi
    local fallback="$HOME/.jdks/temurin-25"
    jdk_25 "$fallback" || die "No JDK 25+ found: set JAVA_HOME to a JDK 25 (a JRE has no bin/jar; java on the PATH is not read), or unpack Temurin 25 into $fallback."
    export JAVA_HOME
    JAVA_HOME="$(native_path "$fallback")"
}

# Ask the OS for a listening socket rather than probing with a connection: on Windows a refused connect to a
# closed localhost port takes ~2 s and looks like a timeout, not a refusal.
port_in_use() {
    if $WINDOWS; then
        netstat -ano | grep "LISTENING" | grep -qE ":$1[[:space:]]"
    elif command -v lsof >/dev/null 2>&1; then
        lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
    else
        (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
    fi
}

# --- the shared containers ---------------------------------------------------------------------------

# The realm's copy that be/docker-compose.yml mounts into niord-keycloak: one path per user, under the home that
# compose resolves `~` to (USERPROFILE on Windows), so the container's configuration is the same from every
# checkout and every country's launcher.
local_realm_path() {
    local home
    if $WINDOWS; then
        home="$(cygpath -u "${USERPROFILE:?USERPROFILE is not set}")"
    else
        home="${HOME:?HOME is not set}"
    fi
    printf '%s/.niord-local/keycloak/niord-realm-local.json' "$home"
}

# Copies the realm file $1 to local_realm_path, when the copy there differs. The mount needs the file before
# compose creates niord-keycloak: a missing one fails the `up` rather than becoming a folder. Keycloak imports the
# realm only into a new volume, so a changed copy reaches a running niord-keycloak only after its volume is removed.
stage_realm() {
    local target
    target="$(local_realm_path)"
    mkdir -p "$(dirname "$target")"
    cmp -s "$1" "$target" || cp "$1" "$target"
}

# A shared container's state, as "<status> <health>" (health none without a healthcheck), or empty when missing.
container_state() {
    docker inspect --type container \
        -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$1" 2>/dev/null || true
}

# Starts the shared containers of the compose file $1 that are missing or stopped, after staging the realm $2, and
# waits until all three are healthy. The containers are shared by every checkout and every country, so a launcher
# never stops, restarts or recreates a running one: while all three run, no compose command is issued at all, and
# the `up` names only the missing or stopped ones, with --no-recreate, so that it reconciles none of the running
# ones either. COMPOSE_PROJECT_NAME is unset for the `up`, since it would outrank the file's `name: be`, and the
# fixed container names would then conflict. A stopped container is started as it is: an edited compose file (an
# image bump, the realm's new mount) reaches a container only by a hand `docker compose up -d <service>`, which
# recreates it and keeps its volume. That is also the way out when a stopped one no longer starts as it is (its old
# bind source is gone, e.g. the realm of a removed worktree), and the refusal names it.
start_services() {
    local file="$1" realm="$2" name state todo=()
    stage_realm "$realm" || die "The realm $realm could not be copied to $(local_realm_path)."
    for name in "${SHARED_CONTAINERS[@]}"; do
        state="$(container_state "$name")"
        case "$state" in
            running\ *|restarting\ *|paused\ *) ;;
            *) todo+=("$name") ;;
        esac
    done
    if (( ${#todo[@]} )); then
        echo ">> Starting the shared containers that are missing or stopped: ${todo[*]} (docker compose up --no-recreate)"
        ( unset COMPOSE_PROJECT_NAME; docker compose -f "$file" up -d --wait --no-recreate "${todo[@]}" ) \
            || die "docker compose could not start ${todo[*]} (above). A stopped container whose old configuration no longer starts (a bind source that is gone) is recreated, its volume kept, by: docker compose -f $file up -d ${todo[*]}"
    fi
    local deadline=$((SECONDS + 180)) waiting
    while :; do
        waiting=""
        for name in "${SHARED_CONTAINERS[@]}"; do
            state="$(container_state "$name")"
            case "$state" in
                "running healthy"|"running none") ;;
                *) waiting="$name"; break ;;
            esac
        done
        [[ -z "$waiting" ]] && return 0
        (( SECONDS < deadline )) \
            || die "The container $waiting is not ready after 180 s (it reads '${state:-missing}'): docker logs $waiting says why."
        sleep 2
    done
}

# Creates the database $1 in niord-db when it does not exist yet, and sets DATABASE_CREATED to whether it did.
# template_postgis carries the same extensions as the image's initial `niord` database.
ensure_database() {
    local db="$1"
    [[ "$db" =~ ^[a-z_][a-z0-9_]*$ ]] || die "A database name must be a plain lower-case identifier, got '$db'."
    if [[ "$(docker exec niord-db psql -U niord -d postgres -tAc "select 1 from pg_database where datname = '$db'")" == "1" ]]; then
        DATABASE_CREATED=false
    else
        echo ">> Creating database $db"
        docker exec niord-db createdb -U niord -T template_postgis "$db"
        DATABASE_CREATED=true
    fi
}

# --- process handling --------------------------------------------------------------------------------

# Stop a background job and everything it started (mvnw -> Maven JVM -> forked app JVM; npm -> node).
kill_tree() {
    local pid="$1"
    [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null || return 0
    if $WINDOWS; then
        # Native java.exe/node.exe do not receive MSYS signals; kill the Windows process tree instead.
        local winpid
        winpid=$(cat "/proc/$pid/winpid" 2>/dev/null || true)
        if [[ -n "$winpid" ]]; then
            taskkill //PID "$winpid" //T //F >/dev/null 2>&1 || true
        fi
        kill "$pid" 2>/dev/null || true
    else
        local child
        for child in $(pgrep -P "$pid" 2>/dev/null || true); do
            kill_tree "$child"
        done
        kill -TERM "$pid" 2>/dev/null || true
    fi
}

# The PID a background job hands back is the bash subshell that started npm or mvnw. On Windows that
# shell can be gone while node.exe or java.exe serve on — so once something has answered on its port,
# liveness is the port, never the PID. A dev server is slow to answer while Vite pre-bundles the
# dependencies on a cold cache (a minute on a slow machine), so only a run of failed polls counts.
serving() { [[ "$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$1" || true)" != "000" ]]; }

# Poll a URL until it answers 200, failing fast if the process behind it exits during startup.
wait_for() {
    local name="$1" pid="$2" url="$3" timeout="$4" log="$5"
    local start=$SECONDS next_note=$((SECONDS + 30)) code
    while :; do
        code=$(curl -s -o /dev/null -m 3 -w '%{http_code}' "$url" || true)
        [[ "$code" == "200" ]] && return 0
        if [[ "$code" == "000" ]] && ! kill -0 "$pid" 2>/dev/null; then
            echo "!! The $name exited during startup. Last lines of $log:" >&2
            tail -n 40 "$log" >&2
            exit 1
        fi
        if (( SECONDS - start > timeout )); then
            echo "!! The $name did not come up within ${timeout}s. See $log" >&2
            exit 1
        fi
        if (( SECONDS >= next_note )); then
            echo "   ... $name still starting ($((SECONDS - start))s) — tail -f $log"
            next_note=$((SECONDS + 30))
        fi
        sleep 2
    done
}

# Stop the backend and wait until its port is free. On Windows the shell behind BE_PID can be gone while
# java.exe serves on, so whatever still listens on the port (ours: it was free at the start) is stopped too.
stop_backend() {
    kill_tree "$BE_PID"
    BE_PID=""
    local deadline=$((SECONDS + 60)) pid
    while port_in_use "$BE_PORT"; do
        if $WINDOWS && (( SECONDS > deadline - 45 )); then
            for pid in $(netstat -ano | grep "LISTENING" | grep -E ":$BE_PORT[[:space:]]" | awk '{ print $NF }' | sort -u); do
                taskkill //PID "$pid" //T //F >/dev/null 2>&1 || true
            done
        fi
        (( SECONDS < deadline )) || die "The backend still holds port $BE_PORT after 60 s."
        sleep 1
    done
}

# Watch the servers given as triples <name> <url> <log>, by their ports, not their PIDs (see serving), and return
# once one stopped answering. A server is declared gone after three failed polls in a row — about ten seconds — so
# a request that takes long does not read as an exit.
watch_servers() {
    (( $# > 0 && $# % 3 == 0 )) || die "watch_servers takes triples <name> <url> <log>, got $# arguments."
    local names=() urls=() logs=() down=() i gone
    while (( $# )); do
        names+=("$1"); urls+=("$2"); logs+=("$3"); down+=(0)
        shift 3
    done
    while :; do
        sleep 3
        gone=false
        for i in "${!names[@]}"; do
            if serving "${urls[$i]}"; then down[i]=0; else down[i]=$((down[i] + 1)); fi
            (( down[i] >= 3 )) && gone=true
        done
        $gone && break
    done
    for i in "${!names[@]}"; do
        if (( down[i] >= 3 )); then
            echo "!! The ${names[$i]} stopped answering on ${urls[$i]} — see ${logs[$i]}" >&2
        fi
    done
    return 0
}

# --- bootstrap ---------------------------------------------------------------------------------------

# The backend writes a marker at its first start on a database (docs/code-data-config-plan.md §7.2, D4): pending
# for a new database, existing for one that held data. A bootstrap runs once, on a new, empty database, so a start
# posts the bootstrap only on pending (and the backend refuses a pending database that holds data). A bootstrap that
# failed leaves failed, and one that broke off (its backend stopped mid-run, e.g. Ctrl+C) leaves in-progress: neither
# is ever run again on the same database, so the launcher prints the state and the remedy and posts nothing. When
# bootstrap.sh reports that the bootstrap this start posted did not finish, the marker read afterwards says what
# happened (bootstrap_outcome): still pending, the bootstrap was refused before it started, for the reason
# bootstrap.sh printed; failed or in-progress, the remedy is named the same way, a live run waited for first; applied,
# the run finished after bootstrap.sh gave up waiting for it. The remedy, the database recreated empty, is the
# launcher's to name. A launcher never drops a database here. existing and applied are left alone. The command needs a
# sysadmin: the local realm's sysadmin / sysadmin through niord-web's password grant (KEYCLOAK_TOKEN_URL).
# At every start, whatever the marker reads, bootstrap_database first makes the missing local Keycloak clients of the
# active domains of each archive after the first that is a folder with a domains.json (ensure_domain_clients). The
# base product's archive, which always comes first, and a --sample run, which posts it alone, are never read, and
# nothing in Keycloak is ever updated or deleted.

# Prints the string field $1 of the JSON object on stdin, or fails when there is none.
json_field() {
    node -e '
        let s = "";
        process.stdin.on("data", d => (s += d)).on("end", () => {
            let v;
            try { v = JSON.parse(s)[process.argv[1]]; } catch { process.exit(1); }
            if (typeof v !== "string") process.exit(1);
            process.stdout.write(v);
        });' "$1"
}

# --- the local Keycloak clients of a country's domains -----------------------------------------------

# Keycloak's admin CLI in niord-keycloak, with the arguments given. MSYS_NO_PATHCONV keeps Git Bash from turning the
# container's path into a Windows one.
keycloak_admin() { MSYS_NO_PATHCONV=1 docker exec niord-keycloak /opt/keycloak/bin/kcadm.sh "$@"; }

# Prints "<domainId><TAB><name>" for each domain of the JSON list on stdin (a domains.json, a leading BOM allowed)
# whose active is true, a tab or line break in either made a space, or fails when stdin is not a JSON list. A domain
# without the flag is imported inactive, so it is not listed.
active_domains() {
    node -e '
        let s = "";
        process.stdin.setEncoding("utf8");
        process.stdin.on("data", d => (s += d)).on("end", () => {
            let list;
            try { list = JSON.parse(s.replace(/^\uFEFF/, "")); } catch { process.exit(1); }
            if (!Array.isArray(list)) process.exit(1);
            const flat = v => v.replace(/[\t\r\n]/g, " ");
            for (const d of list) {
                if (d && d.active === true && typeof d.domainId === "string") {
                    const name = typeof d.name === "string" ? flat(d.name) : "";
                    process.stdout.write(flat(d.domainId) + "\t" + name + "\n");
                }
            }
        });'
}

# Prints the string field $3 of the first element of the JSON list on stdin whose field $1 is $2: exit 1 when there
# is none, 2 when stdin is not a JSON list or that field is not a string.
json_find() {
    node -e '
        let s = "";
        process.stdin.setEncoding("utf8");
        process.stdin.on("data", d => (s += d)).on("end", () => {
            const [key, value, field] = process.argv.slice(1);
            let list;
            try { list = JSON.parse(s); } catch { process.exit(2); }
            if (!Array.isArray(list)) process.exit(2);
            const hit = list.find(e => e && e[key] === value);
            if (!hit) process.exit(1);
            if (typeof hit[field] !== "string") process.exit(2);
            process.stdout.write(hit[field]);
        });' "$1" "$2" "$3"
}

# Gives the new domain client $1 (its Keycloak id $2) the five roles as the local realm's niord-client-master has
# them: user and publication-curate plain, editor holding user, admin holding editor and publication-curate, sysadmin
# holding editor, admin and publication-curate; then the group Sysadmin sysadmin and publication-curate. Fails at the
# first call that fails, and what was made stays as it is.
domain_client_roles() {
    local client="$1" cid="$2" role roles editor admin sysadmin
    for role in user:User editor:Editor admin:Administrator 'sysadmin:System administrator' \
            'publication-curate:Publication curator'; do
        keycloak_admin create "clients/$cid/roles" -r niord -s "name=${role%%:*}" -s "description=${role#*:}" -i \
            >/dev/null || return 1
    done
    roles=$(keycloak_admin get "clients/$cid/roles" -r niord --fields id,name) || return 1
    editor=$(json_find name editor id <<< "$roles") || return 1
    admin=$(json_find name admin id <<< "$roles") || return 1
    sysadmin=$(json_find name sysadmin id <<< "$roles") || return 1
    # A composite takes its client roles through the role's id (--rid); --rname would name a realm role
    keycloak_admin add-roles -r niord --rid "$editor" --cclientid "$client" --rolename user || return 1
    keycloak_admin add-roles -r niord --rid "$admin" --cclientid "$client" --rolename editor \
        --rolename publication-curate || return 1
    keycloak_admin add-roles -r niord --rid "$sysadmin" --cclientid "$client" --rolename editor --rolename admin \
        --rolename publication-curate || return 1
    # The group by its name, not its path, so that no argument starts with a slash
    keycloak_admin add-roles -r niord --gname Sysadmin --cclientid "$client" --rolename sysadmin \
        --rolename publication-curate || return 1
}

# Makes the local Keycloak client of each active domain of the domains.json $1 (a country's be/bootstrap/) that the
# shared local realm niord lacks: shaped as the realm's own domain clients are (its client ID the domain id,
# bearer-only), with its five roles and the group Sysadmin's (domain_client_roles), so the local sysadmin sees the
# domain's Sysadmin menu. A client that exists is left exactly as it is, and nothing in Keycloak is ever updated or
# deleted, so a second start adds nothing. Through kcadm.sh in niord-keycloak, signed in at the container's own
# loopback as Keycloak's local administrator. A failure is reported and never fatal: the next start tries again.
ensure_domain_clients() {
    local file="$1" list id name signin clients cid rc i create ids=() names=() present=() made=()
    list=$(active_domains < "$file") || {
        echo "!! $file is not a JSON list of domains: no local Keycloak client is checked." >&2
        return 0
    }
    while IFS=$'\t' read -r id name; do
        [[ -n "$id" ]] || continue
        ids+=("$id")
        names+=("$name")
    done <<< "$list"
    (( ${#ids[@]} )) || return 0
    # Signed in quietly: kcadm.sh's 'Logging into' line is printed only when the sign-in fails
    signin=$(keycloak_admin config credentials --server "$KEYCLOAK_ADMIN_SERVER" --realm master \
                 --user admin --password admin 2>&1) || {
        if [[ -n "$signin" ]]; then echo "$signin" >&2; fi
        echo "!! kcadm.sh in niord-keycloak could not sign in as the local administrator (above), so the local Keycloak clients of ${ids[*]} are not checked: until they exist, the local sysadmin sees Messages only in those domains. docker logs niord-keycloak says why; the next ./start-local.sh tries again." >&2
        return 0
    }
    # One list for all the domains: each kcadm.sh call starts a JVM in the container
    clients=$(keycloak_admin get clients -r niord --fields id,clientId) || {
        echo "!! The clients of the local realm niord could not be listed (above), so the local Keycloak clients of ${ids[*]} are not checked. docker logs niord-keycloak says why; the next ./start-local.sh tries again." >&2
        return 0
    }
    for i in "${!ids[@]}"; do
        id="${ids[$i]}"
        name="${names[$i]}"
        # Only so that no id is read as an option; which ids a domain may have is the validator's
        if [[ ! "$id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
            echo "!! The domain id '$id' of $file is no plain client ID: no local Keycloak client is made for it." >&2
            continue
        fi
        rc=0
        json_find clientId "$id" id <<< "$clients" >/dev/null || rc=$?
        case "$rc" in
            0) present+=("$id"); continue ;;
            1) ;;
            *)
                echo "!! The clients of the local realm niord that kcadm.sh listed could not be read, so the local Keycloak clients of ${ids[*]} are not checked; the next ./start-local.sh tries again." >&2
                return 0
                ;;
        esac
        echo ">> Creating the local Keycloak client $id: the roles user, editor, admin, sysadmin and publication-curate, the last two for the group Sysadmin"
        create=(-s "clientId=$id")
        if [[ -n "$name" ]]; then create+=(-s "name=$name"); fi
        create+=(-s enabled=true -s bearerOnly=true -s publicClient=false -s protocol=openid-connect
                 -s standardFlowEnabled=true -s directAccessGrantsEnabled=true -s fullScopeAllowed=false -i)
        cid=$(keycloak_admin create clients -r niord "${create[@]}") || cid=""
        cid="${cid%$'\r'}"
        if [[ -z "$cid" ]]; then
            echo "!! The local Keycloak client $id could not be created (above); the next ./start-local.sh tries again." >&2
            continue
        fi
        if ! domain_client_roles "$id" "$cid"; then
            echo "!! The local Keycloak client $id was created, but its roles are not all there (above). A client that exists is never changed here: delete $id in Keycloak's admin console (http://localhost:8080/auth/admin/, admin / admin, realm niord, Clients), and the next ./start-local.sh creates it whole." >&2
            continue
        fi
        made+=("$id")
    done
    if (( ${#present[@]} )); then
        echo ">> The local Keycloak clients ${present[*]} exist: left as they are"
    fi
    if (( ${#made[@]} )); then
        echo ">> Created the local Keycloak clients ${made[*]}: a sysadmin signed in before signs in again to see their domains' Sysadmin menu"
    fi
    return 0
}

# What a bootstrap this start posted came to, when bootstrap.sh reported that it did not finish: the marker read
# afterwards ($1, empty when it could not be read) on database $2, with the remedy $3 and the backend's log $4.
# applied sets BOOTSTRAP_STATE, so that --sample loads; every other state is reported, and none is fatal.
bootstrap_outcome() {
    local state="$1" db="$2" recreate="$3" log="$4"
    case "$state" in
        applied)
            BOOTSTRAP_STATE=applied
            echo ">> The bootstrap finished after bootstrap.sh gave up waiting for it: database $db reads applied"
            ;;
        pending)
            echo "!! The bootstrap was refused before it started (the reason above), and database $db was left as it was; for a database that holds data, $recreate." >&2
            ;;
        failed)
            echo "!! The bootstrap failed (see above and $log), and is never run again on database $db: $recreate." >&2
            ;;
        in-progress)
            echo "!! A bootstrap is still running on database $db, or broke off: wait, GET /rest/bootstrap shows its outcome; only once it reads failed, or in-progress with no sign of life for 5 minutes, $recreate." >&2
            ;;
        *)
            echo "!! The bootstrap did not finish (see above and $log)." >&2
            ;;
    esac
}

# Bootstraps the database $2 of the backend at $1 when its marker reads pending, with the archives given after the
# remedy $3 (how the database is recreated empty), the backend's log $4 and a work folder $5: each archive a zip, or
# a folder that is zipped into $5/bootstrap-<n>.zip (n its place in the list) only then, all posted in the order
# given in one bootstrap.sh run, the base product's first. A failure here leaves the stack running on the database
# as it is, so it is reported, not fatal.
bootstrap_database() {
    local base_url="$1" db="$2" recreate="$3" log="$4" work="$5"
    shift 5
    local token state answer code=""
    # Before the token, so that it runs at every start whatever follows, and before the frontends start, so that the
    # first sign-in carries the new domains' roles: never the first archive, the base product's (see above).
    local folder
    for folder in "${@:2}"; do
        if [[ -d "$folder" && -f "$folder/domains.json" ]]; then ensure_domain_clients "$folder/domains.json"; fi
    done
    token=$(curl -s -m 30 "$KEYCLOAK_TOKEN_URL" -d grant_type=password -d client_id=niord-web \
            -d username=sysadmin -d password=sysadmin | json_field access_token) || {
        echo "!! No sysadmin token from $KEYCLOAK_TOKEN_URL — the database is not bootstrapped." >&2
        return 0
    }
    # The backend answers HTTP before its start-up listeners have run (the bootstrap marker, the default texts and
    # settings): until then GET /rest/bootstrap answers 503, the backend is still starting. So the state is read
    # again for up to 120 s before the run gives up.
    local deadline=$((SECONDS + 120))
    while :; do
        # The token goes to curl as a config on standard input, as in bootstrap.sh, so no process's arguments
        # carry it. The status code follows the body on a line of its own.
        answer=$(curl -s -m 30 -w '\n%{http_code}' -K - "$base_url/rest/bootstrap" \
                     <<< "header = \"Authorization: Bearer $token\"" || true)
        code="${answer##*$'\n'}"
        state=$(printf '%s' "${answer%$'\n'*}" | json_field state) && break
        if (( SECONDS >= deadline )); then
            if [[ "$code" == 503 ]]; then
                echo "!! The backend is still starting after 120 s (GET /rest/bootstrap answers 503) — the database is not bootstrapped. See $log" >&2
            else
                echo "!! GET /rest/bootstrap gave no state (HTTP $code) — the database is not bootstrapped. See $log" >&2
            fi
            return 0
        fi
        sleep 2
    done
    case "$state" in
        pending)
            echo ">> Bootstrapping database $db with $# archive$( (( $# == 1 )) || echo s) in one run — tail -f $log shows each job"
            local archives=() n=0 item zip
            for item in "$@"; do
                n=$((n + 1))
                if [[ -d "$item" ]]; then
                    zip="$work/bootstrap-$n.zip"
                    rm -f "$zip"
                    "$JAVA_HOME/bin/jar" --create --no-manifest --file "$(native_path "$zip")" \
                        -C "$(native_path "$item")" . || {
                        echo "!! Could not zip $item — the database is not bootstrapped." >&2
                        return 0
                    }
                    archives+=("$(native_path "$zip")")
                elif [[ -f "$item" ]]; then
                    archives+=("$(native_path "$item")")
                else
                    echo "!! No bootstrap archive or folder $item — the database is not bootstrapped." >&2
                    return 0
                fi
            done
            # One run, one marker. bootstrap.sh (beside this file, library_dir) reports the outcome; a bootstrap that
            # did not finish is never posted again on this database (docs/code-data-config-plan.md §7.2, D4). Run with
            # the running bash, as the published zip keeps no exec bits.
            if NIORD_TOKEN="$token" "${BASH:-bash}" "$(library_dir)/bootstrap.sh" "$base_url" "${archives[@]}"; then
                BOOTSTRAP_STATE=applied
            else
                # The marker, read once more with a new token (the first may have expired during the run), names
                # the outcome: bootstrap.sh also gives up while a run is live (its timeout passed, its token expired
                # mid-poll, or a backend of another run claimed the marker first), and a database is never dropped
                # under a live run.
                local after=""
                token=$(curl -s -m 30 "$KEYCLOAK_TOKEN_URL" -d grant_type=password -d client_id=niord-web \
                        -d username=sysadmin -d password=sysadmin | json_field access_token) &&
                    after=$(curl -s -m 30 -K - "$base_url/rest/bootstrap" \
                                <<< "header = \"Authorization: Bearer $token\"" | json_field state) || after=""
                bootstrap_outcome "$after" "$db" "$recreate" "$log"
            fi
            ;;
        failed|in-progress)
            echo "!! The bootstrap marker of database $db reads $state: a bootstrap failed there, or one is running or broke off, and a bootstrap is never run again on the same database, so nothing is posted. If a bootstrap is still running in a backend of another run, wait for it; otherwise $recreate." >&2
            ;;
        existing|applied)
            BOOTSTRAP_STATE="$state"
            echo ">> Database $db already holds data ($state): nothing is bootstrapped"
            ;;
        *)
            echo "!! Unknown bootstrap state '$state' — the database is not bootstrapped." >&2
            ;;
    esac
}

# --- the sample --------------------------------------------------------------------------------------

# The test load-sample.sh makes before it writes anything, on the database $1: the sample's domain and a message
# titled "SAMPLE —" (chr(8212) is the dash, so the command line stays ASCII).
sample_present() {
    [[ "$(docker exec niord-db psql -U niord -d "$1" -tAc \
        "select exists (select 1 from \"Domain\" where \"domainId\" = 'niord-nw')
            and exists (select 1 from \"MessageDesc\" where title like 'SAMPLE ' || chr(8212) || '%')")" == t ]]
}

# After the bootstrap: load the sample with the loader $1 (a script, run with bash) into the database $2 while no
# backend runs (the Niord home $3 is kept for the callers of the days the attachments were files; they are rows of
# the SQL now), then start the backend again (start_backend),
# since it caches the domains and builds its message index at start. The loader refuses anything but the loopback
# niord_sample or niord_sample_<cc>, and the sample's own guard refuses a database it was not made for, naming the
# remedy: --reset for a database whose sample id range is taken or whose base rows do not resolve by natural key, a
# regeneration for a sample made on another schema.
sample_load() {
    local loader="$1" db="$2" home="$3" rc=0
    if sample_present "$db"; then
        # The sample's attachments are rows of the database too, so a later run has nothing to copy
        echo ">> $db holds a sample loaded earlier: starting it (a changed sample needs ./start-local.sh --sample --reset)"
        return 0
    fi
    case "$BOOTSTRAP_STATE" in
        applied) ;;
        existing) die "$db holds data the sample did not put there: ./start-local.sh --sample --reset" ;;
        *) die "$db is not bootstrapped (above), so the sample is not loaded: ./start-local.sh --sample --reset" ;;
    esac
    echo ">> Stopping the backend to load the sample"
    stop_backend
    echo ">> Loading the sample into $db ($loader)"
    "${BASH:-bash}" "$loader" --host localhost --db "$db" || rc=$?
    if (( rc != 0 )); then
        die "The sample was not loaded (load-sample.sh exited $rc): the refusal above names the remedy"
    fi
    echo ">> Starting the backend again, so that its caches and message index see the sample"
    start_backend
    wait_for "backend" "$BE_PID" "$BE_URL" 900 "$BE_LOG"
    echo ">> Backend is up"
}

# --reset: drop the sample's database $1, which must be niord_sample or niord_sample_<cc> (a two-letter lower-case
# country code; a country's launcher names its own), refused before any docker call; and refused while a client
# session is connected to it (a backend of another run, or psql). The caller spells the name out, never taking it
# from a variable a user may have exported for another run.
sample_reset() {
    local db="$1"
    case "$db" in
        niord_sample|niord_sample_[abcdefghijklmnopqrstuvwxyz][abcdefghijklmnopqrstuvwxyz]) ;;
        *) die "sample_reset drops only the sample's database, niord_sample or niord_sample_<cc>, not '$db'" ;;
    esac
    if [[ "$(docker exec niord-db psql -U niord -d postgres -tAc "select count(*) from pg_stat_activity where datname = '$db' and backend_type = 'client backend'")" != "0" ]]; then
        die "A session is connected to $db (a backend of another run, or psql): stop it, then retry --reset."
    fi
    echo ">> Dropping database $db (--reset)"
    docker exec niord-db dropdb -U niord --if-exists "$db"
}
