# Test cluster: from Ingress to Gateway API

**Status: stopped on 2026-10-02 and rolled back.** The test cluster stays on Ingress. This
page is kept as the record: what was planned, what was built, what was learned. Section 0
says why it stopped and what was undone. Everything from section 1 on is the plan as it
stood on 2026-10-01, and is not being carried out.
**Downtime is fine (the owner's ruling, 2026-09-30).** The test cluster has one user, the
owner, and may be offline for a day. Wherever being offline makes a step simpler, the step
takes the offline path; no step is split or ordered to keep the stagings up.
**Scope:** the test cluster only (staging DK, staging GL, the Argo CD UI, future test
environments). Prod has no Traefik and is not touched.
**Repos:** devops-shared (platform), niord-ng (the chart), devops-niord (environments).

---

## 0. Stopped on 2026-10-02

**Why.** Ingress could do everything this cluster needs but one thing: pass SECOM's mTLS
through Traefik untouched (step 6, a TLSRoute). That was what tipped the choice. The draft
of SECOM Edition 2 (IEC 63173-2, 80/1198/CDV, vote closing 2026-11-13) removes the mTLS
requirement: the caller's identity comes from the certificate in the signed envelope, and
the requests that carried none become POSTs with an envelope. Without mTLS a SECOM request
is ordinary HTTPS, which Traefik ends like any other, and an Ingress carries it. The owner
ruled the same day: stop, go back to Ingress, remove everything that was added.

**What had been done, and how it was undone.**

| Step | Done on 2026-10-01 | How it is undone |
|---|---|---|
| 1 | cert-manager on the test cluster, the wildcard certificate in a Secret (devops-shared merge requests 37, 38) | devops-shared removes the component, then its project entries; the CRDs, the namespace and the two Secrets are deleted by hand |
| 2 | the TLSRoute CRD as a file under root (devops-shared merge requests 39, 40, 41) | devops-shared removes the file; the CRD is deleted by hand |
| 3 | the chart rendered an HTTPRoute, published as 4.0.46 (pull requests 118, 119) | pull request 118 reverted: the chart renders an Ingress again, with `proxy.ingress`. 4.0.46 is the one version that renders an HTTPRoute; no environment ever pinned it |
| 4 | the switch: a draft, never pushed | discarded |
| 5, 6 | not started | — |

Traffic was never touched. The stagings ran chart 4.0.38 with their Ingress the whole time,
and Traefik kept serving its own certificate.

**What stays true, and is worth knowing if this is taken up again.**

- **The host installs the Gateway API CRDs.** A Tanzu package brings the Standard channel
  of v1.4.0 with the cluster, six CRDs, and its controller puts them back within minutes
  if they are changed. They cannot be taken over (section 2.6).
- **Traefik v3.7 needs a TLSRoute CRD to route by Gateway API at all**, and the host's
  v1.4.0 has none. With the host's six alone it serves nothing.
- **Traefik v3.7.13 works against the host's v1.4.0 plus the TLSRoute CRD of v1.5.1**:
  Gateways, HTTPRoutes, TLS passthrough, and the same forwarded headers as with Ingress
  (steps 2 and 3).
- **Argo CD v3.5.3**: a failed automated sync is retried five times and then waits for a
  new commit; it puts no tracking mark on CRDs and does not prune one; removing an
  Application while one of its ACME challenges is pending hangs (step 1).
- **The Traefik chart's switch for entry-point TLS** is `ports.<name>.http.tls.enabled`
  (step 4).
- **Look at the real cluster before building.** The first attempt at step 2 was rehearsed
  on a clean cluster and failed on the real one.

If another need for Gateway API turns up, this page is where to start from. The SECOM
question is settled: the CDV's clause 4.3.3 puts the TLS layer on Internet-CA certificates
and the MCP identity in the envelope (2026-10-14; devops-shared's open issue 5 was deleted,
its record is `docs/mTLS-support-removed.md` there).

---

## 1. Summary

- **End state:** the test cluster routes with **Gateway API only**. Traefik stays as the
  controller. Ingress goes everywhere, the niord chart's Ingress template included.
- **When:** before staging goes public (devops-shared open issue 8). Going public is at least
  a couple of weeks away.
- **How:** in one switch. Step 4 turns Gateway API on and Ingress off in the same merge
  request; the stagings are down from then until step 5 pins the new chart. Traefik could
  serve both APIs side by side and the move could be made without downtime, at the cost of
  one more merge request and a longer period with two fences; the ruling above makes that
  not worth having.
- **Going public afterwards** is one line per staging (add the `public` Gateway to the route),
  plus the Cloudflare records. That replaces the Traefik annotation in issue 8's checklist.
- **Six steps**, each one merge request or pull request, each proved on OrbStack first. The
  first three change nothing anyone can see. Step 4 is the switch.

| # | Repo | What | Visible effect |
|---|---|---|---|
| 1 | devops-shared | cert-manager fetches the wildcard certificate into a Secret — done 2026-10-01, **rolled back** | none |
| 2 | devops-shared | the host's Gateway API CRDs stay; we add the one that is missing, TLSRoute (redrawn, section 2.6) — done 2026-10-01, **rolled back** | none |
| 3 | niord-ng | chart renders an HTTPRoute instead of an Ingress; published — done 2026-10-01 as chart 4.0.46, **reverted** | none (never deployed) |
| 4 | devops-shared | the switch: Gateways on, Ingress off, fence rewritten, Argo CD UI moved — **not done** | stagings down until step 5 |
| 5 | devops-niord | stagings pin the chart from step 3 — **not done** | stagings back |
| 6 | devops-shared, niord-ng, devops-niord | SECOM mTLS through a TLSRoute (open issue 5) — **not done; the reason it was needed is gone** | SECOM reachable through Traefik |

---

## 2. Background

### 2.1 Ingress and Gateway API in short

Both are Kubernetes APIs for one job: describe how traffic from outside the cluster reaches
Services inside it. Neither routes anything. A controller (here Traefik) watches the objects
and configures itself. Traefik reads both.

**Ingress** (built into Kubernetes, stable since 1.19) is one object type. It can say: host
and path go to a Service and port; this Secret is the certificate; this IngressClass serves
it. That is all. Everything else is a controller-specific annotation: free text, not
checked, not portable. It carries HTTP(S) only; TCP and TLS passthrough need controller
extras. The API is frozen: no new features, but not deprecated. The most used controller,
the community ingress-nginx, was retired in March 2026.

**Gateway API** (v1.0 in 2023, installed as CRDs) is the successor. Three ideas:

1. **Split by role.** `GatewayClass` (which controller), `Gateway` (ports, protocols,
   hostnames, certificates, and which namespaces may attach; owned by the platform), and
   routes: `HTTPRoute`, `GRPCRoute`, `TLSRoute` (owned by the app).
2. **Attaching takes both sides.** A route names its Gateway (`parentRefs`); the Gateway's
   listener says which namespaces and kinds it accepts (`allowedRoutes`). Every route
   reports, per Gateway, whether it was accepted and why not.
3. **Typed features.** Header, query and method matching, weighted backends, header
   changes, redirects, rewrites, mirroring, timeouts, CORS are fields in the spec, not
   annotations. Controller extras are typed references (`ExtensionRef`), not strings.

Since Gateway API v1.5 (2026-02-27), `TLSRoute` (SNI routing, including passthrough) and
`ListenerSet` (listeners added to a shared Gateway from other namespaces) are in the stable
Standard channel.

The costs: the CRDs are not part of Kubernetes, so someone installs and upgrades them, one
version per cluster. On this cluster the host does that (2.6). A listener takes its
certificate from a Secret. Authentication and rate
limiting are still controller-specific. And the typed features cut both ways: a route's
`URLRewrite` filter is a feature for the app and a bypass for a platform rule that looks at
the original path (3.2, rule 3).

### 2.2 How the test cluster routes today

```
browser: https://niord-dk-staging.brs-test.e-navigation.dk/
   │  DNS *.brs-test.e-navigation.dk → Traefik's internal address (Cloudflare, DNS only)
   ▼
Traefik 3.7.13 (chart 41.6.0, namespace traefik, devops-shared platform/test/traefik/)
   • entry point websecure: ends TLS with the wildcard certificate
     (Let's Encrypt, DNS-01 via Cloudflare, kept in acme.json on a volume)
   • route by Host, from the environment's Ingress (class traefik)
   │  plain HTTP
   ▼
nginx (the environment's namespace, port 8888)
   ├─ /auth → Keycloak
   └─ /     → Niord
```

The pieces, and where they live:

- **Two addresses.** Service `traefik` (entry points `web`, `websecure`) is internal: Citrix
  and the jump server. Service `traefik-public` (entry points `public-http`, `public-https`,
  pod ports 8001/8444) is the one the host will forward from the Internet. `web` and
  `websecure` are the defaults, so an Ingress without an annotation stays internal. An app
  goes public with the annotation
  `traefik.ingress.kubernetes.io/router.entrypoints: websecure,public-https`.
- **The fence** (ValidatingAdmissionPolicy `traefik-ingress-fence`, in Traefik's values).
  For every Ingress of class `traefik`: the host is the namespace's own name under
  `brs-test.e-navigation.dk`; no `defaultBackend`; no `tls` section; no Traefik annotation
  but `router.entrypoints`; only the namespaces in `publicNamespaces` may name a public
  entry point.
- **Keycloak's admin pages stay internal.** A route in Traefik's file provider answers 403
  for `/auth/admin` and `/auth/realms/master` on `public-https`, for every hostname,
  above every other route (priority 1000000). It carries no `tls` of its own: it is a TLS
  router today only because the entry point's TLS settings make every router on
  `public-https` one.
- **The Argo CD UI** is an Ingress in `argocd` (`argocd/test/argocd-server-ingress.yaml`),
  internal only, pinned by the validator.
- **Traefik's CRD provider is off** on purpose (open issue 5 explains the risks of turning
  it on).
- **The niord chart** renders an Ingress when `proxy.ingress.enabled`
  ([`ingress.yaml`](../be/k8s/helm/niord/templates/nginx/ingress.yaml)); both stagings set
  it in devops-niord.
  With it on, nginx passes on Traefik's `X-Forwarded-Proto` and `-Port` instead of its own.
- **CI** (`scripts/validate_configs.py`) checks the entry points, the fence, the file
  provider and the Argo CD Ingress.
- **SECOM through Traefik** was the open question when this was written (open issue 5):
  a peer's client certificate would have had to reach nginx's port 8443. Settled since
  2026-10-14: Edition 2 has no client certificate, the chart has no 8443 listener, and
  SECOM is ordinary HTTPS through the Ingress.

### 2.3 Why change

With Ingress we rebuilt, by hand, what Gateway API provides:

| Need | Built today with Ingress | With Gateway API |
|---|---|---|
| The platform decides what is public | default entry points + an annotation + the `publicNamespaces` rule | the `public` Gateway's `allowedRoutes` lists the namespaces; the list lives in devops-shared, in the Gateway |
| Apps bring no certificates | the "no tls section" rule | built in: certificates sit on the Gateway's listeners |
| Apps use no other Traefik features | the "only router.entrypoints" rule | routes carry no annotations; Traefik middlewares would need the CRD provider, which stays off. The typed filters need a fence rule of their own (3.2, rule 3) |
| No catch-all route | the "no defaultBackend" rule | covered by the hostname rule (a route must name its host) |
| SECOM mTLS passthrough | not possible; needs the CRD provider and `IngressRouteTCP` | `TLSRoute`, Standard channel, no experimental flag (step 6) |
| Why a route does not work | mostly silent | `Accepted` / `ResolvedRefs` on the route |

The object ownership also matches the repos: devops-shared owns the Gateways, the niord
chart owns its routes, devops-niord chooses internal or public.

### 2.4 Why now

- Going public waits on the host for at least a couple of weeks (issue 8).
- Nobody but the owner uses the cluster, and it may be down for a day (the ruling at the
  top). So one switch with downtime, no period with both APIs, no environment-by-environment
  move.
- It is the smallest it will ever be: two environments, one chart template, prod not built.

### 2.5 Decisions taken

- **Traefik stays.** Once the chart speaks Gateway API the controller is replaceable, so the
  choice matters less. Traefik is one Deployment, meets Pod Security `restricted` with its
  defaults, and is already running. Envoy Gateway was the alternative: built-in auth and
  rate limiting, but a control plane that creates proxy Deployments, more than a test
  cluster needs.
- **Gateway API only.** The chart's Ingress template is deleted, not kept behind a switch. If
  prod's router turns out to understand only Ingress, the template comes back from git
  history.
- **One switch, with downtime.** The owner's ruling (top of the page). A no-downtime
  variant exists — Gateway provider on next to Ingress, stagings moved, Ingress off in a
  third merge request — and was in the draft of 2026-09-30 for a day; it is not simpler, so
  it is out. What it did teach stays: the rollback order after step 5 (step 4 says).
- **cert-manager issues the certificate.** A Gateway listener names its certificate as a
  Secret; Traefik's `acme.json` cannot be referenced. cert-manager was planned anyway.
- **The host's Gateway API CRDs are used as they are** (2026-10-01, section 2.6). The
  version is the host's: Standard v1.4.0 today. We add one CRD the host's set lacks and
  Traefik cannot run without, TLSRoute, and nothing else.
- **The TLSRoute CRD is one file in git, applied by the root Application** (the owner's
  decision, 2026-10-01). The alternative was a small chart of our own, pushed to Harbor by
  hand for every version. The file needs no Harbor step.
- **The file-provider 403 route stays**, and gets `tls: {}` of its own (it must not depend on
  entry-point TLS settings that step 4 removes). HTTPRoute has no standard way to answer
  403.
- **The chart's routes use no filters and only plain Service backends.** The fence enforces
  it (3.2, rules 3 and 4): the 403 route matches the original path, so a rewrite filter in
  a public namespace could turn `/kc-admin` into `/auth/admin/` behind its back.
- **Test users keep their passwords** (the owner's ruling, unchanged by this plan).

### 2.6 What the first attempt at step 2 found (2026-10-01)

**What was planned.** Step 2 would install the Gateway API CRDs ourselves: the Standard
channel of v1.5.1, eight CRDs, through the chart `traefik-crds`, as a platform component.
It was built that way, proved on OrbStack and merged (devops-shared merge request 39).

**What the cluster already had.** Six of the eight were there before we came:

| | |
|---|---|
| Who | the host's Tanzu package `gateway-api.tanzu.vmware.com`, version `1.4.0+vmware.2-tkg.1`; PackageInstall `test-cluster-brs-01-gateway-api` in `vmware-system-tkg` |
| Since | 2026-09-07, when the cluster was created |
| What | Gateway API Standard v1.4.0: GatewayClass, Gateway, HTTPRoute, GRPCRoute, ReferenceGrant, BackendTLSPolicy |
| What it lacks | TLSRoute and ListenerSet: both joined the Standard channel in v1.5. ReferenceGrant is served as `v1beta1` only |
| Kept in place by | the host's package controller (kapp-controller). It compares the CRDs with its own copy every few minutes and puts them back |
| In use | nothing: no GatewayClass, Gateway or route existed |

**What happened at the merge.**

- Argo CD created the two CRDs that were missing, TLSRoute and ListenerSet.
- It changed five of the host's CRDs to v1.5.1, at 12:16 UTC. The host's controller put
  them back to v1.4.0 at 12:21.
- It could not change the sixth, HTTPRoute:
  `metadata.annotations: Too long: may not be more than 262144 bytes`. The host's tool
  keeps a full copy of each CRD in an annotation (`kapp.k14s.io/original`, 196 674 bytes on
  HTTPRoute). Argo CD keeps one too (about 246 000 bytes for v1.5.1). Together they do not
  fit.
- Argo CD retried five times and stopped. The `gateway-api` Application is left OutOfSync
  and Failed. It tries again at every new commit on devops-shared's `main`, so nothing is
  merged there until the new step 2.

**What it cost.** Nothing for traffic: Traefik's Gateway API provider is off. The host's
CRDs are intact at v1.4.0, and its package still reports "Reconcile succeeded". The
cluster has two CRDs more than before.

**Why the proof did not catch it.** OrbStack was a clean cluster. This plan did name the
risk, "the host installs its own Gateway API CRDs one day", but as a future one. Nobody
looked at the real cluster first. Every step now starts by doing that (section 4).

**Why we do not take the CRDs over.** They are the host's, and the host's controller
restores them within minutes. Forcing our version in, with server-side apply, would get
past the size limit and start a tug-of-war that never ends: the CRDs would flip between
two versions several times an hour.

**What was tried next, on OrbStack** (Kubernetes 1.35, Traefik v3.7.13, chart 41.6.0):

- **The host's six at v1.4.0 plus only the TLSRoute CRD of v1.5.1:** Traefik's provider
  starts, a Gateway is programmed, an HTTPRoute is served over HTTP and HTTPS, and a
  TLSRoute passes TLS through untouched on the same port as the HTTPS listener (what step
  6 needs). The log has no errors.
- **The host's six alone:** Traefik serves nothing. It waits for the TLSRoute kind, logs
  `failed to list *v1.TLSRoute`, and answers 404 for every name.
- **ListenerSet is not needed.** Traefik v3.7.13 does not watch it.
- **Removing the `gateway-api` Application is safe.** The cluster's failed state was
  rebuilt (the six CRDs created first, each with a copy annotation of the host's size, then
  the Application, then the six put back). Deleting the Application left all eight CRDs:
  the host's six untouched, ours two kept.

**What changes in this plan.**

- Step 2 no longer installs a set of CRDs. It removes the component of merge request 39
  and adds the TLSRoute CRD alone (step 2 below).
- The Gateway API version is the host's, and it moves when the host upgrades the cluster.
  v1.4.0 has everything the plan uses: Gateway and HTTPRoute `v1`, `allowedRoutes`,
  HTTPS and TLS passthrough listeners. It has no ListenerSet, which this plan did not use
  (section 6).
- The size limit of the HTTPRoute CRD, and the skew check against v1.5.1, are no longer
  ours to manage. The skew that matters is Traefik against the host's version; steps 3 and
  4 prove on the host's version.
- A new risk: a Tanzu upgrade that brings Gateway API v1.5 or later brings TLSRoute too,
  and the host's controller will then want the CRD we installed (section 5).

---

## 3. Target design

### 3.1 Objects (sketch; the exact YAML is settled in the steps)

```yaml
# devops-shared, namespace traefik: the certificate
apiVersion: cert-manager.io/v1
kind: Issuer                     # namespaced on purpose: it reads a Secret in its own namespace
metadata: { name: letsencrypt, namespace: traefik }
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    privateKeySecretRef: { name: letsencrypt-account }
    solvers:
      - dns01:
          cloudflare:
            apiTokenSecretRef: { name: cloudflare-dns, key: token }   # the existing sealed Secret
---
apiVersion: cert-manager.io/v1
kind: Certificate
metadata: { name: brs-test-wildcard, namespace: traefik }
spec:
  secretName: brs-test-wildcard
  dnsNames: ["*.brs-test.e-navigation.dk"]
  issuerRef: { kind: Issuer, name: letsencrypt }
---
# devops-shared, namespace traefik: the two Gateways
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata: { name: internal, namespace: traefik }
spec:
  gatewayClassName: traefik
  listeners:
    - name: https
      protocol: HTTPS
      port: 8443                       # websecure's pod port: Traefik matches listeners to entry points by port
      hostname: "*.brs-test.e-navigation.dk"
      tls: { mode: Terminate, certificateRefs: [{ name: brs-test-wildcard }] }
      allowedRoutes:
        kinds: [{ kind: HTTPRoute }]
        namespaces: { from: All }      # the fence decides which hostname each may use
---
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata: { name: public, namespace: traefik }
spec:
  gatewayClassName: traefik
  listeners:
    - name: https
      protocol: HTTPS
      port: 8444                       # public-https's pod port
      hostname: "*.brs-test.e-navigation.dk"
      tls: { mode: Terminate, certificateRefs: [{ name: brs-test-wildcard }] }
      allowedRoutes:
        kinds: [{ kind: HTTPRoute }]
        namespaces:
          from: Selector
          selector:
            matchExpressions:          # set by the API server, not editable by the deploy repo
              - key: kubernetes.io/metadata.name
                operator: In
                values: [niord-dk-staging, niord-gl-staging]   # the public list; never argocd
---
# niord chart, the environment's namespace
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata: { name: <release>-proxy, namespace: niord-dk-staging }
spec:
  parentRefs:
    - { name: internal, namespace: traefik }
    # - { name: public, namespace: traefik }   # going public (issue 8)
  hostnames: [niord-dk-staging.brs-test.e-navigation.dk]   # externalBaseUri's host
  rules:
    - backendRefs: [{ name: <release>-proxy, port: <proxy.service.port> }]
      # no matches (PathPrefix / by default), no filters, a port number (HTTPRoute takes
      # no port name, unlike the Ingress's `http`)
```

A route that names `public` from a namespace not on the list is not rejected at admission.
The public Gateway refuses it, the route's status says so, and it is not served there. The
effect is the same as today's admission refusal.

### 3.2 The fence after the move

A new policy, `traefik-route-fence`, on HTTPRoutes (and TLSRoutes from step 6) and on
Gateways. Four rules:

1. **Hostnames.** Every HTTPRoute names at least one hostname, and each is its namespace's
   own name under `brs-test.e-navigation.dk`. The `traefik` namespace may also use
   `smoke.brs-test…` (the smoke test). Step 6 adds a separate TLSRoute rule for
   `<namespace>-secom.brs-test.e-navigation.dk`; it must not permit the web hostname on a
   TLSRoute or the SECOM hostname on an HTTPRoute.
2. **No Gateway outside `traefik`.** Traefik serves every Gateway whose class carries its
   controller name, whatever the class is called. A Gateway in an app namespace could open a
   listener on the public port with a certificate of its own, and bypass the public
   Gateway's list. So a Gateway in any namespace but `traefik` is refused, regardless of
   class: there is no other controller on the cluster, so no other Gateway is legitimate.
   (There are no ListenerSets on this cluster: the host's v1.4.0 has no such kind. Where
   the kind exists, a Gateway accepts none unless it says so, and ours do not.)
3. **No filters.** A rule carries no `filters`, and a backend carries none. Traefik 3.7
   implements `RequestRedirect`, `RequestHeaderModifier`, `ResponseHeaderModifier`,
   `URLRewrite` and `ExtensionRef` as middlewares that run *after* the router has matched.
   The 403 route matches `/auth/admin` on the path the client sent, so a public route
   matching `/kc-admin` and rewriting it to `/auth/admin/` would reach Keycloak's console
   through the public address. The chart needs no filter, so none is allowed.
4. **Plain Service backends only.** Every `backendRefs` entry has no `group` (or `""`), kind
   `Service` (or unset) and no `namespace`. Traefik also accepts `TraefikService` backends,
   including its own `api@internal` (the dashboard), even with the CRD provider off.
   Leaving `crossProviderNamespaces` unset allows these references: Traefik 3.7.13 treats
   a nil allowlist as unrestricted, and chart 41.6.0 emits no flag for an empty list.
   This admission rule is the protection; the provider default supplies no extra restriction.

`allowedRoutes.kinds: [HTTPRoute]` keeps GRPCRoute out, so the fence need not cover it.

The validator (`scripts/validate_configs.py`) keeps checking, rewritten for the new
objects: the public list never holds `argocd`; the Gateways' ports match the entry points;
the chart's default Gateway is off; the fence restricts backends to same-namespace core
Services; the file provider
holds only the 403 route, with `tls: {}` and a priority above the floor. **The floor moves:**
today it is 100 000 with the note that Ingress routers rank far below. Traefik gives Gateway
API routers computed priorities: 100 000 for an exact path, 10 000 + 100 per character for a
prefix, +1 000 for a method, +100 per header, +10 per query parameter, plus the host's
length. An exact-path route with a method and a few headers passes 100 000. The route stays
at 1 000 000; the floor becomes 500 000.

### 3.3 What stays and what goes

**Stays:** Traefik and its chart; both Services and all four entry points; the HTTP→HTTPS
redirect on the entry points; `aliasHeadersStrategy: delete`; untrusted incoming
`X-Forwarded-*`; the file-provider 403 route (with `tls: {}` added); the CRD provider off;
`crossProviderNamespaces` unset (unrestricted; fence rule 4 enforces the backend restriction);
the Cloudflare token Secret (cert-manager reads it now).

**Goes (step 4):** Traefik's Ingress provider and the `traefik` IngressClass; the ACME
resolver, the `acme.json` volume, `fsGroup` and the `Recreate` strategy (Traefik becomes
stateless, so rolling updates and a second replica become possible); the Cloudflare token
in Traefik's environment; the entry-point TLS settings; the fence `traefik-ingress-fence`;
the Argo CD UI's Ingress; the entry-points annotation everywhere. **Gone in step 3:** the
chart's Ingress template and `proxy.ingress` values.

---

## 4. Steps

Each step: one merge request or pull request, proved on OrbStack before review, merged,
checked on the cluster, then the next step is agreed. Every step names its rollback.

**Each step starts with a look at the real cluster** (since 2026-10-01, section 2.6):
before anything is built, the owner runs a few read-only commands on the jump server, and
the proof on OrbStack starts from what they show, not from a clean cluster.

### Step 1 — devops-shared: cert-manager and the certificate (done 2026-10-01, rolled back 2026-10-02)

Merged as devops-shared merge request 37; the result is recorded in its
`runbooks/bootstrap.md`. What was built, with the five places where it differs from the
plan of 2026-09-30 marked **(changed)**:

- Platform component `platform/test/cert-manager/` (chart `cert-manager` v1.21.2; the `v`
  is part of the chart's version). `crds.enabled: true`;
  `global.leaderElection.namespace: cert-manager` (the chart's default is `kube-system`,
  outside the platform project); `startupapicheck.enabled: false` (one image fewer).
- **Images from Harbor, like every platform component.** **(changed)** The chart has one
  top-level `imageRegistry` value (with `imageNamespace`, default `jetstack`), so there
  are no three repository overrides: `imageRegistry: reg.sitcloud.dk/cu3504-mssb-brs`
  gives `…/jetstack/cert-manager-{controller,webhook,cainjector}`. The pull Secret
  `harbor-pull` is sealed for namespace `cert-manager` (the step's one new sealed Secret)
  and named in `global.imagePullSecrets`, as objects with a `name`: the chart copies the
  list onto its three ServiceAccounts unchanged.
- The Issuer and Certificate of 3.1, in namespace `traefik`, through the chart's
  `extraObjects` (strings, which the chart runs through Helm's template engine).
  **(changed)** They carry only a sync wave (`argocd.argoproj.io/sync-wave: "1"`).
  `SkipDryRunOnMissingResource` is not needed: the CRDs are part of the same sync. The
  wave is needed: without it the Issuer is refused, because cert-manager's webhook has no
  pods yet, and the sync hangs. The Issuer reads the existing `cloudflare-dns` Secret in
  `traefik`: no new Cloudflare Secret. It is a namespaced `Issuer` for that reason, and
  for a second one: the token can edit the whole `e-navigation.dk` zone, and only a
  Certificate in `traefik`, where only devops-shared writes, can use a namespaced Issuer.
- `argocd/test/platform-project.yaml`: destination and Namespace `cert-manager`, CRDs
  `*.cert-manager.io`, Mutating- and ValidatingWebhookConfiguration `cert-manager-webhook`
  (both cover cert-manager's own kinds only). **(changed)** Also an
  `orphanedResources.ignore` list for three Secrets cert-manager writes itself:
  `cert-manager-webhook-ca`, `brs-test-wildcard` and `letsencrypt-account`. Without it the
  cert-manager and the Traefik Application both carry an orphan warning for good.
- Validator and tests: cert-manager's values hold exactly one Issuer and one Certificate,
  both in `traefik` and in a later sync wave, the Certificate for the wildcard only; the
  leader-election namespace is held too.
- **Nothing changed for traffic:** Traefik keeps serving its own certificate.
- **Proof on OrbStack** (Kubernetes 1.35, Argo CD v3.5.3, Pod Security `restricted`), with
  the branch's project file and values: nine fresh installs synced on the first attempt;
  the real Issuer registered with Let's Encrypt's staging server and created the DNS
  challenge; the project refused a ClusterIssuer and the default leader-election
  namespace.
- **Result on the cluster, 2026-10-01:** the `cert-manager` Application Synced and
  Healthy; `kubectl -n traefik get certificate brs-test-wildcard` shows `Ready=True`; the
  Secret's certificate is from Let's Encrypt (issuer `YR2`), valid until 2026-12-30.
  **(changed)** It has no subject, only the name `*.brs-test.e-navigation.dk` as a
  subject alternative name: cert-manager asks for no subject. So the check reads the
  alternative name.
- **Rollback:** revert. **(changed)** Argo CD deletes the Issuer and the Certificate with
  the Application. What stays, and is removed by hand: the `cert-manager` namespace (Argo
  never deletes a namespace it created; it still holds `cert-manager-webhook-ca`), the
  six CRDs (the chart's default `crds.keep: true` stamps them
  `helm.sh/resource-policy: keep`, which Argo CD honours), and the Secrets
  `brs-test-wildcard` and `letsencrypt-account` in `traefik`. If a challenge is pending
  when the Application is deleted, the deletion hangs: the challenge's finalizer needs
  the Issuer, which is already gone. Clearing the finalizer by hand lets it finish
  (devops-shared `platform/test/cert-manager/config.yaml`, "REMOVING IT").
- Let's Encrypt allows five certificates a week for the same name. This was one.

Two facts about Argo CD v3.5.3, measured in the proof, that the later steps rely on: an
automated sync that fails is retried five times, over about five minutes; after that
Argo CD waits for a new commit or a manual Sync. And orphan warnings come from the
Application's own destination namespace only.

### Step 2 — devops-shared: the one missing CRD (redrawn and done 2026-10-01, rolled back 2026-10-02)

The first attempt is in section 2.6. This step replaced it. Merged as devops-shared merge
request 40; the result is recorded in its `runbooks/bootstrap.md`, and what is left open
in its open issue 9.

- **The component of merge request 39 is removed:** `platform/test/gateway-api/`, its
  validator rule and tests, the `*.gateway.networking.k8s.io` entry in
  `argocd/test/platform-project.yaml`, and its places in the docs. With the directory gone,
  the ApplicationSet deleted the `gateway-api` Application, and Argo CD stopped touching
  the host's CRDs. The CRDs themselves stayed.
- **The TLSRoute CRD is one file:** `argocd/test/gateway-api-tlsroute-crd.yaml`, the
  `tlsroutes.gateway.networking.k8s.io` document of the upstream Standard bundle v1.5.1,
  unchanged but for one annotation, `argocd.argoproj.io/sync-options: Prune=false`. The
  root Application applies every file in that directory, so it applies this one. It took
  over the CRD the first attempt had created, which had the same content.
- **Why root, and not a platform component.** The platform ApplicationSet takes only
  charts, and there is no upstream chart of this one CRD. A chart of our own would be
  built and pushed to Harbor by hand for every version. Root already holds the few plain
  objects that belong to no chart, like the Argo CD UI's Ingress. Root runs in the `default`
  project, which is not fenced, so the validator is the fence: it allows the kind
  `CustomResourceDefinition` in `argocd/<cluster>/` only in this file, under this name,
  on the Standard channel, and with `Prune=false`.
- **`Prune=false` is a safeguard, not the mechanism.** Deleting the file must leave the
  CRD, and with it every TLSRoute. In the rehearsal Argo CD v3.5.3 left the CRD alone even
  without the annotation: it puts no tracking mark on CRDs, so it does not see one as its
  own once the file is gone. The annotation makes the outcome certain, whatever a later
  version does.
- **The size limit is not in play.** The TLSRoute CRD is 81 KB, far below the 256 KiB an
  object's annotations may hold, and the host's tool keeps no copy on it.
- **Rehearsal on OrbStack** (Kubernetes 1.35, Argo CD v3.5.3), from the cluster's state,
  with the real `root.yaml`, platform ApplicationSet and platform project read from a git
  server in the cluster:
  - the host's six CRDs created first, each with a copy annotation of the host's size,
    then `main`: the `gateway-api` Application failed as on the cluster, with the same
    message;
  - the branch on top: within 15 seconds the Application was gone and root was Synced with
    the TLSRoute CRD; the host's six were untouched; the `status` block of the upstream
    file causes no difference;
  - another reconcile by the host's controller (simulated): root stays Synced;
  - the file deleted: the CRD stays;
  - Traefik v3.7.13 with its provider on, against exactly these seven CRDs: a Gateway with
    an HTTP, an HTTPS and a passthrough listener programmed, an HTTPRoute served, a
    TLSRoute passing TLS through, no errors.
- **Result on the cluster, 2026-10-01:** no `gateway-api` Application; `root` Synced and
  Healthy; seven CRDs, the host's six at v1.4.0 and `tlsroutes` at v1.5.1; the host's
  package reports "Reconcile succeeded". The ListenerSet CRD the first attempt had also
  created was deleted by hand. Nothing changed for traffic.
- **Rollback:** delete the file. The CRD stays; remove it by hand if it must go, once no
  TLSRoute exists (`kubectl delete crd tlsroutes.gateway.networking.k8s.io`). Do not
  revert merge request 40 as a whole: that brings the failing component back.
- **When the host's package brings TLSRoute itself** (a Tanzu upgrade to Gateway API v1.5
  or later): delete our file before that upgrade, so the host's controller finds a CRD
  nobody else manages. How that controller treats a CRD it did not create is not tried;
  it is devops-shared's open issue 9, asked with host question H6, and checked before a
  cluster upgrade.

The rehearsal set-up is the one to reuse for step 4: a git server in the proof cluster, a
copy of devops-shared with only the git URL and the chart registry changed, and the real
root Application and ApplicationSet. It starts from the cluster's state, the host's CRDs
included, not from a clean cluster.

### Step 3 — niord-ng: the chart renders an HTTPRoute (done 2026-10-01 as chart 4.0.46, reverted 2026-10-02)

Merged as pull request 118 and published as **4.0.46**: the chart
`reg.sitcloud.dk/cu3504-mssb-brs/charts/niord:4.0.46` and the three app images of that
version. That is the version step 5 pins. 4.0.42 and earlier render an Ingress.

The look at the cluster was the one that closed step 2, an hour earlier: seven Gateway API
CRDs, the host's six at v1.4.0 and `tlsroutes`. The proof ran on those.

- `templates/nginx/ingress.yaml` is replaced by `httproute.yaml`: an HTTPRoute `v1` with no
  `matches`, no `filters` and a numeric backend port (sketch in 3.1). Values:
  `proxy.route.enabled` and `proxy.route.parentRefs`, each entry a Gateway's `name` and
  `namespace` (default: the `internal` Gateway in `traefik`). `proxy.ingress` is gone.
- **Old values fail the render.** Values that still set `proxy.ingress` would be silently
  ignored, and the environment would have no route. So the chart refuses them and names
  `proxy.route`. The stagings' values of today fail that way against the new chart, which
  is what step 5 changes. An empty `parentRefs`, or an entry without a namespace, fails
  too.
- The host helper and its check stay, renamed (`niord.proxy.routeHost`): the route only
  makes sense for `https://<host>` with no port.
- nginx trusts the forwarded headers when `proxy.route.enabled`, as it did for the Ingress
  ([`configmap.yaml`](../be/k8s/helm/niord/templates/nginx/configmap.yaml)); the comment in
  `keycloak/deployment.yaml` follows.
- [`be/k8s/README.md`](../be/k8s/README.md) and the Ingress mentions in
  [`be/docs/cicd/`](../be/docs/cicd/) are updated.
- **Proof on OrbStack** (Kubernetes 1.35, Traefik v3.7.13, the seven CRDs, Pod Security
  `restricted`):
  - *Today's path as the baseline:* Traefik with devops-shared's values, the old chart and
    its Ingress, and two echo backends in place of the app and Keycloak. They show what
    nginx forwards: `X-Forwarded-Proto: https`, the browser's host and port, and the
    spoofed `X-Forwarded-*` headers the request carried are gone.
  - *The Gateway path:* Traefik with a draft of step 4's values (the Gateway API provider
    on, Ingress off, the two Gateways of 3.1 with the certificate from the Secret) and the
    new chart. The route is `Accepted` on `internal`. What nginx forwards is the same, line
    for line, on `/rest` and on `/auth`. The certificate served is the Secret's.
  - *The public Gateway:* a route on `internal` only answers 404 on the public address.
    With both Gateways in `parentRefs` it is `Accepted` on both and served there; `/auth/admin`
    and `/auth/realms/master` still answer 403 on the public address.
  - *The whole stack behind the route* (the app, Keycloak, Postgres): the health endpoint
    is UP, Keycloak's issuer is `https://<host>/auth/realms/niord` with no port, the sign-in
    page loads, a REST call answers.
  - *Between steps 4 and 5:* with Traefik switched and the old chart still deployed, its
    Ingress is not served. That is the downtime the plan accepts.
- Not deployed until step 5. The stagings pin their chart explicitly (4.0.38), so the new
  version reaches nothing until they are moved. Until step 5 the stagings cannot take 4.0.46
  or later: their values would fail the render. A freeze that does not matter here.
- **Rollback:** none needed; the stagings do not pin it yet.

### Step 4 — devops-shared: the switch (not done)

One merge request. Both stagings are down from its merge until step 5.

- Traefik values: `providers.kubernetesGateway.enabled: true`; the chart's default Gateway
  off (`gateway.enabled: false`); `gatewayClass.name: traefik` pinned; the two Gateways of
  3.1 through `extraObjects`; `providers.kubernetesIngress` off; `ingressClass` off; the
  ACME resolver, entry-point TLS settings, volume, `fsGroup`, `Recreate` and the token
  variable removed. "Removed" for the entry-point TLS means
  `ports.websecure.http.tls.enabled: false` and `ports.public-https.http.tls.enabled:
  false`, written out: the chart's default for `websecure` is `true`, so dropping the block
  would keep entry-point TLS on. (The path has `http` in it; `ports.<name>.tls` is refused
  by the chart's schema. Found in step 3's proof, where a draft of these values ran.) The
  file-provider 403 route gets `tls: {}` (without it, the router is a plain-HTTP router
  once the entry point's TLS settings are gone, and matches nothing).
- Optional, tried on OrbStack first: `additionalArguments:
  ["--providers.kubernetesgateway.crossProviderNamespaces="]`. Traefik treats a nil
  allowlist as unrestricted but a non-nil empty one as deny-all; the chart cannot emit the
  flag for an empty list, a raw argument can. If Traefik parses the empty value as an empty
  list, `api@internal` is refused by the provider too, and fence rule 4 is no longer the only
  guard. If it parses as nil, drop it; the fence stays the protection either way.
- The fence rewritten (3.2), renamed `traefik-route-fence`.
- `argocd/test/argocd-server-ingress.yaml` → `argocd-server-route.yaml`, an HTTPRoute on
  the `internal` Gateway.
- `runbooks/traefik-smoke.yaml` → HTTPRoutes (today it has three Ingresses: served,
  classless, other class; the new cases: served, attached to no Gateway, public from an
  unlisted namespace).
- `argocd/test/platform-project.yaml`: allow GatewayClass `traefik`; drop IngressClass
  `traefik`; the policy's new names.
- Validator and tests: the entry-point, fence, file-provider and Argo checks rewritten for
  Gateways and HTTPRoutes (3.2, the priority floor included); the root directory's allowed
  kinds get `HTTPRoute` and lose `Ingress`.
- Docs: `platform/test/traefik/config.yaml`, `runbooks/bootstrap.md`, open issue 5 (the
  TLSRoute route, which needs no experimental channel; its "ruled out" paragraph on TLSRoute
  is stale) and open issue 8 (step 2 of its checklist becomes "add the `public` Gateway to
  `proxy.route.parentRefs`").
- **Proof on OrbStack, before review**, on the CRDs the cluster has (the host's Standard
  v1.4.0 plus the TLSRoute CRD, section 2.6):
  - both Gateways `Programmed=True`; HTTPS through both with the Secret's certificate and
    nothing else; Traefik starts with no volume and a rolling update works;
  - HTTP→HTTPS redirect with no HTTP listener on either Gateway (the redirect is an
    entry-point feature, independent of any provider);
  - the file-provider 403 route answers 403 over HTTPS for `/auth/admin` and
    `/auth/realms/master` on `public-https`, and still outranks Gateway routes, tested
    against an adversarial HTTPRoute from a public namespace: exact match on
    `/auth/admin/master/console/`, a method and several headers;
  - a route from an unlisted namespace is refused by the public Gateway;
  - fence: foreign hostname, missing hostname, Gateway outside `traefik` (of any class), a
    `URLRewrite` from `/kc-admin` to `/auth/admin/`, any other filter, a backend with a
    `group`, kind `TraefikService`, `api@internal`, or a `namespace` — all refused.
- **After the merge:** the Argo CD UI answers again at once; both stagings are down until
  step 5 (their Ingresses are no longer served).
- **Rollback, before step 5:** revert. Traefik then fetches its certificate again into a
  new volume (one more of the five a week). **After step 5:** revert step 5 first, then this
  step — never this step alone, because the stagings would then have routes and no
  Gateway, and no Ingress either.

### Step 5 — devops-niord: the stagings on the new chart (not done)

- `envs/staging/dk` and `envs/staging/gl`: pin the chart from step 3 (4.0.46, or the
  newest published by then); `proxy.ingress` →
  `proxy.route.enabled: true` (internal only); comments updated; `docs/open-issues.md`
  (the `proxy.ingress` mention in issue 1).
- `envs/_template/values.yaml`: add the same block. It has no Ingress block today, so a new
  test environment would not be reachable.
- The niord AppProject needs no change: it allows every namespaced kind.
- **Done when**, from Citrix: both stagings load; sign-in works; Admin → Users lists the
  users; `kubectl -n niord-dk-staging get httproute` shows `Accepted=True` on `internal`;
  `kubectl get ingress -A` lists nothing.
- **Rollback:** revert the pins, then step 4 (in that order, see step 4).

### Step 6 — SECOM mTLS through a TLSRoute (open issue 5) (not done)

- A passthrough listener (`protocol: TLS`, `mode: Passthrough`, `allowedRoutes.kinds:
  [TLSRoute]`) on the public port next to the HTTPS listener (the Gateway API allows the
  two on one port with distinct hostnames; Traefik routes by SNI), a SECOM hostname per
  environment (issue 5: its own name, never the web name), and a TLSRoute in the chart to
  the proxy Service's numeric `secom.proxyPort` (default **18443**). That Service forwards
  to nginx's **8443**; the TLSRoute must reference the Service port, not the pod port.
- Extend fence rule 1 with the TLSRoute-only hostname
  `<namespace>-secom.brs-test.e-navigation.dk` (for example,
  `niord-dk-staging-secom.brs-test.e-navigation.dk`). Keep HTTPRoutes restricted to their
  existing web hostname. Extend rule 4 to TLSRoutes too; a TLSRoute has no filters.
  **Proof:** admit each route on its own hostname, reject a TLSRoute using the web hostname
  and an HTTPRoute using the SECOM hostname, and verify mTLS passthrough through the Service
  port while the web hostname still serves HTTPS normally.
- **Tried on the host's version** (2026-10-01, section 2.6): on a Gateway of the v1.4.0
  CRD, a passthrough listener next to the HTTPS listener on the same port, and a TLSRoute
  of v1.5.1 to a backend with its own certificate. The client saw the backend's
  certificate, and the web hostname was still served by Traefik's.
- **No experimental channel.** Settled 2026-09-30 from Traefik 3.7's source: the TLSRoute
  `v1` informer is registered unconditionally; `experimentalChannel` only adds TCPRoute,
  and turning it on without the experimental CRDs would stop the whole provider. The chart
  41.6.0's comment that the flag "enables TCPRoute and TLSRoute" is out of date.
- The SECOM trust model does not change: Traefik never decrypts the stream, so it cannot add
  or forge `X-Secom-Cert` (devops-niord `runbooks/secom/cutover.md`).
- **A side effect to prove:** as soon as one TLSRoute exists, Traefik adds a catch-all TCP
  router (`HostSNI(*)` without ALPN `h2` or `http/1.1`, priority 1) that closes TLS
  connections with an unknown SNI instead of letting them reach a 404. Browsers and curl
  send ALPN and are not affected. The proof checks the web hostname over HTTPS and an
  unknown hostname (closed connection, not a certificate error) after the TLSRoute is in.
- Planned in detail when it starts.

### Afterwards: going public (open issue 8)

Unchanged, except its step 2: in devops-niord, add `{ name: public, namespace: traefik }` to
`proxy.route.parentRefs` for each staging. The external checks (200 for the site, 403 for
`/auth/admin/`, 404 for `argocd.brs-test…`) and the Cloudflare records stay as written.

---

## 5. Risks and open checks

| Risk | Where it is handled |
|---|---|
| CRD version skew: the host's v1.4.0 CRDs, Traefik built against v1.6.1 | tried 2026-10-01 (section 2.6); the proofs of steps 3 and 4 run on v1.4.0 |
| A CRD Traefik watches is missing, and the provider serves nothing | step 2: the TLSRoute CRD. With the host's six alone Traefik serves nothing (section 2.6) |
| The host upgrades the cluster, and the Gateway API version changes under us | before a cluster upgrade: read the new package's version, and repeat the step 4 proof on it |
| A Tanzu upgrade brings TLSRoute, and the host's controller meets the CRD we installed | step 2: delete our file before the upgrade (the CRD stays); devops-shared open issue 9; asked with host question H6 |
| Someone installs a full set of Gateway API CRDs again | section 2.6 is the record; the validator allows a CRD in `argocd/test/` only as the TLSRoute file |
| A rollback expects the CRDs to go, and they stay | step 1 (`resource-policy: keep`) and step 2 (Argo CD does not prune a CRD; `Prune=false` on top): rollback says so, `kubectl delete crd` by hand |
| Removing cert-manager hangs while a challenge is pending | step 1: rollback says so; the fix is in cert-manager's `config.yaml` in devops-shared |
| A Gateway in an app namespace bypasses the public list | fence rule 2 (3.2), any class |
| A rewrite filter smuggles `/auth/admin/` past the 403 route | fence rule 3; step 4 proof |
| A route targets Traefik's own `api@internal` (the dashboard) | fence rule 4 and step 4 admission proof; unset `crossProviderNamespaces` allows references and is not a restriction |
| The 403 route loses to a Gateway route | priority 1 000 000 against computed priorities near 100 000; validator floor 500 000; step 4 proof with an adversarial route |
| The 403 route stops matching once entry-point TLS is gone | `tls: {}` on the router; step 4 proof |
| No HTTP listener, so no redirect | step 4 proof |
| cert-manager cannot pull its images | step 1: sealed pull Secret for `cert-manager`, `imageRegistry` (done; the pods run) |
| A SECOM route is rejected or sends traffic to the wrong port | step 6: a separate TLSRoute hostname rule and backend port `secom.proxyPort`, with passthrough proof |
| Let's Encrypt limit (5 a week for the same name) | one issued in step 1 (2026-10-01); one more on a step 4 rollback |
| A rollback strands the stagings | after step 5, revert step 5 before step 4, never step 4 alone |
| The Argo CD UI goes down with a broken Traefik (as today) | jump server: `kubectl -n argocd get applications`, `kubectl -n traefik get pods` |
| The host installs its own Gateway API CRDs | it already had (section 2.6). Prod is not checked yet: look there before anything is built for prod, and ask with host question H6 |
| The public Gateway's status shows the internal Service's address | cosmetic; Traefik copies one Service's address to every Gateway (`statusAddress`) |

---

## 6. Not in this plan

- **ListenerSet.** The host's Gateway API v1.4.0 has no such kind, and Traefik's support,
  merged on 2026-09-28, is not in a release. Later it could give each environment its own
  listener, and make fence rule 1 unnecessary.
- **Prod.** No Traefik there; it sits behind the host's HTTPS offloader. How prod routes
  between DK and GL is host question H6.
- **Rate limiting** for the public stagings (issue 8, step 5).
- **`RequestMirror` and the CORS filter.** Standard in the spec, not implemented by Traefik
  3.7; the fence refuses them with every other filter anyway.

---

## 7. References

- devops-shared: `platform/test/traefik/{values,config}.yaml`,
  `platform/test/cert-manager/{values,config}.yaml` (step 1),
  `platform/test/reloader/` (the pull-secret pattern),
  `argocd/test/{platform-project,argocd-server-ingress}.yaml`, `runbooks/traefik-smoke.yaml`,
  `scripts/validate_configs.py`, `docs/open-issues.md` (issues 5 and 8)
- niord-ng (this repo): [`ingress.yaml`](../be/k8s/helm/niord/templates/nginx/ingress.yaml),
  [`configmap.yaml`](../be/k8s/helm/niord/templates/nginx/configmap.yaml),
  [`values.yaml`](../be/k8s/helm/niord/values.yaml) (`proxy`),
  [`be/k8s/README.md`](../be/k8s/README.md), [`be/docs/cicd/`](../be/docs/cicd/)
- devops-niord: `envs/staging/{dk,gl}/values.yaml`, `envs/_template/values.yaml`,
  `docs/open-issues.md` (issue 1)
- [Gateway API v1.5: Moving features to Stable](https://kubernetes.io/blog/2026/04/21/gateway-api-v1-5/)
- [Traefik: Kubernetes Gateway API provider](https://doc.traefik.io/traefik/reference/install-configuration/providers/kubernetes/kubernetes-gateway/)
- [Traefik: routing with Gateway API](https://doc.traefik.io/traefik/reference/routing-configuration/kubernetes/gateway-api/)
- [traefik/traefik PR #12909 (ListenerSet)](https://github.com/traefik/traefik/pull/12909)
- Versions checked 2026-09-29: Traefik v3.7.13 `go.mod` (gateway-api v1.6.1); chart traefik
  41.6.0 values; chart cert-manager v1.21.2 (four images, leader election in `kube-system`
  by default, `extraObjects`, no `global.imageRegistry`).
- Checked 2026-09-30: `helm template` of traefik-crds 1.18.0 (`gatewayAPI: true`: eight
  CRDs, Standard v1.5.1, TLSRoute serves `v1`) and of traefik 41.6.0 (no Gateway API CRDs);
  Traefik v3.7 source `pkg/provider/kubernetes/gateway/client.go` (TLSRoute informer
  unconditional, TCPRoute behind `experimentalChannel`) and `httproute.go` (router
  priorities; filters as middlewares; `TraefikService` backends). The cross-provider check
  in `kubernetes.go` allows all namespaces when its allowlist is nil; chart 41.6.0 omits the
  flag for an empty list. Rendering cert-manager v1.21.2 confirmed that
  `global.imagePullSecrets` must contain `{name: ...}` objects, not strings.
- Checked 2026-09-30, third review: the Gateway API v1.5.1 and v1.6.1 Standard bundles
  (per-CRD size as compact JSON; the `safe-upgrades` admission policy in both; TCPRoute and
  UDPRoute in v1.6.1); traefik-crds 1.18.0 `templates/_helpers.tpl` (`resource-policy:
  keep` unless `deleteOnUninstall`) and its `gateway-standard-install.yaml` (eight CRDs, no
  policy); cert-manager v1.21.2 `crds.keep: true`; Argo CD's Helm page (`resource-policy:
  keep` is `Delete=false`) and gitops-engine (`shouldUseServerSideApply`: no automatic
  fallback for oversized objects); devops-shared `platform-appset.yaml` (client-side apply,
  charts from Harbor only); Traefik v3.7.13 `tlsroute.go` (the `deny-unknown-host` router),
  `kubernetes.go` (listener conflicts are keyed on protocol, port and hostname, so HTTPS and
  TLS share a port) and `isCrossProviderNamespaceAllowed` (nil allows, empty denies).
- Checked 2026-10-01, building step 1: chart cert-manager v1.21.2 rendered with the
  component's values (46 objects plus the Issuer and the Certificate; top-level
  `imageRegistry` and `imageNamespace`; pull secrets on the ServiceAccounts; both webhook
  configurations limited to the groups `cert-manager.io` and `acme.cert-manager.io`); Argo
  CD v3.5.3 on OrbStack (`retry.limit: 5` on an automated sync; no new attempt for the
  same revision after it fails; `helm.sh/resource-policy: keep` kept the CRDs on delete;
  orphan detection in the destination namespace).
- Checked 2026-10-01 on the test cluster (jump server), after merge request 39: creation
  times, annotations, labels and field managers of the eight CRDs; the PackageInstall and
  the kapp-controller App `test-cluster-brs-01-gateway-api` in `vmware-system-tkg`; the
  `gateway-api` Application's sync message. And on OrbStack: the upstream Standard bundle
  v1.4.0 (six CRDs, the sizes of the host's copies, ReferenceGrant `v1beta1` only);
  Traefik v3.7.13 against it with and without the TLSRoute CRD of v1.5.1, TLS passthrough
  included; the cluster's failed state rebuilt and the Application deleted.
- Checked 2026-10-01, the new step 2: the rehearsal above (real `root.yaml`, platform
  ApplicationSet and project from a git server in the proof cluster); Argo CD v3.5.3 puts
  a tracking annotation on the ConfigMap and the Ingress root applies, and none on a CRD;
  on the test cluster after merge request 40: the Application gone, root Synced and
  Healthy, seven CRDs, the host's package "Reconcile succeeded".
