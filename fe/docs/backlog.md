# Backlog

Three lists, all verified against the code in this repository:

1. **[Before production go-live](#1-before-production-go-live)** — must have an owner before the publications window opens, or before the public site's first release.
2. **[Maintenance backlog](#2-maintenance-backlog)** — real gaps that nobody has decided against. Nothing here blocks anything; pick from it.
3. **[Deliberately not built](#3-deliberately-not-built)** — capabilities closed by a decision. Read this one before "fixing" something that looks missing.

Where an item is a whole subject of its own, the line points at the document that owns it rather than repeating it.

---

## 1. Before production go-live

The operational sheet — who does what, in what order, with the measured figures from the rehearsals — is
[`publications/go-live/GO-LIVE-TASKS.md`](publications/go-live/GO-LIVE-TASKS.md) and the runbook beside it.
That set is kept verbatim until the window closes. What follows is the part of it that lives in code or
needs a decision outside the sheet.

One item of that set is not open: the HTTP 500s on concurrent GETs (the sheet's E4, the runbook's §9).
They came from a defect of the Quarkus backend the redesign was built on, fixed the morning after they were
measured: every read of a series or an issue rewrote the rows it had loaded, so two reads at once collided
on them. This backend's reads write nothing (`PublicationReadsWriteNothingITTest`). What rests on that finding
alone is what the [go-live process](publications/go-live/GO-LIVE-PROCESS.md) carries into every rehearsal
because of it: its repeat of "nobody on the system during the import", and verification in sequence
afterwards. Those are the owner's to reconsider. Keeping users off production from the final rehearsal's
backup until the window closes, which the process and the sheet's D1 both require, has a reason of its own,
no new data after the backup, and stands.

| Item                                                                   | What it is                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | Where it lives                                                                                                                               |
| ---------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| No deployable production build                                         | `environment.production.ts` carries 5 of the ~40 help anchors. Its API and Keycloak addresses are origin-relative (`/rest`, `/auth`), and the publish workflow puts each deployable's bundle into its backend image, which serves it at `/`; the missing help anchors remain.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | `ui-apps/admin/src/environments/environment.production.ts`, `.github/workflows/`                                                             |
| A `test` build runs unflagged                                          | `environment.test.ts` sets `production: false`, and a country's own `environment.test.ts`, which replaces the base file for the `test` configuration, is held to nothing else: set-up checks `production: true` in the production file alone. Two dev-only paths stay live in a deployed `test` build: the `/dev/geo-editor` sandbox route, and the pre-authenticated Keycloak stub that returns a session from a token bundle seeded on `window`. Only a `production: true` environment closes both.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | `ui-apps/admin/src/environments/environment.test.ts`, `ui-apps/admin/src/app/app.routes.ts`, `ui-apps/admin/src/app/app.config.ts`           |
| No curator role                                                        | There is no publication-curate realm role and no curator capability on `AuthFacade` — the whole role vocabulary is `user` / `editor` / `admin` / `sysadmin`. Curation is gated by three coarser facts instead: the `/admin` route is role-guarded on admin/sysadmin, every curation control is disabled unless the session desk owns the series (`seriesWritable`), and the issue must be open. Provision a finer right, or record that admin-in-the-owning-domain is the intended gate.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | `ui-apps/admin/src/app/core/auth/area-roles.ts`, `ui-apps/admin/src/app/features/admin/sections/admin/publications/series-rules.ts`, backend |
| Public download site not checked                                       | Nobody has looked at how the public site renders the exact stamped cut-off instant. Needs the site owner's sign-off before the window opens.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         | [`publications/domain-rules.md`](publications/domain-rules.md) (cut-off model)                                                               |
| Open rehearsal steps and sign-offs                                     | The schema-validate boot on the deployed container, the UAT pass with no developer present, and the reviewer signatures on both checklists.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | [`publications/go-live/GO-LIVE-TASKS.md`](publications/go-live/GO-LIVE-TASKS.md)                                                             |
| Sequenced after the window                                             | Two backend follow-ups, both listed on the task sheet: decommission the message-tag coupling (in the outgoing model each publication also owned a message tag holding its members; the series/issues model mints none) and lock that frontend's publication pages; write the admin-manual chapter for the series/issues model. These are ordering, not omissions. The sheet's third, gating the anonymous listing, is done: `GET /rest/publications/search` and `/all` serve an anonymous caller the public tier only (`OQ-18`, closed 2026-09-25).                                                                                                                                                                                                                                                                                                                                                                                                                                                                  | backend + [`publications/go-live/GO-LIVE-TASKS.md`](publications/go-live/GO-LIVE-TASKS.md)                                                   |
| Base map on OSM's volunteer servers                                    | The web maps of both applications and the backend's map images draw their base map from `tile.openstreetmap.org`, the OpenStreetMap Foundation's volunteer-run tile servers. Production volume needs a tile source of our own or a paid one: choose it before go-live and set it in the `osmUrl` setting, which switches the web maps and the server images together. The host must serve https and send CORS headers (the administration app's Content-Security-Policy admits it from the setting by itself), and a public site that moves to another host updates its cookie statement, which names `tile.openstreetmap.org`.                                                                                                                                                                                                                                                                                                                                                                                      | the `osmUrl` setting ([map-portrayal.md](map-portrayal.md), _Server-made map images_), deployment                                            |
| Map images: slower for uncached places, untested over the real network | The backend now draws the map images of messages from OSM tiles it fetches itself. Measured on a workstation against stand-in map servers, they are faster than the previous renderer's wherever the tiles are cached, and 1.1 to 1.45 times slower for a place whose tiles are not: an image waits for the slowest of up to four tile requests instead of one static map. The first image after a start and a page of images right after a portrayal change are slower too. Whether that blocks go-live is the product owner's decision. Over the real network nothing is measured yet: at the next test deployment (Kubernetes, on the test data), check that the application pod reaches `tile.openstreetmap.org` (a proxy goes in as `-Dhttps.proxyHost` and `-Dhttps.proxyPort` through `app.javaOpts`), set `mapTileContact`, then run the benchmark in `remote` mode and hold its table against the one recorded for the previous Niord on the test environment.                                              | [`be/docs/map-image-benchmark.md`](../../be/docs/map-image-benchmark.md), deployment                                                         |
| Performance not compared with the previous Niord                       | **Must be fixed before go-live** (owner, 2026-11-02). The message reads are far slower than the previous Niord's: its list answers in ~110–150 ms on its test environment, Niord 4's in ~1 s on a workstation, on the same data but not yet the same machine, database engine or path to the database. Measured on the sample (2026-11-05): every association is batched, so the round trips are set by the batch fetch size: a page of 100 messages costs 86 statements at 16 and 19 at 128 or more, and the second-level cache the previous Niord had (still off) saves 2 of them. niord-base's core defaults load 256 owners a query since 2026-11-05 (16 before); the local profiles of niord-dk and niord-gl no longer set it, so their local runs take 256 too. Still to do: the time on the test data, the comparison of both applications on the same data and machine, and the cache; the assessment, the measurements and the plan are in [the message-read benchmark](../../be/docs/message-read-benchmark.md). | backend: `config/niord-core-defaults.yml`, `niord/pom.xml`; [`be/docs/message-read-benchmark.md`](../../be/docs/message-read-benchmark.md)   |

### Also due in the same window, outside publications

- **Stored date offsets.** Date intervals and firing periods written before the time-zone handling was fixed
  carry browser-zone offsets and were never retro-corrected. A data review, not a code change.
- **Protected-WMS redirect flood.** The WMS answers a no-data tile with a redirect to a blank image; because the
  protected loader fetches tiles with auth headers, the redirect fails the CORS preflight and a pan or zoom
  floods the console. The fix belongs at the gateway or the proxy servlet — a frontend workaround was tried and
  deliberately reverted.
- **The deferred security findings.** The security review of September 2026 deferred its nine findings about
  the deployment — the Helm chart, the Keycloak it runs and the realm it imports — because the deployment was
  still being built. Each is rechecked against the finished deployment before production go-live, together
  with ingress rate limiting and the realm pattern (Niord roles as client roles; logins through `niord-web` or
  a client listed in `niord.oidc.allowed-clients`). What to check for each is in section 5 of
  [`security-review-2026-09.md`](../../docs/security-review-2026-09.md).

### The repository and the backend settings

- **Review for changes under `.github/`.** A ruleset on `main` requires the `Frontend gate`, the `Info gate`
  and the backend's `Unit tests` since 2026-11-17, but no review. A rule requiring a maintainer's review for
  changes under `.github/` (with a `CODEOWNERS` entry naming the maintainers) is still to come: until then whoever may write `main` may also edit `.github/maintainers` or the two release
  workflows in the same push, so who may retract or break-glass is in effect who may write `main`, and write
  access only for named maintainers is what carries the rule ([fe-country-split-plan](../../docs/fe-country-split-plan.md)
  §4.11 rule 1).
- **The publishing token is a repository secret until the country split** (owner, 2026-10-22). `publish.yml`
  deploys with `NIORDORG_PACKAGES_USER` / `NIORDORG_PACKAGES_TOKEN`, which any workflow copy on a branch can
  name, and `retract` does not exist, so `retract-version.yml` refuses every run. At the split: a NiordOrg
  machine user with admin on `NiordOrg/niord`; its classic tokens, `write:packages` as `NIORD_PUBLISH_TOKEN` in
  `publish` (its login as the variable `NIORD_PUBLISH_USER`) and `delete:packages` + `read:packages` as
  `NIORD_DELETE_TOKEN` in a new `retract` environment, `main` only; the four deploy steps back to those names;
  then the two repository secrets deleted and the old token revoked, and both new tokens rotated yearly
  ([fe-country-split-plan](../../docs/fe-country-split-plan.md) §4.11 rule 1, _Until the country split_).
- **The Maven wrapper's distribution is pinned by TLS alone.** `be/.mvn/wrapper/maven-wrapper.properties` has no
  `distributionSha256Sum`, so the Maven the publish steps (which hold the publishing token) and a country's
  set-up stub run is the one the download gives; the country repositories' wrappers are the same. Add the
  checksum here and in `niord-dk` and `niord-gl`, and keep it in step with each wrapper bump. Until then Node,
  the Maven distribution and Maven's plugins are the accepted residual trust of plan §4.11 rule 2.
- **An expected secret-scanning alert.** `scripts/info-container-test.mjs` carries a PEM private key: the key of
  a self-signed certificate nothing trusts, which the container test's untrusted-https case needs. When the
  repository goes public and secret scanning is enabled, the alert on that key is expected; dismiss it as used in
  tests.
- **The backend `mode` setting.** The public site names every deployment whose `mode` web setting is anything but
  `production` in a ribbon across its menu bar (DEVELOPMENT, TEST), in front of every mariner. The backend's base
  settings file (`defaults/base/settings.json`) sets `test`; only the backend's own fallback, the catalogue's code
  default, is `production`. The settings file fills in only the keys a database lacks: a database that already
  carries a `mode` row keeps it. So a deployment that must show no ribbon sets `production` with a system
  property or environment variable named `mode`, which the web-settings endpoint reports in place of the row.
  Check the stored row before the public site is released against the database. A row can be corrected on
  `/sysadmin/settings` in the administration app only while it is stored with `editable` on: a row stored with
  `editable` off, as the imported test data carries one, is not on that page (SETTINGS-8 in
  `be/docs/potential-errors.md`, left by the owner). A settings import or a direct `PUT` to
  `/rest/settings/setting/mode` still writes such a row, since neither checks `editable` (SETTINGS-10, left by the
  owner). The override never changes the row: it only takes the row's place in what the web-settings endpoint
  reports, so the stored value stays hidden behind it. A row stored with
  `web` off, as that data also carries, never reaches the web-settings endpoint at all, so neither frontend shows
  a ribbon for it.

### Before the public site's first release

Background: the "Still open" list of [`niord-info-integration-plan.md`](niord-info-integration-plan.md).

- **Cut-over.** The site replaces a running one whose source lives in two upstream repositories: until the
  cut-over a fix made there is ported here by hand, and after it both are archived. No deployment of this site
  has served production data yet: browse one against the production backend before the host is switched over.

---

## 2. Maintenance backlog

### The public site

Background: [`niord-info-integration-plan.md`](niord-info-integration-plan.md).

- A deployment can name a language its build does not brand. The switcher follows the backend's
  `infoSiteLanguages`, limited to the bundles the build's `language.config.ts` names, so no setting reaches a
  language without words, and a language with no flag in the configuration shows its name and no image. The loader
  asks for `assets/branding/<lang>.json` only for the languages the configuration's `brandingLanguages` lists.
  Left: a language listed there whose overlay lacks the file takes the 404 and shows the bundle's product line
  ("Niord © year") in place of the authority's name in that language; and a configuration that leaves the field
  out brands every language, so it asks for every language's file (the in-tree configurations and the kit's
  generated one always set it). The administration app renders a
  message language it ships no flag for as its code.
- **The filter bar's "active now" chip changes nothing.** The list read sends `active=true`, and
  `/rest/public/v1/messages` declares no `active` parameter, so the backend drops it: against a live
  backend the same filter returned 211 messages with the chip on and 211 with it off. The chip
  presses, looks pressed and narrows nothing. Either the public API grows the parameter, or the
  narrowing is done where the list is rendered — every message carries the dates it would be judged
  by. Carried over from the site as it arrived, and the reason the printed sheet does not map it.
- **A publication being browsed is not named on the page.** Browse on a message report opens
  `/messages/details?publicationId=<id>`, which lists that publication's messages and leaves the main-type,
  active-now and root-area chips out of the filter bar — but nothing on the page says which publication it is,
  and the menu's Details, Table and Map links carry no query, so each of them returns to the current list rather
  than showing the publication in another view. The site this one replaced showed the publication's title in the
  filter bar with a download link and a control that left it. Doing the same means reading
  `/public/v1/publication/{id}`, a label for the leaving control that no bundle has yet, and menu links that keep
  `publicationId` while it is set.
- **A list that comes back empty shows nothing.** The details view renders the failure line, the spinner and
  one card per message (`ui-apps/info/src/app/features/messages/components/message-details-view/message-details-view.component.ts`
  16-47) and has no branch for a list that arrived empty, so Browse on a released issue that holds no message
  (`/messages/details?publicationId=<id>`) shows only the Print bar and the footer: no heading and no sentence
  saying the issue lists no notices, which a reader can take for a page that failed. The same holds for a filter
  that matches nothing. A line for the empty list (and, with the entry above, the publication's name) closes it;
  it needs wording the site owner has not been asked for. Found by the error hunt's live check (2026-11-13).
- The filter bar is a row of toggles with no name of its own. Each button carries its visible label and its
  `aria-pressed`, which meets AA, but a `role="group"` with an `aria-label` would tell a screen-reader user what
  the row of toggles is for. It needs a word that exists in no bundle, so it waits for the site owner to write one
  (in English here; each country's pack translates it).
- No route has an `<h1>`. The headings start at `<h4>` — the area and category headings the site has always
  shown — so a screen reader finds no page title in the document. Giving each route one means either a new visible
  heading or a visually hidden one, and both need wording the site owner has not been asked for.
- **The map view's panel of messages without a position covers the chart's own controls on a phone.**
  Below 640 px the panel spans the full width of the chart from its top edge, so it lies over the zoom
  buttons in the top-left corner, and on a chart not much taller than the panel it reaches the attribution
  links at the bottom as well. All of them stay in the tab order, so a keyboard user's focus lands on the
  zoom-out button and on the attribution links out of sight. Starting the panel clear of the zoom buttons —
  as the line that reports a failed load already does — and ending it above the attribution closes it.
- The print route (`/messages/print`) and the teaser render outside the site's layout, so neither has a main
  landmark and a screen reader's jump to the main content finds nothing there. Wrapping each view's content in
  `<main>` closes it.
- On the print route (`/messages/print`) every link-styled button in the message cards has its styling removed,
  focus ring included, and stays in the tab order; the message-reference ids among them do nothing when pressed.
  A keyboard user's focus disappears on each one. Rendering the references as text on that route, or taking the
  buttons out of the tab order, closes it.
- **Every first visit carries OpenLayers' stylesheet.** `ol/ol.css` is imported by the global stylesheet, so a
  visit that lands on the message list, which draws no map, transfers about 1.2 kB (brotli) of map chrome in the
  styles group that `bundle:info` measures. Both applications import it globally today; loading it with the map
  kit instead means a lazy stylesheet in `shared-map/`, a map change measured for both.
- **A busy PDF answers with a raw error.** The backend makes only a few PDFs at a time for readers without an
  identity (three by default) and answers every further request with 503 at once — see _The PDF reports are
  anonymous_ in [backend-api-notes.md](backend-api-notes.md). Both print buttons open the PDF's address in a
  new tab, so a reader who meets the limit sees the backend's JSON error there rather than a page that asks
  them to try again shortly. A page of the site's own for that answer, served by the container for a 503 on the
  two PDF locations, needs wording the site owner has not been asked for.
- **The site reserves no scrollbar gutter.** A permanent gutter would show as a stripe beside the full-bleed map
  view, so there is none, and where a platform draws classic scrollbars the centred message list can shift
  sideways by the scrollbar's width when the list arrives and grows past one screen. Not observed on the
  platforms checked so far. A gutter kept to the list views, not the whole page, would close it without
  touching the map.
- **The Downloads page's tabs overflow on a phone.** At 390 px "Historical NtM and Publications" runs past
  the right edge and is cut ("Historical NtM and Pub"); the strip neither wraps nor shows that it scrolls.
- **The map view's panel of messages without a position is translucent.** Over the map its background lets
  the terrain and the place names show through behind the titles, and a title that wraps continues under the
  id chip rather than in line with the title's first line. An opaque background and a hanging indent close it.
- **Cancelled messages are drawn on the map like the live ones.** The map view draws every message of the list
  (`shared/components/message-features-layer/message-features-layer.ts` 155-163, no status check), and the list of
  a publication being browsed holds its cancelled messages: the card says _Cancelled_, the map draws the same
  symbol as for a message in force. [`map-portrayal.md`](map-portrayal.md) says cancelled warnings must not be
  drawn on the map and stay findable in a list. Leaving `CANCELLED` messages out of the layer closes it. Found by
  the user guides' writers (2026-11-11), read in the code; not seen in a picture.
- **The map tooltip's id badge breaks at its hyphens.** Each row of the map's hover tooltip is a flex row of the id
  badge and the title inside a box at most `max-w-xs` wide
  (`ui-apps/info/src/app/features/messages/components/map-layers/message-map-tooltip.ts` 37-49). The badge may
  shrink and its pill (`.status-badge.mono-id`, `styles/components-core.css` 111-136) does not forbid wrapping, so
  whenever a title fills its line the id breaks at its hyphens and stands on two or three lines ("NW-" / "319-" /
  "26"). `shrink-0` on the badge in the tooltip, or `white-space: nowrap` on the id pill, closes it. Found by the
  user guides' picture review (2026-11-12, `public-site/PublicSiteMapTooltip.png`, which now points at a lone
  message for it).
- **A lone divider is left when the filter bar wraps on a phone.** The dotted dividers between the filter
  bar's groups are flex items of the same wrapping row as the buttons
  (`ui-apps/info/src/app/features/messages/components/message-filter/message-filter.component.ts` 46-49, 61-64 and
  104-107), so at phone width a divider can end up alone at the end or the start of a line, beside no group.
  Hiding the dividers below `sm`, or drawing the separation as a border of a group's wrapper, closes it. Found by
  the user guides' picture review (2026-11-12).
- **An area the public API does not know gives an empty teaser chart and an empty print sheet, with no word.** The
  backend drops an area id it cannot find and answers 200 with an empty list (`MessageSearchService` 351-360,
  `ApiRestService.searchMessages`), while an unknown publication id is refused with 400 (`AbstractApiService`
  123-136). The teaser (`ui-apps/info/src/app/features/teaser/teaser.component.ts` 152-153) and the print sheet
  (`message-print-view.component.ts`) report only a failed read, so the teaser for an unknown area shows its banner
  on an empty chart and the print sheet shows only its banner, where an unknown publication gives "Failed to load
  messages". Either the frozen v1 API answers 400 for an area id that resolves to no area, as it does for a
  publication, so the existing failure line shows; or the teaser and the print sheet get a line for an empty list.
  The first changes what an outside consumer with a stale area id gets, and the second needs a text, so the owner
  decides first. The hunt proposes keeping the API as it is and giving both views the empty-list line that _A list
  that comes back empty shows nothing_ above needs anyway, worded by the owner. Found by the error hunt's phase 2
  (2026-11-15), seen in a browser on a local rig; Low. Left by the owner on 2026-11-15: no new public-site text
  and no API change.

### The public site's image, configuration and gates

Background: [`ui-apps/info/AGENTS.md`](../ui-apps/info/AGENTS.md).

- **The image pins its base by tag, and has no `HEALTHCHECK`.** It runs as the unprivileged user `nginx` (101)
  on port 8080 and writes under `/tmp` alone, so it fits a pod held to the restricted Pod Security standard, the
  base Helm chart's `info` component, which probes it over HTTP. Left: the Alpine base is pulled by tag, not by
  digest, and a plain Docker run has no health check of its own; neither stops a restricted pod. Separately, and
  deliberately: the unhashed files under `assets/` (translation bundles, branding) are cached for five minutes and
  loaded without a version query, so for up to five minutes after a release a newly added key can show its
  fallback.
- **The `/wms/` proxy would not work as written.** The location is dormant — the base build has the sea-chart
  layer switched off, and a country's build switches it on only with a WMS of its own — but with `WMS_SERVER_URL` set, three things break: the variable `proxy_pass`
  resolves names only through the resolvers it lists (Docker's embedded `127.0.0.11`, then the public `8.8.8.8`),
  so a cluster-internal name is never found (every tile waits about 30 seconds and answers 502) and the lookups go
  to a third party; a path or query in `WMS_SERVER_URL` replaces the client's GetMap request instead of prefixing
  it; and the upstream receives the public site's `Host` (`ui-apps/info/nginx/default.conf.template` 344-363).
  Fix it — a resolver taken from the container, a host-only upstream with the path and the credentials in
  variables of their own, and `Host $proxy_host` — before a country enables the layer. The location already
  empties the four credential headers and verifies an https chart server against the image's CA bundle, so a WMS
  whose certificate a private CA signed is refused, the limit the next entry records for the backend; the
  container test has cases with a WMS configured by IP address (`scripts/info-container-test.mjs`), which needs
  no resolver. Read in the configuration (nginx not run); Low.
- **An https backend must carry a publicly trusted certificate.** The image verifies an https
  `NIORD_SERVER_URL` against the Alpine CA bundle alone, and no setting adds a private or internal CA: a backend
  whose certificate such a CA signed has every proxied request refused until the image is rebuilt with that CA.
  A backend in the same cluster is reached over its `http` Service address instead, which is the documented
  route. A CA file mounted into the container and named by a variable the entrypoint checks closes it, if a
  deployment ever needs one.
- **Three `proxy_cache_valid` directives do nothing.** The public API and repository-file locations (three
  minutes) and the `/wms/` location (24 hours) each carry one, taken over from the previous deployment, but no
  cache zone is defined and no location names one with `proxy_cache`, so nginx caches nothing. Configure a cache
  zone deliberately or drop the directives.
- **The first-visit gate here measures the base build.** `npm run bundle:info` and `npm run separation:info` run
  on the country-neutral build. A country's first visit — its footer in the initial chunk, its brand tokens and
  its overlay assets — is held in its own repository by the checks on every country build: the separation check,
  and the first visit against the country's own `info/bundle-baseline.json`, which its init does not create
  ([the kit's README](../../country-kit/README.md), _The checks on every country build_). Until a country starts
  that baseline, the check only warns, and Angular's raw budgets, whose warning does not fail the build, are all
  that holds its first visit.

### The public site's code and translations

Background: [`angular-conventions.md`](angular-conventions.md), [`ui-apps/info/AGENTS.md`](../ui-apps/info/AGENTS.md).

- **Component naming and member modifiers are half-applied.** About a third of the site's components follow the
  workspace convention — no `Component` suffix on the class or the file name, `protected readonly` for what the
  template reads, `readonly` inputs and outputs; the rest keep the inherited `*.component.ts` / `…Component`
  names and public, mutable members. Finishing it is a rename across the whole site that no reader would notice,
  so it waits. New components follow the workspace convention; the existing ones keep their names until the
  rename is done.
- **Translated strings cross component boundaries.** The translation-ownership rule (keys cross boundaries; the
  component that puts a text in the DOM translates it) is broken in two places. The shared dialog frame takes an
  already-translated `title`, which each opener pipes through Transloco. `MessageLabelService` returns finished
  strings for a message's type label and its cancelled or expired chip, which the id badge renders from a
  `computed()` whose only translation is a one-shot `translate()`. The badge is the visible one: the first time a
  reader switches to a language in a session, the badges recompute before that language's bundle has loaded and
  show the fallback language's words ("Afmeldt", "Udløbet" on an English page of a Danish deployment) until the list
  is read again, which takes seconds on a slow link — and in the details dialog can last until the reader
  navigates. The fix is keys out of the service, the pipe in the badge and the frame, and a spec that switches
  language with the bundle arriving late.
- **Three keys nothing renders.** `TERM_APPLY` and `TERM_CANCEL` word an apply and a cancel button no view has
  any more, and `ARIA_SWITCH_LANGUAGE` named the language buttons before each was named in its own language.
  All three still ship in the `en` bundle and in the countries' packs. Removing them is an `en` change, named in
  the PR so each pack prunes them (`pack.mjs prune` in its repository); a country's machine-made values for them
  and its translator's workbook are that country's to drop, before a professional translation comes back, so
  that nobody translates them.

### Shell, authentication and domains

- After a Keycloak login triggered by hitting a protected page while signed out, the user lands back on the page
  they came from, not the page they asked for — `role.guard.ts` hands `window.location.href` to `login()`.
- The Editor nav item and the Editor route disagree by design but not by affordance: the item is gated on
  editor/admin/sysadmin, the route also admits plain `user`. A `user` who may read and comment therefore has
  deep-link-only access with no way in from the header.
- Deleting or deactivating the domain the current session is on leaves the session on the dead domain until the
  next reload — the refresh only re-points ids that still exist. Documented as a known limit in `domain.service.ts`.
- A failed domain bootstrap degrades to an empty domain list with no notice. The start-up splash covers the wait;
  nothing covers the failure.
- Keycloak `url` / `realm` / `clientId` come from the backend at boot (`GET /rest/settings/keycloak`) or else
  from the build: the runtime settings overlay still excludes the whole `keycloak` block, because it is
  consumed before settings arrive.
- The update banner hears of a missing lazy chunk only through the router. A module imported outside a navigation —
  the rich-text engine when a description row first opens, the code editor, a `@defer` block — that fails after a
  deploy leaves its own surface empty (the landing map shows its `@error` retry) and raises no banner until a
  navigation fails the same way or the tab next comes back into view.
- **The shared select's and autocomplete's clear buttons and the autocomplete's chip-remove buttons do nothing
  from the keyboard, and every chip-remove button has the same name.** All three are real `<button>`s in the tab
  order, but they act on `(mousedown)` only: the chip's remove button
  (`ui-apps/admin/src/app/shared/forms/autocomplete/autocomplete.html` 12-18, handler `autocomplete.ts` 284-287),
  the autocomplete's clear button (`autocomplete.html` 54-59, `autocomplete.ts` 289-292) and the select's clear
  button (`ui-apps/admin/src/app/shared/forms/select/select.html` 46-51, `select.ts` 295-299). Enter and Space
  fire a `click`, not a `mousedown`, so a keyboard user tabs onto a button that does nothing, and a screen reader
  that activates by a click does the same. Backspace in an empty autocomplete removes the last chip
  (`autocomplete.ts` 269-272), so only the last one can be removed from the keyboard, and the select has no key that clears it
  (`select.ts` 231-280).
  Every chip's remove button is named by `removeLabel` alone (`autocomplete.html` 16; the callers pass
  `common.remove`), so a screen-reader user hears "Remove" once per chip without which. The specs hold only the
  mouse path (`autocomplete.spec.ts` 141-150 and `select.spec.ts` 122-129 dispatch `mousedown`). The date picker's
  clear button already acts on `(click)` (`shared/forms/date-picker/date-picker.html` 29). Fix: keep
  `(mousedown)` for `preventDefault()` alone (so the input keeps focus) and run the action on `(click)`, put focus
  back on the input or trigger after a chip or the value is removed, name each chip's button with the item's label
  as well, and add a spec that activates each button with a `click`. Found while preparing the screen-reader
  protocol (2026-11-18).

### Country deployments and translation

Background: [`country-deployments.md`](country-deployments.md).

- The favicon and `ui-apps/admin/src/index.html` (page title, splash wordmark, four literal colours) are fixed
  paths and literals shared by every country; no configuration overrides `index`. (The footer logo, texts and
  legal documents are the country's asset overlay; the header logo is the Niord mark, fixed by design.)
- The only per-country text layer is the footer's branding file; any other country wording difference can
  only be made through that deployment's runtime Dictionary. `provideTranslocoScope` is used nowhere. Runtime UI,
  model and default language settings are supported; a new UI language still requires a shipped bundle.
- The country seams are the ones `country-kit/seams.json` lists: the language list, the environments, the
  providers with their behaviour hooks (promulgation renderers and editors among them) and component slots
  (`home.welcome`), the country routes and menu, the asset overlay (branding and legal documents), the brand
  tokens and `index.html` ([country-deployments.md](country-deployments.md), _The behaviour seam_).
  The sysadmin promulgation admin-plugin registry has a core provider and no country extension.
- Declared and read by nothing: `--niord-primary-soft` (`tokens.default.css`, and so every country's copy). Either consume it or drop it from
  the documented overlay contract.
- A deployment's Dictionary rows keyed `template.*` or `publication_type.*` resolve to nothing: those screens ship
  under `editor.template.*` and the publications content-mode keys. Re-key the rows or accept that they are inert.
- Set-up and the launcher find a JDK differently. The launcher takes `JAVA_HOME`, else `~/.jdks/temurin-25`
  (`country-kit/templates/start-local.sh`), and exports it; a set-up run by hand runs `../be/mvnw` with what the
  shell has (`country-kit/templates/setup.mjs`). On a machine with neither `JAVA_HOME` nor `java` on the PATH the
  launcher runs, and a set-up by hand fails with Maven's
  `The JAVA_HOME environment variable is not defined correctly`. The country guide and the agent contract now say
  to export `JAVA_HOME`. Set-up taking the launcher's fallback, or naming `JAVA_HOME` when Maven fails, would
  remove the trap. Found by the agent route of the country guides' verification, round 2 (2026-10-26).
- The country guides and the release's commands reach a new country only inside the kit package. No Maven
  wrapper or `be/pom.xml` is needed before `init` any more, and the settings file, its catalog and the PDF logo
  come with the kit package, but a first maintainer still needs the country guide's Quick start
  before the kit is unzipped: it is read in the base product's repository (`country-kit/docs/country-guide.md`); the base
  product's release notes could carry it, or link the guides where they are published. The backend's English
  texts are outside both packages; the in-depth guide fetches the niord-base jar to read them (_The base product's
  backend files_). Found by the person and agent routes (2026-10-26).
- `be/docs/country-api.md`, the backend contract the guides send a country to, cites documents a country never
  receives (the split plan, the code/data/configuration plan, `be/docs/potential-errors.md`, DK's `pom.xml`,
  `be/scripts/bootstrap.sh`), and long passages record how DK and GL moved onto the base product. A country
  cannot follow the references, and the rules are harder to find. The next `be/` documents pass replaces each
  reference with the rule it stands for or a link to a shipped guide, and takes that record out (its Changes table
  starts again, empty, after 4.0.196, and is paused until production). The country guide meanwhile says that
  the rule a reference states is the rule. Found by the agent route (2026-10-26).
- **The administration app's English bundle ships keys that nothing reads.** Confirmed dead:
  `shared.auth.accountManagement` (the account menu reads `shared.auth.editAccount`),
  `core.domain.bootstrap.errorTitle` and `errorBody` (no notice uses them: a failed domain bootstrap shows none,
  see _Shell, authentication and domains_),
  `editor.parts.moveUp`, `moveDown`, `add` and `remove` (the part cards read `editor.part.*`) and
  `msg.no_pos_messages` (`languages/en/i18n/admin/en.json` 93, 216-217, 3957-3960 and 4538). A count of the keys
  with no literal reference in the application found 36 more candidates outside publications, not checked one by
  one (a key can be built at run time). Each still ships in every country's pack and goes to its translators.
  Removing them is an `en` change named in the PR so each pack prunes them, as for the public site's _Three keys
  nothing renders_; a check that every key is referenced (or listed as built) would stop new ones. Found by the
  user guides' check (2026-11-12, its map of the frontend); read, not run.

### Messages

Background: [`features/messages.md`](features/messages.md).

- **Cancellation warnings cannot be listed.** Cancelling a published NW warning in an auto-numbered series makes
  the backend issue a cancellation warning: a separate message of `kind=CANCELLATION`, published at once with no
  end date, which stays in force until an editor retires it. The messages search sends no `kind` parameter and
  every query-driven search defaults to `kind=WARNING`, so no list in the application shows one; each is
  reachable by its id alone. Until the application can list them, they stay in the S-124 in-force datasets.
  Needed: a kind switch in the message list, a banner in the editor on a `kind=CANCELLATION` message, and the
  announcement in the cancel dialog that cancelling will issue one (`SystemMessageVo.autoCancellationWarning`).
  The backend design is in [`s124-cancellation-plan.md`](../../be/docs/s124-cancellation-plan.md).
- **The tag dialog offers changes the backend refuses.** Unlock, edit, delete, and adding or removing
  messages are offered on a PUBLIC tag that belongs to another domain; the backend refuses each of them from
  any domain but the tag's own with 403, and the user sees a generic error. Hiding or disabling them needs the
  tag's domain, which `MessageTagVo` does not carry.
- **A toast raised while a dialog is open is drawn partly under the dialog.** Seen on 2026-11-16 in the bulk status
  dialog: when the backend refuses a row, the toast "No status was changed ..." sits half behind the dialog's modal
  layer. Nothing is lost there, because the refused row carries its reason, but any toast shown while a dialog stays
  open is covered the same way. Needed: the toast layer above the shared dialog's modal layer (or in the browser's
  top layer).
- No image or video in the attachment surfaces binds an `(error)` handler: a deleted or unreadable file renders as
  a broken image, in the thumbnail strip, the inline rendering and the preview dialog alike.
- The preview dialog opens with an empty frame and no loading feedback, and a non-previewable file shows neither
  its size nor its content type.
- The selection has no maximum and does not survive a reload (it is in-memory only). Opening a selected message's
  details does not carry the fuller record back into the selection — the summary it was starred from is kept.
- The message-series picker labels chips with the raw `seriesId` even though the model carries an optional `name`;
  focusing it with an empty box shows nothing; and a URL naming a series that no longer exists comes back with
  fewer chips and no explanation.
- A message id typed into a reference field or the reference filter is accepted without checking that it exists.
- The date filter accepts a from-date later than the to-date with no visual hint.
- Saved filters can only be created, applied and deleted — no rename, reorder, share or preview. One that stores
  ids belonging to another domain silently returns nothing.
- Messages carry `unackComments`, but no list row or grid card shows a comment count or an unacknowledged marker.
- The landing map asks for at most 1000 published messages and gives no sign when a deployment has more. The ZIP
  export has the same silent ceiling from the other side: the server caps a search export at 1000 messages and
  `message-export.service.ts` neither counts nor warns, so a larger search downloads a truncated archive.
- The table view keeps its date-column choice in the un-namespaced browser-storage key `dateColumnType`, while
  the rest of the application's keys carry the `niord.` prefix.
- There are no skeleton placeholders anywhere; lists signal loading with the thin progress strip only.
- **A bookmark with the Domain filter switches the current domain.** The Domain filter writes `domain=<id>` into
  the address (`features/messages/services/messages-filter-params.serializer.ts` 93-96), and at start-up the
  application reads the same parameter as a link to the current domain (`app.config.ts` 134-146,
  `core/domain/services/domain.service.ts` `resolveInitialSessionDomain`). Opening a bookmarked or shared Messages
  address with a Domain filter therefore makes the filtered domain the current one, with its roles and menus, where
  the filter only meant to search it. A filter parameter of its own name closes it. The editor manual
  (`guides/editor-manual/03-Messages.adoc`, _Domain Filter_ and the page's introduction) describes it as it is;
  change it with the fix. Found by the user guides' check (2026-11-11), read in the code.
- **The Report... dialog's result frame leaves an empty band.** The result of a mailing-list report sits in a box
  24rem high (`h-96`, `features/messages/dialogs/mailing-list-reports-dialog/mailing-list-reports-dialog.html` 75)
  around `app-sandboxed-html`, whose frame is fixed at 18rem (`h-72`,
  `shared/components/sandboxed-html/sandboxed-html.ts` 22): the result scrolls in the smaller frame, cut mid-row,
  and 6rem of empty box follow, inside two borders.
  Letting the frame fill its host closes it; the component's two other users (the mail queue's details, a
  mailing-list trigger's test) set no box around it. Seen in the user guides' rig (2026-11-11,
  `editor-manual/MailingListReportDialog.png`).
- **The Filter menu cuts off the bin of a saved filter with a long name.** A saved filter's row is its name and a
  bin; the name takes the row (`flex-1`) without `min-w-0` or truncation while the menu has a maximum width
  (`features/messages/messages-filter-dropdown/messages-filter-dropdown.html`), so a name of about 40
  characters pushes the bin half out of the menu. Truncating the name (with its full text as the title) keeps
  the bin whole. Seen in the user guides' rig (2026-11-12, `editor-manual/MessageFiltering.png`, whose example
  filter got a shorter name for it).
- **The import dialogs' _View batch job_ link opens a page an administrator is refused.** The message import
  (`features/messages/dialogs/message-import-dialog/message-import-dialog.ts` 16, offered to admin and sysadmin)
  and the publication series import of Admin > Publications (`publications-page.html` 699) link to
  `/sysadmin/batch/…`, and the whole Sysadmin area admits the `sysadmin` role only
  (`ui-apps/admin/src/app/core/auth/area-roles.ts` 18): an administrator who follows the link is told
  _Access denied._ and sent to the home page. Showing the link to system administrators only, or a job status
  the dialog shows itself, closes it. The editor manual (`guides/editor-manual/03-Messages.adoc`, _Import Action_)
  describes it as it is; change it with the fix. Found by the user guides' check (2026-11-11), read in the code.
- **The sender of a message mail is never told whether it was sent.** The backend only queues the mail (a pending
  `ScheduledMail`, `MessageMailService.java`), and the toast has said so since 2026-11-13. A mail that fails ends
  as _Error_ on the sysadmin's Mail Queue only, after its retries, and with `mailValidRecipients` at its shipped
  default `LOG` (`MailService.java`) nothing is sent at all. A way for the sender to see the mail's status, and a
  notice when it ends in _Error_, close it; both need a backend feature, as the Mail Queue is sysadmin-only and
  nothing records the sender to notify. Found by the error hunt (2026-11-12, track C, a reading of the delivery
  path); Medium.
- **Two lookups send `geometry=false` to handlers that do not read it.** `charts-api.service.ts` 42-61 and
  `areas-api.service.ts` 56-67 (`features/messages/services/api/`) send `geometry`, but `ChartRestService`'s
  `searchCharts` (83-90) and `/charts/search/{ids}` read no such parameter and map with `DataFilter.ALL`, and
  `AreaRestService.searchAreaIds` (118-137, `/areas/search/{ids}`) always adds `GEOMETRY`. Every chart search and
  area-by-id lookup returns full geometries, and the comment at `charts-api.service.ts` 42-44 is wrong. No result
  changes, only the answers' size. Reading `geometry` in the handlers, or not sending it and correcting the
  comment, closes it. Found by the error hunt (2026-11-12, track D, the frontend's calls held against the
  backend's handlers); Low. Left by the error hunt's fix stage (2026-11-13): no result changes and nothing wrong is
  shown, and the useful fix is in the backend handlers, which is performance work rather than a fault; recommended
  as a follow-up when the chart and area lookups are next touched.
- **The tag manager offers nothing to the owner of a locked private tag.** The tag manager
  (`message-tags-dialog`) offers no edit, duplicate or delete for a locked tag, and offers Unlock, which calls the
  admin-only `/unlock-tag`, to admin and sysadmin only. Since 2026-11-13 the backend lets the owner of a PRIVATE or
  TEMP tag change, unlock (a PUT with `locked` false) and delete it whatever its lock, so such an owner has no way
  in the app to do what the backend allows. Offering those actions to the owner of a locked private tag closes it;
  [features/messages.md](features/messages.md) (the tag manager: "A locked tag offers no edit, duplicate or
  delete") describes the dialog as it is until then. Found by the error hunt's fix stage (2026-11-13, cluster
  A19).
- **The admin app sends query parameters that eleven handlers do not read.** Spring drops each without a word, and
  no answer changes: `lang` to `GET /rest/areas/area/{id}` (`areas-admin-api.service.ts`),
  `/rest/firing-schedules/all`, `/rest/publication-series/{id}/history`, `/rest/charts/search` and
  `/rest/charts/search/{ids}` (`charts-api.service.ts`), `/rest/message-series/search` and
  `/rest/message-series/search/{ids}` (`message-series-api.service.ts`),
  `/rest/messages/copy-message-template/{id}` (`messages-api.service.ts`) and
  `/rest/messages/message/{id}/comments` (`editor-comments.store.ts`); `expandParams` to
  `/rest/message-reports/detail-reports` (sent only when the print settings' `expandParams` input is set, which no
  template binds); and `inclPromulgations` to `POST /rest/messages/adjust-editable-message`, which is the client's
  own merge switch (`message-adjust.util.ts` 32) and need not be sent. Stopping each parameter at its call site
  closes it, not in `LangHttpHelper.getByIdsCsv`, which also serves the area and category by-id reads that do read
  `lang`; then drop the entries of `fe/scripts/endpoint-roles/accepted-unread.json` that name this line. Found by
  the error hunt's wave 3b endpoint-role guard (2026-11-14), confirmed in the code by a second reader; Low, code
  hygiene (the old app sent `lang` to some of these too).
- **A guest's _Print_ of one message offers the Draft report, which ends on a raw 403 page.** The print settings
  narrow a guest's choice to the public report only for the list print (`loadReports` in
  `ui-apps/admin/src/app/features/messages/components/message-print-settings/message-print-settings.ts` 209-230,
  `guest && forList`), but the one-message print lists Standard and Draft, and the backend refuses a caller it does
  not know any report but the standard one (`MessageReportRestService`, 403). Picking Draft therefore ends on the
  backend's 403 page, as the list print did before 2026-11-16. Offering a guest only the standard report when not
  in list mode closes it. Met by the error hunt's phase 2 fix stage (2026-11-16, cluster P3-9), read in the code;
  Low.

### Map

Background: [`map-portrayal.md`](map-portrayal.md).

- A failing protected-WMS tile is logged to the console only; the user sees a blank layer and is told nothing.
- The messages map's LAYERS box is as wide as the search-result panel above it (about 320 px for two check
  boxes) and its background is translucent, so the map's place names show faintly through it.
- The tile cache size is the literal `2048`, written out in two layer files rather than being a deployment
  setting; the OpenSeaMap layer sets none at all.
- Large result sets are drawn as one vector feature per message — no clustering, no vector-tile source.
- Hovering a geometry on a single-message map shows nothing: the details layer has no hover handling, while the
  list and landing maps do.
- The coordinate-label layer builds and discards a whole GeoJSON feature per coordinate just to project one point.
- The layer switcher tears down and re-attaches listeners for every layer whenever the layer collection changes,
  instead of diffing what came and went.
- Geometry enrichment in the area and chart layers is not cancelled when the selection changes; a late response
  can re-add a feature the newer pass already drew.
- The S-124 approximate-position adornment is fully built and dormant: nothing upstream produces a positional
  quality flag, so the style branch can never be reached. It needs a backend field, not frontend work.
- `GET /rest/aton-icon/overview` is anonymous and nothing calls it: neither application draws AtoN (section
  3). Since the security fixes of September 2026 it renders its PNG in memory on every request, one request at
  a time, instead of writing a file per combination of values into the repository. Remove the endpoint, or
  give it a small bounded cache of rendered images.
- The administration app's own animations ignore a reader's reduced-motion setting: the 300 ms fit of the
  message-list layer, the geometry editor's zoom-to-extent and the drawer's slide. The map kit's own zooms and
  pans honour it; these three are started by the application and would read the same media query.
- **The administration app takes the tile addresses unchecked.** It applies the `osmUrl` and `openSeaMapUrl`
  web settings to every map as they are, a blank value aside (`ui-apps/admin/src/app/core/config/web-settings.ts`):
  a template that is not https, or lacks one of the `{z}`, `{x}` and `{y}` placeholders, reaches the tile layers
  and the base map can load nothing, with no notice. The public site takes `osmUrl` only as an `https:` template
  carrying all three (`resolveTileUrl`), and the backend's images fall back to their default for a template
  they cannot fetch from, so the three can draw from different hosts. The public site's check, applied to both
  settings in the administration app's overlay, closes it.
- **A superseded map image can outlive a restart.** When the backend draws a message's map image anew, it
  deletes the image it replaces 16 minutes later. A restart in between loses that deletion, and the old file
  stays in the message's repository folder until the image is next drawn — when the message is edited or the
  portrayal changes — because the request that finds a current image never lists the folder. That is at most
  one file per message; a daily sweep of the repository for superseded `map_*.png` files, beside the
  regeneration in `MapImageRegenerator`, would remove it.
- **The sea-chart (WMS) tile cache is built and switched on nowhere.** The backend's `/wms/` proxy can keep
  the tiles it fetches on the application's volume and serve them to logged-in users only (the
  `wmsCacheDays` setting, off by default). Decided by the owner on 2026-11-10 to 2026-11-12 in
  [`docs/sea-chart-tile-cache-plan.md`](../../docs/sea-chart-tile-cache-plan.md), which replaces the decision
  of 2026-10-24 to cache on the proxies' disks. What is open lies outside this tree. No deployed
  administration app names the chart's address: the Danish deployment's production environment file (in its
  own repository since the split) sets no `wmsUrl`, its test and development files name the previous
  production's proxy, and the Greenland deployment has no environment file; so no deployed map draws the
  sea chart, and the cache would serve nobody. Each staging needs its provider's address and token, and each
  country switches the cache on in its settings file. The public site gets no cache: its `/wms/` location is
  anonymous and stays dormant (_The `/wms/` proxy would not work as written_ and _Three `proxy_cache_valid`
  directives do nothing_, under the public site's image above).
- **Geometry import: a text parse in flight when the dialog closes can fill it again after a reopen.** In a
  dialog with no area source (it opens on _Text_), `reset()`
  (`ui-apps/admin/src/app/shared/components/geometry-editor/geometry-import-dialog/geometry-import-dialog.ts`
  259-268) cancels an area pick in flight but not the text and UTM parses, and their tab gate passes after a
  reopen on the same tab. An answer that lands after a quick reopen fills the working set with the previous
  open's positions while the text box is empty; the comment above the gate (206-207) says it covers a reopen. An
  open token, as _Edit as text_ has, or a value pushed into the two parse streams from `reset()` that maps to
  nothing, closes it. Met by the error hunt's phase 2 fix stage (2026-11-16, cluster P1-13), read in the code;
  Low.
- **A dropped file's latitude of 270 or more is taken as another latitude.** The editor leaves out a dropped
  feature whose position has no number in the map's projection
  (`ui-apps/admin/src/app/shared/components/geometry-editor/ol-editor-layer/ol-editor-layer.ts` 331-334,
  `util/longitude-turns.ts` 52-60), which catches a latitude above 90 and below 270. A latitude of 270 or more
  projects to a finite place (405 reads as 45), so the feature is taken at that other latitude and saved there;
  the backend's refusal of a latitude beyond 90 never sees the file's value. Checking the file's own
  latitudes before projecting closes it. Met by the error hunt's phase 2 fix stage (2026-11-16, cluster P1-12-fe),
  read in the code; Low.

### Editor

Background: [`features/editor.md`](features/editor.md).

- `/editor/execute-template` is not deep-linkable: the message type and the working copy live in an in-memory seed
  store, so a pasted or reloaded URL returns to the start screen.
- The template parameter-type model is shared from `features/messages/models/template.model.ts` and the editor
  uses it; the admin section still declares its own diverging copy.
- Two positions UIs coexist: the shared `GeometryPreviewField` used by the template cards, and the message-part
  card's own implementation.
- The two insert-dialog preference keys (`formatLocationSettings`, `formatTimeSettings`) are un-namespaced while
  the rest carry the `niord.` prefix.
- Two editors saving the same message overwrite each other: there is no revision check on the save path. Accepted
  today; worth a decision if concurrent editing becomes common.
- The deployment setting for the base editor-field visibility has no consumer; the additional-editor-field pool
  is a pinned list in `editor-field-candidates.ts`.
- The rich-text toolbar's text-colour picker (description and comments) writes an inline `color` on a `<span>`,
  which the message-HTML sanitiser drops: the editing surface shows the colour, the preview, the message details,
  the comment cards and the public site do not. Either the picker goes, or `color` joins the sanitiser's two kept
  declarations.
- A time typed into the date-time field in a clock change resolves differently from the backend.
  `zonedDateToUtc` (`shared/utils/zoned-date.ts`) says a clock in a spring-forward gap moves forward and one in a
  fall-back hour is its second occurrence, "as Java does"; Java takes the first occurrence, and west of UTC the
  gap moves backward: in `America/Nuuk`, 23:30 on the spring day resolves to 22:30. The public site's copy
  resolves both as Java's `ZonedDateTime.of` does. (The inclusive end of a day in the same file, which had the
  same fault on the autumn day, is derived from the next day's start since 2026-11-13, in the frontend and in the
  backend's `TimeUtils.endOfDay`.) When it is fixed, this item's allow entry in `country-kit/country-words.json`
  goes too.
- A save the backend refuses with 400 shows only the general `editor.save.error` toast, never the reason the
  backend gives. Since the rulings of 2026-09-26 a short id or referenced message id holding `<`, `>`, `"` or `&`
  is refused so (`docs/security-review-2026-09.md` §8); the editor could show the problem detail, or check the
  short-id field inline. The working copy is kept, so nothing is lost.
- **Revert to draft is offered to users without the editor role.** Above the live preview, the _Draft_ step of a
  verified message is a button for everyone who can open the Edit tab
  (`features/editor/components/status-flow/status-flow.ts` 62-74); only the Verify and Publish action beside it
  (line 88) and the Status page's buttons (`status-action-panel.ts`) are inside the `isEditor()` check. A plain
  user, whom the editor route admits, clicks it, confirms, and the backend refuses (`CHANGE_STATUS` needs the editor
  role, `MessageRestService` 171): "Could not revert to draft". A button only when `isEditor()` holds closes it.
  The editor manual (`guides/editor-manual/04-Editor.adoc`, _Live Preview and Status_) describes it as it is; change
  it with the fix. Found by the user guides' check (2026-11-11), read in the code; not clicked on the rig.
- **The cancel dialog's two buttons are both called Cancel.** The dialog that cancels a published message
  (`features/editor/components/cancel-confirm-dialog/cancel-confirm-dialog.ts` 40-45) closes with `term.cancel`
  ("Cancel") and cancels the message with `editor.status.cancel_msg`, also "Cancel": only the red colour tells them
  apart, and a screen reader hears the same name twice. "Cancel message" on the red button and "Close" on the other
  close it. `04-Editor.adoc` (_Cancelling a Message_) says so as it is. Found by the user guides' writers
  (2026-11-11).
- **The History tab cannot tell apart two versions with the same number.** The revision table tracks its rows by
  `version` and the selection compares versions
  (`features/editor/components/revision-table/revision-table.ts` 29 and 58-60,
  `features/editor/views/editor-history-page/editor-history-page.ts` 199-205; `MessageHistoryVo` carries no id).
  Messages carried over from the previous system hold repeated numbers: in `niord_guides` 384 version numbers are
  used twice or more, on 354 messages (entries from 2016 to 2022); entries written by Niord 4 repeat none (0 of
  the 1,745 in `niord_test` since August). On such a message, ticking one of the two rows ticks both and the pair
  is compared, neither can be shown alone, and Angular's `@for` meets a duplicate key [read in the code and the
  data; not clicked]. Keying the rows and the selection on the entry's id, or on its index, closes it; SCRIPT9 in
  `be/docs/potential-errors.md` is the same fault in the script resources' history dialog.
- **Load errors read as empty data in several places.** Nine components declare an `httpResource` and never read
  its error state: the home page, the editor's action menu, recently edited drafts, the template selection drawer
  and search popover, the comments store, the History page, the message details menu and the template parameters
  field. A failed load there shows the empty state ("no history", no comments, no drafts) as if there were nothing.
  In Admin > Publications the series and one-off editors turn a failed load of the domains, the publication
  categories or the reports into an empty list (`catchError(() => of([]))`, `series-editor.ts` 366, 386 and 490,
  `one-off-editor.ts` 309, 319 and 344, `one-off-detail.ts` 131, `issue-overrides-drawer.ts` 133), as does the
  one-off list of the Publications page (`publications-page.ts` 294); an editor then sees an empty picker and no
  notice. The Publications panel of the editor shows how it is meant: an error banner with _Retry_. Found by the
  user guides' check (2026-11-12, its map of the frontend); read in the code (four of the nine resources and every
  `catchError` site re-read for this entry), no failure was provoked.

- **A missed row in the Publications panel cannot be read to its week, and its date looks like a release.** A
  `MISSED` row has no link (only a frozen row is in the document), so its name is the plain span with `truncate`
  and no `title` (`features/editor/components/message-issues-panel/message-issues-panel.ts` 129-131): in the side
  panel "Guide example weekly list, week 46 - 2026" is cut to "Guide example weekly l…" and the week cannot be
  read, by hover or otherwise. The line under it shows the issue's period start (`stamp`, 209-212, by design for
  a missed row), with nothing saying so, while the same issue's row after an amend shows its release date: a duty
  officer may take the period start for the day it came out. The full name as the span's title (or wrapping
  instead of cutting), and a word for the date (or the period's two ends, `intervalFrom`/`intervalTo`, which the
  answer carries) close it. Found by the error hunt's live check (2026-11-13); read in the code.
