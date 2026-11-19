# Repository files in the database: the volume a cache, one thing to back up

**Status:** written 2026-10-22 at the owner's request; D1 to D3 were decided the same day, D4 to D10 on
2026-10-24 and D11 on 2026-10-27 (§2); no decision is open. **R1 is done: merged into `main` on
2026-11-10 and released as 4.0.146** (its state under § R1). **R2 is done: DK and GL staging run base
4.0.147 on the store since 2026-11-10; neither had a file to import, so no import ran** (its state under
§ R2). R0's measurements
were not taken before R2 (the owner, 2026-11-10). **R4 is done** (2026-11-10, its state under § R4). R0's host
questions and R3 are not started. **R5 is replaced**: D10 was amended on 2026-11-10 (the cache moves from the
proxies' disks to the application's volume), and the sea-chart tiles' cache has a plan of its own,
[`sea-chart-tile-cache-plan.md`](sea-chart-tile-cache-plan.md).
**D11 sets the scope: this plan runs on the database the deployments use today, the chart's Postgres; the move of that database
to the hotel is a separate project, for which this plan builds the chart seams and writes the handover
(§4.9, §5, R3).** The motivation is the host's managed PostgreSQL
(the "database hotel"), which comes with automatic backup. Today a deployment's
durable state is split between PostgreSQL and a file tree on the application's volume (`NIORD_HOME`), so a
backup has to cover both: the devops design plans a nightly `pg_dump` CronJob to object storage plus an
optional mirror of the file tree (`be/docs/cicd/design.md` §9, copied to `devops-niord`), the object-storage question H4 is
unanswered, and no backup of any kind exists today (`devops-niord/docs/phase-0-answers.md` H4). When no
durable byte lives outside the database and the database lives in the hotel, the hotel's backup is the
deployment's backup, and this repository builds and runs no backup job at all.
File and line references are against the `main` of 2026-10-23 and the `main` of `devops-niord` and of
`devops-shared` at that time. **[I]** marks an inference not confirmed by code or a run.
**Scope:** every durable file the backend keeps on disk, the caches and work folders next to them, the
application's volume in the chart and the environment values, and the chart seams the later move of
PostgreSQL (with Keycloak's database) to the hotel will need. The move itself is a separate project (D11).
**Not in scope:** production, which is touched only at the owner's request (this plan prepares nothing
there; §6 R4 says what its cutover will need); the retirement of Lucene (`be/docs/lucene-todo-removal.md`);
the OSM and OpenSeaMap tiles the browsers fetch from their providers, which this plan does not touch. The
sea-chart (WMS) tiles' cache came into scope on 2026-10-24 (D10, R5), as a cache on the proxies' disks, never
in the database; since 2026-11-10 it is a cache on the application's volume with a plan of its own
([`sea-chart-tile-cache-plan.md`](sea-chart-tile-cache-plan.md)), and of this plan only D10's "never in the
database" still speaks of it.
**Repos:** `niord` (niord-base, the chart, the sample, and `be/docs/cicd/`, which owns the design and
implementation plan that `devops-niord` copies); `niord-dk` and `niord-gl` (each on the release that carries
the store, and its image, before R2); `devops-niord` (values, runbooks, its copies of the design);
`devops-shared`, which has no backup component but whose monitoring and log-collection documents name the
Postgres volumes (R4).

---

## 1. Summary

**Terms.** *The store*: the table that holds repository files after the move. *Repository path*: the
path a file has under today's repository root (`messages/7/7a/77a0…/3/chart.pdf`: the uid's first
character, its next two, the uid, the revision), which is also the path in its URL (`/rest/repo/file/<path>`) and in the rows that refer to it. *The home*: `NIORD_HOME`, the
folder the caches and work files live in.

**The rule.** A file an operator or a mariner would miss if the pod were replaced is a row in the
database. A file the application can make again (a thumbnail, a map image, a preview, a tile, the
Lucene index) or that is a work file of a job in flight lives on the application's volume, which is a
cache from then on: nothing backs it up, and losing it costs a warm-up. The only persistent volumes are
that cache and, until R3, the chart's own Postgres.

| | Today | After |
|---|---|---|
| Message attachments, embedded images, custom map thumbnails | files under `repo/messages/…` on the volume; the row holds the path | rows in the store; the row holds the same path; the URL is the same |
| Publication issue files (generated and uploaded), legacy publication files | files under `repo/publications/…` | rows in the store; same paths, same URLs |
| Superseded editions of an issue | files under `publication-archive/`; the audit row holds absolute disk paths | rows under `archive/publications/…`; the audit row holds store paths; never served by `/rest/repo/file` |
| Thumbnails, generated map images, previews | files next to the originals, or under `publication-preview/` | files in a cache tree on the volume, made on a miss, expiring where they expire today; served directly, not through `/rest/repo/file` |
| Edit sessions (`repo/temp/`) | files, swept hourly after a day | temp rows, swept hourly a day after they were made |
| Batch execution files and logs, AtoN tiles, OSM tile cache, Lucene index | the volume | the volume, as a cache: rebuilt or expiring |
| PostgreSQL; Keycloak's database | the chart's Postgres on a PVC | the same, holding every durable byte; the hotel when the separate project moves it (D11) |
| The application's PVC (`app.persistence`) | 1Gi default, 5Gi on staging, 20Gi in the parked prod values; holds the only copy of every attachment | the same volume, holding caches and work files only; nothing on it is backed up |
| Sea-chart (WMS) tiles for the browser maps | fetched from the provider on every request; no server-side cache anywhere | cached on the application's volume by the backend's WMS proxy, for logged-in users (D10 as amended, [`sea-chart-tile-cache-plan.md`](sea-chart-tile-cache-plan.md)); never in the database, backed up by nobody |
| Backup | none exists; designed: a CronJob plus a file mirror | one thing to back up, the database: the devops design's CronJob without its file mirror until the hotel, the hotel's own backup after |

**What to implement first.** R0 (measure the trees, put the gate questions to the host) costs no code and
decides the sizes; R1 (the store and the move of the code to it) starts at once on a feature branch and
does not wait for the host. R2 (the cutover on staging) needs R1 released and each country repository on
that release. R3 builds the chart seams the later move needs and writes the handover; the move itself, with
the host's answers, the dump and restore and the restore proof, is the separate hotel project (D11), and
the old trees and the chart's Postgres are removed only after that proof.

---

## 2. Decisions for the owner

