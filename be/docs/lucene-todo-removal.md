# Lucene removal — remaining work

Free-text message search currently ships **two** backends. Which one a search reads is selected by the
`disableLucene` setting (default `false` = Lucene); both indexes are always built and maintained. The
PostgreSQL backend and how it differs from Lucene are described in
[`lucene-postgres-toggle.md`](./lucene-postgres-toggle.md).

This document tracks what is left to retire Lucene entirely. Spatial search never used Lucene (it is
pure PostGIS), and the AtoN half is already done — Hibernate Search was removed and AtoN tag search
runs on SQL `LIKE` (follow-up trigram index tracked in [`aton-todo.md`](./aton-todo.md)).

## Blocked on: sign-off of the behavioural differences

The PostgreSQL backend is deliberately *smarter but different* — stemming, AND-default, whole-word-only
(no leading/infix wildcards), stop-words, per-language accent handling and relevance ordering. Those
changes affect which messages a given query returns, so they must be **explicitly signed off** before
Lucene is removed; the toggle exists precisely so the two can be compared side by side on real data
first. See the "How it differs from Lucene" and "Limitations" sections of the toggle doc.

## Remaining Lucene surface

Raw Apache Lucene (`lucene-core` + `lucene-queryparser`, declared explicitly in `niord/pom.xml`) is
used by exactly:

- `org.niord.core.message.MessageLuceneIndex` — the message free-text index (the only real consumer).
- `org.niord.core.util.LuceneUtils` — `normalize()` (accent folding) + `normalizeQuery()`. `normalize`
  is also called by `AtonService` and is *not itself* a Lucene dependency except for the
  `org.apache.lucene.util.Version` import.
- `niord/src/test/java/org/niord/core/LuceneTest.java` — unit test of the query-parser behaviour.

## The removal itself

- Delete `MessageLuceneIndex` (and with it the `@Scheduled` reindex, the `ReentrantLock`, `FSDirectory`,
  and the `messageIndexPath` / `messageIndexIncludeDeletedMessages` / `messageIndexDeleteOnStartup`
  settings).
- Drop the `disableLucene` branch in `MessageSearchService.searchPagedMessageIds` and the
  `disableLucene` setting itself, so `MessagePostgresIndex` is the only backend.
- Remove `lucene-core` + `lucene-queryparser` (and the `lucene.version` property) from `niord/pom.xml`
  — **a build-file change, so it needs explicit authorization.**
- Decide on `LuceneUtils`: keep `normalize()`/`normalizeQuery()` (rename away from "Lucene" if desired)
  but drop the `org.apache.lucene.util.Version` import; delete `LuceneTest` or repoint it.

## Test rework

- `MessagePostgresIndexITTest` and `MessageFreeTextSearchToggleITTest` are the acceptance gate for the
  surviving backend; the toggle test loses its point once there is nothing to toggle and must be
  reduced to the PostgreSQL assertions.
- `MessageLuceneIndexITTest` and `LuceneTest` disappear with the index. Their *semantic* assertions
  (field coverage, exclusion rules, recreate count, scheduler pickup) are already mirrored in
  `MessagePostgresIndexITTest`; the Lucene-only mechanics (`deleteOnStartup`, query-parser grammar)
  have no successor by design.

## Residual risks after removal

- **Denormalization fidelity** — the searchable text is assembled by `MessageSearchTextBuilder` walking
  ~8 related collections including area/category parent lineage. The maintenance sweep must keep firing
  on every contributing entity change (Message, MessagePart, Area name, Category name, Chart, Reference,
  Attachment) or search text goes stale. This is already true today for the PostgreSQL index; removing
  Lucene just removes the fallback.
- **No wildcard path at all** — with Lucene gone there is no leading/infix wildcard search anywhere. If
  substring search turns out to be required, it needs a separate trigram (`pg_trgm`) index, not a
  tsquery tweak.
- **Accent folding across spellings** (`cafe` ↔ `café`) needs the `unaccent` extension plus a custom
  text-search configuration; PostgreSQL full-text search does not do it out of the box.
