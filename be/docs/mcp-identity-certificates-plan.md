# MCP identity & certificates

Niord's identity subsystem: what it is, how the certificate lifecycle works (expiry
monitoring, auto-reload, unattended renewal), the MCP ecosystem it plugs into, and what
remains to be done. The sysadmin-facing howto lives in the documentation site:
`guides/_parked/secom/A2-SecomCertificates.adoc`.

## 1. Framing: identity first, SECOM second

The starting insight (and it is the right one): **certificates are not a SECOM feature.
They are Niord's *identity*, and SECOM is merely the first consumer of it.**

Concretely, a Niord instance's identity in the maritime ecosystem is:

1. an **MRN** (Maritime Resource Name, e.g. `urn:mrn:mcp:entity:mcc:dma:niord-nw`) — the
   stable name, which never changes, and
2. a **key pair + X.509 certificate** binding that MRN to a public key — the rotating
   credential, reissued every ~6 months.

Everything SECOM does (envelope/payload signatures, S-124 exchange-set signatures, the
robot-user calls to the MIR) hangs off that identity. *Revised 2026-10-14:* SECOM Edition 2
(IEC CDV 63173-2, clause 4.3.3) takes a peer's identity from the certificate in the signed
envelope, not from a TLS client certificate; the nginx listener, its TLS Secret and the
Reloader that rolled it are gone from the chart (`secom-design.md` D3). The sections
below that described them are marked. Future consumers (S-100 data protection, MMS
messaging, other SECOM product streams) would reuse the same identity. So "identity &
certificate lifecycle" is treated as its own subsystem — which is how the code is shaped:
`org.niord.secom.pki.McpSecurityService` *is* that subsystem, and the lifecycle services
described in §3 (expiry watch, store watch, renewal) are built around it.

## 2. Certificate primer (the 10 concepts that matter here)

For readers who don't live in PKI land. Each concept is mapped to where it shows up in Niord.

| Concept | What it is | Where it appears in Niord |
|---|---|---|
| Key pair | A private key (secret, signs/decrypts) + public key (shared, verifies/encrypts). | Private key sits in the keystore at alias `1`. |
| Certificate | A signed statement by a CA: "this public key belongs to this subject, valid from/to". | The "service certificate" shown on the SECOM sysadmin page. |
| CA / chain / root | Certificates are issued by a Certificate Authority, whose own cert is issued by another CA, ending at a self-signed **root**. Trust flows from the root down. | MCP: provider root → intermediate ("identity CA") → Niord's cert. |
| Keystore | File holding **our** private key + certificate chain. Compromise = identity theft. | `niord.secom.keystore.*` → PKCS12, k8s secret `niord-secom-stores`. |
| Truststore | File holding **other people's CA certificates** we choose to trust. No secrets in it, but tampering = trusting an attacker. | `niord.secom.truststore.*`. |
| Client-certificate TLS | TLS where the *client* also presents a certificate. | Only towards the MIR: the robot-user credential in `McpSecurityService.newHttpClient(...)`. Not on the SECOM endpoint since Edition 2. |
| Digital signature | Hash of a payload encrypted with the private key; anyone with the cert can verify origin + integrity. | SECOM envelopes/payloads and persisted S-124 exchange-set signatures. |
| Revocation (OCSP/CRL) | How a CA says "this cert is no longer good" before it expires. OCSP = online query, CRL = downloadable list. | PKIX `SOFT_FAIL` checks both directions; setting `secomRevocationCheck`. |
| Expiry & renewal | Certs die on a date. Renewal = generate **fresh keys**, get a new cert, swap it in. MCP certs live ~6 months. | Watched (T1) and automated (T3/T3b): renewal runs ~60 days before expiry with fresh keys. |
| Fingerprint | SHA-256 of the cert bytes — a short unique handle to confirm "which cert is live". | `GET /rest/secom/status`, reload response, sysadmin page. |

## 3. The identity subsystem

Everything below is gated behind `niord.secom.enabled` (default `false`).

### 3.1 Core capabilities