| | Question | Recommendation | Alternative, and why not |
|---|---|---|---|
| **D1** | How are bytes stored? | **Decided 2026-10-22: one `bytea` per file, stored out of line and uncompressed (`SET STORAGE EXTERNAL`), read whole, in one query (the sliced reads of the first text were removed on 2026-10-27 at the owner's decision: the files are a few MB at most, and the slicing was complexity for no gain; a row holds at most `RepoStore.MAX_SIZE`, 50 MB, the multipart limit); no chunk table, no large objects, no content sharing.** A row per path; its SHA-256 is kept for the import's verification (the ETag stays `updated` and size, §4.2). Copying a folder (an edit session's revision on save, a copied message, an archive entry) is an `INSERT … SELECT` inside the database: the bytes are written again but never cross the network (a move on save was weighed and set aside, §4.3). | Large objects (`oid`): the streamable kind PostgreSQL has, but a large object is not part of its row and is never unlinked by itself (the schema's standing rule against `@Lob`, `ScheduledMail.java:98-100`), logical replication does not carry them, and a stream holds a connection for as long as the client reads; at files under 50 MB (the proxy's cap) they buy nothing over a sliced `bytea`. A chunk table: not needed, the server slices an external `bytea` itself. Content-addressed sharing (one row per distinct content): the duplicate scan of the legacy production tree on 2026-10-22 found 92 MB of re-uploaded PDFs among 490 MB of uploads and publications, about a fifth, and 357 MB of duplicates among the 580 MB of generated map images, which are a cache and stay on disk (D2); 90 MB does not pay for a second table, a hash lookup on insert and an orphan purge. (An earlier draft claimed every edit copies a message's whole folder; it does not: an ordinary edit leaves the attachments where they are, `MessageRestService.java:344, 387`; only copying a message copies the folder, `:424`.) |
| **D2** | Where do derived files live? | **Decided 2026-10-22: on the application's volume, which becomes a cache that nothing backs up.** Thumbnails, generated map images, previews, the AtoN and OSM tiles, the Lucene index and the batch work files stay on the volume at `/niord-home`; the store holds uploads and publications only. The volume survives a deploy, so the caches stay warm; a lost volume costs a warm-up (the next views draw their images again and fetch tiles again). Thumbnails never expire (as today); a superseded map image is deleted by the regenerator (as today); the 7-day preview sweep that exists and has no caller (`IssuePreviewService.java:121-146`) is scheduled; the AtoN tiles get a nightly 24-hour age sweep (D6). | Derived rows in the store: warm and shared by replicas, but 580 MB of regenerable map images in the backed-up database, 357 MB of them copies, since D1 stores no content once. An `emptyDir`: no volume at all, but emptied at every pod replacement, which on staging is every deploy, and the first views after each one draw every image again with a cold tile cache. |
| **D3** | Lucene | **Decided 2026-10-22: nothing; it stays as it is.** The index is deleted and rebuilt from the database at every start (`messageIndexDeleteOnStartup` defaults to `true`, `MessageLuceneIndex.java:102-104`), so it is already a cache, and it stays on the cache volume (D2). R0 still measures the rebuild time on prod-sized data, as information for the Lucene retirement, not for a decision here. | Switching staging to the PostgreSQL index (`disableLucene=true`, `lucene-postgres-toggle.md`) as part of R2: a search-behaviour change inside a storage change. Retiring Lucene here: its own plan (`lucene-todo-removal.md`), blocked on a sign-off of search differences. |
| **D4** | Batch execution files (the uploaded data file's copy, the handlers' logs) | **Decided 2026-10-24: stay on the cache volume, as today.** JBeret's job repository is in memory (no `jberet.properties`, so the default `InMemoryRepository` [I, from the JBeret 3.1 bytecode]), so nothing of an execution survives a restart today, and the folders of earlier executions become unreachable after one (`BatchService.java:407-418` picks the newest row for a colliding instance id). The folders expire after `batchFileExpiryDays` (10) as today. Moving them into the database would preserve what the system itself already forgets. | The data file and logs as rows: a `BatchData` of bytes plus a log row per handler, and sixteen upload services and every reader changed to read a stream instead of a file. Buys an audit copy of what a sysadmin imported, which the message history already records for messages. Can be done later without undoing anything here. |
| **D5** | How does an existing deployment get its files in? | **Decided 2026-10-24: one import job, run once per deployment inside a maintenance window** (§4.8): a SYSADMIN starts `repo-import` with the directory of the old tree; the job walks it, inserts rows, verifies and reports. Until it has run, the deployment serves no files, which is why the window exists. A dump and a volume snapshot taken just before it are the rollback inside the window (R2's runbook takes a copy of the database instead, §4.8); after it, the way back loses what was written since (§4.8). | A read-through fallback (a path not in the store is read from disk and imported on use): no window, but it is the copy-on-use pattern the code/data/configuration plan just removed (R-CDC4), and it keeps the disk code path alive. A runtime switch between a file store and a database store: two implementations to test and a flag to forget. |
| **D6** | AtoN tiles | **Decided 2026-10-24: `${niord.home}/aton-tiles` on the cache volume, with a nightly 24-hour age sweep.** They are a cache with a 24-hour TTL (`AtonTileRestService.java:74`) that happens to sit inside the repository root today (`repo/aton_tiles/`); with no repository root on disk they need a folder of their own. | Rows in the store: a cache with no size bound written by an anonymous endpoint, in the backed-up database. |
| **D7** | A proxy cache for `/rest/repo/file/` on the public site's nginx | **Decided 2026-10-24: later, if measured.** Serving a file becomes one query per MiB instead of a `FileSystemResource` stream from disk; the public site's readers fetch the same few issue PDFs. The `Expires`/`ETag` headers stay as they are, and a `proxy_cache` in `fe/ui-apps/info/nginx` is a small, separate change once R2 shows the numbers (the same change as the backlog's *Three `proxy_cache_valid` directives do nothing*: the repository-file location names a 3-minute validity and no cache zone). The owner's concern on the day was not the PDFs but the map tiles, the WMS above all; those became D10 and R5 the same day: cached on the proxies' disks, never in the database. | Caching before measuring. |
| **D8** | How is the cutover window closed on staging? (§4.8) | **Decided 2026-10-24: by announcement, and the import's report read afterwards.** On staging the window's users are testers. What goes wrong inside it is found afterwards: a message copied in the window leaves references that resolve to no row, which the report lists; a publish, an amend or a replacing upload in it shows as a mismatch when the import runs with `verify`, the old tree still holding the superseded edition; an issue deleted in it leaves its files behind as rows no issue names, harmless, as a cleared file is today (§3.1). The first two are redone by hand after the window. The owner's ruling: staging is a test environment, to be done with as we please. Production has no window at all: it is not running on these clusters, and its files arrive through the same import job from the legacy installation's tree while it is set up, before it serves anyone (§6). The fourth review's P1 had asked for a technical closure on staging (block the write and copy flows until the import and its verification are done, or keep the application unreachable); not taken. | A maintenance response on both proxies for the window: nothing can go wrong in it, at the price of one more values change to roll out and back in each country and a sysadmin who reaches the import through a port-forward. |
| **D9** | PUB-DEL1 and EXPORT-THUMB1, whose lines R1d and R1c rewrite | **Decided 2026-10-24: fix both in the rewrite, an IT each.** `IssueDeleteService` deletes an issue's rows only when no legacy `Publication.repoPath` names the same folder, and the export takes a message that has a thumbnail as well as one that has attachments (`MessageExportService.java:97-104`). Rewriting the two lines with their defects would mean rewriting them again. | Carry both unchanged, as `be/CLAUDE.md`'s "do not alter behaviour that the task does not name" would have it, and leave the two entries open. |
| **D10** | Where are the sea-chart (WMS) tiles cached? | **Decided 2026-10-24: never in the database. Amended 2026-11-10: on the application's volume, by the backend's WMS proxy, for logged-in users only; not on the proxies' disks.** The owner on 2026-10-24: "this should be cached on a proxy drive; it is just that we do not need it in the database." A WMS tile is a cache like the thumbnails and the OSM tiles: made again on a miss, backed up by nobody, so it belongs on a disk under this plan's own rule (§1). That stands; what changed is which disk. On 2026-11-10 the owner settled that the provider's licence allows a cache, required that only logged-in users get a cached tile, which takes the public site out, and chose the application's volume for the rest. The reasons: the chart's proxy pod has no volume, so its cache would go with every replacement of the pod, while the application's volume survives a deploy (D2); the login check would exist a second time, in nginx configuration that no test of this repository runs, and a cached `auth_request` answer under nginx's default key is kept per tile, not per caller; and `auth_request` needs backend code after all (an endpoint that honours `wmsProtected`, and an error status where the servlet redirects to a blank image with a day-long cache header that nginx would keep). The cache is read and written only for a request that has a user, whatever `wmsProtected` says. The design, the decisions taken on 2026-11-12 (its measuring, its cap, off by default) and the steps are in [`sea-chart-tile-cache-plan.md`](sea-chart-tile-cache-plan.md), which replaces R5. | In the database: a cache with no bound, written on anonymous requests, in the backed-up store, which is what this whole plan removes from it. On the proxies' disks, the decision of 2026-10-24: see the amendment. The text of that day set aside "the backend servlet's memory or the application's volume" as "one more cache inside the application for what nginx does with three directives", and because the public site's tiles never pass the backend; the first did not hold once the login check was counted, and the second fell away with the public site. |
| **D11** | Is the move to the hotel part of this plan? | **Decided 2026-10-27: no. This plan runs on the database the deployments use today, the chart's Postgres, and finishes everything that does not need the hotel; the move is a separate project.** What this plan still does for that project: the chart seams the move needs (R3: a Keycloak on a database it did not create with a secret of its own, JDBC URL parameters and a CA for TLS), the handover design (§4.9), and the questions the host has to answer (§5), which may be sent in R0 since they cost nothing and the answers take time. What it leaves to that project: the host's databases and roles, the dump and restore of both databases, the values merges, the restore proof, the removal of the chart's Postgres and of the old trees. Until then the database on the chart's PVC holds every durable byte, which is the point: one thing to back up, where today there are two and neither is. | Keeping the move in this plan: it waits on the host, and everything before it does not. |

---

## 3. What is on disk today

Every path is relative to the repository root unless marked. The root is the setting `repoRootPath`
(default `${niord.home}/repo`, declared twice: `RepositoryService.java:90`, `PublicationPathService.java:61`).
`<h>/<hh>` are the uid's first character and its next two, which `UidUtils.uidToHashedFolderPath` makes
(`UidUtils.java:78-85`; the name says hash, the code says substring);
`<rev>` is the message's or publication's revision.

### 3.1 Durable: the only copy, moves into the store

| Path | What | Written by | Rows that refer to it |
|---|---|---|---|
| `messages/<h>/<hh>/<uid>/<rev>/<file>` | attachments and images embedded in message HTML | the copy from the edit folder on save, `RepositoryService.updateRepoFolderFromTempEditFolder` (`:771-803`), from `MessageRestService` create/update and `BatchMsgArchiveImportWriter.java:71-72` | `Attachment.path` (`Attachment.java:52`; the file-name segment stored URL-encoded, `MessageRestService.java:771`), `Message.repoPath` (`:105`), HTML in `MessagePartDesc.details` holding `"/rest/repo/file/messages/…"` |
| `messages/<h>/<hh>/<uid>/<rev>/custom_thumb_<size>.png` | an editor's custom map thumbnail | `MessageMapImageRestService` PUT/POST into temp (`:311-379`), `CustomThumbnailScaler.scale` (`:52`); committed by the same copy | `Message.thumbnailPath` (`:102`) |
| `publications/<publicId>/<file>` | a publication issue's PDF per language, generated at publish (`IssuePublishService.java:984-1090`) or uploaded (`IssueFileService.java:80-176`); unpublished issues included | the publish step, the upload | `PublicationIssue.repoPath` (`:114`), `PublicationIssueDesc.filePath`/`fileName`/`fileSource` (`:54-89`) |
| `publications/<h>/<hh>/<pubId>/<rev>/<file>` | legacy `Publication` files; imported issues point at them verbatim (`LegacyIssueTranslation.java:158, 318-323`) | `PublicationService.updatePublicationFromTempRepoFolder` (`:590-599`) | `Publication.repoPath` (`:152`), `PublicationDesc.link` (a full `/rest/repo/file/…` URL), `.fileName` |
| `${niord.home}/publication-archive/<publicId>/<lang>/<millis>-<file>` (outside the root) | the superseded file, copied before a publish, an amend or a replacing upload overwrites it; kept forever | `IssuePublishService.archiveExistingFiles` (`:805-827`) and `IssueFileService.archiveExisting` (`:255-271`); `IssueArchiveService.java:144-157` is the path check on read | `IssueAuditEntry.archivePath` (`:125-126`): a JSON array of **absolute** paths; `IssueArchiveService.resolve` (`:144-157`) refuses one outside the current root |

Nothing ever deletes a committed message folder (the status DELETED is a soft delete). `IssueDeleteService`
removes an issue's folders (`:310-367`) and refuses PUBLISHED issues; for an imported issue that folder is
the legacy publication's own (`LegacyIssueTranslation.java:158`), so deleting the issue deletes the legacy
publication's files with it (PUB-DEL1 in `be/docs/potential-errors.md`). The shared folders already exist
on every migrated deployment, so an import-time copy would not separate them; D9 settles it in R1d's
rewrite: the delete leaves a folder alone when a legacy `Publication.repoPath` names the same path.
Clearing an issue's file nulls the columns and leaves the bytes, still anonymously served
(`IssueFileService.java:185-201`); the same for the old file of a re-upload under a new name.
A file write happens inside the database transaction, so a rollback leaves the bytes behind
(`IssueFileService.java:88-94`): with the bytes in the same transaction, that goes away.

### 3.2 Derived: made again on a miss

| Path | What | Expiry today |
|---|---|---|
| `<folder>/<name>_thumb_<32\|64\|128>.<ext>` next to any image, temp included | thumbnails, made by the **anonymous** `GET /rest/repo/thumb` (`RepositoryService.java:370`, `ThumbnailService.java:178-205`, vips or Java) | never |
| `messages/<h>/<hh>/<uid>/map_<size>_<portrayalVersion>.png` (legacy `map_<size>.png`) | generated map images (`MessageMapImageGenerator.java:245-260`; freshness decided by file mtime against `Message.updated`) | the regenerator deletes a superseded one 16 minutes later (`MapImageRegenerator.java:392-407`) |
| `${niord.home}/publication-preview/<publicId>/<lang>/<millis>-<file>` | preview PDFs (`IssuePreviewService.java:68-80`); found by listing the folder, no row | a 7-day sweep exists and has no caller |
| `aton_tiles/<z>/<x>/<y>.png` (inside the root) | AtoN tiles (`AtonTileRestService.java:108-162`) | regenerated when older than 24 h; no size bound |
| `${niord.home}/tile-cache/` | OSM base-map tiles (`TileDiskCache`, `MapTileSettings.java:99`) | 200 MB cap, swept nightly (`TileFetcher.java:165, 833`) |
| `${niord.home}/message-index/` | the Lucene index | deleted and rebuilt at every start |

### 3.3 Transient: work in flight

| Path | What | Expiry today |
|---|---|---|
| `temp/<uuid>/<rev>/…` | an edit session: empty at the start of an edit (`MessageRestService.java:344`, `PublicationService.java:590`), the source message's folder copied in only when a message is copied (`:424`); then uploads and custom thumbnails; the extracted message archive of an import (`BatchMsgArchiveImportReader.java:112-149`) and of a batch set (`BatchSetService.java:113-145`) | `DELETE /rest/repo/file` (temp only, EDITOR); the hourly sweep deletes every entry whose own mtime is older than a day (`RepositoryService.java:697-722`). A copied message's folder keeps the source's dates (`copyDirectory(…, true)`), so its edit folder can be swept at the next hourly run (REPO-SWEEP1 in `be/docs/potential-errors.md`) |
| `${niord.home}/batch-jobs/<job>/execution/<y>/<m>/<n>/` | the uploaded data file's copy and one log file per handler (`BatchData.java:114-145`, `AbstractItemHandler.java:66-97`) | `batchFileExpiryDays` = 10 (`BatchService.java:616-657`) |

The watched folders (`batch-jobs/<job>/in/`, `batch-sets/`) are gone since `code-data-config-plan.md` C4.2b:
no folder on the server starts a job (`BatchService.java:71-73`).

### 3.4 Not files of the application

| What | Where | After |
|---|---|---|
| PostgreSQL's data | the chart's Postgres PVC (2Gi default, 10Gi staging, 50Gi parked prod) | the same PVC, now holding every durable byte, until the separate hotel project (D11) |
| Keycloak's database (`keycloak.mode: production`, staging and prod) | a `keycloak` database on the same Postgres, created by an init container (`keycloak/deployment.yaml:47, 133-136`) | a second database in the hotel, provisioned by the host (§4.9) |
| `${niord.home}/niord.json` | an optional settings overlay read at start (`SettingsService.withServerOverlay`, `:352-374`); nothing provides it in Kubernetes | unchanged |
| SECOM keystores | Kubernetes Secrets at `/run/secrets/secom` (`configmap.yaml:73-80`); the renewal writer patches the Secret; the file writer is for VM installs | unchanged (§8) |
| The administration app | `/app/web` in the image | unchanged |

### 3.5 A finding on the way: the generic image does not use the volume

`niord.home` is resolved by `SettingsService.peek` from a system property `niord.home`, an environment
variable literally named `niord.home`, the `Setting` row, or else the settings files' default
(`SettingsService.java:573-600`); the chart's `NIORD_HOME` and its Spring property `niord.home`
(`app/deployment.yaml:87-88`, `app/configmap.yaml:47`) are read by nothing for this key. Start-up adds the
row where the database lacks it (`addMissing`, `:219-276`) from the first settings file
`DefaultSettings.read` finds (`DefaultSettings.java:109-152`): a country's `defaults/country/settings.json`,
else a transitional root `niord.json`, else the base product's `defaults/base/settings.json`. `niord-dk` and
`niord-gl` set `niord.home` to the token `${NIORD_HOME}`, which the expander resolves from the environment:
in a root `niord.json` on their mains, from which the 4.0.66 images on staging are built, and in
`defaults/country/settings.json:5` on their C6 branches. So a DK or GL staging gets a row that resolves to
`/niord-home`, and its volume does hold its files (the owner, 2026-10-24). The exposure is the generic
`niord-app-en` image, which has no country file and takes the base product's `${user.home}/.niord`
(`defaults/base/settings.json:5`), the container's own filesystem. A country can no longer leave the key out
by accident: its build's settings check refuses a file without it (`missing-key`, `DefaultSettingsChecks`)
unless the key is listed in `useBaseDefault` with a reason. Recorded as SETTINGS-6 in
`be/docs/potential-errors.md`, next to PORT-15 (an unresolvable token). The fix needs no code: the chart
passes an environment variable named exactly `niord.home`, as it already passes `authServerUrl`
(`app/deployment.yaml:109-110`), which `peek` consults before the row; the same variable decides where the
server's own `niord.json` is read (`SettingsService.java:128-135`, `:179-195`). The JVM starts in exec form,
with no shell in between (`k8s/docker/Dockerfile:58`), so the dotted name reaches it; a `dash` wrapper would
drop it, and a check in the pod uses `kubectl exec -- env`, not `sh -c env`. The Settings page goes on
showing the row's value while the variable wins, as it does for `authServerUrl`. The two alternatives were
weighed and set aside: an upper-snake lookup in `peek` would widen the environment override to every setting
key, and `-Dniord.home` in `app.javaOpts` is lost as soon as an environment sets its own Java options. The
Spring key `niord.home` in the ConfigMap and in `niord-core-defaults.yml:78` is read by nothing and goes
(R1f). Fixed in code on 2026-11-03 instead (the owner's ruling; the E-fixes): `niord.home`
follows `NIORD_HOME` when no `niord.home` property or variable is set, so the generic image's home is the
volume without R1f's variable, which R1f may still pass but no longer needs for SETTINGS-6.

---

## 4. Target design

### 4.1 The store

One Liquibase changeset, `V009__repo_files.sql` (id `niord:009-repo-files`; `V006` to `V008` exist on
`main`), with its include line in `db.changelog-master.yaml` and its checksum pinned in
`ChangelogChecksumPinTest`, never edited once applied; schema only (the rules in `code-data-config-plan.md`:
no content, no deleted rows, no changed values). It takes the form of `V006` and `V007`: quoted mixed-case
names, named constraints, `timestamp(6) with time zone`.

```
RepoFile   path varchar(1024) COLLATE "C" primary key,   -- the repository path, decoded, forward slashes;
                                                          -- no surrogate id (the sample's capture and reset
                                                          -- know only Hibernate's "<table>_SEQ" sequences)
           folder varchar(1024) NOT NULL,                 -- the parent path, for listing; indexed
           name varchar(255) NOT NULL, size bigint NOT NULL,
           sha256 char(64),                               -- the import's check; not part of the ETag (§4.2)
           created timestamptz NOT NULL, updated timestamptz NOT NULL,   -- updated feeds the ETag
           expires timestamptz NULL,                      -- set on a temp row only: created + 1 day
           data bytea NOT NULL
             ALTER COLUMN data SET STORAGE EXTERNAL       -- out of line and uncompressed: a query that does not
                                                          -- name data never reads it; PDFs, PNGs and JPEGs are
                                                          -- compressed already
           index (folder), partial index (expires) WHERE expires IS NOT NULL
```

One table. With `STORAGE EXTERNAL` the bytes live out of line, so a query that does not name `data` never
reads it and an update of the other columns never rewrites it: listing a folder or checking a path reads
metadata only without a second table, and there is no foreign key, no join in the read and no id to
carry over to a content row in every folder copy. The primary key is on `path` under the `C` collation,
which serves both `=` and the prefix `LIKE` the folder operations use.

The times are `timestamptz` because `updated` is an instant compared with file times (the import's mtime,
the cached thumbnail's) and printed into the ETag: through a `timestamp` column the millis would move with
the JVM's zone, which `be/CLAUDE.md` forbids ("nothing may depend on the JVM default zone"). The content
type is not stored: `GET /file` derives it from the name, as it does today (`FileTypes.getContentType`, by
extension, `RepositoryService.java:246`), so a client's multipart header never decides it.

A temp row is one whose path begins with `temp/`; `expires` marks it, and no `kind` column repeats what
the path says. Folder copies and deletes are prefix matches on `path` (`LIKE 'temp/<uuid>/<rev>/%'`,
nested for an edit session's revision folder), which a plain btree under a non-C collation does not
serve: the primary key's `C` collation does, so it is the only index on `path`. The prefix is escaped (`_`
and `%` are wildcards to `LIKE`), although every folder a caller names today is built from a uid or a
fixed name.

**The store is JDBC-only: the table has no entity.** A JPA `byte[]` would put the whole file on the heap
at insert, and a JDBC prefix delete or upsert beside an entity would leave stale instances in the session.
So `RepoStore` runs on a `JdbcTemplate` over the same `DataSource` (the first use of `JdbcTemplate` in this
code; the two other tables without an entity, `DefaultsLedger` and `BootstrapState`, are written through
the `EntityManager`'s native queries and `Session.doWork`, which suit a write inside a transaction but not
a read outside one): a write joins the JPA transaction around it through Spring's transaction
synchronisation, a read borrows a connection for milliseconds. JDBC writes the bytes (`setBinaryStream`
with the length, §4.3) and reads a row whole, `SELECT data FROM "RepoFile" WHERE path = ? AND updated = ?`
(the pin of §4.3). A row holds at most `RepoStore.MAX_SIZE`, 50 MB, the multipart limit; the import reports
a larger file and leaves it on the tree.

### 4.2 Paths, validation and the REST contract

`/rest/repo/*` keeps every endpoint, URL, guard, header and answer it has today (the 28 cases of
`RepositoryRestServiceITTest` are the contract; of the 33 in `RepositoryServiceITTest`, about 15 test the
`Path` API this plan deletes and go with it): `GET /file`,
`GET /thumb`, `GET /list`, `GET /new-temp-dir` public; `POST /upload`, `POST /upload-temp` and
`DELETE /file` EDITOR and confined to `temp/`; the `nosniff`/`sandbox`/disposition headers
(`fileSecurityHeaders`), the weak ETag `W/"<updatedMillis>_<size>"` (today `<mtime>_<size>`, the same wire
format), the 5-minute `Expires`, the plain-text 404 that never echoes the path, the listing filter that
hides thumbnails and map images. The frontends change nothing: `fe/shared-html/repo-path.ts`,
`fe/ui-apps/info/src/app/core/services/repo-url.ts` and the admin app's URL builders keep building the
same URLs, and the public site's nginx keeps proxying `GET /rest/repo/file/`.

Path validation stays lexical and keeps its rules, now on strings instead of `Path`: normalise `.` and
`..`, refuse an absolute path and one that climbs out, answer a path outside the repository exactly
like a missing file. `isBelowFolder` exists already in that form (`RepositoryService.java:668-683`);
`resolveInRepo`, `validateTempRepoPath` and `validateRepoFolderPath` become its siblings and return the
normalised repository path. `getHashedSubfolder` has no caller in main code and is deleted; `getRepoRoot`,
`getTempRepoRoot` and the `Path` overloads go. One answer changes on purpose: `DELETE /file` today
repeats the requested path in its "not found" body and names the full disk path in its success body;
the store's version names the repository path in both.

**Served roots.** `/rest/repo/*` serves `messages/`, `publications/` and `temp/` only. `archive/`
(§4.6) answers like a missing file from every endpoint, listing included. The archive endpoint
(`GET /rest/publication-issues/issue/{publicId}/archive/{auditEntryId}/{lang}`, ticketed ADMIN) is its
only reader, as today.

### 4.3 Streaming

**Out.** `GET /file` returns `ResponseEntity<Resource>` with a `RepoFileResource` (`AbstractResource`):
`contentLength()` and `lastModified()` from the row, `getInputStream()` the row's bytes, read whole in one
query on the `JdbcTemplate`, never the `EntityManager`, outside any transaction (the controller is not
transactional; the query auto-commits and gives its connection back at once). The stream skips by offset,
so Spring MVC's `Range` handling (a `Resource` body on a GET with a `Range` header is written as resource
regions, 206) works on it; browser PDF viewers and the audio player use `Range`, and an IT pins a 206 with
the right bytes. Memory per download is the file (a publication PDF of 2 MB at most today, most under
100 KB; a row holds at most 50 MB). The read pins the row's `updated` (`WHERE path = ? AND updated = ?`,
§4.1), so a file replaced in place or deleted between the look-up and the read is not served under the
look-up's length and time. A HEAD answers the headers without the read. (The first text read a row in
1 MiB slices with `substring`, growing from 64 KiB, to bound memory for a large file; the owner removed
it on 2026-10-27 as complexity for no gain at these sizes.)

**In.** Every write knows its length before the content row is written, so the driver streams it
(`setBinaryStream` with the length) and no whole file reaches the heap that was not there already. A
multipart upload to the repository (`POST /upload-temp`, the attachment endpoints) is already a file of the
servlet container's (Spring Boot's multipart threshold is 0) and is written as it is, with the part's size;
an issue file and a publish's generated PDF are already a byte array (`IssueFileService.java:82`,
`IssueRenderService.java:170`); only a stream of unknown length, an extracted archive entry, is spooled first,
to a file under `java.io.tmpdir` (the container's writable layer: the chart sets no `readOnlyRootFilesystem`
and no ephemeral-storage limit). The SHA-256 is computed on the way; the row is written as an upsert on
`path` (`INSERT … ON CONFLICT (path) DO UPDATE`), bytes included, and a spool, where there is one, is
deleted. The row joins the caller's transaction where there is one (an issue's file and its columns commit or roll back
together, which closes the rollback gap of §3.1); the upload endpoints of `RepositoryService` write in a
transaction of their own. No `spring.servlet.multipart` limit is configured anywhere, so Spring Boot's
defaults apply (1 MB per file, 10 MB per request; UPLOAD-1 in `potential-errors.md`); R0 confirms what an
upload of 2 MB does today. The limit is very likely live rather than inferred: the attachment upload takes
Spring's multipart request, so Boot's 1 MB default applies to every upload today. The fix is two keys
(`spring.servlet.multipart.max-file-size` and `max-request-size` in `niord-core-defaults.yml`), set to what
the proxy allows (`client_max_body_size 50m`, `nginx/configmap.yaml:28`), and it is part of this plan: R1f
sets them, with the texts that name the 1 MB default (the owner, 2026-10-24, on the fourth review's advice
to make it a pull request of its own: part of the plan). Nothing in front of the chart's nginx sets a smaller
body limit, but Traefik's default read timeout of 60 seconds bounds an upload by time (50 MB needs about
0.85 MB/s) [I].

