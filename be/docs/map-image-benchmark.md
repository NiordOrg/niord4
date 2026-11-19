# Map-image benchmark

The backend draws the map image of a message (`GET /rest/message-map-image/{uid}.png`: a 200 with the generated
image itself, served from the message's place in the cache tree in one request; a 307 to the file in the
repository only for a custom thumbnail an editor set; or, when no image can be made, the placeholder image
itself, marked `X-Niord-Map-Image: placeholder`). A change to how that image is drawn must not make anything a user waits for
slower. `be/scripts/bench-map-images.mjs` measures the paths the image sits on, in the same way before and after
such a change, and over the real network against a deployed system. This document says what it measures and
why, how to run it, what passes, and holds the reference numbers measured on the test environment.

## What must not get slower

| What the user waits for | How the map image is involved | Cases |
| --- | --- | --- |
| A PDF with map thumbnails (list, selection, one message) | The PDF engine fetches each message's image from the backend itself, one after the other, one request each (the image at once; a custom thumbnail takes two, the 307 and then the file). A PDF of 50 messages makes 50 such requests. The reference numbers below were measured when every image took two requests (see "Two requests then, one now"). | 3, 3b, R3 (R4 is the same PDF without thumbnails) |
| The Details, Grid and Selected views | Every row names the image address in an eagerly loaded `<img>`; a page holds up to 200 rows, all requested at once. | 4, 4b, R2 |
| The tweet on publish | Publishing changes the message, so the image the tweet attaches is always made fresh. | 2a |
| A cached image | Every view that shows an image, after the first time. | 1, R1 |
| The first image after a restart | Whatever the renderer loads once (symbols, fonts, image codecs). | 2c |
| Message GET, list search, save | They never draw an image, but share the database pool, the CPU and the request threads with image generation. | 5, 6, 7, R5 |

## The script

One Node file, built-ins only, one process (process starts are slow on the Windows workstations). Five
subcommands; `--help` lists every flag.

| Subcommand | What it does |
| --- | --- |
| `stand-ins` | Serves a static-map server and an OSM-like tile server on loopback (below). |
| `local` | Times a backend on this machine whose map source points at the stand-ins: cases 1 to 7. Deletes generated images under `--cache` (the cache tree, `<niord.home>/cache`), and case 5 saves a scratch message: a benchmark copy of the data only. A message's images sit in its folder there, the message's `repoPath`; the search answer carries none, so the script takes the folder the backend gives every message from its UID (`messages/<uid[0]>/<uid[1..2]>/<uid>`), not the image's answer, which names no file when the image comes at once. It learns the renderer's file names from those folders after its first fetch of the set. Only `map_<size>.png` and `map_<size>_<version>.png` are deleted. The old flag `--repo` is refused with a message naming `--cache`: a current backend keeps nothing to delete there, so a command line of the earlier version would time cached images as cold ones. |
| `remote` | Times a deployed system with GET requests only (cases R1 to R5; R6 to R8, the message reads, when named). The one other request is the Keycloak token POST. |
| `compare` | Applies the pass criteria to the result files of an old and a new run. |
| `selftest` | Starts the stand-ins on free ports and checks their answers, then checks the map-image client against a stand-in of the endpoint's answers (a generated image at once, the placeholder, a custom thumbnail's 307, an older backend's redirects), `compare`'s note on one request against two, and the local mode's files in a temporary cache tree (each message's folder from the search answer, the file names, the deletion of the map images only, the refusal of `--repo`), and the message reads against a stand-in of their endpoints. |

Credentials come from the environment only: `NIORD_USER` and `NIORD_PASS`, a password grant on the public
client `niord-web` (`--keycloak`, `--realm`, `--client`). They are never taken from a flag or a file and never
written to a result file. A result file (`--out`) holds every raw sample, so runs can be pooled and compared
later, plus the set of messages used, the checks and the findings.

### The stand-ins

- **Static map** — `/staticmap.php?center=<lat>,<lon>&zoom=<z>&size=<w>x<h>`, the query the previous renderer
  sends (it asks for 300x300 and crops a 22-pixel margin). It answers a PNG of exactly that window. A centre
  written with decimal commas (a JVM in a comma-decimal locale) is read too and counted. It answers in one of two
  models (`--static-map-model`):
  - `static` (the default, and the model of the first result below): the answer waits one latency draw, as if the
    static-map server held every tile. It charges the previous renderer one request per image and nothing for the
    tiles its static-map server must itself fetch for a place it has not drawn.
  - `fetching`: the stand-in behaves like a static-map server. It works out the 256 px tiles its window covers (a
    300 px window covers four to nine), fetches those it does not hold from the tile stand-in over HTTP, all at
    once, each paying a draw of the same latency distribution the tile renderer's tiles pay, keeps them in a tile
    cache of its own, composes the map and answers after a LAN hop (`--lan-latency`, 2 ms: the previous static-map
    server stood on the local network; the hop is waited out precisely, not on the timer tick). Maps asked for at
    the same time share the fetch of a tile, at most 64 tile requests run at a time (the tile renderer's own
    limit per host), and fetching all of a map's tiles at once is the fastest a static-map server could do it;
    one that fetches them one after another is slower. The harness empties that
    cache (`POST /__static-map/clear-tiles`) in exactly the states in which it empties the tile renderer's tile
    cache (2a, 2c, `coldTiles`), and its tile requests are counted apart (they carry the User-Agent
    `bench-static-map`).
- **Tiles** — `/osm/{z}/{x}/{y}.png`, 256x256 PNGs with the headers tile.openstreetmap.org sends: a
  `Cache-Control` lifetime, a strong `ETag`, `Expires`, `Last-Modified`, and a 304 to `If-None-Match`.
  Connections are kept alive, requests are served concurrently, and a request offering `Upgrade: h2c` (what an
  HTTP/2-preferring Java client sends over plain http) is answered as plain HTTP/1.1.
- **Seamark tiles** — `/seamark/{z}/{x}/{y}.png` exists only to be counted: server images are drawn on OSM
  alone, so any request for one fails the run.
- **One synthetic map** — 48 tile templates (sea, coast, land with roads, parks, building blocks and label
  specks; about 11 KB each, like real OSM tiles) are drawn once at start; tile (z, x, y) is template
  hash(z, x, y), and a static map is cut from the templates under its window. The previous renderer's image
  and the tile renderer's image of a message therefore show the same pixels, and no request pays for drawing
  (a stand-in that drew per request would spend its one thread on it and slow the concurrent cases by its own
  cost). A 300x300 static map is about 14 KB.
- **Latency** — every request, static map and tile alike, waits for a latency drawn from one lognormal
  distribution with median `--latency` (ms) and spread `--sigma` (0.5). One distribution for both matters: the
  previous renderer makes one request per cold image, the tile renderer up to four, and a fixed latency would
  hide the slowest of four. Run the comparison at 250 ms (about what tile.openstreetmap.org takes per tile
  from here) and at 50 ms. A timer fires on the operating system's tick (about 15.6 ms on Windows), so the
  delivered latency is the drawn one rounded up to it; both variants see the same, and `/__stats` reports it.
- **`GET /__stats`** (either port) — requests, 200s, 304s and bytes per route (the static-map stand-in's own
  tiles as `staticmapTiles`), hits per path, the User-Agents seen (the first request of each is printed),
  comma-decimal centres, the delivered latency, and the static-map model with the tiles it holds and the
  delivered LAN hop.

## Running it locally: old against new

1. **Two fat jars, built in their own worktrees** — the old one at the merge base, the new one at the branch
   head: `./mvnw -B -ntp -pl niord-dk -am package -DskipTests -DskipITs` in each `be/`, then copy
   `niord-dk/target/niord-dk-*.jar` to `old.jar` and `new.jar`. A reactor `package` installs nothing, so the two
   worktrees cannot run each other's `niord-base` through `~/.m2`. Delete `niord-dk/target/niord-dk-*.jar*`
   before each build: when `niord-dk`'s own classes have not changed, the build keeps the jar it made before,
   already repackaged with the `niord-base` of that time, and says nothing.
2. **Two database copies** of the test data, one per variant:
   `docker exec niord-db createdb -U niord -T template_postgis niord_bench_old`, then
   `docker exec niord-db sh -c "pg_dump -U niord -d niord_test | psql -q -U niord -d niord_bench_old"`; the same
   for `niord_bench_new`.
3. **Two homes**, copies of `~/.niord-local/niord_test` (`niord.json` and the cache tree `cache/`), with every `map_*.png` in
   them deleted (a build from before the cache tree kept its images in `repo/`; for such a build that folder is
   what `--cache` and `--legacy-from` name, as the folders below it are the same): the images the test data came with are the previous renderer's, which the new one would serve as
   superseded images and redraw in the background during the first cases. Never one home for both: an image
   the old code made is the new code's previous image, which is case 3b's state, not case 1's.
4. **The stand-ins**: `node be/scripts/bench-map-images.mjs stand-ins --latency 250` (a second series with
   `--latency 50`), and for the faithful model of the previous renderer `--static-map-model fetching`.
   `node be/scripts/bench-map-images.mjs selftest` checks them first. The model concerns the old variant only:
   the new one never asks for a static map, so its runs serve both models, and a series in both models runs old
   (static), old (fetching), new, old (static), old (fetching), new, restarting the stand-ins before each old run.
   A result file records the model the stand-ins ran.