- **The source view's _Format_ writes an HTML comment beside a block as the word "undefined".** `inlineOf`
  (`ui-apps/admin/src/app/shared/forms/rich-text-editor/html-format.ts` 61-65) treats every inline node that is
  not text as an element and takes its `outerHTML`, which a comment does not have, so
  `<div><!-- c --><p>x</p></div>` (pasted Word HTML can hold such comments) comes out with `undefined` in place
  of the comment. Writing a comment node as `<!--…-->`, or dropping it, closes it. Met by the error hunt's phase 2
  fix stage (2026-11-16, cluster P3-11), read in the code; Low.

### Admin and sysadmin

Background: [`features/admin.md`](features/admin.md), [`features/sysadmin.md`](features/sysadmin.md).

- The Help link for publication categories opens no section on the hosted manuals. The application maps
  `publication-categories` to `/admin-manual/manual.html#publication-categories`
  (`ui-apps/admin/src/app/core/main-shell/help/help-urls.json` 25, pinned by `help-url.service.spec.ts` 153-155),
  and the repository's manual carries that anchor (`guides/admin-manual/05a-PublicationCategories.adoc` 4), but the
  `?` opens the manual at `documentationUrl` (`https://docs.niord.org` in every environment file), whose published
  administrators' manual has only `publication-categories-tab` ([`docs-niord-org-map.md`](docs-niord-org-map.md),
  the section descriptors). It closes when the rewritten guides are published there.
