# The sea-chart tile cache

**Status: decided by the owner on 2026-11-10 and 2026-11-12 (section 8). Step 1, the cache in the
backend, is built and on `main` (section 11). Step 2, the dashboard, is prepared
as a draft merge request in devops-shared and waits for the owner's go. Steps 3 to 5 are
open: the countries' settings, the provider's settings and the checks on staging.**
**Scope:** the backend's WMS proxy and a cache on the application's volume, for the administration
app. The public site gets no cache. Production is not part of this plan.
**Repos:** niord (the cache, one pull request), devops-shared (the dashboard), niord-dk and
niord-gl (the administration app's `wmsUrl`, two settings, a bump), and each staging's settings (the
provider's address and token).

This plan replaces D10's "on the proxies' disks" and phase R5 of
[`repo-files-in-database-plan.md`](repo-files-in-database-plan.md), which put the cache in nginx.

---

## 1. Where things stood (2026-11-10)

- **Nothing cached a sea-chart tile on a server.** The administration app asks the backend's
  `/wms/` servlet for each tile
  ([`WmsProxyServlet`](../be/niord/src/main/java/org/niord/web/wms/WmsProxyServlet.java)). The
  servlet fetched it from the provider on every request, decoded it, wrote it again as a PNG and
  told the browser to keep it for a day.
- **The servlet already refuses anonymous callers.** With `wmsProtected` on (the default) a request
  without a user gets 403. The administration app loads the tiles through its HTTP client, so each
  request carries the Keycloak token
  ([`ol-wms-layer.ts`](../fe/shared-map/layers/ol-wms-layer.ts)).
- **The tiles are regular.** OpenLayers 10.9's `TileWMS`, as the map kit configures it (no
  `serverType`, no tile grid of its own), always asks for 256 x 256 pixels on the standard
  EPSG:3857 grid, whatever the screen's pixel ratio (read in its source). Every tile therefore has
  a zoom, a column and a row.
- **The backend has a disk tile cache for another purpose.** `TileFetcher` and `TileDiskCache`
  keep the OSM tiles of the server-made map images under `tile-cache/` on the volume, with atomic
  writes and a nightly sweep to 200 MB. They are the pattern that was followed.
- **The application's volume is a cache already.** `/niord-home` is a PVC (5Gi on both stagings)
  that survives a deploy and that nothing backs up; it holds the thumbnails, the map images, the
  OSM and AtoN tiles, the Lucene index and the batch files. The application runs as one replica.
- **The chart's nginx caches nothing and has no volume.** Its `location /` passes `/wms` to the
  application ([`configmap.yaml`](../be/k8s/helm/niord/templates/nginx/configmap.yaml)); the pod
  mounts its ConfigMap only and is limited to 128Mi.
- **No deployed administration app is configured to draw the sea chart.** DK's
  `environment.production.ts` sets no `wmsUrl`, and its test and development files name the
  old test environment's proxy, `/wms/` on that environment's host. GL has no environment file. Both
  countries' settings files switch the layer on and name `http://kortforsyningen.kms.dk/` as the
  provider, with an empty token. (Read from the files; not checked on the stagings.)
- **The public site draws no sea chart.** `wmsLayer` is `false` in both countries; the site's own
  anonymous `/wms/` location stays dormant.
- **The licence allows a cache** (the owner, 2026-11-10). That was the open check of R5.
- **Nobody knows how much space the tiles need** (the owner, 2026-11-12). So the cache is measured
  from its first day (section 5), and its cap is a setting.

## 2. The decision: in the backend, on the application's volume

The servlet keeps its login check and, behind it, reads and writes tiles under
`/niord-home/wms-tiles`. Nothing goes into the database and nothing is backed up, which is what
D10 was protecting. Four alternatives were weighed and dropped.

**nginx's `proxy_cache` on the chart's proxy, behind `auth_request` (R5 as written).** The owner's
requirement of 2026-11-10, that only logged-in users get the tiles, removes the public-site half of
R5. The other half does not hold up:

- The proxy pod has no volume. The cache would sit on the container's own disk and be lost every
  time the pod is replaced, which a ConfigMap change does. The application's volume survives the
  deploys that happen far more often.
- The login check would exist twice, once in the servlet and once in nginx configuration, and the
  chart has no test that runs nginx. A cached `auth_request` answer under nginx's default cache key
  is keyed on the tile's address, not on the caller: an anonymous caller would get any tile a
  logged-in user had fetched in the last minute, and no check in this repository would fail.
- It needs backend code after all: an endpoint for `auth_request` that honours `wmsProtected`, and
  an error status where the servlet answers a provider failure with a blank image (a redirect to
  it when this was decided, the picture itself since 2026-11-21). That answer carried
  `max-age=86400` then (five minutes since 2026-11-22, decision 6), and nginx takes a response's
  lifetime from its headers whatever its status, so it would have kept the blank for a day.
- A hit from nginx is faster (an estimated 0.1 ms against 1 to 3 ms through the application). With
  tens of editors and about thirty tiles a view, nobody would see the difference.
- It is a chart release rolled through the devops repositories; the cache in the backend is an
  ordinary base bump.

**The OSM tile cache's own classes.** `TileFetcher` decodes tiles to compose images, keeps a
`.meta` file of validators beside each tile and may never serve a tile to a user (the OSM tile
policy). A WMS answer has no validators and is served as bytes. Reuse would mean opening those
classes and bending them; a small class of its own, written the same way, is less change.

**A component of its own (MapProxy or the like).** A seventh component of the chart with its own
image, and it would still need the login check in front.

**A tile address in the frontend (`/wms/tile/{z}/{x}/{y}.png`).** The cleanest key, but it changes
the map kit both applications share, and the servlet can derive the same key from the request it
already gets.

## 3. The shape

Two classes beside the servlet, in `be/niord/src/main/java/org/niord/web/wms/`: `WmsTileKey` and
`WmsTileCache`. Nothing under `web/map` changes, and nothing in the frontend.

### 3.1 Which requests are cached

A request is cached only when all of this holds; every other request takes the path it took before,
unchanged.

- It has a user. An anonymous request never reads or writes the cache, whatever `wmsProtected`
  says.
- The cache is switched on (`wmsCacheDays` and `wmsCacheMaxMb` above 0) and a provider is set.
- It is `REQUEST=GetMap` with `WIDTH=256`, `HEIGHT=256`, `FORMAT=image/png` and `CRS` (or `SRS`)
  `EPSG:3857`, each exactly so, and the constants of the protocol are the ones a web map sends:
  `VERSION` 1.3.0 or 1.1.1, `SERVICE` `WMS` or absent, `TRANSPARENT` true or false or absent. The
  layers and the styles are a deployment's own and may be anything.
- Its `BBOX` is a tile of the grid. With `H = 20037508.342789244` and `W = 2H`:
  `z = round(log2(W / width))`, from 0 to 20; `span = W / 2^z`; the box's width and height each
  within `1e-6 * span` of `span`; `x = round((minX + H) / span)` and `y = round((H - maxY) / span)`,
  each within `1e-6` of the unrounded value and inside `0 .. 2^z - 1`. The tolerance is 0.0003 of a
  pixel: far above the noise of floating-point arithmetic, far below anything visible.

The key (`WmsTileKey`) is the provider's host, a variant and `z/x/y`. The variant is the first 32
hex digits of a SHA-256 over the provider's address and every parameter sent to it except the box,
each text written with its length in front, so another provider, other layers or styles, or
another WMS version starts a new folder, and a logged-in caller cannot make up parameters whose
tile would take the name of another map's. The key is built from the same normalised parameters as
the request to the provider; otherwise `bbox=A&BBOX=B` could store one tile under another's name.
The token is never part of a key or a path.

### 3.2 The files

`<niord.home>/wms-tiles/<host>/<variant>/<z>/<x>/<y>.png`, the home taken from `RepoCache.home()`
as the AtoN tiles do, and never under `tile-cache/`, which the OSM sweep owns. A tile is written
to a temporary file and moved into place.

What is stored is the PNG the servlet answers with: the provider's image decoded, checked to be
256 x 256, and encoded again. A hit is then the same bytes as a miss, a hit decodes nothing, and
an error document from the provider can never be stored.

### 3.3 Freshness and failure

- A file's modification time is when it was fetched. A tile is served from the cache while it is
  younger than `wmsCacheDays`; DK and GL get 7 days.
- A WMS answer carries no validators, so an older tile is fetched again, on the request's thread.
- One fetch per tile at a time: a second request for a tile being fetched waits for the first.
  The map cancels and repeats its requests at every pan, so this happens constantly.
- When the provider fails, the older copy is served if there is one, up to 30 days old; otherwise
  the blank image, kept for five minutes. With `wmsCacheDays` at 30 or more there is
  no older copy to serve: a tile is fresh until it is older than that. (The blank image was a
  redirect to `/img/blank.png` when this was built; since 2026-11-21 it is answered directly,
  status 200 with the picture's bytes, on every path that answers one.)
- After three failed fetches in a row the provider is not asked for 2 minutes; then one request is
  let through, and its failure pauses the provider again. Without the pause each tile of a view
  holds a request thread for up to 40 seconds while the provider is down.
- An answer that is no tile gives that request the blank image and is not a failure of the
  provider: it does not count towards the pause, since the provider is there and other tiles may be
  fine. That is an error document, an image of another size, and a status that refuses the request
  itself (a 4xx other than 401, 403, 408 and 429, which speak of the token or of an overloaded
  provider). So a logged-in caller who asks for a style the provider does not have cannot pause it
  for everybody. A caller who can make the provider answer 5xx three times in a row still can, for
  two minutes at a time; the tiles in the cache are served meanwhile.

### 3.4 The bound

The volume is shared with the Lucene index and the batch files, so the cache must not fill it.

- `wmsCacheMaxMb` is the cap. The cache keeps a running total in which each file counts as at
  least 16 KB, so the cap bounds the number of files too: an open-sea tile is a few hundred bytes,
  and ext4 makes one inode per 16 KB of volume by default, so the cache's share of the volume's
  inodes never exceeds its share of the bytes (1000 MB is at most 64,000 tiles). A write that
  crosses the cap starts one sweep, off the request's thread, down to 90 %, oldest first. The first
  write after a start starts one too, which counts the folder; until the count is done the cap
  waits, and no request waits for the count.
- A nightly job counts the folder again and deletes the tiles older than 30 days (or than
  `wmsCacheDays` when that is longer: no tile is deleted while it is fresh), the temporary files
  an interrupted write left, and the folders that have stood empty for an hour, so the folders of
  tiles long gone do not use up the volume's inodes. It runs whether or not the cache is on, so a
  cache that was switched off empties itself within a month.
- Nothing is written, and no folder made, while the volume has less than 256 MB free; the tile is
  served uncached.

### 3.5 The servlet

`WmsProxyServlet` gets the cache injected, and its query builder is split so the key and the
provider request share one parameter map. `WmsServletConfig` is unchanged. The class comment lost
its claim that colours are masked; the code masks nothing.

A cached answer carries `Cache-Control: private, max-age=86400` (300 seconds for an older copy),
`Last-Modified` with the fetch time (and 304 on `If-Modified-Since`), `Content-Length` and
`X-Niord-Cache: HIT`, `MISS` or `STALE`.

### 3.6 Settings

| Key | Type | Default | What |
|---|---|---|---|
| `wmsCacheDays` | Integer | `0` | Days a tile is served before the provider is asked again; `0` switches the cache off |
| `wmsCacheMaxMb` | Integer | `200` | The cap of the cache folder |

Both are operator settings of `WmsTileCache`, editable and not sent to the web applications. A save
takes effect at once, as for every setting since the settings took effect on save (2026-11-23): the
next tile and the next sweep use the new value, and nothing in the cache is emptied (its folders are
named by provider and layers). The base product's default is off: a new
key must be inert, and whether a provider's licence allows a cache is each deployment's to know. A
country switches it on in its own settings file.

## 4. Logged-in only

- The check that existed stays where it was, ahead of everything else in the servlet.
- The cache itself needs a user: `wmsProtected` is a setting a sysadmin can switch off
  ([`potential-errors.md`](../be/docs/potential-errors.md)), and that opens the proxy, not the
  cache.
- The folder is served by nothing but the servlet.
- `Cache-Control: private` keeps a shared cache in front of the application from storing a tile.
- Tests pin it at two levels (section 6): the servlet's unit test for both settings of
  `wmsProtected`, and an integration test through the real security chain.

## 5. The monitoring unit

Decided on 2026-11-12: an hourly log line and a Grafana dashboard. The test cluster already runs
Prometheus, Grafana and Loki (devops-shared, `platform/test/`); they record each volume's fill and
collect the application's logs, and nothing measured one cache folder. Metrics from the application
on Prometheus were the alternative: the standard way, but the system's first metrics endpoint, a
project of its own.

- **The line.** While the cache is on, `WmsTileCache` logs one INFO line an hour, at seven minutes
  past, in a fixed form that a unit test pins:

  ```
  WMS tile cache: bytes=17100 charged=933888 files=57 cap=1048576 hits=0 misses=65 stale=0 failed=0 bypassed=0 evicted=8
  ```

  `bytes` is the tiles' size on disk, `charged` what the cap counts (16 KB a tile at least),
  `files` the number of tiles and `cap` the cap in bytes. The rest count the answers since the line
  before: `hits` from the cache, `misses` a tile fetched from the provider just now, `stale` an
  older copy, `failed` no tile at all, `bypassed` proxied without the cache, and `evicted` the
  tiles the cap removed. A request that waited for another's fetch of the same tile is a miss too,
  so `misses` is not the number of requests to the provider; `hits`, `misses`, `stale` and
  `failed` together are every answer the cache gave. The numbers are running counters, set right
  by the nightly recount.
- **The dashboard.** "Niord: sea-chart tile cache", a ConfigMap for Grafana's sidecar in
  devops-shared's `platform/test/monitoring/values.yaml`: per `niord-*` namespace the cache's
  bytes and files, what counts towards the cap, the answers, the hit ratio and the evictions, read
  from Loki, beside the application volume's used bytes and inodes, which Prometheus has from the
  kubelet. Loki keeps the lines for 13 months.
- **Reading it.** A tile nobody asks for again is deleted after 30 days, so the curve flattens
  after about a month. That level is the space the tiles need, as long as `evicted` stays 0: a cap
  that binds shows only the cap. Staging shows the mechanism and an order of magnitude; the real
  figure comes from production's editors, with the monitoring stack's copy for production, which
  is not this plan's.

## 6. Tests

- `WmsTileKeyTest` (13): boxes as OpenLayers writes them, from zoom 0 to 20; a box off by one
  pixel; 512 pixels; EPSG:4326; `NaN`; a column outside the grid; a zoom too deep; `BBOX` given
  twice in different case; the protocol's constants; the variant per provider and parameter, and
  values made up to read as two parameters.
- `WmsTileCacheTest` (24), on a temporary folder with a clock the test sets: fresh and expired;
  the older copy and its 30 days; the pause after three failures, the one request that tries the
  provider after it, and its end; an answer that is no tile; one fetch for two threads, and two
  misses; the sweep's order and the 16 KB a file counts for at least; the free-space guard; the
  nightly sweep and the empty folders; a tile kept while it is fresh when `wmsCacheDays` is over
  30; the first write after a start, which leaves the count and the cap to a sweep off its thread;
  no temporary file left; the line's form.
- `WmsProxyServletTest` (12 added): a user's tile fetched once and then answered from the cache;
  anonymous and protected answers 403 and the cache is not read; anonymous with `wmsProtected`
  off and the tile cached asks the provider and neither reads nor writes the cache; with the cache
  off, and for a request that is no tile, the servlet answers as before; 304; a failing provider
  and its pause; a request the provider refuses, which pauses nothing; no token in a cached path
  or in the log.
- `WmsProxyHttpITTest` (2 added), through the real security chain with a stub provider: `MISS`
  then `HIT` with one request at the provider; the cached tile goes to the lowest tier of user;
  the same address without a token answers 403 and with a bad token 401; with the cache left at
  its default every request goes to the provider.

To run while iterating:

```bash
cd be && ./mvnw -pl niord test -Dtest='WmsTileKeyTest,WmsTileCacheTest,WmsProxyServletTest,SettingsCatalogCurrentTest' -Dsurefire.failIfNoSpecifiedTests=false
be/test-it --its-only -pl niord -Dit.test='WmsProxyHttpITTest,WmsProxyServletITTest'
```

## 7. The steps

| | Step | Where | State |
|---|---|---|---|
| 1 | The cache, its tests, the two settings with the regenerated catalog, and the records of section 9, as one pull request into `main`. Merged by the owner: a merge under `be/` publishes a version | niord | built and merged (section 11) |
| 2 | The dashboard of section 5, as a merge request | devops-shared | prepared, a draft (section 11); merged on the owner's go, since it deploys to the test cluster |
| 3 | `map.wmsUrl: '/wms/'`: a line in DK's `environment.production.ts`, an environment file of its own for GL. `wmsCacheDays: 7` and `wmsCacheMaxMb: 1000` in each `defaults/country/settings.json`. All in the release that first carries the base version of step 1 | niord-dk, niord-gl | not started; on the owner's go |
| 4 | The provider's current address, its layers and the token (`wmsProvider`, `wmsLayers`, `wmsPassword`) on each staging's Settings page, before step 3 deploys | the owner, who has the token | not started |
| 5 | The checks of section 10 | both stagings | not started |

Step 3 must be in the country release that first carries the new base version: start-up adds a
missing key once, with the value the settings file gives at that start, and never changes the row
afterwards (`SettingsService.addMissing`). A country that bumps first gets `0` and `200`, and the
values are then set on the Settings page instead.

Step 4 comes before step 3 deploys: both countries' settings switch the layer on, so the layer
shows as soon as the administration app names its address, and without a working provider every
tile of it is blank.

## 8. Decisions

| | Question | Decision |
|---|---|---|
| 1 | Where does the cache live? | **2026-11-10, the owner: in the backend, on the application's volume.** It replaces D10's "on the proxies' disks"; "never in the database" stands |
| 2 | How long is a tile served before the provider is asked again? | **2026-11-10, the owner: 7 days** for DK and GL. 1 day (what R5 wrote) and 30 days were the alternatives |
| 3 | How is the space measured? | **2026-11-12, the owner: an hourly log line and a Grafana dashboard** (section 5). Not metrics from the application on Prometheus; not the volume's fill alone, which cannot tell the cache from the rest |
| 4 | What cap do the stagings start with? | **2026-11-12, the owner: 1000 MB** of the 5Gi volume. If the dashboard shows evictions the cap is raised, and the measuring starts again. 2000 MB and a larger volume were the alternatives |
| 5 | Is the cache on by default? | **2026-11-12, the owner: no.** `wmsCacheDays` defaults to 0 in the base product; DK and GL switch it on in their settings files |
| 6 | Is the day-long cache header on the uncached path's blank redirect fixed here? | **2026-11-12, the owner: no.** That path stays byte for byte as it was; the defect was recorded as WMS1 in `be/docs/potential-errors.md`. Since 2026-11-21 the blank image is answered directly instead of by a redirect, on this path too. **2026-11-22, the owner: fixed.** On the uncached path the blank image now carries five minutes in each of `Cache-Control: max-age`, `Last-Modified` and `Expires`, as on the cached path; a tile keeps its day, and WMS1 left the record |
| 7 | What does an editor get when the provider is down? | **2026-11-12, the owner: the older copy, up to 30 days old, and the provider paused for 2 minutes after three failures in a row.** Never serving an older copy, and serving it without the pause, were the alternatives |
| 8 | Where is the chart's address set? | **2026-11-12, the owner: per country.** A line in DK's production environment file, an environment file of its own for GL. A default in the base product's deployed build was the alternative |

Fixed by the requirement and not asked: the public site gets no cache, and its dormant `/wms/`
location is left as it is.

## 9. Records updated with step 1

- [`repo-files-in-database-plan.md`](repo-files-in-database-plan.md): the status and scope lines,
  the summary table, D10 (amended, with the reasons), §4.7, R5 (replaced, pointing here), §8 and a
  dated entry in its record.
- The root `AGENTS.md`: this document in the `docs/` row, and R5's state there.
- [`fe/docs/backlog.md`](../fe/docs/backlog.md): the entry on caching the tiles on the proxies'
  disks became what stays open (steps 3 to 5).
- `be/k8s/helm/niord/values.yaml`: the comment on `NIORD_HOME` names `wms-tiles/` and its cap.
- [`be/docs/potential-errors.md`](../be/docs/potential-errors.md): WMS1 (decision 6; fixed and
  removed on 2026-11-22) and WMS2 (section 11).
- `be/docs/country-api.md`: a row in its table of changes, with what a country does to switch the
  cache on.
- `be/niord/src/main/resources/defaults/base/settings-catalog.json`, regenerated, with the two keys
  introduced in 4.0.153; the key count `SettingsCatalogCurrentTest` expects went from 71 to 73.

## 10. What to check on staging

- A repeated tile answers `X-Niord-Cache: MISS`, then `HIT`.
- `curl` without a token on a cached tile's address answers 403.
- A `HIT` survives a redeploy of the application.
- The hourly line appears in the application's log, and the dashboard draws both stagings.
- After a working day: the hit ratio, and a tile's time as a `HIT` against a `MISS` (a `MISS` that
  waited for another request's fetch takes less time than one that fetched).
- `du -sh` and `df -i` on `/niord-home`. The reasoning about inodes assumes ext4, which was not
  checked on the stagings.
- The provider's real answers: no token was at hand while building, so that it answers a
  256 x 256 PNG is assumed, and the tests use a stub. A provider that answers otherwise shows as
  `failed` in the line and as blank tiles.
- The console no longer floods on a pan (the flood came from the cross-origin `wmsUrl` and from
  the blank-tile redirect; to be confirmed once step 3 is in). Since 2026-11-21 the servlet
  answers "no tile" with the blank picture itself, no redirect, so no redirect can name another
  origin either.

## 11. Execution record

### Step 1, as built (2026-11-12)

- `WmsTileKey`, `WmsTileCache` and the changes to `WmsProxyServlet` are as sections 3 to 5 say.
  The provider request of the uncached path is unchanged, the order of its parameters included.
- One thing goes beyond decision 7's wording: an answer that is no tile does not count towards the
  provider's pause (section 3.3). Three error documents for three tiles would otherwise blank
  every other tile for two minutes.
- Run locally: the three unit classes (58 tests), `SettingsCatalogCurrentTest`, and the backend's
  whole unit suite once, before the review's fixes (5341 tests, none failed); the two WMS
  integration classes (8 tests, none skipped) on OrbStack, this Mac's only container engine. The
  integration suite as a whole is CI's, on the pull request.
- **An independent review before the pull request was marked ready** found no way for an anonymous
  caller to reach the cache and no leak of the token, and five defects, all fixed with a test each
  where one can be written:
  1. Two requests could share a tile's name: the variant was 8 hex digits, found by trial in two
     minutes, over a text in which a value with a line break read as two parameters. Now 32 digits
     over length-prefixed texts, and the protocol's constants are held to what a map sends.
  2. A logged-in caller could pause the provider for everybody with requests it refuses. A
     refusal of the request itself no longer counts as a failure of the provider (section 3.3).
  3. Folders were never removed, and were made before the free-space check. The nightly sweep now
     removes the ones long empty, and the check comes first.
  4. The first map after a restart walked the folder once per request. Now once.
  5. A sweep stopped when a file went while the folder was walked. The walk now goes on.
  A test written for the first fix then found a defect in the fix itself: a request without
  `VERSION` threw. Fixed before anything was pushed.
- **CI's first run failed on one integration test**, of 3405: `SettingsFreshDatabaseITTest`
  counts the operator settings a start-up adds to an empty database, and the two new keys make
  them 54, not 52. The count was not updated with the catalog's; the local runs had covered the
  WMS classes only. Corrected, and the class run locally: it also shows that a fresh database gets
  the two keys with their flags.
- **A second review, after the pull request was marked ready** (the code-review skill at its high
  level), found nothing that blocks and seven smaller things. Three were fixed, each with a test:
  1. Every sweep deleted a tile at 30 days, whatever `wmsCacheDays` said, so a setting above 30
     was cut to 30 without a word. A sweep now deletes a tile at 30 days or at `wmsCacheDays`,
     whichever is longer (section 3.4).
  2. `misses` was described as the fetches from the provider, but a request that waits for
     another's fetch of the same tile counts there too, and is answered `MISS`. The count stays,
     an answer like the others, and the line keeps its form; its description now says what it
     counts (section 5).
  3. The first write after a start counted the folder on its request's thread, and could run the
     cap's sweep there, while every other write waited for it. It now hands the count to a sweep
     off its thread, and the cap waits for the count (section 3.4).
  Left as they are, all small: the nightly removal of an empty folder can, in a window of two
  system calls, take the folder of a tile being written, which is then answered but not kept; the
  cache's own folder is made before the free-space check, where the class comment and section 3.4
  say no folder is made; a hit builds the provider's address, which only a miss uses; and a 304
  reads the whole tile first. Set aside as decided: an answer that refuses the request does not
  pause the provider (section 3.3), and the pause and the file handling are written again beside
  `HostCircuitBreaker` and `TileDiskCache` rather than shared with them (section 2).
  Run locally after the fixes: the three unit classes and `SettingsCatalogCurrentTest` (61 tests,
  none failed); `WmsTileCacheTest` once more with the old logic put back, where the two new tests
  failed and the other 22 passed; and the two WMS integration classes on OrbStack (8 tests, none
  skipped).

### Step 2, as prepared (2026-11-12, devops-shared, a draft)

- The platform ApplicationSet applies only `*.sealed.yaml` from a component's directory, so the
  dashboard's ConfigMap is in the monitoring component's `values.yaml`, under the chart's
  `extraManifests`. The chart runs those through Helm's template engine, so Grafana's own
  `{{namespace}}` is escaped there. The Loki datasource got a fixed uid, `loki`, for the dashboard
  to name.
- The application's log stream carries the labels `namespace` and `container` (`niord`); the
  queries filter on the line's prefix and read one number each with a regular expression.
- Checked without the cluster: the chart (kube-prometheus-stack 92.11.0) renders with the new
  values and the rendered ConfigMap holds the dashboard as written; every Loki query ran against a
  local Loki with sample lines, which found one query that Loki refused (a grouping
  `sum_over_time` does not take) before any Grafana saw it. Not checked: the dashboard in the real
  Grafana, which needs the merge.
- A merge restarts Grafana on the test cluster, since its datasource changes; nothing else there
  changes. The cache panels stay empty until an environment has switched the cache on and logged
  its first line; the two volume panels need no cache.

### Found while building

- **The integration suite asks a real WMS host.** The test settings name
  `http://kortforsyningen.kms.dk/`, so `WmsProxyServletITTest` makes two requests to it per run
  and asserts the failure branch, not the no-provider branch it describes. Recorded as WMS2 in
  `be/docs/potential-errors.md`; not changed here.

## 12. Not in this plan

- **The public site's `/wms/` location.** Its backlog entries (_The `/wms/` proxy would not work
  as written_, _Three `proxy_cache_valid` directives do nothing_) stand.
- **Seeding the cache.** Tiles are fetched when a map asks for them.
- **Metrics from the application on Prometheus.** The alternative of decision 3; a project of its
  own if it is wanted.
- **Production.**
