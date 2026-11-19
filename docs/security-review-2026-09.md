# Security review, September 2026

What the security review of September 2026 found, what the fix sweep that followed it did, and what is
left.

**What was reviewed.** The whole repository at `main` as of 2026-09-24: the backend, both
frontend applications, the CI workflows and the build, and the Helm chart with the Keycloak realm it
imports.

**How.** A multi-agent code review, in which every finding was challenged by an adversarial
verification pass before it was kept, and live probes against a local Danish stack
(`start-local.sh --dk`) loaded with the test environment's data. It reported 95 findings, none of them
critical.

**What this record is not.** The detailed review, with the evidence for each finding, is held by the
owner and is not in the repository. This record names each weakness in neutral terms and gives no
reproduction steps, because the repository may become public at go-live. The ids (`F-X…`, `N-…`) are
the review's own; the commit messages and pull requests of the sweep use them.

---

## 1. Summary

| Status | High | Medium | Low | Info | Total |
|---|---|---|---|---|---|
| Fixed | 9 | 32 | 24 | 3 | 68 |
| Mitigated | — | 4 | 3 | — | 7 |
| Accepted | — | 2 | 9 | — | 11 |
| Deferred | 1 | 2 | 6 | — | 9 |
| **Total** | **10** | **40** | **42** | **3** | **95** |

By area: backend 77, deployment 9, CI/CD 4, frontend 3, dependencies 2.

**The owner's rulings (2026-09-24 and 25).**

- An effort-versus-risk triage of the low and medium findings followed the review.
- 12 findings, of low realistic risk and high effort to fix, were **accepted**: no action (section 6).
  One of them, F-X146, has been fixed since (the rulings of 2026-09-26 below).
- 7 findings are **mitigated** with the cheap partial fix only (section 4).
- The 9 findings about the deployment — the Helm chart, its Keycloak and the realm it imports — are
  **deferred**: the deployment is still being built, and they are rechecked against the finished
  deployment before production go-live (section 5).
- Everything else is **fixed** now, the findings in the legacy publication endpoints included.

**The owner's rulings of 2026-09-26** settle the five options the sweep did not take (section 8). One
of them changed a status: F-X146 moved from accepted to fixed, because subscriber mails are now cleaned.
The others concern residues of findings already counted — F-X21, F-X108, F-X112, F-X145, N-04 and
N-07 stay fixed, F-X68 stays accepted and F-X100 stays deferred — so no other count changed.

---

## 2. The sweep

The fixes were made on one feature branch, cut from `main` four merges after the reviewed commit,
with one pull request per phase, each squash-merged
into the branch. The phases are numbered in planning order; the table lists them in the order their pull requests were opened.

| Phase | What it covered | Findings |
|---|---|---|
| P11 | CI and build: the publish gate, workflow inputs, action references, Maven repository order | F-X73, F-X106, F-X107, F-X108, F-X109 |
| P12 | Administration app: rich-text attributes and the web-settings overlay | F-X112, N-19, N-21 |
| P10 | SECOM: outbound limits before the cut-over | F-X13, F-X14, F-X152, F-X156 |
| P05 | Anonymous message search: status floor, limits and GML references | F-X1, F-X63, F-X44, F-X7, N-13, F-X17 |
| P02 | Repository: path containment and safe serving | F-X83, F-X29, F-X57, F-X30, F-X52, F-X45, F-X55 |
| P01 | Script engines: sandboxed Nashorn, no caller-supplied scripts | F-X42, F-X43, F-X22, N-05, F-X139 |
| P07 | Messages: tags, comments, history and the viewing check | F-X9, F-X87, F-X4, N-06, N-11, F-X2, N-07, F-X12 |
| P03 | Uploads, archives, exports and served HTML | F-X160, F-X50, F-X49, N-01, N-14, N-08, N-23, F-X21, F-X145 |
| P09 | Anonymous resource limits: tiles, reformat, PDFs, JSON parsing | F-X24, F-X28, F-X86, F-X113 |
| P00 | Test stability: one Lucene index writer at a time across Spring contexts; the SECOM outbox schedule off in the SECOM integration tests | — |
| P08 | Tickets, domains, tokens and role tiers | N-02, N-10, F-X37, N-12, N-22, F-X15, N-04, N-09, F-X23 |
| P06 | Publications: the anonymous public tier and domain guards | F-X60, F-X121, F-X85, F-X16, F-X126, F-X127, N-16, N-18 |
| P04 | Outbound fetches: mail, PDF rendering, Keycloak admin, WMS proxy | F-X147, F-X46, F-X138, F-X36, F-X40, F-X77 |
| P13 | Follow-ups: the unlock-tag domain rule, comment mail links, shared helpers, archive edit folders | the rest of F-X4 |
| P14 | This record, `be/docs/potential-errors.md` and `fe/docs/backlog.md` | — |
| P15 | The external review: the comment-mail cap counts mailboxes | N-07 |

**Verification.** Every phase added tests for what it changed, rewrote the tests that pinned the old
behaviour, and ran them locally. The first twelve phases of the table, P11 to P06, also had the full CI run: backend unit and
integration tests, and the administration app's and the public site's gates where those changed. The
integration run of P09 failed twice on two flakes unrelated to the change, a Lucene index lock held by
another Spring context's indexer and the SECOM outbox schedule sending a test's entry a second time;
P00 fixed both. The Lucene flake had also hit P01, where a re-run passed. P09 was merged with its
integration check red; P00, P08 and P06 ran the suite green on top of it.

**The CI outage.** GitHub Actions stopped starting jobs on 2026-09-25 (the account's billing and
spending limit), so the checks of P04 and P13 failed without starting a job. Both were verified with the
full backend suite run locally on Windows instead, whose only failures were the ones `main` already has
on these workstations. The pull request of the feature branch into `main` must get a full CI
run once Actions works again.

**The external review.** An external review of the sweep's pull requests found a gap in the
N-07 fix of P07: the comment endpoints capped the number of strings in the recipient list, and one RFC 822
group address is a single string that names many mailboxes, each of which gets the mail. P15 closes it: a
recipient that is a group, or holds more than one address, is answered 400 before the comment is saved,
so a comment is mailed to at most 20 mailboxes. The e-mail suggestions the comment dialog offers
(`GET /rest/users/search-emails`) now quote a display name that holds a comma or another special character, so
every suggestion still parses as exactly one mailbox.

**The live test realm.** Checked on 2026-09-25 (`login.t-dma.dk`): no Niord role is granted as a
Keycloak realm role, and tokens are issued to `niord-web`. The sweep's realm-role and authorized-party
changes (F-X37) therefore lock nobody out there. A production realm must follow the same pattern:
Niord roles as client roles, and logins through `niord-web` or a client listed in
`niord.oidc.allowed-clients`.

---

## 3. Status of every finding

Sorted by severity, then area. Sections 4 to 6 say what remains of each mitigated, deferred and
accepted finding.