- The Settings page renders every setting identically. Nothing marks the keys the web client never reads, so a
  sysadmin can edit an inert value and see nothing change.
- The standard-template-field pool is a pinned array in the Categories editor rather than the deployment's
  `stdTemplateFields` setting; the additional-editor-field pool has the same shape.
- The Settings `json` value and the batch execute-JavaScript dialog use `<app-code-text-input>` without a
  `[language]`, so both render the plain textarea instead of the highlighted editor.
- Four of the fourteen pages that host the shared import dialog pass a `jobLink`; the rest open an import with no
  "view batch job" link.
- Areas registers no param segment, so an area cannot be linked to — only publications and batch jobs have the
  trailing-param route.
- The mailing-list list filters in the browser over the whole download. The API service's optional `name=`
  parameter has no caller (the client filter is broader: id, name and description).
- The Keycloak console deep link is assembled as `{url}/admin/master/console/#/{realm}/clients` and has never been
  confirmed against the deployed Keycloak version.
- The users list fetches up to 10 000 Keycloak users in one request and pages in the browser — ruled acceptable,
  but it is the one place that scales with the realm.
- The retroactive accumulated years are made **by hand after go-live**, oldest first: every year since the
  last one carried in from the previous system shows on the strip as a missing period, and each is one
  session — create from the gap, review the grouped list and the Sources card, publish with the 31 December
  cut-off. Nothing new inside the go-live window, and each year's public window is capped by the next.
