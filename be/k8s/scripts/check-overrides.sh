#!/usr/bin/env bash
# The backend override check of a country build (docs/fe-country-split-plan.md §4.9, §4.11 rule 3;
# be/docs/country-api.md). A country's workflow runs it after its package step, over the jar it built:
#
#   ./check-overrides.sh <the country's executable jar> <the country's be/ directory>
#   e.g. ./check-overrides.sh target/niord-dk-4.0.80.jar .
#
# It compares the country's own resources and classes (BOOT-INF/classes/, and the META-INF/ that Spring
# Boot keeps at the jar's root) with the pinned niord-base in the same jar (BOOT-INF/lib/niord-base-*.jar)
# and fails on every shadow of a niord-base resource the country's be/overrides.json does not list, on a
# shadow that may never be overridden, on a template or a defaults/base/ file in the jar, on a committed
# defaults/packs/, and on a listed override whose basedOn fingerprint no longer matches niord-base's file.
# Every run prints the listed overrides.
#
# Exit status: 0 the check passes, 1 it found something (each finding names its rule and path),
# 2 usage or unreadable input.
#
# The check is CheckOverrides.java beside this script, run by the JDK's source launcher: JDK built-ins
# only, nothing to build or fetch. Needs `java` 25 on the PATH or in JAVA_HOME, as split-jar.sh does.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
command -v "$JAVA" >/dev/null 2>&1 || { echo "ERROR: java not found (set JAVA_HOME or put java on the PATH)" >&2; exit 2; }

exec "$JAVA" "$SCRIPT_DIR/CheckOverrides.java" "$@"
