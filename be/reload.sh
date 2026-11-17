#!/usr/bin/env bash
#
# Hot-reload the application started by ./run.sh — run this from a second terminal after editing
# code, and keep run.sh running.
#
# It recompiles whatever changed in niord/ and niord-app/ (a reactor compile, so niord-app sees
# niord's fresh output directly — no install involved). If anything was actually recompiled,
# spring-boot-devtools inside the running app notices the changed classpath directories and
# restarts the application on the warm JVM in a few seconds: no Maven install, no JVM cold start,
# no docker-compose wait, and the database is untouched. If nothing changed, nothing restarts.
#
# The niord/ half of this works because niord-app's spring-boot-maven-plugin configuration swaps
# the ~/.m2 niord-base jar for ../niord/target/classes on the run classpath (see niord-app/pom.xml):
# devtools only watches classpath DIRECTORIES, so the jar could never trigger a restart.
#
# Not covered — do a full stop and ./run.sh instead: dependency/pom changes (the running JVM keeps
# its old dependency classpath), and changes the app only reads at start-up (the settings it adds when the
# database lacks them, Liquibase changelogs already applied).
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

exec ./mvnw -B -ntp -pl niord,niord-app compile