- A published compilation whose sources have moved since its release shows **no banner**. The issue records
  the source issues it covered, so comparing that record against today's published set is cheap; what to do
  about a difference (amend to refresh) is the decision, and it is sequenced after the first annual is out.
- **The series criteria editor lists message types by their codes.** The _Message type_ criterion's boxes read
  `LOCAL_WARNING`, `TEMPORARY_NOTICE` and the like: its options carry the code as their label and no
  `labelKey` (`sections/admin/publications/series-criteria-editor.ts` 387-392), while the _Main type_ options
  beside them have one (394-398). English names exist (`msg.type.*` in `fe/languages/en/i18n/admin/en.json`).
  Found by the user guides' pictures (2026-11-12).
- **An issue's History reads "1 entries".** `admin.publications.issue.historyCount` is `{{count}} entries` with
  no singular (`fe/languages/en/i18n/admin/en.json`, used in `issue-detail.html` 1456), so an issue with one
  history entry, such as a newly opened one, says "1 entries". The member count beside it already picks a key
  by the count (`memberCountKey`). Found by the user guides' pictures (2026-11-12).
- **The publication category's description promises a place on the public site.** The editor's hint under
  _Description_ (`admin.publications.categories.hint.description`, "Optional. Shown beside the category where
  the public page has room for it.", `categories-tab.html` 226) says the public site shows it. The public site
  draws only the category's name as the heading of its publications (`ui-apps/info/src/app/features/publications/components/publication-list/publication-list.component.ts`
  32-37) and reads the description nowhere. Either the site shows it or the hint says it is kept with the
  category only. Found by the user guides' check (2026-11-12).