| Capability | Implementation |
|---|---|
| Key material loading | `McpSecurityService` — PKCS12 keystore + truststore via 6 properties (`niord.secom.keystore.location/password/alias`, `niord.secom.truststore.location/password/root-alias`); `classpath:`/`file:` locations; **base64-encoded stores auto-detected** (k8s-secret-friendly); fail-fast at startup if missing. `niord/src/main/java/org/niord/secom/pki/McpSecurityService.java` |
| Hot rotation seam | `POST /rest/secom/security/reload` (SYSADMIN) atomically swaps an immutable `KeyMaterial` snapshot; failed reload keeps the old material; response carries old/new SHA-256 fingerprints; UI button on the SECOM sysadmin page. Published dataset versions are untouched by a rotation — they keep their signature and travel with the chain that made it — covered by `SecomSecurityReloadITTest`. |
| Signing | SECOMLib (`org.grad.secomv2`) wired through `NiordSecomProviders` (certificate + signature + truststore provider). Outgoing algorithm = setting `secomSignatureAlgorithm`, default `SHA2_384_WITH_ECDSA`; inbound verification accepts both 384-bit variants (split-fleet tolerant). Exchange-set dataset signatures are made **once at write**, inside the build that packages the dataset, and persisted as an immutable `S124DatasetVersion` (file bytes, catalogue entry with the signature, signing chain). |
| Inbound identity | `MrnCertificateFilter` validates the peer's certificate against the truststore CAs, PKIX revocation `SOFT_FAIL`, extracts the peer MRN from the subject `UID`, records it as a `SecomNode`. The certificate comes from the signed envelope (Edition 2); today the filter still reads the `X-Secom-Cert` header the former nginx listener set, and only where `niord.secom.trust-cert-header=true` says a verifying proxy sets it (default `false`: every request but ping is refused; `s124-secom-spec-gap-analysis.md` §7). |
| Outbound identity | the envelope and payload signatures; the `HttpClient` still offers our key material as a client certificate, which an Edition 2 peer ignores, and validates the peer's server chain against the truststore with OCSP→CRL `SOFT_FAIL` (an Edition 2 peer presents a public-CA certificate, §7 of the gap analysis). |
| k8s wiring | Helm chart secret `niord-secom-stores` (app stores), created outside Helm; `deploy.sh --secom[-client]` rehearses the full subscribe→publish→ack→cancel flow live with the SECOMLib test client. |
| Failure alerting | Mails to the `secomAlertEmails` recipients on outbox terminal failure / NACK, certificate-expiry warnings (T1), store rotations and reload failures (T2), and renewal outcomes (T3). |

### 3.2 Expiry visibility and alerting (T1)

* Gauge `niord.secom.certificate.days-to-expiry` on `SecomMetrics` — supplier-based,
  computed at scrape time from the in-memory certificate: no database touch, and it
  tracks hot reloads immediately.
* `SecomCertificateAlertService` (daily, `niord.secom.cert-expiry-cron`): setting
  `secomCertExpiryWarnDays` (default 60 = the G1183 "renew 2 months before expiry"
  point); inside the window a mail goes to the `secomAlertEmails` recipients, repeated
  weekly, escalating at ≤14 days and again on expiry; in-memory dedup keyed on
  certificate fingerprint + tier (a restart re-sends at most one mail). The dedup marker
  advances only once the alert mail is committed — or when no recipients are configured —
  so an alert that cannot be queued is retried on the next run (the
  "commit-before-marker" rule, shared with T2/T3).
* `serviceCertificateDaysToExpiry` on the status VO + shown on the SECOM sysadmin page
  (red EXPIRED marker when negative).

### 3.3 Store-file watch → auto-reload (T2)

* `SecomStoreWatchService`, scheduled every minute (`niord.secom.store-watch-cron`,
  default `47 * * * * ?`; `-` disables), delegates to
  `McpSecurityService.reloadIfChanged()`. The change check compares the **raw bytes of
  both stores** (per-store SHA-256, remembered at load time) — so truststore-only
  updates (a new peer CA before cutover) converge too, and on change the fresh material
  is parsed from the *same* bytes that were compared (no read race). The atomic swap and
  keep-old-on-failure properties are inherited from the `reload()` contract.
* Alerting: a reload queues a confirmation mail to the `secomAlertEmails` recipients
  (the subject distinguishes a certificate rotation from a truststore-only update, and a
  rotation mail notes that published dataset versions keep their signatures); a failed load keeps the previous
  material serving and alerts once per *distinct* failure (not once per minute), with
  the commit-before-marker retry rule; the rotation confirmation stays best-effort
  (a one-shot event, already logged).
* Why this shape: in k8s, updated `Secret`s propagate into mounted volumes automatically
  (~1 min); with the watch, *any* rotation mechanism (manual `kubectl apply`, an external
  agent, the renewal job itself) converges without anyone calling the REST endpoint.
  Also covers plain-file deployments. `classpath:` stores stay restart-only (the watch
  skips entirely when no store is file-backed).
* The REST endpoint and UI button stay (immediate, verifiable, returns fingerprints).
* Nothing outside the app holds certificate material any more (the nginx listener is
  gone), so no restart of anything is needed.
* Tests: `McpSecurityServiceTest` units on the reload seam + `SecomStoreWatchITTest`
  driving `watch()` against a temp-file keystore (rotation + confirmation mail, broken
  store keeps serving + single alert, blank recipients reload silently).

### 3.4 Unattended renewal via the MIR (T3)

Default-off, setting-gated (`secomRenewalEnabled=false`). **The renewal logic is pure
Java and runs as a normal in-app Spring `@Scheduled` job** — the same pattern as the
outbox dispatcher, the metrics sweep and the reconcile job. Key generation is plain JCA
(`KeyPairGenerator`, curve secp384r1); the CSR is BouncyCastle's
`PKCS10CertificationRequestBuilder` (`bcpkix-jdk18on` is a direct dependency — SECOMLib
does not bring it); the MIR call reuses the existing `McpSecurityService.newHttpClient`
mTLS plumbing. No external process is *required*; only the "persist" step is
deployment-specific.

