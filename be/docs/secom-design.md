# SECOM / S-124 integration — Design

Scope: Niord itself is the SECOM node that serves and pushes S-124 navigational-warning
datasets. The service is embedded in the Niord Spring application — the `org.niord.secom`
package of the `niord` base module, bootable through `niord-app`/`niord-dk` — not a separate
deployable. The go-live checklist is [secom-go-live.md](secom-go-live.md); the
certificate/identity side is [mcp-identity-certificates-plan.md](mcp-identity-certificates-plan.md).

---

## 1. Background

### 1.1 What the service does

Niord makes navigational warnings available to the e-navigation world as **S-124 datasets over
SECOM** (IALA/IHO secure comms standard, IEC 63173-2), authenticated with **MCP** (Maritime
Connectivity Platform) PKI:

- Produces an S-124 dataset for every eligible published navigational warning, in-process, from
  the same generator that backs `GET /rest/S-124/messages/{id}` (`org.niord.s124.S124Service`).
- Serves that data over the SECOM interfaces (Capability, Ping, Get, GetSummary, Subscription,
  RemoveSubscription, Acknowledgement) implemented with the GRAD **SECOMLib**, on the **SECOM v2**
  line of the library (`org.grad.secom:secom-v2-core-jakarta` + `secom-v2-springboot3` 0.1.0,
  java package `org.grad.secomv2`, endpoint paths `/v2/*`; the older v1 line — `secom-core-jakarta`
  0.0.50, `/v1/*` paths — is not supported).
- Pushes new, updated and cancelled datasets to subscribers through a durable outbox.
- Client identity: the certificate in the peer's signed envelope (SECOM Edition 2, IEC CDV
  63173-2 clause 4.3.3 and Table 5); a filter validates it against the MCP truststore and
  extracts the MRN from the cert's `UID` field. *Today* the filter still reads the
  `X-Secom-Cert` header the chart's former TLS listener set (D3).
- Signs outgoing payloads with its MCP service certificate (keystore.p12 / truststore.p12) and
  resolves subscriber MRNs to endpoints via the MCP Service Registry (MSR).

### 1.2 What Niord provides (the seams the service builds on)

| Seam | Where | Relevance |
|---|---|---|
| S-124 GML generation | `niord/src/main/java/org/niord/s124/S124Service.java`; REST at `/rest/S-124/messages/{id}` (`S124RestService`) | The generator — called in-process, no HTTP hop |
| Message lifecycle events | `MessageService.updateStatus()` broadcasts a JMS map message `{ID, UID, STATUS, PREV_STATUS}` on topic `messageStatus` (`niord.jms.topic.messagestatustopic`) | The publish/cancel/expire trigger. Two existing consumers show the pattern: `TwitterMessageListener`, `MailingListMessageListener` |
| Durable outbox precedent | `org.niord.core.mail.ScheduledMail` + `ScheduledMailService` (status/attempts/lastError, cron every minute, bounded executor, `@Setting`-tuned rate) | The model for the SECOM outbox |
| Geometry | JTS + PostGIS everywhere (`Feature.geometry`, `JtsConverter`, `GeoJsonUtils.computeBBox`) | Dataset geometry from `message.toGeoJson()` natively — no GML re-parsing needed to recover geometry |
| Config | `@Setting` (DB-backed, admin-editable) for tunables; `application.yml` for deployment values | Keystore paths → yml/secrets; cadence/backoff → settings |
| Security | Single Spring Security chain: HTTP-layer permit-all + JSR-250 method security + Keycloak JWT + `NiordDomainFilter`; guard test `RestControllerSecurityAnnotationTest` forces explicit `@PermitAll`/`@RolesAllowed` on every MVC handler | Admin API slots straight in; SECOM endpoints get their own authentication (below) |
| Scheduling | Spring `@Scheduled` (the bootable applications enable scheduling) | Dispatcher + reconciliation jobs. The dispatcher runs on Boot's scheduler, whose four threads every `@Scheduled` job shares (`spring.task.scheduling.pool.size` in `niord-core-defaults.yml`, SCHED3), so each outbound exchange is bounded by an overall deadline (30 s, §5.1 Outbound; a delivery can take two, the registry search and the POST, and a run makes up to `secomOutboxMaxPerRun` deliveries): a slow peer can no longer hold its thread indefinitely |
| Liquibase | `niord/src/main/resources/db/changelog/db.changelog-master.yaml`; runs everywhere (tests, local, k8s) | The SECOM changeset `V003__secom.sql` |
| Admin UI | The sysadmin area of the administration app, `fe/ui-apps/admin/src/app/features/admin/sections/sysadmin/` | The SECOM section mirrors the other sysadmin sections |
| Deployment | k8s Helm chart with single-origin nginx proxy | nginx forwards `/api/secom` like everything else and holds no certificate |

### 1.3 Design goals

1. **Event-driven, transactional dataset production**: datasets are created exactly when a
   message is published/cancelled/expired, with stable identity — no polling of the public API.
2. **Real SECOM semantics**: stable `dataReference` UUIDs, working GetSummary, honored
   subscription filters, geometry/time filtering against PostGIS.
3. **Push, not just serve**: subscribers get pushed new warnings *and cancellations*, with durable
   retry and acknowledgement tracking.
4. **One deployable, one database, one ops story** (Niord's k8s, Keycloak-secured admin,
   Liquibase, monitoring), one admin UI — and no coupling to Niord's public REST API (including
   no dependence on the unauthenticated S-124 endpoint).

### 1.4 Non-goals

- **Receiving** datasets over SECOM Upload (the consumer role).
- Data **encryption/compression** SECOM options (advertised off).
- New S-100 products (S-201/S-125) — no speculative SPI is kept for them (§3.1); extract the
  interface when a second product actually lands.

---

## 2. Target architecture

```
                     Niord Spring app
 ┌───────────────────────────────────────────────────────────────────────┐
 │                                                                       │
 │  MessageService.updateStatus() ──JMS topic "messageStatus"──┐         │
 │                                                             ▼         │
 │   org.niord.s124.S124Service          SecomMessageStatusListener      │
 │   (existing FM generator)  ◄───────── S124DatasetService              │
 │                                        │  • eligibility + MRN + UUID  │
 │                                        │  • persist s124_dataset      │
 │                                        │  • enqueue secom_outbox      │
 │   S124ReconciliationJob (@Scheduled) ──┘  (backfill + self-healing)   │
 │                                                                       │
 │  ┌─ SECOM protocol adapter (RESTEasy servlet at /api/secom/v2/*) ───┐ │
 │  │  MrnCertificateFilter (envelope cert → chain check → MRN)        │ │
 │  │  Capability | Ping | Get | GetSummary | Subscription |           │ │
 │  │  RemoveSubscription | Acknowledgement   (GRAD SECOMLib v2)       │ │
 │  └───────────────────────────────────────────────────────────────── ┘ │
 │                    │ reads                     ▲ acks                 │
 │                    ▼                           │                      │
 │   s124_dataset / secom_subscription / secom_node / secom_outbox      │
 │                    ▲                           │                      │
 │   SecomOutboxDispatcher (@Scheduled) ──────────┘                      │
 │     │ resolve MRN → endpoint (MSR, cached)                            │
 │     └─ GRAD SecomClient (HTTPS + payload signing, MCPSecurityService) │
 │                                                                       │
 │  SecomAdminRestService (/rest/secom/**, @RolesAllowed("sysadmin"))    │
 └───────────────────────────────────────────────────────────────────────┘
        ▲ HTTPS via nginx, identity in the           │ outbound HTTPS
        │ signed envelope                              ▼
   SECOM consumers (ECDIS shore systems, VTS, …)   MCP Service Registry + subscriber nodes
```