| Id | Severity | Area | Weakness | Status | Phase |
|---|---|---|---|---|---|
| F-X29 | high | Backend | The repository served uploaded HTML inline on the backend origin | fixed | P02 |
| F-X42 | high | Backend | Template execution evaluated a caller-supplied script outside any sandbox | fixed | P01 |
| F-X43 | high | Backend | The legacy publication tag filter ran as an unsandboxed script for domain admins | fixed | P01 |
| F-X83 | high | Backend | The repository's "not found" answer reflected the request path as HTML on the backend origin | fixed | P02 |
| F-X147 | high | Backend | Mail assembly fetched any image or style-sheet address found in message HTML | fixed | P04 |
| F-X160 | high | Backend | Batch upload file names were used as given, so a file could land outside its job folder | fixed | P03 |
| N-01 | high | Backend | The message export read attachment files by a stored name with no containment check | fixed | P03 |
| F-X112 | high | Frontend | The rich-text editor kept every attribute of a publication span | fixed | P12 |
| F-X73 | high | CI/CD | The publish workflow did not check that its trigger was a push to main in this repository | fixed | P11 |
| F-X92 | high | Deployment | The chart's Keycloak runs in development mode with committed seed credentials | deferred | — |
| F-X1 | medium | Backend | Anonymous message-id search returned non-public messages | fixed | P05 |
| F-X2 | medium | Backend | Comments of any message, with e-mail addresses, were readable across domains | fixed | P07 |
| F-X4 | medium | Backend | Plain users could unlock and change locked public tags, across domains | fixed | P07, P13 |
| F-X13 | medium | Backend | SECOM deliveries went to whatever endpoint the registry named | fixed | P10 |
| F-X14 | medium | Backend | The SECOM outbox had no per-node limit | mitigated | P10 |
| F-X15 | medium | Backend | The Niord-integration endpoints were open to an admin of any domain | fixed | P08 |
| F-X21 | medium | Backend | The S-124 endpoint returned generation errors as HTML | fixed | P03, section 8 |
| F-X22 | medium | Backend | Caller-supplied AtoN filters were evaluated with the engine's globals reachable | fixed | P01 |
| F-X24 | medium | Backend | The anonymous AtoN tile and icon endpoints did unbounded work and wrote files per request | fixed | P09 |
| F-X28 | medium | Backend | The NAVTEX and SafetyNET reformat endpoints were anonymous and quadratic in the text length | fixed | P09 |
| F-X30 | medium | Backend | The general repository upload was not confined to the temporary area | fixed | P02 |
| F-X44 | medium | Backend | Anonymous free-text search accepted the full query syntax | fixed | P05 |
| F-X45 | medium | Backend | A publication file name from the request body was not reduced to a bare name | fixed | P02 |
| F-X46 | medium | Backend | Report parameters reached the PDF as markup | fixed | P04 |
| F-X50 | medium | Backend | The message-archive import could extract entries outside its folder | fixed | P03 |
| F-X52 | medium | Backend | The repository folder sync took its paths from the request body | fixed | P02 |
| F-X60 | medium | Backend | The anonymous legacy publication listing included unreleased rows and their file links | fixed | P06 |
| F-X63 | medium | Backend | Anonymous searches accepted any result size | fixed | P05 |
| F-X85 | medium | Backend | Files of unreleased publication issues can be downloaded through the public host | mitigated | P06 |
| F-X86 | medium | Backend | Anonymous PDF rendering had no concurrency limit | fixed | P09 |
| F-X121 | medium | Backend | The anonymous publication lookup resolves at the internal audience | mitigated | P06 |
| F-X126 | medium | Backend | Curation could include a non-public message in a public issue | fixed | P06 |
| F-X127 | medium | Backend | The publication-series batch import bypassed the domain guard | fixed | P06 |
| F-X128 | medium | Backend | The anonymous issue lookup by id shows the titles of unreleased issues | accepted | — |
| F-X138 | medium | Backend | The PDF renderer fetched any resource named in message markup | fixed | P04 |
| F-X145 | medium | Backend | The mailing-list report HTML was served unsandboxed from the backend origin | fixed | P03 |
| F-X152 | medium | Backend | A slow SECOM peer could hold the shared scheduler thread | mitigated | P10 |
| F-X156 | medium | Backend | SECOM GetSummary built a signed exchange set per item, with no page-size cap | fixed | P10 |
| N-02 | medium | Backend | The NiordDomain header overrode the domain a ticket was issued for | fixed | P08 |
| N-03 | medium | Backend | The legacy publication write endpoints have no domain guard | accepted | — |
| N-04 | medium | Backend | A domain admin could replace the global dictionaries by upload | fixed | P08 |
| N-05 | medium | Backend | The anonymous category search ran a new script engine per category for caller-supplied AtoNs | fixed | P01 |
| N-06 | medium | Backend | TEMP message tags were readable by every caller | fixed | P07 |
| N-07 | medium | Backend | A comment could mail any message to any number of recipients, markup unescaped | fixed | P07, P15 |
| N-08 | medium | Backend | Batch data files were served inline with a type taken from the extension | fixed | P03 |
| N-09 | medium | Backend | Batch jobs were not scoped to a domain | fixed | P08 |
| F-X106 | medium | CI/CD | The break-glass publish could be run from any branch | fixed | P11 |
| F-X113 | medium | Dependencies | A Jackson number-parsing weakness was reachable before authorization | fixed | P09 |
| F-X70 | medium | Deployment | The admin application's Keycloak client allows any redirect URI and origin and does not enforce PKCE | deferred | — |
| F-X72 | medium | Deployment | An unused confidential Keycloak client holds a privileged service account | deferred | — |
| F-X6 | low | Backend | The message map image is served without a viewing check | accepted | — |
| F-X7 | low | Backend | The anonymous export carried system-only message details | fixed | P05 |
| F-X9 | low | Backend | A public tag made a draft readable anonymously | fixed | P07 |
| F-X12 | low | Backend | Message history was readable across domains | fixed | P07 |
| F-X16 | low | Backend | The anonymous publication filter resolved publications in internal categories | fixed | P06 |
| F-X17 | low | Backend | S-124 GML resolved non-public referenced messages | fixed | P05 |
| F-X19 | low | Backend | The SECOM client-certificate header is trusted from any caller inside the cluster | accepted | — |
| F-X23 | low | Backend | Any signed-in user could list every user's name and e-mail address | mitigated | P08 |
| F-X36 | low | Backend | The Keycloak admin URL came from the request host, and TLS was not verified | fixed | P04 |
| F-X37 | low | Backend | Tokens from any client of the realm were accepted, and realm roles were mapped | fixed | P08 |
| F-X40 | low | Backend | The WMS proxy forwarded the caller's query parameters and logged its token | fixed | P04 |
| F-X49 | low | Backend | The batch-set archive could extract entries outside its folder | fixed | P03 |
| F-X55 | low | Backend | The thumbnail decoder had no image-size limit | fixed | P02 |
| F-X57 | low | Backend | Repository reads relied on the HTTP firewall alone for containment | fixed | P02 |
| F-X64 | low | Backend | Administration configuration is readable anonymously through open GET endpoints | accepted | — |
| F-X68 | low | Backend | Staff can have the backend mail a message to any address | accepted | — |
| F-X77 | low | Backend | Password settings and the WMS token were written to the log | fixed | P04 |
| F-X87 | low | Backend | The public host's message PDF rendered a non-published message in a public tag | fixed | P07 |
| F-X129 | low | Backend | The curation reads are not scoped to a domain | accepted | — |
| F-X139 | low | Backend | Template parameters were printed unescaped into HTML fields | fixed | P01 |
| F-X146 | low | Backend | Message markup was mailed to subscribers as the editor wrote it | fixed | section 8 |
| F-X157 | low | Backend | TLS verification towards the SECOM registry can be switched off, and an http registry is accepted | accepted | — |
| N-10 | low | Backend | Domain roles were granted for any client the NiordDomain header named | fixed | P08 |
| N-11 | low | Backend | DOMAIN message tags were visible to anonymous callers that named the domain | fixed | P07 |
| N-12 | low | Backend | Anonymous callers could have unlimited tickets issued, each logged at INFO | mitigated | P08 |
| N-13 | low | Backend | Anonymous search could filter on staff user names and on internal comments | fixed | P05 |
| N-14 | low | Backend | The geometry template name was not restricted | fixed | P03 |
| N-15 | low | Backend | Estate-wide publication categories can be changed by an admin of any domain | accepted | — |
| N-16 | low | Backend | The legacy publication search-details served the system shape to any user | fixed | P06 |
| N-17 | low | Backend | The anonymous legacy publication search loads both publication tables on every call | accepted | — |
| N-18 | low | Backend | The estate-wide legacy import and its undo were open to one domain's admin | fixed | P06 |
| N-20 | low | Backend | The shipped defaults reach the WMS over plain HTTP (the map-image server they also reached is gone) | accepted | — |
| N-19 | low | Frontend | The rich-text editor kept any class on links and table cells | fixed | P12 |
| F-X107 | low | CI/CD | The retract workflow expanded its reason input into the script and did not gate the tag delete | fixed | P11 |
| F-X108 | low | CI/CD | Workflow actions were referenced by movable tags, one of them third-party | fixed | P11, section 8 |
| F-X109 | low | Dependencies | Maven consulted JitPack before the repository that publishes the SECOM library | mitigated | P11 |
| F-X76 | low | Deployment | Chart credentials are plain values with weak defaults | deferred | — |
| F-X96 | low | Deployment | The chart's public entry point serves plain HTTP only | deferred | — |
| F-X99 | low | Deployment | The chart's pods have no security-context hardening | deferred | — |
| F-X100 | low | Deployment | One database superuser is shared by every release | deferred | — |
| F-X114 | low | Deployment | The chart pins an outdated Keycloak release | deferred | — |
| F-X116 | low | Deployment | The chart uses end-of-life nginx and MailHog images | deferred | — |
| N-22 | info | Backend | The security guard tests covered only public methods of REST controllers | fixed | P08 |
| N-23 | info | Backend | Unused Java deserialization of batch data files | fixed | P03 |
| N-21 | info | Frontend | The web-settings overlay could move the WMS prefix that receives users' tokens | fixed | P12 |

