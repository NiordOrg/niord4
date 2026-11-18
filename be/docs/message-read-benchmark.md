# Message reads: Niord 4 against the previous Niord

**Status:** 2026-11-05. niord-base's core defaults batch lazy loads 256 owners a query (16 before). niord-dk's and
niord-gl's local profiles no longer set their own value (since 2026-11-06), so both countries' local runs and
deployments take 256 from the core defaults. The check on the test data (section 4, step 1) needs no decision,
only the data; steps 2 to 4 wait for the owner's go. The owner ruled on 2026-11-02 that the gap is closed before
production go-live ([backlog](../../fe/docs/backlog.md), _Before production go-live_).
**Scope:** the message reads of both web applications: the administration app's list (`GET /rest/messages/search`,
a page of 200, and its map view, 1,000), the public site's list (`GET /rest/public/v1/messages`, every published
message) and one message (`/rest/messages/message/{id}`, `/rest/public/v1/message/{id}`). Not the PDFs and not the
map images, which have [their own benchmark](map-image-benchmark.md).

---

## 1. What the figures compare

- **The backlog's figures** (2026-11-02):
  `GET /rest/messages/search?language=en&mainType=NW&mainType=NM&areaId=453` answered in 110–150 ms on the
  previous Niord's test environment and in about 1 s on a Windows workstation against Niord 4 on the same data,
  where it made 541 table lookups; how they were counted is not recorded. The machine, the database engine (MySQL
  against PostgreSQL) and the path to the database all differ, so the factor of 7 is not a like-for-like figure.
- **That request lists everything.** `/rest/messages/search` reads the area from `area` and the language from
  `lang`, in both applications, so `areaId` and `language` are ignored. It answers the default page: the first
  100 of the 199 published NW and NM messages, with the texts of every language. Both applications read the same
  names, so the two figures do time the same request.
- **The repository's own records show no factor of 7.** The map-image benchmark timed the previous Niord on its
  test environment from a workstation (R5, medians: one message 72 ms, a list of 176 messages 621 ms) and Niord 4
  on a workstation with the test data and niord-dk's local profile (case 6: one message about 39 ms, the list
  search with `maxSize=200` about 560 ms). Neither pair is like for like, but they are close.
- **The batch size's own records**, on the test data: a list of 100 took 1,119 statements and about 4.5 s without
  batching, and 92 statements and about 0.5 s at 16 (niord-dk's local profile, which set it first); the public
  message list, about 275 messages, took 21–25 s without and 1.2–3 s at 16 (which was then put into the core
  defaults).

## 2. What is the same and what differs

The same in both: the associations of the message and of the 19 entities its value object reaches (their
annotations, compared file by file), and the search: one query for the ids, one for the messages, then the value
objects walk each message's associations.

| | Previous Niord | Niord 4 |
| --- | --- | --- |
| Stack | Quarkus 3.7.2, Hibernate ORM 6.4.3, MySQL | Spring Boot 4.0.7, Hibernate ORM 7.2, PostgreSQL 18 |
| Batch fetch size | 16, Quarkus's default | 256 in `niord-core-defaults.yml` since 2026-11-05 (16 before) |
| Second-level cache | on for the 18 `@Cacheable` entities: Quarkus's default, built on Caffeine, 10,000 entries a region, no expiry | off: no cache provider is configured, so `@Cacheable` does nothing |
| Cached collections | none: no collection carries `@Cache` | none |
| Bytecode enhancement | always: Quarkus enhances the entities at build time | none |

## 3. Measured on 2026-11-05

A throwaway integration test, not committed: the requests over HTTP on the integration suite's fixture (the
base bootstrap and the 34 sample messages, 18 of them published NW or NM), then with the sample's messages copied
five more times (108 published), every statement recorded through Hibernate's statement inspector and its
statistics. PostgreSQL ran in Testcontainers on a Mac (OrbStack), where a statement costs a fraction of a
millisecond: the times are that machine's, the statement counts are not.

| Batch fetch size | List, a page of 100 | Public list, 78 messages | Search and value objects of the page |
| --- | --- | --- | --- |
| 16 | 86 statements | 67 | 25 ms |
| 64 | 30 | 33 | 20 ms |
| 128 | 19 | 23 | 14 ms |
| 256 (now) | 19 | 23 | 13 ms |

- Every association is batch-loaded (`= any(?)`): there is no unbatched N+1 on this data. A collection takes one
  statement per batch of its owners as the list walks them. At 16, the ten collections the list walks for every
  message (its areas, categories, charts, texts, parts, references and attachments, its parts' texts and dates,
  its attachments' texts) take ⌈100 / 16⌉ = 7 each, 70 in all; the five whose owners are fewer or shared (the
  parts' features, the categories' texts and editor fields, the areas' texts, the references' texts) take 12; the
  queries for the ids and for the messages and the areas and series loaded by id take 4: 86. At 256 a collection
  takes one statement for every 256 messages walked: the page of 100 takes 19 in all, the administration app's
  page of 200 one per collection too, and its map view of 1,000 up to four for each of the fewer collections it
  walks.
- **The batch fetch size is the lever.** Below 256 messages the statement count of a list no longer grows with its
  length, and `HibernateBatchFetchITTest` holds the list to it: the seed's 18 published messages take 19
  statements at 256 and 34 at 16, and the test's ceiling is 25.
