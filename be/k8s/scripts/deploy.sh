#!/usr/bin/env bash
# Deploy (install/upgrade) the Niord Helm release to the current kube context
# (expected to be OrbStack's "orbstack" context).
#
#   ./deploy.sh                 # helm upgrade --install, then wait for rollout
#   ./deploy.sh --no-wait       # don't block on rollout
#   ./deploy.sh --secom         # also create the SECOM test secrets + enable SECOM
#   ./deploy.sh --secom-client  # --secom + deploy the in-cluster SECOM test client and point
#                               # Niord's service-registry lookup at it (k8s/secom-test-client/)
#
# EXTRA_HELM_ARGS can append additional helm values, e.g. the renewal rehearsal's
#   EXTRA_HELM_ARGS="-f my-values.yaml --set secom.renewal.enabled=true" ./deploy.sh --secom
# (appended last, so its --set flags win over the pinned defaults below).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHART_DIR="${REPO_ROOT}/k8s/helm/niord"
RELEASE="${RELEASE:-niord}"
NAMESPACE="${NAMESPACE:-niord}"

WAIT_ARGS=(--wait --timeout 10m)
# Always set the feature toggles explicitly: `helm upgrade` without value flags REUSES the
# previous release's user-supplied values, so a plain deploy after an enabling run would
# otherwise silently keep the feature on. EXTRA_HELM_ARGS (last) can override these pins.
SECOM_ARGS=(--set secom.enabled=false)
PIN_ARGS=(--set secom.renewal.enabled=false)
SECOM_CLIENT=0
for arg in "$@"; do
  case "$arg" in
    --no-wait) WAIT_ARGS=() ;;
    --secom)   SECOM_ARGS=(--set secom.enabled=true) ;;
    # The test client doubles as the MCP service registry; the callback endpoint is the
    # in-cluster proxy, so the client's signed acknowledgements can reach us.
    # The client is plain http on a cluster-internal address (the test PKI cannot issue peer
    # server certificates), which Niord refuses to push to unless the test-only
    # secomAllowInsecureEndpoints setting is on.
    --secom-client)
      SECOM_CLIENT=1
      SECOM_ARGS=(--set secom.enabled=true
        --set secom.settings.secomServiceRegistryUrl=http://secom-test-client:8080
        --set "secom.settings.secomCallbackEndpoint=http://${RELEASE}-proxy:18888/api/secom"
        --set secom.settings.secomAllowInsecureEndpoints=true) ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

command -v helm >/dev/null || { echo "ERROR: helm not installed (brew install helm)" >&2; exit 1; }

CTX="$(kubectl config current-context 2>/dev/null || echo '?')"
echo ">> kube context: ${CTX}  (expected: orbstack)"

# Keep the chart's bundled realm in sync with the test resource it came from. The app builds the schema with
# Liquibase on startup; a new database holds no data until it is bootstrapped once with
# scripts/bootstrap.sh (k8s/README.md, Quick start).
echo ">> Syncing realm JSON into the chart"
cp "${REPO_ROOT}/niord/src/test/resources/keycloak/niord-realm.json"   "${CHART_DIR}/files/niord-realm.json"

if [[ "${SECOM_ARGS[*]}" == *"secom.enabled=true"* ]]; then
  echo ">> Creating the SECOM test secrets (committed test PKI)"
  NAMESPACE="${NAMESPACE}" "${REPO_ROOT}/k8s/scripts/secom-test-secrets.sh"
fi

echo ">> helm upgrade --install ${RELEASE} (namespace: ${NAMESPACE})"
# EXTRA_HELM_ARGS is deliberately word-split (no quotes): it is a flat flag string.
# shellcheck disable=SC2086
helm upgrade --install "${RELEASE}" "${CHART_DIR}" \
  --namespace "${NAMESPACE}" \
  --create-namespace \
  ${SECOM_ARGS[@]+"${SECOM_ARGS[@]}"} \
  ${PIN_ARGS[@]+"${PIN_ARGS[@]}"} \
  ${WAIT_ARGS[@]+"${WAIT_ARGS[@]}"} \
  ${EXTRA_HELM_ARGS:-}

if [[ "$SECOM_CLIENT" == "1" ]]; then
  echo ">> Deploying the SECOM test client"
  NAMESPACE="${NAMESPACE}" "${REPO_ROOT}/k8s/scripts/secom-test-client.sh"
fi

echo
echo ">> Pods:"
kubectl -n "${NAMESPACE}" get pods