---

## 4. Mitigated: what remains

| Id | What was done | What remains |
|---|---|---|
| F-X14 | One remote node holds at most `secomMaxSubscriptionsPerNode` subscriptions (50); a failed transaction raises one alert, and no acknowledgement undoes a cancellation. | Fair dispatch between nodes, alert throttling and one payload per dataset version. |
| F-X85 | The two anonymous lookups (`/publications/publication/{ids}`, `/publication-issues/by-ids`) no longer hand out the link of an unreleased issue's document. | The public host still proxies the whole repository, so a file uploaded to an open issue has an address before release. The full fix keeps unreleased files outside the served repository. |
| F-X109 | Maven consults the GRAD repository before JitPack. | A per-repository group filter, checksum pinning and a mirror (`be/docs/secom-design.md`, R4). |
| F-X121 | With the anonymous listing floor (F-X60), publication ids are no longer found anonymously, and links of unreleased documents are withheld. | `/publications/publication/{ids}` still resolves at the internal audience; the one-line full fix is recorded in `getPublications`. |
| F-X152 | Each outbound SECOM exchange has a 30-second deadline over the whole response, so a slow peer can no longer hold the dispatcher's scheduler thread indefinitely. A delivery can take two exchanges (the registry search and the POST), and a run up to `secomOutboxMaxPerRun` deliveries (20). Since SCHED3 (2026-11) the scheduler has four threads (`spring.task.scheduling.pool.size`), so a slow run holds one of them, not every other scheduled job. | An executor of its own for SECOM deliveries, and a cap on the response body. |
| F-X23 | `GET /users/all` needs the admin role; the user search needs two characters and returns at most 25 users. | Any signed-in user can still search the users of every domain. |
| N-12 | Ticket issuance is logged at DEBUG, and the ticket cache holds at most 10 000 entries. | Anonymous callers can still have tickets issued. |

---

## 5. Deferred until go-live

The deployment — the Helm chart in `be/k8s/helm/niord`, the Keycloak it runs and the realm it imports —
was still being built when the review ran. Its nine findings are rechecked against the finished
deployment before production go-live; the "Before production go-live" list in
[`fe/docs/backlog.md`](../fe/docs/backlog.md) points here. Chart changes merged on `main` after the
review probably address some of them: every pod meets the restricted Pod Security standard (F-X99),
credentials come from Secrets (F-X76), and `keycloak.mode: production` gives a realm with no
`niord-web-admin` client and no client secret from the repository, and a `niord-web` limited to the
environment's origin with PKCE and no password grant (F-X70, F-X72, for an environment that sets it). F-X92 stays open: that mode
runs Keycloak in production mode with its admin from a Secret, but keeps the test users with the passwords
in the repository. The owner ruled on 2026-09-29 that they are not changed, also when staging goes on the
Internet. The recheck decides for production.

