# AtoN — follow-up TODO

## Add a trigram GIN index to accelerate AtoN tag-value search

**Status:** not done.

**Why.** AtoN name search runs `lower("v") LIKE '%' || lower(:value) || '%'` against `AtonTag.v`
(`AtonService.buildSearchCriteria` → `CriteriaHelper.like`). The existing `aton_tag_v` btree index
cannot accelerate a leading/infix substring `LIKE`, so this is effectively a sequential scan. This was
already the case before Hibernate Search was removed (Lucene only ever backed the dead
`searchAtonTagKeys` path), so it is **not a regression** — but a trigram GIN index makes the surviving
SQL path fast.

**Change.** Add a Liquibase changeset (the next free number is the one after the highest `Vnnn` in `niord/src/main/resources/db/changelog/changes/`) included
from `niord/src/main/resources/db/changelog/db.changelog-master.yaml`:

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;
CREATE INDEX aton_tag_v_trgm ON public."AtonTag" USING gin (lower(v) gin_trgm_ops);
```

The functional expression `lower(v)` matches the `lower("v")` the query emits (column `v` is
lowercase; `globally_quoted_identifiers` quotes it as `"v"`, same column). `gin_trgm_ops` is what
accelerates `LIKE '%…%'`.

**Notes / caveats.**
- Every integration test runs Liquibase (the `changelog-with-seed.yaml` changelog builds the master
  changelog and then layers the base-data seed on top), so a new changeset reaches the whole IT suite
  automatically. Hibernate `validate` does not check indexes/extensions, so adding it does not affect
  validation.
- `pg_trgm` is a standard contrib extension present in the `imresamu/postgis` image (a multi-arch PostGIS build) used by tests and
  deployment, so `CREATE EXTENSION` succeeds.
- Behavior is unchanged — this is purely a performance optimization.

**Optional, independent:** the V001 baseline already carries GIST indexes for the two predicate sites
that use them (`aton_node_geom` on `AtonNode.geometry` for `ST_Within`, `feature_geom` on
`Feature.geometry` for `ST_Intersects`). The remaining `GEOMETRY` columns without a GIST index are
`Area` and `Chart`; adding `CREATE INDEX … USING gist(geometry)` on those is a separate additive win,
unrelated to the Lucene removal.
