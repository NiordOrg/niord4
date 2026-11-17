#!/usr/bin/env bash
# Live OrbStack rehearsal of the unattended SECOM certificate renewal (identity plan
# phase 4: T3b Secret-PATCH backend + T5 offline E2E) - no real MIR involved:
#
#   1. Mints a throwaway rehearsal PKI: a fresh CA plus 30-day service and robot
#      identities - inside the 60-day renewal window, so renewal is due immediately.
#      The truststore comes from secom-truststore.sh (T4), dogfooding it.
#   2. Starts a stub MIR on the host (secom-stub-mir.py) and probes which host alias
#      the pods can reach it on (plain http - the same path the integration tests use).
#   3. Deploys the chart with SECOM + renewal enabled (a fast renewal cron).
#      The deploy installs the committed test PKI; the rehearsal PKI is then PATCHed
#      into the secrets in place - proving the store watch (T2) converges on rotation.
#   4. The in-app renewal job takes it from there: robot re-issue -> service re-issue
#      -> Secret PATCHes through the pod ServiceAccount.
#   5. Waits and verifies: both secrets hold fresh ~200-day certificates with the same
#      MRNs, the app auto-reloads the renewed material (kubelet + store watch), and
#      MailHog holds the rotation + renewal confirmation mails. Nothing else holds
#      certificate material: the proxy has no TLS listener (SECOM Edition 2).
#
# Prereqs: OrbStack k8s running (orb start), the app image built (build-image.sh),
# helm + kubectl + openssl + keytool + python3. Wall time ~10 minutes. Everything is
# throwaway; restore the standard SECOM test setup afterwards with: deploy.sh --secom
#
# Rehearsal-pace artifact: the 20s renewal cron outpaces the ~1-minute kubelet Secret
# sync, so between a renewal and its mount convergence the app's stalled-pickup guard
# fires once (a "never became visible" alert mail that clears by itself) - impossible
# at the real daily cadence, and proof the guard works.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${REPO_ROOT}/k8s/secom-rehearsal-out"
NAMESPACE="${NAMESPACE:-niord}"
RELEASE="${RELEASE:-niord}"
PW=changeit
MIR_PORT="${MIR_PORT:-18446}"
ORG_MRN="urn:mrn:mcp:org:mcc:test:rehearsal"
SERVICE_MRN="urn:mrn:mcp:service:mcc:test:instance:niord-rehearsal"
ROBOT_MRN="urn:mrn:mcp:user:mcc:test:rehearsal-robot"

step() { echo; echo "==> $*"; }
die()  { echo "ERROR: $*" >&2; exit 1; }

# Log assertions only count lines from THIS run - the app pod may survive re-runs
START_TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

for tool in helm kubectl openssl keytool python3 curl; do
  command -v "$tool" >/dev/null || die "$tool not on PATH"
