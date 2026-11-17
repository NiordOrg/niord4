#!/usr/bin/env bash
# Build + deploy the SECOM test client (k8s/secom-test-client/): a minimal in-cluster
# SECOM consumer that subscribes to the Niord SECOM endpoint with the committed test-PKI
# client identity, doubles as its own service registry, receives pushed S-124 uploads and
# sends signed acknowledgements. See the "SECOM end-to-end demo" section in k8s/README.md.
#
#   ./secom-test-client.sh            # build jar (if missing) + image, secret, apply
#   ./secom-test-client.sh --rebuild  # force a fresh `mvn package`
#   ./secom-test-client.sh --delete   # remove the client again (keeps the secret)
#
# NB: Niord only pushes to this client if its secomServiceRegistryUrl points here -
# deploy the release with `./deploy.sh --secom-client`, which sets that up.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CLIENT_DIR="${REPO_ROOT}/k8s/secom-test-client"
PKI_DIR="${REPO_ROOT}/niord/src/test/resources/secom-test"
NAMESPACE="${NAMESPACE:-niord}"
IMAGE="${IMAGE:-secom-test-client:dev}"
JAR="${CLIENT_DIR}/target/secom-test-client.jar"

command -v kubectl >/dev/null || { echo "ERROR: kubectl not installed" >&2; exit 1; }

if [[ "${1:-}" == "--delete" ]]; then
  kubectl -n "$NAMESPACE" delete -f "${CLIENT_DIR}/k8s.yaml" --ignore-not-found
  exit 0
fi

# --- Shaded jar (host build, mirroring build-image.sh) -----------------------
if [[ "${1:-}" == "--rebuild" || ! -f "$JAR" ]]; then
  echo ">> Building the SECOM test client jar with Maven..."
  ( cd "$CLIENT_DIR" && mvn -q clean package )
else
  echo ">> Reusing existing jar: $JAR"
fi
[[ -f "$JAR" ]] || { echo "ERROR: jar not found at $JAR" >&2; exit 1; }

# --- Image (shared with OrbStack Kubernetes, no registry push needed) --------
echo ">> Building image: ${IMAGE}"
docker build -t "$IMAGE" "$CLIENT_DIR"

# --- Client PKI secret: the committed test client identity -------------------
kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 || kubectl create namespace "$NAMESPACE"
kubectl -n "$NAMESPACE" create secret generic niord-secom-test-client \
  --from-file=client-one.p12="${PKI_DIR}/client-one.p12" \
  --from-file=truststore.p12="${PKI_DIR}/truststore-test.p12" \
  --dry-run=client -o yaml | kubectl apply -f -

echo ">> Deploying the SECOM test client"
kubectl -n "$NAMESPACE" apply -f "${CLIENT_DIR}/k8s.yaml"
kubectl -n "$NAMESPACE" rollout status deploy/secom-test-client --timeout=2m

echo
echo "SECOM test client is up. Follow it with:"
echo "  kubectl -n ${NAMESPACE} logs -f deploy/secom-test-client"