A first recheck ran on 2026-11-12, against the chart and both stagings
([The recheck of 2026-11-12](#the-recheck-of-2026-11-12), below), and a second one the same day,
after the changes the first led to
([The second recheck of 2026-11-12](#the-second-recheck-of-2026-11-12)): its table is the current
state. Production does not exist yet, so neither closed anything: all nine stay deferred, and the
table below is still what the last recheck before go-live holds production to.

| Id | Severity | Recheck before go-live |
|---|---|---|
| F-X92 | high | Keycloak runs in production mode, and no credential committed to the repository or defaulted by the chart — the bootstrap admin, a seeded user — works against a production realm or holds a Niord role there. |
| F-X70 | medium | The `niord-web` client lists the exact redirect URIs and web origins of each public host, and requires PKCE. |
| F-X72 | medium | No unused client with a service account that can manage the realm exists in a production realm, and no client secret comes from the repository. |
| F-X76 | low | Every credential of a production release comes from a Secret, and no weak default from `values.yaml` reaches it. A chart change after the review moved the Postgres and Keycloak credentials and the SECOM store passwords into Secrets; check what else a release carries. |
| F-X96 | low | Every public host is served over https only, so tokens and passwords never cross the network in clear text. |
| F-X99 | low | Every pod runs as a non-root user under the restricted Pod Security standard. A chart change after the review did this for the chart's own pods. The public site's image runs as its unprivileged user 101 on port 8080 since the chart deploys it as its `info` component ([public-site-deploy-plan.md](public-site-deploy-plan.md)); its container test runs it as a restricted pod does. |
| F-X100 | low | The application connects to its database as a user that owns that database and is not a superuser (the owner's ruling of 2026-09-26, section 8). One Postgres per country (design §6) already keeps the countries apart, so a user per country is no longer the goal. The application still connects as the environment's admin user, and the base-data seed disables triggers, which needs a superuser (design §6): the recheck also settles how the seed runs. |
| F-X114 | low | Keycloak runs a current patch release. |
| F-X116 | low | The proxy, which also terminates SECOM mutual TLS, runs a supported nginx release, and MailHog is not part of a production release. (The SECOM mutual-TLS listener was removed on 2026-10-14: SECOM Edition 2 takes identity from the signed envelope.) |

Three more items belong to the same recheck:

- **The realm pattern** of section 2: Niord roles as client roles, logins through `niord-web` or a client
  in `niord.oidc.allowed-clients`.
- **Rate limiting of anonymous requests at the ingress**, which F-X24, F-X44 and F-X86 name as the part
  of their fix the application does not do.
- **The go-live checks of three accepted findings**: F-X19, F-X157 and N-20 (section 6).

### The recheck of 2026-11-12

**What it was held against.** The chart at `main` on that day; the two stagings as their values pin
them (chart 4.0.151, in `devops-niord`); the test cluster's shared Keycloak and its entry
point, Traefik (in `devops-shared`); and the production values parked in `devops-niord`
(`reference/pending/envs/prod/`). The deployment has changed shape twice since the review. The stagings
sign in through one shared Keycloak for the cluster, with a realm per country that a platform admin
creates once from the realm the chart renders (2026-11-05). And the public site is the chart's sixth
component, on a host of its own (2026-11-10).

**How.** The chart was rendered with each of those value sets, and a script read the rendered objects.
The realm the chart renders for production was imported into a throwaway Keycloak 26.8.1, the version
the stagings sign in to, and read back from it. The schema's changesets were applied in a throwaway
PostGIS container by a database user that is not a superuser. The release pages of Keycloak, nginx and
Alpine were read on the day.

**What it could not reach.** Production does not exist: its cluster is not set up, its domain and its
entry point are not decided, and its Secrets are not sealed. So no finding is closed. The test cluster
is not reachable from a workstation either, so two things were not read: the two realms as the shared
Keycloak holds them, and the settings each staging holds in its database. A realm is created from the
chart's file once and changed in the admin console from then on, so the rendered realm says what a new
realm gets, not what a running one holds.

| Id | Result | The chart and the stagings | Open before go-live |
|---|---|---|---|
| F-X92 | **open** | Keycloak runs in production mode on the stagings: the shared Keycloak is started with `start`, on a database of its own, and its bootstrap admin comes from a sealed Secret. The chart refuses production mode without a Secret for that admin. But the realm the chart renders for production still holds the four test users with the passwords in this repository, and no value leaves them out. One of them is a Niord sysadmin on every domain and may manage the realm's users and clients: on the throwaway Keycloak the repository's password signed it in and gave it both. Staging keeps them by the ruling of 2026-09-29. | A production realm without the four test users: a chart value that leaves them out, or their removal by hand, checked afterwards. Who administers the `master` realm of production's Keycloak (on the test cluster one shared bootstrap account, by the owner's ruling of 2026-11-05 for a closed network). The admin console and the realm `master` refused from the Internet at production's entry point, as Traefik refuses them on the test cluster's public address. And a guard: `keycloak.mode` defaults to `dev`, and nothing refuses a staging or production environment that leaves it out. |
| F-X70 | **met by the chart**; the running realms not read | The production realm's `niord-web`, as rendered and as read back after the import: one redirect pattern, `<externalBaseUri>/*`, one web origin, PKCE (`S256`) required, no password grant and no implicit flow. The public site signs nobody in, so it needs no redirect. | The same read on the realms that run, staging's now and production's when it is created: the fresh-database runbook switches the password grant on for one token and off again, and a changed address is changed by hand. Whether a path wildcard under the one origin counts as exact is the owner's call; the recheck recommends accepting it, with PKCE required. |
| F-X72 | **met by the chart**; the running realms not read | The production realm has no `niord-web-admin` client, and no client with a service account. No client secret comes from the file: after the import the confidential clients held secrets that Keycloak generated. The file holds no signing key either; Keycloak generates those too. Dev mode keeps the client, with the file's masked placeholder as its secret: local runs and `test-<name>` environments only. | The same read on the running realms: no client with a service account added since. |
| F-X76 | **met on the stagings**, with two gaps | Rendered with the stagings' values and with the parked production values, the chart writes no Secret of its own and no credential into a ConfigMap or a pod. The Postgres admin, the pull secret and (parked production) the Keycloak admin are Secrets the environment seals beside its values. SECOM is off, so its stores are not there, and the WMS token is a setting in the database. A release carries no other credential today. | A release that leaves out `postgres.existingSecret` gets the chart's default user and password without a word: the chart refuses that for the Keycloak admin only, and the deploy repository's validator does not look. Outbound mail has no login: if production's relay asks for one, the application and the chart need a seam for it (`devops-niord` open issue 1). Production's Secrets are not sealed. |
| F-X96 | **met on the stagings** | Traefik ends TLS for every host of the test cluster and redirects http to https on both its addresses, the internal and the public one. The chart refuses an Ingress for an `externalBaseUri` that is not https, and every Service of a staging is ClusterIP. Both stagings are on the internal address only. Inside the cluster the hops are plain http, by design. The public site's container sends no `Strict-Transport-Security` and Traefik adds none; the administration host's answer was not read. | Production's entry point: it sits behind the host's HTTPS offloader, and who ends TLS there is still open (phase-0 question H6). The parked production values predate the Ingress: as they stand they render the proxy as a LoadBalancer on plain http with no Ingress, so they are written again, not unparked. `Strict-Transport-Security` on both hosts. |
| F-X99 | **met** | Every pod of every render — the local default, both stagings, the parked production values; init containers and the databases Job included — runs as a non-root user with the default seccomp profile, no privilege escalation and no capabilities. So do the shared Keycloak and its Postgres. The cluster enforces the restricted level on every namespace, so a pod that missed it would not start. Beyond the standard: root file systems are writable, and every pod mounts the default service account's token, which only the SECOM renewal needs. | Nothing for the standard; that production's cluster enforces it is part of its set-up. |
| F-X100 | **not met** | The application connects as the Postgres superuser on both stagings, and would in production as parked; a Keycloak of the environment's own does too. How the seed runs is settled: no deployment is seeded any more. The chart has no seed, and a new database gets its data from the bootstrap command, which runs as the application (the parked production values still carry the dead `postgres.seed` key). Tried in the throwaway container: a user that owns the database and is not a superuser builds the whole schema except one statement of the first changeset, the comment on the PostGIS extension, which only the extension's owner may write. That statement stops the first start on a new database; a database that has the changeset already is not affected. On a database without PostGIS, creating the extension needs a superuser once. Only the changesets were tried, not the running application. | An application user of its own: a chart value for its Secret beside the Postgres admin's, the user created with the database it owns, the first changeset's statement made harmless (a deliberate change to an applied changeset and its checksum), and the ownership handed over in the databases that exist. A database in the host's database hotel comes with an owner that is not a superuser, so there the same statement fails, and the hotel must offer PostGIS with the extension created. |
| F-X114 | **not met** | The chart pins Keycloak 26.6.3 (2026-06-04). The 26.6 line ended with 26.6.4 (2026-06-26); the current release is 26.8.2 (2026-11-05), a security release. The stagings' shared Keycloak runs 26.8.1, one release behind it. The chart's pin is what local runs, `test-<name>` environments and the parked production values run. Nothing moves either pin by itself: Renovate for the devops repositories was decided against (2026-10-03). | Both pins on the current patch release at go-live, and someone who watches Keycloak's security releases. |
| F-X116 | **not met** for the proxy | The proxy runs `nginx:1.27-alpine`: nginx 1.27.5 on Alpine 3.21, an image built in April 2025. nginx maintains its stable and its mainline line, 1.30 and 1.31 today; 1.27 ended in April 2025, and Alpine 3.21 on 2026-11-01. The public site's image carries the nginx package of Alpine 3.22 (1.28.3), which Alpine maintains until 2027-05-01. MailHog runs on the stagings only: the parked production values switch it off. The proxy has no SECOM listener left. | The proxy on a maintained nginx line: the chart's default and its mirror in Harbor. The public site's base on a newer Alpine before 3.22 ends. MailHog stays out of production by its values; nothing in the chart refuses it there. |

The three items that belong to the same recheck:

- **The realm pattern holds in the chart.** The rendered realm has no Niord role as a realm role: the
  roles are client roles, on `niord-web` and the domain clients, and the users hold Keycloak's default
  realm roles only. The backend still accepts a token only from a client in `niord.oidc.allowed-clients`
  and maps no realm role (`SecurityConfig`). Neither staging sets `app.oidc.allowedClients`, so the
  default stands. The domain clients are bearer-only, the chart's and the ones the fresh-database
  runbook makes. The running realms were not read.
- **Rate limiting at the ingress is not built.** Neither Traefik, the chart's proxy nor the public
  site's nginx limits requests, and the test cluster's Ingress rule admits no Traefik annotation that
  could add a limit. The limits the sweep put into the application (section 7) are the only ones. The
  decision waits for the host's answer on whether Traefik sees the client's own address (`devops-shared`
  open issue 8): without it a limit cannot tell one client from another.
- **The go-live checks of the three accepted findings.**
  - **F-X19** needs its check no longer. The backend ignores the client-certificate header unless
    `niord.secom.trust-cert-header` is set, which the chart never does, and the proxy blanks the header.
    No NetworkPolicy exists; the owner ruled it a minor nice-to-have on 2026-11-10, the host's
    firewall being restrictive.
  - **F-X157** waits for SECOM, which is off on both stagings and in the parked production values. The
    setting defaults to off. Its check belongs in the SECOM cutover runbook (`devops-niord`,
    `runbooks/secom/cutover.md`), which does not carry it yet.
  - **N-20** is open for the countries. The base product ships no WMS address any more, and its
    `osmUrl` is https. The default settings of DK and GL still name the WMS over plain http, and the WMS
    token travels in that request. What each deployment holds in its database (`wmsProvider`, `osmUrl`,
    `baseUri`) was not read.

**What the recheck leaves.** Ordinary work on the chart and the test cluster, each item on the owner's
go:

1. Keycloak on 26.8.2: the shared Keycloak's pin (`devops-shared`) and the chart's, with its mirror in
   Harbor (F-X114). Done on 2026-11-12: the chart and the local compose file on 26.8.2
   (published as 4.0.161), the image mirrored in Harbor, the shared Keycloak on 26.8.2 (devops-shared),
   and the parked production values on 26.8.2 (devops-niord, on the owner's request). From
   26.8.2 the Admin REST API refuses a token without the client scope `kc-admin-api`, and the
   backend's admin calls carry the signed-in user's `niord-web` token: the realms the chart, the
   local run and the tests import now give `niord-web` that scope, and the owner gave it to
   `niord-web` in `niord-dk` and `niord-gl` by hand after the upgrade (devops-shared,
   `runbooks/keycloak.md`, Upgrades). Both stagings run base 4.0.162 (devops-niord), and
   the owner checked them the same day. Watching Keycloak's security releases stays open.
2. The proxy on a maintained nginx line (F-X116). Done the same day: nginx 1.30.6, the stable line,
   on Alpine 3.24, mirrored in Harbor, and the proxy of both stagings since their move to base 4.0.162.
   1.30 ends when 1.32 comes out, expected in April 2027. The public site's Alpine 3.22 base is
   maintained until 2027-05-01 and stays for now.
3. A chart value that renders the production realm without the test users (F-X92).
4. Two guards (F-X76, F-X92): the chart refuses production mode without `postgres.existingSecret`, and
   the deploy repository's validator refuses a staging or production environment that renders dev mode
   or a default credential of the chart.
5. The application's own database user (F-X100). A plan first: it depends on when the database moves
   to the host's database hotel, and the first changeset's statement is in the way on either road.
   The plan is [database-user-plan.md](database-user-plan.md), decided and built on the same day: the
   chart makes the user, and the first changeset builds as one (published as 4.0.162). Done on
   both stagings the same day: they connect as `niord_app`, a user that owns its database and is not
   a superuser, since two merges in devops-niord (the sealed Secrets and the values). The owner checked
   them: every session on each database is that user's, the app reads and saves, and the Postgres
   pods were not restarted. Production's values name both Secrets when they are written.
6. The read of the running realms and of the stagings' settings, from the jump server (F-X70, F-X72,
   the realm pattern, N-20), and of the administration host's `Strict-Transport-Security` (F-X96).
7. F-X157's check into the SECOM cutover runbook, and the countries' default WMS address on https
   (N-20).

For the owner to decide, with what the recheck recommends:

- **F-X92:** no test user in a production realm (recommended), and who administers the `master` realm
  of production's Keycloak.
- **F-X70:** `<origin>/*` accepted as the redirect pattern (recommended: PKCE is required, and the
  application returns the user to the page they were on).
- **F-X100:** the application user built into the chart now, or left to the database hotel
  (recommended: decide it with the hotel's date). **Decided by the owner on 2026-11-12: built into
  the chart now** ([database-user-plan.md](database-user-plan.md)). The chart serves both: a user it
  makes on its own Postgres, or the user a hosted database comes with. Both stagings moved to their
  second Secret on the same day (item 5), so F-X100 is met on the stagings. It stays deferred until
  production exists.
- **Rate limiting:** where it goes, once the host has answered.

When production is taken up, the last recheck holds it to the table at the top of this section: its
entry point and https, its realm and its admins, its Secrets, and its values.

### The second recheck of 2026-11-12

**What changed in between.** The first recheck led to three changes on the same day, and the owner
asked for the recheck again from the latest commits. The chart went to Keycloak 26.8.2 and nginx 1.30.6
(base 4.0.161). The application got a database user of its own (base 4.0.162;
[database-user-plan.md](database-user-plan.md)). Both stagings took base 4.0.162 and then a second
sealed Secret for that user (devops-niord), and the shared Keycloak's pin went to 26.8.2
(devops-shared).

**What it was held against.** `main` at its latest commit; the published chart 4.0.162, pulled from Harbor,
whose templates, values and realm file are `main`'s; `devops-niord` and `devops-shared` at theirs.
The method was the first recheck's. Every value set was rendered again. The production realm
was imported into a throwaway Keycloak 26.8.2 and read back. The chart's `database-user` script, taken
from the staging render, was run in throwaway PostGIS containers. The test cluster and production were
out of reach as before: what the stagings run is taken from their values and from the owner's checks of
that day, not read.

| Id | Result now | What the second recheck found |
|---|---|---|
| F-X92 | **open**, unchanged | The production realm of chart 4.0.162 still holds the four test users. On Keycloak 26.8.2 the repository's password still signed the sysadmin test user in and let it read the realm's users. |
| F-X70 | **met by the chart**, unchanged; the running realms not read | The same `niord-web` on 26.8.2: one redirect pattern, one web origin, PKCE required, no password grant. The owner changed `niord-web` in both running realms by hand that day (the scope below), one more reason to read them. |
| F-X72 | **met by the chart**, unchanged; the running realms not read | No `niord-web-admin`, no client with a service account, client secrets generated by Keycloak. New: `niord-web` carries the client scope `kc-admin-api` by default, as Keycloak's own two admin clients do, because the Admin API of 26.8.2 asks for it. What a token may do there is still decided by its user's `realm-management` roles. |
| F-X76 | **met on the stagings**; one gap wider | Each staging names three sealed Secrets, and the chart writes none. The application's container holds its own user's Secret only; the superuser's is in the init container and the Postgres pod. The chart now refuses the superuser's Secret without the application's, one Secret under both names, and a production-mode Keycloak without a database Secret of its own where the others are named. But a release that names no database Secret still gets the chart's defaults without a word, three of them now (the superuser, the application's user, Keycloak's database user), in production mode too. The deploy repository's validator still does not look. Outbound mail and production's Secrets: as before. |
| F-X96 | **met on the stagings**, unchanged | Nothing changed: no `Strict-Transport-Security` from the public site or Traefik, and the parked production values still render the proxy as a LoadBalancer on plain http. |
| F-X99 | **met** | Every pod of every render again, the new `database-user` init container included. |
| F-X100 | **met on the stagings**, with one hole in the chart's script | Run in the throwaway containers, the script does what the plan says. The user it makes is no superuser and may create no database or role; it owns the database and everything in the schema `public` that no extension owns; it builds all ten changesets on a new volume; and it is refused programs, the server's files and its settings. A Secret that names the superuser is refused. **The hole:** the script runs each time the application's pod starts, as the superuser, inside the database the application's user owns, and with the default search path. A statement of its hand-over can then resolve to an object that user has put into its own schema. Tried: after the script's next run the application's user was a superuser. So the separation holds against a mistake, but not against someone who can already run SQL as the application's user, which is the case F-X100 is about. With the superuser's search path held to `pg_catalog` the same attempt changed nothing and the hand-over still worked. Keycloak's init container shares the script. Two smaller things: the superuser's password on the stagings is the one the application held until the move, and it was not changed; and the parked production values still name one Secret, the superuser's, for the application and for Keycloak. |
| F-X114 | **met today** | The chart, the local compose file, the shared Keycloak's pin and the parked production values are on 26.8.2, the current release on the day. The running shared Keycloak was not read. Nothing moves the pins by itself. |
| F-X116 | **met on the stagings** | The proxy's image is nginx 1.30.6 on Alpine 3.24, built on 2026-11-05: the stable line and its current release on the day. Both stagings' renders use it. The public site's base is still Alpine 3.22 (until 2027-05-01), and MailHog still runs on the stagings only. |

The three items that belong to the same recheck are as the first one left them. The realm pattern holds
in the chart and the backend. Rate limiting at the ingress is not built. F-X19 needs its check no
longer, the SECOM cutover runbook still lacks F-X157's check, and the default settings of DK and GL
still name the WMS over plain http (N-20).

**What is left now**, the first list's open items and one new one, each on the owner's go:

1. **New (F-X100):** the `database-user` script holds the superuser's search path to `pg_catalog`
   after it connects to the application's database. One line in the chart, and a new base version
   that the stagings take with their next bump. The recheck recommends it first: until then someone
   who can run SQL as the application's user can become the superuser at the pod's next start. Not
   fixed: on the owner's word of 2026-11-12 it is recorded as SECREV-4 in
   [`be/docs/potential-errors.md`](../be/docs/potential-errors.md), with the remedy that was tried.
   Fixed in the chart the same day, on the owner's go: every session of the script has the
   search path `pg_catalog`, set by the client, which also overrides a search path set on the
   database, a second way to the same end that the recheck had not tried. A schema named after the
   superuser, which it had not tried either, was a third; the fix closes all three.
   `ChartDatabaseUserScriptITTest` runs the script in the chart's Postgres image, with something
   planted in each of the three places, and checks that the hand-over still reaches every kind of
   object. The databases Job, which also connects to the application's database as the superuser,
   was tried the same way and needed nothing. SECREV-4 is closed; the stagings run the script
   without the fix until they take a base version that carries it.
2. A chart value that renders the production realm without the test users (F-X92).
3. The guards (F-X76, F-X92): the chart refuses production mode with a default database credential,
   and the deploy repository's validator refuses a staging or production environment that renders dev
   mode or a default credential of the chart.
4. The read of the running realms and of the stagings' settings, from the jump server (F-X70, F-X72,
   the realm pattern, N-20), and of the administration host's `Strict-Transport-Security` (F-X96).
5. F-X157's check into the SECOM cutover runbook, and the countries' default WMS address on https
   (N-20).
6. Someone who watches Keycloak's and nginx's releases: both pins move by hand (F-X114, F-X116).

Still for the owner to decide: the test users in a production realm and who administers the `master`
realm of production's Keycloak (F-X92), `<origin>/*` as the redirect pattern (F-X70), and where rate
limiting goes once the host has answered.

---

## 6. Accepted risks

The owner accepted these eleven as low in realistic risk and high in effort to fix. Each line says what
stays open and why the risk is low. (F-X146 was the twelfth; it is fixed since the rulings of 2026-09-26.)

| Id | Severity | What stays open | Why it is accepted |
|---|---|---|---|
| F-X128 | medium | Anyone can read the title and status of an unreleased publication issue by its id. | Titles only: the document link of an unreleased issue is withheld since P06. A public-only variant of the endpoint means changing its callers too. |
| N-03 | medium | The legacy publication write endpoints check the admin role, not the publication's domain. | Admins only, on the previous publication model, which no screen of the administration app edits any more; locking its admin is on the publications go-live task sheet, kept outside this repository. |
| F-X6 | low | The map image of a message, drafts included, is served to anyone who has the message's UID. | The UID is a random UUID, and the image shows the geometry only. |
| F-X64 | low | Many open GET endpoints return administration configuration to anonymous callers. | Configuration, not message content. Closing it means deciding, endpoint by endpoint, which reads the public site and the administration app's guest mode need; `be/docs/potential-errors.md` (SECAUD-12) keeps the systemic item. |
| N-17 | low | The anonymous legacy publication search loads both publication tables on every call, whatever page it asks for. | The cost is bounded by the size of the two tables, and anonymous callers get the public tier and at most 1000 rows (P06, P13). |
| F-X19 | low | The backend trusts the SECOM client-certificate header that the proxy sets, from any caller that reaches it inside the cluster. | Only a workload inside the cluster can bypass the proxy. Recheck at go-live that the cluster's network policy lets only the proxy reach the backend's SECOM path. |
| F-X68 | low | Staff can have the backend mail a message to any address: an editor through the mail dialog, any user of the message's domain through a comment (at most 20 addresses since P07, each a single mailbox since P15: a group address is refused). | Mailing messages is the feature, and the senders are signed-in staff. The owner accepted who may be a recipient as it is on 2026-09-26 (section 8): own domain only; attributable through the Reply-To, the scheduled-mail record and the log; comment mails capped at 20 single mailboxes. |
| F-X129 | low | The curation reads of a publication series are not limited to its domain. | Read-only, and for curators and admins only. |
| F-X157 | low | A sysadmin setting (`secomServiceRegistryInsecure`, off by default) switches off TLS verification towards the SECOM registry, and an http registry address is accepted. | Meant for test registries. Recheck at go-live that the setting is off and the registry address is https. |
| N-15 | low | The publication categories are shared by every domain, and an admin of any domain can rename them or change whether they publish. | Admins only. Categories owned per domain would be a change to the model. |
| N-20 | low | The shipped default settings reach the WMS over plain HTTP. The static-map server the finding also named is no longer called: the backend draws map images on OpenStreetMap tiles it fetches itself, by default over https from `tile.openstreetmap.org` (the `osmUrl` setting), with a User-Agent that names a contact (`mapTileContact`), a 3 s connect and 5 s request timeout, an 8 s deadline per image, a 1 MB response cap, redirects not followed, a per-host circuit breaker, and a tile cache outside the public repository that nothing serves. At start-up the tile fetcher rehearses one exchange against a server socket of its own on the loopback interface (an ephemeral port, one accepted connection, closed after it; skipped when a proxy would carry it), so no tile host is contacted before an image asks for a tile. | Deployment settings, not code. Recheck at go-live that production uses https addresses, the tile address included: `osmUrl` also accepts http. |

The rulings of 2026-09-26 (section 8) also accepted residues of findings that are counted as fixed. They
add no finding to the count above.

| Id | What stays open | Why it is accepted |
|---|---|---|
| F-X112, F-X145 | Message HTML is stored as the editor's browser sends it; the server does not clean it on save. | The administration app's editor narrows the markup it keeps, both applications clean message HTML before they show it, the mailing-list report is sandboxed, and every mail made from a message carries the message's texts cleaned and its attachment images' attributes checked (F-X146). |
| N-04 | Dictionary text is printed unescaped by the FreeMarker templates. | Only a sysadmin can upload dictionaries since P08. |
| N-07 | Who may receive a comment mail. | As F-X68: accepted as it is. |
| F-X108 | GitHub's own `actions/*` are referenced by major tag, not by commit SHA. | GitHub publishes them. Since P11 no third-party action is used (the Harbor login runs the docker CLI), and one added later is pinned to a commit SHA; Dependabot security updates now cover the workflows too. |

---

## 7. Behaviour changes that operators and staff notice

### Raised role requirements

| Endpoint | Before | Now |
|---|---|---|
| `PUT /rest/categories/search` (the AtoN-filtering search) | anonymous | editor, at most 100 AtoNs |
| `PUT /rest/dictionaries/matches-aton` | user | editor |
| `PUT /rest/promulgation/navtex/reformat-navtex`, `PUT /rest/promulgation/safetynet/reformat-safetynet` | anonymous | editor, at most 20 000 characters |
| `PUT /rest/templates/execute` with an inline category definition | user | sysadmin; for anyone else only the category's id counts, and without one the answer is 403 |
| `POST /rest/publications/publication`, `PUT /rest/publications/publication/{id}` with a message-tag filter that is neither the stored one nor a known one | admin | sysadmin |
| `/rest/niord-integrations/*` | admin | sysadmin |
| `POST /rest/dictionaries/upload-dictionaries` | admin | sysadmin |
| `/rest/batch/execution/{id}/stop`, `/restart`, `/abandon`; `/rest/batch/{jobName}/instances`; `/rest/batch/instance/{id}/logs` and a log file; the data-file download | admin | sysadmin |
| `GET /rest/users/all` | user | admin |
| `GET /rest/publications/search-details` | user | admin |
| `POST` and `DELETE /rest/publication-series/import-legacy` | admin | sysadmin (the dry run and the import check stay admin) |
| `GET /rest/messages/message/{id}/history` | any domain's user, for a public-status message | the message's own domain |
| The comment endpoints under `/rest/messages/message/{id}/` | any domain's user | the message's own domain; at most 20 mail recipients, each a single mailbox (a group address gets 400) |
| `POST /rest/repo/upload/{folder}` | any repository folder | the temporary area only |

Message tags: a TEMP tag belongs to its creator; DOMAIN tags need the user role; a locked PUBLIC or
DOMAIN tag is changed by an admin only, while a PRIVATE or TEMP tag stays its owner's whatever its lock
(since 2026-11-13; an owner had locked themselves out); and a PUBLIC tag that records a domain is changed,
and unlocked, from that domain only. A PUBLIC tag no longer makes a draft readable without the user role.

Tokens and tickets: a Keycloak realm role grants nothing; a token issued to a client outside
`niord.oidc.allowed-clients` is answered 401; the `NiordDomain` header adds roles only for a stored,
active domain; and a ticket is bound to the domain it was issued for.

### Limits for anonymous callers

- **Searches:** `maxSize` is kept within 1 to 1000, for every caller. A caller with no identity has its
  free text matched as literal words, cut to 256 characters, and cannot filter on user names or
  comments. `search-message-ids` matches public messages only for a caller without the user role, and
  keeps `maxGroupCount` within 1 to 50.
- **PDFs:** a caller with no identity takes one of `niord.reports.anonymous-pdf-concurrency` permits (3);
  when none is free the answer is 503. Its PDF is rendered before the first byte is sent, and without
  map thumbnails.
- **Legacy publications:** `/rest/publications/search` and `/all` serve the public tier only, at most
  1000 rows. `/rest/publications/publication/{ids}` and `/rest/publication-issues/by-ids` withhold the
  file link of an unreleased issue from a caller without the user role.
- **Tiles and icons:** an AtoN tile outside zoom 0 to 20 or outside its zoom level's grid gets 400, and
  an SVG icon wider or taller than 1000 pixels, or scaled beyond 10, gets 400. The overview icon is
  rendered in memory and no longer written to the repository.
- **Images:** when the `vipsPath` setting is not set, a thumbnail source above 100 megapixels gets the
  file-type icon instead.
- **SECOM:** a Get or GetSummary page holds at most 100 items.

### Files, mail and PDFs

- Repository files carry `X-Content-Type-Options: nosniff` and, except PDFs,
  `Content-Security-Policy: sandbox`. Raster images, PDF, audio and video open inline; every other file —
  HTML, SVG and XML among them — downloads as an attachment. A batch data file always downloads.
- An uploaded file keeps only its bare name: `/` and `\` separate folders, and a name that leaves
  nothing usable gets 400. Archive entries that would land outside their folder are refused.
- PDFs and mails load images and style sheets from the backend's own `baseUri` origin only. A mail keeps
  an http(s) or `data:` image from elsewhere as an address for the mail client and drops any other
  foreign resource. A comment mail carries the comment cleaned of scripts and images.
- A mail made from a message — a mailing-list mail, the mail dialog's mail and the message in a comment
  mail — carries the message's texts cleaned: scripts, event handlers, styles and resources from
  elsewhere go, and relative links and images become absolute. The message keeps its formatting: links,
  the classes of the frontends' message-HTML vocabulary (`fe/shared-html/message-html-classes.ts`) on
  the elements they are meant for — the position tables and lists of the geometry templates, the
  editor's `no-border`, `condensed` and `underline` tables and the `no-wrap` span — and images from the
  backend's own `baseUri` origin, with their alternative text and a plain-length width and height. Any
  other class goes, and so does an image from another origin or a `data:` image. The text of a comment
  keeps the stricter list: no classes and no images. The mailing-list report and a trigger's test run
  show the same cleaned texts. The stored message is unchanged. An attachment image whose width or
  height is not a plain CSS length (a number with an optional unit) shows at its natural size in a
  mail, and one whose path holds `"`, `<`, `>` or `&`, which the repository never writes, is left out.
- The mail dialog's and comment mails print the recipient's name in the greeting, and the sender's
  name in the regards, as text: markup in a name shows as typed and does not reach the mail as HTML.
- Saving a message whose short id, or the message id of one of its references, holds `<`, `>`, `"` or
  `&` gets 400, unless the stored message already has that id. The message import and the
  message-archive import refuse such an id too. Every short-id format in use passes.
- A `param:` value of a message-report PDF is printed as text, and cannot replace a value the endpoint
  sets itself.
- A template parameter that contains the start of a markup tag gets 400.
- A curation include of a message that is not public gets 409 (`OVERRIDE_MESSAGE_NOT_PUBLIC`).
- The mailing-list report HTML is sandboxed: no script runs in it.
- S-124 generation errors are answered 400 in plain text.

### New settings, properties and chart values

| Name | Kind | Default | What it does |
|---|---|---|---|
| `niord.reports.anonymous-pdf-concurrency` | Spring property | 3 | The anonymous PDFs made at once; at least 1. |
| `niord.oidc.allowed-clients` (`NIORD_OIDC_ALLOWED_CLIENTS`) | Spring property | `niord-web` and the base client | The Keycloak clients whose tokens the API accepts; one comma-separated string. |
| `niord.secom.outbox-dispatch-cron` | Spring property | `37 * * * * ?` | The SECOM outbox schedule; `-` switches it off. |
| `authServerTrustAllCertificates` | setting, not editable | false | Development only: skip TLS verification on the Keycloak admin connection. |
| `secomAllowInsecureEndpoints` | setting | false | Test environments only: allow SECOM deliveries to http and to non-public addresses. |
| `secomMaxSubscriptionsPerNode` | setting | 50 | The subscriptions one remote SECOM node may hold. |
| `app.oidc.allowedClients` | chart value | empty (the application's default) | Rendered as `niord.oidc.allowed-clients`; a value replaces the default, so name `niord-web` too. |
| `secom.settings.secomAllowInsecureEndpoints`, `secom.settings.secomMaxSubscriptionsPerNode` | chart values | empty | Passed to the settings above; `deploy.sh --secom-client` sets the first for the test client. |
| `authServerUrl` | environment variable in the chart | the in-cluster Keycloak Service | The Keycloak address of the backend's admin calls, set by `app/deployment.yaml`. |

Operators also notice:

- **Keycloak TLS is verified.** A relative `authServerUrl` resolves against the `baseUri` setting, never
  against the request's host. If the backend reaches Keycloak over https with a certificate the JVM does
  not trust, user and domain administration fails until the certificate is in the trust store. The chart
  uses the in-cluster http address and is not affected.
- **The log** masks the values of password settings and the WMS token, and logs ticket issuance at DEBUG.
- **The WMS proxy** forwards only the standard GetMap parameters.
- **Jackson** is pinned at 3.1.7 and 2.21.7 in `be/pom.xml`, above what Spring Boot manages; remove the
  override once Boot manages those releases or later ones.
- **Dependabot** opens security-update pull requests only, one grouped pull request per ecosystem
  (Maven, npm, GitHub Actions; the SECOM test client's Maven updates in one of their own), and no
  version updates (`.github/dependabot.yml`).
- **Publishing** starts only from a push to `main` in this repository, and a break-glass run only from
  `main`. The `harbor` environment already limits its deployments to `main` (checked 2026-09-25); a
  required reviewer on it is the owner's decision. The publish job's Harbor credentials are secrets of
  that environment. A private repository keeps environment secrets and branch rules only on a paid
  GitHub plan (Pro, Team or Enterprise), so recheck both after any change of owner or plan.

---

## 8. Residual items and where they are tracked

New entries in [`be/docs/potential-errors.md`](../be/docs/potential-errors.md), in its security-review
section:

- **SECREV-1** — mailing-list trigger filters run on the sandboxed engine but are still handed the
  managed message entity.
- **SECREV-2** — the SECOM outbox dispatcher reads its due entries without claiming them. Fixed on
  2026-11-12 (each entry is claimed with a lease before it is sent, `be/docs/secom-design.md` D6) and
  removed from that file again.
- **SECREV-3** — `LineDirective` can pass a line length of zero or less to `TextUtils.maxLineLength`.
  Fixed 2026-11-12 and removed from that file again: a length below 1 is refused.
- **SECREV-4** — the chart's `database-user` script runs as the superuser, with the default search path,
  inside the database the application's user owns (the second recheck of 2026-11-12, section 5). Fixed
  the same day and removed from that file again.

The same file's SECAUD-12 now carries F-X64 as accepted; SECAUD-15, MASSASSIGN-2, MASSASSIGN-3 and
RENDER6 are removed, because the sweep closed them; and U6, SECAUD-9, LUCENE-2, TMPL6, PORT-5 and REPO3
now describe the code as the sweep left it. Since then LUCENE-2 was fixed and removed from that file,
and in November 2026 TMPL6 and REPO3 were too (what is still open of REPO3, a file name with a
semicolon on the public site, is REPO6 there now); and the open RENDER6 there now (the message-list
PDF's labels and time stamp) is a later, unrelated entry under the reused code.

New items in [`fe/docs/backlog.md`](../fe/docs/backlog.md):

- the deferred deployment findings, under "Before production go-live";
- printing and exporting with a ticket that carries no role;
- the administration app does not read roles on the base client;
- the tag dialog offers changes to another domain's PUBLIC tags that the backend refuses;
- the anonymous AtoN overview icon endpoint, which nothing calls;
- a page of the public site's own for the anonymous PDF limit.

### The owner's rulings of 2026-09-26

The sweep left five options open. The owner ruled on each of them on 2026-09-26, and a follow-up to the sweep implements the
rulings.

| # | Option the sweep did not take | The owner's ruling | What was done |
|---|---|---|---|
| 1 | Sanitising message HTML on the server (F-X112, F-X145, F-X146), and escaping dictionary text in the FreeMarker templates (N-04) | Clean subscriber mails with the allow-list the comment mail already uses; stored message HTML stays as it is. N-04 is accepted: only a sysadmin can upload dictionaries since P08. | `MailHtml` holds both allow-lists. A comment keeps the comment mail's list (formatting and links; no classes, images, styles or scripts). The texts of each message — title, vicinity, publications, source, the subject and details of each part, the description of each reference, the caption of each attachment — are cleaned before a mailing-list template runs, and before `MessageMailService` renders the mail dialog's mail and the message in a comment mail. The ruling is about active and foreign content, not the message's formatting, so a message text keeps, on top of the comment list, the classes of the frontends' message-HTML vocabulary (`fe/shared-html/message-html-classes.ts`, mirrored in `MailHtml` and held to it by `MailHtmlTest`) on the elements they are meant for, and images from the backend's own `baseUri` origin with their alternative text and a plain-length size; any other class, foreign or `data:` images, styles and scripts go. The path, width and height of an attachment, which the templates print into the attributes of its image, are checked there too, and the SafetyNET cancellation mail escapes the SafetyNET id. `MessageMailService` escapes the recipient's and the sender's names, which `message-mail.ftl` prints into the greeting and the regards, before it renders the mail, so every stored copy of the template is covered. F-X146 is fixed. |
| 2 | Validating a message's short id when it is saved (F-X21) | Refuse `<`, `>`, `"` and `&` in a message short id when a message is saved (400). Formats with slashes and spaces, such as `FA/EK-D-371 2017` and `A/1 2024`, stay valid. | Creating and updating a message refuse such a short id with 400, and such a message id of a reference, which names another message by its short id; an id the stored message already has is kept, so a message saved before stays editable. The message import and the message-archive import refuse them too. Ids Niord generates are not checked. The Niord-integration import, which reads another Niord server rather than a request, is not checked. Accepted edge: a copy made with a reference to a message whose short id was saved with such a character before the rule is refused when it is saved; no short-id format in use produces one. The editor shows its general save error for the 400, not the reason (`fe/docs/backlog.md`, Editor). |
| 3 | Referencing GitHub's own actions by commit SHA, and a Dependabot configuration (F-X108) | `actions/*` on major tags is accepted. Dependabot alerts and security updates are switched on (done on 2026-09-26). | `.github/dependabot.yml` opens no version-update pull requests and groups the security updates of each ecosystem into one pull request: Maven under `be/` (the SECOM test client, outside the reactor, in a pull request of its own), npm under `fe/`, GitHub Actions. |
| 4 | Per-country database users (F-X100) | Stays deferred to the go-live recheck, with a narrower goal: the application connects as a non-superuser owner of its own database. One Postgres per country (design §6) already separates DK and GL. | Section 5. |
| 5 | Who may receive a message mail or a comment mail (F-X68, N-07) | Accepted as it is: own domain only; attributable (Reply-To, the scheduled-mail record, the log); comment mails capped at 20 single mailboxes. | Section 6. |
