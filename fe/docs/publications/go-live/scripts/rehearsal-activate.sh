#!/usr/bin/env bash
# GO-LIVE-PROCESS step 7: activate every imported series in its OWNER domain, confirm the
# public list is unchanged by it, and read the chain on the two weeklies.
# The public list changed hands at the import; what waits on this step is the next release --
# publishing from a DRAFT series is refused and its gap detection is off.
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$HERE/lib.sh"
if [ -s "$OUT/token.txt" ]; then TOKEN=$(cat "$OUT/token.txt"); H="Authorization: Bearer $TOKEN"; else fetch_token; fi
URLREASON=$(node -e 'process.stdout.write(encodeURIComponent(process.argv[1]))' "$REASON")

echo "$(stamp) === owners from search-details ==="
$CURL -s -H "$H" "$A/publication-series/search-details" -o "$OUT/series-imported.json" -w "http %{http_code}\n"
node -e '
const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")); const l=Array.isArray(r)?r:(r.data||[]);
const by={}; for(const s of l){ (by[s.domainId||"-"]=by[s.domainId||"-"]||[]).push(s.seriesId+"("+s.status+"/"+(s.availability||"?")+")"); }
for(const [d,v] of Object.entries(by)) console.log(" ",d,":",v.join(" "));
// One line per owner domain, and a trailing newline: see lib.sh on reading it back.
require("fs").writeFileSync(process.argv[2], Object.entries(by).map(([d])=>d+" "+l.filter(s=>(s.domainId||"-")===d).map(s=>s.seriesId).join(",")).join("\n")+"\n");
' "$OUT/series-imported.json" "$OUT/owners.txt"

echo "$(stamp) === activate every series in its owner domain (no hand edits) ==="
while read -r DOM IDS || [ -n "${DOM:-}" ]; do
  [ -z "${DOM:-}" ] && continue
  # A series with no owner is a SERIES_WITHOUT_OWNER violation at the import check, and the
  # window does not get here with one. If one turns up anyway it is named, not activated blind.
  [ "$DOM" = "-" ] && { echo "  OWNERLESS, not activated: $IDS"; continue; }
  for ID in ${IDS//,/ }; do
    CODE=$($CURL -s -X PUT -H "$H" -H "NiordDomain: $DOM" -H "Content-Type: application/json" \
      -d '"ACTIVE"' "$A/publication-series/series/$ID/status?reason=$URLREASON" -o "$OUT/act-$ID.json" -w "%{http_code}")
    echo "  activate $ID [$DOM] -> $CODE $(grep -o '"status":"[A-Z_]*"\|"code":"[A-Z_]*"\|"message":"[^"]\{0,140\}' "$OUT/act-$ID.json" | head -3 | tr '\n' ' ')"
  done
done < "$OUT/owners.txt"

echo "$(stamp) === the public list is unchanged by activation ==="
snap activated
compare after activated

echo "$(stamp) === series state (every row must read ACTIVE) ==="
$CURL -s -H "$H" "$A/publication-series/search-details" -o "$OUT/series-after.json"
node -e '
const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")); const l=Array.isArray(r)?r:(r.data||[]);
let bad=0; for(const s of l){ const ok=s.status==="ACTIVE"; if(!ok) bad++; console.log(" ",ok?"  ":"!!",s.seriesId,s.status,s.domainId,s.availability||""); }
if(bad) console.log("NOT DONE:",bad,"series not ACTIVE -- work their owner domain");
' "$OUT/series-after.json"

echo "$(stamp) === newest issues of the weeklies (chain check) ==="
for SID in weekly-ntm weekly-ntm-p-t; do
  $CURL -s -H "$H" "$A/publication-issues/series/$SID?maxSize=3" -o "$OUT/issues-$SID.json" -w "$SID http %{http_code}\n"
  node -e '
const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")); const l=Array.isArray(r)?r:(r.data||[]);
const iso=(t)=>t?new Date(t).toISOString().slice(0,16):"-";
for(const i of l.slice(0,3)) console.log("   ",(i.descs||[]).map(d=>d.name||d.title).find(Boolean)||i.publicId, i.status, "cutoff",iso(i.cutoffStampedAt||i.intervalTo),"public",iso(i.publicFrom),"->",iso(i.publicTo),"members",i.memberCount);
' "$OUT/issues-$SID.json"
done
echo "$(stamp) === done ==="
