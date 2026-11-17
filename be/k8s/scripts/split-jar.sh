#!/usr/bin/env bash
# Split a Spring Boot executable jar into the pieces the image is built from.
#
#   ./split-jar.sh <fat jar> <dist dir> <module>     # e.g. ../niord-dk/target/niord-dk-4.0.1.jar dist niord-dk
#   ./split-jar.sh --hash <dir>                      # print the jar-set hash of a directory of jars
#
# Writes, under <dist dir> (an existing <dist dir>/<module> is removed first):
#
#   <module>/app/app.jar                 the thin jar: the module's own classes and resources, and a
#                                        manifest Class-Path naming lib/*.jar
#   <module>/app/lib/niord-base-*.jar    our library, kept with the app: its file name carries the version
#   <module>/deps.sha256                 the jar-set hash of the third-party jars (below)
#   deps/<hash>/*.jar                    the third-party jars, one directory per distinct set; left alone
#                                        when another module already produced the same set
#
# The layout is what the Dockerfile's named build contexts expect (docker/Dockerfile) and what
# publish-images.sh reads, locally (build-image.sh) and in the publish workflow's build job.
#
# The jar-set hash is one SHA-256 over the lines `<sha256>  <file name>` of every jar in the set,
# sorted under LC_ALL=C and with the two spaces whatever mode sha256sum reports, so that it comes
# out the same on macOS, on Linux and on Git Bash. It names the dependency set in the artifact
# and is what publish-images.sh checks the built image against.
#
# Spring Boot's own tool does the unpacking (java -Djarmode=tools -jar <jar> extract). Its layer
# index (extract --layers) is not used: in the reactor build it puts niord-base with the application,
# but a build that resolves niord-base from a repository (the country repositories after the
# repository split) puts it with the dependencies. The move by file name gives the same split in both.
# The extract refuses a destination that is not empty, and with --force it would keep jars of an
# earlier run, so it always gets a fresh directory.
#
# Needs `java` on the PATH or in JAVA_HOME (the JDK that built the jar, or any JRE that runs it).
set -euo pipefail

# Portable sha256: coreutils' sha256sum (Linux, macOS 15+, Git Bash) or Perl's shasum (older macOS).
# Git Bash's sha256sum marks binary mode with ` *` before the file name; the helper turns it into the
# two spaces the others print, so the output is the same everywhere.
sha256() {
  { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; } \
    | sed 's/^\([0-9a-f]\{64\}\) \*/\1  /'
}

# The jar-set hash of a directory of jars (see the header).
jar_set_hash() {
  local dir="$1" f
  ( cd "$dir" && for f in *.jar; do printf '%s\n' "$f"; done | LC_ALL=C sort | while IFS= read -r f; do sha256 "$f"; done ) \
    | sha256 | cut -d' ' -f1
}

if [[ "${1:-}" == "--hash" ]]; then
  [[ $# -eq 2 && -d "$2" ]] || { echo "usage: $0 --hash <dir>" >&2; exit 2; }
  jar_set_hash "$2"
  exit 0
fi

[[ $# -eq 3 ]] || { echo "usage: $0 <fat jar> <dist dir> <module>   |   $0 --hash <dir>" >&2; exit 2; }
JAR="$1"; DIST="$2"; MODULE="$3"
[[ -f "$JAR" ]] || { echo "ERROR: no such jar: $JAR" >&2; exit 1; }
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
command -v "$JAVA" >/dev/null 2>&1 || { echo "ERROR: java not found (set JAVA_HOME or put java on the PATH)" >&2; exit 1; }

OUT="$DIST/$MODULE"
rm -rf "$OUT"
mkdir -p "$OUT/app/lib" "$DIST/deps"
EXTRACT="$(mktemp -d "${TMPDIR:-/tmp}/split-jar.XXXXXX")"
trap 'rm -rf "$EXTRACT"' EXIT

# --application-filename: the thin jar lands under its final name, so no rename is needed.
"$JAVA" -Djarmode=tools -jar "$JAR" extract --destination "$EXTRACT" --application-filename app.jar

mv "$EXTRACT/app.jar" "$OUT/app/app.jar"

# niord-base goes with the app. Exactly one: a copy left among the dependencies would give every
# publish a new dependency layer, because its file name carries the version.
base_count=0
for f in "$EXTRACT"/lib/niord-base-*.jar; do
  [[ -e "$f" ]] || continue
  mv "$f" "$OUT/app/lib/"
  base_count=$((base_count + 1))
done
[[ "$base_count" -eq 1 ]] || { echo "ERROR: expected exactly one niord-base-*.jar among the libraries, found $base_count" >&2; exit 1; }

hash="$(jar_set_hash "$EXTRACT/lib")"
printf '%s\n' "$hash" > "$OUT/deps.sha256"
if [[ -d "$DIST/deps/$hash" ]]; then
  # Another module produced the same set: byte-identical by construction (the hash covers every file).
  rm -rf "${EXTRACT:?}/lib"
else
  mv "$EXTRACT/lib" "$DIST/deps/$hash"
fi

echo "$MODULE: app.jar $(du -k "$OUT/app/app.jar" | cut -f1) KB, $(ls "$OUT/app/lib") , $(ls "$DIST/deps/$hash" | wc -l | tr -d ' ') third-party jars, set $hash"
