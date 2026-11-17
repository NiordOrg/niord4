# S-124 SECOM service: spec conformance

**Implementation:** `org.niord.secom` + `org.niord.s124`
**Specs compared against** (the working group's drafts of 16 June, tracked changes accepted; unpublished, so not in this repository, `docs/specifications/README.md`):

| Document | Version |
|---|---|
| Service Design for the Provision of NW to End-users using SECOM | v0.995 ("the design") |
| Service Specification for the Provision of NW to End-users, no-S128 variant | v0.996 ("the spec") |
| Service Specification for the Provision of NW to End-users, S-128 variant | v0.996 |

The two spec variants are identical except for how the "keep the consumer complete" duty is
met: the no-S128 variant makes the S-124 in-force bulletin optional (*"The service **may**
provide an S-124 in-force bulletin dataset"*), while the S-128 variant adds **S-124R010:
the service must provide an S-128 dataset in parallel**, updated on every issue/update/
cancellation. Cancellation information (fileless dataset cancellation, 365-day window) is
required in **both** variants (R010 in no-S128, R011 in S-128).

---

## Executive summary

The SECOM protocol layer, the S-124 2.0.0 payload generation, the exchange-set packaging
and the cancellation flow (auto-minted cancellation warnings + §17-4.4.1 fileless dataset
cancellations) are conformant (§1, §3). In-force bulletins / S-128 are **parked by
decision** (§2), which de facto targets the no-S128 spec variant — fully satisfied on the
"keep the consumer complete" axis. What stays open: a handful of deliberate deviations
and coordination items (§4–§5) and the spec-internal inconsistencies flagged to the WG
(§6). The one substantive coordination item is the peer-fleet signature rollout (§4),
part of the SECOM go-live (`docs/secom-go-live.md`, gate 3).

## 1. Cancellations

When a published NW of an auto-numbered series is editorially cancelled, Niord mints a
numbered, born-published cancellation warning (S-124 §9.3 method 2; design in
`docs/s124-cancellation-plan.md`). This is unconditional — there is no per-message and no
per-series opt-out. The source dataset row flips to `CANCELLED` with a
stamped cancellation time, its final GML (carrying the in-band `cancellationDate`) is
published as a new signed version and pushed vicinity-aware, and every subsequently built
exchange set announces it per §17-4.4.1 (see §3).

Deliberate scope choices:

- `CANCELLED` dataset rows are **kept indefinitely** (individually retrievable by
  dataReference, use case 2); only their fileless-cancellation catalogue entries age out of
  the 365-day window. Revisit if a data-retention policy is ever required.
- Every dataset row carries a published version from the transaction that created it (the
  schema was restructured in place before cutover, so no row predates the record), and a
  published version is never re-signed: the signature is the consumer's matching key. The
  cancellation entry reproduces the entry of the dataset's *current* (final) version.

## 2. In-force bulletin / S-128 — parked

**Bulletins are parked by decision:** no S-124 in-force bulletin and no parallel S-128
dataset, which de facto targets the **no-S128 spec variant**. That is spec-coherent: in
the no-S128 variant the in-force bulletin is a *may*, and keeping consumers complete via
subscription push + cancellation information is an accepted mechanism (R004) — both
halves of which are implemented (§1/§3). Nothing mandatory is missing on this axis.

Reopen triggers:

- DMA electing the **S-128 variant**, whose R010 makes the parallel S-128 dataset a *must*
  regenerated on every issue/update/cancellation — a whole new data product (S-128
  catalogue generation, its own MRN/UUID, capability entry).
- Operations wanting the **bulletin failsafe** (PS Ed 2.0.0 §9.2.1: absence from the
  latest in-force bulletin as the cancellation failsafe for long-lived warnings).

The `BULLETIN` message kind and the `S124SeriesConfig` plumbing remain reserved for that
day (see `docs/s124-cancellation-plan.md`, out-of-scope list).

## 3. Exchange sets: fileless cancellation + 365-day catalogue entries

Get/GetSummary/Subscription/Upload serve and push S-100 Ed 5.0 Part 17 exchange sets
(containerType 1, also the default when unspecified) with Part 15 signatures
(per-dataset + catalogue, ECDSA-384-SHA2), built by `S124ExchangeSetService` on the
NiordOrg bindings library's `S124ExchangeSetFactory` (`s124-2_0_1-xml-bindings`); bare
`S100_DataSet` GML remains available on explicit request for legacy peers.

The §17-4.4.1 / R010–R011 mechanics: each dataset version is signed **once when
written** (publish/update/cancel), inside the one exchange-set build that packages it, and
what that build shipped — the file bytes, the file name, the discovery-metadata entry with
its signature, and the chain that signed it — is persisted as an immutable
`S124DatasetVersion`. Every served exchange set ships the current version verbatim (the
bindings' `RetainedDataset` input, 0.3.3): the same bytes under the same, stable
per-dataset signature (a fresh ECDSA signature would differ per request) and the very entry
a later cancellation reproduces. Every built exchange set appends `purpose=cancellation`
discovery-metadata entries for the datasets cancelled within the previous 365 days
(excluding datasets shipped in the same set): the current version's entry reproduced
verbatim with the **original signature** and no dataset file, and the cancellation date as
issueDate — S-100 5.2.0 clause 17-4.4.1 is explicit: *"with the exception of the issueDate,
which must be set to the issue date of the fileless cancellation itself"* (the 5.2.0 change
log lists this as a clarification added to the clause). Nothing is ever re-signed: the
reconciliation sweep publishes a new version only where the regenerated file differs from
the current one, and a certificate rotation changes nothing about published versions (see
below).

**Certificate rotation vs. reused signatures — closed** (bindings 0.3.0, 2026-09-06). A
cancellation entry whose original signature was made under a rotated-away certificate
travels with that certificate: the factory registers it in the catalogue under its own id
and points the reused signature's `certificateRef` at it (S-100 Part 15 §15-8.7), so the
entry is verifiable, not merely matchable. Covered by
`S124ExchangeSetServiceTest.supersededCertificateSignatureIsStillAnnouncedAndVerifiable`.
With unattended renewal rotating the certificate ~4-monthly
(`docs/mcp-identity-certificates-plan.md` §8) this is the normal path for every entry
older than the current certificate.

**Full certificate path — closed** (2026-09-10). Exchange sets carry the MCP issuing chain
between the service certificate and the MCP root — the Domain Coordinator of Part 15 §15-8.7,
so the consumer validates the path "without any external access" with nothing but the root
it has installed — and name that root as the `schemeAdministrator` instead of the factory's
`IHO` default, which MCP certificates are not issued under. `McpSecurityService` establishes
the path from the keystore entry's stored chain (what the MIR issues and the renewal job
stores), completed from the truststore when the keystore holds the leaf alone. Each version
records the chain that signed it (`signingCertificates`, leaf first), so a version signed
under a rotated-out certificate — shipped as a file by a Get, or announced by a fileless
cancellation — travels with that certificate *and* its chain; the factory carries a
certificate it already has once.

**Rotation — nothing to re-sign** (2026-09-10, bindings 0.3.3). A published version is
never touched after the rotation: it keeps shipping under the signature it was published
with, referencing the rotated-out certificate the exchange set carries, while the catalogue
around it is signed by the current one. "Same signature everywhere" therefore holds across
every path, including a cancelled dataset retrieved individually (use case 2). The one thing
to keep an eye on: a warning that stays in force past the *expiry* of the certificate that
signed it keeps that signature too — should a consumer reject a signature whose certificate
has since expired, the remedy is to publish a new version of the same bytes under the current
certificate, which the version model accommodates without any further schema change.

## 4. Signature algorithm: peer-fleet coordination

Design §3.3.3: *"The algorithm for signing envelope and the payload is **ECDSA-384-SHA2**."*
Outgoing signatures default to the mandate (the `secomSignatureAlgorithm` setting, set
`SHA3_384_WITH_ECDSA` to revert), and incoming signatures are accepted in the declared
algorithm plus either 384-bit ECDSA variant — the filter envelopes have no field to declare
one, so `SecomSignatureFilter` silently substitutes our own default, and trusting only that
would reject a correct signature from half the ecosystem. Incoming envelope CSVs are also
accepted without the CD3-deprecated trailing `envelopeSignatureReference` field the GRAD
`CsvStringGenerator` still renders (it is `@JsonIgnore`, so on an incoming request it is
always an empty trailing column a post-CD3 signer omits; data signatures get no such
leeway). Both tolerances were diagnosed against live AMSA `/v2/object/search` traffic
(2026-09-01) and are worth raising upstream at GRAD. Open: **legacy SHA3-only peers
cannot verify our SHA2 signatures** until they upgrade or add the same tolerance — a
per-consumer gate at go-live (`docs/secom-go-live.md`, gate 3), or configure SHA3 per
deployment until then.

## 5. Protocol-level deviations and open items

- **Initial in-force set on subscribe:** spec §6.2 says the in-force set *should* be
  transmitted automatically at subscription initiation; design §7.3.2 says the opposite
  (initial retrieval via Get, subscription delivers only updates). We follow the design
  (no initial push). Flagged to the WG — see §6.
- **UN/LOCODE filter** rejected with 400; the spec lists it as a search parameter the
  provider "shall process". Rejecting loudly is defensible but non-conformant to the
  letter; honoring it needs a UN/LOCODE gazetteer (~22 MB lookup table) Niord does not
  carry.
- **Encryption of warnings** (S-124R009 second half: encryption support "when intermediates
  are used"): not implemented — consistent with the design, which fixes
  `dataProtection=false`; spec/design tension, nothing to do until the design changes.
- **MSR self-registration** (spec §4.1.9, design §4.3): we *search* the registry
  (`SecomOutboundClient.resolveEndpoint`) but never register/update our own instance —
  an ops/deployment task, fine as long as it is done manually (recorded in
  `docs/secom-go-live.md`).
- **Affected charts are not encoded.** In S-124 2.0's schema (`xsd/124_2.0.0.xsd` of the bindings jar the pom
  pins, which `S124XsdValidator` validates against) `chartAffected` requires an `editionDate` after the
  `chartNumber` (`chartPlanNumber` and `lastNoticeDate` are optional), and `affectedChartPublications` requires a
  `language`. A Niord chart has no edition date, and the mapper set no language, a second cause of the old
  failure: a chart with an edition date would have failed too. So the mapper writes no `affectedChartPublications`
  for any chart (owner, 2026-10-23, kept on 2026-10-24: one rule for all charts). Until then every NW naming a
  chart failed the schema and got no dataset (S124-1, found by the regenerated IT fixture, C4b). A dataset now
  names a chart only where the message's own text does.
  - The alternative considered: `affectedChartPublications` also offers `internationalChartAffected` (the
    international paper chart number, a plain string) and `chartPublicationIdentifier` (an MRN), each valid with
    only `language` beside it. Niord's `Chart` has `internationalNumber`, which 31 of the IT fixture's 172 charts
    carry (chart `141` is INT 1370), so `{ language, internationalChartAffected }` could name those charts with no
    new data on `Chart`. The owner kept the drop on 2026-10-24.
  - For S-124's production readiness, together: that alternative for the charts with an international number,
    and an optional edition date on `Chart`, emitted as `chartAffected` with `language` set. A chart plan number
    is no substitute for the edition date.
- **secomlib 0.1.0** is pinned; the design was updated against
  IEC CD 63173-2 (2026 Committee Draft) and "will be updated upon final publication" —
  expect a coordinated lib bump.

## 6. Spec-internal inconsistencies raised with the WG

Distilled into the feedback letter `docs/s124-spec-wg-feedback.md`:

1. `productVersion` = **2.0.0** in Get/GetSummary (design §6.3.5/§6.4.5) but **2.2.0** in
   Subscription (design §6.5.4). Our subscription matching accepts 2.0.0 or unset, and the
   subscribe endpoint **rejects any other productVersion with a validation error** — so a
   subscriber literally following §6.5.4 (2.2.0) fails loudly at subscribe time instead of
   being accepted and silently never receiving a push.
2. Initial in-force set on subscribe: spec §6.2 ("should automatically be transmitted")
   vs design §7.3.2 ("initial retrieval … using the Get interface; subscription delivers only
   subsequent updates").
3. "Only active warnings" via Get without temporal extent (spec §4.1.3/4.1.4) vs use case 4
   ("including cancellation information" in the same response).
4. The service-spec identification table still says version 0.993 while the document is
   circulated as 0.996; the design (0.995) says it implements Technical Specification 0.995.
5. Timestamp precision: design §6.3.5 prescribes `yyyy-MM-ddTHH:mmZ` (minute precision) for
   Get/GetSummary `validFrom`/`validTo`, while §6.5.4 uses `yyyy-MM-ddTHH:mm:ssZ` for the
   subscription period — and SECOMLib, the shared conformance implementation, parses
   `yyyy-MM-dd'T'HH:mm:ss` with an *optional* offset everywhere. A consumer literally
   sending the §6.3.5 minute-precision form is rejected with 400 by every SECOMLib-based
   service (ours included). §6.3.5 should say `yyyy-MM-ddTHH:mm:ssZ`.

---

## 7. Transport and identity in the CDV (added 2026-10-14)

IEC CDV 63173-2 (2026, vote closing 2026-11-13) changes the transport model the
implementation was built on. What the text says, and where the implementation stands:

| CDV | Implementation today | Gap |
|---|---|---|
| Foreword, change h): "remove requirements of mTLS by converting HTTP GET requests to HTTP POST by adding envelopes". | `MrnCertificateFilter` takes the peer certificate from the `X-Secom-Cert` header that the chart's former nginx listener set from the TLS handshake. The chart no longer has that listener (`secom-design.md` D3), and the filter ignores the header unless `niord.secom.trust-cert-header=true` (default `false`; the chart never sets it; the ITs do), so in a deployed stack every request but ping is refused, whatever a caller puts in the header. | **Open, blocking.** The filter must take the certificate from the request's signed envelope (Table 5 `publicCertificate`, leaf first), verify the signature with it, then run the same chain, validity and revocation checks. Ping stays open. |
| Clause 4.3.3: TLS "covered by certificates generated by trusted Internet CAs"; identity in "the SECOM envelope which includes data signing with a certificate provided by PKI for SECOM". | `SecomOutboundClient` offers our MCP certificate as a TLS client certificate and validates peer server chains against the MCP truststore. | An Edition 2 peer presents a public-CA server certificate, which the MCP truststore does not cover. Server validation needs the platform trust store (or both); offering a client certificate is harmless. |
| Get, GetSummary, Access, Subscription and the others that carried no body become POSTs with an envelope (change h, c). | The v2 resource serves `GET /v2/object`, `GET /v2/object/summary`, `GET /v2/capability` as SECOMLib 0.1.0 defines them. | The protocol surface follows the SECOMLib line. A library release on the CDV is the natural vehicle; until then the deviation is the library's, recorded in `secom-go-live.md` ("secomlib bump"). |
| Change g): `publicKey` in the Capability response. | Not served. | Follows the library bump. |
| Clause 6.2: revocation by CRL or OCSP in the authentication procedure. | Soft-fail OCSP→CRL on inbound and outbound, `secomRevocationCheck`. | None. |
| Table 5: the signer's chain, leaf first, mandatory in every signature object. | Signatures carry the signing certificate; chain handling is SECOMLib's. | Verify the library emits the chain, not only the leaf, once bumped. |
| Clause 10.5 still reads "since SECOM uses mutual authentication"; clause 6.1 still speaks of a client certificate being exchanged. | — | Leftovers that contradict change h). Worth a WG comment before the vote closes. |

The k8s test client (`k8s/secom-test-client/`) authenticates with a TLS client
certificate and so cannot subscribe to the plain endpoint until it signs envelopes; the
subscribe rehearsal (`deploy.sh --secom-client`) waits for the first row.

The platform side is done: devops-niord's SECOM runbooks and devops-shared's network
pages describe SECOM as ordinary HTTPS, and devops-shared `docs/mTLS-support-removed.md`
holds the record of the former setup with the CDV clauses quoted.
