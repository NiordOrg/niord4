# Free-text message search: Lucene ↔ PostgreSQL toggle

The message free-text search backend is **selectable at runtime** so the PostgreSQL implementation can
be evaluated side by side with Lucene *before* Lucene is removed. Lucene is **not** removed and remains
the default; the work left to retire it is tracked in
[`lucene-todo-removal.md`](./lucene-todo-removal.md).

## The setting

`disableLucene` — Boolean, **default `false`** (declared by `MessagePostgresIndex.DISABLE_LUCENE`, with its
value in `niord/src/main/resources/defaults/base/settings.json`; `editable=true`, `web=false`).

- `false` (default) → free-text search uses the Lucene index (`MessageLuceneIndex`) — unchanged behavior.
- `true` → free-text search uses the PostgreSQL backend (`MessagePostgresIndex`).

The setting is read at search time (`MessageSearchService.searchPagedMessageIds`), so it can be flipped on
a running system through the settings REST API; `SettingsService.set(...)` evicts the cache, and the cache
lifespan is 3 minutes, so a flip takes effect promptly.

## The pieces

The schema lives in a Liquibase changeset (`niord/src/main/resources/db/changelog/changes/V002__message_search_text.sql`,
registered in `db.changelog-master.yaml`); the rest lives in `org.niord.core.message`:

- **`MessageSearchTextBuilder`** — the single source of truth for *what text is searchable* for a message
  in a given language (UID, short-id, number, reference ids/descriptions, area + category **parent
  lineage** names, chart numbers, horizontal datum, the localized title/vicinity/publication/source, every
  message-part subject and HTML-stripped details, attachment captions). `MessageLuceneIndex.addMessageToDocument` delegates to it
  (each visited value is added as its own tokenized Lucene field). Both backends consume this walk, so
  they index identical text.

- **`MessagePostgresIndex`** — the PostgreSQL **full-text-search** twin of `MessageLuceneIndex`. It maintains
  a denormalized table `message_search_text(message_id, lang, search_tsv, msg_updated)` whose `search_tsv`
  is a PostgreSQL `tsvector` built with `to_tsvector(<config>, text)` from `MessageSearchTextBuilder`'s
  per-language text. Queries use `websearch_to_tsquery(<config>, query)` (Google-style syntax) matched with
  the `@@` operator. The text-search configuration is chosen per language (`english` / `danish` give proper
  stemming + stop-words; anything else falls back to `simple`). It exposes the same surface as the Lucene
  index: `searchIndex(query, lang, maxHits)` (ids only, no ordering — the caller re-queries and sorts),
  `recreateIndex()` / `recreateIndexAsync()`, a `@Scheduled` incremental maintainer, and
  `allMessagesIndexed()`. The exclusion rules are identical (`shouldAddMessage`: `DELETED` excluded unless
  `messageIndexIncludeDeletedMessages`, and `excludeFromMessageIndex` series suppressed).

- **Liquibase changeset `V002__message_search_text.sql`** — owns the schema: the
  `message_search_text(message_id, lang, search_tsv, msg_updated)` table and a `gin (search_tsv)` index. It
  is in the **master** changelog, so it is created in every deployment (default/prod, local, and the
  integration tests, which run Liquibase: V001 baseline → V002 → base-data seed). No extension is
  needed (`tsvector` / `to_tsvector` / `websearch_to_tsquery` are built into PostgreSQL). The table is a
  derived/rebuildable search artifact, so it is created empty, carries no base-seed rows, and has no foreign
  key to `Message` (orphans are cleaned up by reindexing). `ddl-auto=validate` is unaffected —
  `message_search_text` has no JPA `@Entity`; it is accessed purely via native queries.

`MessageSearchService` branches on the setting:

```java
boolean disableLucene = Boolean.TRUE.equals(
        settingsService.getBoolean(MessagePostgresIndex.DISABLE_LUCENE_SETTING));
ids = disableLucene
        ? messagePostgresIndex.searchIndex(...)
        : messageLuceneIndex.searchIndex(...);
```

## Lifecycle: always-on, symmetric to Lucene