5. **One variant at a time on port 8888** (or any free port: `baseUri` and `--base` follow it), with
   `NIORD_HOME=<home>` exported and the same flags:

   ```
   java -Xms1g -Xmx1400m -XX:+UseG1GC -Duser.timezone=UTC -Duser.language=en -Duser.country=US \
     -Dspring.profiles.active=local -Dserver.port=8888 -Dniord.home=<home> \
     -Dspring.datasource.url=jdbc:postgresql://localhost:5432/niord_bench_<variant> \
     -Dspring.liquibase.change-log=classpath:db/changelog/db.changelog-master.yaml \
     -DauthServerUrl=http://localhost:8080/auth -Dmode=development -DbaseUri=http://127.0.0.1:8888 \
     -DmessageIndexPath=<home>/message-index \
     -Dniord.schedule.auto-publish-cron=- -Dniord.schedule.auto-expire-cron=- \
     <old: -DmapImageServer=http://127.0.0.1:9101/staticmap.php> \
     <new: -DosmUrl=http://127.0.0.1:9102/osm/{z}/{x}/{y}.png -DmapTileContact=bench@localhost> \
     -jar <variant>.jar
   ```

   Why these: `baseUri` defaults to the local Keycloak's port, where the PDF engine's own image requests fail
   and PDFs look faster; `en_US` keeps the previous renderer's static-map URL free of decimal commas; the two
   scheduled sweeps add noise; the message index is kept between starts (below); and the map settings go in as
   system properties because a `${niord.home}/niord.json` overrides database rows, and only a system property
   or an environment variable wins over both. Wait until `/rest/settings/web` answers and the log shows
   `Started NiordDkApplication`, then 20 s for the deferred start-up work.

   **The search indexes first.** The backend keeps two search indexes of the messages, the Lucene folder and
   the PostgreSQL table `message_search_text`, and fills both on its one scheduler thread, 5,000 messages a
   pass, one pass a minute. A run made while they fill measures the indexing as much as the images. None of the
   cases searches for text, so neither index changes a result, only the noise:

   - The table keeps the indexer's place, so the table is filled once, 5,000 messages a pass, and later starts
     find nothing to do. Start each copy once before its first run and leave it until the log shows a pass of
     fewer than 5,000 messages (`Indexed <n> messages (PostgreSQL) in <ms> ms`). A build older than the fix of
     2026-11-12 (IDX2) never finishes: it rewrites the same first 5,000 messages every pass, minutes of a
     processor core each. For such a build, switch the table off on each copy before the first start:
     `alter table message_search_text rename to message_search_text_bench_off`. Every pass then stops at its
     first query (an error in the log once a minute) and leaves the scheduler thread to the rest.
   - The Lucene folder is deleted at every start and refilled in about ten passes, unless
     `messageIndexDeleteOnStartup` is false. The row says so to every build: `update "Setting" set setting_value =
     'false' where setting_key = 'messageIndexDeleteOnStartup'`. A system property
     (`-DmessageIndexDeleteOnStartup=false`) says so only to a build from the fix of 2026-11-13 on (SETTINGS-3): an
     older build read a false system property as unset and kept the default, true. Then start each copy once
     before its first run and leave it until the log shows a pass of fewer than 5,000 messages (`Indexed <n>
     messages in <ms> ms`); later starts keep the folder and find nothing to do. The log line `Lucene index
     deleted` at a start means the flag did not take.
6. **Run the cases**, the first run choosing the set of messages and the later ones reusing it with `--set`:

   ```
   NIORD_USER=<sysadmin> NIORD_PASS=<password> node be/scripts/bench-map-images.mjs local --variant old \
     --domain niord-nm --filter 'domain=niord-nm&status=PUBLISHED&area=529&area=466&area=504' \
     --list-filter 'domain=niord-nm&status=PUBLISHED' --pool-filter 'status=PUBLISHED' \
     --cache <home-old>/cache --stand-ins http://127.0.0.1:9101 --scratch-id <uid> --out old-1.json
   ```

   The new variant adds `--tile-cache <home-new>/tile-cache --legacy-from <home-old>/cache`. The pool of cases 4b
   and 7 needs 200 messages with a geometry; the run lists a finding when `--pool-filter` finds fewer. The
   scratch message of case 5 is saved 30 times, so it must not be one of the bench messages. Order: old, new, old, new,
   stopping one backend before starting the other. Case 2c starts and stops the backend itself and runs on its
   own, per variant: `--cases 2c --set old-1.json --server-cmd "java ... -jar old.jar" --ready-log 'Started NiordDkApplication'`.
7. **Compare**: `node be/scripts/bench-map-images.mjs compare --old old-1.json,old-2.json --new new-1.json,new-2.json`,
   once per model with that model's old runs. It prints the model of the old runs.

`--states cached,stale` (any of `cached`, `cold`, `coldTiles`, `stale`) limits cases 4 and 4b to those states, for
a targeted run while a change is measured.

### The local cases

Every case first runs `--warmup` rounds (1) that are not counted.

