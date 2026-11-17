#!/usr/bin/env bash
# Builds the Niord guides into guides/_site/ (gitignored), at the paths docs.niord.org serves:
#
#   index.html, CNAME, stylesheets/, images/          copied as they are; the landing page then
#                                                     loses each entry whose guide was not built
#   <folder>/<master>.html                            every sub-site: each folder of guides/ with a
#                                                     master document (an .adoc at its top level
#                                                     with a "= Title" line), with its images/
#                                                     beside it; a new folder needs no edit here
#   country-kit/                                      the country kit's guides, rendered from
#                                                     country-kit/ (and be/docs/country-api.md);
#                                                     a guide naming SECOM or S-124 is left out
#   <folder>/<master>.pdf                             with --pdf only (not the kit's guides)
#
# then runs check-links.mjs over the result: every relative link, image and #fragment between the
# built pages must resolve, every target of the admin application's help map must exist
# (fe/ui-apps/admin/src/app/core/main-shell/help/help-urls.json: key -> /<manual>/manual.html#<id>),
# no page may name an internal host, a person's home folder or a staff account's user id, and no
# page outside the archived development guides may name SECOM or S-124 or link a GitHub address
# other than the NiordOrg organisation (in the archive these only warn). README.md has the rules.
#
# Asciidoctor, kramdoc (Markdown to AsciiDoc) and Graphviz run from one pinned image (tag and
# digest), so every machine and CI build the same output: Docker is the only prerequisite, plus
# Node for the helpers (build-tools.mjs) and the link check. Runs from Git Bash on Windows and from
# bash on Linux, from any directory.
#
#   ./build.sh          the HTML site
#   ./build.sh --pdf    the HTML site and the PDFs
#   ./build.sh --preview  the site for a folder of docs.niord.org that nothing links yet (/new):
#                       every page marked noindex, and no CNAME
set -euo pipefail

# asciidoctor/docker-asciidoctor 1.108.1: Asciidoctor 2.0.26, Asciidoctor PDF 2.3.27, CodeRay 1.1.3,
# kramdown-asciidoc 2.1.1, asciidoctor-diagram 3.2.1, Graphviz. Bump the tag and the digest together.
IMAGE='asciidoctor/docker-asciidoctor:1.108.1@sha256:0b75447cc4b616e18746a77cd2739ec4eedc0d2fb3d7e6db121a9dd2dd9dc078'

pdf=false
preview=false
for arg in "$@"; do
  case "$arg" in
    --pdf) pdf=true ;;
    --preview) preview=true ;;
    -h|--help) sed -n '2,/^[^#]/{/^#/p;}' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "build.sh: unknown argument '$arg' (try --help)" >&2; exit 2 ;;
  esac
done

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
cd "$here"
site=_site
start=$(date +%s)

command -v docker > /dev/null || { echo "build.sh: docker is not on PATH" >&2; exit 1; }
command -v node > /dev/null || { echo "build.sh: node is not on PATH (the helpers and the link check need it)" >&2; exit 1; }

# Each sub-site: its folder and its master document (built to <folder>/<document>.html).
mapfile -t DOCS < <(node build-tools.mjs subsites | tr -d '\r')
[ ${#DOCS[@]} -gt 0 ] || { echo "build.sh: no sub-site found" >&2; exit 1; }
echo "Sub-sites: ${DOCS[*]}"

# Docker wants a host path it understands: C:/… from Git Bash (pwd -W), the plain path elsewhere.
# Only guides/ is mounted: the kit's guides are read by Node on the host and staged in _kit/.
# MSYS_NO_PATHCONV stops Git Bash from rewriting the container's paths into Windows ones. On
# Linux the container runs as the calling user, so _site stays removable without root.
host_dir=$(pwd -W 2> /dev/null || pwd)
user_args=()
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) ;;
  *) user_args=(--user "$(id -u):$(id -g)") ;;
esac

# The footer's "Last updated" is the time of the last commit that touched the page's sources:
# guides/ for the guides, the kit's folder and the backend contract for the kit's guides; never a
# file's modification time on the machine that built it.
epoch_args=()
kit_epoch_args=()
if command -v git > /dev/null; then
  if epoch=$(git log -1 --format=%ct -- . 2> /dev/null) && [ -n "$epoch" ]; then
    epoch_args=(-e "SOURCE_DATE_EPOCH=$epoch")
  fi
  if epoch=$(git log -1 --format=%ct -- ../country-kit ../be/docs/country-api.md 2> /dev/null) && [ -n "$epoch" ]; then
    kit_epoch_args=(-e "SOURCE_DATE_EPOCH=$epoch")
  fi
