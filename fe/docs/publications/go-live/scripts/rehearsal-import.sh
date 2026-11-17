#!/usr/bin/env bash
# GO-LIVE-PROCESS steps 1-5: undo -> the public list BEFORE -> dry run -> import ->
# import check -> the public list AFTER.
# The import runs 13-15 minutes on the production estate; run this where a shell can wait that long.
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$HERE/lib.sh"
fetch_token

echo "$(stamp) === 0. undo the previous import (refuses by design once a series has left DRAFT) ==="
$CURL -s -X DELETE -H "$H" "$A/publication-series/import-legacy" -o "$OUT/undo.json" -w "http %{http_code}\n"
head -c 400 "$OUT/undo.json"; echo

echo "$(stamp) === 1. the public list BEFORE -- the signature everything after is diffed against ==="
snap before

echo "$(stamp) === 2. dry run ==="
$CURL -s -X POST -H "$H" "$A/publication-series/import-legacy/validate" -o "$OUT/dryrun.json" -w "http %{http_code}\n"
node -e 'const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")); const {problems,notes,...rest}=r; console.log(JSON.stringify(rest)); console.log("problems:",(problems||[]).length,JSON.stringify((problems||[]).slice(0,3))); console.log("notes:",(notes||[]).length,JSON.stringify((notes||[]).slice(0,5)));' "$OUT/dryrun.json"
P=$(node -e 'const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")); console.log((r.problems||[]).length)' "$OUT/dryrun.json")
if [ "$P" != "0" ]; then echo "HARD STOP: dry run has $P problems"; exit 2; fi

echo "$(stamp) === 3. import ==="
START=$(date +%s)
$CURL -s -X POST -H "$H" "$A/publication-series/import-legacy" -o "$OUT/import.json" -w "http %{http_code}\n" --max-time 1800
echo "duration $(( $(date +%s) - START )) s (budget 1800)"
node -e 'const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")); const {problems,notes,...rest}=r; console.log(JSON.stringify(rest)); console.log("problems:",(problems||[]).length,JSON.stringify((problems||[]).slice(0,3))); console.log("notes:",(notes||[]).length);' "$OUT/import.json"

echo "$(stamp) === 4. import check -- the go/no-go ==="
$CURL -s -H "$H" "$A/publication-series/import-check" -o "$OUT/import-check.json" -w "http %{http_code}\n"
node -e 'const p=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")); console.log("clear:",p.clear,JSON.stringify(p.counts)); console.log("violations:",(p.violations||[]).length,JSON.stringify((p.violations||[]).slice(0,5))); console.log("triggerAudit:",(p.triggerAudit||[]).length); for(const [k,v] of Object.entries(p.series||{})) console.log(" ",k,JSON.stringify(v));' "$OUT/import-check.json"
N=$(node -e 'const p=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")); console.log((p.counts||{}).importedIssues||0)' "$OUT/import-check.json")
V=$(node -e 'const p=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")); console.log((p.violations||[]).length)' "$OUT/import-check.json")
W=$(node -e 'const p=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")); console.log((p.counts||{}).seriesWithoutOwner)' "$OUT/import-check.json")
echo "importedIssues $N, violations $V, seriesWithoutOwner $W"
if [ "$N" = "0" ]; then echo "HARD STOP: importedIssues 0 -- the import wrote nothing, and a clear check over nothing asserts nothing"; exit 2; fi
if [ "$V" != "0" ]; then echo "HARD STOP: $V violation(s) -- the window ends here, and the way back is a restore"; exit 2; fi

echo "$(stamp) === 5. the public list AFTER ==="
snap after
compare before after
echo "  now read a NAME -- one series and one issue -- in the admin list: the check counts rows, not content"
echo "$(stamp) === done ==="