**Thumbnails and map images.** The generators need a file (vips is a process; `ImageIO` wants a
stream): on a miss, read the source from the store to a spool file, generate into the cache tree on the
volume (`${niord.home}/cache/<repository path>_thumb_<size>.<ext>`,
`…/<message folder>/map_<size>_<version>.png`: today's names, mirrored under one cache root instead of
next to the originals), delete the spool. Freshness: today a thumbnail is reused when it is at least as
new as its source file (`ThumbnailService.java:188-191`); a spool file is always new, so the cached
thumbnail is compared with the row's `updated` instead, spooled only on a miss, and given `updated` as its
mtime. A generated map image keeps its mtime set to `message.updated`, which is what `MapImageFiles`
compares. The two endpoints change where the derived file no longer sits: `GET /rest/repo/thumb` serves
the cached thumbnail of an image directly instead of redirecting into `/rest/repo/file/`, and keeps its
redirect to the file-type icon for everything else (`RepositoryService.java:389-400`, pinned by the IT);
`GET /rest/message-map-image/{uid}.png` serves the generated image directly. A direct response carries
the `nosniff`/`sandbox`/disposition headers `/file` sends today (today `/thumb` is a bare 307) and a weak
ETag of mtime, size **and file name**, since every map image of one message carries the same mtime and
time plus size would barely tell the old image from the new one. Its `Expires` is the endpoint's own: the
thumbnail keeps `/file`'s 5 minutes; the map image keeps the two the endpoint puts on its redirect today, 10
minutes for a current image and 60 seconds for one drawn with an earlier portrayal
(`MessageMapImageRestService.java:99-100`, `:225`, `:239`), so that browsers ask again soon after the
background redraw. With the image itself as the response, that expiry has to sit on the image, or browsers
would keep the old portrayal five times longer.

**Old links to derived files keep working, and `/file` has no fallback.** Message HTML written years ago
may embed a thumbnail or a map image by its path (`<img src="/rest/repo/file/messages/…/chart_thumb_64.png">`;
R0 counts them). The import collects every path the HTML names before it classifies anything (§4.8), so
such a file becomes a row like any upload, frozen as it was on disk (which is what the link serves today),
and the link resolves to a row. A path with no row is a 404, as a file that is not on disk is today. A
fallback that drew a derived name on a miss was proposed by the fourth review and dropped by the fifth:
the public site's nginx forwards `/rest/repo/file/` to anonymous readers, so it would have let anyone have
a thumbnail or a map image drawn, OSM tiles fetched included, under any made-up `_thumb_<n>` or
`map_<n>[_<version>]` name (the generator draws one size at the current version), with nothing to limit
how many; and after the import nobody but a bookmark would reach it, since the templates link the
endpoint, not the file (`bootstrap/templates/messages/message-support.ftl:488`). No stored HTML is
rewritten, and the public site's nginx needs no change.

**Custom map thumbnails are not derived.** An editor's custom thumbnail is the only copy of itself
(`Message.thumbnailPath`, §3.1), written into the edit folder by the PUT and POST of
`MessageMapImageRestService` (`:311-379`) through `CustomThumbnailScaler`. It is written as a temp row in
the store, committed by the save like any upload, and the map-image endpoint's custom branch (`:169-173`)
redirects to it under `/rest/repo/file/`, or streams it, as a store file.

**Copy on save, as today.** The save (`updateRepoFolderFromTempEditFolder`, cases 1 and 2,
`RepositoryService.java:771-803`) copies the rows a prefix match on `path` selects (§4.1) with one
`INSERT … SELECT … ON CONFLICT (path) DO UPDATE`, computing `path`, `folder` and `name` afresh for each
copied row (a row that kept its temp `folder` would exist at its committed path and be missing from
`GET /list`, which reads `folder`), keeping each row's `updated` (as `copyDirectory(…, true)` keeps a
file's date), giving the committed rows no `expires`, and replacing a committed row at the same path (as
the copy overwrites a file). The upsert, not a delete and an insert, is what keeps today's last-write-wins
when two sessions save into one revision folder at the same time: under `READ COMMITTED` the second
transaction's `DELETE` would wait on the first's row and then delete nothing, its snapshot would never see
the first's new row, and its `INSERT` would fail on the primary key; nothing upstream serialises the two
(`Message` has no `@Version`, the save takes no lock). An IT saves twice, overlapping, and finds the second
save's bytes. The temp rows stay until they expire. Copying a message
(`createTempEditRepoFolder(copyToTemp)`) and archiving a superseded edition are the same statement; a
message's copy into temp gets `created` now and expires a day later, whatever its source's dates (§4.4). The
bytes are written again inside the database and never cross the network (D1).

A move on save (`UPDATE "RepoFile" SET path = …`) was proposed by the second review and is set aside: it
assumed a temp folder is used once, and it is not. The editor keeps its working copy, temp folder included,
when the reload after a save fails, also after a save that committed (`editor-facade.ts:1398-1408`); its
next upload of a name it uploaded before (a second `chart.pdf`) then finds the temp folder empty, takes the
same name again (`MessageRestService.java:848-863` looks only in temp) and, at the next save, collides with
the committed row or replaces the first file's only copy, where today it becomes `chart 2.pdf`. Two sessions on one message get
the same revision (`Message.java:344`) and save into the same folder, last write winning
(`fe/docs/features/editor.md`), which a plain `UPDATE` turns into a unique violation on
`custom_thumb_256.png` or any shared name. And an archive that lists a message twice extracts it into one
temp folder (`BatchMsgArchiveImportReader.java:118-182`), which a move would empty for the second. The copy
costs an upload's bytes twice, with their WAL, and dead TOAST rows after the sweep, which autovacuum
reclaims; at the size of the uploads (§11) that is the price of today's semantics. `moveRepoFolder` has no
caller and goes.

### 4.4 Listing, unique names, the sweep

`GET /list/{folder}` is `WHERE folder = ?` plus today's name filter (`isListedFileName`). A unique name
in a folder (`getUniqueFile`: `chart.pdf`, `chart 2.pdf`, …) is a lookup per candidate, as it is a
`Files.exists` per candidate today.