| Case | What is timed | How |
| --- | --- | --- |
| 1 | Cached image | 50 images one at a time, 20 rounds; the whole fetch (`total`), one request for a generated image. Only for an image served by a redirect (a custom thumbnail, or any image of a backend that redirected) are the 307 alone (`redirect`) and the file it names (`file`) timed as well; a case whose images all came at once has neither metric. The same holds for 2a, 2b and R1. |
| 2a | Cold image, cold tile cache: the tweet on publish | Every `map_*.png` of the set deleted (and the tile cache: the new variant's, or in the fetching model the static-map stand-in's), 50 images one at a time, 3 rounds. |
| 2b | Cold image, warm tile cache (new; old in the fetching model) | Images deleted, tiles kept. |
| 2c | First image after a JVM start | 5 starts, the tile cache emptied as in 2a; also the time from start to ready. |
| 3 | List PDF of the set with thumbnails | 10 runs with the images cached, 5 with all of them deleted; controls: the same PDF without thumbnails, and one message's PDF. PDF and PNG bytes recorded. |
| 3b | The same PDF right after the deployment (new only) | Every image still under the previous file name (`--legacy-from` copies the old variant's, with their times); held against case 3 cached of the old variant. |
| 4 | A page of 50 images requested at once | Concurrency 6 (a browser's HTTP/1.1 limit per host) and 50 (HTTP/2 through a proxy); cached, cold (tiles in the tile cache), cold with an empty tile cache for the new variant and, in the fetching model, the old one (`coldTiles`: a page of places no image was drawn of for a week; before each batch the images of up to 30 other messages of the pool are drawn, so that the 64 tiles the tile renderer keeps in memory are theirs), and for the new variant stale after an image-version change; the page's makespan and each request. |
| 4b | The same for a full page of 200 | From `--pool-filter`. |
| 5 | Save round trip | `GET /rest/messages/editable-message/{id}` + `PUT /rest/messages/message/{id}`, 30 times. |
| 6 | Message GET and list search | `GET /rest/messages/message/{id}` 200 times, the list search (`maxSize=200`) 30 times. |
| 7 | Case 6 under load | While 200 cold (`--load cold`) or stale (`--load stale`, new) images are requested at concurrency 50. |

Cases 6b, 6c and 6d (and R6, R7 and R8 in `remote` mode) are the [message-read benchmark](message-read-benchmark.md)'s:
the administration app's list search at its default page and in its map view, and the public site's list and
single message. They run only when `--cases` names them, and a run of read cases alone (6 and R5 among them)
fetches no map image.

## Pass criteria

- **No slower**: for every case and timed metric, the new median and the new p95 are at most the old ones
  x 1.05. For operations under about 40 ms the allowance is at least 2 ms: 5 % of a 10 ms request is below the
  timer and endpoint-protection noise of the workstations. A state only the new variant has is held against
  the old variant's nearest one: case 3b against case 3 cached, 2b (cold image, warm tiles) against 2a, and in
  cases 4 and 4b `coldTiles` against `cold` and `stale` against `cached`. In the fetching model the old variant
  has 2b and `coldTiles` too, and they are held against their own kind. `compare` applies this and prints a
  bootstrap 95 % interval of each median ratio.
- **No placeholder and no failed request**: the placeholder is a failure, never a fast sample, whichever way
  the backend answers it: a 200 marked `X-Niord-Map-Image: placeholder`, or, from a backend before that
  answer, a 307 to `map_image_placeholder.png`. Timings count only the requests that got their image.
- **Every thumbnail in the PDF**: each thumbnail PDF holds one image of the map-image size per message with a
  geometry.
- **The expected stand-in hits**: old, exactly one static map per cold image and no tile, and in the fetching
  model one to nine tiles of the static-map stand-in per cold image with its tile cache emptied and none with it
  warm; new, one to four OSM tiles per cold image with a cold tile cache, none with a warm one, and never a
  static map; nobody asks for a seamark tile.
- PNG and PDF bytes are reported, not gated: a larger PDF matters only when it makes case 3 slower.

`local` exits 1 when a check fails; `compare` exits 1 when a metric is slower or a run had a failed check.

### Two requests then, one now

Every result in this document, the local ones and the reference of the test environment, was measured when the
endpoint answered every image with a 307 and the client then fetched the file: two requests per image. A current
backend serves a generated image at once, in one request; only a custom thumbnail still takes two. So the
retained `total` times and image makespans are two hops, a run made today is one, and the comparison of the two
is not like for like: a new run is expected to be faster by about one request per image, and that says nothing
about the renderer. The retained `redirect` and `file` rows have no counterpart in a run against a current
backend. A result file records how its images came (`imageHops`: how many of the run's image fetches, the
discovery and warm-up ones included, came in one request and how many in two), and `compare` prints a `NOTE`
line when the old runs and the new ones differ. A result file written before that field existed counts as
two-hop when it served an image: when a case timed a 307, or when a case's counts show a served image (`ok`,
also in a nested count such as `c6` or `cold.c6`), because that version of the script took only a redirect as
an image. The pass criteria are unchanged: an old-against-new
comparison that is like for like needs both variants of the same backend shape.

## Result: after the fixes, in both static-map models (current)

This is the current result; the first result, below it, is kept as the record of the state the fixes started from.

Measured on 2026-09-29, 01:21 to 02:52 UTC, on the same workstation as the first result, with the other backend
still running on it. Old: `niord-dk` at the first result's merge base. New: `niord-dk` at the branch head, with the changes below.
The same data, set (50) and pool (173). Per latency, two runs of each variant in each static-map model, in the order
old (static), old (fetching), new, old (static), old (fetching), new; the new variant's runs serve both models, as
the tile renderer never asks for a static map. Case 2c: five starts per variant and model. Two of the twelve runs
ended without a result file and were repeated once (the new variant's first run at 50 ms, exit 1, most likely the
harness emptying the tile cache while the backend still wrote into it, which the harness now retries; the old
variant's second fetching-model run at 50 ms, exit 127, as in the first result). Every check passed in every
retained run: no placeholder and no failed request, all 50 thumbnails in every PDF, the expected stand-in hits. The
images are 16.9 KB against 16.5 KB (median), the thumbnail PDF 886 KB against 857 KB. In the fetching model the
static-map stand-in fetched 169 tiles for a round of 2a's 50 images where the tile renderer fetched 144, and 474
against 405 for a `coldTiles` page of 173.

The fetching model is the faithful one: it charges the previous renderer for the tiles its static-map server fetches,
at the same latency the tile renderer pays. The static model is kept because the first result used it; its cold-tile
rows hold a cold tile cache against a static-map server that holds every tile.

### What changed since the first result

Each change was measured on its own before this series.

- **The start.** Comparing the classes the JVM loads before `Started` with the previous build's found about 160
  more on the main thread: the Jackson tree classes and the hashing of the portrayal definition (about 55 ms) and
  the HTTP client of the tiles with its TLS context (about 55 ms). Both now run on threads of their own, started
  with their beans. Six alternating starts of each, time to ready (median): before, old 17.9 s and the branch 17.6 s;
  after, old 17.8 s and new 17.5 s. Start-to-start spread on this workstation is 16.6 to 19.7 s, which covers the
  first result's +1 s (1.06); here 2c's `startToReady` is 0.97 to 1.03.
- **The first image after a start.** The symbols were rasterised on a thread started when the application was
  ready, and the first image request overtook it in two of the first result's ten starts. Now the generator starts,
  when its bean is created, a thread that rasterises the symbols and draws a trial image in memory (planner,
  composition of blank tiles decoded as tiles are, painter, PNG encoder); the tile fetcher runs one tile exchange
  against a socket of its own on the loopback interface. No tile host is asked for anything, and the trial image was
  drawn 0.8 to 1.0 s before `Started` in every start of this series. The drawing of the first image at 50 ms tiles
  took 199 to 394 ms in the first result's five starts (tiles 150 to 242 ms, marks 18 to 133) and 92 to 226 ms in
  this series' (tiles 84 to 121 ms, marks 0 to 4).
- **A page after a portrayal change** (4b `stale`). A request looks at `map_<size>.png` first and lists the folder
  only when that is missing or older than the message; the tile settings are read by the thread that draws, so the
  request that serves the older image reads none; and the background redrawing waits while image requests are
  served, at most 2 s after it was queued. Targeted runs of 3b and 4b, two each, the page of 173 at concurrency 6
  against the same run's page of current images: 1.15 and 1.18 before, 1.11 and 1.12 with the request path trimmed,
  1.05 and 0.99 with the wait. 3b, the PDF right after the deployment, took 1.87 and 1.91 s with the wait against
  1.90 and 1.98 s without it: the fixed delay tried before made it slower, this does not.
- **Tiles from the disk cache.** A first series with the fetching model (stopped after one round) found the tile
  renderer drawing the PDF of 50 deleted images in 3.9 s where the previous renderer took 2.3 s, and a page of such
  images at concurrency 6 1.4 to 1.6 times as slowly, although an image took 8 ms to draw. The per-image log
  showed one image in ten, and four in ten inside the PDF, waiting 101 or 202 ms for tiles the disk cache held. A
  probe outside the application found the cause: of 2,020 reads of the cached tile files on virtual threads, 35
  waited 100 or 200 ms, while the same reads on a platform thread took at most 1.4 ms. The fetcher now reads the
  disk cache on the thread that draws the image; only fetches from the host run on virtual threads. The PDF then
  took 2.1 s (3 `cold` below).
- **Not done: overlapping the request's own work with the tile fetch.** A cold image whose tiles are cached takes
  10.2 ms more than a cached image at the 307 (2b's 33.5 ms against case 1's 23.3 ms, at 250 ms), of which drawing
  it takes 8 to 9 ms (the per-image log). What the request does before its first tile request beyond a cached
  image's work (a second file name, the folder listing, the tile settings) is the remaining 1 to 2 ms; overlapping
  it with a fetch of 50 to 250 ms could save no more than that.
- **Tried and dropped**, on the page of 173 images without cached tiles at concurrency 50 and 50 ms tiles, three
  runs each: tile requests without a timeout of their own (a flight recording showed the HTTP client's timer lock,
  which every request with a timeout enters twice, contended for up to 0.4 s; without the timeouts it was not
  contended, but the page took 2.49 s against 2.38 s), and one thread writing the tile cache instead of a thread per
  write (a write takes about 8 ms on this workstation even alone, so the queue drained for seconds; the page was no
  faster).

### The fetching model

**Faster or the same** in every image state at 250 ms, and at 50 ms in all but the rows below: a cold image, cached
tiles or not (2a median 0.90 at both latencies, 2b 0.95 and 0.93), the first image after a start (2c 0.81 and 0.77),
the PDF with every image deleted (3 `cold` 0.95 and 0.96), pages of cold images and of images without cached tiles (4
and 4b `cold` and `coldTiles` 0.38 to 0.99 by median at 250 ms), the page after a portrayal change (0.80 to 1.04 at
250 ms), and case 7's load of 173 images (0.85 and 0.87 of the time).

**Still slower**, at 250 ms (7 of 58 rows):

- 2a `total` and `redirect`, the p95 only: 780 ms against 742 (1.05; the median is 0.90). The tile renderer waits
  for the slowest of up to four tiles, the static-map stand-in for the slowest of the tiles it lacks.
- 4 `cached.c6.makespan`: 271 ms against 248 (1.09; p95 1.21), six page times per variant; each request of the same
  pages passes (0.97), and a cached image takes the same path in both.
- 5 `editableGet` (1.09), `put` (1.06, p95 1.09), `roundTrip` (1.05, p95 1.16): the order of the series, not the
  code. The new runs came third in each round, after the heaviest image cases; three alternating runs of each variant
  with cases 3, 5 and 6 alone gave 1.00, 1.02 and 1.00, every row passing.
- 7 `messageGet`, the median only: 146 ms against 111 (1.32, bootstrap interval 0.37 to 4.62; p95 0.60). The new
  variant's load lasts 0.83 s against 0.98 s, so few reads fall inside it.

At 50 ms (12 of 59 rows):

- 4b `coldTiles`: `c6.makespan` 1.05 (p95 1.06), `c6.request` 1.04 (p95 1.08), `c50.makespan` 1.07 (p95 1.23),
  `c50.request` p95 1.28 (median 0.99). A flight recording of this state shows each tile file write taking 8 to 9 ms
  (median) while the page's 405 tile writes run, and the images' own PNG writes 11 ms instead of about 1. The
  static-map stand-in keeps its tiles in memory and writes no file; a real static-map server writes its tile cache
  on its own machine. The two changes tried against it are above. At 250 ms the same page takes 0.98 (`c6`) and 0.38
  (`c50`) of the previous time.
- 4 `cold`: `c6.makespan` 1.04 (p95 1.28), `c6.request` p95 137 ms against 64 (median 0.91), `c50.makespan` 1.11
  (bootstrap interval 0.93 to 1.51; p95 1.00), `c50.request` p95 1.23 (median 0.94); 4b `cold.c6.request` p95 59 ms
  against 53 (median 0.90). Both variants have requests of 100 to 150 ms here, the new variant a few more (11 of 150
  against 6 in one run); a flight recording of the new variant traces them to the first read of an image file written
  a moment earlier (100 ms, in the file's GET).
- 4 `stale.c50.request` p95 253 ms against 197 (median 0.88) and `stale.c50.makespan` p95 255 ms against 241
  (1.06; median 0.97): in one of the six pages every request was slow (median 252 ms against 124 to 138 in the
  other five).
- 3 `noThumbnails.firstByte` p95 338 ms against 304 (median 1.01): the order of the series, as case 5 above.

### The static model

Comparable with the first result. The rows the fixes aimed at pass now: the first image after a start (2c
`firstImage` 0.90 at 250 ms against 1.36 before, 0.82 at 50 ms against 1.05) and the start (`startToReady` 0.97
against 1.06, and 1.00 against 1.05), and the page after a portrayal change (4b `stale.c6.makespan` 1.00 at 250 ms
against 1.13 before, 1.03 at 50 ms against 1.08; every 4 and 4b `stale` row at 250 ms passes).

**Still slower** at 250 ms (16 of 58) and at 50 ms (14 of 59):

- An image of a place without cached tiles against a static-map server that holds every tile: 2a 1.25 (p95 1.29)
  at 250 ms and 1.09 (p95 1.07) at 50 ms; 4 `coldTiles` at concurrency 6 1.22 and 1.45 (page and request) at
  250 ms, 1.27 and 1.37 at 50 ms; 4b `coldTiles` at concurrency 6 1.17 and 1.36, and 1.17 and 1.29; at 50 ms also
  4 `coldTiles.c50.makespan` p95 1.29 and 4b `coldTiles.c50` 1.27 and 1.17. The model charges the previous renderer
  nothing for these tiles; in the fetching model these rows pass but for 2a's p95 at 250 ms (1.05) and the 4b
  `coldTiles` rows at 50 ms above.
- The rows of paths both variants share, as in the fetching model: 3 `noThumbnails` (1.08 and 1.07 at 250 ms, 1.07
  and 1.04 at 50 ms) and `oneMessage` (p95 1.07 at 250 ms), case 5 (1.09 to 1.10 at 250 ms), 4 `cached.c6.makespan`
  (1.13 at 250 ms; p95 1.12 at 50 ms), 4 `cached.c50.request` p95 1.10 and 4b `cached.c50.makespan` 1.07 at 250 ms;
  4 `stale.c6.makespan` p95 1.18 and `stale.c50.request` p95 1.34 at 50 ms (the slow page above). The alternating
  runs of cases 3, 5 and 6 passed every row.

These rows were not made to pass; whether they block the merge is the product owner's decision. The final verdict on
images of places without cached tiles is the real-network run at the next test deployment (below).

<details>
<summary>All rows, fetching model, 250 ms</summary>

| Case | Metric | Old median | New median | Ratio | Old p95 | New p95 | Verdict |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `total` | 24.7 | 25.3 | 1.02 | 29.4 | 29.7 | pass |
| 1 | `redirect` | 22.8 | 23.3 | 1.02 | 27.3 | 27.7 | pass |
| 1 | `file` | 1.6 | 1.7 | 1.06 | 2.7 | 2.7 | pass |
| 2a | `total` | 434 | 389 | 0.9 | 742 | 780 | **slower** |
| 2a | `redirect` | 432 | 387 | 0.9 | 741 | 779 | **slower** |
| 2a | `file` | 1.6 | 1.6 | 1 | 2.4 | 2.5 | pass |
| 2b | `total` | 37.5 | 35.5 | 0.95 | 42.2 | 41.1 | pass |
| 2b | `redirect` | 35.8 | 33.5 | 0.94 | 40 | 39 | pass |
| 2b | `file` | 1.6 | 1.6 | 1 | 2.6 | 2.6 | pass |
| 2c | `firstImage` | 1,169 | 942 | 0.81 | 1,250 | 1,148 | pass |
| 2c | `startToReady` | 16,625 | 17,076 | 1.03 | 17,477 | 17,493 | pass |
| 3 | `cached.total` | 1,659 | 1,635 | 0.99 | 1,886 | 1,904 | pass |
| 3 | `cached.firstByte` | 1,594 | 1,569 | 0.98 | 1,790 | 1,749 | pass |
| 3 | `cold.total` | 2,234 | 2,124 | 0.95 | 2,488 | 2,365 | pass |
| 3 | `cold.firstByte` | 2,164 | 2,060 | 0.95 | 2,438 | 2,288 | pass |
| 3 | `noThumbnails.total` | 341 | 353 | 1.04 | 391 | 386 | pass |
| 3 | `noThumbnails.firstByte` | 294 | 294 | 1 | 339 | 322 | pass |
| 3 | `oneMessage.total` | 113 | 110 | 0.98 | 122 | 121 | pass |
| 3 | `oneMessage.firstByte` | 108 | 106 | 0.98 | 118 | 116 | pass |
| 3b | `total` (vs 3 `cached.total`) | 1,659 | 1,575 | 0.95 | 1,886 | 1,630 | pass |
| 3b | `firstByte` (vs 3 `cached.firstByte`) | 1,594 | 1,520 | 0.95 | 1,790 | 1,578 | pass |
| 4 | `cached.c6.makespan` | 248 | 271 | 1.09 | 258 | 310 | **slower** |
| 4 | `cached.c6.request` | 26.2 | 25.3 | 0.97 | 33.2 | 31.1 | pass |
| 4 | `cached.c50.makespan` | 185 | 163 | 0.88 | 227 | 181 | pass |
| 4 | `cached.c50.request` | 146 | 147 | 1.01 | 168 | 169 | pass |
| 4 | `cold.c6.makespan` | 456 | 384 | 0.84 | 566 | 427 | pass |
| 4 | `cold.c6.request` | 45.9 | 40.1 | 0.87 | 64.6 | 50.1 | pass |
| 4 | `cold.c50.makespan` | 344 | 342 | 0.99 | 367 | 365 | pass |
| 4 | `cold.c50.request` | 246 | 225 | 0.91 | 292 | 273 | pass |
| 4 | `coldTiles.c6.makespan` | 3,907 | 3,458 | 0.89 | 4,793 | 3,645 | pass |
| 4 | `coldTiles.c6.request` | 436 | 402 | 0.92 | 834 | 757 | pass |
| 4 | `coldTiles.c50.makespan` | 2,663 | 1,412 | 0.53 | 2,749 | 1,463 | pass |
| 4 | `coldTiles.c50.request` | 2,109 | 742 | 0.35 | 2,384 | 1,088 | pass |
| 4 | `stale.c6.makespan` (vs 4 `cached.c6.makespan`) | 248 | 232 | 0.93 | 258 | 247 | pass |
| 4 | `stale.c6.request` (vs 4 `cached.c6.request`) | 26.2 | 24.5 | 0.94 | 33.2 | 29.1 | pass |
| 4 | `stale.c50.makespan` (vs 4 `cached.c50.makespan`) | 185 | 148 | 0.8 | 227 | 170 | pass |
| 4 | `stale.c50.request` (vs 4 `cached.c50.request`) | 146 | 135 | 0.93 | 168 | 146 | pass |
| 4b | `cached.c6.makespan` | 772 | 791 | 1.02 | 833 | 850 | pass |
| 4b | `cached.c6.request` | 25.4 | 25.1 | 0.99 | 31.9 | 32.2 | pass |
| 4b | `cached.c50.makespan` | 590 | 569 | 0.96 | 624 | 606 | pass |
| 4b | `cached.c50.request` | 144 | 139 | 0.97 | 219 | 213 | pass |
| 4b | `cold.c6.makespan` | 1,400 | 1,314 | 0.94 | 1,498 | 1,386 | pass |
| 4b | `cold.c6.request` | 43.2 | 38.1 | 0.88 | 59.1 | 60.4 | pass |
| 4b | `cold.c50.makespan` | 1,037 | 944 | 0.91 | 1,113 | 1,050 | pass |
| 4b | `cold.c50.request` | 245 | 226 | 0.92 | 430 | 363 | pass |
| 4b | `coldTiles.c6.makespan` | 11,506 | 11,321 | 0.98 | 11,891 | 11,954 | pass |
| 4b | `coldTiles.c6.request` | 405 | 394 | 0.97 | 770 | 715 | pass |
| 4b | `coldTiles.c50.makespan` | 6,841 | 2,620 | 0.38 | 7,331 | 2,835 | pass |
| 4b | `coldTiles.c50.request` | 1,747 | 609 | 0.35 | 2,970 | 984 | pass |
| 4b | `stale.c6.makespan` (vs 4b `cached.c6.makespan`) | 772 | 793 | 1.03 | 833 | 868 | pass |
| 4b | `stale.c6.request` (vs 4b `cached.c6.request`) | 25.4 | 26.4 | 1.04 | 31.9 | 33.3 | pass |
| 4b | `stale.c50.makespan` (vs 4b `cached.c50.makespan`) | 590 | 513 | 0.87 | 624 | 549 | pass |
| 4b | `stale.c50.request` (vs 4b `cached.c50.request`) | 144 | 128 | 0.89 | 219 | 195 | pass |
| 5 | `editableGet` | 48.1 | 52.6 | 1.09 | 56.6 | 63.2 | **slower** |
| 5 | `put` | 93 | 98.5 | 1.06 | 107 | 117 | **slower** |
| 5 | `roundTrip` | 142 | 149 | 1.05 | 159 | 184 | **slower** |
| 6 | `messageGet` | 39 | 39.8 | 1.02 | 44.7 | 46.1 | pass |
| 6 | `listSearch` | 555 | 566 | 1.02 | 602 | 592 | pass |
| 7 | `messageGet` | 111 | 146 | 1.32 | 392 | 236 | **slower** |
| 7 | `loadMakespan` | 985 | 835 | 0.85 | 1,012 | 862 | pass |

</details>

<details>
<summary>All rows, fetching model, 50 ms</summary>

| Case | Metric | Old median | New median | Ratio | Old p95 | New p95 | Verdict |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `total` | 24.3 | 24.9 | 1.02 | 28.5 | 30 | pass |
| 1 | `redirect` | 22.4 | 22.9 | 1.02 | 26.3 | 27.8 | pass |
| 1 | `file` | 1.7 | 1.7 | 1 | 2.7 | 2.8 | pass |
| 2a | `total` | 124 | 111 | 0.9 | 202 | 175 | pass |
| 2a | `redirect` | 122 | 109 | 0.89 | 199 | 173 | pass |
| 2a | `file` | 1.6 | 1.7 | 1.06 | 2.4 | 2.6 | pass |
| 2b | `total` | 37.2 | 34.5 | 0.93 | 42.9 | 40.5 | pass |
| 2b | `redirect` | 35.3 | 32.7 | 0.93 | 40.5 | 38.2 | pass |
| 2b | `file` | 1.6 | 1.6 | 1 | 2.6 | 2.5 | pass |
| 2c | `firstImage` | 785 | 605 | 0.77 | 869 | 764 | pass |
| 2c | `startToReady` | 17,059 | 17,023 | 1 | 17,105 | 17,102 | pass |
| 3 | `cached.total` | 1,617 | 1,598 | 0.99 | 1,802 | 1,862 | pass |
| 3 | `cached.firstByte` | 1,558 | 1,528 | 0.98 | 1,705 | 1,766 | pass |
| 3 | `cold.total` | 2,232 | 2,134 | 0.96 | 2,756 | 2,282 | pass |
| 3 | `cold.firstByte` | 2,178 | 2,072 | 0.95 | 2,698 | 2,209 | pass |
| 3 | `noThumbnails.total` | 324 | 335 | 1.03 | 371 | 381 | pass |
| 3 | `noThumbnails.firstByte` | 281 | 284 | 1.01 | 304 | 338 | **slower** |
| 3 | `oneMessage.total` | 103 | 107 | 1.04 | 115 | 119 | pass |
| 3 | `oneMessage.firstByte` | 98.6 | 103 | 1.04 | 110 | 113 | pass |
| 3b | `total` (vs 3 `cached.total`) | 1,617 | 1,552 | 0.96 | 1,802 | 1,779 | pass |
| 3b | `firstByte` (vs 3 `cached.firstByte`) | 1,558 | 1,485 | 0.95 | 1,705 | 1,726 | pass |
| 4 | `cached.c6.makespan` | 263 | 219 | 0.83 | 299 | 288 | pass |
| 4 | `cached.c6.request` | 26.1 | 24.3 | 0.93 | 33.6 | 30.2 | pass |
| 4 | `cached.c50.makespan` | 191 | 163 | 0.86 | 241 | 244 | pass |
| 4 | `cached.c50.request` | 150 | 147 | 0.98 | 197 | 165 | pass |
| 4 | `cold.c6.makespan` | 428 | 445 | 1.04 | 443 | 566 | **slower** |
| 4 | `cold.c6.request` | 42.5 | 38.5 | 0.91 | 64.4 | 137 | **slower** |
| 4 | `cold.c50.makespan` | 336 | 374 | 1.11 | 429 | 430 | **slower** |
| 4 | `cold.c50.request` | 245 | 232 | 0.94 | 278 | 341 | **slower** |
| 4 | `coldTiles.c6.makespan` | 1,124 | 1,100 | 0.98 | 1,301 | 1,185 | pass |
| 4 | `coldTiles.c6.request` | 124 | 123 | 0.99 | 198 | 202 | pass |
| 4 | `coldTiles.c50.makespan` | 717 | 592 | 0.83 | 756 | 771 | pass |
| 4 | `coldTiles.c50.request` | 612 | 354 | 0.58 | 699 | 535 | pass |
| 4 | `stale.c6.makespan` (vs 4 `cached.c6.makespan`) | 263 | 230 | 0.87 | 299 | 302 | pass |
| 4 | `stale.c6.request` (vs 4 `cached.c6.request`) | 26.1 | 24.8 | 0.95 | 33.6 | 30.2 | pass |
| 4 | `stale.c50.makespan` (vs 4 `cached.c50.makespan`) | 191 | 186 | 0.97 | 241 | 255 | **slower** |
| 4 | `stale.c50.request` (vs 4 `cached.c50.request`) | 150 | 132 | 0.88 | 197 | 253 | **slower** |
| 4b | `cached.c6.makespan` | 790 | 750 | 0.95 | 850 | 821 | pass |
| 4b | `cached.c6.request` | 25.4 | 24.6 | 0.97 | 33.3 | 30.6 | pass |
| 4b | `cached.c50.makespan` | 638 | 518 | 0.81 | 662 | 540 | pass |
| 4b | `cached.c50.request` | 150 | 131 | 0.87 | 240 | 195 | pass |
| 4b | `cold.c6.makespan` | 1,349 | 1,271 | 0.94 | 1,379 | 1,380 | pass |
| 4b | `cold.c6.request` | 43 | 38.8 | 0.9 | 53.4 | 58.7 | **slower** |
| 4b | `cold.c50.makespan` | 1,016 | 900 | 0.89 | 1,083 | 944 | pass |
| 4b | `cold.c50.request` | 249 | 223 | 0.9 | 389 | 327 | pass |
| 4b | `coldTiles.c6.makespan` | 3,226 | 3,384 | 1.05 | 3,338 | 3,534 | **slower** |
| 4b | `coldTiles.c6.request` | 113 | 118 | 1.04 | 189 | 203 | **slower** |
| 4b | `coldTiles.c50.makespan` | 2,042 | 2,184 | 1.07 | 2,085 | 2,562 | **slower** |
| 4b | `coldTiles.c50.request` | 508 | 503 | 0.99 | 779 | 997 | **slower** |
| 4b | `stale.c6.makespan` (vs 4b `cached.c6.makespan`) | 790 | 806 | 1.02 | 850 | 866 | pass |
| 4b | `stale.c6.request` (vs 4b `cached.c6.request`) | 25.4 | 26.2 | 1.03 | 33.3 | 33.1 | pass |
| 4b | `stale.c50.makespan` (vs 4b `cached.c50.makespan`) | 638 | 545 | 0.85 | 662 | 611 | pass |
| 4b | `stale.c50.request` (vs 4b `cached.c50.request`) | 150 | 135 | 0.9 | 240 | 199 | pass |
| 5 | `editableGet` | 47.5 | 47.5 | 1 | 58.2 | 56.5 | pass |
| 5 | `put` | 91.1 | 91.2 | 1 | 110 | 114 | pass |
| 5 | `roundTrip` | 138 | 139 | 1.01 | 167 | 165 | pass |
| 6 | `messageGet` | 38.7 | 39.3 | 1.02 | 44.1 | 44.8 | pass |
| 6 | `listSearch` | 563 | 563 | 1 | 592 | 584 | pass |
| 7 | `messageGet` | 218 | 139 | 0.64 | 379 | 288 | pass |
| 7 | `listSearch` | 566 | 552 | 0.97 | 566 | 557 | pass |
| 7 | `loadMakespan` | 1,043 | 912 | 0.87 | 1,071 | 937 | pass |

</details>

<details>
<summary>All rows, static model, 250 ms</summary>

| Case | Metric | Old median | New median | Ratio | Old p95 | New p95 | Verdict |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `total` | 24.5 | 25.3 | 1.03 | 29.5 | 29.7 | pass |
| 1 | `redirect` | 22.6 | 23.3 | 1.03 | 27.6 | 27.7 | pass |
| 1 | `file` | 1.6 | 1.7 | 1.06 | 2.7 | 2.7 | pass |
| 2a | `total` | 312 | 389 | 1.25 | 604 | 780 | **slower** |
| 2a | `redirect` | 311 | 387 | 1.25 | 603 | 779 | **slower** |
| 2a | `file` | 1.6 | 1.6 | 1 | 2.3 | 2.5 | pass |
| 2b | `total` (vs 2a `total`) | 312 | 35.5 | 0.11 | 604 | 41.1 | pass |
| 2b | `redirect` (vs 2a `redirect`) | 311 | 33.5 | 0.11 | 603 | 39 | pass |
| 2b | `file` (vs 2a `file`) | 1.6 | 1.6 | 1 | 2.3 | 2.6 | pass |
| 2c | `firstImage` | 1,053 | 942 | 0.9 | 1,174 | 1,148 | pass |
| 2c | `startToReady` | 17,583 | 17,076 | 0.97 | 17,652 | 17,493 | pass |
| 3 | `cached.total` | 1,598 | 1,635 | 1.02 | 1,898 | 1,904 | pass |
| 3 | `cached.firstByte` | 1,528 | 1,569 | 1.03 | 1,794 | 1,749 | pass |
| 3 | `cold.total` | 16,768 | 2,124 | 0.13 | 17,668 | 2,365 | pass |
| 3 | `cold.firstByte` | 16,711 | 2,060 | 0.12 | 17,617 | 2,288 | pass |
| 3 | `noThumbnails.total` | 326 | 353 | 1.08 | 366 | 386 | **slower** |
| 3 | `noThumbnails.firstByte` | 273 | 294 | 1.07 | 306 | 322 | **slower** |
| 3 | `oneMessage.total` | 108 | 110 | 1.02 | 114 | 121 | **slower** |
| 3 | `oneMessage.firstByte` | 104 | 106 | 1.02 | 110 | 116 | **slower** |
| 3b | `total` (vs 3 `cached.total`) | 1,598 | 1,575 | 0.99 | 1,898 | 1,630 | pass |
| 3b | `firstByte` (vs 3 `cached.firstByte`) | 1,528 | 1,520 | 0.99 | 1,794 | 1,578 | pass |
| 4 | `cached.c6.makespan` | 241 | 271 | 1.13 | 310 | 310 | **slower** |
| 4 | `cached.c6.request` | 25.3 | 25.3 | 1 | 32.8 | 31.1 | pass |
| 4 | `cached.c50.makespan` | 169 | 163 | 0.97 | 238 | 181 | pass |
| 4 | `cached.c50.request` | 145 | 147 | 1.01 | 154 | 169 | **slower** |
| 4 | `cold.c6.makespan` | 2,841 | 384 | 0.14 | 3,090 | 427 | pass |
| 4 | `cold.c6.request` | 277 | 40.1 | 0.14 | 652 | 50.1 | pass |
| 4 | `cold.c50.makespan` | 1,857 | 342 | 0.18 | 1,954 | 365 | pass |
| 4 | `cold.c50.request` | 1,469 | 225 | 0.15 | 1,663 | 273 | pass |
| 4 | `coldTiles.c6.makespan` (vs 4 `cold.c6.makespan`) | 2,841 | 3,458 | 1.22 | 3,090 | 3,645 | **slower** |
| 4 | `coldTiles.c6.request` (vs 4 `cold.c6.request`) | 277 | 402 | 1.45 | 652 | 757 | **slower** |
| 4 | `coldTiles.c50.makespan` (vs 4 `cold.c50.makespan`) | 1,857 | 1,412 | 0.76 | 1,954 | 1,463 | pass |
| 4 | `coldTiles.c50.request` (vs 4 `cold.c50.request`) | 1,469 | 742 | 0.51 | 1,663 | 1,088 | pass |
| 4 | `stale.c6.makespan` (vs 4 `cached.c6.makespan`) | 241 | 232 | 0.96 | 310 | 247 | pass |
| 4 | `stale.c6.request` (vs 4 `cached.c6.request`) | 25.3 | 24.5 | 0.97 | 32.8 | 29.1 | pass |
| 4 | `stale.c50.makespan` (vs 4 `cached.c50.makespan`) | 169 | 148 | 0.88 | 238 | 170 | pass |
| 4 | `stale.c50.request` (vs 4 `cached.c50.request`) | 145 | 135 | 0.93 | 154 | 146 | pass |
| 4b | `cached.c6.makespan` | 791 | 791 | 1 | 903 | 850 | pass |
| 4b | `cached.c6.request` | 25.2 | 25.1 | 1 | 32.7 | 32.2 | pass |
| 4b | `cached.c50.makespan` | 534 | 569 | 1.07 | 584 | 606 | **slower** |
| 4b | `cached.c50.request` | 135 | 139 | 1.03 | 209 | 213 | pass |
| 4b | `cold.c6.makespan` | 9,639 | 1,314 | 0.14 | 9,904 | 1,386 | pass |
| 4b | `cold.c6.request` | 289 | 38.1 | 0.13 | 649 | 60.4 | pass |
| 4b | `cold.c50.makespan` | 5,674 | 944 | 0.17 | 6,248 | 1,050 | pass |
| 4b | `cold.c50.request` | 1,378 | 226 | 0.16 | 2,404 | 363 | pass |
| 4b | `coldTiles.c6.makespan` (vs 4b `cold.c6.makespan`) | 9,639 | 11,321 | 1.17 | 9,904 | 11,954 | **slower** |
| 4b | `coldTiles.c6.request` (vs 4b `cold.c6.request`) | 289 | 394 | 1.36 | 649 | 715 | **slower** |
| 4b | `coldTiles.c50.makespan` (vs 4b `cold.c50.makespan`) | 5,674 | 2,620 | 0.46 | 6,248 | 2,835 | pass |
| 4b | `coldTiles.c50.request` (vs 4b `cold.c50.request`) | 1,378 | 609 | 0.44 | 2,404 | 984 | pass |
| 4b | `stale.c6.makespan` (vs 4b `cached.c6.makespan`) | 791 | 793 | 1 | 903 | 868 | pass |
| 4b | `stale.c6.request` (vs 4b `cached.c6.request`) | 25.2 | 26.4 | 1.05 | 32.7 | 33.3 | pass |
| 4b | `stale.c50.makespan` (vs 4b `cached.c50.makespan`) | 534 | 513 | 0.96 | 584 | 549 | pass |
| 4b | `stale.c50.request` (vs 4b `cached.c50.request`) | 135 | 128 | 0.95 | 209 | 195 | pass |
| 5 | `editableGet` | 48 | 52.6 | 1.1 | 59.6 | 63.2 | **slower** |
| 5 | `put` | 89.6 | 98.5 | 1.1 | 105 | 117 | **slower** |
| 5 | `roundTrip` | 137 | 149 | 1.09 | 163 | 184 | **slower** |
| 6 | `messageGet` | 40.1 | 39.8 | 0.99 | 46.5 | 46.1 | pass |
| 6 | `listSearch` | 547 | 566 | 1.04 | 616 | 592 | pass |
| 7 | `messageGet` | 701 | 146 | 0.21 | 2,271 | 236 | pass |
| 7 | `loadMakespan` | 6,225 | 835 | 0.13 | 6,343 | 862 | pass |

</details>

<details>
<summary>All rows, static model, 50 ms</summary>

| Case | Metric | Old median | New median | Ratio | Old p95 | New p95 | Verdict |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `total` | 24.4 | 24.9 | 1.02 | 28.3 | 30 | pass |
| 1 | `redirect` | 22.4 | 22.9 | 1.02 | 26.1 | 27.8 | pass |
| 1 | `file` | 1.7 | 1.7 | 1 | 2.7 | 2.8 | pass |
| 2a | `total` | 102 | 111 | 1.09 | 163 | 175 | **slower** |
| 2a | `redirect` | 100 | 109 | 1.08 | 161 | 173 | **slower** |
| 2a | `file` | 1.5 | 1.7 | 1.13 | 2.4 | 2.6 | pass |
| 2b | `total` (vs 2a `total`) | 102 | 34.5 | 0.34 | 163 | 40.5 | pass |
| 2b | `redirect` (vs 2a `redirect`) | 100 | 32.7 | 0.33 | 161 | 38.2 | pass |
| 2b | `file` (vs 2a `file`) | 1.5 | 1.6 | 1.07 | 2.4 | 2.5 | pass |
| 2c | `firstImage` | 733 | 605 | 0.82 | 924 | 764 | pass |
| 2c | `startToReady` | 17,045 | 17,023 | 1 | 17,081 | 17,102 | pass |
| 3 | `cached.total` | 1,587 | 1,598 | 1.01 | 1,865 | 1,862 | pass |
| 3 | `cached.firstByte` | 1,523 | 1,528 | 1 | 1,771 | 1,766 | pass |
| 3 | `cold.total` | 5,354 | 2,134 | 0.4 | 5,552 | 2,282 | pass |
| 3 | `cold.firstByte` | 5,287 | 2,072 | 0.39 | 5,488 | 2,209 | pass |
| 3 | `noThumbnails.total` | 314 | 335 | 1.07 | 343 | 381 | **slower** |
| 3 | `noThumbnails.firstByte` | 272 | 284 | 1.04 | 294 | 338 | **slower** |
| 3 | `oneMessage.total` | 105 | 107 | 1.02 | 119 | 119 | pass |
| 3 | `oneMessage.firstByte` | 101 | 103 | 1.02 | 115 | 113 | pass |
| 3b | `total` (vs 3 `cached.total`) | 1,587 | 1,552 | 0.98 | 1,865 | 1,779 | pass |
| 3b | `firstByte` (vs 3 `cached.firstByte`) | 1,523 | 1,485 | 0.98 | 1,771 | 1,726 | pass |
| 4 | `cached.c6.makespan` | 227 | 219 | 0.96 | 256 | 288 | **slower** |
| 4 | `cached.c6.request` | 25.4 | 24.3 | 0.96 | 32.1 | 30.2 | pass |
| 4 | `cached.c50.makespan` | 178 | 163 | 0.92 | 269 | 244 | pass |
| 4 | `cached.c50.request` | 155 | 147 | 0.95 | 188 | 165 | pass |
| 4 | `cold.c6.makespan` | 869 | 445 | 0.51 | 939 | 566 | pass |
| 4 | `cold.c6.request` | 90 | 38.5 | 0.43 | 182 | 137 | pass |
| 4 | `cold.c50.makespan` | 563 | 374 | 0.66 | 598 | 430 | pass |
| 4 | `cold.c50.request` | 469 | 232 | 0.49 | 524 | 341 | pass |
| 4 | `coldTiles.c6.makespan` (vs 4 `cold.c6.makespan`) | 869 | 1,100 | 1.27 | 939 | 1,185 | **slower** |
| 4 | `coldTiles.c6.request` (vs 4 `cold.c6.request`) | 90 | 123 | 1.37 | 182 | 202 | **slower** |
| 4 | `coldTiles.c50.makespan` (vs 4 `cold.c50.makespan`) | 563 | 592 | 1.05 | 598 | 771 | **slower** |
| 4 | `coldTiles.c50.request` (vs 4 `cold.c50.request`) | 469 | 354 | 0.75 | 524 | 535 | pass |
| 4 | `stale.c6.makespan` (vs 4 `cached.c6.makespan`) | 227 | 230 | 1.01 | 256 | 302 | **slower** |
| 4 | `stale.c6.request` (vs 4 `cached.c6.request`) | 25.4 | 24.8 | 0.98 | 32.1 | 30.2 | pass |
| 4 | `stale.c50.makespan` (vs 4 `cached.c50.makespan`) | 178 | 186 | 1.05 | 269 | 255 | pass |
| 4 | `stale.c50.request` (vs 4 `cached.c50.request`) | 155 | 132 | 0.85 | 188 | 253 | **slower** |
| 4b | `cached.c6.makespan` | 784 | 750 | 0.96 | 843 | 821 | pass |
| 4b | `cached.c6.request` | 25.4 | 24.6 | 0.97 | 32.2 | 30.6 | pass |
| 4b | `cached.c50.makespan` | 570 | 518 | 0.91 | 697 | 540 | pass |
| 4b | `cached.c50.request` | 140 | 131 | 0.94 | 231 | 195 | pass |
| 4b | `cold.c6.makespan` | 2,903 | 1,271 | 0.44 | 2,984 | 1,380 | pass |
| 4b | `cold.c6.request` | 91.4 | 38.8 | 0.42 | 170 | 58.7 | pass |
| 4b | `cold.c50.makespan` | 1,725 | 900 | 0.52 | 1,822 | 944 | pass |
| 4b | `cold.c50.request` | 430 | 223 | 0.52 | 746 | 327 | pass |
| 4b | `coldTiles.c6.makespan` (vs 4b `cold.c6.makespan`) | 2,903 | 3,384 | 1.17 | 2,984 | 3,534 | **slower** |
| 4b | `coldTiles.c6.request` (vs 4b `cold.c6.request`) | 91.4 | 118 | 1.29 | 170 | 203 | **slower** |
| 4b | `coldTiles.c50.makespan` (vs 4b `cold.c50.makespan`) | 1,725 | 2,184 | 1.27 | 1,822 | 2,562 | **slower** |
| 4b | `coldTiles.c50.request` (vs 4b `cold.c50.request`) | 430 | 503 | 1.17 | 746 | 997 | **slower** |
| 4b | `stale.c6.makespan` (vs 4b `cached.c6.makespan`) | 784 | 806 | 1.03 | 843 | 866 | pass |
| 4b | `stale.c6.request` (vs 4b `cached.c6.request`) | 25.4 | 26.2 | 1.03 | 32.2 | 33.1 | pass |
| 4b | `stale.c50.makespan` (vs 4b `cached.c50.makespan`) | 570 | 545 | 0.95 | 697 | 611 | pass |
| 4b | `stale.c50.request` (vs 4b `cached.c50.request`) | 140 | 135 | 0.97 | 231 | 199 | pass |
| 5 | `editableGet` | 49.5 | 47.5 | 0.96 | 57.5 | 56.5 | pass |
| 5 | `put` | 93.4 | 91.2 | 0.98 | 111 | 114 | pass |
| 5 | `roundTrip` | 143 | 139 | 0.97 | 162 | 165 | pass |
| 6 | `messageGet` | 40 | 39.3 | 0.98 | 47.1 | 44.8 | pass |
| 6 | `listSearch` | 561 | 563 | 1 | 596 | 584 | pass |
| 7 | `messageGet` | 259 | 139 | 0.54 | 795 | 288 | pass |
| 7 | `listSearch` | 568 | 552 | 0.97 | 580 | 557 | pass |
| 7 | `loadMakespan` | 1,898 | 912 | 0.48 | 1,900 | 937 | pass |

</details>

## First result: before the fixes (history)

Superseded by the result above. It is the record of the state the fixes started from, in the static model only.


Measured on 2026-09-28, 22:21 to 23:22 UTC, on a developer workstation: Windows 11, 22 logical processors,
Temurin 25, Node 24.15, the corporate endpoint protection active, and another backend running on the machine
that used about one processor core throughout (both variants shared it). Old: `niord-dk` at the merge
base. New: `niord-dk` at the branch head, with the two changes below. The data: the copies of the test data
(51,577 messages); the set: the 50 NM messages of the `--filter` above; the pool of cases 4b and 7: the 173
published messages with a geometry (the pool filter finds no more). Two runs of each variant per latency, in the
order old, new, old, new, pooled by `compare`. Every check passed: no placeholder and no failed request, all 50
thumbnails in every PDF, and the expected stand-in hits. The images are 16.9 KB against 16.5 KB (median), the
thumbnail PDF 886 KB against 857 KB.

**Faster**, most by several times: every image drawn from tiles the renderer has cached. A cold image with its
tiles cached takes 35 ms against the previous 312 ms (2b against 2a, at 250 ms), a page of 50 or 173 such images
a fifth to a sixth of the time at 250 ms and about 0.6 of it at 50 ms (4 and 4b `cold`), and the PDF of the set
with every image deleted 3.6 s against 16.5 s (3 `cold`, at 250 ms). Message GET while 173 images are drawn
(case 7) takes 149 ms against 729 ms at 250 ms: the renderer no longer holds a database connection while it
draws, so it no longer starves the other requests of one.

**The same**, within the noise: a cached image (1), the PDFs with cached images (3, and 3b, the PDF right after
the deployment), a page of cached images, and message GET, search and save (5, 6).

**Slower**:

- **An image of a place whose tiles are not cached**, 1.25 times at 250 ms and 1.18 at 50 ms (2a), and a page
  of such images at concurrency 6, 1.1 to 1.45 times (4 and 4b `coldTiles`, `c6`); at 50 ms the page of 173 at
  concurrency 50 as well (1.3). The tile renderer waits for the slowest of up to four tile requests where the
  previous renderer waited for one static map, and the stand-ins charge each request the same latency. The
  previous renderer's static-map server fetched the tiles of a place it had not drawn itself, which the stand-in
  does not charge, so these rows hold the tile renderer with a cold tile cache against the previous one with a
  warm static-map server; in reality both pay for the tiles of a place neither has drawn. The next test
  deployment measures it over the real network (below). Once fetched, a tile serves every image of its place for
  at least seven days.
- **The first image after a start**, 1.36 times at 250 ms (2c): the same cold tiles, plus the first use of the
  HTTP client. The start itself takes about a second longer (1.05).
- **A page of images drawn with an earlier portrayal** (4b `stale` against the old `cached`), 1.05 to 1.13
  times: each request starts a background regeneration, which runs while the page loads. This state lasts from a
  portrayal change until each image has been asked for once. Delaying the regeneration by a second brought the
  concurrency 6 rows within the limit but made 3b, the PDF right after the deployment, 1.15 to 1.23 times slower
  in two runs, so it was left out.
- Rows whose medians are within 7 % and that pass at the other latency (case 5's p95 at 250 ms; 3 `oneMessage`,
  4 `cached.c50.request` and 4b `cached.c50.makespan` at 50 ms): the noise of this workstation, as far as can
  be told, since those paths do the same work in both variants.

These rows were not made to pass; whether they block the merge is the product owner's decision.

**Changed because of it.** Two changes to the tile renderer came out of these runs, each measured on case 4
`coldTiles` at concurrency 50, against the previous renderer's 1,924 ms for the same page (`cold`):

- Tile requests per host: at 8 the page took 3.6 s, with nearly half of its tiles still in the renderer's memory
  (80 of 144 fetched); at 32, 2.1 s; at 64, 1.2 s, now the number.
- The tile cache is written after the image has its tiles, not before: at 50 ms the page went from 0.87 s to
  0.61 s.

**Workstation effects.** Reading a tile file written a moment earlier sometimes waits about 100 ms (the per-image
timings in the backend log). Twice the harness process ended without a message (exit code 127), each time while it
renamed and deleted many images, most likely stopped by the endpoint protection; those runs were repeated. The
Linux containers of a deployment have neither.

<details>
<summary>All rows, 250 ms</summary>

| Case | Metric | Old median | New median | Ratio | Old p95 | New p95 | Verdict |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `total` | 24.9 | 24.4 | 0.98 | 29 | 28.3 | pass |
| 1 | `redirect` | 23 | 22.5 | 0.98 | 26.7 | 26.2 | pass |
| 1 | `file` | 1.7 | 1.6 | 0.94 | 2.7 | 2.6 | pass |
| 2a | `total` | 312 | 389 | 1.25 | 616 | 739 | **slower** |
| 2a | `redirect` | 311 | 387 | 1.25 | 614 | 737 | **slower** |
| 2a | `file` | 1.5 | 1.6 | 1.07 | 2.5 | 2.6 | pass |
| 2b | `total` (vs 2a `total`) | 312 | 35.3 | 0.11 | 616 | 139 | pass |
| 2b | `redirect` (vs 2a `redirect`) | 311 | 33.5 | 0.11 | 614 | 137 | pass |
| 2b | `file` (vs 2a `file`) | 1.5 | 1.6 | 1.07 | 2.5 | 2.4 | pass |
| 2c | `firstImage` | 902 | 1,229 | 1.36 | 929 | 1,319 | **slower** |
| 2c | `startToReady` | 17,128 | 18,118 | 1.06 | 18,051 | 18,597 | **slower** |
| 3 | `cached.total` | 1,579 | 1,608 | 1.02 | 1,933 | 1,963 | pass |
| 3 | `cached.firstByte` | 1,511 | 1,537 | 1.02 | 1,849 | 1,857 | pass |
| 3 | `cold.total` | 16,454 | 3,640 | 0.22 | 18,514 | 3,939 | pass |
| 3 | `cold.firstByte` | 16,403 | 3,584 | 0.22 | 18,457 | 3,883 | pass |
| 3 | `noThumbnails.total` | 338 | 324 | 0.96 | 378 | 351 | pass |
| 3 | `noThumbnails.firstByte` | 288 | 276 | 0.96 | 320 | 308 | pass |
| 3 | `oneMessage.total` | 109 | 104 | 0.95 | 116 | 119 | pass |
| 3 | `oneMessage.firstByte` | 106 | 100 | 0.95 | 111 | 113 | pass |
| 3b | `total` (vs 3 `cached.total`) | 1,579 | 1,587 | 1.01 | 1,933 | 1,707 | pass |
| 3b | `firstByte` (vs 3 `cached.firstByte`) | 1,511 | 1,535 | 1.02 | 1,849 | 1,641 | pass |
| 4 | `cached.c6.makespan` | 243 | 227 | 0.93 | 298 | 235 | pass |
| 4 | `cached.c6.request` | 25.8 | 25 | 0.97 | 31.8 | 30.5 | pass |
| 4 | `cached.c50.makespan` | 232 | 158 | 0.68 | 251 | 167 | pass |
| 4 | `cached.c50.request` | 149 | 144 | 0.96 | 160 | 154 | pass |
| 4 | `cold.c6.makespan` | 2,870 | 608 | 0.21 | 3,265 | 734 | pass |
| 4 | `cold.c6.request` | 286 | 35.7 | 0.12 | 604 | 140 | pass |
| 4 | `cold.c50.makespan` | 1,924 | 344 | 0.18 | 2,231 | 359 | pass |
| 4 | `cold.c50.request` | 1,516 | 217 | 0.14 | 1,736 | 292 | pass |
| 4 | `coldTiles.c6.makespan` (vs 4 `cold.c6.makespan`) | 2,870 | 3,868 | 1.35 | 3,265 | 4,271 | **slower** |
| 4 | `coldTiles.c6.request` (vs 4 `cold.c6.request`) | 286 | 414 | 1.45 | 604 | 741 | **slower** |
| 4 | `coldTiles.c50.makespan` (vs 4 `cold.c50.makespan`) | 1,924 | 1,207 | 0.63 | 2,231 | 1,630 | pass |
| 4 | `coldTiles.c50.request` (vs 4 `cold.c50.request`) | 1,516 | 729 | 0.48 | 1,736 | 1,296 | pass |
| 4 | `stale.c6.makespan` (vs 4 `cached.c6.makespan`) | 243 | 232 | 0.96 | 298 | 276 | pass |
| 4 | `stale.c6.request` (vs 4 `cached.c6.request`) | 25.8 | 26.5 | 1.03 | 31.8 | 34.8 | **slower** |
| 4 | `stale.c50.makespan` (vs 4 `cached.c50.makespan`) | 232 | 156 | 0.67 | 251 | 166 | pass |
| 4 | `stale.c50.request` (vs 4 `cached.c50.request`) | 149 | 136 | 0.91 | 160 | 149 | pass |
| 4b | `cached.c6.makespan` | 782 | 766 | 0.98 | 862 | 819 | pass |
| 4b | `cached.c6.request` | 25.6 | 24.4 | 0.95 | 34.4 | 30.3 | pass |
| 4b | `cached.c50.makespan` | 552 | 533 | 0.97 | 581 | 590 | pass |
| 4b | `cached.c50.request` | 139 | 135 | 0.97 | 203 | 201 | pass |
| 4b | `cold.c6.makespan` | 9,814 | 1,724 | 0.18 | 10,276 | 2,121 | pass |
| 4b | `cold.c6.request` | 306 | 35.5 | 0.12 | 620 | 138 | pass |
| 4b | `cold.c50.makespan` | 5,735 | 955 | 0.17 | 6,411 | 1,020 | pass |
| 4b | `cold.c50.request` | 1,388 | 233 | 0.17 | 2,596 | 354 | pass |
| 4b | `coldTiles.c6.makespan` (vs 4b `cold.c6.makespan`) | 9,814 | 10,912 | 1.11 | 10,276 | 11,099 | **slower** |
| 4b | `coldTiles.c6.request` (vs 4b `cold.c6.request`) | 306 | 387 | 1.26 | 620 | 712 | **slower** |
| 4b | `coldTiles.c50.makespan` (vs 4b `cold.c50.makespan`) | 5,735 | 2,690 | 0.47 | 6,411 | 2,919 | pass |
| 4b | `coldTiles.c50.request` (vs 4b `cold.c50.request`) | 1,388 | 602 | 0.43 | 2,596 | 1,025 | pass |
| 4b | `stale.c6.makespan` (vs 4b `cached.c6.makespan`) | 782 | 886 | 1.13 | 862 | 905 | **slower** |
| 4b | `stale.c6.request` (vs 4b `cached.c6.request`) | 25.6 | 28 | 1.09 | 34.4 | 38.3 | **slower** |
| 4b | `stale.c50.makespan` (vs 4b `cached.c50.makespan`) | 552 | 599 | 1.08 | 581 | 639 | **slower** |
| 4b | `stale.c50.request` (vs 4b `cached.c50.request`) | 139 | 146 | 1.05 | 203 | 252 | **slower** |
| 5 | `editableGet` | 46 | 46 | 1 | 55.4 | 63.5 | **slower** |
| 5 | `put` | 87.5 | 88.3 | 1.01 | 101 | 123 | **slower** |
| 5 | `roundTrip` | 134 | 134 | 1 | 156 | 187 | **slower** |
| 6 | `messageGet` | 38.3 | 38.3 | 1 | 44.2 | 46.3 | pass |
| 6 | `listSearch` | 553 | 565 | 1.02 | 608 | 614 | pass |
| 7 | `messageGet` | 729 | 149 | 0.2 | 2,488 | 315 | pass |
| 7 | `loadMakespan` | 5,717 | 924 | 0.16 | 5,859 | 943 | pass |
| 7 | `listSearch` | 548 | 568 | 1.04 | 559 | 568 | pass |

</details>

<details>
<summary>All rows, 50 ms</summary>

| Case | Metric | Old median | New median | Ratio | Old p95 | New p95 | Verdict |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `total` | 24.8 | 24.9 | 1 | 29.2 | 30.1 | pass |
| 1 | `redirect` | 22.9 | 22.8 | 1 | 27 | 28 | pass |
| 1 | `file` | 1.7 | 1.7 | 1 | 2.8 | 2.8 | pass |
| 2a | `total` | 96.3 | 114 | 1.18 | 158 | 204 | **slower** |
| 2a | `redirect` | 94.4 | 111 | 1.17 | 156 | 197 | **slower** |
| 2a | `file` | 1.5 | 1.7 | 1.13 | 2.4 | 2.7 | pass |
| 2b | `total` (vs 2a `total`) | 96.3 | 36.4 | 0.38 | 158 | 140 | pass |
| 2b | `redirect` (vs 2a `redirect`) | 94.4 | 34.7 | 0.37 | 156 | 137 | pass |
| 2b | `file` (vs 2a `file`) | 1.5 | 1.6 | 1.07 | 2.4 | 2.5 | pass |
| 2c | `firstImage` | 830 | 870 | 1.05 | 948 | 1,327 | **slower** |
| 2c | `startToReady` | 17,206 | 18,106 | 1.05 | 17,997 | 18,829 | **slower** |
| 3 | `cached.total` | 1,612 | 1,630 | 1.01 | 1,889 | 1,948 | pass |
| 3 | `cached.firstByte` | 1,544 | 1,571 | 1.02 | 1,788 | 1,822 | pass |
| 3 | `cold.total` | 5,465 | 3,784 | 0.69 | 5,919 | 4,307 | pass |
| 3 | `cold.firstByte` | 5,415 | 3,727 | 0.69 | 5,870 | 4,246 | pass |
| 3 | `noThumbnails.total` | 331 | 330 | 0.99 | 367 | 367 | pass |
| 3 | `noThumbnails.firstByte` | 283 | 280 | 0.99 | 315 | 310 | pass |
| 3 | `oneMessage.total` | 106 | 112 | 1.06 | 116 | 124 | **slower** |
| 3 | `oneMessage.firstByte` | 102 | 108 | 1.06 | 111 | 119 | **slower** |
| 3b | `total` (vs 3 `cached.total`) | 1,612 | 1,642 | 1.02 | 1,889 | 1,742 | pass |
| 3b | `firstByte` (vs 3 `cached.firstByte`) | 1,544 | 1,583 | 1.03 | 1,788 | 1,683 | pass |
| 4 | `cached.c6.makespan` | 245 | 224 | 0.91 | 329 | 274 | pass |
| 4 | `cached.c6.request` | 25.3 | 24.2 | 0.96 | 31.6 | 30.2 | pass |
| 4 | `cached.c50.makespan` | 201 | 171 | 0.85 | 254 | 231 | pass |
| 4 | `cached.c50.request` | 148 | 154 | 1.04 | 160 | 172 | **slower** |
| 4 | `cold.c6.makespan` | 909 | 573 | 0.63 | 955 | 736 | pass |
| 4 | `cold.c6.request` | 92 | 36.3 | 0.39 | 164 | 138 | pass |
| 4 | `cold.c50.makespan` | 590 | 347 | 0.59 | 646 | 361 | pass |
| 4 | `cold.c50.request` | 477 | 217 | 0.46 | 558 | 262 | pass |
| 4 | `coldTiles.c6.makespan` (vs 4 `cold.c6.makespan`) | 909 | 1,059 | 1.17 | 955 | 1,177 | **slower** |
| 4 | `coldTiles.c6.request` (vs 4 `cold.c6.request`) | 92 | 124 | 1.34 | 164 | 207 | **slower** |
| 4 | `coldTiles.c50.makespan` (vs 4 `cold.c50.makespan`) | 590 | 613 | 1.04 | 646 | 733 | **slower** |
| 4 | `coldTiles.c50.request` (vs 4 `cold.c50.request`) | 477 | 390 | 0.82 | 558 | 628 | **slower** |
| 4 | `stale.c6.makespan` (vs 4 `cached.c6.makespan`) | 245 | 247 | 1.01 | 329 | 270 | pass |
| 4 | `stale.c6.request` (vs 4 `cached.c6.request`) | 25.3 | 26.7 | 1.06 | 31.6 | 32.3 | pass |
| 4 | `stale.c50.makespan` (vs 4 `cached.c50.makespan`) | 201 | 148 | 0.74 | 254 | 167 | pass |
| 4 | `stale.c50.request` (vs 4 `cached.c50.request`) | 148 | 137 | 0.92 | 160 | 163 | pass |
| 4b | `cached.c6.makespan` | 809 | 787 | 0.97 | 838 | 828 | pass |
| 4b | `cached.c6.request` | 25.9 | 25.8 | 1 | 33.5 | 33.4 | pass |
| 4b | `cached.c50.makespan` | 552 | 591 | 1.07 | 669 | 635 | **slower** |
| 4b | `cached.c50.request` | 144 | 150 | 1.04 | 224 | 230 | pass |
| 4b | `cold.c6.makespan` | 2,908 | 1,771 | 0.61 | 3,107 | 1,970 | pass |
| 4b | `cold.c6.request` | 93.5 | 38 | 0.41 | 166 | 140 | pass |
| 4b | `cold.c50.makespan` | 1,801 | 982 | 0.55 | 1,914 | 1,145 | pass |
| 4b | `cold.c50.request` | 436 | 228 | 0.52 | 759 | 391 | pass |
| 4b | `coldTiles.c6.makespan` (vs 4b `cold.c6.makespan`) | 2,908 | 3,406 | 1.17 | 3,107 | 3,513 | **slower** |
| 4b | `coldTiles.c6.request` (vs 4b `cold.c6.request`) | 93.5 | 118 | 1.26 | 166 | 205 | **slower** |
| 4b | `coldTiles.c50.makespan` (vs 4b `cold.c50.makespan`) | 1,801 | 2,360 | 1.31 | 1,914 | 2,495 | **slower** |
| 4b | `coldTiles.c50.request` (vs 4b `cold.c50.request`) | 436 | 508 | 1.16 | 759 | 1,036 | **slower** |
| 4b | `stale.c6.makespan` (vs 4b `cached.c6.makespan`) | 809 | 875 | 1.08 | 838 | 1,002 | **slower** |
| 4b | `stale.c6.request` (vs 4b `cached.c6.request`) | 25.9 | 28.4 | 1.1 | 33.5 | 41.6 | **slower** |
| 4b | `stale.c50.makespan` (vs 4b `cached.c50.makespan`) | 552 | 562 | 1.02 | 669 | 637 | pass |
| 4b | `stale.c50.request` (vs 4b `cached.c50.request`) | 144 | 141 | 0.98 | 224 | 252 | **slower** |
| 5 | `editableGet` | 49.1 | 49.5 | 1.01 | 62.1 | 59.2 | pass |
| 5 | `put` | 93.3 | 94.9 | 1.02 | 108 | 114 | pass |
| 5 | `roundTrip` | 143 | 144 | 1.01 | 171 | 169 | pass |
| 6 | `messageGet` | 40.8 | 39.8 | 0.98 | 47.7 | 47.7 | pass |
| 6 | `listSearch` | 571 | 565 | 0.99 | 619 | 599 | pass |
| 7 | `messageGet` | 327 | 143 | 0.44 | 894 | 251 | pass |
| 7 | `loadMakespan` | 1,798 | 840 | 0.47 | 1,819 | 848 | pass |

</details>

## Running it against a deployment

```
NIORD_USER=<user> NIORD_PASS=<password> node be/scripts/bench-map-images.mjs remote \
  --base https://<host> --keycloak https://<keycloak host>/auth --realm niord --client niord-web \
  --domain niord-nm --filter 'domain=niord-nm&status=PUBLISHED&area=529&area=466&area=504' \
  --list-filter 'domain=niord-nm&status=PUBLISHED' --out remote.json
```

GET only. Each path is tried once before any loop; the set is the first 50 messages with a geometry that
`--filter` finds (or the set of an earlier result file, `--set`). Concurrency is capped at 50, and the cases
are 1 s apart. A user without the sysadmin role needs `--domain` (the `NiordDomain` header).

| Case | What is timed |
| --- | --- |
| R1 | The image of each message, one at a time, 5 rounds. The first round is reported on its own: the state on the server is unknown. |
| R2 | The 50 images requested at once, at concurrency 6 and 50 (one uncounted batch, then 3): the page's makespan and each request. |
| R3 | The list PDF of the set with thumbnails, as the administration app's print dialog asks for it (`report=standard&pageSize=A4&pageOrientation=portrait&mapThumbnails=true&lang=en` and the filter; the bearer token stands in for the one-time ticket). One uncounted run, then 5. |
| R4 | The same PDF without thumbnails. |
| R5 | `GET /rest/messages/message/{id}` 200 times and the list search 30 times, one at a time. |

Image requests carry no token and no `NiordDomain` header, as an `<img>` carries neither. A generated image
comes in one request; a 307 (a custom thumbnail's, or any image's from a backend that redirects) is followed
to the file. A 307 whose Location names `http://` on the site's own host while the site is https is followed to `https://`, as a
browser upgrades a mixed-content image, and counted.

## Reference: the previous Niord on the test environment

Measured on 2026-09-28, 12:54 to 12:58 UTC, from a developer workstation on the office network (Windows 11,
Node 24.15; the times include the network path to the test environment).

**The environment.** The old test environment: the previous Niord (the Quarkus backend on MySQL) behind the
test ingress, with a copy of the production data (the snapshot of 17 August 2026, plus test activity since).
Its map images come from an internal static-map server on the test network (the `mapImageSize` setting is
256). Keycloak on that environment's own login host, realm `niord`; logged in as the test sysadmin with
`NiordDomain: niord-nm`.

**The set.** `--filter 'domain=niord-nm&status=PUBLISHED&area=529&area=466&area=504'` (the NM domain's
published notices in the Kattegat, the Baltic Sea and the waters south of Funen) found exactly 50 messages,
all with a geometry: NM-053-26, NM-016-26, NM-1006-25, NM-246-26, NM-403-25, NM-796-26, NM-614-26, NM-395-23,
NM-367-26, NM-775-26, NM-762-26, NM-396-23, NM-797-26, NM-997-24, NM-792-26, NM-674-25, NM-584-26, NM-620-26,
NM-630-26, NM-678-26, NM-736-26, NM-649-26, NM-807-26, NM-562-26, NM-561-26, NM-444-26, NM-464-26, NM-978-23,
NM-513-26, NM-471-26, NM-633-26, NM-051-26, NM-412-26, NM-624-26, NM-492-22, NM-216-25, NM-795-26, NM-809-26,
NM-738-26, NM-419-25, NM-766-25, NM-788-26, NM-536-26, NM-801-26, NM-489-26, NM-452-26, NM-679-26, NM-566-23,
NM-417-26, NM-720-26. The list search (`--list-filter 'domain=niord-nm&status=PUBLISHED'`) returns 176
messages, 454 KB. The images of the set are 0.9 to 61 KB, median 23 KB.

| Case | Metric | n | Median ms | p95 ms | Max ms |
| --- | --- | --- | --- | --- | --- |
| R1 | image, first fetch in the run: whole fetch | 50 | 81.1 | 125.8 | 313.5 |
| R1 | image, cached: whole fetch | 200 | 78.3 | 104.0 | 246.0 |
| R1 | image, cached: the 307 alone | 200 | 47.1 | 58.7 | 122.2 |
| R1 | image, cached: the file | 200 | 30.7 | 45.6 | 123.6 |
| R2 | 50 images at concurrency 6: page makespan | 3 | 886.4 | 887.9 | 888.1 |
| R2 | 50 images at concurrency 6: each request | 150 | 91.8 | 130.8 | 163.7 |
| R2 | 50 images at concurrency 10: page makespan (30 of 150 requests refused) | 3 | 500.4 | 538.7 | 543.0 |
| R2 | 50 images at concurrency 10: each served request | 120 | 94.6 | 120.3 | 143.6 |
| R2 | 50 images at concurrency 50: each served request (130 of 150 refused) | 20 | 98.8 | 137.5 | 142.3 |
| R3 | list PDF of the 50 with thumbnails (87 KB, no map image in it) | 5 | 1788.7 | 1805.4 | 1806.6 |
| R3 | the same, first byte | 5 | 1736.6 | 1744.5 | 1745.3 |
| R4 | list PDF of the 50 without thumbnails (85 KB) | 5 | 453.3 | 465.5 | 468.1 |
| R5 | message GET | 200 | 72.2 | 88.5 | 100.3 |
| R5 | list search, 176 messages | 30 | 620.9 | 704.3 | 903.6 |

R2 was run on its own a few minutes after the other cases, with concurrency 10 added; the first, full run
gave 771.1 ms (median page makespan) and 86.4 / 111.4 ms (median / p95 per request) at concurrency 6.

**What the numbers show beyond the table.**

- **The thumbnail PDF holds no map image.** The template names all 50 images (the debug HTML carries 50
  `<img src="/rest/message-map-image/...">`), and the PDF engine spends about 1.3 s on them (R3 against R4), but
  every PDF came back with the same two images as the one without thumbnails, 87 KB against 85 KB. So R3 times
  50 image requests whose answers the PDF drops, not a PDF with thumbnails; a PDF that does embed its 50
  images does more work than R3 did.
- **The ingress refuses bursts.** At concurrency 6 every request was served; at 10 one in five, and at 50
  nearly nine in ten, were answered `429 Too Many Requests`. A Details or Grid page over HTTP/2, where the
  browser does not hold itself to 6 connections, would show broken images for most of a page behind such a
  limit.
- **The 307 names `http://`.** Every Location the test environment sent for an image points at
  `http://` on the site's own host although the site is https. A browser upgrades such an image request; a client
  that follows it literally gets one more redirect (a 302 to https) per image.
- R1's first round was as fast as the cached rounds (81.1 against 78.3 ms median), so the images of the set were
  already there: the table has no cold image over the real network.

The raw result files are not kept in the repository; this table is the record.

**At the next test deployment** (Kubernetes, on the test database), in this order:

1. Check that the application pod reaches `https://tile.openstreetmap.org` (a HEAD of `/0/0/0.png` from the pod,
   or from a throwaway pod carrying the application's labels, so that the same network policy applies). If the
   cluster needs a proxy, pass `-Dhttps.proxyHost=<host> -Dhttps.proxyPort=<port>` (and `-Dhttp.nonProxyHosts`
   for the in-cluster hosts) through `app.javaOpts`, which the chart hands to the JVM as `JAVA_TOOL_OPTIONS`;
   the tile fetcher uses the JVM's default proxy selector.
2. Set `mapTileContact` (the sysadmin settings page) to the operator's e-mail address or URL: the OSM tile
   policy asks for a contact in the User-Agent.
3. Run `remote` with the command above against that deployment's host, and hold its table against this one. They are
   different systems on different hardware, so this is a guide, not the gate (the gate is the local
   comparison): R1, R2 at concurrency 6 and R5 should be no slower; R3 is held against the R3 above while
   holding 50 map images (the result file lists `mapImagesPerPdf`); R4 against R4. Check whether a burst of
   50 is refused there, and, for a custom thumbnail, whether the 307 names `https://`. The table above is two
   requests per image and a current backend's one (see "Two requests then, one now"). If the filter no longer finds 50 messages, pass
   `--count` and say so. A first round of R1 right after the deployment is the one cold measurement over the
   real network (tiles from OSM): report it on its own.