The PostgreSQL index is **always-on**, exactly like `MessageLuceneIndex`:

- The `message_search_text` table (and its `gin (search_tsv)` index) is created by **Liquibase** at startup
  (changeset `V002`, in the master changelog), in every deployment — including the integration tests, which
  run the real Liquibase bootstrap (`ddl-auto=validate`).
- The scheduled maintainer (`cron 48 */1 * * * ?`) **always runs** (it is *not* gated on `disableLucene`),
  so the table is kept fresh whether or not searches currently use it. The incremental marker persists in
  the table (it survives restarts), so unlike the Lucene index there is no delete-and-rebuild on startup.
- The marker is the `msg_updated` time and the id of the table's last row in the order the passes read
  messages (`updated`, then id), so a pass of at most 5,000 that ends among messages sharing one `updated` time
  leaves the rest for the next pass. The marker keeps the time to the microsecond the column stores: cut to the
  millisecond (until 2026-11-12), it lay before messages sharing a time with microseconds, as one the database
  writes (`now()`) has, and the next pass read them again from the first. The table is filled once, a pass of
  5,000 messages at a time; after that a pass finds nothing until a message changes. The native statements name
  their table and do not flush the session first (`MessagePostgresIndex.searchTableStatement`): with Hibernate's
  defaults every statement dirty-checked every message the pass had loaded, and a pass of 5,000 took minutes.
- The Lucene index keeps the same marker, the time and the id of the last message it indexed, in its last
  commit's user data (`lastUpdatedTime`, written in full, and `lastUpdatedId`). It is deleted at every start
  (`messageIndexDeleteOnStartup`) and refilled 5,000 messages a pass, so before the marker had the id (LUCENE-4,
  fixed 2026-11-12) a pass that ended among messages sharing one `updated` time left the rest of them out of the
  index. An index written before then has no such marker and is filled again from the first message.
- Until a pass has made every message searchable again (`MessageLuceneIndex.allMessagesIndexed()`: cleared when
  the index is deleted, set once a pass that found fewer messages than it may index has committed), a free-text
  search answers from the messages indexed so far. It is not refused (the owner's decision of 2026-11-19):
  `MessageSearchService` marks its result, `PagedSearchResultVo.indexIncomplete` (`true`, and left out of the JSON
  otherwise), and the administration app's _Messages_ list shows a warning above such a result. It reads the
  index's state before and after the search, so a re-index that empties the index meanwhile is marked too. The
  mark is set only by the Lucene branch: a search without free text and a PostgreSQL search are never marked. The public API
  takes no free text and serializes the messages alone, a scheduled mailing-list trigger waits for the index in the
  half hour after the start (`MailingListExecutionService.INCOMPLETE_INDEX_WAIT`), the print of a list (PDF) and a
  mailing-list report run by hand carry a line at their top that says messages may be missing
  (`IndexIncompleteNotice`, the `pdf` text `pdf.list.index_incomplete`), and the administration app shows the list's
  warning in the mail dialog and asks before an export, whose archive drops the mark to keep its form. What is left is
  in [`potential-errors.md`](./potential-errors.md), LUCENE-7.
- Each index says when it is complete, for a person or a script that prepares a database: the first time after a
  start or a re-index that its state turns complete, it logs one INFO line, `Message search index complete: Lucene,
  <n> messages indexed in this fill` (or `PostgreSQL`), also when the fill found nothing to do, and no line after
  that; and `GET /rest/monitor/message-index` (system administrators) answers `{"lucene":…,"postgres":…,"complete":…}`
  from the two flags in memory. `MessagePostgresIndex.allMessagesIndexed()` is set once the completing pass has
  committed (it used to be set when the pass had found its messages, before it wrote them) and is false after a
  start until the first pass, about a minute; a pass that commits after a re-index began does not mark the re-index's
  table complete. A re-index (`recreateIndex` of either index) is started by code and tests only: no page or endpoint
  starts one. The Lucene line after a start counts the whole rebuild; the PostgreSQL line after a restart counts only
  what the table lacked, so `0 messages` there means nothing was missing, and its figure counts the passes that
  committed. The state is the backend process's: with several backend pods, each answers for itself. The Lucene line
  follows the line of the pass that completed the index. The operators' guide (`country-kit/docs/operators-guide.md`,
  _Cut-over_) tells how to wait for it.