One `@Scheduled` sweep, hourly, replaces today's temp sweep: `DELETE FROM "RepoFile" WHERE expires < now()`
(a temp row expires a day after its creation; today's sweep goes by each entry's own mtime, which a copied
message's folder inherits from its source, REPO-SWEEP1, so `created + 1 day` is also the fix for that).
The message-archive import deletes its own temp rows when the job ends (`BatchMsgArchiveImportReader`), so
a day's extracted archives never sit in the backed-up database and the sample's capture never holds them.
The cache tree on the volume keeps the sweeps the disk has today (the OSM
tiles' 200 MB cap, the batch folders' 10 days, the regenerator's deletion of a superseded map image) and
gains the two it lacks: the preview TTL of 7 days, whose sweep exists and has no caller, and a nightly 24-hour
age sweep of the AtoN tiles (D6; `AtonTileRestService.sweepTiles`), their only bound. A thumbnail of a temp upload goes with the temp row's sweep, by path.

### 4.5 The anonymous writer

`GET /rest/repo/thumb` makes a thumbnail on a miss for any caller, today next to the original, after
this in the cache tree. The work is bounded as today (two threads, a queue of 100,
`ThumbnailService.java:init`); the files are bounded by three sizes per image. It stays an anonymous
write to the cache volume, as the generated map images are (`GET /rest/message-map-image/{uid}.png`),
and is listed in §7 so that nobody is surprised. That endpoint draws for any message uid with no status
check, anonymously (`MessageMapImageRestService.java:71`, `@PermitAll`; `snapshots.snapshot(uid)` reads
the message whatever its status), so a draft's geometry can be seen as a picture by anyone who knows its
uid; a uid is a UUID, so the exposure is small. The owner accepted it on 2026-10-24 and had it noted
permanently: the endpoint's class Javadoc says what is exposed, why it is accepted (the uid cannot be
guessed, the public site never calls the endpoint, the administration application calls it signed in), and
that whoever changes one of those facts adds the status check. It is not an open item.

### 4.6 Publications

Issue files and legacy publication files already live under the repository root, so they ride on §4.1
to §4.3 with their columns unchanged. Two things move:

- **The archive** (`publicationArchiveRootPath`) becomes the store folder
  `archive/publications/<publicId>/<lang>/<millis>-<file>`, never expiring, never served by
  `/rest/repo/*`. `IssueAuditEntry.archivePath` holds store paths from then on; the existing absolute
  paths are rewritten by the import job (§4.8), which knows the old root, and only after it has found and
  stored each file. `IssueArchiveService.resolve` keeps refusing a path outside `archive/`, and the archive
  endpoint, which today reads the file whole from disk (`PublicationIssueRestService.java:958`,
  `Files.readAllBytes`), streams it from the store like any other file.
- **Previews** stay files, on the cache volume (`publicationPreviewRootPath`, default
  `${niord.home}/publication-preview`, today's layout), found by listing the folder as today; the 7-day
  sweep that exists and has no caller is scheduled. The preview endpoint (ADMIN, `no-store`) is the
  reader, as today.

The two path settings `repoRootPath` and `publicationArchiveRootPath` stop being declared in code; their
rows stay inert until an operator removes them by hand (`code-data-config-plan.md` D2 allows no
retirement changeset); meanwhile they leave the Settings page, and `set` refuses them
(`SettingsService.java:457-464`, `:680-683`). Both are in the generated settings catalog
(`defaults/base/settings-catalog.json:747`, `:795`), which is regenerated, the key count
`SettingsCatalogCurrentTest` pins going from 73 to 71 (`:87`); `publicationPreviewRootPath`'s description
("MUST NOT be under repoRootPath") changes with them. No settings file of the base product or a country sets
either key. `PublicationPathService` keeps the preview root and loses the other two and the containment
check in `init`, which has nothing left to compare.

### 4.7 The home that remains

`NIORD_HOME` keeps existing, on the volume the chart has today, for what is a cache or work in flight:
`cache/` (thumbnails and map images, §4.3), `publication-preview/`, `message-index/` (D3),
`tile-cache/`, `aton-tiles/` (D6; today `repo/aton_tiles`), `batch-jobs/` (D4), `niord.json` if a
deployment provides one; and, since D10's amendment, `wms-tiles/`, the sea-chart tiles' cache, under a cap
of its own. Nothing durable is on it any more, so nothing backs it up, and a deployment
that loses it loses a warm-up: `app.persistence` stays, its comment and `be/k8s/README.md` say what the
volume is now, and its size follows the caps and sweeps rather than a measurement: the OSM tiles' 200 MB, the AtoN
tiles' nightly 24-hour age sweep (no size bound), ten days of batch files, seven days of previews, the thumbnails and map images (580 MB
on the legacy tree, §11; they grow with the messages), the Lucene index. The dead Spring keys
`niord.home` and `niord.search.index-root` in `app/configmap.yaml` (`:47`, `:60-61`) go, the `fsGroup`
comment that names `repo/` (`app/deployment.yaml:39-41`) changes, and the deployment gains an
environment variable named exactly `niord.home` with the value `/niord-home`, as it has `authServerUrl`
today, so that the setting resolves to the volume whatever settings file the jar carries (§3.5). The deployment stays `replicas: 1`: the ReadWriteOnce volume is one reason
(`app/deployment.yaml:10`), JBeret's in-memory repository is the other, and neither is changed here.

### 4.8 The import job (the cutover)

A JBeret job `repo-import`, started by a SYSADMIN through a new `POST /rest/batch/repo-import` that R1e
adds (jobs start today from an upload, a batch set, the bootstrap command, or a JSON body for
`execute-javascript`, `BatchRestService.java:344-358`, which is the shape R1e follows; none from parameters
alone), so that `BatchData` records who ran it and the admin app's Batch page shows its progress. The runbook says how a
sysadmin calls it (a `curl` with a token). Its parameters:

- `dir`: the old repository root, readable by the pod (on staging the PVC, still mounted at
  `/niord-home/repo` during the window; for a tree copied from another installation, a mount under the
  home). Both `dir` and `archiveDir` must lie below `${niord.home}`, or the job refuses: otherwise this
  is a sysadmin-driven read of any server directory into the backed-up database.
- `archiveDir`: the old archive root, or none. Each file found becomes
  `archive/publications/<rest of its path>`; afterwards every `IssueAuditEntry.archivePath` entry that
  starts with `archiveDir` is rewritten to its store path and listed in the report with its old value
  (the way back of a rollback, below), and an entry whose file was not found is left as it is and
  reported.
- `verify`: compare the SHA-256 of a path that already has a row, instead of skipping it.

The job walks `messages/`, `publications/` and nothing else (`temp/` is skipped: edit sessions in flight
are lost at cutover, which the window announces; `aton_tiles/` is a cache; an unknown top-level folder is
reported and skipped), hashes each file as it streams it in (a file on disk knows its length, so nothing
is spooled), inserts the `RepoFile` row and its content when the path is missing, sets `updated` from the file's mtime so that every ETag stays what it was before the
cutover, and keeps a count of files, bytes, skipped, mismatched and unknown. It is idempotent and
resumable: a second run after an interruption does the rest.

**Derived files need the import's own rule, not the listing filter.** `isListedFileName` hides
`custom_thumb_256.png` too (its pattern `.+_thumb_\d{1,3}\.\w+` matches it; `RepositoryListingFilterTest.java:39`
pins that), and an editor may upload a file named like a thumbnail or a map image. A rule that skipped
what the listing hides would lose every custom map thumbnail. So the job first collects every path a
reference names (`Attachment.path` decoded, `Message.thumbnailPath`, `PublicationIssueDesc.filePath`,
`PublicationIssueDesc.link`, `PublicationDesc.link`, `IssueAuditEntry.archivePath`, and every
`/rest/repo/file/` URL in the message HTML and in a message's publication texts, `MessageDesc.publication`
and `.internalPublication`), and a referenced path is imported whatever its name (a custom thumbnail is
`custom_thumb_<mapImageSize>.png`, and `mapImageSize` is a setting, 256 by default; an upload an editor
named `chart_thumb_64.png` beside `chart.png` is an attachment). Of the rest, it imports every file, under
a revision folder or not (an issue's folder has no revision level, and a cleared issue's PDF is such an
unreferenced file, kept as the bytes are kept on disk today), and treats as derived only
`<name>_thumb_<32|64|128>.<ext>` (the `IconSize` values) where `<name>.<ext>` lies beside it in the same
folder, and `map_<n>[_<version>].png` directly in a message folder; a file the heuristic is unsure of is
an upload. Those it does not insert: it **moves them into the cache tree
on the same volume with their mtimes kept**, so the first views after the cutover are warm, and leaves a
file the cache tree already holds, which the window may have drawn (the regenerator draws on request). Without that, the public list PDF (a 180 s proxy timeout) and the publish step,
which embed `/rest/message-map-image/…`, would draw up to a thousand images with no cached tiles.

**Verification is from the rows' side, not `find | wc -l`.** After the run, every reference must resolve
to a row: `Attachment.path` (decoded), `Message.thumbnailPath`, `PublicationIssueDesc.filePath` and
`.link`, `PublicationDesc.link`, every `IssueAuditEntry.archivePath` entry, and every `/rest/repo/file/` URL in
the message HTML and in a message's publication texts (`MessageDesc.publication`, `.internalPublication`;
read since 2026-11-19). The job reports each reference that does not, which catches an encoding mistake as well as
a file the rule missed. An HTML link to a thumbnail or a map image resolves to a row like any other,
because the reference-first rule above imported that file as one; `/file` has no fallback to consult
(§4.3), so what the check reports is exact, and a derived file it reports is a file the tree did not hold. The
byte and file counts against `du -sb` and `find` stay as a plausibility check.

The runbook of a cutover is `devops-niord/runbooks/repo-files-cutover.md` (written in R2, 2026-11-10); what
follows is its design, and where the runbook simplified it, that is said. Both stagings sync automatically with
`prune` and `selfHeal` (`envs/staging/{dk,gl}/config.yaml:11-15`), which revert a `kubectl scale` and a
sync paused in the UI, so every step that changes the deployment is a merge request. Announce the window
and ask the editors to stay out of it; merge the release (the chart pin and `app.image.tag` of the country
image that carries the store, as `scripts/staging-bump.py` writes them) with `app.replicaCount: 0` in the
same merge request, so the old application stops and the new one cannot start yet; take the rollback point;
merge `replicaCount` back to 1, which the `Recreate` strategy starts with the volume still mounted. The
rollback point was first a `pg_dump -Fc` of the database and a CSI snapshot of the application's volume; the
runbook takes a copy of the database inside the same Postgres instead (`CREATE DATABASE … TEMPLATE`, exact
since the application is stopped, no file to move, the way back a rename; it needs the database's size free
on the Postgres volume, which the runbook checks with the trees' size before the window) and no volume
snapshot, since the import changes nothing on the volume but the derived files it moves into the cache
tree, which the previous image draws again. The import runs inside the application, so from
here the application is up and reachable and serves no files until the job is done: a reader of the public
site sees a notice without its attachments. An ordinary save in the window is harmless [I, from the code]:
it writes paths under a new revision, which the import then leaves alone. Three kinds of action are not.
Copying a message breaks the copy for good: its references are rewritten to the new message's folder, where no row
will ever be (`RepositoryService.java:763`, `MessageRestService.java:537`), and the copy dialog and an NM's
cancel-then-copy are routine. A publish, an amend or a replacing upload finds no row to archive, so the
superseded published edition is lost and the import then skips that path. Deleting an issue is undone by the
import, which brings its files back as orphans. On staging the window is closed by announcement and the
report read afterwards (D8); production has no window (§6). Start
`repo-import` with `verify` (so that a path written in the window shows as a mismatch) and wait for it; read its report of references that resolve to no row (above), and its counts
against `find … | wc -l` of the tree and the rows; smoke-test on the administration origin an attachment, a
thumbnail and a map image, from the public site an issue PDF and the list PDF (which embeds the map images:
the public site proxies neither `/rest/repo/thumb` nor `/rest/message-map-image/`), and an archive download;
end the window. The public site was not deployed on staging during R2 (2026-11-10), so its two checks waited
for it: since the same day it is (`docs/public-site-deploy-plan.md`), and the list PDF answers 200
`application/pdf` through the site's container on both stagings; the issue PDF waits until a staging has
published an issue (neither had one on 2026-11-10). A
sysadmin's token for the import's `curl` comes from a browser signed in to the stack: the shared Keycloak's
`niord-web` has no password grant, and the jump server reaches the stack through a port-forward only; the
runbook starts the import from that browser's console, with the token and the `NiordDomain` header.

**Rollback.** Inside the window: merge `replicaCount: 0` again (a revert of the second merge request), put the
copy of the database back by a rename, and revert the release.
After the window the way back loses data, and the runbook says so: every file written since exists only as
a row, which the previous image cannot read, and the archive entries the import rewrote name store paths,
which the previous image refuses (`IssueArchiveService.java:144-157`); the import's report lists each
rewritten entry with its old value, so that a rollback can set them back. The old `repo/` and
`publication-archive/` trees stay on the volume, untouched but for the derived files moved into the cache
tree (which the previous image would draw again), until the separate hotel project has seen a restore work
(D11); then they are deleted by hand, and the volume itself stays as the cache.

### 4.9 The hotel (the handover design for the separate project; the seams are built here)

This section is what the separate hotel project (D11) starts from. Of it, this plan builds the chart seams
(R3) and may send the host's questions (§5); the rest is that project's.

The chart has the seams since 2026-09-24 (`be/docs/cicd/README.md`): `postgres.enabled=false`,
`postgres.host` (which the host helper prefers whenever it is set, `_helpers.tpl:79-87`) and
`postgres.service.port`, `postgres.existingSecret` for the application. It lacks two things. One is a
Keycloak that uses a database it did not create and a secret of its own (today it uses the application's,
`keycloak/deployment.yaml:166-175`). The other is a seam for JDBC URL parameters such as `sslmode`, which
G5's TLS needs: the application's URL is fixed (`app/configmap.yaml:18`) and Keycloak gets only host, port
and database (`keycloak/deployment.yaml:133-136`); if the host requires `verify-ca` or `verify-full`, a CA
file is mounted for the application, Keycloak and the init container's `psql` (`PGSSLMODE`,
`PGSSLROOTCERT`) as well, since pgjdbc's default `prefer` encrypts but verifies nothing (documented: only
the two `verify` modes validate the server certificate). R3 adds
`keycloak.db.host`/`port`/`existingSecret` (the database name is `keycloak.database` already,
`values.yaml:231`) and, when they are set, drops the init container's `CREATE DATABASE` but keeps its wait
for the old pod's sessions to end before a new Keycloak migrates the schema (`:43-46`, `:87-92`). Two
databases per country, `niord` and `keycloak`, each with its own role, provisioned by the host; the
application needs DDL on its own database (Liquibase) and the PostGIS extension present
(`V001__baseline_schema.sql:20` creates it when missing, which needs a privilege a hotel usually withholds,
so the host creates it; nothing else in the changelogs needs more than ownership). The seed is not used
outside local and test environments (`code-data-config-plan.md` C1), so no superuser is needed. Staging's
application connects as the chart's Postgres superuser today (`envs/staging/dk/values.yaml:24-25`), so a role
of its own is new there and is tried first.