```
 Spring @Scheduled job (daily)                      MIR (identity provider)
   │ service cert expires < secomRenewalWindowDays?      ▲
   ├── no ──▶ done (T1 gauge/alert mails keep watching)  │ 3. POST …/certificate/
   ▼ yes                                                 │    issue-new/csr
 1. generate fresh EC P-384 key pair                     │    (mTLS: robot-user
 2. build CSR (subject ignored by the MIR) ──────────────┘     credential)
 4. receive PEM chain ─▶ assemble keystore.p12 (same alias + password)
 5. persist the new keystore          ── the only deployment-specific step
 6. reload() in-memory ─▶ verify fingerprint changed
 7. alert mail (success — or failure, which retries next run)
    … and the same loop renews the robot credential itself (user self-issue)
```

Components:

* `McpMirClient` — a small interface for `issue-new/csr` (deliberately ACME-swappable,
  see the non-goal below). `McpMirRestClient` is the REST implementation, authenticated
  with a separate **robot-user credential keystore** (`niord.secom.renewal.*`
  properties; the robot store is read freshly per call). MIR server trust uses the
  **JDK default anchors** — the registry API serves public TLS, not MCP-chain TLS.
* `SecomCertificateRenewalService` — the scheduled loop above; failures mail
  `secomAlertEmails` and retry on the next run, while the expiry alerting (T1) keeps
  escalating independently.

Behavior details:

* Verification gates before anything is mutated: fresh-key match, validity of every
  chain certificate, subject-UID==MRN and chain-link signatures (hard failures), plus
  truststore anchoring (**soft**: renewal proceeds with a log warning and a mail caveat,
  so a provider CA rollover cannot brick the renewal that is needed exactly then).
* The robot credential renews strictly before the service certificate (a user may issue
  a new certificate for itself, so the loop is closed); a robot failure still lets the
  service attempt run. Both certs are ~6-month-lived, so the renewer maintains two.
* `service-mrn`/`user-mrn` default to the certificate subject UIDs; `org-mrn` is always
  explicit (the org choice is irreversible — §6 — and must be deliberate).
* Forced run: `POST /rest/secom/security/renew` + the sysadmin-page *Renew Now* button
  renew both credentials immediately, ignoring the window and the `secomRenewalEnabled`
  setting (but not the `niord.secom.renewal.*` configuration) — the supervised
  first-renewal tool (§7.1 step 4). Same gates, mails, dedup and pickup guards as a
  scheduled run; concurrent runs are rejected (409).
* Settings: `secomRenewalEnabled` (false), `secomRenewalWindowDays` (60); cron
  `niord.secom.renewal-cron` (05:27 daily, before the 06:17 expiry check; `-` disables);
  `niord.secom.renewal.*` properties incl. `timeout-seconds` (30). Failure alerts follow
  the commit-before-marker dedup; success confirmations are best-effort.
* Tests: units on the client/CSR/verification logic + `SecomCertificateRenewalITTest`
  against a stub MIR — an ephemeral in-test CA signs the app's actual CSR, since the
  committed test root's key is deliberately not available.

Persistence backends for step 5 (choose per deployment):

| Backend | How | Trade-off |
|---|---|---|
| Writable file path (VM installs) | `FileKeystoreWriter` overwrites the configured `file:` keystore in place (atomic same-dir temp+rename) | simplest; just requires the path to be writable |
| **k8s Secret PATCH — the chart's backend** | the job PATCHes `niord-secom-stores` through the Kubernetes REST API — pure Java, one HTTPS call authenticated with the pod's mounted ServiceAccount token; the chart ships a ServiceAccount + Role scoped to `get`/`patch` on the SECOM Secrets (T3b) | the Secret stays the single source of truth (pod restarts, new replicas); kubelet propagates to the mount; T2 covers any other consumer |
| `/niord-home` file (k8s, zero RBAC) | point `keystore.location` at the existing writable volume; the Secret only seeds first boot | works with no k8s API rights, but the private key lands on the data volume/backups, and with `app.persistence.enabled=false` (emptyDir) a pod restart silently falls back to the stale Secret |
| External CronJob (fallback) | same client code in a tiny CLI main (or an openssl+curl script in `k8s/scripts/` style) + `kubectl apply` of the Secret; T2 auto-reload closes the loop | for operators who refuse the app any k8s API access; costs a second deployable + credential |

The backends hang off a `KeystoreWriter` seam, so they are swappable per deployment
without touching the renewal loop.

Explicit non-goal: ACME. `McpMirClient` is kept behind a small interface so an ACME
implementation can slot in if/when MIRs grow one (G1183 points there), but none is built
— no MCP registry implements ACME (§5.4).

### 3.5 Kubernetes backend: Secret PATCH (T3b)