- Both passes also look five minutes behind their marker (`MessageQueryService.INDEX_LOOK_BACK`, a constant).
  A message's `updated` time is stamped when its transaction writes it, and transactions commit in another
  order: a message stamped before the time a pass read up to, and committed after that pass, lay below the
  marker, and no pass indexed it (LUCENE-6, fixed 2026-11-16). Each pass now also indexes the messages of that
  window that it has not indexed as they are now, at most 5,000, and the marker moves only with the messages after
  it. The PostgreSQL pass knows this exactly: its rows hold the time they were built from. The Lucene pass keeps
  in memory the time of each message of the window it indexed, so after a restart that keeps the index it indexes
  the window once. A missed message that throws while it is indexed is logged and tried again by the next pass,
  without holding back the messages after the marker. A message after the marker that throws while the Lucene pass
  indexes it is logged as an ERROR with its id and skipped: the marker moves past it, the messages after it are
  indexed, and it is indexed again when it is saved again (LUCENE-5, fixed 2026-11-19). A message whose document
  cannot be built keeps the document an earlier pass gave it, if any; one that Lucene refuses when it is added (a
  term too long, for example) has none until then, since its earlier document is deleted first. A skipped message
  does not hold the index's refill open: the pass commits, and `allMessagesIndexed()` is set as without it, so only
  the log tells of it. A failure of the database while a message is read (a dropped connection, a pool timeout, an
  SQL error after which the transaction refuses every later statement) is not skipped: it would fail every later
  message of the batch too, so the pass fails as a whole, the marker stays, and the next pass tries the same
  messages again. A re-index (`recreateIndex`, started by code and tests only) runs the same pass. A transaction that takes longer than five minutes from
  writing a message to its commit is still missed.
- `disableLucene` therefore only routes which index a free-text **search** reads. Both indexes are always
  populated, so the two can be compared at any time and the switch is effectively instant.

This is a deliberate cost (a Postgres search table is now built and maintained on every deployment) and is
accepted because Lucene is going away long-term, making the Postgres index the destination.

## How to evaluate the PostgreSQL backend

1. Set `disableLucene=true` (settings admin page / settings REST API). The table is already built and
   maintained, so searches use it immediately.
2. Run free-text searches and compare against the Lucene results (set it back to `false` to A/B). A full
   rebuild can be forced with `MessagePostgresIndex.recreateIndex()` (there is currently **no REST
   endpoint** for this — same as `MessageLuceneIndex.recreateIndex()`; see *Limitations*).

## How it differs from Lucene (it is "smarter", and that means different)

The PostgreSQL backend is **true full-text search**, deliberately chosen to be smarter than today's Lucene
search rather than a like-for-like copy. The differences (which **must be signed off** before Lucene is
removed, because they change which messages a given query returns):

- **Stemming** — searches match word *stems*: `harbours` matches `harbour`, `warnings`/`warning` match
  `warn`. The current Lucene search does not stem, so this is the headline behavioural change. Asserted by
  `MessageFreeTextSearchToggleITTest` (the plural matches only via the PostgreSQL backend).
- **Whole words only, no wildcards / no infix** — matching is on whole word stems. The current Lucene
  search supports leading/trailing wildcards (`*suffix`, `help*`); full-text search does not. Searching a
  fragment in the middle of a word finds nothing. (Lucene offers its query syntax to signed-in callers
  only: a search from a caller without an identity runs in literal mode, `MessageSearchParams.literalQuery`,
  where every operator is escaped and the text is matched as plain words.)
- **AND is the default operator (Google-style)** — a multi-word query requires *all* words
  (`websearch_to_tsquery`). Today's Lucene search is OR-default. Supported query syntax: space = AND,
  `"quoted phrase"` (adjacency), `or`, and `-excluded`.
- **Stop words are ignored** — common words (`the`, `a`, …) are dropped by the language configuration. A
  query consisting only of stop words matches nothing.
