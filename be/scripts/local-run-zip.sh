#!/usr/bin/env bash
#
# Builds org.niord:niord-local-run:<version>:zip, the files a country's launcher takes from the base product for a
# local run (docs/fe-country-split-plan.md §3.4 and §5 step 9), into <out.zip>:
#
#   be/scripts/local-run-zip.sh <out.zip>
#
# The zip's layout, each entry from its source under be/:
#   scripts/local-run-lib.sh          the launcher library (scripts/local-run-lib.sh), beside the two scripts it runs
#   scripts/bootstrap.sh              the bootstrap command's client (scripts/bootstrap.sh)
#   scripts/load-sample.sh            the sample's loader (niord/scripts/load-sample.sh), which reads ../sample
#   sample/niord-sample.sql           the sample's rows, its attachment files among them (niord/sample/), byte for
#                                     byte; not the regeneration's sources (archive/, messages/, README.md): a
#                                     country never regenerates the sample
#   docker-compose.yml                the shared containers (docker-compose.yml)
#   keycloak/niord-realm-local.json   the local realm (keycloak/niord-realm-local.json)
#   README.md                         what the package is, and its warning (scripts/local-run-README.md)
#
# publish.yml's build job runs it before its first deploy and deploys the zip with deploy-file beside
# niord-image-tools; LocalRunZipTest builds it, unpacks it and runs its scripts. For a local run only: the zip carries
# the sample and known passwords, and is never an image input. No Dockerfile or image script reads it, and
# check-no-sample.sh, which guards the deployables and the image's inputs, fails on it.
#
# Deterministic: two builds of the same tree give the same bytes, on any checkout. The entries are files only (no
# folder entry, no manifest), sorted bytewise (LC_ALL=C), stored rather than deflated (so the bytes depend on the
# contents and the zip format alone, not on a zlib build) and dated 1980-01-01T00:00:02Z. Every entry outside
# sample/ is copied with its CRLFs read as LF, so a Windows checkout with core.autocrlf gives the bytes CI's gives;
# the sample is LF in every checkout (be/.gitattributes) and is copied as it is. The zip is made with the JDK's jar
# ($JAVA_HOME/bin/jar when JAVA_HOME is set, else jar on the PATH; JDK 19 or later, for --date): Git Bash has no
# zip, and a JDK is a prerequisite of the backend and of the launcher already. jar keeps no Unix modes, so the
# scripts are run with bash (the README and the library say so).
#
# Exit status: 0 built, 1 a source is missing or the zip could not be written, 2 usage.
set -euo pipefail

usage() { sed -n '2,33p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
[ $# -eq 1 ] && [ -n "$1" ] || usage

BE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${JAVA_HOME:+$JAVA_HOME/bin/}jar"
command -v "$JAR" >/dev/null 2>&1 \
    || { echo "!! jar not found: set JAVA_HOME to a JDK, or put jar on the PATH" >&2; exit 1; }

# The output as an absolute path jar understands (C:/... on Windows)
out_dir="$(cd "$(dirname "$1")" 2>/dev/null && pwd)" || { echo "!! The folder of $1 does not exist" >&2; exit 1; }
out="$out_dir/$(basename "$1")"
if command -v cygpath >/dev/null 2>&1; then out="$(cygpath -m "$out")"; fi

stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT

# Copies the text file $1 (under be/) to the entry $2, its CRLFs read as LF
text() {
    [ -f "$BE/$1" ] || { echo "!! $BE/$1 is missing" >&2; exit 1; }
    mkdir -p "$(dirname "$stage/$2")"
    sed $'s/\r$//' "$BE/$1" > "$stage/$2"
}

text scripts/local-run-README.md README.md
text docker-compose.yml docker-compose.yml
text keycloak/niord-realm-local.json keycloak/niord-realm-local.json
text scripts/bootstrap.sh scripts/bootstrap.sh
text niord/scripts/load-sample.sh scripts/load-sample.sh
text scripts/local-run-lib.sh scripts/local-run-lib.sh

# The sample, byte for byte: what load-sample.sh reads, at ../sample from the loader
sample="$BE/niord/sample"
[ -f "$sample/niord-sample.sql" ] || { echo "!! $sample/niord-sample.sql is missing" >&2; exit 1; }
mkdir -p "$stage/sample"
cp "$sample/niord-sample.sql" "$stage/sample/niord-sample.sql"

cd "$stage"
entries=()
while IFS= read -r entry; do
    entries+=("${entry#./}")
done < <(find . -type f | LC_ALL=C sort)

rm -f "$out"
"$JAR" --create --no-manifest --no-compress --date=1980-01-01T00:00:02Z --file "$out" "${entries[@]}" \
    || { echo "!! jar could not write $out" >&2; exit 1; }
echo ">> $out: ${#entries[@]} entries"
