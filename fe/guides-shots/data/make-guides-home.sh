#!/usr/bin/env bash
# make-guides-home.sh: makes ~/.niord-local/niord_guides, the NIORD_HOME of the user-guide screenshots, from the local
# Danish test data's home ~/.niord-local/niord_test, which is only read.
#
#   bash make-guides-home.sh            (Git Bash)
#   "%LOCALAPPDATA%\Programs\Git\bin\bash.exe" make-guides-home.sh    (PowerShell or cmd, Git not on the PATH)
#
# Copied:   repo/ (message attachments and map images, publication files: the repo-import job reads them into the
#           database's RepoFile table), tile-cache/ (map tiles), niord.json (with its secrets blanked).
# Left out: message-index*/ (Lucene indexes, rebuilt at start-up), batch-jobs/ (batch job data files and logs),
#           publication-preview/ (previews made while curating, made again on demand), repo/temp/ (edit sessions),
#           niord.json.bak-* (an old copy of the settings file), and the attachment folders of the messages that were
#           never public (DRAFT, VERIFIED, DELETED, IMPORTED in niord_test), which nobody has checked for personal data;
#           and every repository file that holds a person's address (repo-files.mjs leave-out: scrub.sql's persons and
#           the addresses of a person's form, read from niord_test into memory; in the bytes and in what a PDF, a docx
#           or a zip holds inflated). A check then fails the run when a file still holds one (repo-files.mjs check).
#           Since 2026-11-14 (default-deny): every repository file that holds any address outside example.org that
#           is neither on scrub.sql's functional list nor made of scrub-lists.mjs's role words only, also read the way
#           a PDF reader shows it (the text pieces joined; pdftotext's text when pdftotext is there); a PDF keeps its
#           name as a one-page placeholder ("Example document. The original is left out of the guides' copy."),
#           a file of any other kind is left out.
# Made empty: publication-archive/.
# The target is deleted and made again on every run, so a second run gives the same result as the first.
set -euo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

SRC="${HOME}/.niord-local/niord_test"
DST="${HOME}/.niord-local/niord_guides"
CONTAINER=niord-db
SOURCE_DB=niord_test
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

die() { echo "!! $*" >&2; exit 1; }
winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

[[ "$(basename "$DST")" == "niord_guides" && "$(basename "$(dirname "$DST")")" == ".niord-local" ]] || die "Refusing the target $DST."
[[ -d "$SRC/repo" && -f "$SRC/niord.json" ]] || die "No test data home at $SRC (repo/ and niord.json expected)."
command -v robocopy >/dev/null 2>&1 || die "robocopy is not on the PATH (this script runs on Windows)."
NODE="$(command -v node 2>/dev/null || true)"
[[ -n "$NODE" ]] || { [[ -x "/c/Program Files/nodejs/node.exe" ]] && NODE="/c/Program Files/nodejs/node.exe"; }
[[ -n "$NODE" ]] || die "node is needed to leave out the files with a person's address and to blank the secrets in niord.json."

started=$SECONDS

# The attachment folders of the messages that were never public: the folder of each attachment (its path without the
# file name; the folder names are ASCII, the file names are URL-encoded in the column). niord_test is only read.
mapfile -t private_dirs < <(docker exec "$CONTAINER" psql -X -U niord -d "$SOURCE_DB" -At -c \
    "select distinct regexp_replace(a.path, '/[^/]*$', '') from public.\"Attachment\" a join public.\"Message\" m on m.id = a.message_id where m.status in ('DRAFT','VERIFIED','DELETED','IMPORTED') order by 1" \
    | tr -d '\r')
echo ">> ${#private_dirs[@]} attachment folders of never-public messages are left out"

if [[ -e "$DST" ]]; then
    echo ">> Deleting the old $DST"
    rm -rf "$DST" || die "Could not delete $DST (is a backend still running on it?)."