An environment moves by `pg_dump -Fc` from the chart's Postgres and `pg_restore --no-owner --no-acl` into
the hotel (the roles differ; errors on what an ordinary user may not touch are expected and harmless:
tried on 2026-11-12 with the chart's image, a restore by a user that owns the database reported
fifteen, on the comments of the four extensions the image installs, on the `tiger` and `topology`
schemas of two of them and their tables, and on `spatial_ref_sys`; restricted to the schema `public` it reported one,
on `spatial_ref_sys`), with
the files already rows (R2 before R3). `pg_dump -Fc` takes one database per run, and the chart's Postgres
holds two, `niord` and `keycloak`: each is dumped and restored into its own hotel database, with both
writers stopped for it (`app.replicaCount: 0` and `keycloak.replicaCount: 0` in the same merge), or
Keycloak's users and sessions stay behind when the chart's Postgres is pruned. Then two merges, because
both stagings prune: setting
`postgres.enabled: false` removes the chart's Postgres and its PVC in the same sync (`postgres/pvc.yaml:1-2`).
The first merge points `postgres.host` at the hotel, with the secrets and the URL parameters, and leaves
`enabled` true, so the old database keeps its data, unused (the chart's Postgres then restarts with the
application's new secret, which a data folder that exists ignores [I]). The second sets `enabled: false`,
after the separate project's restore proof and the agreed days. Argo's `Prune=false` sync option on that PVC would do the
same in one merge, at the price of a PVC nobody's values own. The devops design's snapshot feature (§9 there:
a prod dump restored, scrubbed, into a test environment) needs a producer of that dump once no CronJob of
ours makes one: the host restoring into a second database each time (G4), or a dump job; R4 records which.
Its attachments then come along instead of 404-ing.

---

## 5. What the hotel must offer (the separate project's gates; the questions may go to the host in R0)

| | Gate | Why |
|---|---|---|
| **G1** | PostgreSQL **18** with **PostGIS 3.6** (the chart's image is `18-3.6`), or a stated version the owner accepts after a test run of the suite against it | the application is built and tested on 18 (`be/CLAUDE.md`); V001 creates the `postgis` extension; an older major version also changes the move, since a PostgreSQL 18 dump is not promised to restore into it |
| **G2** | Two databases per country and environment, each with its own role; who creates the extension | §4.9; the init container's `CREATE DATABASE` will not be allowed [I]; staging's application uses a superuser today, so a role of its own is new |
| **G3** | A storage quota covering today's database plus the file trees plus growth (R0's numbers), and the price per GB | the files add their size to the database, and the database on disk is larger than its live rows: the dead TOAST rows of swept temp files until autovacuum reuses them, and the WAL a point-in-time recovery keeps |
| **G4** | Backup terms in writing: frequency, point-in-time recovery or not, retention, the restore procedure and who runs it, a restore into a *second* database (for the snapshot feature), and one rehearsed restore before any production use | the whole point; "automatic backup" is a claim until a restore has been seen |
| **G5** | Reachability from the test cluster and the prod cluster; TLS, its mode and its CA; the connection limit per role; whether a connection pooler sits in front, and in which mode | network before values; the planned NetworkPolicy needs an egress rule to it (`devops-niord/docs/open-issues.md:57-65`); the floor per role is HikariCP's 10 per pod for the application and Keycloak's pool, 100 by default [I], for Keycloak; transaction pooling needs pgjdbc's `prepareThreshold=0` and breaks the init container's session wait |
| **G6** | Monitoring and alerting on the host's side, or a metric we can scrape (size, failed backups), and the database's logs | the devops design's "failed backup CronJobs" alert has no CronJob to watch any more; the log-collection plan lists Postgres as a source |

---

## 6. Phases and order

Each phase is one PR into `main` or a feature branch with sub-PRs, verified in proportion
(the root `AGENTS.md`): CI runs on every pull request, a sub-PR's included.

### R0: measure and ask (no code; a week of waiting at most)

**State (2026-11-10):** not run, and R2 does not wait for it: the owner, "no need to take measurements". What
R2 needed from it is read on the day by the runbook's check before the window: each tree's top-level folders,
its size, and the free space of the chart's Postgres volume against the database and the trees. The SETTINGS-6
bullet is moot (fixed in code on 2026-11-03), and so is the UPLOAD-1 bullet (R1f set the limits,
and R1 is released). What remains is for later: the legacy production's numbers and its HTML query, which size
production's import (§6), and the host questions, which belong to the separate hotel project (D11). The bullets
below are the original text.

Staging and the legacy production are reachable from the host network only, so R0 is one short script the
owner runs: a `kubectl exec` per staging for the trees, the pod's environment and the database's size, and
one query on the legacy database.

- **How much the database will grow, for the hotel's quota (G3).** The bytes of the durable trees,
  `repo/messages` and `repo/publications` (and `publication-archive` where it exists), plus the size of
  the database itself (`pg_database_size`), per environment: the legacy Danish production (the owner's
  scan of 2026-10-24 has the files: 490 MB, §11; the database size and the yearly growth remain), DK
  staging and GL staging. One `du -sb` per tree and one query per database. The largest file on the
  trees, for the upload limit R1f sets, and each tree's top-level folders, so that a folder the import
  would report as unknown is known before R2. The same numbers are compared with the free space of the
  chart's Postgres volume (10Gi on DK staging, `envs/staging/dk/values.yaml:79`), not only with the hotel's
  quota: R2 lands before R3, so the trees land in the chart's Postgres first, with the WAL of the import
  and the dead tuples of a `verify` re-run on top.
- **That the DK and GL stagings resolve `niord.home` to the volume (SETTINGS-6).** In the app pod: the
  `niord.home` row's value, the start-up line `Publication archive root:` (`PublicationPathService.java:88`
  in the 4.0.66 images; on a later base `Expecting the server's settings file at …`,
  `SettingsService.java:363`, names the home itself), `ls /niord-home`, and the environment with
  `kubectl exec -- env` (not `sh -c env`: `dash` hides a dotted name). The country jars ship the token
  `${NIORD_HOME}`, so the volume should hold the files; the check is a confirmation. The generic image is
  the known exposure (§3.5; fixed in code on 2026-11-03).
- **What an upload of a 2 MB attachment does today (UPLOAD-1),** on staging: it decides whether R1f's
  multipart limits fix a live defect or only make a default explicit.
- **How much old message HTML links to a thumbnail or a map image by path,** on the legacy production's
  database, which is the previous Niord's MySQL ("the Quarkus backend on MySQL",
  `be/docs/map-image-benchmark.md:833`):
  `details REGEXP '_thumb_[0-9]+\\.|map_[0-9]+(_[0-9a-f]+)?\\.png'` (a MySQL string literal doubles the
  backslash; PostgreSQL's `~` does not exist there). A file the HTML names is imported as a row whatever
  its name (§4.8), so the count sizes how many derived files become rows, a few kilobytes each, rather
  than deciding anything.
- **The gate questions G1 to G6 to the host,** as one email, recorded in
  `devops-niord/docs/phase-0-answers.md` as H10 onward, next to H4. They belong to the separate hotel
  project (D11) and nothing in this plan waits for the answers; they go now only because asking costs
  nothing and the answers take time. The owner may hold them back without changing anything here.

Dropped from the draft: the sizes of the cache folders and the Lucene rebuild time (D2 and D3 keep them
on the volume as they are; nothing here depends on their numbers), and the duplicate scan (done, §11).

**Done when:** the numbers are in §11 of this document and the host's answers are recorded. R1 does
not wait for the host; R3 does.

### R1: the store

**Merged into `main` on 2026-11-10 and released as 4.0.146**, the first base with the store.

**State (2026-10-27):** R1a to R1f are built on one feature branch, as one change set rather than six
sub-PRs, since the four of R1a to R1d cannot be verified apart (below) and R1e and R1f are small. What
differs from the text below: the archive reader refuses an absolute entry name as it refuses a climbing one;
`GET /rest/repo/list/<folder>` answers an empty list, not 404, for a folder outside the served roots (the REST
contract its tests pin: a folder without files lists empty, and the archive is never listed); the sample's
regeneration takes the attachments from the `RepoFile` rows of its database and its safety check reads them as
bytea literals (`SampleSafetyCheck`, rule (f) on every row's content, and the hash and folder columns among
the identifier columns); `load-sample.sh` lost its `--home` and copies nothing; the multipart limits are set in
niord-base's defaults (`config/niord-core-defaults.yml`, 50 MB, the chart's proxy's body limit), so every
deployable and a local run have them, not the chart alone; the dead Spring property `niord.home` is gone from
the chart's ConfigMap and the defaults (the chart passed an environment variable named `niord.home` until the merge
of `main` on 2026-11-05, whose E-fixes make `niord.home` follow `NIORD_HOME`; the variable went with it); the
findings SETTINGS-6 (both entries), UPLOAD-1, PUB-DEL1, REPO-SWEEP1 and EXPORT-THUMB1 are closed and removed
from `be/docs/potential-errors.md`. The import job's report is the job's log on the Batch page; the
`RepoImportServiceITTest` imports a tree the test makes (a custom thumbnail, a `custom_thumb_64.png` beside
`custom.png`, an upload named `chart_thumb_64.png`, `Søkort.pdf`, a map image, an edit session and an
`aton_tiles/` folder), an old archive root with an audit entry to rewrite, a dangling reference, a second run
that skips and one that verifies, and the resume index. Not done in R1: the devops runbook (R2), the record
(R4), and the `verify-local` and CI runs are recorded in the Record below as they happen.

R1a to R1d are **one change set, verified together.** Sixteen files outside `RepositoryService` touch the
disk directly, twenty-two counting the classes handed a `Path`, the export, map-image and publication
services among them (`ApiRestService` rewrites URLs and does no disk I/O), so a `RepositoryService`
on the store with those callers still on disk sends uploads into rows while the export, map-image and
publication suites read the volume, and each of the four alone is red. D5 rejected the runtime switch
between two stores that would make them green one at a time. The four are the reading order of one
sub-PR, or four draft sub-PRs merged into the feature branch together and verified once. R1e and R1f are
sub-PRs of their own.

1. **R1a, schema and store:** `V009__repo_files.sql` with its include line and its checksum pin, a
   `RepoStore` service on JDBC (stat, open, write, delete, list, copy folder, delete folder, the sweep),
   `RepositoryService` on it with its REST contract unchanged, `RepoFileResource`, Range, ETag; the
   `repoRootPath` declaration gone and the settings catalog regenerated (§4.6). `RepositoryRestServiceITTest`
   keeps its requests and assertions unchanged, with six cases' fixtures moved to the store: five build
   their files on disk through `getRepoRoot()` and one assertion uses it (`:193`, `:363`, `:375`, the last
   shared by four cases), and the SECAUD-3 case's top-level `it-nontemp-…` folder becomes a folder under
   `messages/`, since §4.2's served roots answer a top-level folder 404. `RepositoryFolderContainmentTest`
   and `RepositoryListingFilterTest` make no directories and stay as they are; the ~15 cases of
   `RepositoryServiceITTest` that test the `Path` API go with it, the rest become store tests; an IT saves
   twice, overlapping, for the upsert (§4.3). `moveRepoFolder` and `getHashedSubfolder` deleted.
2. **R1b, derived files:** `ThumbnailService`, `MessageMapImageGenerator`/`MapImageRegenerator`/
   `MessageMapImageSnapshots`/`MapImageFiles` read their sources from the store and write into the cache
   tree, with the freshness rule of §4.3; `MessageMapImageSnapshots.apartFromRepository` (`:157-171`), a
   check against a repository root that no longer exists, goes. `CustomThumbnailScaler` and the PUT/POST
   of `MessageMapImageRestService` write a temp row in the store (a custom thumbnail is durable, §4.3), and
   the map-image endpoint's custom branch redirects to or streams that row; `GET /thumb` serves a cached
   thumbnail directly and keeps its file-type-icon redirect; the directly served map image carries the
   endpoint's own expiries (§4.3); `GET /file` has no fallback for a derived name; the map-image ITs pass;
   the AtoN tiles move to `aton-tiles/` under the home with a nightly 24-hour age sweep; the preview sweep is scheduled.
3. **R1c, messages:** `MessageRestService`'s uploads and `checkRepoFileReferences`, `MessageService`'s
   save path, `MessageExportService` (reads rows into the zip; EXPORT-THUMB1 fixed here, D9: a message
   with a custom thumbnail is exported like one with attachments, with an IT),
   `BatchMsgArchiveImportReader` (extracts entries into temp rows), `AttachmentMailPart.fromFile` (no
   caller; deleted). `BatchSetService.extractAndExecuteBatchSetArchive` (`:118-119`) unzips into the
   repository's temp folder through `getRepoRoot` today and would no longer compile; its only caller is the
   batch-set route (`POST /rest/batch/execute-batch-set`, `BatchRestService.java:323`; the bootstrap reads its
   archive in memory, `BootstrapArchive.java:55-57`), so it unpacks into a work folder the ten-day expiry
   reaches, `batch-jobs/batch-set/execution/<uuid>/`: the expiry walks only `batch-jobs/<job>/execution/`
   (`BatchService.java:616-657`), and a folder anywhere else under `batch-jobs/` would stay forever, where
   today the unzip in `repo/temp/` is gone after a day. `BatchMsgArchiveImportReader` deletes its temp rows
   when the job ends (§4.4). The sample's four attachments (`niord/sample/repo/`) become rows:
   `regen-sample.sh` writes them into `niord-sample.sql` instead of copying them out of the repository root
   (`:716-718`), `load-sample.sh` stops copying them into the home (`:96-98`), and
   `SampleSafetyCheck.ALLOWED_TABLES` (`:72`) and `SampleFolderTest` (`:80-81`) admit the store table. The
   table has no surrogate id for the sample's sake: `regen-sample.sh` captures a table with
   `CREATE TABLE … AS SELECT` (`:548`, `:551`), which drops an identity, and resets only Hibernate's
   `"<table>_SEQ"` sequences (`:709-710`), so an identity column would have made `--sample` load nothing or
   collide on its first upload; keyed on `path`, the sample's rows load as any other (§4.1).
   The sample is regenerated in any case, since `SampleFolderTest` takes the changeset list from the master
   changelog (`:156-158`). The draft of C4b changes the same sample files; whichever lands second rebases.