- **The second-level cache saves 2 of the 86**: the areas and the series looked up by id. The rest are the
  messages' own collections, which neither application caches. Until 2026-11-12 it would not have restored the
  previous behaviour either: the PostgreSQL search indexer (`MessagePostgresIndex`) ran native deletes and inserts
  that named no table, and Hibernate emptied every region on each of them, on every pass, once a minute. Since the
  IDX2 fix they name their table.
- A read-only transaction saves 1–2 ms; writing the page's JSON takes about 1 ms.

## 4. Plan

In this order; steps 2 and 3 are independent of each other.

1. **The check on the test data**: time the same requests on a country stack with the test data, at 256 and at
   16. A country's local run takes 256 from the core defaults, and its launcher passes no argument through to the
   backend. For the run at 16, start the stack with
   `SPRING_APPLICATION_JSON='{"spring.jpa.properties.hibernate.default_batch_fetch_size":16}'` in the environment:
   the backend's JVM inherits it, and it wins over the core defaults. A `-D` on Maven's command line never reaches
   that JVM. If the time is in the statements, it falls with their count. Production-size data arrives with the
   first regular restore of the previous Niord's production database (the go-live rehearsal); the requests are
   timed there too.
2. **The benchmark**, built on `be/scripts/bench-map-images.mjs` (its harness, credentials and result files; its
   case 6 and R5 already time one message and the list search) rather than a second script beside it: the reads of
   the scope as cases, each timed (median and p95), with Niord 4's statements per request recorded, in its two
   modes: `local`, both applications on one workstation with the test data (the previous one on MySQL, Niord 4 on
   its PostgreSQL copy), and `remote`, the two test environments with GET requests only. The pass rule is the
   map-image benchmark's, for `local`: in every case Niord 4's median and p95 are at most the previous Niord's
   × 1.05, with an allowance of at least 2 ms for operations under about 40 ms. `remote` is a guide, not the
   gate. **Needs:** for `local`, the test data's MySQL dump and a running build of the previous Niord; for
   `remote`, a Niord 4 test deployment on the test data.
3. **The second-level cache**, as the previous Niord had it: `org.hibernate.orm:hibernate-jcache` with Caffeine's
   JCache (`com.github.ben-manes.caffeine:jcache`), both versions managed by Spring Boot, `ENABLE_SELECTIVE`,
   10,000 entries a region; a deliberate `pom.xml` change. Three conditions come first:
   - each application context gets a cache manager of its own, created and closed with it and handed to Hibernate
     as `hibernate.javax.cache.cache_manager`. The provider's default manager is one per JVM, which the integration
     tests' contexts would share: a context on a fresh database would read rows cached from the seeded one, and
     the first context to close would close the manager for the others, which then fail with an
     `IllegalStateException`;
   - `MessagePostgresIndex`'s native statements declare their table, or every indexing pass empties the cache
     (met since 2026-11-12: each carries the hint `HibernateHints.HINT_NATIVE_SPACES`);
   - the backend runs one replica (the chart's `replicaCount: 1`), and no write outside Hibernate touches the table
     of a cached entity. Such writes exist: `RepoStore`'s `JdbcTemplate`, the `Session.doWork` writers of
     `SettingsService` and `DefaultsLedger`, `BootstrapMarker`'s native SQL, `LegacyImportService`'s two native
     deletes of `PublicationSeries` collection tables and `MailingListStatusChangeOutbox`'s native SQL (a row
     written, taken and deleted with every status change). None of their tables belongs to a cached entity:
     `Setting` carries no `@Cacheable`, `PublicationSeries` neither, and `RepoFile`, `BootstrapState`,
     `DefaultsLedger` and `MailingListStatusChange` have no entity. `Setting`, `RepoFile`, `BootstrapState` and
     `DefaultsLedger` stay uncached. A native write that declares no table empties the cache when it runs, as
     `MessagePostgresIndex`'s did; the outbox's writes declare their table, while `BootstrapMarker`'s run at
     start-up and during a bootstrap and `LegacyImportService`'s in the legacy import, so they cost a cold cache
     then, not stale reads.

   With `hibernate-jcache` on the class path Hibernate switches the cache on by itself, so the core defaults state
   the settings rather than leave them to that.
4. **The comparison**, then whatever it still shows to differ. The backlog item closes when every case passes.

**What 256 costs.** A flow that loads many messages and then reads the collections of a few loads up to 255 other
owners' collections with each collection it reads: more rows, the same statements. Two such flows are known:

- A message status change while a legacy publication is RECORDING first loads that publication's whole tag
  (`PublicationService.checkMessageForRecordingPublication`, about 150–165 messages on the test data), so every
  collection read after it loads for all of them: about 1,000–2,000 rows more. It happens on test and UAT with the
  test data; production is clear, since go-live requires that no publication is RECORDING.
- The hourly Niord integration (`NiordIntegrationExecutionService`) loads a series' own messages before it cancels
  one: about 2,000 rows more once in a run that cancels a message. With two or more changes in a run, 256 is
  cheaper than 16.

Not proposed: read-only transactions for the read handlers (1–2 ms, and a read path writes: `currentUser()` saves
the user's row when their token changed, and in a read-only transaction that save is dropped without a word:
"Updated user" is still logged, but no UPDATE reaches the database), and a fetch join for the list (the batch size
reaches the same count without a query shaped for each view).