- **A new issue of a series with no time relation is warned of as an in-force list.** The New issue dialog's
  draft adds `IN_FORCE_HAS_NO_LOWER_BOUND` ("issues of this publication describe what is in force at an
  instant") whenever the series' time relation does not tile (`be/.../publication/series/IssueDraftService.java`
  134, 329-333). A series of uploaded files or links has no time relation at all, so it gets the warning too,
  although it lists nothing in force. Seen on an uploaded series without a frequency by the user guides'
  pictures (2026-11-12).
- **A series with no issue hides its missing periods on its own page.** The backend lists a _Missing_ and an
  _Upcoming_ row for an active weekly series that has never had an issue
  (`GET /rest/publication-issues/series/{id}`, `gapCount` 1), and its card on the Publications page shows the
  missing week. Its page says _No issues yet_ instead: `isEmpty` counts the real issues only
  (`series-detail.facade.ts` 528-530), which hides the rows, their _Create_ and the line that counts the missing
  periods (`series-detail.html` 337-383). The comment there means to keep a lone _Upcoming_ row out; a _Missing_
  row is a finding and could be shown. _New issue_ under _Current issue_ still opens the first period. Found by
  the user guides' pictures (2026-11-12).
- **The entity tree's search prunes only its top level.** `filterTree`
  (`ui-apps/admin/src/app/shared/components/entity-tree/tree-filter.ts`) prunes at every depth, but the CDK nested
  tree renders a pruned node under the same `trackBy` key as before and keeps its cached child rows
  (`entity-tree.ts` 104-106 and 124-138, whose comment names the same caching for the selection): searching
  "Bornholm" in the geometry import's _From Area_ tab drops the other countries but leaves every row under Denmark
  and The Baltic Sea, and a click on a nested row then did nothing. Every `app-entity-tree` may be affected (the
  areas and categories pages, the user editor, the template selection). Seen by the user guides' picture
  review and measured by its fixer (2026-11-12); the finishing round an hour earlier saw the tree pruned, so the
  conditions are not settled, and the cause is inferred from the code. A spec that filters an expanded nested tree
  and counts the rendered rows would settle it. `fe/guides-shots/editor-manual-positions.shots.ts` leaves the
  search empty for `PositionEditorImportGeometry.png` until it is fixed.
