# SECOM / S-124 go-live checklist

Companion to [secom-design.md](secom-design.md); the certificate/identity side (incl.
registry onboarding) is [mcp-identity-certificates-plan.md](mcp-identity-certificates-plan.md).

## Where things stand

**The software side is complete; what remains is external coordination.** Niord's
embedded SECOM service is feature-complete for the producer role: stable
dataReferences, honored subscription filters, real push with a durable acknowledged
outbox, cancellation warnings + fileless dataset cancellations, exchange sets, in-app
chain + revocation validation, hot-reloadable keystore with unattended certificate
renewal, failure alerting, Micrometer gauges, and tests. The k8s chart wires the SECOM
secrets and renewal machinery, all off by default. What separates "ready" from "done"
are the gates below.

SECOM Edition 2 (IEC CDV 63173-2, foreword change h and clause 4.3.3) takes a peer's
identity from the certificate in the signed envelope, not from a TLS client certificate.
A SECOM request is ordinary HTTPS on the environment's public hostname, through the
same ingress and proxy as the web traffic. The app still reads identity from the
`X-Secom-Cert` header the old listener set, and trusts it only where the deployment says
so (`niord.secom.trust-cert-header`, default `false`, never set by the chart) — so a
deployed stack refuses every request but ping; moving the identity to the envelope is the
open item in `docs/s124-secom-spec-gap-analysis.md` §7 and precedes B1.

## Rollout model: own identity, clean slate, flag-gated

Niord's SECOM service goes live with its **own MCP service certificate** and its own MSR
instance entry, and starts with an **empty subscription store**: consumers subscribe
themselves, at their own pace (Get first, then subscribe — service design §6.5.2).
Rollback is a flag: disable the listener (`secom.enabled=false` in the chart /
`niord.secom.enabled=false` in the app) and the node disappears; nothing else in Niord
depends on it. Dataset identities are independent of the service certificate:
dataReferences/MRNs derive from the *warnings* (design D5), so a later certificate
rotation never changes them.

## S-124 off

`niord.s124.enabled` (default `true` in `niord-core-defaults.yml`; the S-124 opt-out, the
owner 2026-11-01) is the parent of `niord.secom.enabled`. It is a boot property: a restart
applies it.

- **Off** removes `S124Service` and `S124RestService`, so `/rest/S-124/**` (the messages and
  the XSDs) answers 404, and the producer code is neither used nor checked: a deployment
  without S-124 needs none. The generic `niord-app` is the demo application and writes
  `false`; DK and GL write `true`.
- **Off with SECOM on is refused at start-up.** `org.niord.s124.S124SwitchCheck` runs before
  any singleton is created (a `BeanFactoryPostProcessor`) and stops the start with a
  message that names both keys (`niord.secom.enabled=true needs niord.s124.enabled: …`),
  rather than an unsatisfied dependency. The chart refuses the same pair when its values set both
  (`k8s/README.md` § S-124 on or off).
- **The automatic cancellation warning stays** whatever the switch says
  (`s124-cancellation-plan.md` decision 2, unchanged by the opt-out): cancelling a published
  NW in an auto-numbered series still mints the warning.
- **The frontends are told** through `/rest/settings/web`, which always carries the booleans
  `s124Enabled` and `secomEnabled`, computed from the running properties and never stored. A
  stored `secomEnabled` row is overridden by the computed value. The administration app's
  SECOM entry reads `secomEnabled`; its dictionaries page leaves the S-124 agency reader out
  of the delete confirm when `s124Enabled` is false.
- **Switching off deletes nothing.** `S124Dataset` rows, subscriptions and the outbox stay in
  the database, unread while the beans are absent.
- **Switching on later** mints no cancellation warnings after the fact: none is missing, since
  they were minted while S-124 was off.

## Go-live gates 1–6 (external coordination)

Registry onboarding — service entity, robot user, first issuance
(`docs/mcp-identity-certificates-plan.md` §7.1) — precedes gate 1.

