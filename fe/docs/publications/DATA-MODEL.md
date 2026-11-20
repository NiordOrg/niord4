# Data model — publications redesign

> **A shared specification, not a repository-local document.** This file and `API-CONTRACT.md` are the
> publications specification, held here and read from both repositories: this one (the Angular client)
> and `niord` (the backend that implements the model). Ownership splits cleanly. **This document owns
> the model** — what an entity means, which columns exist, what each invariant requires. **The backend
> owns the schema** — its JPA entities are the source of truth for DDL, so where an entity and a DDL
> sketch here disagree the entity wins and this file is corrected to match. `API-CONTRACT.md` is the
> wire half of the same specification.
>
> **`scripts/publications/` guards it, and parses it by heading.** `gen-rule-ids.js` exports the
> invariant ids of section 8, and `gen-field-manifest.js` the field tables of sections 3.1 to 3.10,
> into test resources in the backend module (`be/niord/src/test/resources/`). The exports travel to the
> backend and its tests read them; the `# source-sha256` header each export carries is not read there —
> it is how the frontend side notices that the specification moved and the export did not. `spec-check.js` re-runs both and
> compares this file with `API-CONTRACT.md` for the defect classes that have actually occurred here:
> one identifier described two ways, one error code carrying two HTTP statuses, two routes sharing a
> path template, a section reference pointing at nothing.
>
> **So: do not renumber or restructure the headings.** After editing section 8 or sections 3.1–3.10,
> regenerate the exports (`node scripts/publications/gen-rule-ids.js --write`,
> `node scripts/publications/gen-field-manifest.js --write`) and commit the specification and the
> exports together. Run `node scripts/publications/spec-check.js` after any edit to either
> specification file.
>
> **All three scripts resolve the backend as this repository's `be/niord` module** (`NIORD_REPO` or
> `--out` point them elsewhere). Without it the generators cannot write and `spec-check.js` reports the
> exports as missing rather than stale.
>
> **Known baseline, so a first run is not read as your own damage.** On an untouched checkout
> `spec-check.js` reports blockers that predate any edit: both exports are stale against the current
> text and must be regenerated with the backend, and four "path collision" hits are query-string
> spellings of one route inside `API-CONTRACT.md` that the checker cannot yet tell apart from a real
> duplicate. A finding naming a route or an identifier you did not touch is one of these; a finding
> naming one you did is yours.
>
> **Citations here name a path and a line (`File.java:NN`). The path is the durable half.** Line
> numbers are as of writing and rot as the backend moves; search for the identifier rather than
> trusting the number.
>
> **Provenance citations no longer resolve, and that is deliberate.** This text was merged from a set
> of working documents — a verification survey, a decisions record, a capability ledger, a cut-off
> model note, a rulings log — none of which ships. Inline references of the form
> `MODEL-VERIFICATION §x`, `CUTOFF-MODEL.md`, "a settled decision" and the bare ruling ids (`R2`,
> `C7`, `R-xxvi`, `DM-Q…`) are kept as an audit trail of *why* a rule reads the way it does; they name
> nothing you can open. The standing authorities are the backend entities (for the schema) and this
> text (for the model).

*Field-level specification for the series/issues model. Written 2026-08-21 from five specialist surveys of
the real repo, then reviewed for completeness against the nine signed decisions and attacked adversarially.*

> **Status: v2 — all eight blocking rulings ratified (Rasmus, 2026-08-21) and applied in the text below.**
> Both review passes found real contradictions inside this document; every one of them is now resolved
> *in place*, not in an appendix. The sharpest was `aliveAtCutoff`, specified three incompatible ways and
> capable of rejecting 531 of 1,031 message-backed series at the first `INSERT` — it is now one rule,
> stated identically at every site (R2). The rulings log that recorded the reasoning is one of the
> merged-away sources named in the preamble; **this document is the specification.** The schema can be
> built from this file.
>
> The eight, and where each lands: **R1** the render is extracted out of the HTTP layer into an in-process
> service taking the ordered member list, and **no message tag is created at all** — §2.5, §4.4 step 10,
> §4.5 step 4, RI-14; **R2** `aliveAtCutoff` — §3.1.3, §3.1.5, §3.6.5, §5.5, §5.5.6, S-2/S-3; **R3**
> `seriesId` is an authored `VARCHAR(64)` and legacy provenance is `legacyTemplateId` — §1.2, §3.1.1, §8.1,
> X-1; **R4** five column-level contradictions — `nominalCutoffDay`, the deduplicated snapshot header, the
> new `intervalFromSource`/`intervalToSource` columns, and the `publicTo` immutability carve-out; **R5**
> symmetric capping and I-18/I-19; **R6** the no-revision rule scoped to newly published issues; **R7** the
> member-set designation predicate (RI-15); **R8** the importer writes `publicWindowSource = DERIVED` for
> any cadenced series.
>
> **Reconciled against `API-CONTRACT.md` in a single pass (2026-08-21).** The two documents had drifted at
> their seams; every disagreement is now closed **in the text on both sides**, never as a note or an
> appendix. What moved here: `snapshotIntervalFrom` is confirmed a real column and the contract's
> "not a column" ruling is withdrawn; the step-10 post-render assertion is **`500 REPORT_GENERATION_FAILED`**,
> with `409 MISSING_FILE_FOR_LANGUAGE` surviving only as P4's pre-flight; `archivePath` + `archived[]` reach
> `PUBLISHED` and `FILE_REPLACED_MANUALLY`, not `AMENDED` alone; **D-6** is relaxed so `replacedBy`/`replacedAt`
> mean "this upload replaced existing bytes" rather than "`fileSource` is `UPLOADED`"; the neighbour lookup is
> `status IN (PUBLISHED, RETIRED)` at every site; `OVERRIDE_ADDED`/`OVERRIDE_REMOVED` carry the plural detail
> shape; §6.4's duplicate `IssueMember` field table is **deleted** the way R4 deleted §6.3's, settling
> `reasonCode` (not a column), `appliedAtPublish` (not `staleAtFreeze`) and `legacyType` (derived) in one
> place each; and `publicationPreviewRootPath` joins `publicationArchiveRootPath` under X-6.

The one companion document that ships is [`API-CONTRACT.md`](API-CONTRACT.md) — the wire contract built
on this model. The rules as an admin meets them are in [`domain-rules.md`](domain-rules.md); the
cut-over material is kept outside this repository. Everything else this text cites is merged-away
provenance; see the preamble.

---

## Amendment index — read this before the body

**The body below is the model as specified in August 2026. The amendment sections at the foot are the model
as it shipped, and they win wherever the two disagree.** The body is not rewritten, so the history stays
readable — which is only safe if a reader knows the amendments exist.

**Two things about how this file is laid out, because neither is discoverable.** The body's sections run
`1, 2, 3, 4, 6, 7, 5, 8, 9, 10` — out of order on purpose, because the export scripts parse by heading and
renumbering would silently repoint them, so nothing is ever moved to tidy the order. And the sections listed
below are only the *trailing* amendments: later ones are written **inline**, in the passage they amend, and
carry the word "Amended" or "Superseded". Search for both as well as reading the foot.

The trailing amendments, in order:

| Section | What it settles |
|---|---|
| [Amendments — 2026-08-27 (Stage A)](#amendments--2026-08-27-stage-a-of-the-work-queue) | `PublicationSeries.cutoffDefault` + migration **V9** · cut-off vs publication moment (`cutoffStampedAt` vs `publishedAt`/`publishedBy`) and how the importer derives each · chaining per destination series and the five-minute sibling rule · `VISIBILITY_CAPPED` / `IMPORTED` written · coverage-based gap detection · weekly naming · the 42-not-47 figure |
| [Amendments — 2026-08-28 (Stages B/C and the verification pass)](#amendments--2026-08-28-stages-bc-and-the-verification-pass) | migration **V10** (resolved criteria operands frozen on a published issue) · membership uniqueness is **V7**, not a later migration · `publishedAt` is the release instant · `intervalToSource = RECOVERED` · `PublicationIssueDesc.replacedBy`/`replacedAt` |
| [Amendments — 2026-08-28 (B-5 / B-11 / B-12)](#amendments--2026-08-28-b-5--b-11--b-12) | no schema change — drift, the curation why-line, `publishedIssueCount` and the pre-flight rows are computed on read · `IssueListService` is the one gap synthesizer · the count predicate · the test-suite floor |
| [Amendments — 2026-08-28 (§7.11 audit vocabulary as shipped)](#amendments--2026-08-28-711-audit-vocabulary-as-shipped) | the vocabulary is the backend's `AuditAction` enum and the §7.11 table is a reading aid, not a count; DM-Q2 is answered by the series-level actions; `AUTO_RELEASE_ABORTED` is not in it |
| [Amendments — 2026-08-29 (E-6 backend half)](#amendments--2026-08-29-e-6-backend-half) | migration **V12** (issue read-path indexes) · `AuditAction` is an enum in code · `@NotNull` on the 35 non-nullable columns |
| [Amendments — 2026-08-29 (R-xxvi: the owner domain and availability)](#amendments--2026-08-29-r-xxvi-the-owner-domain-and-availability) | `PublicationSeries.domain` is **NOT NULL** and means the OWNER · new `availability` column · new `PublicationSeries_AvailableDomain` join table · migration **V13** · `snapshotDomainId` is *not* the owner · §7.11 gains `OWNER_TRANSFERRED` |
| [Amendments — the new model serves the public (V17)](#amendments--the-new-model-serves-the-public-v17) | migration **V17** — `PublicationSeries.publicAuthority` dropped, the `SERIES_AUTHORITY_CHANGED` audit rows deleted, `ShadowDiffRun` dropped · the public list is the union with no switch · the go/no-go is `GET /publication-series/import-check` |
| [Amendments — 2026-09-13 (compilation series)](#amendments--2026-09-13-compilation-series-and-section-8-caught-up) | migration **V18** · `TimeRelation.COMPILED_FROM_SOURCE` and the `tiles()` / `compiled()` pair · `PublicationSeries.sourceSeries` · `IssueMember.sourceIssuePublicId` and `MemberSource.COMPILED` · `PublicationIssue.snapshotSourceSeriesId` / `.snapshotSourceIssueIds` and `MembershipProvenance.COMPILED` · rules S-19…S-25, RI-16, RI-17, I-20 |

Tiers and error codes are the wire contract's business: their amendments are in `API-CONTRACT.md`, whose
§1.3 (tiers) and §7.3 (catalogue) are both superseded there.

---

# Data model — entities and fields

*Part 1 of the publications data-model document: scope, the identity strategy, the entity map, and the full field table for every entity. Status transitions, the criteria JSON schema, the enumeration definitions and the traceability matrix are separate sections of this document and are referenced, not restated, here.*

---

## 1. Scope and identity strategy

### 1.1 What this section covers

Nine tables in a new package `org.niord.core.publication.series`, plus two ALTERs against the existing `PublicationCategory` pair:

**New:** `PublicationSeries` (+ its `PublicationSeries_languages` collection table), `PublicationSeriesDesc`, `PublicationIssue`, `PublicationIssueDesc`, `IssueMember`, `IssueOverride`, `IssueAuditEntry`.
**Reused essentially unchanged:** `PublicationCategory`, `PublicationCategoryDesc` — 15 of the 17 ledger rows on them are PRESERVED; the redesign changes one default and makes one existing column visible.
**Untouched:** the legacy `Publication` / `PublicationDesc` tables. They stay in place for the public union and as the legacy JSON interchange target; nothing here migrates or drops them.

### 1.2 Identity — the shared legacy sequence, stated explicitly

Every entity here extends `BaseEntity<Integer>` or `VersionedEntity<Integer>`, so every one inherits:

```java
@Id
@GeneratedValue(strategy = GenerationType.AUTO)
@Column(unique = true, nullable = false)
protected ID id;
```
— `niord-core/.../model/BaseEntity.java:24-30` (verified).

**The consequence that hand-written DDL must respect.** `niord-dk/niord-dk-web/src/main/resources/application.properties:86` sets

```
quarkus.hibernate-orm.unsupported-properties."hibernate.id.db_structure_naming_strategy"=legacy
```

which restores Hibernate 5's naming for the implicit id-generator structure: **a single shared generator table for the whole schema**, not a per-entity `<Table>_SEQ`. Combined with `quarkus.hibernate-orm.dialect=org.hibernate.dialect.MySQLDialect` (`:74`) — MySQL has no sequences, so the sequence-style generator falls back to a table — every id in the system is drawn from **one shared row in one shared table**. There is no per-entity generator and no per-entity `AUTO_INCREMENT` anywhere in the schema, and no `@SequenceGenerator` / `@TableGenerator` annotation exists anywhere in `niord` (verified by grep — zero hits).

Therefore:

- Every `id` column below is declared **`INTEGER NOT NULL`, primary key, with no `AUTO_INCREMENT` clause and no per-table sequence.**
- Do not add `@SequenceGenerator`, `@TableGenerator` or `strategy = IDENTITY` to any new entity. Inheriting `BaseEntity` is the whole contract.
- Before hand-applying DDL, read the deployed generator table's current value; new inserts share the same counter as `Message`, `Publication` and everything else.

**Two external handles sit beside the numeric id**, following the repo's split between configuration rows and instance rows:

| Row kind | Key style | Precedent |
|---|---|---|
| **Configuration** — `PublicationSeries`, `PublicationCategory` | human-authored, immutable, unique `VARCHAR` business key | `MessageSeries.seriesId` (`MessageSeries.java:50-51`), `PublicationCategory.categoryId` (`PublicationCategory.java:49-51`), `FmReport.reportId` (`FmReport.java:56-57`), `Domain.domainId` |
| **Instance** — `PublicationIssue` | UUID string, `@Column(nullable = false, unique = true, length = 36)`, minted by `UidUtils.newUid()` | `Publication.publicationId` (`Publication.java:92-93`), `Message.uid` (`Message.java:96-97`) |

**Resolution, against fragment A's proposal that `seriesId` be a UUID adopted verbatim from the legacy template's `publicationId`:** `seriesId` is **human-authored**, because a series is a configuration row and every configuration row in this codebase carries an authored key, and because the argument for the UUID — "one resolver can answer `publication=<x>`" — does not hold: `publication=` always names an **issue**, never a series (a series has no member set to resolve to). Import provenance and IMP-008's template relinking are carried instead by an explicit `legacyTemplateId VARCHAR(36)` column (§3.1). **Ratified as R3 on 2026-08-21; this is no longer an open question.** The uniqueness table (§8.1) and the cross-entity collision rule (X-1) are written to it: `seriesId` is a `VARCHAR(64)` authored key in its own keyspace, and only `PublicationIssue.publicId`, `PublicationSeries.legacyTemplateId` and legacy `Publication.publicationId` share the UUID value space.

**Naming hazard, recorded once.** `seriesId` already means *message series* elsewhere in this codebase (`MessageSeries.seriesId`, `MessageSearchParams.seriesIds`, and the `messageSeries` criteria node). The publication series key appears only as `PublicationSeries.seriesId` on the entity and as `publicationSeriesId` in every VO and query parameter. Never put a bare `seriesId` parameter on any surface that also accepts message criteria.

### 1.3 Column-level conventions taken from the repo

| Convention | Applied as | Source (verified) |
|---|---|---|
| `@Version int version` + `created`/`updated` stamped in `@PrePersist`/`@PreUpdate` | `PublicationSeries`, `PublicationIssue`, `IssueOverride` | `VersionedEntity.java:28-53` |
| Append-only audit rows: extend `BaseEntity`, own `@NotNull created` with `@PrePersist`, no `@Version`, no `updated`, nullable `@ManyToOne User` | `IssueAuditEntry` | `MessageHistory.java:43-70` |
| Per-language data in a `*Desc` entity extending `DescEntity<Owner>`; owner implements `ILocalizable<XDesc>` with `@OneToMany(cascade = ALL, mappedBy = "entity", orphanRemoval = true)`; desc table carries `@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"lang","entity_id"}))` | `PublicationSeriesDesc`, `PublicationIssueDesc` | `DescEntity.java:30-56`; `PublicationDesc.java:31-34`; `Publication.java:86, 168-169` |
| `@Enumerated(EnumType.STRING)` with **no** explicit `@Column(length)` | every enum column below | `Publication.java:99-122` |
| Explicit `@Column(length = …)` for a `String` with a known bound | reasons, paths, links, uids | `ReferenceDesc.java:35` (512); `MessageDesc.java:37-48` (1024/2048); `Message.uid` (36), `Message.repoPath` (128) |
| Structured columns: `@Column(columnDefinition = "TEXT") @Convert(converter = …)` | `criteria`, `criteriaSnapshot`, `criteriaOverride`, `reportParams` (**on both `PublicationSeries` and `PublicationIssue`**), `detail`, `snapshotSeriesIds` | `Publication.java:158-166`; `FmReport.java:70-80`; `Setting.java:44-45` |
| DB defaults expressed through `columnDefinition` | `memberCount`, `cutoffReconstructed`, … | `MessageTag.java:94-96` (`INT default 0`); `Message.java:143-144` (`FLOAT default 0.0`) |
| Ordered string list as a child table: `@OrderColumn(name = "indexNo") @ElementCollection List<String>` | `PublicationSeries.languages` | `MailingListTrigger.java:139-141`; `MessageSeries.java:68-69` |
| Nominal schedule = an enum for the day + a `"HH:mm"` `String` for the time + an explicit timezone `String` | the series' nominal cut-off | `MailingListTrigger.java:79-96` (`scheduleType` / `scheduledExecutionTime` / `scheduledExecutionTimeZone`) |
| Self-reference by `@ManyToOne` to the same entity | `PublicationIssue.supersedes` | `Publication.template` (`Publication.java:111-112`) |

**Table names.** `SequenceEntity` (`SequenceEntity.java:31`) is the only `@Table(name = …)` in `niord-core` — verified by grep. Every other table takes the Hibernate default, which is the **entity name in CamelCase**. All table names below are therefore literally the entity class names, exactly as `Publication` / `PublicationDesc` already are.

**Enum columns are native MySQL `ENUM`, not `VARCHAR(255)`.** This section previously asserted the opposite, and repeated it down every enum row below. It was wrong: Hibernate 6.4 with `MySQLDialect` emits `enum('A','B',…)` from a plain `@Enumerated(EnumType.STRING)` with no `@Column(length)` — verified against a generated baseline where **15** such columns already exist, e.g. `type enum ('CATEGORY','TEMPLATE')`, from exactly that annotation at `Publication.java:99-122`. The error is recorded rather than quietly corrected because it is the whole argument for generating the DDL: an agent hand-writing 1,870 lines of schema from this document would have produced `VARCHAR(255)` throughout, and the mismatch would have surfaced much later as a validation failure or a silent behavioural difference. **The entities are the source of truth for DDL; this document is the source of truth for the model.** Do not add `@Column(length = …)` to tighten these: it buys nothing and produces a schema the entities would not reproduce. 

**The operational consequence, which this document did not carry:** a native `ENUM` **rejects values outside its list**, so adding a Java enum constant later requires an `ALTER TABLE`. That makes every speculative enum value — DM-Q15 and DM-Q16 among them — more expensive than it looks, and it is a real cost of the status quo rather than of this redesign. 

The enum rows in this document were swept once against a generated schema baseline. Neither that sweep script nor its output is kept: the entities in the backend repository are the standing authority for what each enum column is, and `scripts/publications/gen-field-manifest.js` is what keeps the field tables below and those entities bound to each other.

**Physical delivery — settled, and no longer an open question.** This paragraph previously recorded that there was "no Flyway, no Liquibase and no `.sql` anywhere" and that delivery was **DM-Q1**. Both are now out of date: `B0.0.7` wired **Flyway** into the deployable with `migrate-at-start`, `baseline-on-migrate` and `baseline-version=0`, with migrations living in `niord-core` beside the entities and travelling in its jar. An existing database is therefore adopted at version 0 rather than rebuilt. `generation` stays `none` outside `%dev` — Flyway and Hibernate must not both own the schema. This section still specifies the **logical** model and the DDL it implies; the DDL itself is generated from the entities and checked in as `schema/baseline-MaDaMe.sql`.

---

## 2. Entity overview

### 2.1 The tables

| # | Entity / table | Base class | Rows per parent | What it is |
|---|---|---|---|---|
| 1 | `PublicationSeries` | `VersionedEntity<Integer>`, `ILocalizable<PublicationSeriesDesc>` | — | The **definition**: what an issue of this publication *is*, how often, over which query, rendered by which report. Replaces the legacy template publication. |
| 2 | `PublicationSeries_languages` | *(collection table)* | 1..N per series | The series' configured language list, ordered. Declared, never inferred from which desc rows happen to exist. |
| 2b | `PublicationSeries_AvailableDomain` | *(join table)* | 0..N per series | **New 2026-08-29 (R-xxvi).** The domains a publication is shared with when `availability = SELECTED_DOMAINS`. Explicit `@JoinTable`, owner side on the series, like `Category_Domain`. Never contains the series' own owner domain. |
| 3 | `PublicationSeriesDesc` | `DescEntity<PublicationSeries>` | exactly one per configured language | The series' per-language **patterns**: display name, name-suggestion, file-name, message-reference, link. **This table is C5.** |
| 4 | `PublicationIssue` | `VersionedEntity<Integer>`, `ILocalizable<PublicationIssueDesc>` | 0..N per series | One concrete **udgave**: its interval, its stamped cut-off, its public window, its frozen snapshot header. |
| 5 | `PublicationIssueDesc` | `DescEntity<PublicationIssue>` | exactly one per configured language | The issue's per-language **concrete values**: name, file name, file path, link, reference format, and C6's `fileSource` triple. |
| 6 | `IssueMember` | `BaseEntity<Integer>` | 0..N per issue, **written only at freeze** | The frozen member snapshot: one row per member, keyed on message `uid`, carrying the four mutable facts as at freeze plus the recorded print order. |
| 7 | `IssueOverride` | `VersionedEntity<Integer>` | 0..N per issue | Live curation state — one manual include or exclude, with author, reason and timestamp. |
| 8 | `IssueAuditEntry` | `BaseEntity<Integer>` (append-only) | 0..N per issue | The Historik panel. Never updated, never deleted while the issue exists. |
| 9 | `PublicationCategory` + `PublicationCategoryDesc` | *(existing, `BaseEntity<Integer>` / `DescEntity`)* | many series → one category | Grouping, the public on/off switch (`publish`) and the public sort key (`priority`). |

### 2.2 How they relate

```
PublicationCategory 1 ──< N PublicationSeries                    (category_id, NOT NULL — CAT-001)
PublicationCategory 1 ──< N PublicationCategoryDesc              (one per model language)
Domain              0..1 ──< N PublicationSeries                 (domain_id, NULLABLE = global — ISS-020/VIS-013)
                                                                 ^ amended 2026-08-29 (R-xxvi): 1 ──< N,
                                                                   domain_id NOT NULL = the OWNER (V13)
Domain              N >──< N PublicationSeries                   (PublicationSeries_AvailableDomain — R-xxvi:
                                                                  who else may see and cite it)

PublicationSeries   1 ──< N PublicationSeries_languages          (ordered: the configured language list)
PublicationSeries   1 ──< N PublicationSeriesDesc                (exactly one per configured language)
PublicationSeries   1 ──< N PublicationIssue                     (series_id, NOT NULL)

PublicationIssue    1 ──< N PublicationIssueDesc                 (exactly one per configured language)
PublicationIssue    1 ──< N IssueMember                          (frozen; UNIQUE(issue_id, messageUid))
PublicationIssue    1 ──< N IssueOverride                        (live;   UNIQUE(issue_id, messageUid))
PublicationIssue    1 ──< N IssueAuditEntry                      (append-only)
PublicationIssue    0..1 ──< N PublicationIssue                   (supersedes_id — self-FK, same series)

IssueOverride       0..1 ──< N IssueMember                       (override_id — set iff source = OVERRIDE_INCLUDE)
Message             0..1 ──< N IssueMember                       (message_id — convenience only; messageUid is the key)
Message             0..1 ──< N IssueOverride                     (message_id — convenience only)
User                0..1 ──< N { PublicationIssue.publishedBy, .retiredBy,
                                 PublicationIssue Desc.replacedBy,
                                 IssueOverride.author, IssueAuditEntry.user,
                                 PublicationSeries.statusChangedBy }
```

**FK creation order** (each references only what precedes it): `PublicationCategory` → `PublicationSeries` → `PublicationSeries_languages`, `PublicationSeriesDesc` → `PublicationIssue` → `IssueOverride` → `IssueMember` (references both `PublicationIssue` and `IssueOverride`) → `PublicationIssueDesc`, `IssueAuditEntry`.

### 2.3 Why there are two Desc tables, and what each one holds

C5 is signed as a **one-way door**, and it is the reason the desc layer is doubled rather than singular. Its failure mode is silent: `ILocalizable.getDescs(DataFilter)` falls back to `descs.get(0)` when no language matches (`niord-model/.../ILocalizable.java:60-77`), so a collapsed single-language pattern does not error — it quietly serves Danish titles, Danish file names and Danish citations to English consumers. Of 1,077 production publications, 1,037 have different links per language, 1,039 different file names and 1,051 different titles.

The split between the two Desc tables is **pattern vs. concrete value**:

| | `PublicationSeriesDesc` (pattern) | `PublicationIssueDesc` (concrete) |
|---|---|---|
| Name | `nameSuggestionPattern` — `EfS uge ${week} - ${year}` | `name` — `EfS uge 27 - 2026` |
| File | `fileNamePattern` — `EfS-Uge-${week}-${year}.pdf` | `fileName` + `filePath` + `link` |
| Citation | `messageReferenceFormat` — the authored pattern | `messageReferenceFormat` — **the pattern already token-substituted for this issue** |
| Display | `name` — the series' own display name, distinct from the pattern that names its issues (SER-005) | — |

**Resolution — the issue desc *does* carry `messageReferenceFormat`,** against fragment B's "deliberately absent, it is a series-level pattern". Two reasons: legacy already substitutes it onto the concrete row at save (`Publication.java:322-328` via `PublicationTemplateUpdateCtx.str()`), and the substituted text is what is written *into stored message HTML* at citation time — so it must be frozen exactly as it was, for the same reason the member snapshot is frozen. If it were expanded on read from the series pattern, editing the series pattern would silently change what a published 2019 issue's citation format says. `titleFormat` genuinely is absent from the issue desc — that one really is a pattern with no per-issue counterpart.

### 2.4 Naming convention for frozen data, so the two prefixes never blur

- **`snapshot*`** — columns on `PublicationIssue` that describe the **whole snapshot** (the inputs that produced it): `snapshotFrozenAt`, `snapshotTimeRelation`, `snapshotAliveAtCutoff`, `snapshotIntervalFrom`, `snapshotSeriesIds`, `snapshotDomainId`, `snapshotSortBy`, `snapshotSortOrder`.
- **`frozen*`** — columns on `IssueMember` that capture **one message's own mutable scalar** as at freeze: `frozenStatus`, `frozenType`, `frozenMainType`, `frozenPublishDateFrom`, `frozenPublishDateTo`, `frozenShortId`.

`criteriaSnapshot` is the third kind: the effective criteria *document* at freeze. The interval lower bound is carried as **`snapshotIntervalFrom`** and it is **not** redundant with the live `intervalFrom` column: amend does not change `intervalFrom`, but a later **retro-creation of a gap issue does change the neighbour chain** around an already-published issue, so the live column can stop describing what that issue was actually resolved against. It is defined once, in §3.6.5, and written by §4.4 step 7. (An earlier reading called a `frozenIntervalFrom` redundant on the C3 grounds that amend never moves `intervalFrom`; that argument does not cover retro-creation, which R5 makes a first-class flow.)

### 2.5 Deliberately absent columns, recorded so nobody restores them

| Absent | Legacy counterpart | Why |
|---|---|---|
| `messageTag` FK on the issue | `Publication.messageTag` (`Publication.java:141-142`) | **C8 as amended (R1)** — no tag is minted, and **no temp tag is created either**. The render is an in-process call taking the resolved, ordered member list (§4.4 step 10); nothing anywhere reads membership from a tag. |
| `messageTagFormat` | `Publication.messageTagFormat` (`:131-132`) | C8. The naming capability survives as `PublicationSeriesDesc.nameSuggestionPattern`. |
| `messageTagFilter` | `Publication.messageTagFilter` (`:134-139`) | **C4** — replaced by structured `criteria` + `timeRelation` + `aliveAtCutoff`. |
| `advancedExpression` | — | C4 cuts the hatch from Phase 1 entirely. The criteria document is a discriminated union carrying its own `schemaVersion`, so a hatch is purely additive later and needs no schema change. |
| `revision` on the issue, and any revision segment in a **newly published** issue's file path | `Publication.revision` (`:95-97`), and `toVo()` handing out `revision + 1` (`:268-270`) | **C3** requires a *stable* public URL. Legacy's revision folder is precisely why an amend changes the URL today and why every stored citation resolves to the superseded document. <br>**Scope, per R6:** no `revision` **column** exists on either new entity, ever. The no-revision-segment rule binds **issues this system publishes**. Imported issues carry their legacy `repoPath`, `filePath` and `link` **verbatim**, revision segment and all — legacy stores bytes at `<repoPath>/<revision>/<fileName>` and that exact string is written into stored message HTML as the citation href, so rewriting it 404s the file and every citation pointing at it. See §3.6.1's `repoPath` and D-4. |
| `printSettings` as a free-form map | `Publication.printSettings` (`:158-161`) | **ADD-005** — `report.pdf` instantiates *both* `MessagePrintParams` and `MessageSearchParams` from the same query string, so any key outside the six `MessagePrintParams` reads (`MessagePrintParams.java:38-63`) falls through to the **message search** and silently rewrites what is in the PDF and in what order. Replaced by typed columns (§3.1). |
| `template` self-reference on the series | `Publication.template` (`:111-112`) | Series and issue are separate entities; inheritance is a relation, not a self-join. |
| `criteriaVersion` as a column | — | The criteria document carries `schemaVersion` at its root. A column that can disagree with the document it versions is a bug factory; the converter parses the document on every read anyway. |
| `legacyType` on the series | — | **Ruling X2.** `contentMode` is bijective with legacy `PublicationType` (verified: `NONE / LINK / REPOSITORY / MESSAGE_REPORT`), so the adapter derives `PublicationVo.type` by a total pure mapping. A second stored column can drift from the first, which is strictly worse than deriving. §7.12 says the same thing about the same enum; if the two ever disagree again, this row wins. |
| `reasonCode` on `IssueMember` | — | **Ruling X3.** Every value it would carry (`MATCHED_PUBLISHED_IN_INTERVAL` / `MATCHED_IN_FORCE_AT_CUTOFF` / `MANUAL_INCLUDE` / `IMPORTED_FROM_LEGACY_TAG`) is derivable from `source` plus the snapshot header's `snapshotTimeRelation`, and a stored derivable enum is a second source of truth that can disagree with the first. The machine half of the settled why-line survives as the **wire** field `MemberReasonVo.kind` — `MemberReasonKind` is listed as *(wire)*, owned by the resolver, in §7.13. `reasonNote` **is** a column: the honest import note is a stored string nothing can derive. See §3.8. |
| `staleAtFreeze` on `IssueOverride`, and `overrideApplied` on `IssueMember` | — | **Ruling X4.** One boolean, one name, one row: **`appliedAtPublish` on `IssueOverride`** (§3.9). `staleAtFreeze` is its logical negation — two names for one boolean is how a second column gets added by accident — and `overrideApplied` on the member row would be constant-true, because an EXCLUDE produces no member row at all. |
| `messageReferenceEnabled` boolean | — | `messagePublication != NONE` **is** "reference enabled". One source of truth. |
| a `status` criterion inside `criteria` | the `msg.status==PUBLISHED` half of every legacy filter | The conjunct is `status ∈ Status.isPublic()` (verified: `Status.java:56-58` → `PUBLISHED \|\| CANCELLED \|\| EXPIRED`) and is a **resolver invariant**, not an authorable setting. All 2,324 blank-era members are EXPIRED or CANCELLED and **zero** are PUBLISHED, so a literal current-status filter empties every historical issue. |
| `IssueMember` rows for excluded messages | — | An exclusion is an `IssueOverride(kind = EXCLUDE)`; it produces no member row. One table, one meaning. |

---

## 3. The entities

### 3.1 `PublicationSeries`

`@Entity` · `extends VersionedEntity<Integer> implements ILocalizable<PublicationSeriesDesc>` · table `PublicationSeries`.

#### 3.1.1 Identity, provenance and bookkeeping

| Field | Java type | Column | Null | Default | Validation | Semantics · **forced by** |
|---|---|---|---|---|---|---|
| `id` | `Integer` | `id INTEGER NOT NULL` (PK) | no | shared legacy sequence (§1.2) | — | Surrogate PK. Never appears on the wire, never an interchange key. **`BaseEntity.java:27-30`** |
| `version` | `int` | `version INTEGER NOT NULL` | no | `0` | must match on every PUT | Optimistic lock. **`VersionedEntity.java:29-32`** |
| `created` | `Date` | `created DATETIME(6)` | yes | set in `@PrePersist` | — | Row creation. Overwritable by an import payload, as legacy already allows. **`VersionedEntity.java:34-48`** |
| `updated` | `Date` | `updated DATETIME(6)` | yes | set in `@PrePersist`/`@PreUpdate` | — | Last-write stamp. **Never read as a business timestamp** — MODEL-VERIFICATION §3.2 proves `publication.updated` is not a release moment. |
| `seriesId` | `String` | `seriesId VARCHAR(64) NOT NULL UNIQUE` | no | — | `^[a-z0-9][a-z0-9._-]{1,63}$`; **immutable after create** | The human-authored business key: admin route segment and JSON-interchange upsert key. **Convention: `MessageSeries.java:50-51`, `PublicationCategory.java:49-51`, `FmReport.java:56-57`.** **Settled by R3 (2026-08-21)** — authored, never a UUID, and never carrying legacy provenance; that is `legacyTemplateId`'s job, one row below. See §1.2 and §8.1. |
| `legacyTemplateId` | `String` | `legacyTemplateId VARCHAR(36) UNIQUE` | yes | `null` | `UidUtils.validUidFormat` when set | The legacy **template** publication's `publicationId`, recorded verbatim. This is what IMP-008 relinks template→child associations against on re-import and what makes IMP-005's upsert-by-`publicationId` round-trip total, now that `seriesId` is authored rather than adopted. Null for a series with no legacy template — e.g. the 16 hand-created Skydeområder rows. **IMP-005, IMP-008.** |
| `importSource` | `String` | `importSource VARCHAR(255)` | yes | `null` | ≤255 | Free-text provenance, e.g. `legacy-template` / `synthesised-from-publications`. Distinguishes "imported without a template" from "hand-created here", which `legacyTemplateId` alone cannot. **A settled decision** (one honest "Importeret fra tidligere system" record) **+ MODEL-VERIFICATION §5.13** (never key an imported row on title or tag name — three publications carry a literal `${year}` in title, fileName *and* tag name). |

#### 3.1.2 Lifecycle

| Field | Java type | Column | Null | Default | Validation | Semantics · **forced by** |
|---|---|---|---|---|---|---|
| `status` | `SeriesStatus` (`DRAFT`, `ACTIVE`, `RETIRED`) | `status enum('DRAFT','ACTIVE','RETIRED') NOT NULL` | no | `DRAFT` | transitions owned by the lifecycle section | Series lifecycle. Only `ACTIVE` series appear in the "ny udgave" picker; `RETIRED` series are excluded from gap warnings but **their published issues stay publicly visible and their files stay at their links** — retiring a series is not retiring its issues. **C7(3); SER-003, SER-004** (production parks four `DONT USE …` templates as INACTIVE purely to keep them out of the picker). |
| `statusChangedAt` | `Date` | `statusChangedAt DATETIME(6)` | yes | `null` | set together with the two below | When the **last** lifecycle change happened. **C7.** |
| `statusChangedBy` | `User` `@ManyToOne` | `statusChangedBy_id INTEGER` FK → `User(id)` | yes | `null` | — | Who changed it. A `User` association, not a name string — `MessageHistory.java:53-54` is the precedent, and the display name is snapshotted only where the row is permanent (`IssueAuditEntry.actorLabel`). **C7.** |
| `statusChangeReason` | `String` | `statusChangeReason VARCHAR(512)` | yes | `null` | **required** for `ACTIVE→RETIRED` and `RETIRED→ACTIVE`; 3..512 | Why. **C7** ("reason required, audited"). |

> ⚠ **These three columns record only the last change, not a trail.** C7 says series retire/reactivate are *audited*; a reactivate overwrites the retire reason, so on their own they do not satisfy that. The alternative is to generalise `IssueAuditEntry` (nullable `issue_id` + nullable `series_id`, exactly one non-null). That is **DM-Q2** (§9.4), and it must be settled **before** the schema is applied — retrofitting is exactly the hand-rolled migration **DM-Q1** is about.

**`DORMANT` is derived, not persisted.** A series with a cadence whose newest issue is older than N cadence periods is dormant, which is what ADM-019 needs to suppress a permanent gap warning on "Akkumuleret EfS" (last issue 2018). It is computed from `lastIssueAt` and needs no column; "we deliberately stopped" is already expressible as `RETIRED`. The threshold is **DM-Q4** (§9.4).

#### 3.1.3 Content mode — the gate on everything below it

| Field | Java type | Column | Null | Default | Validation | Semantics · **forced by** |
|---|---|---|---|---|---|---|
| `contentMode` | `ContentMode` (`GENERATED_FROM_QUERY`, `UPLOADED_FILE`, `EXTERNAL_LINK`, `NONE`) | `contentMode enum('GENERATED_FROM_QUERY','UPLOADED_FILE','EXTERNAL_LINK','NONE') NOT NULL` | no | `GENERATED_FROM_QUERY` | see the legality note below | What an issue's content *is*, and therefore whether it has membership semantics at all. **MODEL-VERIFICATION §5.1** — ~48 publications have no membership of any kind, so `criteria` and the interval must both be nullable and the nullability must be *driven* by a declared mode rather than inferred. |

**Amendment to MODEL-VERIFICATION §5.1, flagged for ratification (DM-Q5, §9.4).** §5.1 proposes `{GENERATED_FROM_QUERY, UPLOADED_FILE, EXTERNAL_LINK, ONE_OFF}`. That set conflates two independent axes and loses a live capability:

- `ONE_OFF` is not a *content* mode — it answers "what kind of thing is this?", which is a question of its own. Legacy already models one-offs as ordinary `Publication` rows with no template (**SER-024**), and C7 requires retire/reactivate/delete to reach them, which is the same machinery.
- **Shipped, and the reading below is superseded.** `ONE_OFF` stayed out of `ContentMode` as proposed here, and became a value of `PublicationSeries.kind` instead — a stored fact of its own, specified in §3.1.4. It is **not** derived from `cadence = NONE`, which the paragraph under the table still assumes; §3.1.4 is the standing rule.
- `NONE` is missing and is **preserved** (**FIL-001**): a publication with no file and no link, existing purely to be cited, and exempt from the "every language must carry a link" activation guard (`PublicationService.java:499-503`; ISS-008). Production runs two `type=NONE` rows plus four citation-only publications.

The amended set is **bijective with legacy `PublicationType`** (verified: `NONE / LINK / REPOSITORY / MESSAGE_REPORT`), which is what makes both the importer and the public-adapter `type` mapping total:

| `contentMode` | legacy `PublicationType` | live rows |
|---|---|---|
| `GENERATED_FROM_QUERY` | `MESSAGE_REPORT` | 1044 (FIL-004) |
| `UPLOADED_FILE` | `REPOSITORY` | 24 (FIL-003) |
| `EXTERNAL_LINK` | `LINK` | 7 (FIL-002) |
| `NONE` | `NONE` | 2 (FIL-001) |

Importer note: a `LINK` row carrying no link in any language normalises to `NONE`, not to a corrupt row (**FIL-005**).

**Legality matrix** — `contentMode` decides which of the fields below may be non-null. The formal invariant list belongs to the validation section; the nullability it forces is stated per field.

| `contentMode` | `timeRelation` | `aliveAtCutoff` | `criteria` | `firstIssueStartsAt` | `reportId` + print columns | the issue carries |
|---|---|---|---|---|---|---|
| `GENERATED_FROM_QUERY` | **required** | **required** — free-valued (`true`/`false`) under `PUBLISHED_IN_INTERVAL`, constrained to **`TRUE`** under `IN_FORCE_AT_CUTOFF` | **required** | required iff `PUBLISHED_IN_INTERVAL`, else **must be null** | **required** | interval (iff `PUBLISHED_IN_INTERVAL`) + cut-off + frozen snapshot + generated per-language files |
| `UPLOADED_FILE` | must be null | must be null | must be null | must be null | must be null | per-language uploaded files, no membership |
| `EXTERNAL_LINK` | must be null | must be null | must be null | must be null | must be null | per-language links, no files, no membership |
| `NONE` | must be null | must be null | must be null | must be null | must be null | nothing — exists only to be cited |

#### 3.1.4 Cadence, numbering and the chain's base case

| Field | Java type | Column | Null | Default | Validation | Semantics · **forced by** |
|---|---|---|---|---|---|---|
| `kind` | `SeriesKind` (`SCHEDULED`, `UNSCHEDULED`, `ONE_OFF`) | `kind enum('SCHEDULED','UNSCHEDULED','ONE_OFF') NOT NULL DEFAULT 'SCHEDULED'` | no | `SCHEDULED` | `ONE_OFF` holds **exactly one** issue and refuses a second | **What the publication *is*, as a stored fact — never inferred from `cadence` or from an issue count.** Added by `V8__series_kind.sql`. `cadence` answers "how often", and its `NONE` value covers two different things: a real series with no calendar (eleven NCAGS editions, eight ice-service notices, four editions of Dansk Fyrliste) and something published once and stopped. Reading `NONE` as "one-off" merges them — which is what put an eleven-issue series in the one-off list. Decided once, at import, and read everywhere after: deriving it live would reclassify a publication under whoever added the second issue. A one-off that turns out to recur becomes `UNSCHEDULED` by somebody's decision, not as a side effect of an upload. |
| `cadence` | `SeriesCadence` (`NONE`, `DAILY`, `WEEKLY`, `MONTHLY`, `YEARLY`) | `cadence enum('NONE','DAILY','WEEKLY','MONTHLY','YEARLY') NOT NULL` | no | `NONE` | — | How often an issue is nominally released. Maps 1:1 to legacy `PeriodicalType` (verified: `DAILY/WEEKLY/MONTHLY/YEARLY`) with `NONE ↔ null`. **`cadence = NONE` is *not* the one-off** — that is `kind` above; `NONE` means only "no calendar", which an `UNSCHEDULED` series also has. **SER-017** (production uses only WEEKLY — 1001 issues — and YEARLY). |
| `nominalCutoffDay` | `CutoffDay` (`MONDAY`…`SUNDAY`) | `nominalCutoffDay enum('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY')` **(corrected 2026-08-23 — the DDL transcribed the `MONDAY…SUNDAY` ellipsis as a two-element list, so the weekly EfS could not record its Wednesday release; see `V3__cutoff_day_all_seven.sql`)** | yes | `null` | required iff `cadence = WEEKLY`; null otherwise | Nominal weekday of the cut-off. Enum-per-weekday rather than `java.time.DayOfWeek` because **no entity in this codebase persists a `java.time` type** and `MailingListTrigger.scheduleType` (`:79-84`) is the established shape. **SER-017** — "cadence gains a nominal weekday+time, which legacy only had implicitly in the dates". |
| `nominalCutoffDayOfMonth` | `Integer` | `nominalCutoffDayOfMonth SMALLINT` | yes | `null` | required iff `cadence ∈ {MONTHLY, YEARLY}`; `1..31` or `-1` (= last day) | Nominal day of month. **SER-017.** |
| `nominalCutoffMonth` | `Integer` | `nominalCutoffMonth SMALLINT` | yes | `null` | required iff `cadence = YEARLY`; `1..12` | Nominal month, **1-based** — legacy's expander adds 1 to `Calendar.MONTH` for exactly this reason (`PublicationTemplateUpdateCtx.java:64`). **SER-017.** |
| `nominalCutoffTime` | `String` | `nominalCutoffTime VARCHAR(5)` | yes | `null` | required iff `cadence ≠ NONE`; `^([01]\d\|2[0-3]):[0-5]\d$` | Nominal time of day, `"HH:mm"`. String rather than `LocalTime`/`TIME`, copying `MailingListTrigger.scheduledExecutionTime` (`:86-90`) verbatim. **SER-017.** |
| `nominalCutoffTimeZone` | `String` | `nominalCutoffTimeZone VARCHAR(64)` | yes | seeded from `domain.timeZone` at create | required iff `nominalCutoffTime` non-null; a valid `ZoneId` | The zone the nominal time is read in. **Stored explicitly rather than derived**, copying `MailingListTrigger.scheduledExecutionTimeZone` (`:92-96`). This is what settles the domain-less-series question with a repo precedent instead of a new rule, and it is what stops the JVM-default-timezone bug REVIEW **F6** records from being copied. The "what seeds a domain-less series" question (**DM-Q8**) is closed: V13 made `domain_id` `NOT NULL`, so there are none. |
| `numberingScheme` | `NumberingScheme` (`ISO_WEEK_YEAR`, `YEAR_EDITION`, `MONTH_YEAR`, `EDITION_SEQUENCE`, `NONE`) | `numberingScheme enum('ISO_WEEK_YEAR','YEAR_EDITION','MONTH_YEAR','EDITION_SEQUENCE','NONE') NOT NULL` | no | `NONE` | must be `NONE` when `cadence = NONE` | Which numbers an issue derives, and therefore what `${year}` means (calendar year vs ISO week-based year). Evidenced in production: `ISO_WEEK_YEAR` (1001 weekly issues) and `YEAR_EDITION` (`firing-areas-2017-v1/-v2`, `nm-almanac-2020-v1/-v2` — ISS-018, ADD-004). `MONTH_YEAR` and `EDITION_SEQUENCE` have **zero production instances**. No separate edition-reset column exists: `YEAR_EDITION` implies the edition resets each year, `EDITION_SEQUENCE` implies it never does. |
| `firstIssueStartsAt` | `Date` | `firstIssueStartsAt DATETIME(6)` | yes | `null` | **required** iff the relation **tiles** — `PUBLISHED_IN_INTERVAL` or, from 2026-09-13, `COMPILED_FROM_SOURCE`; **must be null** otherwise (S-4) | **The chain's base case** — the notional cut-off of the issue before the first, i.e. the lower bound of the first issue's interval. Without it the first issue has no lower bound and sweeps the whole corpus. Stored at `DATETIME(6)` because the release-moment boundary is decisive to the second (NM-466-26 at 62 s; P&T uge 12/2024 at **4 s**). Forbidden for `IN_FORCE_AT_CUTOFF`: those 531 issues have **no lower bound at all**. **The settled chaining rule; MODEL-VERIFICATION §3.1, §7.** |

#### 3.1.5 Membership definition (C2 / C4)

| Field | Java type | Column | Null | Default | Validation | Semantics · **forced by** |
|---|---|---|---|---|---|---|
| `timeRelation` | `TimeRelation` (`PUBLISHED_IN_INTERVAL`, `IN_FORCE_AT_CUTOFF`, **`COMPILED_FROM_SOURCE`**) | `timeRelation enum('PUBLISHED_IN_INTERVAL','IN_FORCE_AT_CUTOFF','COMPILED_FROM_SOURCE')` **(the third value added 2026-09-13 by `V18__compilation_series.sql`, the V4 `MODIFY` idiom)** | yes | `null` | **required** iff `contentMode = GENERATED_FROM_QUERY`; null otherwise | Which time predicate the resolver applies — and, critically, **whether tiling, gap detection, "missing week" recovery and "the previous issue's cut-off" run at all**. They run for the relations that **tile**. <br>**Amended 2026-09-13 (compilation series): two relations tile, not one.** `COMPILED_FROM_SOURCE` tiles exactly as `PUBLISHED_IN_INTERVAL` does — a yearly period with a lower bound, chained off the predecessor's stamp — so the enum answers `tiles()` (true for those two) and `compiled()` (true for the third alone), and every site that meant *tiling* asks `tiles()` rather than comparing against one value. What the third value changes is not the shaping but the **derivation**: the members are the union of another series' frozen member rows (§7.5, RI-16), not the answer to a query. **MODEL-VERIFICATION §3.1, §5.2, §5.18** — `IN_FORCE_AT_CUTOFF` is the larger shape by publication count (531 vs 500), its issues **overlap rather than tile** (the 2026 and 2027 firing-areas issues share 31 of 32 members), and under a chained interval the 2027 issue would contain **one** message instead of thirty-two. |
| `aliveAtCutoff` | `Boolean` (**boxed, nullable — not `boolean`**) | `aliveAtCutoff BIT(1)` | yes | `null` | **NULL exactly when the series selects nothing of its own** — `contentMode ≠ GENERATED_FROM_QUERY`, or (**amended 2026-09-13**) `timeRelation = COMPILED_FROM_SOURCE`, where liveness was judged at each source issue's own cut-off and re-judging it at the compilation's would drop what those issues rightly carried; **non-null otherwise** — free-valued (`true`/`false`) under `PUBLISHED_IN_INTERVAL`, **constrained to `TRUE`** under `IN_FORCE_AT_CUTOFF` (S-2, S-3) | Whether the NULL-safe alive conjunct `(publishDateTo IS NULL OR publishDateTo >= cutoff)` is applied. `true` for the modern phase regime, `false` for the blank/sticky regime, and `true` — necessarily — for every `IN_FORCE_AT_CUTOFF` series. **C2, as ruled R2 (2026-08-21).** <br>**Ruling R2, against the earlier "must be null under `IN_FORCE_AT_CUTOFF`" reading, which would have rejected 531 of 1,031 message-backed series at the first `INSERT`:** `IN_FORCE_AT_CUTOFF` **is** `publishDateFrom <= cutoff AND (publishDateTo IS NULL OR publishDateTo >= cutoff)` — the second half *is* the alive clause, so `TRUE` there is semantically accurate rather than a fudge, while NULL would falsely read as "not applied". Storing it also lets the resolver read one non-null boolean for **every** query-backed series with no "NULL means yes here" special case, and it keeps the frozen echo `snapshotAliveAtCutoff` (§3.6.5) populated for every issue that has members. NULL is reserved for the one honest meaning: this series has no query at all. |
| `criteria` | `IssueCriteriaVo` | `criteria TEXT` + `@Convert(JpaCriteriaAttributeConverter.class)` | yes | `null` | must be null unless `contentMode = GENERATED_FROM_QUERY`; **must be null on a compilation** (`timeRelation = COMPILED_FROM_SOURCE`, S-24); must parse; the document's own schema is the criteria section's | The structured criteria document. **MODEL-VERIFICATION §5.1** (nullable) **+ C4** (a discriminated union, so a hatch stays purely additive). <br>**A new typed converter is required and it must rethrow on a parse failure.** `JpaJsonAttributeConverter` deserialises to a bare `Object` (`:33`) and would lose the polymorphic node typing; `JpaPropertiesAttributeConverter` is fixed to `Map<String,Object>`. Both **swallow** parse failures and return `null`/empty (`JpaJsonAttributeConverter.java:60-64`) — a silently-nulled `printSettings` degrades a PDF, but a silently-nulled `criteria` degrades to "no criteria at all". Model the new converter on `JpaPropertiesAttributeConverter`'s `TypeReference` idiom and let it throw. |
| `sourceSeries` | `PublicationSeries` `@ManyToOne` | `sourceSeries_id INTEGER` FK → `PublicationSeries(id)`, index `pub_series_source_k` | yes | `null` | set **iff** `timeRelation = COMPILED_FROM_SOURCE` (**S-24**); the source is a different series, is `GENERATED_FROM_QUERY`, and is not itself a compilation (**S-25**) | **New 2026-09-13 (compilation series), `V18__compilation_series.sql`.** The series whose published issues this one compiles. A compilation has no query of its own: its member set is the union of the frozen member rows of the source series' PUBLISHED issues whose effective cut-off falls inside the compilation issue's period (**RI-16**), each member remembering which source issue printed it (§3.8). An entity reference rather than a stored `seriesId` string, because it is a relation between two rows in this table and the database can say so; the **wire** carries the source's immutable `seriesId`, like every other cross-series reference, and it is resolved in the REST resource and the import processor rather than in `updateFromVo` — an id naming no series is refused, never quietly dropped, or a compilation would be left with nothing to compile. **No chains** (S-25): a compilation of a compilation would make one issue's contents depend on a derivation two levels away with nothing recording the middle one. |

> **Two distinct nulls, and confusing them is a live hazard.** `criteria = null` means *no query at all* (the ~48 no-membership publications). `criteria = {schemaVersion: 1, match: "ALL", criteria: []}` — a document with an **empty node array** — is a legal query meaning "everything in scope". Confusing them turns a link-only one-off into a series that resolves the entire message corpus.

**The five legal combinations (C4's import gate).** Any imported publication whose `messageTagFilter` is not one of these fails the import loudly, naming the publication and the verbatim filter:

| # | Legacy `messageTagFilter` | live | `contentMode` | `timeRelation` | `aliveAtCutoff` | criteria adds |
|---|---|---:|---|---|---|---|
| 1 | *(blank)* → sticky `DEFAULT_MESSAGE_TAG_FILTER` | 160 | `GENERATED_FROM_QUERY` | `PUBLISHED_IN_INTERVAL` | **`false`** | — |
| 2 | `data.phase=='msg-status-change' && msg.status==PUBLISHED` | 387 | `GENERATED_FROM_QUERY` | `PUBLISHED_IN_INTERVAL` | **`true`** | — |
| 3 | `msg.status == PUBLISHED` | 29 | `GENERATED_FROM_QUERY` | `IN_FORCE_AT_CUTOFF` | **`true`** | — |
| 4 | `(msg.type==T \|\| msg.type==P) && msg.status==PUBLISHED` | 501 | `GENERATED_FROM_QUERY` | `IN_FORCE_AT_CUTOFF` | **`true`** | `messageType ∈ {P, T}` |
| 5 | *(no filter, no tag, no report)* | ~48 | `UPLOADED_FILE` / `EXTERNAL_LINK` / `NONE` | `null` | `null` | `null` |

Row 1 is the entire reason `aliveAtCutoff` exists: the interval predicate **alone** reproduces 115 of 116 sticky issues and 2,323 of 2,324 members with zero extras; adding the conjunct is what breaks them (73 drops across 39 issues). **C2 / MODEL-VERIFICATION §3.3.**

**Which row a SERIES is in is decided by its newest edition, not by its template** (2026-09-07, RE-RUNNABILITY): the weekly EfS template is a row-1 blank while its editions have been row 2 since 2019, and a series read off the template resolved its open issue under the sticky regime. Imported issues keep their own row via `IssueSnapshotDeriver`.

Rows 3 and 4 carry **`true`, not `null`** (R2): under `IN_FORCE_AT_CUTOFF` the alive clause is constitutive, so `true` records what the resolver actually applies. `null` is written only in row 5, where there is no query to apply anything to.

**A sixth shape exists and no legacy filter produces it (2026-09-13).** A **compilation** is `GENERATED_FROM_QUERY` with `timeRelation = COMPILED_FROM_SOURCE`, `criteria` null, `aliveAtCutoff` null and `sourceSeries` set. It cannot appear in the table above, because the table translates a legacy `messageTagFilter` and a compilation has no filter to translate: the accumulated annual was produced by hand in the old system and imported as uploaded PDFs. It reaches the estate through an **import shape override** instead — the importer converts `accumulated-yearly-ntm` in place after the source series is persisted, keeping its identifier, its category and its already-imported issues with their files — so every rehearsal and the cut-over import produce the converted series. The five-row gate is unchanged and still fails loudly on an unknown filter.

#### 3.1.6 Relations, scope and the language list

| Field | Java type | Column | Null | Default | Validation | Semantics · **forced by** |
|---|---|---|---|---|---|---|
| `category` | `PublicationCategory` `@ManyToOne @NotNull` | `category_id INTEGER NOT NULL` FK | no | — | must resolve | Grouping, **public visibility gate** (`category.publish`) and public sort key (`category.priority`). The `@NotNull` is load-bearing, not cosmetic: the public API dereferences it unguarded (`AbstractApiService.java:228-233`). **CAT-001, CAT-005, CAT-006.** |
| `domain` | `Domain` `@ManyToOne @NotNull` | `domain_id INTEGER NOT NULL` FK | ~~yes~~ **no** | — | must resolve | **Amended 2026-08-29 (R-xxvi): the OWNER, and required for every kind** — scheduled, unscheduled and one-off alike. The one domain the publication is listed in, administered from, and whose timezone its cut-offs are read in; it also supplies the default `nominalCutoffTimeZone` and the default `messageSortOrder`. Made `NOT NULL` by **V13** (conditionally — see the R-xxvi amendment). Enforced above the column by **S-20a** on every save, by the importer's three-source assignment, and by the pre-flight's `SERIES_WITHOUT_OWNER`. Reachability from other domains is `availability`'s job, not this column's. **ISS-020, VIS-013.** <br>*Superseded text:* "Scope. **NULL means global** — visible from every domain. 17 production rows are domain-free, and that is what makes the four citation-only publications and the external links reachable outside NM. A required domain would hide them." |
| `availability` | `SeriesAvailability` (`OWNER_ONLY`, `SELECTED_DOMAINS`, `ALL_DOMAINS`) | `availability enum('OWNER_ONLY','SELECTED_DOMAINS','ALL_DOMAINS') NOT NULL` | no | `OWNER_ONLY` *(column default; the application defaults from `contentMode`)* | `SELECTED_DOMAINS` ⟹ `availableDomains` non-empty (**S-20b**) | **New 2026-08-29 (R-xxvi).** Who besides the owner may **see and cite** the publication. Never grants a write — the domain guard compares the owner and nothing else — and never reaches the public site, which is decided by `category.publish`. The application default is decided from what the publication *is*: `GENERATED_FROM_QUERY` → `OWNER_ONLY` (its editions mean one desk's week), everything else → `ALL_DOMAINS` (a document, a link or a publication with no content model is a reference other desks point at, which is how the whole catalogue behaved before this column existed). One rule, applied identically by the create form, the interchange import and the legacy importer. |
| `availableDomains` | `List<Domain>` `@ManyToMany @JoinTable` | `PublicationSeries_AvailableDomain (series_id, domain_id)` (§2.1 row 2b) | — | empty | every entry active and distinct; **never the owner** | **New 2026-08-29 (R-xxvi).** The domains `SELECTED_DOMAINS` names. The owner is dropped rather than refused (it is already the strongest form of "visible from here", and storing it would make the stored list and the read disagree); an **inactive** domain is refused, because the visible-from predicate ignores one and the row would read as a setting that is switched on and does nothing. |
| `messagePublication` | `MessagePublication` (`NONE`, `INTERNAL`, `EXTERNAL`) | `messagePublication enum('NONE','INTERNAL','EXTERNAL') NOT NULL` | no | `NONE` | **immutable once any issue of this series has published** | Whether and how the series' issues may be cited from a message, and *which message field* the citation is written to. Reuses the legacy enum verbatim (verified: `MessagePublication.java:22-34`). Default `NONE` = un-citable until an admin opts in. <br>**The immutability is not style.** `PublicationUtils.extractMessagePublication` chooses `internalPublication` vs `publication` from this value (`:50-51`); flipping the channel after citations exist makes every existing citation unfindable — it lives in the other field — and re-applying **appends a duplicate** rather than replacing. There is no rewrite path and none should be built. **REF-001, REF-002, ADD-020; fragment E P5.** |
| `languages` | `List<String>` `@ElementCollection @OrderColumn(name = "indexNo")` | table `PublicationSeries_languages` (§3.2) | — | seeded from `NiordApp.getLanguages()` on create | non-empty; ordered; duplicate-free; every entry ∈ the installation's model languages | The series' configured language list — **the list C5 drives everything off**. Explicit and declared, not inferred from which desc rows exist: legacy infers it via two competing mechanisms (a template pushes a row per template language onto every child — **ADD-019** — while the admin form independently creates one row per configured model language), and that ambiguity is exactly what "N languages, never two" removes. A one-language series is legitimate (**SER-019** — live template `ebf7e99d` carries a `da` desc only). **C5.** |
| `sortOrder` | `Integer` | `sortOrder INTEGER` | yes | `null` | — | **A stored, editable column that no ordering path reads.** It was specified as the deterministic tiebreak for the public list; what shipped uses the publication id instead. `PublicationPublicAdapter.ordered()` sorts by `category.priority` ascending, then `publishDateFrom` descending, then `publicationId` ascending — the id is what makes the EfS row and its P&T twin, which share a window to the millisecond, come back in the same order twice. Nothing in either repository consults `sortOrder`; it is persisted, emitted on the VO and editable in the one-off editor, and otherwise inert. Leave it alone or retire it deliberately — do not wire it in on the assumption that this row describes behaviour. |
| `descs` | `List<PublicationSeriesDesc>` `@OneToMany(cascade = ALL, mappedBy = "entity", orphanRemoval = true)` | child table | — | empty | exactly one row per entry in `languages`, each with a non-blank `name` | **`Publication.java:168-169`.** |

#### 3.1.7 Rendering — the typed replacement for `printSettings`

The problem being replaced (**ADD-005**): the release path and the admin JS build the `report.pdf` query string as `tag` + **every** `printSettings` entry + `param:*` + `lang` + `fileName`. `report.pdf` then instantiates **both** `MessagePrintParams` **and** `MessageSearchParams` from that same string. `MessagePrintParams` reads only `report`, `pageSize`, `pageOrientation`, `mapThumbnails`, `fileName`, `debug`, `param:*` (`MessagePrintParams.java:38-63`). **Every other key falls through to the message search** — so a `printSettings` entry named `sortBy`, `sortOrder`, `status`, `type`, `mainType`, `area`, `category`, `messageSeries`, `dateType`, `fromDate`, `toDate`, `query` or `referenceLevels` silently rewrites what is in the PDF and in what order. FIL-021 describes this as "an extension point report templates can read", which is the opposite of true: templates never see stray keys at all.

| Field | Java type | Column | Null | Default | Validation | Semantics · **forced by** |
|---|---|---|---|---|---|---|
| `reportId` | `String` | `reportId VARCHAR(64)` | yes | `null` | **required** iff `contentMode = GENERATED_FROM_QUERY`; must resolve to an existing `FmReport.reportId` offered in `domain` | Which print report renders the issue. A **soft** reference (string, not FK), matching how legacy stores it inside `printSettings['report']` and how `FmReport.reportId` is itself the interchange key (`FmReport.java:56-57`). **FIL-017** (release is refused without it); **ADD-013** (a report with no domains is offered everywhere). |
| `pageSize` | `PageSize` (`A3`, `A4`, `A5`, `B4`, `B5`, `LETTER`, `LEGAL`, `LEDGER`) | `pageSize enum('A3','A4','A5','B4','B5','LETTER','LEGAL','LEDGER')` | yes | `A4` on create when `reportId` is set | non-null iff `reportId` non-null | **FIL-021** — all eight legacy values kept. Typing the field must not also shrink the enumeration, which the prototype's A4-only offering would. |
| `pageOrientation` | `PageOrientation` (`PORTRAIT`, `LANDSCAPE`) | `pageOrientation enum('PORTRAIT','LANDSCAPE')` | yes | `PORTRAIT` | non-null iff `reportId` non-null | **FIL-021**; matches `MessagePrintParams.java:40`. |
| `mapThumbnails` | `Boolean` | `mapThumbnails BIT(1)` | yes | `false` | non-null iff `reportId` non-null | **FIL-021**; matches `MessagePrintParams.java:41`. |
| `messageSortBy` | `String` | `messageSortBy VARCHAR(32)` | yes | `null` | one of `id`, `date`, `publish_date`, `follow_up_date`, `area` | The declared render/freeze order. NULL ⇒ fall back to `domain.messageSortOrder` (`Domain.java:89`), then to the hardcoded `AREA`/`ASC` (`MessageSearchParams.java:187-209`). Promoted out of `printSettings` because the order **decides whether the PDF has area headings at all** (`report.pdf` derives `areaHeadings` from `sortByArea()`) and because a frozen snapshot must carry a defined order or the same issue reprints differently. **ADD-005 + ADD-006 + OPTIONAL §3.5.** |
| `messageSortOrder` | `SortOrder` (`ASC`, `DESC`) | `messageSortOrder enum('ASC','DESC')` | yes | `null` | non-null iff `messageSortBy` non-null | Same. Reuses `PagedSearchParamsVo.SortOrder`. |
| `reportParams` | `Map<String,Object>` | `reportParams TEXT` + `@Convert(JpaPropertiesAttributeConverter.class)` | no | `{}` | key `^[A-Za-z0-9_.\-]{1,64}$`; no `param:` prefix; **`week`, `year` and `edition` rejected** | User report parameters, emitted into the FreeMarker data model as `param:<key>`. They can never leak into the message search, so free-form here is safe and **FIL-022 is preserved intact** (the `nm-annex` report declares a free-text `NtM-ref` param today). `week`/`year`/`edition` are rejected because they are injected from the issue and must never be typed — **FIL-023 + a settled decision.** Same column idiom as `Publication.java:163-166` and `FmReport.java:76-80`. <br>**The series map is the default, not the only one:** `PublicationIssue.reportParams` (§3.6.6) carries the per-issue map and is merged **over** this one key by key at render time, the issue winning. |

**One column that is deliberately not here.** The *årgang* printed on the EfS cover is computed in the FreeMarker template as `year − 1884` at render time and stored nowhere (**ADD-002**). The prototype puts "117. årgang" among the series report params, which would create a second, conflicting source the template ignores. Do not add a `volume` column; if that value must become data, the template changes too.

#### 3.1.8 Automation control

| Field | Java type | Column | Null | Default | Validation | Semantics · **forced by** |
|---|---|---|---|---|---|---|
| `releaseMode` | `ReleaseMode` (`MANUAL_GATE`, `AUTO_RELEASE`) | `releaseMode enum('MANUAL_GATE','AUTO_RELEASE') NOT NULL` | no | `MANUAL_GATE` | writable **only** via the series-settings PUT | Manual gate vs fully automatic release. Carried by the entity even though no task builds the automatic path yet; when it is `AUTO_RELEASE`, a publish has no human actor, which is what forces `IssueAuditEntry.actorKind` (§3.9) and nullable `publishedBy`. **A settled decision** — "per-series, editable only in series settings; shown read-only elsewhere. Manual gate is the default." |
| `nextIssueCreation` | `NextIssueCreation` (`AUTO_ON_PUBLISH`, `MANUAL`) | `nextIssueCreation enum('AUTO_ON_PUBLISH','MANUAL') NOT NULL` | no | `AUTO_ON_PUBLISH` | must be `MANUAL` when `cadence = NONE` | Whether publishing an issue auto-creates the next one starting **exactly at the stamped cut-off**. That chaining is what removes the drift nominal +7d produced — production shows six 14-day gaps, one 5-day and three 0-day gaps between consecutive EfS issues. **The settled chaining rule; ISS-003.** |

**No column decides which model serves the public.** The public list is the union of `API-CONTRACT.md` §8.8: a published issue in a publishing category is served, and a legacy row is served until the issue imported from it has been published. Both halves are read from the data itself — the new half asks `PublicationIssue.status`, and the legacy half's exclusion reads `PublicationIssue.publishedAt` together with `legacyPublicationId` — so there is nothing here to set, nothing to flip and no direction to run it in. A series whose rows were never imported keeps being served from the legacy half for as long as that stays true.

`publishedAt` rather than the status is what the exclusion asks because it is set at publish and never cleared, retire included: a retired issue has still taken over from the legacy row it replaced, and an issue still at OPEN has not. **A released imported issue therefore always carries a stamp** — I-5 requires it and the exclusion depends on it. `LegacyImportService.publishedAtOf` recovers the release moment from the cut-off or from a last-write time credible inside the issue's own public window; where neither witnesses it, the issue is stamped with its own `publicFrom`, the earliest instant the data says it was public and so precisely the instant its legacy row must stop being served. That is not an invented release moment — `cutoffSource` still records that no stage witnessed one — it answers a different question than *when did somebody press publish*. An OPEN imported issue stays unstamped, because it has not taken over. §8.8's dedup assertion remains a safety net only: nothing in the import is expected to reach it.

#### 3.1.9 Legacy-parity column

| Field | Java type | Column | Null | Default | Validation | Semantics · **forced by** |
|---|---|---|---|---|---|---|
| `languageSpecific` | `boolean` | `languageSpecific BIT(1) NOT NULL` | no | `true` | must be `true` when `contentMode = GENERATED_FROM_QUERY` and `languages.size() > 1` | **Pure legacy parity.** It no longer syncs anything — that was an admin-UI convenience (`admin-publication-ctrl.js:78-92`). Its one surviving reader is the editor's per-message link override, which requires LINK-type **and** non-language-specific **and** no link of its own (**REF-007**, AT RISK; already partly lost in shipped niord-app because the flag lives only on `SystemPublicationVo` and never reaches the public VO). It also feeds the legacy export adapter. The validation guard exists because **ADD-027**: a non-language-specific report publication generates N PDFs to one path and the last language rendered wins. |

---

### 3.2 `PublicationSeries_languages` (collection table)

Generated by `@OrderColumn(name = "indexNo") @ElementCollection List<String> languages` on `PublicationSeries` — the `MailingListTrigger.java:139-141` idiom.

| Column | DB type | Null | Semantics |
|---|---|---|---|
| `PublicationSeries_id` | `INTEGER NOT NULL` FK → `PublicationSeries(id)` | no | Owner. Hibernate's default join-column name for this collection. |
| `languages` | `VARCHAR(255) NOT NULL` | no | The language code (`da`, `en`, …). Hibernate's default element-column name and default `String` length; do not narrow it — see §3.3's note on `lang`. |
| `indexNo` | `INTEGER NOT NULL` | no | Position in the configured list. **Order is meaningful** — it is the order every per-language UI row and every generated file set is padded and rendered in. |

PK `(PublicationSeries_id, indexNo)`.

---

### 3.3 `PublicationSeriesDesc`

`@Entity` · `extends DescEntity<PublicationSeries>` · `@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"lang", "entity_id"}))` — copied verbatim from `PublicationDesc.java:31-34`.

| Field | Java type | Column | Null | Default | Validation | Semantics · **forced by** |
|---|---|---|---|---|---|---|
| `id` | `Integer` | `id INTEGER NOT NULL` (PK) | no | shared sequence | — | `DescEntity extends BaseEntity<Integer>`. |
| `lang` | `String` `@NotNull` | `lang VARCHAR(255) NOT NULL` | no | — | must be in the owner's `languages` | Inherited from `DescEntity.java:33-35`. **The 255 is not a choice:** `DescEntity.lang` carries no `@Column(length)`, so every desc table in the system has `lang VARCHAR(255)`, and narrowing it here would require an `@AttributeOverride` no existing desc entity uses. |
| `entity` | `PublicationSeries` `@ManyToOne @NotNull` | `entity_id INTEGER NOT NULL` FK | no | — | — | Inherited from `DescEntity.java:36-39`. |
| `name` | `String` `@NotNull` | `name VARCHAR(255) NOT NULL` | no | — | non-blank, required in every configured language | The **series' own display name** in this language — distinct from the pattern that names its issues. Legacy templates carry Title *and* Title Format as two separate authored values (**SER-005**), and a publication cannot be saved without a title in every configured language (**ADD-017**). |
| `nameSuggestionPattern` | `String` | `nameSuggestionPattern VARCHAR(255)` | yes | `null` | tokens must be in the vocabulary for this series' `cadence`/`numberingScheme`; `${parameters}` is **not** legal here | The pattern the issue-name suggestion is derived from. Replaces `PublicationDesc.titleFormat`. Per language because every production weekly issue has two differently-worded titles: `EfS uge ${week} - ${year}` / `NtM week ${week} - ${year}` (live template `a8e661ee`). Derived from the **ISO week of the cut-off**, never the interval start — the prototype's `suggestName()` is wrong here. **C5; SER-006; SER-009.** |
| `fileNamePattern` | `String` | `fileNamePattern VARCHAR(255)` | yes | `null` | no `/` or `\`; must end `.pdf` when `contentMode = GENERATED_FROM_QUERY`; **must be distinct across the series' languages** when `languages.size() > 1`; `${parameters}` not legal | The generated PDF's file name in this language. Live: `EfS-Uge-${week}-${year}.pdf` / `NtM-Week-${week}-${year}.pdf`; `Skydeomraader-${year}.pdf` / `Firing-Areas-${year}.pdf`. The public download site links these names directly, so collapsing them is externally visible. Distinctness is required because otherwise every language's PDF is generated to one path and the last language wins (**ADD-027**). **C5; FIL-025; FIL-006.** |
| `messageReferenceFormat` | `String` | `messageReferenceFormat VARCHAR(512)` | yes | `null` | required in **every** configured language iff `messagePublication ≠ NONE`; `${parameters}` **is** legal here and only here | The pattern for the exact text written **into stored message HTML** at citation time — `EfS ${week}/${year}` renders "EfS 27/2026". Replaces `PublicationDesc.messagePublicationFormat`. Per language because the citation is publicly visible in the message body and no endpoint can clear it; removal means hand-editing rich text, message by message. Structural differences are real: `… ${year}, punkt ${parameters}` (da) vs `… ${year}, section ${parameters}` (en). Citing into a language with no format writes the literal `" null"` today (**REF-015**) — the every-language rule is the fix, **scoped to series with the reference option on** so the weekly EfS template (which carries no format in either language) is not blocked. **C5 as amended; REF-003, REF-004, REF-025.** |
| `linkPattern` | `String` | `linkPattern VARCHAR(1024)` | yes | `null` | must be null unless `contentMode = EXTERNAL_LINK` | External URL pattern pushed down to each issue. Legacy parity with low usage — legacy expands a template's link through `ctx.str()` (`Publication.java:325`), so it is a pattern there too, but production has **zero cadenced LINK series** (all 7 LINK rows are one-offs). Reserved so a cadenced link series stays expressible without a schema change. 1024 because external URLs are the unbounded case. |

**`descDefined()` must include every field above, and the VO must agree field-for-field.** Legacy has a live trap here, verified in the source: `PublicationDesc.descDefined()` counts `messagePublicationFormat` (`fieldsDefined(title, titleFormat, messagePublicationFormat, link, fileName)`) but `PublicationDescVo.descDefined()` does **not** (`fieldsDefined(title, titleFormat, link, fileName)`) — so a format-only language row is persisted by the entity and silently dropped by the VO on both read and write, and an export-then-import loses its citation text (**REF-023**). The new pair must not repeat the asymmetry.

---

### 3.4 `PublicationCategory` — existing entity, two changes

`@Entity` · `extends BaseEntity<Integer>` (**not** versioned — unchanged) · `implements ILocalizable<PublicationCategoryDesc>`. 15 of the 17 ledger rows are PRESERVED; the redesign changes one default and makes one existing column visible.

| Field | Java type | Column | Null | Default | Validation | Semantics · **forced by** |
|---|---|---|---|---|---|---|
| `id` | `Integer` | `id INTEGER NOT NULL` (PK) | no | shared sequence | — | Unchanged. |
| `categoryId` | `String` `@NotNull @Column(unique = true)` | `categoryId VARCHAR(255) NOT NULL UNIQUE` | no | — | non-blank; **immutable after create**; `^[a-z0-9][a-z0-9-]{0,63}$` recommended | Human-authored, **not** a UUID, and the import/export upsert key — so it must stay stable and author-chosen. Live ids: `dk-dma-weekly-nm-publications`, `dk-dma-nm-annex`, `dk-dma-publications`, `dk-dma-internal-publications`, `dk-external-publications`. **CAT-002, IMP-011.** |
| `priority` | `int` | `priority INTEGER NOT NULL` | no | **`100`** (changed from `0`) | ≥ 0 | Primary sort key of every publication list — admin lists, pickers **and the public download site** (`PublicationService.java:293-296`; live public payload orders 10 → 20 → 50). **Deliberate change:** the entity defaults to `0` today while the admin UI seeds `100`, so a category created through the API sorts to the very front of every list including the public site. Aligning the entity default with the UI's intent is the fix; existing rows are untouched either way. **CAT-006; ADD-024.** Ratification is **DM-Q6** (§9.4). |
| `publish` | `boolean` | `publish BIT(1) NOT NULL` | no | `false` | — | The on/off switch for public exposure of every publication in the category. Verified live: 2 of 5 categories have `publish = false` and their 4 publications never appear on `/public/v1`. Unchanged. **CAT-005.** |
| `descs` | `List<PublicationCategoryDesc>` `@OneToMany(cascade = ALL, mappedBy = "entity", orphanRemoval = true)` | child table | — | empty | ≥ 1 row | Unchanged. |

No lifecycle status, no domain, no `@Version` — none exists today and none is asked for. Many series map to one category (**CAT-010**).

---

### 3.5 `PublicationCategoryDesc` — existing entity, unchanged schema

`@Entity` · `extends DescEntity<PublicationCategory>` · `@UniqueConstraint({"lang", "entity_id"})`.

| Field | Java type | Column | Null | Default | Validation | Semantics · **forced by** |
|---|---|---|---|---|---|---|
| `lang` | `String` `@NotNull` | `lang VARCHAR(255) NOT NULL` | no | — | ∈ the installation's model languages | Inherited. |
| `entity` | `PublicationCategory` `@ManyToOne @NotNull` | `entity_id INTEGER NOT NULL` FK | no | — | — | Inherited. |
| `name` | `String` `@NotNull` | `name VARCHAR(255) NOT NULL` | no | — | non-blank; **required in every model language** | **CAT-003.** |
| `description` | `String` | `description VARCHAR(255)` | yes | `null` | — | Optional longer description per language. **This is ledger row CAT-004 (AT RISK, owner P2.3):** it exists in the entity and in `admin-manual §6.1.1` today but appears in neither the prototype's categories table nor the plan, "which is exactly why it would vanish unnoticed". <br>**No schema change is needed to satisfy it** — the column and the `PublicationCategoryDescVo` field already exist, and `descDefined()` already counts it. Closing CAT-004 is (a) keeping the column, (b) carrying it on both VOs, and (c) *rendering it* on the new categories page. If the field is to be widened for real use, `@Column(length = 1024)` matches `MessageDesc.java:37-48`; that is a deliberate widening, not a fix, and is not proposed here. |

---

### 3.6 `PublicationIssue`

`@Entity` · `extends VersionedEntity<Integer> implements ILocalizable<PublicationIssueDesc>` · table `PublicationIssue`.

#### 3.6.1 Identity and ownership

| Field | Java type | Column | Null | Default | Validation | Semantics · **forced by** |
|---|---|---|---|---|---|---|
| `id` | `Integer` | `id INTEGER NOT NULL` (PK) | no | shared sequence | — | Internal PK. Never leaves the backend. |
| `version` | `int` | `version INTEGER NOT NULL` | no | `0` | must match on every write | Optimistic lock. |
| `created` / `updated` | `Date` | `created`, `updated DATETIME(6)` | yes | `@PrePersist`/`@PreUpdate` | — | Row housekeeping only. **Never** the release moment — that is `cutoffStampedAt`. MODEL-VERIFICATION §3.2. |
| `publicId` | `String` | `publicId VARCHAR(36) NOT NULL UNIQUE` | no | `UidUtils.newUid()` **at create** | `@NotNull`; `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`; **immutable for life** | **The external handle** — emitted as `PublicationVo.publicationId`, carried in `?publication=`, in saved message filters, and written into stored message HTML as `<a publication="{id}">`. <br>**The lowercase-UUID shape is a hard technical constraint, not a style preference:** `PublicationUtils` finds a citation with `doc.select("a[publication=" + id + "],span[publication=" + id + "]")` — an **unquoted** CSS selector built by string concatenation (`:60-61`, `:123`). Any id containing a CSS-special character matches nothing, and `updateMessagePublications` then takes its else-branch and **appends a duplicate citation** (`:129-131`) instead of replacing in place. Human-readable ids (`efs-uge-27-2026`) are therefore forbidden as `publicId`; a slug may exist as a separate, non-citable admin field. <br>**Never reassigned** — not on amend, retire, reactivate, edition bump, interval edit, name change, series move or re-import. Legacy's `Publication.assignNewPublicationId()` (`:342-359`) must have no counterpart. <br>**Minted at CREATE, not at publish** — resolving fragment D's "at publish" against fragments B and E: OPTIONAL §3.4 requires `publication=<openIssueId>` to resolve for an OPEN issue (live preview, editor tier, sysadmin test render), which is impossible if the id does not exist yet. This amends C7's literal delete-guard wording; see **DM-Q3** (§9.4). **ISS-022, REF-010, C8.** |
| `legacyPublicationId` | `String` | `legacyPublicationId VARCHAR(36) UNIQUE` | yes | `null` | when set, **must equal `publicId`** | Set by the historical importer to the legacy `Publication.publicationId` this issue *is*. Two jobs: it records provenance, and it is the **dedup key of the public union** — the legacy half of the public adapter excludes every publication whose id appears here on an issue that has been published. Never mint a fresh UUID for an imported row: the citation attribute is the only representation of the message↔publication relation, there is no join table, and a re-keyed import dangles every citation in the archive with no way to find them. **ISS-022, REF-010, a settled decision.** |
| `series` | `PublicationSeries` `@ManyToOne @NotNull` | `series_id INTEGER NOT NULL` FK | no | — | must resolve | Owning series. NOT NULL for every issue including one-offs, which are modelled as a `cadence = NONE` series holding exactly one issue — that is what keeps C7's "delete only a series with no issues" rule coherent and gives one-offs their category, domain, `messagePublication` and reference format for free. |
| `repoPath` | `String` | `repoPath VARCHAR(128) NOT NULL` | no | `UidUtils.uidToHashedFolderPath("publications", publicId)` at create — **importable, not always re-derived** (R6): the historical importer writes the legacy publication's own repo path verbatim | `@NotNull` | `publications/<p[0]>/<p[1..2]>/<publicId>` — verified derivation, `UidUtils.java:70-85`. **No `revision` column exists, and no newly published issue carries a revision segment.** <br>**Imported issues are the exception, and it is deliberate (R6).** Legacy stores bytes at `<repoPath>/<revision>/<fileName>` and writes that exact string into stored message HTML as the citation href; re-deriving the path for the 1,077 imported publications would 404 every file and every citation that points at it, and 42 of those rows no longer have their bytes at all. So an imported issue keeps its legacy `repoPath` (and its `filePath`/`link`, §3.7) exactly as they are, revision segment included, and the constraint is the containment rule `filePath startsWith issue.repoPath` (D-4) rather than a shape rule about segments. A flat layout would require a one-time file move plus a verification pass over every stored link, and an answer for the 42 rows whose bytes are gone. Legacy commits only the `<revision>` sub-folder (`RepositoryService.java:570-594`) and the public link embeds it, which is why an amend already changes the URL today and why only 26 of 68 publications at revision ≥ 2 still serve their previous revision. C3 requires a stable URL, so for an issue published by this system the file lives at `<repoPath>/<fileName>` (§3.7) and the outgoing copy goes to an archive root **outside** the `@PermitAll` repository (`RepositoryService.java:67-69`). Length 128 copies `Message.repoPath` (`Message.java:105-106`); the value is ~54 chars. **C3.** |

#### 3.6.2 Interval and cut-off

| Field | Java type | Column | Null | Default | Validation | Semantics · **forced by** |
|---|---|---|---|---|---|---|
| `intervalFrom` | `Date` | `intervalFrom DATETIME(6)` | **yes** | `null` | **must be null** when `series.timeRelation = IN_FORCE_AT_CUTOFF`; when `PUBLISHED_IN_INTERVAL`, equals the `cutoffStampedAt` of the PUBLISHED issue with the greatest `cutoffStampedAt` **strictly below this issue's `intervalTo`** (chaining, stamp-ordered and half-open — I-7, §4.3 T0) unless an `INTERVAL_CHANGED` audit entry explains the deviation | Lower bound of the membership window, **exclusive**. NULL = *no lower bound at all*. Nullable because **531 issues have no interval** — P&T (502) + Skydeområder (16) + EfS A (13), against 500 weekly EfS. **MODEL-VERIFICATION §3.1, §5.2.** |
| `intervalTo` | `Date` | `intervalTo DATETIME(6)` | **yes** | derived from `series.cadence` at create | non-null when `series.contentMode = GENERATED_FROM_QUERY`; null only for the ~48 no-membership publications | **The nominal cut-off** — the upper bound of the membership window, **inclusive**. Displayed as "nominel — stemples ved publicering" until published. <br>⚠ **Naming hazard, stated so it is not inferred:** this column is present for **both** time relations, but for `IN_FORCE_AT_CUTOFF` there is no interval — only an upper bound. A developer who reads `intervalFrom`/`intervalTo` as a window and applies tiling to those 531 issues produces exactly the EfS-shaped bug MODEL-VERIFICATION §3.1 warns about. The name is kept because the transition, API and criteria sections all use it; the hazard is closed by `intervalFrom IS NULL` and by gating every tiling/gap behaviour on `series.timeRelation`. |
| `intervalFromSource` | `IntervalBoundSource` (`STAMPED`, `NOMINAL`, `RECOVERED`, `MANUAL`) | `intervalFromSource enum('STAMPED','NOMINAL','RECOVERED','MANUAL')` | yes | `STAMPED` when T0 chains off the predecessor's stamp; `NOMINAL` when derived from `series.firstIssueStartsAt` or the cadence; `RECOVERED` by the importer; `MANUAL` when typed | non-null **iff** `intervalFrom` non-null | **Where the lower bound came from — a real column, not a derivation** (R4). The approved UX puts a "stemplet / nominel" marker on **every** interval in the issue list and on the retro-create dialog's prefill, and no derivation can produce the `MANUAL` value the enumeration declares, because "an admin typed this" is not recoverable from the value itself. Values and their meaning: §7.9. |
| `intervalToSource` | `IntervalBoundSource` | `intervalToSource VARCHAR(255)` | yes | `NOMINAL` at create (derived from the cadence); `MANUAL` when an admin edits the nominal cut-off; `RECOVERED` by the importer | non-null **iff** `intervalTo` non-null | Same, for the upper bound. Note that publish does **not** rewrite `intervalTo` or this column with the stamp (§4.4 "what publish does not do") — the stamped instant lives on `cutoffStampedAt` with its own `cutoffSource`, and `intervalToSource` keeps saying where the *nominal* bound came from. `STAMPED` is legal here only for an issue whose upper bound was set from a neighbouring issue's stamp during retro-creation. |
| `cutoffStampedAt` | `Date` | `cutoffStampedAt DATETIME(6)` | yes | `null` | non-null iff `status ∈ {PUBLISHED, RETIRED}` and `membershipProvenance ≠ NO_MEMBERSHIP` | The **stamped release moment** — the authoritative cut-off, to the millisecond. Set at publish to NOW or to an admin-chosen instant; set by the importer from the recovery cascade. Precision is decisive: P&T uge 12/2024's member set changes **4 seconds** after its stamp, NM-466-26 is decided by **62 seconds**. |
| `cutoffSource` | `CutoffSource` | `cutoffSource VARCHAR(255)` | yes | `null` | non-null iff `cutoffStampedAt` non-null | Recorded provenance of the stamp. Required because **the cut-off oracle does not transfer**: `publication.updated` recovers the release moment for ~493 of 499 weekly and 208 of 214 P&T issues but only **13 of 35** non-weekly tag-carrying publications, and 27 annual cut-offs had to come from **printed PDF cover dates**. Values are the enumerations section's; the cascade is `updated` → the next issue's `messageTag.created` where they differ by > 5 min → the printed cover date → MANUAL. **MODEL-VERIFICATION §3.2, §5.6, §5.12.** |
| `cutoffReconstructed` | `boolean` | `cutoffReconstructed BIT(1) NOT NULL`, `columnDefinition = "BIT(1) default 0"` | no | `false` | — | True for every issue whose cut-off was recovered rather than stamped by this system. Redundant with `cutoffSource` by construction, stored because it is the **indexed predicate** for the issue-list badge and for any sweep that has to separate recovered cut-offs from stamped ones, and because it survives future `cutoffSource` values. **A settled decision** ("reconstructed cut-offs flagged"). |

**Effective cut-off** — the single value every consumer must use: `effectiveCutoff = cutoffStampedAt != null ? cutoffStampedAt : intervalTo`.

**Interval openness** is `(previousCutoff, thisCutoff]` — strict below, closed above. It is a **resolver invariant, not a per-series field**, deliberately: a per-series variant would be unverifiable, because data cannot arbitrate the convention at all (zero of 10,413 `dma-nm` messages have `publishDateFrom` exactly equal to any of the 992 cut-off stamps). Recorded here because it constrains nothing in the schema but everything in what these two columns *mean*. The implementation trap that makes it a code decision: `PUBLISH_FROM_DATE` maps to `CriteriaHelper.between(...)`, which is `>=` **and** `<=` — verified, `CriteriaHelper.java:179-187` — closed at *both* ends, so transcribing it puts a boundary message into two adjacent issues and breaks tiling.

#### 3.6.3 Public visibility window — a separate pair, on purpose

| Field | Java type | Column | Null | Default | Validation | Semantics · **forced by** |
|---|---|---|---|---|---|---|
| `publicFrom` | `Date` | `publicFrom DATETIME(6)` | yes | set at publish | ≤ `publicTo` when both set | Start of public visibility. **= this issue's `cutoffStampedAt`.** NULL ⇒ the issue never appears on `/public/v1/publications`. Maps to `PublicationVo.publishDateFrom`. |
| `publicTo` | `Date` | `publicTo DATETIME(6)` | yes | `null` | **NULL only when no successor exists** (I-19); at most one issue per series may carry NULL (I-18) | End of public visibility. **= the NEXT issue's `cutoffStampedAt` minus 1 ms.** It is written by **whichever publish transaction closes the bracket**, and there are two of them (R5): the successor's publish writes it *retroactively onto this row* (§4.4 step 13), **and** this issue's own publish writes it immediately when a successor already exists (§4.4 step 12) — which is the case for every retro-created gap issue, because for a gap week every successor has already published. NULL therefore means *"nothing supersedes this yet"*, not "open-ended by choice"; genuine open-endedness is `publicWindowSource = MANUAL` on a one-off. Maps to `PublicationVo.publishDateTo`. <br>**Without the symmetric write the flagship gap-recovery flow is wrong in the worst possible direction:** recovering uge 41/2024 would leave the 2024 issue with `publicTo = NULL` while step 13 capped **this week's live EfS**, and `overlaps(…, now, now)` would then make the 2024 issue the site's current publication. <br>**The −1 ms is required, resolving fragment B's exact-equal proposal:** `CriteriaHelper.overlaps()` is closed at both ends (verified, `:199-207`), so an exactly-equal boundary would make two issues *both* current for one instant. **ISS-015, ISS-016; R5.** |
| `publicWindowSource` | `PublicWindowSource` (`DERIVED`, `MANUAL`) | `publicWindowSource enum('DERIVED','MANUAL') NOT NULL` | no | `DERIVED` | **the importer writes `DERIVED` for any series with a cadence** (R8) | `DERIVED` ⇒ the publish transaction owns the pair and does the capping of §4.4 steps 12–13. `MANUAL` ⇒ an admin owns it and the chain does not rewrite it. <br>**R8 splits what `MANUAL` used to conflate.** A window that is a *derived historical fact* — every issue of a cadenced series, imported ones included — is `DERIVED`, because the chain is exactly what produced it. `MANUAL` is reserved for **genuinely open-ended one-offs**: the four citation-only publications current since 01.01.2016 and the external-link rows that no successor will ever supersede. Marking imported annuals `MANUAL` is what would leave the newest imported issue uncapped and hand the public site **two current EfS issues** at the first native publish. <br>**Belt and braces:** the predecessor is capped regardless of this column whenever its `publicTo IS NULL` (§4.4 step 13, I-14), and the import check asserts I-18 — at most one uncapped issue per series — as the go/no-go of the go-live window. This column is still what makes ISS-015 (supersede-by-capping) and ISS-016 (permanently current) two behaviours rather than one bug. |

**Why this is its own pair and not the interval — the single failure the ledger flags twice.** The public window is **offset from the content interval by one period**. Live: `EfS uge 33 - 2026` (`5cc97a7f`) carries `publishDateFrom = 2026-08-12T10:00Z`, `publishDateTo = 2026-08-19T09:59Z`, while its release is `updated = 2026-08-12T10:30:18Z`. The content interval *ends* at the cut-off; the public window *starts* there. Mapping `intervalFrom`/`intervalTo` onto `publishDateFrom`/`publishDateTo` — the only mapping the entity names suggest — gives every issue the previous period's window and, under the public API's `from = to = now` default, **the newest issue is never current**: the site would show last week's EfS from the moment of cutover. **ADD-029, VIS-005, ISS-014.**

| Series shape | `intervalFrom` | `intervalTo` | `cutoffStampedAt` | `publicFrom` | `publicTo` |
|---|---|---|---|---|---|
| Weekly EfS (`PUBLISHED_IN_INTERVAL`) | prev issue's stamped cut-off | nominal Wed 12:00 | actual release moment | = `cutoffStampedAt` | next issue's cut-off − 1 ms |
| P&T / firing areas (`IN_FORCE_AT_CUTOFF`) | **NULL** | nominal cut-off | actual release moment | = `cutoffStampedAt` | next issue's cut-off − 1 ms |
| Dansk Fyrliste one-off (`NO_MEMBERSHIP`) | **NULL** | **NULL** | **NULL** | admin-set, `MANUAL` | **NULL** — forever current, because no successor exists |

**Capping applies to every series, both time relations, and it is symmetric.** Membership tiling is gated on `timeRelation`; the *public window* is not. Legacy tiled the public window even for the overlapping annuals — Skydeområder 2017 ed1 `01.01 → 28.06 09:59`, then ed2 `28.06 10:00 → 31.12`. Two issues may share 31 of 32 members and still have disjoint public windows. **Symmetric** means a publishing issue closes its own window against an existing successor as well as capping its predecessor (§4.4 steps 12–13, R5); the two writes together are what enforce I-18's "at most one issue per series with `publicTo IS NULL`".

#### 3.6.4 Status and lifecycle stamps

| Field | Java type | Column | Null | Default | Validation | Semantics · **forced by** |
|---|---|---|---|---|---|---|
| `status` | `IssueStatus` (`OPEN`, `PUBLISHED`, `RETIRED`) | `status enum('OPEN','PUBLISHED','RETIRED') NOT NULL` | no | `OPEN` | `@NotNull` | Only **three** values are persisted. *Kommende* is a computed refinement of `OPEN` (`intervalFrom > now`), and *Mangler* is a pseudo-row with **no entity at all**. Importer mapping: legacy `DRAFT` + `RECORDING` → `OPEN`, `ACTIVE` → `PUBLISHED`, `INACTIVE` → `RETIRED` (**ISS-005**). Public eligibility is `PUBLISHED` only; `?publication=` resolvability is `PUBLISHED` **or** `RETIRED`, so a retired issue's citations do not dangle. **C7, C9.** |
| `publishedAt` | `Date` | `publishedAt DATETIME(6)` | yes | `null` | non-null iff `status ∈ {PUBLISHED, RETIRED}` | When the publish *action* ran. **Distinct from `cutoffStampedAt`**, because a settled decision lets the admin publish now with a manually chosen cut-off instant — the two differ by design, and conflating them re-creates the "`updated` is the release moment" error at a new address. |
| `publishedBy` | `User` `@ManyToOne` | `publishedBy_id INTEGER` FK → `User(id)` | yes | `null` | — | NULL ⇔ published by the automatic gate (`series.releaseMode = AUTO_RELEASE`); the paired audit entry then carries `actorKind = SYSTEM`. Nullable `@ManyToOne User` follows `MessageHistory.java:52-54`. |
| `retiredAt` | `Date` | `retiredAt DATETIME(6)` | yes | `null` | non-null iff `status = RETIRED` | **C7.** |
| `retiredBy` | `User` `@ManyToOne` | `retiredBy_id INTEGER` FK → `User(id)` | yes | `null` | non-null iff `status = RETIRED` | **C7** — retire is admin-only. |
| `retiredReason` | `String` | `retiredReason VARCHAR(512)` | yes | `null` | non-blank, 3..512, iff `status = RETIRED` | **C7**: "reason required, audited". Also copied onto the `RETIRED` audit entry, so the Historik panel needs no join and the reason survives a later reactivate (which clears this column). Length follows `ReferenceDesc.java:35`; see **DM-Q7** (§9.4). |
| `workingAction` | `String` | `workingAction VARCHAR(16)` | yes | `null` | `PUBLISH` \| `AMEND`, non-null iff `workingSince` is | **New 2026-09-14, `V19__issue_work_marker.sql`.** The release or amend **in flight** on this issue — written by `IssueWorkMarker` in its own transaction before the render starts and cleared when the request ends, so a refreshed page, another tab and another admin all read the same fact from I30's `inFlight`. **Not part of the issue's record**: mapped read-only on the entity (only the marker's two statements write the pair), never exported, never compared by the import check. A marker older than the marker's ceiling (ten minutes) is treated as stale — a dead render must never lock an issue — and every marker is cleared at boot. |
| `workingSince` | `Date` | `workingSince DATETIME(6)` | yes | `null` | non-null iff `workingAction` is | When the work above started; what I30 emits as `inFlight.since` and what the ceiling is measured from. |

#### 3.6.5 Frozen-snapshot header

Forced by REVIEW **B6** (the plan contradicted itself on whether a snapshot exists) and MODEL-VERIFICATION §5.5: message `type`, `publishDateFrom` and `publishDateTo` are **mutable and unversioned**, so a live re-resolve silently rewrites already-published history. Proven case: NM-300-24 sits in 10 consecutive seeded tags and drops out of the 11th while still PUBLISHED and in force, because the query reads *today's* type.

| Field | Java type | Column | Null | Default | Validation | Semantics · **forced by** |
|---|---|---|---|---|---|---|
| `membershipProvenance` | `MembershipProvenance` (`EXACT`, `EXPLAINED_DIFF`, `UNION_SNAPSHOT`, `NO_MEMBERSHIP`, **`IMPORTED`**, **`COMPILED`**) | `membershipProvenance enum('EXACT','EXPLAINED_DIFF','UNION_SNAPSHOT','NO_MEMBERSHIP','IMPORTED','COMPILED')` **(`IMPORTED` added 2026-08-23 by `V4__membership_provenance_imported.sql`, ruling `B5-iv`; `COMPILED` added 2026-09-13 by `V18__compilation_series.sql`)** | yes | `NO_MEMBERSHIP` set at create when `contentMode ≠ GENERATED_FROM_QUERY`, else `null` | non-null iff a snapshot exists or the content mode has none | How much the snapshot can be trusted, and whether a comparison against the query means anything for it at all. Without it, a zero-member issue is indistinguishable from a resolver bug. Required because **four annual issues are the answer to no query at any instant** (Skydeområder 2018/2020-ed1/2022-ed1, EfS A 2020-ed1 — every `publishDate` boundary ±1 ms was swept across the whole series history and none reproduces the recorded set), and ~48 publications have no membership semantics at all. **MODEL-VERIFICATION §5.7, §5.17.** <br>**Amended 2026-08-23 (ruling `B5-iv`).** `NO_MEMBERSHIP` means *no membership semantics* — nothing derives the contents — and that is **compatible with rows that were named by hand**. It is **not** a claim that the row count is zero. The six tag-carrying annexes import with **one member row each** under the new `IMPORTED` provenance, because the locked tag is the only surviving record of what that annex contained: the series carries two live messages a year (B/1 ice service, B/2 NCAGS) and no query of any shape can select one and not the other, since the only discriminator is the message body. The **ten** tagless annexes still import with zero rows — measured as **thirteen** against the captured estate — and that is a different case: nothing to import, rather than something discarded. `IMPORTED` is not a query provenance, so `I-12`'s `criteriaSnapshot IS NOT NULL` condition reads "when the provenance is a query provenance", not "when there are members". <br>**Amended 2026-09-13 (compilation series).** `COMPILED` is the sixth value and the second non-query provenance: the rows were derived, and honestly so, but from another series' frozen member rows rather than from a predicate — so there is no query left for a comparison to re-run, and a compiled issue is exempted **explicitly**, on the regime, rather than by noticing that its criteria are null. It is also what makes the header's own null `criteriaSnapshot` and null `snapshotAliveAtCutoff` legible: a compiled snapshot records its **sources** instead (the two columns below), and those are the operands. |
| `membershipProvenanceNote` | `String` | `membershipProvenanceNote TEXT` | yes | `null` | required when provenance ∈ {`EXPLAINED_DIFF`, `UNION_SNAPSHOT`} | The named divergence class or blocklist reason — "type mutated after release", "union over a long window", "contaminated oracle `nm-w01-2025`". `TEXT` rather than a bounded varchar because it is diagnostic prose, never a UI-width field. **MODEL-VERIFICATION §4, §5.15.** |
| `snapshotFrozenAt` | `Date` | `snapshotFrozenAt DATETIME(6)` | yes | `null` | non-null iff `IssueMember` rows exist | When the snapshot was written. Re-set on amend only if the amend re-freezes. |
| `memberCount` | `int` | `memberCount INTEGER NOT NULL`, `columnDefinition = "INT default 0"` | no | `0` | equals the `IssueMember` row count (I-11) | Denormalized member count. The `MessageTag.messageCount` idiom, comment and all — "More efficient than counting related messages" (`MessageTag.java:94-96`). <br>**The column is `int NOT NULL`; the wire field is not** (MF12). `API-CONTRACT.md` §3.4 exposes `memberCount` as a nullable `Integer` whose null-versus-zero distinction is load-bearing, so it is a **derived** field, not a straight projection. The rule has **three branches**, and the third is not optional: `membershipProvenance = NO_MEMBERSHIP` → **`null`**; `status ∈ {PUBLISHED, RETIRED}` → **the frozen column**; `status = OPEN` (including computed `UPCOMING`) → **the live resolved count**, because an OPEN issue has no snapshot yet and its column is still `0` — serving that column would tell an admin previewing this week's issue that it is empty. Without the first branch the ~48 no-membership publications emit `0`, which the contract's own text reads as "resolved, and empty" — the exact confusion `membershipProvenance` exists to prevent, one field along; without the third, every OPEN issue reads as empty until it publishes. Keeping the column NOT NULL keeps I-11 a clean equality **against the column**, unaffected by any of this. |
| `snapshotTimeRelation` | `TimeRelation` | `snapshotTimeRelation VARCHAR(255)` | yes | `null` | non-null iff `snapshotFrozenAt` non-null and provenance ≠ `NO_MEMBERSHIP` | The time relation **as it was at freeze**. Echoed on every member-list response so the UI can state the rule that was applied, and read on a historical issue to decide whether tiling logic applies to it at all. |
| `snapshotAliveAtCutoff` | `Boolean` | `snapshotAliveAtCutoff BIT(1)` | yes | `null` | **non-null iff `snapshotTimeRelation` is a query relation** — i.e. NULL for a `NO_MEMBERSHIP` snapshot and (**amended 2026-09-13**) for a `COMPILED` one, whose members were judged at their own source issues' cut-offs | The **effective** value actually applied: `true` for `IN_FORCE_AT_CUTOFF` (where the clause is constitutive), the series flag for `PUBLISHED_IN_INTERVAL`. <br>Under R2 this echo now **agrees with the series column** (§3.1.5) rather than deviating from it: both are NULL exactly when there is no query, and both are `TRUE` under `IN_FORCE_AT_CUTOFF`. The echo is still stored rather than re-read, because the series flag is editable after an issue publishes, so replay must be literal. **C2 / R2.** |
| `snapshotIntervalFrom` | `Date` | `snapshotIntervalFrom DATETIME(6)` | yes | `null` | NULL when `snapshotTimeRelation = IN_FORCE_AT_CUTOFF`; non-null otherwise once a snapshot exists | The interval lower bound **actually used at freeze** — the predecessor's stamped cut-off. Frozen separately from the live `intervalFrom` column because a later **retro-creation legitimately changes the neighbour chain** (§4.3 T0 `CREATED_RETROACTIVELY`, and R5's symmetric capping makes that a first-class flow), so the live column can cease to describe what the published issue was resolved against — and I8's interval edit can move a bound directly. Written by §4.4 step 7, echoed as `resolved.intervalFrom` in §5.6, and served as the member list's `intervalFrom` on every **FROZEN** list (`API-CONTRACT.md` §3.8; a LIVE list has no snapshot and reads the live column). <br>**It is a real column, and the earlier "not a column" reading is withdrawn.** Reading the live `intervalFrom` at response time for a published issue is precisely the pattern B6 exists to remove: the inputs are mutable, so what mattered is frozen. |
| `snapshotSeriesIds` | `List<String>` | `snapshotSeriesIds TEXT` + JSON converter | yes | `null` | non-empty when non-null | The message-series set **after domain expansion**, as at freeze. Denormalized out of `criteriaSnapshot` because C8's public status-widening and domain/series restriction-lifting must be reproduced **from the frozen snapshot** rather than from tag presence, and that runs per request. Freezing it also means adding a series to a domain later cannot rewrite what a 2018 issue meant. **C8; MODEL-VERIFICATION §5.21.** |
| `snapshotSourceSeriesId` | `String` | `snapshotSourceSeriesId VARCHAR(64)` | yes | `null` | non-null iff `membershipProvenance = COMPILED` | **New 2026-09-13 (compilation series), `V18__compilation_series.sql`.** The `seriesId` of the series this issue compiled, as at freeze. By the same argument that produced `snapshotSeriesIds`: the **operand** is a series and it is editable afterwards, so a compilation whose series was later re-pointed would otherwise answer with today's source when asked what it compiled. |
| `snapshotSourceIssueIds` | `List<String>` | `snapshotSourceIssueIds TEXT` (comma-separated `publicId`s, in cut-off order) | yes | `null` | non-null iff `membershipProvenance = COMPILED` | **New 2026-09-13 (compilation series), `V18__compilation_series.sql`.** Every source issue whose period fell inside this one, in the order their cut-offs fell — **I-20**. The **answer**, not the operand, and it cannot be re-derived: a source issue retired or published later moves the union, so a reader asking years afterwards what this annual actually covered has no other record. Written from the survey rather than from the frozen rows, so a source period that contributed no member is still named. A joined text column rather than a join table, for the same reason `IssueMember.sourceIssuePublicId` is text (§3.8): the compilation's record must survive the deletion of a row in another series. |
| `snapshotDomainId` | `String` | `snapshotDomainId VARCHAR(255)` | yes | `null` | — | The series' domain **as it was at freeze**. Denormalized for the same two hot paths, plus the sort-domain rule (sort by the *issue's* series domain, never the caller's). <br>**Note 2026-08-29 (R-xxvi): this is not the owner, and it stays nullable.** It records the domain the row carried at freeze — for an imported issue, the domain the **old publication row** carried, which is very often `null`. R-xxvi did not change it and the importer does not backfill it from the new owner: a 2018 issue must keep describing 2018. Read it as history, never as "who administers this publication now". |
| `snapshotSortBy` | `String` | `snapshotSortBy VARCHAR(32)` | yes | `null` | one of `id`, `date`, `publish_date`, `follow_up_date`, `area` | The `sortBy` that produced `IssueMember.sortIndex`. **OPTIONAL §3.5 + §6 carve-out — reserve now.** Every input is mutable and unversioned: `messageSortOrder` is an editable domain setting, `areaSortOrder` is a mutable float writable via `PUT /rest/messages/change-area-sort-order`, and the area tree moves. Without a stored order the same issue reprints differently — and `sortBy` decides more than sequence, because `report.pdf` derives `areaHeadings` from `sortByArea()`. |
| `snapshotSortOrder` | `SortOrder` | `snapshotSortOrder VARCHAR(255)` | yes | `null` | non-null iff `snapshotSortBy` non-null | Same. |
| `criteriaSnapshot` | `IssueCriteriaVo` | `criteriaSnapshot TEXT` + `@Convert(JpaCriteriaAttributeConverter.class)` | yes | `null` | valid criteria document | The **effective** criteria at freeze — the series criteria merged with `criteriaOverride`. Renders the published-issue screen's "Kriterier (som de gjaldt ved publiceringen)". |
| `criteriaOverride` | `IssueCriteriaVo` | `criteriaOverride TEXT` + same converter | yes | `null` | valid criteria document; null unless `contentMode = GENERATED_FROM_QUERY` | The per-issue deviation from the series criteria ("Tilpas for denne udgave…"). NULL = inherit the series unchanged. **C4** — the discriminated-union shape is what keeps a later hatch purely additive. |

#### 3.6.6 Naming, edition and supersede

| Field | Java type | Column | Null | Default | Validation | Semantics · **forced by** |
|---|---|---|---|---|---|---|
| `week` | `Integer` | `week INTEGER` | yes | derived at create, re-derived on interval edit | `1..53` | **ISO week of the effective cut-off (the END), never the interval start.** On the canonical Wed→Wed interval, deriving from the start returns "Uge 26+27, 2026" where production says "EfS uge 27"; the prototype's `suggestName()` does exactly that and must not be copied. Stored rather than computed so the value that named the issue and the PDF is on the row and does not drift when the derivation is fixed. **The settled canonical rule; SER-009.** |
| `weekTo` | `Integer` | `weekTo INTEGER` | yes | `null` | `> week`, or wraps the year | Multi-week issues ("Uge 2+3, 2026"). Nine live double issues; previously a template-cloning workaround, which is a direct cause of the hand-cloned templates behind C8's tag collisions. **ISS-028.** Set when the window spans more than one cadence period (13 whole days or more, 1¾ periods) **and** closed two or more ISO weeks: `weekTo` is the cut-off week and `week` the first ISO week after the one the previous cut-off fell in, counted in ISO weeks, not in days (`IssueNaming.derive`, since 2026-11-19, NAMING-1: nineteen days from week 35 into week 38 had been named `37+38`, and week 36 by no issue). Printed as a pair, `36+37`, for two weeks and as a range, `36-38`, for three weeks or more (`52-2` across a year's end, beside the later year): the `+` a pattern writes between `${week}` and `${weekTo}` prints as a hyphen then (since 2026-11-20; domain rules, "Numbering and naming"). |
| `year` | `Integer` | `year INTEGER` | yes | derived | `2000..2100` | **ISO week-year** when `series.numberingScheme = ISO_WEEK_YEAR`, calendar year otherwise. Legacy pairs a correct ISO week with the **calendar** year (verified: `PublicationTemplateUpdateCtx.java:60-66` uses `Calendar.YEAR` with `getISO8601WeekOfYear`) and reads it in the JVM default timezone, so a 31.12.2025 cut-off yields "EfS uge 1 - **2025**". Derived in `series.nominalCutoffTimeZone`. **REVIEW F6.** |
| `weekLabel` | `String` | `weekLabel VARCHAR(64)` | yes | `null` | — | **What this edition is *called* where the derived week would be printed** (`V15__issue_overrides.sql`). Free text **beside** the number, never instead of it, and the split is the point: `week`/`weekTo`/`year` above are arithmetic — the list order, the timeline strip, gap detection and the archive read them, and nothing typed can reach any of that — while these labels are read by everything that **prints**. A double week is written here (`"36+37"`, `"36 og 37"`, `"36 & 37"` are all in the estate, and were previously only expressible by cloning a series). Absent means the derived number prints, which is how "follow the cut-off again" is said. |
| `yearLabel` | `String` | `yearLabel VARCHAR(64)` | yes | `null` | — | The same, for the year, and the place a year that is not a number goes: an edition spanning a turn of the year is titled `"2025/2026"` by hand. `V15`. |
| `reportId` | `String` | `reportId VARCHAR(64)` | yes | `null` | must resolve like the series column | **The report *this* edition renders with, where it is not the series'** (`V15`). Null means it follows the series, which is what all but a handful do. Resolved through the effective-report accessor, **never read raw** — a render, a preview or a label takes `issue.reportId ?? series.reportId`. It exists because an edition that had to be set out differently previously required cloning the whole series into a throwaway one; six such clones are in the imported estate, fragmenting the archives they were cloned from. Same width as the series column, since it holds the same identifiers. |
| `edition` | `String` | `edition VARCHAR(64)` | yes | `null` | — | Free text, with **no ordering and no supersede semantics**. Passed to the report verbatim as `param:edition`. Widened from legacy `Integer` (verified: `Publication.edition` is `Integer`) so `"2025"`, `"v2"` and any non-numeric value round-trip; the importer stringifies. **MODEL-VERIFICATION §5.8; ISS-018.** The legacy export adapter must parse back to `Integer` or refuse — see **DM-Q7** (§9.4). |
| `supersedes` | `PublicationIssue` `@ManyToOne` | `supersedes_id INTEGER` FK → `PublicationIssue(id)` | yes | `null` | must be a different issue of the **same** series; no cycles | **Explicit** supersede reference, because `edition` cannot carry it: Skydeområder 2025 and EfS A 2025 both carry `edition = 2025` and both covers print "Version 2025". Self-FK follows `Publication.template` (`:111-112`). The inverse (`supersededBy`) is a named query, not a column, to avoid a bidirectional write hazard. Setting it triggers the ISS-015 capping side effect on `publicTo`. **MODEL-VERIFICATION §5.8.** |
| `descs` | `List<PublicationIssueDesc>` `@OneToMany(cascade = ALL, mappedBy = "entity", orphanRemoval = true)` | child table | — | one row per configured language | exactly one row per entry in `series.languages` | **`Publication.java:168-169`.** |

**Per-issue report parameters — `reportParams` exists on the issue too** (MF13). The contract exposes `reportParams` on `SystemPublicationIssueVo` and makes it writable on the issue-update endpoint, and legacy carries it per publication (`Publication.java:240-241, 285-287`), so the model must have somewhere to persist it. It is a real column, not a derived value:

| Field | Java type | Column | Null | Default | Validation | Semantics · **forced by** |
|---|---|---|---|---|---|---|
| `reportParams` | `Map<String,Object>` | `reportParams TEXT` + `@Convert(JpaPropertiesAttributeConverter.class)` | no | `{}` | identical to the series column (§3.1.7): key `^[A-Za-z0-9_.\-]{1,64}$`; no `param:` prefix; **`week`, `year` and `edition` rejected** | This issue's own report parameters, emitted into the FreeMarker data model as `param:<key>`. **Merge rule at render time: the issue map is merged *over* the series map, key by key, and the issue wins**; a key absent from the issue map inherits the series value. `week`/`year`/`edition` stay rejected on both because they are injected from the issue's own columns and must never be typed (FIL-023 + a settled decision). Read by §4.4 step 10 and by the amend re-render (§4.5 step 4). Same column idiom as `Publication.java:163-166`. **FIL-022 preserved per issue.** |

#### 3.6.7 Indexes

```java
@Table(indexes = {
    @Index(name = "publication_issue_public_id_k",  columnList = "publicId"),
    @Index(name = "publication_issue_legacy_id_k",  columnList = "legacyPublicationId"),
    @Index(name = "publication_issue_series_k",     columnList = "series_id"),
    @Index(name = "publication_issue_status_k",     columnList = "status"),
    @Index(name = "publication_issue_cutoff_k",     columnList = "cutoffStampedAt"),
    @Index(name = "publication_issue_public_k",     columnList = "publicFrom,publicTo")
})
```
`publicId` mirrors `publication_id_k` on the legacy table (verified: `Publication.java:62-67`). `publicFrom,publicTo` serves the public adapter's `overlaps(…, now, now)`. `legacyPublicationId` serves the public union's exclusion subquery.

---

### 3.7 `PublicationIssueDesc`

`@Entity` · `extends DescEntity<PublicationIssue>` · `@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"lang", "entity_id"}))`.

| Field | Java type | Column | Null | Default | Validation | Semantics · **forced by** |
|---|---|---|---|---|---|---|
| `id` | `Integer` | `id INTEGER NOT NULL` (PK) | no | shared sequence | — | `DescEntity extends BaseEntity<Integer>`. |
| `lang` | `String` `@NotNull` | `lang VARCHAR(255) NOT NULL` | no | — | must be in `issue.series.languages` | Inherited; see §3.3 on the 255. |
| `entity` | `PublicationIssue` `@ManyToOne @NotNull` | `entity_id INTEGER NOT NULL` FK | no | — | — | Inherited. |
| `name` | `String` `@NotNull` | `name VARCHAR(255) NOT NULL` | no | expanded from `series.descs[lang].nameSuggestionPattern` at create | `@NotNull`, non-blank | The issue's name in this language — "EfS uge 27 - 2026" / "NtM Week 27 - 2026". **C5 replaces `PublicationIssue.name` as a single value**, which `implementation-plan.md:16` currently precludes. Mirrors `PublicationDesc.title` (`@NotNull`). Emitted as `PublicationDescVo.title`. |
| `nameOverridden` | `boolean` | `nameOverridden BIT(1) NOT NULL`, `columnDefinition = "BIT(1) default 0"` | no | `false` | — | True once an admin has typed a name in this language. The settled rule: the name is "auto-suggested from the interval and always overridable per issue" — so an interval edit must re-derive the suggestion **only where the admin has not overridden it**, and per language, because the patterns are per language. |
| `fileNameOverridden` | `boolean` | `fileNameOverridden BIT(1) NOT NULL`, default `0` | no | `false` | — | **The twin of `nameOverridden`, and it exists for the same reason** (`V15__issue_overrides.sql`). A file name the series' pattern produced is a *rendering* of the period, so it re-renders whenever the period or the printed numbering moves; a file name somebody typed is a *decision*, and re-deriving over it would discard the decision with nothing left to say it had been made. Without the flag the two are indistinguishable, because the column also holds the name of an `UPLOADED` document and the name the last publish happened to write — neither of which is a decision about what the file should be called. **Clearing the override leaves `fileName` alone**: the file that exists keeps the name it has, and only stops being preferred over the pattern. |
| `fileName` | `String` | `fileName VARCHAR(255)` | yes | expanded from `series.descs[lang].fileNamePattern` | **distinct across the issue's languages**; must carry the content type's extension; **immutable once `publishedAt` is set** | `EfS-Uge-27-2026.pdf` vs `NtM-Week-27-2026.pdf`. The public download site links these names directly. Immutability after publish is C3: a later edit of the series' file-name pattern must not move a published file, because that changes the public URL and dangles every citation. Emitted as `PublicationDescVo.fileName`. **C5; FIL-025; ADD-027.** |
| `filePath` | `String` | `filePath VARCHAR(512)` | yes | `<issue.repoPath>/<fileName>` for an issue published here; **carried verbatim from legacy for an imported issue** (R6) | must start with `issue.repoPath`; **immutable once `publishedAt` is set** | Repo-relative path of the file on disk. Stored **separately from `link`** because (a) an `EXTERNAL_LINK` issue has a `link` and no path, and (b) resolving the file for C3's archive-then-overwrite must not mean parsing a URL — legacy rewrites links by string-matching `"/rest/repo/file/"` (`Publication.java:349-357`), a bug class this kills. **For an issue published here: no revision segment and no `published/` sub-folder** — the stable-URL guarantee comes from `fileName` + `filePath` being immutable after publish, not from a path segment, and an extra segment is one more thing an amend can get wrong. **An imported issue's `filePath` is written verbatim from legacy** (R6), `<repoPath>/<revision>/<fileName>` and all, because that exact string is the citation href inside stored message HTML; the only constraint it must satisfy is containment under the issue's own `repoPath` (D-4). **C3; R6.** |
| `link` | `String` | `link VARCHAR(1024)` | yes | `RepositoryService.getRepoUri(filePath)`; **verbatim from legacy for an imported issue** (R6) | **relative** (`/rest/repo/file/…`) for repository-hosted files; absolute `https://…` only for `EXTERNAL_LINK`; an imported link is accepted as stored, including a `<revision>` segment | Emitted as `PublicationDescVo.link`. Stored repo-relative and **externalized to an absolute URL by the public adapter** — storing an absolute repo URL makes `externalize` a no-op and bakes the wrong host into every environment. 1024 because external URLs are the unbounded case; the repo form is ~70 chars. **C5; VIS-008.** |
| `messageReferenceFormat` | `String` | `messageReferenceFormat VARCHAR(512)` | yes | expanded from `series.descs[lang].messageReferenceFormat` at create | may not be non-blank while `name` is blank (see below) | The citation text for **this issue** in this language, **already token-substituted** — `EfS 27/2026`, possibly still carrying `${parameters}`, which is resolved at citation time by the editor's dialog. Emitted as `PublicationDescVo.messagePublicationFormat`. Stored on the issue rather than expanded on read from the series pattern so a later edit of the pattern cannot change what a published historical issue's citation format says — the same freeze argument that governs `IssueMember`. See §2.3. **C5; REF-003, REF-004, REF-025.** |
| `fileSource` | `FileSource` (`GENERATED`, `UPLOADED`) | `fileSource enum('GENERATED','UPLOADED')` | yes | `null` | non-null iff `filePath` non-null | **C6**, per language — **not** a content type per issue, and it does **not** change the series' `contentMode` or the `PublicationVo.type` the adapter emits. |
| `fileSourceSticky` | `boolean` | `fileSourceSticky BIT(1) NOT NULL`, `columnDefinition = "BIT(1) default 0"` | no | `false` | — | **C6**: "sticky per issue-language so it survives an amend". When true, amend does **not** regenerate this language. A separate column rather than `fileSource == UPLOADED` because it must be explicitly clearable ("regenerate this language anyway") without first destroying the record of who uploaded. Every diff **skips** flagged files — a manually replaced file was never generated, so "reproducible from the member list" does not hold for it. |
| `replacedBy` | `User` `@ManyToOne` | `replacedBy_id INTEGER` FK → `User(id)` | yes | `null` | non-null **iff this upload replaced existing bytes** — set together with `replacedAt`, never on a first upload | **C6**: author of the manual **replacement**. <br>**Not "iff `fileSource = UPLOADED`"** (D-6, as relaxed): the pair means *"this upload replaced bytes that were already there"*, not *"this language was uploaded"*. A first hand-upload to an OPEN issue that had no file yet sets `fileSource = UPLOADED` and leaves both columns NULL, because it replaced nothing — which is exactly the distinction the audit trail already draws between `FILE_UPLOADED` and `FILE_REPLACED_MANUALLY` (§7.11). Tying them to `fileSource` would make the first upload unrepresentable. |
| `replacedAt` | `Date` | `replacedAt DATETIME(6)` | yes | `null` | non-null **iff this upload replaced existing bytes** — set together with `replacedBy` | **C6**: timestamp of the manual **replacement**. Pairs with a `FILE_REPLACED_MANUALLY` audit entry and a persistent badge on the issue, the release rail and the issue list. NULL after a first upload, for the reason in the row above. |
| `fileGeneratedAt` | `Date` | `fileGeneratedAt DATETIME(6)` | yes | `null` | — | When the generated file was last produced. Distinguishes a stale preview from the published file and drives "Forhåndsvisning regenereret". |
| `fileSize` | `Long` | `fileSize BIGINT` | yes | `null` | ≥ 0 | Bytes. Recorded so a C3 archive entry can be tied to the exact file it replaced. |
| `fileHash` | `String` | `fileHash VARCHAR(64)` | yes | `null` | sha-256 hex | Same, and it answers "did this file actually change on amend?" — needed because C6 makes every diff skip manually replaced files. |

**Save-time validation that is not obvious.** Reject an issue desc carrying a non-blank `messageReferenceFormat` with a blank `name`. Verified cause: `PublicationDescVo.descDefined()` is `fieldsDefined(title, titleFormat, link, fileName)` — it does **not** count `messagePublicationFormat` — and the adapter filters `descs` on `descDefined()` exactly as `Publication.java:261-266` does. A format-only language row therefore round-trips to nothing and the citation format silently disappears from the public payload (**REF-023**).

**Deliberately absent:** `titleFormat` and any pattern field. Both are series-level under C5; the issue supplies only `week`/`weekTo`/`year`/`edition` for their expansion. Recorded so nobody re-adds them by analogy with `PublicationDesc.java:36-57`.

---

### 3.8 `IssueMember` — the frozen member snapshot

`@Entity` · `extends BaseEntity<Integer>` · its own table, **not** a `@ManyToMany` to `Message`. Rows are written **only at freeze** (publish, or a re-freeze inside an amend) and never live.

| Field | Java type | Column | Null | Default | Validation | Semantics · **forced by** |
|---|---|---|---|---|---|---|
| `id` | `Integer` | `id INTEGER NOT NULL` (PK) | no | shared sequence | — | PK. |
| `issue` | `PublicationIssue` `@ManyToOne @NotNull` | `issue_id INTEGER NOT NULL` FK | no | — | — | Owner. |
| `messageUid` | `String` | `messageUid VARCHAR(36) NOT NULL` | no | — | `@NotNull`, `UidUtils.validUidFormat` | **The semantic key. Never `shortId`.** `dma-fa` holds 357 messages under only 331 distinct shortIds, and a shortId-keyed comparison silently reported 0-diff on issues that in fact differ by **eight** members. Length 36 matches `Message.uid` (verified: `Message.java:96-97`). **MODEL-VERIFICATION §5.16.** |
| `message` | `Message` `@ManyToOne` | `message_id INTEGER` FK → `Message(id)` | yes | resolved at freeze | — | Convenience association for rendering the member list. **Nullable on purpose**, so an import whose uid cannot be resolved records the member honestly rather than failing or fabricating one. |
| `sortIndex` | `int` | `sortIndex INTEGER NOT NULL` | no | — | dense, 0-based, unique within the issue, assigned over the **post-override** member set | **The recorded print order.** A printed report's order comes from `sortBy`/`sortOrder` resolved from a mutable domain setting, over a mutable `areaSortOrder` float, across an area tree that moves — so without this, the same issue reprints differently. **This is the one part of `OPTIONAL-issue-as-message-set.md` a reviewer must not drop:** it is schema, and `MaDaMe` has no migration tooling, so adding the column later is hand-rolled DDL against a live production schema. Reserve it now even if the optional phase is never built. Replay needs no new machinery — `MessageService.getMessages` already re-sorts to a caller-supplied id order via `ids.indexOf`. |
| `frozenShortId` | `String` | `frozenShortId VARCHAR(255)` | yes | value at freeze | — | Display and diff aid only. **Never a key.** |
| `frozenMainType` | `MainType` | `frozenMainType VARCHAR(255) NOT NULL` | no | value at freeze | `@NotNull` | `NW` \| `NM`. Captured so the member list and the `messageMainType` why-line render without a `Message` join, and so the frozen record is self-contained: the criteria vocabulary has a `messageMainType` node, and a frozen why-line that cannot say which main type matched is incomplete. It costs one varchar. |
| `frozenType` | `Type` | `frozenType VARCHAR(255) NOT NULL` | no | value at freeze | `@NotNull` | `Message.type` is a plain unversioned `@Enumerated` column with no history (verified: `Message.java:125-127`). **12 P&T messages across 26 issues are PERMANENT_NOTICE today and were P or T at their issue's release** — a live re-resolve silently drops them and rewrites official history. **MODEL-VERIFICATION §4, §5.5.** |
| `frozenStatus` | `Status` | `frozenStatus VARCHAR(255) NOT NULL` | no | value at freeze | `@NotNull`; ∈ `Status.isPublic()` at freeze | Same mutability. The conjunct is `status ∈ Status.isPublic()` — verified `Status.java:56-58` = `PUBLISHED \|\| CANCELLED \|\| EXPIRED` — **not** `status == PUBLISHED`: all 2,324 blank-era members are EXPIRED or CANCELLED and **zero** are PUBLISHED. **MODEL-VERIFICATION §5.10; REVIEW R2.** |
| `frozenPublishDateFrom` | `Date` | `frozenPublishDateFrom DATETIME(6)` | **yes** | value at freeze | — | Nullable, and the nullability is load-bearing: NM-780-18 carries a NULL `publishDateFrom` and leaks into 8 issues under `CriteriaHelper.overlaps()`, including P&T issues released a year before the message existed. Back-dating is real too — NM-1116-22 was published 2023-02-20 carrying a 2022 date. **MODEL-VERIFICATION §4, §6.** |
| `frozenPublishDateTo` | `Date` | `frozenPublishDateTo DATETIME(6)` | **yes** | value at freeze | — | **NULL = still open**, which is precisely what a NULL-unsafe alive clause drops: the unsafe form collapses P&T uge 28/2026 from 165 members to 47 and returns **zero** for the 2026 and 2027 firing-areas issues. **REVIEW R1; C2.** |
| `source` | `MemberSource` (`CRITERIA`, `OVERRIDE_INCLUDE`, `IMPORTED`, **`COMPILED`**) | `source enum('CRITERIA','OVERRIDE_INCLUDE','IMPORTED','COMPILED') NOT NULL` **(`COMPILED` added 2026-09-13 by `V18__compilation_series.sql`)** | no | — | `@NotNull` | Why this row exists. **`COMPILED` (2026-09-13)**: the row is here because a source issue printed it — the compilation's own derivation, as much an automatic membership as `CRITERIA` is, which is why curation treats the two alike (an include naming a message a source issue already printed is refused as already a member). Excluded messages are **not** rows here — they live as `IssueOverride(kind = EXCLUDE)`. <br>*Resolution:* fragment C's separate `reasonKind` (`CRITERIA_MATCH \| OVERRIDE_INCLUDE \| SNAPSHOT_ONLY`) is dropped as a duplicate of this column — the mapping is 1:1 and two columns that must agree is one column too many. |
| `sourceIssuePublicId` | `String` | `sourceIssuePublicId VARCHAR(36)`, index `issue_member_source_issue_k` | yes | `null` | non-null iff `source = COMPILED` | **New 2026-09-13 (compilation series), `V18__compilation_series.sql`.** Which issue of the source series printed this message — the one fact a compiled row carries that no other row needs, and what the grouped member list and the grouped document are built from. **A `publicId` text and deliberately not an FK:** the compiled row is the compilation's own frozen record, a retired source issue is deletable, and an FK would either block that deletion or need a cascade rule — both make one issue's record depend on another issue's row surviving. The id stays readable after the source is gone; the name, week and year are read live by `publicId` in **one** query per list (the `MessageNaming` pattern) while it is still there, and a row whose source has gone degrades to the id alone. A repeated message is owned by the **earliest** source issue (RI-16), so this column answers "which issue printed it here", not "which issues carried it". |
| `reasonNote` | `String` | `reasonNote VARCHAR(512)` | yes | `null` | set only when `source = IMPORTED` | The honest note behind a non-criteria membership — "Importeret fra tidligere system". **The only stored string on a member row.** For a `CRITERIA` member the why-line is fully **derivable** from the frozen scalars plus the snapshot header, so no rendered string is stored. |
| `override` | `IssueOverride` `@ManyToOne` | `override_id INTEGER` FK → `IssueOverride(id)` | yes | `null` | non-null iff `source = OVERRIDE_INCLUDE` | One hop to the author and reason, which **C9 shows to admins only**. Keeping it a FK rather than copied columns is what makes that redaction a single `instanceof SystemXVo` branch. |

**Constraints and indexes**

```java
@Table(
  uniqueConstraints = @UniqueConstraint(columnNames = { "issue_id", "messageUid" }),
  indexes = {
    @Index(name = "issue_member_issue_sort_k", columnList = "issue_id,sortIndex"),
    @Index(name = "issue_member_uid_k",        columnList = "messageUid"),
    @Index(name = "issue_member_source_issue_k", columnList = "sourceIssuePublicId")
  })
```
`issue_member_uid_k` is what serves C9's editor-tier question *"which issue(s) does this message belong to"*. `issue_member_source_issue_k` (2026-09-13) serves the reverse question a compilation asks of its own rows — which of them a given source issue printed.

**Not stored:** the member's title. It is rendered from the live message in the requested language at read time; only facts that *decide membership or order* are frozen.

**Excluded messages are not rows in this table.** They live as `IssueOverride(kind = EXCLUDE)`, frozen immutable at publish. That is the complete record of exclusions; no second table exists.

**Three columns that are deliberately absent, stated here so nobody restores them.** This table is specified **once**, above; an earlier duplicate field table in §6.4 carried different answers on all three and has been deleted rather than reconciled, because two field tables that must agree are one field table too many.

- **`reasonCode` is not a column** (ruling **X3**). One fragment carried both a `source` enum and a finer `reasonCode` (`MATCHED_PUBLISHED_IN_INTERVAL` / `MATCHED_IN_FORCE_AT_CUTOFF` / `MANUAL_INCLUDE` / `IMPORTED_FROM_LEGACY_TAG`). Every one of those values is derivable from `source` plus the header's `snapshotTimeRelation`, and a stored derivable enum is a second source of truth that can disagree with the first. The machine half of the settled "why included" line survives as the **wire** field `MemberReasonVo.kind` — `MemberReasonKind` is already listed as *(wire)*, owned by the resolver, in §7.13 — derived per response. `reasonNote` stays a column: the honest import note is a stored string nothing can derive.
- **`reasonKind` is not a column either.** Fragment C's `CRITERIA_MATCH | OVERRIDE_INCLUDE | SNAPSHOT_ONLY` maps 1:1 onto `source`; it is the same duplication one name along.
- **`overrideApplied` does not belong on the member row.** An EXCLUDE produces no member row and an INCLUDE that produced a row was by definition applied, so the flag would be constant-true here and could never record the stale-EXCLUDE case that motivated it. What it was reaching for is real and is levied on `IssueOverride` instead, as **`appliedAtPublish`** (§3.9) — set at freeze, `false` for an EXCLUDE whose target the query did not return anyway, so the published-issue screen can distinguish *"we deliberately took this out"* from *"this override never did anything"*. **The column is `appliedAtPublish` and nothing else** (ruling **X4**): `staleAtFreeze` is its logical negation, a retired spelling, and must not appear in the entity, the DDL, the VOs or the wire.

---

### 3.9 `IssueOverride`

`@Entity` · `extends VersionedEntity<Integer>` · the **only** live membership state. Writes require the named permission `PUBLICATION_CURATE`, not a role literal (**C1**), and the endpoint takes a message **selection** (a uid list), not a single uid.

| Field | Java type | Column | Null | Default | Validation | Semantics · **forced by** |
|---|---|---|---|---|---|---|
| `id` | `Integer` | `id INTEGER NOT NULL` (PK) | no | shared sequence | — | PK. |
| `version` | `int` | `version INTEGER NOT NULL` | no | `0` | must match on write | Optimistic lock. |
| `created` | `Date` | `created DATETIME(6)` | yes | `@PrePersist` | — | The override's timestamp, shown on the why-line. The permanent copy lives on the paired audit entry. |
| `updated` | `Date` | `updated DATETIME(6)` | yes | `@PreUpdate` | — | Set when the reason is edited. |
| `issue` | `PublicationIssue` `@ManyToOne @NotNull` | `issue_id INTEGER NOT NULL` FK | no | — | `issue.status` must be `OPEN` for any write (or inside an amend transaction) | Owner. |
| `messageUid` | `String` | `messageUid VARCHAR(36) NOT NULL` | no | — | `@NotNull`, valid UUID, must resolve to a `Message` | Keyed on **uid**, never `shortId` — same reason as `IssueMember`. An override targeting an unresolvable uid is a hard error, never silent. **MODEL-VERIFICATION §5.16.** |
| `message` | `Message` `@ManyToOne` | `message_id INTEGER` FK → `Message(id)` | yes | resolved on write | — | Convenience association. |
| `kind` | `OverrideKind` (`INCLUDE`, `EXCLUDE`) | `kind enum('INCLUDE','EXCLUDE') NOT NULL` | no | — | `@NotNull`; an `INCLUDE` of a uid already returned by the query is rejected at write time (it would produce two competing why-lines for one row) | Which direction the curation goes. |
| `author` | `User` `@ManyToOne @NotNull` | `author_id INTEGER NOT NULL` FK → `User(id)` | no | current caller | `@NotNull` | **C1**: "every override carries author/timestamp/reason". A `User` association, never a free string — `MessageHistory.user` is the precedent. <br>**Recorded regardless of who may write**, which is exactly what makes C1 cheaply reversible: widening curation to an editor tier later is a permission change with no migration. |
| `reason` | `String` | `reason VARCHAR(512) NOT NULL` | no | — | `@NotBlank`, 3..512 | **C1 / a settled decision.** Mandatory in the model precisely so the prototype's gap cannot ship: it *displays* quoted reasons ("dækket i uge 26") but `toggleRow()` writes a hardcoded string and no input field exists on any of the six toggle buttons. Length follows `ReferenceDesc.java:35`; see **DM-Q7** (§9.4). **C9 amendment:** author and reason are rendered to **admins only**. |
| `appliedAtPublish` | `Boolean` | `appliedAtPublish BIT(1)` | yes | `null` | set at freeze, null while `OPEN` | Whether this override actually changed the member set at freeze. An `EXCLUDE` whose target was not in the query result is *stale* — it is accepted (the query is time-varying and an admin retro-creating a week may legitimately pre-empt) but is frozen as `false`, so the published record does not claim a removal that never happened. <br>*Resolution:* fragment C put an equivalent flag on the member row; it belongs here instead, because a member row exists **only** when an override was applied, so the flag would be constant-true there and could never record the stale-EXCLUDE case that motivated it. |

**Constraint:** `@UniqueConstraint(columnNames = { "issue_id", "messageUid" })` — one override per message per issue. Flipping include↔exclude replaces the row and writes two audit entries. Index `issue_override_uid_k` on `messageUid`.

**Staleness is computed, never stored** (beyond the frozen `appliedAtPublish`). While an issue is OPEN, an `EXCLUDE` is *stale* iff its message is no longer in the query result and an `INCLUDE` is *redundant* iff its message now is; both surface in an "Overstyringer uden effekt" list for the admin to clear explicitly. Overrides are **never silently dropped** when the interval or criteria change — that is what stops a criteria edit from quietly rewriting a curated member list.

**The importer creates no overrides.** A settled decision: no fabricated overrides or audit. The single detectable legacy manual edit in ~10,200 members (NM-375-24) is an attribution by elimination, not a record.

---

### 3.10 `IssueAuditEntry`

`@Entity` · `extends BaseEntity<Integer>` · **append-only**: no `@Version`, no `updated`, its own immutable `created` with `@PrePersist`. Shape copied from `MessageHistory.java:43-70` (verified).

| Field | Java type | Column | Null | Default | Validation | Semantics · **forced by** |
|---|---|---|---|---|---|---|
| `id` | `Integer` | `id INTEGER NOT NULL` (PK) | no | shared sequence | — | PK. |
| `issue` | `PublicationIssue` `@ManyToOne @NotNull` | `issue_id INTEGER NOT NULL` FK | no | — | `@NotNull` | Owner. Rows are never updated or deleted while the issue exists. |
| `action` | `IssueAuditAction` | `action VARCHAR(255) NOT NULL` | no | — | `@NotNull` | What happened. The value set is the enumerations section's; it drives the Historik panel and the per-action shape of `detail`. |
| `created` | `Date` | `created DATETIME(6) NOT NULL` | no | `@PrePersist` | `@NotNull` | Event time. Declared on this entity, not inherited — `BaseEntity` has no timestamps and `VersionedEntity`'s `updated` would be mutable. |
| `actorKind` | `ActorKind` (`USER`, `SYSTEM`, `IMPORT`) | `actorKind enum('USER','SYSTEM','IMPORT') NOT NULL` | no | — | `@NotNull` | **This is the answer for a non-human action.** `series.releaseMode = AUTO_RELEASE` allows a publish with no human (a settled decision) and another forbids fabricating a user for the historical import — so `user` is NULL and this column says why, rendered as *"Systemet — automatisk publicering"* or *"Importeret fra tidligere system"*. Without it, a NULL `user` is indistinguishable from a bug. |
| `user` | `User` `@ManyToOne` | `user_id INTEGER` FK → `User(id)` | yes | current caller | non-null iff `actorKind = USER` | Nullable `@ManyToOne User`, as `MessageHistory.java:52-54`. |
| `actorLabel` | `String` | `actorLabel VARCHAR(255)` | yes | the user's display name at write time | — | The name **snapshotted at write time**, so the Historik panel survives a rename or a deleted account. Legacy snapshots the same value into the VO (`MessageHistory.toVo()` does `history.setUser(user.getName())`); an audit row is permanent, so it snapshots into the row. |
| `reason` | `String` | `reason VARCHAR(512)` | yes | copied from the action | required for `RETIRED`, `REACTIVATED`, `OVERRIDE_INCLUDED`, `OVERRIDE_EXCLUDED`, `OVERRIDE_REMOVED`, `AMENDED` | Copied rather than joined, so the panel needs no join and the reason outlives a deleted override row or a cleared `retiredReason`. |
| `archivePath` | `String` | `archivePath VARCHAR(512)` | yes | `null` | required for **`AMENDED`, `PUBLISHED` and `FILE_REPLACED_MANUALLY`** whenever that event overwrote a byte-carrying file, and the entry then also carries `archived: [{lang, fileName, size, hash, fileSource}]` in `detail` | **C3.** The archive **folder** for this event, relative to a new `publicationArchiveRootPath` setting: `<archiveRoot>/publications/<h1>/<h2>/<publicId>/<epochMillis>/`. <br>**`AMENDED` is not the only action that reaches the archive** (MF11). §4.4 step 9 archives during **publish** — the case of a language whose sticky flag was deliberately cleared so the upload is regenerated over — and the post-publish file replacement of `API-CONTRACT.md` §4.12 archives too (this document's §4 ends at §4.9; the file-replacement action is the contract's). An archive that no audit row can carry is an archive nobody can reach, so all three actions accept the column and the `archived[]` payload, and the archive stream resolves against **any** entry with a non-null `archivePath`, never `AMENDED` alone. <br>**It must sit outside `repoRoot`.** `RepositoryService` is class-level `@PermitAll` and `streamFile` serves anything resolvable under the root (verified: `RepositoryService.java:67-69`), so archiving *inside* it makes superseded official notices permanently reachable at guessable public URLs that cannot be recalled. Startup must assert `!archiveRoot.startsWith(repoRoot)` and refuse to boot otherwise — the `validateTempRepoPath` shape (`:481-489`). <br>**Two new settings, not one.** `publicationArchiveRootPath` has a sibling, **`publicationPreviewRootPath`**, which holds the ephemeral per-language preview renders at `<previewRoot>/publication-issues/<publicId>/preview/<generation>/<fileName>`. It takes the **same boot assertion** — `!previewRoot.startsWith(repoRoot)`, refuse to boot otherwise — for the sharper version of the same reason: an *unpublished* official notice set is precisely the thing that must not be world-readable, and `getTempRepoRoot()` is literally `getRepoRoot().resolve("temp")` (`:125-126`), so the temp folder is not an escape from a `@PermitAll` root. It also takes an **equivalent sweep** to the hourly temp purge, because previews must still expire. Both roots are covered by **X-6**, and neither is reachable through `GET /rest/repo/file/…`. The preview artefact itself is not modelled here: the only thing a preview persists is a `PREVIEW_GENERATED` audit entry (§7.11). <br>Retention is **indefinite** (C3): ~4–6 amend events a year, and the documents are not reproducible from data (the cover *årgang* is `year − 1884` computed at render time, cover strings come from a runtime-editable dictionary, annex titles come from the first member message, and order comes from the domain). |
| `detail` | `Object` (JSON) | `detail TEXT` + `@Convert(converter = JpaJsonAttributeConverter.class)` | yes | `null` | per-action shape, owned by the transitions section | Structured payload — the member delta on `AMENDED`, the per-language file list on `PUBLISHED`, the from/to pair on `INTERVAL_CHANGED`. The `Publication.printSettings` column idiom (`:158-166`); the untyped `JpaJsonAttributeConverter` is correct **here** (unlike for `criteria`, §3.1.5) because a swallowed parse failure degrades one history line rather than a membership definition. |

Index: `@Index(name = "issue_audit_issue_k", columnList = "issue_id,created")`.

**There is no legacy history to migrate** (ADM-013). The importer writes exactly **one** honest entry per issue — `action = IMPORTED`, `actorKind = IMPORT`, `detail = {legacyPublicationId, legacyStatus, legacyTagId?, cutoffSource, provenance, note}` — and fabricates nothing else.


## 4. Lifecycle — status transitions, and what each transition actually does

*Two lifecycles: the **series** (a definition, C7(3)) and the **issue** (a document, C7(1)(2)). They are independent: a retired series keeps serving its published issues, and a retired issue does not touch its series. Every transition below names its preconditions, its side effects in order, the audit entry it writes, and — where a plausible reading is wrong — what it explicitly does **not** do.*

### 4.1 The states, and which of them are persisted

| Lifecycle | Persisted states | Derived, never persisted |
|---|---|---|
| **Series** | `DRAFT` · `ACTIVE` · `RETIRED` | `dormant` (a boolean on the read VO) |
| **Issue** | `OPEN` · `PUBLISHED` · `RETIRED` | `UPCOMING` (Kommende) · `MISSING` (Mangler — no row exists at all) |

**Conflict resolved — `DORMANT` is not a fourth series status.** One fragment proposed persisting it so that "we deliberately stopped" and "nobody got round to it" are different facts. They already are: *"we deliberately stopped"* is `RETIRED`, which C7 requires to carry a reason and an audit entry, and *"nobody got round to it"* is dormancy, which is an observation about the calendar and cannot go stale if it is computed. A persisted `DORMANT` would be a fourth state with no reason, no transition guard and no verb, sitting between two states that already cover the ground. `dormant = status == ACTIVE && cadence != NONE && lastIssueAt older than N cadence periods`; it suppresses gap warnings (ADM-019, so "Akkumuleret EfS" — last issue 2018 — does not raise a permanent warning) and nothing else. The threshold `N` is open — **DM-Q4** (§9.4).

**`UPCOMING` and `MISSING` are computed, and gap detection is gated.** `UPCOMING` = a persisted `OPEN` issue whose `intervalFrom` is in the future, plus the single projected successor of the newest published issue when the series has a cadence and no successor row exists. `MISSING` = a synthesized pseudo-row with `issueId = null`. Gap detection — and therefore the `MISSING` row, the retro-create affordance and the gap warning — runs **only** when `series.cadence != NONE && series.status = ACTIVE && !dormant`. **Amended 2026-09-11:** the time relation is no longer part of the gate. It decides how coverage *between* two consecutive releases is read: off the intervals on a `PUBLISHED_IN_INTERVAL` series; on an `IN_FORCE_AT_CUTOFF` series (and a series with no relation) two consecutive releases cover the stretch between them by definition — those 531 issues overlap rather than tile (the 2026 and 2027 firing-areas issues share 31 of 32 members), so a "missing year" between two editions is a category error — and a period between them is `MISSING` only where a release was cut off in it and later retired. The forward pass (missing periods after the newest cut-off, the single `UPCOMING` period) runs for every cadenced series, so a weekly in-force publication that has stopped is late in exactly the way a weekly tiling one is. MODEL-VERIFICATION §5.18's assertion now reads: no `MISSING` row between two consecutive in-force editions, **asserted by a test**.

### 4.2 Series lifecycle — transition table

Enum `SeriesStatus`; legacy analogue is the template lifecycle at `PublicationService.java:492-506` (RECORDING never applied to templates either).

| # | From → To | Who | Preconditions | Side effects, in order | Audit | Explicitly does **not** |
|---|---|---|---|---|---|---|
| **S0** | — → `DRAFT` | ADMIN | — | Mint `seriesId`; seed one desc row per configured language; apply entity defaults. The series is invisible to the "＋ Ny udgave" picker. | `SERIES_CREATED` (see §4.8) | — |
| **S1** | `DRAFT` → `ACTIVE` | ADMIN | **All series validation invariants green** for the chosen `contentMode` (the V-rules in the definition-side section — content-mode legality, the language list, a name in every configured language, a reference format in every language when the reference option is on, no `status` criterion, no unknown `${…}` token, distinct file-name patterns across languages). Reason optional. | `status = ACTIVE`; `statusChangedAt/By`; the series becomes selectable for new issues; gap detection begins **iff** `timeRelation = PUBLISHED_IN_INTERVAL`. | `SERIES_ACTIVATED` | Does not create an issue. Does not backfill history. |
| **S2** | `ACTIVE` → `RETIRED` | ADMIN | **Reason required** (non-blank, 3..512). No guard on outstanding OPEN issues — see below. | `status = RETIRED`; `statusChangedAt/By/Reason`; series leaves the new-issue picker; excluded from gap warnings; `nextIssueCreation` is suppressed for any subsequent publish of an already-existing issue. | `SERIES_RETIRED` | **Does not retire, hide, unpublish or move a single issue.** Published issues stay on the public listing, their files stay at their links, and `?publication=` keeps resolving. Does not change any issue's `publicFrom`/`publicTo`. Does not delete anything. |
| **S3** | `RETIRED` → `ACTIVE` | ADMIN | **Reason required**; S1's invariants re-run (a series may have been retired *because* it was misconfigured). | as S1, plus `statusChangeReason`. | `SERIES_REACTIVATED` | Does not re-open, re-resolve or regenerate any issue. |
| **S4** | `DRAFT` \| `RETIRED` → *deleted* | ADMIN | **No `PublicationIssue` row references this series** (C7(2), literal). | Cascade-delete descs and the language list. | — (the row is gone) | — |
| — | `ACTIVE` \| `RETIRED` → `DRAFT` | — | **Illegal.** No legacy path exists either (`PublicationService.java:499-503` reaches ACTIVE from anything but ACTIVE, and DRAFT only from RECORDING). | | | |
| — | `ACTIVE` → *deleted* | — | **Illegal** — retire first. | | | |
| — | `ACTIVE` → *deleted with issues* | — | **Illegal** — 409, naming the issue count. | | | |

**Refusal wording is legacy parity:** an illegal transition returns `400` carrying the exact text `Invalid state transition <FROM> -> <TO>` (`PublicationService.java:509-511`, surfaced at `PublicationRestService.java:415-423`; ledger ISS-012). A missing reason on S2/S3 is a separate `400`. (The legacy publication's own `PUT /publications/update-status` answers that text with 409 since 2026-11-14; the series keeps its `400`.)

**Retiring a series with OPEN issues is permitted, and those issues stay publishable.** Gating publish on `series.status = ACTIVE` would strand in-flight work with no recovery verb — an admin retiring a weekly series on a Tuesday could never release the issue already assembled for Wednesday, and the only escapes would be delete (which loses the work) or reactivate-publish-retire (which writes two spurious lifecycle audit entries). So the issue publish precondition is `series.status ∈ {ACTIVE, RETIRED}` while issue *creation* requires `ACTIVE`. Retirement is about new issues, not about work already begun. The retire confirm dialog must name the outstanding OPEN issues.

**Confirm copy (C7 requires the dialog to state the real effect):** *"Serien kan ikke længere bruges til nye udgaver. Allerede publicerede udgaver bliver på den offentlige liste, og deres filer bliver liggende på deres links."* — quoted from `admin.publications.series.editor.retireEffect`, which is the copy that ships; keep the two in step.

**The series status is not in the public eligibility predicate.** Only the *issue* status gates public visibility. A dormant or retired series must keep serving its already-published issues, or every citation into that series' back catalogue goes dark — and the citations live inside stored message HTML with no endpoint that can clear them.

### 4.3 Issue lifecycle — transition table

Enum `IssueStatus`. Importer mapping (ledger ISS-005): legacy `DRAFT` + `RECORDING` → `OPEN`; `ACTIVE` → `PUBLISHED`; `INACTIVE` → `RETIRED`.

| # | From → To | Who | Preconditions | Side effects, in order | Audit | Explicitly does **not** |
|---|---|---|---|---|---|---|
| **T0** | — → `OPEN` | ADMIN, or SYSTEM at publish of the predecessor | `series.status = ACTIVE`. For `PUBLISHED_IN_INTERVAL`: `intervalFrom` = the `cutoffStampedAt` of the **PUBLISHED issue with the greatest `cutoffStampedAt` strictly below this issue's `intervalTo`** (chaining), or `series.firstIssueStartsAt` at the head of the chain. **Stamp-ordered and half-open, not "the previous issue"** — publish does not overwrite `intervalTo` with the stamp, so an early release (uge 27 stamped 11:42 against a nominal 12:00) leaves the predecessor carrying `intervalTo = 12:00` while the successor's mandated `intervalFrom` is `11:42`; a naive previous-issue lookup over closed intervals reads that as an 18-minute overlap and raises `ISSUE_INTERVAL_OVERLAP` **inside the publish transaction**, which then rolls back entirely. Both interval checks therefore run over the effective interval `[intervalFrom, effectiveCutoff)`. Without this neither the auto-created successor nor the retro-created gap issue is expressible. For `IN_FORCE_AT_CUTOFF`: `intervalFrom` **must be NULL** — no chaining, no tiling, no gap check. | Mint `publicId` (§4.7); derive `repoPath`; derive nominal `intervalTo` from the cadence and set `intervalToSource = NOMINAL`; set `intervalFromSource` = `STAMPED` when `intervalFrom` chains off the predecessor's stamped cut-off, `NOMINAL` at the head of the chain (`series.firstIssueStartsAt`), `RECOVERED` on a retro-creation prefilled by the importer, `MANUAL` when the admin typed it; derive `week`/`weekTo`/`year`/`edition`; create one `PublicationIssueDesc` per configured language with the suggested per-language name and file name; set `membershipProvenance = NO_MEMBERSHIP` when the content mode has none. | `CREATED` \| `CREATED_FROM_PREVIOUS_PUBLISH` \| `CREATED_RETROACTIVELY` | Does not touch the predecessor's visibility window. Does not resolve or store members. |
| **T1** | `OPEN` → `PUBLISHED` | ADMIN, or SYSTEM under `releaseMode = AUTO_RELEASE` | §4.4 preconditions | §4.4 ordered contract | `PUBLISHED` (+ `VISIBILITY_CAPPED` on the predecessor, + `VISIBILITY_CAPPED` **on this issue** when it is capped against an existing successor, + `CREATED_FROM_PREVIOUS_PUBLISH` on the successor) | Does not mint `publicId` (already minted at T0). Does not overwrite `intervalTo` with the stamp. |
| **T2** | `PUBLISHED` → `PUBLISHED` (**amend**) | ADMIN | `status = PUBLISHED` (**not** `RETIRED`); reason required | §4.5 ordered contract | `AMENDED` | §4.5's unchanged list |
| **T3** | `PUBLISHED` → `RETIRED` | ADMIN | `retiredReason` non-blank | `status = RETIRED`; `retiredAt`/`retiredBy`/`retiredReason` | `RETIRED` | §4.6's not-list |
| **T4** | `RETIRED` → `PUBLISHED` (**reactivate**) | ADMIN | reason non-blank | `status = PUBLISHED`; clear `retiredAt`/`retiredBy`/`retiredReason` (the reason survives on the `RETIRED` audit entry) | `REACTIVATED` | Does not re-resolve, re-freeze, regenerate, re-cap or re-derive anything. The stored public window is reused verbatim. |
| **T5** | `OPEN` → *deleted* | ADMIN | `status = OPEN` **and** `cutoffStampedAt IS NULL` **and** `publishedAt IS NULL` (§4.7) | Cascade-delete descs, overrides, audit entries and any member rows; **delete the issue's repo folder** (§4.7) | — | — |
| — | `PUBLISHED` \| `RETIRED` → *deleted* | — | **Illegal, permanently** (C7). A hard-deleted published issue takes its `publicId` with it and dangles every stored citation carrying it; there is no join table with which to find them. | | | |
| — | `RETIRED` → `PUBLISHED` (**amend**) | — | **Illegal** — reactivate first (T4), then amend (T2). | | | |
| — | `OPEN` → `RETIRED` | — | **Illegal** — an issue that never published has nothing to withdraw; delete it (T5). | | | |

**Conflict resolved — amending a `RETIRED` issue requires reactivation first.** C3 exists so that exactly one public version of a document exists at a stable URL. Amending something that is not on the listing produces a new official document nobody can see, and it makes "which version is public?" unanswerable during the window between amend and reactivate. Reactivate-then-amend costs one extra click and leaves two honest audit entries.

**T2 is a self-transition, not a status change.** No `INVALID_STATUS_TRANSITION` is raised for it; the guard is `ISSUE_NOT_PUBLISHED`.

### 4.4 What publish freezes — the ordered contract (T1)

#### Preconditions

**Every row below except P1 is a release-checklist item, whatever its severity.** The severity set is `OK` / `WARN` / `BLOCK`, exactly as on the wire, and the column states what a row reports when it is **not** satisfied — a satisfied row reports `OK`. `BLOCK` items appear on the checklist **and additionally refuse the call** (`400`/`409`); `WARN` items appear on the checklist and refuse nothing **on their own**, but must be acknowledged in the request when marked *(ack)*. The earlier wording — "`BLOCK` items refuse the call; `WARN` items appear on the release checklist" — read as though the two severities went to different places, which contradicts P4, P6, P6b and P12 naming `BLOCK`-severity checklist items; it is corrected here and must not be restored. The checklist is **server-authoritative** — legacy's rules live only client-side (ADM-023) and must not be reproduced that way.

**This list and the release rail in `API-CONTRACT.md` §3.10 are one rule stated twice, and they correspond row-for-row.** Two rows do not map one-to-one: **P1** is an authorization gate rather than a rail item and has no checklist code, and **P6** carries two rail rows (`CUTOFF_AFTER_PREVIOUS` and `CUTOFF_NOT_FUTURE`). The mapping is therefore 14 P-rows to 15 rail rows. Where a row names its identifiers it names **both** the checklist item and the refusal code — the two-namespace convention P6/P6b state. Rows P2, P3, P5, P7, P9 and P10 currently name neither or only one; their codes are authoritative in `API-CONTRACT.md` §3.10. Note in particular that P7's checklist item is `MEMBER_LIMIT` while its refusal code is `MEMBER_LIMIT_EXCEEDED` — a row that names only the latter will be read as the rail code and emit the wrong one.

| # | Severity | Check |
|---|---|---|
| P1 | BLOCK | Caller holds ADMIN, **or** `series.releaseMode = AUTO_RELEASE` and the scheduler is acting. |
| P2 | BLOCK | `issue.status = OPEN` and `series.status ∈ {ACTIVE, RETIRED}` (§4.2). |
| P3 | BLOCK | `intervalTo` present when `contentMode = GENERATED_FROM_QUERY`; `intervalFrom` present **iff** `timeRelation = PUBLISHED_IN_INTERVAL`. |
| P4 | BLOCK | **Bytes that must pre-exist, do.** The per-language file check (`FILE_PRESENT_PER_LANGUAGE`) applies **only** where nothing in this transaction will produce the file: `contentMode ∈ {UPLOADED_FILE, EXTERNAL_LINK}` — every configured language carries a non-blank `link` — plus, for a `GENERATED_FROM_QUERY` series, any **individual** language carrying `fileSourceSticky = true`, whose bytes must already be on disk because step 10 deliberately skips it. `contentMode = NONE` is exempt outright (FIL-001). **`409 MISSING_FILE_FOR_LANGUAGE` is raised here and only here** — it is the pre-flight code for those three cases, where the bytes must already exist and an admin can go and supply them; it is **never** raised for the non-sticky languages of a `GENERATED_FROM_QUERY` series. <br>**For `GENERATED_FROM_QUERY` the guarantee is P5 (`REPORT_CONFIGURED`) plus the post-render assertion in step 10**, which rolls the transaction back with **`500 REPORT_GENERATION_FAILED`** if any non-sticky language ends the render without a link. That is a 5xx and not a 4xx on purpose: the renderer ran and produced no file for a language, which is a system failure nobody can fix by filling in a field, not a precondition an admin left unmet. Blocking on `link` up-front would be blocking on a column that step 10 is the sole writer of, and previews are written to the preview root, never to the publication repo — so **every first publish of the weekly EfS would refuse**. This is legacy's activation guard (ISS-008, `PublicationService.java:499-503`) promoted to a visible checklist item and split at the one place legacy never had to: legacy had no transaction that produced the file. Mirrored on the wire in `API-CONTRACT.md` §3.10 and §7.3. |
| P5 | BLOCK | `reportId` present for `contentMode = GENERATED_FROM_QUERY` (FIL-017 — legacy refuses release without it). |
| P6 | BLOCK | The chosen cut-off is strictly greater than the predecessor's `cutoffStampedAt` — checklist item **`CUTOFF_AFTER_PREVIOUS`**, refusal code **`CUTOFF_BEFORE_PREVIOUS`** — and is not in the future — checklist item **`CUTOFF_NOT_FUTURE`**, refusal code **`CUTOFF_IN_FUTURE`** — **unless the request sets `allowFuture`** (`PublishIssueParam.allowFuture`, `API-CONTRACT.md` §4.3), which is the documented escape for stamping a cut-off ahead of now; without this clause the parameter is dead and every future cut-off is refused. The predecessor is the issue of the same series with the greatest `cutoffStampedAt` strictly below this one and **`status ∈ {PUBLISHED, RETIRED}`**, the same filter step 13 caps against. Both checks are decisive to the second: P&T uge 12/2024's member set changes 4 s after its stamp, NM-466-26 turns on 62 s. <br>**Two namespaces, not one** — the checklist item states the condition that must hold (`CUTOFF_AFTER_PREVIOUS`) and the error code names the violation (`CUTOFF_BEFORE_PREVIOUS`). `API-CONTRACT.md` §3.10 and §7.3 use exactly this convention; mixing the two names is how a client ends up matching on a code the server never emits. |
| P6b | BLOCK | Checklist item **`CUTOFF_BEFORE_SUCCESSOR`**, refusal code **`CUTOFF_AFTER_SUCCESSOR`** — where a successor exists (an issue of the same series with `cutoffStampedAt` strictly above this one and **`status ∈ {PUBLISHED, RETIRED}`**), the chosen cut-off must be strictly below it. The exact mirror of P6, and it is what makes retro-creating a gap week safe: without it a recovered issue can be stamped past the issue that already superseded it, and step 12's symmetric cap then has no bracket to close. <br>**The status filter is `PUBLISHED, RETIRED` — the same one steps 12 and 13 use, stated identically at all three sites.** Retire deliberately leaves the file and the public window in place (§4.6), so a retired issue still occupies its bracket; skipping it here would let a new issue stamp past a retired successor, leave that successor's predecessor uncapped, and make I-18 unsatisfiable. |
| P7 | BLOCK | Resolved member count ≤ 1000 → otherwise a loud `409 MEMBER_LIMIT_EXCEEDED` naming the issue and the count. `report.pdf` and `mail/send` truncate silently at `maxSize(1000)` today; for an official set, dropping members without a word is the exact wrongness this redesign removes. The in-process renderer (R1) imposes no cap of its own, so 1000 is a **product** threshold now, not a pipeline constraint. Largest live member set is 228, so this is headroom, not a limit — see **DM-Q12** (§9.4). |
| P8 | ~~WARN~~ **Removed** — the row, its code and the staleness computation are gone; publishing always renders, so nothing can go out stale, and there is no `regenerate` option to promote a preview instead. Kept for the record: ~~Preview freshness — checklist item **`PREVIEW_FRESH`**, refusal code **`PREVIEW_STALE`**, the same two-namespace convention P6/P6b state. **The rail item is `WARN`, not `BLOCK`, and that is a ruling:** a stale preview is recoverable — the admin regenerates — and `regenerate = true` is the normal path, which re-renders from the frozen snapshot and never consults the preview at all, so a `BLOCK` would refuse a publish that is about to re-render anyway. The refusal is **request-dependent** and lives with the request, not on the rail: publishing with `regenerate = false` against a stale preview promotes the stale bytes verbatim (step 10b) and is therefore refused with `409 PREVIEW_STALE`. `API-CONTRACT.md` §3.10 carries the same `WARN` for `PREVIEW_FRESH`. |
| P9 | WARN | Stale or redundant overrides outstanding (an EXCLUDE whose target the query no longer returns; an INCLUDE the query now returns anyway). |
| P10 | WARN *(ack)* | Any member with `frozenStatus ∈ {CANCELLED, EXPIRED}` **and** `frozenPublishDateTo >= cutoff`: *"N medlemmer er aflyst/udløbet — bekræft at de var i kraft ved skæringen."* This is C2's signed release-rail mirror, confirmed three times independently (11 weekly, 15 messages across 21 P&T issues, 10 across two annuals). An exclusions panel is structurally blind to this class. |
| P11 | WARN | Checklist item **`OVERLAPPING_ISSUE`** — `IN_FORCE_AT_CUTOFF` and ≥1 member shared with the previous issue — informational, so nobody reads the 31-of-32 firing-areas overlap as a bug. The same condition is the member-list resolution warning of that name in `API-CONTRACT.md` §3.9; it is on the release rail at §3.10 as well, so this list and that one correspond. |
| P12 | BLOCK | Checklist item **`REFERENCE_FORMAT_COMPLETE`**, refusal code **`REFERENCE_FORMAT_MISSING_LANGUAGE`** — every configured language carries a non-blank `messageReferenceFormat`, **only when `messagePublication <> NONE`**. The scoping is S-13's and is load-bearing: unscoped, it blocks the flagship weekly EfS, which carries no reference format in either language. <br>**This is a publish-time check and not only a series invariant, and that is the gap it closes.** S-13 is enforced at DRAFT→ACTIVE (S-17) and nowhere else, so a series activated *before* `messagePublication` was turned on carries no format in any language: publishable per this document and refused per the contract's rail. Re-checking at publish is what makes the two agree, and it is why `REFERENCE_FORMAT_MISSING_LANGUAGE` is raised by I16 as well as by the series writes (`API-CONTRACT.md` §7.3). |
| P13 | WARN | Checklist item **`INTERVAL_CHAINED`** — `intervalFrom` equals the predecessor's `cutoffStampedAt`, or an `INTERVAL_CHANGED` audit entry explains the deviation. This is **I-7** promoted to a rail item, and it is `WARN` rather than `BLOCK` because I-7 already admits the audited deviation: production carries six 14-day gaps, one 5-day and three 0-day between consecutive weekly issues. Reported only for `PUBLISHED_IN_INTERVAL`; under `IN_FORCE_AT_CUTOFF` there is no chain and the item is not emitted at all. |
| P14 | OK/WARN | Checklist item **`MEMBERS_RESOLVED`** — the resolver ran, and `memberCount` is reported on the item. Never `BLOCK` on its own: an empty resolution is a legitimate answer (§6.7) and a `NO_MEMBERSHIP` series has no resolution to report at all, so the *size* question belongs to P7 and the *acknowledgement* question to P10. It is on the rail because the member count is the number the admin actually releases against. |

**One further precondition, and it is request-dependent, which is why it is not a rail item.** **Superseded — the `regenerate` option was never shipped. The publish request has no `regenerate` property, publishing always renders, and `409 NO_PREVIEW` does not exist. Read the rest of this paragraph as history.** When the request carries `regenerate = false`, a preview file must exist **for every configured language with `fileSourceSticky = false`**, else **`409 NO_PREVIEW`**. The checklist is computed from the issue and not from the request, and with the default `regenerate = true` this condition does not exist at all — so it cannot be a row in the table above. It is not covered by P8 either: `PREVIEW_STALE` is about a preview that is *out of date*, never one that is *gone*, and preview files live under `publicationPreviewRootPath` with its own expiry sweep (§3.10, X-6). Without this check an issue whose preview has been swept publishes with **no document at all** — after which §4.5 makes `fileName` and `filePath` immutable forever. `API-CONTRACT.md` §4.3 states it in the same words.

**Under `AUTO_RELEASE` nobody can acknowledge a warning.** Rule: `BLOCK` items abort the automatic run — the issue stays `OPEN`, nothing is stamped, and an **`AUTO_RELEASE_ABORTED`** audit entry (§7.11, `actorKind = SYSTEM`, `detail = {blockingCodes:[…], attemptedCutoff}`) records the abort with the failing codes. That value exists in the enumeration precisely so this rule has something to write; without it the abort is mandated and unrecordable. `WARN` items do not abort; they are recorded on the `PUBLISHED` audit entry's **`unacknowledgedWarnings`** (step 15), which is materially different from a human having confirmed them and must render differently in the Historik panel. How an aborted automatic release reaches a human is open — **DM-Q11** (§9.4).

#### The transaction, in order

Everything below is one transaction. Any failure rolls the whole thing back — there is no half-published issue, and in particular `ARCHIVE_FAILED` must roll back (C3 is a one-way door; an overwrite without an archive is unrecoverable).

1. **Take a pessimistic lock on the series row.** A double-clicked publish must yield exactly one stamped cut-off. A repeat call against an already-`PUBLISHED` issue returns `409 ISSUE_ALREADY_PUBLISHED`, never a re-stamp.
2. **Stamp the cut-off.** `cutoffStampedAt` = NOW, or the admin-chosen instant (a settled decision makes both legitimate and both are stamped identically). `cutoffSource = STAMPED_AT_PUBLISH` or `STAMPED_MANUAL_TIME`; `cutoffReconstructed = false`. This happens **before** the resolve, because the resolve is *against* the cut-off — one fragment ordered the freeze first, which is not implementable.
3. **Resolve members in-process**, against `effectiveCutoff` (= `cutoffStampedAt`), through `MemberResolutionService`'s own JPA predicate — **never** through `MessageSearchRestService`, whose `adjustDateInterval(currentDomain.timeZone())` rounds the window to whole days (week 23 would become 27.05 00:00 → 03.06 23:59, pulling in the exact three messages that belong to week 24) and which silently rewrites `messageSeries`.
4. **Apply overrides:** `members = (Q \ activeExcludes) ∪ activeIncludes`, keyed on `uid`.
5. **Compute the order** (§6.6) and assign dense 0-based `sortIndex` over the post-override union.
6. **Write the `IssueMember` rows** with the frozen scalars (§6.5).
7. **Write the snapshot header** on the issue: `snapshotFrozenAt`, `memberCount`, `snapshotSortBy`, `snapshotSortOrder`, `snapshotDomainId`, `snapshotSeriesIds`, `criteriaSnapshot`, `snapshotTimeRelation`, `snapshotAliveAtCutoff`, `snapshotIntervalFrom`, and `membershipProvenance = EXACT` (or `NO_MEMBERSHIP` for the ~48 rows with no membership semantics).
8. **Freeze the overrides.** Every `IssueOverride` on this issue becomes immutable and records **`appliedAtPublish`** (§3.9) — `true` where the override actually changed the member set, `false` for an EXCLUDE whose target the query did not return anyway, so the published record does not claim a removal that never happened. **The column is `appliedAtPublish`, here and everywhere**; `staleAtFreeze` is its logical negation, a retired spelling, and must not appear in the entity, the DDL, the VOs or the wire. The EXCLUDE rows *are* the frozen record of exclusions — no second table is needed, and this is what lets the published-issue screen still render *"NM-229-26 … manuelt udeladt i uge 27"*.
9. **Archive, if and only if a byte-carrying file already sits at the target published path.** On a first publish there is normally nothing to archive: previews live under the preview root, never in the publication repo, and an UPLOADED language is skipped by regeneration because C6's flag is sticky. The one case that reaches here is a language whose sticky flag was deliberately cleared so that the upload is regenerated over — that is a publish-over in C3's sense and must archive first, to the same `<archiveRoot>/publications/<h1>/<h2>/<publicId>/<epochMillis>/` layout amend uses, recording `{lang, fileName, size, hash, fileSource}` per file. **Those records and the folder are carried on this transaction's own `PUBLISHED` audit entry** (step 15, §3.10, §7.11) — publish reaches this path, so `AMENDED` is not the only action that can record an archive.
10. **Write the files — in-process, from the ordered member list.** **Superseded: there is one way, not two.** The `regenerate` flag was never shipped, so **10a is what publish does, every time**; **10b is gone** along with `409 NO_PREVIEW` and `409 PREVIEW_STALE`, and a preview's bytes never become official. **10c still applies**, unchanged, after 10a. The two-way text is kept because 10a's contract is stated inside it. Historically: which one ran was decided by the request's `regenerate` flag, whose default is `true`. <br>**10a · `regenerate = true` (the default) — render.** Call the extracted render service **inside this transaction**, handing it the frozen member rows read back in `sortIndex` order (step 5's dense 0-based order **is** the order the renderer prints in), the resolved `snapshotSortBy`/`snapshotSortOrder`/`snapshotDomainId`, the series' `reportId` and print columns, and the effective `reportParams` (§3.6.6 — the issue map merged over the series map, issue keys winning). **No `MessageTag` of any kind is created, and there is no HTTP self-call**: the render does not go through `/rest/message-reports/report.pdf`, carries no admin ticket, and never re-runs a message search. Write each language's file to `<repoPath>/<fileName>`, **bypassing `RepositoryService.uploadFile`/`getUniqueFile`**, which appends `" 2"` on collision (`RepositoryService.java:417-432`) and would silently make the stable public URL stop pointing at the current document. Skip any language with `fileSourceSticky = true`. Set `fileSource`, `fileGeneratedAt`, `fileSize`, `fileHash`, `filePath`, `link`. The render **must read the snapshot being frozen in this same transaction**, or the official PDF and the official record disagree — the one case where getting it wrong is unrecoverable. <br>**10b · `regenerate = false` — promote the preview.** For each configured language with `fileSourceSticky = false`, **copy** that language's file from the newest preview generation under `publicationPreviewRootPath` (§3.10, X-6) to `<repoPath>/<fileName>`, bypassing `RepositoryService.uploadFile`/`getUniqueFile` exactly as 10a does; recompute `fileSize` and `fileHash` from the copied bytes; set `fileSource`, `fileGeneratedAt`, `filePath` and `link`. Sticky languages are skipped here too, and no render runs. **This is a real copy, not a no-op:** it is the only path by which preview bytes ever become official, and it is the whole of what `regenerate = false` means. Its two extra preconditions are the request-dependent `409 NO_PREVIEW` stated above the transaction and P8's `409 PREVIEW_STALE`. `API-CONTRACT.md` §4.3 step 10b states it in the same words. <br>**10c · Post-render assertion (the second half of P4), after whichever of 10a/10b ran.** Before the transaction commits, assert that every configured language with `fileSourceSticky = false` now carries a non-blank `link` and a file at `filePath`; if any does not, roll the whole transaction back with **`500 REPORT_GENERATION_FAILED`** naming the languages. This is where a `GENERATED_FROM_QUERY` issue's per-language file guarantee is enforced — **not** as a precondition, because step 10 is the sole writer of that column. **It is a 5xx and not a 4xx, and that is a ruling, not an oversight:** the renderer ran and produced no link for a language, which is a system failure and not a condition an admin can fix by filling in a field. `409 MISSING_FILE_FOR_LANGUAGE` survives **only** as P4's pre-flight check for `UPLOADED_FILE`, `EXTERNAL_LINK` and sticky languages, where the bytes must pre-exist and the admin can act. `API-CONTRACT.md` §4.3 step 10c and §7.3 say this in the same words.
11. **Flip the status:** `publishedAt` = now; `publishedBy` = caller (NULL under `AUTO_RELEASE`); `status = PUBLISHED`.
12. **Open the public window, and cap it against the successor.** `publicFrom = cutoffStampedAt`. Then let *S* be the issue of the same series with the **smallest** `cutoffStampedAt` strictly **above** this one, `status ∈ {PUBLISHED, RETIRED}`: if *S* exists, `publicTo = S.cutoffStampedAt − 1 ms` and a `VISIBILITY_CAPPED` audit entry is written **on this issue**; if no successor exists, `publicTo = null`. <br>**The capping is symmetric, and that is not decoration.** A retro-created gap issue publishes *below* issues that already released — recovering uge 41/2024 with `publicTo = null` would cap this week's live EfS through step 13 and then make the 2024 issue the site's current publication, because `overlaps(…, now, now)` finds an open-ended window. <br>**The gate is `publicWindowSource = DERIVED` *or* `publicTo IS NULL`** — the same belt-and-braces disjunct step 13 carries, and it is needed here for the same reason: a row mis-marked `MANUAL` would otherwise never self-cap, and I-18 would be violable by data this model calls legal. The skip is therefore only for a `MANUAL` row that is *already* capped, i.e. a genuinely open-ended one-off that owns its own window (§3.6.3, R8). <br>**The neighbour filter is `status ∈ {PUBLISHED, RETIRED}` here, in step 13 and in P6b — one filter, stated identically at all three sites** (and in `API-CONTRACT.md` §4.3 step 13 and §3.10's `CUTOFF_BEFORE_SUCCESSOR`). Retire deliberately leaves the file and the public window in place (§4.6), so a retired issue still occupies its bracket; skipping it would leave an uncapped predecessor and make I-18 unsatisfiable.
13. **Cap the predecessor.** Let *P* be the issue of the same series with the greatest `cutoffStampedAt` strictly below this one and `status ∈ {PUBLISHED, RETIRED}` — the same neighbour filter as step 12 and P6b: set `P.publicTo = this.cutoffStampedAt − 1 ms` and write a `VISIBILITY_CAPPED` audit entry **on P**. The gate is `P.publicWindowSource = DERIVED` **or** `P.publicTo IS NULL` — the second disjunct is R8's belt and braces, and it is what stops a `MANUAL`-marked imported predecessor from leaving two uncapped current issues on the public site at cutover.
14. **Create the successor** if `series.cadence != NONE`, `series.status = ACTIVE`, `nextIssueCreation = AUTO_ON_PUBLISH` and the series tiles — T0 with `intervalFrom = cutoffStampedAt` and `CREATED_FROM_PREVIOUS_PUBLISH`.
15. **Write the `PUBLISHED` audit entry**, and it must match §7.11's declared shape field-for-field: `{cutoffStampedAt, cutoffSource, memberCount, overrideCount, acknowledgedWarnings, unacknowledgedWarnings, files:[{lang, fileName, link, fileSource}], archived:[{lang, fileName, size, hash, fileSource}]}` **plus `archivePath`** on the entry itself. `unacknowledgedWarnings` carries the `WARN` items nobody confirmed — the `AUTO_RELEASE` case, which must render differently from a human having ticked them. `archived[]` and `archivePath` are present **only** when step 9 archived (MF11): publish reaches the archive on the cleared-sticky-flag path, so `AMENDED` cannot be the only action that can record one, and the archive stream resolves against **any** audit entry with a non-null `archivePath`.

**Conflict resolved — capping runs for *every* series, both time relations.** One fragment gated step 13 on `PUBLISHED_IN_INTERVAL`. That is wrong, and it confuses two different windows: *membership* tiling is gated on `timeRelation`, the *public visibility* window is not. Legacy tiled the visibility window even for the overlapping annuals — Skydeområder 2017 ed1 ran 01.01 → 28.06 09:59 and ed2 28.06 10:00 → 31.12 (ISS-015), while the two editions share almost all their members. Two issues may share 31 of 32 members and still have disjoint public windows. The real gate is `publicWindowSource = DERIVED` **or an uncapped `publicTo`**, which protects the genuinely open-ended one-offs — the four citation-only publications current since 01.01.2016 (ISS-016) — without letting an imported cadenced annual escape the chain (R8; §3.6.3).

**Resolved — the render vehicle (R1, ratified 2026-08-21).** The render is **extracted out of the HTTP layer** into an in-process service whose input is an ordered `List<MessageVo>` (equivalently, the ordered member rows plus the resolved print settings). **The earlier claim that "the FmReport pipeline is unchanged" is formally retired** — the evidence says it was never true. `PublicationRestService.generatePublicationReport` renders by opening `new URL(app.getBaseUri() + "/rest/message-reports/report.pdf?…&ticket=…")` (`:449-460`): a separate request, a separate transaction, a separate connection. A `MessageTag` created inside the publish transaction — temporary or not — is uncommitted and therefore **invisible** to that request, so `MessageSearchRestService` would find no tag, take its restriction branch (`:144-170`) and write an official PDF containing the domain's entire published corpus truncated at 1000 messages, with no error. Extracting the render removes four hazards in one change: the HTTP self-call, the admin ticket, the silent 1000-message truncation and `adjustDateInterval`'s day-snapping — and it is the **only** arrangement under which the frozen `sortIndex` is reachable on the print/export path at all, because a query-parameter pipeline has no way to accept a caller-supplied order. C8 is otherwise unchanged: no tag is minted for an issue, the three `?publication=` prerequisites stand, and mirroring remains rejected.

#### What publish freezes — the explicit list

The cut-off instant and its provenance · the member set, keyed on `uid` · per member: `publishDateFrom`, `publishDateTo`, `status`, `type`, `mainType` and `shortId` **as at that instant** · the print order (`sortIndex` + the resolved `sortBy`/`sortOrder`/domain) — **and that frozen `sortIndex` order is literally the ordered list handed to the renderer**, at publish (step 10), at amend (§4.5 step 4) and at every later reprint or export · the effective criteria document, the resolved message-series set, the time relation and the alive flag · the override set and each override's staleness · the rendered per-language files and their hashes.

#### What publish does **not** do

It does not mint `publicId` (minted at T0). It does not overwrite `intervalTo` — the nominal cut-off stays on the row and `effectiveCutoff = cutoffStampedAt ?? intervalTo` is what every consumer reads. It does not modify any other issue's membership, ever. **It does not create a message tag of any kind — not durable, not temporary.** It does not open an HTTP connection back to itself, and it does not mint an admin ticket. It does not change the series.

### 4.5 What amend does — the ordered contract (T2, C3)

**Amend never re-resolves the query.** Three fragments disagreed; two are right and the argument is decisive. Re-running the query reads `type`, `publishDateFrom` and `publishDateTo`, all of which are mutable and unversioned — a live re-resolve of a published P&T issue silently drops the twelve messages that are `PERMANENT_NOTICE` today and were P or T at release, across 26 issues. Rewriting an official member list from data nobody authorised is exactly what the snapshot exists to prevent.

**But amend may apply an explicit, audited curation delta**, and that is how the third fragment's `AMENDED` audit payload (`{added:[uid], removed:[uid]}`) is ever non-empty. The distinction is the whole of it: *re-running the query* is forbidden; *editing the frozen snapshot by an admin-chosen uid selection carrying a reason* is permitted and audited. C3 exists to correct a wrong official document, and "the member list was wrong" is one of the two ways it can be wrong.

1. Lock the series row; require `status = PUBLISHED` and a non-blank reason.
2. **Apply the curation delta, if any** — an explicit uid selection, gated on `PUBLICATION_CURATE`, applied to the frozen snapshot. Overrides become writable again inside this transaction only.
3. **Archive first.** For every language whose file is about to be overwritten, copy the outgoing file to `<publicationArchiveRootPath>/publications/<h1>/<h2>/<publicId>/<epochMillis>/<fileName>`, recording `{lang, fileName, size, hash, fileSource}` per file and the folder on the `AMENDED` entry's `archivePath`. The archive root **must** sit outside the `@PermitAll` repository root — `RepositoryService` serves anything resolvable under it with no authentication, so archiving inside would make superseded official notices permanently reachable at guessable public URLs that cannot be recalled. Failure here rolls the transaction back. Retention: **indefinite** (C3, ~4–6 amend events a year, and the documents are not reproducible from data — the cover årgang is computed at render time as year − 1884, cover strings come from a runtime-editable dictionary, annex titles come from the first member message, and the order comes from the domain).
4. **Regenerate only the languages with `fileSourceSticky = false`**, writing to the **same** `filePath` and `fileName`. The regeneration is the same in-process render call as §4.4 step 10 — the frozen member rows read back in `sortIndex` order, no message tag, no HTTP self-call, no re-resolve. A language flagged `UPLOADED` is left alone — C6's flag is sticky precisely so it survives an amend.
5. If the delta changed membership: update `snapshotFrozenAt` and `memberCount`, and re-densify `sortIndex` per §6.6.
6. Write the `AMENDED` audit entry with `archivePath`, the member delta, the regenerated/kept language lists and the archived-file records.

**Unchanged throughout, and this is the load-bearing list:** `publicId` · `cutoffStampedAt` and `cutoffSource` · `intervalFrom`/`intervalTo` · **`publicFrom`/`publicTo`** · `filePath`, `fileName` and every `link` · `status` (stays `PUBLISHED`) · `criteriaSnapshot`, `snapshotSortBy`, `snapshotSortOrder`, `snapshotDomainId`, `snapshotSeriesIds`, `snapshotIntervalFrom` · `membershipProvenance` (a curated snapshot was already not equal to any query result; overrides do not change how the snapshot relates to the query) · the predecessor's cap. **`publicTo` is in this list on purpose**, and `API-CONTRACT.md` §4.4 carries the same list: amend never caps anything, so the only writer of `publicTo` is a *later* issue's publish transaction (§4.4 steps 12–13). §8.1's immutability carve-out is for that write and for nothing else.

`fileName` and `filePath` are **immutable once `publishedAt` is set**. A later edit of the series' file-name pattern must not move a published file: that would change the public URL and dangle every citation, which is the thing C3 exists to prevent. An `UPLOADED` replacement is written to the same path and name regardless of the uploaded file's own name — C3's stable URL beats FIL-024's `Content-Disposition` fallback.

### 4.6 What retire does — and does not (T3), and reactivate (T4)

**Does:** sets `status = RETIRED` with `retiredAt`/`retiredBy`/`retiredReason`; the issue leaves `/public/v1/publications` (the eligibility predicate is `status = PUBLISHED`); `/public/v1/publication/{publicId}` returns the same indistinguishable `404` it returns for a missing id (legacy parity — INACTIVE returns null there too).

**Does not:**

- delete, move or rename any file — the PDF stays at its public URL, which is why the confirm copy must read *"Udgaven fjernes fra publikationslisten. Filen forbliver tilgængelig på sit link."* **There is no verb in legacy or in this redesign that makes a released file unreachable.** That must be stated, not discovered.
- clear or alter the frozen snapshot;
- break `?publication=` — a RETIRED issue stays resolvable to its frozen members on both call sites, so citations do not dangle (C7 requires this explicitly);
- drop out of the comma-separated hydration lookup, which ignores status so a chip for a retired issue still renders its title;
- change `publicFrom`, `publicTo`, `cutoffStampedAt`, the interval, `publicId`, `membershipProvenance` or the member rows;
- **un-cap the predecessor.**

**Consequence, specified rather than discovered: retiring the newest published issue leaves the series with no current issue on the public site.** The predecessor's `publicTo` stays capped at the retired issue's cut-off. This is legacy parity — capping is by date and deactivation is by status, and the 2025-01-09 four-issue retire/republish batch left exactly this hole. The alternative (un-capping the predecessor) was considered and rejected: it silently republishes a document that was deliberately superseded, and it would fire on every retire including the ones where the hole is intended. The retire dialog must say so.

**Amended 2026-09-08 (Rasmus) — one thing retire now DOES: it uncovers the period.** Gap detection ignores `RETIRED` issues when deciding coverage, so the withdrawn period is synthesised as a `MISSING` row with the retro-create offer while the retired row stays in the list unchanged; and `assertNoOverlap` (`ISSUE_INTERVAL_OVERLAP`) tests `PUBLISHED` neighbours only. This is the correction path for an issue that cannot be amended — every imported one — withdraw, then create, curate and publish a new issue for the same period; the archive then holds one Withdrawn and one Published row for that period. Everything in the "does not" list above still holds.

**Reactivate (T4)** restores `status = PUBLISHED`, clears the retire triple, and reuses the stored public window verbatim. It does **not** re-resolve, re-freeze, regenerate, re-cap or re-derive anything. Legacy parity: `ACTIVE` is reachable from anything but `ACTIVE` (`PublicationService.java:499-503`; ledger ISS-009).

### 4.7 `publicId`, and the delete guards

**`publicId` is minted at issue CREATE (T0), not at publish** — resolving a three-way disagreement between fragments 2–1, and in favour of the two that agree with the downstream requirements. OPTIONAL §3.4 requires `publication=<openIssueId>` to resolve for an OPEN issue (live preview, the C9 editor tier, the sysadmin report test-run), and the id must be immutable for life so a citation written at any point never dangles. Minting at publish would either make an OPEN issue unaddressable or require a second, temporary id space.

That changes the shape of C7's delete guard. C7's signed text reads *"an issue that never published (no stamped cut-off, no publicId)"*; the buildable equivalent is:

```
DELETE is legal  ⟺  status = OPEN  AND  cutoffStampedAt IS NULL  AND  publishedAt IS NULL
```

This is the same set of issues C7 meant — the citation picker only ever offers PUBLISHED issues (REF-022, ADM-023), so a never-published issue is *provably* never cited — but it amends the literal wording, and that amendment needs ratification (**DM-Q3**, §9.4).

**Deleting a never-published issue deletes its repo folder.** The only thing that folder can contain at that point is a file uploaded for a language of an issue that never published; previews live under `publicationPreviewRootPath`, outside the repository root entirely (X-6), and are swept by that root's own purge. Leaving it behind (legacy parity, FIL-035) means orphaned bytes reachable at a guessable URL under the `@PermitAll` repository root — the same hazard C3's archive rule exists to prevent. Deleting is the deviation from legacy, and it is the right one.

**Series delete** requires zero issue rows (S4), returns `409` naming the issue count otherwise, and is never available on an `ACTIVE` series.

**Amended 2026-09-07 (Rasmus's ruling on deleting an issue — supersedes the C7 guard above).** The guard becomes:

```
DELETE is legal  ⟺  status = OPEN
                 OR  (status = RETIRED  AND  no message cites the issue)
PUBLISHED is never deletable — retire first.
```

Two corrections to the paragraph above, both found while building it. (1) The claim that a never-published issue is *provably* never cited does not hold, though not because of the picker, which offers PUBLISHED issues only: an OPEN issue is cited through an import, where an imported issue keeps its legacy id and sits at OPEN until first published, so the messages that cited the legacy row cite it (`PublicationResolver`'s resolution falls through to the legacy row for that window, and `systemVo` stays ungated so such a citation can still be opened and updated). The ruling first kept OPEN deletable without a citation check; the API-CONTRACT amendment widened the check to OPEN on 2026-09-09, and `IssueDeleteService.delete` refuses a cited OPEN issue with `ISSUE_CITED`. (2) "Cites" is the stored marker `publication="<publicId>"` in `MessageDesc.publication` / `internalPublication` (one spelling, `PublicationUtils.citationMarker`), looked up in reverse by `PublicationResolver.citingMessages`; membership rows are not citations and go with the issue. Soft-deleted messages count as citing (conservative; open decision). **What a delete removes** (`IssueDeleteService`): the issue, its descs, `IssueMember`, `IssueOverride`, the issue's own `IssueAuditEntry` rows, and the `repoPath` folder; it nulls a successor edition's `supersedes` pointer (the FK has no `ON DELETE SET NULL`) and touches nothing else of any other issue. **What it leaves:** one series-level `IssueAuditEntry` — `issue = null`, `series` set, `action = DELETED`, actor, optional reason, `detail { publicId, statusAtDeletion, names, intervalFrom, intervalTo, publishedAt, memberCount, legacyPublicationId? }` — written *before* the rows are removed; `issue_id` was already nullable (V1) because the `SERIES_*` actions write such rows, so no migration. Read back through `GET /publication-series/{seriesId}/history`. The strip renders the deleted period as **missing** afterwards.

### 4.8 Which audit entry each transition writes

Every transition writes exactly one `IssueAuditEntry` (values in §7.11), append-only, with `actorKind` distinguishing a human from the scheduler from the importer. Two entries are written **on a row other than the one being acted on**, and both must be, or a change to public visibility happens with no trace: `VISIBILITY_CAPPED` on the *predecessor* when a successor publishes, and `SUPERSEDED_BY` on the *old* issue when `supersedes` is set on a new one. **`VISIBILITY_CAPPED` is also written on the publishing issue itself** whenever step 12 caps it against a successor that already exists (R5) — one entry per capped row, on the row whose window changed, so a retro-created gap issue's bounded window is as traceable as the predecessor's. `CREATED_FROM_PREVIOUS_PUBLISH` is written on the *successor*, which is why there is no separate `NEXT_ISSUE_CREATED` action — one event, one entry, on the row it describes.

The **series** lifecycle currently records itself in three columns (`statusChangedAt`/`By`/`Reason`) rather than in audit rows. Whether to generalise `IssueAuditEntry` to cover both is open, and it must be settled **before** the schema is applied (**DM-Q2**, §9.4).

### 4.9 Open questions — lifecycle

*A reading aid, not a numbering. The identifiers are the register's (§9.4); the retired spelling `§4.9 Q1…Q6` is this list's positions 1–6, in order.*

1. **DM-Q3 — Ratify the delete-guard wording.** `publicId` is minted at create, so C7's literal *"no publicId"* becomes `status = OPEN AND cutoffStampedAt IS NULL AND publishedAt IS NULL`. Same issues, different words; it amends a signed decision, so it needs a sign-off rather than a silent substitution.
2. **DM-Q4 — The dormancy threshold.** `dormant` is derived, but *"no issue for N cadence periods"* needs an N. It decides only whether a gap warning appears; it is a product judgement, not a data fact. Proposed: 3 cadence periods.
3. **DM-Q2 — Series audit shape.** Three columns on the series, or generalise `IssueAuditEntry` to carry series lifecycle events too (`SERIES_CREATED` / `SERIES_ACTIVATED` / `SERIES_RETIRED` / `SERIES_REACTIVATED` / `SERIES_SETTINGS_CHANGED`)? If generalised, the three columns must be dropped **before** the schema is applied — retrofitting is exactly the hand-rolled migration the physical-delivery question is about.
4. **DM-Q10 — Pre-amend snapshot retention.** This spec archives the *file* (C3's rationale is that the PDF is not reproducible from data, whereas the member list is) and records the member *delta* on the `AMENDED` entry. Is the delta enough, or should the full outgoing snapshot be retained in a history table? One extra table; cheap now, expensive later.
5. **DM-Q11 — How an aborted automatic release reaches a human.** Under `AUTO_RELEASE`, a `BLOCK` precondition aborts the run and writes an audit entry — but nothing in the design surfaces that to the admin who expected an issue on Wednesday. Mailing list, dashboard warning, or "the gap row is the notification"?
6. **DM-Q12 — `MEMBER_LIMIT_EXCEEDED` threshold, now a free product choice.** The 1000 was inherited from `report.pdf`'s `maxSize(1000)`, and R1 takes that pipeline off the publish path entirely: the in-process renderer imposes no cap of its own, so nothing technical pins the number. Largest live member set is 228. Pick a threshold on product grounds and state it; do **not** justify it by the report pipeline any more.

---

## 6. The frozen member snapshot

### 6.1 Why it exists, and what it is not

`IssueMember` is its own table, **not** a `@ManyToMany` from the issue to `Message`. The plan contradicted itself on whether a snapshot exists at all (`IssueOverride` "is the *only* extra membership state" vs publish "freezes member list snapshot"); the contradiction is settled in favour of the snapshot, by measurement:

- **`type` is mutable and unversioned.** Twelve messages are `PERMANENT_NOTICE` today and sit inside 26 already-published P&T issues; all twelve were P or T at their issue's release. NM-300-24 sits in 10 consecutive recorded tags and drops out of the 11th while still PUBLISHED and in force, because the query reads *today's* type.
- **`publishDateFrom` and `publishDateTo` are mutable too.** `updateStatus` overwrites `publishDateFrom` only when it is null or in the future, so an editor-set past date survives publication (NM-1116-22, published 2023-02-20, carries a 2022 date); `publishDateTo` is stamped on cancel/expire only when null or already past, which is what produces the "date-alive, status-dead" class.
- **The print order's inputs are mutable.** `Domain.messageSortOrder` is an editable setting, `Message.areaSortOrder` is a mutable float writable via `PUT /rest/messages/change-area-sort-order`, and the area tree moves.

A live re-resolve of a published issue therefore silently rewrites official history — which contradicts the settled rule that "later EXPIRED/CANCELLED members stay in their historical issue".

**What the snapshot is not:** it is not a copy of the messages. It freezes only the facts the predicate and the sort read. If a message's *text* is later corrected, the snapshot does not change and the issue's PDF — archived under C3 — is the record of what was printed. That division is deliberate: data reproduces membership, the archived file reproduces the document.

### 6.2 Live versus frozen — keyed off issue status, with no request parameter

| Issue status | Member source |
|---|---|
| `OPEN` (incl. computed `UPCOMING`) | **LIVE re-resolve** — an admin previewing this week wants what is in it right now |
| `PUBLISHED` | **FROZEN snapshot** — "reprint EfS uge 33" must return what was released |
| `RETIRED` | **FROZEN snapshot** — files and `?publication=` membership stay resolvable so citations do not dangle |
| anonymous / public | **FROZEN, published only** |

**No `publicationSnapshot=frozen|live` hatch ships.** It would create deep links whose meaning changes over time — the exact non-reproducibility the redesign exists to remove. It stays purely additive if it is ever genuinely needed.

An unresolvable or not-yet-servable issue id **fails loudly** (`400`), never widens. Live defect today: a bogus id returns the entire published corpus (244 messages), as do all 27 tagless ACTIVE publications, because an empty tag set falls through to "everything published".

**The member-set designation predicate, defined (R7).** `designatesMemberSet()` is true **iff at least one `publication=` id resolved to an issue the caller is entitled to see** — and it is **independent of member count**. A resolution that yields zero members still designates the set; it means "this issue has no members", not "no issue was named". Getting this wrong reinstates live defect 1 inside the model built to remove it: a membership-free publication would fall through to the widening branch and return the whole published corpus. Two consequences bind the implementation:

- The predicate builder must emit an **explicit empty disjunction** for a designated-but-empty set — never `in(emptyCollection)`, which `CriteriaHelper.in()` turns into `cb.disjunction()` by a side path that is indistinguishable from "no predicate was added" at the call site (RI-6, RI-15).
- The ~48 `NO_MEMBERSHIP` publications are exactly this case in production: `?publication=<a NO_MEMBERSHIP issue>` must return **0 messages, not 244** — asserted by a fixture (§8.5), not left to inspection.

### 6.3 Snapshot header — the fields that live on `PublicationIssue`

One snapshot per issue, re-frozen in place by an amend that carries a curation delta; a separate snapshot entity would buy a 1:1 join for nothing.

**The snapshot header is specified once, in §3.6.5, and that table is authoritative** (R4). This section previously restated it with different nullability on `snapshotAliveAtCutoff` and `snapshotSeriesIds`; the duplicate is deleted rather than reconciled, because two field tables that must agree are one field table too many. The header consists of exactly these columns on `PublicationIssue`:

`membershipProvenance` · `membershipProvenanceNote` · `snapshotFrozenAt` · `memberCount` · `snapshotTimeRelation` · `snapshotAliveAtCutoff` · `snapshotIntervalFrom` · `snapshotSeriesIds` · `snapshotSourceSeriesId` · `snapshotSourceIssueIds` · `snapshotDomainId` · `snapshotSortBy` · `snapshotSortOrder` · `criteriaSnapshot`

— types, nullability, defaults and validation per **§3.6.5**; written in one pass by §4.4 step 7; echoed on the wire as the resolved envelope of §5.6. Two of them are worth repeating in prose because they are read on hot paths rather than only at freeze: `snapshotSeriesIds` is the **resolved** message-series set after domain expansion, denormalized out of `criteriaSnapshot` because C8's restriction-lift path reads it per request and because a later edit to a domain's series list must not retroactively rewrite what a 2018 issue meant; and `snapshotDomainId` is the series' domain as at freeze, read per request both by that same widening and by "sort by the *issue's series* domain, never the caller's".

`cutoffStampedAt` is the snapshot's other input, but it lives on the issue as a lifecycle field (§4.4 step 2) and is not duplicated here either.

### 6.4 `IssueMember` — the member row

**The member row is specified once, in §3.8, and that table is authoritative** (R4, applied here exactly as it was applied to §6.3's snapshot header). This section previously restated it as a second field table, and the two disagreed on three columns at once: the duplicate declared a `reasonCode` column §3.8 does not have, it named the override's frozen flag `staleAtFreeze` rather than `appliedAtPublish`, and it carried its own DB-type and nullability spellings. **The duplicate is deleted rather than reconciled** — two field tables that must agree are one field table too many — and every statement it carried that §3.8 did not already make has been folded into §3.8 first. The row consists of exactly these columns on `IssueMember`:

`id` · `issue` · `messageUid` · `message` · `sortIndex` · `frozenShortId` · `frozenStatus` · `frozenType` · `frozenMainType` · `frozenPublishDateFrom` · `frozenPublishDateTo` · `source` · `sourceIssuePublicId` · `reasonNote` · `override`

— Java types, DB types, nullability, defaults and validation per **§3.8**, together with its unique constraint `(issue_id, messageUid)` and its three indexes (`issue_member_issue_sort_k`, `issue_member_uid_k`, `issue_member_source_issue_k`), written in one pass by §4.4 step 6. Ids come from the shared legacy sequence (`@GeneratedValue(AUTO)` + `hibernate.id.db_structure_naming_strategy=legacy`), **not** per-table `AUTO_INCREMENT` — §1.2.

**The three columns that are not here** are recorded in §3.8 and settled: **no `reasonCode`** — the why-line kind is the wire field `MemberReasonVo.kind`, derived from `source` plus the header's `snapshotTimeRelation`, and `MemberReasonKind` is already owned by the resolver as *(wire)* in §7.13; **no `reasonKind`**, which maps 1:1 onto `source`; and **no `overrideApplied`**, because an EXCLUDE produces no member row and an INCLUDE that produced one was by definition applied, so the flag would be constant-true here — what it was reaching for lives on `IssueOverride` as **`appliedAtPublish`** (§3.9), never as `staleAtFreeze`.

Two properties are worth repeating in prose because they are read far from the field table. `messageUid` is **the** semantic key and `frozenShortId` is never one (§6.5); and `issue_member_uid_k` is what serves C9's editor-tier question *"which issue(s) does this message belong to"*.

**Excluded messages are not rows in this table.** They live as `IssueOverride(kind = EXCLUDE)`, frozen immutable at publish. That is the complete record of exclusions; no second table exists.

### 6.5 uid keying, and what is captured at freeze

**Every member list, override, diff and re-render keys on `uid`, never `shortId`.** `dma-fa` holds 357 messages under only 331 distinct shortIds; a shortId-keyed comparison silently reported **0-diff on issues that in fact differ by eight members**. `shortId` is unique only within a message series, and `frozenShortId` exists purely so a human can read the diff.

Captured per member at the freeze instant: `uid`, `shortId`, `status`, `type`, `mainType`, `publishDateFrom`, `publishDateTo`, `sortIndex`, and the membership `source` (plus the override FK, the import note, or — on a compilation — the `publicId` of the source issue that printed it). Captured on the header: the effective criteria, the resolved series set, the time relation, the alive flag, the interval lower bound, the cut-off, the sort key/order and the sort domain; on a compilation, the source series and the source issues instead of the criteria and the alive flag.

**Not captured, deliberately:** the message title or body (read live; the archived PDF is the record of what was printed); the message's area or category assignments (see §6.6 on why the area sort keys are not frozen); the current `type`/`status`, which are read live only to compute drift warnings.

**Drift is surfaced, never healed.** A frozen member whose current `type` or `status` differs from the frozen value drives a `TYPE_DRIFT` warning on the admin view. The member list itself never changes. **Amended 2026-09-11:** the frozen `status` of every member is `PUBLISHED` by construction — nothing withdrawn before the cut-off is a member — and the importer writes exactly that rather than the message's status on import day, so an imported issue and a native one read alike: the status shown on a published issue's row is the live one, and a row whose status or type moved since the release says so and stays a member, because what changed happened after the issue was decided. `frozenPublishDateTo` stays part of the record and is not compared.

### 6.6 The `sortIndex` rule

**Why the order is data and not presentation.** A printed report's order comes from `params.sortBy`/`sortOrder`, resolved from the domain's `messageSortOrder` (`MessageSearchParams.checkSortByDomain`, `:187-209`: explicit request > `domain.messageSortOrder` > `AREA`/`ASC`). It decides more than sequence — `report.pdf` derives `areaHeadings` from `params.sortByArea()`, so the sort order decides **whether the PDF has area headings at all**. And every input is mutable: the domain setting is editable, `areaSortOrder` is a writable float, and the area tree moves. Without a stored order, the same issue reprints differently.

**Resolution order (promoted to every surface).** When an issue designates the set, the order is decided by the **issue's series' domain**, never the caller's thread-local domain: the series' explicit `messageSortBy`/`messageSortOrder` if set, else `seriesDomain.messageSortOrder`, else `AREA`/`ASC`. Today the surfaces disagree — the admin path uses the caller's current domain, the public API uses the first named publication's domain, and release uses whatever domain the releasing admin happened to be in — so the same issue can print in two different orders.

**The rule:**

1. `sortIndex` is **dense, 0-based, unique within the issue**, assigned in the resolved order over the **post-override union** (`members`, not the raw query result). Overrides are ordered like any other member; they are **not** appended at the end.
2. The order is produced by reproducing `searchPagedMessageIds`' ORDER BY tuples **exactly** — including the `AREA` tuple `(treeSortOrder, areaSortOrder, year, number, id)`, the `CASE WHEN treeSortOrder IS NULL THEN 999999` that sorts area-less general messages last (`MessageService.java:1240-1246, 1305-1325`), and the `id` tie-breaker on every branch. Without the tie-breaker the order is non-deterministic and a reprint differs. The other tuples are the search's too (`MessageSearchService`): `ID` is `(year, number, publishDateFrom, id)`, not the row id alone, `PUBLISH_DATE` is `(publishDateFrom, publishDateTo, id)`, `EVENT_DATE` is `(eventDateFrom, eventDateTo, id)` and `FOLLOW_UP_DATE` is `(followUpDate, id)`; a descending sort reverses the whole tuple, the `id` included, as the search's `desc(id)` does (`IssueOrdering`, since 2026-11-19).
3. The header stores the **resolved** `snapshotSortBy`, `snapshotSortOrder` and `snapshotDomainId` that produced it.
4. **Replay** reads member rows ordered by `sortIndex`, then fetches by uid. No new machinery is needed: `MessageService.getMessages` already re-sorts to a caller-supplied id order via `ids.indexOf` (`:986-987`).
5. **On amend, `sortIndex` is not recomputed for surviving members.** Surviving members keep their relative order; a member added by a curation delta is placed by a live evaluation of the *recorded* sort key; a removed member's index is closed up; indices are then re-densified to `0..n−1`. Re-running the whole sort would reorder an unchanged document whenever the area tree had moved since publication — which is the exact failure the stored order exists to prevent. The `AMENDED` audit entry records the resulting insertion positions.
6. The area sort keys themselves (`treeSortOrder`, `areaSortOrder`) are **not** frozen per member. The stable-index rule already makes the order reproducible, and freezing one message's position in a tree duplicates a structure that only means anything whole.

> ⚠ **Reserve these columns in the first schema pass even if every optional phase is dropped.** `sortIndex` on the member row plus `snapshotSortBy`/`snapshotSortOrder`/`snapshotDomainId` on the header are schema, and the `MaDaMe` baseline had no migration tooling, so adding a column later meant hand-rolled DDL against a live production schema. *(Flyway was adopted afterwards — §1.3, §9.3 — so the cost is no longer prohibitive; the reservation stands because it is already made and costs nothing.)* This is the one part of the optional-phase document a reviewer must not drop.

### 6.7 Provenance, and the issues no query can reproduce

`membershipProvenance` is what makes a snapshot honest about its own relationship to the query. It is what any comparison of a frozen member set against the criteria has to read first.

| Value | What the snapshot holds | What a comparison against the query means |
|---|---|---|
| `EXACT` | Natively published by this engine, **or** an imported issue whose recorded legacy tag the canonical query reproduced member-for-member in both directions | Comparable; any difference is a real defect |
| `EXPLAINED_DIFF` | The recorded legacy tag membership. The query differs, but every difference is attributed to a named class (type mutated after release; cancelled-with-future-`publishDateTo`; alive-at-cut-off drops in the sticky era; `publishDateFrom IS NULL`; back-dated `publishDateFrom`; legacy tag staleness) | Not comparable as a pass/fail — the named class is the explanation, and a diff here says nothing about the engine |
| `UNION_SNAPSHOT` | The recorded tag verbatim. **No instant reproduces it** — Skydeområder 2018 (71 members), 2020-ed1 (66), 2022-ed1 (41), EfS A 2020-ed1 (56), each holding two full annual cohorts after a ±1 ms sweep of the whole series history | Not comparable at all; the snapshot is the only record |
| `NO_MEMBERSHIP` | Zero rows, and that is correct | n/a |
| `COMPILED` *(2026-09-13)* | The union of the frozen member rows of another series' published issues, each row naming the issue that printed it (RI-16), and the header naming the source series and every source issue (**I-20**) | **Not comparable, and exempt rather than pending.** There is no query to compare against, and the rows it would be compared with are themselves already-published frozen snapshots. The exemption is a branch on the regime, not a side effect of `criteria` being null |

`NO_MEMBERSHIP` covers the ~48 publications with no membership semantics of any kind: 18 uploaded Akkumuleret EfS PDFs, 4 external Dansk Fyrliste links, 6 one-offs including two `type=NONE`, 10 tagless annexes whose member lists can never be recovered, and the NCAGS/Isbilag family where each tag holds one hand-picked message that no predicate can select. A `NO_MEMBERSHIP` issue with zero member rows is correct, and the member endpoint answers `200` with an empty list, never a `404`. Equally, `?publication=<that id>` **designates the member set and returns zero messages** — the designation predicate is "an id resolved to an issue the caller may see", independent of member count (RI-15 / R7); a zero-member resolution must never fall through to the widening branch.

Consequence for acceptance, reached independently by three sweeps: **three of the five provenances cannot be compared against the query at all, so a sweep over the archive is a diagnostic and never a pass/fail gate.**

Files flagged `fileSource = UPLOADED` are **excluded from every diff**, because a manually replaced file was never generated and the "reproducible from the member list" invariant does not hold for it.

### 6.8 Open questions — snapshot

*A reading aid, not a numbering. The identifiers are the register's (§9.4); the retired spelling `§6.8 Q1…Q3` is this list's positions 1–3, in order.*

1. **DM-Q10 — Are the amend delta and the archived file enough history?** §6.3 re-freezes the snapshot in place; the outgoing member list survives only as `{added, removed}` on the `AMENDED` audit entry. A full outgoing-snapshot history table is one extra table, cheap now and expensive later. (**DM-Q10** in the register, §9.4 — recorded on both sides because it is decided once.)
2. **DM-Q13 — May a published issue ever be deliberately re-resolved?** §6.2 and §4.5 say no. If an audited "re-resolve and write a new snapshot version" action is wanted — to repair a bad import, say — it needs an owner, a role, an audit action and a rule for what happens to the already-published PDF. Recommendation: not in Phase 1.
3. **DM-Q14 — Deleted members.** `Status.DELETED` is not in `isPublic()`, so a member later moved to DELETED silently vanishes from a published issue under a live re-resolve — but the frozen snapshot still holds its row. Serve it from the snapshot (the record is the record), hide it (legacy parity), or fail loudly? The frozen-snapshot model must decide explicitly; parity plus an admin-side drift warning is the recommendation, but it is a choice.

---

## 7. Enumerations

*Every enumeration the model introduces or reuses, with all values and their meaning. Enums owned by the criteria/resolver and REST sections are listed here too, values only, so the sets cannot drift between documents.*

**Persistence convention for every persisted enum below:** `@NotNull @Enumerated(EnumType.STRING)` with **no explicit length**, which Hibernate maps to `varchar(255)` — matching `Publication.java:99-122`. One fragment proposed tight explicit lengths (`varchar(16)`, `varchar(24)`); rejected, because a hand-applied DDL that disagrees with what Hibernate would generate fails the first time anyone runs `generation=update` against it, and the repo has no migration tooling with which to reconcile the two. **Style:** javadoc per constant, as `vo/PublicationStatus.java:22-50`.

### 7.1 `SeriesStatus` — the series lifecycle (C7(3))

| Value | Meaning |
|---|---|
| `DRAFT` | Created but not yet usable. Invisible to the new-issue picker; not gap-checked. The initial state. |
| `ACTIVE` | Fully valid and in use. New issues may be created; gap detection runs (if the time relation tiles). |
| `RETIRED` | Deliberately taken out of service, with a reason and an audit entry. No new issues; excluded from gap warnings; **its published issues stay public**. |

`dormant` is **not** a value — it is derived (§4.1). Legacy parked four `DONT USE …` templates as INACTIVE purely to keep them out of the picker (SER-003/SER-004); `RETIRED` is that capability, made explicit.

### 7.2 `ContentMode` — what an issue's content *is*

Bijective with legacy `PublicationType` (`niord-model/…/publication/PublicationType.java:22-35`), which is what makes the importer and the legacy export adapter total.

| Value | Legacy `PublicationType` | Live rows | Meaning |
|---|---|---:|---|
| `GENERATED_FROM_QUERY` | `MESSAGE_REPORT` | 1044 | Issues carry a generated per-language report over a resolved member set. The only mode with membership semantics. |
| `UPLOADED_FILE` | `REPOSITORY` | 24 | Issues carry per-language uploaded files. No criteria, no interval, no members. |
| `EXTERNAL_LINK` | `LINK` | 7 | Issues carry per-language absolute URLs to another system. No file, no members. |
| `NONE` | `NONE` | 2 | No file and no link — the publication exists purely to be cited. Exempt from the "every language must carry a link" publish precondition (FIL-001, ISS-008). |

**Conflict resolved, and shipped.** MODEL-VERIFICATION §5.1 proposed `{GENERATED_FROM_QUERY, UPLOADED_FILE, EXTERNAL_LINK, ONE_OFF}`. That set conflates two independent axes and loses a preserved capability: `ONE_OFF` answers *"what kind of thing is this?"*, while `NONE` answers *"what is the content?"* and is live in production. `ONE_OFF` is therefore **not** a `ContentMode` value — which is what shipped. What it *is* is a value of `PublicationSeries.kind`, a separate stored column (§3.1.4, `V8__series_kind.sql`); the earlier reading of a one-off as "a `cadence = NONE` series holding one issue" is **superseded**, because a real series with no calendar has `cadence = NONE` too. A one-off is still an ordinary series with an issue under it, which keeps `PublicationIssue.series` NOT NULL, keeps C7's "delete a series with no issues" coherent, and gives it somewhere to keep its category, domain and reference format. **DM-Q5 is closed on both halves.**

Importer note: a `LINK` row with no link in any language is normalised to `NONE`, not treated as corrupt (FIL-005).

### 7.3 `SeriesCadence`

| Value | Legacy `PeriodicalType` | Meaning |
|---|---|---|
| `NONE` | *(null)* | One-off. Exactly one issue; `nextIssueCreation` forced to `MANUAL`; no tiling, no gap detection, no missing-issue check ever runs. |
| `DAILY` | `DAILY` | Nominal daily release. **Zero production instances** — and unnameable with the legacy token set (there is no `${day}`). |
| `WEEKLY` | `WEEKLY` | 1001 production issues. |
| `MONTHLY` | `MONTHLY` | Zero production instances. |
| `YEARLY` | `YEARLY` | The annual series (firing areas, EfS A). |

Maps 1:1 to `PeriodicalType` (`publication/vo/PeriodicalType.java:22-27`) with `NONE ↔ null` for the legacy adapter. Cadence is always a **suggestion**, never enforced: production shows six 14-day gaps, one 5-day and three 0-day gaps between consecutive weekly issues, so a deviation is never a validation failure.

### 7.4 `NumberingScheme`

| Value | Meaning | Evidence |
|---|---|---|
| `ISO_WEEK_YEAR` | Issue derives `week` (+ `weekTo`) and `year` from the **ISO week and ISO week-year** of the cut-off | 1001 weekly issues |
| `YEAR_EDITION` | Issue derives `year` + an `edition` that resets each year | `firing-areas-2017-v1`/`-v2`, `nm-almanac-2020-v1`/`-v2` |
| `MONTH_YEAR` | Month + year | **Speculative — zero production instances** |
| `EDITION_SEQUENCE` | A never-resetting edition counter | **Speculative — zero production instances** |
| `NONE` | No derived numbering; the issue is named freely | one-offs |

**Conflict resolved** in favour of these five over a proposed `{WEEK_YEAR, EDITION, MONTH_YEAR, NONE}`: `ISO_WEEK_YEAR` names the ISO-week/ISO-week-year pairing that legacy got wrong (it pairs a correct ISO week with the *calendar* year, so a 31.12.2025 cut-off yields "EfS uge 1 - **2025**"), and splitting `YEAR_EDITION` from `EDITION_SEQUENCE` is what makes a reset policy derivable instead of needing its own column.

### 7.5 `TimeRelation` (C2, MODEL-VERIFICATION §5.2)

| Value | Population | Lower bound | Upper bound | Tiles? | Gap detection / chaining |
|---|---:|---|---|---|---|
| `PUBLISHED_IN_INTERVAL` | 500 issues (weekly EfS, both eras) | previous issue's stamped cut-off, **exclusive** | this cut-off, **inclusive** | yes | **runs** |
| `IN_FORCE_AT_CUTOFF` | **531 issues** — P&T 502, Skydeområder 16, EfS A 13 | **none at all** | this cut-off, inclusive | **no — issues overlap** | **must not run** |
| `COMPILED_FROM_SOURCE` *(2026-09-13)* | the accumulated annual, converted in place at import | previous issue's stamped cut-off, **exclusive** | this cut-off, **inclusive** | yes | **runs** |

**The third value (2026-09-13).** `COMPILED_FROM_SOURCE` shapes an issue exactly as `PUBLISHED_IN_INTERVAL` does and selects its content completely differently: the members are the union of the frozen member rows of `sourceSeries`' PUBLISHED issues whose effective cut-off falls in the period (**RI-16**), ordered by source cut-off and the source's own recorded order (**RI-17**), each row naming its source issue. No predicate is evaluated, no criteria document is read and `MembershipPredicate` is never reached. The enum carries exactly two derived answers, and no more: `tiles()` — true for `PUBLISHED_IN_INTERVAL` and `COMPILED_FROM_SOURCE`, which is what every call site meaning *tiling* asks — and `compiled()`. A third predicate for "has a lower bound" would be the same set as `tiles()` under a second name.

By publication count `IN_FORCE_AT_CUTOFF` is the larger shape. The gating is a hard requirement, not a nicety: under a chained interval the 2027 firing-areas issue would contain **one** message instead of thirty-two.

### 7.6 `ReleaseMode` and `NextIssueCreation`

`ReleaseMode` — `MANUAL_GATE` (default; a human publishes) · `AUTO_RELEASE` (the scheduler publishes; `publishedBy` is NULL and `actorKind = SYSTEM`). Per-series, **editable only in series settings**, shown read-only everywhere else (a settled decision). One fragment named the field `automation`; `releaseMode` is used here because *automation* names a capability rather than a value axis, and both fragments agree on the two values.

`NextIssueCreation` — `AUTO_ON_PUBLISH` (default; publishing creates the successor starting exactly at the stamped cut-off) · `MANUAL`. Forced to `MANUAL` when `cadence = NONE`.

### 7.7 `IssueStatus`, and the computed statuses

**Persisted** (`IssueStatus`): `OPEN` (Åben — collecting; membership is live) · `PUBLISHED` (Publiceret — stamped, frozen, public) · `RETIRED` (Tilbagetrukket — withdrawn from the listing; files and citations still resolve).

**Computed** (`ComputedIssueStatus`, never persisted, wire-only): `UPCOMING` (Kommende) · `OPEN` (Åben) · `PUBLISHED` (Publiceret) · `RETIRED` (Tilbagetrukket) · `MISSING` (Mangler — a detected gap with **no row at all**).

**Wire-only** (`IssueRowKind`, the pseudo-row discriminator on the issue list): `MISSING` · `UPCOMING`. Absent/null on real rows.

**Legacy `PublicationStatus`** (`vo/PublicationStatus.java:22-50`) is retired from the model but stays a **valid input token** through the whole transition, because the shipped frontend sends `status=ACTIVE&status=RECORDING` until it is re-pointed: `DRAFT` → `OPEN`, `RECORDING` → `OPEN`, `ACTIVE` → `PUBLISHED`, `INACTIVE` → `RETIRED`. The mapping is **accepting, not emitting** — `PublicationVo` carries no status field at all, so nothing in the public payload changes.

### 7.8 `CutoffSource`, and `cutoffReconstructed`

| Value | Meaning | Sets `cutoffReconstructed` |
|---|---|---|
| `STAMPED_AT_PUBLISH` | This system stamped NOW at publish | `false` |
| `STAMPED_MANUAL_TIME` | An admin chose the instant at publish | `false` |
| `RECOVERED_FROM_UPDATED` | The importer took `publication.updated` — step 1 of the recovery cascade | `true` |
| `RECOVERED_FROM_NEXT_TAG` | Overridden by the next issue's `messageTag.created` where the two differ by more than 5 minutes (exact on 452 of 496 chained pairs) | `true` |
| `RECOVERED_FROM_PDF_COVER` | Printed cover date extracted from the production PDF — the only route for 27 annual issues | `true` |
| `MANUAL_ENTRY` | The cascade failed; an admin typed it | `true` |

**Conflict resolved** in favour of six values over the four named in MODEL-VERIFICATION §5.6 (`STAMPED`, `RECOVERED_FROM_NEXT_TAG`, `RECOVERED_FROM_PDF_COVER`, `MANUAL`). Both additions are forced: `STAMPED_MANUAL_TIME` because the publish instant is selectable, and `RECOVERED_FROM_UPDATED` because §3.2 proves `updated` is a last-write stamp and **not** a release moment — collapsing it into `STAMPED` would launder a last-write stamp into a release stamp for ~493 weekly and 208 P&T issues.

`cutoffReconstructed` is redundant with `cutoffSource` by construction and is stored anyway: it is the indexed predicate behind the issue-list badge and behind any sweep that has to separate recovered cut-offs from stamped ones, and it survives the addition of future enum values.

**Amended 2026-09-09 — the shipped vocabulary, and the two values this table never listed.** The constants are `CutoffRecovery`'s (strings, as the column is a string): `STAMPED_AT_PUBLISH` · `STAMPED_MANUAL_TIME` · `RECOVERED_FROM_UPDATED` · `RECOVERED_FROM_NEXT_TAG` · **`RECOVERED_FROM_COVER`** (this table's `RECOVERED_FROM_PDF_COVER` was never the shipped name) · **`MANUAL`** (likewise for `MANUAL_ENTRY`) · **`NOMINAL_CLOSE`** · **`PUBLIC_WINDOW`** · **`NOT_RELEASED`**. The last three are not recovery stages and are the reason the "cascade" wording above under-describes what the importer does:

- **`NOMINAL_CLOSE`** — no stamp was believable and the period has a nominal close, so the close is the cut-off. For a tiling issue that is the honest answer, not a loss: the content period ended at its nominal instant whatever time the release action ran, and the membership rule reads the close.
- **`PUBLIC_WINDOW`** — the period *is* the public window, so the cut-off is one of its two ends. Three shapes reach it: an accumulated annual at the window's **close**; an in-force annual at the day the window **opens**, as the fallback under R-xxii/R-xxiii when no credible release stamp beats it; and a **cadence-less** publication at the window's **open**, where there is no nominal close to fall back to at all.
- **`NOT_RELEASED`** — not a recovery failure. An OPEN issue is unstamped because nobody has published it, and it takes its stamp from the publish action like any other; recording it as `MANUAL` would put a "needs a human" badge on the one row behaving normally.

**Every candidate is bounds-checked, not merely present** (`CutoffRecovery.Bounds`): a stamp is believed only inside `[window open − 3 h, nominal close + 24 h]` **and strictly before `replacedAt`** — the moment a successor took over. A write made once the successor is public is this row's *withdrawal*, not its release. See the 2026-09-09 amendment to `CUTOFF-MODEL.md` §2 for the cadence-less row this bound repaired.

### 7.9 `IntervalBoundSource` — `intervalFromSource` / `intervalToSource`

| Value | Meaning |
|---|---|
| `STAMPED` | Taken from a neighbouring issue's stamped cut-off (chaining) |
| `NOMINAL` | Derived from the series cadence — the "(nominel)" marker in the issue list |
| `RECOVERED` | Reconstructed by the importer |
| `MANUAL` | Typed by an admin |

**Persisted as two real columns on `PublicationIssue` — `intervalFromSource` and `intervalToSource` (§3.6.2), `VARCHAR(255)`, each non-null iff its own bound is non-null (I-17).** They are **not derived** (R4): a derivation from the neighbour chain can distinguish `STAMPED` from `NOMINAL` at best, and can never produce `RECOVERED` or the `MANUAL` value this enumeration declares — "an admin typed this bound" is not recoverable from the bound.

Drives the "stamped vs nominal" marker the approved UX requires on every interval in the issue list and on the retro-create dialog's prefill.

### 7.10 `MembershipProvenance`, `MemberSource`, `OverrideKind`, `FileSource`, `ActorKind`

- **`MembershipProvenance`** — `EXACT` · `EXPLAINED_DIFF` · `UNION_SNAPSHOT` · `NO_MEMBERSHIP` · `IMPORTED` · **`COMPILED`** (2026-09-13: derived from another series' published issues rather than from a query, so nothing ever re-runs a query against it; anything that compares a snapshot with its query exempts the regime explicitly). Full semantics in §6.7.
- **`MemberSource`** — `CRITERIA` (matched the query) · `OVERRIDE_INCLUDE` (added by an audited override) · `IMPORTED` (present because a tag in the previous system said so; no query matched) · **`COMPILED`** (2026-09-13: a source issue printed it, and `sourceIssuePublicId` says which). Excluded messages are never rows.
- **`OverrideKind`** — `INCLUDE` · `EXCLUDE`.
- **`FileSource`** (C6) — `GENERATED` (produced by the report pipeline) · `UPLOADED` (replaced by hand). **Per issue-language, not a content type per issue**, and paired with a sticky flag that survives amend. It does **not** change the series' `contentMode` or the emitted legacy `type`.
- **`ActorKind`** — `USER` (a human; `user` FK non-null) · `SYSTEM` (the automatic release gate; `user` NULL, rendered *"Systemet — automatisk publicering"*) · `IMPORT` (the historical importer; `user` NULL). Required because one settled decision permits a publish with no human and another forbids fabricating one for the import.

### 7.11 `IssueAuditAction` — the audit vocabulary

> **The vocabulary is the backend's `AuditAction` enum. The table below is a reading aid and is not exhaustive.**
> It was written in August and is not rewritten as the enum grows, so do not count it and do not treat an absence
> here as an absence there — actions added since include `CREATED_NEW_EDITION`, `NUMBERING_CHANGED`,
> `REPORT_CHANGED`, `FILE_NAME_CHANGED`, `EDITION_CHANGED` and `VISIBILITY_WINDOW_CHANGED`. Earlier revisions of
> this section quoted a running total (twenty, then 26, 29, 31); every one of those is now wrong, which is why
> the count is gone. Read the enum.
>
> Two of the later additions are worth stating because the distinction is easy to lose. **`EDITION_CHANGED`** is
> its own action rather than a `NAME_CHANGED` because the edition is what tells two documents of the same period
> apart, is printed on the cover, and expands into the file-name pattern. **`VISIBILITY_WINDOW_CHANGED`** is a
> public window end set or cleared by hand, distinct from **`VISIBILITY_CAPPED`**, which is a neighbour's publish
> closing a window as a side effect of taking over — and the only action that can put an expired publication back
> on the public site.
>
> The override actions are **`OVERRIDE_INCLUDED`**, **`OVERRIDE_EXCLUDED`** and **`OVERRIDE_REMOVED`**. Anywhere
> this text says `OVERRIDE_ADDED`, read those three.

| Value | Typical `actorKind` | Written on | `detail` payload |
|---|---|---|---|
| `CREATED` | USER | this issue | `{intervalFrom, intervalTo, chainedFromIssueId?}` |
| `CREATED_FROM_PREVIOUS_PUBLISH` | **SYSTEM** | the **successor** | `{previousIssueId, cutoffStampedAt}` |
| `CREATED_RETROACTIVELY` | USER | this issue | `{intervalFrom, intervalTo, prevIssueId, nextIssueId}` |
| `IMPORTED` | **IMPORT** | this issue | `{legacyPublicationId, legacyStatus, legacyTagId?, cutoffSource, provenance, note}` — the single honest "Importeret fra tidligere system" entry |
| `INTERVAL_CHANGED` | USER | this issue | `{from:{…}, to:{…}}`; re-derives `week`/`weekTo`/`year` and the per-language name suggestion where not overridden |
| `NAME_CHANGED` | USER | this issue | `{lang, from, to}` — per language (C5) |
| `CRITERIA_OVERRIDDEN` | USER | this issue | `{from, to}` |
| `OVERRIDE_ADDED` *(shipped as `OVERRIDE_INCLUDED` / `OVERRIDE_EXCLUDED`)* | USER | this issue | `{kind, messageUids[], shortIds[], count}` + `reason` — **plural on purpose** (MF5): the curation endpoints take a 1..500-uid **selection** and write exactly **one** entry per call, so a singular shape could not record what one call did without writing 500 rows, which §4.8's "one entry per transition" forbids |
| `OVERRIDE_REMOVED` | USER | this issue | `{kind, messageUids[], shortIds[], count}` + `reason` — same plural shape, written by both *exclude* and *clear* |
| `PREVIEW_GENERATED` | USER | this issue | `{langs:[…], memberCount}` — survives the ephemeral preview file |
| `FILE_UPLOADED` | USER | this issue | `{lang, fileName, size, hash}` — the **first** manual file for a language, which replaced nothing |
| `FILE_REPLACED_MANUALLY` | USER | this issue | `{lang, fileName, size, hash, replacedSource, archived:[{lang, fileName, size, hash, fileSource}]}` + `archivePath` — **C6's named action**; sets the persistent badge, and carries the archive because it overwrote existing bytes |
| `FILE_CLEARED` | USER | this issue | `{lang, fileName}` — a per-language file removed from an **OPEN** issue, which also clears `fileSourceSticky` so the next publish regenerates that language. A published issue's document is never cleared, only replaced |
| `PUBLISHED` | USER or **SYSTEM** | this issue | `{cutoffStampedAt, cutoffSource, memberCount, overrideCount, acknowledgedWarnings, unacknowledgedWarnings, files:[{lang, fileName, link, fileSource}], archived:[{lang, fileName, size, hash, fileSource}]}` + `archivePath` — the last two present **only** when §4.4 step 9 archived (the deliberately-cleared-sticky-flag path); `unacknowledgedWarnings` is what distinguishes an `AUTO_RELEASE` run from a human who confirmed the same warnings |
| `AUTO_RELEASE_ABORTED` | **SYSTEM** | this issue | `{blockingCodes:[…], attemptedCutoff}` — an automatic run stopped by a `BLOCK` precondition. The issue stays `OPEN`, nothing is stamped, and this is the only trace; without a value in the enumeration the abort §4.4 mandates has nothing to write. How it reaches a human is **DM-Q11** (§9.4) |
| `AMENDED` | USER | this issue | `{added:[uid], removed:[uid], regenerated:[lang], kept:[lang], archived:[{lang,fileName,size,hash,fileSource}], orderChanged:bool}` + `archivePath` + `reason` |
| `RETIRED` | USER | this issue | `{}` + `reason` (required) |
| `REACTIVATED` | USER | this issue | `{}` + `reason` (required) |
| `SUPERSEDED_BY` | USER | the **old** issue | `{bySucceedingIssueId, edition}` |
| `VISIBILITY_CAPPED` | **SYSTEM** | the **capped row** — the predecessor (step 13), **or this issue itself** when step 12 caps it against an existing successor (R5) | `{publicTo, cappedByIssueId}` — without it a change to public visibility happens with no trace |

**Two merge decisions.** A proposed `NEXT_ISSUE_CREATED` is not a separate value: it is the same event as `CREATED_FROM_PREVIOUS_PUBLISH`, written once, on the successor row it describes. A proposed generic `UPDATED` is rejected: a Historik panel cannot render "something changed", and the specific actions above already cover every mutation an admin can make.

Series lifecycle actions (`SERIES_CREATED` / `SERIES_ACTIVATED` / `SERIES_RETIRED` / `SERIES_REACTIVATED` / `SERIES_SETTINGS_CHANGED`) exist only if the audit entity is generalised to cover series — open, **DM-Q2** (§9.4).

### 7.12 Public-adapter enums

- **`PublicWindowSource`** — `DERIVED` (the publish transaction owns `publicFrom`/`publicTo` and does the symmetric capping of §4.4 steps 12–13) · `MANUAL` (an admin owns them; the chain does not rewrite them). **R8:** the importer writes `DERIVED` for **any series with a cadence**, imported annuals included — their windows are derived historical facts, produced by exactly the chain this column hands to the publish transaction. `MANUAL` is reserved for genuinely **open-ended one-offs**: the four citation-only publications current since 01.01.2016 and the external links no successor will supersede. Marking a cadenced imported series `MANUAL` is what leaves the newest imported issue uncapped and puts **two current issues** on the public site at the first native publish. Regardless of this column, a predecessor whose `publicTo IS NULL` is capped anyway (I-14), and the import check asserts I-18 before the go-live window proceeds.
- **`MessagePublication`** — `NONE` · `INTERNAL` · `EXTERNAL`. Reused **verbatim** from `publication/vo/MessagePublication.java:22-34`: whether and how an issue may be cited from a message, and which message field the citation is written to. Entity default `NONE` = un-citable until an admin opts in. Immutable once any issue of the series has published — flipping the channel afterwards makes every existing citation unfindable, because it lives in the other field.
- **`PublicationType`** (legacy, emitted) — `NONE` · `LINK` · `REPOSITORY` · `MESSAGE_REPORT`. Part of a published XSD, and **derived, never stored** (ruling X2): the public adapter maps it from `contentMode` by the total pure bijection of §7.2 (`GENERATED_FROM_QUERY↔MESSAGE_REPORT`, `UPLOADED_FILE↔REPOSITORY`, `EXTERNAL_LINK↔LINK`, `NONE↔NONE`). **There is no `legacyType` column** — §2.5 keeps it in the deliberately-absent table, because a bijection cannot drift while a second stored column can. A per-language `fileSource = UPLOADED` does **not** change the emitted value; C6 is explicit that it is not a content type per issue.

### 7.13 Enums owned elsewhere — values listed so the sets cannot drift

| Enum | Owner | Values |
|---|---|---|
| `CriterionOperator` | criteria schema | `IN` · `NOT_IN` *(schema-legal, Phase-1 rejected)* |
| criteria node `kind` | criteria schema | `messageSeries` · `messageMainType` · `messageType` · `domain` · `area` · `category` · `chart` |
| `MemberReasonKind` (wire) | resolver | `CRITERIA_MATCH` · `OVERRIDE_INCLUDE` · `SNAPSHOT_ONLY` |
| `OmissionReasonKind` (wire) | resolver | `OVERRIDE_EXCLUDE` · `CRITERIA_MISS` |
| `CriteriaMissCode` | resolver | `BEFORE_INTERVAL` · `AFTER_CUTOFF` · `NO_PUBLISH_DATE` · `NOT_ALIVE_AT_CUTOFF` · `STATUS_NOT_PUBLIC` · `CRITERION_MISMATCH` |
| `ResolutionWarningCode` | resolver | `CANCELLED_BUT_DATE_ALIVE` · `TYPE_DRIFT` · `NULL_PUBLISH_FROM_DROPPED` · `STALE_OVERRIDE` · `OVERLAPPING_ISSUE` · `LIMIT_EXCEEDED` · **`SOURCE_ISSUES_INCOMPLETE`** *(2026-09-13; the second **acknowledgeable** code, beside `CANCELLED_BUT_DATE_ALIVE` — a compilation whose period holds a source period that is still open or has no published issue)* |
| membership reason (wire) | resolver | `IN_INTERVAL` · `IN_FORCE_AT_CUTOFF` · `MANUAL_INCLUDE` · **`FROM_SOURCE_ISSUE`** *(2026-09-13: its source issue printed it)* · the miss reasons `STATUS_NOT_PUBLIC` · `CRITERIA_MISMATCH` · `NO_PUBLISH_DATE` · `BEFORE_INTERVAL` · `AFTER_CUTOFF` · `NOT_ALIVE_AT_CUTOFF` |
| `ChecklistSeverity` | REST contract | `OK` · `WARN` · `BLOCK` |
| `MemberListSource` (wire) | REST contract | `LIVE` · `FROZEN` · `PROBE` |
| `SortOrder` | reused, `PagedSearchParamsVo.java:32` | `ASC` · `DESC` |
| `PageSize` | reused, all eight legacy values | `A3` · `A4` · `B4` · `B5` · `A5` · `LETTER` · `LEGAL` · `LEDGER` |
| `PageOrientation` | reused | `PORTRAIT` · `LANDSCAPE` |
| `Status` (message) | `niord-model`, unchanged | `DRAFT` · `VERIFIED` · `PUBLISHED` · `CANCELLED` · `EXPIRED` · `DELETED`; the membership conjunct is `isPublic()` = `{PUBLISHED, CANCELLED, EXPIRED}`, **derived** via `Arrays.stream(Status.values()).filter(Status::isPublic)`, never hardcoded, so a future status change propagates |
| `Type` / `MainType` (message) | `niord-model`, unchanged | eight `Type` constants; `NW` · `NM` |

**Conflict resolved on the warning codes.** Two fragments carried different names for the same three checks (`CANCELLED_OR_EXPIRED_ALIVE_AT_CUTOFF` / `TYPE_MUTATED_SINCE_FREEZE` / `PUBLISH_DATE_NULL` against `CANCELLED_BUT_DATE_ALIVE` / `TYPE_DRIFT` / `NULL_PUBLISH_FROM_DROPPED`). The second set is used throughout, because it is the fuller of the two (it also carries `STALE_OVERRIDE`, `OVERLAPPING_ISSUE` and `LIMIT_EXCEEDED`) and because each name states the mechanism rather than the symptom. The first set has no other referent and should be dropped, not aliased.

### 7.14 Open questions — enumerations

*A reading aid, not a numbering. The identifiers are the register's (§9.4); the retired spelling `§7.14 Q1…Q4` is this list's positions 1–4, in order.*

1. ~~**DM-Q5 — Ratify the `contentMode` value set**~~ — **closed.** `NONE` is in and `ONE_OFF` is out of `ContentMode`, as proposed; a one-off is a value of the separate stored column `PublicationSeries.kind` (§3.1.4), not a reading of `cadence = NONE`.
2. ~~**DM-Q15 — `cadence = DAILY` is unnameable**~~ — **closed:** `${day}` and `${day-2-digits}` ship in the token vocabulary, and `DAILY` stays.
3. **DM-Q16 — `MONTHLY` / `MONTH_YEAR` / `EDITION_SEQUENCE` are speculative** — zero production instances between them. Keep them (they cost nothing and a later addition to a persisted enum is free) or cut them to keep the pickers honest?
4. ~~**DM-Q2 — Series audit action values**~~ — **closed:** the audit entity was generalised, and the series-level actions ship.


## 5. The criteria document — schema, vocabulary and the four worked production documents

*Owns the JSON document stored on `PublicationSeries.criteria` and copied, resolved, onto `PublicationIssue.criteriaSnapshot` at freeze. The per-entity field tables, the enumerations and the transitions are specified in §§3–4 and §§6–7; this section defines only the document's own shape and the rules that bind it.*

### 5.1 Column binding, and the two nulls

```java
/** The structured membership query. NULL <=> this series has no membership semantics. */
@Column(name = "criteria", columnDefinition = "TEXT")
@Convert(converter = JpaCriteriaAttributeConverter.class)
IssueCriteriaVo criteria;
```

`TEXT` + an `AttributeConverter` is the repo's structured-column idiom, taken verbatim from `Publication.printSettings` / `Publication.reportParams` (`niord-core/…/publication/Publication.java:158-166`). `Setting.value` uses the same `@Convert` with an explicit `length = 2000` (`niord-core/…/settings/Setting.java:44-45`); `TEXT` is chosen here because the document is bounded at 8,000 characters (V-C7) and a `VARCHAR(2000)` would truncate a large `messageSeries` set.

**`criteria` is nullable, and that nullability is a measured requirement, not a convenience.** Roughly 48 production publications have no membership semantics of any kind — 18 uploaded Akkumuleret EfS PDFs, 4 external Dansk Fyrliste links, 6 one-offs including two `type=NONE`, 10 tagless `MESSAGE_REPORT` annexes, and the NCAGS/Isbilag family whose tags each hold one hand-picked message that no query of any shape can select (MODEL-VERIFICATION §3.4, §5.1).

**Two nulls that must never be confused:**

| Value | Meaning |
|---|---|
| `criteria IS NULL` | **No query at all.** The series' issues have no member set. `timeRelation` and `aliveAtCutoff` are NULL too. The resolver returns an empty member list with `membershipProvenance = NO_MEMBERSHIP`; it never fabricates a criteria why-line. |
| `{"schemaVersion":1,"match":"ALL","criteria":[]}` | A query with **no nodes** — semantically "every message in the installation, bounded only by the universal status conjunct and the time relation". |

Confusing them turns a link-only one-off into a series that resolves the entire message corpus. **Resolved:** the second form is *not savable* — V-C6 requires at least one scope node, and the JSON Schema sets `minItems: 1`. The distinction is documented anyway, because it is the reason V-C6 exists, and because it is exactly legacy's `MEM-009` foot-gun: `PublicationService.java:544-546` scopes recording to `publication.getDomain().getMessageSeries()`, and a publication with **no** domain records from every message series in the installation.

### 5.2 What is deliberately *not* in the document

The document holds the *filter*. Everything that decides **behaviour** stays on a column, because behaviour gates tiling, chaining, gap detection and the release rail, and a value buried in a JSON blob cannot be a SQL predicate on a list endpoint.

| Fact | Where it lives | Why not in the criteria document |
|---|---|---|
| `timeRelation` | `PublicationSeries` column | Gates whether tiling, gap detection, "missing week" recovery and "previous issue's cut-off" run **at all** (MODEL-VERIFICATION §5.2, §5.18). Behaviour, not a filter. The prototype's `Tidsforhold` dropdown inside the criteria panel is one of the two prototype behaviours the decisions record marks as an *error* rather than a blank; it is corrected here. |
| `aliveAtCutoff` | `PublicationSeries` column | Same — and it is the one clause separating two regimes whose documents are byte-identical (§5.5.1 vs §5.5.2). **C2.** |
| The status conjunct | **Resolver invariant** (§8.4 RI-1) | Not authorable. `status ∈ Status.isPublic()` (`niord-model/…/message/Status.java:57`), never `status == PUBLISHED`: all 2,324 blank-era members are `EXPIRED` or `CANCELLED` and **zero** are `PUBLISHED`, so a literal current-status filter empties every historical issue. The prototype's "Status Publiceret" chip implements this wrongly and must not be copied. **MODEL-VERIFICATION §5.10; REVIEW R2.** |
| Interval bounds | `PublicationIssue.intervalFrom` / `.intervalTo` | Per issue, not per series. |
| The cut-off | `PublicationIssue.cutoffStampedAt` | Per issue; stamped, not derived. |
| `messageSortBy` / `messageSortOrder` | `PublicationSeries` columns | Promoted out of the untyped `printSettings` map, where they silently rewrote the PDF's contents (**ADD-005**). They decide whether the PDF has area headings at all (`MessageReportRestService.java:314`). |
| `advancedExpression` | **Nowhere.** No column, no node kind | **C4** cuts the hatch from Phase 1 entirely. The union below keeps a later hatch purely additive. |
| A `status` / `messageStatus` node | **Nowhere.** Rejected at save (V-C5) | See the status-conjunct row above. |

### 5.3 The envelope

```json
{
  "schemaVersion": 1,
  "match": "ALL",
  "criteria": [ /* 1..8 nodes */ ]
}
```

| Property | Type | Required | Semantics |
|---|---|---|---|
| `schemaVersion` | integer, currently `1` | yes | Document-level discriminator. A resolver **refuses loudly** a document whose version it does not know. Adding a node kind, an operator value or `match:"ANY"` is *additive and does not bump it*; changing the meaning of an existing document does. |
| `match` | enum, currently `"ALL"` only | yes | Conjunction of nodes. Declared as an enum rather than implied so `"ANY"` is a purely additive change later. All four production filters are top-level conjunctions; the one disjunction in the estate (`type==T \|\| type==P`) is expressed *inside* a set-valued node (§5.5.4), which is why no top-level `ANY` is needed in Phase 1. |
| `criteria` | array of nodes, 1–8 | yes | See §5.4. |

**Resolved — the version lives *inside* the document, not in a sibling `criteriaVersion` column.** The frozen snapshot (§5.6) copies the criteria document into `PublicationIssue.criteriaSnapshot`; if the version were a column on the series, an edit to the series would leave every frozen copy's version unrecoverable. The same applies to the JSON export/import round-trip, where the document travels alone. A queryable projection ("which series are still on schema 1") can be added later as a generated column with no format change — it is not needed in Phase 1, and there are 1,077 rows.

### 5.4 The discriminated union

The repo already has this exact mechanism twice — `niord-core/…/category/vo/ParamTypeVo.java:30-34` and `niord-core/…/promulgation/vo/SafetyNetAreaVo.java:40-46`, both `@JsonTypeInfo(use = Id.NAME, include = As.PROPERTY, property = "type")` + `@JsonSubTypes`. Copy it.

**One documented deviation: the discriminator property is `kind`, not `type`.** Both precedents chose `"type"` because neither had a collision. Here `type` is already the message-type domain word (`Message.type`, `PublicationType`, the `messageType` node), and a node reading `{"type":"messageType", …}` invites exactly the confusion the estate cannot afford. The Jackson discriminator name is a per-hierarchy choice, so this deviates from two instances, not from a rule.

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = MessageSeriesCriterionVo.class,   name = "messageSeries"),
    @JsonSubTypes.Type(value = MessageMainTypeCriterionVo.class, name = "messageMainType"),
    @JsonSubTypes.Type(value = MessageTypeCriterionVo.class,     name = "messageType"),
    @JsonSubTypes.Type(value = DomainCriterionVo.class,          name = "domain"),
    @JsonSubTypes.Type(value = AreaCriterionVo.class,            name = "area"),
    @JsonSubTypes.Type(value = CategoryCriterionVo.class,        name = "category"),
    @JsonSubTypes.Type(value = ChartCriterionVo.class,           name = "chart"),
})
public abstract class IssueCriterionVo implements IJsonSerializable {
    CriterionOperator operator = CriterionOperator.IN;   // IN | NOT_IN
    List<String> values = new ArrayList<>();
}
```

Every node is `{ kind, operator, values[] }`. **`values` holds stable string keys only** — never surrogate integer ids, never display labels. Labels are resolved live for the criteria chips and captured in the frozen envelope (§5.6), so a renamed area cannot rewrite a stored document or leave a stale caption on a published issue.

| `kind` | Operand (stable key) | Legal operators | Backing predicate | In production? |
|---|---|---|---|---|
| `messageSeries` | `MessageSeries.seriesId` — unique, not null (`MessageSeries.java:51`) | `IN` (`NOT_IN` reserved) | `m.messageSeries.seriesId IN (…)` — `MessageService.java:1075-1077` | **Yes — all four regimes** |
| `messageType` | `Type` enum name, 8 values (`niord-model/…/message/Type.java:21-33`) | `IN` (`NOT_IN` reserved) | `m.type IN (…)` — `MessageService.java:1048-1050` | **Yes — the P&T series, 501 publications** |
| `messageMainType` | `MainType` name: `NW` \| `NM` (`MainType.java:26-28`) | `IN` (`NOT_IN` reserved) | `m.mainType IN (…)` — `MessageService.java:1045-1047` | No |
| `domain` | `Domain.domainId` | `IN` **only** | **Macro** — expanded by the resolver to a `messageSeries` set before the query (§8.4 RI-8) | No — legacy used the publication's `domain` *field*, not a criterion |
| `area` | Area **MRN** (e.g. `urn:mrn:iho:country:dk`) | `IN` (`NOT_IN` reserved) | self-or-descendant: `join("areas", LEFT)` + `like(areas.lineage, '<lineage>%')`, OR-ed — `MessageService.java:1081-1092` | No |
| `category` | Category **MRN** | `IN` (`NOT_IN` reserved) | self-or-descendant over `join("categories", LEFT)` — `MessageService.java:1095-1104` | No |
| `chart` | `Chart.chartNumber` — exact, case-sensitive, unique index `chart_chart_number` (`Chart.java:44`) | `IN` (`NOT_IN` reserved) | OR of `equal(charts.chartNumber, n)` — `MessageService.java:1107-1114` | No |

**`NOT_IN` is defined in the schema and rejected by the Phase-1 validator (V-C2).** That is C4's pattern applied literally: the union stays extensible so a later addition is purely additive, but no untested predicate ships. No production filter negates anything.

**Area and category operands must be MRNs, never numeric surrogate ids.** `AreaService.findByAreaId` branches on `StringUtils.isNumeric` and accepts either (`niord-core/…/area/AreaService.java:407-412`; identically `CategoryService.java:434-439`). A surrogate id is not portable across the JSON import/export and is not auditable. V-C9 rejects a purely-numeric operand. Whether every production area and category actually carries an MRN is **open — DM-Q19 (§9.4)** — low urgency, because no production publication uses an area, category or chart criterion; these node kinds are forward-looking vocabulary.

#### Formal schema (JSON Schema draft 2020-12)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://niord.dma.dk/schema/publication-issue-criteria/v1.json",
  "title": "Publication series issue criteria",
  "type": "object",
  "additionalProperties": false,
  "required": ["schemaVersion", "match", "criteria"],
  "properties": {
    "schemaVersion": { "type": "integer", "const": 1 },
    "match":         { "enum": ["ALL"] },
    "criteria": {
      "type": "array", "minItems": 1, "maxItems": 8,
      "items": { "$ref": "#/$defs/node" }
    }
  },
  "$defs": {
    "node": {
      "type": "object",
      "additionalProperties": false,
      "required": ["kind", "operator", "values"],
      "properties": {
        "kind": {
          "enum": ["messageSeries", "messageMainType", "messageType",
                   "domain", "area", "category", "chart"]
        },
        "operator": { "enum": ["IN", "NOT_IN"] },
        "values": {
          "type": "array", "minItems": 1, "maxItems": 200, "uniqueItems": true,
          "items": { "type": "string", "minLength": 1, "maxLength": 256 }
        }
      },
      "allOf": [
        { "if":   { "properties": { "kind": { "const": "messageType" } }, "required": ["kind"] },
          "then": { "properties": { "values": { "items": { "enum": [
                      "PERMANENT_NOTICE", "TEMPORARY_NOTICE", "PRELIMINARY_NOTICE",
                      "MISCELLANEOUS_NOTICE", "COASTAL_WARNING", "SUBAREA_WARNING",
                      "NAVAREA_WARNING", "LOCAL_WARNING"] } } } } },
        { "if":   { "properties": { "kind": { "const": "messageMainType" } }, "required": ["kind"] },
          "then": { "properties": { "values": { "items": { "enum": ["NW", "NM"] } } } } },
        { "if":   { "properties": { "kind": { "const": "domain" } }, "required": ["kind"] },
          "then": { "properties": { "operator": { "const": "IN" } } } },
        { "if":   { "properties": { "kind": { "enum": ["area", "category"] } }, "required": ["kind"] },
          "then": { "properties": { "values": { "items": { "pattern": "^(?!\\d+$)\\S.*$" } } } } }
      ]
    }
  }
}
```

`minItems: 1` on `criteria` is the schema-level half of V-C6; `^(?!\d+$)\S.*$` is the "not a surrogate id" guard of V-C9. The eight `messageType` values are exactly `Type.java:21-33` — do **not** re-spell them as the legacy filter's `T`/`P` abbreviations.

### 5.5 The four worked production documents

Every `messageTagFilter` in the 17.08.2026 snapshot was read: 1,077 publications and 12 templates, **exactly four distinct values** in nine years (CONSENT-RECOMMENDATIONS, "the production filter inventory is now complete"). Each regime needs the document **and** the two series columns; neither determines membership alone. Series scope is written as an explicit `messageSeries` node in all four — see the importer rules below.

#### 5.5.1 Blank filter → the sticky regime · weekly EfS 2017-01 → mid-2019 · 160 publications

```json
{ "schemaVersion": 1, "match": "ALL",
  "criteria": [ { "kind": "messageSeries", "operator": "IN", "values": ["dma-nm"] } ] }
```
```
contentMode   = GENERATED_FROM_QUERY
timeRelation  = PUBLISHED_IN_INTERVAL
aliveAtCutoff = false          <-- C2's flag OFF
interval      = (previousIssue.cutoff, thisIssue.cutoff]
```

Legacy's blank filter falls back to `DEFAULT_MESSAGE_TAG_FILTER = "data.phase == 'msg-status-change' && (msg.status == Status.PUBLISHED || data.isIncluded)"` (`PublicationService.java:64-65`, verbatim). The `|| data.isIncluded` clause makes membership permanent — i.e. **it cancels the alive conjunct**. The interval predicate alone reproduces **115 of 116 issues, 2,323 of 2,324 members, zero extras in either direction**; adding the conjunct is precisely what breaks them (73 drops across 39 issues). This regime is why `aliveAtCutoff` is a per-series flag rather than a constant in the rule.

#### 5.5.2 `data.phase=='msg-status-change' && msg.status==PUBLISHED` · weekly EfS + 3 Isbilag · 387 publications

```json
{ "schemaVersion": 1, "match": "ALL",
  "criteria": [ { "kind": "messageSeries", "operator": "IN", "values": ["dma-nm"] } ] }
```
```
contentMode   = GENERATED_FROM_QUERY
timeRelation  = PUBLISHED_IN_INTERVAL
aliveAtCutoff = true           <-- C2's flag ON
interval      = (previousIssue.cutoff, thisIssue.cutoff]
```

**The document is byte-identical to §5.5.1.** The two regimes differ by exactly one clause, and that clause is the alive conjunct — the single most consequential finding of the all-series sweep. 365 of 379 issues exact after cut-off recovery; 14 divergent messages out of 7,887 (0.18%), each named and mechanism-proven.

The `data.phase` conjunct is a **recorder-lifecycle** condition, not a message predicate — it says "only react to live status changes, do not back-fill". It has no query translation and is dropped deliberately (C4's accepted loss: "phase-dependent back-fill behaviour"). What it produced is supplied instead by the interval predicate plus the frozen snapshot.

#### 5.5.3 `msg.status == PUBLISHED` · Skydeområder (16) + EfS A / NM Almanac (13) · 29 publications

```json
{ "schemaVersion": 1, "match": "ALL",
  "criteria": [ { "kind": "messageSeries", "operator": "IN", "values": ["dma-fa"] } ] }
```
*(EfS A: `"values": ["dma-nm-almanac"]`.)*
```
contentMode   = GENERATED_FROM_QUERY
timeRelation  = IN_FORCE_AT_CUTOFF
aliveAtCutoff = true           <-- constitutive here: stored TRUE, never NULL (S-3 / R2)
interval      = null           <-- no lower bound, no chaining, no tiling, no gap check
firstIssueStartsAt = null
```

The status literal contributes **no node** — it is the universal conjunct (§5.2). Verified: 11 of 15 released Skydeområder issues reproduce exactly at an instant on the printed release day; 9 of 13 for EfS A.

#### 5.5.4 `(msg.type==T || msg.type==P) && msg.status==PUBLISHED` · the P&T series · 501 publications

```json
{ "schemaVersion": 1, "match": "ALL",
  "criteria": [
    { "kind": "messageSeries", "operator": "IN", "values": ["dma-nm"] },
    { "kind": "messageType",   "operator": "IN",
      "values": ["PRELIMINARY_NOTICE", "TEMPORARY_NOTICE"] }
  ] }
```
```
contentMode   = GENERATED_FROM_QUERY
timeRelation  = IN_FORCE_AT_CUTOFF
aliveAtCutoff = true           <-- constitutive here: stored TRUE, never NULL (S-3 / R2)
interval      = null
```

The `||` becomes **one set-valued node**, not two nodes — which is why the envelope needs no top-level `"ANY"`. 163 of 214 swept issues exact in both directions; P&T uge 28/2026 returns 165/165. Note that this is the larger shape by publication count: 502 P&T + 16 Skydeområder + 13 EfS A = **531 issues with no interval and no lower bound at all**, against 500 for the weekly EfS.

#### 5.5.5 The fifth combination — no membership at all · ~48 publications

```
contentMode   ∈ { UPLOADED_FILE, EXTERNAL_LINK, NONE }
criteria      = null
timeRelation  = null
aliveAtCutoff = null
interval      = null
```

Do not attempt to fit these. The resolver returns an empty list with `membershipProvenance = NO_MEMBERSHIP` and a `SNAPSHOT_ONLY` why-line, never a fabricated criteria match.

#### 5.5.6 The import translation table, and the fail-loudly rule

| Verbatim `messageTagFilter` | Count | `contentMode` | `timeRelation` | `aliveAtCutoff` | Nodes written |
|---|---:|---|---|---|---|
| *(blank)* | 160 | `GENERATED_FROM_QUERY` | `PUBLISHED_IN_INTERVAL` | **`false`** | `messageSeries IN <expanded domain series>` |
| `data.phase=='msg-status-change' && msg.status==PUBLISHED` | 387 | `GENERATED_FROM_QUERY` | `PUBLISHED_IN_INTERVAL` | **`true`** | `messageSeries IN <…>` |
| `msg.status == PUBLISHED` | 29 | `GENERATED_FROM_QUERY` | `IN_FORCE_AT_CUTOFF` | `true` | `messageSeries IN <…>` |
| `(msg.type==T \|\| msg.type==P) && msg.status==PUBLISHED` | 501 | `GENERATED_FROM_QUERY` | `IN_FORCE_AT_CUTOFF` | `true` | `messageSeries IN <…>`, `messageType IN [PRELIMINARY_NOTICE, TEMPORARY_NOTICE]` |
| *(no filter, no tag, no report)* | ~48 | `UPLOADED_FILE` \| `EXTERNAL_LINK` \| `NONE` | `null` | `null` | `criteria = null` |

Rules the importer follows, all forced:

1. **Series scope is materialised, never referenced.** Legacy scoped recording to `publication.getDomain().getMessageSeries()` (`PublicationService.java:544-546`; `Domain.java:98`). The importer expands that **at import time** and writes the resulting `seriesId` set as an explicit `messageSeries` node. It never writes a `domain` node, so a later domain edit cannot retroactively rewrite what a 2018 issue means. (`niord-nm` resolves to exactly one series, `dma-nm` — settled three ways; MODEL-VERIFICATION §5.21.)
2. **Match on the normalised verbatim string:** trim, collapse internal whitespace runs to one space, compare case-sensitively. Whitespace normalisation is required because the estate carries both `msg.status == PUBLISHED` and `msg.status==PUBLISHED`.
3. **No parsing, no evaluation, no fuzzy matching.** Do not strip `Status.` / `Type.` prefixes, do not reorder operands, do not invoke `MessageScriptFilterService`. There are exactly four values in nine years; anything else is unknown *by construction*.
4. **Fail loudly** — C4's signed acceptance criterion. A filter outside the five combinations aborts that row with a named error carrying the `publicationId` and the verbatim filter, and the whole import fails rather than importing a partial estate. Not a warning, not a skip.

> **Blocking prerequisite — DM-Q17 (§9.4).** The four canonical strings have **never been read verbatim**. CONSENT-RECOMMENDATIONS abbreviates them (`msg.type==T`) while the code's own documented example spells `msg.type == Type.TEMPORARY_NOTICE` (`Publication.java:135-137`; `MessageScriptFilterService.java:29-34`). The importer matches stored bytes, so the four strings must be dumped from the 17.08.2026 snapshot and committed as a test resource **before** this table is implemented. Blocking for the importer only; nothing else in the model depends on it.

### 5.6 The resolved envelope — what `criteriaSnapshot` holds

At publish (and at re-freeze during amend), the **effective** criteria are frozen onto the issue. The frozen value is not the bare series document: it is a resolved envelope carrying every input the resolver actually used, so the published-issue screen's *"Kriterier (som de gjaldt ved publiceringen)"* panel, the `?publication=` status widening (C8), and any replay all read one object.

```json
{
  "schemaVersion": 1,
  "match": "ALL",
  "criteria": [ /* the series document merged with the issue's criteriaOverride */ ],
  "resolved": {
    "timeRelation":      "PUBLISHED_IN_INTERVAL",
    "aliveAtCutoff":     true,
    "seriesIds":         ["dma-nm"],
    "intervalFrom":      1750000000000,
    "cutoff":            1751000000000,
    "sortBy":            "area",
    "sortOrder":         "ASC",
    "labels": { "messageType": { "PRELIMINARY_NOTICE": "Foreløbig efterretning" } }
  }
}
```

Consequences, each of which removes a column another design would need:

- `resolved.seriesIds` is the **post-expansion** message-series set (§8.4 RI-8). Freezing it is what stops a later "add a series to this domain" edit from rewriting a 2018 issue's meaning.
- `resolved.timeRelation` / `resolved.aliveAtCutoff` are echoed here rather than re-read from the series, because both are editable after an issue publishes.
- `resolved.labels` captures display strings at freeze so a renamed area cannot leave a stale caption on a published issue. Labels are **display only** — they are never a key and never re-parsed.
- **`snapshotDomainId` stays a separate denormalised column** on `PublicationIssue` even though it is derivable, because two hot paths read it per request: C8's domain/series restriction lifting, and "sort by the issue's series domain, not the caller's". That is the one deliberate duplication.
- `resolved.sortBy` / `resolved.sortOrder` are the values that produced `IssueMember.sortIndex` and are carried on the issue as `snapshotSortBy` / `snapshotSortOrder` as well, for the same hot-path reason.

**A `CRITERIA_MATCH` why-line is fully derivable from this envelope plus the member's frozen scalars** — do not store a rendered string per member. Only `OVERRIDE_*` (an FK) and `SNAPSHOT_ONLY` (a short note) need stored payload.

### 5.7 Canonical serialization

Before persisting, sort nodes by `(kind, operator)` and each `values` array ascending. Two documents that mean the same thing then compare **byte-equal**, which is what the audit trail and the amend guard both need, and what stops a re-save minting a spurious `CRITERIA_OVERRIDDEN` audit entry. Enforced by V-C10.

### 5.8 The converter must be new, and it must throw

Neither existing converter can carry this document:

- `JpaJsonAttributeConverter` deserialises to a bare `Object` (`niord-core/…/db/JpaJsonAttributeConverter.java:57`) — the polymorphic `kind` typing would be lost to `LinkedHashMap`.
- `JpaPropertiesAttributeConverter` is fixed to `Map<String,Object>` (`JpaPropertiesAttributeConverter.java:36`).

Write `JpaCriteriaAttributeConverter implements AttributeConverter<IssueCriteriaVo, String>`, modelled on `JpaPropertiesAttributeConverter`'s typed-Jackson idiom but reading `IssueCriteriaVo.class`.

**Deliberate deviation, stated so a reviewer does not "fix" it:** both existing converters catch the parse failure, log it and return `null` / an empty map (`JpaJsonAttributeConverter.java:60-64`; `JpaPropertiesAttributeConverter.java:60-65`). **The criteria converter must rethrow as an unchecked exception instead.** A silently-nulled `printSettings` degrades a PDF; a silently-nulled `criteria` degrades to "no criteria" — and §5.1's first null then makes a link-only one-off resolve the whole corpus into an official publication. Loud beats lossy here.

---

## 6.–7. *(entity field tables, transitions and enumerations — specified elsewhere in this document)*

---

## 8. Constraints, indexes and invariants

*This section is the single authority for the model's constraints. It supersedes any V-numbering that appears inside the per-entity sections; rules are prefixed by the thing they constrain — **S** series, **C** criteria document, **I** issue, **D** per-language desc, **M** frozen member, **O** override, **X** cross-entity, **RI** resolver invariant.*

**Where enforcement lives.** Every rule below is enforced **in the service layer**, surfaced as a structured `400`, matching how the repo already does it (`PublicationService.java:492-511` refuses a status transition with `"Invalid state transition X -> Y"`, surfaced at `PublicationRestService.java:415-423`). There is **no `@Check` annotation anywhere in `niord`** — verified by grep — so DB-level `CHECK` constraints are a deviation from house style. They are specified in §9.2 as optional defence-in-depth for hand-applied DDL only; they are not the enforcement mechanism, and Hibernate's `update` mode will not retrofit them onto an existing table.

### 8.1 Uniqueness, foreign keys, and immutability

| Object | Constraint | Reason |
|---|---|---|
| `PublicationSeries.seriesId` | `UNIQUE NOT NULL`, `VARCHAR(64)`, `^[a-z0-9][a-z0-9._-]{1,63}$` | **Human-authored business key** — the admin route segment and the JSON-interchange upsert key, exactly as `MessageSeries.seriesId`, `PublicationCategory.categoryId`, `FmReport.reportId` and `Domain.domainId` are. **Not a UUID and not in the UUID keyspace** (R3): a series is a configuration row, and `publication=` always names an *issue*, never a series, so the "one resolver answers both" argument for adopting a legacy UUID does not apply. §1.2, §3.1.1. |
| `PublicationSeries.legacyTemplateId` | `UNIQUE` nullable, `VARCHAR(36)` | The legacy **template** publication's `publicationId`, recorded verbatim — the whole of the series' legacy provenance, carried here rather than in `seriesId` (R3). What IMP-008 relinks template→child associations against, and what keeps IMP-005's upsert-by-`publicationId` round-trip total. NULL for a series with no legacy template. |
| `PublicationIssue.publicId` | `UNIQUE NOT NULL`, `VARCHAR(36)`, lowercase-UUID pattern | Cited from stored message HTML; see X-1 and X-2. |
| `PublicationIssue.legacyPublicationId` | `UNIQUE` nullable | Import provenance and the transition-union dedup key. Equals `publicId` when non-null. |
| `PublicationCategory.categoryId` | `UNIQUE NOT NULL` (unchanged) | Human-authored interchange upsert key (`PublicationCategory.java:40-59`). |
| every `*Desc` table | `UNIQUE (lang, entity_id)` | Copied verbatim from `PublicationDesc.java:31-34`; 20+ existing desc tables do the same. |
| `IssueMember` | `UNIQUE (issue_id, messageUid)` | One row per message per issue. |
| `IssueOverride` | `UNIQUE (issue_id, messageUid)` | One override per message per issue; flipping include↔exclude **replaces** the row and writes two audit entries. |
| `PublicationSeries_languages` | `PRIMARY KEY (PublicationSeries_id, indexNo)` | `@ElementCollection @OrderColumn(name="indexNo")`, the `MailingListTrigger.java:139-141` pattern. |
| FKs | `PublicationSeries → PublicationCategory` (**NOT NULL**), `→ Domain` (**nullable**), `→ PublicationSeries` (self, `sourceSeries`, nullable, **2026-09-13**); `PublicationIssue → PublicationSeries` (NOT NULL), `→ User ×2`, `→ PublicationIssue` (self, `supersedes`); `IssueMember → PublicationIssue` (NOT NULL), `→ Message`, `→ IssueOverride`; `IssueOverride → PublicationIssue` (NOT NULL), `→ Message`, `→ User` (NOT NULL); `IssueAuditEntry → PublicationIssue` (NOT NULL), `→ User` | `category` is `@NotNull` because the public API dereferences it unguarded (`AbstractApiService.java:228-233`). ~~`domain` is **nullable and NULL means global** — 17 production rows are domain-free, and that is what makes the citation-only and external-link publications visible from every domain.~~ **Amended 2026-08-29 (R-xxvi): `PublicationSeries → Domain` is NOT NULL** (V13) and names the owner; a second FK pair lands on `PublicationSeries_AvailableDomain` — `→ PublicationSeries` **RESTRICT** (the application empties the list before deleting a series; a cascade there would let a stray `DELETE` take the sharing with it silently) and `→ Domain` **ON DELETE CASCADE** (deleting a domain removes it from every list that named it, which is what "this domain no longer exists" means — the alternative is a domain delete that fails against a publication in another domain entirely). |

**Immutable after creation:** `PublicationSeries.seriesId`, `PublicationCategory.categoryId`, `PublicationIssue.publicId`, `PublicationIssue.repoPath`.
**Immutable after publish:** `PublicationIssueDesc.fileName`, `.filePath`, `.link`; `PublicationIssue.cutoffStampedAt`, `.intervalFrom`, `.intervalTo`, `.publicFrom`; `PublicationSeries.messagePublication` (once *any* issue of the series has published).

**`publicTo` is the one exception, and it is not a loophole** (R4). It is immutable after publish **except for the capping write**: the successor's publish sets it once, to `successor.cutoffStampedAt − 1 ms`, when `publicWindowSource = DERIVED` — or, per R8, when it is still NULL whatever the source says. The earlier immutability list included `publicTo` outright, which contradicted the publish transaction that must write it onto an already-published predecessor; that contradiction is resolved in favour of the transaction. Every such write is audited as `VISIBILITY_CAPPED` on the row that was capped (§4.8), and it happens **once** — a row already carrying a non-NULL `publicTo` is never re-capped.

### 8.2 Validation invariants

#### Series (S)

| # | Rule | Forced by |
|---|---|---|
| **S-1** | `contentMode = GENERATED_FROM_QUERY` ⟺ `timeRelation IS NOT NULL` ⟺ `criteria IS NOT NULL` ⟺ `reportId IS NOT NULL`. **`reportId` here is the *series* column.** An issue may carry its own `PublicationIssue.reportId` override (§3.6.6); the invariant is about what the series guarantees, and the effective report is `issue.reportId ?? series.reportId` | MODEL-VERIFICATION §5.1 |
| **S-2** | `aliveAtCutoff IS NOT NULL` ⟺ `criteria IS NOT NULL` — i.e. **NULL exactly when the series is not query-backed**, never as a way of saying "not applicable" | C2 / **R2** |
| **S-3** | `timeRelation = 'IN_FORCE_AT_CUTOFF'` ⟹ `aliveAtCutoff = TRUE`; under `PUBLISHED_IN_INTERVAL` the value is free (`true` or `false`) | C2 / **R2, ratified 2026-08-21** |
| **S-4** | `firstIssueStartsAt IS NOT NULL` ⟺ `timeRelation = 'PUBLISHED_IN_INTERVAL'` | MODEL-VERIFICATION §3.1 |
| **S-5** | `cadence = 'WEEKLY'` ⟺ `nominalCutoffDay IS NOT NULL` | SER-017. **The column is `nominalCutoffDay`** (§3.1.4) — the one name, everywhere (R4); `nominalCutoffDayOfWeek` and `cutoffWeekday` are retired spellings and must not appear in the entity, the DDL, the VOs or the wire |
| **S-6** | `cadence ∈ ('MONTHLY','YEARLY')` ⟺ `nominalCutoffDayOfMonth IS NOT NULL`; `cadence='YEARLY'` ⟺ `nominalCutoffMonth IS NOT NULL` | SER-017 |
| **S-7** | `cadence <> 'NONE'` ⟺ `nominalCutoffTime IS NOT NULL` | SER-017 |
| **S-8** | `cadence = 'NONE'` ⟹ `nextIssueCreation = 'MANUAL'` **and** `numberingScheme = 'NONE'` | one-off semantics |
| **S-9** | `reportId IS NOT NULL` ⟺ `pageSize`, `pageOrientation`, `mapThumbnails` all non-null | FIL-021 |
| **S-10** | `messageSortBy IS NULL` ⟺ `messageSortOrder IS NULL` | ADD-005/ADD-006 |
| **S-11** | `languages` non-empty, ordered, duplicate-free, every entry ∈ `NiordApp.getLanguages()` | C5 |
| **S-12** | every language in `languages` has a `PublicationSeriesDesc` with a non-blank `name`; no desc row outside `languages` | ADD-017 |
| **S-13** | `messagePublication <> 'NONE'` ⟹ every language carries a non-blank `messageReferenceFormat` | C5 **as amended** — scoped to series with the reference option ON, because the weekly EfS template carries no `messagePublicationFormat` in either language and the unscoped rule would block the flagship series |
| **S-14** | every `${…}` token in every pattern is in the vocabulary **for this series' `cadence`/`numberingScheme`**; post-expansion the resolver asserts no `${` survives | SER-010 — production serves a real PDF at `…/Skydeomraader-%24%7Byear%7D.pdf` because an unresolved token reached a file name |
| **S-15** | `contentMode = GENERATED_FROM_QUERY` and `languages.size() > 1` ⟹ `fileNamePattern` distinct across languages | ADD-027 — otherwise every language renders to one path and the last language wins |
| **S-16** | `seriesId` immutable after create | IMP-005 / IMP-008 round-trip |
| **S-17** | `status = 'ACTIVE'` requires every activation rule green — S-1…S-16 as written, and equally the rules added since: S-19, S-20, S-21, S-24 and S-25. The four **hard** rules (the owner pair, S-22 and S-23) are refused on *every* save, activation or not | the DRAFT→ACTIVE guard |
| **S-18** | `messagePublication` immutable once any issue of the series has published | `PublicationUtils.java:50-51` picks `internalPublication` vs `publication` from this value; flipping it after citations exist makes every existing citation unfindable and re-applying **appends a duplicate** instead of replacing |
| **S-19** | `category` is set. The column is `NOT NULL`, and without a rule the constraint is met at flush time as a 500 about a transient value rather than as an answer about an empty picker | the column, plus the same omission found twice on two different save routes |
| **S-20** | where the series has a cadence, its owner domain carries a **readable** time zone: it is the only source of the zone the cut-off schedule is reckoned in, and the installation's domains genuinely differ. Asked only of a domain that names itself, so the dry-run report's id-only stand-in cannot fail every series at once | R-xxvi; an hour either way at the year boundary is a different year on the cover |
| **S-21** | `cutoffDefault` is set, and a period-boundary default is legal only on a `YEARLY` cadence — everything else is cut off at the release. A calendar-driven default on a weekly would stamp every issue at a nominal instant nobody pressed | the nominal-versus-stamped distinction the model exists to keep apart |
| **S-22** | `releaseMode = AUTO_RELEASE` is refused while the scheduler and the abort path do not exist. The column is stored, displayed and preserved; saving a series **into** that mode is what is refused | an unattended release has nobody to acknowledge its warnings |
| **S-23** | no `reportParams` key is one of the six injected from the issue — the four printed strings (`week`, `weekTo`, `year`, `edition`, as the issue prints them) and the two derived numbers (`yearNumber`, `weekNumber`, `Integer`, absent where the issue has none, for a template that computes from a year rather than prints it). A typed copy is a second, fixed answer beside the injected one. The render injects a seventh, `weeks` (the issue's weeks as the weekly reports' heading prints them, made from the six); it is not refused, and a typed `weeks` is overwritten by the injected one | FIL-023 |
| **S-24** | `sourceSeries IS NOT NULL` ⟺ `timeRelation = 'COMPILED_FROM_SOURCE'`; a compilation carries neither `criteria` nor `aliveAtCutoff`. **Both directions**, and the second is the one that bites: a series switched back off the regime would otherwise keep a source nothing reads | the compilation regime (§7.5) — the criteria and liveness legs are reported here rather than through S-1/S-2, because on a compilation they are not a missing half of a shape but a value contradicting the regime |
| **S-25** | the source series is **not this series**, is `GENERATED_FROM_QUERY`, and is **not itself a compilation** | no chains: a compilation of a compilation makes one issue's contents depend on a derivation two levels away with nothing recording the middle one. A self-source would compile an issue's own predecessors into it and grow without bound |

**The two owner rules are deliberately not in this table, and that is a mechanical constraint rather than an omission.** The validator reports them under ids carrying a letter suffix — one for "every series names an owner", one for "availability is a closed answer and selected-domains must name somebody" — and the exported id list is built by a pattern that reads a family and a number. An id with a suffix cannot be matched by it, so declaring the pair here would export ids the backend manifest could never bind and turn a green build red for a spelling. Both rules are stated where they are enforced (§3.1.6) and both are hard rules, refused on every save.

> **Resolution — S-2 + S-3 are ruling R2, ratified 2026-08-21, and they supersede the two competing readings of `aliveAtCutoff`.** One reading made it a nullable `Boolean` that is NULL under `IN_FORCE_AT_CUTOFF` ("not applicable"); the other made it `NOT NULL DEFAULT TRUE`. Adopted: **nullable `Boolean` column, NULL exactly when the series is not query-backed (`criteria IS NULL`), constrained to `TRUE` under `IN_FORCE_AT_CUTOFF`, and free-valued under `PUBLISHED_IN_INTERVAL`.** This is strictly better than either: storing `false` under `IN_FORCE_AT_CUTOFF` is unrepresentable, *and* the resolver reads one non-null boolean for every query-backed series with no "NULL means yes here" special case — which is exactly the kind of trap that regresses. The ~48 no-membership rows carry NULL alongside their NULL `criteria`, and `resolved.aliveAtCutoff` in a frozen envelope is therefore always a real boolean whenever members exist. The competing "must be null under `IN_FORCE_AT_CUTOFF`" rule would have rejected **531 of 1,031** message-backed series at the first `INSERT`; every other site in this document — the field row (§3.1.5), the legality matrix (§3.1.3), the five-combination table (§3.1.5), the worked documents (§5.5.3, §5.5.4), the import translation table (§5.5.6) and the frozen echo (§3.6.5) — is written to this rule, and `PublicationSeriesVo` / `IssueMemberListVo` carry it on the wire (`API-CONTRACT.md`).
>
> **S-3 is semantically accurate, not a tightening by inference.** `IN_FORCE_AT_CUTOFF` *is* `publishDateFrom <= cutoff AND (publishDateTo IS NULL OR publishDateTo >= cutoff)`: the second half **is** the alive clause, so recording `TRUE` records what the resolver applies. Without the clause, "in force at the cut-off" degenerates to "published at any time up to the cut-off", i.e. the whole corpus back to 2017.

#### Criteria document (C)

| # | Rule | Failure |
|---|---|---|
| **C-1** | Validates against §5.4 | `400` naming the JSON Pointer |
| **C-2** | `operator = 'NOT_IN'` rejected in Phase 1 | `400` — "reserved, not yet supported" |
| **C-3** | At most one node per `(kind, operator)` pair | `400` — two same-kind `IN` nodes under `match:"ALL"` are always either redundant or empty |
| **C-4** | Every `values` entry resolves: `seriesId` → a `MessageSeries`, `domainId` → a `Domain`, MRN → an `Area`/`Category`, chart number → a `Chart` | `400` naming the unresolvable operand. **Never save a document with a dangling operand** — see RI-6 |
| **C-5** | No `status` / `messageStatus` criterion of any kind | `400` — the conjunct is a resolver invariant (RI-1) |
| **C-6** | At least one `messageSeries` **or** `domain` node | `400` — an unscoped query resolves across every message series in the installation, legacy's `MEM-009` foot-gun |
| **C-7** | Serialized document ≤ 8,000 characters | `400` — bounds the audit payload and the `IN`-list size |
| **C-8** | `schemaVersion = 1` | `400` — an unknown version is refused loudly, never best-effort parsed |
| **C-9** | `area` / `category` operands are MRNs, not purely numeric | `400` |
| **C-10** | Canonically serialized (§5.7) before persist | internal; a non-canonical document is normalised, not rejected |

#### Issue (I)

| # | Rule | Forced by |
|---|---|---|
| **I-1** | `intervalFrom IS NOT NULL` ⟺ `series.timeRelation = 'PUBLISHED_IN_INTERVAL'`; **NULL** for `IN_FORCE_AT_CUTOFF` | MODEL-VERIFICATION §3.1 — 531 issues have no lower bound at all |
| **I-2** | `intervalTo IS NOT NULL` when `series.contentMode = 'GENERATED_FROM_QUERY'` | the nominal cut-off must exist before publish |
| **I-3** | `cutoffStampedAt IS NOT NULL` ⟺ `status ∈ ('PUBLISHED','RETIRED')` **and** `membershipProvenance <> 'NO_MEMBERSHIP'` | the settled canonical rule |
| **I-4** | `cutoffSource IS NOT NULL` ⟺ `cutoffStampedAt IS NOT NULL` | MODEL-VERIFICATION §5.6 |
| **I-5** | `publishedAt IS NOT NULL` ⟺ `status ∈ ('PUBLISHED','RETIRED')` | distinct from `cutoffStampedAt` because a settled decision lets the admin choose the instant |
| **I-6** | `retiredAt`, `retiredBy`, `retiredReason` all non-null ⟺ `status = 'RETIRED'`; reason 3..512 chars, non-blank | C7 |
| **I-7** | `timeRelation = 'PUBLISHED_IN_INTERVAL'` ⟹ `intervalFrom` = the `cutoffStampedAt` of the **PUBLISHED issue with the greatest `cutoffStampedAt` strictly below this issue's `intervalTo`**, **or** an `INTERVAL_CHANGED` audit entry explains the deviation. The lookup is **stamp-ordered and half-open** (`[intervalFrom, effectiveCutoff)`), never a bare "previous issue": publish leaves `intervalTo` at the nominal bound, so an early release makes the naive form read an overlap that does not exist and abort the publish transaction. §4.3 T0 and `API-CONTRACT.md` §4.2 state the same lookup | the settled chaining rule |
| **I-8** | Two issues of one `PUBLISHED_IN_INTERVAL` series may **not** overlap. Two issues of an `IN_FORCE_AT_CUTOFF` series **may** — the overlap check must not merely be omitted there, it must be **asserted absent** by a test | MODEL-VERIFICATION §5.18 — the 2026 and 2027 firing-areas issues share 31 of 32 members |
| **I-9** | Within a series, `cutoffStampedAt` is strictly increasing in publish order | breaking it breaks chaining and the visibility window |
| **I-10** | `publicId` matches `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$` and is immutable for life | **hard technical constraint** — `PublicationUtils.java:60-61, 123` builds a jsoup selector by *unquoted string concatenation*; any CSS-special character makes the selector match nothing and `updateMessagePublications` then **appends a duplicate citation** instead of replacing in place. Human-readable ids (`efs-uge-27-2026`) are forbidden as `publicId`; a slug may exist as a separate, non-citable admin field |
| **I-11** | `memberCount` equals the number of `IssueMember` rows | denormalised, the `MessageTag.messageCount` idiom (`MessageTag.java:93-96`) |
| **I-12** | `snapshotFrozenAt`, `snapshotSortBy`, `snapshotSortOrder`, `criteriaSnapshot`, `membershipProvenance` all non-null when `memberCount > 0` | reproducibility |
| **I-13** | `supersedes` references a different issue of the **same** series; no cycles | MODEL-VERIFICATION §5.8 — `edition` is free text with no supersede semantics |
| **I-14** | Public window: `publicFrom = cutoffStampedAt`, and `publicTo = successor.cutoffStampedAt − 1 ms` **whenever a successor exists — for this issue as well as for the predecessor**. **The neighbour lookup on both sides is `status IN (PUBLISHED, RETIRED)`**, the one filter §4.4 steps 12–13 and P6b all use: retire leaves the file and the public window in place, so a retired issue still occupies its bracket, and skipping it would leave an uncapped predecessor and make I-18 unsatisfiable. Written when `publicWindowSource = 'DERIVED'`, or regardless of source when the row's `publicTo IS NULL` (R8's belt and braces). The capping is **symmetric**: §4.4 step 12 caps the publishing issue against its successor, step 13 caps the predecessor against it | R5, R8; see the resolution below |
| **I-15** | `week` / `weekTo` / `year` derived from the **effective cut-off** (`cutoffStampedAt`, else `intervalTo`), as ISO week + **ISO week-year**, in the **series domain's** time zone | REVIEW F6 — legacy pairs a correct ISO week with the *calendar* year in the JVM default zone, so a 31.12.2025 cut-off yields "EfS uge 1 - **2025**" |
| **I-16** | Delete allowed only when `status = 'OPEN'` **and** `cutoffStampedAt IS NULL` **and** `publishedAt IS NULL`. A `PUBLISHED` or `RETIRED` issue is never deletable | C7 + X-2 |
| **I-17** | `intervalFromSource IS NOT NULL` ⟺ `intervalFrom IS NOT NULL`; `intervalToSource IS NOT NULL` ⟺ `intervalTo IS NOT NULL`. Both are stored columns (§3.6.2), never derived at read time | R4 — the approved UX needs the stamped-versus-nominal marker on every interval, and no derivation can produce `MANUAL` |
| **I-18** | **A series has at most one issue with `publicTo IS NULL`.** That issue is the current one; every other published or retired issue of the series is capped. Enforced by the symmetric capping of §4.4 steps 12–13, asserted as an **import check** over every imported series, and re-asserted after every publish | R5, R8 — the newest imported issue carries `publicTo = NULL`, and the first native publish would otherwise leave **two** current EfS issues on the public site |
| **I-19** | `publicTo IS NULL` is legal **only when no successor exists**. Where a successor exists, `publicTo = successor.cutoffStampedAt − 1 ms`, whichever of the two publish steps wrote it | R5 — a retro-created gap issue publishes below issues that already released, so "the successor caps me later" never fires for it |
| **I-20** | A compiled issue's snapshot header names its source series and **every** source issue whose period fell inside its own: `membershipProvenance = 'COMPILED'` ⟺ `snapshotSourceSeriesId IS NOT NULL` ⟺ `snapshotSourceIssueIds IS NOT NULL`, the ids in cut-off order. The list is written from the survey, not from the frozen rows, so a source period that contributed no member is still named — what the year **covered** and what it **printed** are different questions | the union is not re-derivable: a source issue retired or published later moves it, and nothing else records what this issue actually compiled |

> **Resolution — the public visibility window.** Two competing forms appeared: `visibleFrom`/`visibleTo` on the issue, and `publicFrom`/`publicTo`/`publicWindowSource`. Adopted: **`publicFrom` / `publicTo` / `publicWindowSource ∈ {DERIVED, MANUAL}`** — §3.6.3's naming, which is also the wire's (`API-CONTRACT.md`), so the entity, the invariants, the indexes and the DTO all use one set of names; `visibleFrom`/`visibleTo`/`visibleWindowSource` are retired spellings and must not appear anywhere. The source column is load-bearing: `MANUAL` protects the genuinely open-ended one-offs — the four citation-only publications current since 01.01.2016 — from being capped by the chain, while **every series with a cadence is `DERIVED`, imported rows included** (R8), so the chain owns their windows. And **the cap is `successor.cutoffStampedAt − 1 ms`, not the bare cut-off**, because `CriteriaHelper.overlaps()` is closed at both ends (`CriteriaHelper.java:199-208`) and an exactly-equal boundary would make two issues publicly current for one instant. Whether any external consumer parses legacy's `:59`-minute convention is **open — DM-Q20 (§9.4)**.
>
> This pair is **not** the content interval, and conflating them is the single failure the ledger flags twice: the visibility window is offset from the content interval by one period, so mapping `intervalFrom`/`intervalTo` onto the legacy `publishDateFrom`/`publishDateTo` makes the newest issue **permanently not-current** on the download site.

#### Per-language descs (D)

| # | Rule | Forced by |
|---|---|---|
| **D-1** | `UNIQUE (lang, entity_id)` on every desc table | `PublicationDesc.java:31-34` |
| **D-2** | `desc.lang ∈ series.languages` | C5 |
| **D-3** | `PublicationIssueDesc.fileName` distinct across the issue's languages | ADD-027 |
| **D-4** | `filePath` starts with `issue.repoPath` — that containment is the whole constraint. **No `revision` column exists on either new entity**, and **no issue published by this system carries a revision segment**; an **imported** issue carries its legacy `repoPath`, `filePath` and `link` verbatim, revision segment included | C3 — legacy embeds `revision` in the public URL (`PublicationRestService.java:558`), which is exactly why an amend changes the URL today. **R6 scopes the rule to newly published issues:** legacy stores bytes at `<repoPath>/<revision>/<fileName>` and writes that string into stored message HTML as the citation href, so forbidding the segment outright either fails the import of 1,077 publications or 404s every imported file and the ~1,077 public URLs citing them |
| **D-5** | `fileName`, `filePath`, `link` immutable once `publishedAt` is set — including for an `UPLOADED` replacement, which is written to the same path regardless of the uploaded file's own name | C3's stable URL beats FIL-024's `Content-Disposition` fallback |
| **D-6** | `fileSource IS NOT NULL` ⟺ `filePath IS NOT NULL`; **`replacedBy` and `replacedAt` are non-null together, and non-null iff this upload replaced existing bytes** — never as a consequence of `fileSource = 'UPLOADED'` alone | C6, **as relaxed**. `replacedBy`/`replacedAt` mean *"this upload replaced bytes that were already there"*, not *"fileSource is UPLOADED"*. A first hand-upload to an OPEN issue replaces nothing, so it sets `fileSource = 'UPLOADED'` with both columns NULL; the audit trail already draws exactly this line as `FILE_UPLOADED` versus `FILE_REPLACED_MANUALLY` (§7.11). The stricter reading made the first upload unrepresentable, which is why `ck_pubissuedesc_upl` is rewritten in §9.2 to constrain only the pairing |
| **D-7** | An issue desc carrying `messageReferenceFormat` must also carry a non-blank `name` | `PublicationDescVo.descDefined()` counts only `title/titleFormat/link/fileName` (`PublicationDescVo.java:44-46`), so a format-only language row **round-trips to nothing** and its citation text silently disappears (REF-023) |
| **D-8** | `link` is **relative** (`/rest/repo/file/…`) for repository-hosted files; absolute only for `EXTERNAL_LINK` | storing an absolute repo URL makes `externalize` a no-op and bakes the wrong host into every environment |
| **D-9** | `descDefined()` and the corresponding `*DescVo` must agree field-for-field | the REF-023 asymmetry above must not be reproduced |

#### Frozen members (M) and overrides (O)

| # | Rule | Forced by |
|---|---|---|
| **M-1** | `messageUid` is **the** key; `frozenShortId` is display-only and never a key, in members, overrides and diffs alike | MODEL-VERIFICATION §5.16 — `dma-fa` holds 357 messages under only 331 distinct shortIds, and a shortId-keyed comparison silently reported 0-diff on issues differing by **eight** members |
| **M-2** | `sortIndex` dense, 0-based, unique within the issue, assigned over the **post-override union**, not over the raw query result | OPTIONAL §3.5/§6 |
| **M-3** | `frozenStatus ∈ Status.isPublic()` at freeze; `frozenType`, `frozenStatus` NOT NULL | MODEL-VERIFICATION §5.5, §5.10 |
| **M-4** | `frozenPublishDateFrom` and `frozenPublishDateTo` are **nullable**, and that is load-bearing: NULL `publishDateTo` means "still open", which is precisely what a NULL-unsafe alive clause drops | REVIEW R1 — the unsafe form collapses P&T uge 28/2026 from 165 members to 47 and returns **zero** for the 2026/2027 firing-areas issues |
| **M-5** | Rows are written **only** at freeze and re-freeze; never live, never incrementally | REVIEW B6 |
| **M-6** | `source = 'OVERRIDE_INCLUDE'` ⟺ `override` FK non-null | one hop to the admin-only author/reason (C9) |
| **M-7** | An `EXCLUDE` override produces **no** member row; exclusions live only as override rows | avoids a second table |
| **O-1** | `reason` mandatory, non-blank, 3..512 chars | C1 / a settled decision — the prototype *displays* quoted reasons but `toggleRow()` writes a hardcoded string and no input exists; the field is mandatory in the model precisely so that gap cannot ship |
| **O-2** | `author` mandatory, recorded **regardless of who may write** | C1's cheap reversibility — widening to an editor tier later is a permission change with no migration |
| **O-3** | Writes require `issue.status = 'OPEN'`, or the transaction is an amend | 409 otherwise |
| **O-4** | `INCLUDE` of a uid already in the query result is rejected `409` | two competing why-lines for one row |
| **O-5** | `EXCLUDE` of a uid not in the query result is **accepted**, flagged `STALE`, and never auto-deleted; an override stale at publish is frozen as not applied | the query result is time-varying and an admin retro-creating a week may legitimately pre-empt |
| **O-6** | An override uid that resolves to no `Message` is a **hard error**, never silent | |
| **O-7** | The importer creates **no** overrides | a settled decision — the single detectable legacy manual edit in ~10,200 members (NM-375-24) is an attribution by elimination, not a record |

#### Cross-entity (X)

| # | Rule | Forced by |
|---|---|---|
| **X-1** | `PublicationIssue.publicId`, `PublicationSeries.legacyTemplateId` and legacy `Publication.publicationId` share **one value space** — the UUID keyspace. Uniqueness is per-table; there is **no** cross-table constraint — random UUIDv4 makes collision a non-risk and legacy already relies on exactly that. The importer asserts non-collision on the way in. **`PublicationSeries.seriesId` is *not* in this value space** (R3): it is an authored `VARCHAR(64)` business key in its own keyspace, so no collision reasoning applies to it and it is never compared against a `publicationId` | ISS-022, REF-010 |
| **X-2** | An imported legacy publication's `publicationId` becomes the `publicId` of the one issue it maps to, **verbatim**. Never mint a fresh UUID for an imported row; never key an issue on title or tag name — three publications carry a literal `${year}` in title, fileName *and* tag name | MODEL-VERIFICATION §5.13 |
| **X-3** | `?publication=<id>` resolves in order: new `PublicationIssue.publicId`, then legacy `Publication.publicationId`, then **fail loudly with `400`**. Never widen. **A successful resolution designates the member set even when that set is empty** — see RI-15 (R7): "designated" is about the id resolving, never about the member count | C8 prerequisite 3 — live defect: a bogus id returns the entire published corpus (244 messages), as do all 27 tagless ACTIVE publications |
| **X-4** | For every `publicId` that has ever appeared in stored message HTML, the by-ids lookup must keep returning at least `publicationId`, `type`, `category` and per-language `link` + `messagePublicationFormat` — ignoring status and `category.publish` | the **resolvability floor**: `PublicationUtils.java:44-90, 153-182` reads exactly those. This is why I-16's delete guard is the *only* enforceable rule — there is no join table, so "is this id cited?" is not answerable |
| **X-5** | Series delete allowed only when the series has **no** issue rows (409 naming the count) | C7 |
| **X-6** | **Two new roots, one rule.** Neither `publicationArchiveRootPath` **nor `publicationPreviewRootPath`** may resolve under `repoRootPath`; **both** are asserted at startup — `!archiveRoot.startsWith(repoRoot)` and `!previewRoot.startsWith(repoRoot)` — and the app refuses to boot otherwise | C3 — `RepositoryService` is class-level `@PermitAll` (`RepositoryService.java:67-69`) and `streamFile` does `repoRoot.resolve(path)` with no authentication and no containment check (`:208-245`), while `validateTempRepoPath` (`:481-489`) guards **uploads only**. Archiving inside the root makes superseded official notices permanently reachable at guessable public URLs that cannot be recalled; previewing inside it does the same for **unpublished** ones, and `getTempRepoRoot()` is literally `getRepoRoot().resolve("temp")` (`:125-126`), so the temp folder is not an escape. `publicId`, `fileName` and a small integer generation are all editor-tier knowledge, so a temp-repo preview is a world-readable unpublished official notice set at a guessable URL. Same defensive shape as `validateTempRepoPath` for both. Written paths are containment-checked against their **own** root, not against the temp repo |
| **X-7** | `contentMode` is the gate: `GENERATED_FROM_QUERY` requires `timeRelation`, `criteria`, `reportId`; the other three modes forbid all of them and forbid an interval on their issues | MODEL-VERIFICATION §5.1. Written here against the token `NONE`; if the enumeration ratification lands on `ONE_OFF` instead, every rule reads identically with the token swapped |

### 8.3 Indexes

Declared in the entity via `@Table(indexes = …)`, matching `Publication.java:62-67`'s naming style (`<table>_<column>_k`).

| Table | Index | Serves |
|---|---|---|
| `PublicationSeries` | `UNIQUE (seriesId)` | external handle lookup — the authored `VARCHAR(64)` key |
| | `UNIQUE (legacyTemplateId)` | import provenance: IMP-008's template relink and IMP-005's upsert-by-`publicationId` |
| | `(status)`, `(contentMode)` | the series list's default `status=ACTIVE` filter and the content-mode facet |
| | `(category_id)`, `(domain_id)` | FK joins; the public sort key is `category.priority` |
| | `(sourceSeries_id)` *(2026-09-13)* | the self FK a compilation names, and the reverse question "what compiles this series" |
| `PublicationSeriesDesc` | `UNIQUE (lang, entity_id)` | desc fetch |
| `PublicationIssue` | `UNIQUE (publicId)` | mirrors legacy `publication_id_k`; the `?publication=` hot path |
| | `UNIQUE (legacyPublicationId)` | the transition-union dedup |
| | `(series_id)` | issue list per series — one production series holds 1,077 issues |
| | `(status)` | the issue list's status facet |
| | `(cutoffStampedAt)` | chaining ("the previous issue's cut-off") and gap detection |
| | `(publicFrom, publicTo)` | the public adapter's `overlaps(…, now, now)` |
| `PublicationIssueDesc` | `UNIQUE (lang, entity_id)` | |
| `IssueMember` | `UNIQUE (issue_id, messageUid)` | membership identity |
| | `(issue_id, sortIndex)` | **the ordered replay** — the covering index for "render this issue in its frozen order" |
| | `(messageUid)` | C9's editor-tier "which issue(s) does this message belong to" |
| | `(sourceIssuePublicId)` *(2026-09-13)* | the compiled rows a given source issue printed |
| `IssueOverride` | `UNIQUE (issue_id, messageUid)`, `(messageUid)` | |
| `IssueAuditEntry` | `(issue_id, created)` | the Historik panel, ordered |

**No index was added for the member resolution (2026-09-07), and that is a measurement.** The obvious candidate was a composite `Message (messageSeries_id, publishDateFrom)` for `narrow()`'s candidate query. `EXPLAIN` over the local copy of the corpus says it is not needed: InnoDB's automatic foreign-key index on `messageSeries_id` already narrows to the series' ~19k rows and the date filter keeps about 8 % of them, so **the scan was never the cost — the hydration was**. The resolve's ≈ 0.1 s per 1,000 candidates was a 45-field `Message` with seven associations being materialised for every candidate, and the two changes that removed it are the liveness prefilter (RI-4, which cuts the row count on an in-force series by ~97 %) and a seven-scalar projection in place of the entity read. An index would have trimmed a scan that was not the bottleneck and charged a write cost on every message save for it. **No index migration was added, which is why there is no `V14`** — the gap in the numbering is this decision, not an omission. (Later migrations exist for other reasons; the committed set is whatever is in `niord-core/src/main/resources/db/migration`, and a number quoted here would only go stale.) Revisit only if `EXPLAIN` on the deployed estate says otherwise — and note that the mirror index `(messageSeries_id, publishDateTo)` is a worse candidate still, because the liveness clause is a disjunction with `IS NULL` and MySQL will not serve that from one b-tree range.

**Sizing, so the `(issue_id, sortIndex)` index is justified rather than assumed.** Derived from the counts in MODEL-VERIFICATION §1 — ~500 weekly issues at ~28 members, ~500 P&T issues at ~150, ~29 annuals at ~40 — the historical import lands **order 10⁵ `IssueMember` rows** against ~1,077 issues, growing by roughly 9,000 rows a year. This is an estimate from published counts, not a measurement; confirm it against the snapshot before sizing anything on it. It is small enough that no partitioning or archival strategy is warranted, and large enough that the ordered index is not optional.

### 8.4 Resolver invariants — the rules that are *not* fields

These cannot be expressed as a column, and every one of them changes the member list if got wrong. They belong in the resolver, in its tests, and in this document — not in the criteria vocabulary, where a per-series variant would be unverifiable.

| # | Invariant | Evidence |
|---|---|---|
| **RI-1** | The status conjunct is `status IN (derived Status.isPublic() set)`, built as `Arrays.stream(Status.values()).filter(Status::isPublic)` — never a hardcoded triple, and **never empty**. `searchPagedMessageIds` adds the status predicate only when the set is non-empty (`MessageService.java:1053-1056`) and applies no default of its own; the REST-layer defaulting lives in the layer the resolver bypasses. An empty set would leak `DRAFT`/`VERIFIED`/`DELETED` rows into a published issue, and unpublished messages genuinely carry `publishDateFrom` | `Status.java:57`; `MessageSearchRestService.java:161` derives, `AbstractApiService.java:201` hardcodes — derive, so a future status change propagates |
| **RI-2** | Interval openness is **`(previousCutoff, thisCutoff]`** — strict lower, closed upper. The resolver emits `cb.greaterThan(publishDateFrom, intervalFrom)` **itself** and must not call `CriteriaHelper.between()` for this bound: `between` is `>=` **and** `<=` (`CriteriaHelper.java:180-188`), closed at both ends, and `DateType.PUBLISH_FROM_DATE` routes to it (`MessageService.java:1035-1037`). Transcribed verbatim it puts a message published exactly on a cut-off into **both** adjacent issues and destroys tiling | MODEL-VERIFICATION §5.20 / REVIEW R3 — **data cannot arbitrate this**: zero of 10,413 `dma-nm` messages have `publishDateFrom` exactly equal to any of the 992 cut-off stamps. See **DM-Q18** (§9.4) |
| **RI-3** | `publishDateFrom IS NULL` is **excluded**. The resolver emits an explicit `cb.isNotNull(m.publishDateFrom)` so the intent is legible in the generated SQL, and **does not** reuse `CriteriaHelper.overlaps()`, which ORs `isNull(publishDateFrom)` (`CriteriaHelper.java:205`). The exclusion must be **visible, never silent**: every in-scope public-status message with a NULL `publishDateFrom` is reported as a `NO_PUBLISH_DATE` omission | MODEL-VERIFICATION §6 — NM-780-18 leaks into 8 issues under `overlaps()`, including P&T issues released a year before the message existed. See **DM-Q18** (§9.4) |
| **RI-4** | The alive clause is `(publishDateTo IS NULL OR publishDateTo >= :cutoff) AND (withdrawnAt IS NULL OR withdrawnAt >= :cutoff)` — NULL-safe on both instants, applied **only** when `aliveAtCutoff = true`. `withdrawnAt` is the earliest `MessageHistory` row with status CANCELLED or EXPIRED, read for every withdrawn candidate and never frozen (history is immutable). The decisions record's literal `NOT (publishDateTo < cutoff)` is NULL-unsafe and returns nearly the inverse set; `publishDateTo` alone keeps a notice cancelled before the cut-off whose editor-set validity end lies after it (the "date-alive, status-dead" class). <br>**Amended 2026-09-07 — the rule is unchanged; where it is *enforced* is not.** The **date half** is now applied in **two** places: still in `MembershipPredicate`, and additionally as a NULL-safe SQL conjunct in `MemberResolutionService.narrow()`, emitted **only when `aliveAtCutoff`**, written as the exact negation of the predicate's own branch (`MembershipPredicate.dateAliveAt`, from which both halves are derived rather than transcribed twice). Because it is that negation, a row it removes is a row the predicate was going to reject, and **the candidate set stays a superset of the answer by construction, not by testing**. It is deliberately **absent from the null-`publishDateFrom` (omissions) query**, where the predicate answers `NO_PUBLISH_DATE` before it ever reaches liveness — a conjunct there would delete the omissions that query exists to report, and the `NULL_PUBLISH_FROM_DROPPED` warning with them. The **status half stays in Java alone**: the withdrawal instant is a history aggregate, and it can only change a verdict for rows the date half already kept. The reason for the split is a measurement, not a preference — an in-force series has no lower bound (RI-7), so its candidate set was its whole published history, ~6,000 rows on the P&T list of which ~5,900 were long expired and were hydrated only to be rejected one at a time | C2 signed; REVIEW R1; **R-xxxii** (2026-09-07, NM-814-26 in uge 36/2026). <br>**Guard for the SQL half (2026-09-07):** `MemberResolutionDifferentialTest` runs **all 21 fixtures a second time with `aliveAtCutoff = true`** (they had all declared `false`, so the clause had zero coverage), and `theSqlAliveClauseRemovesExactlyWhatThePredicateWouldHaveRejected` compares the loose and tight candidate sets of the P&T list on the real corpus, asserting row by row that the difference is describable by `dateAliveAt` alone, that no dropped row is one the rule keeps, and that a candidate with a NULL `publishDateTo` survives. Measured there: **6,066 candidates without the clause, 174 with it** |
| **RI-5** | The resolver **never** routes through `MessageSearchRestService`. That layer calls `params.adjustDateInterval(currentDomain.timeZone())`, which does `resetTime(from)` / `endOfDay(to)` — destroying release-moment precision to whole days — and it silently rewrites `seriesIds` and forces `statuses` to `PUBLISHED` only. `currentDomain` is the thread-local from the `NiordDomain` header or a ticket, *not* the `domain=` query param, so anything running in-app or server-side loses it silently | REVIEW R4. Precision matters at this scale: P&T uge 12/2024's member set changes **4 seconds** after its stamped cut-off; NM-466-26 is decided by **62 seconds** |
| **RI-6** | The resolver resolves every operand itself and **never hands an empty collection to `CriteriaHelper.in()`**, which emits `cb.disjunction()` — always false — on an empty collection (`CriteriaHelper.java:163-170`). The area/category branches are worse: `findByAreaId` returns `null` for an unresolvable id and the stream **silently filters it out** (`MessageService.java:1084-1089`), producing `builder.or()` over an empty array, also always false. Either path **silently empties the issue** | this is why V-C4 rejects a dangling operand at save *and* the resolver raises at query time |
| **RI-7** | For `IN_FORCE_AT_CUTOFF` the resolver **must not read the previous issue at all** — no lower bound, no chaining, no tiling, no gap detection, no "missing week" recovery. Asserted, not merely omitted | MODEL-VERIFICATION §5.18 — the 2026/2027 firing-areas issues share 31 of 32 members; under a chained interval the 2027 issue would contain **one** |
| **RI-8** | A `domain` node is a **macro**: `resolvedSeriesIds = ⋃{ d.messageSeries.seriesId } ∪ messageSeriesNode.values`, expanded **before** the query and frozen into the snapshot envelope. `MessageSearchParams.domain` **is not a predicate at all** — `searchPagedMessageIds` never reads it. If `PublicationSeries.domain` is also applied as a predicate the scope is silently double-counted; that column is for sort order, repo scoping and time zone only | REVIEW R4, verified by grep across `MessageService.java:1002-1341` |
| **RI-9** | Order comes from the **issue's series domain**, never the caller's thread-local domain: explicit series `messageSortBy`/`messageSortOrder` > `seriesDomain.messageSortOrder` (split on space, `MessageSearchParams.java:187-209`) > `AREA`/`ASC`. The resolver reproduces `searchPagedMessageIds`' ORDER BY tuples exactly, including the `AREA` tuple, the `treeSortOrder CASE WHEN NULL THEN 999999` that sorts area-less messages last, and **the `id` tie-breaker on every branch** — without it the order is non-deterministic and a reprint differs | OPTIONAL §3.5 / O-B3. `sortBy` decides more than sequence: `report.pdf` derives `areaHeadings` from `params.sortByArea()` |
| **RI-10** | `members = (Q \ activeExcludes) ∪ activeIncludes`, keyed on `uid`. Multiple `publication=` ids **union**, deduplicated by `uid` — today multiple publications become multiple tags which `MessageService` ORs (`:1154-1162`); moving the predicate into its own conjunct would silently turn a union into an intersection. Overrides participate in ordering like any other member; they are **not** appended at the end | C1, M-2 |
| **RI-11** | The area *criterion* joins `msgRoot.join("areas")` — the message's full area list (`MessageService.java:1082-1083`). The area *sort* joins `msgRoot.join("area")` — the primary area only (`:1239`, kept in sync by `@PrePersist`, `Message.java:137-141`). **Do not conflate them**: a message whose secondary area matches the criterion is a member but sorts under its primary area | |
| **RI-12** | No paging and no accidental cap. A directly-constructed `MessageSearchParams` has `maxSize = Integer.MAX_VALUE` (`PagedSearchParamsVo.java:33`); the 100 default exists only in `instantiate()`. A resolver that builds its own query is safe by construction. Separately, `report.pdf` and `mail/send` cap at 1000 and truncate **silently** — for an official set that must become a loud 4xx naming the issue and the count. **R1 takes `report.pdf` off the publish path entirely** (the render is in-process), so the silent truncation is removed rather than guarded against, and the remaining limit is P7's declared product threshold | consent defect 3; OPTIONAL O-B4. Largest live member set is 228, so 1000 is headroom |
| **RI-13** | Resolution is pure JPA with no Lucene: `requiresLuceneSearch()` is true only for a non-blank free-text `query` (`MessageSearchParams.java:213-215`) and the vocabulary has **no free-text node**. This is what makes a resolution reproducible from the frozen inputs. Adding a free-text node later reopens it and must not be done casually | REVIEW, "the single biggest feasibility risk turned out to be a non-issue" |
| **RI-14** | Live vs frozen is keyed off **issue status**, with no request parameter: `OPEN` → live re-resolve; `PUBLISHED`/`RETIRED` → frozen snapshot; anonymous → frozen, published only. Release-time render **must read the snapshot being frozen in the same transaction** — which is possible only because **R1** makes the render an in-process call inside that transaction, taking the ordered member list; the retired HTTP self-call could not see uncommitted rows at all, and would have rendered the domain's entire published corpus instead. Amend re-renders from the same frozen snapshot and **never re-resolves** | OPTIONAL §3.4; C3. A `publicationSnapshot=frozen\|live` hatch is deliberately not shipped — it would create deep links whose meaning changes over time, the exact non-reproducibility the redesign exists to remove. It is purely additive if ever needed |
| **RI-15** | **`designatesMemberSet()` is "at least one `publication=` id resolved to an issue the caller may see" — independent of member count.** A designated set with zero members produces an **explicit empty disjunction**, never `in(emptyCollection)` and never a fall-through to the widening branch. Zero members means zero messages returned; it never means "no publication was named" | **R7** — otherwise a membership-free publication reinstates live defect 1 (the entire published corpus, 244 messages) inside the model built to remove it. The ~48 `NO_MEMBERSHIP` publications are exactly this case in production, so the "`NO_MEMBERSHIP` returns 0" fixture in §8.5 is mandatory, not optional |
| **RI-16** | **The compiled member set** (2026-09-13). For a compilation issue with period `(intervalFrom, at]`, the members are the uid-**union** of the frozen `IssueMember` rows of `series.sourceSeries`' **PUBLISHED** issues whose effective cut-off (`cutoffStampedAt`, else `intervalTo`) lies in that period, then RI-10's curation over it. A repeated uid is **owned by the earliest** source issue. RETIRED source issues are never read: retiring one is the statement that what went out for its period should not stand, and its replacement, once published, is what the compilation carries. OPEN ones contribute nothing, and their existence inside the period is what the release rail warns about. No predicate is evaluated and no criteria document is read — two queries, one for the sources and one for the union, and the owner rule is applied in Java over the ordered sources | measured: 14 of 10,224 weekly memberships appear in more than one weekly, and the member table's unique key forbids the alternative. A source issue belongs to the period its **stamped cut-off** falls in; its own week and year numbering is not consulted |
| **RI-17** | **Compiled order** (2026-09-13). Rows with a source issue come first, ordered by (source cut-off, the source's own frozen `sortIndex`, uid); manual includes follow, in the series' ordinary sort. `sortIndex` stays dense over the whole list. This is what "the weekly documents one after another" means, and it is **one definition** used by all three orderings — the release, the preview and the live member list — so the document, the screen and the archive cannot disagree | the frozen order of a published issue is already the recorded answer to "in what order did this go out"; re-sorting the union would replace it with today's area tree |

### 8.5 Mandatory fixtures the invariants are verified by

An invariant with no fixture is a comment. Each row below binds to a rule above; the first two are synthetic because **no production message can supply them**.

| Fixture | Asserts |
|---|---|
| **Synthetic boundary pair** — one message stamped exactly on a shared cut-off | RI-2: it lands in the **earlier** issue and in exactly one. Reachable in reality: `ScheduledMessageService` auto-publishes VERIFIED messages at their editor-set `publishDateFrom` (`:96-100`) and `updateStatus` overwrites that date only when null or in the future (`MessageService.java:662-666`) |
| **Synthetic empty-operand** — an all-unresolvable `values` list | RI-6: raises; never yields an empty issue |
| **P&T uge 28/2026** | RI-4: the NULL-unsafe form collapses 165 → 47. Sharpest regression test in the estate. <br>**Extended 2026-09-07:** `MembershipPredicateTest` now also pins **`dateAliveAt` over all 165 members** — every one of them must satisfy it, and on the 42 that carry a `publishDateTo` the extracted half must agree with the full branch exactly (they differ only about null, which is the whole hazard). That makes this fixture the regression test for the **SQL** clause as well as the Java one, in half a second with no MySQL, because the conjunct `narrow()` emits is that same function. A companion pure case pins the helper in both of its nullable arguments: null `publishDateTo` alive, null cut-off alive, `publishDateTo == cutoff` alive (the bound is closed), one millisecond earlier dead |
| **firing-areas 2026 vs 2027** | RI-7 and I-8: 31 of 32 members shared; no tiling, no gap check, no overlap refusal |
| **NM-780-18** | RI-3: excluded from all 8 issues, surfaced as `NO_PUBLISH_DATE`, not silent |
| **NM-300-24** | M-3/M-5: frozen `type` prevails over today's; `TYPE_DRIFT` fires. 12 messages are `PERMANENT_NOTICE` today and sit inside 26 already-published P&T issues; all twelve were P or T at release |
| **NM-1116-22** | back-dated `publishDateFrom` — filed by date, not by publication moment |
| **Skydeområder 2017 ed2** | the R5 superset at the supersede moment → the `CANCELLED_BUT_DATE_ALIVE` release-rail check |
| **EfS uge 45 - 2018** | explicit **negative** fixture: with `aliveAtCutoff = false` the resolver is *expected* to differ from the conjunct form |
| **Skydeområder 2018** | `SNAPSHOT_ONLY` / `UNION_SNAPSHOT` — never a fabricated criteria match |
| **FA/EK-R-19 2017** | M-1: a shortId-keyed comparison passes while the member sets differ by 8 |
| **P&T uge 12/2024 + NM-466-26** | RI-5: sets change 4 s and 62 s after the stamped cut-off; nothing may round to days |
| **Differential** (JPA predicate ≡ pure-function twin) | the whole fixture corpus. Defining the predicate twice is also what makes the fixture suite runnable **with no database on day one** — the repo has 29 plain JUnit 4 test files, no `quarkus-junit5`, no `@QuarkusTest` and no test datasource |
| **`NO_MEMBERSHIP` designation** — `?publication=<a NO_MEMBERSHIP issue>` | **RI-15 / R7**: returns **0** messages, not 244. The designation predicate is true (an id resolved to a visible issue) while the member set is empty, and the builder emits an explicit empty disjunction rather than `in(emptyCollection)` |
| **Retro-created gap issue** — recover uge 41/2024 into a series whose later issues have all published | **R5 / I-18 / I-19**: the recovered issue is capped at its own successor's stamp − 1 ms in the same transaction, this week's live issue keeps `publicTo IS NULL`, exactly one issue of the series is uncapped, and the public list with no `from`/`to` still returns **this week's** issue |
| **Import check** — an imported cadenced series plus its first native publish | **R8 / I-18**: imported issues carry `publicWindowSource = DERIVED`, the newest imported issue is capped by the first native publish, and the public site never shows two current issues |
| **Publish window off-by-one-period** | I-14: after publishing issue *N+1*, the public list with no `from`/`to` returns *N+1*, not *N* |
| **Id immutability** | I-10/X-4: amend, retire and reactivate leave `publicId` byte-identical and a stored `<a publication="…">` still resolves |
| **Compiled union** — a weekly source series with two PUBLISHED issues sharing one message, an OPEN one and a RETIRED one, and a compilation pointing at it | **RI-16 / RI-17**: the union is the rows of the two published issues; the shared message appears once, owned by the earlier issue; the retired issue contributes nothing; the open one contributes nothing and is reported instead; the order is source cut-off, then the source issue's own frozen `sortIndex`, with manual includes last; and an exclude naming a message no source carries is reported as a stale override. Its frozen half asserts **I-20** — the published issue's header names the source series and every source issue of the period |

**Oracle hygiene** for anything capturing ground truth from legacy tags: resolve publication→tag **by the stored join**, never by scanning the tag table (six tags are shared, nine are orphans); key on `uid`; honour the contaminated-oracle blocklist — `nm-w01-2025`, `nm-pt-w01-2025`, `nm-w52-2024`, `nm-pt-w52-2024`, `nm-pt-w02-2025`, the three 2019 changeover issues and the four union annuals. Clean positive controls: `nm-pt-w01-2026` (123/123) and `nm-w01-2026`. Any sweep over the archive is a **diagnostic, never a pass/fail gate** — two of the four provenances cannot be compared against the query at all, reached independently by three sweeps.

---

## 9. What the DDL implies, and the open physical-delivery decision

### 9.1 The schema objects, and the rules that constrain any hand-written DDL

**Eight new tables**, one altered, three untouched:

| | Object | Note |
|---|---|---|
| new | `PublicationSeries` | |
| new | `PublicationSeries_languages` | `@ElementCollection @OrderColumn(name="indexNo")` collection table |
| new | `PublicationSeriesDesc` | |
| new | `PublicationIssue` | |
| new | `PublicationIssueDesc` | |
| new | `IssueMember` | |
| new | `IssueOverride` | |
| new | `IssueAuditEntry` | |
| altered | `PublicationCategory` | `priority` entity default `0 → 100` only; existing rows untouched either way. Ratification is **DM-Q6** (§9.4), the same one §3.4's field row names — one identifier for one decision; the earlier `Q-C9` here and the bare "open question 5" spelling were both second names for it and are retired |
| untouched | `PublicationCategoryDesc` | `description` already exists — CAT-004 is a *visibility* gap, not a schema gap |
| untouched | `Publication`, `PublicationDesc` | **must not be dropped.** The public union reads them — a legacy row is served until the issue imported from it has been published — the citation resolvability floor (X-4) reads them, and the legacy JSON export contract points at them |

**Creation order** (FK dependencies): `PublicationSeries` → `PublicationSeries_languages`, `PublicationSeriesDesc` → `PublicationIssue` → `IssueOverride` → `IssueMember` (references both) → `PublicationIssueDesc`, `IssueAuditEntry`. Drop order is the reverse.

**Five rules any hand-written DDL must obey.** Each is a way to get it silently wrong:

1. **No `AUTO_INCREMENT`, anywhere, and no per-table sequence.** `BaseEntity` uses `@Id @GeneratedValue(strategy = GenerationType.AUTO)` (`niord-core/…/model/BaseEntity.java:27-30`), and `quarkus.hibernate-orm.unsupported-properties."hibernate.id.db_structure_naming_strategy"=legacy` (`niord-dk/niord-dk-web/src/main/resources/application.properties:86`) makes Hibernate 6 on MySQL resolve `AUTO` to the **single shared legacy id table**, not per-table auto-increment. Every `id` column is a plain `INTEGER NOT NULL` primary key. **Delivery step:** confirm the generator table's actual name and current value against the deployed schema before applying anything — the repo's only explicitly-named table is `SequenceEntity`'s `@Table(name="Sequence")` (`niord-core/…/sequence/SequenceEntity.java:31-34`), which is the *business* number sequence and **not** the id generator; every other table takes the Hibernate default.
2. **Table names are the entity names, CamelCase.** No naming strategy is configured in any of the three repos — verified: no `physical-naming` or `implicit-naming` property anywhere — so Hibernate 6 defaults apply, exactly as `Publication` / `PublicationDesc` are named today. The collection table is `PublicationSeries_languages` with columns `PublicationSeries_id`, `languages`, `indexNo`.
3. **Enums are `@Enumerated(EnumType.STRING)` with no explicit length**, matching `Publication.java:99-122` — Hibernate emits `VARCHAR(255)`. Do **not** hand-tighten them to `VARCHAR(16)` in DDL: the entity would then disagree with the schema, and the next `update` run would try to widen them. Explicit lengths are used only where the repo already does so on plain strings (`ReferenceDesc.java:35` → `length = 512`; `MessageDesc.java:37-48` → 1024/2048).
4. **JSON columns are `TEXT` + `@Convert`**, per `Publication.java:158-166` — `criteria`, `criteriaOverride`, `criteriaSnapshot`, `IssueAuditEntry.detail`, `membershipProvenanceNote`, `snapshotSeriesIds`, and `reportParams` **on both `PublicationSeries` (§3.1.7) and `PublicationIssue` (§3.6.6)**.
5. **Match the existing schema's character set and collation rather than assuming `utf8mb4`.** `application.properties` sets `quarkus.datasource.db-kind=mysql` at line 66 and configures **no** charset; the effective value comes from the server and the existing database. Inspect the live `PublicationDesc` table and mirror it — a mismatched collation on `PublicationSeriesDesc.name` silently changes case-insensitive title search behaviour on the series list.

### 9.2 The CHECK constraints implementing §8.2 — optional, and why

MySQL 8.0.16+ enforces `CHECK`; earlier versions parse and ignore it. **Confirm the deployed server version before relying on any of this.** There is **no `@Check` annotation anywhere in `niord`**, so these are a deviation from house style and are belt-and-braces only: §8.2 is enforced in the service layer, which is the authoritative mechanism and the only one that can produce the structured `400` the UI needs. Note also that Hibernate `database.generation=update` **will not retrofit CHECK constraints onto an existing table**, so if the DDL is hand-applied these must be applied in the same pass or not at all.

```sql
-- PublicationSeries
CONSTRAINT ck_pubseries_query   CHECK ((contentMode = 'GENERATED_FROM_QUERY')
                                      = (timeRelation IS NOT NULL AND criteria IS NOT NULL
                                         AND reportId IS NOT NULL)),                       -- S-1
CONSTRAINT ck_pubseries_alive   CHECK ((aliveAtCutoff IS NOT NULL) = (criteria IS NOT NULL)), -- S-2
CONSTRAINT ck_pubseries_alive2  CHECK (timeRelation <> 'IN_FORCE_AT_CUTOFF'
                                      OR aliveAtCutoff = TRUE),                            -- S-3 (R2, ratified)
CONSTRAINT ck_pubseries_base    CHECK ((firstIssueStartsAt IS NOT NULL)
                                      = (timeRelation = 'PUBLISHED_IN_INTERVAL')),          -- S-4
CONSTRAINT ck_pubseries_weekly  CHECK ((cadence = 'WEEKLY') = (nominalCutoffDay IS NOT NULL)),      -- S-5
CONSTRAINT ck_pubseries_time    CHECK ((cadence <> 'NONE') = (nominalCutoffTime IS NOT NULL)),       -- S-7
CONSTRAINT ck_pubseries_oneoff  CHECK (cadence <> 'NONE'
                                      OR (nextIssueCreation = 'MANUAL'
                                          AND numberingScheme = 'NONE')),                  -- S-8
CONSTRAINT ck_pubseries_print   CHECK ((reportId IS NOT NULL) = (pageSize IS NOT NULL)),   -- S-9
CONSTRAINT ck_pubseries_sort    CHECK ((messageSortBy IS NULL) = (messageSortOrder IS NULL)), -- S-10

-- PublicationIssue
CONSTRAINT ck_pubissue_retired  CHECK ((status = 'RETIRED')
                                      = (retiredAt IS NOT NULL AND retiredBy_id IS NOT NULL
                                         AND retiredReason IS NOT NULL)),                  -- I-6
CONSTRAINT ck_pubissue_pubat    CHECK ((status IN ('PUBLISHED','RETIRED'))
                                      = (publishedAt IS NOT NULL)),                        -- I-5
CONSTRAINT ck_pubissue_cutsrc   CHECK ((cutoffStampedAt IS NULL) = (cutoffSource IS NULL)), -- I-4
CONSTRAINT ck_pubissue_super    CHECK (supersedes_id IS NULL OR supersedes_id <> id),      -- I-13
CONSTRAINT ck_pubissue_window   CHECK (publicFrom IS NULL OR publicTo IS NULL
                                      OR publicTo > publicFrom),                            -- I-14
CONSTRAINT ck_pubissue_ivsrc    CHECK ((intervalFrom IS NULL) = (intervalFromSource IS NULL)
                                      AND (intervalTo IS NULL) = (intervalToSource IS NULL)), -- I-17

-- PublicationIssueDesc
CONSTRAINT ck_pubissuedesc_src  CHECK ((filePath IS NULL) = (fileSource IS NULL)),         -- D-6
-- D-6, as relaxed: replacedBy/replacedAt travel together and mean "this upload replaced
-- existing bytes". A first hand-upload sets fileSource='UPLOADED' with both NULL, so the
-- old `fileSource='UPLOADED' => replaced* NOT NULL` form is WRONG and must not be restored.
-- Only the pairing is checkable here; "did it replace bytes?" is not a column.
CONSTRAINT ck_pubissuedesc_upl  CHECK ((replacedBy_id IS NULL) = (replacedAt IS NULL)
                                      AND (replacedBy_id IS NULL OR fileSource = 'UPLOADED')), -- D-6

-- IssueMember / IssueOverride
CONSTRAINT ck_issuemember_ovr   CHECK ((source = 'OVERRIDE_INCLUDE') = (override_id IS NOT NULL)), -- M-6
CONSTRAINT ck_issuemember_sort  CHECK (sortIndex >= 0),                                    -- M-2
CONSTRAINT ck_issueoverride_rsn CHECK (CHAR_LENGTH(TRIM(reason)) >= 3)                     -- O-1
```

Rules that **cannot** be a CHECK and are therefore service-only: S-11…S-15 (cross-row, cross-table, or requiring the token vocabulary), I-7…I-9, I-11, **I-18 and I-19** (cross-row within a series — "at most one uncapped issue" and "NULL `publicTo` only without a successor" are aggregate facts, enforced by the publish transaction and asserted by the import check), I-15 (requires the domain time zone), every C-, O- and X- rule, and every RI-.

### 9.3 Physical delivery — **settled; kept for the reasoning**

> **Closed. Flyway ships.** Migrations live in `niord-core/src/main/resources/db/migration` and travel in its
> jar; `niord-dk`'s `application.properties` sets `quarkus.flyway.migrate-at-start=true` with
> `baseline-on-migrate=true` and `baseline-version=0`, so an existing database is adopted at version 0 rather
> than rebuilt, and `hibernate-orm.database.generation` stays `none` outside `%dev` — two owners of one schema
> is the one arrangement that must not happen. §1.3 states this; the survey below is what the decision was
> made against and is kept for its reasoning, not as a description of today.

**The facts as surveyed before that decision.** Schema came solely from Hibernate:

```
niord-dk/niord-dk-web/src/main/resources/application.properties
  66  quarkus.datasource.db-kind=mysql
  72  %dev.quarkus.hibernate-orm.database.generation=update
  73  quarkus.hibernate-orm.database.generation=${NIORD-HIBERNATE-DB-GENERATE:none}
  79  quarkus.hibernate-orm.unsupported-properties."hibernate.id.db_structure_naming_strategy"=legacy
```

So outside `%dev` the app **creates no tables at all**, and the property that would change that lives in the **niord-dk overlay** — a third repo. Two facts materially narrow the decision and are worth stating because neither is obvious:

- **Line 73 is already externally overridable.** `${NIORD-HIBERNATE-DB-GENERATE:none}` means flipping a deployment to `update` needs **an environment variable, not a code change** in any repo.
- **Hibernate can emit the exact DDL for review.** Quarkus 3.7.2 (`pom.xml:21`) supports `quarkus.hibernate-orm.scripts.generation=create` with `…scripts.generation.create-target=<file>` — so the DDL that would be applied can be generated, diffed and code-reviewed before anything touches a database, removing the "hand-written DDL silently disagrees with the entities" risk that rule 9.1(1)–(3) exist to guard. **Verify the exact property spelling against 3.7.2 before relying on it.**

**The three candidate routes:**

| | Route | Cost | Risk |
|---|---|---|---|
| **A** | Set `NIORD-HIBERNATE-DB-GENERATE=update` on the test deployment; on production apply a Hibernate-generated, reviewed DDL script by hand | none in the repos | `update` acts on the **whole** schema, not just the eight new tables — it will attempt to reconcile every existing entity, and it never drops or alters columns, so it can leave the two schemas silently divergent. It also does **not** add §9.2's CHECK constraints to existing tables |
| **B** | Generate the DDL from Hibernate, review it, apply it by hand to **both** test and production; `generation` stays `none` everywhere | one reviewed artifact per schema change | Every future change repeats the ceremony. The `sortIndex` reservation and any criteria-hatch column inherit this |
| **C** | Adopt a migration tool now (Flyway is the natural fit — Quarkus ships `quarkus-flyway`) | a dependency, a baseline migration for the existing schema, and a decision about who owns it | Baselining an existing nine-year production schema is the whole cost; after that every later change is free |

**Why this cannot be decided here.** It is an operations decision about who touches the production database and when, not a modelling one. It is also constrained by an out-of-scope boundary: deployment to test is performed manually from Azure DevOps, that arrangement is owned by Rasmus, and **pipeline or deployment changes are explicitly not an action item for this work** — so route A's env-var flip and route C's adoption both need his sign-off rather than a proposal from here.

**What the answer must cover, whichever route wins:**

1. Who applies the DDL to production, and under what change control.
2. Whether §9.2's CHECK constraints are applied at all (route A largely cannot; routes B and C can).
3. The **`PublicationCategory.priority` default change** — a one-line `ALTER` under B/C, and something `update` will *not* do under A, because Hibernate `update` does not alter existing columns.
4. **Every later schema change inherits this answer**, including two that are already known: the `sortIndex` + resolved-sort reservation (deliberately pulled into the first pass precisely *because* there is no migration tooling — this is the one carve-out a reviewer must not drop), and a future criteria-hatch column.
5. Rollback: what happens if the eight tables are created and the feature is deferred. All eight are new and unreferenced by legacy code, so dropping them is safe — but that must be written down, not assumed.

**Recorded consequence of the survey, and why the reservations below exist.** At the time there was no migration tooling, so adding a column later meant hand-rolled DDL against a live production schema. Route **C** was taken and Flyway now carries later columns (`V8`'s `kind`, `V13`'s `availability`, `V15`'s label and override columns all arrived this way), so the cost is no longer prohibitive — but the reservations were made under the old assumption and are harmless. That is why `IssueMember.sortIndex`, `snapshotSortBy`, `snapshotSortOrder`, `snapshotDomainId`, `snapshotIntervalFrom`, `PublicationIssue.legacyPublicationId`, `publicWindowSource`, `intervalFromSource`, `intervalToSource`, `PublicationIssue.reportParams` and `criteriaOverride` are all reserved in the **first** schema pass even though several of them belong to work that may never ship.

---

### 9.4 The open-question register — one identifier per open decision

*Every open decision in this document, numbered once, in one table, so that a reference can be resolved without guessing which list it belongs to.*

Until this section existed the document carried **three** overlapping namespaces: a bare "open question N" spelling that nothing anywhere defined, the per-section `Q` lists in §4.9, §6.8 and §7.14, and the `Q-C…` set used in §5 and §8.4. They collided — "open question 4" and "§4.9 Q3" were one decision under two names, while "open question 3" and "§4.9 Q3" were two different decisions a reader would resolve to the same place. **`DM-Q…` is now the only identifier for an open decision in this document.** The per-section lists survive as reading aids and each of their items carries its register id; the retired spellings are kept in the last column so an older fragment can still be resolved against this table.

The prefix is deliberately **not** `OQ-…`: that namespace belongs to `API-CONTRACT.md` §11 and is numbered independently. Where one decision is open in both documents the contract's identifier is named in the last column, and the two must be answered together.

| # | Open decision | Referenced at | Retired spelling · sibling identifier |
|---|---|---|---|
| **DM-Q1** | ~~Physical schema delivery~~ — **Closed. Flyway ships**, route **C**: migrations in `niord-core`, `migrate-at-start` with `baseline-on-migrate` at version 0, Hibernate `generation` left at `none`. See §1.3 and §9.3. | §1.3, §3.1.2, §9.3 | "open question 2" |
| **DM-Q2** | ~~Series audit shape~~ — **Closed: generalised.** `IssueAuditEntry` carries a nullable `issue` **and** a nullable `series`, and the series-level actions (`SERIES_ACTIVATED`, `SERIES_RETIRED`, `OWNER_TRANSFERRED`) are in the shipped enum. | §3.1.2, §4.8, §7.11, §7.14 | "open question 4"; "§4.9 Q3"; "§7.14 Q4" · `API-CONTRACT.md` §11 OQ-4 |
| **DM-Q3** | **Ratify the delete-guard wording.** `publicId` is minted at create, so C7's literal *"no publicId"* becomes `status = OPEN AND cutoffStampedAt IS NULL AND publishedAt IS NULL` (I-16). The same set of issues, different words — but it amends a signed decision and needs a sign-off rather than a silent substitution. | §3.6.1, §4.7, §4.9 | "open question 7"; "§4.9 Q1" · `API-CONTRACT.md` §11 OQ-3 |
| **DM-Q4** | **The dormancy threshold `N`.** `dormant` is derived, but *"no issue for N cadence periods"* needs an N. It decides only whether a gap warning appears — a product judgement, not a data fact. Proposed: 3 cadence periods. | §3.1.2, §4.1, §4.9 | "open question 9"; "§4.9 Q2" |
| **DM-Q5** | ~~Ratify the `contentMode` value set~~ — **Closed.** `NONE` in, `ONE_OFF` out of `ContentMode`, as proposed. The accompanying reading ("a one-off is `cadence = NONE`") is **superseded**: a one-off is `PublicationSeries.kind = ONE_OFF`, a separate stored column (§3.1.4). | §3.1.3, §3.1.4, §7.2, §7.14 | "open question 3"; "§7.14 Q1" |
| **DM-Q6** | **`PublicationCategory.priority` entity default `0 → 100`.** The entity defaults to `0` today while the admin UI seeds `100`, so a category created through the API sorts to the front of every list including the public site. Existing rows are untouched either way, and the entity still defaults to `0`. **Open**, but no longer blocked: Flyway can carry the `ALTER` (DM-Q1). | §3.4, §9.1 | "open question 5"; `Q-C9` · the contract's §2.3 row **C4** records that the decision is this document's |
| **DM-Q7** | **Column bounds taken from precedent, and `edition`'s widening.** The 512-character bounds on `retiredReason` and `IssueOverride.reason` follow `ReferenceDesc.java:35` rather than any product requirement; `edition` is widened from legacy `Integer` to `VARCHAR(64)` so `"2025"`, `"v2"` and the three rows carrying a literal `${year}` round-trip, which obliges the **legacy export adapter to parse back to `Integer` or refuse**. Confirm the bounds and the export behaviour together — they are the same question about fidelity to the legacy shape. | §3.6.4, §3.6.6, §3.9 | "open question 6" |
| **DM-Q8** | ~~`nominalCutoffTimeZone` for a domain-less series~~ — **Closed by V13: there are no domain-less series.** `domain_id` is `NOT NULL` and means the owner, so the column always has a domain to seed from. See the R-xxvi amendment. | §3.1.4, §3.1.6 | "open question 8" |
| **DM-Q9** | ~~The deterministic public sort tiebreak~~ — **Closed, and not as specified.** The shipped comparator is `category.priority` ascending, then `publishDateFrom` descending, then **`publicationId`** ascending (`PublicationPublicAdapter.ordered()`). `series.sortOrder` is not consulted (§3.1.6). Adding any tiebreak at all is still a deliberate change from the previous system's non-deterministic order for the EfS/P&T twin pair. | §3.1.6 | "open question 10" · `API-CONTRACT.md` §11 OQ-17 |
| **DM-Q10** | **Pre-amend snapshot retention.** The model archives the *file* and records the member *delta* on the `AMENDED` entry; the outgoing member list survives only as `{added, removed}`. Is the delta enough, or should the full outgoing snapshot be retained in a history table? One extra table — cheap now, expensive later. | §4.5, §4.9, §6.3, §6.8 | "§4.9 Q4"; "§6.8 Q1" |
| **DM-Q11** | **How an aborted automatic release reaches a human.** Under `AUTO_RELEASE` a `BLOCK` precondition aborts the run and writes an `AUTO_RELEASE_ABORTED` entry, but nothing surfaces that to the admin who expected an issue on Wednesday. Mailing list, dashboard warning, or "the gap row is the notification"? | §4.4, §4.9, §7.11 | "§4.9 Q5" · `API-CONTRACT.md` §11 OQ-6 |
| **DM-Q12** | **The `MEMBER_LIMIT_EXCEEDED` threshold, now a free product choice.** The 1000 was inherited from `report.pdf`'s `maxSize(1000)`, and R1 takes that pipeline off the publish path entirely. Largest live member set is 228. Pick a threshold on product grounds and state it; do not justify it by the report pipeline any more. | §4.4 (P7), §4.9 | "§4.9 Q6" · `API-CONTRACT.md` §11 OQ-15 |
| **DM-Q13** | **May a published issue ever be deliberately re-resolved?** §6.2 and §4.5 say no. An audited "re-resolve and write a new snapshot version" action — to repair a bad import, say — would need an owner, a role, an audit action and a rule for the already-published PDF. Recommendation: not in Phase 1. | §4.5, §6.2, §6.8 | "§6.8 Q2" |
| **DM-Q14** | **Deleted members.** `Status.DELETED` is not in `isPublic()`, so a member later moved to DELETED vanishes from a published issue under a live re-resolve while the frozen snapshot still holds its row. Serve it from the snapshot, hide it (legacy parity), or fail loudly? | §6.7, §6.8 | "§6.8 Q3" |
| **DM-Q15** | ~~`cadence = DAILY` is unnameable~~ — **Closed: the tokens were added.** `${day}` and `${day-2-digits}` are in the shipped token vocabulary (`IssueNaming.TOKENS`) and `DAILY` stays in the enum and the picker. | §7.3, §7.4, §7.14 | "§7.14 Q2" |
| **DM-Q16** | **`MONTHLY` / `MONTH_YEAR` / `EDITION_SEQUENCE` are speculative** — zero production instances between them. Keep them (a later addition to a persisted enum is free) or cut them to keep the pickers honest? | §7.3, §7.4, §7.14 | "§7.14 Q3" |
| **DM-Q17** | **The four canonical legacy `messageTagFilter` strings have never been read verbatim. Blocks the importer, and nothing else.** The consent document abbreviates them (`msg.type==T`) while the code's own example spells `msg.type == Type.TEMPORARY_NOTICE`. The importer matches stored bytes, so they must be dumped from the production snapshot and committed as a test resource **before** §5.5.6's table is implemented. | §5.5.6 | `Q-C1` |
| **DM-Q18** | **Ratify the two resolver invariants data cannot arbitrate.** **RI-2** — interval openness is `(previousCutoff, thisCutoff]`, strict lower and closed upper, with `CriteriaHelper.between()` explicitly not reused — and **RI-3** — `publishDateFrom IS NULL` is excluded rather than OR-ed in as `overlaps()` does. Zero of 10,413 messages sit on any of the 992 cut-off stamps, so no measurement decides RI-2; RI-3 changes NM-780-18's membership across 8 issues. Ratify both, together with the mandatory synthetic boundary fixture (§8.5). | §8.4, §10.2 | `Q-C3` |
| **DM-Q19** | **Do all production areas and categories carry MRNs?** V-C9 rejects a purely-numeric operand, and `AreaService.findByAreaId` accepts either an MRN or a surrogate id. Low urgency: no production publication uses an area, category or chart criterion — these node kinds are forward-looking vocabulary. | §5.4 | `Q-C4` |
| **DM-Q20** | **Does any external consumer parse legacy's `:59`-minute `publicTo` convention?** The cap is `successor.cutoffStampedAt − 1 ms`, which reproduces the convention but not the exact minute boundary legacy happens to emit. | §8.2 (I-14) | `Q-C7` · related to `API-CONTRACT.md` §11 OQ-16, the neighbouring `publicFrom`-precision question; answer the two together |

**How to read this register now.** It is a record of the decisions this text left open, and several have since been answered by code — those rows say so and name the shipped answer. It is **not** the live open-items list: anything still genuinely undecided belongs in [`../backlog.md`](../backlog.md), so there is one list to work from rather than two. A row here with no "Closed" marker has not been swept against the code; check the entities before treating it as live.

It also does not absorb `API-CONTRACT.md` §11: that list carries contract-side questions (Keycloak provisioning, the `ExceptionMapper` scoping, OpenAPI, picker domain scoping) that have no counterpart here, and its `OQ-…` numbering stays its own.

---

## 10. Traceability — every decision and every measured finding, to the fields that satisfy it

### 10.1 The nine decisions

| Decision | Fields / rules that satisfy it |
|---|---|
| **C1** — admin-only curation via a named `PUBLICATION_CURATE` permission; the override endpoint takes a message **selection** | `IssueOverride` entity: `author` **NOT NULL recorded regardless of who may write** (O-2 — this is what makes widening later a permission change with no migration), `reason` `@NotBlank` 3..512 (O-1), `kind`, `UNIQUE(issue_id, messageUid)`; `IssueMember.source = OVERRIDE_INCLUDE` + `override` FK (M-6); RI-10's set algebra over a uid **list**; `Permissions.PUBLICATION_CURATE` as a compile-time constant, checked at exactly one call site |
| **C2** — `aliveAtCutoff` per series, NULL-safe, status conjunct carried, `publishDateTo` an accepted proxy | `PublicationSeries.aliveAtCutoff` as a **nullable `Boolean`** with **S-2** and **S-3** — **R2: NULL exactly when the series is not query-backed, `TRUE` under `IN_FORCE_AT_CUTOFF`, free under `PUBLISHED_IN_INTERVAL`**, one rule at all five sites plus the frozen echo and the two wire VOs (`PublicationSeriesVo`, `IssueMemberListVo`); RI-4 (the NULL-safe clause); RI-1 (the status conjunct, as a resolver invariant not a criterion — V-C5); `IssueMember.frozenPublishDateTo` **nullable** (M-4); `resolved.aliveAtCutoff` echoed into the frozen envelope (§5.6); §5.5.1 vs §5.5.2 — two byte-identical documents separated only by this flag; the `CANCELLED_BUT_DATE_ALIVE` release-rail check |
| **C3** — amend replaces in place at a stable URL; outgoing per-language file archived outside the `@PermitAll` root; retention indefinite | **No `revision` column, and no revision path segment on any issue this system publishes** — imported issues keep their legacy path and link verbatim (D-4, R6); `PublicationIssueDesc.fileName`/`filePath`/`link` immutable after publish (D-5); `PublicationIssueDesc.fileSize`, `fileHash` (ties the archive entry to the exact file it replaced); `IssueAuditEntry.archivePath` on **`AMENDED`, `PUBLISHED` and `FILE_REPLACED_MANUALLY` alike** (§3.10, §7.11 — publish reaches the archive on the cleared-sticky-flag path, so `AMENDED` cannot be the only action that records one); the **two** new settings `publicationArchiveRootPath` **and `publicationPreviewRootPath`** + the **startup containment assertion X-6** over both; RI-14 (amend never re-resolves) |
| **C4** — no expression hatch; criteria a discriminated union; import fails loudly | `criteria` as a nullable `TEXT` + typed converter (§5.1, §5.8); `schemaVersion` **inside** the document (§5.3) so a hatch is purely additive with no migration; the `kind` discriminator (§5.4); `NOT_IN` defined-and-rejected (V-C2); **no `advancedExpression` column anywhere**; the five-combination import table (§5.5.6) and its loud failure |
| **C5** — per-language name, file-name and reference patterns on the series **and** the issue, over N languages | `PublicationSeries.languages` as an explicit ordered `@ElementCollection` (S-11) — declared, not inferred from which desc rows exist; the whole of `PublicationSeriesDesc` (`nameSuggestionPattern`, `fileNamePattern`, `messageReferenceFormat`, `linkPattern`); the whole of `PublicationIssueDesc` (`name`, `nameOverridden`, `fileName`, `link`); S-12, **S-13 (scoped to `messagePublication <> NONE`, so the EfS template is not blocked)**, S-15, D-2, D-3, **D-7 + D-9** (the format-only-row round-trip trap) |
| **C6** — per-language `fileSource (GENERATED\|UPLOADED)`, sticky across amend, with author and timestamp | `PublicationIssueDesc.fileSource`, `.fileSourceSticky`, `.replacedBy`, `.replacedAt`, `.fileGeneratedAt` (D-6); the `FILE_REPLACED_MANUALLY` audit action; `fileHash` — needed because C6 makes every diff **skip** manually replaced files, so "reproducible from the member list" does not hold for them. `fileSource` is **not** exposed on the public VO and does **not** change the emitted legacy `PublicationVo.type`, which is derived from `contentMode` and has no column of its own (X2, §7.12) |
| **C7** — retire + reactivate; delete only for never-published; series lifecycle status; extends to one-offs | `PublicationSeries.status` + `statusChangedAt/By/Reason`; `PublicationIssue.status ∈ {OPEN, PUBLISHED, RETIRED}` + `retiredAt/By/Reason` (I-6); **I-16** (delete guard) and **X-5** (series delete guard); retire changes **no** window and **no** file location — a retired issue keeps resolving through `?publication=` so citations do not dangle (X-4). One-offs are a series with `cadence = NONE` holding one issue, so all of it reaches them with no separate entity |
| **C8** — no minted tag; `?publication=` re-pointed in both call sites; widening from the frozen snapshot; unresolvable fails loudly | **Absence of any `messageTag` column on either new entity**; `criteriaSnapshot` (§5.6) + the denormalised `snapshotDomainId` — the widening branches on "resolved to a member set", **not** on tag presence; **X-3** (fail loudly, `400`); **no message tag at all — not durable, not temporary** — the render is an in-process call taking the resolved, ordered member list (**R1**, §4.4 step 10), so there is nothing for membership to be read back from; **RI-15** (designation is by resolved id, not by member count); RI-10's union semantics |
| **C9** — three read tiers; override author and reason admin-only | The `PublicationSeriesVo`/`SystemPublicationSeriesVo` and `PublicationIssueVo`/`SystemPublicationIssueVo` splits — structural classes, not field-level redaction; `criteria` on the **admin** VO only while the rendered `criteriaSummary` chips are on the editor VO; `IssueMember.override` FK as the single hop to admin-only data; the `issue_member_uid_k` index serving "which issue(s) does this message belong to"; **no anonymous series endpoint at all** |

### 10.2 The measured findings

| Finding | Fields / rules it forces |
|---|---|
| **`IN_FORCE_AT_CUTOFF` is the larger shape** — 531 issues with no interval and no lower bound vs 500 weekly; the 2026/2027 firing-areas issues share 31 of 32 members; issues **overlap rather than tile** | `PublicationSeries.timeRelation` **required** for query-backed series (S-1); `firstIssueStartsAt` **forbidden** under `IN_FORCE_AT_CUTOFF` (S-4); `PublicationIssue.intervalFrom` **NULL** there (I-1); **I-8** (overlap permitted, asserted); **RI-7** (the resolver must not read the previous issue at all) |
| **~48 publications have no membership semantics of any kind** | `contentMode` (**X-7**); `criteria`, `timeRelation`, `aliveAtCutoff`, `intervalFrom`, `intervalTo` all **nullable**; `membershipProvenance = NO_MEMBERSHIP`; §5.1's two-nulls distinction; the `SNAPSHOT_ONLY` why-line kind |
| **`publication.updated` is NOT the release moment** — it recovers only 13 of 35 non-weekly cut-offs; 27 annual cut-offs came from printed PDF cover dates | `cutoffStampedAt` and `publishedAt` as **separate** columns, both distinct from `VersionedEntity.updated`; `cutoffSource` with `RECOVERED_FROM_UPDATED` as its **own** value (collapsing it into `STAMPED` would launder a last-write stamp into a release stamp for ~700 issues); `cutoffReconstructed` as the indexed badge predicate (I-4) |
| **Member lists must key on `uid`, never `shortId`** — `dma-fa` holds 357 messages under 331 distinct shortIds; a shortId-keyed comparison silently reported 0-diff on issues differing by 8 | **M-1**; `IssueMember.messageUid VARCHAR(36) NOT NULL` + `UNIQUE(issue_id, messageUid)`; `IssueOverride.messageUid`; `frozenShortId` demoted to display-only; RI-10 |
| **`type`, `publishDateFrom`, `publishDateTo` are mutable and unversioned** — a live re-resolve silently rewrites published history; NM-300-24 sits in 10 consecutive tags and drops out of the 11th while still PUBLISHED | The **entire frozen scalar set** on `IssueMember`: `frozenStatus`, `frozenType`, `frozenMainType`, `frozenPublishDateFrom`, `frozenPublishDateTo` (M-3, M-4); **M-5** (written only at freeze); **RI-14** (amend never re-resolves); §5.4's recorded caveat that a stored `messageType` criterion reads *current* type, with **no criteria-level mitigation** — a time-aware type criterion would need a `MessageHistory` reconstruction, which C2 explicitly did not fund |
| **Four annual issues are the answer to no query at any instant**; 10 annexes have no recoverable member list | `membershipProvenance ∈ {EXACT, EXPLAINED_DIFF, UNION_SNAPSHOT, NO_MEMBERSHIP}` + `membershipProvenanceNote`; the `SNAPSHOT_ONLY` reason kind; the acceptance downgrade from a pass/fail gate to a diagnostic (§8.5) |
| **`edition` is free text with no supersede semantics** — Skydeområder 2025 and EfS A 2025 both carry `edition=2025` and both covers print "Version 2025" | `edition VARCHAR(64)` (widened from legacy `Integer` so `"2025"`, `"v2"` and the three rows carrying a literal `${year}` all round-trip) **plus an explicit `supersedes` self-FK** (I-13) |
| **The public visibility window is offset from the content interval by one period** | `publicFrom` / `publicTo` / `publicWindowSource` as **their own pair**, never mapped from `intervalFrom`/`intervalTo` (**I-14**); the **symmetric** capping side effect with the **−1 ms** rule (§4.4 steps 12–13, R5); **I-18** (at most one issue per series with `publicTo IS NULL`) and **I-19**; the `(publicFrom, publicTo)` index |
| **The status conjunct is `status IN Status.isPublic()`, not `== PUBLISHED`** — all 2,324 blank-era members are EXPIRED or CANCELLED and zero are PUBLISHED | **RI-1**; **V-C5** (no `status` node may be stored); `IssueMember.frozenStatus` constrained to `isPublic()` at freeze (M-3); C8's widening reproduced from the snapshot |
| **NULL hazard: the corrected rule excludes NULL `publishDateFrom`; legacy `overlaps()` ORs `isNull`** — they disagree on NM-780-18 across 8 issues, including issues released a year before the message existed | **RI-3** (exclude; emit `isNotNull` explicitly; never reuse `overlaps()`), the `NO_PUBLISH_DATE` omission code, `frozenPublishDateFrom` nullable. Recorded as a **resolver invariant, not a per-series field** — a field would create unverifiable variants. Ratification is **DM-Q18** (§9.4) |
| **A printed report's order comes from the search sort order / the domain's `messageSortOrder`, and every input is mutable** | `IssueMember.sortIndex` (M-2) + `snapshotSortBy` / `snapshotSortOrder` / `snapshotDomainId` + `resolved.sortBy`/`sortOrder` in the frozen envelope; series-level `messageSortBy` / `messageSortOrder` promoted out of `printSettings` (S-10); **RI-9**; the `(issue_id, sortIndex)` index. **Reserved in the first schema pass** because there is no migration tooling |
| **Interval openness cannot be settled by data** — zero of 10,413 messages sit on any of the 992 cut-off stamps; `between()` is closed at both ends | **RI-2** as a documented resolver invariant with the `between()` trap named, **plus the mandatory synthetic boundary fixture** (§8.5). Ratification is **DM-Q18** (§9.4) |

### 10.3 Review defects and legacy hazards the model closes

| Source | What it forces |
|---|---|
| **REVIEW R1** (NULL-unsafe alive clause) | RI-4; P&T uge 28/2026 as the regression fixture |
| **REVIEW R2** (missing status conjunct) | RI-1; V-C5 |
| **REVIEW R3** (four incompatible interval readings; `between()` closed at both ends) | RI-2 + the synthetic fixture |
| **REVIEW R4** (`adjustDateInterval` rounds to whole days; `MessageSearchParams.domain` is not a predicate) | RI-5, RI-8 |
| **REVIEW R5** (`publishDateTo` is not a cancellation stamp) | the `CANCELLED_BUT_DATE_ALIVE` release-rail check — an exclusions panel is structurally blind to this class, confirmed three times independently |
| **REVIEW B2** (no migration tooling; legacy id strategy) | §9.1 rule 1; §9.3 |
| **REVIEW B3** (the report pipeline has no uid-list parameter) | **R1** — the render is extracted out of the HTTP layer into an in-process service taking the ordered member list, so no tag is needed to carry membership and no uid-list query parameter has to be invented; the temp-tag answer is retired because a tag created in the publish transaction is invisible to the self-call that would read it. RI-12's loud member-limit error, now a product threshold rather than `report.pdf`'s `maxSize(1000)` |
| **REVIEW B5** ("criteria (structured JSON)" never specified) | the whole of §5 |
| **REVIEW B6** (the plan contradicts itself on whether a snapshot exists) | `IssueMember` as its own table; M-5; RI-14 |
| **REVIEW F5 / ISS-022 / REF-010** (citations live in stored message HTML; no join table; no endpoint clears one) | **I-10** (the UUID-shape constraint, forced by the unquoted jsoup selector), **X-1, X-2, X-4**, I-16 |
| **REVIEW F6** (ISO week paired with the calendar year, JVM default time zone) | **I-15**; the `${calendar-year}` token kept addressable so a historical pattern round-trips verbatim |
| **ADD-005** (untyped `printSettings` keys are re-parsed as a message search and rewrite the PDF) | typed `reportId`/`pageSize`/`pageOrientation`/`mapThumbnails`; `messageSortBy`/`messageSortOrder` promoted; the passthrough **deleted**, with a loud import failure on any key outside the typed set |
| **ADD-027** (a non-language-specific report generates N PDFs to one path, last language wins) | S-15, D-3 |
| **MEM-009** (a domain-less publication records from every message series in the installation) | **V-C6** |
| **CriteriaHelper.in() → `cb.disjunction()` on an empty collection; `findByAreaId` silently drops unresolvable ids** | **V-C4** at save + **RI-6** at query time |
| **Live defect 1** (a bogus `?publication=` returns the entire published corpus) | **X-3** for the unresolvable id, and **RI-15** (R7) for the resolvable-but-memberless one — the second half is the easier of the two to reintroduce, because a zero-member resolution looks exactly like "no publication was named" unless the predicate is defined on resolution rather than on count |
| **Live defect 2** (three publications share one tag) | importer rule: resolve publication→tag **by the stored join**, never by scanning; the contaminated-oracle blocklist (§8.5) |
| **Live defect 3** (`limit` vs `maxSize` caps every picker at 100 rows) | the new endpoints declare `maxSize` only; the frontend fix lands in the same change |

---

## Amendments — 2026-08-27 (Stage A of the work queue)

*Recorded against the shipped code after the reconciliation; wins over the sections above where they disagree. The model behind them is `CUTOFF-MODEL.md` (ruling D20).*

- **`PublicationSeries.cutoffDefault`** `ENUM('RELEASE_MOMENT','PERIOD_START','PERIOD_END') NOT NULL DEFAULT 'RELEASE_MOMENT'` — migration **V9** (idempotent; backfills yearly rows by time relation). Validation **S-21**: required; a period-boundary default only on a YEARLY cadence.
- **Cut-off vs publication moment.** `cutoffStampedAt` is the end of the content period; `publishedAt`/`publishedBy` the release action. The importer derives them separately: weekly — the release stamp when within `[nominal close − 3 h, nominal close + 24 h]`, else the nominal close (`cutoffSource = NOMINAL_CLOSE`); yearly in-force — `publicFrom`; yearly accumulated — the window's end, an open legacy end closing at 31 Dec 23:59:59 in the series' zone (`cutoffSource = PUBLIC_WINDOW`, interval bounds `NOMINAL`). `publishedAt` is the witnessed release stamp, or the row's `updated` when it falls inside the public window, and failing both the issue's own `publicFrom` — a released imported issue is never left unstamped, because the public union's legacy half is excluded on that stamp (§3.1.8). OPEN issues alone carry none.
- **Chaining is per destination series**, not per legacy template, so orphan double-week issues and the dont-use clones sit between their real neighbours. Rows released within five minutes of each other (a withdrawal and its replacement) are **siblings**: both open where the row before the pair closed; the chain continues from the PUBLISHED sibling's close.
- **§7.11 audit vocabulary** gains `VISIBILITY_CAPPED`; `IMPORTED` is now actually written, once per imported issue.
- **Gap detection** (`GapDetection.uncovered`) is coverage-based; the release-slot arithmetic is used only when an issue's interval start is unknown. An OPEN newest issue suppresses forward pseudo-rows.
- **Weekly naming** (§ `week`/`weekTo`): `weekTo` only when the window spans more than one cadence period; an ordinary Wednesday-to-Wednesday window names the cut-off week alone.
- The "165 → 47" NULL-unsafe figure quoted in `MembershipPredicate` is **42**, measured against the recovered cut-off.

## Amendments — 2026-08-28 (Stages B/C and the verification pass)

- **Migration V10 — the rest of the resolved operands on a published issue.** `PublicationIssue` gains nullable snapshot columns for the main types, areas, categories and charts the criteria resolved to at release (the message series were already frozen). NULL = the criteria did not select on that facet; no backfill. Idempotent like V6/V8/V9.
- **Membership uniqueness is V7**, not a later migration: `UK_issue_member_issue_uid` / `UK_issue_override_issue_uid` (`UNIQUE (issue_id, messageUid)`) have existed since V7; the duplicate V11 written for queue item B-10 was removed before it shipped.
- **`publishedAt` is the release action's own instant**, never the chosen cut-off — the importer and the publish agree on this now (the publish stamped the cut-off into it in the first Stage A cut).
- **`intervalToSource = RECOVERED`** on a yearly issue whose year-end the import had to invent; `NOMINAL` where the archive recorded one.
- **`PublicationIssueDesc.replacedBy / replacedAt`** (Stage B): who replaced a published language's file, and when; written only on a replacement, never on a first upload.

## Amendments — 2026-08-28 (B-5 / B-11 / B-12)

- **No schema change.** Drift (`drift[]`, `current`), the curation why-line, `publishedIssueCount` and the pre-flight rows are computed on read; the draft (S12) and the copy (S7) persist nothing.
- **`IssueListService` is the one synthesizer** of gap rows: the issue list and the dashboard strip (I29) both call it, so a MISSING period is one fact.
- **The count predicate** behind `publishedIssueCount` is `status <> OPEN`, identical to `hasPublishedIssue` (S-18); a boolean and a count that could diverge would show a locked control beside a zero.
- **Test-suite floor** (`TestSuiteGuardTest.FLOOR`) raised 30 → 590 against 617 discovered.

## Amendments — 2026-08-28 (§7.11 audit vocabulary as shipped)

*The vocabulary is one closed set and an action outside it throws; §7.11's original list was already short of it.
No schema change: the column is a string and the set is enforced in code. The vocabulary has grown since — the
shipped `AuditAction` enum is the authority, and §7.11 says why this text no longer quotes a count.*

- **Added, all of them the write paths §7.11 predated:** `CREATED_NEW_EDITION` (B-3) · `LINK_SET` /
  `LINK_CLEARED` (a link is the published artefact of an external publication exactly as a file is of a
  hosted one) · `WINDOW_ADJUSTED` · `DELETED`.
- **`OVERRIDE_ADDED` split into `OVERRIDE_INCLUDED` and `OVERRIDE_EXCLUDED`.** The two decisions read
  differently in a history panel and an EXCLUDE never appears in the member list at all, so one action
  covering both would leave a withdrawal with nothing to name. `OVERRIDE_REMOVED` is unchanged and keeps its
  reason (`V-iv`). The plural detail shape of §7.11 stands — one entry per call, whatever the selection size.
- **DM-Q2 is answered: the audit does carry series lifecycle actions** — `SERIES_ACTIVATED` and
  `SERIES_RETIRED`, and §7.11's "only if the entity is generalised" is superseded. The
  three `statusChanged*` columns are unaffected and stay.
- **`AUTO_RELEASE_ABORTED` is NOT in the vocabulary.** D17 puts the scheduler and its abort path after
  go-live; the validator refuses `AUTO_RELEASE` outright (S-22), so nothing can reach the abort. It returns
  with the scheduler, together with DM-Q11.

## Amendments — 2026-08-29 (E-6 backend half)

- **Migration V12 — issue read-path indexes**: `PublicationIssue (series_id, status)` and `(status, publicFrom)`, guarded like V6; declared on the entity via `@Table(indexes = …)`. `PublicationSeries` gets none (23 rows). Migrations V1–V5 keep their bare `ADD COLUMN`s (Flyway checksums applied files); `MigrationIdiomTest` requires the guard idiom from V6 on.
- **`AuditAction` is an enum** in code; the wire and the column stay strings (`IssueAuditEntry.action VARCHAR`).
- **`intervalToSource`** is the same enum as `intervalFromSource` in code, pinned to `varchar(255)` so the stored bytes and a `validate` boot are unchanged.
- **`@NotNull`** on the 35 `nullable = false` columns, matching the older entities.

## Amendments — 2026-08-29 (R-xxvi: the owner domain and availability)

*The spec is `DOMAIN-OWNER-AVAILABILITY.md`; the wire half is the R-xxvi amendment in `API-CONTRACT.md`.
One column used to answer two questions — "which desk administers this publication and supplies the timezone
its cut-offs are read in" and, by being null, "it may be cited from everywhere". The second question now has
its own answer, and the first becomes single-valued. The rows this supersedes are marked in place: §2.2's
relation map, §3.1.6 `domain`, §3.6's `snapshotDomainId`, §8.1's FK list.*

- **`PublicationSeries.domain` is the OWNER and is `NOT NULL`** (§3.1.6, amended). Required for every kind —
  `SCHEDULED`, `UNSCHEDULED` and `ONE_OFF` alike — the three values of `PublicationSeries.kind` (§3.1.4),
  so §7.2's "gives the one-off somewhere to keep its category, domain and reference format" now reads as a
  requirement rather than a convenience. Null is not a value; a row that somehow has none is **not adopted**
  by an ordinary save (S-20a refuses it), and an owner is assigned by the importer or by the transfer endpoint.
- **New column `availability`** `ENUM('OWNER_ONLY','SELECTED_DOMAINS','ALL_DOMAINS') NOT NULL DEFAULT 'OWNER_ONLY'`
  (§3.1.6). The column default is the conservative one — a publication that has not said who may cite it is not
  silently published into every domain's citation dialog — while the *application* default is decided from
  `contentMode`: generated → `OWNER_ONLY`, everything else → `ALL_DOMAINS`.
- **New join table `PublicationSeries_AvailableDomain (series_id, domain_id)`**, `PRIMARY KEY (series_id, domain_id)`
  (a domain is in the list or it is not; a duplicate row would make "shared with three" and "shared with two"
  indistinguishable from a count). FKs: `series_id → PublicationSeries(id)` **RESTRICT**, `domain_id → Domain(id)`
  **ON DELETE CASCADE**. Purged natively by `DELETE /series/{seriesId}` and by the import undo — the undo does it with
  an explicit native `DELETE … WHERE series_id IN (:ids)`, because a JPQL bulk delete does not cascade the way
  `remove()` would and the leftover rows would fail the series delete on the constraint, in the one operation a
  cutover window cannot do without.
- **Migration `V13__series_owner_and_availability.sql`**, joining V9 (`cutoffDefault`), V10, V12. Re-runnable by
  the V6 idiom throughout. It (1) adds `availability` through the `add_column_if_absent` procedure, (2) classifies
  once — `availability = 'ALL_DOMAINS'` where `contentMode <> 'GENERATED_FROM_QUERY'` **and the row still holds the
  column default**, like V8's `kind`, (3) creates the join table with its FKs declared **inline** so a re-run has no
  unguarded `ADD CONSTRAINT` to trip over, (4) backfills `domain_id` to `niord-annex` where null — the same answer
  the importer gives — guarded by an `EXISTS` so it is a no-op where that domain is absent, and (5) applies
  `MODIFY domain_id INTEGER NOT NULL` **only when no NULL survives**. The conditional constraint is the part to
  read: three shapes must boot off this file — an empty database (go-live imports from nothing; the constraint
  applies immediately, and this is the shape it is for), a rehearsal restore with rows (the backfill clears them),
  and a database with ownerless rows and no `niord-annex` (the constraint is **skipped** rather than taking the boot
  down). Skipping is safe because the rule is enforced above the column anyway: S-20a on every write,
  `SERIES_WITHOUT_OWNER` in the import check, and the importer's assignment.
- **`snapshotDomainId` is unchanged and is not the owner** (§3.6, noted in place): it records the domain the row
  carried at freeze — for an imported issue, the domain the *old publication row* carried, which is usually null.
  No backfill; a 2018 issue keeps describing 2018.
- **§7.11 gains `OWNER_TRANSFERRED`** — a series-level action, written on the series rather than on an issue,
  `detail {from, to}` (both `domainId`s; `from` may be null) plus a required reason. The enum is the vocabulary;
  every running total this text used to quote was wrong within weeks, which is why §7.11 no longer carries one.
- **Validation.** New **S-20a** (`domainId` required, every kind) and **S-20b** (`availability` required;
  `SELECTED_DOMAINS` must name at least one domain), both **hard rules** refused on every save in any status
  because the columns behind them are `NOT NULL`. **S-20 itself is unchanged in scope**: it stays the *timezone*
  half and stays cadence-only — a series with a cadence must have an owner whose zone is readable — and it keeps
  R-xix's narrowing (only where the domain names itself). §8.2's table stops at S-18 and does not list S-19…S-23,
  which is a pre-existing gap this amendment does not close; the ids are bound in `SeriesValidator` and exported to
  the backend by `gen-rule-ids.js`, whose regex does not match the `a`/`b` suffixes.

## Amendments — the new model serves the public (V17)

*Migration `V17__drop_public_authority.sql`, joining V9, V10, V12, V13, V15 and V16. Idempotent by the V16
idiom: MySQL 8 has no `DROP COLUMN IF EXISTS`, so the existence check goes in a procedure reading
`information_schema`.*

- **`PublicationSeries.publicAuthority` is dropped.** It was the only thing a deployment could turn the two
  halves of the public list on and off with, and the behaviour its `NEW` value selected is now the only one:
  a published issue in a publishing category is served, a legacy row is served until the issue imported from
  it has been published, and there is no per-series switch, no estate-wide switch and no direction to run one
  in. An abort is a full database restore, not a setting.
- **The `SERIES_AUTHORITY_CHANGED` audit rows are deleted**, and the action is out of the vocabulary. The rows
  describe an event that can no longer happen, and the column they are read back into is a Java enum whose
  constant is gone — one row left behind fails the read of any audit trail that includes it.
- **`ShadowDiffRun` is dropped.** The table held one row per comparison of the new engine against a legacy
  release, which was evidence for a decision that is no longer taken. Its unique key and its series index are
  part of the table and go with it.
- **The earlier migrations keep the text the schema has outgrown.** V1 still creates `publicAuthority` in its
  `CREATE TABLE`, V12 still names it among the clauses to index, and V5 still builds `ShadowDiffRun`. All three
  stand exactly as written: a migration that has run is the record of what the schema did on the day it ran,
  and editing one moves a checksum other databases have already stored. V17 is where a reader who followed any
  of those pointers learns that the column, the action and the table are gone.
- **The go/no-go of the go-live window is `GET /publication-series/import-check`**, whose violations must be
  zero. There is no readiness evidence to accumulate and nothing to sign off per series. The import refuses to
  run a second time — `ALREADY_IMPORTED`, keyed on the legacy ids — so a rehearsal always starts from a fresh
  restore.

## Amendments — 2026-09-13 (compilation series, and section 8 caught up)

*Written inline in the passages they amend — §3.1.4, §3.1.5, §3.6.5, §3.8, §6.3–§6.7, §7.5, §7.10, §7.13
and §8 — and listed here because the index above is where a reader looks for what shipped after August.*

- **Migration `V18__compilation_series.sql`.** Three native enums widened by the V4 `MODIFY` idiom
  (`PublicationSeries.timeRelation` gains `COMPILED_FROM_SOURCE`, `IssueMember.source` gains `COMPILED`,
  `PublicationIssue.membershipProvenance` gains `COMPILED`) and four columns added by the V13
  add-if-absent idiom: `PublicationSeries.sourceSeries_id` (self FK, indexed),
  `IssueMember.sourceIssuePublicId` (indexed), `PublicationIssue.snapshotSourceSeriesId` and
  `.snapshotSourceIssueIds`. No backfill — no compiled row exists anywhere, in any installation.
- **The third membership regime.** A compilation is `GENERATED_FROM_QUERY` with
  `timeRelation = COMPILED_FROM_SOURCE` and a `sourceSeries`, carrying neither `criteria` nor
  `aliveAtCutoff`. It **tiles**, so nothing about the interval, the chain, gap detection or the retro-create
  flow changes; its members are the union of the source series' published issues' frozen rows (**RI-16**),
  ordered by source cut-off and the source's own recorded order (**RI-17**), each row naming the issue that
  printed it, and the header naming the source series and every source issue (**I-20**). The enum answers
  `tiles()` and `compiled()` and nothing else.
- **Section 8 now declares the rules the validator already reported.** S-19 to S-23 existed in code and not
  in the table; they are written down here with S-24 and S-25, so the exported id list binds all of them.
  The two owner rules stay out, for the mechanical reason stated under the table — their ids carry a letter
  suffix the export pattern cannot match, so declaring them would export an id nothing could bind.
- **Not in this amendment, by decision.** The nine retroactive annual years are made by hand after go-live,
  one session each from the gap the strip already shows; and a published compilation whose sources have
  moved since its release shows no banner yet. Both are recorded in the frontend repository's backlog.