- **Category _Test…_ runs the template without parameters.** The editor's _Test…_ sends the example message's id
  and the category, no template parameters
  (`ui-apps/admin/src/app/features/admin/services/api/categories-admin-api.service.ts` 101-106), and the backend
  then gives each template an empty map (`TemplateExecutionService.java` 462-464), so a template that writes its
  text from its parameters, such as "Light buoy unlit", shows a _Details_ heading with nothing under it. A test is
  meant to show
  the template's output; a parameter form in the test (or opening the _Execute templates_ page on the example
  message with this category) closes it. The system administrators' manual
  (`guides/sysadmin-manual/10-Categories.adoc`, the _Test…_ paragraphs) describes it as it is; change it with the
  fix. Found by the user guides' check (2026-11-11, `CategoryTemplateResult.png`).
- **The mail details' _Sent_ row shows when a mail is due, also for a mail not yet sent.** The row prints the
  mail's `sendDate` (`ui-apps/admin/src/app/features/admin/sections/sysadmin/mails/mails-page.html` 166), which is
  the time the mail becomes due: set to the creation time when it is queued and moved on after each failed attempt
  (`be/.../core/mail/ScheduledMail.java` 85-87, 119-120 and 180). A _Pending_ mail therefore shows a _Sent_ time,
  and a failed one the time of its next attempt; the template's comment (`mail-details-dialog.html` 51) expects
  the value to be empty while pending. Labelling the row _Send date_, or showing it as _Sent_ only for a sent
  mail, closes it. The system administrators' manual (`guides/sysadmin-manual/12-MailQueue.adoc`, _Mail Details_)
  says the dialog shows when the mail was sent. Found by the user guides' picture review (2026-11-12); read in the
  code (the picture's own time was the rig's).
- **The Batch Jobs card reads "1 executions".** `admin.batch.executionsCount` is `{{count}} executions` with no
  singular (`languages/en/i18n/admin/en.json` 1658, used in `batch-page.html` 31), as the issue history's "1
  entries" above. Found by the user guides' picture review (2026-11-12).
- **The domain editor's _Map Center_ boxes have no visible labels.** Latitude, longitude and zoom are three number
  boxes under one heading, named by their placeholders and aria-labels only
  (`ui-apps/admin/src/app/features/admin/sections/sysadmin/domains/domain-editor.html` 108-141), so once they hold
  values the reader sees three unnamed numbers. A visible label per box closes it. Found by the user guides'
  picture review (2026-11-12).
- **The example-message field translates its placeholder twice.** The Categories editor passes
  `'admin.categories.field.exampleMessagePlaceholder' | transloco` to `app-message-id-field`
  (`ui-apps/admin/src/app/features/admin/sections/sysadmin/categories/category-editor.html` 144), which pipes its
  `placeholder` through transloco again for the placeholder and the aria-label
  (`ui-apps/admin/src/app/features/messages/components/message-id-field/message-id-field.html` 24-25). The second
  lookup misses and returns the text, so nothing shows in a production build; a development build logs a missing
  key on each render. It breaks the translation-ownership rule (the component that renders a text translates it),
  as the drawer header and the comment e-mail placeholder did before they were fixed on 2026-11-11/12; passing the
  key closes it. Found by the user guides' check (2026-11-12, its map of the frontend).
- **A refused delete of an area, a category or a message series shows no reason.** Since 2026-11-13 the backend
  refuses to delete one that is in use with a 409 whose detail names the uses (messages, domains, firing periods,
  NAVTEX transmitters, firing schedules, S-124 configurations), and deletes a sub-area or sub-category that is not.
  The three pages' delete-error toasts show only their generic text. Showing the 409's detail closes it; the
  administrators' manual (`guides/admin-manual/04-Areas.adoc`, the delete paragraph) and the system administrators'
  manual (`guides/sysadmin-manual/03-MessageSeries.adoc`, the delete paragraph) can then name the reason. Found by
  the error hunt's fix stage (2026-11-13, cluster B3).
- **A refused delete of a parameter type shows no reason.** Sysadmin > Parameter types shows only the generic
  "Error deleting parameter type" toast (`param-types-page.ts`) when a delete fails. Since 2026-11-13 the backend
  refuses to delete a type that a template or a composite type uses, with a 409 whose detail names the categories
  and composite types; showing it, as the dictionary entry delete does, closes it. Found by the error hunt's fix
  stage (2026-11-13, cluster B16).
- **A failed Generate of a promulgation shows no reason.** Since 2026-11-13 `POST /rest/promulgations/generate/{typeId}`
  answers a failing script or service of the promulgation type with a 500 whose detail says the type's script or
  service failed and that the server log holds the error (it was a bare 500). The editor's toast
  (`promulgation-field.ts` `runGenerate`, `editor.promulgation.generateError`) shows only "Could not generate the
  '{{type}}' promulgation", so a duty officer cannot tell a broken script, which a system administrator must
  repair, from a passing fault. Showing the detail, or a second sentence for a 500 that says to tell a system
  administrator, closes it. Found by the error hunt's fix stage (2026-11-13, the review's R1-3).
- **_New edition_ stays offered while an edition superseding the issue exists.** `canOpenNewEdition`
  (`sections/admin/publications/issue-detail.ts`) offers _New edition_ on a published issue whatever its
  successors, and its comment relies on the server refusing an overlapping interval, which this path never sends.
  Since 2026-11-13 the backend answers a repeated _New edition_ with the open successor, so pressing it opens that
  edition rather than a second one; hiding the button, or naming it _Open edition N_, while a successor is open
  says so before the click. Found by the error hunt's fix stage (2026-11-13, cluster B24).
- Missing unit specs: `area-editor`, `areas-page`, `areas.facade`, `sort-area-dialog`, `master-detail-tree-admin`,
  the hierarchical-admin page base / tree state / unsaved-changes guard, `editor-fields-grid`, and
  `mailing-list-reports-api.service`. `entity-tree.spec.ts` covers mapping and selection but not the
  drag-and-drop reparent guards or the emitted move payload. That guard (`entity-tree.ts` 177, `isDescendant` 198)
  is the app's check against moving a node under its own descendant; the backend refuses such a move with 400 since
  2026-11-13 (the error hunt), so a spec of a drop onto a descendant is test coverage of a guard that works.
- Under the dev server the backend's static files 404: the Batch jobs page's file icons
  (`img/filetypes/generic-32_32.png`), an attachment's icon in the editor and the message details, and the
  stylesheet of a mail or report the application shows (`/css/templates/mail.css`). The icons are
  `/rest/batch/instance/{id}/thumbnail.png` (`ui-apps/admin/src/app/features/admin/services/api/batch-api.service.ts`)
  and `/rest/repo/thumb/…`, which the backend answers with a redirect to an absolute `/img/filetypes/…` URL built
  from the request's host (`BatchRestService.getBatchDataFileThumbnail` 258-270, `RepositoryService` 318-325); the
  map image's placeholder, `/rest/message-map-image/{id}.png`, is not affected, since it answers itself with no
  redirect (`MessageMapImageRestService` 226). A mail or report
  result (Messages > Report..., the Sysadmin mail queue's details, a mailing-list trigger's test) links its
  stylesheet by path, and the sandboxed frame loads it from the page's origin. Behind the dev server that origin is
  the dev server's, whose proxy (`proxy.conf.mjs`) forwards `/rest` only, so the icons break and the result shows
  unstyled. A deployment serves both: the chart's nginx sends everything but `/auth` to the backend, which serves
  `/img` and `/css/templates` beside the bundle (`be/k8s/helm/niord/templates/nginx/configmap.yaml` 71-76).
  Proxying `/img` and `/css/templates` in the dev server closes it, as the user guides' file server does
  (`fe/guides-shots/serve-built.mjs`); a deployment that serves the administration app from another origin
  (`adminAppUrl`) needs the same two passes on its web server. Seen in the manual check of 2026-10-22
  (`docs/code-data-config-plan.md` §6.4) and by the user guides' rig (2026-11-11).
