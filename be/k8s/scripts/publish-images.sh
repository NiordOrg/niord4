#!/usr/bin/env bash
# Build, check and push the deployable images from split jars and web bundles.
#
#   publish-images.sh <module>...        # e.g. niord-app niord-dk niord-gl
#   publish-images.sh --image-repo <module>   # print the module's Harbor repository name and exit
#
# Environment:
#   DIST_DIR     what split-jar.sh wrote: <DIST_DIR>/<module>/app/{app.jar,lib/}, <DIST_DIR>/<module>/deps.sha256
#                and <DIST_DIR>/deps/<hash>/*.jar
#   WEB_DIR      the web bundles: <WEB_DIR>/<module>/ holds the deployable's build of the administration
#                app (index.html at its root); an empty directory gives an API-only image
#   VERSION      the image tag
#   IMAGE_PREFIX what goes before the repository name: "<registry>/<project>" in CI, empty locally
#   IMAGE_REPO   the repository name; default the module's Harbor repository (image_repo below:
#                niord/niord-app-en for the generic niord-app, niord/niord-app-<cc> for niord-<cc>).
#                Only for a single module.
#   PUSH         1 (default) pushes each image after its checks; 0 only builds (build-image.sh)
#   SHA, SOURCE_URL   optional, go into the image labels as in the publish workflow
#   SUMMARY      a markdown file the run's summary is appended to; default $GITHUB_STEP_SUMMARY,
#                else stdout
#
# The script runs in the publish workflow's harbor job with the Harbor robot's Docker session
# (the login and the logout stay in the workflow) and locally through build-image.sh, so the local
# image is built by the code that builds the published one. It holds no module list and no path
# into the monorepo: a country repository's pipeline calls it with its one module and a checkout
# of this directory (docs/image-split-plan.md §7).
#
# What it does (docs/image-split-plan.md §3.5), once per distinct dependency set:
#   1. makes the dependency tree the Dockerfile copies to /: app/lib/*.jar and an empty niord-home/,
#      modes normalised and every entry at one fixed time (1970-01-01 00:00:01 UTC: portable to
#      macOS's touch, and not the zero Java also reports for a missing file). An Actions artifact
#      keeps neither modes nor empty directories, so this is done here and not in the build job;
#   2. checks that the dependency layer is reproducible: builds the `deps` stage twice without cache,
#      a second apart, and fails unless both produce the same layers. The layer is what the saving
#      rests on; a layer that depends on the build time would cost ~158 MB on every publish.
# Then, per module:
#   3. builds the image with plain `docker build` and the three named build contexts. --provenance=false
#      keeps the pushed tag a single manifest with the containerd image store too (Docker 29), where
#      a plain build would otherwise push an index with an attestation manifest;
#   4. checks the content: /app/app.jar is the module's thin jar, /app/lib holds exactly one
#      niord-base-*.jar, and the other jars hash to deps.sha256 (the hash of split-jar.sh);
#   5. pushes it (PUSH=1) and writes the image and its digest to the summary.
#
# The summary names the dependency layer's diff ID: two publishes with the same dependencies show
# the same value, and a publish that shows a new one without a dependency change is the sign that
# the layer is not stable any more.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$HERE/../docker"
SPLIT_JAR="$HERE/split-jar.sh"