- **Gate 1 — MCP service certificate.** Have a certificate issued for the Niord instance
  via the MCP identity registry. Load the material as the `niord-secom-stores` secret
  (base64 or binary; the truststore must hold the CA(s) that *issue* peer certificates). Verify the production certificate carries OCSP/CRL pointers (design
  R3b) — SECOMLib's signed-POST validation and our outbound checking rely on them
  (soft-fail tolerates an unreachable responder, not an absent pointer;
  `secomRevocationCheck=false` is the fallback knob). Later rotations need no restart —
  changed store files are picked up automatically within a minute or two, with a
  confirmation mail to the `secomAlertEmails` recipients (`POST /rest/secom/security/reload`
  forces it immediately), and unattended renewal can take over entirely.
- **Gate 2 — MSR registration.** Niord searches the registry but does not self-register
  (manual ops, by design for now): register an instance entry for the Niord endpoint. Set
  `secomServiceRegistryUrl` so Niord can resolve its subscribers for pushes. Niord pushes
  to a subscriber only through the registry entry whose `instanceId` is the subscriber's
  own MRN, and only to an `https` endpoint on a public address; leave
  `secomAllowInsecureEndpoints` at its default `false` in production (it exists for the
  in-cluster rehearsal below, which pushes over plain HTTP).
- **Gate 3 — Signature algorithm: per consumer, not a flag day.**
  (`docs/s124-secom-spec-gap-analysis.md` §4.) Niord signs with the mandated
  ECDSA-384-**SHA2** by default; incoming signatures are accepted in either 384-bit
  variant regardless. A consumer that can only verify SHA3-384 either upgrades before it
  moves to Niord, or the deployment sets `secomSignatureAlgorithm=SHA3_384_WITH_ECDSA`
  until it has. Any consumer talking to Niord is implicitly SECOM-v2-capable.
- **Gate 4 — Enable + backfill.** Expose the production series
  (`PUT /rest/secom/series-config/{seriesId}` or the sysadmin SECOM page; the same row
  carries the optional per-series language restriction - leave it empty unless a series
  must publish in fewer languages than the model languages), run
  `POST /rest/secom/datasets/reconcile` (idempotent; safe to re-run), watch the outbox
  drain and the `niord.secom.outbox` gauges. Set `secomAlertEmails` so terminal delivery
  failures page somebody (one mail per failed transaction until an admin retries it — a
  failed or cancelled entry stays so, so a peer repeating its NACK, with or without acks in
  between, does not repeat the mail). Two sweep effects to expect: the first sweep after a deployment
  that changes the generator's output re-versions every exposed dataset and pushes it, and
  changing a series' language restriction re-issues that series' active datasets the same
  way — bounded to the series, and what the sysadmin page says it does.
- **Gate 5 — Subscriptions.** Consumers subscribe to the instance themselves: Get first,
  then subscribe (service design §6.5.2). Niord rejects `productVersion` ≠ 2.0.0 at
  subscribe, so stale client configurations fail loudly instead of silently receiving
  nothing. A node may hold at most `secomMaxSubscriptionsPerNode` subscriptions (default
  50; further non-identical requests get a 400) — raise it for a consumer that genuinely
  needs more. Track uptake via `GET /rest/secom/subscriptions` and the
  `niord.secom.subscriptions`/`niord.secom.nodes` gauges.
- **Gate 6 — Observe.** Watch ≥1 publish and ≥1 cancellation flow to a real subscriber
  (upload + acknowledgement in the outbox; fileless cancellation in the next exchange
  sets). Also run one **supervised automatic certificate renewal** against the real MIR
  if not already done during onboarding (the sysadmin page's *Renew Now* button forces
  it on demand).

None of the gates is a point of no return: every one of them is walk-back-able by
disabling the listener. The only irreversible part is the identity's key material
(identity plan §6).

## Rehearsing locally (OrbStack k8s, committed test PKI)

The in-repo rehearsals prove everything short of a real peer, and are runnable at any
time:

- **`deploy.sh --secom`** — the SECOM endpoint + admin plane: `GET /v2/ping` → 200;
  capability/GetSummary/Get with a signed request from the test identity → correct S-124
  responses (empty-store Get → 404 per the empty-Get rule) once the app reads the envelope
  (§7 of the gap analysis; until then 403); an unsigned request → 403; a forged
  `X-Secom-Cert` header is ignored by the app (`trust-cert-header` is off) and dropped by
  the proxy besides → 403; the admin plane with a sysadmin JWT (status incl. the certificate fingerprint,
  series-config upsert, reconcile); the app loads both stores from the mounted secret
  (base64-tolerant); a disabled redeploy returns the chart to its pre-SECOM behavior. On the
  chart's default image `niord-app-en`, which switches S-124 off and has no producer code,
  pass both: `EXTRA_HELM_ARGS="--set s124.enabled=true --set s124.producerCode=XX00"
  ./scripts/deploy.sh --secom` (the same for `--secom-client`); without them the app refuses
  to start, naming both keys. `secom-renewal-rehearsal.sh` sets `EXTRA_HELM_ARGS` itself and
  puts both keys, with the rehearsal code `XX00`, in the values file it generates.
