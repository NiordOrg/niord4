#!/usr/bin/env bash
# Build a Niord deployable's container image for the local OrbStack Kubernetes cluster.
#
#   ./build-image.sh                     # niord-app: build the fat jar (if missing), the web app, then the image
#   ./build-image.sh --rebuild           # force a fresh `mvn package`
#   ./build-image.sh --no-web            # skip the web app: the image serves the API only
#
# The web app is the generic production build of the administration app (fe/ui-apps/admin, `npm run
# build`), the same build the publish workflow puts into the published image. It needs Node and
# `npm ci` run once in fe/. The country deployables are built in their own repositories since
# 2026-10-06 (SIFA-DK/niord-dk, SIFA-DK/niord-gl, their image.yml), with these scripts from the
# niord-image-tools package.
#
# The image is named like its Harbor repository, niord/niord-app-en (publish-images.sh --image-repo),
# and tagged with the chart's appVersion — the tag the chart's app.image.tag default resolves to — so a
# plain helm install finds it: niord/niord-app-en:4.0.0-SNAPSHOT, the image the chart's defaults
# reference. Override with IMAGE_REPO / IMAGE_TAG.
#
# The image itself is built the way the publish workflow builds it: the fat jar is unpacked by
# split-jar.sh and publish-images.sh (with PUSH=0) makes the image from the pieces, checks that its
# dependency layer is reproducible and checks its content. Needs `java` (JDK 25) also when an
# existing jar is reused. See docs/image-split-plan.md.
#
# OrbStack shares Docker images with its Kubernetes, so no registry push is needed;
# the chart references the image by tag with imagePullPolicy=IfNotPresent.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CHART_YAML="${REPO_ROOT}/k8s/helm/niord/Chart.yaml"

FE_DIR="$(cd "${REPO_ROOT}/.." && pwd)/fe"

MODULE=niord-app
WEB_SCRIPT=build
REBUILD=0
WEB=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --rebuild) REBUILD=1 ;;
    --no-web)  WEB=0 ;;
    --module|--module=*) echo "--module is gone: this repository builds niord-app only; niord-dk and niord-gl build their images in their own repositories" >&2; exit 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done
# Checked before the Maven build, so a machine that cannot build the web app stops at once rather
# than after it.
if [[ "$WEB" == "1" ]]; then
  command -v npm >/dev/null 2>&1 || { echo "ERROR: npm is not on the PATH — install Node, or pass --no-web" >&2; exit 1; }
  [[ -d "${FE_DIR}/node_modules" ]] || { echo "ERROR: ${FE_DIR}/node_modules is missing — run 'npm ci' in fe/ first, or pass --no-web" >&2; exit 1; }
fi

APP_VERSION="$(sed -n 's/^appVersion:[[:space:]]*"\{0,1\}\([^"]*\)"\{0,1\}[[:space:]]*$/\1/p' "$CHART_YAML")"
[[ -n "$APP_VERSION" ]] || { echo "ERROR: no appVersion in $CHART_YAML" >&2; exit 1; }
IMAGE_REPO="${IMAGE_REPO:-$("${SCRIPT_DIR}/publish-images.sh" --image-repo "$MODULE")}"
IMAGE_TAG="${IMAGE_TAG:-$APP_VERSION}"
IMAGE="${IMAGE_REPO}:${IMAGE_TAG}"

# --- Host build of the runnable fat jar -------------------------------------
# The jar is <module>-<version>.jar; the version is the reactor's `revision` (be/pom.xml), so it is
# found by pattern rather than spelled out here. Spring Boot's repackage leaves the thin jar next to
# it as *.jar.original, which the pattern does not match. An existing jar is reused without checking
# what changed since it was built: after an edit under be/ or to the portrayal definition in
# fe/shared-map/portrayal (which the jar carries for the map images), pass --rebuild.
find_jar() { ls "${REPO_ROOT}/${MODULE}/target/${MODULE}-"*.jar 2>/dev/null || true; }

# Java is needed for the Maven build and for the extract in split-jar.sh, so it is resolved here,
# whether or not Maven runs.
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 25 2>/dev/null || true)}"
export JAVA_HOME
echo ">> JAVA_HOME=${JAVA_HOME:-<default>}"

JAR="$(find_jar)"
if [[ "$REBUILD" == "1" || -z "$JAR" ]]; then
  echo ">> Building ${MODULE} fat jar with Maven (JDK 25)..."
  ( cd "$REPO_ROOT" && ./mvnw -q -pl "$MODULE" -am clean package -DskipTests )
  JAR="$(find_jar)"
else
  echo ">> Reusing existing jar: $JAR"
fi

[[ -n "$JAR" ]] || { echo "ERROR: no ${MODULE}-*.jar under ${MODULE}/target" >&2; exit 1; }
[[ "$(printf '%s\n' "$JAR" | wc -l)" -eq 1 ]] || { echo "ERROR: more than one jar under ${MODULE}/target — run with --rebuild:" >&2; printf '%s\n' "$JAR" >&2; exit 1; }

# --- The web app -------------------------------------------------------------
# Built into a temporary directory, so the fe/dist of an ongoing `ng build` is left alone. The
# image gets it as a build context, <WEB_DIR>/<module>/; with --no-web that directory stays empty
# and the image serves the API only (WebAppConfig needs index.html). Everything is made under one
# temporary directory that the trap removes.
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
WEB_DIR="${WORK}/web"
mkdir -p "${WEB_DIR}/${MODULE}"
if [[ "$WEB" == "1" ]]; then
  echo ">> Building the web app: npm run ${WEB_SCRIPT}"
  ( cd "$FE_DIR" && npm run -s "$WEB_SCRIPT" -- --output-path "${WORK}/web-out" )
  [[ -f "${WORK}/web-out/browser/index.html" ]] || { echo "ERROR: the web app build left no browser/index.html in ${WORK}/web-out" >&2; exit 1; }
  cp -R "${WORK}/web-out/browser/." "${WEB_DIR}/${MODULE}/"
else
  echo ">> Skipping the web app (--no-web): the image serves the API only"
fi

# --- Split the jar and build the image -----------------------------------------
# The same two steps as the publish workflow (its build and harbor jobs), see the header.
echo ">> Splitting the jar"
"${SCRIPT_DIR}/split-jar.sh" "$JAR" "${WORK}/dist" "$MODULE"

echo ">> Building image: ${IMAGE}"
DIST_DIR="${WORK}/dist" WEB_DIR="$WEB_DIR" IMAGE_PREFIX="" IMAGE_REPO="$IMAGE_REPO" VERSION="$IMAGE_TAG" PUSH=0 \
  SUMMARY=/dev/null "${SCRIPT_DIR}/publish-images.sh" "$MODULE"

echo ">> Done. Image '${IMAGE}' is available to OrbStack Kubernetes."