`KubernetesSecretKeystoreWriter` (`@Primary`, gated on `niord.secom.renewal.k8s.enabled`)
PATCHes the renewed store into its Secret through the Kubernetes API — a JSON merge
patch, authenticated with the pod's (rotating) ServiceAccount token read per call, the
API server trusted via the mounted CA bundle, and the response echo checked so a "200
but not persisted" can never pass. Store locations map to Secrets via
`niord.secom.renewal.k8s.secret-mounts` (`<mount-dir>=<secret-name>`, key = file name); a
misconfiguration alerts at run time, never fails the boot. Plain `http:` API URLs are
the IT stub path.

Writes report "deferred pickup": the renewal service skips the immediate read-back —
the read-only mount converges via the kubelet + store watch (T2) within ~2 minutes,
whose own rotation mail closes the loop (the renewal confirmation says so, and names the
`secret-mounts`-misconfig symptom: no rotation mail ever follows). The lost read-back is
re-established asynchronously: each deferred renewal records the rotated-away
fingerprint, and if a later run still finds it being served, re-issuing is paused with a
"never became visible" alert instead of minting MIR identities daily.

The former `publishServedCertificate` seam (PEM forms of the renewed credential into a
second Secret, `niord.secom.renewal.k8s.proxy-tls-secret`) is removed (2026-10-14): it
served the nginx listener, which is gone (Edition 2), and the renewal Role no longer
covers a third Secret. Error hints are backend-aware: MIR 401/403s get the robot-role
hint, Kubernetes RBAC 403s get their own ServiceAccount hint.

The Helm chart renders the renewal machinery behind `secom.renewal.enabled`: the
robot-credential mount, the renewal config, and a ServiceAccount + Role scoped to
`get`/`patch` on exactly the two SECOM Secrets. `deploy.sh` pins both
toggles per run (`secom.enabled`, `secom.renewal.enabled`, default false) and has an
`EXTRA_HELM_ARGS` passthrough to override them. Tests: units on the writer/PEM
round-trips/deferred+publish contracts + `SecomCertificateRenewalK8sITTest` against a
stub k8s API (the PATCH trio incl. headers/payloads, RBAC-403 alert with the right
hint, echo mismatch, proxy-publish caveat, simulated kubelet+watch convergence).

### Sidebar: can off-the-shelf container software do this for us?

Evaluated 2026-07. Nothing on the shelf can *issue or renew* MCP certificates — the MIR
speaks a bespoke authenticated REST API and no ACME — so the renewal logic must exist on
our side in some form regardless. The shelf does cover *distribution and pickup*.
Recorded here so we don't re-litigate each tool later:

| Tool | What it does | Fit |
|---|---|---|
| **cert-manager** (the k8s standard) | Automates issuance + renewal into k8s Secrets via ACME, Vault, Venafi, a local CA, or *external issuer* plugins | **Cannot issue MCP certs today** (no MIR issuer exists, MIR has no ACME). Two future paths: (a) write a small "MIR external issuer" controller — the same logic as T3's client, worth it only if DMA wants **one shared renewal component across several MCP services** (Niord and other DMA SECOM services); (b) if MIRs ever implement ACME — which G1183 §5.6 formally requires, citing RFC 8555 — cert-manager works out of the box and T3's `McpMirClient` can retire. T3 sits behind an interface so this stays a drop-in swap. |
| **Reloader** (stakater) | Watches Secrets/ConfigMaps and rolling-restarts the Deployments that use them when they change | **Was adopted, then removed (2026-10-14).** It rolled the nginx proxy when the SECOM listener certificate rotated. Edition 2 has no such listener, nothing else is annotated, and devops-shared removed the cluster-shared instance. The app never needed it: its in-place hot-reload is gentler than a restart. |
| HashiCorp Vault (PKI engine) | Runs its *own* CA hierarchy; renders/rotates secrets | Cannot make the MIR sign anything, and MCP providers don't delegate signing (G1183 PKI1.12: MIR intermediates sign client certs only). As a mere secret *store* it duplicates what k8s Secrets already do for us. |
| SPIFFE/SPIRE, service meshes (Istio/Linkerd) | Workload-identity mTLS inside the cluster, from their own CA | Internal cluster identity only — orthogonal to the *external* MCP identity SECOM peers verify. |
| **Keycloak** (we run it; the MIR runs one too) | OIDC/SAML — issues *tokens*, authenticates users/clients | **Keycloak is not a CA; it cannot issue or renew X.509 certificates.** There are two separate identity planes: humans/API calls (Keycloak, short-lived JWTs) and machine identity (MCP PKI, ~6-month X.509). They meet only at the MIR itself: *its* Keycloak (`maritimeid.…`) authenticates OIDC calls to the MIR API and offers a cert→token exchange (`cert2oidc`), but no client-credentials grant is documented, so unattended renewal rides the robot-user **mTLS** path. Our own Niord Keycloak plays no role in SECOM certificates. |
| Init containers / kubelet Secret propagation | Fetch material at pod start / auto-refresh mounted Secrets (~1 min) | Building blocks, not lifecycle: an init container can't renew a 6-month cert mid-life, and Secret propagation is what T2/T3 already build on. |

