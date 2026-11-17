#!/usr/bin/env bash
#
# Start the generic English Niord application (niord-app).
#
#   ./run.sh              the app in English; a new database holds no data until it is bootstrapped
#                         once (../start-local.sh does it, or scripts/bootstrap.sh by hand; README.md)
#
# Any further arguments are passed through to the spring-boot:run invocation, e.g.
#   ./run.sh -Dspring-boot.run.jvmArguments=-Dspring.docker.compose.enabled=false
#
# While the app is running, apply code changes WITHOUT a stop/start: edit, then run ./reload.sh in
# a second terminal. It recompiles what changed and spring-boot-devtools restarts the application
# on the warm JVM in a few seconds. Stop/start is only needed after dependency or pom changes.
#
# Why this wrapper exists rather than a bare `mvnw -pl niord-app spring-boot:run`:
#
#   1. `-pl niord-app` selects ONLY that module, and it depends on the reactor-local artifact
#      org.niord.base:niord-base:${revision}. In a fresh clone that artifact is not in the local
#      repository, so the run fails to resolve before the app ever starts. The install step below
#      puts it there.
#   2. Once it IS installed, `-pl niord-app` keeps resolving niord-base from ~/.m2 — so edits under
#      niord/ are silently ignored, with no error and no warning, until something reinstalls it.
#      Re-running the install every time removes that trap.
#
# `-pl niord-app -am spring-boot:run` is NOT an alternative to this: with -am the goal is invoked on
# every selected project, so it runs first against the parent pom and fails there with "Unable to
# find a suitable main class". The build and the run have to be separate invocations.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

# Even a no-change incremental install costs ~15s of Maven overhead, so skip it when it provably
# cannot matter: a previous install succeeded (the stamp — written below, wiped by `mvnw clean`
# because it lives under target/), the exact niord-base artifacts a run needs still exist in
# ~/.m2, niord/target/classes still exists (the run classpath swaps the jar for that directory —
# see the spring-boot-maven-plugin configuration in niord-app/pom.xml), and nothing under the two
# modules' sources, the three poms or ../fe/shared-map/portrayal (the portrayal definition, which
# the niord-base build copies onto its class path for the map images) is newer than the stamp.
# mtimes are a safe proxy here: git checkout/restore refresh them, and a deletion bumps the parent
# directory, which `find` also visits. Anything else — profiles, run.sh arguments — does not
# change what `install` would produce. When in doubt: `rm` the stamp (or `./mvnw clean`) and the
# full install is back.
#
# The ~/.m2 check names the exact version-pinned files, not a niord-base-*.jar wildcard: a
# wildcard also matches the -tests.jar alone or a leftover older version, and would then skip the
# install exactly when spring-boot:run is about to fail resolving the version. Both jars are
# required — the main jar for niord-app's compilation, and the test-jar because spring-boot:run
# forks the build to test-compile, which resolves the test-scoped niord-base test-jar dependency.
# NIORD_VERSION is the parent pom's `revision` default; keep it in sync (after a bump this check
# merely fails and the install runs, so drift costs seconds, never a broken skip).
NIORD_VERSION="${NIORD_VERSION:-4.0.0-SNAPSHOT}"
STAMP=niord-app/target/.run.sh-install.stamp
# NIORD_M2_REPO names another local repository to look in — ../start-local.sh --slot sets it, together with
# the MAVEN_ARGS that make Maven install there.
NIORD_BASE_M2="${NIORD_M2_REPO:-$HOME/.m2/repository}/org/niord/base/niord-base/$NIORD_VERSION"
if [[ -f "$STAMP" ]] \
    && [[ -d niord/target/classes ]] \
    && [[ -f "$NIORD_BASE_M2/niord-base-$NIORD_VERSION.jar" ]] \
    && [[ -f "$NIORD_BASE_M2/niord-base-$NIORD_VERSION-tests.jar" ]] \
    && [[ -z "$(find pom.xml niord/pom.xml niord/src ../fe/shared-map/portrayal niord-app/pom.xml niord-app/src -newer "$STAMP" -print -quit)" ]]; then
    echo ">> niord-base + niord-app unchanged since last install — skipping the build step."
else
    echo ">> Building and installing niord-base + niord-app ..."
    ./mvnw -B -ntp -pl niord-app -am install -DskipTests -DskipITs
    touch "$STAMP"
fi

# Spring Boot's docker-compose support runs `docker compose up` when none of the services runs, and the realm's bind
# source (~/.niord-local/keycloak/, docker-compose.yml) must exist by then.
( source scripts/local-run-lib.sh && stage_realm keycloak/niord-realm-local.json )

echo ">> Starting niord-app ..."
exec ./mvnw -B -ntp -pl niord-app spring-boot:run "$@"
