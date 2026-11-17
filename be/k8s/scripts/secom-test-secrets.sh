#!/usr/bin/env bash
# Creates the SECOM secrets for a LOCAL test deployment from the committed test PKI
# (niord/src/test/resources/secom-test, all passwords "changeit"). Idempotent — safe to
# re-run (e.g. after `teardown.sh --purge`, which deletes the namespace including secrets).
# Real deployments create the same secrets from the real MCP stores instead, the store
# passwords next to the stores (the chart hands them to the app as environment variables):
#
#   kubectl -n niord create secret generic niord-secom-stores \
#       --from-file=keystore.p12=... --from-file=truststore.p12=... \
#       --from-literal=keystore-password=... --from-literal=truststore-password=...
#   # Only with unattended renewal (secom.renewal.enabled) — the MIR robot-user credential:
#   kubectl -n niord create secret generic niord-secom-renewal \
#       --from-file=robot.p12=... --from-literal=robot-password=...
#
# Only the app holds certificate material: SECOM Edition 2 takes a peer's identity from
# the signed envelope, so the proxy has no TLS listener and needs no secret.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC="${REPO_ROOT}/niord/src/test/resources/secom-test"
NAMESPACE="${NAMESPACE:-niord}"
PW=changeit

command -v kubectl >/dev/null || { echo "ERROR: kubectl not installed" >&2; exit 1; }

# Before Edition 2 this script also minted the proxy listener's throwaway TLS material into
# k8s/secom-test-out; nothing reads it any more, so a leftover directory is removed.
rm -rf "${REPO_ROOT}/k8s/secom-test-out"

kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 || kubectl create namespace "$NAMESPACE"
kubectl -n "$NAMESPACE" create secret generic niord-secom-stores \
  --from-file=keystore.p12="$SRC/keystore-test.p12" \
  --from-file=truststore.p12="$SRC/truststore-test.p12" \
  --from-literal=keystore-password="$PW" \
  --from-literal=truststore-password="$PW" \
  --dry-run=client -o yaml | kubectl apply -f -
# The renewal robot-user credential (only mounted when secom.renewal.enabled; client-one
# doubles as the robot in the test PKI, exactly like the renewal integration tests).
kubectl -n "$NAMESPACE" create secret generic niord-secom-renewal \
  --from-file=robot.p12="$SRC/client-one.p12" \
  --from-literal=robot-password="$PW" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "SECOM test secrets created in namespace ${NAMESPACE}"
