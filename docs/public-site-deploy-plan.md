# The public site's deployment

**Status: proposed, reviewed and built on 2026-11-10. Both stagings serve the public site on base
4.0.151, each on its own host (section 12). Left: two checks of section 7, the issue PDF and an
attachment's address, which wait until a staging has published an issue and a message with an
attachment.** Decisions 1 to 3 of section 8 are taken.
**Scope:** the public site's image, the base Helm chart, DK's and GL's staging and the template
for test environments. Production is not part of this plan.
**Repos:** niord (the image and the chart), devops-shared (the Ingress rule), niord-dk and
niord-gl (a bump, no code change), devops-niord (the environments' values).

This is the plan for the open item of Phase 8 in
[`be/docs/cicd/implementation-plan.md`](../be/docs/cicd/implementation-plan.md): "Give the public
site a Helm deployment and an ingress in the base chart". The same gap is recorded in
[`fe/docs/backlog.md`](../fe/docs/backlog.md) (_Nothing deploys the public site_ and _The image
does not fit a hardened pod_) and as F-X99 in
[`security-review-2026-09.md`](security-review-2026-09.md).

---

## 1. Where things stand (2026-11-10)

- **The image exists.** Each country's Image workflow builds, tests and pushes
  `reg.sitcloud.dk/cu3504-mssb-brs/niord-<cc>-info:<base>.<run>` beside its app image. The last
  runs, on base 4.0.147, were green in both country repositories on 2026-11-10. The push job
  refuses to push unless the site's image carries the same version as the backend's, so the two
  images of a country always share one tag.
- **The Image workflow runs only when the staging bump starts it.** It is disabled in both
  country repositories while Actions minutes are short. The script enables it for one run and
  disables it again (devops-niord's staging bump, then a script run from a workstation). *Until 2026-11-19. Since then
  Image runs after every green CI run on `main`, and the staging bump is devops-niord's promote
  job with `TARGET=staging`, which reads the images in Harbor.*
- **Nothing runs the image.** The chart (`be/k8s/helm/niord`) has five components and none for
  the site. Its `NOTES.txt` says "The public site (fe/ui-apps/info) runs separately". The staging
  values in devops-niord have nothing for it.
- **The backend image carries the administration app only** (`be/k8s/docker/Dockerfile`,
  `WebAppConfig`). The public site was never part of it.
- **Two checks wait for the site.** The repository-files cutover left two checks open on both
  stagings: an issue PDF and the list PDF, read through the site's proxy
  ([`repo-files-in-database-plan.md`](repo-files-in-database-plan.md); devops-niord,
  `runbooks/repo-files-cutover.md`).

## 2. The decision: the existing image, deployed by the base chart

The site becomes a sixth component of the `niord` chart, `info`, behind an `enabled` flag like
the other five. Two alternatives were weighed and dropped.

**Serving the site from the backend image, as the administration app is.** The public host's
protection is the site's own nginx: only GET and HEAD reach the backend, only five backend paths
and `/wms/` exist, the credential headers are emptied, every response carries the site's
Content-Security-Policy, and the print addresses are held to two query maps that a guard test
checks against the application. The files are compressed once at build time, because the size of
a first visit is a product requirement. All of this would have to be rebuilt in Spring, together
with the container test, the layout check and the kit's image tool that DK and GL already run. It
would also reverse R8 of the country split ([`fe-country-split-plan.md`](fe-country-split-plan.md)):
the base product owns the site's container.

**A chart of its own.** The site and the backend it reads must be the same version. One chart
gives one pin, which the staging bump and `promote.py` already move. A second chart would be a
second pin that can disagree with the first.

## 3. The shape

```
reader ─ https://<site host> ─ Traefik ─ Ingress (info) ─ Service (info) ─ pod niord-<cc>-info
                                                                             │  nginx: the built files,
                                                                             │  and the allow-listed /rest/ paths
                                                                             ▼
                                                           http://<fullname>-app:8888   Service (app)

editor ─ https://<admin host> ─ Traefik ─ Ingress (proxy) ─ the chart's proxy ─ app     (unchanged)
```

- **A host and an Ingress of its own, not behind the chart's proxy.** The chart's proxy sends
  everything on its one host to the app, and both applications are served at `/`. The site's
  container already is the proxy for its host, with the allow-list. Its Ingress points straight
  at its Service. The test cluster's Ingress rule has to allow the host first (section 4).
- **The backend is reached inside the cluster.** `NIORD_SERVER_URL` is the app's Service over
  plain http, which the chart fills in itself. This is the route
  [`fe/ui-apps/info/AGENTS.md`](../fe/ui-apps/info/AGENTS.md) documents for a backend in the same
  cluster. The name must resolve when nginx starts, so the site needs the app in the same
  release.
- **One tag for both images.** `info.image.tag` is empty by default and then takes the app
  image's tag. The helper `niord.image` refuses an empty tag for every component but `app`
  today, so it learns this one case. The staging bump moves two fields (the chart pin and
  `app.image.tag`) and `promote.py` three (those and the Postgres image tag). Neither changes.
- **Off by default.** The base product publishes no image of the site for the generic deployable,
  so the chart's own defaults render no `info` component. An environment turns it on.
- **Hardened like the other pods.** The pod and container security contexts come from the chart's
  existing helpers (`niord.podSecurityContext`, `niord.containerSecurityContext`). The probes are
  TCP, like the proxy's (as built; HTTP GETs of `/` would log a line every few seconds).
- **No sea-chart proxy yet.** The chart offers no `WMS_SERVER_URL`: the image's `/wms/` location
  does not work in a cluster as written (backlog, _The `/wms/` proxy would not work as written_),
  and both countries build with `wmsLayer: false`.

The values, as a sketch:

```yaml
info:
  enabled: false
  image:
    registry: ""
    repository: ""          # niord-dk-info, niord-gl-info; required when enabled
    tag: ""                 # "" = the app image's tag
    pullPolicy: IfNotPresent
  service:
    type: ClusterIP         # the site is reached through its Ingress; the rehearsal port-forwards
    port: 8080
  ingress:
    enabled: false
    className: traefik
    host: ""                # required when the Ingress is enabled
    annotations: {}
  teaserFrameAncestors: "*" # NIORD_TEASER_FRAME_ANCESTORS, the image's own default
  resources: { … }          # small: nginx serving files
```

`validate.yaml` fails the render when `info.enabled` is true without `app.enabled` (the backend's
Service would not exist, and the container would not start), without a repository, or with the
Ingress enabled and no host. As built it also refuses a host that is not a plain name, the host of
`externalBaseUri`, a `teaserFrameAncestors` the entrypoint would refuse, and values with no `info`
at all (section 12).

## 4. The host, and the cluster's Ingress rule

The site's host is the namespace's name with `-info` appended (decision 1). DNS and the
certificate need nothing: the name sits one level under `brs-test.e-navigation.dk`, which the
wildcard record and Traefik's wildcard certificate cover.

**The cluster refuses that host today.** Since 2026-09-29 an admission policy in devops-shared,
`traefik-ingress-fence` (`platform/test/traefik/values.yaml`), holds every Ingress of the
`traefik` class to one host: its namespace's own name under `brs-test.e-navigation.dk`. Two
namespaces have a named exception, `traefik` and `keycloak`. An Ingress for
`niord-dk-staging-info.…` in `niord-dk-staging` is denied, and Argo CD shows a failed sync. The
local rehearsal cannot show this: OrbStack has no Traefik and no policy.

**The change (decision 3, proposed):** one devops-shared merge request, before the site is turned
on anywhere.

- The policy also allows `<namespace>-info` under the domain, for namespaces whose name starts
  with `niord-`.
- A namespace whose own name ends in `-info` is given no host, so two namespaces can never claim
  the same one (`niord-test-x-info` and the site of `niord-test-x`). devops-niord's
  `validate_configs.py` refuses such an environment name too, so it fails at the merge request
  and not at the sync.
- The manual's paragraph _A namespace uses only its own name_ (`docs/manual.md`) and the
  validator's `check_traefik_fence` follow.

The site's Ingress carries no Traefik annotation, so the site stays on the internal address, like
the administration host.

Two other ways to give the site an address were weighed and dropped. A namespace of its own would
make the site a second release with a second pin, which section 2 rules out. A path under the
administration host would need Traefik middleware annotations, which the same policy forbids, and
the site is built to be served at `/`.

## 5. The prerequisite: the image must fit a restricted pod

Every namespace on the cluster enforces the restricted Pod Security standard (devops-shared,
`docs/manual.md`). The image as built today does not run there:

- nginx runs as root and listens on port 80;
- the entrypoint renders the server block into `/etc/nginx/http.d` when the container starts, a
  folder only root can write.

A pod without the restricted security context is refused at admission. A pod with the chart's
context is admitted, since admission reads the pod and not the image, and the container then
fails at start and restarts in a loop.

The change:

- nginx runs as a non-root user with a fixed numeric id, 101, the id the chart's own proxy
  already runs nginx as (`templates/nginx/configmap.yaml` is the model);
- it listens on an unprivileged port, 8080;
- the rendered configuration, the pid file and nginx's temporary files go under `/tmp`, so a
  read-only root filesystem later needs one `emptyDir` and no further image change.

**How, named: within the five nginx files the image already carries.** The temporary paths are
set at the top of `default.conf.template`, which is rendered into the `http` block; the pid path
is given on nginx's command line (`-g`); and `/etc/nginx/http.d` becomes a link into `/tmp`, where
the entrypoint renders. Alpine's own `nginx.conf`, which loads the brotli module, stays the
package's. The change is then the Dockerfile, the template and the entrypoint, and no kit code.
The alternative is a `nginx.conf` of the image's own, as the chart's proxy has. It is plainer, but
it is a sixth file: the kit's fixed list (`country-kit/lib/info-image.mjs`, `NGINX_FILES`), its
tests and every "five files" in the documents change with it. It is the fallback if the rehearsal
shows Alpine's `nginx.conf` standing in the way, for instance by setting a pid path itself. Either
way a country edits nothing, because a bump moves the kit with the base.

The container test (`fe/scripts/info-container-test.mjs`) starts the image the way the cluster
will: as that user, with every capability dropped and no privilege escalation. It is the one test
that names port 80 today (two lines). Because the Example country job and every country's Image
run execute it, an image that stops fitting a restricted pod then fails before it is pushed.

The move from port 80 to 8080 and to a non-root user matters to anyone who runs the image, so it
is recorded in `country-kit/API-CHANGES.md`, which already records the image's changes. DK's and
GL's `image.yml` name neither the port nor the user.

Left in the backlog, because a restricted pod does not need them: base images pinned by digest,
the three `proxy_cache_valid` directives that do nothing, and the `/wms/` proxy.

## 6. The steps

| # | Where | What | Verified by |
|---|---|---|---|
| 1 | niord, one pull request into `main` | The image change of section 5, the chart's `info` component of section 3, and the records of section 9 that describe them | Info CI; Frontend CI's Example country job, which container-tests the example's image; the local rehearsal and the renders below. Backend CI runs too, because the chart is under `be/`, but it checks nothing of the chart |
| 2 | niord | The merge publishes base version 4.0.N: the chart, the source zip and the kit | the publish run |
| 3 | devops-shared, one merge request | The Ingress rule of section 4 (decision 3) | its validator; then step 6's sync |
| 4 | niord-dk, niord-gl | Merge the bump pull request the Niord base bump app opens. While the base product's repository is private, each needs the hand pin its comment describes first | the country's CI |
| 5 | devops-niord, one merge request | The staging bump (then a script run from a workstation): it starts each country's Image run, which builds and container-tests the hardened image for the first time and pushes both images on 4.0.N, and opens the merge request that moves staging. Merge it and wait until both stacks are Healthy | the Image runs; Argo CD |
| 6 | devops-niord, a merge request of its own | The `info` block in both staging values files (`enabled`, `image.repository`, the Ingress and its host) and in `envs/_template/values.yaml` (decision 2); the validator's refusal of an environment name ending in `-info` | Argo CD; the checks of section 7 |
| 7 | niord, devops-niord, devops-shared | The remaining records of section 9 | — |

Steps 1 and 3 do not depend on each other and can be worked at the same time. Step 6 needs steps
3 and 5.

**The local rehearsal (step 1).** On OrbStack: build the base site's image by hand
(`npm run build:info && npm run image:info -- --tag …`), label the namespace
`pod-security.kubernetes.io/enforce=restricted`, deploy the chart with `info.enabled=true`, and
open the site through a port-forward. This proves the pod is admitted, nginx starts as the
non-root user and the site reads the backend, before anything is published. `deploy.sh` gets no
new flag in this plan; the rehearsal passes its values through `EXTRA_HELM_ARGS`.

**The renders (step 1).** No workflow and no test renders the chart: `publish.yml` runs
`helm package`, which does not. And the rehearsal cannot prove the pull secret (Harbor refuses
anonymous pulls) or the Ingress. So the pull request also records a `helm template` run of the
chart with each staging values file plus the block of step 6, read for five things: the site's
image reference (registry, repository, the app's tag), the pull secret on its pod, the Service's
type, the Ingress's host, and that the chart's defaults alone render no site.

**Why the block has a merge request of its own (step 6).** The staging bump rebuilds its branch
(`staging/<base>` then, `staging/bump` since 2026-11-19) from `main` on every run and force-pushes
it, so a commit added there by hand is lost on a rerun. A separate merge request also keeps a failure of the site, such as a refused
Ingress, from showing as a failed bump. Nothing forces the two together: a chart older than
4.0.N ignores the block, and the site's image tag follows `app.image.tag`, which only ever moves
with the chart pin. The one case to avoid by hand is setting `info.image.tag` to an image built
on a base older than 4.0.N: under the new chart it is admitted and then restarts in a loop.

**Rollback.** Revert step 6's merge request. Argo CD prunes the Deployment, the Service and the
Ingress. The component holds no state, and the rest of the stack is untouched.

## 7. What to check on staging

The site's host is on the internal network only, so the owner runs these, or they are run from
the jump server. For each country:

- the pod is running with no restarts, and no event names a Pod Security violation;
- `GET /` answers 200 with the site's Content-Security-Policy, and a client that asks for brotli
  gets brotli;
- `GET /rest/public/v1/messages?lang=…` answers 200 with JSON; `GET /rest/messages/search` and
  `GET /auth/` answer 404; a `POST` to the public API is refused;
- in a browser: the message list, the details, the map with its base map, the publications list,
  and the teaser at `/teaser`;
- **the two checks the repository-files cutover left open:** a publication issue's PDF, and the
  list PDF from the print button;
- an attachment in a message. DK's `info.config.ts` lists `niord.dma.dk` and the old test
  environment's host as the backend's public hosts and not the staging host, so look at which
  address an attachment is loaded from on staging;
- the ribbon shows the deployment's `mode`.

## 8. Decisions

1. **The site's host is the namespace's name with `-info` appended, for now** (the owner,
   2026-11-10): `niord-dk-staging-info.brs-test.e-navigation.dk`, `niord-gl-staging-info.…` and
   `niord-test-<name>-info.…` for a test environment. The host is one value of an environment
   (`info.ingress.host`) and neither the chart nor the image names it, so another name later is
   a one-line change there, plus the Ingress rule if the new name does not fit it.
2. **The site is on in every test environment** (the owner, 2026-11-10).
   `envs/_template/values.yaml` carries the `info` block switched on. A test environment runs a
   country's app image, whose site image exists under the same tag, and the pod is small. A GL
   environment then names two image repositories of its own, `niord/niord-app-gl` and
   `niord-gl-info`, which the template's comment says.
3. **The test cluster's Ingress rule allows `<namespace>-info` for niord namespaces** (the
   owner's go, 2026-11-10; in force once step 3 is merged). Decision 1 cannot be deployed without
   it (section 4). As built, the rule that keeps two namespaces off one name applies to niord
   namespaces only (section 12).

## 9. Records to update

In step 1, with the change they describe:

- `be/k8s/helm/niord/values.yaml` (the header's "five components"), `templates/NOTES.txt` (the
  site's address in place of "runs separately") and `be/k8s/README.md`;
- the "five components" of `be/docs/cicd/README.md` (the Helm chart row) and `be/docs/cicd/design.md`
  (the chart's row), with devops-niord's copy of `design.md`;
- `fe/ui-apps/info/AGENTS.md`: the port and the user, where it describes what the image serves
  and its three settings. No other document describes either today;
- `country-kit/API-CHANGES.md`: the image's port and user;
- `fe/docs/backlog.md`: _The image does not fit a hardened pod_ shrinks to what is left (as built:
  _The image pins its base by tag, and has no `HEALTHCHECK`_), and _Nothing deploys the public
  site_, untrue once the chart has the component, becomes _No environment runs the public site
  yet_;
- `security-review-2026-09.md`: F-X99's recheck names the site's non-root image (a go-live recheck
  item, so it is updated rather than closed).

In step 3: devops-shared's `docs/manual.md`, _A namespace uses only its own name_, and the comment
in `platform/test/traefik/config.yaml` that says which host an app may use.

In step 6: the comment "The host also names the namespace" in both staging values files, the
template's comment on a GL environment's two image repositories, and the site's host and the
`-info` name rule in `envs/_template/README.md` and `runbooks/ephemeral-environments.md`.

In step 7, once staging runs the site:

- `be/docs/cicd/implementation-plan.md`: the Phase 8 item is done; `be/docs/cicd/design.md`: the
  status line and the row of the site's container;
- `fe/docs/backlog.md`: _No environment runs the public site yet_ goes;
- devops-shared: a probe of each site in `platform/test/blackbox-exporter/values.yaml`, beside the
  stagings' own (`/index.html` and `/rest/public/v1/messages` through the site's host), added only
  once the sites run, or they alert; and the site's hosts in the table of names in
  `docs/network-architecture/current/README.md`;
- [`feature-branch-deploy-plan.md`](feature-branch-deploy-plan.md): a preview build names the
  site's image in the preview project, as it names the app's;
- `repo-files-in-database-plan.md` and devops-niord's `runbooks/repo-files-cutover.md`: the two
  waiting checks, with their result;
- devops-niord's `docs/implementation-plan.md` and `docs/design.md`, which carry the same open
  item;
- this page's status line.

## 10. The review of 2026-11-10

The first version of this plan was reviewed the day it was written. What changed:

| Finding | Change |
|---|---|
| The cluster's Ingress rule refuses the site's host; the plan had checked DNS and the certificate only | section 4, step 3, decision 3; devops-shared joins the repositories |
| CI on `main` does not start a country's Image run: the workflow is disabled, and the staging bump starts it. A bump pull request needs the hand pin while the base product's repository is private | section 1; steps 4 and 5 |
| The `info` block does not belong in the bump's merge request, whose branch is rebuilt and force-pushed | step 6, and the rollback |
| Nothing renders the chart before staging; `niord.image` refuses an empty tag | the renders of step 1; section 3 |
| Moving nginx's files to `/tmp` can reach into the kit's fixed list of five files | section 5 names the way that does not, and the fallback |
| Records: `API-CHANGES.md` was missing; two documents were named that describe neither the port nor the user; the manual, the staging comment, the template and the preview builds were missing | section 9 |
| A render with `info.enabled` and no app; `ClusterIP` as the default; an old image under the new chart restarts in a loop and is not refused; five backend paths and `/wms/`, not six; `promote.py` moves three fields; the checks run from the internal network | sections 2, 3, 5, 6 and 7 |

## 11. Not in this plan

- **Production.** Nothing here prepares it.
- **The cut-over from the site that runs today** (backlog, _Cut-over_).
- **The `/wms/` proxy.** It is fixed before a country turns `wmsLayer` on.
- **An image of the site for the generic deployable**, published by the base product, and a
  `deploy.sh --info` for the local bundle. The local rehearsal builds the image by hand.
- **A CI job that renders the chart.** The renders of step 1 are run by hand for this change.
- **An automated smoke test** of the deployed site. The staging smoke test of
  `be/docs/cicd/design.md` §6 is still deferred; section 7 is done by hand.
- **The site on the public address.** Both staging namespaces are on the Ingress rule's public
  list, so the site could go public like the administration host: its Ingress then names
  Traefik's public entry point, and its host gets its own public A record, which devops-shared's
  `docs/host-request-public-ip.md` does not list today.
- **A read-only root filesystem for the pod.** The image runs with one (section 12); the chart
  sets it for none of its components today.

## 12. Execution record

### Steps

| # | State (2026-11-10) | Where |
|---|---|---|
| 1 | merged | niord |
| 2 | published as 4.0.151 | niord's publish run |
| 3 | merged | devops-shared |
| 4 | merged, after the hand pin; set-up of 4.0.151 passed, no human action | niord-dk #23, niord-gl #22 |
| 5 | merged: both Image runs green, the site's images `niord-<cc>-info:4.0.151.10` container-tested (168 assertions each) and pushed | devops-niord |
| 6 | merged five minutes after step 5, with its pipeline's `helm-template` rendering both stagings against the published 4.0.151 chart; not after Argo CD showed step 5 Healthy, which this machine cannot see | devops-niord |
| 7 | merged: the records of step 1 in step 1's pull request, staging's in two more pull requests, two merge requests in devops-niord, one in devops-shared, and the backlog's _No environment runs the public site yet_ removed on its own | niord, devops-niord, devops-shared |

### As built (step 1)

- **The image.** The user `nginx` is created with uid and gid 101 before the package is installed.
  Alpine's package would otherwise pick its own (100 on Alpine 3.22, with group 101), so `USER
  101:101` alone would have run as an id nothing in the image belongs to. The image runs as that
  user, listens on 8080, renders into `/tmp/niord-http.d` (the target of the `/etc/nginx/http.d`
  link, created by the entrypoint), keeps the temporary files under `/tmp` from the top of the
  template, and the pid file under `/tmp` from `/etc/nginx/conf.d/niord-pid.conf`, which the
  Dockerfile writes and Alpine's `nginx.conf` includes at its top level, so it holds for any nginx
  command, not only the image's own. Alpine's `nginx.conf` stayed the package's, so the fallback of
  section 5 was not needed; one line of it is deleted in the Dockerfile, `user nginx;`, which a
  master process that is not root ignores with a warning in every log. The five nginx files are
  still five.
- **The container test** runs every container as the image's own user (no `--user`, so an image
  that drops its `USER` runs as root and fails the user check), with `--cap-drop ALL --security-opt
  no-new-privileges --read-only --tmpfs /tmp`, on port 8080, and checks that the image declares
  `101:101` and port 8080 alone and that the container runs as uid 101: 151 assertions green, 4
  skipped as for the base build. Against an image built from `main` it fails at once.
- **The chart.** `templates/info/` (Deployment, Service, Ingress), the `info` values, the tag rule
  in `niord.image` (an empty `info.image.tag` is the app image's resolved tag), and `NOTES.txt`,
  whose port-forward uses 18400 locally (docker-compose's Keycloak holds 8080). `validate.yaml`
  refuses: no app, no repository, an Ingress without a host, a host that is not a plain name, the
  host of `externalBaseUri` (two Ingresses for one host at `/`), a `teaserFrameAncestors` outside
  the entrypoint's character set or empty, and values with no `info` at all (`helm upgrade
  --reuse-values` from an earlier release), with the `--reset-then-reuse-values` guidance. The
  `info` templates render nothing for such values, because Helm renders them before
  `validate.yaml`. The probes are TCP, like the proxy's.
- **The local rehearsal** (OrbStack, a namespace labelled
  `pod-security.kubernetes.io/enforce=restricted`, the base build's image tagged with the app's
  tag): every pod admitted, the site Running with no restarts; through a port-forward `/` 200 with
  the site's policy, brotli to a client that asks for it, `/rest/public/v1/messages` 200 JSON,
  `/rest/settings/web` 200, `/rest/messages/search` and `/auth/` 404, a `POST` 403, `/teaser` 200,
  and after the base bootstrap the list PDF (`report=public`) 200 `application/pdf`, with another
  report and the `debug` switch 403. An issue PDF was not tried: the base bootstrap publishes no
  issue.
- **The renders** with each staging values file and step 6's block: the site's image is
  `reg.sitcloud.dk/cu3504-mssb-brs/niord-<cc>-info:4.0.147.9`, the app's tag; the pod carries
  `harbor-pull`; the Service is ClusterIP on 8080; the Ingress names
  `niord-<cc>-staging-info.brs-test.e-navigation.dk`, class traefik, with no tls section and no
  annotation; `NIORD_SERVER_URL` is `http://staging-<cc>-niord-app:8888`; the chart's defaults
  alone render no site. The test template, its token filled, renders the same way.

### As built (steps 3 and 6)

- **The Ingress rule.** A niord namespace may use `<namespace>-info`; a namespace that starts with
  `niord-` and ends in `-info` gets no host. Section 4 said every namespace ending in `-info`; only
  a niord one can collide, because the other half of a collision is always a niord namespace, so
  the platform's other namespaces keep their rule. A refused Ingress in such a namespace gets a
  message of its own instead of "must name the host" followed by nothing. The validator's
  `check_traefik_fence` holds the policy's shape, its fail mode and the public list, and nothing
  about hosts, so it did not change. The rule was tried instead on the local cluster with the
  policy applied: admitted `niord-x` and `niord-x-info` in `niord-x`, and `foo-info` in `foo-info`;
  refused `niord-y-info` and `niord-x-info-info` in `niord-x`, every host in `niord-x-info`,
  `foo-info-info` in `foo-info`; the chart's own Ingress admitted with its host and refused with
  another.
- **devops-niord's validator** refuses a `test-<name>` ending in `-info` (one test); the 80 tests
  and the configuration check pass.

### On staging (section 7)

Run by the owner on 2026-11-10, from the jump server through a port-forward to each site's Service, the
jump server reaching Traefik on port 80 only:

- both pods Running with no restarts, and no event naming a Pod Security violation or the Ingress
  rule; both Ingresses admitted, with an address;
- on both: `/` 200; `/rest/public/v1/messages` 200 JSON; `/rest/messages/search` 404; a `POST` 403;
  the list PDF (`report=public`) 200 `application/pdf`, one of the two checks the repository-files
  cutover had left open;
- in a browser, through Traefik, both sites open on their hosts.

Not checkable yet: the issue PDF (the cutover's other open check) and the address an attachment is
loaded from on DK's staging (section 7, the backend's public hosts in DK's `info.config.ts`). On
2026-11-10 neither staging had a published publication or message (`/rest/public/v1/publications`
and `/rest/public/v1/messages` both `[]` through the site), so both wait until one of each is
published there.

### Found while executing

- **No probe watches the site** (section 9, step 7, now listed): devops-shared's blackbox exporter
  probes both stagings' administration hosts and would not notice the site down.
- **Three devops documents name the hosts** and were not in section 9: the template's README and
  the ephemeral-environments runbook (step 6, done), and devops-shared's table of names (step 7).
- **The test-environment template routes no administration host through Traefik.** It sets
  neither `proxy.ingress.enabled` nor `proxy.service.type: ClusterIP`, as both stagings do, so a
  test environment made from it today would have its site on its host and its administration app
  on none. Its `app.image.tag` is also the 4.0.61 placeholder, whose site image predates this
  change and would restart in a loop under the new chart; the README already says to set the tag.
  Neither is fixed here: the template has no environment made from it yet.
- **`deploy.sh` changes a tracked file**: it copies a test realm over the chart's, and the two
  name the domain clients differently (`be/docs/potential-errors.md`, K8S1). Closed since: the
  two files are one realm, named after the domains, and `deploy.sh` stops on a difference.