fi

rm -rf "$site" _kit
mkdir -p "$site"
cp index.html CNAME "$site/"
cp -R stylesheets images "$site/"

sources=()
for doc in "${DOCS[@]}"; do
  sources+=("$doc.adoc")
  dir=${doc%%/*}
  mkdir -p "$site/$dir"
  if [ -d "$dir/images" ]; then
    cp -R "$dir/images" "$site/$dir/"
  fi
done

# -R . -D _site keeps each document's folder under _site. --failure-level ERROR fails the build on
# an error (a missing include among them); warnings are printed and listed below. -v reports the
# cross references that point at no id ("possible invalid reference"). -r asciidoctor-diagram
# renders a guide's text diagrams ([plantuml, <name>, svg] and the like) into _site/<folder>/images/;
# its cache folders (.asciidoctor/) are removed from the site after the build.
run() {
  MSYS_NO_PATHCONV=1 docker run --rm "${user_args[@]}" "${epoch_args[@]}" \
    -v "$host_dir:/documents" -w /documents "$IMAGE" "$@"
}
run_kit() {
  MSYS_NO_PATHCONV=1 docker run --rm "${user_args[@]}" "${kit_epoch_args[@]}" \
    -v "$host_dir:/documents" -w /documents "$IMAGE" "$@"
}

log=$(mktemp)
part=$(mktemp)
trap 'rm -f "$log" "$part"' EXIT
status=0

# The country kit's guides: Markdown in country-kit/, rendered, not copied. Stage 1 (Node) picks
# the guides and prepares them in _kit/; kramdoc and dot turn them into AsciiDoc and SVG in the
# image; stage 2 (Node) finishes the AsciiDoc pages, which Asciidoctor renders below.
echo "Preparing the country kit's guides"
node build-tools.mjs kit-prepare
run_kit sh _kit/convert.sh 2> "$part" || status=$?
cat "$part" | tee -a "$log" >&2
[ $status -eq 0 ] || { echo "build.sh: converting the kit's guides failed (exit $status)" >&2; exit $status; }
mapfile -t kit_sources < <(node build-tools.mjs kit-finish "$site" | tr -d '\r')

echo "Building the HTML with $IMAGE"
run asciidoctor -r asciidoctor-diagram -v --failure-level ERROR -R . -D "$site" "${sources[@]}" 2> "$part" || status=$?
cat "$part" | tee -a "$log" >&2
if [ $status -eq 0 ]; then
  run_kit asciidoctor -v --failure-level ERROR -R _kit -D "$site" "${kit_sources[@]}" 2> "$part" || status=$?
  cat "$part" | tee -a "$log" >&2
fi
if [ "$pdf" = true ] && [ $status -eq 0 ]; then
  echo "Building the PDFs"
  run asciidoctor-pdf -r asciidoctor-diagram -v --failure-level ERROR -R . -D "$site" "${sources[@]}" 2> "$part" || status=$?
  cat "$part" | tee -a "$log" >&2
fi
if [ $status -ne 0 ]; then
  echo "build.sh: Asciidoctor failed (exit $status)" >&2
  exit $status
fi

for doc in "${DOCS[@]}"; do
  [ -f "$site/$doc.html" ] || { echo "build.sh: $site/$doc.html was not built" >&2; exit 1; }
  if [ "$pdf" = true ]; then
    [ -f "$site/$doc.pdf" ] || { echo "build.sh: $site/$doc.pdf was not built" >&2; exit 1; }
  fi
done
[ -f "$site/country-kit/index.html" ] || { echo "build.sh: $site/country-kit/index.html was not built" >&2; exit 1; }
find "$site" -type d -name .asciidoctor -prune -exec rm -rf {} +

node build-tools.mjs landing "$site"

messages=$(grep -cE '^asciidoctor: (WARNING|INFO|ERROR)' "$log" || true)
echo "Asciidoctor messages: $messages"

check_args=()
[ "$pdf" = true ] || check_args=(--no-pdf)
node check-links.mjs "$site" "${check_args[@]}"

# After the link check, which reads the pages as they were built.
[ "$preview" = false ] || node build-tools.mjs preview "$site"

echo "Built $site in $(( $(date +%s) - start )) s"