- A domain name or domain id made of spaces passes the domain editor: Signal Forms' `required` takes only an empty
  string as empty (`domain-editor.ts`), so Save is enabled, the backend refuses with 400 "A domain needs a name"
  and the editor shows its generic save error without that sentence. Trim before the check, or show the detail.
- **A refused SECOM outbox _Retry_ or _Cancel_ leaves the stale row's buttons.** Since 2026-11-16 the backend
  refuses a retry or a cancel of an entry in another status with 409, but the outbox tab's error branch only shows
  its toast
  (`ui-apps/admin/src/app/features/admin/sections/sysadmin/secom/secom-outbox-tab.ts` 115 and 133), so the row
  keeps its old status and buttons until the operator presses _Refresh_. Nothing is sent again or cancelled.
  Refreshing the outbox in both error branches, as the success branches do, closes it. Met by the check of the
  error hunt's phase 2 fix stage (2026-11-16, cluster P1-19), read in the code; Low.

### Schedule

Background: [`features/schedule.md`](features/schedule.md).

- The selected day is component state, not a URL parameter: a day cannot be linked to or bookmarked, and a refresh
  returns to today.
- Cancelling the inline period editor discards unsaved rows with no prompt.
- Pressing Add appends an empty row but leaves keyboard focus where it was.
- **The schedule map's Layers card covers the zoom buttons and the attribution.** The Firing Area Schedule page
  places its layer switcher at the map's bottom right (`absolute right-2 bottom-2 z-10`,
  `ui-apps/admin/src/app/features/schedule/schedule-view.html` 172-175), where the administration app also puts
  the zoom buttons (`app-ol-map .ol-zoom`, bottom right, `ui-apps/admin/src/styles.css` 139-144) and OpenLayers
  its attribution, so the card lies over both and they cannot be reached. The other maps with a card of their own
  (the message details, the editor's positions preview) put it at the bottom left; doing the same here closes it.
  Found by the user guides' picture review (2026-11-12, `editor-manual/FiringAreaSchedule.png`, kept as it is).
- **A firing period's time fields have no visible label and cut their placeholder.** The From and To fields of a
  period are named by their aria-labels only
  (`ui-apps/admin/src/app/features/schedule/components/time-schedule-editor/time-schedule-editor.html` 5-17), and
  in their `w-28` box the 12-hour placeholder `h:mm AM/PM`
  (`ui-apps/admin/src/app/shared/forms/time-field/time-field.ts` 78) is cut to "h:mm A…" beside the clock icon:
  the reader sees two unnamed fields per line. Visible labels (or a column heading) and a box wide enough for the
  placeholder close it. Found by the user guides' check and picture review (2026-11-11/12).

### Shared styles

Background: [`styling-reference.md`](styling-reference.md).

- **The shared stylesheets carry administration-only rules, the applications diverge on shared ones, and nothing
  guards the administration app against a shared-style edit.** About half of the classes in
  `styles/components-core.css` are used by no public-site template (roughly 0.7 kB brotli on every first visit),
  and `.row-selected .details-row` names a class only the administration app renders, against the file's own
  rule. On the public site's table rows the `bg-surface-0` utility beats `.row-interactive`'s hover tint, so the
  rows never tint. The administration app's `underline` utility underlines the `tr.underline` / `td.underline`
  rows of message HTML that `message-html.css` only borders, so a coordinate table previews underlined there and
  plain on the public site. The public site keeps map-control rules for OpenLayers 2 classes that match nothing,
  while the administration app moves `.ol-zoom` on its own, so the kit's controls sit in different corners in the
  two applications. `button.chip:disabled`, written for the public site's print chip, also dims the
  administration app's domain chips whenever their field is disabled (no caller disables it today). The
  administration app's built stylesheet is equivalent to what it was before the stylesheets were split, not
  byte-identical: it lost the utilities no administration source uses and gained that chip rule.
  `scripts/css-neutrality.mjs`, which compares two built stylesheets, is run by no npm script and no workflow, and
  it reports "identical under reordering" for real value changes, because it sorts declarations and drops those
  under nested at-rules. None of this breaks a task, so it waits: move the administration-only families to
  `ui-apps/admin/src/styles/components-admin.css`, settle the three divergences, delete the dead map rules, and either fix the script (keep
  the order, walk nested at-rules, fail on a reordered shared property) and run it in CI on `fe/styles` changes or
  record it as a one-off.
- **Three contrast and forced-colours gaps in the administration app's own classes**, left where the shared
  classes were fixed. A pressed `.status-chip` (the status filter) is marked by its fill alone, so in a
  high-contrast theme it looks like an unpressed one — `button.chip` and `.seg-btn` have a forced-colours rule
  in `components-core.css` that it could join. `.tree-filter-input:focus-visible` draws its ring in the accent,
  which need not hold 3:1 on the page (a country's orange accent measured 2.3:1), where the tabs now use the
  primary. The rich-text editor's frame is
  `--color-surface-300`, under 2:1 on white, beside form fields whose border now holds 3:1.
- **The default `--niord-fg-subtle` is below AA off `--niord-surface`.** `#667589` measures 4.70:1 on
  `--niord-surface`, the floor the designers' brief sets, but 4.34:1 on `--niord-bg` and 4.45:1 on
  `--niord-surface-sunken`, and subtle text can sit on both: `text-fg-subtle` is a utility 189 template lines use,
  on whatever background they have, and `.text-input:disabled` sets it on the sunken fill
  (`styles/components-core.css`). The brief tells a designer to meet 4.5:1 there too and calls the default's
  figure a known gap. Fix: a darker default (`#5f6d80` measures 5.27:1, 4.86:1 and 4.99:1 on the three) and the
  floor widened to the three backgrounds. Found by the design route of the country guides'
  verification (2026-10-26).

### Quality gates and tooling

Background: [`testing.md`](testing.md).

- Dependabot alert 68, `minimatch` below 11.0.1 (a denial of service through a glob pattern), stays open in the
  lock: the only `minimatch` is 10.2.5, held by ESLint and typescript-eslint at `^10`, so no update within their
  ranges fixes it and Dependabot opens no pull request. It is a lint-only dependency that runs on this repository's
  own files, so the owner dismissed the alert as tolerable (2026-11-01). It closes itself once ESLint and
  typescript-eslint move to `minimatch` 11; an `overrides` entry forcing 11 under ESLint was not wanted.
- **The pre-commit hook is never installed.** `prepare` runs husky 9 in `fe/`, which stops at ".git can't be
  found" because `.git` is at the repository root, so `core.hooksPath` stays unset and `fe/.husky/pre-commit`
  never runs: lint-staged (since KM-2 also over `../country-kit/`) runs only by hand, `npx lint-staged` from
  `fe/`. Found in KM-2 (2026-11-01). The fix runs husky from the root (it refuses a folder named with `..`), for
  instance a `prepare` of `cd .. && husky fe/.husky`, and the hook then has to `cd fe` before `npx lint-staged`,
  since git runs hooks at the repository root. Tooling only; nothing in production depends on it.
- No accessibility tooling at all: neither axe nor Lighthouse is installed or wired into CI. The only automated
  check is the Angular template accessibility lint rule set.
- The Playwright suite is excluded from CI by design — it needs a live backend and Keycloak — so nothing runs it
  on a push. It is a local, deliberate step.
- The Playwright suite cannot pass against the local stack `start-local.sh` starts, which is why it is documented
  to run on 4200. The specs that expect a URL hard-code
  `localhost:4200` (twenty spec files name it), although `playwright.config.ts` reads `E2E_BASE_URL` and the local stack serves the
  administration app on 4100 + its slot; the language is pinned to English for authenticated pages only, so the
  anonymous specs follow the backend's default language (a Danish deployment's, say) and fail on their English
  copy; and
  `tsc -p ui-apps/admin/e2e/tsconfig.json` fails with `rootDir` errors, because a spec imports application
  source. A run against a Danish deployment's stack failed 18 of 71 tests, every one for these reasons — enough red to hide a
  real regression among them. Nothing runs the suite in CI, so it waits for the next time the suite is worked on: derive
  the home URL from `baseURL`, set the language in the base fixture, and give the e2e tsconfig a `rootDir` of `..`.
  (Since P3c2 the suite names no environment: `KEYCLOAK_URL` is required, the unreachable-Keycloak spec blocks
  every `/realms/` path, whichever host, and the deep-link spec reads the served backend through `baseURL`.)
- `data-testid` is used only on the publications screens (twelve templates plus one inline) and the dev geometry
  sandbox; the rest of the suite binds to English copy, which a Dictionary override or a wording change breaks.
- `@vitest/coverage-v8` is installed but there is no `test:coverage` script and no threshold anywhere.
- **`ui-apps/admin/src/app/app.spec.ts` can time out.** "should render main layout sections" timed out once in
  `fixture.whenStable()` (5 s) on a CI runner and passed on the re-run. The spec builds the shell from the real
  `appConfig` — the real `HttpClient`, and the start-up initializers that start authentication, read the web
  settings and load the translation bundle — with only the domain lookup stubbed, so what `whenStable()` waits
  for depends on work no test answers. Giving the spec the testing `HttpClient` and stubs for the start-up
  services, or testing the layout without the application's initializers, closes it.
- **`ui-apps/info/src/app/app.config.spec.ts` can fail on a busy machine.** "opens a new page at its top, and Back
  returns to where the reader was" failed once in a whole `./verify-local` on a Windows workstation that ran
  several Maven builds beside it (1 of 882; 2026-11-14), and passed on the re-run with nothing changed. The spec
  boots the real `appConfig` and steps the router with `nextTask()`, one macrotask per navigation, so a scroll
  event that arrives a task late is missed. Waiting for the navigation's own end (the router's `NavigationEnd` and
  the `Scroll` event) instead of a fixed number of tasks closes it.
- **One guard test cannot pass in Git Bash on Windows.** In `country-kit/base-bump-workflow.test.mjs`, "refuses a
  changed byte, another SHA256SUMS, a short SHA-256, unverified notes and a missing lock" takes the lock's line
  out of its `SHA256SUMS` fixture with `line.endsWith(' niord-pins.lock.json')`. Git Bash's `sha256sum` writes
  `<hash> *niord-pins.lock.json` (the binary marker), so the line stays and the step answers "FAILED open or
  read" where the test expects "SHA256SUMS does not list niord-pins.lock.json." It passes on CI's Linux runners;
  on a Windows workstation `npm run test:guards`, and with it `./verify-local`, ends red on this one test (seen
  2026-11-17, 1 of 1,819, on `main` as well). A filter that also accepts the ` *name` form closes it. The
  fixture only: the workflow template runs on Linux.