### 3.6 Trust management (T4)

* The sysadmin appendix A2 covers: where authoritative chains live, building the
  truststore with keytool (including **why the issuing/intermediate CAs should be
  present** — a peer that sends only its leaf still validates), environment separation,
  the peer-onboarding runbook, and a troubleshooting table.
* `k8s/scripts/secom-truststore.sh` (fetch chain → split → keytool import → emit
  `truststore.p12`, de-duplicating by fingerprint) builds the one trust artifact; with
  `--apply` it merge-patches it into the running cluster's stores secret. The renewal
  rehearsal (T5) dogfoods it.

### 3.7 Test story (T5)

* The committed openssl fixtures (`niord/src/test/resources/secom-test/`, regenerable
  via `generate-test-pki.sh`) are the base PKI: secp384r1/SHA-384 root + service +
  client + a second "evil" root for negative tests; subjects carry MCP-style
  `UID=urn:mrn:mcp:service:mcc:test:instance:…`; OCSP AIA points at a closed port to
  exercise soft-fail. Unit/IT classes cover load/sign/validate/reload/revocation/mTLS.
  (A test-scope `net.maritimeconnectivity.pki:mcp-pki` dependency — the exact library
  the real MIR mints with — was considered and not adopted; it becomes interesting only
  if we start parsing more than the subject UID.)
* **Stub MIR** for renewal ITs, following the repo's existing HTTP-stub pattern: happy
  issue, 403 (role mismatch), 5xx, malformed chain, slow response; assertions on
  keystore swapped/not-swapped + fingerprints + alert mails. The entire renewal loop is
  CI-testable without external infrastructure.
* k8s rehearsal: `k8s/scripts/secom-renewal-rehearsal.sh` + `secom-stub-mir.py` (a
  host-side stub MIR that the in-app job reaches over OrbStack's host aliases, probed at
  run time; the committed root's key is deliberately unavailable, so the rehearsal mints
  its own throwaway CA) exercises secret rotation → store-watch adoption (T2) →
  robot-then-service renewal → Secret PATCHes → renewed-material pickup, end to end,
  offline. One rehearsal-pace artifact worth
  knowing: with a cron faster than the ~1-minute kubelet sync the robot can be re-issued
  once more before its mount converges — harmless (the last PATCH wins), impossible at
  the real daily cadence.
* Real-world E2E against a real MIR: remaining work (§7).

## 4. Storage and flow map

Where certificates physically live. **"Is this a Java thing?"** — partly: PKCS12 is a
standard container format (RFC 7292; openssl reads and writes it — the Java-proprietary
format was the legacy JKS), but the keystore/truststore *pattern* is a Java idiom and
Niord loads the stores with `java.security.KeyStore`. The same certificates travel in a
different container — plain PEM text — for nginx. Two packagings, one identity.

k8s deployment (a VM install replaces the two Secrets with plain files on disk and the
proxy with whatever fronts the instance):

```
 MCP identity provider (MIR)
   │  issues our certificate (~6-month lifetime); publishes CA chains + OCSP/CRL
   ▼
 keystore.p12   = OUR private key + certificate chain            (secret!)
 truststore.p12 = THEIR CA certificates (roots + issuing CAs)    (no secrets)
   │                                       │
   │      packaged by the sysadmin or by the renewal job (T3/T3b)
   ▼                                       ▼
 Secret niord-secom-stores               (the former niord-secom-proxy-tls, the nginx
   keystore.p12, truststore.p12            listener's PEM copy of the same cert, is gone:
   │                                       Edition 2 has no SECOM listener)
   │ mounted read-only
   ▼
 /run/secrets/secom/*.p12
   │ niord.secom.{key,trust}store.*
   ▼
 McpSecurityService — in-memory
   immutable KeyMaterial snapshot
   (hot-swapped by reload(); a failed reload keeps the old snapshot)

 Also writable in the pod: /niord-home (PVC when app.persistence.enabled, else
 emptyDir) — a candidate renewal write-target, see the T3 backends table.

 Database: PUBLIC material only — each S-124 dataset row persists its signature and
 the signing certificate as PEM text (so old signatures stay verifiable after key
 rotation); peers are stored as plain MRN strings (no certs, no keys). The private
 key is never in the database.
```

Runtime trust flow (covered by ITs):

```
 INBOUND   peer ──HTTPS, signed envelope──▶ nginx (plain forward, no certificate)
                                        ▼
                                      MrnCertificateFilter
                                        │ certificate from the envelope (today: the header, §7)
                                        │ chain against truststore (needs the ISSUING CA)
                                        │ revocation check, PKIX soft-fail
                                        │ subject UID ──▶ peer MRN ──▶ SecomNode
                                        ▼
                                      SECOM endpoints (responses/pushes signed with keystore key)

 OUTBOUND  Niord ──HTTPS + envelope/payload signatures (keystore key)──▶ peer / MSR
                   ▲ peer's server chain verified, OCSP→CRL soft-fail
```

## 5. The ecosystem (what we integrate with)