No separate Maven module (D2) — the code lives in the existing reactor as a new package,
`org.niord.secom`:

```
reactor root
 ├── niord      (niord-base — org.niord.secom.** [protocol, dataset store, outbox, admin
 │               REST] + the GRAD/RESTEasy deps; the org.niord.s124 generator; the
 │               Liquibase changeset and the SECOM tests)
 ├── niord-app  (the generic bootable app — unchanged shape)
 └── niord-dk   (the Danish bootable app — unchanged shape)
```

Everything SECOM is gated on **`niord.secom.enabled=false`** (default): with the flag off, no
servlet registration, no JMS consumer, no schedulers, no admin endpoints — the tables exist
(Liquibase always runs) but stay empty. This is the rollback story: the flag, not a module
boundary, is the real on/off switch (the classes ship in the fat jar either way).
`niord.secom.enabled` needs **`niord.s124.enabled`** (default `true`; the S-124 opt-out, the owner
2026-11-01): SECOM exchanges S-124 datasets, so start-up refuses S-124 off with SECOM on, naming
both keys (`org.niord.s124.S124SwitchCheck`; `secom-go-live.md` § S-124 off).

### 2.1 Dataset production flow

```
editor publishes message
  → MessageService.updateStatus (TX: status, promulgation, flush, JMS publish)
  → SecomMessageStatusListener receives {UID, STATUS=PUBLISHED}
      → new TX: re-read message by UID, verify status & eligibility
      → dataset = org.niord.s124.S124Service.generate(uid, lang)      (in-process, no HTTP)
      → mrn = S124Mrn.of(message, country) (same expression the generator uses; country = the country setting)
      → uuid = MrnToUuid(mrn)              (SHA-256 → RFC 9562 UUIDv8, deterministic)
      → unchanged file? (regenerated XML == current version's bytes) → done, nothing pushed
      → S124ExchangeSetService.publish: one exchange-set build signs the typed dataset;
        append an immutable S124DatasetVersion (file bytes, file name, catalogue entry with
        its signature, signing chain) and point s124_dataset (status, geometry, validity)
        at it - a signing failure fails the whole write
      → enqueue one secom_outbox row per matching subscription (filters honored)
  → SecomOutboxDispatcher (each minute): claim each due row (a short committed
      transaction that leases it, D6), send UploadObject
      via SecomClient with DELIVERED_ACK_REQUESTED; backoff on failure (a peer that
      does not complete an exchange within the 30 s deadline counts as a failure)
  → subscriber POSTs /api/secom/v2/acknowledgement → outbox row marked acked/opened/error
```

Eligibility (mirrors what the generator supports + adds publication scoping):
`mainType == NW && number != null && status == PUBLISHED && series exposed via S124SeriesConfig`
(per-series `exposeOverSecom` rows, closed by default ⇒ nothing is exposed until ops opts
series in — see `docs/s124-cancellation-plan.md`).

Languages: a dataset carries the message texts — part bodies, titles, locality and area names,
each a language-tagged entry — in every model language the message has content in, narrowed by
the series' `languages` restriction (`S124SeriesConfig`). The dataset language is English when it
is among them, else the first remaining model language; `S124Service.languagesFor` orders the
candidates and `S124Mapper` drops the ones without content, reporting what it emitted
(`Generated.languages`, primary first). The exchange set declares that list as its catalogue
locales (`S124ExchangeSetService`), and each version records it (`S124DatasetVersion.languages`)
so serving never parses GML. Nothing is configured per node — the former `secomS124Language`
setting is gone; the anonymous `/rest/S-124` endpoint and the editor preview take a preferred
language, which leads when the message has it.

Cancel/expire flow: on `CANCELLED`/`EXPIRED`, regenerate the dataset (the S-124 preamble carries
cancellation semantics) and publish it as the final version, stamp `valid_to`, mark the dataset
row `CANCELLED`, and push the final version to matching subscribers. Cancelled rows and every
version are retained indefinitely and stay queryable (a retention policy is an open question,
Q4). Editorial cancellations additionally mint a *cancellation warning* — a separate
message/dataset (`docs/s124-cancellation-plan.md`).

Two safety nets close the JMS gaps (topic messages are fire-and-forget, sent just before commit,
and lost if the app is down):

- The listener re-reads the message and trusts the DB, not the event payload (the event is only a
  "look now" hint) and retries shortly if the row doesn't yet reflect the announced status.
- The reconcile sweep in `S124DatasetService` (@Scheduled, `niord.secom.reconcile-cron`): diff eligible
  published messages vs dataset rows — create missing, cancel orphaned, publish a new version
  where the regenerated file differs from the current one. Nothing is ever re-signed: a
  version's signature is over its own bytes and travels with the chain that made it, so a
  certificate rotation or a bindings upgrade leaves published versions as they are. The same
  code path is the **backfill** for first enablement and the manual
  `POST /rest/secom/datasets/reconcile`.

### 2.2 SECOM serve flow (Get/GetSummary)

Straight queries against `s124_dataset` (no live rendering, no draft access possible by
construction): filter by `dataReference` (UUID), `ST_Intersects` for geometry, validity
window overlap, product type/version; real pagination. Payloads are served as S-100 Part 17
exchange sets by default, shipping each dataset's current `S124DatasetVersion` verbatim — the
file bytes, the catalogue entry and the signature exactly as published (the bindings'
`RetainedDataset` input, 0.3.3) — so nothing is parsed, re-marshalled or re-signed per request;
only the catalogue around it is built and signed fresh. GetSummary reads the dataset
identification (gml:id, file identifier, title) captured on the version at publish time.

---

## 3. Key design decisions

### D1 — Use GRAD SECOMLib for the protocol edge, mounted as an isolated RESTEasy servlet

