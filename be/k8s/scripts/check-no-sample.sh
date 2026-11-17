#!/usr/bin/env bash
# The no-sample check of the base product (docs/fe-country-split-plan.md P9, the safety rules of 2026-10-19): the
# sample (be/niord/sample) is loaded only into the loopback database niord_sample, and no jar, zip or docker export
# tar carries it. Backend CI runs it over the jars and the bootstrap zip it built, publish.yml over the jars before
# anything is deployed and over the image's inputs before the image is built:
#
#   ./check-no-sample.sh <sample-dir> <artifact>...
#   e.g. k8s/scripts/check-no-sample.sh niord/sample niord/target/*.jar niord-app/target/*.jar niord-bootstrap/target/*.zip
#
# It guards the deployables: the jars, the bootstrap zip and the image's inputs. The one package that carries the
# sample is org.niord:niord-local-run (be/scripts/local-run-zip.sh), the files of a country's local run, which is
# never an image input and which neither Backend CI nor publish.yml passes to this check (release-workflows.test.mjs
# holds that); the check fails on it (LocalRunZipTest).
#
# An artifact is a directory, a zip, jar or war (the zips and jars inside it are opened, at any depth), or an
# uncompressed tar such as `docker export` writes (the zips and jars inside it are opened). It fails on an entry under
# a sample/ folder of the sample's layout, on one named niord-sample.sql or messages.json beside an archive/ folder,
# on one whose bytes equal a file of the sample (SHA-256), and on one that holds the sentinel line or the message uid
# prefix; it reads both from the sample folder (niord-sample.sql's sentinel line, the common start of
# messages/messages.json's uids), and neither is written anywhere else. An entry of a directory or a tar larger than
# 512 MiB is searched as a stream but not opened, and one that is itself a zip or a tar is unreadable input (exit 2);
# a zip's entries are opened whatever their size. A `docker save` tar (manifest.json, oci-layout or index.json at its
# root) keeps its layers compressed and is refused (exit 2): check an image with `docker export` of a container made
# from it.
#
# niord/target/*.jar includes niord-base's test-jar. Its IT fixture, db/niord-base-data.sql
# (niord/scripts/regen-test-seed-postgres.sh), is a dump of a database the sample was loaded into, and is not a finding:
# no image takes the test-jar, and the dump carries no sample file's bytes, name, layout, sentinel or message uid prefix
# (the regeneration rewrites the uids' prefix), so the check passes over it as over any other entry.
#
# Exit status: 0 no artifact carries the sample, 1 one does (each finding names the artifact and the entry),
# 2 usage or unreadable input.
#
# The check is CheckNoSample.java beside this script, run by the JDK's source launcher: JDK built-ins only, Java 17
# syntax, nothing to build or fetch. Needs `java` 17 or later on the PATH or in JAVA_HOME.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
command -v "$JAVA" >/dev/null 2>&1 || { echo "ERROR: java not found (set JAVA_HOME or put java on the PATH)" >&2; exit 2; }
[ $# -ge 2 ] || { sed -n '2,35p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }

exec "$JAVA" "$SCRIPT_DIR/CheckNoSample.java" "$@"