Condensed from IALA **G1183 Ed 1.1** (Dec 2024, read in full), the MCP docs/portals
(live-checked 2026-07-11), the MIR reference implementation source, and DMC's published
CP/CPS. Sources in §9.

### 5.1 Identity model and MRNs

* MCP is organization-rooted: an **organization** registers with an MCP Identity Service
  Provider; under it live **users, devices, services, vessels, MMS** entities. Niord
  registers as a **service** entity; certificates are issued against its MRN.
* Current MRN syntax: `urn:mrn:mcp:<TYPE>:<IPID>:<IPSS>` with TYPE ∈
  `entity | mir | mms | msr` (`service`, `user`, `org`, `vessel`, `device` still parse but
  are legacy). IPID identifies the identity provider (`mcc` = the MCC testbed; ISO
  3166-1 alpha-2 codes are reserved for national authorities — Denmark could hold `dk`).
  Example shape for us: `urn:mrn:mcp:entity:mcc:dma` (org) /
  `urn:mrn:mcp:entity:mcc:dma:niord-nw` (the Niord service).
* The MIR's *versioned* service identities are deprecated; register one unversioned
  service and put deployment/version metadata in the MSR (service registry) instead.
* Niord already derives identity exactly this way: our MRN is read from the certificate
  subject `UID`; peers' MRNs likewise (`MrnCertificateFilter`). No code change is needed
  to "adopt" MCP identities — we already speak the format.

### 5.2 The PKI is decentralized — there is no single MCP root

G1183 requirement PKI1.1: *"There shall be no root CA at the top level of the MCC. Every
Identity Service Provider that hosts a PKI instance is to provide their own root CA."*
The MCC merely endorses providers and publishes a signed list of their roots. Practical
consequences for us:

* "Install **a** root certificate" is really "curate the set of provider roots +
  intermediates we trust" — per environment. Test roots and production roots are
  different chains and must never be mixed.
* The MCC testbed publishes its chains at fixed URLs (PEM/JKS/ZIP), e.g.
  `https://raw.githubusercontent.com/maritimeconnectivity/docs.maritimeconnectivity.net/refs/heads/files/mcp-ca-chain.pem`
  (public demonstrator) and `.../files/test/mcp-ca-chain.pem` (test environment).
  Endorsed production providers publish theirs per their CPS (DMC does).
* Relying parties are expected to verify a peer chain against the provider identified by
  the **IPID inside the peer's MRN** (G1183 PKI1.2). We approximate this today with a
  single shared truststore — fine while all peers use one provider (the DMA
  world), worth revisiting if we ever trust multiple providers (§7).

### 5.3 Certificate profile and lifetimes

* **ECDSA only**: P-384/SHA-384 (primary) or P-256/SHA-256 (bandwidth-constrained
  contexts). Our default `SHA2_384_WITH_ECDSA` and secp384r1 test PKI match.
* Subject DN carries `UID=<entity MRN>`, `O=<org MRN>`, `OU=service`, `C=<country>`,
  `CN=<name>`; MCP-specific SAN otherName OIDs additionally carry MRN, permissions,
  flag state, etc. (We only parse the subject UID — sufficient and spec-aligned.)
* Certificates **must embed OCSP and CRL pointers** (G1183; the MIR does this). Note the
  go-live doc's flag: SECOMLib peers tolerate an *unreachable* responder (soft-fail) but
  not an *absent* pointer.
* Recommended lifetimes (G1183 §5.5): root 10y, intermediate 3y, **end-entity 6 months,
  renewed 2 months before expiry, with fresh keys each time**. The reference MIR's
  default config issues 6-month certs for every entity type. Plan around a ~4-month
  renewal cadence per environment.

### 5.4 Issuance and renewal — the reality check

* **G1183 §5.6 mandates** that MIRs "must allow automatic renewal of certificates using
  an open protocol", citing ACME (RFC 8555), and that software suppliers must support
  unattended renewal. **But no MCP registry implements ACME today** (checked the
  reference MIR source, the live MCC APIs, and DMC's CPS — which even states certificates
  are "manually renewed"). Renewal in practice = *authenticated re-issue* of a new cert.
* The automatable path is the MIR REST API: `POST
  {mir}/x509/api/org/{orgMrn}/service/{serviceMrn}/certificate/issue-new/csr` with a PEM
  PKCS#10 CSR (or the `/oidc/...` twin with a bearer token). Response = PEM cert chain.
  **The CSR's subject is ignored** — the MIR builds the cert from registry data + the
  CSR's public key. This is exactly what T3 implements.
* **The role-model catch** (verified in MIR source): issuing a service cert requires
  `SERVICE_ADMIN`. On the mTLS (`/x509/...`) API, only certificates with `OU=user` get a
  role lookup — a *service's own certificate* authenticates as bare `ROLE_USER` and
  **cannot renew itself**. The working unattended pattern is a dedicated **"robot" user**
  in the org whose permissions map to `SERVICE_ADMIN`:
  1. robot user's cert (mTLS) → `POST .../service/{mrn}/certificate/issue-new/csr` with a
     fresh service CSR;
  2. the robot's *own* cert can also be self-renewed unattended (a user may issue a new
     certificate for itself — verified in `UserController`), so the loop is closed;
  3. both certs are ~6-month-lived, so the renewer maintains two.
