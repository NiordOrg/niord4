#!/usr/bin/env bash
# GO-LIVE-PROCESS steps 0-2, read-only: is the backend up, is the estate empty after
# the reseed, and what does the dry run say -- the expected figures for step 3.
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$HERE/lib.sh"
fetch_token

echo "$(stamp) === new-model series (expect none on a fresh restore) ==="
$CURL -s -H "$H" "$A/publication-series/search-details" -o "$OUT/series-before.json" -w "http %{http_code}\n"
node -e 'const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")); const l=Array.isArray(r)?r:(r.data||[]); console.log("series:",l.length, l.slice(0,3).map(s=>s.seriesId+":"+s.status).join(", "))' "$OUT/series-before.json" 2>&1 | head -3

echo "$(stamp) === import check on the empty estate (importedIssues must read 0) ==="
$CURL -s -H "$H" "$A/publication-series/import-check" -o "$OUT/import-check-before.json" -w "http %{http_code}\n"
node -e 'const p=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")); console.log("clear:",p.clear,"-- over importedIssues",(p.counts||{}).importedIssues,"which asserts nothing on its own"); console.log("counts:",JSON.stringify(p.counts));' "$OUT/import-check-before.json"

echo "$(stamp) === the public list BEFORE anything is written ==="
snap precheck

echo "$(stamp) === legacy publications (the source estate) ==="
$CURL -s -H "$H" "$A/publications/search?limit=1" -o "$OUT/legacy-count.json" -w "http %{http_code}\n"
head -c 200 "$OUT/legacy-count.json"; echo

echo "$(stamp) === dry run (the expected figures for the import) ==="
$CURL -s -X POST -H "$H" "$A/publication-series/import-legacy/validate" -o "$OUT/dryrun.json" -w "http %{http_code}\n"
node -e 'const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")); const {problems,notes,...rest}=r; console.log(JSON.stringify(rest)); console.log("problems:",(problems||[]).length,JSON.stringify((problems||[]).slice(0,5))); console.log("notes:",(notes||[]).length,JSON.stringify((notes||[]).slice(0,8)));' "$OUT/dryrun.json"