- **`deploy.sh --secom-client`** (`k8s/secom-test-client/`) — a live subscriber loop,
  **waiting for the envelope work**: the minimal SECOMLib-based consumer still presents
  its identity as a TLS client certificate, which the plain endpoint never sees, so its
  subscribe is refused (403, retried every 10 s) until the client signs envelopes and the
  app reads them (§7 of the gap analysis). What it rehearses once a subscription exists:
  an NW created + published through the editor REST API reaches it within the dispatcher
  minute as a signed Part 17 exchange set, the client verifies the envelope + payload
  signatures (ECDSA-384-SHA2, as declared) and acknowledges — outbox `UPLOAD → ACKED`;
  an editorial cancellation pushes the final `CANCELLED` version the same way. What gate
  6 adds on top of this rehearsal: a *real* consumer, the production certificate, and
  outbound HTTPS to the peer (the rehearsal pushes over in-cluster plain HTTP because the
  test PKI cannot issue peer server certificates — `--secom-client` therefore sets
  `secomAllowInsecureEndpoints=true`, which production leaves off).
- **`k8s/scripts/secom-renewal-rehearsal.sh`** — the unattended-renewal loop end to end
  against a stub MIR: secret rotation → store-watch adoption → robot-then-service
  renewal → Secret PATCHes → renewed-material pickup (`docs/mcp-identity-certificates-plan.md` §3.7).
- **The SECOM IT suite** covers the dataset production/push/cancellation data plane
  (exchange-set Get, outbox dispatch to a stubbed peer, acknowledgement handling) on
  every build.

## Languages and the S-124 bindings (niord-xml-bindings)

Datasets carry the message texts in every model language the message has content in
(`docs/secom-design.md` §2.1), and no bindings change was needed for that: the S-124 v2.0.0
schema types already hold any number of language-tagged information entries, titles and
location names, and the exchange-set factory (`S124ExchangeSetFactory.Builder.locales`) takes
the locale list Niord now passes — primary language first — and declares it as the catalogue's
`defaultLocale`/`otherLocale`. When it picks one location name for the catalogue it takes the
one in the first locale, so the primary must lead the list, which `S124ExchangeSetService`
guarantees on publish and on serve (the union of the shipped versions' languages, first
dataset's primary first; `S124DatasetVersion.languages` records each version's list so
serving never parses GML).

**One bindings change is pending** (verified against 0.3.3 by
`S124ExchangeSetServiceTest.publishedEntriesDeclareTheDatasetLanguagesAsWell`, whose
assertions arm themselves by assumption once it lands): the factory does not copy the
build's locales into the per-dataset
`S100_DatasetDiscoveryMetadata` entry's own `defaultLocale`/`otherLocale` (S-100 Part 17,
Table 17-3; optional, so the entries stay schema-valid without them). The entry is the
factory's to build — Niord deliberately stores it verbatim rather than mirroring the
factory's metadata rules — so the fix belongs in `S124ExchangeSetFactory`, released through
JitPack and bumped in `niord/pom.xml`. Until then consumers read the languages from the
catalogue level and from the dataset header's `datasetLanguage`. Not a go-live blocker.

## Deferred by design (not go-live blockers)

- **MSR self-registration** — manual ops step (gate 2); a registry-write integration is
  future work.
- **UN/LOCODE filter** — deliberately rejected with 400 (no gazetteer); WG-flagged.
- **Initial in-force push on subscribe** — follows the design (Get-first), not spec §6.2;
  WG-flagged (`docs/s124-spec-wg-feedback.md`).
- **secomlib bump** — pinned to 0.1.0 until IEC CD 63173-2 is published and the fleet
  coordinates.
- **S-128 / in-force bulletin** — parked; the no-S128 spec variant is fully satisfied
  (`docs/s124-secom-spec-gap-analysis.md` §2 lists the reopen triggers).