fi
mkdir -p "$DST/publication-archive"

echo ">> Copying repo/ and tile-cache/"
xd=("$(winpath "$SRC/repo/temp")")
for d in "${private_dirs[@]}"; do [[ -n "$d" ]] && xd+=("$(winpath "$SRC/repo/$d")"); done
set +e
robocopy "$(winpath "$SRC/repo")" "$(winpath "$DST/repo")" /E /COPY:DAT /DCOPY:T /R:1 /W:1 /MT:16 /NFL /NDL /NJH /NP \
    /XD "${xd[@]}"
rc=$?
robocopy "$(winpath "$SRC/tile-cache")" "$(winpath "$DST/tile-cache")" /E /R:1 /W:1 /MT:16 /NFL /NDL /NJH /NJS /NP
rc2=$?
set -e
(( rc < 8 && rc2 < 8 )) || die "robocopy failed (exit codes $rc and $rc2)."

# A person's address in a repository file (mailto: links in PDFs and documents): the file is left out of the guides
# home, so the repository import never puts it into niord_guides. Only the guides home is changed; the persons'
# addresses are read from $SOURCE_DB into memory and never written or printed.
echo ">> Leaving out the repository files that hold a person's address (repo-files.mjs, about 5 minutes; a PDF becomes a placeholder)"
GUIDES_DB_CONTAINER="$CONTAINER" GUIDES_SOURCE_DB="$SOURCE_DB" \
    "$NODE" "$(winpath "$HERE/repo-files.mjs")" leave-out "$(winpath "$DST/repo")" \
    || die "repo-files.mjs could not leave out the files with a person's address."
echo ">> Check: no repository file of the guides home holds a person's address (repo-files.mjs, about 5 minutes)"
GUIDES_DB_CONTAINER="$CONTAINER" GUIDES_SOURCE_DB="$SOURCE_DB" \
    "$NODE" "$(winpath "$HERE/repo-files.mjs")" check "$(winpath "$DST/repo")" \
    || die "The check failed: a repository file of $DST still holds a person's address."

echo ">> Writing niord.json with its secrets blank"
"$NODE" -e '
const fs = require("fs");
const [src, dst] = process.argv.slice(1);
const settings = JSON.parse(fs.readFileSync(src, "utf8").replace(/^\uFEFF/, ""));
const secret = (s) => s.type === "Password" || /(password|secret|token|apikey|realmkey|login|user)$/i.test(s.key)
    || /^legacyNwDb/.test(s.key);
let blanked = 0;
for (const s of settings) {
    if (secret(s) && s.value !== "") { s.value = ""; blanked++; }
    // The database row wins over the file (prepare.sql sets it to ALL in niord_guides, for the loopback mail
    // catcher); the file says LOG, so a home used with any other database sends no mail
    if (s.key === "mailValidRecipients") s.value = "LOG";
}
fs.writeFileSync(dst, JSON.stringify(settings, null, 2) + "\n");
console.log("   " + settings.length + " settings, " + blanked + " non-empty secret(s) blanked, mailValidRecipients = LOG");
' "$(winpath "$SRC/niord.json")" "$(winpath "$DST/niord.json")"

echo
echo "== $DST"
for d in "$DST"/*/ "$DST"/repo/*/; do
    [[ -d "$d" ]] || continue
    printf '   %-40s %8s files %8s\n' "${d#"$DST"/}" "$(find "$d" -type f | wc -l)" "$(du -sh "$d" | cut -f1)"
done
echo ">> Done in $((SECONDS - started)) s. $SRC was only read."
echo "   The repository files reach the database only through the repo-import batch job, once, as sysadmin:"
echo "   POST /rest/batch/repo-import {\"dir\": \"$(winpath "$DST/repo" | tr '\\' '/')\"}"
echo "   and then node fe/guides-shots/data/seed-examples.mjs, which first checks the imported rows (repo-files.mjs check-db)"
