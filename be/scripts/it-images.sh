#!/usr/bin/env bash
#
# Print the Docker images the integration tests start with Testcontainers, one per line.
#
#   scripts/it-images.sh
#
# The names are read from the test sources rather than repeated here: NiordTestContainers.java
# pins the four shared containers (PostGIS, Artemis, MailHog, Keycloak) and
# LiquibaseBootstrapITTest.java its own fresh PostGIS (the same image today; listed once).
#
# The backend CI's integration job (.github/workflows/be-ci.yml) pulls them in the background
# while Maven compiles, so the first integration-test class finds every image locally and
# Testcontainers pulls nothing: on run 37513320751 (2026-10-06) the four pulls, one after the other
# and only once the compile was over, took about 50 s of the job. `./dev doctor` may list them too.
#
# Fails when it finds none, so a renamed constant or a moved file is noticed instead of silently
# pulling nothing.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

SOURCES=(
    niord/src/test/java/org/niord/test/NiordTestContainers.java
    niord/src/test/java/org/niord/test/LiquibaseBootstrapITTest.java
)

for f in "${SOURCES[@]}"; do
    if [ ! -f "$f" ]; then
        echo "it-images.sh: $f not found" >&2
        exit 1
    fi
done

# Two spellings: the `*_IMAGE = "..."` constants, and an inline `DockerImageName.parse("...")`.
# Duplicates are printed once, in order of first appearance.
images=$(grep -hoE '(_IMAGE[[:space:]]*=[[:space:]]*|DockerImageName\.parse\()"[^"]+"' "${SOURCES[@]}" \
    | sed -E 's/.*"([^"]+)"/\1/' \
    | awk '!seen[$0]++')

if [ -z "$images" ]; then
    echo "it-images.sh: no image names found in ${SOURCES[*]}" >&2
    exit 1
fi

# Every line must look like a tagged image reference (registry/repository:tag); anything else
# means the extraction matched something it should not have.
while IFS= read -r img; do
    if ! [[ "$img" =~ ^[a-z0-9][a-z0-9._/-]*:[A-Za-z0-9._-]+$ ]]; then
        echo "it-images.sh: '$img' does not look like an image reference" >&2
        exit 1
    fi
done <<< "$images"

printf '%s\n' "$images"