4. **R1d, publications:** `IssueFileService`, `IssuePublishService`'s render step, `IssueArchiveService`,
   `IssuePreviewService`, `IssueDeleteService` (PUB-DEL1 fixed here, D9: the delete leaves a folder alone
   when a legacy `Publication.repoPath` names the same path, with an IT), `PublicationService`/
   `PublicationRestService` (legacy), `PublicationPathService` reduced to the preview root (the
   `publicationArchiveRootPath` declaration gone, §4.6), the archive endpoint of
   `PublicationIssueRestService` (`:958`) streaming from the store; the 9 publication ITs pass with the
   rollback gap closed.
5. **R1e, the import job** (§4.8): the job, the `POST /rest/batch/repo-import` that starts it from
   parameters (SYSADMIN, `dir` and `archiveDir` confined below the home), the derived-file rule and the
   move into the cache tree, the verification from the rows' side, the rewritten archive entries listed
   with their old values; with an IT that imports a tree made by the test itself (a custom thumbnail, a
   `custom_thumb_256.png` beside an upload named `custom.png`, an upload named like a thumbnail, and a file
   named `Søkort.pdf`: the walk decodes names with `sun.jnu.encoding`, which the base image's `LANG` makes
   UTF-8, and `eclipse-temurin:25-jre` is a floating tag), rewrites an archive entry, reports a dangling
   reference and resumes after a stop.
6. **R1f, the chart and the home:** the volume stays and becomes a cache (the comment at
   `values.yaml:134` and `be/k8s/README.md` say so, and that nothing on it is backed up; the `fsGroup`
   comment at `app/deployment.yaml:39-41` stops naming `repo/`), an environment variable named exactly
   `niord.home` beside `authServerUrl`, the dead keys gone, the multipart limits set, and with them the
   texts that name the 1 MB default: `BootstrapArchive.java:66-69` (its own 16 MB and 64 MB caps become the
   ones that apply), `be/scripts/bootstrap.sh:89-90` and `be/docs/country-api.md:326-328`. The country
   contract gets a **new** row for R1's release, not an edit of its row 417, which is history (what a
   country did at C5b): a country runs `repo-import` once per deployment, the two settings are gone, and
   `RepositoryService` has no `Path` API any more. `./start-local.sh` changes only through `--sample`
   (R1c); its other durable state is in the local database now. A local database from before R1 answers
   404 for every attachment until the developer runs `repo-import` once against the Niord home's old `repo/`
   or starts over with `--sample --reset`: one line here and in `README.md`.
7. The feature PR into `main`, CI green; the release notes name the import job and the window. Its release
   is the one each country moves to before R2.

**Done when:** the suite is green on a database with no `repo/` on disk, `LiquibaseBootstrapITTest`
boots from the bare master, and the volume the chart mounts holds no durable file.

### R2: the cutover on staging

**State (2026-11-10): done on both stagings, with nothing to import.** `SIFA-DK/niord-dk` and
`SIFA-DK/niord-gl` moved to base 4.0.147 (4.0.146 and the LUCENE-2 fix; the base bump
app moved both pull requests from 4.0.146 to 4.0.147 while their CI ran, so the hand pin was made twice), and
`scripts/staging-bump.py` built `niord-app-dk`/`-gl:4.0.147.9`. The check before the window found the same on
both stacks: the volume's `repo/` held `temp/` only, no `messages/` and no `publications/`;
`publication-archive/` was empty; the database (24 MB) referred to no file (`Attachment.path`,
`Message.thumbnailPath`, `PublicationIssueDesc.filePath`, `IssueAuditEntry.archivePath` and `/rest/repo/file/`
links in message texts: none). The text below this paragraph expected the volumes to keep the trees of the
databases before the new ones of 2026-11-05 (`devops-niord/runbooks/fresh-database.md`); they did not. So:

- **DK** followed the runbook to its step 4: in `devops-niord` the release with `app.replicaCount: 0`, the
  copy `niord_before_r2`, then the start. The import was then left out, since it had nothing to read; its
  only output would have been the check from the rows' side, which the counts above had made. The copy was
  checked against the stopped database with `pg_dump | grep -vE '^\\(un)?restrict ' | md5sum`: PostgreSQL 18's
  `pg_dump` writes a random `\restrict` key into every plain dump, so two raw dumps of the same database never
  hash alike. The owner dropped the copy the same day, once the cutover was done.
- **GL** moved by a plain release: no `replicaCount: 0`, no copy, no import.

The owner left out the runbook's smoke list (an upload, its thumbnail and its download); the store's write and
read paths are covered by the R1 integration tests, and nothing was moved. What the volumes keep of the old
layout is `repo/temp/` and an empty `publication-archive/`. No import rate was measured (§11), so production's
import (R4) has no staging number to size it by.

The rest of this section is the plan as written before the cutover, kept for the runbook's design. R2 was
to wait for each country's base bump to 4.0.146 or later. Then, per country, DK first, the runbook:
`scripts/staging-bump.py --country <cc>` opens the release merge request (the chart pin and `app.image.tag`,
the two-pin model), to which `app.replicaCount: 0` is added; a check on the jump server (the trees' folders and
sizes against the free space of the Postgres volume); its merge starts the window and stops the application;
the copy of the database; a second merge request removes the line, starts the application on the store and
changes the stack's `persistence` comment (the volume is a cache, §4.7); the import with `verify`, from the
console of a browser signed in as `sysadmin`; the report, the counts against the tree and the rows, the smoke
list on the administration origin; the copy dropped after a few days. The runbook differs from §4.8 in three
places, each a simplification, which §4.8 now records: the stop and the release are one merge request; a copy
of the database is the rollback point, not a `pg_dump -Fc`; and no snapshot is taken of the application's
volume. The old trees stay on the volume until the separate hotel project has seen a restore work (D11); they
cost volume space and nothing else.

**Done when:** both stagings serve every file from the database, and a pod replacement loses nothing a
tester can find. Each cutover's `Planned` line, counts and run time (the runbook asks for them in a comment on
its second merge request) go into §11, for the import rate.

### R3: the chart seams for the hotel, and the handover (the move itself is a separate project, D11)