**Decision:** use `secom-v2-core-jakarta` (interfaces, models, envelope/signature
filters, exception mappers) and the `SecomClient` from `secom-v2-springboot3`, but **do not use
the `resteasy-servlet-spring-boot-starter` autoconfiguration** (verified in the 0.1.0 pom: the v2
module still declares it, and it targets Boot 3; Niord is on Boot 4). Instead mount RESTEasy's
`HttpServlet30Dispatcher` ourselves via a
`ServletRegistrationBean` at `/api/secom/*` with an explicit `jakarta.ws.rs.core.Application`
listing our resources + the SECOMLib providers (~50 lines, deterministic, no autoconfig
surprises).

Why not write the seven endpoints as Spring MVC controllers, which is what the rest of the
codebase uses?

- The conformance-sensitive parts are not the routes — they're the **byte-level behaviors**:
  envelope signing/verification, SECOM timestamp formats, exchange-metadata population, SECOM
  error body shapes. SECOMLib encodes interop lessons from the actual SECOM testbeds; hand-rolling
  those risks silent cross-vendor breakage that only surfaces in scheduled interop tests.
- Niord is on **Jackson 3** (tools.jackson); SECOMLib models/serializers are Jackson 2. Inside an
  isolated RESTEasy servlet they use their own Jackson 2 provider chain and never touch Niord's
  MVC converters. A MVC implementation would have to bridge that — real friction for zero
  protocol gain.
- The servlet is a bounded protocol adapter: Spring Security still fronts it (filter order), the
  rest of the app stays 100 % Spring MVC. JAX-RS API (`jakarta.ws.rs-api` 4.0) is already a
  declared dependency (Keycloak client), and RESTEasy client jars are already on the classpath via
  `keycloak-admin-client` — `resteasy-core` is aligned to that version.

**Escape hatch:** the conformance ITs exercise the endpoints at the HTTP level (§7). Those tests
are exactly the safety net that would make a later MVC rewrite safe, if we ever want to drop the
second stack — harden the tests first, then swap.

### D1a (§3.1) — Simplifications applied on review

Applied to keep the code free of speculative abstractions:

- **No multi-product SPI at all.** No `S100DataProductService`-style manager/dataset/exception
  scaffolding for a hypothetical S-125 future; S-124 is wired directly. When a second S-100
  product actually arrives, extract the interface then — the seams (capability building, dataset
  queries) are single methods.
- **One resource class.** `SecomV2Resource` implements all seven SECOM interfaces.
- **UNLOCODE filtering not supported.** It would need a ~22 MB gazetteer, and nothing in the DK
  context uses it. Get/GetSummary/subscription answer a `unlocode` parameter with a SECOM
  validation error instead.
- **No dataset-reference join table.** The GML itself carries the references; nothing would
  consume a DB-level linkage.
- **Registry endpoint cache is a 10-minute constant**, not a tunable setting; the outbox knobs
  (`secomOutboxMaxAttempts`, `secomOutboxBackoffMinutes`, `secomOutboxMaxPerRun`) are
  `@Setting`s, and series exposure/language are runtime config so ops can change the exposure
  scope without a restart (§5.4).
- **Servlet is lazily initialized** (not load-on-startup): `ServletRegistrationBean`s are
  instantiated while Tomcat is still starting, before JPA exists — the JAX-RS application
  therefore resolves its Spring beans on the first SECOM request (D1 implementation detail).
- Status mapping: all certificate-authentication failures are `SecomNotAuthorisedException`
  (→ 403); missing acknowledgement-envelope attributes are `SecomValidationException` (→ 400).

### D2 — No sub-module: `org.niord.secom` package in the existing `niord` module