done
CTX="$(kubectl config current-context 2>/dev/null || echo '?')"
[[ "$CTX" == "orbstack" ]] || echo "WARNING: kube context is '$CTX' (expected: orbstack)"
# The image must live in ORBSTACK's engine (that is what its k8s runs) - check there
# first, falling back to the current docker context. Its name is the chart's default app
# image: niord/niord-app-en tagged with the chart's appVersion (what build-image.sh builds).
APP_VERSION="$(sed -n 's/^appVersion:[[:space:]]*"\{0,1\}\([^"]*\)"\{0,1\}[[:space:]]*$/\1/p' "${REPO_ROOT}/k8s/helm/niord/Chart.yaml")"
APP_IMAGE="niord/niord-app-en:${APP_VERSION}"
docker --context orbstack image inspect "$APP_IMAGE" >/dev/null 2>&1 \
  || docker image inspect "$APP_IMAGE" >/dev/null 2>&1 \
  || die "app image $APP_IMAGE not built - run k8s/scripts/build-image.sh (against the orbstack docker context)"

# ------------------------------------------------------------------ 1. rehearsal PKI
step "Minting the rehearsal PKI (30-day identities under a fresh CA)"
rm -rf "$OUT"; mkdir -p "$OUT"
openssl ecparam -name secp384r1 -genkey -noout -out "$OUT/ca.key"
openssl req -x509 -new -key "$OUT/ca.key" -sha384 -days 3650 \
        -subj "/CN=Niord Renewal Rehearsal CA" -out "$OUT/ca.pem"
# Same leaf profile as the committed test PKI (generate-test-pki.sh): keyUsage + a
# closed-port OCSP AIA so revocation checking soft-fails instantly.
cat > "$OUT/ext.cnf" <<'EOF'
[v3_ext]
keyUsage = digitalSignature, keyEncipherment
authorityInfoAccess = OCSP;URI:http://127.0.0.1:9/ocsp
EOF
issue() { # $1=basename $2=mrn $3=cn
  openssl ecparam -name secp384r1 -genkey -noout -out "$OUT/$1.key"
  openssl req -new -key "$OUT/$1.key" -subj "/UID=$2/CN=$3" -out "$OUT/$1.csr"
  openssl x509 -req -in "$OUT/$1.csr" -CA "$OUT/ca.pem" -CAkey "$OUT/ca.key" \
      -CAcreateserial -sha384 -days 30 -extfile "$OUT/ext.cnf" -extensions v3_ext \
      -out "$OUT/$1.pem" 2>/dev/null
}
issue service "$SERVICE_MRN" "Niord Rehearsal Service"
issue robot   "$ROBOT_MRN"   "Niord Rehearsal Renewal Robot"
openssl pkcs12 -export -name 1 -inkey "$OUT/service.key" -in "$OUT/service.pem" \
        -certfile "$OUT/ca.pem" -passout "pass:$PW" -out "$OUT/keystore.p12"
openssl pkcs12 -export -name 1 -inkey "$OUT/robot.key" -in "$OUT/robot.pem" \
        -certfile "$OUT/ca.pem" -passout "pass:$PW" -out "$OUT/robot.p12"
# truststore.p12 from the curated input (T4 helper)
"${REPO_ROOT}/k8s/scripts/secom-truststore.sh" -o "$OUT" -p "$PW" "$OUT/ca.pem"

fingerprint() { openssl x509 -in "$1" -noout -fingerprint -sha256 | cut -d= -f2; }
SERVICE_FP_BEFORE="$(fingerprint "$OUT/service.pem")"
ROBOT_FP_BEFORE="$(fingerprint "$OUT/robot.pem")"

# ------------------------------------------------------------------ 2. stub MIR
step "Starting the stub MIR on the host (:${MIR_PORT})"
python3 "${REPO_ROOT}/k8s/scripts/secom-stub-mir.py" \
        --ca-cert "$OUT/ca.pem" --ca-key "$OUT/ca.key" --port "$MIR_PORT" --days 200 \
        --ext-file "$OUT/ext.cnf" \
        > "$OUT/stub-mir.log" 2>&1 &
MIR_PID=$!
# shellcheck disable=SC2329  # invoked through the EXIT trap
cleanup() {
  kill "$MIR_PID" 2>/dev/null || true
  wait "$MIR_PID" 2>/dev/null || true   # reap it quietly (no "Terminated" job notice)
}
trap cleanup EXIT
for i in $(seq 1 20); do
  curl -fsS "http://127.0.0.1:${MIR_PORT}/healthz" >/dev/null 2>&1 && break
  [[ $i == 20 ]] && die "stub MIR did not come up - see $OUT/stub-mir.log"
  sleep 0.5
done

step "Probing which host alias the pods can reach the stub on"
MIR_HOST=""
for candidate in host.internal host.docker.internal host.k8s.orb.local; do
  PROBE="$(kubectl run "rehearsal-probe-$RANDOM" --rm -i --restart=Never \
      --image=busybox:1.36 --command -- \
      sh -c "wget -q -T 3 -O- http://${candidate}:${MIR_PORT}/healthz" 2>/dev/null || true)"
  if [[ "$PROBE" == *ok* ]]; then MIR_HOST="$candidate"; break; fi
done
[[ -n "$MIR_HOST" ]] || die "pods cannot reach the host stub on any known alias (host.internal / host.docker.internal / host.k8s.orb.local)"
echo "   pods reach the host as: ${MIR_HOST}"

# ------------------------------------------------------------------ 3. deploy
step "Deploying the chart (SECOM + renewal, 20s renewal cron)"
cat > "$OUT/rehearsal-values.yaml" <<EOF
# SECOM needs S-124 on and a producer code; the chart's default image niord-app-en switches
# S-124 off and carries no code, and the app refuses to start with SECOM on and S-124 off.
# A rehearsal code: the real one is the deployment's values.
s124:
  enabled: true
  producerCode: "XX00"
secom:
  renewal:
    mirUrl: "http://${MIR_HOST}:${MIR_PORT}"
    orgMrn: "${ORG_MRN}"
    cron: "0/20 * * * * ?"          # rehearsal pace - the real default is daily
  settings:
    secomRenewalEnabled: "true"
    secomAlertEmails: "ops@rehearsal.local"
EOF
EXTRA_HELM_ARGS="-f ${OUT}/rehearsal-values.yaml --set secom.renewal.enabled=true" \
  "${REPO_ROOT}/k8s/scripts/deploy.sh" --secom

# ------------------------------------------------------------------ 4. rotate in the rehearsal PKI
step "PATCHing the rehearsal PKI into the SECOM secrets (in-place rotation, T2)"
kubectl -n "$NAMESPACE" create secret generic niord-secom-stores \
  --from-file=keystore.p12="$OUT/keystore.p12" \
  --from-file=truststore.p12="$OUT/truststore.p12" \
  --from-literal=keystore-password="$PW" \
  --from-literal=truststore-password="$PW" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NAMESPACE" create secret generic niord-secom-renewal \
  --from-file=robot.p12="$OUT/robot.p12" \
  --from-literal=robot-password="$PW" \
  --dry-run=client -o yaml | kubectl apply -f -

app_logs() { kubectl -n "$NAMESPACE" logs "deploy/${RELEASE}-app" --tail=-1 --since-time="$START_TS" 2>/dev/null || true; }
wait_for_log() { # $1=pattern $2=timeout-seconds $3=min-count
  local deadline=$(( $(date +%s) + $2 ))
  while (( $(date +%s) < deadline )); do
    if [[ "$(app_logs | grep -c "$1")" -ge "${3:-1}" ]]; then return 0; fi
    sleep 5
  done
  echo "TIMEOUT waiting for log: $1" >&2
  return 1
}

# A timed-out wait falls through to the verification report (which will show the FAILs)
step "Waiting for the store watch to adopt the rehearsal PKI (kubelet + watch, ~2 min)"
wait_for_log "Auto-reloaded SECOM key material" 360 1 || true

# ------------------------------------------------------------------ 5. the renewal itself
step "Waiting for the unattended renewal (robot first, then the service; 20s cron)"
wait_for_log "SECOM renewal: renewed the robot certificate" 300 1 || true
wait_for_log "SECOM renewal: renewed the service certificate" 300 1 || true

step "Waiting for the renewed material to go live (kubelet + store watch again, ~2 min)"
wait_for_log "Auto-reloaded SECOM key material" 360 2 || true

# ------------------------------------------------------------------ 6. verify
step "Verifying the outcome"
FAILURES=0
# Every probe runs as `s=0; <probe> || s=1` so a failing check is REPORTED and counted
# instead of aborting the script through set -e - the summary must always print.
check() { # $1=description $2=ok(0/1)
  if [[ "$2" == "0" ]]; then echo "   PASS  $1"; else echo "   FAIL  $1"; FAILURES=$((FAILURES+1)); fi
}

s=0; kubectl -n "$NAMESPACE" get secret niord-secom-stores -o jsonpath='{.data.keystore\.p12}' \
  | base64 -d > "$OUT/renewed-keystore.p12" \
  && openssl pkcs12 -in "$OUT/renewed-keystore.p12" -passin "pass:$PW" -clcerts -nokeys \
       -out "$OUT/renewed-service.pem" 2>/dev/null || s=1
check "service keystore Secret readable (PKCS12, expected password)" $s
s=0; kubectl -n "$NAMESPACE" get secret niord-secom-renewal -o jsonpath='{.data.robot\.p12}' \
  | base64 -d > "$OUT/renewed-robot.p12" \
  && openssl pkcs12 -in "$OUT/renewed-robot.p12" -passin "pass:$PW" -clcerts -nokeys \
       -out "$OUT/renewed-robot.pem" 2>/dev/null || s=1
check "robot credential Secret readable (PKCS12, expected password)" $s

SERVICE_FP_AFTER="$(fingerprint "$OUT/renewed-service.pem" 2>/dev/null || echo unreadable)"
ROBOT_FP_AFTER="$(fingerprint "$OUT/renewed-robot.pem" 2>/dev/null || echo unreadable)"
s=0; [[ "$SERVICE_FP_AFTER" != "$SERVICE_FP_BEFORE" && "$SERVICE_FP_AFTER" != unreadable ]] || s=1
check "service keystore Secret rotated (fresh certificate)" $s
s=0; [[ "$ROBOT_FP_AFTER" != "$ROBOT_FP_BEFORE" && "$ROBOT_FP_AFTER" != unreadable ]] || s=1
check "robot credential Secret rotated (fresh certificate)" $s
s=0; openssl x509 -in "$OUT/renewed-service.pem" -noout -subject 2>/dev/null | grep -q "$SERVICE_MRN" || s=1
check "renewed service certificate keeps the MRN (subject UID)" $s
s=0; openssl x509 -in "$OUT/renewed-robot.pem" -noout -subject 2>/dev/null | grep -q "$ROBOT_MRN" || s=1
check "renewed robot certificate keeps the MRN (subject UID)" $s
s=0; openssl x509 -in "$OUT/renewed-service.pem" -noout -checkend $((100 * 86400)) >/dev/null 2>&1 || s=1
check "renewed service certificate is long-lived (>100 days - the stub issues 200)" $s

s=0; app_logs | grep -q "SECOM renewal: renewed the robot certificate $ROBOT_MRN" || s=1
check "renewal log names the robot MRN" $s
s=0; app_logs | grep -q "SECOM renewal: renewed the service certificate $SERVICE_MRN" || s=1
check "renewal log names the service MRN" $s
s=0; [[ "$(app_logs | grep -c 'Auto-reloaded SECOM key material')" -ge 2 ]] || s=1
check "the app auto-reloaded twice (rehearsal PKI adoption + renewed material pickup)" $s

# MailHog is the strongest evidence, but it stores messages in memory and OrbStack
# occasionally recreates its sandbox mid-rehearsal - fall back to the app's own
# queued-plus-delivered log lines when the MailHog store came up empty.
MAILS="$(curl -fsS "http://localhost:18025/api/v2/messages?limit=50" 2>/dev/null || echo '')"
mail_check() { # $1=subject
  echo "$MAILS" | grep -qF "$1" && return 0
  app_logs | grep -q "Queued SECOM.*mail '$1'" && app_logs | grep -q "Sent email to"
}
s=0; mail_check "SECOM service certificate renewed automatically" || s=1
check "service renewal confirmation mailed (MailHog or app log)" $s
s=0; mail_check "SECOM renewal robot credential renewed automatically" || s=1
check "robot renewal confirmation mailed (MailHog or app log)" $s
s=0; mail_check "SECOM service certificate rotated (auto-reload)" || s=1
check "store-watch rotation confirmation mailed (MailHog or app log)" $s

echo
if [[ "$FAILURES" == "0" ]]; then
  echo "==> REHEARSAL PASSED"
else
  echo "==> REHEARSAL FAILED: $FAILURES check(s) - see above, app logs, and $OUT/stub-mir.log"
fi
echo "    service certificate: ${SERVICE_FP_BEFORE} -> ${SERVICE_FP_AFTER}"
echo "    robot credential:    ${ROBOT_FP_BEFORE} -> ${ROBOT_FP_AFTER}"
echo "    Everything rehearsal-created is throwaway. Restore the standard SECOM test"
echo "    setup with:  k8s/scripts/deploy.sh --secom     (or teardown.sh --purge)"
exit "$FAILURES"