- **Accent / language handling follows the text-search configuration**, not Lucene's 44-char fold. `english`
  and `danish` give proper per-language stemming/stop-words; other languages fall back to `simple` (tokenize
  + lowercase, no stemming). There is no cross-spelling accent folding (e.g. ASCII `cafe` does not match
  `café`) unless an `unaccent`-based configuration is added later.
- **Relevance ranking ("best match first")** — a free-text search with **no explicit sort** now returns
  results ordered by relevance (`ts_rank`): the message where the term appears more often / more
  prominently comes first. `searchIndex` returns ids in `ts_rank` order and `MessageService` preserves that
  order (it skips the JPA sort for the free-text/no-explicit-sort case). This is wired uniformly: the Lucene
  backend already returns ids in score order, so it gets best-match-first too. An explicit
  `sortBy=area|date|publish_date|follow_up_date|id` still overrides it. Implemented via a `RELEVANCE`
  default sort for free-text in `MessageSearchParams.checkSortByDomain`.
- **Preserved:** case-insensitivity, HTML stripping (`html2txt`) before indexing, and the index-exclusion
  rules (`DELETED` status and `excludeFromMessageIndex` series). Quoted-phrase order sensitivity is also
  preserved (now via `tsvector` positional adjacency rather than Lucene phrase positions).

## Limitations / things to revisit before committing to removal

- **No REST trigger for `recreateIndex()`** — neither backend exposes one today; an admin/REST hook (and
  a UI affordance) would make flipping the toggle operationally smoother.
- **Relevance is the default sort for free-text searches on both backends** — best-match-first rather
  than the area/domain default sort. No test asserted free-text ordering before, so the UI effect should be
  confirmed; an explicit sort still overrides it.
- **Accent folding** — full-text search does not fold accents across spellings; if cross-spelling accent
  insensitivity (e.g. `cafe` ↔ `café`) is required, add the `unaccent` extension + a custom text-search
  configuration.
- **Both indexes are maintained on every deployment.** Since the PostgreSQL maintainer is always-on (not
  gated on `disableLucene`), every deployment builds and maintains the `message_search_text` table in the
  background even while searches still use Lucene — accepted because Lucene is being retired. Removing
  Lucene takes its index and scheduler away and leaves only the PostgreSQL index
  ([`lucene-todo-removal.md`](./lucene-todo-removal.md)).
- **Stemming / whole-word / AND-default semantics** (above) — the headline behavioural decisions to sign
  off on before replacing Lucene.

## Tests

- `MessagePostgresIndexITTest` — the PostgreSQL full-text twin of `MessageLuceneIndexITTest` (field
  coverage, **stemming**, AND-default, `-`-exclusion, quoted-phrase adjacency, case-insensitivity, HTML
  stripping, **relevance ordering** (`ts_rank`), `DELETED` + series exclusion, recreate count, the always-on
  incremental scheduler, that a pass right after a full rebuild indexes nothing and the next one only what
  changed, that a pass cut among messages sharing one `updated` time, with microseconds, leaves the rest for the
  next, that the incremental marker advances past an excluded newest message, and that a message committed after
  a pass read past its time is indexed by the next pass).
- `MessageLuceneIndexITTest` holds the Lucene index to the same marker: a pass cut among messages sharing one
  `updated` time, with microseconds, leaves the rest for the next, and a message committed after a pass read past
  its time is indexed by the next pass. `DeliveryFaultInjectionITTest` checks that such a message that throws
  does not hold back the messages after the marker.
- `MessageFreeTextSearchToggleITTest` — end-to-end through `MessageSearchService.search`: the toggle
  switches backends (the plural `harbours` matches only the stemming PostgreSQL backend), results come back
  in relevance order (best match first), plus the match-nothing semantics.
- `MessageLuceneIndexStartWindowTest` and `MessageSearchIndexIncompleteITTest` — the window after a start: the
  index says it is not complete until a pass has committed every message, and a free-text search in the window
  answers as before with its result marked, on the service and the editors' search, never on a search without
  free text, on the PostgreSQL backend or on the public API.