The deployable is one Spring Boot fat jar either way, so a module never reduces the runtime
footprint — the `niord.secom.enabled` flag is the real gate. The module's only genuine benefit
(keeping GRAD/RESTEasy deps off `niord-base`'s classpath for *other* consumers) is theoretical in
this fork: nothing outside this reactor consumes `niord-base`, and `niord/pom.xml` already
carries a custom-repo dependency (`com.github.NiordOrg:josm-seachart` via JitPack), so the GRAD
repo (declared **after** Central and **before** JitPack per the root pom's ordering rule) follows
existing practice.
Skipping the module also keeps the reactor layout and the test tree in the house shape, which an
extra module would have split awkwardly.

Placement: code + SECOM/RESTEasy deps in `niord` (next to the existing `org.niord.s124` and
`org.niord.web`; the security guard test scans there and picks up the admin controller
automatically), along with the Liquibase changeset and the tests; the sysadmin page is the
administration app's SECOM section (`fe/ui-apps/admin/src/app/features/admin/sections/sysadmin/secom/`).
Beans carry `@ConditionalOnProperty(name = "niord.secom.enabled",
havingValue = "true")`; entities are always scanned (harmless — Liquibase owns the schema
regardless).

What the module boundary would have enforced at compile time, a guard test enforces instead: a
plain classpath-scanning unit test (same style as `RestControllerSecurityAnnotationTest`)
asserting that only `org.niord.secom.**` imports `org.grad.**` — so SECOM types can't creep into
core code (`SecomImportBoundaryTest`). Two accepted cosmetic consequences: `jakarta.ws.rs`
imports appear in `niord` prod sources at the protocol boundary (inherent to D1 wherever the
code lives), and extraction into a real module later stays mechanical (self-contained package +
its own deps).

### D3 — Identity: the certificate in the signed envelope, validated in-app

**Revised 2026-10-14.** SECOM Edition 2 (IEC CDV 63173-2) removed the mutual-TLS
requirement (foreword, change h). Clause 4.3.3: the transport is "standard TLS protection
that is covered by certificates generated by trusted Internet CAs", and identity is "the
SECOM envelope which includes data signing with a certificate provided by PKI for SECOM".
Table 5 makes the signer's certificate chain, leaf first, a mandatory part of every
signature object. So:

- The chart's nginx has no SECOM listener. `/api/secom` is forwarded on the plain vhost
  like every other path; the proxy holds no certificate and strips any `X-Secom-Cert`.
  In a cluster, TLS ends at the ingress controller with an ordinary public-CA certificate.
- The identity check stays in-app, in `MrnCertificateFilter`: chain against the MCP
  truststore, validity window, soft-fail OCSP/CRL revocation, MRN from the subject `UID`.
  What changes is the *source* of the certificate: the signed envelope of the request
  instead of a header. **Open:** the filter still reads the header, so in a deployed stack
  every request but ping is refused until it reads the envelope
  (`s124-secom-spec-gap-analysis.md` §7). Ping stays open, as the spec wants.
- The header is only a source of identity when the deployment vouches for it:
  `niord.secom.trust-cert-header` (an application property, default `false`) says a
  TLS-terminating proxy verifies the client certificate, sets the header, and is the only
  way to reach the app. With the default the filter ignores the header whoever sent it.
  The guard is in the app, not in nginx: the proxy's strip protects only the paths that
  pass through it, and an app-only release, a pod in the namespace reaching
  `niord-app:8888` or a future direct route would not. The chart never sets the property.
- A NetworkPolicy on the app pods is hygiene, not a precondition: a pod reaching the app
  directly gains nothing from a forged header. That changes the day
  `trust-cert-header` is switched on behind a verifying proxy — then the policy is what
  keeps every other path off the app, and is a precondition.

The original design, kept as the record of why the filter reads a header today:

Proxy-terminated mTLS was how the k8s chart fronted Niord with nginx:

- nginx SECOM vhost (dedicated hostname/port): `ssl_verify_client optional` against the MCP CA
  chain — presented certificates are proxy-verified, and the CertificateRequest still advertises
  the CA DN (which `optional_no_ca` would not); `proxy_set_header X-Secom-Cert
  $ssl_client_escaped_cert` → app. `optional` rather than `on` because `GET /v2/ping` is served
  **certless** (the spec's liveness probe); the app 403s every other endpoint without a forwarded
  certificate. The plain vhost 404s `/api/secom` outright, so the mTLS listener is the *only*
  route to the servlet.
- All other vhosts/paths **strip** `X-Secom-Cert` (header-spoof hygiene, enforced in the chart).
- In-app `MrnCertificateFilter` (JAX-RS `ContainerRequestFilter`): parse cert → **validate chain
  against the MCP truststore + validity window** (the proxy check is kept *and* the chain is
  verified in-app — the DB-backed `secomValidateClientChain` setting, with `secomRevocationCheck`
  for soft-fail OCSP/CRL checking, both inbound and outbound) → extract MRN from Subject `UID` →
  request attribute. No MRN ⇒ SECOM 403 on every endpoint except ping.
- Native Tomcat mTLS (second connector + X509 filter) was considered and rejected: more
  moving parts in-app, no existing precedent in the port, and it complicates the single-origin k8s
  story. The filter seam doesn't care where the cert came from, so this can be revisited.

Spring Security: one added `SecurityFilterChain` bean ordered before the default, matching
`/api/secom/**`, `permitAll` + JWT resource-server disabled — SECOM auth is entirely the cert
filter's job, and SECOM traffic never touches `NiordDomainFilter`/Keycloak. (The existing chain is
`anyRequest().permitAll()` at HTTP layer anyway; the dedicated chain makes the separation explicit
and gives the future X509 option a home.) The guard test only sees MVC `@RestController`s — the
admin controller satisfies it with `@RolesAllowed`; the JAX-RS resources are outside its scope
by design, documented in the test's javadoc.

### D4 — Trigger: JMS `messageStatus` listener + reconciliation sweep (no core-code changes)

`PromulgationManager` was considered and rejected as the hook: promulgation types are per-message,
editor-managed, domain-scoped content channels (NAVTEX text, Twitter, …). S-124/SECOM is an
automatic transport of the message itself for every in-scope published NW — subscribing to the
status topic (like Twitter/MailingList listeners already do) requires **zero changes to
`MessageService`**'s eventing and keeps SECOM concerns out of the editorial model. The
reconciliation job (§2.1) compensates for the topic's fire-and-forget nature. If the port ever
gains transactional application events, the listener swaps trivially.

The listener and the sweep's units are two writers of the same dataset rows. Each locks the row
(a `PESSIMISTIC_WRITE` row lock) before it reads the message, so they take turns and the later one
reads what the earlier one committed. The row has no version column; before the lock (until PR
#322), a writer that had read the published warning just before a cancel committed wrote its ACTIVE
row over the CANCELLED one, and the cancelled warning was served and pushed as in force until the
sweep's cancellation pass or the next status event put it back.

A row lock needs a row, so before it each writer takes the message's dataset lock: a
transaction-level advisory lock (`pg_advisory_xact_lock`, key space `0x5124` and the hash of the
message UID), and only then looks the row up. Without it, a sweep unit that found no row and was
creating one when the warning was cancelled committed an ACTIVE row after the listener's
CANCELLED handler had found no row and done nothing; the cancelled warning was served and pushed
as in force until the same sweep's cancellation pass. Now the handler waits for the unit and
cancels its row. Only the dataset writers take this lock: a cancel or an edit of the message, and
every reader, never waits for it. The cancellation pass (`cancelIfStale`) takes only the row lock;
it never creates a row.

### D5 — Dataset identity: one row per warning MRN, deterministic UUIDv8

- MRN: the template's expression (`urn:mrn:iho:nw:<country>:<shortId lowercased>`, the country token
  from the `country` setting, lower-cased) is extracted into a shared `S124Mrn` helper,
  `S124Mrn.of(message, country)`, so the DB key and the GML `warningIdentifier` can never diverge.
- UUID (`dataReference`): SHA-256 of the lower-cased MRN packed as an RFC 9562 UUIDv8
  (`MrnToUuid`). Deterministic ⇒ stable across updates and re-publications ⇒ Get-by-dataReference
  and subscription `dataReference` filters work; also idempotent under reconciliation replays.
  The bit layout is frozen by a golden-value test: the deployed SECOM ecosystem has already seen
  these exact UUIDs.
- A published message **update** appends a new immutable `S124DatasetVersion` (same MRN, same
  UUID, the row's geometry/validity/`updated` refreshed and its pointer moved), and re-enqueues
  to subscribers. The versions are the history of exactly what was signed and served when; the
  outbox rows additionally snapshot each delivery's payload.
- References: carried inside the generated GML itself (the S-124 template renders the message's
  references); there is no DB-level reference join table (§3.1).

### D6 — Durable outbox, modeled on the mail queue

One table (`secom_outbox`): each row is one operation to one node, carrying its SECOM
`transactionIdentifier`, payload snapshot, attempt counter, `next_attempt_at`, status
(`PENDING → SENT → ACKED/OPENED`, or `FAILED`/`CANCELLED`), and ack/nack timestamps — so the
Acknowledgement endpoint is a single ownership-checked UPDATE. The dispatcher runs every minute,
as `ScheduledMailService` does, with `@Setting`-tuned rate/backoff/max-attempts (defaults: 20 per
run; 1 m, 5 m, 30 m, 2 h, 12 h; 8 attempts).

It sends one entry at a time on its scheduler thread and claims each just before it sends it, in a
short transaction of its own that commits before anything is sent (`SecomOutboxService.claimNextDue`):
the oldest entry due by the run's start is read `FOR UPDATE SKIP LOCKED`, and its `next_attempt_at`
is moved five minutes ahead, a lease. A second run at the same moment (a second replica's) passes
over an entry being claimed instead of waiting for it, and sees no claimed entry as due, so each entry
is sent once. The lease outlasts the longest delivery several times over (a registry search and the
POST, each bounded by the 30 s deadline); claiming one entry at a time keeps it that short, where a
lease over a whole run would have to cover `secomOutboxMaxPerRun` deliveries. Recording the outcome
replaces the lease: a sent entry leaves `PENDING`, a failed one gets its backoff. A run that dies after
a claim (the pod killed mid-send) records nothing, and its entry is due again when the lease runs out
and is sent by the next run; if the first send had reached the peer, the peer gets the upload twice,
with one `transactionIdentifier`. An entry that falls due during a run (a backoff, a new entry) waits
for the next run, and an admin retry makes an entry due at once, a leased one too. The lease is
reckoned on the claiming node's clock, as the backoff is, so the replicas' clocks must agree to well
within five minutes. The mail queue claims differently: it locks each mail `FOR UPDATE` (no
`SKIP LOCKED`) inside the transaction that sends it, so a second sender waits on the lock and then
sees the mail sent.

Terminal failures and NACKs additionally queue an alert mail to the
`secomAlertEmails` recipients — one per transaction until an admin retries it: an entry that
has failed or been cancelled stays so whatever the peer sends afterwards (a later ack stamps its
time but leaves the status, a later NACK is ignored), and a delivery attempt that fails after the
entry has left `PENDING` is only counted. Subscription notifications are enqueued, never sent inline in the
subscribe transaction.

### D7 — Persistence idiom: EntityManager + named queries, not Spring Data

The Niord port deliberately hasn't adopted Spring Data yet (that's the separate
persistence-modernization track). The SECOM code follows the house `BaseService`/EntityManager
idiom so it doesn't preempt that decision.

### D8 — S-124 edition: preserve today's bytes first, upgrade second

Don't conflate this with the SECOM **v2** line: SECOM v2 is the *transport/API* edition (URL
paths, envelopes, signature SPI); the S-124 edition is the *payload* format inside the envelope.

The integration emits exactly what `org.niord.s124.S124Service` emits (golden-file tested); an
edition change is a contained follow-up behind the same generator seam. Capability advertises
the product version actually emitted (2.0.0, on the NiordOrg `s124-2_0_1-xml-bindings`).

---

## 4. Data model (Liquibase `V003__secom.sql`)

Core shape:

```
secom_node                          -- remote SECOM/MCP peers we have seen
  id            BIGSERIAL PK
  mrn           VARCHAR(256) NOT NULL UNIQUE
  last_interaction TIMESTAMPTZ         -- feeds /v2/ping
  created       TIMESTAMPTZ NOT NULL

secom_subscription
  id            UUID PK               -- = SECOM subscriptionIdentifier
  node_id       BIGINT FK → secom_node NOT NULL
  data_product_type VARCHAR(32) NOT NULL         -- 'S124'
  product_version   VARCHAR(16)                  -- null = any
  container_type    VARCHAR(32)
  data_reference    UUID                         -- null = all datasets
  geometry      geometry(Geometry,4326)          -- parsed WKT; null = anywhere
  wkt           TEXT                             -- original kept for display
  period_start  TIMESTAMPTZ
  period_end    TIMESTAMPTZ
  created       TIMESTAMPTZ NOT NULL
  INDEX (node_id)

s124_dataset
  id            BIGSERIAL PK
  uuid          UUID NOT NULL UNIQUE             -- deterministic from MRN (D5)
  mrn           VARCHAR(256) NOT NULL UNIQUE
  message_id    BIGINT FK → message NOT NULL     -- provenance (no cascade)
  status        VARCHAR(16) NOT NULL             -- ACTIVE | CANCELLED
  current_version_id BIGINT FK → s124_dataset_version  -- the version served; set in the same tx
  geometry      geometry(Geometry,4326)          -- from message features, SRID 4326
  valid_from    TIMESTAMPTZ
  valid_to      TIMESTAMPTZ
  cancelled     TIMESTAMPTZ                      -- drives the 365-day cancellation window
  created       TIMESTAMPTZ NOT NULL
  updated       TIMESTAMPTZ NOT NULL
  INDEX (message_id), GIST INDEX (geometry), INDEX (status, valid_from, valid_to)

s124_dataset_version                            -- immutable: written once per publish/update/cancel
  id            BIGSERIAL PK
  dataset_id    BIGINT FK → s124_dataset NOT NULL
  gml           TEXT NOT NULL                    -- the dataset file exactly as packaged and signed
  file_name     VARCHAR(128) NOT NULL            -- 124<producer><unique code>.GML
  dataset_gml_id VARCHAR(256)                    -- the dataset's gml:id and datasetTitle, captured
  dataset_title VARCHAR(256)                     --   at publish for GetSummary (no parsing when serving)
  discovery_metadata TEXT NOT NULL               -- the S100_DatasetDiscoveryMetadata entry, with signature
  signature     BYTEA NOT NULL                   -- the Part 15 signature over gml (the consumer's key)
  signing_certificates TEXT NOT NULL             -- the chain that made it, leaf first (Part 15 §15-8.7)
  created       TIMESTAMPTZ NOT NULL
  INDEX (dataset_id)

secom_outbox
  id            BIGSERIAL PK
  transaction_id UUID NOT NULL UNIQUE            -- SECOM transactionIdentifier
  node_id       BIGINT FK → secom_node NOT NULL
  subscription_id UUID FK → secom_subscription   -- null once a subscription is deleted
  operation     VARCHAR(32) NOT NULL             -- UPLOAD | SUBSCRIPTION_NOTIFICATION
  dataset_id    BIGINT FK → s124_dataset         -- null for notifications
  payload       BYTEA NOT NULL                   -- snapshot actually sent (audit trail, D5/D6)
  status        VARCHAR(16) NOT NULL             -- PENDING|SENT|ACKED|OPENED|FAILED|CANCELLED
  attempts      INT NOT NULL DEFAULT 0
  next_attempt_at TIMESTAMPTZ
  last_error    TEXT
  sent_at       TIMESTAMPTZ
  acked_at      TIMESTAMPTZ
  opened_at     TIMESTAMPTZ
  nack_type     VARCHAR(64)
  created       TIMESTAMPTZ NOT NULL
  INDEX (status, next_attempt_at), INDEX (node_id)
```

The authoritative schema is the changeset itself
(`niord/src/main/resources/db/changelog/changes/V003__secom.sql`), which additionally holds:
the `containerType` columns, the `S124SeriesConfig` per-series table, and the core `Message.kind`
column (`docs/s124-cancellation-plan.md`). Why the content lives on immutable versions rather than
on the dataset row: a version's signature is over its own bytes by construction, so there is no
payload hash, no staleness check and no re-signing after a certificate rotation or a bindings
upgrade — every exchange set ships the record verbatim and a fileless cancellation reproduces its
entry (gap doc §3).

Naming/style follows the existing schema (PascalCase quoted tables: "SecomNode", "SecomSubscription",
"S124Dataset", "SecomOutboxEntry"); JPA entities in `org.niord.secom.model` extending the house
`BaseEntity`, with named queries per D7.

---

## 5. API design

### 5.1 SECOM protocol surface (external; MRN-authenticated by the signed envelope; RESTEasy servlet)

Base path `/api/secom`. Paths, payload schemas, and wire formats come from the GRAD **SECOM v2**
interfaces (`org.grad.secomv2.core.interfaces.*`, `SecomConstants.SECOM_VERSION = "v2"`; wire
timestamps are ISO `yyyy-MM-dd'T'HH:mm:ss` — v1's compact `yyyyMMdd'T'HHmmss` is gone). We
implement:

| Method & path | Interface | Behavior |
|---|---|---|
| `GET /v2/capability` | `CapabilityServiceInterface` | S-124 entry: containerType `S100_ExchangeSet`, implementedInterfaces `{get, getSummary, subscription}`, productVersion = emitted edition (D8) |
| `GET /v2/ping` | `PingServiceInterface` | Served certless (liveness); `lastPrivateInteractionTime` from `secom_node.last_interaction`, which every authenticated call updates |
| `GET /v2/object` | `GetServiceInterface` | Query params: `dataReference`, `containerType` (default `S100_ExchangeSet` = S-100 Part 17 exchange set with Part 15 signatures; `S100_DataSet` = bare GML on explicit request for legacy peers), `dataProductType` (default S124), `productVersion` (only 2.0.0 matches), `geometry` (WKT), `unlocode` (rejected with a SECOM validation error — not supported, §3.1), `validFrom`/`validTo` (`Instant`, ISO format), `page`, `pageSize` (capped at 100, the SECOM default; `maxItemsPerPage` advertises the size applied). Serves persisted datasets: stable UUIDs, PostGIS `ST_Intersects`, validity-overlap filter, real pagination + total counts; empty result → 404 (the empty-Get rule). `ExchangeMetadata`: compression/protection false; payload signing via SECOMLib providers. A page's exchange sets are built in one batch that reads the fileless-cancellation candidates once |
| `GET /v2/object/summary` | `GetSummaryServiceInterface` | Same query engine; `pageSize` is capped at 100 as for Get. `info_size` = real byte length (the page's exchange sets are built in one batch, as for Get); `info_identifier` = MRN; `info_lastModifiedDate` = dataset `updated` |
| `POST /v2/object/search` | `PostGetServiceInterface` | The body form of Get — the same ten filters in a signed envelope, for callers whose geometry will not fit in a URL (and what AMSA's `s124-secom-client` uses exclusively). The capability's single `get` flag covers both forms. Unpacks the envelope (required: `envelopeRootCertificateThumbprint`, `envelopeSignatureTime`; signature verified by the SECOM signature filter) and delegates to the URL-parameter method, so the two forms cannot answer the same query differently |
| `POST /v2/object/search/summary` | `PostGetSummaryServiceInterface` | The body form of GetSummary, unpacked and delegated the same way |
| `POST /v2/subscription` | `SubscriptionServiceInterface` | v2 envelope-wrapped request (`SubscriptionRequestObject` → `EnvelopeSubscriptionRequestObject` carries the filters). Persists the subscription **with** its requested filters (productVersion, dataReference, WKT geometry, period); a `productVersion` other than 2.0.0 is rejected with a validation error (stale clients fail loudly instead of silently never receiving a push). Multiple subscriptions per node allowed (spec-conformant), up to `secomMaxSubscriptionsPerNode` (default 50; each subscription gets its own outbox row per pushed dataset version) — beyond it a new request is a SECOM validation error (400); byte-identical duplicates return the existing id, also at the cap. Response: `subscriptionIdentifier`; `SUBSCRIPTION_CREATED` notification (envelope-wrapped in v2) **enqueued** to the outbox |
| `DELETE /v2/subscription` | `RemoveSubscriptionServiceInterface` | v2 signature takes the `subscriptionIdentifier` UUID directly (the v1 body object is gone). Owner-checked (requesting MRN must own it, else SECOM NotFound). Enqueues `SUBSCRIPTION_REMOVED` notification |
| `POST /v2/acknowledgement` | `AcknowledgementServiceInterface` | Envelope validation (required fields, ERROR ⇒ nackType). Resolves `secom_outbox` by `transaction_id` (row-locked), verifies node ownership, stamps `acked_at`/`opened_at`/`nack_type` + status; an entry already `FAILED` or `CANCELLED` keeps its status (an ack only stamps its time, a NACK is logged and ignored), so a transaction raises at most one failure alert whatever the peer sends and in whatever order, and no ack undoes an admin's cancellation. NB: unknown/foreign transactions answer 400 — the v2 acknowledgement interface maps SecomNotFoundException to BAD_REQUEST (unlike Get's 404) |

Not exposed (absent from capability): the rest of the much larger SECOM v2 optional
surface — Upload/UploadLink (consume), GetByLink/PostGetByLink, the async `RetrieveResult`
flow, SearchService, `GetPublicKey`/`UploadPublicKey`, access requests, encryption-key
exchange.
SECOMLib's exception mappers translate `Secom*Exception`s into standard SECOM error responses.

**Outbound** (via `org.grad.secomv2…SecomClient` + MCP keystore): `upload(UploadObject)` with
`DELIVERED_ACK_REQUESTED` and the v2 envelope's `subscriptionIdentifier` set (replaces v1's
`fromSubscription` flag), `subscriptionNotification(...)`, and MSR `searchService(
SearchFilterObject)` → `SearchResult`/`ServiceInstanceObject` for MRN→endpoint resolution
(TTL-cached; the registry client honors the configured truststore — `secomServiceRegistryInsecure`
is a config flag defaulting to off — plus soft-fail OCSP→CRL revocation checking behind
`secomRevocationCheck`). The registry answer is checked before anything is delivered
(`SecomOutboundClient`): only the instance whose `instanceId` equals the peer's MRN
(case-insensitively) counts — there is no first-hit fallback; its `endpointUri` must be an https
URL with a host and without user-info, query or fragment (the SECOM paths are appended to it as a
string); and right before each delivery its host must resolve to public addresses only (no
loopback, link-local, private, unique-local or reserved range — the rule of
`NiordIntegrationUrlValidator.isNonPublicAddress`). The JDK client resolves the host again when
it connects; that check-then-connect window is accepted because deliveries are https-only
(MCP truststore + host-name verification) and redirects are never followed. The
`secomAllowInsecureEndpoints` setting (default off, test environments only) allows plain http and
non-public addresses, for in-cluster rehearsals such as `deploy.sh --secom-client`. Every
exchange, registry search included, has an overall 30 s deadline covering the whole response
body, not just the headers (`HttpRequest.timeout` alone stops at the headers): on expiry the
exchange is cancelled and recorded as a failed attempt with backoff.

**Signing SPI (v2):** providers implement the reshaped `SecomSignatureProvider` — the algorithm is
advertised once via `getSignatureAlgorithm()` (the `secomSignatureAlgorithm` setting, default
`SHA2_384_WITH_ECDSA` per the design mandate) and `validateSignature` receives the full
certificate *chain* (`String[]`, signer first); inbound validation accepts the declared algorithm
plus both 384-bit ECDSA variants (split-fleet tolerance — gap doc §4), over both envelope-CSV
renderings: as the GRAD library computes it and without the CD3-deprecated trailing
`envelopeSignatureReference` field the library still appends (`@JsonIgnore`, so always an empty
trailing column a post-CD3 signer omits — diagnosed against live AMSA traffic, 2026-09-01).
Data signatures get no rendering leeway; a verification failure logs the exact CSV checked plus
any algorithm/rendering combination that would have verified (diagnostic only).

### 5.2 Admin REST API (Niord MVC; Keycloak JWT; all `@RolesAllowed("sysadmin")`)

`SecomAdminRestService` under `/rest/secom` (guard-test compliant; VO classes in
`org.niord.secom.web.vo`, house pagination conventions):

| Method & path | Purpose / response |
|---|---|
| `GET /rest/secom/status` | Node health: enabled flag, service cert subject MRN + expiry/days-to-expiry + fingerprint, truststore root, MSR URL + last resolution result, product versions, row counts (datasets/subscriptions/outbox-by-status) |
| `POST /rest/secom/security/reload` | Hot-swap the keystore/truststore material; returns old/new fingerprints (`docs/mcp-identity-certificates-plan.md`) |
| `GET /rest/secom/series-config` · `PUT/DELETE /rest/secom/series-config/{seriesId}` | Per-series SECOM configuration (`S124SeriesConfig`): exposure flag + language restriction (`languages`, model-language codes; empty = no restriction; a non-model language is a 400). PUT replaces the row as sent |
| `GET /rest/secom/subscriptions?page&size` | Paged subscriptions: id, node MRN, filters (product/version/geometry-WKT/period), created, delivery stats (sent/acked/failed counts) |
| `DELETE /rest/secom/subscriptions/{id}` | Admin removal; enqueues `SUBSCRIPTION_REMOVED` notification to the node |
| `GET /rest/secom/nodes?page&size` | Known peers: MRN, last interaction, subscription + outbox counts |
| `GET /rest/secom/datasets?messageId&mrn&status&page&size` | Dataset instances: uuid, mrn, message uid/shortId, status, validity, sizes, updated |
| `GET /rest/secom/datasets/{uuid}` | Detail incl. references and outbox delivery summary |
| `GET /rest/secom/datasets/{uuid}/gml` | Raw stored GML (`application/gml+xml`) |
| `POST /rest/secom/datasets/{uuid}/republish` | Re-enqueue this dataset to all matching subscriptions |
| `POST /rest/secom/datasets/reconcile` | Run the reconciliation sweep now (= backfill button); returns `{created, updated, cancelled, unchanged, failed}`; `failed` counts the units the sweep could not complete (an eligible message whose GML could not be generated or whose unit threw twice, a stale dataset whose cancellation threw), retried by the next sweep |
| `GET /rest/secom/outbox?status&nodeMrn&page&size` | Outbox rows with attempts, next attempt, last error, ack state |
| `POST /rest/secom/outbox/{id}/retry` | Reset FAILED/PENDING row for immediate dispatch with a fresh schedule (attempts 0, last error and NACK type cleared); any other status → 409 |
| `DELETE /rest/secom/outbox/{id}` | Cancel a pending row (status → CANCELLED); any other status → 409 |

Sysadmin UI: the administration app's SECOM section
(`fe/ui-apps/admin/src/app/features/admin/sections/sysadmin/secom/`; tabs: overview, certificates,
datasets incl. series exposure, subscribers, outbox; its rail entry and overview card ride the
`secomEnabled` value of `/rest/settings/web`, computed from the running `niord.secom.enabled`
and never stored, so it is true exactly where SECOM runs — `docs/s124-cancellation-plan.md`).

### 5.3 Internal SPI (future S-100 products)

None (§3.1). The design originally kept a slimmed `SecomDataProduct` SPI for a
hypothetical S-125/S-201 future; it was cut — the abstraction cost real code without a second
implementation. S-124 is wired directly (`S124DatasetService` + the capability built inline in
`SecomV2Resource`). If a second S-100 product ever lands, extract the interface at that point.

### 5.4 Configuration surface

`application.yml` (deployment concerns; k8s secrets for stores):

```yaml
niord:
  s124:
    enabled: true        # S-124 at all (the default); SECOM needs it, false unmaps /rest/S-124
    producer-code: …     # deployment identity (e.g. DK01), carried by every dataset and exchange set
  secom:
    enabled: false
    keystore:            # MCP service cert + key (PKCS12; path or base64 file)
      location: file:/run/secrets/secom/keystore.p12
      password: …
      alias: …
    truststore:
      location: file:/run/secrets/secom/truststore.p12
      password: …
      root-alias: …
    reconcile-cron: …    # + store-watch-cron, cert-expiry-cron, renewal-cron, outbox-dispatch-cron
    renewal:             # unattended certificate renewal (docs/mcp-identity-certificates-plan.md)
      …
```

`@Setting`s (DB-backed, admin-tunable on the generic Settings page):
`secomSignatureAlgorithm`, `secomValidateClientChain`, `secomRevocationCheck`,
`secomServiceRegistryUrl`, `secomServiceRegistryInsecure`, `secomAllowInsecureEndpoints`
(test environments only: lets deliveries go to plain-http peers and peers on
loopback/link-local/private addresses, §5.1 Outbound), `secomCallbackEndpoint`,
`secomAlertEmails`, `secomOutboxMaxPerRun`, `secomOutboxBackoffMinutes`,
`secomOutboxMaxAttempts`, `secomMaxSubscriptionsPerNode`, `secomCertExpiryWarnDays`, `secomRenewalEnabled`,
`secomRenewalWindowDays`, `secomMirPortalUrl`, `secomEnabled` (declared and kept, add-only, but
`/rest/settings/web` reports the computed value over the row). Per-series exposure and the
per-series language restriction are entity-based (`S124SeriesConfig`), not settings.

### 5.5 Events consumed

JMS topic `messageStatus` (map: `ID`, `UID`, `STATUS`, `PREV_STATUS`) — consumer identical in
shape to `TwitterMessageListener` (single-thread executor, `ApplicationReadyEvent` start,
`ContextClosedEvent` stop), reacting to `PUBLISHED`, `CANCELLED`, `EXPIRED`.

---

## 6. Security model

| Plane | AuthN | AuthZ |
|---|---|---|
| SECOM `/api/secom/**` | the certificate in the signed envelope → in-app chain validation + MRN extraction (D3; today still read from the `X-Secom-Cert` header) | Any valid MCP identity may Get/Subscribe (product data is public-by-publication); ownership checks on subscription removal + acks; ping is open |
| Admin `/rest/secom/**` | Keycloak JWT (existing chain) | `@RolesAllowed("sysadmin")` on the class |
| Everything else | unchanged | unchanged |

Properties worth stating:

- Drafts can never leak over SECOM: datasets are only ever produced from `PUBLISHED` transitions,
  and Get reads only the dataset table. (The unauthenticated `/rest/S-124/messages/{id}` endpoint
  rejects non-public statuses — `S124Service.generateGML` 404s any non-public message.)
- Header hygiene: `X-Secom-Cert` is stripped at the proxy on every route, and no listener
  sets it any more. An IT asserts a forged header gets 403 when chain validation is on.
- **Signed-POST validation is stricter than it looks:** SECOMLib's signature
  filter PKIX-validates the *envelope* certificate of signed POSTs (acknowledgements) with
  revocation checking enabled (`SOFT_FAIL`) — certificates MUST carry OCSP/CRL information (real
  MCP certificates do; an unreachable responder is tolerated, an absent one is not), and the
  truststore alias of the root must be exposed via `SecomTrustStoreProvider.getCARootCertificateAlias()`
  (the library default is the literal alias `"root"`, a silent 4xx-everything trap —
  `NiordSecomProviders` returns the actual resolved alias).
- The keystore private key stays in a k8s secret; the admin `status` endpoint exposes cert metadata
  only (subject, expiry, fingerprint), never key material.
- Multi-domain: scoping is by message series (§2.1), so a misconfigured series exposure is the only
  cross-domain exposure risk; the sysadmin page shows the active scope.

---

## 7. Testing strategy (house conventions)

Unit tests and full-context ITs both live in `niord` (`org.niord.secom.**`), running against the
committed test PKI (`niord/src/test/resources/secom-test/generate-test-pki.sh`). Conformance is asserted at
the HTTP/JSON level — RestAssured plus the SECOMLib model classes and envelope-signing helpers
for request signing — rather than through the GRAD `SecomClient` (which would require webflux +
a TLS server setup in-test); the live `SecomClient` path is instead exercised on the k8s stack
by the `k8s/secom-test-client/` subscriber rehearsal (see secom-go-live.md).

- **Units:** `MrnToUuid` (RFC 9562 v8 bit-freeze golden value, determinism, case
  normalization), `S124Mrn` vs template output, backoff schedule math, PKI/provider round-trips,
  cert-header parsing, WKT parsing errors → SECOM validation errors, the D2 import-boundary
  guard (`SecomImportBoundaryTest`).
- **ITs (PostGIS Testcontainers, house `ITTest` base):**
  - Pipeline (`S124DatasetPipelineITTest`): create+publish a message through `MessageService` →
    dataset row and its first version exist, geometry/validity/references correct; update → same
    UUID, a new version, re-enqueued; cancel/expire → CANCELLED + final version pushed;
    reconciliation creates missing/cancels orphaned rows idempotently and leaves unchanged
    versions alone; series-scoping respected. Versions and cancellation entries
    (`S124FilelessCancellationITTest`); rotation leaves versions as they are and serves them with
    their chain (`SecomSecurityReloadITTest`).
  - Protocol (`SecomProtocolITTest`): full HTTP against the mounted servlet with the test cert
    chain: capability/ping/get/getSummary round-trips incl. exchange-set payloads; pagination and
    every Get filter; negative: missing cert 403, broken chain 403, expired cert 403,
    ExchangeSet/DataSet container selection.
  - Subscriptions (`SecomSubscriptionITTest`): subscribe/remove round-trips, filter persistence,
    productVersion rejection, foreign subscription delete → NotFound.
  - Outbox (`SecomOutboxDispatchITTest`): `HttpStub` as the receiving SECOM node — delivery,
    per-failure backoff progression, terminal FAILED + alert mail, ack/opened/nack transitions,
    foreign-tx ack → 400, retry endpoint, rate limiting; the claims (D6): two runs at once send each
    entry once, a run passes over an entry another run is claiming without waiting, and an entry
    whose run died after the claim is due again when the lease runs out.
  - Security: role matrix on `/rest/secom/**` (sysadmin 200 / admin 403 / anon 401) following
    `RoleAuthorizationMatrixITTest` (`SecomAdminRestITTest`); flag-off smoke
    (`SecomDisabledITTest`): with `niord.secom.enabled=false`, servlet absent (404), no
    consumers/jobs registered.
  - Golden files: fixture message → byte-exact GML snapshot (guards template drift, D8).
  - Certificate lifecycle: reload/watch/renewal suites — see
    `docs/mcp-identity-certificates-plan.md` §3.7.

---

## 8. Risks & open questions

| # | Item | Handling |
|---|---|---|
| R1 | SECOM v2 lib is young (pinned 0.1.0) with less interop mileage than the old v1 0.0.50 line | Conformance ITs catch drift on every upgrade; a coordinated fleet bump is expected when IEC CD 63173-2 is finally published (gap doc §5); watch for HV000151-style sharp edges (parameter constraints must stay inherited from the interfaces, not redeclared) |
| R3 | JMS topic is pre-commit + non-durable → missed/early events | Listener re-reads DB; reconciliation sweep is the source of truth (§2.1) |
| R3b | Production MCP certificates must carry OCSP/CRL info and the truststore must hold the issuing CA(s) — otherwise every signed POST (acknowledgements) fails SECOMLib's PKIX check | Verify against real MCP certs during the go-live pre-flight (test PKI embeds an unreachable OCSP AIA to exercise SOFT_FAIL); go-live gate 1 |
| R4 | GRAD Maven repo availability breaks cold builds; a catch-all repository could answer for its coordinates | Repo declared after Central (existing ordering rule) and before JitPack, so the JitPack catch-all is never asked for `org.grad.secom` (2026-09-25); a per-repository groupId filter and checksum pinning of the non-Central jars are open; consider mirroring the jars in the org's registry |
| R5 | RESTEasy version clash with keycloak-admin-client's resteasy-client | Keep `resteasy-core` aligned with the version keycloak-admin-client drags in |
| Q1 | Series scope: which message series should be exposed (`S124SeriesConfig`)? | DMA ops decision at rollout (go-live gate 4); closed by default = safe |
| Q4 | Retention of CANCELLED datasets | Keep-forever until ops states a policy (gap doc §1) |
| Q5 | The `/rest/S-124/messages/{id}` draft leak | Closed — `generateGML` 404s non-public statuses |
