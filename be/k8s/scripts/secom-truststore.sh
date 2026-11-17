#!/usr/bin/env bash
# Builds the SECOM truststore from a curated CA input:
#
#   truststore.p12  the PKCS12 store the app validates SECOM signatures and peer
#                   certificates against (niord.secom.truststore.*). Built with keytool on
#                   purpose: the JDK PKCS12 reader skips openssl's cert-only bags (see
#                   generate-test-pki.sh).
#
# It is the only trust artifact: SECOM Edition 2 takes a peer's identity from the
# certificate in the signed envelope, so nothing in front of the app checks certificates.
#
# Input: one or more PEM files or http(s):// URLs, each holding one or more CA
# certificates - e.g. a provider chain download from docs.maritimeconnectivity.net.
# Remember the issuing/intermediate CAs, not just the root: the app checks a peer's leaf
# certificate directly against each entry of this store (MrnCertificateFilter walks no
# chain), so a leaf issued by an intermediate validates only when that intermediate is here.
#
# The FIRST certificate of the FIRST input is imported under the alias "mcp-root" and is
# what `niord.secom.truststore.root-alias` should name (or leave that setting "" - the
# first certificate entry wins). Further certificates become mcp-ca-2, mcp-ca-3, ...
# Duplicates (by SHA-256 fingerprint) are dropped.
#
#   ./secom-truststore.sh [-o outdir] [-p password] [-n namespace] [--apply] input...
#
#   -o outdir     output directory (default: k8s/secom-truststore-out, gitignored)
#   -p password   truststore password (default: changeit, the committed test PKI's)
#   --apply       additionally PATCH the rebuilt store into the running cluster's stores
#                 secret (a merge patch - the other keys are untouched). The app picks it
#                 up via the store watch within ~2 minutes.
#   -n namespace  namespace for --apply (default: niord, or $NAMESPACE)
#   --stores-secret NAME   stores secret for --apply (default: niord-secom-stores,
#                          matching the chart's secom.storesSecret)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${REPO_ROOT}/k8s/secom-truststore-out"
PW=changeit
NAMESPACE="${NAMESPACE:-niord}"
STORES_SECRET="niord-secom-stores"
APPLY=0
INPUTS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    -o) OUT="$2"; shift 2 ;;
    -p) PW="$2"; shift 2 ;;
    -n) NAMESPACE="$2"; shift 2 ;;
    --stores-secret) STORES_SECRET="$2"; shift 2 ;;
    --apply) APPLY=1; shift ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "unknown option: $1" >&2; exit 2 ;;
    *) INPUTS+=("$1"); shift ;;
  esac
done
[[ ${#INPUTS[@]} -gt 0 ]] || { echo "usage: secom-truststore.sh [-o outdir] [-p password] [--apply] <pem-file-or-url>..." >&2; exit 2; }

command -v keytool >/dev/null || { echo "ERROR: keytool not on PATH (install a JDK)" >&2; exit 1; }
command -v openssl >/dev/null || { echo "ERROR: openssl not on PATH" >&2; exit 1; }

mkdir -p "$OUT"
SPLIT="$(mktemp -d "${TMPDIR:-/tmp}/secom-truststore.XXXXXX")"
trap 'rm -rf "$SPLIT"' EXIT

# 1. Gather all inputs into one PEM stream (curl for URLs, cat for files)
COMBINED="$SPLIT/combined.pem"
for input in "${INPUTS[@]}"; do
  case "$input" in
    http://*|https://*) echo ">> Fetching $input"; curl -fsSL "$input" >> "$COMBINED"; echo >> "$COMBINED" ;;
    *) [[ -f "$input" ]] || { echo "ERROR: no such file: $input" >&2; exit 1; }
       cat "$input" >> "$COMBINED"; echo >> "$COMBINED" ;;
  esac
done

# 2. Split into individual certificates (only CERTIFICATE blocks; keys etc. are ignored)
awk -v dir="$SPLIT" '
  /-----BEGIN CERTIFICATE-----/ { n++; f = sprintf("%s/cert-%03d.pem", dir, n) }
  f { print > f }
  /-----END CERTIFICATE-----/ { f = "" }
' "$COMBINED"
ls "$SPLIT"/cert-*.pem >/dev/null 2>&1 || { echo "ERROR: the input contains no PEM certificates" >&2; exit 1; }

# 3. Rebuild the store from scratch (idempotent - never accumulates stale CAs)
TRUSTSTORE="$OUT/truststore.p12"
rm -f "$TRUSTSTORE"

echo
echo ">> Truststore entries:"
COUNT=0
SEEN=""
for cert in "$SPLIT"/cert-*.pem; do
  FP="$(openssl x509 -in "$cert" -noout -fingerprint -sha256 | cut -d= -f2)"
  case "$SEEN" in *"$FP"*) continue ;; esac   # dedup by fingerprint
  SEEN="$SEEN $FP"
  COUNT=$((COUNT + 1))
  if [[ $COUNT -eq 1 ]]; then ALIAS="mcp-root"; else ALIAS="mcp-ca-$COUNT"; fi
  keytool -importcert -noprompt -storetype PKCS12 \
          -keystore "$TRUSTSTORE" -storepass "$PW" \
          -alias "$ALIAS" -file "$cert"
  SUBJECT="$(openssl x509 -in "$cert" -noout -subject | sed 's/^subject= *//')"
  NOTAFTER="$(openssl x509 -in "$cert" -noout -enddate | cut -d= -f2)"
  printf '   %-10s %s\n              expires %s\n              sha256  %s\n' "$ALIAS" "$SUBJECT" "$NOTAFTER" "$FP"
done

echo
echo ">> Wrote $TRUSTSTORE ($COUNT certificate(s))"
echo "   root alias: mcp-root (matches niord.secom.truststore.root-alias, or leave it \"\")"

# 4. Optionally patch the running cluster - a merge patch leaves the other secret keys alone
if [[ "$APPLY" == "1" ]]; then
  command -v kubectl >/dev/null || { echo "ERROR: kubectl not installed" >&2; exit 1; }
  B64_STORE="$(openssl base64 -A -in "$TRUSTSTORE")"
  echo
  echo ">> Patching secret ${STORES_SECRET} (truststore.p12) in namespace ${NAMESPACE}"
  kubectl -n "$NAMESPACE" patch secret "${STORES_SECRET}" --type=merge \
    -p "{\"data\":{\"truststore.p12\":\"${B64_STORE}\"}}"
  echo "   The app reloads the truststore automatically within ~2 minutes (store watch)."
fi