# The Harbor repository of a module's image (docs/image-split-plan.md §3.7). One pattern for the
# three deployables, niord/niord-app-<country>; the Maven module niord-app is the generic English
# deployable, so it is `en`. The one place that knows this: build-image.sh asks with --image-repo.
image_repo() {
  case "$1" in
    niord-app) echo "niord/niord-app-en" ;;
    niord-??)  echo "niord/niord-app-${1#niord-}" ;;
    *) echo "ERROR: no image repository for module '$1' (expected niord-app or niord-<cc>)" >&2; return 1 ;;
  esac
}
if [[ "${1:-}" == "--image-repo" ]]; then
  [[ $# -eq 2 ]] || { echo "usage: $0 --image-repo <module>" >&2; exit 2; }
  image_repo "$2"; exit $?
fi

[[ $# -ge 1 ]] || { echo "usage: $0 <module>...   |   $0 --image-repo <module>" >&2; exit 2; }
: "${DIST_DIR:?DIST_DIR is not set}" "${WEB_DIR:?WEB_DIR is not set}" "${VERSION:?VERSION is not set}"
PUSH="${PUSH:-1}"
IMAGE_PREFIX="${IMAGE_PREFIX:-}"
SUMMARY="${SUMMARY:-${GITHUB_STEP_SUMMARY:-/dev/stdout}}"
if [[ -n "${IMAGE_REPO:-}" && $# -gt 1 ]]; then echo "ERROR: IMAGE_REPO names one repository; give one module" >&2; exit 2; fi

# split-jar.sh's helper, the same text: sha256sum's or shasum's output with the two spaces everywhere.
sha256() {
  { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; } \
    | sed 's/^\([0-9a-f]\{64\}\) \*/\1  /'
}
fail() { echo "::error::$*" >&2; echo "ERROR: $*" >&2; exit 1; }

# Fixed time for every entry of the dependency tree (see the header). -t is the form both GNU and
# BSD touch accept.
FIXED_TIME=197001010000.01

WORK="$(mktemp -d "${TMPDIR:-/tmp}/publish-images.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# --- The inputs, checked before any Docker call -------------------------------
# (No associative arrays: macOS ships bash 3.2.) A module's set is its deps.sha256; a set's tree
# is $WORK/deptree-<hash>.
set_of() { cat "$DIST_DIR/$1/deps.sha256"; }
tree_of() { printf '%s' "$WORK/deptree-$1"; }
for module in "$@"; do
  d="$DIST_DIR/$module"
  [[ -f "$d/app/app.jar" && -f "$d/deps.sha256" ]] || fail "$d has no app/app.jar or deps.sha256 — run split-jar.sh first"
  [[ -d "$WEB_DIR/$module" ]] || fail "$WEB_DIR/$module is missing (an empty directory gives an API-only image)"
  [[ -d "$DIST_DIR/deps/$(set_of "$module")" ]] || fail "$DIST_DIR/deps/$(set_of "$module") is missing"
done

# --- 1 + 2: one dependency tree per distinct set, and its reproducibility ------
{
  echo "## Images $VERSION"
  echo ""
} >> "$SUMMARY"
for hash in $(for m in "$@"; do set_of "$m"; done | sort -u); do
  jars="$DIST_DIR/deps/$hash"
  actual="$("$SPLIT_JAR" --hash "$jars")"
  [[ "$actual" == "$hash" ]] || fail "$jars hashes to $actual, not to its name"

  tree="$(tree_of "$hash")"
  mkdir -p "$tree/app/lib" "$tree/niord-home"
  cp "$jars"/*.jar "$tree/app/lib/"
  chmod -R u=rwX,go=rX "$tree"
  find "$tree" -exec env TZ=UTC touch -h -t "$FIXED_TIME" {} +

  echo ">> Checking that the dependency layer of set $hash is reproducible"
  layers_of_build() {
    local id
    id="$(docker build -q --no-cache --target deps --build-context "deptree=$tree" "$DOCKER_DIR")"
    [[ -n "$id" ]] || fail "docker build of the deps stage produced no image"
    docker image inspect --format '{{json .RootFS.Layers}}' "$id"
    docker image rm -f "$id" >/dev/null
  }
  first="$(layers_of_build)"; sleep 1; second="$(layers_of_build)"
  [[ -n "$first" && "$first" != "null" && "$first" != "[]" ]] || fail "could not read the layers of the deps stage"
  [[ "$first" == "$second" ]] || fail "the dependency layer is not reproducible: two builds a second apart differ ($first vs $second). Something in the Dockerfile before the dependency COPY creates a directory, or the tree is not normalised."
  deps_layer="$(printf '%s' "$first" | sed -E 's/.*"([^"]+)"\]$/\1/')"
  echo "   dependency layer $deps_layer"
  echo "Dependency layer \`$deps_layer\` (jar set \`$hash\`, $(ls "$jars" | wc -l | tr -d ' ') jars)" >> "$SUMMARY"
  echo "" >> "$SUMMARY"
done

# --- 3 + 4 + 5: the images --------------------------------------------------------
{
  echo "| Image | Digest |"
  echo "|---|---|"
} >> "$SUMMARY"
for module in "$@"; do
  hash="$(set_of "$module")"
  repo="${IMAGE_REPO:-$(image_repo "$module")}"
  image="${IMAGE_PREFIX:+$IMAGE_PREFIX/}$repo:$VERSION"
  labels=(--label "org.opencontainers.image.version=$VERSION")
  [[ -n "${SHA:-}" ]] && labels+=(--label "org.opencontainers.image.revision=$SHA")
  [[ -n "${SOURCE_URL:-}" ]] && labels+=(--label "org.opencontainers.image.source=$SOURCE_URL")

  echo ">> Building $image"
  docker build --provenance=false \
    --build-context "deptree=$(tree_of "$hash")" \
    --build-context "app=$DIST_DIR/$module/app" \
    --build-context "web=$WEB_DIR/$module" \
    "${labels[@]}" -t "$image" "$DOCKER_DIR"

  echo ">> Checking the content of $image"
  # The image only hashes; the comparison is made here. It sets LC_ALL=en_US.UTF-8, so the jar
  # list is sorted on this side under LC_ALL=C, as split-jar.sh sorts it.
  listing="$(docker run --rm --network none --entrypoint sh "$image" -c \
    'cd /app && sha256sum app.jar && echo --- && cd lib && sha256sum -- *.jar')"
  want_app="$(sha256 "$DIST_DIR/$module/app/app.jar" | cut -d' ' -f1)"
  got_app="$(printf '%s\n' "$listing" | sed -n '1s/ .*//p')"
  [[ "$got_app" == "$want_app" ]] || fail "$image: /app/app.jar is not $module's thin jar ($got_app vs $want_app)"
  libs="$(printf '%s\n' "$listing" | sed '1,/^---$/d')"
  base_count="$(printf '%s\n' "$libs" | grep -c '  niord-base-.*\.jar$' || true)"
  [[ "$base_count" -eq 1 ]] || fail "$image: /app/lib holds $base_count niord-base jars, not one"
  got_set="$(printf '%s\n' "$libs" | grep -v '  niord-base-.*\.jar$' | LC_ALL=C sort -k2 | sha256 | cut -d' ' -f1)"
  [[ "$got_set" == "$hash" ]] || fail "$image: the third-party jars in /app/lib hash to $got_set, not to $hash"

  if [[ "$PUSH" == "1" ]]; then
    docker push "$image"
    digest="$(docker image inspect --format '{{ index .RepoDigests 0 }}' "$image")"
    echo "| \`$image\` | \`${digest#*@}\` |" >> "$SUMMARY"
  else
    echo "| \`$image\` | (not pushed) |" >> "$SUMMARY"
  fi
done
echo ">> Done."
