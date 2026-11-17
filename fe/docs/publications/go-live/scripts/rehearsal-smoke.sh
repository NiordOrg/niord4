#!/usr/bin/env bash
# GO-LIVE-PROCESS step 8: the public list and a citation, checked from outside.
#
# Anonymous where the reader is anonymous. A publication that only resolves with a bearer
# token is not the thing the public site does, and the whole point of this step is to ask
# the estate the questions its consumers ask.
#
#   SMOKE_SERIES   the series to take the citation from, default weekly-ntm
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$HERE/lib.sh"
if [ -s "$OUT/token.txt" ]; then TOKEN=$(cat "$OUT/token.txt"); H="Authorization: Bearer $TOKEN"; else fetch_token; fi
: "${SMOKE_SERIES:=weekly-ntm}"

echo "$(stamp) === the public list (anonymous) ==="
$CURL -s "$A/public/v1/publications?lang=da" -o "$OUT/smoke-public.json" -w "http %{http_code}\n"
node -e '
const l=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));
console.log("entries:",l.length);
for(const p of l.slice(0,8)) console.log("  ",p.publicationId,p.type,"|",((p.descs||[])[0]||{}).title||"(no title -- READ THIS, a name rendered as an id is defect 8)");
' "$OUT/smoke-public.json"

echo "$(stamp) === the newest published issue of $SMOKE_SERIES ==="
$CURL -s -H "$H" "$A/publication-issues/series/$SMOKE_SERIES?maxSize=5" -o "$OUT/smoke-issues.json" -w "http %{http_code}\n"
ID=$(node -e '
const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")); const l=Array.isArray(r)?r:(r.data||[]);
const i=l.find(x=>x.status==="PUBLISHED"); process.stdout.write(i?i.publicId:"");
' "$OUT/smoke-issues.json")
MEM=$(node -e '
const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")); const l=Array.isArray(r)?r:(r.data||[]);
const i=l.find(x=>x.status==="PUBLISHED"); process.stdout.write(String(i&&i.memberCount!=null?i.memberCount:""));
' "$OUT/smoke-issues.json")
[ -z "$ID" ] && { echo "HARD STOP: no published issue on $SMOKE_SERIES to cite"; exit 2; }
echo "  issue $ID, memberCount $MEM"

echo "$(stamp) === the citation target resolves (anonymous) ==="
$CURL -s "$A/public/v1/publication/$ID?lang=da" -o "$OUT/smoke-publication.json" -w "http %{http_code}\n"
LINK=$(node -e '
const p=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")); const d=(p.descs||[])[0]||{};
console.error("   "+p.publicationId+" "+(p.type||"")+" | "+(d.title||"(no title)"));
process.stdout.write(d.link||"");
' "$OUT/smoke-publication.json")
echo "   link: ${LINK:-none}"
if [ -n "$LINK" ]; then
  echo -n "   the file opens: "
  $CURL -s -o /dev/null -w "http %{http_code}, %{size_download} bytes\n" "$LINK"
fi

echo "$(stamp) === the citation filter resolves to the issue's own members (anonymous) ==="
$CURL -s "$A/public/v1/messages?publication=$ID&lang=da" -o "$OUT/smoke-messages.json" -w "http %{http_code}\n"
CNT=$(node -e 'const l=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")); console.log(Array.isArray(l)?l.length:(l.data||[]).length)' "$OUT/smoke-messages.json")
echo "   messages $CNT against memberCount $MEM"
[ "$CNT" = "$MEM" ] || echo "   they differ -- read the list before calling it wrong: a member cancelled since the freeze is still a member and no longer a published message"

echo "$(stamp) === the admin picker offers it ==="
$CURL -s -H "$H" "$A/publication-issues/picker?lang=da&publicationSeriesId=$SMOKE_SERIES&maxSize=5" -o "$OUT/smoke-picker.json" -w "http %{http_code}\n"
node -e '
const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")); const l=r.data||[];
console.log("   total:",r.total,"offered:",l.map(i=>i.publicId+"("+i.status+")").join(" "));
console.log("   the cited issue is in the picker:",l.some(i=>i.publicId===process.argv[2]));
' "$OUT/smoke-picker.json" "$ID"
echo "$(stamp) === done ==="