Built and tested here, against the chart's own Postgres, so that the separate project is values and a
dump: `keycloak.db.host`/`port`/`existingSecret`, with the init container's `CREATE DATABASE` dropped and
its session wait kept when they are set; a seam for JDBC URL parameters (`sslmode` and the like) for the
application and Keycloak, and a mounted CA for the `verify` modes, reaching the init container's `psql`
too (§4.9). Each seam is exercised once on OrbStack with the chart's Postgres standing in for the hotel
(a second release's Postgres as the "external" one), so the first time the seams meet a real hotel is not
the first time they run. The handover is §4.9 and §5 of this document as they stand, plus the host's
answers if R0 sent the questions.

Left to the separate project: the host's databases and roles; the dump and restore of both databases with
both writers stopped; the two values merges; the restore proof (the host restores the previous day's
backup into a second database, the chart is pointed at it in a test namespace, and a tester finds
yesterday's messages and their attachments); the removal of the chart's Postgres and its PVC after the
proof, and of the old trees on the application's volume.

**Done when:** the seams render and run on OrbStack against a stand-in external Postgres, and §4.9 and §5
say what the separate project does next.

**State (2026-11-12):** not started, but [database-user-plan.md](database-user-plan.md) built what
the hotel needs from the application: it runs as a user that owns its database and is no superuser,
on the chart's own Postgres too, and the first changeset builds as one (its comment on the `postgis`
extension is gone). `keycloak.db.existingSecret` exists, for a Keycloak on the chart's Postgres. Left
for R3: `keycloak.db.host` and `port` with the `CREATE DATABASE` dropped, the JDBC URL parameters and
the CA.

### R4: the record

**State (2026-11-10): done**, in this repository, `devops-niord` and `devops-shared`, documentation only. What
differs from the text below, which was written before D11:

- **The backup CronJob stays in the design, Phase 7 and the monitoring lists.** D11 keeps the nightly
  `pg_dump` CronJob as the interim backup until the separate hotel project, so `design.md` §9 and Phase 7
  keep the producer (with "since 4.0.146 it is the whole backup" and "until the hotel's backup replaces it"),
  and the monitoring lists keep the failed or missing backup CronJob beside PVC fill. The text below said
  the monitoring list loses it and Phase 7 shrinks to the restore, the scrub and the smoke test.
- **`be/AGENTS.md`** had the line already (R1, the repository paragraph under the backend's rules).
- **The root `AGENTS.md` row** says R1, R2 and R4 are done and R3 and R5 are not started, rather than
  marking the plan done.
- **`devops-niord`'s H4** keeps its wording: it already asks only for object storage and the host's
  backups, and its internal half (who backs up the live legacy production) is unchanged; an update line
  says that the database dump is now the whole backup.
- **`devops-shared`:** the manual's and the monitoring plan's alert lists say what the Postgres volumes and
  the backup CronJob now hold; the log-collection plan's claim that the devops design plans CSI snapshots is
  corrected (it plans `pg_dump` to object storage), and its Postgres rows note that the hotel's logs will come
  from the host. `README.md:73` and `platform/test/monitoring/config.yaml` stay as they are: both name the
  backup Jobs the monitoring watches, which is still true.

The text as planned:

The design documents are this repository's (`devops-niord/README.md:154-158`: its `docs/` are copies), so
R4 edits them here and then resyncs the copies. `be/docs/cicd/design.md` §6 (the hotel is no longer "later",
and it needs the chart work of §4.9 rather than only values, `:162`, whose superuser premise the seed's move
into the tests has made obsolete) and §9 (the producer CronJob stays, as the interim backup of the one
database until the separate hotel project replaces it; the file mirror and "attachments are files" go,
`:262`, `:273`, since the CronJob's dump now holds every durable byte; the snapshot consumer's dump comes
from that CronJob until the hotel, then from the producer §4.9 names);
`be/docs/cicd/implementation-plan.md` Phase 7 shrinks to the snapshot restore, the scrub and the smoke test,
keeps the roll-forward check (`:193`), and its staging refresh (`:194`) goes through merge requests, since
staging self-heals; `design.md:211`'s monitoring list loses the failed backup CronJobs and keeps PVC fill
(the cache can fill); `be/docs/cicd/README.md` the same. In `devops-niord`: H4 narrows to "object storage for
test-environment snapshots, if wanted" and keeps its own two halves (who backs up the live legacy DK
production today; whether the host backs up our PVCs or etcd). In `devops-shared`: `manual.md:589-596` ("the
Postgres volumes are the ones that page"), `monitoring-plan.md:289, 301-304`, `README.md:73`,
`monitoring/config.yaml:8` and `log-collection-plan.md:73, 518-521, 553-558` (Postgres's logs come from the
host; `:553-558` says design §9 plans CSI snapshots, which it does not). `be/CLAUDE.md` gains one line
("repository files are rows: the store, never a path"); the `docs/` row of the root `CLAUDE.md` marks this
plan done.

**Production** is not prepared here, and it has no cutover window (D8): it is not running on these
clusters, so when the owner asks, it starts on the store. Its files arrive through the same import job,
with the legacy installation's tree as `dir` (copied to a mount under the pod's home), while production is
set up and before it serves anyone; its databases are the chart's or the hotel's, whichever the separate
project has delivered by then (D11), and the import is the same either way. The order matters: the database migrated from the legacy MySQL is loaded first, then the import runs,
because the reference-first rule of §4.8 reads the attachment paths, thumbnail paths, issue columns and
message HTML from the rows. R2 was to measure the import rate on staging, to say how long that step takes;
neither staging had a file to import (§ R2), so production's import is sized on a copy of its own tree first.

### R5: the sea-chart tiles' cache (D10): replaced by a plan of its own

R5 as written on 2026-10-24 was nginx configuration and the chart: one `proxy_cache_path` zone on the public
site's proxy and one on the chart's proxy in front of the backend's servlet, the second behind `auth_request`.
It was never started. D10's amendment of 2026-11-10 moves the cache to the application's volume and leaves the
public site without one, so nothing of that text is built:
[`sea-chart-tile-cache-plan.md`](sea-chart-tile-cache-plan.md) has the design, the decisions, the steps and
their state.

Its two checks are answered there: the provider's licence allows a cache (the owner, 2026-11-10); and the
backend's servlet masks no colours any more, whatever its class comment said, so the two paths never drew
different pictures. The public site's `/wms/` location is untouched, and its two entries in
`fe/docs/backlog.md` stand (*The `/wms/` proxy would not work as written*, *Three `proxy_cache_valid`
directives do nothing*).

---

## 7. Risks

| Risk | Where it bites | What the plan does about it |
|---|---|---|
| The database grows by the file trees; the hotel's quota, price and backup window follow | G3, G4 | R0 measures before anything is built; the legacy production's durable files are 490 MB (§11), and D1 accepts the fifth of that which is re-uploads |
| Serving a file is queries, not a `FileSystemResource` stream from disk; the public site's PDF readers hit the same rows | §4.3 | one query per download, `Expires` 5 min and the ETag as today; D7 adds a proxy cache if R2's numbers say so |
| A download holds memory | §4.3 | the file, read whole: a few MB at most today, 50 MB by the cap |
| `Range` requests stop working, and PDF viewers fall back to full downloads or break | §4.3 | the stream skips by offset; an IT pins a 206 |
| The cutover window: no files are served until the import has run, and a copy, a publish or an issue delete inside it goes wrong | D5, D8, §4.8 | announced; a copy of the database first (the runbook's simplification of the dump and the volume snapshot, §4.8), the rollback inside the window; the import's report lists what went wrong |
| A rollback after the window loses what was written since, and the previous image refuses the rewritten archive paths | §4.8 | said in the runbook; the import's report holds each old archive path |
| The editor goes on using a temp folder after a save it took for failed, and two sessions save into one revision, overlapping | §4.3 | the save copies as an upsert on `path`, so a same-named file is replaced and two overlapping saves end last-write-wins instead of a primary-key violation; an IT saves twice, overlapping |
| A values change prunes the chart's Postgres and its PVC before the hotel's backup has been proven (the separate project's risk, D11) | §4.9 | two merges, the second after the restore proof and the agreed days; this plan touches neither |
| Until the hotel, the chart's Postgres holds every durable byte and has no backup | D11, R4 | one thing to back up instead of two: the devops design's CronJob, now without a file mirror, is the interim backup, and its dump carries the files |
| Edit sessions in flight at cutover are lost | §4.8 | the window; `temp/` is not imported |
| Absolute archive paths that the import cannot match stay broken | §4.6 | reported by the job, left as they are; the archive endpoint answers 404 for them as it does today for a moved root |
| An anonymous `GET /thumb` writes files to the cache volume | §4.5 | bounded as today, when it writes next to the original; a note, not a change |
| The read runs inside a caller's transaction and holds it open while a slow client reads | §4.3 | the controller is not transactional, and the stream is read by Spring after the handler returns; an IT asserts no transaction is active in the stream |
| The hotel is not PostgreSQL 18, or has no PostGIS (the separate project's risk, D11) | G1 | the suite against the offered version before any decision; nothing in this plan depends on the version |
| Thirty-odd test classes touch the repository on disk (`be/niord/src/test`: the repo, publication, map-image, export and batch tests), and so do the sample's loader and its checks | R1 | the REST contract is unchanged, so most ITs change only their fixtures; the folder-level unit tests are rewritten, not deleted |
| A deployment of the generic image, or of a country that lists `niord.home` in `useBaseDefault`, writes its files to the container layer and not the volume (§3.5) | now | the chart passes an environment variable named exactly `niord.home` (R1f); R0 confirms that the DK and GL stagings resolve to the volume (fixed in code 2026-11-03) |

---

## 8. What stays outside the database, so the claim is precise

- Git: code, charts, values, the sealed secrets' ciphertext.
- The Sealed Secrets sealing key: its offline backup and runbook exist (`devops-shared/runbooks/sealing-key-backup.md`).
- The live SECOM keystores: renewed in-cluster by patching the Secret, so the renewed material is not in
  git; a cluster rebuild re-enrols (`be/docs/mcp-identity-certificates-plan.md`). Already the case; not
  changed here.
- Keycloak's realm definition: a ConfigMap from the chart; its users and sessions are in the `keycloak`
  database, which is in the hotel.
- Caches and work files: rebuilt or expiring; losing them costs a slow first request.
- The sea-chart tiles on the application's volume, `wms-tiles/` (D10 as amended): a cache, fetched again on a
  miss.

Nothing else. After this plan there is one thing to back up, the database, where today there are two and
neither is. Until the separate hotel project (D11) that backup is the devops design's CronJob, now without
a file mirror, and its dump carries the files; after it, the hotel's, and "no backup of our own" then
means no CronJob, no object storage, no PVC snapshot, no file mirror, no runbook for any of them. The copy
of the database a cutover takes before its import (§4.8) is its rollback point, dropped a few days after the
cutover, not a backup.

---

## 9. What is deleted

`RepositoryService.getRepoRoot/getTempRepoRoot/moveRepoFolder/getHashedSubfolder` and every `Path`
overload; `PublicationPathService`'s repository and archive roots and its containment check;
`MessageMapImageSnapshots.apartFromRepository`; `BatchSetService`'s use of the repository's temp folder;
`AttachmentMailPart.fromFile`; the declarations of `repoRootPath` and `publicationArchiveRootPath`, and their
catalog entries; `load-sample.sh`'s copy of the sample's attachments into the home; the
redirect of `GET /thumb` into `/rest/repo/file/` for an image's thumbnail and the redirect of the map-image
endpoint for a generated image (the file-type-icon redirect and the custom-thumbnail redirect stay); the
Spring keys `niord.home` and `niord.search.index-root` in the chart's ConfigMap and the key in
`niord-core-defaults.yml:78`; in `be/docs/cicd/design.md` §9, and so in its copy in `devops-niord`, the
producer CronJob, the `files: mirror` option and the sentence "Attachments are files, not DB rows".

---

## 10. Rules and documents to change

- `be/CLAUDE.md`, *Idioms that matter*: "Repository files are rows (`RepoStore`), never a path on disk;
  `NIORD_HOME` holds caches and work files only, on a volume that is a cache, and nothing backs it up."
- `be/docs/test-infrastructure.md`: the `NIORD_HOME` temp directory the ITs set stays, for the caches;
  the seed's `${NIORD_HOME}` token stays.
- `be/k8s/README.md` (`app.persistence` row, the tile-cache note), `be/k8s/helm/niord/values.yaml`'s
  comments.
- `be/niord/sample/README.md`: its `repo/` row (the attachments are rows in `niord-sample.sql`, R1c).
- `be/docs/potential-errors.md`: SETTINGS-6 and UPLOAD-1 are resolved by R1f, REPO-SWEEP1 by R1a's expiry,
  and PUB-DEL1 and EXPORT-THUMB1 by R1d and R1c (D9); each is deleted then. SETTINGS-6 was closed earlier, by
  the E-fixes of 2026-11-03.

---

## 11. Numbers (filled in by R0)

| Environment | `repo/messages` files / bytes | `repo/publications` | `publication-archive` | database | yearly growth | largest file |
|---|---|---|---|---|---|---|
| DK staging (2026-11-10, R2) | none (`repo/` held `temp/` only) | none | empty | 24 MB | | |
| GL staging (2026-11-10, R2) | none (`repo/` held `temp/` only) | none | empty | 24 MB | | |
| Legacy DK production | 490 MB with `repo/publications` (the scan below) | | none (the folder is new with this backend) | | | |

Lucene rebuild at start on the largest tree: — . Import rate measured on staging (R2): none; neither
staging had a file to import (§ R2).

**Duplicate content on the legacy Danish production tree (the owner's scan, 2026-10-22):** uploads and
publications together are 490 MB, of which 92 MB are byte-identical copies (50 groups of re-uploaded
PDFs across messages, 87 MB; 3 groups in publications, 5 MB, the revision pattern of one file under two
names). The generated map images are 580 MB, of which 357 MB are copies: 3,628 groups, 19,568 files,
the three largest groups one image repeated 1,264, 1,242 and 720 times (messages with no geometry or the
same standard area). Upload thumbnails: 0.1 MB of copies. This is what decided D1 and bears on D2.

---

## Record

- 2026-10-22: written as a proposal at the owner's request, after three read-only inventories of the tree
  (the repository root and its callers; the chart, the environments and the devops design; the
  publications and batch subsystems). Two suspected production bugs found on the way were recorded in
  `be/docs/potential-errors.md` (SETTINGS-4, WEB14); nothing was changed in code.
- 2026-10-22, D1 decided by the owner: one `bytea` per file, external storage, sliced reads (removed on
  2026-10-27, above), no chunk
  table, no large objects, no content sharing. The draft's chunked, content-addressed design was
  withdrawn after the owner's duplicate scan of the legacy production tree (§11) and the correction that
  an ordinary edit copies no folder.
- 2026-10-22, D2 decided by the owner: derived files stay on the application's volume, which becomes a
  cache nobody backs up; the volume survives deploys, so the caches stay warm. The draft's derived rows
  in the store and the `emptyDir` alternative were both rejected.
- 2026-10-22, D3 decided by the owner: the Lucene index is left as it is, on the cache volume, rebuilt
  at every start.
- 2026-10-24, D4 decided by the owner: batch execution files and logs stay on the cache volume, as
  today, with their ten-day expiry.
- 2026-10-24, D5 decided by the owner: one import job per deployment inside a maintenance window; no
  read-through fallback, no runtime switch between stores.
- 2026-10-24, D6 decided by the owner: the AtoN tiles move to `aton-tiles/` under the home on the cache
  volume, with a nightly cap.
- 2026-10-24, D7 decided by the owner: a proxy cache for repository files later, if R2's numbers call
  for it. The owner's concern is the map tiles, the WMS above all, which this plan does not touch; the
  missing WMS cache on the public site's nginx is recorded in `fe/docs/backlog.md`, *Map*.
- 2026-10-24, the owner's review of the decided plan, applied the same day: §3.5, §7 and R0 no longer
  state the staging case as fact (the country jars ship `${NIORD_HOME}` in their own settings file, which
  precedes the library's in the Boot jar; the exposure is the generic image, and the clean fix is one
  line in `peek`); WEB14 answers 413, not 500; R1a to R1d are one change set; the hash is the import's
  check only and the ETag stays `updated` plus size; the content row has no entity and runs on JDBC;
  `kind` dropped, `expires` marks a temp row; the volume is sized from the caps; the window's wording says
  the application is up and serving no files; the `varchar_pattern_ops` index for prefix matches.
- 2026-10-24, a second review against the code (about 80 of 95 citations checked held), applied the same
  day. Four fixes before R1: the import gets its own derived-file rule, since the listing filter hides
  custom map thumbnails; a custom thumbnail is a temp row in the store, not a cache file, and the
  file-type-icon and custom-thumbnail redirects stay; three missed disk users (`BatchSetService`'s unzip,
  the archive endpoint's read, `apartFromRepository`); the store is JDBC-only with no entity for either
  table. Design points: move on save instead of copy; thumbnail freshness against `updated` and defined
  headers for the direct responses; verification from the rows' side and `updated` from mtime; the start
  endpoint and the confinement of `dir` below the home; publications as the window's real risk; the
  current map images and thumbnails moved into the cache tree at import; an environment variable named
  `niord.home` instead of a `peek` change or a Java option. Corrections: deleting an imported issue deletes
  the legacy publication's folder; the archive's writers; the folder levels are substrings, not a hash; the
  temp sweep goes by mtime; the `DELETE /file` bodies; `getHashedSubfolder` deleted; 16/22 files, not 26;
  which repository tests are the contract; line drift. Three suspected bugs recorded as PUB-DEL1,
  REPO-SWEEP1 and EXPORT-THUMB1 in `be/docs/potential-errors.md`.
- 2026-10-24, a third review, against the `main` of 2026-10-23 (seven pull requests past the first base: 131
  citations checked, 111 held), the editor's code and the devops repositories, applied the same day; the
  working tree was moved onto that `main`. Reversed: the move on save (§4.3), since the editor keeps its temp
  folder after a save whose reload failed, two sessions on one message save into one revision folder, and an
  archive that lists a message twice shares one temp folder; the save copies, as today. Before R1: the
  changeset is `V009` (`V006` to `V008` exist); §3.5 and its entry follow `main`'s settings files (the
  library's `niord.json` is gone), the entry renumbered SETTINGS-6 (SETTINGS-4 is taken); WEB14 withdrawn in
  favour of `main`'s UPLOAD-1; the sample's attachments become rows in R1c; R2 waits for each country
  on R1's release; the rollback is stated as it is (the files written since, the rewritten archive paths),
  with a dump and a volume snapshot taken first; both stagings self-heal and prune, so the window's steps are
  merge requests, and R3 removes the chart's Postgres in a second merge after the restore proof, the old
  trees with it. Design points: `timestamptz`, an identity column, one index on `path`, an escaped prefix,
  no stored content type, the slice query's join; writes without a second spool; the import's derived-file
  rule narrowed to the thumbnail sizes and to no referenced path, and a cache file already there left alone;
  copying a message in the window; the smoke list on the administration origin; the Keycloak init
  container's wait kept; the gates (a pooler, TLS and its CA, Keycloak's pool, staging's superuser,
  PostGIS 3.6, the database's logs); R0 run by the owner, its legacy query in MySQL; R4 edits
  `be/docs/cicd/` here and resyncs the copies, and names devops-shared's documents. Open: D8 and D9.
- 2026-10-24, a fourth review: six inline comments on the plan and a list of gaps, read against the text
  the third review left. Three were already answered by it and are recorded as such: the move on save and
  its `folder` column (the move is gone; the copy now says it recomputes `path`, `folder` and `name` and
  deletes a row it lands on in the same statement set), D1's ETag sentence, and the old trees' deletion
  (they stay until R3's restore proof). Applied: the import collects every referenced path first and the
  derived-file heuristic applies only to the rest, so an upload named like a thumbnail is an attachment;
  `GET /file` answers an old link to a derived file from the cache tree, so no stored HTML is rewritten and
  R0's count only sizes a warm-up; the hotel move dumps and restores both databases, `niord` and
  `keycloak`, with both writers stopped; the batch-set work folder is swept by the ten-day expiry; §3.1
  names D9's rule for PUB-DEL1; D8 carries the review's demand for a technical closure on staging; the
  upload limit (UPLOAD-1) is very likely live, and the review advised a pull request of its own.
  Already true in the text and left as they were: the slice query joins the file table for its pin; R1a
  names no entity; `path` has one index. Open: D8 and D9.
- 2026-10-24, D8 decided by the owner: on staging the window is closed by announcement and the import's
  report read afterwards; staging is a test environment, to be done with as we please. Production has no
  window: it is not running on these clusters, and its files arrive through the same import job from the
  legacy tree while it is set up, before it serves anyone (§6). Open: D9.
- 2026-10-24, D9 decided by the owner: PUB-DEL1 and EXPORT-THUMB1 are fixed inside R1d's and R1c's
  rewrites, an integration test each; the delete leaves a folder a legacy `Publication.repoPath` names, and
  the export takes a message with a custom thumbnail like one with attachments. No decision is open; the
  plan awaits the go for R0.
- 2026-10-24, the owner: the upload limit (UPLOAD-1) is part of the plan, set by R1f, not a pull request of
  its own as the fourth review advised.
- 2026-10-24, a finding on the import's verification: the §4.3 fallback paragraph and R0's bullet said an
  HTML link to a thumbnail or a map image points at a file the import moves into the cache tree, while the
  verification requires every HTML link to resolve to a row, so working links would have been reported as
  dangling. Resolved the other way round from the finding's suggestion: §4.8's reference-first rule already
  imports a file the HTML names as a row whatever its name, so those links resolve to rows and the check
  stays exact; §4.3 and R0 now say so, and the `GET /file` fallback is for a derived name with no row only.
- 2026-10-24, a fifth and a sixth review, both checked against the code and applied the same day; none
  reopens D1 to D9. Three design points settled together: **one table**, keyed on `path` under the `C`
  collation, the bytes in the same row under `STORAGE EXTERNAL` (no identity column, which the sample's
  `CREATE TABLE … AS SELECT` capture would drop and whose sequence the reset would not know; no foreign key,
  no join, no id to carry over); the save as an **upsert** on `path`, since under `READ COMMITTED` two
  overlapping saves into one revision folder would otherwise end in a primary-key violation, nothing
  upstream serialising them; and **no `/file` fallback**, which would have let anonymous readers through the
  public site's nginx have thumbnails and map images drawn under any made-up name, and which after the
  import nobody but a bookmark would reach. Also: the map image served directly carries the endpoint's own
  expiries (10 min, 60 s) rather than `/file`'s 5 minutes; the batch-set work folder is
  `batch-jobs/batch-set/execution/<uuid>/`, the only kind of folder the ten-day expiry walks; the
  message-archive import deletes its temp rows when the job ends; production loads its migrated database
  before the import; R0 compares the trees with the chart's Postgres volume, where they land first; the
  repository IT keeps its requests and assertions with six fixtures moved to the store, the SECAUD-3 folder
  under `messages/`; a new country-contract row instead of an edit of row 417; a local database from before
  R1 needs one `repo-import`; unreferenced files outside a revision folder are imported; §3.3 no longer says
  an edit copies a folder in; the flush-order argument against a JPA entity is dropped (an identity column
  inserts on persist), the other two stand; the inference mark on pgjdbc's `prefer` is gone. The aside that
  the map-image endpoint draws for any uid anonymously was recorded as an open item, then accepted by the
  owner the same day and noted permanently in the endpoint's class Javadoc instead ("note it somewhere
  permanently, for example in the code"); the open item was withdrawn.
- 2026-10-24, D10 decided by the owner, on reading the backlog's entry that no server-side cache of WMS tiles
  exists: "this should be cached on a proxy drive; it is just that we do not need it in the database." The
  sea-chart tiles' cache moves from a backlog note into this plan as D10 and R5: a `proxy_cache` zone on
  each proxy's own disk, bounded, keyed on the request URI, `auth_request` in front of the backend's servlet
  so `wmsProtected` keeps its meaning; never in the database; a cache like the others, backed up by nobody.
  The scope line, the summary table, D7 and §8 follow; the backlog entry now points here.
- 2026-10-27, R1 built on its feature branch (its state under § R1): V009 and `RepoStore`, `RepoPaths`,
  `RepoFileInfo`, `RepoFileResource`, `RepoCache`; `RepositoryService` on the store with its REST contract;
  `ThumbnailService` drawing from a spool of the row into the cache tree; the map-image endpoint serving the
  cache image itself and redirecting to a custom thumbnail row; the AtoN tiles under `aton-tiles/` with a
  nightly sweep; the preview sweep scheduled; the message export, the archive import and the batch set; the
  publications on the store with the archive folder, PUB-DEL1 and EXPORT-THUMB1 fixed; the settings catalog
  regenerated (71 keys); the sample scripts and checks; `RepoImportService` with the `repo-import` job and
  `POST /rest/batch/repo-import`; the chart's `niord.home` variable and cache comments, the multipart limits,
  the texts, the country contract's new row, the README line; the sample and the IT fixture regenerated.
  `./verify-local` on 2026-10-27: unit 5218 tests green, integration 3371 tests with three failures
  of `MessageSeriesServiceITTest`, an order dependency on publication-series tests that commit series they never
  delete (unchanged since the publications port; `be/docs/potential-errors.md` IT-ORDER1), the no-sample check
  green. The feature PR into `main` is opened as a draft for the owner's review and CI. Simplified the
  same day after a four-angle review (reuse, simplification, efficiency, altitude): a write is one statement
  with the hash computed in the database and the expiry derived from the path (a temp row expires a day after
  its write, nothing else does, and no caller says so), the served-root, containment, LIKE-escape and
  derived-name rules live in one place each, the archive-before-overwrite in `IssueArchiveService`, the
  archive import reads its index from the archive, the import's verification looks rows up in batches, the
  cache sweep is shared with the AtoN tiles; verified again with the same result. A second review the same
  day found fifteen defects, all fixed: a served root itself was served, so an issue whose stored folder
  normalised to `publications` would have had the whole root deleted (`RepoPaths.isServed` requires a path
  strictly below a root); the archive's copy replaced a row at the target (now `ON CONFLICT DO NOTHING`, and
  `IssueArchiveService.archive` refuses the second edition); EXPORT-THUMB1 was not fixed after all (the
  export processed only messages with attachments; its test needed one); the store keys every row on the
  normalised path, so a caller's spelling and a request's meet at one row; the contract row said a country
  "may" drop the two settings where its settings check requires it; a save could reach the archive folder;
  the archived-file endpoint set a whole-file Content-Length a Range answer contradicted; a spool file
  carried the whole file name; the cache ETag carried the name; an archive entry of unknown size failed;
  an edit session's thumbnails of old files were swept hourly (stamped with the later of the source's time
  and now); a deleted issue's cache files stayed (`RepoCache.evict`); the listing hid uploads named like a
  thumbnail or map image (every row is an upload: only the custom map thumbnail is hidden); the spooling
  write bypassed the transaction proxy; the store is injected, not reached through the controller. A third
  review the same day found fifteen more, all fixed: a stream's slices start at 64 KiB and grow (a Range of a
  few bytes read a megabyte); a HEAD answers headers only (Spring ran the handler and read the whole row);
  a thumbnail is named by its row's content hash (so a file written again, a save copying an older row over
  it, or a restored database names another, and a refused image leaves a marker so it is not read again on
  every request; a thumbnail the import moved in is adopted once); a map image is the message's only when
  its time equals the message's change time (a restored database no longer serves the lost geometry's
  image); the import reports a file that fails and goes on; an upload's name is decided by the database
  (two uploads of one name at once got one row); a published name from the series pattern must be a bare
  name (`FILE_NAME_INVALID`); the custom thumbnail's scaler returns null where no PNG writer takes the image;
  a deleted issue in a legacy publication's folder takes its own files; the export skips any failure and
  names a short entry; the cache ETag carries a hash of the name again (one URL serves two images); the home
  is read once; the publication writes keep their coded refusals and the archived name fits its column; the
  no-sample check reads the attachment rows of the SQL. The owner then removed the sliced reads: a row is read
  whole, in one query, held in memory for the download, and capped at 50 MB (`RepoStore.MAX_SIZE`); the
  import reports a larger file and leaves it.
- 2026-10-27, D11 decided by the owner: the move to the hotel is a separate project; this plan runs on the
  database the deployments use today and finishes everything that does not need the hotel. R3 becomes the
  chart seams and the handover, exercised on OrbStack against a stand-in external Postgres; the host's
  questions may still go in R0 since they cost nothing; the old trees and the chart's Postgres go only when
  the separate project has seen a restore work; the devops design's CronJob stays as the interim backup of
  the one database, without its file mirror; the title, the status line, the scope, the summary table,
  §3.4, §4.8, §4.9, §5, R0, R2, R4, §6's production paragraph, §7 and §8 follow.

- 2026-11-10, a fourth review of R1 (read against the branch with main merged, 2026-11-05 and 2026-11-10) found
  four defects, all fixed: deleting an imported issue deleted the legacy publication's files (the import gives the
  issue's descriptors the legacy paths, `LegacyIssueTranslation.filePath`, so "its own files" were the legacy
  publication's; now a file a legacy publication names, by its revision folder or by its link, stays, and only a
  file the issue itself published there goes; the test uses the import's layout); a request of many ranges read the
  row once per range (Spring's region writer opens the resource's stream again for a range before its position;
  `RepoFileResource` keeps the bytes of its first read for the request); the thumbnail's refusal marker was written
  for every failure of the drawing, a missing vips, a full volume or an interrupted thread included, and nothing
  swept it (now only the image's own refusal, no reader or above the pixel cap, leaves the marker,
  `ImageRefusedException`; a vips exit other than 0 is reported and tried again); V009's comment still described the
  sliced reads (edited while the changeset has run on local databases only, its checksum pin with it: a local
  database that ran the branch needs `--reset`).
- 2026-11-10, a fifth review of R1 found seven defects, all fixed, and one non-finding: `RepoCache.pathFor` resolved
  any string under the cache root, so the containment of a stored folder was each caller's to remember (now it
  normalises and refuses a path that is no path of the repository, `RepoCacheTest`); deleting an imported issue
  whose descriptor held an absolute or climbing path was undone by the store's refusal after the row was gone (the
  issue's own files are read with `RepoPaths.served`, and such a path is left alone with a warning; the delete IT
  carries both); the delete and the import's verification read a `/rest/repo/file/` link back to a row by two rules
  (one now, `RepoPaths.fileLinkPaths` and `RepoPaths.candidates`, cut at a query string or a fragment, a parenthesis
  part of the name since `encodeURI` keeps it; `RepoFileLinksTest`); the spooling write of a stream without a length
  held a transaction, and so a connection, for the whole transfer from the report renderer (the spool runs outside
  one, the sized write's one statement is atomic by itself; the repository service IT reads the source with no
  transaction open); `RepoCache.evict` counted the folders it deleted as files (asked before the delete); the vips
  path swallowed an interrupt and read a stream that `inheritIO` had closed (the flag is kept, vips is destroyed,
  its output read from a redirected pipe before the wait); the import's processor rebuilt the archive root from
  the raw job property while the reader had confined it (confined in both). The contract row named
  `RepoStore.open`, gone since the fourth review (now `readAll`, `copyTo`, `spool`). The non-finding: `GET
  /rest/repo/list/{folder}` was said to have dropped sub-folders, but main's listing filtered to regular files
  too, so `directory` was always false before as well; nothing changed.
