# S-124 SECOM service documents: editorial feedback for the working group

**From:** Danish Maritime Authority (Niord / SECOM S-124 service implementation)
**Date:** 2026-07-10
**Documents reviewed** (16 June drafts, tracked changes accepted):

| Document | Version |
|---|---|
| Service Design for the Provision of Navigational Warnings to End-users using SECOM | v0.995 ("the design") |
| Service Specification for the Provision of Navigational Warnings to End-users, no-S128 variant | v0.996 ("the spec") |
| Service Specification for the Provision of Navigational Warnings to End-users, S-128 variant | v0.996 |

While implementing a provider-side S-124 SECOM service against these drafts we found five
internal inconsistencies where the documents currently point implementers in different
directions. Each item below quotes the conflicting passages verbatim and proposes a
resolution. None of them are objections to the substance of the documents — they are
requests for the next revision to pick one reading so that independently developed
providers and consumers interoperate.

---

## 1. `productVersion`: 2.0.0 in Get/GetSummary vs 2.2.0 in Subscription

- Design §6.3.5 (Get): *"The productVersion is the specific version of the data product
  specification of the data requested and shall be set to **2.0.0**."*
- Design §6.4.5 (Get Summary): *"productVersion: shall be set to **2.0.0**"*.
- Design §6.5.4 (Subscription): *"productVersion shall be set to **2.2.0**"*.

The 2.2.0 in §6.5.4 is the only occurrence of that number in all three documents; every
other product-version reference is 2.0.0, matching the S-124 product specification draft
v2.0.0 (2019) cited in the design's reference list. This is presumably a typo, but it has
a real interoperability consequence: a provider that matches subscriptions on the
advertised product version will silently never deliver to a consumer that subscribes with
the literal 2.2.0 from §6.5.4 (our implementation matches 2.0.0 and treats an unspecified
version as a wildcard; a strictly §6.5.4-conformant consumer would receive nothing).

**Proposal:** change §6.5.4 to 2.0.0, and state the intended behavior for version
mismatches (reject the subscription vs. accept-and-never-match).

> **Defensive behavior implemented in Niord (2026-07-10):** the subscribe endpoint now
> rejects any `productVersion` other than 2.0.0/unset with a SECOM validation error, so a
> consumer following the literal §6.5.4 fails loudly at subscribe time instead of being
> accepted and silently never receiving a push.

## 2. Initial in-force set on subscription: spec §6.2 and use case 3 vs design §7.3.2

- Spec §6.2: *"The initial set of Navigational Warnings that are in-force should
  automatically be transmitted at the initiation of a subscription."* Spec use case 3
  (§3.3.3) states the same: *"The service will then send all active warnings to the user
  system."*
- Design §7.3.2: *"Initial retrieval of datasets is expected to be performed using the
  Get interface (see Clause 7.2). The subscription mechanism delivers only subsequent
  updates."* Design §6.5.2 likewise: *"it is recommended that a service consumer first
  retrieves all active NWs using the Get interface, then subscribes for updates."*

All four passages are recommendations ("should", "expected", "recommended") rather than
mandates, but they steer implementers in opposite directions: a consumer written against
the spec expects the full in-force set to arrive after subscribing; against the design it
is expected to Get first. Both patterns are workable, but the documents should agree —
otherwise a design-conformant provider (like ours: no initial push) looks broken to a
spec-conformant consumer.

**Proposal:** align the spec with the design (Get first, subscription = updates only),
which avoids large unsolicited pushes at subscribe time — this requires amending both
spec §6.2 and use case 3; alternatively define the initial push explicitly (ordering,
pagination, acknowledgement expectations).

## 3. "Only active warnings" without a temporal extent vs "including cancellation information"

- Spec §4.1.3 (Request Warning) and §4.1.4 (Get Summary): *"Only active warnings shall be
  provided through this interface if the user does not specify a temporal extent."*
- Spec use case 4 (§3.3.4) expects the response to include *"cancellation information"*
  in the same no-temporal-extent retrieval.

As written, use case 4 can be read to mean that cancelled warnings appear in the active
result set — which §4.1.3/§4.1.4 exclude. The requirements section already points at the
resolution: S-124R010 "Provision of cancellation information" (numbered S-124R011 in the
S-128 variant) mandates cancellation information *"in accordance with S-100 Section
17-4.4.1 (fileless dataset cancellation)"* — catalogue entries accompanying each provided
dataset, not cancelled warnings in the result set. Our implementation follows that
reading: it serves only active warnings (cancelled datasets remain retrievable by
dataReference or an explicit temporal window), with the §17-4.4.1 catalogue entries as
the carrier of cancellation information (emitting these entries for datasets cancelled
within the 365-day window is implemented on our side).

**Proposal:** add explicit cross-references to the cancellation-information requirement
(S-124R010; S-124R011 in the S-128 variant) and S-100 §17-4.4.1 in use case 4 and
§4.1.3/§4.1.4, so that "cancellation information" cannot be read as cancelled warnings
appearing in the active result set.

## 4. Version identification: the service-spec tables still say 0.993

The identification tables in both spec variants state version **0.993** (ID
`urn:mrn:iho:techsvc:ss:navwarn:0.993`) while the documents are circulated as **0.996**.
Because the two variants carry identical Name, ID and Version rows, they are also
mutually indistinguishable by their identification tables. The design (0.995) already
carries a pairing row ("Implements: Technical Specification for the Provision of
Navigational Warnings to End-users, Version 0.995"), but the spec has no equivalent row
pointing back at the design. Trivial, but the identification table is exactly what an
implementer cites in conformance claims and MSR registrations.

**Proposal:** update the identification tables in both variants with the circulated
version, give the no-S128 and S-128 variants distinct identification, and add a pairing
row to the spec — mirroring the design's "Implements" row — stating which design version
it pairs with.

## 5. Timestamp precision: §6.3.5 prescribes a format SECOMLib-based services reject

- Design §6.3.5 (Get): *"The validFrom and validTo are the validity time for the
  requested data in ISO8601 UTC formatted as yyyy-MM-ddTHH:mmZ."* (minute precision)
- Design §6.5.4 (Subscription): *"The time period should be expressed in ISO 8601 format:
  yyyy-MM-ddTHH:mm:ssZ (UTC)."* (second precision)

Beyond the internal mismatch, SECOMLib — the shared conformance implementation — parses
`yyyy-MM-ddTHH:mm:ss` (seconds required, offset optional) for all temporal parameters, so
a consumer literally sending the §6.3.5 minute-precision form is rejected with HTTP 400
by every SECOMLib-based service, ours included.

**Proposal:** change §6.3.5 to `yyyy-MM-ddTHH:mm:ssZ`, aligning it with §6.5.4 and with
SECOMLib's parsing behavior.

<!-- Internal note, remove before submission: distilled from
docs/s124-secom-spec-gap-analysis.md ("Spec-internal inconsistencies", section 6) in this
repository. -->