* OIDC alternative: an MCP cert can be exchanged for a short-lived OIDC token
  non-interactively (`cert2oidc` Keycloak client), but there is **no client-credentials
  grant / standing API token**, so the robot-user + mTLS route stays the simplest.
* Caveat to confirm per provider: whether scripted issuance by a robot credential is
  acceptable under their CP/CPS (DMC's current CPS wording implies manual renewal) — §7.

### 5.5 Where to get identities

| Environment | What | Notes |
|---|---|---|
| MCC **test environment** | `https://test-management.maritimeconnectivity.net` (portal; apply at `/#/apply`), API/Keycloak at `test-api.` / `test-maritimeid.` (inferred from live hosts; confirm on registration) | Free, open to "any relevant maritime stakeholders", explicitly non-operational. Own CA chain. The right place for Niord's automated-renewal E2E testing. |
| MCC **public demonstrator** | `https://management.maritimeconnectivity.net`, API `https://api.maritimeconnectivity.net` | The "main" testbed instance (operated by the MCC host members, endorsed by IALA). Own CA chain. |
| **IHO SECOM test instance** | Being stood up by DMC for IHO members to test SECOM/S-100/S-124 dissemination (announced May 2026) | Directly aimed at our use case; worth tracking for the NW-DK context. |
| **Production** | Endorsed identity service providers, per G1183 + MCC Gen4/5/7 vetting. DMC (Denmark) is formally endorsed; Navelink operates an operational MCP-based registry used by Nordic SECOM services (endorsement status unverified). | For DMA, the realistic path is identities from an endorsed provider; the org-level decision (which provider, who owns the org registration) is DMA's, not Niord's. |
| **SECOM's stance** | IEC 63173-2 requires X.509 from "a PKI" and does *not* mandate MCP — but MCP is the de-facto SECOM PKI, the MIR even implements SECOM's `GetPublicKey` interface, and the SECOM peers we exchange S-124 with use MCP service certificates, the MCP CA chain and the MCP service registry. | We are already de-facto MCP-aligned. |

## 6. What is genuinely irreversible

Only two things in this whole area are genuinely irreversible:

1. **The production service MRN.** Peers configure, subscribe and log against it, and it
   is designed to survive every certificate rotation — permanence is its purpose. It
   also *embeds the identity provider* (the IPID segment of
   `urn:mrn:mcp:entity:<ipid>:<org>:<name>`), so switching provider later changes the
   MRN. Decide it once, when the production identity is registered, together with the
   provider question (§7). Test-environment MRNs are throwaway by construction
   (different IPID and CA chain), so nothing done while practicing is precious.
2. **Private-key hygiene.** Every architectural choice here — Secret vs file vs CronJob,
   Reloader, algorithm, backend — is reversible at the next rotation (~4 months). A
   leaked private key (committed to git, swept into a backup, mailed around) can only be
   revoked, never un-leaked. Hence the default posture: keys exist only in the k8s
   Secrets, are generated fresh at every renewal, and are never reused or copied.

Everything else — trust lists, signature algorithm, renewal backend, even go-live
itself — remains reversible: the SECOM listener can be disabled at any time
(`docs/secom-go-live.md`), and the flag-gated rollout keeps it that way on purpose.

## 7. Remaining work

Context: an MCP **organization already exists**, and SECOM going dark for a few days is
acceptable while it is a test system — so none of this is emergency-driven. The in-repo
automation is complete; what remains is registry-side, organizational, and a few
deferred improvements.

### 7.1 Registry onboarding (the next concrete step)

Human work against the identity provider (procedures in the sysadmin appendix A2):

1. **Org inventory** (~30 min): (a) which environment/provider + portal URL, (b) the org
   MRN, (c) does a Niord service entity already exist, (d) who holds org-admin rights.
2. On the **MCC test environment** (the right place to practice — test MRNs are
   throwaway): create the Niord **service entity** (unversioned; deployment/version
   metadata belongs in the MSR) and the **robot user** with SERVICE_ADMIN-mapped
   permissions.
3. Issue the first certificates manually (portal or CSR) for both the service and the
   robot; install per the appendix.
4. Run a **supervised live renewal E2E against the real MIR** (first proven live on
   2026-07-14 — see §8). The sysadmin page's *Renew Now* button exists for exactly this:
   it forces the full robot+service renewal immediately, without arming the unattended
   schedule.

Production mirrors this with the chosen endorsed provider and folds into go-live
gate 1 (`docs/secom-go-live.md`).

### 7.2 Open decisions

* **Production identity provider** — DMC (endorsed, Danish, runs the IHO SECOM test
  instance) vs Navelink vs the MCP organisation DMA already uses (likely simplest: Niord
  becomes a service entity under DMA's existing MCP org). A DMA-level decision;
  binds only when the production identity is registered.
* **Robot-user acceptability**: confirm the chosen provider's CP/CPS permits scripted
  issuance via a robot credential (DMC's current CPS wording implies manual renewal).
  Decided per provider, together with the choice above.
* **Fleet scale**: if DMA ends up operating several MCP-certified services, consider
  promoting the renewal logic into a shared cert-manager external issuer instead of
  per-app jobs (see the sidebar) — a strategy question, not a Niord one. `McpMirClient`
  stays behind an interface so the swap remains a drop-in.

### 7.3 Deferred improvements (revisit on demand)

* Truststore minimum-days-to-expiry gauge (the T1 gauge covers the service certificate
  only).
* Per-IPID trust resolution (G1183 PKI1.2) if we ever trust multiple identity providers
  at once; today a single shared truststore approximates it.
* Truststore management UI (the scripted routine in T4 covers current needs).
* Test-fixture modernization: fixture MRNs use the legacy `urn:mrn:mcp:service:…` type
  (current scheme uses `entity`); `producer-code`, store passwords `changeit` and
  keystore alias `1` are conventions rather than problems. Align opportunistically.
* ACME support if MIRs ever implement it (G1183 formally requires it; none do today) —
  `McpMirClient` is the seam, and cert-manager could then replace the in-app client
  outright.

## 8. Risks / traps to keep in view

* **Truststore alias trap**: `niord.secom.truststore.root-alias` must match the actual
  alias or SECOM signature validation fails on *every* request (documented in code and
  in the appendix).
* **Issuing-CA-not-just-root trap**: the proxy forwards only the leaf; the app truststore
  needs the intermediate(s). Same for nginx `ssl_verify_depth 2` — correct for
  root→intermediate→leaf, would fail deeper chains.
* **SHA2/SHA3 fleet split**: outgoing algorithm is a per-consumer move gate (go-live gate 3);
  renewal doesn't change it, but new certs + old peers is exactly when it bites.
* **Key rotation and persisted signatures** — nothing to do: a published dataset version
  keeps the signature it was published with and records the chain that made it, so after a
  rotation every exchange set that ships or cancels it carries the rotated-away certificate
  and its MCP issuing chain — verifiable, not merely matchable
  (`docs/s124-secom-spec-gap-analysis.md` §3). The chain comes from the keystore entry,
  completed from the truststore: keep the issuing CA in one of them, or exchange sets fall
  back to the leaf alone. Renewal automation makes rotation *frequent* (4-monthly), so a
  long-lived warning will routinely be served under a certificate that is no longer current;
  if a consumer turns out to reject a signature whose certificate has since *expired*,
  republish the same bytes as a new version under the current certificate.
* **Clock skew**: new certs are valid from "now"; a skewed peer clock rejects freshly
  rotated certs. Renewal 60 days early makes this a non-issue except at first issuance.
  Found live (first real-MIR renewal, 2026-07-14): the MIR mints NotBefore at *its*
  issuance instant — seconds after the run's captured "now" — so the verification gate
  tolerates a bounded future NotBefore (10 min); the stub MIR's backdated fixtures never
  showed this.
* **Robot credential is a second secret to protect** — it can mint identities. Scope it
  to one org, store it like the keystore, and rotate it on the same loop (T3 does).

## 9. Sources

* IALA G1183 Ed 1.1 "The provision of MCP identities" (Dec 2024) — read in full; §4 MRN
  scheme, §5.2 decentral PKI, §5.3 crypto, §5.5 validity, §5.6 renewal, §5.8 GetPublicKey.
* MCP docs: `https://docs.maritimeconnectivity.net` (MIR, MRN, useMCPInstance — CA chain
  download URLs), management portals + live Swagger (`https://api.maritimeconnectivity.net/v3/api-docs`), all checked 2026-07-11.
* MIR reference implementation source (`github.com/maritimeconnectivity/IdentityRegistry`,
  v1.3.9 2026-06): `ServiceController`/`UserController` @PreAuthorize rules,
  `X509HeaderUserDetailsService` OU=user role lookup, deprecation of versioned services.
* MCP-PKI library (`github.com/maritimeconnectivity/MCP-PKI`, 1.4.2 on Maven Central).
* DMC Certificate Policy / CPS v1.0 (2025) — endorsed-provider practice: 6-month client
  certs renewed 2 months early, manual renewal wording.
* IALA MCP test instance page (`iala.int/technical/mcp-test-instance`); MCC news re the
  IHO SECOM test instance (May 2026).
* The 2022 "MIR reference implementation" deck (maritimeconnectivity.net) — still
  architecturally accurate; superseded on: Java versions, versioned services
  (deprecated), legacy MRN types, single-testbed assumption, and the since-added SECOM
  publicKey endpoint.
* In-repo: `org.niord.secom.pki.*`, `docs/secom-go-live.md`,
  `docs/s124-secom-spec-gap-analysis.md`, `k8s/` chart + scripts.