- 2026-11-10, R1 merged into `main` and released as 4.0.146. R2's runbook written in `devops-niord`
  (`runbooks/repo-files-cutover.md`), with the README's note that the first move to 4.0.146
  is not a pin change alone and the comment of `envs/_template/values.yaml`. The owner: R2 does not wait for R0's
  measurements ("no need to take measurements"); the runbook reads what R2 needs on the day. The runbook
  simplifies §4.8 in three places: the stop and the release are one merge request; the rollback point is a copy
  of the database in the same Postgres, not a `pg_dump -Fc`; and no snapshot is taken of the application's
  volume. It also says how a sysadmin gets the import's token on staging (a signed-in browser, since the shared
  Keycloak's `niord-web` has no password grant), and that the public site's checks wait for its deployment.
  The status line, D5, §4.8, R0, R1, R2, §7 and §8 follow; R2's text no longer names the 4.0.66 images.
- 2026-11-10, R2 done on both stagings. The countries moved to base 4.0.147 and
  staging to `niord-app-dk`/`-gl:4.0.147.9`. The check before the window showed that neither volume held a
  `messages/` or `publications/` tree and neither database referred to a file, contrary to R2's expectation
  that the volumes kept the trees from before the new databases of 2026-11-05. DK ran the runbook's stop, copy
  and start (in `devops-niord`) and left out the import; GL moved by a plain release. The owner
  left out the smoke list. No import rate was measured, so §11 has none and R4 says production's import is
  sized on its own tree. DK's copy `niord_before_r2` was kept as an open issue in `devops-niord` and dropped by
  the owner the same day; the issue is deleted. The status line, R2, R4 and §11 follow.
- 2026-11-10, R4 done, at the owner's go ("update the docs"): `be/docs/cicd/design.md` §6 (the hotel is a
  separate project and more than values; the superuser premise is gone), §6's monitoring list, §9 (the dump
  is the whole backup, attachments are rows, the staging refresh goes through merge requests) and §11's
  snapshot closer; `implementation-plan.md` Phase 7; `be/docs/cicd/README.md`'s note on the copies; the
  root `AGENTS.md` row; the copies in `devops-niord/docs/` brought level (three edits made only there, on the
  monitoring stack being decided and running, came back here first, with the mail receiver now in), with an
  update line on H4; and in
  `devops-shared` the manual, the monitoring plan and the log-collection plan. Where R4's text predates D11
  (the backup CronJob leaving the monitoring list and Phase 7), the documents follow D11 (§ R4).
- 2026-11-10 to 2026-11-12, D10 amended by the owner and R5 replaced. Asked for a plan for the sea-chart tile
  cache, the owner settled that the licence allows one and required that only logged-in users get a cached
  tile. Weighed again on that footing, the nginx cache of R5 lost to a cache in the backend on the application's
  volume (the proxy pod has no volume; the login check twice, the second copy untested; backend code needed
  anyway), and the owner chose the latter on 2026-11-10, with a freshness of 7 days. Six further decisions were
  taken one at a time on 2026-11-12 (the cache measured by an hourly log line and a Grafana dashboard, a
  starting cap of 1000 MB on the stagings, off by default in the base product, the uncached path left as it
  is, an older copy and a pause when the provider fails, the chart's address set per country). The status
  line, the scope line, the summary table, D10, §4.7, R5 and §8 follow;
  [`sea-chart-tile-cache-plan.md`](sea-chart-tile-cache-plan.md) carries the design and the state from here.
- 2026-11-12, from [database-user-plan.md](database-user-plan.md): §4.9's restore by an ordinary user
  reports fifteen errors with the chart's image, not one (tried), and R3's state says what that plan
  already built: the application as an ordinary user on either database, and Keycloak's own Secret.

## References

- `be/niord/src/main/java/org/niord/core/repo/RepositoryService.java`, `ThumbnailService.java`
- `be/niord/src/main/java/org/niord/core/publication/series/{PublicationPathService,IssueFileService,IssueArchiveService,IssuePreviewService,IssuePublishService,IssueDeleteService}.java`
- `be/niord/src/main/java/org/niord/web/map/{MessageMapImageGenerator,MapImageRegenerator,MessageMapImageSnapshots,CustomThumbnailScaler}.java`
- `be/niord/src/main/java/org/niord/core/batch/{BatchService,BatchData,BatchSetService}.java`
- `be/niord/src/main/java/org/niord/core/settings/SettingsService.java` (`peek`, `expandSettingValue`)
- `be/k8s/helm/niord/templates/app/{deployment,configmap,pvc}.yaml`, `values.yaml`
- `devops-niord/docs/design.md` §6, §9; `docs/implementation-plan.md` Phase 7; `docs/phase-0-answers.md` H4;
  `envs/staging/*/values.yaml`; `runbooks/ephemeral-environments.md`
- `docs/code-data-config-plan.md` (the rules a changeset and a setting row follow), `be/docs/lucene-postgres-toggle.md`,
  `be/docs/lucene-todo-removal.md`, `be/docs/mcp-identity-certificates-plan.md`
