#!/usr/bin/env bash
# Remove the Niord Helm release.
#
#   ./teardown.sh           # uninstall the release (keeps PVCs / data)
#   ./teardown.sh --purge   # also delete PVCs and the namespace (wipes all data)
set -euo pipefail

RELEASE="${RELEASE:-niord}"
NAMESPACE="${NAMESPACE:-niord}"
PURGE=0
for arg in "$@"; do
  case "$arg" in
    --purge) PURGE=1 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

echo ">> helm uninstall ${RELEASE} (namespace: ${NAMESPACE})"
helm uninstall "${RELEASE}" --namespace "${NAMESPACE}" || true

if [[ "$PURGE" == "1" ]]; then
  echo ">> Deleting PVCs in ${NAMESPACE}"
  kubectl -n "${NAMESPACE}" delete pvc --all --ignore-not-found
  echo ">> Deleting namespace ${NAMESPACE}"
  kubectl delete namespace "${NAMESPACE}" --ignore-not-found
fi

echo ">> Done."
