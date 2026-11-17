#!/bin/sh
# Regenerates the SECOM test PKI fixtures (committed; run only if they ever need to change).
# Layout mimics MCP: a root CA issues service/client certificates whose subject UID carries
# the MRN. Keys are EC P-384 to match the SHA3-384/ECDSA SECOM signature algorithm.
# All stores use password "changeit". Certificates are valid for 100 years to avoid test rot.
#
# Products (in this directory):
#   keystore-test.p12    - the Niord test node identity (alias "1": key + cert + root chain)
#   truststore-test.p12  - the test MCP root certificate
#   client-one.p12       - a remote test node identity (alias "1"), used by ITs to sign acks
#   client-one.pem       - its leaf certificate, sent in the X-Secom-Cert header
#   untrusted-client.pem - a certificate from a DIFFERENT root, for negative chain tests
#
# Copies of keystore-test.p12 / truststore-test.p12 / untrusted-client.pem / client-one.p12
# (+ a base64 keystore variant) also live in niord/src/test/resources/secom-test/ for the
# McpSecurityService unit tests (client-one doubles as the "rotated" identity there).
set -e
cd "$(dirname "$0")"
TMP=$(mktemp -d)
PW=changeit

# Test MCP root CA
openssl ecparam -name secp384r1 -genkey -noout -out "$TMP/root.key"
openssl req -x509 -new -key "$TMP/root.key" -sha384 -days 36500 \
    -subj "/CN=Niord Test MCP Root" -out "$TMP/root.pem"

# SECOMLib validates envelope certificates with PKIX + revocation checking (SOFT_FAIL):
# the certificate MUST carry revocation info (like real MCP certs do), but an unreachable
# OCSP responder is tolerated. Point AIA at a closed local port -> instant soft-fail.
cat > "$TMP/ext.cnf" <<'EOF'
[v3_ext]
keyUsage = digitalSignature, keyEncipherment
authorityInfoAccess = OCSP;URI:http://127.0.0.1:9/ocsp
EOF

issue() { # $1=basename $2=mrn $3=cn $4=ca-basename
    openssl ecparam -name secp384r1 -genkey -noout -out "$TMP/$1.key"
    openssl req -new -key "$TMP/$1.key" -subj "/UID=$2/CN=$3" -out "$TMP/$1.csr"
    openssl x509 -req -in "$TMP/$1.csr" -CA "$TMP/$4.pem" -CAkey "$TMP/$4.key" \
        -CAcreateserial -sha384 -days 36500 -extfile "$TMP/ext.cnf" -extensions v3_ext \
        -out "$TMP/$1.pem"
}

issue service "urn:mrn:mcp:service:mcc:test:instance:niord-test" "Niord Test Service" root
issue client-one "urn:mrn:mcp:service:mcc:test:instance:client-one" "Test Client One" root

openssl pkcs12 -export -name "1" -inkey "$TMP/service.key" -in "$TMP/service.pem" \
    -certfile "$TMP/root.pem" -passout "pass:$PW" -out keystore-test.p12
openssl pkcs12 -export -name "1" -inkey "$TMP/client-one.key" -in "$TMP/client-one.pem" \
    -certfile "$TMP/root.pem" -passout "pass:$PW" -out client-one.p12
# NB: the truststore is built with keytool - the JDK PKCS12 reader skips openssl's
# cert-only bags (no Oracle trustedkeyusage attribute), yielding an "empty" store.
rm -f truststore-test.p12
keytool -importcert -noprompt -alias "niord test mcp root" -file "$TMP/root.pem" \
    -keystore truststore-test.p12 -storetype PKCS12 -storepass "$PW"
cp "$TMP/client-one.pem" client-one.pem

# A client from a different (untrusted) root
openssl ecparam -name secp384r1 -genkey -noout -out "$TMP/root2.key"
openssl req -x509 -new -key "$TMP/root2.key" -sha384 -days 36500 \
    -subj "/CN=Untrusted Test Root" -out "$TMP/root2.pem"
issue evil "urn:mrn:mcp:service:mcc:test:instance:evil" "Evil Client" root2
cp "$TMP/evil.pem" untrusted-client.pem

# Unit-test copies (niord module) + a base64-encoded keystore variant
UNIT_DIR=../../../../../niord/src/test/resources/secom-test
mkdir -p "$UNIT_DIR"
cp keystore-test.p12 truststore-test.p12 untrusted-client.pem client-one.p12 "$UNIT_DIR/"
openssl base64 -in keystore-test.p12 -out "$UNIT_DIR/keystore-test-base64.txt"

rm -rf "$TMP"
echo "SECOM test PKI regenerated"