- Lint enforces the layer and feature boundaries, the time-zone restrictions and the selector rules. Nothing
  enforces the naming conventions, `import type`, the no-`Component`-suffix rule or the "do not add a component
  library" rule. Country-specific code staying out of `core` / `shared` / `features` is lint-guarded on the
  public site only, and there only as an import — nothing but its `app.config.ts` may import a country folder;
  in the administration app, and for a branch on a country value in either, it is review alone.
- No exhaustive-switch guard exists anywhere, so adding a member to a literal union does not break any build.
- `.npmrc` still carries `legacy-peer-deps=true` because typescript-eslint's declared peer range has not caught up
  with the TypeScript version in use. Delete the file when it does.
- **The unit tests fail on Node 26, which `package.json`'s `engines` accepts.** Node 25 and later put a
  `localStorage` of their own on the global object (Web Storage, on by default, `undefined` without
  `--localstorage-file`), and vitest's jsdom environment leaves a global that is already there in place, so a
  spec that touches `localStorage` reads `undefined`. On Node 26.7 the public site's suite fails 30 of 921 tests
  in four files (`language.service`, `preferences.service`, `messages.store`, `teaser.component`) with "Cannot
  read properties of undefined (reading 'removeItem')", in the specs' own `beforeEach`; on Node 24, which
  `.nvmrc` pins and CI uses, and on Node 26 with `NODE_OPTIONS=--no-webstorage`, all 921 pass. Turning Node's
  Web Storage off for the test run, or narrowing `engines` to the Node versions CI runs, closes it.
- Two data-loading idioms coexist: roughly two dozen files use `httpResource`, roughly three dozen still build
  their reads from `toObservable` + `switchMap`. New code follows the first — [`angular-conventions.md`](angular-conventions.md).
- The administration app's tab list (`ui-apps/admin/src/app/shared/components/tabs/tabs.ts`) injects the tab set
  component and copies the active tab between the set's `value` and aria's `selectedTab` with two effects that
  write signals, the pattern [`angular-conventions.md`](angular-conventions.md) rules out. The public site's copy
  exposes aria's `selectedTab` as the tab list's own `value` / `valueChange` through its host directive, one piece
  of state with nothing copying it; the administration app can take that shape (the binding moves from
  `<app-tabs>` to `<app-tab-list>` at every caller) with a spec for an outside change, a click and the arrow keys.
- 16 `debounceTime` call sites across 16 files; a contained `debounced()` signal wrapper was proposed and never built.
- All three of `scripts/publications/gen-rule-ids.js`, `gen-field-manifest.js` and `spec-check.js` (which re-runs
  both) are hand-run: no npm script and no CI step calls them, so a stale rule manifest or field manifest goes
  unnoticed. `npm run perf:publications` is in the same position — it works, nothing schedules it.
- The help anchors point at the published manuals; a new section's anchor has to exist on the manual side too.
  See [`docs-niord-org-map.md`](docs-niord-org-map.md).
- **Frontend CI's `Example country` job pulls three images from Docker Hub anonymously**, under Docker Hub's
  anonymous rate limit, which counts per address on runners that share addresses: `alpine:3.22` for `image:info`,
  `registry:2.8.3` for the publishing step (`REGISTRY_IMAGE` in `country-kit/example/acceptance.mjs`), and the
  Dockerfile frontend `docker/dockerfile:1` through the `# syntax` directive on the first line of
  `ui-apps/info/Dockerfile`. Nothing has failed on Docker Hub yet: its pulls worked on every CI run so far. A change
  of October 2026 moved the first two to the ECR Public mirror of Docker's official images
  (`public.ecr.aws/docker/library/…`, Alpine through the Dockerfile's `ALPINE_IMAGE` build argument); on
  2026-10-26 ECR Public answered GitHub's runner `429 Too Many Requests` (`toomanyrequests: Data limit exceeded`),
  since its anonymous limit is per source address too, so the job pulls from Docker
  Hub again. `ALPINE_IMAGE` stays, for a country with a mirror of its own. What would close it, untested: an
  authenticated pull, a `docker login` step with a read-only Docker Hub token kept as a repository secret; or a
  mirror such as `mirror.gcr.io` (Google's cache of Docker Hub), named in the job's `ALPINE_IMAGE` and in
  `REGISTRY_IMAGE`. ECR Public has no copy of `docker/dockerfile` (`public.ecr.aws/docker/dockerfile`: not found,
  2026-10-26); dropping the directive removes that pull but changes every country's build, so it is the owner's
  call. The backend's image (`be/k8s/docker/Dockerfile`) already has no `# syntax` line for this reason, and the
  only instruction here that once needed the directive, `COPY --chmod`, is in the engine's built-in frontend.

---

## 3. Deliberately not built

Each of these was closed by a decision. They are listed so nobody re-implements them by accident or files them as
defects.

| Capability                                                                                                                                                    | Why it is closed                                                                                                                                                                                       | What exists instead                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **AtoN (aids to navigation)** — filter picker, map layer, per-feature toggle in the geometry editor, template coupling, symbol insertion into rich text       | Removed as a whole capability                                                                                                                                                                          | The `aton` filter slice still round-trips opaque uids through the URL, so an existing link keeps filtering; the domain `atons` flag and the `atonFilter` fields on categories and dictionary entries remain as data                                                                                                                                                                                                                                                                   |
| **The Danish import page** — navigational-warning import, firing-area import, AtoN spreadsheet upload                                                         | Omitted                                                                                                                                                                                                | Nothing. `/sysadmin/niord-integration` is a different feature: server-to-server pull integrations with a per-row execute action                                                                                                                                                                                                                                                                                                                                                       |
| **The previous publications admin** — paged publication search, template inheritance, per-language file upload, the old status flow                           | Superseded by the series and issues model                                                                                                                                                              | [`publications/domain-rules.md`](publications/domain-rules.md)                                                                                                                                                                                                                                                                                                                                                                                                                        |
| **Geometry set operations** (union, intersect, subtract)                                                                                                      | A geometry library's bundle weight for a rarely used action                                                                                                                                            | `merge`, `convert` and `split` in the geometry editor                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| **Reactive in-place domain switch** — a domain-changed event, per-feature resets, selection pruning, dialog closing, map-extent reset, template-catalog reset | The full browser reload is the decision: it guarantees every domain-scoped piece of state starts clean                                                                                                 | Switching desk sets a one-reload marker and reloads to the same path; screens that need to know a switch happened read that marker                                                                                                                                                                                                                                                                                                                                                    |
| **Dark mode**                                                                                                                                                 | Deferred                                                                                                                                                                                               | The `dark:` variant is pre-wired to `.app-dark` in the shared `styles/theme.css`, for both applications, and about two hundred `dark:` utilities are authored, but there is no dark token file and nothing sets the class — they are inert, not half-finished                                                                                                                                                                                                                         |
| **S-125 portrayal, a distinct NM palette, dusk/night palettes, millimetre-accurate symbol sizing, producer-authored text labels, full S-100 fidelity**        | Conformance was scoped to S-124 day portrayal. S-125 for notices is ruled but waits for its IHO catalogue to leave draft                                                                               | `NM` draws with the NW colour on purpose. The text drawn on the canvas is the kit's own, not S-124's: the co-location count (administration app), the numbered coordinate pins and the names a message gives its coordinates (`coordinateIndexStyle()` / `coordinateNameStyle()`, both applications) and, on the public site, a whole geometry's name; message titles show in the HTML hover tooltip. S-124 text-label portrayal stays closed. [`map-portrayal.md`](map-portrayal.md) |
| **Chained compilations, and compiling a series whose issues overlap**                                                                                         | Closed with the compilation regime itself: a compilation of a compilation hides the middle derivation, and an in-force source would make the earliest-issue ownership rule visible on nearly every row | One level of compilation over a tiling, query-backed source. [`publications/domain-rules.md`](publications/domain-rules.md); the design reasoning is in [`publications/COMPILATION-SERIES-PLAN.md`](publications/COMPILATION-SERIES-PLAN.md)                                                                                                                                                                                                                                          |
| **A global HTTP error surface** for 403 / 404 / 500                                                                                                           | Deliberately none — a generic toast cannot say anything useful about a failed read                                                                                                                     | Reads render inline error state, mutations raise a toast at the call site, `provideBrowserGlobalErrorListeners()` is the only global hook, and no custom `ErrorHandler` is registered. [`coding-standards.md`](coding-standards.md)                                                                                                                                                                                                                                                   |
| **Multi-tab session sync and idle-timeout re-login**                                                                                                          | Closed                                                                                                                                                                                                 | A failed token refresh drops that tab to guest with a banner; the app never reloads or signs the user out on its own                                                                                                                                                                                                                                                                                                                                                                  |
| **Reload from system, Reload files, and any reset to default**                                                                                                | Removed: one click overwrote every operator edit of a text, template or script that also exists in the server's files                                                                                  | A sysadmin's import, or the editor. [`../../docs/code-data-config-plan.md`](../../docs/code-data-config-plan.md)                                                                                                                                                                                                                                                                                                                                                                      |
| **Page and event analytics**                                                                                                                                  | Not carried over                                                                                                                                                                                       | No analytics code of any kind ships                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| **Mail, print or export "by issue", and the reverse handoff from a message selection into an issue's overrides**                                              | Parked as one lane                                                                                                                                                                                     | The messages filter can narrow to a publication, so an issue's messages can be listed and worked with; what has no route is carrying that set into the mail dialog, or pushing a list selection into an issue                                                                                                                                                                                                                                                                         |
| **Server-side paging or filtering of a series' issue archive**                                                                                                | The gap envelope is computed over the complete set — a page would report gaps against a screenful of rows                                                                                              | One call returns the whole archive; `maxSize` is a ceiling against an unbounded response, not a page size. Paging and filtering happen in the browser                                                                                                                                                                                                                                                                                                                                 |
| **The advanced-expression criteria hatch and the `NOT_IN` criterion operator**                                                                                | Closed: the structured criteria vocabulary is the whole language                                                                                                                                       | [`publications/domain-rules.md`](publications/domain-rules.md). Reopen only against a series that genuinely cannot be expressed                                                                                                                                                                                                                                                                                                                                                       |
| **Automatic release of an issue**                                                                                                                             | Sequenced after go-live, with its abort notification                                                                                                                                                   | The release mode is fixed to the manual gate and the series editor shows it read-only                                                                                                                                                                                                                                                                                                                                                                                                 |
| **An auto-generated source line riding along with the auto-title round-trip**                                                                                 | The field it would carry has no counterpart in the backend model, so nothing produces one                                                                                                              | `applyAdjustment` in `ui-apps/admin/src/app/features/editor/util/message-adjust.util.ts` copies the server's title per language when auto-title is on, plus `shortId`, `editorFields` and — on request — promulgations, and nothing else; a unit spec pins the omission                                                                                                                                                                                                               |
| **Keyboard operation of the message map** — map focus, arrow-key panning, reaching a drawn message, dismissing its tooltip, zoom buttons named in words       | A keyboard-operable OpenLayers map is a project of its own, and the messages it draws are reachable without it                                                                                         | The list and table views show the same messages, each opened from the keyboard, with their positions as text. The map route does not point a keyboard user there                                                                                                                                                                                                                                                                                                                      |
| **A version check on the admin and sysadmin editors** — a save from a tab opened earlier refused with 409 instead of putting back what another tab changed    | Closed by the owner on 2026-11-15: last write wins everywhere, as the old system had it for most of these records                                                                                      | The publication series, one-off and issue writes keep their version check (`StaleVersionGuard`). The message editor's own case is the Editor bullet _Two editors saving the same message overwrite each other_                                                                                                                                                                                                                                                                        |
