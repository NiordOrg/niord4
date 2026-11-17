#!/usr/bin/env python3
"""Offline stand-in for the MCP Identity Registry's certificate issuance endpoints, used
by the renewal rehearsal (secom-renewal-rehearsal.sh; identity plan T3b/T5). Answers

    POST /x509/api/org/{org}/{service|user}/{mrn}/certificate/issue-new/csr

with a PEM certificate chain: the posted CSR signed by the rehearsal CA via
`openssl x509 -req` - which preserves the CSR subject, so the UID=MRN carries over
exactly like the app's renewal gates expect - followed by the CA certificate, mirroring
the real MIR response shape. Issued leaves carry the same keyUsage + soft-fail OCSP AIA
as the committed test PKI. GET /healthz answers 200 (used to probe pod->host routes).

Plain HTTP on purpose: the renewal client treats http: MIR URLs as the stub path (no
TLS), exactly like the integration tests.

Usage: secom-stub-mir.py --ca-cert ca.pem --ca-key ca.key [--port 18446] [--days 200]
                         [--ext-file ext.cnf]

--ext-file points at an openssl extension file with a [v3_ext] section, letting the
caller (the rehearsal script) reuse ITS leaf profile so initial and renewed
certificates cannot drift apart; without it a built-in copy of the same profile is used.
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ISSUE_PATH = re.compile(r"^/x509/api/org/[^/]+/(?:service|user)/[^/]+/certificate/issue-new/csr$")

EXTENSIONS = """[v3_ext]
keyUsage = digitalSignature, keyEncipherment
authorityInfoAccess = OCSP;URI:http://127.0.0.1:9/ocsp
"""


def main():
    parser = argparse.ArgumentParser(description="Stub MCP Identity Registry (rehearsal)")
    parser.add_argument("--ca-cert", required=True)
    parser.add_argument("--ca-key", required=True)
    parser.add_argument("--port", type=int, default=18446)
    parser.add_argument("--days", type=int, default=200)
    parser.add_argument("--ext-file", help="openssl extension file with a [v3_ext] section")
    args = parser.parse_args()

    with open(args.ca_cert) as f:
        ca_pem = f.read()
    if args.ext_file:
        with open(args.ext_file) as f:
            extensions = f.read()
    else:
        extensions = EXTENSIONS

    class Handler(BaseHTTPRequestHandler):

        def _reply(self, status, body, content_type="text/plain"):
            data = body.encode() if isinstance(body, str) else body
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if self.path == "/healthz":
                self._reply(200, "ok")
            else:
                self._reply(404, "not found")

        def do_POST(self):
            if not ISSUE_PATH.match(self.path):
                self._reply(404, "not an issue-new/csr path: " + self.path)
                return
            csr = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            with tempfile.TemporaryDirectory() as tmp:
                csr_file = os.path.join(tmp, "req.csr")
                ext_file = os.path.join(tmp, "ext.cnf")
                leaf_file = os.path.join(tmp, "leaf.pem")
                with open(csr_file, "wb") as f:
                    f.write(csr)
                with open(ext_file, "w") as f:
                    f.write(extensions)
                result = subprocess.run(
                    ["openssl", "x509", "-req", "-in", csr_file,
                     "-CA", args.ca_cert, "-CAkey", args.ca_key, "-CAcreateserial",
                     "-sha384", "-days", str(args.days),
                     "-extfile", ext_file, "-extensions", "v3_ext", "-out", leaf_file],
                    capture_output=True, text=True)
                if result.returncode != 0:
                    self._reply(500, "openssl x509 -req failed: " + result.stderr)
                    return
                with open(leaf_file) as f:
                    leaf_pem = f.read()
            print("[stub-mir] issued for", self.path, flush=True)
            self._reply(200, leaf_pem + ca_pem, "application/pem-certificate-chain")

        def log_message(self, fmt, *fmt_args):
            print("[stub-mir]", fmt % fmt_args, flush=True)

    print(f"[stub-mir] listening on 0.0.0.0:{args.port}, issuing {args.days}-day certificates", flush=True)
    try:
        ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()
